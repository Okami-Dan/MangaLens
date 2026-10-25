package com.mangareader.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** A monotonic deadline survives activity recreation without extending the artwork. */
internal class StartupArtworkTimer {
    private var deadline: Long? = null

    fun start(now: Long) {
        if (deadline == null) deadline = now + 2500L
    }

    fun remaining(now: Long): Long = deadline?.let { (it - now).coerceAtLeast(0L) } ?: 2500L
}

/** Process ownership avoids re-claiming an image when Android recreates the activity. */
internal object StartupArtworkSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selection: Deferred<Int>? = null
    val timer = StartupArtworkTimer()

    fun image(context: Context): Deferred<Int> = selection ?: scope.async {
        ReleasePreferences(context.applicationContext).takeStartupArtwork()
    }.also { selection = it }
}
