package com.simplemobiletools.smsmessenger2.interfaces

import androidx.room.Dao
import androidx.room.Query
import com.simplemobiletools.smsmessenger2.models.Attachment

@Dao
interface AttachmentsDao {
    @Query("SELECT * FROM attachments")
    fun getAll(): List<Attachment>
}
