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

package com.android.systemui.shared.clocks.view

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Region
import android.util.DisplayMetrics
import android.view.View
import com.android.app.animation.Interpolators
import com.android.systemui.shared.clocks.DepthWallpaperProvider
import com.android.systemui.shared.clocks.depth.domain.interactor.DepthWallpaperInteractor
import com.android.systemui.shared.clocks.depth.shared.model.DepthMaskModel
import java.io.PrintWriter
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ClockDepthController(private val view: View) {

    var enabled = true

    private val interactor: DepthWallpaperInteractor = DepthWallpaperProvider.getInteractor(view.context)

    private var currentMask: DepthMaskModel? = null
    private var depthVisible = true
    private var maskAlpha = 0f
    private var revealProgress = 0f
    private var maskAnimator: ValueAnimator? = null
    private var observeScope: CoroutineScope? = null

    private val transformedPath = Path()
    private val revealPath = Path()
    private val pathMatrix = Matrix()
    private val revealMatrix = Matrix()
    private val pathBounds = RectF()
    private val revealBounds = RectF()
    private val layerRect = RectF()
    private val coverageRegion = Region()
    private val coverageClip = Region()
    private val coverageDiff = Region()

    private var metrics = TransformMetrics()
    private var pathDirty = true

    var sourceBoundsProvider: (() -> RectF?)? = null
        set(value) {
            field = value
            resetTransformCache()
            view.postInvalidateOnAnimation()
        }

    var sourceScale = 1f
        set(value) {
            if (field == value) return
            field = value
            resetTransformCache()
            view.postInvalidateOnAnimation()
        }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val maskXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    fun onAttached() {
        if (!enabled) return
        val scope = CoroutineScope(Dispatchers.Main)
        observeScope = scope

        scope.launch {
            interactor.activeDepthMask.collect { mask ->
                val wasActive = currentMask != null
                currentMask = mask
                pathDirty = true

                if (mask != null && (!wasActive || maskAlpha < 1f)) {
                    animateReveal()
                } else if (mask == null && wasActive) {
                    hideDepth()
                } else {
                    view.postInvalidateOnAnimation()
                }
            }
        }

        scope.launch {
            interactor.wallpaperScale.collect { scale ->
                if (metrics.zoom != scale) {
                    metrics = metrics.copy(zoom = scale)
                    pathDirty = true
                    view.postInvalidateOnAnimation()
                }
            }
        }
    }

    fun onDetached() {
        hideDepth()
        observeScope?.let {
            it.launch { }
            observeScope = null
        }
        currentMask = null
    }

    fun onConfigurationChanged() {
        resetTransformCache()
        view.postInvalidateOnAnimation()
    }

    fun onDozeChanged(dozing: Boolean) {
        interactor.setDozing(dozing)
        if (dozing) {
            hideDepth()
        }
    }

    fun onDozeAmountChanged(dozeAmount: Float) {
        interactor.setDozeAmount(dozeAmount)
        if (dozeAmount > 0f) {
            hideDepth()
        }
    }

    fun setDepthVisible(visible: Boolean) {
        if (depthVisible == visible) return
        depthVisible = visible
        interactor.setVisible(visible)
        if (!visible) {
            hideDepth()
        }
    }

    fun shouldApplyDepth(): Boolean {
        return enabled && depthVisible && currentMask != null && maskAlpha > 0f
    }

    fun drawWithDepth(canvas: Canvas, drawSuper: (Canvas) -> Unit) {
        val mask = currentMask
        if (mask == null || !shouldApplyDepth()) {
            drawSuper(canvas)
            return
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        if (location[0] == 0 && location[1] == 0 && view.width == 0 && view.height == 0) {
            drawSuper(canvas)
            return
        }

        updateMetricsAndTransform(location[0].toFloat(), location[1].toFloat(), mask)

        val screenW = metrics.screenW
        val screenH = metrics.screenH
        val translatedViewX = metrics.viewX
        val translatedViewY = metrics.viewY

        transformedPath.computeBounds(pathBounds, true)

        val layerLeft = -translatedViewX
        val layerTop = -translatedViewY
        val layerRight = screenW - translatedViewX
        val layerBottom = screenH - translatedViewY
        layerRect.set(layerLeft, layerTop, layerRight, layerBottom)

        if (!RectF.intersects(pathBounds, layerRect)) {
            drawSuper(canvas)
            return
        }

        renderMasked(canvas, drawSuper, layerLeft, layerTop, layerRight, layerBottom)
    }

    private fun updateMetricsAndTransform(viewX: Float, viewY: Float, mask: DepthMaskModel) {
        val viewScaleX = view.scaleX
        val viewScaleY = view.scaleY
        val sourceBounds = sourceBoundsProvider?.invoke()
        val boundsW = sourceBounds?.width() ?: 0f
        val boundsH = sourceBounds?.height() ?: 0f

        val nextScreenW: Float
        val nextScreenH: Float
        val nextViewX: Float
        val nextViewY: Float
        val nextZoom: Float

        if (sourceBounds != null && boundsW > 0f && boundsH > 0f) {
            nextScreenW = boundsW / sourceScale
            nextScreenH = boundsH / sourceScale
            nextViewX = (viewX - sourceBounds.left) / sourceScale
            nextViewY = (viewY - sourceBounds.top) / sourceScale
            nextZoom = 1f
        } else {
            val realMetrics = DisplayMetrics()
            val display = view.display ?: view.context.display
            if (display != null) {
                display.getRealMetrics(realMetrics)
            } else {
                realMetrics.setTo(view.resources.displayMetrics)
            }
            nextScreenW = realMetrics.widthPixels.toFloat()
            nextScreenH = realMetrics.heightPixels.toFloat()
            nextViewX = viewX
            nextViewY = viewY
            nextZoom = interactor.wallpaperScale.value
        }

        val nextMetrics = TransformMetrics(
            screenW = nextScreenW,
            screenH = nextScreenH,
            viewX = nextViewX,
            viewY = nextViewY,
            viewScaleX = viewScaleX,
            viewScaleY = viewScaleY,
            zoom = nextZoom,
        )

        if (metrics != nextMetrics) {
            metrics = nextMetrics
            pathDirty = true
        }

        if (!pathDirty) return

        val screenW = metrics.screenW
        val screenH = metrics.screenH
        val wallAspect = mask.aspect
        val screenAspect = screenW / screenH
        val cropLeft: Float
        val cropTop: Float
        val visibleW: Float
        val visibleH: Float

        if (wallAspect > screenAspect) {
            visibleW = (screenAspect / wallAspect) * 10000f
            visibleH = 10000f
            cropLeft = (10000f - visibleW) / 2f
            cropTop = 0f
        } else {
            visibleW = 10000f
            visibleH = (wallAspect / screenAspect) * 10000f
            cropLeft = 0f
            cropTop = (10000f - visibleH) / 2f
        }

        pathMatrix.reset()
        pathMatrix.setTranslate(-cropLeft, -cropTop)
        pathMatrix.postScale(screenW / visibleW, screenH / visibleH)
        if (metrics.zoom != 1f) {
            pathMatrix.postScale(metrics.zoom, metrics.zoom, screenW / 2f, screenH / 2f)
        }
        pathMatrix.postTranslate(-metrics.viewX, -metrics.viewY)

        if (metrics.viewScaleX != 1f || metrics.viewScaleY != 1f) {
            pathMatrix.postScale(1f / metrics.viewScaleX, 1f / metrics.viewScaleY)
        }

        transformedPath.reset()
        mask.path.transform(pathMatrix, transformedPath)
        pathDirty = false
    }

    private fun renderMasked(
        canvas: Canvas,
        drawSuper: (Canvas) -> Unit,
        layerLeft: Float,
        layerTop: Float,
        layerRight: Float,
        layerBottom: Float,
    ) {
        val clockViewW = view.width.toFloat()
        val clockViewH = view.height.toFloat()
        val viewRect = RectF(0f, 0f, clockViewW, clockViewH)
        val coversCompletely = pathFullyCovers(viewRect)

        if (coversCompletely) {
            val layerCount = canvas.saveLayer(layerLeft, layerTop, layerRight, layerBottom, null)
            canvas.save()
            canvas.clipOutPath(transformedPath)
            drawSuper(canvas)
            canvas.restore()
            canvas.restoreToCount(layerCount)
            return
        }

        var maskPath = transformedPath
        if (revealProgress < 1f) {
            transformedPath.computeBounds(revealBounds, false)
            val s = REVEAL_MIN_SCALE + (1f - REVEAL_MIN_SCALE) * revealProgress
            revealMatrix.setScale(s, s, revealBounds.centerX(), revealBounds.centerY())
            revealMatrix.postTranslate(0f, PARALLAX_PX * (1f - revealProgress))
            revealPath.reset()
            transformedPath.transform(revealMatrix, revealPath)
            maskPath = revealPath
        }

        val layerCount = canvas.saveLayer(layerLeft, layerTop, layerRight, layerBottom, null)
        drawSuper(canvas)

        maskPaint.alpha = (maskAlpha * 255f).toInt().coerceIn(0, 255)
        maskPaint.xfermode = maskXfermode
        canvas.drawPath(maskPath, maskPaint)
        maskPaint.xfermode = null
        maskPaint.alpha = 255

        canvas.restoreToCount(layerCount)
    }

    private fun hideDepth() {
        maskAnimator?.cancel()
        maskAnimator = null
        maskAlpha = 0f
        revealProgress = 0f
        view.postInvalidateOnAnimation()
    }

    private fun pathFullyCovers(layerRect: RectF): Boolean {
        coverageClip.set(
            floor(layerRect.left).toInt(),
            floor(layerRect.top).toInt(),
            ceil(layerRect.right).toInt(),
            ceil(layerRect.bottom).toInt()
        )
        coverageRegion.setPath(transformedPath, coverageClip)
        coverageDiff.set(coverageClip)
        coverageDiff.op(coverageRegion, Region.Op.DIFFERENCE)
        return coverageDiff.isEmpty
    }

    private fun animateReveal() {
        maskAnimator?.cancel()
        maskAnimator = ValueAnimator.ofFloat(maskAlpha, 1f).apply {
            duration = REVEAL_DURATION
            interpolator = Interpolators.EMPHASIZED_DECELERATE
            addUpdateListener {
                val v = it.animatedValue as Float
                maskAlpha = v
                revealProgress = v
                view.postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun resetTransformCache() {
        metrics = TransformMetrics()
        pathDirty = true
    }

    fun dump(pw: PrintWriter) {
        pw.println("ClockDepthController:")
        pw.println("  enabled=$enabled")
        pw.println("  depthVisible=$depthVisible")
        pw.println("  maskAlpha=$maskAlpha")
        pw.println("  revealProgress=$revealProgress")
        pw.println("  hasMask=${currentMask != null}")
        pw.println("  shouldApplyDepth=${shouldApplyDepth()}")
        pw.println("  metrics=$metrics")
        pw.println("  pathBounds=$pathBounds")
        pw.println("  layerRect=$layerRect")
        pw.println("  maskAnimatorRunning=${maskAnimator?.isRunning}")
    }

    private data class TransformMetrics(
        val screenW: Float = 0f,
        val screenH: Float = 0f,
        val viewX: Float = Float.NaN,
        val viewY: Float = Float.NaN,
        val viewScaleX: Float = 1f,
        val viewScaleY: Float = 1f,
        val zoom: Float = 1f,
    )

    private companion object {
        const val TAG = "ClockDepthController"
        const val DEBUG = false
        const val REVEAL_DURATION = 600L
        const val REVEAL_MIN_SCALE = 0.97f
        const val PARALLAX_PX = 24f
    }
}
