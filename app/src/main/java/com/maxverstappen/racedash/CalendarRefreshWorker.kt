package com.maxverstappen.racedash

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.net.ssl.HttpsURLConnection

class CalendarRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val year = LocalDate.now(ZoneOffset.UTC).year
            var scheduleJson = JolpicaSchedule.toSessionJson(
                requestText("https://api.jolpi.ca/ergast/f1/$year/races.json")
            )
            var sessions = RaceCalendar.parse(scheduleJson)
            val now = Instant.now()
            if (RaceCalendar.nextRace(sessions, now) == null) {
                try {
                    val nextSeasonJson = JolpicaSchedule.toSessionJson(
                        requestText("https://api.jolpi.ca/ergast/f1/${year + 1}/races.json")
                    )
                    val nextSeasonSessions = RaceCalendar.parse(nextSeasonJson)
                    if (RaceCalendar.nextRace(nextSeasonSessions, now) != null) {
                        val combined = JSONArray(scheduleJson)
                        val nextSeason = JSONArray(nextSeasonJson)
                        for (index in 0 until nextSeason.length()) {
                            combined.put(nextSeason.getJSONObject(index))
                        }
                        scheduleJson = combined.toString()
                        sessions = RaceCalendar.parse(scheduleJson)
                    }
                } catch (error: IOException) {
                    Log.i(TAG, "Next season schedule is not available yet", error)
                } catch (error: JSONException) {
                    Log.w(TAG, "Could not read the optional next season schedule", error)
                }
            }
            if (sessions.isEmpty()) {
                throw IOException("Jolpica returned no sessions for $year or the next season")
            }

            val preferences = applicationContext.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
            )
            preferences.edit()
                .putString(KEY_SCHEDULE, scheduleJson)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply()

            val race = RaceCalendar.nextRace(sessions, now)
            if (race != null) {
                val trackFile = CircuitMapFiles.forCircuitKey(race.circuitKey)
                if (trackFile != null) {
                    try {
                        val geoJson = requestText(
                            "https://raw.githubusercontent.com/SergioSediq/f1-circuits/main/circuits/$trackFile.geojson"
                        )
                        JSONObject(geoJson)
                        preferences.edit()
                            .putString(trackKey(race.circuitKey), geoJson)
                            .apply()
                    } catch (error: IOException) {
                        Log.w(TAG, "Track geometry refresh failed for ${race.circuitName}", error)
                    } catch (error: JSONException) {
                        Log.w(TAG, "Track geometry was invalid for ${race.circuitName}", error)
                    }
                }
            }

            F1WidgetProvider.refreshAll(applicationContext)
            Result.success()
        } catch (error: IOException) {
            Log.e(TAG, "Calendar refresh failed; WorkManager will retry", error)
            Result.retry()
        } catch (error: JSONException) {
            Log.e(TAG, "Jolpica calendar response was invalid; WorkManager will retry", error)
            Result.retry()
        }
    }

    private fun requestText(address: String): String {
        val connection = URL(address).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "VerstappenRaceDash/1.0")

            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} from $address")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "f1-calendar-periodic-refresh"
        const val ONCE_WORK_NAME = "f1-calendar-refresh"
        const val PREFERENCES = "race_calendar"
        const val KEY_SCHEDULE = "schedule_json"
        const val KEY_UPDATED_AT = "updated_at"
        const val TAG = "CalendarRefreshWorker"

        fun trackKey(circuitKey: Int): String = "track_geojson_$circuitKey"
    }
}
