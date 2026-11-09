package com.akong.spotifyautoresume

import android.app.Notification
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Handler
import android.os.Looper
import android.util.Log

class SpotifyResumeService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    private var currentToken: MediaSession.Token? = null

    private var retryCount = 0
    private var retryRunnable: Runnable? = null

    private val maxRetries = 5

    private val retryDelays = longArrayOf(
        1000L,
        2000L,
        4000L,
        8000L,
        15000L
    )

    private val callback = object : MediaController.Callback() {

        override fun onPlaybackStateChanged(
            state: PlaybackState?
        ) {
            when (state?.state) {

                PlaybackState.STATE_PLAYING -> {
                    resetRetries()
                    Log.d(TAG, "Spotify sedang bermain")
                }

                PlaybackState.STATE_PAUSED -> {
                    Log.d(TAG, "Spotify paused, mencoba resume")
                    scheduleResume()
                }

                PlaybackState.STATE_STOPPED,
                PlaybackState.STATE_ERROR -> {
                    resetRetries()
                }
            }
        }

        override fun onSessionDestroyed() {
            resetRetries()
            controller = null
            currentToken = null
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        Log.d(TAG, "Notification listener aktif")

        scanNotifications()
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {
        if (sbn.packageName != SPOTIFY_PACKAGE) return

        attachSession(sbn.notification)
    }

    private fun scanNotifications() {
        try {
            activeNotifications?.forEach { notification ->
                if (notification.packageName == SPOTIFY_PACKAGE) {
                    attachSession(notification.notification)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membaca notifikasi", e)
        }
    }

    private fun attachSession(notification: Notification) {

        @Suppress("DEPRECATION")
        val token = notification.extras
            ?.getParcelable(Notification.EXTRA_MEDIA_SESSION)
                as? MediaSession.Token
                ?: return

        if (currentToken == token && controller != null) {
            return
        }

        try {
            resetRetries()

            controller?.unregisterCallback(callback)

            val newController = MediaController(this, token)

            controller = newController
            currentToken = token

            newController.registerCallback(callback)

            val state = newController.playbackState?.state

            if (state == PlaybackState.STATE_PAUSED) {
                scheduleResume()
            }

            Log.d(TAG, "Spotify media session terhubung")

        } catch (e: Exception) {
            Log.e(TAG, "Gagal menghubungkan media session", e)
        }
    }

    private fun scheduleResume() {

        if (retryCount >= maxRetries) {
            Log.d(TAG, "Batas percobaan resume tercapai")
            return
        }

        retryRunnable?.let {
            handler.removeCallbacks(it)
        }

        val delay = retryDelays[retryCount]

        val task = Runnable {

            val activeController = controller ?: return@Runnable

            val state = activeController.playbackState?.state

            if (state == PlaybackState.STATE_PAUSED) {

                try {
                    activeController.transportControls.play()

                    retryCount++

                    Log.d(
                        TAG,
                        "Percobaan resume ke-$retryCount"
                    )

                    if (retryCount < maxRetries) {
                        scheduleResume()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Gagal mengirim perintah play", e)
                }

            } else {
                resetRetries()
            }
        }

        retryRunnable = task
        handler.postDelayed(task, delay)
    }

    private fun resetRetries() {
        retryRunnable?.let {
            handler.removeCallbacks(it)
        }

        retryRunnable = null
        retryCount = 0
    }

    override fun onDestroy() {
        resetRetries()

        controller?.unregisterCallback(callback)
        controller = null
        currentToken = null

        super.onDestroy()
    }

    companion object {
        private const val TAG = "SpotifyAutoResume"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}