package com.simplemobiletools.smsmessenger2.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simplemobiletools.commons.extensions.notificationManager
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.smsmessenger2.extensions.conversationsDB
import com.simplemobiletools.smsmessenger2.extensions.markThreadMessagesRead
import com.simplemobiletools.smsmessenger2.extensions.updateUnreadCountBadge
import com.simplemobiletools.smsmessenger2.helpers.MARK_AS_READ
import com.simplemobiletools.smsmessenger2.helpers.THREAD_ID
import com.simplemobiletools.smsmessenger2.helpers.refreshMessages

class MarkAsReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MARK_AS_READ -> {
                val threadId = intent.getLongExtra(THREAD_ID, 0L)
                context.notificationManager.cancel(threadId.hashCode())
                ensureBackgroundThread {
                    context.markThreadMessagesRead(threadId)
                    context.conversationsDB.markRead(threadId)
                    context.updateUnreadCountBadge(context.conversationsDB.getUnreadConversations())
                    refreshMessages()
                }
            }
        }
    }
}
