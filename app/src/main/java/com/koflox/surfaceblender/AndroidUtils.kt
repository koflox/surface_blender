package com.koflox.surfaceblender

import android.util.Log

fun debugLog(tag: String, msg: String) {
    if (BuildConfig.DEBUG) Log.d(tag, msg)
}

fun debugLog(tag: String, msg: String, ex: Exception) {
    if (BuildConfig.DEBUG) Log.e(tag, msg, ex)
}