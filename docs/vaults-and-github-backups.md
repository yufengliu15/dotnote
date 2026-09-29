# Vaults and GitHub backups

[Documentation index](README.md) · Sources: [GitHub.kt](../app/src/main/java/dev/dotnote/app/GitHub.kt), [GitBackup.kt](../app/src/main/java/dev/dotnote/app/GitBackup.kt), [VaultCatalog.kt](../app/src/main/java/dev/dotnote/app/VaultCatalog.kt), [VaultUi.kt](../app/src/main/java/dev/dotnote/app/VaultUi.kt)

## Contract

A vault is one local portable directory. Each local vault may bind to one GitHub repository/branch. Multiple vaults permit multiple repositories, but credentials are currently shared app-wide: there is no separate account per vault.

This is **local-first backup plus explicit restore**, not two-way synchronization. A remote change from another device causes a conflict stop. Restore creates another local vault; it does not merge remote strokes into the current scene or replace the current library. There is no automatic polling for remote changes while simply editing.

The application source repository and a vault-data repository are separate. Initial connection requires an empty repository or one containing only root `README.md`, `LICENSE`, and `.gitignore`. An existing vault repository must be restored, not connected as a fresh backup target. The user chooses public/private visibility in GitHub itself; the app does not create repositories or change privacy settings.

## Authentication

### Device authorization

The public OAuth client ID is `Ov23lisfSrWV5wQwk2we`. It is an identifier, not a secret. It is compiled into `DEFAULT_GITHUB_CLIENT_ID`; `github-setup` preferences can store an advanced override.

1. POST form data to `https://github.com/login/device/code` with `client_id` and `scope=repo`.
2. Require a device code and the expected verification URI `https://github.com/login/device`.
3. Show `user_code`; let the user copy it and open the URI in a browser.
4. Poll `https://github.com/login/oauth/access_token` with client ID, device code, and `urn:ietf:params:oauth:grant-type:device_code`.
5. Wait at least five seconds between polls; `authorization_pending` continues and `slow_down` adds five seconds. Stop on timeout, declined/expired response, cancellation, or success.
6. On success, request `/user`, then save login, client ID and token response fields in encrypted credentials.

The app has no OAuth redirect activity, callback URL handler, embedded client secret or auth server. Device authorization must be enabled for the registered GitHub app. This code path does not consume a redirect URI even if GitHub registration asks for one.

The `repo` scope is broader than a repository-scoped fine-grained token. Advanced token setup validates `/user` and stores token/login. Its UI instructs users to grant Contents read/write only on selected vault repositories, or read-only for restore. The code supports repository enumeration for ordinary tokens and installation-backed `ghu_` tokens; selecting a listed repository still requires appropriate effective permissions.

### Credentials and refresh

`Credentials` encrypts the credential JSON with AES/GCM/NoPadding and a 128-bit authentication tag. The AES key is generated in Android Keystore under alias `dotnote-github`. SharedPreferences stores only Base64 IV/ciphertext in `github-credentials` → `encrypted`.

Decrypted fields are `token`, `login`, optional `clientId`, `refresh`, and `expiresAt` in epoch milliseconds. `GitHubAuth.token` serializes refresh under a monitor. If expiry is more than 60 seconds away (or absent), it returns the token. Otherwise it requires a refresh token and requests a refresh without a client secret; a failure to obtain an access token asks the user to reconnect.

Credentials are not included in vault files, ZIPs, exported folders, Git snapshots or Android automatic backup. A new device signs in independently. Clearing local credentials is not remote token revocation and does not delete the Keystore key. Broken credential decryption yields an explicit reconnect error.

Account disconnect cancels currently queued work and clears local credential preferences. Existing vault repository configurations remain. Later app startup/edits can schedule work against those bindings again; it will require credentials to succeed. Per-vault repository disconnect cancels that vault's work and removes `repo`, `base`, and `pending`, while leaving other historical configuration fields.

## HTTP/Git adapter

`GitHub` uses `HttpURLConnection` with fixed GitHub hosts, HTTPS, 20-second connect and 45-second read timeouts, redirects disabled, JSON Accept headers, `User-Agent: Dotnote-Android`, and API version `2022-11-28`. Bearer authorization is sent to API requests, not device-code requests. Repository input normalization supports owner/repository and the expected GitHub HTTPS prefix, strips a trailing slash/`.git`, and rejects invalid owner/name forms. Enterprise/custom Git hosts are not implemented.

