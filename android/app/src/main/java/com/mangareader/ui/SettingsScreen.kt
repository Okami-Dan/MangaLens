package com.mangareader.ui

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.mangareader.pipeline.Backup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Settings tab.
 *
 * Section 2.3 is the only [DECIDED] item this screen can get wrong, so the defaults are
 * the plan's and the copy says which is which: furigana is the primary reading aid and
 * is on; romaji is a toggle and is off.
 *
 * Section 5's translator tier is the other decision this screen owns, and the one with
 * consequences outside the phone: tier 2 sends text to a third party and tier 1 costs a
 * gigabyte. [TranslationCard] carries it, and says so before the user commits.
 *
 * The rest is the state of the two asset stores. They are deliberately shown
 * differently, because they *are* different -- the ONNX graphs are a download or a
 * sideload and can fail; JMdict ships inside the APK and can only be unpacked or not.
 *
 * [StorageCard] is the fourth thing, and the only one that says something about data the
 * user cannot get back: where the library is kept, what that survives, and how to put a
 * copy somewhere an uninstall cannot reach.
 */
@Composable
fun SettingsScreen(
    analysis: AnalysisController,
    translation: TranslationController,
    grammar: GrammarController,
    storage: StorageController,
    settings: ReaderSettings,
    furigana: Boolean,
    romaji: Boolean,
    onAbout: () -> Unit,
    onTutorial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxSize()
            .scrollbar(scroll, Depth.SERIES.thumb)
            .verticalScroll(scroll)
            .padding(start = ScreenMargin, end = ScreenMargin, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Reading aids")
        SettingSwitch(
            title = "Furigana",
            detail = "Kana readings above the kanji in the bubble sheet.",
            checked = furigana,
            onChange = settings::setFurigana,
        )
        SettingSwitch(
            title = "Romaji",
            detail = "Hepburn under the original. Off by default.",
            checked = romaji,
            onChange = settings::setRomaji,
        )

        SectionLabel("Storage")
        StorageCard(storage)

        SectionLabel("Translation")
        TranslationCard(settings, translation, grammar)

        SectionLabel("Models")
        ModelsCard(analysis)
        DictionaryCard(analysis)

        Text(
            "Dictionary data is JMdict/EDICT, © the Electronic Dictionary Research and " +
                "Development Group, used under CC BY-SA 4.0.",
            style = MaterialTheme.typography.labelSmall,
            color = MangaPalette.Faint,
            modifier = Modifier.padding(top = 8.dp),
        )

        SectionLabel("Manga Lens")
        Button(shape = RectangleShape, onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
            Text("About app")
        }
        Button(shape = RectangleShape, onClick = onTutorial, modifier = Modifier.fillMaxWidth()) {
            Text("Tutorial")
        }
    }
}

/** A section heading: small, spaced, grey -- the mockups' own label treatment. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
        color = MangaPalette.Dim,
        modifier = Modifier.padding(top = 14.dp, bottom = 1.dp),
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MangaPalette.White)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MangaPalette.Dim,
                modifier = Modifier.padding(top = 3.dp, end = 12.dp),
            )
        }
        InkSwitch(checked)
    }
}

/**
 * The mockups' switch: a heavy ink outline with a square knob that fills with the accent
 * when it is on. Material's own switch is a rounded pill and cannot be squared, which
 * would make it the one soft-cornered thing in the app.
 *
 * Presentation only -- the row above owns the `toggleable` semantics, so the whole row is
 * the target and a screen reader announces it once rather than twice.
 */
@Composable
private fun InkSwitch(checked: Boolean) {
    val offset by animateDpAsState(if (checked) 19.dp else 0.dp, label = "knob")
    Box(
        Modifier
            .size(width = 46.dp, height = 27.dp)
            .background(if (checked) MangaPalette.Orange else MangaPalette.Paper)
            .border(2.dp, if (checked) MangaPalette.Orange else MangaPalette.Ink),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = 2.dp)
                .offset(x = offset)
                .size(19.dp)
                .background(if (checked) MangaPalette.White else MangaPalette.Ink),
        )
    }
}

