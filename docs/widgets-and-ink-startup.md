# Widgets, vault names, and first-stroke startup

[Documentation index](README.md) · Feature baseline: 0.4.0

## Home-screen widgets

Three native `AppWidgetProvider`s build Android `RemoteViews`. There is no Glance dependency, collection service, network feed or network polling.

| Provider | Definition | Behavior |
| --- | --- | --- |
| `QuickNoteWidget` | [widget_quick_info.xml](../app/src/main/res/xml/widget_quick_info.xml), [widget_quick_note.xml](../app/src/main/res/layout/widget_quick_note.xml) | New note tile; 64×64 dp minimum, target 1×1 home-screen cells |
| `RecentNotesWidget` | [widget_recent_info.xml](../app/src/main/res/xml/widget_recent_info.xml), [widget_recent_notes.xml](../app/src/main/res/layout/widget_recent_notes.xml), [widget_note_row.xml](../app/src/main/res/layout/widget_note_row.xml) | New note header plus as many recent entries as fit; 250×250 dp initial minimum, target 3×3 cells, resizable down to 180×80 dp |
| `CalendarWidget` | [widget_calendar_info.xml](../app/src/main/res/xml/widget_calendar_info.xml), [widget_calendar.xml](../app/src/main/res/layout/widget_calendar.xml) | Current and next month, today highlighted; tap to open Dotnote; target 4×2 cells |

The two note widgets are resizable horizontally/vertically, categorized for the home screen, and set `updatePeriodMillis=0`. Their nonexported receivers and metadata are registered in the manifest. Strings and visuals live in [widget_strings.xml](../app/src/main/res/values/widget_strings.xml), [widget_background.xml](../app/src/main/res/drawable/widget_background.xml) and [widget_button.xml](../app/src/main/res/drawable/widget_button.xml).

To add one, the user opens the launcher's widget chooser and selects Dotnote → New note, Recent notes, or Calendar. There is no app-side pin/configuration activity. The launcher controls exact cell sizes and resizing affordances.

### Two-month calendar (0.7.0)

[`CalendarWidget`](../app/src/main/java/dev/dotnote/app/CalendarWidget.kt) uses a separate local-date renderer with no vault/database reads. It displays the whole current month and next month with Monday-first weeks, blue Saturdays, pink Sundays, and a dark rounded highlight on today. Both grids share the larger month’s week count, so six-week months retain every date. Month names and accessible date descriptions use the device locale; date boundaries use the device time zone. The translucent pale background is intentionally independent of the app’s green note-widget styling.

[Provider metadata](../app/src/main/res/xml/widget_calendar_info.xml) targets 4×2 launcher cells, initially 250×110 dp and resizable down to that size. Layouts shorter than 180 dp use compact headers, less padding and date text sized to the available week-row height, including six-week months. Exact cell dimensions depend on the launcher. API 31+ sizes use exact responsive `RemoteViews` variants; older hosts use portrait/landscape alternatives. The launcher preview is a static November/December example matching the visual reference; installed widgets render the actual date.

Tapping the root launches `MainActivity` with MAIN/LAUNCHER and the existing task flags. It preserves the current app screen and does not request note creation. The calendar also refreshes when the app starts, on launcher update/resize, local time/time-zone/language changes, reboot and package replacement, and note-widget refreshes.

