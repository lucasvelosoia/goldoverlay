package com.example.goldoverlay

data class PriceData(
    val symbol: String = "XAU/USD",
    val bid: String = "---.--",
    val ask: String = "---.--",
    val lastPrice: Double = 0.0,
    val isUp: Boolean? = null, // true = verde/subiu (▲), false = vermelho/caiu (▼), null = neutro
    val lastUpdated: String = "--:--:--"
)
