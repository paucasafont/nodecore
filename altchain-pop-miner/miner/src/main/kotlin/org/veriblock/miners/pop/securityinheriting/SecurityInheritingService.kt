// VeriBlock Blockchain Project
// Copyright 2017-2018 VeriBlock, Inc
// Copyright 2018-2019 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.

package org.veriblock.miners.pop.securityinheriting

import org.veriblock.miners.pop.Miner
import org.veriblock.miners.pop.plugins.PluginFactory
import org.veriblock.sdk.createLogger

private val logger = createLogger {}

class SecurityInheritingService(
    private val miner: Miner,
    private val pluginFactory: PluginFactory
) {
    private val autoMiners = HashMap<String, SecurityInheritingAutoMiner>()

    fun start() {
        for ((chainId, chain) in pluginFactory.getPlugins()) {
            if (chain.shouldAutoMine()) {
                val autoMiner = SecurityInheritingAutoMiner(miner, chainId, chain)
                autoMiner.start()
                autoMiners[chainId] = autoMiner
            }
        }
    }

    fun stop() {
        autoMiners.values.forEach {
            it.stop()
        }
    }
}
