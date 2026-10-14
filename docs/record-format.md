# Shared record contract

The source of truth is fieldlog-core's CaptureRecord, LocationObservation, GeoPoint and LocationProbeRecord, serialized by CaptureRecordCodec. Android owns directory layout and AtomicFile persistence; CaptureFiles owns only photo/journal transactions and accepts record IDs/collision checks from the caller. Phase 2's phone receiver must use this codec rather than recreate JSON keys.

## Version 1 compatibility

Each capture is UTF-8 JSON with schemaVersion 1 and a lowercase UUID photoId. Unknown positions and times are explicitly null. Fields added in this update are optional on read. Defaults apply through explicit Gson InstanceCreator factories, with Unsafe disabled and stable SerializedName annotations. A future schema version or invalid typed record is retained and reported for review. Truncated JSON can be backed up and reconstructed from the journal and image; a foreign photoId is never adopted.

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

LocationProbeRecord retains the previous schemaVersion/probeId/status/requested/received/permission/source/location fields and adds nullable quality. SUCCESS quality is TARGET_ACCURACY_REACHED (at most 50 m with precise permission), APPROXIMATE_PERMISSION, or BEST_AVAILABLE_AT_DEADLINE (recent valid lower precision). Quality is an estimate, not confirmed issue location. FAILED and CANCELLED have null location. A provisional callback is shown while waiting and is not saved as a final successful fix.

The total deadline is 60 seconds on the same device's monotonic clock. Fused gets at most 8 seconds; its failure, low precision fix or deadline switches to Android providers. Cancellation, request identity and source ownership reject late callbacks. At the deadline, only a valid provisional fix no older than 5 seconds can become a labeled best-available result; older observations remain excluded.
