package com.simplemobiletools.smsmessenger2.extensions

import android.text.TextUtils
import com.simplemobiletools.commons.models.SimpleContact

fun ArrayList<SimpleContact>.getThreadTitle() = TextUtils.join(", ", map { it.name }.toTypedArray())
