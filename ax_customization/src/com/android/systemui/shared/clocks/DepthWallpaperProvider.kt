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

package com.android.systemui.shared.clocks

import android.content.Context
import com.android.systemui.shared.clocks.depth.data.repository.DepthWallpaperRepository
import com.android.systemui.shared.clocks.depth.data.repository.DepthWallpaperRepositoryImpl
import com.android.systemui.shared.clocks.depth.domain.interactor.DepthWallpaperInteractor
import com.android.systemui.shared.clocks.depth.domain.interactor.DepthWallpaperInteractorImpl
import java.io.PrintWriter

object DepthWallpaperProvider {

    @Volatile
    private var repositoryInstance: DepthWallpaperRepository? = null

    @Volatile
    private var interactorInstance: DepthWallpaperInteractor? = null

    @Volatile
    private var cachedWallpaperZoom: Float = 1.0f

    fun getRepository(context: Context): DepthWallpaperRepository {
        return repositoryInstance ?: synchronized(this) {
            repositoryInstance ?: DepthWallpaperRepositoryImpl(context.applicationContext ?: context).also { repo ->
                repo.setWallpaperZoom(cachedWallpaperZoom)
                repositoryInstance = repo
            }
        }
    }

    fun getInteractor(context: Context): DepthWallpaperInteractor {
        return interactorInstance ?: synchronized(this) {
            interactorInstance ?: DepthWallpaperInteractorImpl(getRepository(context)).also {
                interactorInstance = it
            }
        }
    }

    val isEnabled: Boolean
        get() = repositoryInstance?.isDepthEnabled?.value ?: false

    val currentWallpaperZoom: Float
        get() = repositoryInstance?.wallpaperZoom?.value ?: cachedWallpaperZoom

    fun init(context: Context) {
        getInteractor(context)
    }

    fun setWallpaperZoom(zoom: Float) {
        cachedWallpaperZoom = zoom
        repositoryInstance?.setWallpaperZoom(zoom)
    }

    fun dump(pw: PrintWriter) {
        pw.println("DepthWallpaperProvider:")
        pw.println("  isEnabled=$isEnabled")
        pw.println("  currentWallpaperZoom=$currentWallpaperZoom (cached=$cachedWallpaperZoom)")
        pw.println("  hasRepository=${repositoryInstance != null}")
        pw.println("  hasInteractor=${interactorInstance != null}")
    }
}
