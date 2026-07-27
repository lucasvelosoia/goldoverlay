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
private data class BinanceBookTicker(
    val u: Long? = null,    // order book updateId
    val s: String? = null,  // symbol
    val b: String? = null,  // best bid price
    val B: String? = null,  // best bid qty
    val a: String? = null,  // best ask price
    val A: String? = null   // best ask qty
)

class WebSocketPriceProvider : PriceProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isClosedManually = false

    private val _priceState = MutableStateFlow(
        PriceData(
            symbol = "XAU/USD",
            bid = "Carregando...",
            ask = "Carregando...",
            lastPrice = 0.0,
            isUp = null,
            lastUpdated = "--:--:--"
        )
    )
    override val priceState: StateFlow<PriceData> = _priceState.asStateFlow()

    private var previousPrice: Double = 0.0

    override fun connect() {
        if (webSocket != null) return
        isClosedManually = false

        // Stream bookTicker da Binance (transmite cada tick/micro-mudança de Bid e Ask 24/7 sem interrupções)
        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/ws/paxgusdt@bookTicker")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Conectado com sucesso
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val ticker = json.decodeFromString<BinanceBookTicker>(text)
                    val bidVal = ticker.b?.toDoubleOrNull() ?: return
                    val askVal = ticker.a?.toDoubleOrNull() ?: return
                    val currentPrice = (bidVal + askVal) / 2.0

                    val isUp = when {
                        previousPrice == 0.0 -> null
                        currentPrice > previousPrice -> true
                        currentPrice < previousPrice -> false
                        else -> _priceState.value.isUp
                    }

                    if (currentPrice != previousPrice) {
                        previousPrice = currentPrice
                    }

                    val timeStr = timeFormat.format(Date())

                    _priceState.value = PriceData(
                        symbol = "XAU/USD",
                        bid = String.format(Locale.US, "%.2f", bidVal),
                        ask = String.format(Locale.US, "%.2f", askVal),
                        lastPrice = currentPrice,
                        isUp = isUp,
                        lastUpdated = timeStr
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                cleanUpConnection()
                if (!isClosedManually) {
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                cleanUpConnection()
                if (!isClosedManually) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun cleanUpConnection() {
        webSocket = null
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(3000)
            connect()
        }
    }

    override fun disconnect() {
        isClosedManually = true
        cleanUpConnection()
        webSocket?.close(1000, "Desconectado pelo usuário")
        webSocket = null
    }
}
