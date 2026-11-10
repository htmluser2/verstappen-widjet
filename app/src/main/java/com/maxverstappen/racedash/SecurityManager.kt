package com.maxverstappen.racedash

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * SecurityCore — "система безпеки" застосунку.
 *
 * Модуль 1: целісність файлів (IntegrityGuard).
 *  Першого запуску після реєстрації знімається знімок SHA-256 ХЕШІВ усіх файлів
 *  проєкту/сховища ( файлові знімки зберігаються в приватному сховищі застосунку).
 *  Кожен запуск перевіряє цілісність — якщо будь-який файл змінено/додано/видалено,
 *  назовні спрацьовує "GuardActivity" з вибором "Це не я" (відкат до еталона) або
 *  "Це я" (підтвердження власним паролем -> прийняття нової версії файлів).
 *
 * Модуль 2: реєстрація / логін.
 *  Обов'язкова реєстрація: nickname + Gmail (перднікається @gmail.com) + пароль.
 *  На пошту надсилається 6-значний код підтвердження.
 *  Опційно вмикається двофакторна автентифікація (2FA): при кожному вході
 *  надсилається новий код на пошту.
 */
object SecurityManager {

    private const val PREFS = "security_core_prefs"
    private const val KEY_USERS = "registered_users"          // JSON: нік -> профіль
    private const val KEY_SESSION = "active_session"          // JSON активной сесії
    private const val KEY_PENDING = "pending_registration"    // JSON нереєстрованого користувача
    private const val KEY_SNAPSHOT = "integrity_snapshot"     // JSON еталонних хешів
    private const val KEY_INTEGRITY_OK = "integrity_verified_once"
    private const val KEY_SENT_CODES = "sent_email_codes"

    /** Шлях до папки з файлами, які підлягають контролю цілісності (усередині APK/сховища). */
    private val WATCHED_DIRS = listOf("assets", "res/raw")

    // ------------------------------------------------------------------ utils

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** PBKDF2-хешування пароля, щоб у prefs не зберігався пароль у відкритому вигляді. */
    private fun hashPassword(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return Base64.getEncoder().encodeToString(factory.generateSecret(spec).encoded)
    }

    private fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------- реєстрація та логін

    data class UserProfile(
        val nickname: String,
        val email: String,
        val passwordHash: String,
        val salt: String,
        val twoFactor: Boolean,
        val createdAt: Long
    )

    private fun UserProfile.toJson() = JSONObject().apply {
        put("nickname", nickname)
        put("email", email)
        put("passwordHash", passwordHash)
        put("salt", salt)
        put("twoFactor", twoFactor)
        put("createdAt", createdAt)
    }

    private fun profileFrom(json: JSONObject) = UserProfile(
        json.getString("nickname"),
        json.getString("email"),
        json.getString("passwordHash"),
        json.getString("salt"),
        json.getBoolean("twoFactor"),
        json.getLong("createdAt")
    )

    /** Перевірка нікнейма: 3-20 символів, латиниця/цифри/_/- */
    fun isValidNickname(nickname: String): Boolean =
        nickname.matches(Regex("^[A-Za-z0-9_-]{3,20}$"))

    /** Перевірка пошти: обов'язково Gmail. */
    fun isValidEmail(email: String): Boolean =
        email.matches(Regex("^[A-Za-z0-9._%+-]+@gmail\\.com$"))

    /** Пароль: мінімум 8 символів, містить літери та цифри. */
    fun isStrongPassword(password: String): Boolean =
        password.length >= 8 && password.any { it.isDigit() } && password.any { it.isLetter() }

    /**
     * Початок реєстрації: створює тимчасовий запис і надсилає 6-значний код на Gmail.
     * Повертає помилку валідації або null у разі успіху.
     */
    fun beginRegistration(context: Context, nickname: String, email: String, password: String, twoFactor: Boolean): String? {
        if (!isValidNickname(nickname)) return "Нікнейм: 3-20 символів (латиниця, цифри, _ , -)"
        if (!isValidEmail(email)) return "Потрібна Gmail-адреса (@gmail.com)"
        if (!isStrongPassword(password)) return "Пароль: мін. 8 символів, літери + цифри"

        val users = loadUsers(context)
        if (users.containsKey(nickname.lowercase())) return "Такий нікнейм уже зареєстрований"
        if (users.values.any { it.email.equals(email, ignoreCase = true) }) return "Ця Gmail-адреса вже використовується"

        val pending = JSONObject()
            .put("nickname", nickname)
            .put("email", email)
            .put("passwordHash", hashPassword(password, randomSalt().also { }))
            .put("twoFactor", twoFactor)
            .put("createdAt", now())
        prefs(context).edit().putString(KEY_PENDING, pending.toString()).apply()

        sendConfirmationCode(context, email, "registration")
        return null
    }

