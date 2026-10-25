package com.mangareader.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.mangareader.model.Box
import com.mangareader.model.PageKind
import com.mangareader.model.PipelineStage
import com.mangareader.model.PointF
import com.mangareader.model.Region
import com.mangareader.model.RegionKind

/**
 * Section 6's Room entities.
 *
 * Section 6 names one container, `Book`. M5 splits it in two, because a shelf of physical
 * manga is two levels deep and flattening it loses the level the user actually browses by:
 * [SeriesEntity] is the work, [VolumeEntity] is one tankoubon of it, and a page belongs to
 * a volume. They are folders and nothing more -- no pipeline stage reads them, and
 * everything downstream of a page is unchanged.
 *
 * [VolumeEntity] and [PageEntity] are what M1 writes; [RegionEntity] is M2's, written by
 * `PageAnalyzer` once detect -> order -> OCR has run. [TokenEntity] and [SavedWordEntity]
 * are M3's and live in `Analysis.kt`.
 *
 * Note the `id` on a page is section 6's content hash of the source image, not an
 * autoincrement, so re-importing the same photo reuses the row -- the same rule
 * `harness/pipeline/runner.py::_content_id` follows. A series or volume id is a plain
 * UUID: there is nothing about a folder to hash.
 */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: String,
    /** The user's own name for the work. The only field the creation dialog requires. */
    val title: String,
    /** Official Japanese title; "" when the user did not supply one. */
    val japaneseTitle: String,
    /**
     * Where the series is written up. Empty until the user supplies it, and display-only
     * for now -- the knowledge base and translation parsing that will read it are the
     * next item on the list, and this is the field they will read.
     */
    val wikiUrl: String,
    /** App-private JPEG under `files/covers`, or null to fall back to a page thumbnail. */
    val coverPath: String?,
    val createdAt: Long,
)

/**
 * One volume of a [SeriesEntity].
 *
 * Carries a number rather than a title: a tankoubon is identified by its number, and a
 * column the creation dialog never fills is a column that can only ever hold one value.
 * The display name is derived -- see `VolumeEntity.label`.
 */
@Entity(
    tableName = "volumes",
    indices = [Index("seriesId")],
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VolumeEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val number: Int,
    val coverPath: String?,
    val createdAt: Long,
) {
    val label: String get() = "Volume " + number
}

