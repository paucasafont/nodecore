package org.veriblock.miners.pop.tasks

import io.grpc.StatusRuntimeException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.TransactionConfidence
import org.veriblock.core.utilities.createLogger
import org.veriblock.miners.pop.common.serializeHeader
import org.veriblock.miners.pop.core.OperationState
import org.veriblock.miners.pop.core.VpmContext
import org.veriblock.miners.pop.core.VpmMerklePath
import org.veriblock.miners.pop.core.VpmOperation
import org.veriblock.miners.pop.core.VpmSpBlock
import org.veriblock.miners.pop.core.VpmSpTransaction
import org.veriblock.miners.pop.core.debug
import org.veriblock.miners.pop.core.info
import org.veriblock.miners.pop.model.ApplicationExceptions
import org.veriblock.miners.pop.model.ExpTransaction
import org.veriblock.miners.pop.model.PopMiningTransaction
import org.veriblock.miners.pop.model.merkle.BitcoinMerklePath
import org.veriblock.miners.pop.model.merkle.BitcoinMerkleTree
import org.veriblock.miners.pop.model.merkle.MerkleProof
import org.veriblock.miners.pop.services.BitcoinService
import org.veriblock.miners.pop.services.NodeCoreGateway
import java.util.ArrayList
import kotlin.math.roundToInt

private val logger = createLogger {}

