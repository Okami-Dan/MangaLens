package com.mangareader.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mangareader.pipeline.LlmStore
import com.mangareader.translate.ChatProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.preferences: DataStore<Preferences> by preferencesDataStore("reader")

internal suspend fun Context.currentTranslationNote(): String =
    applicationContext.preferences.data.first()[stringPreferencesKey("pageContext")].orEmpty()

/**
 * Section 7's DataStore layer: the two reading aids section 2.3 makes settings, and
 * section 5's translator tier.
 *
 * The reading-aid defaults are the plan's, not a guess: **furigana on, romaji off.**
 * Section 2.3 is a [DECIDED] item -- "furigana, not romaji, is the primary reading aid.
 * Romaji is a settings toggle, off by default" -- so this is the one place the app has an
 * opinion about how Japanese should be learned, and it is the plan's opinion.
 *
 * The translator default is the plan's too, and matters more: **tier 0.** Section 5 makes
 * "none" the default because the dictionary layer above is the core feature and needs no
 * model, no account and no network. Nothing here may quietly promote a tier that costs
 * the user money or a gigabyte of storage.
 *
 * The API key is deliberately *not* here. DataStore is a plaintext file; the key lives in
 * [TranslatorSecrets] instead.
 */
class ReaderSettings internal constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) : this(context.applicationContext.preferences, scope)

    internal val release = ReleasePreferences(store)

    private fun flag(key: String, default: Boolean): Flow<Boolean> =
        store.data.map { it[booleanPreferencesKey(key)] ?: default }

    private fun text(key: String, default: String): Flow<String> =
        store.data.map { it[stringPreferencesKey(key)] ?: default }

    private fun instant(key: String): Flow<Long> =
        store.data.map { it[longPreferencesKey(key)] ?: 0L }

    val furigana = flag(FURIGANA, true).stateIn(scope, SharingStarted.Eagerly, true)
    val romaji = flag(ROMAJI, false).stateIn(scope, SharingStarted.Eagerly, false)

    /** Section 5's tier, as a [TranslatorTier] name. Tier 0 unless the user says otherwise. */
    val translatorTier = text(TIER, TranslatorTier.NONE.name)
        .map { runCatching { TranslatorTier.valueOf(it) }.getOrDefault(TranslatorTier.NONE) }
        .stateIn(scope, SharingStarted.Eagerly, TranslatorTier.NONE)

    /**
     * Section 5: "tier 2 must expose a configurable base URL, not just an OpenAI key
     * field. Ship with an empty base URL defaulting to https://api.openai.com/v1."
     */
    val remoteBaseUrl = text(BASE_URL, RemoteProvider.OPENAI.baseUrl)
        .stateIn(scope, SharingStarted.Eagerly, RemoteProvider.OPENAI.baseUrl)

    val remoteModel = text(REMOTE_MODEL, RemoteProvider.OPENAI.model)
        .stateIn(scope, SharingStarted.Eagerly, RemoteProvider.OPENAI.model)

    /**
     * Which [RemoteProvider] is active, stored rather than inferred.
     *
     * Before this existed, [RemoteProvider.of] recovered the preset from the base URL
     * alone -- fine while every preset had a distinct URL. Ollama and vLLM both default
     * to an empty address, so two presets can share a URL *and* a user-typed model name while
     * speaking different wires; nothing about the stored strings can tell them apart. A
     * blank stored value (every install before this field existed) falls back to
     * [RemoteProvider.of] so an existing base URL still resolves correctly on first read,
     * but [ensureRemoteProvider] is what actually writes the resolved value back, once.
     */
    val remoteProvider = text(REMOTE_PROVIDER, "")
        .map { stored ->
            runCatching { RemoteProvider.valueOf(stored) }.getOrNull()
                ?: RemoteProvider.of(remoteBaseUrl.value)
        }
        .stateIn(scope, SharingStarted.Eagerly, RemoteProvider.of(RemoteProvider.OPENAI.baseUrl))

    /** Section 7's optional user-supplied series/volume note, sent as `page_context`. */
    val pageContext = text(PAGE_CONTEXT, "").stateIn(scope, SharingStarted.Eagerly, "")

    /** Read persisted context when a long-running request finishes. */
    suspend fun currentPageContext(): String = store.data.first()[stringPreferencesKey(PAGE_CONTEXT)].orEmpty()

    /** Which tier-1 model the user has chosen from `LlmStore`'s registry. */
    val localModel = text(LOCAL_MODEL, "").stateIn(scope, SharingStarted.Eagerly, "")

    /**
     * ToDo's "HF link to proper format": one pasted-and-resolved model, alongside
     * `LlmStore.MODELS`'s three presets. Not in the compiled registry, because it did not
     * exist at build time -- `LlmStore.resolve()` builds it from the URL the user pasted,
     * and this is where it is remembered across a restart. One slot, not a list: the
     * picker offers "replace" rather than a growing menu of one-off links.
     */
    val customModel = text(CUSTOM_MODEL, "")
        .map { decodeCustomModel(it) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * When the user last exported the library to a file of their own, or 0.
     *
     * Here rather than beside the library, because the library's own volume is the thing
     * being hedged against: a timestamp stored there would vanish with the thing it is
     * about, and "you have never backed this up" is the most useful sentence the storage
     * card has. It goes with the app on uninstall, which is correct -- afterwards there is
     * nothing this app can honestly claim to know about what the user still has.
     */
    val lastBackupAt = instant(LAST_BACKUP).stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * How each grid is ordered, one entry per [SortScope].
     *
     * Built once as a map rather than four named properties: the four differ only in a
     * key and a default, both of which the scope already carries, and a fifth sortable
     * screen should cost an enum entry and nothing here.
     */
    private val sorts = SortScope.entries.associateWith { which ->
        text(which.key, which.default.name)
            .map { runCatching { SortOrder.valueOf(it) }.getOrDefault(which.default) }
            .stateIn(scope, SharingStarted.Eagerly, which.default)
    }

    fun setFurigana(value: Boolean) = set(FURIGANA, value)

    fun setRomaji(value: Boolean) = set(ROMAJI, value)

    fun setTranslatorTier(value: TranslatorTier) = set(TIER, value.name)

    fun setRemoteBaseUrl(value: String) = set(BASE_URL, value)

    fun setRemoteModel(value: String) = set(REMOTE_MODEL, value)

    fun setRemoteProvider(value: RemoteProvider) = set(REMOTE_PROVIDER, value.name)

    /**
     * Resolves [remoteProvider] against what is actually on disk and writes it back if it
     * was never stored, so a user upgrading with a base URL already in [remoteBaseUrl]
     * keeps their inferred provider permanently rather than re-inferring it (and
     * re-reading as [RemoteProvider.OPENAI] between reads) on every cold start.
     *
     * Reads and writes inside one `edit` transaction rather than trusting the [StateFlow]
     * default, which may not have loaded from disk yet the first time this runs.
     *
     * @return the resolved provider, so [TranslatorSecrets.adoptLegacyKey] can move the
     *   single pre-migration key into the right slot in the same pass.
     */
    suspend fun ensureRemoteProvider(): RemoteProvider {
        val providerKey = stringPreferencesKey(REMOTE_PROVIDER)
        val baseUrlKey = stringPreferencesKey(BASE_URL)
        var resolved = RemoteProvider.OPENAI
        store.edit { prefs ->
            val stored = prefs[providerKey]?.let { runCatching { RemoteProvider.valueOf(it) }.getOrNull() }
            resolved = stored ?: RemoteProvider.of(prefs[baseUrlKey] ?: RemoteProvider.OPENAI.baseUrl)
            if (stored == null) prefs[providerKey] = resolved.name
        }
        return resolved
    }

    fun setPageContext(value: String) = set(PAGE_CONTEXT, value)

    fun setLocalModel(value: String) = set(LOCAL_MODEL, value)

    fun setCustomModel(value: LlmStore.Model?) = set(CUSTOM_MODEL, value?.let(::encodeCustomModel).orEmpty())


    fun sort(which: SortScope) = sorts.getValue(which)

    fun setSort(which: SortScope, value: SortOrder) = set(which.key, value.name)

    fun setLastBackupAt(value: Long) {
        scope.launch { store.edit { it[longPreferencesKey(LAST_BACKUP)] = value } }
    }

    private fun set(key: String, value: Boolean) {
        scope.launch { store.edit { it[booleanPreferencesKey(key)] = value } }
    }

    private fun set(key: String, value: String) {
        scope.launch { store.edit { it[stringPreferencesKey(key)] = value } }
    }

    private companion object {
        const val FURIGANA = "furigana"
        const val ROMAJI = "romaji"
        const val TIER = "translatorTier"
        const val BASE_URL = "remoteBaseUrl"
        const val REMOTE_MODEL = "remoteModel"
        const val REMOTE_PROVIDER = "remoteProvider"
        const val PAGE_CONTEXT = "pageContext"
        const val LOCAL_MODEL = "localModel"
        const val CUSTOM_MODEL = "customModel"
        const val LAST_BACKUP = "lastBackupAt"
    }
}

