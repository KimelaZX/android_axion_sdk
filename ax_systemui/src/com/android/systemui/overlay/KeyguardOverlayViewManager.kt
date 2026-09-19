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
package com.android.systemui.overlay

import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.axion.compose.host.AxComposeView
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.edgelight.EdgeLight
import com.android.systemui.edgelight.EdgeLightInteractor
import com.android.systemui.media.MediaArt
import com.android.systemui.media.MediaArtInteractor
import com.android.systemui.pulse.PulseInteractor
import com.android.systemui.pulse.PulseVisualizer
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationShadeWindowView
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SysUISingleton
class KeyguardOverlayViewManager @Inject constructor(
    private val windowView: NotificationShadeWindowView,
    private val mediaArtInteractor: MediaArtInteractor,
    private val pulseInteractor: PulseInteractor,
    private val edgeLightInteractor: EdgeLightInteractor,
    private val dumpManager: DumpManager,
    @Application private val scope: CoroutineScope,
) : CoreStartable, Dumpable {

    private var mediaArtView: AxComposeView? = null
    private var pulseView: AxComposeView? = null
    private var edgeLightView: AxComposeView? = null

    override fun start() {
        dumpManager.registerNormalDumpable(TAG, this)

        val keyguardRoot = windowView.requireViewById<View>(R.id.keyguard_root_view)
        val notificationPanel = windowView.requireViewById<View>(R.id.notification_panel)

        val initialDozing = mediaArtInteractor.uiState.value.isDozing
        val initialAnchor = if (initialDozing) keyguardRoot else notificationPanel

        val mediaArt = createOverlayHost(windowView.indexOfChild(initialAnchor))
        val pulse = createOverlayHost(windowView.indexOfChild(keyguardRoot))
        val edgeLight = createOverlayHost(windowView.indexOfChild(keyguardRoot))

        mediaArtView = mediaArt
        pulseView = pulse
        edgeLightView = edgeLight

        KeyguardOverlayViewBinder.bind(mediaArt) {
            val state by mediaArtInteractor.uiState.collectAsStateWithLifecycle()
            MediaArt(state = state)
        }
        KeyguardOverlayViewBinder.bind(pulse) {
            val state by pulseInteractor.uiState.collectAsStateWithLifecycle()
            PulseVisualizer(state = state)
        }
        KeyguardOverlayViewBinder.bind(edgeLight) {
            val state by edgeLightInteractor.uiState.collectAsState()
            EdgeLight(state = state)
        }

        scope.launch(Dispatchers.Main.immediate) {
            mediaArtInteractor.uiState
                .map { it.isDozing }
                .distinctUntilChanged()
                .collect { isDozing ->
                    updateMediaArtHierarchy(mediaArt, isDozing)
                }
        }
    }

    private fun updateMediaArtHierarchy(mediaArtView: View, isDozing: Boolean) {
        val currentIndex = windowView.indexOfChild(mediaArtView)
        if (currentIndex < 0) return

        val targetAnchor = if (isDozing) {
            pulseView ?: windowView.requireViewById<View>(R.id.keyguard_root_view)
        } else {
            windowView.requireViewById<View>(R.id.notification_panel)
        }
        val targetAnchorIndex = windowView.indexOfChild(targetAnchor)
        if (targetAnchorIndex < 0) return

        val targetIndex = if (currentIndex < targetAnchorIndex) {
            targetAnchorIndex - 1
        } else {
            targetAnchorIndex
        }

        if (currentIndex != targetIndex) {
            windowView.removeView(mediaArtView)
            windowView.addView(mediaArtView, targetIndex)
        }
    }

    private fun createOverlayHost(index: Int): AxComposeView {
        val host = AxComposeView(windowView.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        windowView.addView(host, index)
        return host
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("KeyguardOverlayViewManager:")
        pw.println("  mediaArtIndex: ${mediaArtView?.let { windowView.indexOfChild(it) } ?: -1}")
        pw.println("  pulseIndex: ${pulseView?.let { windowView.indexOfChild(it) } ?: -1}")
        pw.println("  edgeLightIndex: ${edgeLightView?.let { windowView.indexOfChild(it) } ?: -1}")
        pw.println("  keyguardRootIndex: ${windowView.indexOfChild(windowView.findViewById(R.id.keyguard_root_view))}")
        pw.println("  notificationPanelIndex: ${windowView.indexOfChild(windowView.findViewById(R.id.notification_panel))}")
        pw.println("  isDozing: ${mediaArtInteractor.uiState.value.isDozing}")
    }

    companion object {
        private const val TAG = "KeyguardOverlayViewManager"
    }
}
