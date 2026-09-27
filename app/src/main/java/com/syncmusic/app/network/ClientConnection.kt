package com.syncmusic.app.network

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * اتصال Client به Host
 * پشتیبانی از دریافت فایل باینری بعد از پیام file_meta
 */
class ClientConnection(
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onMessage: (SyncMessages.Message) -> Unit,
    private val onFileProgress: ((Int) -> Unit)? = null,
    private val onFileReceived: ((File) -> Unit)? = null,
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "ClientConnection"
        private const val CONNECT_TIMEOUT = 5000
        private const val CHUNK_SIZE = 64 * 1024
    }

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var writer: PrintWriter? = null
    private var input: InputStream? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null

    val clock = SyncClock()

    fun connect(hostIp: String, sessionCode: String) {
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(hostIp, HostServer.PORT), CONNECT_TIMEOUT)
                socket = s
                output = s.getOutputStream()
                writer = PrintWriter(output, true)
                input = s.getInputStream()
                reader = BufferedReader(InputStreamReader(input))

                // ارسال join
                val join = SyncMessages.Message(
                    SyncMessages.TYPE_JOIN,
                    mapOf("code" to sessionCode)
                )
                send(join)

                val ackLine = reader?.readLine()
                val ack = ackLine?.let { SyncMessages.fromJson(it) }
                if (ack?.type == SyncMessages.TYPE_JOIN_ACK && ack.payload["ok"] == true) {
                    withContext(Dispatchers.Main) { onConnected() }
                    startReceiveLoop()
                    performTimeSync()
                } else {
                    close()
                    withContext(Dispatchers.Main) { onDisconnected() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
                withContext(Dispatchers.Main) { onDisconnected() }
            }
        }
    }

    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            try {
                while (isActive) {
                    val line = reader?.readLine() ?: break
                    val msg = SyncMessages.fromJson(line) ?: continue

                    when (msg.type) {
                        SyncMessages.TYPE_FILE_META -> {
                            // بعد از این پیام، باید size بایت خام بخونیم
                            receiveFile(msg)
                        }
                        SyncMessages.TYPE_TIME_SYNC_REPLY -> {
                            handleTimeSyncReply(msg)
                        }
                        else -> {
                            withContext(Dispatchers.Main) { onMessage(msg) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Receive error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { onDisconnected() }
                close()
            }
        }
    }

    private suspend fun receiveFile(meta: SyncMessages.Message) {
        val name = meta.payload["name"] as? String ?: "received_song.mp3"
        val size = when (val s = meta.payload["size"]) {
            is Double -> s.toLong()
            is Long -> s
            is Int -> s.toLong()
            else -> 0L
        }

        if (size <= 0) {
            Log.e(TAG, "Invalid file size: $size")
            return
        }

        Log.d(TAG, "Receiving file $name ($size bytes)")

        val file = File(cacheDir, name)
        try {
            file.outputStream().use { fos ->
                var received = 0L
                val buffer = ByteArray(CHUNK_SIZE)
                val inp = input ?: return

                while (received < size) {
                    val toRead = minOf(CHUNK_SIZE.toLong(), size - received).toInt()
                    val read = inp.read(buffer, 0, toRead)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                    received += read
                    val progress = ((received * 100) / size).toInt()
                    withContext(Dispatchers.Main) {
                        onFileProgress?.invoke(progress)
                    }
                }
            }

            if (file.length() == size) {
                Log.d(TAG, "File received successfully: ${file.absolutePath}")
                withContext(Dispatchers.Main) {
                    onFileReceived?.invoke(file)
                }
            } else {
                Log.e(TAG, "File size mismatch: expected $size, got ${file.length()}")
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receive file error: ${e.message}")
            file.delete()
        }
    }

    private fun handleTimeSyncReply(msg: SyncMessages.Message) {
        val hostTime = (msg.payload["hostTime"] as? Double)?.toLong()
            ?: (msg.payload["hostTime"] as? Long) ?: return
        val clientSendTime = (msg.payload["clientSendTime"] as? Double)?.toLong()
            ?: (msg.payload["clientSendTime"] as? Long) ?: return
        val now = System.currentTimeMillis()
        val rtt = now - clientSendTime
        clock.updateOffset(hostTime, now, rtt)
        Log.d(TAG, "Clock offset updated: ${clock.offsetMs} ms, rtt=$rtt")
    }

    fun performTimeSync() {
        scope.launch {
            repeat(3) {
                val msg = SyncMessages.Message(
                    SyncMessages.TYPE_TIME_SYNC,
                    mapOf("clientSendTime" to System.currentTimeMillis())
                )
                send(msg)
                delay(200)
            }
        }
    }

    fun send(msg: SyncMessages.Message) {
        try {
            writer?.println(SyncMessages.toJson(msg))
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
        }
    }

    fun sendReady() {
        send(SyncMessages.Message(SyncMessages.TYPE_READY))
    }

    fun requestFile() {
        send(SyncMessages.Message(SyncMessages.TYPE_REQUEST_FILE))
    }

    fun close() {
        receiveJob?.cancel()
        scope.cancel()
        try {
            socket?.close()
        } catch (_: Exception) {}
    }
}
