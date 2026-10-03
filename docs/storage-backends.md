# Storage backends: how GoatTidi talks to Drive and S3

GoatTidi uploads to one storage service at a time. Google Drive is the default; any
S3-compatible service can be selected instead. This document explains the shape of that
abstraction, because the interesting decisions are all about how little the two services
have in common.

## The seam

`data/storage/StorageBackend.kt` describes the operations the sync engine needs. Everything
above it — `SyncWorker`, `VerifyEngine`, `ReclaimEngine`, the destination resolver — depends
only on that interface.

`data/storage/DriveStorageBackend.kt` presents Google Drive through it by delegating to the
existing `DriveClient` and `DriveUploader` unchanged. `data/storage/S3StorageBackend.kt` does
the same for `S3Client`. `StorageRouter` hands out whichever the user selected.

Nothing in `data/drive/` was modified. Its six files and their tests are the regression gate
for Drive behaviour, and Drive's resumable-upload protocol, session-expiry restarts and
Google-specific error mapping all remain owned by the Drive code that already handled them.

`StorageException` mirrors `DriveException`'s taxonomy rather than extending it. Kotlin
sealed-class subclasses must share a package, so making one a subtype of the other would have
meant restructuring `DriveModels.kt`; translating at the adapter boundary keeps both intact
and keeps S3's error vocabulary out of the sync engine.

## Checksums: the part that actually needed thought

Invariant #1 says a file may only be marked SYNCED when the stored bytes are *proven* to
match the local file. On Drive the stored checksum is `md5Checksum` and the proof is direct.

S3 stores no MD5 of the content. It stores an **ETag**, which means different things
depending on how the object was written:

| How it was written | ETag |
|---|---|
| Single-request `PUT` | `MD5(content)` |
| Multipart upload | `MD5(MD5(part₁) ‖ MD5(part₂) ‖ …) - <partCount>` |

Because GoatTidi chooses the multipart part boundaries, it can reproduce the second form
from the local file — but only with the *same* boundaries. That is why
`S3Checksum.PART_SIZE_BYTES` is a documented constant rather than a preference: changing it
would make every previously uploaded large object's stored ETag unmatchable, and its record
un-verifiable.

The rule `S3StorageBackend.outcomeFor` applies, in full:

- ETag exactly equals the locally computed value → `Verified`. This is the only path to
  SYNCED.
- ETag is md5-shaped but different → `ChecksumMismatch`, reported as a failed upload.
- ETag is not md5-shaped (some gateways return opaque values) → `Unverified`, so the record
  stays in flight and is checked again later.

That third case is the reason this can claim to be safe on *any* S3-compatible service: a
value it cannot interpret is never treated as proof. It also means the app may upload a
large file and never mark it synced, rather than marking it synced wrongly. The former is
visible and fixable; the latter is the exact failure this app exists to prevent.

Both directions stay cheap. A single-request PUT compares against the MD5 the app already
cached. For multipart, the expected composite is built from the per-part digests as the parts
are sent — and an individual part's ETag *is* that part's MD5, so a resumed upload rebuilds
it from `ListParts` without re-reading the file.

## What S3 cannot do, and what was done about it

| Drive | S3 | Consequence |
|---|---|---|
| Trash, distinct from deletion | none | A deleted object makes a record ORPHANED directly |
| Per-app file ownership (`isAppAuthorized`) | not expressible in a listing | Our uploads are recognised by sitting under the destination prefix. Objects carry an `x-amz-meta-goattidi-upload` marker too, but `ListObjects` does not return user metadata, so the prefix is what the scan actually uses |
| Folder tree with `parents` | flat keys | A container is a key prefix. Every object reports the prefix it sits in, so one parent map still answers "is this inside that container" for both backends |
| Two files may share a name in a folder | keys are unique | Keys carry the media id. Without it `IMG_0001.jpg` from two folders would target one key |
| OAuth sign-in | static credentials | No Connect button for S3; the endpoint and key are the identity |

## Switching backends

`sync_records.backendId` records which service issued each object id. It is `null` on rows
written before this existed and reads as Drive, so an upgrade changes nothing about anyone's
synced state. The ledger's primary key is `(backendId, md5)`: the same bytes legitimately
exist in both services once a user switches, and a single-column key would have made one
entry overwrite the other.

Selecting a backend drops remote state the other one issued. The files stay untouched; they
simply need uploading to the newly selected service. A record must not keep claiming they are
already there.

## S3 limitations worth knowing

- **Large objects uploaded by another tool may not be recognized as duplicates.** Their
  multipart ETag reflects the part size *they* used, so it will not equal one computed at
  GoatTidi's boundaries. Such files are re-uploaded rather than wrongly skipped. Where the
  service reports `x-amz-checksum-md5`, that value is used instead and dedup works. This
  makes dedup weaker than Drive's for large files, never wrong.
- **Removing a remote copy is not offered.** Free up space deletes the local file only,
  gated on a fresh check of the stored copy — the same scope as the Drive feature.
- **Object keys contain the media id.** Necessary for uniqueness, and it means keys do not
  match bare filenames.

## Testing

- `SigV4SignerTest` replays seven cases from AWS's published signing test suite
  (`awslabs/aws-c-auth`, `tests/aws-signing-test-suite/v4`), each pinning the exact canonical
  request, string-to-sign and signature the specification produces.
- `S3ClientTest` and `S3StorageBackendTest` run against `MockWebServer`, covering signing and
  addressing, listings with continuation tokens and `CommonPrefixes`, part resume via
  `ListParts`, every error-code to failure-type mapping, and each unverified path.
- `AppDatabaseMigrationTest` opens a real v4 database so Room performs `MIGRATION_4_5` and
  validates the result against the entities.
- The existing Drive tests were re-pointed at `DriveStorageBackend` rather than deleted, so
  every mocked Drive response now has to survive being translated into a `StorageBackend`
  call.

As with Drive, mock tests cannot reproduce the timing of checksum availability or multipart
session expiry. Before trusting the S3 backend with data you care about, run it against a
real MinIO bucket once — the equivalent of the live-Drive pass the project already requires.