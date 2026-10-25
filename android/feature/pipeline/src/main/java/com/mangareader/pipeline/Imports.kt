package com.mangareader.pipeline

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Installing a model from a file the user picked, rather than from a URL.
 *
 * The third source, beside [Downloads]' network and `ModelStore`'s `adb push` staging, and
 * the one that closes the hole in section 4 stage 5: the INT8 OCR export is produced
 * locally by `tools/quantize_models.py` and is not published anywhere, so until now the
 * only way onto a phone was a USB cable and a shell. A document picker takes the same
 * bytes off Downloads, Drive, or a USB stick with no cable and no CLI.
 *
 * ## Identified by hash, not by name
 *
 * The picked file is streamed to scratch, hashed on the way through by
 * [Downloads.copy], and only then matched -- against the SHA-256 already pinned in
 * `ModelStore.ARTIFACTS` and `LlmStore.MODELS` -- to work out what it is and where it
 * goes. Two things fall out of that, both of which a filename check would get wrong:
 *
 *  - a file the browser saved as `encoder (1).onnx`, or that the user renamed, still
 *    lands in the right place;
 *  - a truncated or tampered file is rejected by the *same* step that identifies it, so
 *    there is no path on which an unverified model is installed. That is the rule the
 *    rest of this package is built around and importing does not get to be an exception.
 */
internal object Imports {

    /** What the picker knows about a document before it is opened. */
    data class Document(val name: String, val bytes: Long)

    fun describe(context: Context, uri: Uri): Document {
        val fallback = Document(uri.lastPathSegment ?: "file", 0L)
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use fallback
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                Document(
                    name = if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                        cursor.getString(nameColumn)
                    } else {
                        fallback.name
                    },
                    // Unknown is 0 rather than -1: it feeds a progress bar, and a
                    // negative total there renders as a bar running backwards.
                    bytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        cursor.getLong(sizeColumn)
                    } else {
                        0L
                    },
                )
            } ?: fallback
        }.getOrDefault(fallback)
    }

    /**
     * Streams [uri] into [into], hashing as it goes.
     *
     * @return the hex SHA-256 of what was written, for the caller to identify and verify
     */
    suspend fun copy(
        context: Context,
        uri: Uri,
        into: File,
        report: (Long) -> Unit,
    ): String =
        context.contentResolver.openInputStream(uri)?.use { Downloads.copy(it, into, report) }
            ?: error("could not open the picked file")
}
