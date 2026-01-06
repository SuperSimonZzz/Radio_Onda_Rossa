package com.example.radioondarossa

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// Simple data class to hold both fields
data class RadioMetadata(
    val mainTitle: String, // From JSON "comment"
    val subTitle: String   // From JSON "title"
)

class MetadataFetcher {

    companion object {
        // Store the full object
        var currentMetadata: RadioMetadata? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    // Update callback to return RadioMetadata object instead of String
    fun start(onMetadataChanged: (RadioMetadata) -> Unit) {
        if (isRunning) return
        isRunning = true

        val runnable = object : Runnable {
            override fun run() {
                Thread {
                    try {
                        Log.d("MetadataFetcher", "Connecting to metadata API...")

                        val url = URL("https://player.ondarossa.info/metadata.json")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0")

                        if (connection.responseCode == 200) {
                            val reader = BufferedReader(InputStreamReader(connection.inputStream))
                            val response = StringBuilder()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                response.append(line)
                            }
                            reader.close()

                            // JSON: {"comment":"Selezioni musicali","title":"www.ondarossa.info"}
                            val jsonObject = JSONObject(response.toString())

                            val rawComment = jsonObject.optString("comment").trim()
                            val rawTitle = jsonObject.optString("title").trim()

                            // Logic to determine what goes where
                            var main = "Radio Onda Rossa"
                            var sub = ""

                            if (rawComment.isNotEmpty() && rawComment != "null") {
                                main = rawComment
                                // If we have a comment, the "title" field becomes the subtitle (artist/url)
                                if (rawTitle.isNotEmpty() && rawTitle != "null") {
                                    sub = rawTitle
                                }
                            } else if (rawTitle.isNotEmpty() && rawTitle != "null") {
                                // Fallback: if no comment, use title as main
                                main = rawTitle
                            }

                            val newMetadata = RadioMetadata(main, sub)

                            // Update logic
                            handler.post {
                                if (currentMetadata != newMetadata) {
                                    currentMetadata = newMetadata
                                    onMetadataChanged(newMetadata)
                                } else {
                                    // Force update on first run/restart
                                    onMetadataChanged(newMetadata)
                                }
                            }
                        }

                    } catch (e: Exception) {
                        Log.e("MetadataFetcher", "Error: ${e.message}")
                    }
                }.start()

                if (isRunning) {
                    handler.postDelayed(this, 10000)
                }
            }
        }
        handler.post(runnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }
}
