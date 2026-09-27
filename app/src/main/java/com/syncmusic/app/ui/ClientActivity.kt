package com.syncmusic.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.syncmusic.app.databinding.ActivityClientBinding
import com.syncmusic.app.network.ClientConnection
import com.syncmusic.app.network.SyncMessages
import com.syncmusic.app.player.SyncPlayer
import java.io.File

class ClientActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientBinding
    private var connection: ClientConnection? = null
    private var player: SyncPlayer? = null
    private var songReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        player = SyncPlayer(this)

        binding.btnJoin.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim()
            if (code.isNullOrEmpty() || code.length != 6) {
                Toast.makeText(this, "کد ۶ رقمی را وارد کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showIpDialog(code)
        }
    }

    private fun showIpDialog(code: String) {
        val input = android.widget.EditText(this).apply {
            hint = "IP آدرس Host (مثلاً 192.168.1.5)"
            setText("192.168.1.")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("آدرس IP میزبان")
            .setMessage("IP گوشی Host را وارد کنید (در صفحه Host نمایش داده می‌شود)")
            .setView(input)
            .setPositiveButton("اتصال") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    connectToHost(ip, code)
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun connectToHost(ip: String, code: String) {
        binding.btnJoin.isEnabled = false
        binding.tvStatus.text = "در حال اتصال به $ip ..."

        connection = ClientConnection(
            onConnected = {
                binding.tvStatus.text = "متصل شد ✓\nمنتظر دریافت آهنگ از Host باشید"
            },
            onDisconnected = {
                binding.tvStatus.text = "اتصال قطع شد"
                binding.btnJoin.isEnabled = true
                songReady = false
            },
            onMessage = { msg -> handleMessage(msg) },
            onFileProgress = { progress ->
                binding.tvDownloadStatus.text = "دریافت فایل از Host: $progress%"
            },
            onFileReceived = { file ->
                onSongFileReceived(file)
            },
            cacheDir = cacheDir
        )

        connection?.connect(ip, code)
    }

    private fun handleMessage(msg: SyncMessages.Message) {
        when (msg.type) {
            // پشتیبانی از روش قدیمی (URL) برای سازگاری
            SyncMessages.TYPE_SONG_URL -> {
                binding.tvDownloadStatus.text = "Host از روش URL استفاده کرد (نسخه قدیمی)"
            }
            SyncMessages.TYPE_FILE_DONE -> {
                // اختیاری - معمولاً onFileReceived کافی است
            }
            SyncMessages.TYPE_PLAY -> {
                val startAt = (msg.payload["startAt"] as? Double)?.toLong()
                    ?: (msg.payload["startAt"] as? Long) ?: return
                val position = (msg.payload["position"] as? Double)?.toLong()
                    ?: (msg.payload["position"] as? Long) ?: 0L

                if (songReady) {
                    val clock = connection?.clock ?: return
                    player?.playAt(startAt, clock, position)
                    binding.tvPlayerStatus.text = "پخش همزمان شروع شد!"
                } else {
                    binding.tvPlayerStatus.text = "آهنگ هنوز آماده نیست"
                }
            }
            SyncMessages.TYPE_STOP -> {
                player?.stop()
                binding.tvPlayerStatus.text = "متوقف شد"
            }
        }
    }

    private fun onSongFileReceived(file: File) {
        try {
            player?.prepare(file)
            songReady = true
            binding.tvDownloadStatus.text = "آهنگ از Host دریافت شد ✓"
            binding.tvPlayerStatus.text = "آماده پخش"
            connection?.sendReady()
        } catch (e: Exception) {
            binding.tvDownloadStatus.text = "خطا در آماده‌سازی فایل"
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connection?.close()
        player?.release()
    }
}
