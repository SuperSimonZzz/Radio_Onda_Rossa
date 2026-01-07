package com.example.radioondarossa

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player

class RadioService : Service() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private val metadataFetcher = MetadataFetcher()
    private val streamUrl = "https://blimp.streampunk.cc/_stream/ondarossa.ogg"
    private val CHANNEL_ID = "radio_channel"
    private val NOTIFICATION_ID = 1

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                // Headset disconnected -> Stop playback explicitly
                stopPlayback()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Radio Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        // 1. IMPORTANT: Set the supported actions!
        val stateBuilder = android.support.v4.media.session.PlaybackStateCompat.Builder()
            .setActions(
                android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP
            )

        mediaSession = MediaSessionCompat(this, "RadioSession")
        mediaSession.setPlaybackState(stateBuilder.build())

        val audioAttributes = com.google.android.exoplayer2.audio.AudioAttributes.Builder()
            .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
            .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // 2. Set the callback (You already have this, just ensure it's here)
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { startPlayback() }
            override fun onPause() { stopPlayback() }
            override fun onStop() { stopPlayback() }
        })

        mediaSession.isActive = true

        player = ExoPlayer.Builder(this).build()
        player.setAudioAttributes(audioAttributes, true)

        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    stopPlayback()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    stopPlayback()
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY" -> startPlayback()
            "STOP" -> stopPlayback()
            "REQUEST_STATE" -> broadcastState()
        }
        return START_STICKY
    }

    // -----------------------------
    // PLAYBACK CONTROL
    // -----------------------------

    private fun startPlayback() {
        sendPlaybackState("PLAYING")

        try {
            registerReceiver(noisyReceiver, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } catch (_: Exception) {
            // Already registered or failed, ignore
        }

        player.playWhenReady = true
        player.play()

        player.playWhenReady = true
        player.play()

        updateMediaSessionState(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING)
        mediaSession.isActive = true

        // Check if we have cached metadata
        val lastData = MetadataFetcher.currentMetadata
        if (lastData != null) {
            updateNotification(lastData.mainTitle, lastData.subTitle)
            // Combine for UI simply
            sendTitleToUI(lastData.mainTitle)
        } else {
            updateNotification("Caricamento...", "Radio Onda Rossa")
            sendTitleToUI("Caricamento...")
        }

        // Start fetching with new Data Class signature
        metadataFetcher.start { metadata ->
            updateNotification(metadata.mainTitle, metadata.subTitle)

            // Format string for the Main Activity UI
            val uiString = if (metadata.subTitle.isNotEmpty())
                metadata.mainTitle
            else
                metadata.mainTitle

            sendTitleToUI(uiString)
        }
    }

    private fun stopPlayback() {
        player.playWhenReady = false
        player.pause()
        metadataFetcher.stop()

        updateMediaSessionState(android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED)
        val lastData = MetadataFetcher.currentMetadata
        val title = lastData?.mainTitle ?: "Radio Onda Rossa"
        val subtitle = lastData?.subTitle ?: ""

        updateNotification(title, subtitle)
        sendPlaybackState("STOPPED")
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    // -----------------------------
    // NOTIFICATION LOGIC
    // -----------------------------

    private fun getActionIntent(action: String): PendingIntent {
        val intent = Intent(this, RadioService::class.java).apply { this.action = action }
        val requestCode = if (action == "PLAY") 10 else 20
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // UPDATED: Now accepts two strings (Title and Artist/Subtitle)
    // UPDATED: Now accepts two strings (Title and Artist/Subtitle)
    private fun updateNotification(mainTitle: String, subTitle: String) {
        val isPlaying = player.playWhenReady

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", getActionIntent("STOP"))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, "Play", getActionIntent("PLAY"))
        }

        // 1. Update MediaMetadata (Lock Screen, Bluetooth, & Android Auto info)
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                // The Main Title (e.g., "Selezioni musicali")
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mainTitle)
                // The Artist/Subtitle (e.g., "www.ondarossa.info")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subTitle)
                .build()
        )

        // 2. Build the Notification (Notification Shade)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(mainTitle) // Top line
            .setContentText(subTitle)   // Bottom line
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.RED)
            .setOngoing(isPlaying)
            .addAction(playPauseAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()

        if (isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateMediaSessionState(state: Int) {
        val playbackStateBuilder = android.support.v4.media.session.PlaybackStateCompat.Builder()
        // Define what buttons are clickable based on state
        if (state == android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING) {
            playbackStateBuilder.setActions(
                android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP
            )
        } else {
            playbackStateBuilder.setActions(
                android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP
            )
        }
        // Fixed reference here:
        playbackStateBuilder.setState(
            state,
            android.support.v4.media.session.PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
            1f
        )
        mediaSession.setPlaybackState(playbackStateBuilder.build())
    }



    // -----------------------------
    // HELPERS & CLEANUP
    // -----------------------------

    private fun sendTitleToUI(title: String) {
        val intent = Intent("METADATA_UPDATE")
        intent.putExtra("title", title)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun sendPlaybackState(state: String) {
        val intent = Intent("PLAYBACK_STATE")
        intent.putExtra("state", state)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun broadcastState() {
        val state = if (player.playWhenReady) "PLAYING" else "STOPPED"
        sendPlaybackState(state)
    }

    override fun onDestroy() {
        player.release()
        mediaSession.release()
        metadataFetcher.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopPlayback()
        stopSelf()
    }
}
