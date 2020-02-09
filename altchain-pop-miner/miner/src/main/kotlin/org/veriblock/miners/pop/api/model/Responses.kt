// VeriBlock NodeCore
// Copyright 2017-2019 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.

package org.veriblock.miners.pop.api.model

import org.veriblock.miners.pop.core.MiningOperation

class MinerInfoResponse(
    val vbkAddress: String,
    val vbkBalance: Long
)

class OperationSummaryResponse(
    val operationId: String,
    val chainId: String,
    val endorsedBlockNumber: Int?,
    val state: String
)

fun MiningOperation.toSummaryResponse() = OperationSummaryResponse(
    id,
    chainId,
    blockHeight,
    state.toString()
)

class OperationDetailResponse(
    val operationId: String,
    val chainId: String,
    val status: String,
    val blockHeight: Int?,
    val state: String,
    val stateDetail: List<String>
)

fun MiningOperation.toDetailedResponse() = OperationDetailResponse(
    id,
    chainId,
    status.name,
    blockHeight,
    state.toString(),
    state.getDetailedInfo()
)
