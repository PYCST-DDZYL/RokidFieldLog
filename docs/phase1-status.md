# Phase 1 实测与修复记录

更新日期：2026-10-12（用户所在时区）。眼镜真实拍照已通过，手机 GPS 仍未实测；Phase 1 尚未完成，不进入 Phase 2。

## 本次修改的文件

| 文件（相对项目根目录） | 改动及原因 |
| --- | --- |
| glasses-app/src/main/kotlin/org/rokidfieldlog/glasses/GlassesApplication.kt | 新增官方 CameraXConfig.Provider，仅枚举后置镜头，处理固件声明与实际镜头不一致的问题。 |
| glasses-app/src/main/AndroidManifest.xml | 注册上述 Application。 |
| glasses-app/src/main/kotlin/org/rokidfieldlog/glasses/MainActivity.kt | 防止重复初始化、页面销毁后处理初始化结果；增加本包相机／保存状态日志，不含照片、GPS 或设备标识。 |
| fieldlog-core/src/main/kotlin/org/rokidfieldlog/core/CaptureFiles.kt、对应 CaptureFilesTest.kt | 持久化请求日志及 8 项文件中断、校验、路径隔离回归测试，扫描只有记录而没有日志的旧数据。 |
| glasses-app/src/main/kotlin/org/rokidfieldlog/glasses/PrivateCaptureStore.kt、glasses-app/build.gradle.kts | 中断恢复、启动重新校验 JPEG、损坏／缺失状态、原记录备份、保留时间和人工修改、限制元数据读取大小。 |
| glasses-app/src/debug/AndroidManifest.xml、RecoveryInstrumentation.kt | 仅 debug 的 9 项隔离 Android 存储测试。 |
| phone-app/src/main/kotlin/org/rokidfieldlog/phone/LocationProbe.kt | Fused 最多等待 8 秒，总等待 20 秒；拒绝过期回调；逐个注册系统提供者，某个提供者注册失败不会丢弃另一个可用提供者。仍需真实手机验收。 |
| fieldlog-core/src/main/kotlin/org/rokidfieldlog/core/LocationAttempt.kt | 将单次请求的来源、关闭状态及单调时钟截止时间集中处理；拒绝主线程延迟导致超过截止时间才处理的结果。 |
| fieldlog-core/src/test/kotlin/org/rokidfieldlog/core/LocationAttemptTest.kt | 新增 5 项来源切换、取消、无 GMS 时间预算及延迟回调回归测试。 |
| phone-app/src/main/kotlin/org/rokidfieldlog/phone/MainActivity.kt | 页面已离开时不因迟到的权限结果启动定位；损坏的历史记录不导致启动崩溃。 |
| phone-app/src/main/kotlin/org/rokidfieldlog/phone/PrivateProbeStore.kt | 由 AtomicFile 自行恢复中断写入的备份，避免提前判断主文件不存在而跳过恢复。 |
| scripts/Test-Apk.ps1 | 新增安装前检查：官方 APK Analyzer 确认包名及实际定义的启动类。 |
| scripts/Build.ps1 | 新增 -Clean，并在构建后检查两个 APK。 |
| scripts/Install-App.ps1 | 安装前检查 APK；启动等待返回值并检查进程，避免把启动后立即崩溃当作成功。 |
| scripts/Test-StorageRecovery.ps1、Read-PhoneProbe.ps1 | 眼镜隔离存储验证，以及手机可用后的本包定位证据检查。 |
| scripts/Read-SamsungUsbDescriptors.ps1、Read-SamsungUsbDescriptors.cs | 使用微软公开的 GET 查询读取当前 USB 配置，仅输出接口类别，不读取序列号、不改 USB 模式或驱动。 |
| docs/phase1-plan.md、phase1-run.md、environment.md、feasibility.md、phone-connection.md、capture-recovery.md、本文件 | 更新状态、复测步骤、证据与限制。 |
| glasses-app/src/main/kotlin/org/rokidfieldlog/glasses/MainActivity.kt | 使用公开 View.OnKeyListener 优先处理 Enter，避免背景点击抢走菜单确认；返回取消时复位并隐藏提示；确认框黑底绿字、默认选择取消。 |
| README.md、LICENSE、CONTRIBUTING.md、docs/install.md、.gitattributes、.gitignore、scripts/Test-PublishSafety.ps1 | 英文开源预览说明、MIT、安装／卸载、提交隐私检查；不表示 Phase 1／2／3 已完成。 |

## 构建与包检查

