/*
 * Copyright (C) 2025 The AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.media

import android.app.WallpaperColors
import android.content.Context
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import java.io.PrintWriter
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

@SysUISingleton
class MediaSessionManager
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    private val mediaDataManager: MediaDataManager,
    private val dumpManager: DumpManager,
) : CoreStartable, MediaDataManager.Listener, Dumpable {

    interface MediaDataListener {
        fun onPlaybackStateChanged(state: Int) {}
        fun onAlbumArtChanged(drawable: Drawable?) {}
        fun onAppIconChanged(drawable: Drawable?) {}
        fun onMediaColorsChanged(color: Int?) {}
        fun onMetadataChanged(track: String, artist: String) {}
    }

    private val listeners = CopyOnWriteArrayList<MediaDataListener>()
    private val mediaEntries = LinkedHashMap<String, MediaData>()
    @Volatile private var currentSession = ResolvedMediaSession()

    val isMediaPlaying: Boolean
        get() = currentSession.playbackState == PlaybackState.STATE_PLAYING ||
            currentSession.playbackState == PlaybackState.STATE_BUFFERING

    override fun start() {
        mediaDataManager.addListener(this)
        dumpManager.registerNormalDumpable(TAG, this)
    }

    fun addListener(listener: MediaDataListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        val session = currentSession
        listener.onPlaybackStateChanged(session.playbackState)
        listener.onMetadataChanged(session.title, session.artist)
        listener.onAlbumArtChanged(session.albumArt)
        listener.onAppIconChanged(session.appIcon)
        listener.onMediaColorsChanged(session.mediaColor)
    }

    fun removeListener(listener: MediaDataListener) {
        listeners.remove(listener)
    }

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
    ) {
        synchronized(mediaEntries) {
            if (oldKey != null && oldKey != key) {
                mediaEntries.remove(oldKey)
            }
            mediaEntries[key] = data
            updateCurrentSessionLocked()
        }
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        synchronized(mediaEntries) {
            mediaEntries.remove(key)
            updateCurrentSessionLocked()
        }
    }

    private fun updateCurrentSessionLocked() {
        val activePlaying = mediaEntries.values.firstOrNull { it.active && it.isPlaying == true }
        val active = activePlaying ?: mediaEntries.values.firstOrNull { it.active }
        val chosen = active ?: mediaEntries.values.firstOrNull()

        val updated = if (chosen != null) {
            val playbackState = if (chosen.isPlaying == true) {
                PlaybackState.STATE_PLAYING
            } else {
                PlaybackState.STATE_PAUSED
            }
            val artDrawable = chosen.artwork?.loadDrawable(context)
            val appIconDrawable = chosen.appIcon?.loadDrawable(context)
            val wallpaperColors = artDrawable?.let {
                try {
                    WallpaperColors.fromDrawable(it)
                } catch (e: Exception) {
                    null
                }
            }
            ResolvedMediaSession(
                playbackState = playbackState,
                albumArt = artDrawable,
                appIcon = appIconDrawable,
                mediaColor = wallpaperColors?.primaryColor?.toArgb(),
                title = chosen.song?.toString() ?: "",
                artist = chosen.artist?.toString() ?: "",
            )
        } else {
            ResolvedMediaSession()
        }

        val previous = currentSession
        if (previous == updated) return
        currentSession = updated

        if (previous.playbackState != updated.playbackState) {
            listeners.forEach { it.onPlaybackStateChanged(updated.playbackState) }
        }
        if (previous.title != updated.title || previous.artist != updated.artist) {
            listeners.forEach { it.onMetadataChanged(updated.title, updated.artist) }
        }
        if (previous.albumArt !== updated.albumArt ||
            previous.title != updated.title ||
            previous.artist != updated.artist
        ) {
            listeners.forEach { it.onAlbumArtChanged(updated.albumArt) }
        }
        if (previous.appIcon !== updated.appIcon) {
            listeners.forEach { it.onAppIconChanged(updated.appIcon) }
        }
        if (previous.mediaColor != updated.mediaColor) {
            listeners.forEach { it.onMediaColorsChanged(updated.mediaColor) }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("MediaSessionManager:")
        pw.println("  isMediaPlaying: $isMediaPlaying")
        pw.println("  mediaEntriesCount: ${mediaEntries.size}")
        pw.println("  registeredListeners: ${listeners.size}")
        pw.println("  currentSession: $currentSession")
        synchronized(mediaEntries) {
            mediaEntries.forEach { (k, v) ->
                pw.println("    [$k] song='${v.song}' artist='${v.artist}' isPlaying=${v.isPlaying} active=${v.active} hasArtwork=${v.artwork != null}")
            }
        }
    }

    companion object {
        private const val TAG = "MediaSessionManager"
    }

    private data class ResolvedMediaSession(
        val playbackState: Int = PlaybackState.STATE_NONE,
        val albumArt: Drawable? = null,
        val appIcon: Drawable? = null,
        val mediaColor: Int? = null,
        val title: String = "",
        val artist: String = "",
    )
}
