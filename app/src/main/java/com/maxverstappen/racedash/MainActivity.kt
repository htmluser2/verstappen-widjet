package com.maxverstappen.racedash

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.webkit.WebViewAssetLoader
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var dashboard: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        dashboard.addJavascriptInterface(DashboardBridge(applicationContext), "AndroidF1")
        dashboard.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ) = assetLoader.shouldInterceptRequest(request.url)

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

        val periodicRefresh = PeriodicWorkRequestBuilder<CalendarRefreshWorker>(
            6,
            TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CalendarRefreshWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRefresh
        )
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
            dashboard.removeJavascriptInterface("AndroidF1")
            dashboard.destroy()
        }
        super.onDestroy()
    }

    companion object {
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

class DashboardBridge(private val context: android.content.Context) {
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
}
