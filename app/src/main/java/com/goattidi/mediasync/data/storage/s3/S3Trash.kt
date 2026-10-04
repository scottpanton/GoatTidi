package com.goattidi.mediasync.data.storage.s3

/**
 * Where an S3-compatible service conventionally keeps deleted objects.
 *
 * S3 has no trash: a delete removes the key, full stop. Tools that want a way out move
 * the object under a reserved prefix instead — `rclone --trash-dir`, `s3cmd`'s policy files
 * and Synology Hyper Backup all default to `.Trash/`. Recognising it buys GoatTidi two
 * things Drive gets for free:
 *
 * - A file another tool trashed can be reported as *in the trash* rather than merely
 *   missing, which is what `RemoteObject.trashed` is for.
 * - Whole-bucket scans stop walking the trash and counting deleted files as files the
 *   app could sync-detect against.
 *
 * Nothing here writes to the prefix. GoatTidi has no remote-delete action to intercept, and
 * inventing one would be a different feature with its own confirmation flow.
 */
object S3Trash {

    /** Reserved key prefix, relative to the bucket root. */
    const val PREFIX = ".Trash/"

    /** Whether [key] is an object that has been trashed rather than deleted. */
    fun isTrashed(key: String): Boolean = key.startsWith(PREFIX)

    /**
     * Where [key] would be moved if it were trashed. A key already under the prefix is
     * returned unchanged, so probing never searches for a trash of a trash.
     */
    fun trashedKey(key: String): String = if (isTrashed(key)) key else PREFIX + key

    /**
     * Whether [name] would put an upload inside the reserved prefix, which would make the
     * app's own files look trashed to itself. Reserved rather than merely unlikely.
     */
    fun isReservedName(name: String): Boolean = name == ".Trash" || name.startsWith(".Trash/")
}