/** Release UI preferences share the reader store but never enter library backups. */
internal class ReleasePreferences(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.preferences)

    suspend fun tutorialWasShown(): Boolean = store.data.first()[TUTORIAL_SHOWN] ?: false

    suspend fun markTutorialShown() {
        store.edit { it[TUTORIAL_SHOWN] = true }
    }

    /** Claim exactly one image per process; the caller retains it through recreation. */
    suspend fun takeStartupArtwork(): Int {
        var selected = 0
        store.edit { prefs ->
            selected = (prefs[NEXT_ARTWORK] ?: 0).mod(4)
            prefs[NEXT_ARTWORK] = (selected + 1) % 4
        }
        return selected
    }

    private companion object {
        val TUTORIAL_SHOWN = booleanPreferencesKey("tutorialShown")
        val NEXT_ARTWORK = intPreferencesKey("nextStartupArtwork")
    }
}

/** Section 5's three tiers, in the order the settings screen lists them. */
enum class TranslatorTier(val label: String, val detail: String) {
    NONE(
        "None",
        "Dictionary-based grammar and lexical breakdown. Completely on-device.",
    ),

    /** The on-device translator uses a model installed on this phone. */
    LOCAL(
        "Local LLM",
        "Requires one-time model download. Completely on-device.",
    ),

