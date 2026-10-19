# 材料表单与手机更新检查（2026-10-19）

> 历史记录，不能用作当前源码与 APK 完全一致的证明。后续真机发现 code 12 包内仍有构建期间修改前的表单文字；下文的文件时间比较不足以排除这一问题。已增加构建输入前后指纹、私有构建凭据及安装前校验，并重新清理共享核心与两端构建。后续还复现了导出照片预览失败，不能将本记录视作材料流程已可用的验收结论。

## 本次改动

通用报修不再显示江苏专用的行政区划、媒体采访、须知和网页入口。选择江苏入口才展开这些控件，切换时不重建表单，因此保留正在填写的内容。联系方式始终可见并说明交出范围：通用交接可选，江苏入口必填。

检查中发现通用交接填写的联系人和电话原先只进入 ZIP 的 JSON，不进入复制、分享及 ZIP 的文字说明。本次将它们加入共用文字生成逻辑；未填写时不生成空的联系方式行。

收起的江苏字段仍存在私人草稿，切回江苏可继续填写。通用材料的 JSON 保留相应键但值为空，文字不包含行政区划和媒体采访选择，避免隐藏内容继续进入对外文件。联系人和电话不属于隐藏字段，填写后明确随材料交出。未增加账号、云端提交、模型识别或无线协议。

| 文件 | 作用 |
| --- | --- |
| phone-app/src/main/kotlin/org/rokidfieldlog/phone/ReportActivity.kt | 按渠道显示字段、保留控件内容、明确联系方式与照片要求 |
| fieldlog-core/src/main/kotlin/org/rokidfieldlog/core/ReportMaterials.kt | 通用联系方式文字修复，当前渠道之外的专用值不对外导出 |
| fieldlog-core/src/test/kotlin/org/rokidfieldlog/core/ReportMaterialsTest.kt | 两项材料集成回归 |
| phone-app/build.gradle.kts | 手机 0.7.4-report-form / code 12 |
| glasses-app/build.gradle.kts | 共享核心更新后的眼镜产物 0.2.4-material-fields / code 7 |
| README.md、CHANGELOG.md、本文件 | 行为、验证方式与边界 |

## 已执行的主机验证

- `scripts/Build.ps1 -Release -Verify -Clean` 成功，Gradle 用时 4 分 47 秒；115 个任务，113 执行、2 个已是最新。编译后的表单及仓库类时间均晚于对应源文件修改时间。
- 213 项 JVM 测试，0 失败、0 错误。新增测试使用合成图片、合成地址和虚构联系方式，不使用真实手机照片或 GPS。
- 新增回归实际读取 ZIP 内文字及 JSON，并比较复制、分享与 ZIP 共用说明；另验证江苏→通用→江苏保存切换，专用字段在通用导出中为空、草稿仍保留、再次选回后完整通过检查。
- 两端 Lint 为 0 错误；眼镜 16 条、手机 66 条警告，仍有本地化、样式、依赖及兼容性提示，没有屏蔽警告来宣称通过。
- 两端 APK 通过包名、必需 DEX 类、发布签名与不可调试检查；手机分享 Provider 仍只开放已审阅副本缓存。

本机完整日志仅在忽略目录 `.local/phone-review-1019-build.log`。JVM 结果不等于 Android 控件、系统选择器、实际定位或眼镜触控验收。

| 本机产物 | 字节数 | SHA-256 |
| --- | --- | --- |
| phone-app/build/outputs/apk/release/phone-app-release.apk | 7,363,068 | d4d8471dc9dafce62212d7893fa42556d507d52ffd3c68e9f480ae1898b6b92d |
| glasses-app/build/outputs/apk/release/glasses-app-release.apk | 6,975,843 | 0b0b711a318dc4caeef748d2ea868ed048b2a7fba75f0849fd3a4d4680e435f5 |

两端继续使用已留存的固定发布签名，证书指纹见根目录 `release-certificate.sha256`。私钥和密码未上传。

## 本次设备结果及限制

标准项目内 ADB 已识别 Samsung SM-S9480（Android 16 / API 36，有 Google Play services）与 Rokid RG-glasses（Android 12 / API 32）。标识未输出或上传，未改变驱动或设备设置。

手机安装前读取本项目旧 APK，确认签名与固定发布证书一致，再通过 `install -r` 从 code 8 覆盖至 code 12。安装返回 Success；更新前后包 UID 相同，设备 APK 的 SHA-256 与上表一致。MainActivity 启动返回 Status ok，之后再次检查进程仍运行。没有卸载或清空数据；这些结果不能单独证明每份旧草稿的内容均已逐字核对。

本次手机持续显示安全锁屏，FieldLog 未取得窗口焦点，因此没有点击报告表单、系统选择器或定位按钮，也没有新取得一次位置。锁屏不能由电脑代为解锁。此前 10 月 16 日的一次 Fused 定位和地址建议仍是历史证据，详见[当时记录](material-flow-validation.md)，不能替代当前版本的真机测试。

本次没有更新眼镜安装包或拍摄新照片。当前手机已安装的包名中没有 Rokid 官方 App；CXR-L 集成的官方手机 App、授权与设备兼容性仍需核对，未自行安装第三方 App。无线同步、地图人工修正及自动识别仍未完成。

## 如何继续复测

重新执行本机检查：

```powershell
.\scripts\Build.ps1 -Release -Verify -Clean *> .local/report-form-retest.log
```

预期为 213 项测试通过、两端 APK 校验通过；已有 Lint 警告须按实际报告保留。仅需要手机覆盖更新时，用户已授权后执行：

```powershell
.\scripts\Install-App.ps1 -Target phone -Release -ConfirmInstallation -StartApp
```

设备测试使用单独的合成演示材料，不修改真实问题草稿，也不向真实市政渠道发送：

1. 正常解锁手机，打开 FieldLog，新建通用材料。省市区、媒体采访和江苏外链不显示，通用字段仍可填写。
2. 在合成材料填虚构联系人、电话，切换江苏、填合成行政区划，再切回通用；通用内容保持，专用控件收起。再次选择江苏，原填写应仍在。
3. 保存并退出重开，核对选择的渠道、填写和确认状态；前后台切换、页面重建也要独立测试，主机测试不覆盖 Android 生命周期。
4. 对两张合成图分别完成隐私审阅及环境/细节角色确认，再复制说明、打开系统分享或生成 ZIP，核对联系人、电话与合成标记。选择器取消和页面重建应能返回表单。仅在系统文件选择器中明确选择测试保存位置；不覆盖或删除已有文件。
5. 首页“更多：演示与定位检查”进入定位页，分别试自动定位、Android 系统定位和停止操作。记录来源、权限、定位时间与接收时年龄，真实坐标只留本机私有证据。没有新鲜结果时应显示失败或待确认，不填零坐标。测试页结果不会自动关联眼镜照片。

系统选择器的创建动作与外部文件保留规则参见 [Android 官方文档](https://developer.android.com/training/data-storage/shared/documents-files#create-a-new-file)。手动导出的文件在卸载后仍由用户管理；测试不可把取消、空文件或仅打开分享页面当作成功交接。
