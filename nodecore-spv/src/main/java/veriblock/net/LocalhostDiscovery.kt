// VeriBlock Blockchain Project
// Copyright 2017-2018 VeriBlock, Inc
// Copyright 2018-2020 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.
package veriblock.net

import org.veriblock.core.params.LOCALHOST
import org.veriblock.core.params.NetworkParameters
import veriblock.model.PeerAddress

/**
 * Discovery peer locally.
 */
class LocalhostDiscovery(
    private val networkParameters: NetworkParameters
) : PeerDiscovery {
    override fun getPeers(count: Int): Collection<PeerAddress> {
        return listOf(PeerAddress(LOCALHOST, networkParameters.p2pPort))
    }
}
