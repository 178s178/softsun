# Review package

Project: D:/zuoye/qingri. New standalone directory, no Git repository or preexisting code.

Requirements: Chinese native Android schedule manager for user's Xiaomi Android phone; APK plus complete reproducible source. Today/month calendar, search, CRUD, per-occurrence completion, repeats, system reminders, offline SQLite and transactional JSON backup. User added manually controlled study mode, timer and natural UI. Study uses monotonic elapsed time, can leave page while running, pause/resume/end at will, records completed session stats, has animated forest Canvas scene. No network or account.

Read: docs/superpowers/specs/2026-11-02-qingri-design.md; docs/superpowers/plans/2026-11-02-qingri.md; docs/progress.md.

Implementation: app/src/main/java/cn/qingri/app/*.java; manifest/resources; build.ps1; scripts/*.ps1 and *.py; tests/CoreTests.java and tests/android/SmokeInstrumentation.java.

Evidence: core tests 36 assertions passed. Android 15/API35 emulator installation succeeded; storage 15 assertions passed; UI/system alarm 31 assertions passed, including create/edit/complete/undo/search/calendar/settings, study pause/resume/end and stats, actual AlarmManager delivery, notification action and edited alarm cancellation. Denied-permission test and final screenshots pending. APK verified with apksigner v2/v3. Keep review read-only, do not control or modify the emulator or running app.

Review Focus:
- 短月、闰年与工作日重复不得漂移。
- 跨日、逾期、单次完成应有一致的列表与提醒行为。
- 编辑和删除取消旧提醒；重启与时区变化恢复新提醒。
- 通知与精确闹钟权限关闭时不崩溃并显示实际状态。
- 导入损坏备份不得清空原数据；只在完成校验后事务替换。
- Study mode must not silently lose or double-count sessions, and must respect manual start/end.

Implementation rulings: Java native Views rather than Kotlin/Compose (no user-mandated language); local official command-line tools rather than global Android Studio installation; no Git worktree because no Git repository. Read Ruling lines in progress.md. Non-Git project: review current files directly, no SHAs or diff available.
