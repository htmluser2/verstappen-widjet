package com.maxverstappen.racedash

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

object JolpicaSchedule {
    private data class SessionField(
        val name: String,
        val keys: List<String>,
        val durationMinutes: Long
    )

    private val sessionFields = listOf(
        SessionField("Practice 1", listOf("FirstPractice", "firstPractice"), 60),
        SessionField("Practice 2", listOf("SecondPractice", "secondPractice"), 60),
        SessionField("Practice 3", listOf("ThirdPractice", "thirdPractice"), 60),
        SessionField("Sprint Qualifying", listOf("SprintQualifying", "sprintQualifying"), 60),
        SessionField("Sprint", listOf("Sprint", "sprint"), 60),
        SessionField("Qualifying", listOf("Qualifying", "qualifying"), 60),
        SessionField("Race", emptyList(), 120)
    )

    @Throws(JSONException::class)
    fun toSessionJson(rawJson: String): String {
        val root = JSONObject(rawJson)
        val races = root.optJSONObject("MRData")
            ?.optJSONObject("RaceTable")
            ?.optJSONArray("Races")
            ?: throw JSONException("Jolpica response did not contain a race schedule")
        val sessions = JSONArray()

        for (raceIndex in 0 until races.length()) {
            val race = races.getJSONObject(raceIndex)
            val round = race.optInt("round")
            if (round <= 0) continue
            val meetingKey = race.optInt("season") * 100L + round
            val circuit = race.optJSONObject("Circuit") ?: JSONObject()
            val location = circuit.optJSONObject("Location") ?: JSONObject()
            val circuitId = circuit.optString("circuitId")
            val circuitKey = CircuitMapFiles.forErgastCircuitId(circuitId) ?: 0
            val meetingName = race.optString("raceName", "Grand Prix")

            for ((sessionIndex, field) in sessionFields.withIndex()) {
                val sessionData = if (field.name == "Race") {
                    race
                } else {
                    field.keys.firstNotNullOfOrNull { key -> race.optJSONObject(key) }
                        ?: continue
                }
                val date = sessionData.optString("date")
                val time = sessionData.optString("time")
                if (date.isBlank() || time.isBlank()) continue

                val start = Instant.parse("${date}T$time")
                val end = start.plus(Duration.ofMinutes(field.durationMinutes))
                sessions.put(
                    JSONObject()
                        .put("session_key", meetingKey * 10 + sessionIndex)
                        .put("meeting_key", meetingKey)
                        .put("circuit_key", circuitKey)
                        .put("session_name", field.name)
                        .put("session_type", if (field.name.startsWith("Practice")) "Practice" else field.name)
                        .put("country_name", location.optString("country", "Country unavailable"))
                        .put("location", location.optString("locality", meetingName.removeSuffix(" Grand Prix")))
                        .put("circuit_short_name", circuit.optString("circuitName", "Circuit unavailable"))
                        .put("date_start", start.toString())
                        .put("date_end", end.toString())
                )
            }
        }
        return sessions.toString()
    }
}
