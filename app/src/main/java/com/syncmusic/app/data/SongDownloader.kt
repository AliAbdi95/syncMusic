package com.syncmusic.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object SongDownloader {

    // یک آهنگ نمونه رایگان (Creative Commons)
    const val SAMPLE_SONG_URL = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
    const val SAMPLE_SONG_NAME = "sample_song.mp3"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun downloadSample(context: Context, onProgress: ((Int) -> Unit)? = null): File? {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(context.cacheDir, SAMPLE_SONG_NAME)
                if (file.exists() && file.length() > 100_000) {
                    return@withContext file
                }

                val request = Request.Builder().url(SAMPLE_SONG_URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    val total = body.contentLength()
                    var downloaded = 0L

                    file.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    val progress = ((downloaded * 100) / total).toInt()
                                    onProgress?.invoke(progress)
                                }
                            }
                        }
                    }
                }
                file
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
