package com.mangareader.pipeline

import java.io.File
import java.util.UUID

/** Table names scope IDs: migrated series and volume rows can share the same ID. */
internal object CoverFiles {
    fun newFile(directory: File, owner: CoverStore.Owner, id: String): File =
        File(directory, "${owner.name.lowercase()}-$id-${UUID.randomUUID()}.jpg")

    fun obsolete(directory: File, owner: CoverStore.Owner, id: String, referenced: Set<File>): List<File> =
        directory.listFiles().orEmpty().filter { file ->
            val owned = file.name.startsWith("${owner.name.lowercase()}-$id-") ||
                file.name == "$id.jpg" || file.name.startsWith("$id-")
            owned && file.canonicalFile !in referenced
        }
}
