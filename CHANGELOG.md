# Changelog

## 2026-10-19 — 按渠道展开材料字段

- 通用材料收起江苏专用的省市区、媒体采访、须知和外链，仅选择江苏入口时显示；切换只改变可见性，保留正在填写的字段。联系方式告知与照片限制区分通用交接和江苏入口。
- 修复通用材料联系人、电话在复制及分享说明中遗漏的问题，ZIP 文本与分享共用同一说明。
- 切回通用入口后，江苏专用字段仍保留在私人草稿，但对外 JSON 对应值为空、文字说明不包含这些字段；重新选择江苏入口可继续原填写。
- 新增两项合成材料集成回归，检查联系方式一致性及渠道切换后的导出边界。手机 0.7.4-report-form / code 12，眼镜 0.2.4-material-fields / code 7，沿用固定发布签名。
- 本机 213 项测试与两端发布构建通过；三星手机已保留 UID 覆盖更新。手机仍锁屏，当前页面与定位未作真机验收，详细结果见[检查记录](docs/report-form-validation.md)。

## 2026-10-18 — 导出失败与报告恢复（第二轮检查）

- 文档复制先打开源文件，再打开用户选择的目标；源文件打不开时，不提前创建、截断或删除目标。目标打开失败时关闭源流并保留原错误，写入开始后的失败仍尝试清理部分文件。
- 报告页面重建后，文件选择器返回可能早于 onResume。导出完成后按需重新读取报告，成功及失败都能恢复表单；读取期间再次重建仍保留待恢复编辑。
- 加入真实隔离文件的导出顺序/关闭回归，并修正清理失败测试，使其实际触发写入失败后的清理分支。
- 手机 0.7.3-export-recovery / code 11，眼镜 0.2.3-reliability / code 6。执行证据、预期与真机限制见[导出与恢复检查](docs/export-recovery-review.md)。

## 2026-10-18 — 定位历史与隐私确认校验

- 定位检查显示和历史参考位置选用共用严格读取：只有受支持版本的成功结果、明确的非模拟状态、有效坐标/时间及回调数据年龄才可采用。失败或取消结果即使残留坐标对象，也不显示为成功；缺少模拟标记不会默认成真实定位。
- 草稿确认标记必须为布尔值；版本号、照片尺寸及审阅时间必须为其范围内的整数；遮挡框必须明确提供四条数值边界。拒绝字符串确认、小数截断和数字溢出。
- 草稿、审阅或问题坐标含未知字段时保留原文件并停止旧版本编辑，防止未来信息被静默丢弃。异常隐私确认不能解锁材料导出。
- 手机 0.7.2-reliability / code 10，眼镜 0.2.2-reliability / code 5，沿用固定发布签名。执行结果及边界见 [本轮检查](docs/bugcheck-2026-10-18.md)。

## 2026-10-17 — 参考位置与构建可靠性修复

- 单照片和多照片草稿在解析拍摄者参考位置前检查原始 JSON：经纬度、坐标系、来源与整数时间戳必须明确有效。不再把缺失坐标补成零、把缺失坐标系补成 WGS84，或将字符串/小数/溢出时间当成有效数据。真正的零坐标、未知精度和未选位置仍可保存。
- 未知的参考位置字段会阻止旧版本重新保存，原文件保留；损坏记录不进入导出，其他可读照片继续显示。
- 单照片页面恢复编辑状态时，“移除参考位置”与其他编辑字段共用版本校验，避免旧界面状态覆盖更新后的记录。
- Windows PowerShell 5 构建改为读取 Gradle 实际退出码，保存日志时不再把普通 SDK stderr 警告误判为失败。
- 手机版本 0.7.1-reliability / code 9，眼镜版本 0.2.1-reliability / code 4。详细验证和复测方式见 [本次 bug 检查](docs/bugcheck-2026-10-17.md)。

## 2026-10-16 — 采集到材料与固定发布签名

- 眼镜新增原始采集包导出到Download/FieldLog，格式接入手机既有导入。
- 手机导入保留批次并可直接新建材料；首页按选图、材料、提交后复查排列，演示与定位检查移到更多。
- 最近成功定位可显式选入记录/材料，保留时间、精度和来源，标明拍摄者参考位置而非问题位置。
- 只按已打开的江苏12345公开页准备字段、实时字数和缺项；区分应用5 MB与官方单附件10 MB，新增联系方式告知及打码相册接收确认。
- release使用固定私钥并核对公开证书指纹；本地出包、APK校验、安装与手动CI流程支持release，禁止退回debug签名。
- 本地184项核心回归通过，两端release构建及Lint通过；真实GPS、无GMS、眼镜触控/光学显示、续航发热仍待设备验收；已验证眼镜签名轮换保留数据及随后普通发布覆盖安装。

