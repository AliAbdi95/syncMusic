package com.syncmusic.app.network

import com.google.gson.Gson

/**
 * پروتکل ساده پیام‌ها بین Host و Client
 */
object SyncMessages {
    const val TYPE_JOIN = "join"
    const val TYPE_JOIN_ACK = "join_ack"
    const val TYPE_READY = "ready"
    const val TYPE_PLAY = "play"
    const val TYPE_STOP = "stop"
    const val TYPE_TIME_SYNC = "time_sync"
    const val TYPE_TIME_SYNC_REPLY = "time_sync_reply"
    const val TYPE_SONG_URL = "song_url"          // قدیمی (fallback)
    const val TYPE_FILE_META = "file_meta"        // شروع ارسال فایل
    const val TYPE_FILE_DONE = "file_done"        // پایان ارسال فایل
    const val TYPE_REQUEST_FILE = "request_file"  // کلاینت درخواست فایل می‌کند
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"

    data class Message(
        val type: String,
        val payload: Map<String, Any?> = emptyMap()
    )

    private val gson = Gson()

    fun toJson(msg: Message): String = gson.toJson(msg)

    fun fromJson(json: String): Message? {
        return try {
            gson.fromJson(json, Message::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
