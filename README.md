# Rokid FieldLog

把现场照片整理成接收方能找到位置、看清问题的报修材料，再记录后续观察与人工复查。

适合先在一个园区、校园或物业区域试用。手机可以直接选入自己的 JPEG，核对地址、处理人脸与车牌、组合环境照和细节照，再分享给实际接收人或保存材料。眼镜提供另一个采集入口，手机端可独立使用。

## 用自己的照片开始

1. 从 [GitHub Actions](https://github.com/PYCST-DDZYL/RokidFieldLog/actions/workflows/android.yml) 最近一次绿色运行的 **Artifacts → debug-apks** 下载构建产物。手机安装 `phone-app-debug.apk`；最低 Android 9。安装会保留已有同签名版本的数据。
2. 打开“选择手机照片（JPEG）”，选同一问题的一张环境照、一张细节照。应用只复制你选择的文件，每次最多 20 张；坏图或读取失败会撤销本批导入。重复选同一文件会保留已有编辑。
3. 进入“报修材料（环境照 + 细节照）”，新建材料，填写实际接收人、对方的分类、地址、参照物、人工确认的现场日期和说明，选入 2–5 张照片。
4. 逐张打开隐私审阅，遮挡无关人脸、车牌等信息，放大检查实际生成的 JPG，再确认同一问题、同一次观察及照片角色。
5. “分享已审阅照片和说明”会打开系统分享；对方应用若不接收附带文字，可单独“复制报修说明”。也可保存 ZIP 后取出文字与 JPG 手动填表。请自行确认接收方是否收到及受理。

没有合适的照片时，可先“载入演示记录”或“载入复查流程演示”。演示始终带合成标记，不能当作真实事项提交。详细流程见 [离线整理](docs/offline-companion.md) 和 [多图材料](docs/multi-photo-materials.md)。

Actions 提供开发测试包，当前未配置持久的发布签名。更换构建来源或运行批次可能导致签名不匹配、无法覆盖安装。有需要保留的记录时不要通过卸载来强行更新；先保留当前应用，持续使用同一签名的构建。详见 [安装与签名限制](docs/install.md#preview-signing)。

选入照片不会把导入日期当成拍摄日期，也不从照片推断问题位置。当前支持 JPEG，单张最多 16 MiB、2400 万像素；PNG、HEIC 和视频需先自行转换。照片导入和材料整理不需要定位或整个相册的读取权限。

<a id="current-status"></a>

## 当前状态

当前状态集中在下表，版本变化见 [CHANGELOG](CHANGELOG.md)。旧的带日期报告保留为历史证据。

| 能力 | 状态 |
| --- | --- |
| 手机直接选照片、私有复制、重复跳过、整批回滚 | 已实现；系统选择器与真实设备兼容性待验收 |
| 记录搜索、每页 20 条、未审阅筛选 | 已实现；筛选与页码在页面重建时保留 |
| 地址、说明、日期及可选的明确坐标系坐标 | 已实现；切回页面保留未保存编辑，返回时保存有效编辑 |
| 逐张隐私遮挡、实际 JPG 预览与确认 | 已实现；原图保留，导出副本去除原始 EXIF 等元数据；需人工检查遗漏 |
| 2–5 张报修材料、环境/细节角色及缺项检查 | 已实现；分类由实际接收方决定，照片内容仍由人判断 |
| 系统分享审阅副本、复制说明、保存 ZIP | 已实现；私有原图库不通过分享接口开放，分享不登记受理 |
| 稳定问题历史、维修反馈、待复查清单及前后对比 | 已实现；明确的人工复查才形成维修确认记录 |
| 眼镜拍照、类别、回看/撤销、后台恢复和空闲释放 | 已实现；早期版本在 RG-glasses 验证拍照，本轮交互需真机验收 |
| 手机 Fused/系统定位及 60 秒等待 | 已实现并有回调/超时回归；真实 GPS、无 GMS 手机待验收 |
| 眼镜到手机无线同步、地图修正和坐标转换 | 待 Rokid 协议、地图提供方与设备集成 |
| 指定地区政务表单兼容、实地耗时、续航和发热 | 待真实接收方与设备试验，不能把合成演示当成结果 |
| JVM 测试、两端 APK、Lint 和 Linux CI | 本地 176 项测试通过、两端 APK 校验通过、Lint 0 错误；远端结果见 [Actions](https://github.com/PYCST-DDZYL/RokidFieldLog/actions)，真机验证独立记录 |

## 数据和交接

所有原图、草稿及历史默认保存在各自应用的私有目录，Android 备份已关闭。覆盖升级保留数据，普通卸载清理私有数据。系统选择器中未选择的照片不会被读取或修改。当前没有账号、云端照片库或自动政务提交。

分享前会重新校验原图导入指纹、审阅副本与材料条件，只提供独立的已审阅 JPG 和共用说明。生成满 24 小时的分享副本在下次打开首页或准备分享时清理，较新的副本保留供接收应用稍后读取；系统可能更早回收缓存。“删除全部项目数据”同时清除这些暂存副本。已经交给其他应用或保存到自选位置的副本由你管理，修改本地遮挡不会撤回它们。

一张材料照片长边最多 2048 像素、最多 5,000,000 字节；2–5 张是本项目的整理范围。当地渠道可能另有照片张数、现场拍摄、字段或联系信息要求，先按 [渠道调研](docs/submission-channels.md) 核实。JPEG、坐标和时间的指纹及校验不证明现场真实性。

## 构建与验证

使用 JDK 21、Android SDK 36、Build Tools 35.0.0。仓库固定 Kotlin 2.2.21、AGP 8.13.2 和 Gradle 8.13，并校验 Gradle 分发包。

Linux/macOS 配置 `ANDROID_HOME` 或 `local.properties` 后：

```sh
./gradlew --no-daemon :fieldlog-core:test assembleDebug lintDebug
```

Windows 准备项目内工具后：

```powershell
.\scripts\Build.ps1 -Verify -Clean
```

两端 APK 在各模块 `build/outputs/apk/debug/`。Windows 校验脚本另检查实际 APK 的包名、必需类和手机分享权限边界。Linux Actions 自动测试、构建、Lint，并上传 APK、测试报告及明确标记的合成样例。

完整安装、设备授权与清理步骤见 [安装指南](docs/install.md)。旧的 [Phase 1 Preview 2 Release](https://github.com/PYCST-DDZYL/RokidFieldLog/releases/tag/v0.1.0-preview.2) 不含当前手机整理功能；使用当前源码或 Actions 构建产物。

## 参与改进

最有价值的反馈是：接收方还缺什么、哪一步需要补拍或重填，以及与原来手机/微信/表单方式比较的完整人工耗时。尚无真实用户效率或付费验证；[产品范围与执行清单](docs/practical-product-scope.md) 和 [试点协议](docs/inspection-pilot.md) 说明如何验证。

- [提交问题或使用反馈](https://github.com/PYCST-DDZYL/RokidFieldLog/issues/new/choose)：提供版本、复现步骤和期望结果，可用内置演示复现。
- [贡献指南](CONTRIBUTING.md)：运行检查，区分 JVM 合成测试和 Android 真机结果。
- [路线图](docs/roadmap.md)、[数据格式](docs/record-format.md)、[坐标与隐私设计](docs/coordinates-and-privacy.md)、[现场验证](docs/field-validation.md)。

模块划分：`glasses-app` 采集，`phone-app` 整理与交接，`fieldlog-core` 记录契约、恢复、材料、历史及测试。`.tools/` 和 `.local/` 是忽略的本机工具和私有证据目录；不要提交真实照片、坐标、设备标识、日志或密钥。

## 许可

项目源码采用 [MIT](LICENSE)。[Gradle wrapper 许可](gradle/wrapper/LICENSE) 与 [声明](gradle/wrapper/NOTICE)、Android/Google 依赖及未来 Rokid SDK 各自保留其许可。这是独立项目。
