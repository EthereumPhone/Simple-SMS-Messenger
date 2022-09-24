package com.simplemobiletools.smsmessenger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RestartServiceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val intent = Intent(context, XMTPListenService::class.java)
        context.startService(intent)
    }
}
