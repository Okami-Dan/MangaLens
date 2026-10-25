package com.mangareader.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.R

/**
 * The design in `Design mockups/`, as a theme.
 *
 * Two things here are deliberate departures from what came before.
 *
 * The scheme is **not** dynamic colour and does not follow the system's dark mode. The
 * mockups are one committed light palette in which hue carries meaning -- orange is a
 * series, lilac is a volume, neutral is a page -- and letting the platform recolour that
 * would delete the signal. `darkTheme` is accepted and ignored so callers do not have to
 * change.
 *
 * Corners are square everywhere. That is the mockups' strongest single gesture and the
 * one the whole thing reads from; [MangaShapes] is the one place to change it.
 */
object MangaPalette {
    /** Page ground. Everything that is not a card sits on this. */
    val Paper = Color(0xFFF0F0EF)

    /** Type, rules, and the outline on every control. */
    val Ink = Color(0xFF16151A)

    /** Depth one: a series. Card bands, the primary action, the reader's bubbles. */
    val Orange = Color(0xFFFFA116)
    val OrangeTint = Color(0xFFFFD6BA)

    /** Depth two: a volume. [Violet] is the same hue at type weight. */
    val Lilac = Color(0xFFCAA8FD)
    val LilacTint = Color(0xFFE6DAFD)
    val Violet = Color(0xFFB073FC)

    /** Depth three: a page. No hue -- the photographs carry the grid. */
    val Silver = Color(0xFFD8D8D8)
    val SilverTint = Color(0xFFE7E7E6)

    /** Secondary type, and the sheet's grab handle. */
    val Dim = Color(0xFF6B6960)

    /** Tertiary type: counts, part-of-speech labels, byte sizes. */
    val Faint = Color(0xFF96938A)

    /** Card fill, row fill, field fill -- one step brighter than the paper. */
    val White = Color(0xFFFFFFFF)

    /**
     * The mockups have no error colour, so this is chosen to sit with them: warm enough
     * to belong beside the orange, saturated enough to read as a warning next to it.
     */
    val Alarm = Color(0xFFD93F2B)
    val AlarmTint = Color(0xFFFBDDD7)

    /** Ink at reading weight over a photograph, and the ground it is drawn on. */
    val Plate = Color(0xFF0C0B0A)

    /**
     * The dark end of each ramp, for coloured text at label size.
     *
     * Section 3.3 tier 4 colours part-of-speech labels by word class, and the brand tones
     * cannot carry 10sp type: [Orange] measures 1.77:1 on [Paper] and [Violet] 2.75:1,
     * both far below the 4.5:1 that small text needs. These are the same three hues taken
     * dark enough to read, so tier 4 extends the palette rather than adding a fourth
     * colour to it. Every value is measured against [Paper].
     *
     * They are text colours only. Nothing here may fill a surface -- fills belong to
     * [Depth], which is how the user knows series from volume from page, and a fourth
     * meaning on a fill would delete that signal.
     */
    val OrangeInk = Color(0xFF6B4100)      // 7.73:1 -- verbs
    val OrangeInkSoft = Color(0xFF9A5F00)  // 4.59:1 -- i-adjectives, auxiliaries
    val VioletInk = Color(0xFF6F0CB7)      // 7.63:1 -- nouns, and the note gloss
    val VioletInkSoft = Color(0xFF9B28FA)  // 4.52:1 -- prenominals
    val NeutralInk = Color(0xFF41403E)     // 9.08:1 -- adverbs
    val NeutralInkSoft = Color(0xFF63625F) // 5.35:1 -- particles, conjunctions
}

/**
 * Rubik, bundled rather than downloaded: the mockups' Type3 outlines carry no font name,
 * and this is the closest widely available match to the geometric sans they were drawn
 * in. Japanese has no Rubik coverage, so the platform's CJK font takes those runs by the
 * usual fallback -- which is what the mockups show too.
 */
private val Rubik = FontFamily(
    Font(R.font.rubik_regular, FontWeight.Normal),
    Font(R.font.rubik_medium, FontWeight.Medium),
    Font(R.font.rubik_semibold, FontWeight.SemiBold),
)

