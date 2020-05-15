// VeriBlock Blockchain Project
// Copyright 2017-2018 VeriBlock, Inc
// Copyright 2018-2020 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.

package org.veriblock.miners.pop.service

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import org.veriblock.core.altchain.checkForValidEndorsement
import org.veriblock.core.utilities.AddressUtility
import org.veriblock.core.utilities.Utility
import org.veriblock.core.utilities.createLogger
import org.veriblock.core.utilities.debugError
import org.veriblock.core.utilities.debugWarn
import org.veriblock.core.utilities.extensions.asHexBytes
import org.veriblock.core.utilities.extensions.formatAtomicLongWithDecimal
import org.veriblock.core.utilities.extensions.toHex
import org.veriblock.lite.NodeCoreLiteKit
import org.veriblock.lite.core.TransactionMeta
import org.veriblock.miners.pop.core.ApmOperation
import org.veriblock.miners.pop.core.ApmSpTransaction
import org.veriblock.miners.pop.core.MiningOperationState
import org.veriblock.miners.pop.core.debug
import org.veriblock.miners.pop.core.info
import org.veriblock.miners.pop.util.VTBDebugUtility
import org.veriblock.sdk.alt.ApmInstruction
import org.veriblock.sdk.alt.model.SecurityInheritingBlock
import org.veriblock.sdk.alt.model.SecurityInheritingTransaction
import org.veriblock.sdk.models.AltPublication
import org.veriblock.sdk.models.BlockStoreException
import org.veriblock.core.crypto.Sha256Hash
import org.veriblock.miners.pop.core.ApmOperationState
import org.veriblock.core.crypto.VBlakeHash
import org.veriblock.sdk.models.VeriBlockPublication
import org.veriblock.sdk.services.SerializeDeserializeService

private val logger = createLogger {}

