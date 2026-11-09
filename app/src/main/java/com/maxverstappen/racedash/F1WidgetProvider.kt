package com.maxverstappen.racedash

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import org.json.JSONException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

abstract class F1WidgetProvider : AppWidgetProvider() {
    protected abstract val widgetLayout: Int

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { renderWidget(context, manager, it, widgetLayout) }
        MainActivity.schedulePeriodicCalendarRefresh(context)
        MainActivity.requestCalendarRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        @Suppress("UNUSED_PARAMETER") newOptions: Bundle
    ) {
        renderWidget(context, manager, appWidgetId, widgetLayout)
    }

    companion object {
        private val kyivZone = ZoneId.of("Europe/Kyiv")
        private val dayStamp = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("uk-UA"))
        private val scheduleDay = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("uk-UA"))
        private const val SAO_PAULO_CIRCUIT_KEY = 14
        private data class ScheduleRow(
            val container: Int,
            val day: Int,
            val session: Int,
            val time: Int
        )
        private val scheduleRows = listOf(
            ScheduleRow(R.id.widget_schedule_row_1, R.id.widget_schedule_day_1, R.id.widget_schedule_session_1, R.id.widget_schedule_time_1),
            ScheduleRow(R.id.widget_schedule_row_2, R.id.widget_schedule_day_2, R.id.widget_schedule_session_2, R.id.widget_schedule_time_2),
            ScheduleRow(R.id.widget_schedule_row_3, R.id.widget_schedule_day_3, R.id.widget_schedule_session_3, R.id.widget_schedule_time_3),
            ScheduleRow(R.id.widget_schedule_row_4, R.id.widget_schedule_day_4, R.id.widget_schedule_session_4, R.id.widget_schedule_time_4),
            ScheduleRow(R.id.widget_schedule_row_5, R.id.widget_schedule_day_5, R.id.widget_schedule_session_5, R.id.widget_schedule_time_5)
        )
        private val widgetProviders = listOf(
            CompactF1WidgetProvider::class.java to R.layout.widget_small,
            WideF1WidgetProvider::class.java to R.layout.widget_wide,
            LargeF1WidgetProvider::class.java to R.layout.widget_large,
            WeekendScheduleWidgetProvider::class.java to R.layout.widget_schedule
        )

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            widgetProviders.forEach { (providerClass, layoutId) ->
                val component = ComponentName(context, providerClass)
                manager.getAppWidgetIds(component).forEach { widgetId ->
                    renderWidget(context, manager, widgetId, layoutId)
                }
            }
        }

        private fun renderWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            layoutId: Int
        ) {
            val preferences = context.getSharedPreferences(
                CalendarRefreshWorker.PREFERENCES,
                Context.MODE_PRIVATE
            )
            val rawSchedule = preferences.getString(CalendarRefreshWorker.KEY_SCHEDULE, "[]") ?: "[]"
            val sessions = try {
                RaceCalendar.parse(rawSchedule)
            } catch (error: JSONException) {
                Log.e("F1WidgetProvider", "Could not read cached F1 schedule", error)
                emptyList()
            }
            val views = RemoteViews(context.packageName, layoutId)
            views.setImageViewResource(R.id.widget_helmet, R.drawable.helmet)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    widgetId,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val now = Instant.now()
            val race = RaceCalendar.nextRace(sessions, now)
            if (race == null) {
                val hasCalendar = sessions.isNotEmpty()
                views.setTextViewText(
                    R.id.widget_race,
                    if (hasCalendar) "NEXT GP PENDING" else "CALENDAR OFFLINE"
                )
                views.setTextViewText(
                    R.id.widget_country,
                    if (hasCalendar) "Next season date not published" else "Calendar unavailable"
                )
                views.setTextViewText(R.id.widget_circuit, "No date has been assumed")
                views.setTextViewText(R.id.widget_session, if (hasCalendar) "SEASON BREAK" else "NO DATA")
                views.setTextViewText(R.id.widget_status, if (hasCalendar) "FINISHED" else "OFFLINE")
                views.setTextColor(
                    R.id.widget_status,
                    if (hasCalendar) Color.rgb(169, 181, 197) else Color.rgb(242, 201, 76)
                )
                views.setTextViewText(R.id.widget_countdown, "—")
                views.setViewVisibility(R.id.widget_track, View.GONE)
                views.setViewVisibility(R.id.widget_gp_photo, View.GONE)
                views.setViewVisibility(R.id.widget_photo_scrim, View.GONE)
                views.setViewVisibility(R.id.widget_photo_label, View.GONE)
                views.setViewVisibility(R.id.widget_helmet, View.VISIBLE)
                views.setTextViewText(R.id.widget_updated, "KYIV TIME · LIVE CALENDAR")
                views.setTextViewText(
                    R.id.widget_schedule,
                    if (hasCalendar) "Waiting for the next published calendar" else "Waiting for the race calendar"
                )
                if (layoutId == R.layout.widget_small) {
                    views.setViewVisibility(R.id.widget_circuit, View.GONE)
                    views.setViewVisibility(R.id.widget_updated, View.GONE)
                }
                if (layoutId == R.layout.widget_schedule) {
                    renderWeekendSchedule(views, emptyList(), now)
                }
            } else {
                val weekend = RaceCalendar.weekendSessions(sessions, race)
                val target = RaceCalendar.nearestSession(weekend, now)
                val country = race.countryName.uppercase(Locale.ROOT)
                val isSaoPaulo = race.circuitKey == SAO_PAULO_CIRCUIT_KEY
                if (isSaoPaulo) {
                    views.setImageViewResource(R.id.widget_gp_photo, R.drawable.saopaulo_2019_win)
                    views.setInt(R.id.widget_gp_photo, "setImageAlpha", 190)
                    views.setViewVisibility(R.id.widget_gp_photo, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_photo_scrim, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_photo_label, View.GONE)
                    views.setViewVisibility(R.id.widget_helmet, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_gp_photo, View.GONE)
                    views.setViewVisibility(R.id.widget_photo_scrim, View.GONE)
                    views.setViewVisibility(R.id.widget_photo_label, View.GONE)
                    views.setViewVisibility(R.id.widget_helmet, View.VISIBLE)
                }
                views.setTextViewText(R.id.widget_race, "${race.location.uppercase(Locale.ROOT)} GP")
                views.setTextViewText(R.id.widget_country, country)
                views.setTextViewText(R.id.widget_circuit, race.circuitName)
                val updatedAt = preferences.getLong(CalendarRefreshWorker.KEY_UPDATED_AT, 0L)
                views.setTextViewText(
                    R.id.widget_updated,
                    if (isSaoPaulo) {
                        "BRAZIL 2019 ARCHIVE"
                    } else {
                        updatedAt.takeIf { it > 0L }
                            ?.let {
                                "SYNC ${dayStamp.format(Instant.ofEpochMilli(it).atZone(kyivZone))}"
                            }
                            ?: "LIVE CALENDAR"
                    }
                )

                if (target != null) {
                    val status = RaceCalendar.status(target, now)
                    views.setTextViewText(R.id.widget_session, RaceCalendar.sessionCode(target.sessionName))
                    views.setTextViewText(R.id.widget_status, status)
                    views.setTextColor(
                        R.id.widget_status,
                        when (status) {
                            "LIVE" -> Color.rgb(255, 104, 120)
                            "FINISHED" -> Color.rgb(169, 181, 197)
                            else -> Color.rgb(120, 186, 255)
                        }
                    )
                    views.setTextViewText(
                        R.id.widget_countdown,
                        when (status) {
                            "LIVE" -> "LIVE NOW"
                            "FINISHED" -> "SESSION FINISHED"
                            else -> formatCountdown(Duration.between(now, target.start))
                        }
                    )
                } else {
                    views.setTextViewText(R.id.widget_session, "RACE WEEKEND")
                    views.setTextViewText(R.id.widget_status, "UPCOMING")
                    views.setTextViewText(R.id.widget_countdown, RaceCalendar.kyivTime(race.start))
                }

                val minimumHeight = manager.getAppWidgetOptions(widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                val scheduleText = formatWeekendSchedule(weekend, now, layoutId, minimumHeight)
                views.setTextViewText(R.id.widget_schedule, scheduleText)
                if (layoutId == R.layout.widget_small) {
                    views.setViewVisibility(R.id.widget_circuit, View.GONE)
                    views.setViewVisibility(R.id.widget_updated, View.GONE)
                }
                if (layoutId == R.layout.widget_schedule) {
                    renderWeekendSchedule(views, weekend, now)
                }
                val track = preferences.getString(
                    CalendarRefreshWorker.trackKey(race.circuitKey),
                    null
                )
                val image = track?.let { TrackMapRenderer.render(it, 420, 180) }
                if (image != null) {
                    views.setImageViewBitmap(R.id.widget_track, image)
                    views.setViewVisibility(R.id.widget_track, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_track, View.GONE)
                }
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun formatWeekendSchedule(
            weekend: List<F1Session>,
            now: Instant,
            layoutId: Int,
            widgetHeight: Int
        ): String {
            val upcoming = weekend.filter { RaceCalendar.status(it, now) != "FINISHED" }
            val sessions = when (layoutId) {
                R.layout.widget_small -> (if (upcoming.isEmpty()) weekend.takeLast(2) else upcoming.take(2))
                R.layout.widget_wide -> {
                    val visibleCount = if (widgetHeight >= 150) 5 else 2
                    (if (upcoming.isEmpty()) weekend.takeLast(visibleCount) else upcoming.take(visibleCount))
                }
                else -> weekend
            }
            val entries = sessions.map { session ->
                val day = scheduleDay.format(session.start.atZone(kyivZone))
                    .uppercase(Locale.forLanguageTag("uk-UA"))
                if (layoutId == R.layout.widget_small) {
                    "$day  ·  ${RaceCalendar.sessionCode(session.sessionName)}  ${RaceCalendar.kyivTime(session.start)}"
                } else {
                    "$day   ·   ${RaceCalendar.localizedSessionName(session.sessionName)}   ·   ${RaceCalendar.kyivTime(session.start)}"
                }
            }
            return entries.joinToString("\n")
        }

        private fun renderWeekendSchedule(
            views: RemoteViews,
            weekend: List<F1Session>,
            now: Instant
        ) {
            val selected = weekend.firstOrNull { RaceCalendar.status(it, now) != "FINISHED" }
            scheduleRows.forEachIndexed { index, row ->
                val session = weekend.getOrNull(index)
                if (session == null) {
                    views.setViewVisibility(row.container, View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(row.container, View.VISIBLE)
                val status = RaceCalendar.status(session, now)
                val isHighlighted = session == selected
                val sessionColor = when {
                    status == "LIVE" -> Color.rgb(255, 104, 120)
                    isHighlighted -> Color.rgb(242, 201, 76)
                    status == "FINISHED" -> Color.rgb(112, 128, 148)
                    else -> Color.rgb(231, 238, 248)
                }
                views.setTextViewText(
                    row.day,
                    scheduleDay.format(session.start.atZone(kyivZone)).uppercase(Locale.forLanguageTag("uk-UA"))
                )
                views.setTextViewText(row.session, RaceCalendar.localizedSessionName(session.sessionName).uppercase(Locale.forLanguageTag("uk-UA")))
                views.setTextViewText(row.time, RaceCalendar.kyivTime(session.start))
                views.setTextColor(row.day, if (isHighlighted) sessionColor else Color.rgb(139, 158, 182))
                views.setTextColor(row.session, sessionColor)
                views.setTextColor(row.time, sessionColor)
                views.setInt(
                    row.container,
                    "setBackgroundResource",
                    if (isHighlighted) R.drawable.widget_schedule_row_active else R.drawable.widget_schedule_row
                )
            }
            selected?.let { views.setTextViewText(R.id.widget_countdown, RaceCalendar.kyivTime(it.start)) }
        }

        private fun formatCountdown(duration: Duration): String {
            val totalMinutes = duration.seconds.coerceAtLeast(0) / 60
            val days = totalMinutes / (24 * 60)
            val hours = (totalMinutes / 60) % 24
            val minutes = totalMinutes % 60
            return if (days > 0) {
                "%02dd %02dh %02dm".format(Locale.ROOT, days, hours, minutes)
            } else {
                "%02dh %02dm".format(Locale.ROOT, hours, minutes)
            }
        }
    }
}

class CompactF1WidgetProvider : F1WidgetProvider() {
    override val widgetLayout: Int = R.layout.widget_small
}

class WideF1WidgetProvider : F1WidgetProvider() {
    override val widgetLayout: Int = R.layout.widget_wide
}

class LargeF1WidgetProvider : F1WidgetProvider() {
    override val widgetLayout: Int = R.layout.widget_large
}

class WeekendScheduleWidgetProvider : F1WidgetProvider() {
    override val widgetLayout: Int = R.layout.widget_schedule
}
