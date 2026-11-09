package com.maxverstappen.racedash

import org.json.JSONArray
import org.json.JSONException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class F1Session(
    val sessionKey: Long,
    val meetingKey: Long,
    val circuitKey: Int,
    val sessionName: String,
    val sessionType: String,
    val countryName: String,
    val location: String,
    val circuitName: String,
    val start: Instant,
    val end: Instant
)

object RaceCalendar {
    private val kyivZone = ZoneId.of("Europe/Kyiv")
    private val kyivTime = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    @Throws(JSONException::class)
    fun parse(rawJson: String): List<F1Session> {
        val entries = JSONArray(rawJson)
        val sessions = ArrayList<F1Session>(entries.length())
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            if (item.optBoolean("is_cancelled", false)) continue
            val start = parseTimestamp(item.getString("date_start"))
            val end = parseTimestamp(item.getString("date_end"))
            sessions.add(
                F1Session(
                    sessionKey = item.optLong("session_key"),
                    meetingKey = item.optLong("meeting_key"),
                    circuitKey = item.optInt("circuit_key"),
                    sessionName = item.optString("session_name", "Session"),
                    sessionType = item.optString("session_type", ""),
                    countryName = item.optString("country_name", "Country unavailable"),
                    location = item.optString("location", ""),
                    circuitName = item.optString("circuit_short_name", "Circuit unavailable"),
                    start = start,
                    end = end
                )
            )
        }
        return sessions.sortedBy(F1Session::start)
    }

    fun nextRace(sessions: List<F1Session>, now: Instant): F1Session? {
        val races = sessions.filter { it.sessionName.equals("Race", ignoreCase = true) }
        return races.firstOrNull { it.end.isAfter(now) }
    }

    fun weekendSessions(sessions: List<F1Session>, race: F1Session): List<F1Session> =
        sessions.filter { it.meetingKey == race.meetingKey }.sortedBy(F1Session::start)

    fun nearestSession(sessions: List<F1Session>, now: Instant): F1Session? {
        return sessions.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
            ?: sessions.firstOrNull { it.start.isAfter(now) }
            ?: sessions.lastOrNull()
    }

    fun status(session: F1Session, now: Instant): String = when {
        now.isBefore(session.start) -> "UPCOMING"
        now.isBefore(session.end) -> "LIVE"
        else -> "FINISHED"
    }

    fun kyivTime(instant: Instant): String = kyivTime.format(instant.atZone(kyivZone))

    fun sessionCode(sessionName: String): String = when (sessionName.lowercase(Locale.ROOT)) {
        "practice 1" -> "FP1"
        "practice 2" -> "FP2"
        "practice 3" -> "FP3"
        "sprint qualifying" -> "SPRINT QUALI"
        "sprint shootout" -> "SPRINT SHOOTOUT"
        "sprint" -> "SPRINT"
        "qualifying" -> "QUALI"
        "race" -> "RACE"
        else -> sessionName.uppercase(Locale.ROOT)
    }

    fun localizedSessionName(sessionName: String): String = when (sessionName.lowercase(Locale.ROOT)) {
        "practice 1" -> "Практика 1"
        "practice 2" -> "Практика 2"
        "practice 3" -> "Практика 3"
        "sprint qualifying" -> "Кваліфікація спринту"
        "sprint shootout" -> "Кваліфікація спринту"
        "sprint" -> "Спринт"
        "qualifying" -> "Кваліфікація"
        "race" -> "Гонка"
        else -> sessionName
    }

    @Throws(JSONException::class)
    private fun parseTimestamp(value: String): Instant {
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (error: DateTimeParseException) {
            throw JSONException("Invalid session timestamp: $value")
        }
    }
}
