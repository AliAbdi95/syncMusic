package com.syncmusic.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import java.io.File

/**
 * پخش‌کننده با قابلیت شروع در زمان مشخص (sync)
 */
class SyncPlayer(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scheduledJob: Job? = null

    val isPlaying: Boolean get() = player.isPlaying

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.d("SyncPlayer", "State: $state")
            }
        })
    }

    fun prepare(file: File) {
        val item = MediaItem.fromUri(Uri.fromFile(file))
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = false
    }

    fun prepare(url: String) {
        val item = MediaItem.fromUri(url)
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = false
    }

    /**
     * شروع پخش در زمان hostTime مشخص
     * @param hostStartTime زمان شروع بر اساس ساعت Host
     * @param clock برای تبدیل به زمان محلی
     * @param positionMs موقعیت شروع آهنگ (معمولاً 0)
     */
    fun playAt(hostStartTime: Long, clock: com.syncmusic.app.network.SyncClock, positionMs: Long = 0) {
        scheduledJob?.cancel()
        scheduledJob = scope.launch {
            val localStart = clock.hostToLocal(hostStartTime)
            val delayMs = localStart - System.currentTimeMillis()

            Log.d("SyncPlayer", "Scheduled play in ${delayMs}ms (hostStart=$hostStartTime)")

            if (delayMs > 0) {
                delay(delayMs)
            }

            player.seekTo(positionMs)
            player.play()
            Log.d("SyncPlayer", "Play started at local ${System.currentTimeMillis()}")
        }
    }

    fun stop() {
        scheduledJob?.cancel()
        player.stop()
        player.clearMediaItems()
    }

    fun pause() {
        player.pause()
    }

    fun release() {
        scheduledJob?.cancel()
        scope.cancel()
        player.release()
    }
}
