package com.mangareader.ui

import androidx.compose.ui.graphics.RectangleShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mangareader.pipeline.LlmStore
import com.mangareader.translate.ChatProtocol

/**
 * Section 5's translator tiers, in Settings.
 *
 * The screen has one job beyond storing four strings: **setting expectations before the
 * user commits.** Section 5 is explicit that tier 1 must be labelled "Offline (lower
 * quality)" and must never be the default, and tier 2 sends the recognized Japanese to a
 * third party, which the user is entitled to know before they turn it on rather than
 * after. Both facts are on the screen, next to the control that acts on them.
 *
 * The API key field is write-only. Once saved it renders as a masked preview from
 * [TranslatorSecrets.preview] and can be replaced or cleared, but never read back --
 * there is no reason for this screen to be able to display a credential.
 */
@Composable
fun TranslationCard(
    settings: ReaderSettings,
    translation: TranslationController,
    grammar: GrammarController,
) {
    val tier by settings.translatorTier.collectAsState()

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.selectableGroup()) {
                TranslatorTier.entries.forEach { entry ->
                    TierRow(
                        tier = entry,
                        selected = entry == tier,
                        onSelect = { settings.setTranslatorTier(entry) },
                    )
                }
            }

            AnimatedVisibility(tier == TranslatorTier.REMOTE) {
                RemoteSettings(settings, translation)
            }
            AnimatedVisibility(tier == TranslatorTier.LOCAL) {
                LocalSettings(settings, translation)
            }
            CacheRow(translation, grammar)
        }
    }
}

