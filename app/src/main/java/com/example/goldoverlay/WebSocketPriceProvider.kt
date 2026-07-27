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
private data class DerivTick(
    val ask: Double? = null,
    val bid: Double? = null,
    val quote: Double? = null,
    val epoch: Long? = null,
    val symbol: String? = null
)

@Serializable
private data class DerivResponse(
    @SerialName("msg_type") val msgType: String? = null,
    val tick: DerivTick? = null,
    val req_id: Int? = null
)

class WebSocketPriceProvider : PriceProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var isClosedManually = false

    private val _priceState = MutableStateFlow(PriceData())
    override val priceState: StateFlow<PriceData> = _priceState.asStateFlow()

    private var previousPrice: Double = 0.0

    override fun connect() {
        if (webSocket != null) return
        isClosedManually = false

        // Endpoint WebSocket Forex Spot (XAU/USD - Ouro Spot idêntico ao Exness/MT4/MT5)
        val request = Request.Builder()
            .url("wss://ws.derivws.com/websockets/v3?app_id=1089")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Inscrever na cotação Forex XAU/USD tick por tick em tempo real
                val subscribeJson = """
                    {
                        "ticks": "frxXAUUSD",
                        "subscribe": 1,
                        "req_id": 1
                    }
                """.trimIndent()
                webSocket.send(subscribeJson)

                // Iniciar timer de ping a cada 30 segundos para manter conexão persistente ativa
                startPingLoop(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val response = json.decodeFromString<DerivResponse>(text)
                    if (response.msgType == "tick" && response.tick != null) {
                        val tick = response.tick
                        val lastPrice = tick.quote ?: tick.bid ?: return
                        val bidVal = tick.bid ?: lastPrice
                        val askVal = tick.ask ?: lastPrice

                        val isUp = when {
                            previousPrice == 0.0 -> null
                            lastPrice > previousPrice -> true
                            lastPrice < previousPrice -> false
                            else -> _priceState.value.isUp
                        }

                        if (lastPrice != previousPrice) {
                            previousPrice = lastPrice
                        }

                        val timeStr = timeFormat.format(Date())

                        _priceState.value = PriceData(
                            symbol = "XAU/USD",
                            bid = String.format(Locale.US, "%.2f", bidVal),
                            ask = String.format(Locale.US, "%.2f", askVal),
                            lastPrice = lastPrice,
                            isUp = isUp,
                            lastUpdated = timeStr
                        )
                    }
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

    private fun startPingLoop(ws: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(30_000)
                try {
                    ws.send("""{"ping": 1, "req_id": 99}""")
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun cleanUpConnection() {
        pingJob?.cancel()
        pingJob = null
        webSocket = null
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(5000)
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
