# 手机定位实测：USB 连接诊断

2026-10-12，用户所在时区。手机 GPS 尚未实测；本文件记录实际电脑检查，不推测手机屏幕状态。

## 已完成的只读检查

- ADB 5047 只识别 Rokid 眼镜，没有三星手机，5037 没有另一个监听服务。
- 没有发现已开启的无线调试 mDNS 服务；没有配对、启用无线调试或扫描其他网络设备。
- 三星当前文件传输、调制解调器及复合 USB 设备均为 OK，问题码 0。
- 复合／调制解调器驱动的供应商为 Samsung，版本 2.21.4.0，日期 2026-04-08。
- 用户确认手机 Auto Blocker 已关闭；没有要求关闭已经关闭的安全功能。
- 用微软文档中的 USB hub GET 查询读取原始配置，成功返回 3 个接口、113 bytes 配置描述：

| 接口 | Class / Subclass / Protocol | 作用 |
| --- | --- | --- |
| 0 | 06 / 01 / 01 | 文件传输 |
| 1 | 02 / 02 / 01 | 调制解调器控制 |
| 2 | 0A / 00 / 00 | 调制解调器数据 |

没有 ADB 标准接口 FF / 42 / 01。当前手机没有向此电脑连接提供 ADB USB 通道；此时仅重装 ADB 驱动不能凭空产生这个接口。仍不能从此结果推断手机 UI 开关一定处于哪种状态，也不能读取被系统策略阻止的具体原因。

## 已准备的下一步

用户原始要求：未经同意不得改变手机系统配置。用户现已同意在当前连接的三星手机上，将“开发者选项 → USB 调试”关闭再开启，随后拔插手机线缆；若出现电脑调试授权提示，则允许本电脑。等待实际接口变化；“同意”不能记作已经连接成功。没有请求恢复出厂、撤销全部授权、Root 或更换固件。

手机出现后，由开发者继续已获授权的操作：核对型号／Android 版本，安装 Companion，授予此项目 App 前台定位权限，启动页面并获取一次真实位置；只读检查本 App 内部 JSON，将私密证据留在项目 .local。若定位真实超时、权限失败或模拟位置被拒绝，会记录失败，不生成假坐标。

## 可重复的检查

在项目根目录的 PowerShell 运行：

```powershell
.\scripts\Inspect-Devices.ps1
.\scripts\Read-SamsungUsbDescriptors.ps1
```

后者只使用 GET_NODE_INFORMATION、GET_NODE_CONNECTION_INFORMATION_EX 和 GET_DESCRIPTOR；不请求设备字符串／序列号，不执行 USB 重置、设置配置、安装驱动或注册表写入。提示 ADB FF/42/01 descriptor present: True 后，再检查 ADB 状态；True 本身不代表调试已授权。

代码位于 scripts，编译临时文件由项目 TEMP/TMP 管理。读取受 Windows 权限限制时报告失败，不自动提升管理员权限或修改安全设置。

## 官方依据

- [Microsoft USB 描述符查询](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/usbioctl/ni-usbioctl-ioctl_usb_get_descriptor_from_node_connection)
- [Microsoft USB_DESCRIPTOR_REQUEST](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/usbioctl/ns-usbioctl-_usb_descriptor_request)
- [Android ADB 接口常量](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.h)
- [Android 真机调试步骤](https://developer.android.com/studio/run/device)
- [Samsung Auto Blocker](https://docs.samsungknox.com/admin/fundamentals/whitepaper/samsung-knox-mobile-security/system-security/samsung-auto-blocker/)
- [Samsung 官方 Windows USB 驱动](https://developer.samsung.com/android-usb-driver)
