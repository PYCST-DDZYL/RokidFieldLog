# 开发环境调查与工具准备方案

2026-10-13 已新增 Windows PowerShell 5.1 的 stderr／UTF-8 BOM 兼容修复和工具回归。最新测试结果见 [可靠性更新](reliability-update.md)，下面保留初次环境与历史证据。

调查日期：2026-10-11（用户所在时区）。

## 调查开始时的环境（历史）

- 操作系统环境为 Windows，当前终端为 PowerShell。
- 工作目录为 D:\AndroidProjects\乐奇问题上传政府，调查开始时为空。
- 用户确认电脑尚未安装 Android Studio。
- PATH 中未发现 java、adb、gradle、kotlinc、studio64；ANDROID_HOME、ANDROID_SDK_ROOT、JAVA_HOME 未设置。
- 常见 Android Studio / Android SDK 位置与 Android Studio 安装登记中未发现现有安装。
- 未执行 adb；未读取设备序列号；未安装、卸载软件或改变系统设置。
- 用户表示眼镜已经连接好。线材是否支持开发数据、眼镜 ADB 是否可用仍待实际确认。
- 用户将使用另一部已有 Google Play 与 Rokid AI / Hi Rokid 的 Android 手机作首轮测试。确切 Android 版本、官方手机 App 版本、眼镜固件版本仍待确认。

## 已执行的项目内工具方案

用户已同意 Android SDK 许可及项目内工具准备。Android Studio Rabbit 1 / 2026.2.1.8 使用官方 Windows ZIP，SHA-256 已核对；SDK 已安装 platform-tools 37.0.1、Android 36 基础平台 revision 2、Build Tools 35.0.0。Gradle 8.13 发行包和 Wrapper 使用官方校验值。

实测 Studio 自带 JBR 为 Java 25.0.3，Gradle 8.13 的官方支持范围不包含 Java 25。当前构建使用项目内 Eclipse Temurin JDK 21.0.12.1+1（官方 SHA-256 已核对）；Studio 界面仍使用自带 JBR。最初准备的 JDK 17 仍仅留在 .tools 中，不参与当前构建。未改变系统 JAVA_HOME 或 PATH。

| 内容 | 拟用位置（均相对 RokidFieldLog 根目录） | 用途 |
| --- | --- | --- |
| Android Studio 与自带 JBR | .tools/android-studio/ | 编辑、Gradle Sync 和 Kotlin/Android 构建 |
| 独立构建 JDK 21 | .tools/jdk-21/ | 与 Gradle 8.13 / AGP 8.13.2 匹配的构建 Java |
| Android SDK | .tools/android-sdk/ | 编译平台、Build Tools、Platform Tools、必要的命令行工具 |
| 工具下载包 | .local/downloads/ | 校验官方发布文件后解压 |
| IDE 配置 | .local/studio/config/ | 隔离项目的 IDE 设置 |
| IDE 缓存 | .local/studio/system/ | 索引和构建相关缓存 |
| IDE 插件与日志 | .local/studio/plugins/、.local/studio/log/ | 项目专用 IDE 插件和诊断日志 |
| Gradle 用户目录 | .local/gradle-user-home/ | Gradle 发行包和依赖缓存 |
| Android 工具用户目录 | .local/android-user-home/ | 工具首选项和项目 debug 签名等本地数据 |
| 临时文件 | .local/tmp/ | 项目启动的工具产生的临时文件 |

通过项目启动脚本仅为子进程设置 STUDIO_PROPERTIES、ANDROID_HOME、ANDROID_USER_HOME、GRADLE_USER_HOME、TEMP / TMP 等变量；用 IDE properties 指定 idea.config.path、idea.system.path、idea.plugins.path 和 idea.log.path。不更改全局环境变量、不添加系统快捷方式、不安装 USB 驱动、不配置模拟器。

依赖下载、Gradle Wrapper、调试签名及缓存配置在首次构建前核对。Prepare-Tools.ps1 必须显式传入 -AcceptSdkLicense，表示已经获得用户许可授权。该脚本只处理三个指定 SDK 组件的许可，不运行全局 --licenses。最初执行误生成了可选组件的许可记录，已修正脚本并清除这些项目内记录；没有安装这些可选组件。

ADB 官方 Windows 实现通过系统用户目录创建或复用电脑认证文件，不能使用 ANDROID_USER_HOME 重定向。用户已单独授权 %USERPROFILE%\.android\adbkey 与 adbkey.pub 这一例外。ADB 服务日志仍留在 .local/adb-server.log；使用端口 5047，设备标识不会输出或提交。

只读检测已识别一台 Rokid RG-glasses：Android 12 / API 32。Windows 识别到 Samsung USB Composite / Modem，状态正常，但 ADB 暂未枚举手机。没有安装驱动或更改 USB 设置。

启动第三方开发工具时，Windows 本身可能维护最近使用记录等系统行为；这与主动把项目源码或 SDK 安装到其他目录不同。若确需驱动、工具、依赖或项目缓存写到根目录外，必须先列出确切路径、改动和原因，经用户同意后执行。

## 当前验证状态

已创建两端 Phase 1 源码与真实 Gradle Wrapper。最新两个 debug APK 构建成功，18 项核心单元测试通过（位置质量 5、回调／截止时间 5、存储事务 8）；未修改任务可复用已通过的结果。眼镜另外运行 9 项隔离 Android 存储恢复测试通过。两端 Lint 为 0 错误（眼镜 8 条警告、手机 1 条警告）。这些测试不代替真实手机 GPS 验证；真实拍照、菜单确认和删除框返回取消也单独验证通过。

实际诊断发现 Windows Java 启动器按本机 native.encoding=GBK 读取 Gradle 的 @参数文件，而 Gradle 原默认写 UTF-8，中文路径因此损坏。Use-ProjectEnvironment.ps1 将匹配的编码写到 .local/gradle-user-home/gradle.properties；源码、XML、JSON 继续使用 UTF-8。没有改系统区域、创建驱动器映射或移动源码。标准 Gradle test 已实际通过，不能仅用直接 JUnit 的通过结果代替。

用户已明确授权两端 App 的安装、权限和 Phase 1 测试，后续同一范围内不需重复询问。眼镜相机校验问题已通过官方后置 Camera Limiter 配置修复，真实拍照、保存、提示隐藏和冷启动后记录保留已验证。一次增量 APK 缺类问题通过清理模块构建产物后重建解决，新增官方 APK Analyzer 包／启动类检查；根因尚未确定。手机仍仅枚举出文件传输/调制解调器接口，未出现 ADB 接口，因此没有安装手机 APK。详情见 phase1-status.md。Phase 1 尚未完成。

## 官方参考

- [Android Studio installation](https://developer.android.com/studio/install)
- [Android Studio properties and configuration](https://developer.android.com/studio/intro/studio-config)
- [Android SDK environment variables](https://developer.android.com/tools/variables)
