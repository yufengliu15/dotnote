# Default notes app · 0.6.0

Dotnote registers as an Android note-taking app through its manifest and a dedicated editor activity. Changing the app description or store category is not what makes it appear in the system selector.

## Select Dotnote

Install `dist/dotnote-0.6.0.apk` over the existing app. Open the library or regular editor menu → **Set as default notes app**. In Android's default-app settings, open the notes category and choose Dotnote. The menu displays **Default notes app: Dotnote** after the system reports that Dotnote holds the role.

The Notes role was introduced in Android 14, but its availability depends on the device. Android's [official Cahier announcement](https://developer.android.com/blog/posts/introducing-cahier-a-new-android-git-hub-sample-for-large-screen-productivity-and-creativity) identifies compatible Lenovo tablets running Android 15 or later. This does not establish support for every Lenovo model, firmware, or pen shortcut. If the role is unavailable, Dotnote displays an explanation and remains usable through the launcher and widgets.

[`DefaultNotes.kt`](../app/src/main/java/dev/dotnote/app/DefaultNotes.kt) checks `RoleManager.isRoleAvailable(ROLE_NOTES)` before offering settings. It uses the public `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` screen. The [AOSP Notes role definition](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android16-qpr2-release/PermissionController/res/xml/roles.xml) marks this role as non-requestable, so Dotnote does not rely on a role-request consent dialog. Settings category names and navigation can differ by manufacturer.

## Registration and quick notes

[`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) declares an exported `CreateNoteActivity` with `android.intent.action.CREATE_NOTE` and `android.intent.category.DEFAULT`. The activity enables `showWhenLocked`, `turnScreenOn`, resizing, and `android.window.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI`. Separate document tasks and an empty task affinity keep it separate from the ordinary launcher activity; quick-note tasks are excluded from Recents.

Each system launch creates a blank drawing note with a timestamp title at the root of the currently selected vault. Creation bypasses the normal vault/folder dialog. The screen retains drawing tools, undo/redo, selection, renaming, and writing settings, with a scrollable bottom toolbar for narrow windows. It omits library navigation, historical notes, vault names, account controls, import/export, and the regular editor overflow menu.

[`CreateNoteActivity.kt`](../app/src/main/java/dev/dotnote/app/CreateNoteActivity.kt) owns a separate `AppState` and never renders the library. External note/vault extras are ignored. If Android reuses the activity for another `CREATE_NOTE`, it immediately hides the previous scene, settles the canvas, saves the previous note, and opens a fresh scene. A save failure keeps the previous note in memory and shows retry feedback instead of replacing it. Multiple independent launches use distinct notes.

Rotation retains the current quick note and history through its ViewModel. After process death, an unlocked recreation can reopen the quick note identified by the activity's saved state; a locked recreation creates a fresh note. Closing flushes the current note before finishing. The shared editor also saves on stop. Quick notes use the normal file-backed storage, local recency, and backup scheduling, and can be opened later from the unlocked library.

The ordinary `MainActivity` is not permitted over the lock screen. The quick-note activity does not dismiss the keyguard. This implements the fresh-note privacy approach in Android's [note-taking app guidance](https://developer.android.com/develop/ui/compose/touch-input/stylus-input/create-a-note-taking-app). The feature creates handwriting/drawing notes; it does not add a typed-text tool or screenshot annotation integration.

## Verification and device acceptance

[`NotesRoleTest.kt`](../app/src/androidTest/java/dev/dotnote/app/NotesRoleTest.kt) covers implicit intent resolution, exported/role qualification flags, unavailable-role guarding, ignored historical-note extras, rotation and saving, reused activity handling, and independent launches. Emulator verification also launched the activity above a secure keyguard and inspected the visible screen. See [VALIDATION.md](../VALIDATION.md) for exact results.

The Android 16 emulator does not expose the Notes role, so actual role assignment and Lenovo pen/floating-window behavior remain device checks:

1. Select Dotnote in Lenovo's default notes-app settings and confirm the app menu reflects the selection.
2. Invoke the configured system note or pen shortcut while unlocked, then while securely locked. Each new request should show a blank quick note without historical content.
3. Write, resize or rotate the window, and close it. Unlock and confirm the note is saved in the library.
4. Invoke the shortcut again while a quick-note window exists. Confirm a new blank note opens and the previous drawing remains saved.
