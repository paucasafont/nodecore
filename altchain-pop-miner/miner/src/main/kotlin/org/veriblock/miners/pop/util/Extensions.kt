package org.veriblock.miners.pop.util

import org.veriblock.sdk.Coin

private const val ATOMIC_UNITS_DECIMAL_PLACES = 8
private const val ATOMIC_UNITS_PER_COIN = 100_000_000

fun Long.formatCoinAmount() =
    "${this / ATOMIC_UNITS_PER_COIN}.${String.format("%0${ATOMIC_UNITS_DECIMAL_PLACES}d", this % ATOMIC_UNITS_PER_COIN)}"

fun Coin.formatCoinAmount() =
    atomicUnits.formatCoinAmount()