/**
 * The type scale, measured off the PDFs at their 402 pt frame width -- which is close
 * enough to a phone's dp that the numbers transfer directly.
 *
 * Only the roles the app actually uses are overridden; the rest inherit Material's
 * defaults with the family swapped, so nothing renders in Roboto by accident.
 */
private val MangaTypography: Typography = Typography().let { base ->
    fun TextStyle.rubik(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        tracking: Float = 0f,
        line: Int = 0,
    ) = copy(
        fontFamily = Rubik,
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = tracking.sp,
        lineHeight = if (line == 0) (size * 1.35f).sp else line.sp,
    )

    Typography(
        // Screen titles: "My library", "Fullmetal alchemist".
        headlineSmall = base.headlineSmall.rubik(21, FontWeight.SemiBold, -0.3f, 23),
        // The volume line under a series name, and the reader's page label.
        titleLarge = base.titleLarge.rubik(20, FontWeight.SemiBold, -0.3f, 22),
        // Card names, option names, the action buttons.
        titleMedium = base.titleMedium.rubik(15, FontWeight.SemiBold, -0.15f, 19),
        titleSmall = base.titleSmall.rubik(14, FontWeight.Medium, -0.1f, 18),
        bodyLarge = base.bodyLarge.rubik(15, FontWeight.Normal, 0f, 22),
        bodyMedium = base.bodyMedium.rubik(13, FontWeight.Normal, 0f, 18),
        // Card counts, option descriptions.
        bodySmall = base.bodySmall.rubik(11, FontWeight.Normal, 0f, 15),
        labelLarge = base.labelLarge.rubik(15, FontWeight.Medium, -0.1f, 18),
        // The numbered section headers in the bubble sheet.
        labelMedium = base.labelMedium.rubik(11, FontWeight.Normal, 0.1f, 14),
        // Part of speech, byte counts, "#3 of 7".
        labelSmall = base.labelSmall.rubik(10, FontWeight.Normal, 0.4f, 13),
        displaySmall = base.displaySmall.rubik(30, FontWeight.SemiBold, -0.5f, 34),
    )
}

/** Square, everywhere. The single switch for the whole app's corner treatment. */
val MangaShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

private val MangaColors = lightColorScheme(
    primary = MangaPalette.Orange,
    onPrimary = Color(0xFF231A08),
    primaryContainer = MangaPalette.OrangeTint,
    onPrimaryContainer = Color(0xFF7A4B08),

    secondary = MangaPalette.Violet,
    onSecondary = MangaPalette.White,
    secondaryContainer = MangaPalette.LilacTint,
    onSecondaryContainer = Color(0xFF5B2E9E),

    tertiary = MangaPalette.Violet,
    onTertiary = MangaPalette.White,
    tertiaryContainer = MangaPalette.LilacTint,
    onTertiaryContainer = Color(0xFF5B2E9E),

    background = MangaPalette.Paper,
    onBackground = MangaPalette.Ink,
    surface = MangaPalette.Paper,
    onSurface = MangaPalette.Ink,
    surfaceVariant = MangaPalette.SilverTint,
    onSurfaceVariant = MangaPalette.Dim,
    surfaceContainer = MangaPalette.White,
    surfaceContainerHigh = MangaPalette.White,
    surfaceContainerHighest = MangaPalette.SilverTint,
    surfaceContainerLow = MangaPalette.Paper,
    surfaceContainerLowest = MangaPalette.White,

    outline = MangaPalette.Silver,
    outlineVariant = MangaPalette.Silver,

    error = MangaPalette.Alarm,
    onError = MangaPalette.White,
    errorContainer = MangaPalette.AlarmTint,
    onErrorContainer = Color(0xFF7A1B0F),

    scrim = Color(0xFF16151A),
)

@Composable
fun MangaReaderTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MangaColors,
        typography = MangaTypography,
        shapes = MangaShapes,
        content = content,
    )
}