/** JMdict's own card: it ships in the APK, so it never downloads and never fails. */
@Composable
private fun DictionaryCard(analysis: AnalysisController) {
    val progress = analysis.dictionaryInstall
    var freed by remember { mutableStateOf(0L) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                progress != null -> {
                    Text("Unpacking dictionary", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        megabytes(progress.bytesDone) + " of " + megabytes(progress.bytesTotal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                !analysis.dictionaryPackaged -> {
                    Text("Dictionary missing", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "This build was assembled without var/assets/jmdict-mobile.sqlite. " +
                            "Readings and furigana still work; meanings do not.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                analysis.dictionaryReady -> {
                    Text("Dictionary installed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "JMdict, " + megabytes(analysis.dictionaryBytes) +
                            " unpacked from the app. Word lookup is fully offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(shape = RectangleShape,
                            onClick = { freed = analysis.freeDictionary() },
                        ) { Text("Free it") }
                    }
                    if (freed > 0) {
                        Text(
                            "Freed " + megabytes(freed) + ". It unpacks again on the next " +
                                "page you analyse.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    Text("Dictionary not unpacked", style = MaterialTheme.typography.titleSmall)
                    Text(
                        megabytes(analysis.dictionaryBytes) + " ships inside the app and " +
                            "has to be copied out before SQLite can open it. The next page " +
                            "you analyse does it, or:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(shape = RectangleShape, onClick = { analysis.ensureDictionary() }) { Text("Unpack now") }
                }
            }
        }
    }
}

/**
 * Where the library lives, what that does and does not survive, and how to keep a copy.
 *
 * Worth a card of its own rather than a line of small print. Every other asset on this
 * screen can be fetched again; the pages cannot, and until M6 they sat in app-private
 * storage that Android deletes on uninstall and on *Clear data* -- so a user who
 * reinstalled the app to pick up a new build lost their shelf, silently, and found out
 * afterwards.
 *
 * This card used to answer that with "reinstalling the app no longer deletes your pages",
 * which turned out not to be true: `Android/media/<package>` goes with the package on at
 * least some devices (see [Vault]). Overstating a safety is worse than not having it,
 * because it is precisely the sentence that stops someone making a backup. So the card now
 * says what the vault actually covers -- *Clear data* -- and puts the export next to it.
 */
@Composable
private fun StorageCard(storage: StorageController) {
    val progress = storage.adoption

    LaunchedEffect(Unit) { storage.refresh() }

    // `CreateDocument` rather than a path of the app's choosing: the whole value of the
    // export is that it lands somewhere the package manager has no claim on, and only the
    // user can say where that is.
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { storage.exportTo(it) } }

    // `*/*`, for the reason ModelsUi gives: a provider that reports the backup as
    // octet-stream would be filtered out of its own folder, and the user would be looking
    // at an empty picker with no way to tell why. The manifest check inside `Backup`
    // rejects a wrong file with a sentence, which is a better filter than a MIME type.
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { storage.importFrom(it) } }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                progress != null -> {
                    Text("Moving the library", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        megabytes(progress.bytesDone) + " of " + megabytes(progress.bytesTotal) +
                            "  ·  file " + (progress.done + 1) + " of " + progress.total,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                storage.protected -> {
                    Text(
                        "Library kept outside the app",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        megabytes(storage.bytes) + " in shared device storage, one copy — " +
                            "clearing the app's data no longer deletes your pages, and " +
                            "reinstalling over the top keeps them. Uninstalling still " +
                            "takes them, so keep a backup file as well.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        storage.location,
                        style = MaterialTheme.typography.labelSmall,
                        color = MangaPalette.Faint,
                    )
                }

                else -> {
                    Text("Library in app storage", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "This device reports no shared storage, so the library has to live " +
                            "inside the app — where uninstalling it, or clearing its data, " +
                            "takes the pages too. Back up to a file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (storage.adopted > 0) {
                Text(
                    "Moved " + storage.adopted + " files out of app storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            storage.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (storage.protected && progress == null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // The images are already out there; this is the index that names
                        // them, which is copied out on every stop rather than every write.
                        // Deliberately not called a backup: it goes on the same volume as
                        // the thing it describes.
                        if (storage.backedUpAt > 0) {
                            "Index saved."
                        } else {
                            "The index is saved whenever you leave the app."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(shape = RectangleShape, onClick = { storage.backUpNow() }) { Text("Save now") }
                }
            }

            BackupSection(storage, exporter, importer)
        }
    }
}

/**
 * The backup half of the storage card: one file out, one file back.
 *
 * Split from [StorageCard] only for length -- it is the same column, and it is placed last
 * because it is the answer to what the paragraphs above it admit.
 */
@Composable
private fun BackupSection(
    storage: StorageController,
    exporter: ActivityResultLauncher<String>,
    importer: ActivityResultLauncher<Array<String>>,
) {
    val transfer = storage.transfer
    val lastBackup by storage.lastBackupAt.collectAsState()

    if (transfer != null) {
        LinearProgressIndicator(
            progress = { transfer.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            megabytes(transfer.bytesDone) + " of " + megabytes(transfer.bytesTotal),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        // The one sentence on this screen that has to be exactly true, because the last
        // version of it was not and that is what left the library unprotected.
        when {
            lastBackup > 0 -> "Backed up on " + date(lastBackup) + "."
            else -> "No backup yet. A backup is the only copy an uninstall cannot reach."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (lastBackup > 0) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    storage.exported?.let {
        Text(
            "Saved " + it.files + " files, " + megabytes(it.bytes) + ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Button(shape = RectangleShape,
        onClick = { exporter.launch(Backup.suggestedName()) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Back up to a file") }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Restoring replaces the library on this device and restarts the app.",
            style = MaterialTheme.typography.labelSmall,
            color = MangaPalette.Faint,
            modifier = Modifier.weight(1f),
        )
        TextButton(shape = RectangleShape, onClick = { importer.launch(arrayOf("*/*")) }) { Text("Restore") }
    }
}

/** `yyyy-MM-dd HH:mm`, for the one timestamp this screen shows. */
private fun date(at: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))
