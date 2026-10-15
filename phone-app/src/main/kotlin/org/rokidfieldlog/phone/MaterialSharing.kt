package org.rokidfieldlog.phone

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import org.rokidfieldlog.core.SharedMaterial

// A concrete provider subclass avoids device-specific failures documented by AndroidX.
class ReviewedPhotoProvider : FileProvider()

object MaterialSharing {
    fun launch(activity: Activity, material: SharedMaterial) {
        val uris = ArrayList(material.photos.map { FileProvider.getUriForFile(activity, activity.packageName + ".reviewed-shares", it) })
        val grant = ClipData.newUri(activity.contentResolver, "已审阅报修照片", uris.first())
        uris.drop(1).forEach { grant.addItem(ClipData.Item(it)) }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, material.text)
            putExtra(Intent.EXTRA_SUBJECT, "现场报修材料")
            clipData = grant
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "分享已审阅照片和说明"))
    }
    fun copyText(activity: Activity, text: String) {
        val clip = ClipData.newPlainText("报修说明", text)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}
