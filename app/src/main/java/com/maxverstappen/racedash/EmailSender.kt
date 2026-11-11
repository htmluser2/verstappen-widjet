package com.maxverstappen.racedash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * EmailSender — автоматично надсилає лист із 6-значним кодом на Gmail
 * користувача через Supabase Edge Function (send-verification-email,
 * всередині — Resend SMTP). Код приходить у скриньку сам, без участі
 * користувача.
 *
 * Налаштування:
 *   SUPABASE_URL       — адреса вашого Supabase-проєкту (Settings -> API)
 *   SUPABASE_ANON_KEY  — публічний anon-ключ того ж проєкту
 *
 * Якщо ключі не заповнені (значення за замовчуванням), функція тихо
 * повертає false і SecurityManager показує код користувачу напряму —
 * поведінка нічим не відрізняється від попередньої версії.
 */
object EmailSender {

    private const val TAG = "EmailSender"

    // Supabase-проєкт (publishable key — публічний, безпечно для клієнта).
    private const val SUPABASE_URL = "https://hjaivwiuehtrmwwydbcu.supabase.co"
    private const val SUPABASE_ANON_KEY = "sb_publishable_U4xZ5SBAQGoLkvESNXAUEQ_t8bp734u"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Надсилає лист. Результат передається в [callback]:
     *   success = true  — лист прийнято сервером, код іде до скриньки;
     *   success = false — не налаштовано/помилка мережі -> показуємо код у діалозі.
     */
    fun send(context: Context, to: String, subject: String, body: String, callback: (Boolean) -> Unit) {
        // Extract 6-digit code from body ("Код підтвердження реєстрації: 123456").
        val code = Regex("\\b\\d{6}\\b").find(body)?.value
        if (isConfigured() && code != null) {
            val purpose = if (subject.contains("2FA") || subject.contains("входу")) "login" else "registration"
            sendViaSupabase(to, code, purpose, callback)
        } else {
            Log.w(TAG, "Supabase not configured or no code found — falling back to mailto")
            fallbackMailto(context, to, subject, body)
            callback(false)
        }
    }

    private fun isConfigured(): Boolean =
        !SUPABASE_URL.contains("YOUR_PROJECT_REF") &&
        !SUPABASE_ANON_KEY.contains("YOUR_SUPABASE_ANON_KEY")

    private fun sendViaSupabase(to: String, code: String, purpose: String, callback: (Boolean) -> Unit) {
        val url = "$SUPABASE_URL/functions/v1/send-verification-email"
        val payload = JSONObject().apply {
            put("to", to)
            put("code", code)
            put("purpose", purpose)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
            .header("apikey", SUPABASE_ANON_KEY)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                Log.e(TAG, "Supabase e-mail request failed for $to", error)
                callback(false)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val ok = response.isSuccessful
                if (!ok) Log.e(TAG, "Supabase responded ${response.code}: ${response.body?.string()}")
                response.close()
                callback(ok)
            }
        })
    }

    /** Резервний варіант: відкрити поштовий клієнт із prefilled листом. */
    private fun fallbackMailto(context: Context, to: String, subject: String, body: String) {
        try {
            val uri = Uri.parse(
                "mailto:$to?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
            )
            context.startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            Log.e(TAG, "Unable to launch e-mail client for $to", error)
        }
    }
}