执行 scripts/Build.ps1 -Verify -Clean：BUILD SUCCESSFUL。两个模块均重新编译、打包，Android Lint 为眼镜 0 错误 / 8 警告、手机 0 错误 / 1 警告；警告为固定依赖存在更新及 KTX 写法建议。

核心单元测试：LocationQualityTest 5 项、LocationAttemptTest 5 项、CaptureFilesTest 8 项，合计 18 项通过，0 failures、0 errors、0 skipped。新增存储测试实际执行通过，未修改任务可复用其结果；Android 隔离存储测试另有 9 项在眼镜上通过。单元测试与合成存储夹具不能算真实 GPS／相机证据。

| 当前产物 | 大小 | SHA-256 |
| --- | --- | --- |
| glasses-app-debug.apk | 7,749,422 bytes | 07ae213a68a81a3f1d13c0849b7bd379170ccfe223f4baeb2f9dab1740a6c474 |
| phone-app-debug.apk | 7,933,218 bytes | d3b53910e891285e6d798895f69664322a5a72d4a04c603c8ad7ce1a9fa2b5b2 |

APK 位于各模块 build/outputs/apk/debug；校验值仅对应本次构建。官方 APK Analyzer 确认眼镜 MainActivity、GlassesApplication 和手机 MainActivity 是 APK 中实际定义的类。

眼镜 minSdk 31 / targetSdk 36，运行时只申请 CAMERA；手机 minSdk 28 / targetSdk 36，只申请 COARSE/FINE_LOCATION。没有公共相册、全盘存储或后台定位权限。AndroidX 另生成本包内部广播权限。

## 眼镜真机通过的部分

设备为 Rokid RG-glasses，Android 12 / API 32。用户已授权本阶段两端安装、权限和测试；更新使用 install -r，保留本包私有数据。

- 最新眼镜 APK 安装 Success，启动 Status: ok，进程存在，本包拥有输入焦点。
- 后置相机初始化成功，本包日志 CAMERA_READY。
- 确认焦点后，通过 ADB 发送官方定义的 KEYCODE_ENTER，实际拍摄照片；没有生成或使用模拟现场照片。
- 本包日志 CAPTURE_SAVED。内部 files/fieldlog/photos 有 JPEG，records 有对应 UUID 的 JSON。
- 实际 JPEG 为 4032×3024，保存前检查尺寸和缩小像素解码；早期照片还通过电脑端 System.Drawing 完整解码。最新记录 photoIntegrityStatus=VERIFIED。
- JSON 的 phoneLocation、issueLocation 都是 null；locationStatus 为 PENDING_CONFIRMATION；phoneConnectionStatus 为 NOT_CONFIGURED_PHASE1，syncStatus 为 LOCAL_ONLY。
- 保存请求与完成的单调时钟区间有效。记录说明时间来自 App 请求区间，不能冒充传感器真实曝光时间。
- 保存后约 3 秒截图为无亮文字的 480×640 暗背景，确认提示已自动隐藏。没有精确测量光学显示上的 1.5 秒持续时间。
- 最新 APK 覆盖更新后冷启动：LaunchState: COLD，相机再次就绪，恢复检查 restored=0 / incomplete=0 / errors=0，原数据保留。
- 本包截图验证绿色菜单、照片计数与未同步状态，内容处于显示区中部。
- 9 项隔离存储测试在眼镜上通过，测试前后的正式记录字节内容不变；请求日志为 66 字节。合成夹具只在私有 test-fixtures，不计入正式照片数量。
- 两次菜单确认复测在修复前触发测试照片，均保留；已修复可点击背景抢走 Enter 的问题。最新日志确认 MENU_SELECTED action=1、ENTER_ACTION selection=1 panelVisible=true，正确打开删除框。黑底绿字、默认取消的截图已核对，Back 后提示隐藏，4 条记录保留。未执行确认删除。
- 修复后再发送一次 Enter：只新增 1 张真实照片，4 条旧记录字节内容不变，坐标仍为空、提示隐藏；当前共 5 张真实测试照片。

真实照片、JSON、状态日志及本包截图只保留在被 .gitignore 排除的 .local/device 中。没有读写手机已有相册或其他 App 数据，没有执行数据清除、卸载或系统配置变更。

## 已处理的失败

最初的 CameraX 1.5.3 初始化失败：设备 feature 声明前置相机，实际仅有后置 camera ID 0，导致全量镜头校验报 Expected camera missing from device。现在使用官方 Camera Limiter 限制为 DEFAULT_BACK_CAMERA；真机初始化和拍摄已通过，无需修改固件或系统 feature。

