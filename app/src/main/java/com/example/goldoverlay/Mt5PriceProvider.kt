package com.example.goldoverlay

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
private data class Mt5Tick(
    val bid: Double? = null,
    val ask: Double? = null,
    val time: Long? = null,
    val symbol: String? = null
)

class Mt5PriceProvider(rawUrl: String) : PriceProvider {

    private val wsUrl: String = normalizeUrl(rawUrl)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var isClosedManually = false

    private val _priceState = MutableStateFlow(
        PriceData(
            symbol = "XAU/USD",
            bid = "---",
            ask = "---",
            lastPrice = 0.0,
            isUp = null,
            lastUpdated = "Conectando MT5..."
        )
    )
    override val priceState: StateFlow<PriceData> = _priceState.asStateFlow()

    private var previousPrice: Double = 0.0

    override fun connect() {
        if (activeWebSocket != null) return
        isClosedManually = false
        openWebSocket()
    }

    private fun openWebSocket() {
        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: Exception) {
            _priceState.value = _priceState.value.copy(lastUpdated = "URL inválida")
            scheduleReconnect()
            return
        }
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectJob?.cancel()
                reconnectJob = null
                _priceState.value = _priceState.value.copy(lastUpdated = "MT5 conectado")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val tick = json.decodeFromString<Mt5Tick>(text)
                    val bid = tick.bid ?: return
                    val ask = tick.ask ?: return
                    val mid = (bid + ask) / 2.0
                    updatePrice(bid, ask, mid)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeWebSocket = null
                if (!isClosedManually) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeWebSocket = null
                if (!isClosedManually) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            _priceState.value = _priceState.value.copy(lastUpdated = "Reconectando...")
            delay(5_000)
            if (!isClosedManually) openWebSocket()
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

        _priceState.value = PriceData(
            symbol = "XAU/USD",
            bid = String.format(Locale.US, "%.2f", bidVal),
            ask = String.format(Locale.US, "%.2f", askVal),
            lastPrice = currentPrice,
            isUp = isUp,
            lastUpdated = timeFormat.format(Date())
        )
    }

    override fun disconnect() {
        isClosedManually = true
        reconnectJob?.cancel()
        reconnectJob = null
        activeWebSocket?.close(1000, "Desconectado")
        activeWebSocket = null
        scope.cancel()
    }

    companion object {
        fun normalizeUrl(url: String): String {
            var u = url.trim()
            // Remove esquema http se o usuário colou errado
            u = u.removePrefix("https://").removePrefix("http://")
            // Garante esquema ws://
            if (!u.startsWith("ws://") && !u.startsWith("wss://")) {
                u = "ws://$u"
            }
            // Garante path /ws se o usuário digitou só o IP:porta
            val afterScheme = u.substringAfter("://")
            if (!afterScheme.contains("/")) {
                u = "$u/ws"
            }
            return u
        }
    }
}
