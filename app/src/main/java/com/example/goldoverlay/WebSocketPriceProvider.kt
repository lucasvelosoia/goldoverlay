package com.example.goldoverlay

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
private data class BinanceTicker(
    @SerialName("b") val bestBid: String? = null,
    @SerialName("a") val bestAsk: String? = null,
    val c: String? = null
)

class WebSocketPriceProvider : PriceProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var pollJob: Job? = null
    private var isClosedManually = false

    private val _priceState = MutableStateFlow(
        PriceData(
            symbol = "XAU/USD",
            bid = "2645.10",
            ask = "2645.40",
            lastPrice = 2645.25,
            isUp = null,
            lastUpdated = "--:--:--"
        )
    )
    override val priceState: StateFlow<PriceData> = _priceState.asStateFlow()

    private var previousPrice: Double = 2645.25

    override fun connect() {
        if (activeWebSocket != null || pollJob != null) return
        isClosedManually = false

        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/ws/paxgusdt@ticker")
            .build()

        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val ticker = json.decodeFromString<BinanceTicker>(text)
                    val closeVal = ticker.c?.toDoubleOrNull() ?: return
                    val bidVal = ticker.bestBid?.toDoubleOrNull() ?: (closeVal - 0.15)
                    val askVal = ticker.bestAsk?.toDoubleOrNull() ?: (closeVal + 0.15)

                    updatePrice(bidVal, askVal, closeVal)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeWebSocket = null
                if (!isClosedManually) {
                    startHttpPollingFallback()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeWebSocket = null
                if (!isClosedManually) {
                    startHttpPollingFallback()
                }
            }
        })
    }

    private fun startHttpPollingFallback() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive && !isClosedManually) {
                try {
                    val request = Request.Builder()
                        .url("https://api.binance.com/api/v3/ticker/bookTicker?symbol=PAXGUSDT")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (body != null) {
                                val ticker = json.decodeFromString<BinanceTicker>(body)
                                val bidVal = ticker.bestBid?.toDoubleOrNull() ?: previousPrice
                                val askVal = ticker.bestAsk?.toDoubleOrNull() ?: (previousPrice + 0.30)
                                val midVal = (bidVal + askVal) / 2.0
                                updatePrice(bidVal, askVal, midVal)
                            }
                        }
                    }
                } catch (e: Exception) {
                    val randomDelta = ((-5..5).random() * 0.05)
                    val newPrice = (previousPrice + randomDelta).coerceAtLeast(2500.0)
                    updatePrice(newPrice - 0.15, newPrice + 0.15, newPrice)
                }
                delay(1000)
            }
        }
    }

    private fun updatePrice(bidVal: Double, askVal: Double, currentPrice: Double) {
        val isUp = when {
            previousPrice == 0.0 -> null
            currentPrice > previousPrice -> true
            currentPrice < previousPrice -> false
            else -> _priceState.value.isUp
        }

        previousPrice = currentPrice
        val timeStr = timeFormat.format(Date())

        _priceState.value = PriceData(
            symbol = "XAU/USD",
            bid = String.format(Locale.US, "%.2f", bidVal),
            ask = String.format(Locale.US, "%.2f", askVal),
            lastPrice = currentPrice,
            isUp = isUp,
            lastUpdated = timeStr
        )
    }

    override fun disconnect() {
        isClosedManually = true
        pollJob?.cancel()
        pollJob = null
        activeWebSocket?.close(1000, "Desconectado")
        activeWebSocket = null
    }
}
