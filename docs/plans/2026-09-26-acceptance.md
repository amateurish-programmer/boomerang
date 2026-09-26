# Remaining acceptance stages

Goal: continue all currently feasible acceptance work automatically, preserve user data, and distinguish completed behavior from external dependencies.

Spec: docs/ARCHITECTURE.md, docs/DEVELOPMENT_TASKS.md and docs/DEVICE_ACCEPTANCE.md. Existing0.4.1 OTA is complete; do not rebuild or repeat completed acceptance without a new reason.

1. Local device: synthetic record, editing/history, capsule, Android file export/import cancellation and duplicate preview, share preview without sending to contacts; update stale acceptance tracking.
2. Reminders: controlled synthetic due record, permission state, inbox and safe notification navigation; observe real foreground/background behavior without changing system date or global battery policy. Whole-phone reboot and long-term timing require an idle-device window and actual elapsed evidence.
3. Account/sync: refresh own-project cloud deployment/auth configuration; exercise dedicated synthetic account data and conflict behavior where safely possible. Never log out a user's active session or capture real private data. Two isolated clients do not count as two physical phones; email verification needs mailbox-owner action.
4. AI/automation: verify deployed contract and credential-name status, keep earlier 百炼 deferral in force; do not enable production Cron without real provider acceptance. Continue all independent validations and record specific dependencies.
5. Evidence and delivery: stage reports, PROGRESS and authoritative checklist, corresponding tests/diff checks, signed explicit-file commits and authorized cloud builds. Automatically advance between feasible stages; no false V1 completion.

Execution rulings: user now explicitly revokes prior stop-after-stage limit and authorizes automatic progression. Existing AGENTS already authorizes stage commits and project deployment. Reuse current project checkout, preserve unrelated puppet-dance.html, root serializes Git/Gradle/device operations. Skills' routine integration approval gates are satisfied by existing project authority. Do not uninstall or clear the populated release app, send external test messages, change other projects, expose credentials, or substitute fixtures for provider/device success.

Root owns phone operations, progress, reports and Git. Delegated cloud audit initially read-only; delegated acceptance audit examines source/report gaps and proposes concrete non-destructive cases. No new business behavior is planned unless an actual defect is found.
