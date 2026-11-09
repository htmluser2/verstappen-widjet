package com.maxverstappen.racedash

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

class F1LiveFeedClient(
    private val onStatus: (String) -> Unit,
    private val onMessage: (String, String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "f1-live-feed").apply { isDaemon = true }
    }

    @Volatile
    private var active = false

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var signalRReady = false
    private var reconnectScheduled = false
    private var reconnectDelaySeconds = 5L

    @Synchronized
    fun start() {
        if (active) return
        active = true
        reconnectDelaySeconds = 5L
        onStatus("CONNECTING")
        executor.execute(::connect)
    }

    @Synchronized
    fun stop() {
        active = false
        signalRReady = false
        reconnectScheduled = false
        socket?.close(1000, "Live timing screen closed")
        socket = null
        onStatus("DISCONNECTED")
    }

    private fun connect() {
        if (!active) return
        try {
            val cookie = fetchLoadBalancerCookie()
            val request = Request.Builder()
                .url(WEBSOCKET_URL)
                .header("Cookie", "AWSALBCORS=$cookie")
                .header("Origin", "https://www.formula1.com")
                .build()

            signalRReady = false
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!active) {
                        webSocket.close(1000, "Live timing screen closed")
                        return
                    }
                    socket = webSocket
                    if (!webSocket.send(JSONObject()
                            .put("protocol", "json")
                            .put("version", 1)
                            .toString() + RECORD_SEPARATOR
                    )) {
                        scheduleReconnect()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrames(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.w(TAG, "F1 feed sent an unsupported binary frame")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) socket = null
                    scheduleReconnect()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    error: Throwable,
                    response: Response?
                ) {
                    Log.w(TAG, "F1 live feed connection failed", error)
                    if (socket === webSocket) socket = null
                    publishStatus("ERROR: ${error.message ?: "connection failed"}")
                    scheduleReconnect()
                }
            })
        } catch (error: Exception) {
            Log.w(TAG, "Could not connect to the F1 live feed", error)
            publishStatus("ERROR: ${error.message ?: "connection failed"}")
            scheduleReconnect()
        }
    }

    private fun fetchLoadBalancerCookie(): String {
        val request = Request.Builder()
            .url(NEGOTIATE_URL)
            .method("OPTIONS", null)
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        call.execute().use { response ->
            val cookie = response.headers("Set-Cookie")
                .asSequence()
                .map { it.substringBefore(';') }
                .firstOrNull { it.startsWith("AWSALBCORS=") }
                ?.substringAfter('=')
                ?.takeIf(String::isNotBlank)
            if (cookie == null) {
                throw IOException("F1 feed preflight returned HTTP ${response.code} without AWSALBCORS")
            }
            return cookie
        }
    }

    private fun handleFrames(webSocket: WebSocket, raw: String) {
        raw.split(RECORD_SEPARATOR).forEach { frame ->
            if (frame.isBlank()) return@forEach
            try {
                val message = JSONObject(frame)
                if (message.has("error")) {
                    throw IOException(message.optString("error"))
                }
                when (message.optInt("type")) {
                    1 -> handleInvocation(message)
                    3 -> handleSubscriptionResult(message)
                    7 -> {
                        publishStatus("ERROR: ${message.optString("error", "feed closed")}")
                        webSocket.close(1011, "F1 feed closed")
                    }
                    else -> if (!signalRReady && message.length() == 0) {
                        subscribe(webSocket)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not process an F1 feed frame", error)
                publishStatus("ERROR: invalid live timing response")
                webSocket.close(1011, "Invalid live timing response")
            }
        }
    }

    private fun subscribe(webSocket: WebSocket) {
        if (signalRReady) return
        signalRReady = true
        val topics = JSONArray().apply {
            TOPICS.forEach(::put)
        }
        val invocation = JSONObject()
            .put("type", 1)
            .put("target", "Subscribe")
            .put("arguments", JSONArray().put(topics))
            .put("invocationId", "0")
        if (!webSocket.send("$invocation$RECORD_SEPARATOR")) {
            throw IOException("F1 feed rejected the topic subscription")
        }
        publishStatus("CONNECTED")
    }

    private fun handleSubscriptionResult(message: JSONObject) {
        if (message.optString("invocationId") != "0") return
        val result = message.optJSONObject("result") ?: return
        val topics = result.keys()
        while (topics.hasNext()) {
            val topic = topics.next()
            val value = result.get(topic)
            onMessage(topic, if (value is String) JSONObject.quote(value) else value.toString())
        }
        Log.i(TAG, "Subscribed to F1 live timing (${result.length()} initial topics)")
        publishStatus("CONNECTED")
    }

    private fun handleInvocation(message: JSONObject) {
        if (message.optString("target") != "feed") return
        val arguments = message.optJSONArray("arguments") ?: return
        if (arguments.length() < 2) return
        val topic = arguments.optString(0)
        val payload = arguments.get(1)
        val json = if (topic.endsWith(".z") && payload is String) {
            inflatePayload(payload)
        } else if (payload is String) {
            JSONObject.quote(payload)
        } else {
            payload.toString()
        }
        onMessage(topic, json)
    }

    private fun inflatePayload(payload: String): String {
        val compressed = Base64.decode(payload, Base64.DEFAULT)
        val inflater = Inflater(true)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            inflater.setInput(compressed)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    if (output.size() + count > MAX_INFLATED_BYTES) {
                        throw IOException("F1 feed payload exceeded the size limit")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }

    private fun scheduleReconnect() {
        synchronized(this) {
            if (!active || reconnectScheduled) return
            reconnectScheduled = true
            val delay = reconnectDelaySeconds
            reconnectDelaySeconds = (reconnectDelaySeconds * 2).coerceAtMost(60L)
            publishStatus("RECONNECTING")
            executor.schedule({
                synchronized(this) {
                    reconnectScheduled = false
                }
                connect()
            }, delay, TimeUnit.SECONDS)
        }
    }

    private fun publishStatus(status: String) {
        if (active || status == "DISCONNECTED") onStatus(status)
    }

    companion object {
        private const val TAG = "F1LiveFeed"
        private const val NEGOTIATE_URL =
            "https://livetiming.formula1.com/signalrcore/negotiate"
        private const val WEBSOCKET_URL = "wss://livetiming.formula1.com/signalrcore"
        private const val RECORD_SEPARATOR = "\u001e"
        private const val MAX_INFLATED_BYTES = 2 * 1024 * 1024
        private val TOPICS = listOf(
            "Heartbeat",
            "SessionInfo",
            "SessionStatus",
            "TrackStatus",
            "RaceControlMessages",
            "TimingData",
            "TimingAppData",
            "TopThree",
            "WeatherData",
            "DriverList",
            "LapCount",
            "Position.z"
        )
    }
}
