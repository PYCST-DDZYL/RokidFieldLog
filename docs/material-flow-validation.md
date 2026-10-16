# 本轮验证记录（2026-10-16）

本轮包含眼镜采集包导出、参考位置、材料流程、持久签名，以及现场先定地址、后补照片和私人回执记录。未增加无线协议、地图、坐标转换、自动识别或自动政务提交。

## 代码与产物验证

| 检查 | 实际结果及边界 |
| --- | --- |
| 核心JVM测试 | 184项，0失败、0错误。均为主机合成图片/位置，不代表实机验收 |
| 现场空材料→后补照片 | 无照片可保存；后续并入保持同一reportId、地址、参照物、确认和参考坐标；新增照片不自动确认角色、同一问题或隐私；重复ID不重复占名额；超额、过期版本、演示与真实混用拒绝 |
| 首次交接与私人记录 | 不以接收方预先接受打码相册为门槛；目的→详细地址→正文分段；真实回执、补充要求、退图、耗时与相册结果保存在材料但排除于对外白名单；分享不改变受理阶段 |
| 采集包格式 | 核心采集仓库写FIELDLOG_CAPTURE_PACKAGE，手机离线库读取；校验指纹、重复批次ID，变化原图拒绝导出。不是物理USB链路验收 |
| 最终构建 | 两端assembleRelease、lintRelease通过，BUILD SUCCESSFUL |
| Lint | 手机0错误/63警告，眼镜0错误/12警告。未为字符串拼接/KTX/单一中文改写资源 |
| 最终APK检查 | 两端Test-Apk.ps1 -Variant release通过：必需DEX类、分享Provider仅开放reviewed-shares缓存、release不可调试、固定证书有效 |
| 密钥持续性 | 重复初始化前后PKCS12字节不变；错误debug密钥被verifyReleaseKey拒绝。私钥和密码仅保存在忽略的.private目录，未上传 |
| 远端CI | 已有使用同一密钥的手动发布工作流；未配置远端Secrets、未执行或发布远端版本 |

测试与首次构建日志：.local/onsite-build.log。最终构建日志：.local/onsite-final-build.log，以及手机最后修正后的.local/onsite-phone-final-build.log。测试报告：fieldlog-core/build/reports/tests/test/。最终构建在最后的后台保存和确认失效调整后重新完成；核心逻辑测试未冒充Android生命周期测试。

| 发布文件 | 版本 | SHA-256 |
| --- | --- | --- |
| phone-app/build/outputs/apk/release/phone-app-release.apk | 0.7.0-onsite-address / code 8 | 最终构建后补充 |
| glasses-app/build/outputs/apk/release/glasses-app-release.apk | 0.2.0-package-export / code 3 | d8b782df4b958b7fcbbedc2d5aeffe643aae236c4ce3fa060b49fcc73635877e |

两端最终APK均通过v2签名校验。发布证书SHA-256：8fc71c5d65b036317565fe78d950b1562976dbec3dfe09d4b98d8dce09506674。须另行安全备份同一密钥，不能靠重建新密钥替换。

## 真实设备完成的部分

- 标准adb.exe受当前工具进程的Windows用户目录异常影响，启动报Cannot mkdir。后经SDK官方AdbWinApi.dll使用现有电脑ADB密钥正常认证，确认连接Samsung SM-S9480（Android16 / API36，有GMS）和Rokid RG-glasses（Android12 / API32）。未修改系统驱动、注册表或用户目录，未绕过设备授权。
- 手机先安装固定签名code7，再将最终code8 APK传输到设备，设备SHA-256与本机一致，pm install -r返回Success，前后包UID相同。MainActivity启动Status ok。未卸载、未清空应用数据。尚未通过UI核对手机旧材料内容保留。
- 手机最初处于安全锁屏，后来正常解锁。已在最终新版首页进入“现场先定地址”，无照片即可创建材料；点空地址确认时显示缺项阻拦。系统定位弹窗选择精确位置、仅限这一次，随后取得真实FUSED/fused定位和系统文字地址建议，建议进入地址框，参考坐标明确标注非问题位置。参照物未补齐，按“暂存待确认”离开；测试没有确认对方能找到，也没有虚构现场问题。地址建议保存/重新打开另外核对。此项是有GMS手机上的一次Fused定位，不等于已验收独立卫星GPS或无GMS定位。
- 眼镜原装debug code1。升级前备份项目私有文件53,024,256字节，181个文件全部与逐文件SHA-256核对一致，包含7张原照片。备份和原图仅在忽略的.local/device/material-flow目录。
- 本机留存的旧debug私钥与眼镜已安装证书相符。用它授权固定发布密钥进行系统支持的v3签名轮换，迁移覆盖安装Success，随后普通固定签名code3发布APK再次覆盖Success，包UID均保持。没有卸载或清空图库。眼镜恢复提示restored=0、incomplete=1、errors=0；那1条未完成记录没有记成通过。
- 眼镜也已普通覆盖安装表中最终APK，Success且UID相同。眼镜唤醒后可从UI树读到7张照片及第四项“导出采集包 / USB转存”；本轮ADB触点/方向键未成功选中导出。未发送可能误触拍照的确认键。没有证明真实触控板、光学显示或实物导出转存完成。

## 仍待现场与设备验收

1. 由人在真实问题现场核对建议地址、补参照物并确认对方能找到，完整验证离开前保存、失败手填、权限拒绝、取消和重新打开。一次Fused定位及地址建议已取得，不代表这些分支全部通过。系统地址建议使用Android Geocoder，可能没有服务或无返回，不能保证每台设备/国家都可用；参见[Android官方说明](https://developer.android.com/reference/android/location/Geocoder)。无GMS设备仍待独立测试。
2. 眼镜同处环境照+细节照、触控板选择导出、光学显示、MediaStore下载目录、电脑USB转存、手机导入原现场材料。确认地址保留，并逐张核对角色、隐私、同一次观察。
3. 手机选择器、后台/旋转恢复、分享目标接收、ZIP保存、已有真实照片/材料保留，以及连续使用的续航发热。
4. 用户说明现场不在中国。本轮没有真实江苏事项，没有锁定实际提交市，没有核对该市官方区县选项，没有登录、上传或提交。当地真实渠道、回执、附件接受/拒绝和完整人工耗时均未验证；不能编造事项试交或写成已兼容。
5. 真实回执后，下次路过沿用现有观察关联和前后对照，由人判断。回执不等于修复，不自动登记受理，也不把“没有打码的照片被接收”当作“打码照片已获接受”。
