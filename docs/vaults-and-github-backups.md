# Vaults and GitHub backups · 0.3.0

Implemented in Kotlin, with native Android file storage, Room as an index, and WorkManager for deferred uploads. No backend server is required.

## Vault layout

```text
.dotnote/
  vault.json
  settings.json
Physics [folder-uuid]/
  .folder.json
  Lecture 8 [note-uuid].dotnote
attachments/
  <asset-id>.pdf
```

All JSON is UTF-8. Vault manifest: `format: "dotnote-vault"`, `version: 1`, stable `id`, display `name`. Each folder marker has stable `id` and display `name`. Each note has `format: "dotnote"`, `version: 1`, `id`, `title`, millisecond `modified`, and a `document` object containing existing version-1 document data. The native Ink payload retains original stylus/brush data; shapes, transforms, highlighters, camera and relative PDF references round-trip unchanged. Filenames include full IDs so duplicate titles cannot overwrite each other.

Portable settings contain six palette slots, active color, stroke width, grid rows/columns, finger drawing and toolbar dock. Repository connection details, account credentials, edit revision, last backed-up commit and WorkManager state are device-local and never included in the vault.

The root is app-private `files/vaults/<local-id>/`; the local ID is distinct from the portable manifest ID. Restoring a vault creates a new local ID and preserves its portable IDs. Multiple copies can coexist safely.

Android Files exposes **Dotnote vaults** as a read-only document provider for copying. Vault import/export uses the system folder picker. Import copies files into a new local vault; export creates a new timestamped snapshot folder. Neither establishes a live link to an arbitrary external/cloud folder. Copy all metadata, notes and attachments together. An export interrupted before success may leave an incomplete destination folder; the source vault remains intact.

## Saving and migration

File writes precede index updates. Android AtomicFile handles individual JSON replacement; `.dotnote/transaction.json` records multi-file writes/deletes so interrupted moves can finish before indexing. Room can be rebuilt from files. Deleted notes are retained under `.dotnote/trash/`; journal, trash and migration bookkeeping are excluded from snapshots. Retained attachments support recovery.

On the first upgrade, the original Room library and referenced PDFs are copied into the initial vault, validated and indexed. The old database and attachments remain untouched. Existing writing preferences are written into that vault. Subsequent launches rebuild the selected vault's index from files.

Restore stages and validates the complete vault before publishing it. It rejects unsupported versions, duplicate IDs, invalid folder trees, missing attachments, unsafe paths, symlinks and oversized input. Git downloads are verified against Git blob SHA-1 identifiers. Limits: 10,000 vault entries, fewer than 64 directory levels, 64 MiB/note JSON, 256 MiB combined note JSON, 1 GiB restore total, plus filesystem/path limits. Git files must be smaller than 100 MiB; local imported PDFs may be up to 512 MiB.

## GitHub connection

The registered OAuth app's public Client ID is included in `GitHub.kt`. GitHub's device authorization is enabled. The callback field may contain `http://127.0.0.1/callback`; device flow does not use it. Dotnote displays a short code; the user copies it and opens GitHub authorization. The app polls at GitHub's required interval, handles slowdown/expiration/denial, and uses no client secret. OAuth requests the `repo` scope, which allows private repository access; this is broader than access to just one vault repository.

Advanced setup supports a different public Client ID or a fine-grained personal access token restricted to selected repositories. Contents read/write is required to back up; read access suffices for restore. Read-only restores do not enable automatic uploads. The UI lists accessible repositories and also accepts `owner/repository` or a github.com URL. Repository visibility is shown and never changed by Dotnote.

Tokens are encrypted with AES-GCM and Android Keystore. Vault exports, source artifacts and Git commits contain no tokens. A device signs in independently after restore. If a token expires or access is revoked, backups report failure and the user reconnects; refresh is attempted only when the authorization response supplies a refresh token. No embedded secret is used.

Reference: [GitHub OAuth device flow](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps).

## Backup scheduling

One unique one-time WorkManager job per vault uses `REPLACE` after each edit. The delay accepts every whole minute from **15 through 360**, measured from the latest persisted edit. Edits restart the countdown. A worker checks that persisted deadline again when it runs. It requires connectivity, optionally an unmetered network, with bounded exponential retries for transient failures. Reboot/process restarts are handled by WorkManager. Opening the app reconciles pending vault jobs.

**Back up now** skips the time delay, while respecting the network constraint. Automatic backups can be disabled independently per vault. The UI shows local save state separately from backup status and the last successful backup time. Android Doze, battery restrictions and network availability can delay execution; this is not an exact alarm.

Reference: [Android persistent work scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## Coherent commits and conflict protection

A vault mutex creates a consistent immutable snapshot of notes, folders, preferences and referenced PDFs. Git blob hashes identify changes. Only changed files are uploaded; removed managed files get deletion entries. Unchanged snapshots do not create commits. A changed snapshot publishes through one new tree and commit. A completely empty repository first needs a README initialization commit.

Before upload, the branch head must equal the last downloaded/uploaded commit. It is checked again before updating the ref, and the update never forces a branch. A newer remote head stops the backup, preserving both local edits and remote changes. A pending commit ID and revision are persisted before the final request; if GitHub accepted it but the response was lost, retry recognizes that commit without uploading a duplicate snapshot.

This is backup plus explicit restore, not concurrent two-way synchronization. Conflict messages direct the user to restore the remote repository as a separate vault for review. There is no automatic conflict merge or in-app historical commit picker. Git history retains earlier backups. Restoring the repository uses its current default-branch head, pinned for the entire download.

Use a dedicated notes repository, such as `dotnote-notes`, separate from application source. Connecting an existing vault repository requires Restore, preventing accidental overwrite. Oversized PDFs fail the backup with an explicit message; no silent omissions or Git LFS support. A lost response during the initial README bootstrap may require reconnecting after inspecting the repository.

Reference: [GitHub file limits](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github).

## Remaining device acceptance

Live device-code issuance has been verified for the configured Client ID. Account authorization and private-repository upload require the user's GitHub session and remain a manual acceptance check. Emulator tests exercise file migration, vault isolation, WorkManager scheduling, Git backup/restore, lost responses and remote conflicts using a deterministic Git API fixture. Physical Lenovo stylus behavior and Android background timing must still be checked on the tablet.
