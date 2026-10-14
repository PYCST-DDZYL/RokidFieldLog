# 拍照中断恢复：实现与验证

> 历史记录：保留本次版本之前的调查、计划和测试证据；当前状态只维护在 [README 状态表](../README.md#current-status)，版本变化见 [CHANGELOG](../CHANGELOG.md)，现行验收计划见 [roadmap](roadmap.md)。下文时间、测试数量和交互描述不表示当前版本已通过。


本文保留 2026-10-12 的验证快照；最新指纹、快捷标签、时钟检查及 13 项存储测试见 [可靠性更新](reliability-update.md)。当前 Test-StorageRecovery.ps1 预期为 13 passed。

更新：2026-10-12（用户所在时区）。本功能属于 Phase 1 的本机数据可靠性，不代表手机同步或定位已完成。

## 行为

开始拍照前，先在 App 私有 files/fieldlog/journals 写入带 CRC 校验的请求日志，包含 photoId、原始 UTC 请求时间及本机单调时钟。日志持久化失败则不启动拍照，也不会显示成功。

照片先写入 photos/UUID.pending.jpg。保存时检查 JPEG 起止标记、尺寸和缩小像素解码，完成后改为 UUID.jpg，再用 AtomicFile 保存 JSON。只有照片和记录均写入成功才显示“本机已保存 · 位置待确认”。

如果照片写好而 JSON 保存失败，提示“照片已保存 · 重开恢复记录”。再次打开 App 时扫描本包自己的 photos、journals 和 records，并重新解码校验已保存照片：

- 完整照片／缺失记录：重建记录；恢复照片仍是本机原文件。
- 完整 pending JPEG：完成持久化，重建记录。
- 损坏或需要替换的 JSON：先保留原始字节到 records/recovery-backups，再写恢复记录。
- 有照片但没有可信请求日志：拍摄时间保持 null，时间来源标为 UNKNOWN_RECOVERED_FILE。
- 有可信日志但不知道照片写完时间：保留原请求时间，完成时间为 null，来源为 JOURNALED_REQUEST_COMPLETION_UNKNOWN。
- 截断／不可解码图片：保留文件，标记 INCOMPLETE，不计作恢复成功。
- 已完整保存的旧记录：保持原始内容，不重写时间或坐标。
- 原记录已保存，但图片后来损坏／丢失：photoIntegrityStatus 标为 UNREADABLE／MISSING，保留原拍摄状态、时间及人工修改，不计入有效照片数量。
- 同 photoId、schemaVersion=1 的结构修复：保留原始时间、定位观察、问题位置和说明，再修改待恢复的结构字段；先备份原记录。
- 元数据 ID 与文件不同：不关联错误记录的坐标和说明；保留备份。未知版本元数据保留且报告错误，不冒充已兼容。

元数据读取限制为 256 KiB，避免一次读入过大记录；不支持的内容保留备份。照片计数来自已通过校验的图片集合，不再单纯统计 .jpg 文件名。

恢复时间单独记录为 recoveredAtUtcMillis，不能当作拍摄时间。恢复后的单调时钟标记为原拍摄开机周期尚未确认，Phase 2 不可直接与当前手机或当前眼镜 uptime 比较。手机位置和问题位置仍为 null／待确认。

恢复在文件线程执行，完成后才初始化相机。不同 Activity 的保存和恢复共享锁；尚在保存的请求不会被新页面误判为中断。恢复、拍摄或写入失败都保留文件，不通过自动删除证据解决错误。

## 创建／修改的文件

- fieldlog-core/src/main/kotlin/org/rokidfieldlog/core/CaptureFiles.kt：请求日志、CRC、路径 ID 校验、照片持久化。
- fieldlog-core/src/test/kotlin/org/rokidfieldlog/core/CaptureFilesTest.kt：8 项存储中断／完整性测试，包含只有旧记录但没有日志／图片的情况。
- glasses-app/src/main/kotlin/org/rokidfieldlog/glasses/PrivateCaptureStore.kt：JPEG 解码、AtomicFile、恢复、原记录备份。
- glasses-app/src/main/kotlin/org/rokidfieldlog/glasses/MainActivity.kt：启动恢复、状态提示、失败时保留文件。
- glasses-app/build.gradle.kts：引用共享模块。
- glasses-app/src/debug/AndroidManifest.xml、debug/kotlin/org/rokidfieldlog/glasses/RecoveryInstrumentation.kt：仅 debug 的隔离 Android 存储测试入口，release 不包含它。
- scripts/Test-StorageRecovery.ps1：核对眼镜后运行隔离测试。
- scripts/Test-Apk.ps1：检查新增启动依赖和 debug 测试类实际进入 APK。
- scripts/Read-PhoneProbe.ps1：手机连接后的私有定位结果检查；当前只验证了无手机时安全拒绝，未取得真实 GPS。

## 如何复测及预期结果

在项目根目录执行：

```powershell
.\scripts\Build.ps1 -Verify -Clean
.\scripts\Install-App.ps1 -Target glasses -ConfirmInstallation -StartApp
.\scripts\Test-StorageRecovery.ps1 -ConfirmDeviceTest
```

本阶段安装和测试已获得用户授权。预期构建成功、APK verified、隔离存储测试 9 passed。测试使用 Android 原生 Instrumentation；不另装测试 App，不增加权限。

测试夹具是 16×16 合成 JPEG 和明确的测试时间，只放在此 App 私有 files/fieldlog/test-fixtures/独立UUID 中。它们验证 Android BitmapFactory、AtomicFile 和恢复逻辑，不验证相机或 GPS，不能导出为实际现场记录。照片计数及正式记录扫描不会包含该目录。夹具保留供检查，“删除全部项目数据”的用户确认操作及卸载都会清理它们；本次没有执行清理。

测试结束后重新打开 FieldLog，原照片应仍在。站定，单指点击触控板，预期保存真实 JPEG、请求日志及对应 JSON，随后隐藏提示。无需删除照片、关闭定位或撤销权限来制造错误。

## 实际验证

- 核心单元测试 18 项通过：位置质量 5、回调／截止时间 5、存储事务 8。未改动的测试任务可能复用之前已通过的结果。
- 两端构建与 APK 定义类检查通过；眼镜 Lint 0 错误 / 8 警告，手机 0 错误 / 1 警告。警告为依赖更新和 KTX 写法建议。
- 实际眼镜上隔离存储测试 9 项通过：完整 pending 恢复、损坏 JSON 备份、旧孤立照片时间为空、截断 JPEG 保留、完整记录不变、已保存图片损坏、旧图片丢失、人工说明／位置保留、元数据 ID 不匹配。
- 最近隔离测试前后，正式记录数量均为 2，两条原记录字节内容不变。
- 新版再次真实拍照成功：4032×3024，66 字节请求日志，captureState=SAVED，坐标字段为 null；电脑端 JPEG 完整解码成功。
- 两次菜单确认测试在修复前触发了测试照片，均保留。已通过 View.OnKeyListener 修复背景点击抢走 Enter 的问题；修复后单次 Enter 只生成一张真实照片，4 条旧记录字节内容不变。当前共 5 张真实照片，隔离夹具没有混入正式记录。
- 冷启动：相机就绪，恢复检查 restored=0 / incomplete=0 / errors=0，原始请求时间保持不变。
- 最新黑底绿字删除框与返回取消路径已真机验证：发送 Back 后提示隐藏，测试前后的 4 张记录仍在，App 进程存在。随后单次拍照增加为 5 张。实际确认删除未执行。

真实照片、JSON 和测试输出只保留在电脑项目被忽略的 .local/device。没有模拟定位、合成现场照片或声称通信通过。

尚未在眼镜上实际断电／存储耗尽验证；隔离测试覆盖的是受控文件状态，不能代替所有固件及文件系统故障。手机 GPS、双端时差、实际触控板及光学可读性仍待验收。

官方接口依据：[Android Instrumentation](https://developer.android.com/reference/android/app/Instrumentation)。
