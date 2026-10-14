# Phase 1 运行指南

本指南仅对应当前拍照与定位验证。两端通信、手机照片列表、地图和导出尚未实现。

## 在 Windows 构建

在 PowerShell 中，切换到项目根目录：

```powershell
Set-Location -LiteralPath 'D:\AndroidProjects\乐奇问题上传政府\RokidFieldLog'
.\scripts\Build.ps1 -Verify -Clean
```

脚本只给当前进程及子进程配置项目内的 Java、SDK 和缓存。-Verify 额外检查位置数据质量测试和 Android Lint；未修改的任务可能显示 UP-TO-DATE。-Clean 先清理两个模块的构建产物，保留设备上的照片和记录。本阶段修改源码后使用此参数，避免已遇到的增量 APK 旧代码问题。

预期看到 BUILD SUCCESSFUL 及两个 APK verified 行；若 APK 缺少启动类，脚本报错，不能安装。检查使用本项目 SDK 内的官方 APK Analyzer，不额外安装工具。

可执行产物为：

- glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
- phone-app/build/outputs/apk/debug/phone-app-debug.apk

生成 APK 不是安装。用户已明确授权两端本阶段的安装、权限和测试，不需重复确认同一范围。构建步骤不会自动安装、授予权限、卸载或清除设备数据。

## 打开 Android Studio

```powershell
.\scripts\Open-AndroidStudio.ps1
```

在首次启动界面不导入其他 IDE 配置，选择本项目已有 SDK 路径 .tools/android-sdk。若提示 Gradle JDK，选择本项目 .tools/jdk-21；最新 Studio 自带 Java 25 不能用于当前固定的 Gradle 8.13。

Android Studio 的 Run 按钮会安装 APK 到选中设备。核对是 glasses-app / phone-app 以及对应设备，保持在已授权的 Phase 1 测试范围内。手机模块不能误装到眼镜上。

## 只读检查连接

用户已允许 ADB 电脑认证密钥例外，当前可运行：

```powershell
.\scripts\Inspect-Devices.ps1
```

该脚本不输出设备序列号，不自动安装 App 或修改设备设置。device 表示调试可用；unauthorized 表示设备尚未允许这台电脑；offline 表示连接异常。开启开发者模式与开启 USB 调试是两件事。

此前诊断的三星 USB 配置不含 ADB 接口，详细只读证据与下一步见 phone-connection.md。无需反复猜 USB 文件传输模式或盲目重装驱动。

## 眼镜验证

本阶段安装授权已取得，可执行：

```powershell
.\scripts\Install-App.ps1 -Target glasses -ConfirmInstallation -StartApp
```

脚本确认设备类型、APK 包名及定义的启动类，覆盖更新保留私有数据；启动后检查进程。当前真机已通过多次真实拍照、私有保存、提示隐藏，以及 ADB 菜单确认／返回取消；上述是旧版本的硬件证据，本次修改尚未真机验收。以下步骤供用户戴眼镜验收物理触控及可读性，不需要删除测试照片。

安装并打开 FieldLog 后，用户在系统提示中允许 CAMERA 权限。出现“已就绪”后站定，单指点击右侧触控板；官方固件会发送 KEYCODE_ENTER。

成功结果应显示“已保存 · 当前类别”（例如“已保存 · 垃圾堆积”），约 1.5 秒后全部提示隐藏，背景保持黑色。60 秒内下一次单指点击仍能拍照；相机空闲 60 秒后释放，首次点击唤醒相机、就绪后再次点击拍照。空白区域点按只唤出菜单。主菜单为拍照、类别、回看/撤销上一张；删除全部在回看菜单的数据管理内。手机未同步时不会显示“已同步”。

照片在此 App 的内部 files/fieldlog/photos，JSON 在 files/fieldlog/records，请求日志在 files/fieldlog/journals；不写公共相册、不申请存储权限。正式照片有唯一 UUID，记录请求与完成时间；尚未取得真实曝光时间，因此元数据标明这是请求区间。重新打开会检查中断拍摄并恢复可确认的文件，未知时间保持 null；具体行为和隔离恢复测试步骤见 capture-recovery.md。

核验使用 Android Studio Device Explorer 查看该 debug App 的私有目录，或由开发者执行限定为本包的 run-as 只读检查。检查一张图片可以解码、JSON 的 photoId 与文件对应；重新打开 App 后，照片数量应保留。照片与 GPS 均未知时坐标字段为 null。

出现系统权限拒绝、初始化错误或真实拍照失败时保留错误状态，不用合成照片代替。

## 手机定位验证

只有 Inspect-Devices.ps1 确认实际手机处于 device 状态后执行（本次执行环境无法重新枚举设备，先前手机诊断未发现 ADB 接口）：

```powershell
.\scripts\Install-App.ps1 -Target phone -ConfirmInstallation -StartApp
```

找不到唯一匹配手机时脚本拒绝安装，不会改 USB 设置或清理设备。

打开 FieldLog Companion 后，在户外点击“测试定位（自动选择）”，由用户选择精确或近似权限。

成功时显示坐标、精度估计、位置时间、收到时间、收到时数据年龄、定位来源、权限级别和 WGS84 坐标系；问题实际位置仍显示待人工确认。结果写入本 App 内部 files/fieldlog/last-probe.json，不输出到终端或 Git。

“测试 Android 系统定位”可以在有 Google 服务的手机上验证系统定位代码路径，但这个结果不能证明真实无 Google 服务手机兼容。后者必须在对应设备上另行测试。

完成真实手机测试后，开发者可运行 scripts/Read-PhoneProbe.ps1，只读取本包的 last-probe.json。它将原始证据保存到被忽略的 .local/device，终端隐藏经纬度；失败结果必须保持位置为空。读取上次结果不代表重新获取当前位置。

最长等待 60 秒；自动选择模式的 Fused 最多等待 8 秒，无结果或精度不足时转系统定位，利用剩余时间。低精度可先显示但不作为最终成功；仅近似权限按该权限等级显示结果。超时时最近 5 秒内的有效低精度位置可以作为明确标记的“低精度结果”，过旧结果保持空。此切换仍需真机验证。权限拒绝、定位关闭、没有可用提供者、位置过旧或模拟位置会明确报告；坐标为空。关闭页面或点“停止本次定位”会取消监听。重新打开页面显示的上次结果带“历史记录”标记，不冒充当前位置。

## 数据删除与卸载

手机“删除全部项目数据”弹出确认框，用户选择“删除”才操作自己的内部 fieldlog 目录。眼镜在“回看 / 撤销上一张”→“数据管理”提供确认入口；输入可用性须在真机上验收。

普通卸载会清理 App 私有照片与定位测试记录；安装新版本覆盖旧版本会保留这些数据。以后由系统文件选择器导出的报告由用户管理，卸载和删除按钮不会删除导出文件。

构建工具 .tools、缓存与设备资料 .local 均被 .gitignore 排除。不要把真实照片、GPS JSON、调试密钥、完整设备日志或序列号复制到源码、docs 或 GitHub。