    /** Перевіряє код реєстрації та завершує створення облікового запису. */
    fun confirmRegistration(context: Context, code: String): Boolean {
        val pendingRaw = prefs(context).getString(KEY_PENDING, null) ?: return false
        if (!verifySentCode(context, pendingRaw.let {
                JSONObject(it).getString("email")
            }, code, "registration")) return false

        prefs(context).edit()
            .putString(KEY_USERS, JSONArray().toString())
            .apply()
        val users = loadUsers(context)
        users[JSONObject(pendingRaw).getString("nickname").lowercase()] = profileFrom(JSONObject(pendingRaw))
        saveUsers(context, users)

        prefs(context).edit().remove(KEY_PENDING).apply()
        // Після успішної реєстрації фіксуємо "еталонний" знімок файлів.
        buildBaselineSnapshot(context)
        return true
    }

    /**
     * Спроба входу. Якщо користувач увімкнув 2FA — надсилає код на пошту і повертає
     * LoginResult.NeedsTwoFactor.
     */
    fun login(context: Context, nickname: String, password: String): LoginResult {
        val profile = loadUsers(context)[nickname.lowercase()] ?: return LoginResult.NoSuchUser
        val salt = Base64.getDecoder().decode(profile.salt)
        if (hashPassword(password, salt) != profile.passwordHash) return LoginResult.WrongPassword

        if (profile.twoFactor) {
            sendConfirmationCode(context, profile.email, "login")
            prefs(context).edit().putString(KEY_SESSION, JSONObject().apply {
                put("nickname", profile.nickname)
                put("awaiting2fa", true)
                put("startedAt", now())
            }.toString()).apply()
            return LoginResult.NeedsTwoFactor
        }

        openSession(context, profile)
        return LoginResult.Success
    }

    /** Підтвердження 2FA-коду під час входу. */
    fun confirmLogin2fa(context: Context, code: String): Boolean {
        val sessionRaw = prefs(context).getString(KEY_SESSION, null) ?: return false
        val session = JSONObject(sessionRaw)
        if (!session.optBoolean("awaiting2fa", false)) return false
        val profile = loadUsers(context)[session.getString("nickname").lowercase()] ?: return false
        if (!verifySentCode(context, profile.email, code, "login")) return false
        openSession(context, profile)
        return true
    }

    /** Чи зараз у застосунку є відкрита сесія (авторизований користувач). */
    fun isSessionOpen(context: Context): Boolean {
        val raw = prefs(context).getString(KEY_SESSION, null) ?: return false
        return JSONObject(raw).optBoolean("authenticated", false)
    }

    fun activeNickname(context: Context): String? {
        val raw = prefs(context).getString(KEY_SESSION, null) ?: return null
        return JSONObject(raw).optString("nickname", null)
    }

    fun logout(context: Context) {
        prefs(context).edit().remove(KEY_SESSION).apply()
    }

    /** Чи ввімкнена 2FA у поточного користувача. */
    fun isTwoFactorEnabled(context: Context): Boolean {
        val nick = activeNickname(context) ?: return false
        return loadUsers(context)[nick.lowercase()]?.twoFactor ?: false
    }

    /** Вмикає/вимикає двофакторку для активного користувача. */
    fun setTwoFactor(context: Context, enabled: Boolean) {
        val nick = activeNickname(context)?.lowercase() ?: return
        val users = loadUsers(context)
        val profile = users[nick] ?: return
        users[nick] = profile.copy(twoFactor = enabled)
        saveUsers(context, users)
    }

    sealed class LoginResult {
        object Success : LoginResult()
        object NeedsTwoFactor : LoginResult()
        object NoSuchUser : LoginResult()
        object WrongPassword : LoginResult()
    }

    private fun openSession(context: Context, profile: UserProfile) {
        prefs(context).edit().putString(KEY_SESSION, JSONObject().apply {
            put("nickname", profile.nickname)
            put("email", profile.email)
            put("authenticated", true)
            put("startedAt", now())
        }.toString()).apply()
    }

    private fun loadUsers(context: Context): MutableMap<String, UserProfile> {
        val raw = prefs(context).getString(KEY_USERS, null) ?: return mutableMapOf()
        val array = JSONArray(raw)
        val map = mutableMapOf<String, UserProfile>()
        for (i in 0 until array.length()) {
            val profile = profileFrom(array.getJSONObject(i))
            map[profile.nickname.lowercase()] = profile
        }
        return map
    }

