package com.maxverstappen.racedash

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * SecurityGateActivity — повноекранний "замок" застосунку.
 *
 * Відображається перед кожним входом у MainActivity, якщо:
 *   1) користувач ще не зареєстрований  -> форма реєстрації
 *      (нікнейм + Gmail + пароль, опційно 2FA);
 *   2) користувач зареєстрований, але не авторизований -> форма входу
 *      (+ 2FA-код, якщо увімкнено двофакторку).
 *
 * І тільки коли сесія відкрита, продовжується звичайний запуск застосунку.
 */
class SecurityGateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val registered = SecurityManager.baselineSize(this) > 0 ||
            SecurityManager.integrityVerifiedOnce(this)

        setContentView(buildUi(registered))
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(registered: Boolean): View {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val title = TextView(this).apply {
            text = "🛡 Verstappen Race Dash\nСистема безпеки"
            textSize = 22f
            setPadding(pad, pad, pad, pad / 2)
            setTextColor(0xFFFFFFFF.toInt())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050913.toInt())
        }
        container.addView(title)

        if (!registered) {
            container.addView(buildRegisterForm(pad))
        } else {
            container.addView(buildLoginForm(pad))
        }
        return container
    }

    private fun field(hint: String, isPassword: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setPadding(28, 24, 28, 24)
        setSingleLine(true)
        if (isPassword) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    // -------------------------------------------------- реєстрація

    private fun buildRegisterForm(pad: Int): View {
        val info = TextView(this).apply {
            text = "Перший запуск. Створіть обліковий запис —\nбез реєстрації застосунок не відкриється."
            setPadding(pad, 0, pad, pad / 2)
        }
        val nick = field("Нікнейм")
        val email = field("Gmail (обов'язково @gmail.com)")
        val password = field("Пароль (мін. 8, літери + цифри)", isPassword = true)
        val twoFactorRow = android.widget.CheckBox(this).apply {
            text = "Увімкнути двофакторну автентифікацію (2FA)"
            setPadding(pad, 8, pad, 8)
        }
        val codeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val code = field("6-значний код з Gmail")
        val message = TextView(this).apply { setPadding(pad, 12, pad, 0) }

        codeBox.addView(code)
        codeBox.addView(button("Підтвердити код") {
            if (SecurityManager.confirmRegistration(this, code.text.toString())) {
                Toast.makeText(this, "Акаунт створено ✔", Toast.LENGTH_SHORT).show()
                proceedToApp()
            } else {
                message.text = "Невірний або прострочений код. Спробуйте ще раз."
            }
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(nick); addView(email); addView(password); addView(twoFactorRow)
            addView(button("Зареєструватися") {
                val error = SecurityManager.beginRegistration(
                    this@SecurityGateActivity,
                    nick.text.toString().trim(),
                    email.text.toString().trim(),
                    password.text.toString(),
                    twoFactorRow.isChecked
                )
                if (error != null) {
                    message.text = error
                } else {
                    message.text = "Код надіслано на вашу Gmail-скриньку.\nВідкрийте лист і введіть код нижче."
                    codeBox.visibility = View.VISIBLE
                }
            })
            addView(codeBox)
            addView(message)
        }
        return root
    }

    // -------------------------------------------------- логін

    private fun buildLoginForm(pad: Int): View {
        val info = TextView(this).apply {
            text = "Увійдіть, щоб продовжити."
            setPadding(pad, 0, pad, pad / 2)
        }
        val nick = field("Нікнейм")
        val password = field("Пароль", isPassword = true)
        val twoFaBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val twoFaCode = field("6-значний 2FA-код з Gmail")
        val message = TextView(this).apply { setPadding(pad, 12, pad, 0) }

        twoFaBox.addView(twoFaCode)
        twoFaBox.addView(button("Підтвердити 2FA") {
            if (SecurityManager.confirmLogin2fa(this@SecurityGateActivity, twoFaCode.text.toString())) {
                proceedToApp()
            } else {
                message.text = "Невірний 2FA-код."
            }
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(nick); addView(password)
            addView(button("Увійти") {
                when (SecurityManager.login(
                    this@SecurityGateActivity,
                    nick.text.toString().trim(),
                    password.text.toString()
                )) {
                    is SecurityManager.LoginResult.Success -> proceedToApp()
                    is SecurityManager.LoginResult.NeedsTwoFactor -> {
                        message.text = "На вашу Gmail-адресу надіслано 2FA-код."
                        twoFaBox.visibility = View.VISIBLE
                    }
                    is SecurityManager.LoginResult.NoSuchUser -> message.text = "Користувача не знайдено."
                    is SecurityManager.LoginResult.WrongPassword -> message.text = "Невірний пароль."
                }
            })
            addView(twoFaBox)
            addView(message)
        }
        return root
    }

    // -------------------------------------------------- перехід у застосунок

    private fun proceedToApp() {
        val report = SecurityManager.verifyIntegrity(this)
        if (!report.clean) {
            showTamperDialog(report)
            return
        }
        startActivity(android.content.Intent(this, MainActivity::class.java))
        finish()
    }

    // -------------------------------------------------- діалог "зміни помічено"

    /**
     * Систему безпеки спрацювала: хтось змінив файли програми.
     * Даємо два варіанти:
     *   • «Це НЕ я» — відкидає зміни, повертає еталонну версію файлів;
     *   • «Це Я» — підтверджує зміни лише після введення власного пароля.
     */
    private fun showTamperDialog(report: SecurityManager.IntegrityReport) {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050913.toInt())
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "⚠️ Виявлено зміни у файлах програми!\n\n${report.summary()}\n\nЦе зробили ви?"
            textSize = 16f
            setTextColor(0xFFFFD54F.toInt())
        }
        val password = field("Ваш пароль для підтвердження", isPassword = true)
        val nick = TextView(this).apply {
            text = "Акаунт: ${SecurityManager.activeNickname(this@SecurityGateActivity)}"
            setPadding(0, 12, 0, 12)
        }
        password.visibility = View.GONE
        nick.visibility = View.GONE

        container.addView(title)
        container.addView(nick)
        container.addView(password)
        container.addView(button("🚫 Це не я — скасувати зміни") {
            SecurityManager.restoreBaseline(this)
            Toast.makeText(this, "Файли відновлено до еталонної версії.", Toast.LENGTH_LONG).show()
            proceedToApp()
        })
        container.addView(button("✅ Це я — ввести пароль") {
            password.visibility = View.VISIBLE
            nick.visibility = View.VISIBLE
        })
        container.addView(button("Підтвердити паролем") {
            val ok = SecurityManager.authorizeModification(
                this,
                SecurityManager.activeNickname(this) ?: "",
                password.text.toString()
            )
            if (ok) {
                Toast.makeText(this, "Зміни прийнято як власні.", Toast.LENGTH_LONG).show()
                startActivity(android.content.Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "Невірний пароль. Зміни НЕ прийнято.", Toast.LENGTH_LONG).show()
            }
        })
        setContentView(container)
    }
}
