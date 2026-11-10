package com.maxverstappen.racedash

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.webkit.WebViewAssetLoader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var dashboard: WebView
    private lateinit var liveFeed: F1LiveFeedClient
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔒 Система безпеки: без відкритої сесії доступ заборонено.
        if (!SecurityManager.isSessionOpen(this)) {
            startActivity(android.content.Intent(this, SecurityGateActivity::class.java))
            finish()
            return
        }

        // 🔒 Перевірка цілісності файлів застосунку.
        val integrity = SecurityManager.verifyIntegrity(this)
        if (!integrity.clean) {
            startActivity(android.content.Intent(this, SecurityGateActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        dashboard = findViewById(R.id.dashboard)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        dashboard.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        liveFeed = F1LiveFeedClient(
            onStatus = { status ->
                runOnUiThread {
                    if (::dashboard.isInitialized && !isDestroyed) {
                        dashboard.evaluateJavascript(
                            "window.onF1FeedStatus?.(${JSONObject.quote(status)})",
                            null
                        )
                    }
                }
            },
            onMessage = { topic, payload ->
                runOnUiThread {
                    if (::dashboard.isInitialized && !isDestroyed) {
                        dashboard.evaluateJavascript(
                            "window.onF1FeedMessage?.(${JSONObject.quote(topic)},${JSONObject.quote(payload)})",
                            null
                        )
                    }
                }
            }
        )
        dashboard.addJavascriptInterface(
            DashboardBridge(applicationContext, liveFeed),
            "AndroidF1"
        )
        dashboard.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
                    ?: proxyApiRequest(request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest
            ): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                if (request.url.scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (error: ActivityNotFoundException) {
                        android.util.Log.w("MainActivity", "No browser can open ${request.url}", error)
                    }
                }
                return true
            }
        }
        dashboard.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        schedulePeriodicCalendarRefresh(this)
        requestCalendarRefresh()
    }

    override fun onResume() {
        super.onResume()
        if (::dashboard.isInitialized) {
            dashboard.evaluateJavascript("window.refreshDashboard?.()", null)
        }
    }

    override fun onDestroy() {
        if (::dashboard.isInitialized) {
            liveFeed.stop()
            dashboard.removeJavascriptInterface("AndroidF1")
            dashboard.destroy()
        }
        super.onDestroy()
    }

    private fun proxyApiRequest(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.scheme != "https" || !isAllowedApiUrl(uri.host.orEmpty(), uri.path.orEmpty())) {
            return null
        }

        if (request.method == "OPTIONS") {
            return WebResourceResponse(
                "application/json",
                "UTF-8",
                204,
                "No Content",
                corsHeaders(),
                ByteArrayInputStream(ByteArray(0))
            )
        }
        if (request.method != "GET") return null

        return try {
            val builder = Request.Builder().url(uri.toString()).get()
            request.requestHeaders["Accept"]?.let { builder.header("Accept", it) }
            request.requestHeaders["User-Agent"]?.let { builder.header("User-Agent", it) }
            val response = apiClient.newCall(builder.build()).execute()
            val contentType = response.header("Content-Type").orEmpty()
            val mimeType = contentType.substringBefore(';').ifBlank { "application/octet-stream" }
            val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentType)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.trim('"')
                ?: "UTF-8"
            val headers = corsHeaders().toMutableMap()
            response.header("Cache-Control")?.let { headers["Cache-Control"] = it }
            response.header("ETag")?.let { headers["ETag"] = it }
            response.header("Last-Modified")?.let { headers["Last-Modified"] = it }
            val body = response.body
            WebResourceResponse(
                mimeType,
                charset,
                response.code,
                response.message.ifBlank { "Upstream response" },
                headers,
                body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            )
        } catch (error: IOException) {
            android.util.Log.e(TAG, "Could not load API data from ${uri.host}", error)
            WebResourceResponse(
                "application/json",
                "UTF-8",
                502,
                "Bad Gateway",
                corsHeaders(),
                ByteArrayInputStream("""{"error":"Upstream API request failed"}""".toByteArray())
            )
        }
    }

    private fun isAllowedApiUrl(host: String, path: String): Boolean = when (host) {
        "api.openf1.org" -> path.startsWith("/v1/")
        "api.jolpi.ca" -> path.startsWith("/ergast/f1/")
        "raw.githubusercontent.com" ->
            path.startsWith("/SergioSediq/f1-circuits/main/circuits/")
        else -> false
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, OPTIONS",
        "Access-Control-Allow-Headers" to "Accept, Content-Type",
        "Access-Control-Max-Age" to "600"
    )

    companion object {
        private const val TAG = "MainActivity"

        internal fun schedulePeriodicCalendarRefresh(context: android.content.Context) {
            val periodicRefresh = PeriodicWorkRequestBuilder<CalendarRefreshWorker>(
                15,
                TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                CalendarRefreshWorker.PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRefresh
            )
        }

        internal fun requestCalendarRefresh(context: android.content.Context) {
            val request = OneTimeWorkRequestBuilder<CalendarRefreshWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                CalendarRefreshWorker.ONCE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    private fun requestCalendarRefresh() = requestCalendarRefresh(this)
}

class DashboardBridge(
    private val context: android.content.Context,
    private val liveFeed: F1LiveFeedClient
) {
    @JavascriptInterface
    fun getSchedule(): String = context
        .getSharedPreferences(CalendarRefreshWorker.PREFERENCES, Activity.MODE_PRIVATE)
        .getString(CalendarRefreshWorker.KEY_SCHEDULE, "[]") ?: "[]"

    @JavascriptInterface
    fun getUpdatedAt(): Long = context
        .getSharedPreferences(CalendarRefreshWorker.PREFERENCES, Activity.MODE_PRIVATE)
        .getLong(CalendarRefreshWorker.KEY_UPDATED_AT, 0L)

    @JavascriptInterface
    fun getTrackGeoJson(circuitKey: Int): String = context
        .getSharedPreferences(CalendarRefreshWorker.PREFERENCES, Activity.MODE_PRIVATE)
        .getString(CalendarRefreshWorker.trackKey(circuitKey), "") ?: ""

    @JavascriptInterface
    fun saveSchedule(rawJson: String) {
        try {
            RaceCalendar.parse(rawJson)
            context.getSharedPreferences(
                CalendarRefreshWorker.PREFERENCES,
                Activity.MODE_PRIVATE
            ).edit()
                .putString(CalendarRefreshWorker.KEY_SCHEDULE, rawJson)
                .putLong(CalendarRefreshWorker.KEY_UPDATED_AT, System.currentTimeMillis())
                .apply()
            F1WidgetProvider.refreshAll(context)
        } catch (error: org.json.JSONException) {
            android.util.Log.e("DashboardBridge", "Rejected invalid calendar JSON", error)
        }
    }

    @JavascriptInterface
    fun refresh() {
        MainActivity.requestCalendarRefresh(context)
    }

    @JavascriptInterface
    fun startLiveFeed() {
        liveFeed.start()
    }

    @JavascriptInterface
    fun stopLiveFeed() {
        liveFeed.stop()
    }
}
