/*
 * Copyright (C) 2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.shared.clocks.depth.data.repository

import android.app.WallpaperManager
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.android.axion.util.DisplayUtils
import com.android.systemui.shared.clocks.depth.shared.model.DepthMaskModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
class DepthWallpaperRepositoryImpl
@Inject
constructor(
    private val context: Context,
    private val bgDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope = CoroutineScope(bgDispatcher),
) : DepthWallpaperRepository {

    private val _isDepthEnabled = MutableStateFlow(false)
    override val isDepthEnabled: StateFlow<Boolean> = _isDepthEnabled.asStateFlow()

    private val _depthMask = MutableStateFlow<DepthMaskModel?>(null)
    override val depthMask: StateFlow<DepthMaskModel?> = _depthMask.asStateFlow()

    private val _wallpaperZoom = MutableStateFlow(1.0f)
    override val wallpaperZoom: StateFlow<Float> = _wallpaperZoom.asStateFlow()

    private val appContext = context.applicationContext ?: context
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val wallpaperManager: WallpaperManager? = WallpaperManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshGeneration = AtomicInteger()
    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    init {
        DisplayUtils.DisplayLayout.values().forEach {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(it.getSettingName(SETTING_DEPTH_MASK)),
                false,
                observer,
            )
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_DEPTH_ENABLED),
            false,
            observer,
        )
        refresh()
    }

    override fun setWallpaperZoom(zoom: Float) {
        _wallpaperZoom.value = zoom
    }

    override fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        refreshJob?.cancel()
        refreshJob = scope.launch(bgDispatcher) {
            try {
                val liveInfo = wallpaperManager?.wallpaperInfo
                val enabled =
                    Settings.Secure.getInt(contentResolver, SETTING_DEPTH_ENABLED, 0) == 1 &&
                        (liveInfo == null ||
                            hasLockWallpaper() ||
                            (liveInfo.component.packageName == EFFECTS_PACKAGE &&
                                liveInfo.component.className.endsWith(MAGIC_PORTRAIT_SERVICE)))

                val paths = if (enabled) {
                    DisplayUtils.DisplayLayout.values()
                        .map { it.getSettingName(SETTING_DEPTH_MASK) }
                        .distinct()
                        .associateWith { setting ->
                            Settings.Secure.getString(contentResolver, setting)?.let(::decodePath)
                        }
                } else {
                    emptyMap()
                }

                if (refreshGeneration.get() != generation) return@launch
                _isDepthEnabled.value = enabled
                val settingName = DisplayUtils.getCurrentDisplayLayout(appContext).getSettingName(SETTING_DEPTH_MASK)
                val result = paths[settingName] ?: paths.values.firstOrNull()
                _depthMask.value = result?.first?.let { DepthMaskModel(it, result.second) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh depth data", e)
                if (refreshGeneration.get() != generation) return@launch
                _depthMask.value = null
            }
        }
    }

    private fun decodePath(base64Str: String): Pair<Path, Float>? {
        return try {
            val bytes = Base64.decode(base64Str, Base64.NO_WRAP)
            if (bytes.size < 7) return null
            if (bytes[0].toInt() and 0xFF != PATH_VERSION) return null

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.get()

            val extractW = buf.short.toInt() and 0xFFFF
            val extractH = buf.short.toInt() and 0xFFFF
            val numContours = buf.short.toInt() and 0xFFFF
            if (extractW <= 0 || extractH <= 0 || numContours <= 0) return null

            val path = Path().apply { fillType = Path.FillType.WINDING }
            for (c in 0 until numContours) {
                if (buf.remaining() < 2) break
                val numPoints = buf.short.toInt() and 0xFFFF
                if (numPoints < 3 || buf.remaining() < numPoints * 4) continue

                for (p in 0 until numPoints) {
                    val x = (buf.short.toInt() and 0xFFFF).toFloat()
                    val y = (buf.short.toInt() and 0xFFFF).toFloat()
                    if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            Pair(path, extractW.toFloat() / extractH)
        } catch (e: Exception) {
            null
        }
    }

    private fun hasLockWallpaper(): Boolean {
        return try {
            wallpaperManager?.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { true } == true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "DepthWallpaperRepository"
        private const val SETTING_DEPTH_MASK = "ax_depth_subject_mask"
        private const val SETTING_DEPTH_ENABLED = "ax_depth_clock_enabled"
        private const val EFFECTS_PACKAGE = "com.android.axion.wallpapereffects"
        private const val MAGIC_PORTRAIT_SERVICE = "MagicPortraitService"
        private const val PATH_VERSION = 0x01
    }
}
