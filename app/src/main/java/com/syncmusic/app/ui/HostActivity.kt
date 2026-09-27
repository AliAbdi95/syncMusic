package com.syncmusic.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.syncmusic.app.data.SongDownloader
import com.syncmusic.app.databinding.ActivityHostBinding
import com.syncmusic.app.network.HostServer
import com.syncmusic.app.network.SyncMessages
import com.syncmusic.app.player.SyncPlayer
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.*

class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding
    private var server: HostServer? = null
    private var player: SyncPlayer? = null
    private var songFile: java.io.File? = null
    private val readyClients = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        player = SyncPlayer(this)

        val code = generateSessionCode()
        binding.tvSessionCode.text = "کد سشن: $code\nIP: ${getLocalIpAddress()}"

        server = HostServer(
            onClientJoined = { id ->
                updateClientsList()
                binding.tvStatus.text = "کلاینت جدید وصل شد"
            },
            onClientReady = { id ->
                readyClients.add(id)
                updateClientsList()
                binding.tvStatus.text = "کلاینت‌های آماده: ${readyClients.size}"
            },
            onMessage = { _, _ -> },
            onFileProgress = { clientId, progress ->
                // می‌تونیم لاگ یا UI نشون بدیم
                if (progress % 20 == 0 || progress == 100) {
                    binding.tvDownloadStatus.text = "ارسال به $clientId: $progress%"
                }
            }
        )
        server?.start(code)

        binding.tvStatus.text = "سشن فعال است. کد را به بقیه بدهید"

        binding.btnDownload.setOnClickListener {
            downloadAndSendSong()
        }

        binding.btnPlay.setOnClickListener {
            startSyncedPlay()
        }

        binding.btnStop.setOnClickListener {
            stopPlay()
        }
    }

    private fun downloadAndSendSong() {
        binding.btnDownload.isEnabled = false
        binding.tvDownloadStatus.text = "در حال دانلود از اینترنت..."

        lifecycleScope.launch {
            val file = SongDownloader.downloadSample(this@HostActivity) { progress ->
                runOnUiThread {
                    binding.tvDownloadStatus.text = "دانلود از نت: $progress%"
                }
            }

            if (file != null) {
                songFile = file
                player?.prepare(file)
                binding.tvDownloadStatus.text = "دانلود کامل شد. در حال ارسال به کلاینت‌ها..."
                binding.btnPlay.isEnabled = true

                // فایل رو مستقیم روی وای‌فای به همه کلاینت‌ها بفرست
                server?.sendFileToAll(file)

                binding.tvDownloadStatus.text = "فایل آماده و در حال ارسال به کلاینت‌ها ✓"
            } else {
                binding.tvDownloadStatus.text = "خطا در دانلود"
                binding.btnDownload.isEnabled = true
            }
        }
    }

    private fun startSyncedPlay() {
        if (songFile == null) {
            Toast.makeText(this, "اول آهنگ را دانلود کنید", Toast.LENGTH_SHORT).show()
            return
        }

        // ۳ ثانیه بعد شروع کن
        val startAt = System.currentTimeMillis() + 3000

        server?.broadcast(
            SyncMessages.Message(
                SyncMessages.TYPE_PLAY,
                mapOf(
                    "startAt" to startAt,
                    "position" to 0
                )
            )
        )

        // خود Host هم پخش کنه (offset = 0)
        player?.playAt(startAt, com.syncmusic.app.network.SyncClock())

        binding.tvPlayerStatus.text = "پخش همزمان در ۳ ثانیه..."
        binding.btnPlay.isEnabled = false
    }

    private fun stopPlay() {
        server?.broadcast(SyncMessages.Message(SyncMessages.TYPE_STOP))
        player?.stop()
        if (songFile != null) {
            player?.prepare(songFile!!)
        }
        binding.tvPlayerStatus.text = "متوقف شد"
        binding.btnPlay.isEnabled = songFile != null
    }

    private fun updateClientsList() {
        val list = server?.getConnectedClients() ?: emptyList()
        binding.tvClients.text = if (list.isEmpty()) {
            "هنوز کسی وصل نشده"
        } else {
            list.joinToString("\n") { id ->
                if (readyClients.contains(id)) "$id ✓ آماده" else id
            }
        }
    }

    private fun generateSessionCode(): String {
        return (100000..999999).random().toString()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in Collections.list(interfaces)) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (_: Exception) {}
        return "unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        player?.release()
    }
}