@Entity(
    tableName = "pages",
    indices = [Index("volumeId"), Index("readingIndex")],
    foreignKeys = [
        ForeignKey(
            entity = VolumeEntity::class,
            parentColumns = ["id"],
            childColumns = ["volumeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val volumeId: String,
    val readingIndex: Int,
    /** Full-resolution OCR source while pending; reader image after processing. */
    val imagePath: String,
    /** Legacy camera original; cleared by storage cleanup. */
    val rawImagePath: String?,
    /** Downscaled for the library grid. */
    val thumbnailPath: String?,
    val width: Int,
    val height: Int,
    val stage: PipelineStage,
    val kind: PageKind,
    val kindAuto: Boolean,
    val colourFraction: Float,
    val rectified: Boolean,
    /** 4 corners, normalized against the raw image; null when no warp ran. */
    @ColumnInfo(name = "quad") val quad: List<PointF>?,
    val title: String,
    val capturedAt: Long,
    val notes: String,
)


/**
 * Section 6's Region entity, whole as of M4.
 *
 * The tokens stage 6 derives are a child table ([TokenEntity]); the SFX match is one
 * nullable column here, because it is at most one row per region.
 *
 * They are no longer mutually exclusive. Section 2.6 used to make a region *either*
 * dialogue *or* onomatopoeia, and a region on the SFX side got no tokens at all. It now
 * gets both: the tokens are what `SfxClassifier` weighed to reach its verdict, and they
 * are also the reading aid for a katakana string a learner cannot sound out.
 *
 * The key is (pageId, regionId), not an autoincrement. The detector numbers its regions
 * 1..n per page after NMS (`Detector.detect`), the harness serializes them under those
 * ids, and `tools/compare_runs.py` diffs on them -- so a page re-analysed twice has to
 * produce the same ids, and a surrogate key would quietly break that correspondence.
 */
@Entity(
    tableName = "regions",
    primaryKeys = ["pageId", "regionId"],
    indices = [Index("pageId")],
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RegionEntity(
    val pageId: String,
    val regionId: Long,
    val kind: RegionKind,
    @ColumnInfo(name = "box") val box: Box,
    val score: Float,
    val readingIndex: Int,
    val panelIndex: Int,
    /** Containing bubble, if any -- a wider tap target now, M5's inpainting later. */
    @ColumnInfo(name = "bubbleBox") val bubbleBox: Box?,
    val ocrText: String,
    /** Section 6: a user correction wins over [ocrText]. Null until the user edits. */
    val ocrTextEdited: String?,
    val ocrConfidence: Float?,
    /** Section 3.4's dictionary hit for a free-floating region; [Serial]-encoded. */
    val sfx: String? = null,
    /**
     * Stage 7's English, or null where the page has not been translated *or* the model
     * declined to guess at this bubble. The two look the same here and are told apart by
     * [translatorId]: a region a translator has been over carries its id whether or not
     * it produced text, so "not translated yet" and "the model could not read it" are
     * different states in the sheet.
     */
    val translation: String? = null,
    /** Which tier and model produced [translation] -- section 6. */
    val translatorId: String? = null,
    @ColumnInfo(defaultValue = "'[]'") val lexicalUnits: String = "[]",
    @ColumnInfo(defaultValue = "0") val analysisVersion: Int = 0,
    val translationKey: String? = null,
) {
    fun toRegion() = Region(
        id = regionId,
        kind = kind,
        box = box,
        score = score,
        readingIndex = readingIndex,
        bubbleBox = bubbleBox,
        panelIndex = panelIndex,
        ocrText = ocrText,
        ocrTextEdited = ocrTextEdited,
        ocrConfidence = ocrConfidence,
        sfx = Serial.decodeSfx(sfx),
        translation = translation,
        translatorId = translatorId,
        lexicalUnits = LexicalSerial.decode(lexicalUnits),
        analysisVersion = analysisVersion,
        translationKey = translationKey,
    )

    /** With stage 6's output folded back in, for the reader and for the fixture tests. */
    fun toRegion(tokens: List<TokenEntity>) =
        toRegion().copy(tokens = tokens.sortedBy { it.position }.map { it.toToken() })

    companion object {
        fun of(pageId: String, region: Region) = RegionEntity(
            pageId = pageId,
            regionId = region.id,
            kind = region.kind,
            box = region.box,
            score = region.score,
            readingIndex = region.readingIndex,
            panelIndex = region.panelIndex,
            bubbleBox = region.bubbleBox,
            ocrText = region.ocrText,
            ocrTextEdited = region.ocrTextEdited,
            ocrConfidence = region.ocrConfidence,
            sfx = Serial.encodeSfx(region.sfx),
            translation = region.translation,
            translatorId = region.translatorId,
            lexicalUnits = LexicalSerial.encode(region.lexicalUnits),
            analysisVersion = region.analysisVersion,
            translationKey = region.translationKey,
        )
    }
}

/**
 * The quad and the boxes are the only non-primitive columns. Stored as eight semicolon-separated
 * floats rather than JSON: it is fixed-length, never queried, and this keeps a JSON
 * dependency out of a module that otherwise needs none. A [Box] goes the same way, for
 * the same reasons -- four floats, only ever read back whole.
 */
class Converters {

    @TypeConverter
    fun quadToString(quad: List<PointF>?): String? =
        quad?.joinToString(";") { "${it.x},${it.y}" }

    @TypeConverter
    fun stringToQuad(value: String?): List<PointF>? = value
        ?.takeIf { it.isNotBlank() }
        ?.split(";")
        ?.map { point ->
            val (x, y) = point.split(",")
            PointF(x.toFloat(), y.toFloat())
        }

    @TypeConverter
    fun boxToString(box: Box?): String? =
        box?.let { "${it.left},${it.top},${it.right},${it.bottom}" }

    @TypeConverter
    fun stringToBox(value: String?): Box? = value
        ?.takeIf { it.isNotBlank() }
        ?.split(",")
        ?.map(String::toFloat)
        ?.let { Box(it[0], it[1], it[2], it[3]) }

    @TypeConverter
    fun regionKindToString(kind: RegionKind): String = kind.name

    @TypeConverter
    fun stringToRegionKind(value: String): RegionKind = RegionKind.valueOf(value)

    @TypeConverter
    fun stageToString(stage: PipelineStage): String = stage.name

    @TypeConverter
    fun stringToStage(value: String): PipelineStage = PipelineStage.valueOf(value)

    @TypeConverter
    fun kindToString(kind: PageKind): String = kind.name

    @TypeConverter
    fun stringToKind(value: String): PageKind = PageKind.valueOf(value)
}