@Composable
private fun TierRow(tier: TranslatorTier, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(48.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(22.dp).border(2.dp, MangaPalette.Ink),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(10.dp).background(MangaPalette.Orange))
            }
        }
        Column(Modifier.padding(top = 12.dp)) {
            Text(tier.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                tier.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Section 5: "tier 2 must expose a configurable base URL, not just an OpenAI key field.
 * Same OpenAI-compatible wire format serves commercial APIs and a self-hosted vLLM or
 * llama.cpp server, so this is one text field for a whole extra quality tier."
 *
 * The presets are shortcuts into that field, not a closed list -- everything stays
 * editable, so a preset that goes stale costs one edit rather than a release. Which
 * service is picked from [ServiceDropdown] rather than a row of chips: a chip row read as
 * a second bank of top-level flags sitting right under the real ones ([TierRow]'s
 * none/local/remote), when only one of these services is ever active at a time.
 */
@Composable
private fun RemoteSettings(settings: ReaderSettings, translation: TranslationController) {
    val baseUrl by settings.remoteBaseUrl.collectAsState()
    val model by settings.remoteModel.collectAsState()
    val provider by settings.remoteProvider.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "This sends the recognized Japanese from each page you translate to the " +
                "service below. Nothing else leaves the phone, and no page is sent " +
                "until you tap translate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ServiceDropdown(
            selected = provider,
            onSelect = { entry ->
                // Custom only changes which wire and key slot are active -- it leaves
                // whatever base URL and model the user already typed, since it exists
                // precisely so a free-form config is reachable once a preset has been
                // picked once.
                if (entry != RemoteProvider.CUSTOM) {
                    settings.setRemoteBaseUrl(entry.baseUrl)
                    settings.setRemoteModel(entry.model)
                }
                settings.setRemoteProvider(entry)
            },
        )

        PersistedTextField(
            stored = baseUrl,
            onChange = settings::setRemoteBaseUrl,
            label = "Base URL",
            placeholder = if (provider == RemoteProvider.OLLAMA ||
                provider == RemoteProvider.VLLM || provider == RemoteProvider.CUSTOM
            ) "https://your-server.example/v1" else null,
            supporting = if (provider.protocol == ChatProtocol.ANTHROPIC) {
                "Anthropic's Messages API; /messages is appended."
            } else {
                "Anything OpenAI-compatible; /chat/completions is appended."
            },
        )

        if (provider == RemoteProvider.OLLAMA || provider == RemoteProvider.VLLM ||
            provider == RemoteProvider.CUSTOM
        ) {
            Text(
                "Enter your server's HTTPS address, including its API path (usually /v1). " +
                    "Remote HTTPS servers are supported. Localhost refers to this phone; " +
                    "plain HTTP works only with localhost or 127.0.0.1, including adb reverse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PersistedTextField(
            stored = model,
            onChange = settings::setRemoteModel,
            label = "Model",
            supporting = when (provider) {
                RemoteProvider.ANTHROPIC -> "claude-haiku-4-5-20251001, or claude-sonnet-5 / claude-opus-5."
                else -> null
            },
        )

        ApiKeyField(translation, provider)
    }
}

/** A closed choice among [RemoteProvider.entries], collapsed until tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceDropdown(selected: RemoteProvider, onSelect: (RemoteProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Service") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RemoteProvider.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        onSelect(entry)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(translation: TranslationController, provider: RemoteProvider) {
    // Held locally while it is being typed and handed to the keystore on save, so the
    // value never lands in DataStore or in a recomposition-surviving app-level holder.
    // Both remembers are keyed on `provider`: unkeyed, switching from one provider's
    // saved key to another's still shows the previous provider's masked preview until
    // the next full recomposition, which reads as this screen showing the wrong key.
    var entry by remember(provider) { mutableStateOf("") }
    var saved by remember(provider) { mutableStateOf(translation.secrets.preview(provider)) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            label = { Text(if (saved.isEmpty()) "API key" else "Replace API key") },
            placeholder = {
                Text(
                    saved.ifEmpty {
                        if (provider.needsKey) "Required" else "Leave empty for a server with no key"
                    },
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (saved.isEmpty()) {
                    "Stored encrypted on this device, one key per service above."
                } else {
                    "Saved as " + saved + ", encrypted on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (saved.isNotEmpty()) {
                TextButton(shape = RectangleShape,
                    onClick = {
                        translation.secrets.setKey(provider, "")
                        saved = ""
                        entry = ""
                    },
                ) { Text("Clear") }
            }
            TextButton(shape = RectangleShape,
                enabled = entry.isNotBlank(),
                onClick = {
                    translation.secrets.setKey(provider, entry)
                    saved = translation.secrets.preview(provider)
                    entry = ""
                },
            ) { Text("Save") }
        }
    }
}

/**
 * Tier 1's model picker: [LlmStore.MODELS]'s three presets, plus one pasted-link custom
 * model if the user has resolved one -- one at a time, chosen from a dropdown the same
 * way [ServiceDropdown] chooses a remote service, with that one model's card underneath
 * it rather than every model's card stacked at once.
 *
 * Section 6 of `docs/on-device-llm.md` lists what the card has to do, and every item is a
 * thing the user would rightly be annoyed to discover afterwards: state the size and that
 * it is a one-time download **before** the tap; warn if storage is short; warn if the
 * device does not have the RAM; and offer a delete that actually frees the file.
 *
 * The quality warning is not hedging either. Measured on the fixture pages, Qwen3-1.7B
 * produced clean English for some bubbles and mangled 俺俺 into "I'm I". A user choosing
 * between a free gigabyte and a text field deserves to know that before they spend the
 * gigabyte, whichever model they pick.
 *
 * Picking an installed model from the dropdown makes it the active one, the way the old
 * per-row radio button did. Picking one that is not installed only browses its card --
 * the active model does not change out from under a translation in progress just because
 * the user looked at a bigger one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalSettings(settings: ReaderSettings, translation: TranslationController) {
    val store = translation.llm
    val chosen by settings.localModel.collectAsState()
    val custom by settings.customModel.collectAsState()
    val progress = translation.modelInstall
    val installingId = translation.installingId
    var freed by remember { mutableStateOf(0L) }
    var expanded by remember { mutableStateOf(false) }
    var customFieldOpen by remember { mutableStateOf(false) }

    if (!LlamaBridgeAvailable) {
        Text(
            "This build carries no inference library for this device, so the on-device " +
                "translator cannot run.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    val allModels = LlmStore.MODELS + listOfNotNull(custom)
    val installedIds = allModels.filter { store.isInstalled(it) }.map { it.id }
    var viewedId by remember {
        mutableStateOf(chosen.ifEmpty { installedIds.firstOrNull() ?: LlmStore.MODELS.first().id })
    }
    val viewed = allModels.firstOrNull { it.id == viewedId } ?: LlmStore.MODELS.first()

    // A custom link that just finished resolving is the thing the user is waiting to
    // see -- switch the card over to it (still mid-download, most likely) rather than
    // leaving the now-stale paste field on screen. Only while that field is the reason
    // it changed: a custom model already resolved from a previous visit must not steal
    // the screen away from whatever preset the user is actually looking at.
    LaunchedEffect(custom) {
        val resolved = custom
        if (customFieldOpen && resolved != null) {
            customFieldOpen = false
            viewedId = resolved.id
        }
    }

    fun pick(model: LlmStore.Model) {
        customFieldOpen = false
        viewedId = model.id
        if (store.isInstalled(model)) settings.setLocalModel(model.id)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = if (customFieldOpen) "Custom model" else modelDropdownLabel(viewed),
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LlmStore.MODELS.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(modelDropdownLabel(model)) },
                        onClick = {
                            pick(model)
                            expanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(custom?.label ?: "Custom model") },
                    onClick = {
                        val resolved = custom
                        if (resolved != null) pick(resolved) else customFieldOpen = true
                        expanded = false
                    },
                )
            }
        }

        if (customFieldOpen) {
            CustomModelField(translation)
        } else {
            ModelDetail(
                model = viewed,
                store = store,
                translation = translation,
                progress = progress.takeIf { installingId == viewed.id },
                onFreed = { freed = it },
                onForget = custom?.takeIf { it.id == viewed.id }?.let { customModel ->
                    {
                        freed = translation.forgetCustomModel(customModel)
                        viewedId = LlmStore.MODELS.first().id
                    }
                },
            )
        }

        if (freed > 0) {
            Text(
                "Freed " + gigabytes(freed) + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun modelDropdownLabel(model: LlmStore.Model): String =
    model.label + if (model.preset != LlmStore.Preset.CUSTOM) "  ·  " + model.preset.label else ""

/**
 * The dropdown's currently chosen model: its card, and whichever action applies to it
 * right now.
 *
 * [progress] is only ever this model's own -- [LocalSettings] passes it in already
 * filtered by [TranslationController.installingId], since [TranslationController.modelInstall]
 * itself carries no model id and would otherwise light up whatever card happens to be on
 * screen for a download that belongs to a different one.
 */
@Composable
private fun ModelDetail(
    model: LlmStore.Model,
    store: LlmStore,
    translation: TranslationController,
    progress: LlmStore.Progress?,
    onFreed: (Long) -> Unit,
    onForget: (() -> Unit)? = null,
) {
    // A gigabyte the user may already have: downloaded once before, pulled off a
    // desktop, carried on a stick. `LlmStore.install` hashes it and matches it against
    // the registry, so picking the wrong file is refused rather than installed.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { translation.importModel(it, model.id) } }

    val installed = store.isInstalled(model)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            model.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            progress != null -> {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    gigabytes(progress.bytesDone) + " of " + gigabytes(progress.bytesTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            installed -> {
                Text(
                    "Installed, " + gigabytes(store.fileFor(model).length()) +
                        ". Translation runs on this phone with no network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(shape = RectangleShape,
                        onClick = {
                            if (onForget != null) onForget() else onFreed(translation.deleteModel(model))
                        },
                    ) { Text(if (onForget != null) "Forget" else "Delete model") }
                }
            }

            !store.verified(model) -> Text(
                "This model has no pinned checksum, so it will not be downloaded " +
                    "— an unverified gigabyte from the network is exactly what the " +
                    "checksum is for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            else -> {
                Text(
                    "One-time download of " + gigabytes(model.totalBytes) +
                        ", then no network and no account. Slower and less accurate than remote API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!store.fits(model)) {
                    Warning(
                        "This phone reports " + gigabytes(store.deviceRamBytes) +
                            " of RAM. This model needs about " +
                            gigabytes(model.minRamBytes) + " to run without the " +
                            "system killing the app mid-page.",
                    )
                }
                if (!store.hasRoom(model)) {
                    Warning(
                        "Only " + gigabytes(store.freeBytes) + " free. Leave a " +
                            "couple of gigabytes spare beyond the model itself.",
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(shape = RectangleShape, onClick = { translation.installModel(model) }) {
                        Text("Download " + gigabytes(model.totalBytes))
                    }
                    TextButton(shape = RectangleShape, onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text("Import from file")
                    }
                }
            }
        }
    }
}

/**
 * ToDo's "HF link to proper format": a Hugging Face `resolve` URL, verified by
 * [LlmStore.resolve] before a byte is downloaded -- see that function's doc for exactly
 * what the verification does and does not cover.
 */
@Composable
private fun CustomModelField(translation: TranslationController) {
    var url by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Custom model", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Insert a model URL from the Hugging Face page to install locally. " +
                "Only GGUF format models are supported. The model’s response format is " +
                "detected automatically, however some errors may occur: the model can be " +
                "unrecognized; the model’s answer may fall out of the token budget due to " +
                "unblockable reasoning. Only use this option if you’re absolutely sure " +
                "what you’re doing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Model URL") },
            placeholder = { Text("https://huggingface.co/.../resolve/main/model.gguf") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(shape = RectangleShape,
                enabled = url.isNotBlank(),
                onClick = {
                    val label = url.substringAfterLast('/').removeSuffix(".gguf")
                    translation.resolveCustomModel(url.trim(), label)
                    url = ""
                },
            ) { Text("Resolve & download") }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Whether the native library loaded, asked once. */
private val LlamaBridgeAvailable: Boolean
    get() = com.mangareader.translate.llamaAvailable()

private fun gigabytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format("%.2f GB", bytes / 1e9)
    else -> String.format("%.0f MB", bytes / 1e6)
}

