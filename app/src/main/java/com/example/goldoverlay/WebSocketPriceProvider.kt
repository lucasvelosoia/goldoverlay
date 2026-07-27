package com.example.goldoverlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
private data class BinanceTicker(
    val c: String? = null, // Preço de fechamento / atual
    val b: String? = null, // Best bid price
    val a: String? = null  // Best ask price
)

class WebSocketPriceProvider : PriceProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _priceState = MutableStateFlow(PriceData())
    override val priceState: StateFlow<PriceData> = _priceState.asStateFlow()

    private var previousPrice: Double = 0.0

    override fun connect() {
        if (webSocket != null) return

        // WebSocket endpoint do par PAXGUSDT (Token de Ouro pareado com USDT, com liquidez 24/7 de alta frequência)
        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/ws/paxgusdt@ticker")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val ticker = json.decodeFromString<BinanceTicker>(text)
                    val price = ticker.c?.toDoubleOrNull() ?: return
                    val bidVal = ticker.b?.toDoubleOrNull() ?: (price - 0.15)
                    val askVal = ticker.a?.toDoubleOrNull() ?: (price + 0.15)

                    val isUp = if (previousPrice == 0.0) null else if (price > previousPrice) true else if (price < previousPrice) false else _priceState.value.isUp

                    if (price != previousPrice && previousPrice != 0.0) {
                        previousPrice = price
                    } else if (previousPrice == 0.0) {
                        previousPrice = price
                    }

                    val now = timeFormat.format(Date())

                    _priceState.value = PriceData(
                        symbol = "XAU/USD",
                        bid = String.format(Locale.US, "%.2f", bidVal),
                        ask = String.format(Locale.US, "%.2f", askVal),
                        lastPrice = price,
                        isUp = isUp,
                        lastUpdated = now
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@WebSocketPriceProvider.webSocket = null
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@WebSocketPriceProvider.webSocket = null
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(5000)
            connect()
        }
    }

    override fun disconnect() {
        webSocket?.close(1000, "Service desativado pelo usuário")
        webSocket = null
    }
}
