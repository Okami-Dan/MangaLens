package com.mangareader.ui

import android.content.Context
import java.io.File

/** Explicitly staged, debug-only input for repeatable tutorial recordings. */
internal object TutorialCameraSource {
    fun load(context: Context): ByteArray? {
        val file = File(context.filesDir, "tutorial-camera.jpg")
        return if (file.isFile && file.length() in 1..16_777_216) {
            runCatching { file.readBytes() }.getOrNull()
        } else null
    }
}