    REMOTE(
        "Remote LLM",
        "Send API requests to your own servers and major providers.",
    ),
}

/**
 * Base-URL presets for tier 2.
 *
 * Section 5's point is that one text field buys a whole quality tier, because the same
 * OpenAI-compatible wire format serves the commercial APIs and a self-hosted vLLM or
 * llama.cpp server. These are shortcuts, not a closed list: [CUSTOM] leaves both fields
 * free, and the model name is always editable, so a preset that goes stale is one edit to
 * fix rather than a broken build.
 *
 * [protocol] is what makes Anthropic possible without a second settings screen -- it is
 * the one preset whose wire format actually differs, everything else here is
 * [ChatProtocol.OPENAI]. [needsKey] only changes the field's placeholder text; the key is
 * always optional to send (see [ChatEndpoint][com.mangareader.translate.ChatEndpoint]).
 *
 * Self-hosted presets require the user's server address. HTTPS can reach remote hosts;
 * `network_security_config.xml` permits plain HTTP only to localhost/127.0.0.1 on the
 * phone (or a desktop forwarded through adb reverse).
 */
enum class RemoteProvider(
    val label: String,
    val baseUrl: String,
    val model: String,
    val protocol: ChatProtocol,
    val needsKey: Boolean,
) {
    // Smallest current model on each paid provider, and the dialect it actually needs --
    // not the classic OpenAI one for two of these three. Verified against the real
    // endpoints with `tools/probe_endpoints.py`: see ChatProtocol's doc for why OPENAI and
    // DEEPSEEK below are not both plain ChatProtocol.OPENAI.
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-5.6-luna", ChatProtocol.OPENAI_REASONING, true),
    ANTHROPIC(
        "Anthropic",
        "https://api.anthropic.com/v1",
        "claude-haiku-4-5-20251001",
        ChatProtocol.ANTHROPIC,
        true,
    ),
    DEEPSEEK(
        "DeepSeek",
        "https://api.deepseek.com/v1",
        "deepseek-v4-flash",
        ChatProtocol.DEEPSEEK,
        true,
    ),
    OLLAMA("Ollama", "", "", ChatProtocol.OPENAI, false),
    VLLM("vLLM", "", "", ChatProtocol.OPENAI, false),
    CUSTOM("Custom", "", "", ChatProtocol.OPENAI, false),
    ;

    companion object {
        /**
         * Which preset a stored base URL corresponds to.
         *
         * No longer the settings screen's source of truth -- [ReaderSettings.remoteProvider]
         * is, because an arbitrary HTTPS address cannot identify its server provider.
         * This stays for the one-time migration in [ReaderSettings.ensureRemoteProvider]
         * and the fallback for a corrupted stored value. Legacy localhost defaults are
         * recognized; an empty address identifies no provider and resolves to CUSTOM.
         */
        fun of(baseUrl: String): RemoteProvider {
            val normalized = baseUrl.trim().trimEnd('/')
            // Retain inference for configurations saved before the presets became empty.
            return when (normalized) {
                "" -> CUSTOM
                "http://localhost:11434/v1" -> OLLAMA
                "http://localhost:8000/v1" -> VLLM
                else -> entries.firstOrNull {
                    it.baseUrl.isNotBlank() && it.baseUrl == normalized
                } ?: CUSTOM
            }
        }
    }
}

/**
 * [ReaderSettings.customModel]'s on-disk shape. Plain [org.json.JSONObject] rather than
 * kotlinx.serialization: `:app` has no serialization dependency today, this is one small
 * object, and pulling in a serializer for a five-field record would be the tail wagging
 * the dog.
 */
private fun encodeCustomModel(model: LlmStore.Model): String =
    org.json.JSONObject().apply {
        put("id", model.id)
        put("label", model.label)
        put("url", model.url)
        put("bytes", model.bytes)
        put("sha256", model.sha256)
    }.toString()

private fun decodeCustomModel(json: String): LlmStore.Model? {
    if (json.isBlank()) return null
    return runCatching {
        val obj = org.json.JSONObject(json)
        LlmStore.Model(
            id = obj.getString("id"),
            label = obj.getString("label"),
            detail = "Imported from a pasted link. Chat format is detected from the " +
                "model's own file at first use.",
            url = obj.getString("url"),
            bytes = obj.getLong("bytes"),
            sha256 = obj.getString("sha256"),
            contextLength = 32_768,
            minRamBytes = 8_000_000_000L,
            preset = LlmStore.Preset.CUSTOM,
            family = LlmStore.ModelFamily.GENERIC,
        )
    }.getOrNull()
}
