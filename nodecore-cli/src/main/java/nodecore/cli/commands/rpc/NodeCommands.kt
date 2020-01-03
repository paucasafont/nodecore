package nodecore.cli.commands.rpc

import nodecore.api.grpc.VeriBlockMessages
import nodecore.cli.CliShell
import nodecore.cli.contracts.PeerEndpoint
import nodecore.cli.prepareResult
import nodecore.cli.rpcCommand
import org.veriblock.shell.CommandParameter
import org.veriblock.shell.CommandParameterType

fun CliShell.nodeCommands() {
    rpcCommand(
        name = "Add Node",
        form = "addnode",
        description = "Add a peer node to the local configuration and connect",
        parameters = listOf(
            CommandParameter(name = "peer", type = CommandParameterType.PEER, required = true)
        ),
        suggestedCommands = { listOf("removenode") }
    ) {
        val peer: PeerEndpoint = getParameter("peer")
        val request = VeriBlockMessages.Endpoint.newBuilder().setAddress(peer.address())
            .setPort(peer.port().toInt()).build()
        val result = adminService.addNode(
            VeriBlockMessages.NodeRequest.newBuilder().addEndpoint(request).build()
        )

        prepareResult(result.success, result.resultsList)
    }

    rpcCommand(
        name = "Remove Node",
        form = "removenode",
        description = "Removes the peer address from the configuration",
        parameters = listOf(
            CommandParameter(name = "peer", type = CommandParameterType.PEER, required = true)
        ),
        suggestedCommands = { listOf("addnode") }
    ) {
        val peer: PeerEndpoint = getParameter("peer")
        val request = VeriBlockMessages.Endpoint.newBuilder().setAddress(peer.address())
            .setPort(peer.port().toInt()).build()
        val result = adminService.removeNode(
            VeriBlockMessages.NodeRequest.newBuilder().addEndpoint(request).build()
        )

        prepareResult(result.success, result.resultsList)
    }
}