/**
 * A text field over a DataStore-backed value, which is not as simple as binding the two.
 *
 * Handing `OutlinedTextField` the stored value directly and persisting on every keystroke
 * looks right and is not: the write is asynchronous, the flow re-emits on its own
 * schedule, and a stale emission arriving mid-word overwrites what the user has typed
 * since. It shows up as dropped and reordered characters when typing quickly -- which a
 * base URL invites, being long and punctuation-heavy.
 *
 * So the local state owns the text while the field has focus, and the stored value flows
 * in only when it does not. That keeps typing lossless without cutting off the paths that
 * legitimately change the value from outside: the preset dropdown, and the first read
 * from disk arriving after the default.
 */
@Composable
private fun PersistedTextField(
    stored: String,
    onChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
) {
    var text by remember { mutableStateOf(stored) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(stored, focused) {
        if (!focused && stored != text) text = stored
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = singleLine,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
    )
}

@Composable
private fun CacheRow(translation: TranslationController, grammar: GrammarController) {
    var dropped by remember { mutableStateOf(-1) }
    var notesDropped by remember { mutableStateOf(-1) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Translations are cached per page and model, so re-opening either never pays " +
                    "for it again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(shape = RectangleShape,
                onClick = {
                    translation.forgetCache { dropped = it }
                    grammar.forgetCache { notesDropped = it }
                },
            ) { Text("Forget") }
        }
        if (dropped >= 0) {
            Text(
                "Forgot " + dropped + " cached " + (if (dropped == 1) "page" else "pages") +
                    " and " + maxOf(notesDropped, 0) + " explained " +
                    (if (notesDropped == 1) "bubble" else "bubbles") + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
