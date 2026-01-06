package com.example.radioondarossa

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.radioondarossa.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Stato reale sincronizzato col servizio
    private var isPlaying = false

    // Receiver per il titolo
    private val metadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val title = intent?.getStringExtra("title")
            if (!title.isNullOrEmpty()) {
                binding.nowPlaying.text = title
            }
        }
    }

    // Receiver per lo stato PLAY/STOP
    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra("state") ?: return

            isPlaying = state == "PLAYING"

            binding.playPause.text = if (isPlaying) "Pause" else "Play"
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Registrazione receiver compatibile Android 7 → 14
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                metadataReceiver,
                IntentFilter("METADATA_UPDATE"),
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                playbackReceiver,
                IntentFilter("PLAYBACK_STATE"),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(metadataReceiver, IntentFilter("METADATA_UPDATE"))
            registerReceiver(playbackReceiver, IntentFilter("PLAYBACK_STATE"))
        }

        // Chiede lo stato al servizio appena l'Activity parte
        val request = Intent(this, RadioService::class.java)
        request.action = "REQUEST_STATE"
        startService(request)

        // Pulsante Play/Pause sincronizzato col servizio
        binding.playPause.setOnClickListener {
            val intent = Intent(this, RadioService::class.java)
            // If isPlaying is TRUE, we send STOP.
            // If the button says "Pause" (meaning audio is playing), isPlaying should be true.
            intent.action = if (isPlaying) "STOP" else "PLAY"
            startService(intent)
        }

    }

    override fun onDestroy() {
        unregisterReceiver(metadataReceiver)
        unregisterReceiver(playbackReceiver)
        super.onDestroy()
    }
}
