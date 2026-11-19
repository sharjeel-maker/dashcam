package com.example.dashcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class DashcamControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = when (intent?.action) {
            "com.example.dashcam.ACTION_START_RECORDING" -> "START"
            "com.example.dashcam.ACTION_STOP_RECORDING" -> "STOP"
            else -> return
        }
        val serviceIntent = Intent(context, DashcamService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
