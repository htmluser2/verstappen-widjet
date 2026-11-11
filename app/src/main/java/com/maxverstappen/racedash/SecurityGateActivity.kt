package com.maxverstappen.racedash

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * SecurityGateActivity — повноекранний "замок" застосунку у стилі самого
 * застосунку: той самий тёмний F1-фон, панелі, типографіка (security.html
 * в WebView). Логіка — через JavascriptInterface на SecurityManager.
 *
 * Потоки:
 *   1) не зареєстрований  -> реєстрація (нікнейм + Gmail + пароль + код з пошти)
 *   2) зареєстрований     -> вхід (+ 2FA, якщо увімкнена)
 *   3) виявлено зміни файлів -> діалог "Це не я" (скасувати) / "Це я" (пароль)
 */
class SecurityGateActivity : Activity() {

    private lateinit var gate: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        gate = WebView(this).apply {
            setBackgroundColor(0xFF050913.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest
                ): android.webkit.WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(SecurityBridge(), "AndroidSecurity")
            loadUrl("https://appassets.androidplatform.net/assets/security.html")
        }
        setContentView(gate)
    }

    override fun onBackPressed() {
        // Замок не дає "втечі" назад — потрібно авторизуватись.
        // Нічого не робимо навмисно.
    }

    override fun onDestroy() {
        if (::gate.isInitialized) {
            gate.removeJavascriptInterface("AndroidSecurity")
            gate.destroy()
        }
        super.onDestroy()
    }

    private inner class SecurityBridge {

        @JavascriptInterface
        fun getState(): String {
            val registered = SecurityManager.baselineSize(applicationContext) > 0
            val report = SecurityManager.verifyIntegrity(applicationContext)
            val tamper = registered && !report.clean && SecurityManager.isSessionOpen(applicationContext)
            return org.json.JSONObject().apply {
                put("registered", registered)
                put("tamper", tamper)
                put("summary", if (tamper) report.summary() else "")
            }.toString()
        }

        @JavascriptInterface
        fun lastFallbackCode(): String? =
            SecurityManager.lastFallbackCode(applicationContext)

        @JavascriptInterface
        fun beginRegistration(nickname: String, email: String, password: String, twoFactor: Boolean): String {
            val error = SecurityManager.beginRegistration(
                applicationContext, nickname, email, password, twoFactor
            )
            return error ?: "OK"
        }

        @JavascriptInterface
        fun confirmRegistration(code: String): Boolean =
            SecurityManager.confirmRegistration(applicationContext, code)

        @JavascriptInterface
        fun login(nickname: String, password: String): String = when (
            SecurityManager.login(applicationContext, nickname, password)
        ) {
            is SecurityManager.LoginResult.Success -> "OK"
            is SecurityManager.LoginResult.NeedsTwoFactor -> "2FA"
            is SecurityManager.LoginResult.NoSuchUser -> "NO_USER"
            is SecurityManager.LoginResult.WrongPassword -> "BAD_PASS"
        }

        @JavascriptInterface
        fun confirmLogin2fa(code: String): Boolean =
            SecurityManager.confirmLogin2fa(applicationContext, code)

        @JavascriptInterface
        fun revertChanges() {
            SecurityManager.restoreBaseline(applicationContext)
        }

        @JavascriptInterface
        fun authorizeModification(password: String): Boolean =
            SecurityManager.authorizeModification(
                applicationContext,
                SecurityManager.activeNickname(applicationContext).orEmpty(),
                password
            )

        @JavascriptInterface
        fun proceed() {
            runOnUiThread {
                startActivity(android.content.Intent(this@SecurityGateActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}
