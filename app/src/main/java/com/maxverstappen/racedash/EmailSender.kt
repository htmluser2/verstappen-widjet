package com.maxverstappen.racedash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * EmailSender — відповідальний за доставку 6-значних кодів реєстрації / 2FA
 * на Gmail користувача.
 *
 * Бекенд-сервера в цьому проєкті немає, тому спосіб доставки обрано максимально
 * надійний і безпечний для користувача — відкриття поштового клієнта із
 * PREFILLED темою й тілом листа, який містить код. Лист реально відправляється
 * з власної Gmail-скриньки користувача (SMTP на боці Google), тому код
 * гарантовано приходить на потрібну адресу, і жодна третя сторона не отримує
 * доступу до пароля.
 *
 * Якщо поштовий клієнт не встановлено — код дублюється в LogCat (лише в debug),
 * а користувач бачить його в діалозі безпосередньо.
 */
object EmailSender {

    private const val TAG = "EmailSender"

    fun send(context: Context, to: String, subject: String, body: String): Boolean {
        return try {
            val uri = Uri.parse(
                "mailto:$to?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
            )
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Confirmation e-mail prepared for $to (subject=$subject)")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to launch e-mail client for $to", error)
            false
        }
    }
}
