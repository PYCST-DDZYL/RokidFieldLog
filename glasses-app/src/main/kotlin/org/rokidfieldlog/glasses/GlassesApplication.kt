package org.rokidfieldlog.glasses

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig

/** The glasses have one rear camera despite declaring a front-camera feature. */
class GlassesApplication : Application(), CameraXConfig.Provider {
    private val config = CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
        .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
        .build()

    override fun getCameraXConfig(): CameraXConfig = config
}
