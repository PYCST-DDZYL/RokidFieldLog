package org.rokidfieldlog.glasses

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import java.util.concurrent.Executors

/** The glasses have one rear camera despite declaring a front-camera feature. */
class GlassesApplication : Application(), CameraXConfig.Provider {
    // CameraX may deliver a save/error callback after its Activity was destroyed.
    // One process-owned queue accepts that callback and releases its capture lease.
    val captureFileExecutor = Executors.newSingleThreadExecutor { work -> Thread(work, "FieldLogFiles") }
    private val config = CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
        .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
        .build()

    override fun getCameraXConfig(): CameraXConfig = config
}
