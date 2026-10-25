package com.mangareader.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Section 5: "store the API key in EncryptedSharedPreferences. Never log it. Never
 * include it in crash reports."
 *
 * This is the only credential the app holds and the only piece of user data in it that
 * cannot be re-derived from a photograph, so it gets its own file rather than a field in
 * [ReaderSettings] -- DataStore writes plaintext, and a key sitting in
 * `files/datastore/reader.preferences_pb` would be readable by anything with a backup of
 * the app's data directory.
 *
 * **One slot per [RemoteProvider]**, not one key for the whole tier. Before Anthropic
 * existed there was only ever one remote configuration worth a key, so one slot was
 * correct; now switching from OpenAI to Anthropic and back must not mean retyping either
 * key. `EncryptedSharedPreferences` encrypts preference *names* under `AES256_SIV` as well
 * as values, so which providers have a key stored is not visible on disk either.
 *
 * Three rules the rest of the app has to keep, because nothing here can enforce them:
 *
 *  - the key is never logged, not even truncated, and never put in an error message that
 *    reaches the UI -- see `ChatEndpoint`, which builds its own error strings
 *  - it is never part of the translation cache key, which is hashed and stored in the
 *    clear
 *  - [preview] is what the settings screen shows, never the value itself
 *
 * If the keystore is unavailable -- a device with a broken or reset keystore, which does
 * happen -- this degrades to holding no key rather than crashing the app. The user sees
 * tier 2 as unconfigured and can re-enter it, which is the honest outcome; taking the
 * whole app down over a settings screen is not.
 */
class TranslatorSecrets(context: Context) {

    // security-crypto 1.0.0's shape: a keystore alias and the five-argument create().
    // The MasterKey builder and the four-argument overload are 1.1.0-alpha, and this
    // project stays on the stable line -- the alpha's only relevant addition is a
    // builder, and one credential does not justify an alpha dependency.
    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                FILE,
                alias,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.onFailure {
            // The exception, not the value -- there is no key in a keystore failure, but
            // this is the one class where that has to be deliberate rather than lucky.
            Log.w(TAG, "encrypted preferences unavailable; tier 2 will read as unconfigured", it)
        }.getOrNull()
    }

    fun keyFor(provider: RemoteProvider): String = prefs?.getString(slot(provider), "").orEmpty()

    fun setKey(provider: RemoteProvider, value: String) {
        val trimmed = value.trim()
        val name = slot(provider)
        prefs?.edit()?.apply {
            if (trimmed.isEmpty()) remove(name) else putString(name, trimmed)
        }?.apply()
    }

    fun hasKey(provider: RemoteProvider): Boolean = keyFor(provider).isNotEmpty()

    /**
     * What the settings screen renders once a key is saved: enough for the user to tell
     * which key it is, not enough to be worth a screenshot. A short key shows only its
     * length, because the last four characters of an eight-character key is most of it.
     */
    fun preview(provider: RemoteProvider): String {
        val key = keyFor(provider)
        return when {
            key.isEmpty() -> ""
            key.length < 12 -> "•".repeat(key.length)
            else -> "••••" + key.takeLast(4)
        }
    }

    /**
     * Moves the pre-multi-provider single key into [into]'s slot. Idempotent and safe to
     * call on every start: the legacy value is removed as part of the same transaction
     * that writes it into the new slot, so there is no window where the key exists in
     * neither place, and a second call finds nothing left to migrate.
     *
     * Does not overwrite a key already present in [into]'s slot -- if the user has
     * somehow already saved one there, the legacy value is discarded rather than clobbering
     * it, since a fresh save is more likely to be the one they meant to keep.
     */
    fun adoptLegacyKey(into: RemoteProvider) {
        val store = prefs ?: return
        val legacy = store.getString(API_KEY, "").orEmpty()
        if (legacy.isEmpty()) return
        store.edit().apply {
            if (store.getString(slot(into), null) == null) putString(slot(into), legacy)
            remove(API_KEY)
        }.apply()
    }

    private fun slot(provider: RemoteProvider) = API_KEY + ":" + provider.name

    private companion object {
        const val TAG = "TranslatorSecrets"
        const val FILE = "translator-secrets"

        /** Pre-multi-provider single slot. Read only by [adoptLegacyKey]. */
        const val API_KEY = "apiKey"
    }
}
