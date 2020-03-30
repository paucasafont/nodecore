package nodecore.miners.pop

import nodecore.miners.pop.core.MiningOperation
import nodecore.miners.pop.model.PoPMinerDependencies
import nodecore.miners.pop.model.VeriBlockHeader
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.veriblock.core.utilities.EmptyEvent
import org.veriblock.core.utilities.Event

object EventBus {
    val bitcoinServiceReadyEvent = EmptyEvent("Bitcoin Service Ready")
    val bitcoinServiceNotReadyEvent = EmptyEvent("Bitcoin Service Not Ready")
    val blockchainDownloadedEvent = EmptyEvent("Blockchain Downloaded")
    val fundsAddedEvent = EmptyEvent("Funds Added")
    val insufficientFundsEvent = EmptyEvent("Insufficient Funds")
    val newVeriBlockFoundEvent = Event<NewVeriBlockFoundEventDto>("New VeriBlock Found")
    val nodeCoreHealthyEvent = EmptyEvent("NodeCore Healthy")
    val nodeCoreUnhealthyEvent = EmptyEvent("NodeCore Unhealthy")
    val nodeCoreSynchronizedEvent = EmptyEvent("NodeCore Synchronized")
    val nodeCoreDesynchronizedEvent = EmptyEvent("NodeCore Desynchronized")
    val popMinerReadyEvent = EmptyEvent("PoP Miner Ready")
    val popMinerNotReadyEvent = Event<PoPMinerDependencies>("PoP Miner Not Ready")
    val popMiningOperationCompletedEvent = Event<String>("PoP Mining Operation Completed")
    val popMiningOperationStateChangedEvent = Event<MiningOperation>("PoP Mining Operation State Changed")
    val programQuitEvent = Event<Int>("Program Quit")
    val shellCompletedEvent = EmptyEvent("Shell Completed")
    val walletSeedAgreementMissingEvent = EmptyEvent("Wallet Seed Agreement Missing")
    val transactionSufferedReorgEvent = Event<MiningOperation>("Bitcoin transaction suffered a reorg")
}

class CoinsReceivedEventDto(
    val tx: Transaction,
    val previousBalance: Coin,
    val newBalance: Coin
)

class NewVeriBlockFoundEventDto(
    val block: VeriBlockHeader,
    val previousHead: VeriBlockHeader?
)