A non-waking inexact alarm requests an update in the first ten minutes after the next local midnight; the provider’s hourly update is a fallback. The alarm is rescheduled using the next calendar day rather than adding 24 hours, preserving daylight-saving transitions. Removing the last calendar cancels it. Android battery saving may defer updates, so midnight refresh is not guaranteed while asleep ([Android alarm documentation](https://developer.android.com/develop/background-work/services/alarms)). No calendar-event access or exact-alarm permission is needed.

`CalendarRulesTest` covers month alignment, leap years, six-week months, year rollover and 23/25-hour days. `CalendarWidgetTest` inflates actual RemoteViews at 280×200, 360×220 and 500×260 dp, checks today and complete dates, validates the static preview and year rollover, and exercises the root’s PendingIntent without creating a note.

### Capacity and adaptive layouts

[`widgetSpace(width,height)`](../app/src/main/java/dev/dotnote/app/NoteWidgets.kt) operates in dp:

```text
columns = 2 if width >= 440, otherwise 1
rows = clamp(integer((height - 104) / 56), 0, 16)
capacity = columns * rows
```

The 104 dp budget consists of 16 dp top/bottom padding, a 44 dp header and a 28 dp section label. Each note row is 56 dp. Maximum visible entries are 32. This is a fixed visible list, not a scrollable collection. If no row fits, the new-note action remains; if rows fit but history is empty, the empty-state label appears. Entries fill row-major order, with the second column hidden when unused.

Examples: 250×280 dp yields one column × three rows = three notes; 500×280 gives six; height 80 yields no rows. Text uses single-line title/folder labels, so long names may truncate rather than expand capacity.

On API 31+, provided `OPTION_APPWIDGET_SIZES` values are deduplicated, capped at 16 and mapped to exact `RemoteViews` sizes. Without those values, including API 29–30, the code creates landscape/portrait alternatives from min/max width/height options. Fallback dimensions are 350×180 and 250×280 dp. Tests cover sizing math and actual inflation; an OEM launcher may report different sizes.

### Updates and threading

`NoteWidgets` uses a single-thread executor. Providers call `goAsync()`, enqueue `updateAll`, and finish their pending result in `finally`. `updateAll` reads current vault names and recent entries, gets IDs of both providers, and updates every installed instance with fresh layouts. Refresh is triggered by opening-history changes, relevant rename/move/delete operations, vault rename, provider updates, and launcher option changes.

Update failures are wrapped in `runCatching`; a widget failure does not block local note saving. There is no durable retry queue or error notification for widget rendering. A stale widget can refresh on the next relevant event. No WorkManager job is added for widget refresh.

## Recently opened data

[`RecentNotes`](../app/src/main/java/dev/dotnote/app/RecentNotes.kt) stores a JSON string under `recent-notes` → `items`:

```json
[
  {
    "vault": "local-vault-id",
    "note": "note-id",
    "title": "Lecture 8",
    "folder": "Course / Week 3"
  }
]
```

Array order is recency; there is no timestamp field. Opening prepends a unique `(localVaultId,noteId)` pair, removes its older occurrence and retains at most 100. IDs are validated on read; a malformed overall array falls back to empty. Preferences commit synchronously inside a class-level monitor, on the background caller, then request widget refresh.

AppState queues openings separately from saves. The FIFO consumer locks the recorded store, re-reads the latest note/folders and only records an existing note. This protects against an opening update arriving after a rename/delete. Optional recency errors are swallowed and do not mark document save failure.

- Note rename/move updates cached labels in place, keeping opening order.
- Folder rename/move recomputes full ancestor paths for that vault and drops missing notes.
- Note deletion removes the pair.
- Vault rename resolves its display name fresh during widget rendering; entries store local IDs, not vault names.
- Missing vaults are filtered from displayed history; their stored records are not proactively purged.
- Opening a stale missing-note entry removes it and reports an error.

`folderPath` traverses parents with cycle protection and joins names using ` / `; root is “Vault root.” Recency is local-only, excluded from portable files/backups, and does not increment `revision` or reset Git inactivity deadlines. Actual camera/settings changes while using a note can still count as edits.

## Click identity and activity routing

[`NoteWidgets.launchIntent`](../app/src/main/java/dev/dotnote/app/NoteWidgets.kt) explicitly targets `MainActivity`:

| Action | URI identity | Extras |
| --- | --- | --- |
| `dev.dotnote.app.NEW_NOTE` | `dotnote-widget://new` | None |
| `dev.dotnote.app.OPEN_NOTE` | `dotnote-widget://open/<localVaultId>/<noteId>` | `vault`, `note` |

The URI is PendingIntent identity, not a public browsable deep-link registration. Request code is 0, with `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`. Unique URI data prevents every row from accidentally opening the most recently constructed intent; extras alone do not distinguish PendingIntents.

Activity flags are NEW_TASK, CLEAR_TOP and SINGLE_TOP. The manifest's `singleTop` activity handles `onNewIntent`, updates the activity Intent and parses a new `WidgetAction`. On fresh creation it parses the launch intent only if `savedInstanceState == null`, avoiding replay on ordinary rotation. A Compose `LaunchedEffect` hands the request to AppState and clears it.

`WidgetAction.from` only recognizes the two actions and requires valid IDs for opening. AppState's action then checks the actual vault/note, flushes old work, switches if needed, and opens it. New note instead toggles the shared creation dialog, where the user explicitly chooses vault/folder. It does not create a note merely because the widget was tapped. Errors use the normal message/busy path.

Do not replace this with an unconditional new Activity/task, skip the flush boundary, or key PendingIntents only by a common request code. These details protect the user's currently open work and cross-vault navigation.

## Vault renaming

[`VaultCatalog.rename`](../app/src/main/java/dev/dotnote/app/VaultCatalog.kt) trims/caps a name to 120 characters, rejects blank, takes the root mutex, reads `.dotnote/vault.json` and atomically updates only `name`. Changed names call `edited()` and widget refresh. Identical names do neither. `AppState.renameVault` increments `vaultVersion` so Compose reloads the chooser/name display.

Renaming preserves local ID, portable ID, files, index name, repository binding, PendingIntent targets and recent order. The root directory remains the local ID; the new display name is visible through the chooser, documents provider and subsequent exports/backups. There is no global uniqueness requirement for display names.

## First-stroke startup work

Version 0.4.1 extends the earlier brush caching and `eagerInit()` setup:

- `InkWarmup.start()` runs once per process from activity creation. A background thread loads native Ink, builds a real pressure stroke, and draws into a tiny disposable bitmap. It creates no document data.
- `NotebookView` prepares both pen and marker brushes. `onAttachedToWindow` calls `InProgressStrokesView.eagerInit()`.
- At the first nonzero layout, an offscreen authoring stroke exercises the per-view native pipeline. It is removed by the normal completion listener and never committed, saved, or added to undo history.
- Real input is still accepted immediately; no warmup timer or blocking wait gates drawing.
- Pen/highlighter saves encode recorded inputs directly instead of generating a duplicate mesh at pen-up.
- Note decoding remains on IO in `AppState.openNow`.

`InkStartupTest.firstPenStrokeIsVisibleBeforePenUp` now checks a screenshot while the first stroke is **still active**, before `ACTION_UP`, with a 500 ms visibility budget. The 0.4.0 test checked after pen-up and could pass even if live ink was delayed. These tests measure synthetic emulator input and screenshot observation, not physical S Pen latency or the entire note-open-to-ready interval. See the current [validation record](../VALIDATION.md) for measurements.

The user still observed first-writing delay on a Galaxy Tab S6 Lite with 0.4.0. The new warmup is a mitigation pending physical-device verification, not proof that the device issue is resolved.

For the emulator-only native teardown crash, the test drains Ink rendering through `ink.sync(2, TimeUnit.SECONDS)` before destroying its view. That happens after measurement and is test cleanup, not a production startup workaround. See [build/test notes](build-test-release.md).

## Verification and acceptance

- `WidgetRulesTest`: capacity math and duplicate-name folder paths.
- `CalendarRulesTest` / `CalendarWidgetTest`: local date boundaries, complete month grids, resizing and app-only tap routing.
- `WidgetPipelineTest`: rename invariants; persistent recency and metadata changes; inflated responsive layouts and distinct click targets; UI-driven creation in another vault's nested folder and cross-vault reopening.
- `InkStartupTest`: synthetic first-stroke dispatch and screenshot visibility.
- `VaultLifecycleTest`: switching preserves separate note/settings state.

The release workflow also used the Android launcher to add the actual recent widget and open its new-note dialog. Physical Lenovo acceptance remains open: add/resize both widget types in both orientations, test stale entries and cold/warm app launches, and compare the first and subsequent strokes immediately after opening large and empty notes. No fix to the deferred GitHub DNS failure is part of these features.
