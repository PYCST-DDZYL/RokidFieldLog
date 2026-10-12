# Rokid FieldLog：可行性核对

官方资料核对日期：2026-10-11；实机状态更新：2026-10-12（用户所在时区）。文档能力与实际验证分开记录，详细证据见 phase1-status.md。

## 当前结论

建议保留“眼镜独立 Android App + Android 手机 Companion”架构。Phase 1 分别验证眼镜拍照和手机定位；Phase 2 使用手机 CXR-L 的 CUSTOM_APP 会话与眼镜 CXR-S 通信。CXR-L 需要 Rokid AI App / Hi Rokid 作为授权和连接代理，不能省略这个依赖。

用户设备为第一代乐奇 AI 眼镜，带绿色显示屏；目前使用三星 Android 手机，也希望支持其他 Android 品牌。目标手机最低版本建议为 Android 9（API 28），理由是后续 CXR-L 的最低要求。这只是支持范围的设计，不能据此宣称所有品牌、固件和后台策略均已兼容。

## 官方资料确认的能力

| 核对项 | 结论 | 实机状态 |
| --- | --- | --- |
| Bare-metal / On-device | YodaOS-Sprite 上运行标准 Android 独立 App。文档基线为 Android 12 / API 31；样例 minSdk 31、targetSdk 36。 | 实测 RG-glasses 为 Android 12 / API 32，FieldLog 已安装启动 |
| USB 安装与调试 | 官方开发数据线连接电脑；通过 Rokid AI App 开启眼镜 ADB；构建 APK 后通过 ADB 安装。 | 已验证眼镜 ADB 及 APK 安装；没有调整设备设置 |
| 眼镜 Camera API | 官方 Photo 章节使用 CameraX ImageCapture，基于 Camera2；异步绑定 ProcessCameraProvider，使用 ImageCapture.takePicture；允许只绑定 ImageCapture、不显示实时预览。 | 限制后置镜头后初始化及真实 JPEG 保存通过，见 phase1-status.md |
| 单色显示 | 官方基线为 480×640、绿色轮廓、黑色区域不发光；内容安全区域 y=80…560。 | 本包截图验证绿色菜单、照片计数和提示隐藏；实际触控及光学观察仍待用户验收 |
| CXR-L | 官网当前 Android 版本为 client-l:1.1.2；运行于手机，依赖官方手机 App。新版入口为 CxrSessionManager / CxrSession。 | 未下载 SDK、未验证兼容性 |
| 独立眼镜 App 通信 | CUSTOM_APP 支持手机 sendCustomCmd、onCustomCmdResult；眼镜通过 CXRServiceBridge.subscribe / sendMessage 收发。CUSTOM_VIEW 不支持自定义指令。 | 未进行双端通信 |
| CXR-M | 官网标为商务接入，不能作为公开开源项目的默认可获得依赖。 | 未申请、未接入 |
| Fused Location Provider | Google Play services API；允许单次异步定位，可能超时返回 null；旧缓存位置必须检查年龄。 | 未进行手机 GPS 测试 |
| 无 Google 服务的 Android | 可使用 Android LocationManager，仍受定位权限、设备定位开关和信号条件限制。 | 路径已实现并编译，尚无对应手机真机证据 |

## 需要特别处理的差异

- 官方 Bare-metal 样例会优先向公共图片目录写入，并声明全盘存储等权限。FieldLog 采用自己的私有存储实现，不直接安装或照搬该样例的存储行为。
- 官方 CXR-L 1.1.2 与旧版 1.0.x 的入口和会话接口不同；不能把网上旧示例的方法名当作新版可用接口。
- CUSTOM_APP 的 glassesApkPath 可触发 connect 时自动安装 APK。未经用户授权，不启动这样的安装流程；Phase 1 优先采用一次明确授权的 USB 安装。
- 当前详细文档的 CXR-S 导入页列出旧时间戳版本，而新版 Custom App 页要求与 CXR-L 对齐；CXR-L 集成页也出现 1.1.2 与旧版本锁定建议混杂。Phase 2 必须核对官方样例的实际依赖及 AAR 公共签名，不能靠文档示意代码宣称兼容。
- CXR-S 文档列出二进制发送能力，但 CXR-L 回传封装、单包大小和照片传输性能仍需验证。先验证小消息，再验证带校验和的分块照片；发送返回成功不等于手机已经落盘。
- CXR-L 1.1.2 的手机远程拍照章节说明返回 WebP，且回调没有明确的曝光时间/EXIF 契约。它可作为后续备选，但本项目优先验证眼镜本地 CameraX 拍照，保留断连时的照片。
- FieldLog 不建立自己的账户或云服务；Rokid 官方 App 的授权、登录或连接要求属于外部依赖，需要在开源说明中如实说明。

