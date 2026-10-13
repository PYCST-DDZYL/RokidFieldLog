# 2026-10-13：可靠性改进与验收

这次主要修复软件；用户说明手机当前未连接。手机真实定位、双端通信、地图和导出仍未完成验收。AI 已列入后续目标，尚无经过验收的模型；见 recognition-readiness.md。

## 修改的文件与原因

| 文件 | 改动 |
| --- | --- |
| scripts/Invoke-ProjectNativeCapture.ps1、Use-ProjectEnvironment.ps1、Prepare-Tools.ps1、Read-PhoneProbe.ps1、Test-StorageRecovery.ps1 | Windows PowerShell 5.1 会把重定向的原生 stderr 转成 ErrorRecord；现在保留诊断输出并检查真实进程退出码，避免将 Java 正常输出当作错误。 |
| scripts/*.ps1、Test-ToolEnvironment.ps1 | 脚本使用 UTF-8 BOM，使自带 PowerShell 正确读取中文；新增 stderr、非零退出、缺失程序、stdin 和语法回归检查。 |
| scripts/Test-PublishSafety.ps1 | 用 Git 的相对前缀确认项目根目录，避免把 UTF-8 中文绝对路径按终端编码误读。 |
| fieldlog-core/CaptureFiles.kt、CaptureFilesTest.kt | 旧记录及 AtomicFile 备份单独存在时也占用其 photoId，阻止新拍照覆盖旧证据。 |
| fieldlog-core/CaptureTimeQuality.kt、CaptureTimeQualityTest.kt | 根据同设备单调时钟计算拍摄区间；检测系统墙上时钟前跳／后跳，原始时间不改写。 |
| fieldlog-core/PhotoDigest.kt、glasses-app/PrivateCaptureStore.kt | 保存 SHA-256 指纹；启动检查可识别有效 JPEG 的字节替换。HASH_MISMATCH 保留照片、原指纹和原时间，要求核对。 |
| fieldlog-core/IssueCategory.kt、glasses-app/MainActivity.kt、PrivateCaptureStore.kt | 人工快捷类别，拍摄时冻结选择并存入同一记录；标为待复核，recognitionStatus=NOT_RUN。 |
| glasses-app/GlassesApplication.kt、MainActivity.kt | 文件队列属于应用进程，页面退出后仍可处理相机回调；页面只解绑自己持有的 ImageCapture。 |
| glasses-app/RecoveryInstrumentation.kt | 增加人工标签、同尺寸图片替换、损坏指纹、外来元数据指纹的隔离 Android 回归。 |
| phone-app/LocationProbe.kt、MainActivity.kt、PrivateProbeStore.kt | 缺少有效位置 UTC 时间时拒绝采用；收到时间与年龄使用同一次计时读数；历史 JSON 读取上限 256 KiB。手机路径仅编译／静态检查，未实机验证。 |

上述模块路径均相对项目根目录，Kotlin 实际源码在各模块 src/main/kotlin/org/rokidfieldlog 下。

## 如何复测

```powershell
.\scripts\Test-ToolEnvironment.ps1
.\scripts\Build.ps1 -Verify -Clean
# 获得目标眼镜安装／测试授权后执行；已有授权在本次仍有效：
.\scripts\Install-App.ps1 -Target glasses -ConfirmInstallation -StartApp
.\scripts\Test-StorageRecovery.ps1 -ConfirmDeviceTest
```

预期：工具检查通过、BUILD SUCCESSFUL、两个 APK verified、26 项核心测试通过、13 项隔离存储测试通过。隔离 JPEG 是 16×16 合成夹具，只证明文件处理和 Android 接口行为，不是相机、GPS 或识别准确率证据。

眼镜菜单按拍照、问题类型、删除全部数据排列。官方触控板的向前滑动键对使选择前进一项，向后滑动退一项；单指点击确认。类别对话框可选待确认或四个类别，也可返回取消。所有入口要在实际触控板和光学显示中验收；ADB 按键不替代佩戴验收。

实拍测试应只增加一张 JPEG 和同 ID 的记录，旧记录保持不变。人工标签、位置待确认、拍摄原始时间、单调区间和保存时指纹应在同一 JSON 中。照片／记录只放在本包私有存储，电脑证据只存忽略的 .local/device。

拍照后立即退出再打开，照片应完整保存，或明确保留为未完成／待核对；不能永久跳过恢复，不能删除证据或把不完整照片显示为成功。

## 可信度的边界

CONSISTENT_INTERVAL 只说明一次拍照的墙上时钟变化与单调区间相符，不能证明设备日期正确，也不能对齐手机时钟。恢复的不完整时间仍是未知；跨设备匹配属于后续通信验收。

SHA-256 指纹检测已保存字节是否改变，不是数字签名，不能证明拍摄日期、地点或现场真实性。旧版完整记录没有原保存时指纹，保持原字节不迁移；只有 JPEG 解码校验。恢复文件建立的指纹来源为 RECOVERY_VERIFICATION_CURRENT_BYTES，不能冒充原拍摄时指纹。

人工标签是用户选择，不是 AI 判定，更不是损坏事实的自动证明。所有位置与类别仍要复核。

## 官方 SDK 的新增核对

官方 Maven 实际提供 client-l:1.1.2。其 POM 依赖 cxr-service-bridge:1.0-20260715.121510-107。已用项目 JDK javap 检查该 AAR 的公开签名：CxrSession.connect(String)、sendCustomCmd(String, Caps, byte[])、生命周期／指令回调，以及 SessionConfig 的眼镜包名、Activity 名和 APK 路径 getter 确实存在。

这些 SDK 仍未集成进 APK，未取得手机授权 Token、未证明当前两端版本兼容，也没有触发 connect 或自动安装流程。源码不会用不存在的方法或假的通信结果填补缺失验证。

## 实际结果

工具检查已在 Windows PowerShell 5.1 通过。26 项核心测试通过，0 failures、0 errors；修改存储逻辑的构建实际执行测试，后续只改 UI／生命周期时复用结果。两端最终构建与 Lint 通过，眼镜 0 错误／13 条依赖或 KTX 建议，手机 0 错误／1 条依赖建议。

13 项 Android 存储测试已在实际眼镜通过。最终 APK 已覆盖安装；人工快捷类别通过 ADB 键盘路径选择“其他问题”，再单次拍照，实际保存 4032×3024 JPEG及同一记录，5 条旧记录字节不变。电脑通过二进制流独立复制本包 JPEG 并计算 SHA-256，与保存时指纹一致；保存提示自动隐藏。这里是 UI 标签测试，不是对画面内容的识别准确率验证。

最终生命周期实测：Enter 请求拍照后立即 Back 退出，再打开。相机取消了拍摄，没有生成 JPEG；原请求恢复为 INCOMPLETE／MISSING，时间与坐标没有编造，6 条旧记录字节不变，相机再次就绪。没有永久跳过请求或误报成功，也没有通过删除照片制造故障。

当前 App 私有数据有 6 张完整真实测试照片和 1 条未完成的真实请求；合成夹具没有混入照片计数。没有执行确认删除，也没有改变时间、固件或系统配置。唤醒休眠眼镜仅用于已授权的本项目测试。

| 最终产物 | 大小 | SHA-256 |
| --- | --- | --- |
| glasses-app-debug.apk | 7,782,190 bytes | 98fb3f51fcef6ed9a59d69b6e8acc4846d8ed228c71838b704567dd4331fc891 |
| phone-app-debug.apk | 7,942,154 bytes | 35bc1de41816b8e6df66cd59faef55904a83825395879b483bb850864a76bd44 |

APK 位于各模块 build/outputs/apk/debug，校验值仅对应本次构建。已发布 [可安装预览包](https://github.com/PYCST-DDZYL/RokidFieldLog/releases/tag/v0.1.0-preview.2)，两个远端资产的 SHA-256 与本地相同。预览包是 debug APK，并非完整工作流或自动识别版本；下载不会自动安装或修改设备设置。

新版第三项删除菜单也完成返回取消验证：7 条记录的字节内容保持不变，没有误拍或确认删除。

仍未验证：手机实机定位、物理触控板与佩戴光学体验、实际断电／满存储、正向删除、跨设备通信、地图／导出、AI 分类。源码中没有启用未经验收的模型。
