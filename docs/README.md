# Dotnote engineering documentation

Start here when joining the project or changing the app. These documents describe the implementation, not a proposed design.

**Current release:** app 0.12.1, version code 23. `app/build.gradle.kts` is authoritative for current app metadata; [CHANGELOG.md](../CHANGELOG.md) records changes and [VALIDATION.md](../VALIDATION.md) records what was tested. Historical entries keep their original versions. Follow [versioning and source control](build-test-release.md#versioning-and-source-control) when shipping any change.

## What the app is

Dotnote is an offline Kotlin Android tablet notebook: an unbounded dot-grid scene, pressure pen, highlighter, shapes, typed text, reusable templates, selection editing, imported PDF pages, nested folders, and multiple vaults. Jetpack Compose provides the interface; a custom Android View and AndroidX Ink handle drawing. It does not use Excalidraw or a web view.

Notes are portable `.dotnote` JSON files. A vault is a directory containing notes, folder metadata, writing settings, and original PDFs. Room indexes that directory for the app. Optional GitHub backups upload snapshots and explicitly restore another local vault. They do not synchronize two devices continuously.

There is no server, subscription, analytics, handwriting recognition, or collaboration layer. Local editing requires neither internet nor an account.

## Read in this order

| Guide | What it explains |
| --- | --- |
| [Architecture and source map](architecture.md) | Every production Kotlin file, startup, state ownership, queues, locks, lifecycle |
| [Data model and file format](data-model-and-format.md) | IDs, JSON fields and examples, coordinates, preferences, format validation |
| [Storage, transfer, and recovery](storage-and-recovery.md) | File authority, Room rebuild, journal, migration, imports, exports, recovery |
| [Drawing and input](drawing-and-input.md) | Stylus arbitration, live-to-saved ink, highlighter composition, selection, history |
| [PDFs and export](pdf-and-export.md) | Attachment import, render caches, page placement, PDF export |
| [Interface and user flows](ui-and-user-flows.md) | Library, editor, dialogs, settings, persistence expectations |
| [Default notes app](default-notes-app.md) | Android Notes role, Lenovo selection, fresh quick notes, lock-screen privacy and lifecycle |
| [Vaults and GitHub backups](vaults-and-github-backups.md) | Authentication, configuration, scheduling, Git protocol, conflicts and restore |
| [Widgets and ink startup](widgets-and-ink-startup.md) | RemoteViews, size calculations, launch intents, recency, vault rename, latency work |
| [App updates and free distribution](app-updates.md) | In-app updater, signing compatibility, GitHub Actions setup and publishing |
| [Build, test, and release](build-test-release.md) | Toolchain, commands, exact test inventory, release checks and emulator caveat |
| [Maintenance and known issues](maintenance-and-known-issues.md) | Change checklist, task-to-file routing, diagnostics, unresolved issues |

For a storage change, read the first three guides and the GitHub guide before editing. For a drawing change, read the model, drawing, and PDF guides together: the screen and exported PDF share a renderer. For widgets, read architecture and the widget guide before changing activity navigation.

## Non-negotiable implementation boundaries

1. **Vault files are authoritative.** Updating Room alone loses changes when the vault reopens. Use `Store.dao` / `FileLibraryDao`, not the underlying database DAO, for normal writes.
2. **Save requests capture a vault, note, and immutable document.** Never resolve their destination from whatever vault happens to be active when the request finishes.
3. **A completed gesture is committed at pointer-up.** Native display handoff must not be the only path that saves a stroke.
4. **Predicted input is visual only.** Persist real input samples, including pressure and historical samples.
5. **A vault snapshot must hold the same root mutex as edits.** Upload the staged copy after releasing that lock.
6. **Remote changes stop uploads.** Never add force-push or automatic replacement as a quick conflict fix.
7. **Import/restore keeps the original.** Vault import and Git restore publish a new local vault; legacy ZIP restore merges with new IDs.
8. **Opening a note updates device-local recency, not its backup revision.** Actual camera/settings/content changes can still count as edits.

## Current verification status

The [release validation record](../VALIDATION.md) records current build, lint and test results. Version 0.6.0 adds default notes-app registration and lock-screen quick notes; current focused verification is recorded in VALIDATION.md. Earlier drawing/UI suites are documented separately, including the emulator graphics caveat.

GitHub sign-in now waits for Dotnote to return to the foreground and retries temporary hostname-resolution failures. Lenovo confirmation and live private-repository backup remain pending in [TODO.md](../TODO.md). Physical stylus latency, OEM palm/button behavior, and Android 17 operation also need device acceptance.

## Where things live

- App source: [`app/src/main/java/dev/dotnote/app/`](../app/src/main/java/dev/dotnote/app/).
- App identity and Android components: [manifest](../app/src/main/AndroidManifest.xml).
- Tests: [JVM](../app/src/test/java/dev/dotnote/app/) and [Android instrumentation](../app/src/androidTest/java/dev/dotnote/app/).
- Build configuration: [root Gradle](../build.gradle.kts), [app Gradle](../app/build.gradle.kts), [settings](../settings.gradle.kts).
- Release evidence: [VALIDATION.md](../VALIDATION.md). Open work: [TODO.md](../TODO.md).
- Local release artifacts: `dist/`; APK/ZIP files are ignored by Git. The source repository is separate from any repository holding a user's vault.
