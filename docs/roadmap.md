# Roadmap and acceptance gates

Current status is maintained only in the README status table. Changes are listed in CHANGELOG; earlier dated reports are historical evidence.

1. Phase 1: validate the updated glasses gestures, review/undo, JPEG quality, space policy, early capture during recovery and idle-camera behavior on hardware; test a real phone with the location matrix in field-validation.md. Source code and passing tests alone do not close this phase.
2. Before Phase 2: select a target city and confirm one complete official submission form using submission-channels.md. Determine required text/address/category/date/photo constraints before committing to an export format. Verify actual Rokid CXR-L/CXR-S SDK/device compatibility against official distributions.
3. Phase 2: implement explicit glasses-to-phone transfer, typed versioned records, retries/acknowledgments and clock-offset uncertainty; measure CXR throughput using real JPEGs. Preserve raw WGS84 observations and differentiate issue location. Phone parsing uses fieldlog-core's codec.
4. Phase 3: add phone record browsing/editing, a map with the required CRS conversion and map-provider information, manual issue-point correction, per-photo privacy review/redaction and channel-specific material export. coordinates-and-privacy.md defines the conversion/export boundaries and privacy gates. Export requires full photo validation and a verified channel profile. Users submit manually through the official channel.
5. Release: complete end-to-end acceptance and install/cleanup documentation; record battery/temperature and known limits. Automatic issue recognition requires the separate model evaluation in recognition-readiness.md. Accounts, cloud photo storage and automatic municipal submission remain outside V1.
