# SDD ledger — plan: docs/superpowers/plans/2026-11-02-qingri.md

User opted out of further design questions with “直接做”. Execute inline, without another approval round.

Workspace: D:/zuoye is not a Git repository. New isolated project directory D:/zuoye/qingri; no existing files modified. Git worktree and commit helpers do not apply.

Pre-flight: Task 2 consumes Task 1 event/occurrence model; Task 3 consumes the same model and EventStore. Use LocalDateTime strings and occurrence keys eventId@originalStart consistently. Task 4 compiles these interfaces together.

Ruling: Use Java native Views rather than Kotlin/Compose — user selected native Android, not a language; no third-party runtime dependencies makes reproducible APK construction smaller — cost is a less conventional modern UI stack.

Ruling: Use local Android command-line build tools rather than installing a global IDE — environment has no JDK or Android SDK — cost is a custom build script to maintain.

Task 1: complete. RED: blank title assertion failed against engine stubs. GREEN: scripts/test.ps1 => PASS: 24 core assertions.

Environment correction: Java was absent from PATH but an existing JDK 21 was found at C:/Program Files/Android/openjdk/jdk-21.0.8. Use it locally; stopped the unresponsive redundant Microsoft download. Android platform and build tools downloaded from Google and verified against repository SHA-1 metadata.

Task 2: complete. SQLite integration RED: insert stub failed to generate ID. GREEN: 10 Android storage assertions. Instrumentation uses target's device-protected test context, since an instrumentation process cannot write a separate package's database directory.

Task 3: UI compiled and launched on Android 15 emulator. Inspected real screenshot; corrected alignment of week labels, day numbers and navigation labels. Extended end-to-end tests in progress.

User addition 2026-11-03: Add manually controlled study mode and natural UI. Add Task 5 before final packaging. Keep the original APK objective and all accepted requirements.

Task 3 and 5: complete. UI+Alarm integration: 31 assertions passed on API35. Includes actual notification delivery and action, cancellation after edit, and study start/pause/resume/end with persisted duration. Core suite now 36 assertions. Backup upgraded to v2 to include completed study sessions, v1 still accepted; 15 storage assertions passed. Initial backup test failed with "No value for study" before the v2 implementation.

Integration test harness correction: startActivitySync waits for a newly created activity; MainActivity uses singleTop, so relaunching it while already on top hung the test harness. Test launch now uses CLEAR_TASK for fresh deterministic activities. No production behavior changed for this correction.

Final review: independent reviewer /root/final_review, read-only, completed. Findings: alarm quota, asymmetric backups, non-idempotent study completion, channel status, stale actions, fixed look-ahead horizon, late alarm cancellation, and wrong-typed delivered history. Regraded hidden query and duplicate overnight display as user-facing consistency bugs and included them in the same correction.

Final: fixed alarm quota, backup size/shape, study completion and stale actions — regression suite RED reproduced double-count, >2MiB restore rejection, wrong-typed history acceptance and actual Android maximum-500-alarm exception; GREEN 7/7 regression assertions.
Final: fixed monthly look-ahead — precompleted November/December test RED returned null, GREEN returns January.
Final: fixed late reminder cancellation and tied-time retention — preserved queue carries original trigger; tied 501 queue test RED truncated to128, GREEN retains501. System delivery tested for two concurrent actual notifications.
Final: fixed channel status — actual system channel toggled off while package permission stayed on; 3/3 channel assertions passed. Notification and exact alarm denial: 2/2.
Final: fixed search close behavior — UI test verifies unfiltered agenda after close. Core 41/41, storage15/15, UI+alarm34/34. No outstanding review findings.

Finishing: no Git repository or branch exists, so branch integration menus and worktree removal are not applicable; deliver APK, source ZIP and verification artifacts in place.

Manual restoration: PASS. Started study, left page, force-stopped emulator App, relaunched and reentered study; restored elapsed 00:00:07. Captured actual running learning page, then paused and ended the test session. Product code unchanged after final full suite.

## Update 1.1.0 — time records (2026-11-03)

New user request: 时间记录、按日/月扇形占比. Existing direct-execution authorization persists. Optional classification clarification sent; default is content categories (阅读/复习/做题/课程学习/其他/custom), only recorded time is denominator. New plan docs/superpowers/plans/2026-11-03-time-records.md. Preserve1.0 APK/signing key, migrate SQLite3→4, backup2→3. Execute inline; do not redo completed original tasks.

