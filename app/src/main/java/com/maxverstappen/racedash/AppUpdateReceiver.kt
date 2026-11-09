package com.maxverstappen.racedash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        F1WidgetProvider.refreshAll(context)
        MainActivity.schedulePeriodicCalendarRefresh(context)
        MainActivity.requestCalendarRefresh(context)
    }
}
