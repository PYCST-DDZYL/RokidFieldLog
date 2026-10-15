# Shared record contract

The source of truth is fieldlog-core's CaptureRecord, LocationObservation, GeoPoint and LocationProbeRecord, serialized by CaptureRecordCodec. Android owns directory layout and AtomicFile persistence; CaptureFiles owns only photo/journal transactions and accepts record IDs/collision checks from the caller. Phase 2's phone receiver must use this codec rather than recreate JSON keys.

## Version 1 compatibility

Each capture is UTF-8 JSON with schemaVersion 1 and a lowercase UUID photoId. Unknown positions and times are explicitly null. Fields added in this update are optional on read. Defaults apply through explicit Gson InstanceCreator factories, with Unsafe disabled and stable SerializedName annotations. A future schema version or invalid typed record is retained and reported for review. Truncated JSON can be backed up and reconstructed from the journal and image; a foreign photoId is never adopted.

Capture and issue-draft schema versions, and package manifest versions/counts, require JSON integer tokens. Fractional values and numeric strings are rejected before conversion. Non-null issue positions require numeric latitude/longitude; explicit null axes cannot become default zero coordinates. An entirely null position remains unknown, while actual numeric zero coordinates are valid.

| Group | Fields / semantics |
| --- | --- |
| Identity and state | photoId, relative photoFile, captureState: SAVED / RECOVERED / INCOMPLETE / DELETED; photoIntegrityStatus: VERIFIED / HASH_MISMATCH / UNREADABLE / MISSING |
| Capture clocks | captureRequestedAtUtcMillis / captureCompletedAtUtcMillis: nullable Long; captureRequestedElapsedRealtimeNanos / captureCompletedElapsedRealtimeNanos: nullable Long, same device and boot only; captureTimeSource, captureWallClockStatus, captureElapsedClockStatus, captureDurationMillis |
| Photo evidence | nullable width/height, photoSha256, photoSha256Source: CAPTURE_SAVE or RECOVERY_VERIFICATION_CURRENT_BYTES; fingerprints attest only to compared bytes |
| Startup cache | optional photoVerification: byteCount, lastModifiedUtcMillis, sha256, width, height, bound to the validated photo; does not establish capture time |
| Human content | nullable issueCategory: ROAD_DAMAGE / DAMAGED_FACILITY / LITTER / OTHER; issueCategorySource, issueCategoryNeedsReview, description, address; recognitionStatus remains NOT_RUN |
| Locations | phoneLocation: nullable typed observation with WGS84, accuracy, UTC/monotonic fix time, ageAtReceiptMillis, provider, isMock; issueLocation: nullable independently confirmed latitude/longitude/coordinateSystem |
| Workflow | locationStatus, phoneConnectionStatus, syncStatus; privacyReviewStatus defaults NOT_REVIEWED; recovery dates/reason are diagnostic fields |

Unknown top-level and nested fields are preserved when a trusted record is repaired. A legacy issueLocation without a coordinateSystem remains unknown. Capture, fix receipt and eventual result completion have separate times; ageAtReceiptMillis refers to receipt of that fix, not completion of a later wait. No capture timestamp is sensor exposure time. Cross-device uptime comparisons are invalid, and wall-clock consistency does not validate the absolute calendar date.

## Recovery and concurrency

Startup compares verified file size and mtime before pixel decoding or hashing. Old records without a cache receive one validation pass. Size/mtime are an optimization: replacing bytes while preserving both can evade the fast check. Call recover(forceFull = true) before a future export and independently validate selected output images. It preserves original fingerprints and reports mismatches; it cannot make a photo cryptographically authentic.

Each root has shared process state and a separate lock per capture ID. Recovery takes a directory snapshot, then works per record; new UUID captures can proceed using another executor. An active capture is skipped by recovery. A root lifecycle lock excludes deletion from file operations. savedPhotoCount reads an in-memory concurrent set with no disk access or I/O lock; the count fills progressively during recovery. Successful metadata commit adds the saved count. Capture leases survive Activity recreation through application-owned executors.

Before beginning a journal, the repository checks at least 64 MiB of available space. This is a reserve policy rather than a guarantee: storage can fill after the check, so save failures still retain their bytes and journal for review. JPEG quality 93 does not guarantee a particular file size; channel limits apply to separately processed export copies.

Undo targets the photo ID shown in the review dialog. A DELETED record is committed before removal; startup replays incomplete deletion instead of resurrecting the image. The adapter removes that capture's photo, journal, record and backups. Whole-project deletion remains a separate confirmed operation on a worker thread.

## Phone probe

LocationProbeRecord retains the previous schemaVersion/probeId/status/requested/received/permission/source/location fields and adds nullable quality. SUCCESS quality is TARGET_ACCURACY_REACHED (at most 50 m with precise permission), APPROXIMATE_PERMISSION, BEST_AVAILABLE_AT_DEADLINE (recent valid lower precision), or BEST_AVAILABLE_PROVIDER_UNAVAILABLE (a still-fresh fix retained when the system provider fails). Quality is an estimate, not confirmed issue location. FAILED and CANCELLED have null location. A provisional callback is shown while waiting and is not saved as a final successful fix until completion rules accept it.

The total deadline is 60 seconds on the same device's monotonic clock. Fused gets at most 8 seconds; its failure, low precision fix or deadline switches to Android providers. Cancellation, request identity and source ownership reject late callbacks. At the deadline or a system-provider failure, only a valid provisional fix no older than 5 seconds at completion can become a labeled best-available result; older observations remain excluded. Its ageAtReceiptMillis still records the original callback age.

## Offline capture package