## 0.5.0 Phone intake and handoff preview — Unreleased

- Add direct user-selected phone JPEG intake through the system photo picker on Android 13+ with a document-picker fallback. Copy only selected streams into private storage, bound bytes/pixels/count, validate a full batch before commit, and preserve existing edits on exact-byte re-selection. Unknown exposure time and position stay null; imported photos require their own privacy review.
- Add native multi-photo sharing and separately copied report text. Reuse full report/photo validation, create independent reviewed JPEG snapshots, and grant temporary read access only through a concrete FileProvider scoped to the reviewed-shares cache. No source JSON or original photos are shared. Sharing never records submission, receipt or repair success.
- Retain recent share snapshots for delayed receiving-app reads; remove expired snapshots on later maintenance, clean abandoned preparations, and include share cache in confirmed project deletion. Android can evict cache earlier; already handed-off files cannot be recalled through local edits.
- Page the phone library at 20 records, add an unreviewed-photo filter, and preserve search/filter/page state. Keep controls disabled until all queued home-screen operations complete.
- Preserve same-revision single-record edits when returning from the background, save valid edits on normal Back/return, allow explicit discard for invalid coordinates, and clear address confirmation after address changes. These Android interaction paths still need device acceptance.
- Fix multi-photo export deleting an unstarted destination after validation failure. Share a tested document-copy operation across single/multi-photo exports: validate first, clean only after a write starts, and preserve the original error if cleanup is denied.
- Rewrite the README around a Chinese real-photo quick start; add bug and real-work feedback forms plus a PR template. Document the product execution checklist and actual acceptance limits. CI also uploads JUnit XML; Windows APK checks inspect required share classes, the packaged provider and its cache-only XML roots.
- Phone version is 0.5.0-handoff-preview (versionCode 6).