官方依据：[CameraX Camera Limiter 配置](https://developer.android.com/media/camera/camerax/configuration#camera-limiter)。

新增 Application 后的一次增量构建虽返回成功，APK 却仍含旧 MainActivity 且缺少 GlassesApplication，造成 ClassNotFoundException。检查中间 .class/.dex 存在、最终 APK 缺类，清理本项目模块构建产物后重建解决。尚未证明增量打包异常的具体根因，不能直接归因于编码或 SDK。

新增 APK 检查可阻止缺少启动类的包被安装；它不能证明所有字节码与当前源码完全一致。修改源码后本阶段采用 -Clean 构建，并保留真机启动检查。

菜单确认最初可能被背景 View 当作点击并直接拍照。现在通过公开 View.OnKeyListener 先处理 Enter，再按当前菜单选择执行。曾尝试覆盖 ComponentActivity 的受限 dispatchKeyEvent，Lint 拦截后已撤回，没有关闭 Lint 或添加错误基线。当前公开接口方案构建、菜单选择、返回取消和单次真实拍照均已通过。依据：[Android View.OnKeyListener](https://developer.android.com/reference/android/view/View.OnKeyListener)。物理触控板输入仍待用户验收。

发布前隐私检查使用隔离夹具验证 3 项：正常源码允许、非功能性合成 Token 拦截、强制加入 .local 文件拦截。夹具留在被忽略的 .local 中；源码提交还需完整差异审阅，不保证自动检测所有个人信息。

Fused 原先等待 20 秒，会耗尽同样为 20 秒的总截止时间，使系统后备定位无法正常尝试。现将 Fused 等待缩短为 8 秒，取消／来源切换后的旧回调也会被忽略。依据：[CurrentLocationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder)。此修复已编译及 Lint 检查，实际切换行为仍需手机验收。

## 仍待解决：手机 ADB 接口

再次只读检查：Windows 枚举 Samsung USB 文件传输、调制解调器和 Composite 接口，状态 OK；未出现 ADB 接口。ADB 当前只识别眼镜。

进一步直接读取 USB 原始配置：当前三星设备为 3 个接口、113 bytes 的配置描述，分别为 06/01/01（文件传输）、02/02/01（调制解调器控制）、0A/00/00（调制解调器数据）。没有 ADB 所需 FF/42/01 接口。这证明问题不仅是 adb devices 没显示名字：当前 USB 配置本身没有提供 ADB。用户确认自动拦截器已关闭；系统已有三星复合及调制解调器驱动 2.21.4.0。没有据此盲目重装驱动。

因此未向手机安装 Companion，没有真实手机定位结果。不能因有 Google Play、蓝牙连接或单元测试通过便宣称取得了手机 GPS。电脑端没有安装驱动、切换 USB 模式、撤销调试授权或改变手机设置。用户已同意在当前连接手机上关闭／重新开启 USB 调试并拔插线缆，后续监测尚未出现手机 ADB；这项同意无需重复询问。详细连接诊断见 phone-connection.md。

## 时间与未验证项目

眼镜此前只读 UTC 时间为 2026-10-07，与当前日期不一致；本次照片亦保留设备原始时钟，没有调整设备时间。Phase 2 必须估计双端时差，保留原始时间及来源，不能直接比较不同设备的 uptime，也不能随意改写拍摄时间。

尚未验证：

- 戴眼镜的光学可读性、实际触控板点击、成功提示持续时间。
- 摄像头权限拒绝、占用／存储故障及确认执行删除的真机路径。本次没有通过撤销权限或删除照片来制造错误；仅已验证返回取消。
- 实际断电／存储耗尽下的恢复。自动恢复已实现并通过 9 项隔离 Android 文件状态测试，但未主动对眼镜断电或填满存储。
- 手机安装与真实定位、8 秒后备切换、取消和定位失败路径。
- 真实无 Google 服务手机兼容性。
- 双端通信、时间匹配、手机列表／地图修正、报告导出。开源预览文件已准备，发布不能替代此前各阶段的验收。

## 构建限制

保留中文目录。Java 的本机参数文件编码问题此前已在项目私有 Gradle 配置中处理；源码、XML、JSON 仍为 UTF-8。android.overridePathCheck 为实验选项；AGP 还输出 SDK XML v3/v4 元数据警告。没有关闭 Lint、隐藏错误或用模拟硬件结果冒充成功。