JSON responses are bounded to 32 MiB. Repository listing is paginated and deduplicated by full name: ordinary listing permits up to 100 pages of 100; installation-backed listing traverses bounded installation/repository pages. Repository metadata supplies the default branch, private flag and push permission. There is no branch-selection UI.

Tree lookup resolves a commit to its recursive tree, rejects truncated responses, limits non-directory entries to 10,000, and accepts only regular blobs with mode `100644`. Symlinks, executable-mode blobs and submodules are rejected. Blob upload streams Base64 JSON instead of constructing a full Base64 attachment string in memory. Blob download streams raw bytes, checks exact advertised length and verifies Git's SHA-1 blob digest (`"blob <length>\0" + bytes`). Local imported-PDF names use SHA-256 instead; those are different digests for different purposes.

HTTP errors map to readable messages. The worker separately classifies 401/404/422 as terminal, while some other HTTP/network failures retry. A protected branch can reject writes; the app does not disable branch protection.

## Device-local backup configuration

`VaultCatalog` stores each JSON object under `config_<local-id>` in the `vaults` preferences. These values are intentionally excluded from portable settings.

| Key | Meaning |
| --- | --- |
| `repo` | `owner/name`; absent/blank means no binding |
| `branch` | Branch captured from repository default branch when connected/restored |
| `private` | Last observed repository privacy, for display |
| `automatic` | Automatic scheduling enabled, default true when bound |
| `minutes` | Inactivity delay, default 60, allowed 15–360 |
| `unmetered` | Require unmetered networking; default false |
| `editedAt` | Last local managed edit epoch milliseconds |
| `revision` | Local dirty revision, incremented by `edited()` |
| `backedRevision` | Revision represented by last successful snapshot; default -1 for scheduling |
| `base` | Last accepted remote commit SHA; empty for an initially empty repository |
| `pending` | Commit created locally/remotely but whose ref-update acknowledgement may be uncertain |
| `pendingRevision` | Revision associated with that pending commit |
| `lastBackup` | Last successful/reconciled backup time |
| `status` | User-visible status/error string, not an enum/state machine |

Vault naming, folder/note changes, document camera/dot changes, and saved writing settings can increment the dirty counter. Merely recording a note opening does not. Identical saves/settings suppress redundant dirty changes. No repository is required for local edits; the status/counter can still be updated while unbound.

## Scheduling: inactivity, not a repeating timer

`BackupScheduler.schedule` creates unique one-time work named `vault-backup-<local-id>` with `ExistingWorkPolicy.REPLACE`.

```text
remainingDelay = max(0, editedAt + minutes * 60,000 - now)
```

Each actual edit replaces existing work and resets the deadline. Continuous editing can postpone automatic backup indefinitely; “every 15 minutes” is not the implemented contract. Scheduling skips unbound vaults, disabled automatic backup, and already-backed revisions. The app reconciles work on startup.

Requests carry `vault` and `manual` input fields. Network constraints are CONNECTED or UNMETERED. Backoff is exponential from 30 seconds. Manual “Back up now” sets initial delay zero and bypasses inactivity/automatic-disabled checks, but still respects networking and Android scheduling. The UI first flushes the document and writing settings.

`VaultBackupWorker` rechecks the persisted deadline and automatic flag after waking. A too-early automatic run retries; a disconnected/disabled automatic run succeeds without upload. Android Doze, battery policies, connectivity and WorkManager may delay execution. No exact alarm, foreground service or battery-exemption flow is implemented.

On errors, status is persisted. `RemoteChanged`, `IllegalArgumentException` and HTTP 401/404/422 return failure. Other exceptions retry while `runAttemptCount < 6`, then fail. Cancellation is rethrown. This is not infinite retry, and no notification channel alerts the user; inspect status in settings.

## Backup algorithm

The complete operation holds the per-vault upload mutex; it acquires the root mutex only for snapshot staging.

