# 本轮改进与验证

本轮按独立改进提交，使用合成照片、时间、坐标与笔记测试。未安装应用、读取设备照片、改变系统配置或向实际接收方提交事项。功能代码与本机/CI 结果不能代替真机验收或商业验证。

## 改进和主要文件

| 改进 | 主要文件 | 验证与边界 |
| --- | --- | --- |
| 防止重叠构建/安装 | `scripts/Enter-ProjectOperationLock.ps1`、`Build.ps1`、`Install-App.ps1`、`Test-ProjectOperationLock.ps1` | PowerShell 5.1 跨进程争用、独立项目、释放后重试及失败释放通过；直接 Android Studio / Gradle 操作不使用该脚本锁 |
| 拒绝错误的采集定位和时间 | `CaptureRecord.kt`、`CaptureLocationValidationTest.kt`、`docs/record-format.md` | 新增 5 项回归，先重现 4 类缺口；该批 240 项核心测试通过。缺少原始坐标系/模拟标记的旧定位记录保留原文件并报告不可读，不猜测补值 |
| 本机资料健康检查 | `LibraryHealthCheck.kt`、`LibraryHealthCheckTest.kt`、手机 `MainActivity.kt` | 7 项隔离文件回归检查缺失、改变、损坏及异常隔离；该批 247 项核心测试和手机 Kotlin 编译通过。不自动修复、删除或确认隐私 |
| 试点概览与投资条件 | `PilotMetrics.kt`、`PilotMetricsTest.kt`、手机 `MainActivity.kt`、`docs/zero-to-one-investment-thesis.md` | 只统计明确非演示的当前材料笔记；未知时间不补零，日期缺失不计为试交，演示/未知类型/冲突条目不抬高结果。无对照组不计算效果或受理率 |
| Windows 持续回归 | `.github/workflows/android.yml` | 独立 Windows 任务运行跨进程锁测试，不需要设备、发布密钥或 Android SDK；结果以对应提交的远端运行记录为准 |

核心 Kotlin 文件位于 `fieldlog-core/src/main/kotlin/org/rokidfieldlog/core/`，测试位于对应的 `src/test/kotlin/`。手机代码位于 `phone-app/src/main/kotlin/org/rokidfieldlog/phone/`。

## 怎样复测

Windows 项目根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-ToolEnvironment.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Build.ps1 -Release -Verify -Clean
```

`-ExecutionPolicy Bypass` 仅用于本次 PowerShell 进程，不改永久执行策略。需要已准备的项目内工具及原固定发布密钥；不要重新生成另一把密钥来覆盖已安装版本。

预期：工具检查通过，完整核心回归和两端 release 编译/Lint 成功；APK 内容、发布证书、源码输入一致性检查通过后，私有 `.local/verified-build-release.json` 为 `VERIFIED`。生成包位于两端 `build/outputs/apk/release/`；旧包不能替代该凭据核对。CI 的 APK 仍是开发用 debug 包，不包含本机发布私钥。

健康检查合成回归核对运行前后所有夹具文件指纹一致；异常由测试有意构造，不是发现用户真实资料损坏。试点回归检查演示和未知数据隔离、奇偶样本中位数、冲突身份、不含私人文本的摘要及源笔记不变。

## Android 界面验收：尚待完成

1. 手机首页进入“更多：资料检查、试点与演示”，运行完整性检查，核对数量并尝试用结果中的记录编号搜索。空库应显示暂无，不将未审阅照片说成可导出；退出/返回应正常。
2. 打开试点概览，核对演示与非演示标记分开、没有耗时时显示未知。由实际发生的人工交接填写日期、时间和结果，再检查概览。不得为试验在真实政府渠道重复提交或伪造回执。
3. 照片损坏与缺失的分支只在电脑隔离夹具验证；不要通过删除或修改手机真实照片来测试。新版本的实际文件选择器、分享、定位、眼镜交互和设备异常仍需逐项验收。

手机当前目标版本 `0.7.10-pilot-metrics` / code 18，眼镜 `0.2.8-record-check` / code 11。新版本未安装到设备。无线定位联动、地图选点、完整工作库备份/恢复和正式政务接收仍未完成；本轮没有 AI 模型或识别率验证。

## 最终本机结果

- 253 项核心测试通过，失败 0、错误 0；相对上次 235 项新增 18 项（定位记录 5、资料健康 7、试点统计 6）。
- `Build.ps1 -Release -Verify -Clean` 退出码为 0。两端 release、Lint、必需 DEX 类、包名、手机分享边界、原固定证书及输入前后指纹检查通过。
- Lint 0 错误、83 条警告：眼镜 16、手机 67，包含 `SetTextI18n`、`UseKtx`、`UnusedAttribute` 及依赖/构建插件版本建议；未用基线隐藏错误或关闭检查。
- Windows PowerShell 5.1 的全部工具及跨进程互斥回归通过。项目外没有安装新 SDK 或依赖，没有安装到手机/眼镜。
- 构建后再次核对源码输入及 APK 指纹，与 `VERIFIED` 私有凭据一致。私有证据在 `.local/improvement-2026-10-27-*.log` 和测试/Lint XML；真实资料、构建缓存和发布私钥不提交。

| 本机发布包 | 字节数 | SHA-256 |
| --- | --- | --- |
| `phone-app/build/outputs/apk/release/phone-app-release.apk` | 7398688 | `b090a392aecc1bbb059f8e31c9f5990f95c58d599a314ccf3a7b59cde1a5879d` |
| `glasses-app/build/outputs/apk/release/glasses-app-release.apk` | 7008611 | `0a812676b5c609748353870646b7b349ff45508d6248d74556e41442c2cba068` |

远端验证见 [GitHub Actions](https://github.com/PYCST-DDZYL/RokidFieldLog/actions)，各批次结果与对应提交绑定。全部真实资料、私有测试日志、工具与签名密钥留在忽略目录，不属于发布内容。
