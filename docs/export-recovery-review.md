# 导出与页面恢复检查（2026-10-18，第二轮）

本轮检查单照片/多照片材料保存失败、报告页面重建，以及文件流关闭。所有文件回归在项目自己的 build 目录中使用合成内容；未操作设备、真实照片或用户实际选择的文件。

## 修复与修改文件

| 文件 | 具体修改 |
| --- | --- |
| `fieldlog-core/.../MaterialDocumentCopy.kt` | 验证后先取得源流，再打开目标。源文件打不开时目标不被创建、截断或删除；目标打开失败时关闭源流，关闭的次要错误不覆盖原始错误 |
| `fieldlog-core/src/test/.../MaterialDocumentCopyTest.kt` | 增加 3 项回归：现有目标逐字节保留、源缺失时不创建新目标、目标权限错误下源流关闭及错误保留。既有清理拒绝测试改为实际写入错误，避免在新流程中没有触发清理却误报覆盖 |
| `phone-app/.../ReportActivity.kt` | 抽出报告读取，导出结果到达时若表单尚未重建，成功/失败都安排读取并保留提示；待恢复的编辑保留到读取成功，连续重建时也可写入 Bundle。已有表单继续保留未保存编辑，过期 revision 不覆盖新记录 |
| 两端 `build.gradle.kts` | 手机 code 11 / 0.7.3-export-recovery，眼镜 code 6 / 0.2.3-reliability；沿用固定证书 |
| `README.md`、`CHANGELOG.md`、本文 | 更新检查入口与证据 |

页面问题来自代码路径核对：Android 会先交付 onActivityResult，再调用 onResume；若此前页面被重建，导出任务可能令 onResume 因 busy 跳过首次读取，而原完成回调只显示结果文字。该顺序依据 [Activity 官方参考](https://developer.android.com/reference/android/app/Activity#onActivityResult(int,int,android.content.Intent))；系统可能在其他 Activity 运行期间回收原页面，因此状态也必须独立保存，见[官方结果处理说明](https://developer.android.com/training/basics/intents/result)。这是代码推导出的故障路径，不是本轮手机实测结果。

## 已执行与待确认

先在旧复制实现上运行 9 项 `MaterialDocumentCopyTest`，6 项既有测试通过、3 项新回归失败。两个文件回归实际观察到源文件打不开后目标仍被打开/清理；全部使用可丢弃的合成文件。另一个回归约束调整顺序后的源流关闭行为。日志：`.local/export-review-before.log`。

修复后完整 JVM 测试为 **211 项，0 失败、0 错误**（原 208 项加 3 项回归），文件复制 9 项全部通过。测试验证源打不开时已有目标的字节保持不变、新目标不被复制函数创建；目标打开失败时源流关闭，关闭/清理的次要错误不会覆盖原错误；成功复制和写入后失败的既有回归继续通过。

干净发布构建为 `BUILD SUCCESSFUL in 4m 12s`（115 项任务，113 执行、2 已是最新）。两端包名、必需 DEX 类、不可调试属性和固定签名检查通过；手机分享 Provider 的缓存目录范围检查通过。Lint 为 0 错误，眼镜 16 条警告、手机 64 条警告，仍保留依赖更新、KTX、文本国际化和旧系统属性提示。

| 项目内 APK | 版本 | 大小 / SHA-256 |
| --- | --- | --- |
| `phone-app/build/outputs/apk/release/phone-app-release.apk` | 0.7.3-export-recovery / code 11 | 7,361,480 bytes；`fe39957a5d0ea10eef57f5baaac40f8249c39d826feda6c53a584dce658b39b4` |
| `glasses-app/build/outputs/apk/release/glasses-app-release.apk` | 0.2.3-reliability / code 6 | 6,975,843 bytes；`fee4ff82222e4ff6190af0125d4c8fa711ef4304afe5980d7a3825bc92917d8e` |

沿用发布证书 SHA-256：`8fc71c5d65b036317565fe78d950b1562976dbec3dfe09d4b98d8dce09506674`。完整日志 `.local/export-review-release.log`，JVM 报告 `fieldlog-core/build/reports/tests/test/`，两端 Lint 报告在各自 `build/reports/lint-results-release.html`。远端执行结论见 [Actions](https://github.com/PYCST-DDZYL/RokidFieldLog/actions)。

页面恢复修改需要真机复测。不能把文件流 JVM 测试当成系统文件选择器、页面旋转、进程回收或具体文档提供者已通过。

## 复测方法与预期

在 `RokidFieldLog` 项目终端运行：

```powershell
.\scripts\Build.ps1 -Release -Verify -Clean *> .local/export-review-retest.log
Get-Content .local/export-review-retest.log -Tail 25
```

预期 Gradle `BUILD SUCCESSFUL`、两次 `APK verified`，211 项核心回归全通过。发布构建需要本机保留的 `.private` 密钥；不要另建密钥替换。

设备上先用明确标记的合成演示材料验证：打开保存位置选择器时旋转页面，返回后表单应继续可见；成功时提示保存完成，拒绝/撤销访问或写入失败时应提示失败并可继续编辑。再次进入读取过程时旋转，应保留同一 revision 的待恢复编辑。先使用新的测试目标，避免拿真实已导出文件做故障实验。

手机使用 `ACTION_CREATE_DOCUMENT` 创建新文档；标准行为为同名自动编号，见[官方保存文件说明](https://developer.android.com/training/data-storage/shared/documents-files#create-a-new-file)。通用复制函数额外用“已存在目标”夹具检查提前改动的边界。

文件选择器/提供者可能已创建空文档，失败后也可能留下空或部分文件。App 仅在本次已打开目标、开始写入后尝试清理，提供者可能拒绝；失败提示不表示外部位置已彻底回滚。