1. Read binding and token, set status to backing up, fetch branch head.
2. If remote head equals a persisted `pending` commit, recognize a previously successful ref update whose response was lost; advance `base`/backed revision and clear pending state as processing completes.
3. Require remote head (or empty string) to equal the accepted `base`. Otherwise stop with `RemoteChanged` before replacing remote content.
4. Under the root mutex, instantiate/read `VaultFiles`, capture current backup revision and create a coherent snapshot in cache `upload-<uuid>`.
5. Preflight **every** staged file as strictly smaller than 100 MiB. Reject the backup if any exceeds the limit; do not omit PDFs/notes.
6. For a completely empty repository, use the Contents endpoint to create `README.md` in a bootstrap commit, then record its SHA as base. Git Data endpoints alone cannot initialize the empty repository in this implementation.
7. Read the accepted tree. Compare each staged file's Git blob SHA against its remote entry. Upload changed blobs only; add deletion entries (`sha: null`) for remote managed files absent locally. Preserve allowed nonmanaged root documents using the base tree.
8. If there are changes, create one new tree and one commit whose parent is the accepted head. Recheck the branch head before publication.
9. Persist `pending` commit SHA/revision **before** PATCHing the branch ref with `force: false`. Convert a 422 rejection to a conflict.
10. Update `base`, `backedRevision`, `lastBackup`, and status. If the current local revision exceeds the staged revision, report “New edits waiting for backup”; otherwise report all changes backed up. Clear pending fields and remove staging in `finally`.

An unchanged snapshot makes no extra content commit, but updates local successful-backup status/time. The first backup of an empty repository may produce two commits (README bootstrap plus vault snapshot). Staged file paths include only manifest/settings, folder markers, notes and referenced PDFs. Trash, journal, migration marker, credentials, Room, widget recency and orphan assets are excluded.

Remote changes during upload are caught by the final head check/non-force ref update. Uploading unreachable blobs/commits before a conflict is possible; they do not overwrite the branch. The pending-commit mechanism covers uncertain final ref acknowledgements. It does not fully cover every network interruption, notably an acknowledged-lost initial README bootstrap. This protocol is not a distributed transaction or a merge engine.

## Restore algorithm

1. Normalize the repo name, inspect repository/default branch and pin its current head SHA.
2. Require `.dotnote/vault.json`. Reject unexpected tree files other than managed paths and root README/LICENSE/.gitignore.
3. Require each managed blob size in `[0, 100 MiB)`, aggregate managed size at most 1 GiB, and an acceptable nontruncated tree.
4. Download managed blobs into `files/vaults/.restore-<uuid>`, verify lengths/digests.
5. `VaultCatalog.publish` validates the entire staged vault and renames it to a fresh local ID. Portable IDs, notes, folders and preferences remain intact.
6. Store a new binding with pinned head as base, revision/backed revision zero, delay 60, and automatic enabled only if the repo is writable. Mark status restored and switch through AppState's ordinary flow.

Read-only repositories can be restored and edited locally. They cannot successfully receive backups without write access; the UI/API still enforce permissions where relevant. Restore is the current default-branch snapshot, not a branch/commit/history chooser. Remote changes after the pinned commit will be detected on a later upload.

## Conflict workflow and limitations

When the branch is newer than `base`, keep local work, restore the repository as another vault, and inspect the two copies. The app does not automatically reconcile them. Two devices repeatedly editing the same repository require an explicit workflow; setting up the same repo on both devices is not sync. Reconnecting an already populated repository as a new target is deliberately rejected.

Private repositories restrict GitHub access but vault content is not end-to-end encrypted. Git LFS, arbitrary Git hosts, repository creation/privacy management, scheduled historical retention, merge conflict resolution, and background download synchronization are absent. Large PDFs that work locally may exceed the stricter Git file limit.

## Verification and deferred issue

`VaultPipelineTest` uses injected `GitHub`/token providers to test trees, commits, no-change backups, final-response loss, restore and remote conflicts. `VaultLifecycleTest` inspects real WorkManager requests for delay replacement and unmetered constraints. These are meaningful local protocol tests, not live private-account certification.

The release record confirms the configured client ID obtained a live device code. The user's Lenovo still reports `Unable to resolve host "github.com": No address associated with hostname`; internet/network-state permissions already exist. The user explicitly deferred this in [TODO.md](../TODO.md). Do not label sign-in or end-to-end backup fixed without reproducing it on that tablet. Future diagnosis should distinguish DNS/network failure before token exchange from API/auth/permission errors after it, and should never log tokens.