class VpmTaskService(
    private val nodeCoreGateway: NodeCoreGateway,
    private val bitcoinService: BitcoinService
) : VTaskService<VpmOperation>() {
    override suspend fun retrieveMiningInstruction(operation: VpmOperation) = operation.runTask(
        taskName = "Retrieve Mining Instruction from NodeCore",
        targetState = OperationState.INSTRUCTION,
        timeout = 10.sec
    ) {
        // Get the PoPMiningInstruction, consisting of the 80 bytes of data that VeriBlock will pay the PoP miner
        // to publish to Bitcoin (includes 64-byte VB header and 16-byte miner ID) as well as the
        // PoP miner's address
        try {
            val popReply = nodeCoreGateway.getPop(operation.endorsedBlockHeight)
            if (popReply.success) {
                operation.setMiningInstruction(popReply.result!!)
            } else {
                error(popReply.resultMessage!!)
            }
        } catch (e: StatusRuntimeException) {
            error("Failed to get PoP publication data from NodeCore: ${e.status}")
        }
    }

    override suspend fun createEndorsementTransaction(operation: VpmOperation) = operation.runTask(
        taskName = "Create Bitcoin Endorsement Transaction",
        targetState = OperationState.ENDORSEMENT_TRANSACTION,
        timeout = 15.min
    ) {
        val miningInstruction = operation.miningInstruction
            ?: error("Trying to create endorsement transaction without the mining instruction!")

        val opReturnScript = bitcoinService.generatePoPScript(miningInstruction.publicationData)
        try {
            val transaction = bitcoinService.createPoPTransaction(opReturnScript)
            if (transaction != null) {
                logger.info(operation) {
                    val txSizeKb = transaction.unsafeBitcoinSerialize().size / 1000.0
                    val feeSats = transaction.fee.value
                    val feePerKb = (feeSats / txSizeKb).roundToInt()
                    "Created BTC transaction '${transaction.txId}'. Fee: ${transaction.fee} Sat ($feePerKb Sat/KB)."
                }

                val exposedTransaction = ExpTransaction(
                    bitcoinService.context.params,
                    transaction.unsafeBitcoinSerialize()
                )

                operation.setTransaction(VpmSpTransaction(transaction, exposedTransaction.getFilteredTransaction()))
            } else {
                error("Create Bitcoin transaction returned no transaction")
            }
        } catch (e: ApplicationExceptions.UnableToAcquireTransactionLock) {
            failTask(e.message)
        }
    }

    override suspend fun confirmEndorsementTransaction(operation: VpmOperation) = operation.runTask(
        taskName = "Confirm Bitcoin Endorsement Transaction",
        targetState = OperationState.CONFIRMED,
        //timeout = 2.hr
        timeout = 100_000.hr // Very long timeout to make sure we never leave a dangling transaction
    ) {
        val endorsementTransaction = operation.endorsementTransaction
            ?: failTask("The operation has no transaction set!")

        if (endorsementTransaction.transaction.confidence.confidenceType == TransactionConfidence.ConfidenceType.DEAD) {
            error("The transaction couldn't be confirmed")
        }

        if (endorsementTransaction.transaction.confidence.confidenceType != TransactionConfidence.ConfidenceType.BUILDING) {
            // Wait for the transaction to be ready
            operation.transactionConfidenceEventChannel.asFlow().onEach {
                if (it == TransactionConfidence.ConfidenceType.DEAD) {
                    error("The transaction couldn't be confirmed")
                }
            }.first {
                it == TransactionConfidence.ConfidenceType.BUILDING
            }
        }

        logger.info(operation) { "BTC endorsement transaction has been confirmed!" }
        operation.setConfirmed()
    }

    override suspend fun determineBlockOfProof(operation: VpmOperation) = operation.runTask(
        taskName = "Determine Block of Proof",
        targetState = OperationState.BLOCK_OF_PROOF,
        timeout = 90.sec
    ) {
        val endorsementTransaction = operation.endorsementTransaction
            ?: failTask("The operation has no transaction set!")

        val blockAppearances = endorsementTransaction.transaction.appearsInHashes
            ?: failTask("The transaction does not appear in any hashes!")

        val bestBlock = bitcoinService.getBestBlock(blockAppearances.keys)
            ?: failTask("Unable to retrieve block of proof from transaction!")

        logger.info(operation) { "Block of proof: ${bestBlock.hash}" }

        // Wait for the actual block appearing in the blockchain (it should already be there given the transaction is confirmed)
        bitcoinService.getFilteredBlock(bestBlock.hash)

        operation.setBlockOfProof(VpmSpBlock(bestBlock))
    }

    override suspend fun proveEndorsementTransaction(operation: VpmOperation) = operation.runTask(
        taskName = "Prove Transaction",
        targetState = OperationState.PROVEN,
        timeout = 90.sec
    ) {
        val endorsementTransaction = operation.endorsementTransaction
            ?: failTask("Trying to prove transaction without the actual transaction!")
        val blockOfProof = operation.blockOfProof
            ?: failTask("Trying to prove transaction without block of proof!")

        val block = blockOfProof.block
        val partialMerkleTree = bitcoinService.getPartialMerkleTree(block.hash)
        val failureReason = if (partialMerkleTree != null) {
            val proof = MerkleProof.parse(partialMerkleTree)
            if (proof != null) {
                val path = proof.getCompactPath(endorsementTransaction.transaction.txId)
                logger.debug(operation) { "Merkle Proof Compact Path: $path" }
                logger.debug(operation) { "Merkle Root: ${block.merkleRoot}" }
                try {
                    val merklePath = BitcoinMerklePath(path)
                    logger.debug(operation) { "Computed Merkle Root: ${merklePath.getMerkleRoot()}" }
                    if (merklePath.getMerkleRoot().equals(block.merkleRoot.toString(), ignoreCase = true)) {
                        operation.setMerklePath(VpmMerklePath(merklePath.getCompactFormat()))
                        return@runTask
                    } else {
                        "Block Merkle root does not match computed Merkle root"
                    }
                } catch (e: Exception) {
                    logger.error("Unable to validate Merkle path for transaction", e)
                    "Unable to prove transaction ${endorsementTransaction.txId} in block ${block.hashAsString}"
                }
            } else {
                "Unable to construct Merkle proof for block ${block.hashAsString}"
            }
        } else {
            "Unable to retrieve the Merkle tree from the block ${block.hashAsString}"
        }

        // Retrieving the Merkle path from the PartialMerkleTree failed, try creating manually from the whole block
        logger.info(operation) {
            "Unable to calculate the correct Merkle path for transaction ${endorsementTransaction.txId} in " +
                "block ${blockOfProof.hash} from a FilteredBlock, trying a fully downloaded block!"
        }
        val fullBlock = bitcoinService.downloadBlock(blockOfProof.block.hash)
        val allTransactions = fullBlock!!.transactions
        val txids: MutableList<String> = ArrayList()
        for (i in allTransactions!!.indices) {
            txids.add(allTransactions[i].txId.toString())
        }
        val bmt = BitcoinMerkleTree(true, txids)
        val merklePath = bmt.getPathFromTxID(endorsementTransaction.txId.toString())!!
        if (merklePath.getMerkleRoot().equals(blockOfProof.block.merkleRoot.toString(), ignoreCase = true)) {
            operation.setMerklePath(VpmMerklePath(merklePath.getCompactFormat()))
        } else {
            logger.error(
                "Unable to calculate the correct Merkle path for transaction ${endorsementTransaction.txId}" +
                    " in block ${blockOfProof.block.hashAsString} from a FilteredBlock or a fully downloaded block!"
            )
            error(failureReason)
        }
    }

    override suspend fun buildPublicationContext(operation: VpmOperation) = operation.runTask(
        taskName = "Build Publication Context",
        targetState = OperationState.CONTEXT,
        timeout = 2.min
    ) {
        val miningInstruction = operation.miningInstruction
            ?: error("Trying to build context without the mining instruction!")
        val blockOfProof = operation.blockOfProof
            ?: error("Trying to build context without the block of proof!")

        val contextChainProvided = miningInstruction.endorsedBlockContextHeaders.isNotEmpty()
        // Get all the previous blocks until we find
        val context = generateSequence(blockOfProof.block) { block ->
            bitcoinService.getBlock(block.prevBlockHash)
                ?: error("Could not retrieve block '${block.prevBlockHash}'")
        }.drop(1).takeWhile { block ->
            val found = if (contextChainProvided) {
                miningInstruction.endorsedBlockContextHeaders.any {
                    it.contentEquals(block.serializeHeader())
                }
            } else {
                val blockIndex = nodeCoreGateway.getBitcoinBlockIndex(block.serializeHeader())
                blockIndex != null
            }
            logger.trace {
                val prefix = if (found) "Found" else "Did not find"
                val where = if (contextChainProvided) "endorsed block context headers" else "search of current NodeCore view"
                "$prefix block ${block.hashAsString} in $where"
            }
            !found
        }.toList().reversed()
        operation.setContext(VpmContext(context))
    }

    override suspend fun submitPopEndorsement(operation: VpmOperation) = operation.runTask(
        taskName = "Submit PoP Endorsement",
        targetState = OperationState.SUBMITTED_POP_DATA,
        timeout = 30.sec
    ) {
        val miningInstruction = operation.miningInstruction
            ?: error("Trying to submit PoP endorsement without the mining instruction!")
        val endorsementTransaction = operation.endorsementTransaction
            ?: error("Trying to submit PoP endorsement without the endorsement transaction!")
        val blockOfProof = operation.blockOfProof
            ?: error("Trying to submit PoP endorsement without the block of proof!")
        val merklePath = operation.merklePath
            ?: error("Trying to submit PoP endorsement without the merkle path!")
        val context = operation.context
            ?: error("Trying to submit PoP endorsement without the publication context!")

        try {
            val popMiningTransaction = PopMiningTransaction(
                miningInstruction, endorsementTransaction.transactionBytes, merklePath.compactFormat,
                blockOfProof.block, context.blocks
            )
            val popTxId = nodeCoreGateway.submitPop(popMiningTransaction)
            logger.info(operation) { "PoP endorsement submitted! PoP transaction id: $popTxId" }
            operation.setProofOfProofId(popTxId)
        } catch (e: ApplicationExceptions.PoPSubmitRejected) {
            logger.error("NodeCore rejected PoP submission")
            failTask("NodeCore rejected PoP submission. Check NodeCore logs for detail.")
        } catch (e: StatusRuntimeException) {
            logger.error("Failed to submit PoP transaction to NodeCore: {}", e.status)
            failTask("Unable to submit PoP transaction to NodeCore. Check that NodeCore RPC is available and resubmit operation.")
        }
    }

    override suspend fun confirmPayout(operation: VpmOperation) = operation.runTask(
        taskName = "Confirm Payout",
        targetState = OperationState.COMPLETED,
        timeout = 20.hr
    ) {
        val miningInstruction = operation.miningInstruction
            ?: error("Trying to confirm Payout without the mining instruction!")

        val endorsedBlockHeight = operation.endorsedBlockHeight
            ?: error("Trying to wait for the payout block without having the endorsed block height set")

        // Wait for the endorsement transaction to have enough confirmations
        // DISABLED: it took too much NodeCore power
        //do {
        //    delay(30000)
        //    val confirmations = try {
        //        nodeCoreService.getTransactionConfirmationsById(state.proofOfProofId)
        //    } catch (e: Exception) {
        //        failTask("Transaction retrieval by id has failed: ${e.message}")
        //    }
        //} while (confirmations == null || confirmations < 50)

        val payoutBlockHeight = endorsedBlockHeight + 500
        val payoutAddress = miningInstruction.minerAddress

        // Wait for the payout block
        var payoutBlockHash: String?
        do {
            delay(30000)
            payoutBlockHash = try {
                nodeCoreGateway.getBlockHash(payoutBlockHeight)
            } catch (e: Exception) {
                failTask("Block retrieval by height has failed: ${e.message}")
            }
        } while (payoutBlockHash == null)

        logger.debug(operation) { "Payout block hash: $payoutBlockHash" }

        // FIXME: Retrieve the reward from the payout block itself
        val endorsementInfo = nodeCoreGateway.getPopEndorsementInfo().find {
            it.endorsedBlockNumber == endorsedBlockHeight && it.minerAddress == payoutAddress
        } ?: error("Could not find PoP endorsement reward in the payout block $payoutBlockHash @ $payoutBlockHeight!")

        operation.complete(payoutBlockHash, endorsementInfo.reward)
        logger.info(operation) { "Operation has completed! Payout: ${endorsementInfo.reward} VBK" }
    }
}