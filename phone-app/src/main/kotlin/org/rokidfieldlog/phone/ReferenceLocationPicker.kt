package org.rokidfieldlog.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import org.rokidfieldlog.core.PhotographerReference

object ReferenceLocationPicker {
    fun add(activity: Activity, parent: LinearLayout, current: PhotographerReference?, changed: (PhotographerReference?) -> Unit) {
        val label = OfflineUi.text(activity, current?.label() ?: "未选用拍摄者参考位置（可选）")
        parent.addView(label)
        parent.addView(OfflineUi.text(activity, "只引用最近一次成功定位的历史结果，不代表照片拍摄时所在位置，也不会填入问题地址或问题坐标。"))
        parent.addView(OfflineUi.button(activity, "选用最近一次定位作为参考") {
            PrivateProbeStore.fileExecutor.execute {
                val fix = PrivateProbeStore(activity).lastReference()
                activity.runOnUiThread {
                    if (activity.isDestroyed || activity.isFinishing || !label.isAttachedToWindow) return@runOnUiThread
                    if (fix == null) AlertDialog.Builder(activity).setMessage("没有可用的成功定位。请先到定位检查获取位置；取消和失败结果不会变成坐标。")
                        .setNegativeButton("返回", null).setPositiveButton("去定位检查") { _, _ -> activity.startActivity(Intent(activity, LocationActivity::class.java)) }.show()
                    else AlertDialog.Builder(activity).setTitle("选用历史参考位置？").setMessage(fix.label() + "\n请核对时间；该位置不是问题位置。选用后会随材料交给接收方。")
                        .setNegativeButton("取消", null).setPositiveButton("选用参考") { _, _ -> changed(fix); label.text = fix.label() }.show()
                }
            }
        })
        parent.addView(OfflineUi.button(activity, "移除参考位置") { changed(null); label.text = "未选用拍摄者参考位置（可选）" })
    }
}