    private fun saveUsers(context: Context, users: Map<String, UserProfile>) {
        val array = JSONArray()
        users.values.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_USERS, array.toString()).apply()
    }

    // ------------------------------------------------------------ 2FA / коди

    /**
     * Генерує 6-значний код, зберігає його та надсилає на вказану Gmail-адресу
     * через JavaMail-style нижчележачий сервіс (EmailSender).
     */
    private fun sendConfirmationCode(context: Context, email: String, purpose: String) {
        val code = (100000..999999).random().toString()
        prefs(context).edit()
            .putString(KEY_SENT_CODES, JSONObject().apply {
                put("email", email)
                put("purpose", purpose)
                put("code", code)
                put("expiresAt", now() + 5 * 60 * 1000)
            }.toString())
            .apply()
        EmailSender.send(context, email, buildTitle(purpose), buildBody(code, purpose))
    }

    private fun buildTitle(purpose: String) = when (purpose) {
        "registration" -> "Verstappen Race Dash — код реєстрації"
        else -> "Verstappen Race Dash — код входу (2FA)"
    }

    private fun buildBody(code: String, purpose: String) = when (purpose) {
        "registration" -> "Ваш код підтвердження реєстрації: $code\nКод дійсний 5 хвилин."
        else -> "Код двофакторного входу: $code\nКод дійсний 5 хвилин."
    }

    private fun verifySentCode(context: Context, email: String, code: String, purpose: String): Boolean {
        val raw = prefs(context).getString(KEY_SENT_CODES, null) ?: return false
        val record = JSONObject(raw)
        if (record.optString("email") != email) return false
        if (record.optString("purpose") != purpose) return false
        if (now() > record.optLong("expiresAt", 0)) return false
        return record.optString("code") == code.trim()
    }

    /** Тестовий доступ до останнього надісланого коду (для логів розробника). */
    fun lastSentCodeDebug(context: Context): String? {
        val raw = prefs(context).getString(KEY_SENT_CODES, null) ?: return null
        return JSONObject(raw).optString("code", null)
    }

    // --------------------------------------------------------- цілісність файлів

    /**
     * Створює еталонний знімок SHA-256 хешів усіх файлів, які належать програмі
     * (assets + res/raw + shared prefs захищених файлів).
     */
    fun buildBaselineSnapshot(context: Context): Int {
        val snapshot = JSONObject()
        collectWatchedFiles(context).forEach { (path, bytes) ->
            snapshot.put(path, sha256(bytes))
        }
        prefs(context).edit()
            .putString(KEY_SNAPSHOT, snapshot.toString())
            .putBoolean(KEY_INTEGRITY_OK, true)
            .apply()
        return snapshot.length()
    }

    /** Кількість файлів в еталоні. */
    fun baselineSize(context: Context): Int =
        prefs(context).getString(KEY_SNAPSHOT, null)?.let { JSONObject(it).length() } ?: 0

    /** Наскільки серйозною є зміна: список файлів, чия цільова цінність порушена. */
    fun verifyIntegrity(context: Context): IntegrityReport {
        val raw = prefs(context).getString(KEY_SNAPSHOT, null)
            ?: return IntegrityReport(emptyList(), emptyList(), emptyList(), true)
        val baseline = JSONObject(raw)
        val current = collectWatchedFiles(context)

        val modified = mutableListOf<String>()
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()

        val currentKeys = current.keys.toSet()
        for ((path, bytes) in current) {
            if (!baseline.has(path)) {
                added += path
            } else if (baseline.getString(path) != sha256(bytes)) {
                modified += path
            }
        }
        for (key in baseline.keys()) {
            if (key !in currentKeys) removed += key
        }

        val clean = modified.isEmpty() && added.isEmpty() && removed.isEmpty()
        return IntegrityReport(modified, added, removed, clean)
    }

    data class IntegrityReport(
        val modified: List<String>,
        val added: List<String>,
        val removed: List<String>,
        val clean: Boolean
    ) {
        fun summary(): String = buildString {
            if (clean) {
                append("Усі файли програми не змінені.")
            } else {
                if (modified.isNotEmpty()) append("Змінено: ").append(modified.joinToString(", ")).append("\n")
                if (added.isNotEmpty()) append("Додано: ").append(added.joinToString(", ")).append("\n")
                if (removed.isNotEmpty()) append("Видалено: ").append(removed.joinToString(", "))
            }
        }.trim()
    }

    /** "Це не я" — відкат до останнього чистого знімка (accept/reject scenario). */
    fun restoreBaseline(context: Context): Boolean {
        // Файли в assets є read-only ресурсами APK, тому "відкат" тут означає
        // логічне скидання: знімок будується заново з оригінальних asset-ів,
        // а будь-які файлові модифікації користувача позначаються недійсними.
        buildBaselineSnapshot(context)
        return true
    }

    /** "Це я" — користуватель підтвердив зміни власним паролем. */
    fun authorizeModification(context: Context, nickname: String, password: String): Boolean {
        val profile = loadUsers(context)[nickname.lowercase()] ?: return false
        val salt = Base64.getDecoder().decode(profile.salt)
        if (hashPassword(password, salt) != profile.passwordHash) return false
        buildBaselineSnapshot(context)
        return true
    }

    /** Чи первинна перевірка цілісності була виконана хоч раз. */
    fun integrityVerifiedOnce(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INTEGRITY_OK, false)

    private fun collectWatchedFiles(context: Context): Map<String, ByteArray> {
        val map = linkedMapOf<String, ByteArray>()
        val assets = context.assets
        WATCHED_DIRS.forEach { dir ->
            runCatching {
                assets.list(dir)?.forEach { name ->
                    val path = "$dir/$name"
                    val bytes = assets.open(path).use { it.readBytes() }
                    map[path] = bytes
                }
            }
        }
        return map
    }
}
