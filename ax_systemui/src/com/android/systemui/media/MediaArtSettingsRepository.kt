/*
 * Copyright (C) 2025-2026 AxionOS
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

import android.os.UserHandle
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

data class MediaArtSettings(
    val isEnabled: Boolean = false,
    val blurLevel: Int = 0,
    val artStyle: Int = 0
)

@SysUISingleton
class MediaArtSettingsRepository @Inject constructor(
    private val secureSettings: SecureSettings,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val dumpManager: DumpManager,
) : Dumpable {
    companion object {
        const val LS_MEDIA_ART_ENABLED = "ls_media_art_enabled"
        const val LS_MEDIA_ART_BLUR = "ls_media_art_blur"
        const val LS_MEDIA_ART_STYLE = "ls_media_art_style"
        private const val DEFAULT_ENABLED = false
        private const val DEFAULT_BLUR = 0
        private const val DEFAULT_STYLE = 0
        private const val TAG = "MediaArtSettingsRepository"
    }

    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    val settingsFlow: Flow<MediaArtSettings> = secureSettings
        .observerFlow(
            UserHandle.USER_ALL,
            LS_MEDIA_ART_ENABLED,
            LS_MEDIA_ART_BLUR,
            LS_MEDIA_ART_STYLE
        )
        .onStart { emit(Unit) }
        .map { readSettings() }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)

    private fun readSettings(): MediaArtSettings {
        return MediaArtSettings(
            isEnabled = secureSettings.getIntForUser(
                LS_MEDIA_ART_ENABLED,
                if (DEFAULT_ENABLED) 1 else 0,
                UserHandle.USER_CURRENT
            ) == 1,
            blurLevel = secureSettings.getIntForUser(
                LS_MEDIA_ART_BLUR,
                DEFAULT_BLUR,
                UserHandle.USER_CURRENT
            ),
            artStyle = secureSettings.getIntForUser(
                LS_MEDIA_ART_STYLE,
                DEFAULT_STYLE,
                UserHandle.USER_CURRENT
            )
        )
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val settings = readSettings()
        pw.println("MediaArtSettingsRepository:")
        pw.println("  isEnabled: ${settings.isEnabled}")
        pw.println("  blurLevel: ${settings.blurLevel}")
        pw.println("  artStyle: ${settings.artStyle}")
        pw.println("  raw_enabled: ${secureSettings.getIntForUser(LS_MEDIA_ART_ENABLED, -1, UserHandle.USER_CURRENT)}")
        pw.println("  raw_blur: ${secureSettings.getIntForUser(LS_MEDIA_ART_BLUR, -1, UserHandle.USER_CURRENT)}")
        pw.println("  raw_style: ${secureSettings.getIntForUser(LS_MEDIA_ART_STYLE, -1, UserHandle.USER_CURRENT)}")
    }
}
