package nodecore.cli.commands.rpc

import nodecore.api.grpc.VeriBlockMessages
import nodecore.api.grpc.utilities.ByteStringAddressUtility
import nodecore.api.grpc.utilities.ByteStringUtility
import nodecore.cli.CliShell
import nodecore.cli.prepareResult
import nodecore.cli.rpcCommand
import nodecore.cli.serialization.ImportPrivateKeyInfo
import nodecore.cli.serialization.PrivateKeyInfo
import org.veriblock.shell.CommandParameter
import org.veriblock.shell.CommandParameterType

fun CliShell.privateKeyCommands() {
    rpcCommand(
        name = "Dump Private Key",
        form = "dumpprivatekey|dumpprivkey",
        description = "Gets private key for an address",
        parameters = listOf(
            CommandParameter(name = "address", type = CommandParameterType.STANDARD_ADDRESS, required = true)
        ),
        suggestedCommands = { listOf("backupwallet", "importwallet", "importprivatekey") }
    ) {
        val address: String = getParameter("address")
        val request = VeriBlockMessages.DumpPrivateKeyRequest.newBuilder()
            .setAddress(ByteStringAddressUtility.createProperByteStringAutomatically(address))
            .build()

        val result = adminService.dumpPrivateKey(request)

        prepareResult(result.success, result.resultsList) {
            PrivateKeyInfo(result)

            printInfo("Anyone with access to this private key can steal any funds held in $address! " +
                "Make sure that this private key is stored SECURELY!")
        }
    }

    rpcCommand(
        name = "Import Private Key",
        form = "importprivatekey|importprivkey",
        description = "Imports the provided private key into NodeCore",
        parameters = listOf(
            CommandParameter(name = "privateKey", type = CommandParameterType.HEXSTRING, required = true)
        ),
        suggestedCommands = { listOf("dumpprivatekey", "backupwallet", "importwallet") }
    ) {
        val privateKeyHex: String = getParameter("privateKey")
        val request = VeriBlockMessages.ImportPrivateKeyRequest.newBuilder()
            .setPrivateKey(ByteStringUtility.hexToByteString(privateKeyHex))
            .build()
        val result = adminService.importPrivateKey(request)

        prepareResult(result.success, result.resultsList) {
            ImportPrivateKeyInfo(result)
        }
    }
}
