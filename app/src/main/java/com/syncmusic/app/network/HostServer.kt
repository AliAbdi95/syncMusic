package com.syncmusic.app.network

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * سرور ساده TCP روی Host
 * پورت ثابت: 9876
 * پشتیبانی از ارسال فایل باینری بعد از پیام file_meta
 */
class HostServer(
    private val onClientJoined: (String) -> Unit,
    private val onClientReady: (String) -> Unit,
    private val onMessage: (String, SyncMessages.Message) -> Unit,
    private val onFileProgress: ((String, Int) -> Unit)? = null
) {
    companion object {
        const val PORT = 9876
        private const val TAG = "HostServer"
        private const val CHUNK_SIZE = 64 * 1024 // 64KB
    }

    private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap<String, ClientHandler>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientIdCounter = AtomicInteger(1)

    @Volatile
    private var currentSongFile: File? = null

    var sessionCode: String = ""
        private set

    fun start(sessionCode: String) {
        this.sessionCode = sessionCode
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Server started on port $PORT, code=$sessionCode")
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    val id = "Client-${clientIdCounter.getAndIncrement()}"
                    val handler = ClientHandler(id, socket)
                    clients[id] = handler
                    handler.start()
                    withContext(Dispatchers.Main) {
                        onClientJoined(id)
                    }
                    // اگر فایل از قبل آماده بود، به کلاینت جدید هم بفرست
                    currentSongFile?.let { file ->
                        handler.sendFile(file)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.cancel()
        clients.values.forEach { it.close() }
        clients.clear()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    fun broadcast(msg: SyncMessages.Message) {
        val json = SyncMessages.toJson(msg)
        clients.values.forEach { it.sendText(json) }
    }

    fun sendTo(clientId: String, msg: SyncMessages.Message) {
        clients[clientId]?.sendText(SyncMessages.toJson(msg))
    }

    /**
     * فایل آهنگ را به همه کلاینت‌های متصل می‌فرستد
     */
    fun sendFileToAll(file: File) {
        currentSongFile = file
        clients.values.forEach { handler ->
            scope.launch {
                handler.sendFile(file)
            }
        }
    }

    fun getConnectedClients(): List<String> = clients.keys.toList()

    private inner class ClientHandler(
        val id: String,
        private val socket: Socket
    ) {
        private var output: OutputStream? = null
        private var writer: PrintWriter? = null
        private var reader: BufferedReader? = null
        private var job: Job? = null
        private val sendLock = Any()

        fun start() {
            job = scope.launch {
                try {
                    output = socket.getOutputStream()
                    writer = PrintWriter(output, true)
                    reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    // اول کد سشن رو چک کن
                    val firstLine = reader?.readLine()
                    val joinMsg = firstLine?.let { SyncMessages.fromJson(it) }
                    if (joinMsg?.type != SyncMessages.TYPE_JOIN ||
                        joinMsg.payload["code"] != sessionCode
                    ) {
                        sendText(SyncMessages.toJson(
                            SyncMessages.Message(SyncMessages.TYPE_JOIN_ACK, mapOf("ok" to false, "reason" to "invalid_code"))
                        ))
                        close()
                        return@launch
                    }

                    sendText(SyncMessages.toJson(
                        SyncMessages.Message(SyncMessages.TYPE_JOIN_ACK, mapOf("ok" to true, "clientId" to id))
                    ))

                    // حلقه دریافت پیام (فقط متن)
                    while (isActive) {
                        val line = reader?.readLine() ?: break
                        val msg = SyncMessages.fromJson(line) ?: continue
                        handleMessage(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Client $id error: ${e.message}")
                } finally {
                    close()
                }
            }
        }

        private fun handleMessage(msg: SyncMessages.Message) {
            when (msg.type) {
                SyncMessages.TYPE_READY -> {
                    scope.launch(Dispatchers.Main) { onClientReady(id) }
                }
                SyncMessages.TYPE_TIME_SYNC -> {
                    val reply = SyncMessages.Message(
                        SyncMessages.TYPE_TIME_SYNC_REPLY,
                        mapOf(
                            "hostTime" to System.currentTimeMillis(),
                            "clientSendTime" to msg.payload["clientSendTime"]
                        )
                    )
                    sendText(SyncMessages.toJson(reply))
                }
                SyncMessages.TYPE_REQUEST_FILE -> {
                    currentSongFile?.let { file ->
                        scope.launch { sendFile(file) }
                    }
                }
                SyncMessages.TYPE_PING -> {
                    sendText(SyncMessages.toJson(SyncMessages.Message(SyncMessages.TYPE_PONG)))
                }
                else -> {
                    scope.launch(Dispatchers.Main) { onMessage(id, msg) }
                }
            }
        }

        fun sendText(json: String) {
            synchronized(sendLock) {
                try {
                    writer?.println(json)
                    writer?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Send text failed to $id: ${e.message}")
                }
            }
        }

        /**
         * ارسال فایل:
         * 1. پیام file_meta (JSON)
         * 2. بلافاصله بایت‌های خام فایل
         * 3. پیام file_done (اختیاری)
         */
        suspend fun sendFile(file: File) {
            if (!file.exists()) return
            val size = file.length()
            Log.d(TAG, "Sending file ${file.name} ($size bytes) to $id")

            synchronized(sendLock) {
                try {
                    // ۱. متادیتا
                    val meta = SyncMessages.Message(
                        SyncMessages.TYPE_FILE_META,
                        mapOf(
                            "name" to file.name,
                            "size" to size
                        )
                    )
                    writer?.println(SyncMessages.toJson(meta))
                    writer?.flush()

                    // ۲. بایت‌های خام
                    val buffer = ByteArray(CHUNK_SIZE)
                    var sent = 0L
                    file.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output?.write(buffer, 0, read)
                            sent += read
                            val progress = if (size > 0) ((sent * 100) / size).toInt() else 100
                            scope.launch(Dispatchers.Main) {
                                onFileProgress?.invoke(id, progress)
                            }
                        }
                    }
                    output?.flush()

                    // ۳. اتمام
                    val done = SyncMessages.Message(
                        SyncMessages.TYPE_FILE_DONE,
                        mapOf("name" to file.name, "size" to size)
                    )
                    writer?.println(SyncMessages.toJson(done))
                    writer?.flush()

                    Log.d(TAG, "File sent completely to $id")
                } catch (e: Exception) {
                    Log.e(TAG, "Send file failed to $id: ${e.message}")
                }
            }
        }

        fun close() {
            job?.cancel()
            try {
                socket.close()
            } catch (_: Exception) {}
            clients.remove(id)
        }
    }
}