User answered clarification: each individual study session gets its own slice. This overrides the provisional category assumption before implementation. No category chooser; optional session name, editable record labels, manual补记, per-record pie and selectable highlight.

Update progress: TimeStats RED returned0 for actual session; GREEN14 allocation/share assertions, expanded DST/leap/rounding tests added. Record storage RED 'Time record is persisted' failed against stubs; schema4/TimeRecordStore implementation complete pending new APK runtime checks. Before-upgrade baseline captured on installed1.0: schema3, events3, sessions2, total18781ms; do not run destructive main-DB tests until after upgrade comparison.

2026-11-04 steering: user requested optimization. Delegated ONLY RecordsActivity/PieView/TimeRecordEditor to records_ui; ONLY RecordsUiInstrumentation to records_tests. Root owns persistence/lifecycle/Main/Focus/manifest/build/runtime. Targeted reviewer flagged stale mutators and finalized-token crash recovery; root added generation ownership to all mutations and completed-token reconciliation plus explicit legacy estimation metadata. Runtime tests pending. Preserve original task through further context changes.

Runtime update: upgrade comparison PASSED schema4, events3, migrated sessions2, duration18781; exact baseline preserved. Initial ADB startup attempted before the daemon rediscovered emulator; nondestructive record tests added new rows before comparison, so the after-check compares migrated estimated=1 history to the before snapshot. No target DB was cleared until this passed.

Record storage13 and records UI50 passed. Whole original storage15/regression7/UI34 passed with new feature. Final reviewer replacement (original model unavailable) reviewed all new source, no Critical/Important blockers. Minor asymmetry >20,000 fragments reproduced RED; removed artificial read-only limit and added regression. TimeStats full-contained interval fast path added; core41+time17 PASS. Final full run and preview packaging in progress.

Update1.1 complete: final suite core41+time17+storage15+regression7+originalUI34+records14+recordsUI50=178 assertions passed. Upgrade legacy subset3/2/18781 preserved. Actual day/month/highlight screenshots inspected. Independent final review no Critical/Important, minor read cap fixed RED→GREEN. Packaging signed1.1 and sources; no further product changes.

## Update1.2.0 — 3D sectors

User: 优化扇区，3D型. Implement projected nativeCanvas top/side/shadow and physically lifted selection with inverse-ellipse/sidewall hit mapping. No data migration. Plan docs/superpowers/plans/2026-11-04-pie-3d.md. Existing direct-execution preference remains; no further approval question. Root owns renderer/build/emulator, independent reviewer only reads.

Update1.2: independent review identified selected lower side occlusion and adjacent exposed cuts; fixed both with clipped side rendering and matching hit priorities. Kept chart stationary on selection (removed previous auto-scroll). Core41+time17 and recordsUI50 passed. Real projected touch checks9/9 passed: top25/75, occluded neighbor, front wall, empty corner, switching, lifted top, final selection and single100% sector. No data model changes. Final screenshots/package in progress.

1.2 packaged: dist/qingri-1.2.0.apk SHA25677b7287a2677ac8bd9c1704cb5aad9e214550ca0753e579561959afbacedda8a and dist/qingri-1.2.0-source.zip. Auto approval service then failed (usage limit) for further emulator-navigation screenshot, action NOT executed; do not bypass. Existing genuine3D screenshots and passed tests retained. Local packaging/signature verification unaffected.

## Update1.3 — DeepSeek schedule Agent (2026-11-05)

User added schedule generation/optimization Agent. Explicitly chose online API, then said “先不写密钥，用deepseek”. This supersedes provisional local+optional model assumption: real DeepSeek tool loop, model/default endpoint prefilled from official docs, key EMPTY. Do not copy Codex login or any existing project secrets. User supplies key later inside App.

Contract sources AgentPlanner.java and AgentService.java created as stubs. Plan docs/superpowers/plans/2026-11-05-schedule-agent.md. Root owns API/settings/service/database/integration; delegate planner core/tests and AgentActivity only to separate scoped workers. No Git repository. Keep1.2 APK and3D features. Live API cannot be claimed tested while key intentionally blank.