FIELDLOG_CAPTURE_PACKAGE version 1 is a private transport-neutral ZIP, separate from government materials. Allowed entries are exactly fieldlog-package.json and paired records/<lowercase-uuid>.json / photos/<same-uuid>.jpg, with no directory entries, extra files, duplicate names or traversal paths. The manifest contains format, version, recordCount and boolean isSynthetic. Record JSON uses CaptureRecordCodec; saved/recovered verified JPEGs must match identity, relative path, dimensions and any recorded fingerprint. Limits: 100 records, 16 MiB per photo, 256 KiB per JSON, 256 MiB total uncompressed bytes. Android also enforces a 24-million-pixel input limit.

OfflineLibrary stages and validates a complete import before one collection-folder rename. An interrupted staging folder is not visible as a collection. Same-ID/source/photo imports skip existing records and preserve edits; content conflicts fail the batch. A corrupted or duplicate on-disk identity is not overwritten during import. A separately persisted import-time SHA-256 binds the immutable original, including legacy sources without a recorded fingerprint; changed bytes cannot establish a new baseline at first review. Android AtomicFile owns source/draft commits; all adapters use one process-owned background queue.

## Editable issue and privacy contract

IssueDraft schemaVersion 1 is distinct from immutable source records. It stores photoId, optimistic revision, explicitly confirmed category/address/date, description, optional issuePosition with mandatory CRS, PhotoReview, workflow stage, user-entered receipt, and immutable isSynthetic. Changing report content returns workflow stage to DRAFT, preserving receipt history. Exporting does not imply submission. Workflow stages are DRAFT / EXPORTED / SUBMITTED / RESOLVED.

PhotoReview records a private review filename, original/output SHA-256, byte count, upright dimensions, normalized MaskRect rectangles, explicit noThirdPartyInformation where no masks are needed, review time and rendererVersion. Masks are finite normalized upright-image coordinates, capped at 100; pixel coverage rounds outward. Actual output bytes are reviewed and checked again on export. A change in either file invalidates export. DraftCodec uses strict JSON and rejects unsupported schemas or invalid identity, revision and coordinates. Unknown source annotations are preserved; draft schemas are owned separately.

FIELDLOG_MANUAL_MATERIALS version 1 contains exactly report.txt, summary.json and photo-reviewed.jpg. Summary is an explicit whitelist: ID/revision/synthetic marker, confirmed category/address/date/description/issuePosition, derived JPEG name/fingerprint and manual-submission profile. It omits source records, original GPS, device annotations and receipts. Current profile GENERIC_MANUAL limits photo long edge to 2048 and bytes to 5,000,000; these are product limits, not researched official-channel limits. Output JPEGs have normalized orientation and no private metadata segments. See [offline workflow](offline-companion.md) for acceptance boundaries.

## Inspection history contract

TrackedIssue schemaVersion 1 has a stable issueId, siteLabel, category, immutable synthetic flag, revision, and append-only InspectionEvent list. Revision equals event count; events have unique IDs and consecutive zero-based sequence values. OBSERVATION binds a photo record ID/import-time SHA-256, manually confirmed observedOn date, visibility/finding, sameSiteConfirmed and note. REPAIR_CLAIM carries feedback without a photo verdict. VERIFICATION references the latest observation and stores the human decision, note and chronology confirmation. Recorded UTC is device-clock bookkeeping and never proves capture order or exposure time.

InspectionState is derived from the latest event. Clear photographs with no visible original problem await verification; blocked/wrong-view/unusable observations require recheck. REPAIRED needs a repair claim followed by a new clear NO_VISIBLE_PROBLEM observation and human chronology confirmation. Later observations replace the current state without erasing historical verdicts. A new visible problem becomes OPEN. Duplicate photo IDs across issues and repeated SHA-256 within one issue cannot masquerade as new visits.

InspectionRepository serializes commands on the same background queue as OfflineLibrary, validates metadata before AtomicFile commits, and verifies selected image fingerprints on association/comparison/verdict. The beforeDelete hook protects referenced photos. Lists read metadata without image decoding/hashing. Histories are bounded to 1000 events / 1 MiB; unsupported versions, unknown history keys and damaged metadata remain untouched and visible as an unreadable count. This strict history contract differs from source CaptureRecordCodec, which preserves unknown source annotations. Full inspection-history import/export is not implemented. See [inspection workflow](inspection-workflow.md).


## 多图报修材料

ReportDraft（schemaVersion 1）独立描述一个问题的一次现场材料，包含 reportId/revision、recipient、接收方自有 category 文本、address/landmark、eventDate/description、位置与同次观察的人工确认、ReportPhoto 列表及锁定的 isSynthetic 标记。ReportPhoto 只引用本机 photoId 与人工视角角色 CONTEXT/DETAIL/ADDITIONAL，不改写原始采集记录或复查历史。

草稿允许未填写完成；导出需要 2–5 张不同原图、环境/细节角色、有效日期、明确位置/接收方及逐张隐私审阅。已人工登记的照片日期如与材料日期不同，需要核对。未知字段/版本拒绝改写并保留原文件；单份 JSON 限 64 KiB。接收方分类不自动由四个采集标签推断。

ReportRepository 以 AtomicTextFiles 写入 offline/reports/<reportId>.json，扫描时同时考虑原子备份和临时伴随文件。照片引用阻止单张删除；删除草稿保留照片。导出前后校验参考的是草稿和照片各自的修订及指纹，不提供拍摄真实性证明。

对外 FIELDLOG_MULTI_PHOTO_MATERIALS v1 仅包含 report.txt、summary.json 和 photo-01.jpg 等已审阅副本；JSON 白名单记录共用报修信息、照片角色及副本指纹，不带原始位置、设备字段、回执或维修历史。此格式供项目归档及手动填写外部渠道，未经当地验证不称为政务导入协议。详见 [多图材料流程](multi-photo-materials.md)。