## 定位与时间可靠性设计

每张照片使用唯一 photoId，保存拍照请求时间、完成时间，以及时间来源；若拿不到真实曝光时间，不把“文件写完时间”标为精确曝光时间。原始照片与元数据均保存到 App 私有目录。

手机位置保存纬度、经度、accuracyMeters、Location.time、Location.elapsedRealtimeNanos、定位来源，以及权限为精确还是近似。精度字段缺失时使用 null；它是估计值，不是误差保证。

Phase 2 用 photoId / requestId 关联请求；分别记录蓝牙、SDK 会话和消息确认状态。通过带往返时间的时钟对齐估计跨设备时差及不确定性，不直接比较两个设备的开机计时。候选匹配阈值先定为 5 秒、单次定位等待上限先定为 20 秒；它们是项目初始策略，须根据实测调整，并非 SDK 保证。

断连、权限拒绝、定位超时、位置过旧或时钟无法可靠对齐时，照片仍可保存，记录标为“位置待确认”。坐标未知用 null，不填 0,0，不拿重连后的当前位置冒充拍摄时位置。若保留原始定位观察用于核对，必须明确标为候选，不能显示成已经匹配的位置。

拍摄者位置与问题实际位置是两个独立字段；问题位置默认待确认。Phase 3 允许在地图上人工设定或修正问题位置，保留原始观测、修改时间和来源。导出报告说明位置是否人工确认。

手机锁屏后的定位将在 Phase 2 设计为用户从可见界面启动的“开始散步记录”前台服务，有持续通知和停止入口；不能假设收到蓝牙消息就能从后台启动定位服务。

## 文件与隐私约束

所有项目文件统一位于 D:\AndroidProjects\乐奇问题上传政府\RokidFieldLog；正式模块为 glasses-app、phone-app、docs，必要的共享协议模块和工具也只放在此根目录内。

两端照片、数据库及缓存使用各自内部私有存储。正式照片不能放在可被系统清理的 cacheDir。禁用项目照片/定位数据的系统备份与设备迁移，并在真机验收时检查行为。

导出使用 Android Storage Access Framework 的 ACTION_CREATE_DOCUMENT，让用户明确选择目标文件。提供删除全部 FieldLog 项目数据的入口，仅操作自己拥有的内部数据；卸载通常清理 App 私有数据，已经由用户导出的文件由用户管理，App 不回头删除。

API Key、授权 Token、设备序列号、真实 GPS、照片、设备日志、签名密钥、工具和构建缓存不得提交。测试优先使用合成元数据；合成测试通过不能替代设备验证。

## 官方来源

以下详细章节均从用户指定官网的 Documentation 链接进入，页面内容通过其公开文档服务只读获取；社区反编译资料没有作为接口依据。

- [Rokid SDK 总览](https://developerdoc.rokid.com/sdk?lang=en)
- [Bare-metal Quick Start](https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/us/5702e09fefa343d49b949260fd23c487.html?documentId=63837f8f9e3d4f3387517b224e5c9875)
- [Bare-metal Photo / CameraX](https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/us/5702e09fefa343d49b949260fd23c487.html?documentId=8005821179264084bccc479e6a024074)
- [CXR-L SDK Integration](https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/us/3a61bc60dd3b4093b0f1b7f19b5af339.html?documentId=3a9fa89fa9d5418a89ce01e3e52fe261)
- [CXR-L Glasses Custom App](https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/us/3a61bc60dd3b4093b0f1b7f19b5af339.html?documentId=0f36b2634a3247b6b19061f74ec6a2a2)
- [CXR-L Custom Commands](https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/us/3a61bc60dd3b4093b0f1b7f19b5af339.html?documentId=0c900d8d66e1499895a83cb2189b8733)
- [CXR-S SDK Import](https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/us/3a61bc60dd3b4093b0f1b7f19b5af339.html?documentId=6a211cbcbe454188bb1a1623d983c02c)
- [CXR-L Photo Capture](https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/us/3a61bc60dd3b4093b0f1b7f19b5af339.html?documentId=70f271a1f6b843b4ac8b2e67c3f8a082)
- [Google Play services setup / availability](https://developers.google.com/android/guides/setup)
- [FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)
- [CurrentLocationRequest](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest)
- [Android Location / timestamps / accuracy](https://developer.android.com/reference/android/location/Location)
- [Android LocationManager](https://developer.android.com/reference/android/location/LocationManager)
- [Android foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android app-specific storage](https://developer.android.com/training/data-storage/app-specific)
- [Android system document picker](https://developer.android.com/training/data-storage/shared/documents-files)