Validation: 176 JVM tests passed, including 28 new intake/share/copy regressions. Both debug APKs built; Android Lint has 0 errors and 40 remaining warnings (11 glasses, 29 phone: localization/style suggestions and API-33 attributes ignored on older Android). Source/pixel/file-boundary tests do not establish Android picker, sharing-app, Back/rotation, GPS or field acceptance. The executed APK checks include the compiled provider and cache-only resource path; latest remote results are in [GitHub Actions](https://github.com/PYCST-DDZYL/RokidFieldLog/actions).

## 0.4.1 Reliability fixes — Unreleased

- Revalidate single-photo draft revisions, import-time original fingerprints, approved JPEGs and prepared ZIPs immediately before writing to the selected document. Changed text or privacy approval now requires regenerating materials. Clean abandoned temporary ZIPs and attempt to remove partially written external documents after copy failures.
- Discard unused redaction previews when returning to editing or closing a screen. Retire an older approved JPEG only after its replacement is committed; delayed cleanup cannot delete the current approval or original. Forced process termination still requires later record/project cleanup for any orphaned files.
- Use the current report revision when deleting a multi-photo draft after a save that did not redraw its controls.
- Retain a valid provisional location when the Android provider fails or throws on startup, explicitly label its reduced quality, and continue rejecting fixes older than five seconds. Keep original callback age separate from result completion time.
- Update the saved-photo count only after the deletion journal commits, retaining the count and reviewable photo on metadata write failure.
- Reject null/string draft coordinate axes and fractional/string schema versions or package record counts before Gson can coerce them. Unknown positions remain distinct from genuine zero coordinates.
- Bump the phone preview to 0.4.1-materials-preview (versionCode 5) and the glasses app to 0.1.1-phase1 (versionCode 2).

Validation: 148 JVM tests passed, including 18 new regressions for stale materials, preview cleanup, provider failure, failed deletion writes and malformed JSON. Both debug APKs built and package/required DEX classes verified; Android Lint has 0 errors. Android gestures, rotation, document-provider failures, actual location and battery/heat acceptance still require devices.

## CI environment fix — 2026-10-15

- Explicitly initialize Android command-line tools and install SDK 36 / Build Tools 35.0.0 before Gradle. The first remote run failed during SDK preparation with exit code 127, before tests started.
- Pin the runner to Ubuntu 24.04 and the Android setup action to its verified v4 commit. Remote validation results are available in [GitHub Actions](https://github.com/PYCST-DDZYL/RokidFieldLog/actions).

## 0.4.0 Multi-photo materials preview — Unreleased

- Add persistent multi-photo report drafts with one recipient, receiver-specific category, address, landmark, manually confirmed visit date and description. Reuse existing manual text when starting from a record; photo tags do not silently become official categories.
- Add missing-material checks for 2–5 distinct photos, context/detail roles, a findable location, same-issue/same-visit confirmation, known date conflicts and independent privacy review. Roles and site/date judgments remain manual.
- Export whitelisted shared text/JSON and reviewed JPEGs, excluding original GPS, private source fields, receipts and longitudinal repair history. Revalidate source/review fingerprints and draft/photo revisions immediately before external saving.
- Protect photos referenced by report drafts, retain originals when deleting a report, preserve unreadable/future records, and discover AtomicFile companions for recovery/deletion safeguards. Isolate synthetic/real material and clear synthetic reports with demo records.
- Add phone entry points, searchable/paged photo selection, editable roles, save-on-back and rotation state. Phone version is 0.4.0-materials-preview (versionCode 4). Android interaction acceptance remains pending.
- Define a practical one-site product hypothesis and proposed first-pass usability/full-work-time measures. Add current official multi-photo channel evidence; no local form compatibility, field efficiency or paying customer is claimed.

Validation: 130 JVM tests passed (20 new real-library multi-photo integration tests). Both debug APKs built; package/required DEX classes verified, including ReportActivity and ReportRepository, and the final phone manifest/version checked. Android Lint has 0 errors and 37 remaining warnings (11 glasses, 26 phone: UseKtx, SetTextI18n and a newer-API manifest attribute ignored on older Android). Synthetic report ZIP/text/manifest and both redacted JPEG fixtures were checked. Android UI, system picker/return gestures and field efficiency remain untested on a device.

## 0.3.0 Inspection preview — Unreleased

- Add stable issue IDs with append-only, revision-checked observations, repair feedback and separate human verification. Original record receipt stages do not imply physical repair verification.
- Add manual photo association with confirmed site/date/visibility/finding. Reject reused IDs, identical bytes under a new ID, mixed synthetic/real evidence, out-of-order dates and altered originals. A later problem reopens the issue; an unclear new view returns to recheck.
- Add paged issue history/search, pending-recheck filtering, and zoomable original/latest photo comparison. Repair confirmation requires clear new evidence after the latest feedback plus explicit human chronology confirmation; unknown and obstructed views cannot close an issue.
- Bind comparisons and verdicts to import-time photo fingerprints. Protect linked records from single-photo deletion; confirmed tracking deletion retains originals. Preserve unreadable/future histories and stop mutations that could lose unknown links.
- Add an actionable synthetic Android walkthrough and integrated host workflow fixtures. Optional test artifacts include a standalone interactive inspection-demo.html, a clearly synthetic capture ZIP and inspectable history JSON.
- Add a one-site pilot protocol measuring full human effort, phone/glasses usability and willingness to pay; no measured tenfold improvement or field acceptance is claimed. Phone version is 0.3.0-inspection-preview (versionCode 3).

Validation: 110 JVM tests passed (26 new inspection tests), both debug APKs built and package/required classes verified, Android Lint 0 errors (35 remaining style/localization warnings). The five-step synthetic HTML data and three JPEG fixtures were checked. Isolated browser screenshot rendering was unavailable because GPU subprocess startup was denied; no Android UI rendering or touch success is claimed. Wireless sync, visual matching/recognition, maps, physical Android UI tests and real pilot outcomes remain pending.


## 0.2.0 Offline companion preview — Unreleased

- Replace the phone launch screen with an offline record library: import validated capture packages, search records, edit confirmed category/address/date/description and optional coordinates with an explicit CRS. Preserve immutable source annotations separately from revisioned drafts.
- Add bounded, staged ZIP imports with path/identity/version/photo checks, duplicate preservation and atomic batch commit. Corrupted existing entries remain on disk and do not hide readable records or permit import overwrite.
- Add local upright photo viewing, zoom/pan, opaque mask drawing/undo, derived JPEG generation and explicit review of the actual output. No-mask reviews require an explicit no-third-party-information declaration. Limit decode memory, output long edge and bytes; omit original metadata.
- Add generic manual-material ZIPs containing text, a whitelisted manifest and the reviewed JPEG. Bind the original to its import-time fingerprint before first review and recheck original/output fingerprints on export; reject private metadata, and require confirmed content and photo review. Save with the system document picker; no automatic government submission or official channel compatibility claim.
- Track material save, explicit manual submission and resolution receipts. Content edits return to draft while retaining receipt history; a late export cannot mark newer edits exported.
- Add clearly marked synthetic Android demos and host-only JPEG workflow tests. Opt-in -PfieldlogDemo=true produces ignored synthetic sample packages; CI uploads these, verification reports and debug APKs.
- Keep independent location diagnostics under “定位检查”; extend APK verification to the new activities, redaction view and core workflows. Phone version is 0.2.0-offline-preview (versionCode 2).

Validation: 84 JVM tests passed (19 new offline workflow tests), both debug APKs built and package/required classes verified, Android Lint 0 errors (29 remaining style/localization warnings across both apps). Synthetic material ZIP/text/JPEG generated and inspected. Linux CI configuration is updated; a remote run is still pending. Wireless glasses transfer, map integration, selected-city form acceptance, Android rendering/input acceptance and hardware energy measurements remain pending. No phone was connected for this work.

## Capture reliability update — 2026-10-14

- Allow capture during background recovery. Persist validated file statistics to skip unchanged JPEG decode/hash on startup; full validation remains available.
- Move record/probe models, versioned JSON codec, recovery and location orchestration into fieldlog-core with injected clocks, image inspection, storage and provider/scheduler interfaces.
- Replace locked disk-based UI counts with memory snapshots. Run journal creation, sync, undo and deletion on worker queues; recover photos individually instead of under one global storage lock.
- Add latest-photo review and confirmed undo. Move delete-all into data management. Empty-background taps show the controls; explicit camera input triggers capture. Show the selected category on save and preserve selection during Activity recreation.
- Use JPEG quality 93 and a 64 MiB pre-capture free-space reserve. Release camera after 60 seconds idle; clear screen-on when prompts hide.
- Extend the phone deadline to 60 seconds. Display provisional low-precision fixes while waiting; preserve explicit quality and reject stale/late/mock fixes. Cover cancellation, fallback and queued callbacks with JVM fake-provider tests.
- Add Ubuntu GitHub Actions running :fieldlog-core:test assembleDebug lintDebug and upload verification reports.
- Consolidate current status in README. Add channel research, the shared record contract, coordinate/privacy design and a device/energy test protocol. Prior dated documents are historical evidence.

Validation: 65 JVM tests passed; both debug APKs rebuilt and package/startup classes verified; Android Lint 0 errors (glasses 11 warnings, phone 2). Staged-source privacy and whitespace checks passed. Linux CI awaits its first remote run. ADB discovery was blocked by this execution environment's key-directory access restrictions; no new device success is claimed.

At this revision, hardware verification, local submission-form acceptance, map/redaction/export implementation and field battery measurements remained pending. The offline companion work above subsequently implements generic redaction/export.

## 0.1.0 Preview 2 — 2026-10-13

- Verified local glasses photo capture and private JPEG/JSON persistence on first-generation RG-glasses.
- Added manual quick tags, capture time checks, saved-photo SHA-256 fingerprints and repair that preserves annotations.
- Built both APKs; passed 26 core JVM tests and 13 isolated Android recovery fixtures. Phone GPS and physical wearable gestures remained unverified.

## 0.1.0 Preview 1 — 2026-10-11 to 2026-10-12

- Added the glasses capture app, separate Android phone location probe and durable photo journals.
- Prepared project-local Windows tools and published the Phase 1 development preview.

## 2026-10-16 现场地址流程补充（手机0.7.0 / code 8）

- 手机先定地址，允许无照片保存；本次定位只提供参考坐标和系统文字建议，失败明确手填，地址/参照物及“对方能找到”由人确认。
- 导入后优先并入原现场材料，保留地址；新增照片不自动确认角色、同一问题或隐私。
- 首次交材料不以打码接收为门槛；分段复制目的、详细地址、正文；回执/额外要求/退图/实际耗时留在同一材料的私人记录，不对外导出。
- 新草稿默认通用人工交接，现场不在中国时不强套江苏。江苏真实市/官方区县尚未锁定，未进行真实提交；复查对照界面沿用现有流程。

- 真实SM-S9480选择精确、仅本次定位权限后，取得Fused参考位置与系统文字建议；无照片现场草稿覆盖升级后保留地址与参考位置，参照物缺失保持待确认。没有提交虚构事项，未完成真实渠道受理或完整人工耗时验证。
