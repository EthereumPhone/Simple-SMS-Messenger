package com.simplemobiletools.smsmessenger2.interfaces

import androidx.room.Dao
import androidx.room.Query
import com.simplemobiletools.smsmessenger2.models.MessageAttachment

@Dao
interface MessageAttachmentsDao {
    @Query("SELECT * FROM message_attachments")
    fun getAll(): List<MessageAttachment>
}
