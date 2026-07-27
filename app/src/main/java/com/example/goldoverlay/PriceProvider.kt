package com.example.goldoverlay

import kotlinx.coroutines.flow.StateFlow

interface PriceProvider {
    val priceState: StateFlow<PriceData>
    fun connect()
    fun disconnect()
}
