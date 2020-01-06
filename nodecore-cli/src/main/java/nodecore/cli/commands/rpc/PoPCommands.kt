package nodecore.cli.commands.rpc

import com.google.protobuf.ByteString
import nodecore.api.grpc.VeriBlockMessages
import nodecore.api.grpc.utilities.ByteStringAddressUtility
import nodecore.api.grpc.utilities.ByteStringUtility
import nodecore.cli.CliShell
import nodecore.cli.prepareResult
import nodecore.cli.rpcCommand
import nodecore.cli.serialization.PoPEndorsementsInfo
import nodecore.cli.serialization.PopPayload
import nodecore.cli.serialization.TroubleshootPoPTransactionsPayload
import org.veriblock.core.utilities.Utility
import org.veriblock.core.utilities.createLogger
import org.veriblock.shell.CommandParameter
import org.veriblock.shell.CommandParameterType
import java.awt.GraphicsEnvironment

private val logger = createLogger {}

fun CliShell.popCommands() {
    rpcCommand(
        name = "Troubleshoot PoP By Address",
        form = "troubleshootpopbyaddress",
        description = "(Returns a troubleshooting report of the PoP transaction(s) matching the provided address in the specified history)",
        parameters = listOf(
            CommandParameter(name = "onlyFailures", type = CommandParameterType.BOOLEAN, required = true),
            CommandParameter(name = "searchLength", type = CommandParameterType.INTEGER, required = false),
            CommandParameter(name = "address", type = CommandParameterType.STANDARD_ADDRESS, required = false)
        ),
        suggestedCommands = { listOf("getbalance", "gethistory") }
    ) {
        val address: String? = getOptionalParameter("address")
        val request = VeriBlockMessages.TroubleshootPoPTransactionsRequest.newBuilder()
        if (address != null) {
            request.setAddresses(
                VeriBlockMessages.AddressSet.newBuilder().addAddresses(
                    ByteStringAddressUtility.createProperByteStringAutomatically(address)
                )
            )
        }
        request.onlyFailures = getParameter("onlyFailures")
        request.searchLength = getOptionalParameter("searchLength") ?: 2000

        val result = adminService.troubleshootPoPTransactions(request.build())

        prepareResult(result.success, result.resultsList) {
            TroubleshootPoPTransactionsPayload(result)
        }
    }

    rpcCommand(
        name = "Get Pop",
        form = "getpop",
        description = "Gets the data VeriBlock wants Proof-of-Proof published to Bitcoin",
        parameters = listOf(
            CommandParameter(name = "block", type = CommandParameterType.INTEGER, required = false)
        ),
        suggestedCommands = {
            if (!GraphicsEnvironment.isHeadless()) {
                listOf("startpopminer")
            } else {
                emptyList()
            }
        }
    ) {
        val request = VeriBlockMessages.GetPopRequest.newBuilder()
            .setBlockNum(getOptionalParameter("block") ?: 0)
            .build()

        val result = adminService.getPop(request)

        prepareResult(result.success, result.resultsList) {
            PopPayload(result)
        }
    }

    rpcCommand(
        name = "Get PoP Endorsements Info",
        form = "getpopendorsementsinfo",
        description = "Returns the PoP endorsements related to a particular address given the particular search length",
        parameters = listOf(
            CommandParameter(name = "address", type = CommandParameterType.STANDARD_ADDRESS, required = true),
            CommandParameter(name = "searchLength", type = CommandParameterType.INTEGER, required = false)
        ),
        suggestedCommands = { listOf("getprotectedchildren", "getprotectingparents", "startpopminer") }
    ) {
        val address: String = getParameter("address")
        val searchLength: Int? = getOptionalParameter("searchLength")
        val request = VeriBlockMessages.GetPoPEndorsementsInfoRequest.newBuilder()
            .addAddresses(VeriBlockMessages.StandardAddress.newBuilder()
                .setStandardAddress(ByteStringAddressUtility.createProperByteStringAutomatically(address)))
        if (searchLength != null) {
            request.searchLength = searchLength
        }

        val result = adminService.getPoPEndorsementsInfo(request.build())

        prepareResult(result.success, result.resultsList) {
            result.popEndorsementsList.map { PoPEndorsementsInfo(it) }
        }
    }

    rpcCommand(
        name = "Submit Proof-of-Proof",
        form = "submitpop",
        description = "Submit a Proof-of-Proof transaction",
        parameters = listOf(
            CommandParameter(name = "endorsedBlockHeader", type = CommandParameterType.STRING, required = true),
            CommandParameter(name = "bitcoinTransaction", type = CommandParameterType.STRING, required = true),
            CommandParameter(name = "bitcoinMerklePathToRoot", type = CommandParameterType.STRING, required = true),
            CommandParameter(name = "bitcoinBlockHeader", type = CommandParameterType.STRING, required = true),
            CommandParameter(name = "address", type = CommandParameterType.STANDARD_ADDRESS, required = false)
        )
    ) {
        val endorsedBlockHeader: String = getParameter("endorsedBlockHeader")
        val bitcoinTransaction: String = getParameter("bitcoinTransaction")
        val bitcoinMerklePathToRoot: String = getParameter("bitcoinMerklePathToRoot")
        val bitcoinBlockHeader: String = getParameter("bitcoinBlockHeader")
        val address: String? = getOptionalParameter("address")

        //TODO: Add context Bitcoin block header parameters

        val request = VeriBlockMessages.SubmitPopRequest.newBuilder().apply {
            this.endorsedBlockHeader = ByteString.copyFrom(Utility.hexToBytes(endorsedBlockHeader))
            this.bitcoinTransaction = ByteString.copyFrom(Utility.hexToBytes(bitcoinTransaction))
            this.bitcoinMerklePathToRoot = ByteString.copyFrom(bitcoinMerklePathToRoot.toByteArray())
            this.bitcoinBlockHeaderOfProof = VeriBlockMessages.BitcoinBlockHeader.newBuilder().setHeader(ByteString.copyFrom(Utility.hexToBytes(bitcoinBlockHeader)))
                .build()
        }
        if (address != null) {
            request.address = ByteStringAddressUtility.createProperByteStringAutomatically(address)
        }
        val result = adminService.submitPop(request.build())

        prepareResult(result.success, result.resultsList)
    }

    rpcCommand(
        name = "Get Protecting Parents",
        form = "getprotectingparents",
        description = "Returns the pop endorsement information of parents protecting the provided block hash",
        parameters = listOf(
            CommandParameter(name = "blockhash", type = CommandParameterType.HASH, required = true),
            CommandParameter(name = "searchLength", type = CommandParameterType.INTEGER, required = false)
        ),
        suggestedCommands = { listOf("getprotectedchildren", "getpopendorsementsinfo", "startpopminer") }
    ) {
        val hash: String = getParameter("blockhash")
        val searchLength: Int? = getOptionalParameter("searchLength")
        val request = VeriBlockMessages.GetProtectingParentsRequest.newBuilder()
            .setVeriblockBlockHash(ByteStringUtility.hexToByteString(hash))

        if (searchLength != null) {
            request.searchLength = searchLength
        }

        val result = adminService.getProtectingParents(request.build())

        logger.info("We received a total of ${result.popEndorsementsCount} pop endorsements!")

        prepareResult(result.success, result.resultsList) {
            result.popEndorsementsList.map { PoPEndorsementsInfo(it)  }
        }
    }

    rpcCommand(
        name = "Get Protected Children",
        form = "getprotectedchildren",
        description = "Returns the children protected by PoP transactions in a particular VeriBlock block identified by the provided block hash",
        parameters = listOf(
            CommandParameter(name = "blockhash", type = CommandParameterType.HASH, required = true),
            CommandParameter(name = "searchLength", type = CommandParameterType.INTEGER, required = false)
        ),
        suggestedCommands = { listOf("getprotectingparents", "getpopendorsementsinfo", "startpopminer") }
    ) {
        val hash: String = getParameter("blockhash")
        val searchLength: Int? = getOptionalParameter("searchLength")
        val request = VeriBlockMessages.GetProtectedChildrenRequest.newBuilder()
            .setVeriblockBlockHash(ByteStringUtility.hexToByteString(hash))

        if (searchLength != null) {
            request.searchLength = searchLength
        }

        val result = adminService.getProtectedChildren(request.build())

        logger.info("We received a total of ${result.popEndorsementsCount} pop endorsements!")

        prepareResult(result.success, result.resultsList) {
            result.popEndorsementsList.map { PoPEndorsementsInfo(it) }
        }
    }
}
