package com.navigator.app.audio

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.navigator.app.notifications.AppNotificationListener

/**
 * Play/pause/next/previous for whichever app currently holds an active
 * media session - the same mechanism a Bluetooth headset's physical buttons
 * use, so it works with any app that supports media sessions without us
 * needing to know anything about that app. Requires notification-listener
 * access (already needed for notification mirroring) since
 * getActiveSessions() requires a bound NotificationListenerService component.
 */
class MediaControlBridge(private val context: Context) {

    private fun activeController(): android.media.session.MediaController? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val component = ComponentName(context, AppNotificationListener::class.java)
        return try {
            manager.getActiveSessions(component).firstOrNull()
        } catch (e: SecurityException) {
            null
        }
    }

    fun playPause() {
        val controller = activeController() ?: return
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (isPlaying) controller.transportControls.pause() else controller.transportControls.play()
    }

    fun next() {
        activeController()?.transportControls?.skipToNext()
    }

    fun previous() {
        activeController()?.transportControls?.skipToPrevious()
    }

    /** "Title - Artist" of the active session, or null if nothing is playing or it has no title. */
    fun nowPlaying(): String? {
        val metadata = activeController()?.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
        val artist = (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim()
        if (title.isNullOrEmpty()) return null
        return if (artist.isNullOrEmpty()) title else "$title - $artist"
    }
}