class ApmTaskService(
    private val minerConfig: MinerConfig,
    private val nodeCoreLiteKit: NodeCoreLiteKit
) : TaskService<ApmOperation>() {
    override suspend fun runTasksInternal(operation: ApmOperation) {
        operation.runTask(
            taskName = "Retrieve Mining Instruction from ${operation.chain.name}",
            targetState = ApmOperationState.INSTRUCTION,
            timeout = 90.sec
        ) {
            logger.info(operation, "Getting the mining instruction...")
            val publicationData = try {
                operation.chain.getMiningInstruction(operation.endorsedBlockHeight)
            } catch (e: Exception) {
                failOperation("Error while trying to get PoP Mining Instruction from ${operation.chain.name}: ${e.message}")
            }
            operation.setMiningInstruction(publicationData)
            logger.info(operation, "Successfully retrieved the mining instruction!")
            val vbkContextBlockHash = publicationData.context[0]
            nodeCoreLiteKit.network.getBlock(VBlakeHash.wrap(vbkContextBlockHash))
                ?: failOperation("Unable to find the mining instruction's VBK context block ${vbkContextBlockHash.toHex()}")
            nodeCoreLiteKit.blockChain.getChainHead()
                ?: failOperation("Unable to get VBK's chain head!")
        }

        operation.runTask(
            taskName = "Create Endorsement Transaction",
            targetState = ApmOperationState.ENDORSEMENT_TRANSACTION,
            timeout = 90.sec
        ) {
            val miningInstruction = operation.miningInstruction
                ?: failTask("CreateEndorsementTransactionTask called without mining instruction!")

            // Something to fill in all the gaps
            logger.info(operation, "Submitting endorsement VBK transaction...")
            val transaction = try {
                val endorsementData = SerializeDeserializeService.serialize(miningInstruction.publicationData)
                endorsementData.checkForValidEndorsement {
                    logger.debugError(it) { "Invalid endorsement data" }
                    failOperation("Invalid endorsement data: ${endorsementData.toHex()}")
                }
                nodeCoreLiteKit.network.submitEndorsement(
                    endorsementData,
                    minerConfig.feePerByte,
                    minerConfig.maxFee
                )
            } catch (e: Exception) {
                failOperation("Could not create endorsement VBK transaction: ${e.message}")
            }

            val valid = AddressUtility.isSignatureValid(
                transaction.id.bytes, transaction.signature, transaction.publicKey, transaction.sourceAddress.address
            )
            if (!valid) {
                failOperation("Endorsement VBK transaction signature is not valid")
            }

            val walletTransaction = nodeCoreLiteKit.transactionMonitor.getTransaction(transaction.id)
            operation.setTransaction(ApmSpTransaction(walletTransaction))
            logger.info(operation, "Successfully added the VBK transaction: ${walletTransaction.id}!")
        }

        operation.runTask(
            taskName = "Confirm transaction",
            targetState = ApmOperationState.CONFIRMED,
            timeout = 1.hr
        ) {
            val endorsementTransaction = operation.endorsementTransaction
                ?: failTask("ConfirmTransactionTask called without wallet transaction!")

            logger.info(operation, "Waiting for the transaction to be included in VeriBlock block...")
            // We will wait for the transaction to be confirmed, which will trigger DetermineBlockOfProofTask
            val txMetaChannel = endorsementTransaction.transaction.transactionMeta.stateChangedBroadcastChannel.openSubscription()
            txMetaChannel.receive() // Skip first state change (PENDING)
            do {
                val metaState = txMetaChannel.receive()
                if (metaState === TransactionMeta.MetaState.PENDING) {
                    failOperation("The VeriBlock chain has reorganized")
                }
            } while (metaState !== TransactionMeta.MetaState.CONFIRMED)
            txMetaChannel.cancel()

            // Transaction has been confirmed!
            operation.setConfirmed()
        }

        operation.runTask(
            taskName = "Determine Block of Proof",
            targetState = ApmOperationState.BLOCK_OF_PROOF,
            timeout = 20.sec
        ) {
            val transaction = operation.endorsementTransaction?.transaction
                ?: failTask("The operation has no transaction set!")

            val blockHash = transaction.transactionMeta.appearsInBestChainBlock
                ?: failTask("Unable to retrieve block of proof from transaction")

            try {
                val block = nodeCoreLiteKit.blockChain.get(blockHash)
                    ?: failTask("Unable to retrieve VBK block $blockHash")
                operation.setBlockOfProof(block)
            } catch (e: BlockStoreException) {
                failTask("Error when retrieving VBK block $blockHash")
            }
            logger.info(operation, "Successfully added the VBK block of proof!")
        }

        operation.runTask(
            taskName = "Prove Transaction",
            targetState = ApmOperationState.PROVEN,
            timeout = 20.sec
        ) {
            val endorsementTransaction = operation.endorsementTransaction
                ?: failTask("ProveTransactionTask called without VBK endorsement transaction!")
            val blockOfProof = operation.blockOfProof
                ?: failTask("ProveTransactionTask called without VBK block of proof!")

            val walletTransaction = endorsementTransaction.transaction

            logger.info(operation, "Getting the merkle path for the transaction: ${walletTransaction.id}...")
            val merklePath = walletTransaction.merklePath
                ?: failOperation("No merkle path found for ${walletTransaction.id}")
            logger.info(operation, "Successfully retrieved the merkle path for the transaction: ${walletTransaction.id}!")

            val vbkMerkleRoot = merklePath.merkleRoot.trim(Sha256Hash.VERIBLOCK_MERKLE_ROOT_LENGTH)
            val verified = vbkMerkleRoot == blockOfProof.merkleRoot
            if (!verified) {
                failOperation(
                    "Unable to verify merkle path! VBK Transaction's merkle root: $vbkMerkleRoot;" +
                        " Block of proof's merkle root: ${blockOfProof.merkleRoot}"
                )
            }

            operation.setMerklePath(merklePath)
            logger.info(operation, "Successfully added the verified merkle path!")
        }

        operation.runTask(
            taskName = "Wait for next VeriBlock Keystone",
            targetState = ApmOperationState.KEYSTONE_OF_PROOF,
            timeout = 1.hr
        ) {
            val blockOfProof = operation.blockOfProof
                ?: failTask("RegisterVeriBlockPublicationPollingTask called without block of proof!")

            logger.info(operation, "Waiting for the next VBK Keystone...")
            val topBlockHeight = nodeCoreLiteKit.blockChain.getChainHead()?.height ?: 0
            val desiredKeystoneOfProofHeight = blockOfProof.height / 20 * 20 + 20
            val keystoneOfProofHeight = if (topBlockHeight >= desiredKeystoneOfProofHeight) {
                topBlockHeight / 20 * 20 + 20
            } else {
                desiredKeystoneOfProofHeight
            }
            val keystoneOfProof = nodeCoreLiteKit.blockChain.newBestBlockChannel.asFlow().first { block ->
                logger.debug(operation, "Checking block ${block.hash} @ ${block.height}...")
                if (block.height > keystoneOfProofHeight) {
                    failOperation(
                        "The next VBK Keystone has been skipped!" +
                            " Expected keystone height: $keystoneOfProofHeight; received block height: ${block.height}"
                    )
                }
                block.height == keystoneOfProofHeight
            }
            operation.setKeystoneOfProof(keystoneOfProof)
            logger.info(operation, "Keystone of Proof received: ${keystoneOfProof.hash} @ ${keystoneOfProof.height}")
        }
        operation.runTask(
            taskName = "Retrieve VeriBlock Publication data",
            targetState = ApmOperationState.CONTEXT,
            timeout = 1.hr
        ) {
            val miningInstruction = operation.miningInstruction
                ?: failTask("RegisterVeriBlockPublicationPollingTask called without mining instruction!")
            val keystoneOfProof = operation.keystoneOfProof
                ?: failTask("RegisterVeriBlockPublicationPollingTask called without keystone of proof!")

            // We will be waiting for this operation's veriblock publication, which will trigger the SubmitProofOfProofTask
            val publications = nodeCoreLiteKit.network.getVeriBlockPublications(
                operation.id,
                keystoneOfProof.hash.toString(),
                miningInstruction.context[0].toHex(),
                miningInstruction.btcContext[0].toHex()
            )
            operation.setContext(publications)

            // Verify context
            verifyPublications(miningInstruction, publications)
        }

        operation.runTask(
            taskName = "Submit Proof of Proof",
            targetState = ApmOperationState.SUBMITTED_POP_DATA,
            timeout = 240.hr
        ) {
            val miningInstruction = operation.miningInstruction
                ?: failTask("SubmitProofOfProofTask called without mining instruction!")
            val endorsementTransaction = operation.endorsementTransaction
                ?: failTask("SubmitProofOfProofTask called without endorsement transaction!")
            val blockOfProof = operation.blockOfProof
                ?: failTask("SubmitProofOfProofTask called without block of proof!")
            val merklePath = operation.merklePath
                ?: failTask("SubmitProofOfProofTask called without merkle path!")
            val veriBlockPublications = operation.publicationData
                ?: failTask("SubmitProofOfProofTask called without VeriBlock publications!")

            try {
                val proofOfProof = AltPublication(
                    endorsementTransaction.transaction,
                    merklePath,
                    blockOfProof,
                    miningInstruction.context.mapNotNull {
                        nodeCoreLiteKit.blockStore.get(VBlakeHash.wrap(it))?.block
                    }
                )

                val siTxId = operation.chain.submit(proofOfProof, veriBlockPublications)

                val chainName = operation.chain.name
                logger.info(operation, "VTB submitted to $chainName! $chainName PoP TxId: $siTxId")

                operation.setProofOfProofId(siTxId)
            } catch (e: Exception) {
                logger.debugWarn(e) { "Error submitting proof of proof" }
                failTask("Error submitting proof of proof")
            }
        }

        operation.runTask(
            taskName = "Payout Detection",
            targetState = MiningOperationState.COMPLETED,
            timeout = 10.days
        ) {
            val miningInstruction = operation.miningInstruction
                ?: failTask("PayoutDetectionTask called without mining instruction!")
            val endorsementTransactionId = operation.proofOfProofId
                ?: failTask("PayoutDetectionTask called without proof of proof txId!")

            val chainName = operation.chain.name
            logger.info(operation, "Waiting for $chainName Endorsement Transaction ($endorsementTransactionId) to be confirmed...")
            val endorsementTransaction = operation.chainMonitor.getTransaction(endorsementTransactionId) { transaction ->
                if (transaction.confirmations < 0) {
                    throw AltchainTransactionReorgException(transaction)
                }
                transaction.confirmations >= operation.chain.config.neededConfirmations
            }
            logger.info(
                operation,
                "Successfully confirmed $chainName endorsement transaction ${endorsementTransaction.txId}!" +
                    " Confirmations: ${endorsementTransaction.confirmations}"
            )

            val endorsedBlockHeight = miningInstruction.endorsedBlockHeight
            logger.info(operation, "Waiting for $chainName endorsed block ($endorsedBlockHeight) to be confirmed...")
            val endorsedBlock = operation.chainMonitor.getBlockAtHeight(endorsedBlockHeight) { block ->
                block.confirmations >= operation.chain.config.neededConfirmations
            }

            val endorsedBlockHeader = miningInstruction.publicationData.header
            val belongsToMainChain = operation.chain.checkBlockIsOnMainChain(endorsedBlockHeight, endorsedBlockHeader)
            if (!belongsToMainChain) {
                failOperation(
                    "Endorsed block header ${endorsedBlockHeader.toHex()} @ $endorsedBlockHeight" +
                        " is not in $chainName's main chain"
                )
            }
            logger.info(operation, "Successfully confirmed $chainName endorsed block ${endorsedBlock.hash}!")

            val payoutBlockHeight = endorsedBlockHeight + operation.chain.getPayoutInterval()
            logger.debug(
                operation,
                "$chainName computed payout block height: $payoutBlockHeight ($endorsedBlockHeight + ${operation.chain.getPayoutInterval()})"
            )
            logger.info(operation, "Waiting for $chainName payout block ($payoutBlockHeight) to be confirmed...")
            val payoutBlock = operation.chainMonitor.getBlockAtHeight(payoutBlockHeight) { block ->
                if (block.confirmations < 0) {
                    throw AltchainBlockReorgException(block)
                }
                block.confirmations >= operation.chain.config.neededConfirmations
            }
            val coinbaseTransaction = operation.chain.getTransaction(payoutBlock.coinbaseTransactionId)
                ?: failTask("Unable to find transaction ${payoutBlock.coinbaseTransactionId}")
            val rewardVout = coinbaseTransaction.vout.find {
                it.addressHex.asHexBytes().contentEquals(miningInstruction.publicationData.payoutInfo)
            }
            if (rewardVout != null) {
                logger.info(
                    operation,
                    "$chainName PoP Payout detected! Amount: ${rewardVout.value.formatAtomicLongWithDecimal()} ${operation.chain.key.toUpperCase()}"
                )
                logger.info(operation, "Completed!")
                operation.setPayoutData(payoutBlock.hash, rewardVout.value)
            } else {
                failOperation(
                    "Unable to find ${operation.chain.name} PoP payout transaction in the expected block's coinbase!" +
                        " Expected payout block: ${payoutBlock.hash} @ ${payoutBlock.height}"
                )
            }
        }
        operation.complete()
    }

    private fun verifyPublications(
        miningInstruction: ApmInstruction,
        publications: List<VeriBlockPublication>
    ) {
        try {
            val btcContext = miningInstruction.btcContext
            // List<byte[]> vbkContext = context.getContext();

            // Check that the first VTB connects somewhere in the BTC context
            val firstPublication = publications[0]

            val serializedAltchainBTCContext = btcContext.joinToString("\n") { Utility.bytesToHex(it) }

            val serializedBTCHashesInPoPTransaction = VTBDebugUtility.serializeBitcoinBlockHashList(
                VTBDebugUtility.extractOrderedBtcBlocksFromPopTransaction(
                    firstPublication.transaction
                )
            )

            if (!VTBDebugUtility.vtbConnectsToBtcContext(btcContext, firstPublication)) {
                logger.error {
                    """Error: the first VeriBlock Publication with PoP TxID ${firstPublication.transaction.id} does not connect to the altchain context!
                               Altchain Bitcoin Context:
                               $serializedAltchainBTCContext
                               PoP Transaction Bitcoin blocks: $serializedBTCHashesInPoPTransaction""".trimIndent()
                }
            } else {
                logger.debug {
                    """Success: the first VeriBlock Publication with PoP TxID ${firstPublication.transaction.id} connects to the altchain context!
                               Altchain Bitcoin Context:
                               $serializedAltchainBTCContext
                               PoP Transaction Bitcoin blocks: $serializedBTCHashesInPoPTransaction""".trimIndent()
                }
            }

            // Check that every VTB connects to the previous one
            for (i in 1 until publications.size) {
                val anchor = publications[i - 1]
                val toConnect = publications[i]

                val anchorBTCBlocks = VTBDebugUtility.extractOrderedBtcBlocksFromPopTransaction(anchor.transaction)
                val toConnectBTCBlocks = VTBDebugUtility.extractOrderedBtcBlocksFromPopTransaction(toConnect.transaction)

                val serializedAnchorBTCBlocks = VTBDebugUtility.serializeBitcoinBlockHashList(anchorBTCBlocks)
                val serializedToConnectBTCBlocks = VTBDebugUtility.serializeBitcoinBlockHashList(toConnectBTCBlocks)

                if (!VTBDebugUtility.doVtbsConnect(anchor, toConnect, (if (i > 1) publications.subList(0, i-1) else ArrayList<VeriBlockPublication>()))) {
                    logger.warn {
                        """Error: VTB at index $i does not connect to the previous VTB!
                                   VTB #${i - 1} BTC blocks:
                                   $serializedAnchorBTCBlocks
                                   VTB #$i BTC blocks:
                                   $serializedToConnectBTCBlocks""".trimIndent()
                    }
                } else {
                    logger.debug { "Success, VTB at index $i connects to VTB at index ${i - 1}!" }
                }
            }
        } catch (e: Exception) {
            logger.error("An error occurred checking VTB connection and continuity!", e)
        }
    }
}

class AltchainBlockReorgException(
    val block: SecurityInheritingBlock
) : IllegalStateException("There was a reorg leaving block ${block.hash} out of the main chain!")

class AltchainTransactionReorgException(
    val transaction: SecurityInheritingTransaction
) : IllegalStateException("There was a reorg leaving transaction ${transaction.txId} out of the main chain!")
