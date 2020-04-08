package org.veriblock.miners.pop.api.controller

import io.ktor.application.call
import io.ktor.locations.Location
import io.ktor.locations.post
import io.ktor.request.receive
import io.ktor.routing.Route
import org.veriblock.miners.pop.api.model.MineRequest
import org.veriblock.miners.pop.api.model.WithdrawRequest
import org.veriblock.miners.pop.service.MinerService

//@Group("Wallet")
@Location("/api/wallet/btc/withdraw")
class withdrawBtc

class WalletController(
    private val minerService: MinerService
) : ApiController {

    override fun Route.registerApi() {
        post<withdrawBtc>(
        //    "withdrawbtc"
        //        .description("Withdraws BTC from the PoP Miner")
        ) {
            val request: WithdrawRequest = call.receive()
            val address: String = request.destinationAddress
                ?: throw BadRequestException("Parameter 'destinationAddress' was not supplied")
            val amountString: String = request.amount
                ?: throw BadRequestException("Parameter 'amount' was not supplied")
            val amount = amountString.toBigDecimalOrNull()
                ?: throw BadRequestException("Parameter 'amount' is not a valid number")
            val result = minerService.sendBitcoinToAddress(address, amount)
            if (result.didFail()) {
                throw CallFailureException(
                    result.messages.firstOrNull()?.let { rm ->
                        rm.message + (rm.details.firstOrNull()?.let { "; $it" } ?: "")
                    } ?: "Failed to send $amount Bitcoins to $address"
                )
            }
        }
    }
}
