package com.syncmusic.app.network

/**
 * مدیریت offset ساعت بین دستگاه‌ها
 * Host = مرجع (offset = 0)
 * Client = offset = hostTime - localTime
 */
class SyncClock {

    @Volatile
    var offsetMs: Long = 0L
        private set

    fun updateOffset(hostTimeMs: Long, localReceiveTimeMs: Long, rttMs: Long = 0) {
        // تقریبی: نصف RTT رو کم می‌کنیم
        offsetMs = hostTimeMs - localReceiveTimeMs + (rttMs / 2)
    }

    /** زمان فعلی بر اساس ساعت Host */
    fun nowHostTime(): Long = System.currentTimeMillis() + offsetMs

    fun localToHost(localMs: Long): Long = localMs + offsetMs
    fun hostToLocal(hostMs: Long): Long = hostMs - offsetMs
}
