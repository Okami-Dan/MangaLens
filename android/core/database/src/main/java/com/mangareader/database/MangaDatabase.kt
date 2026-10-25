package com.mangareader.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mangareader.model.PageKind
import com.mangareader.model.PipelineStage
import com.mangareader.model.RegionKind
import kotlinx.coroutines.flow.Flow

/**
 * The top folder. IGNORE on insert for the same reason [VolumeDao] has it: `PageStore`
 * inserts the unsorted parent before every page so the foreign key holds, and that must
 * not overwrite a row the library screen already created.
 */
@Dao
interface SeriesDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(series: SeriesEntity)

    @Query("SELECT * FROM series ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id")
    fun observe(id: String): Flow<SeriesEntity?>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun byId(id: String): SeriesEntity?

    /** The creation dialog reopened on an existing row. The cover goes separately,
     *  because leaving it unpicked means "keep the one you have", not "clear it". */
    @Query(
        """
        UPDATE series SET title = :title, japaneseTitle = :japaneseTitle, wikiUrl = :wikiUrl
        WHERE id = :id
        """
    )
    suspend fun edit(id: String, title: String, japaneseTitle: String, wikiUrl: String)

    @Query("UPDATE series SET coverPath = :path WHERE id = :id")
    suspend fun setCover(id: String, path: String?)

    @Query("DELETE FROM series WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM series")
    suspend fun count(): Int
}

@Dao
interface VolumeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(volume: VolumeEntity)

    @Query("SELECT * FROM volumes WHERE seriesId = :seriesId ORDER BY number")
    fun observeSeries(seriesId: String): Flow<List<VolumeEntity>>

    @Query("SELECT * FROM volumes WHERE seriesId = :seriesId ORDER BY number")
    suspend fun bySeries(seriesId: String): List<VolumeEntity>

    @Query("SELECT * FROM volumes WHERE id = :id")
    fun observe(id: String): Flow<VolumeEntity?>

    @Query("SELECT * FROM volumes WHERE id = :id")
    suspend fun byId(id: String): VolumeEntity?

    /** Seeds the new-volume dialog. Same idiom as [PageDao.nextReadingIndex], except
     *  volumes are 1-based because that is how they are printed on a spine. */
    @Query("SELECT COALESCE(MAX(number), 0) + 1 FROM volumes WHERE seriesId = :seriesId")
    suspend fun nextNumber(seriesId: String): Int

    @Query("UPDATE volumes SET number = :number WHERE id = :id")
    suspend fun setNumber(id: String, number: Int)

    @Query("UPDATE volumes SET coverPath = :path WHERE id = :id")
    suspend fun setCover(id: String, path: String?)

    /**
     * Moves a volume to another shelf. Its number comes with it: a tankoubon is
     * identified by the number on its spine, and renumbering it to fit the destination
     * would rename the thing the user is holding. Two volume 3s on one shelf are possible
     * and are the user's business -- nothing keys on the number, and [nextNumber] takes
     * the maximum, so the next volume added still lands past both.
     */
    @Query("UPDATE volumes SET seriesId = :seriesId WHERE id = :id")
    suspend fun setSeries(id: String, seriesId: String)

    @Query("DELETE FROM volumes WHERE id = :id")
    suspend fun delete(id: String)

    /** Every volume, for the series grid's cover fallback and volume counts. Cheaper
     *  than one query per card, and there are never many rows. */
    @Query("SELECT * FROM volumes ORDER BY seriesId, number")
    fun observeAll(): Flow<List<VolumeEntity>>
}

@Dao
interface PageDao {
    @Query("SELECT s.id AS seriesId, s.title AS seriesTitle, s.japaneseTitle AS japaneseTitle " +
        "FROM pages p JOIN volumes v ON v.id=p.volumeId JOIN series s ON s.id=v.seriesId WHERE p.id=:id")
    fun observeContext(id: String): Flow<SeriesContextRow?>
    @Query("SELECT * FROM pages ORDER BY capturedAt")
    suspend fun all(): List<PageEntity>

    @Query("UPDATE pages SET imagePath = :imagePath, rawImagePath = NULL WHERE id = :id")
    suspend fun retainReaderImage(id: String, imagePath: String)

    @Query("SELECT imagePath FROM pages UNION SELECT thumbnailPath FROM pages WHERE thumbnailPath IS NOT NULL UNION SELECT rawImagePath FROM pages WHERE rawImagePath IS NOT NULL UNION SELECT coverPath FROM series WHERE coverPath IS NOT NULL UNION SELECT coverPath FROM volumes WHERE coverPath IS NOT NULL")
    suspend fun referencedImages(): List<String>

    /** REPLACE, because a page id is a content hash: re-capturing the same photo
     *  should update the row rather than fail or duplicate it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity)

    @Query("SELECT * FROM pages ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE volumeId = :volumeId ORDER BY readingIndex")
    fun observeVolume(volumeId: String): Flow<List<PageEntity>>

    /** For the delete path: `PageStore` needs the image paths before the rows cascade. */
    @Query("SELECT * FROM pages WHERE volumeId = :volumeId ORDER BY readingIndex")
    suspend fun byVolume(volumeId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun byId(id: String): PageEntity?

    @Query("SELECT COALESCE(MAX(readingIndex), -1) + 1 FROM pages WHERE volumeId = :volumeId")
    suspend fun nextReadingIndex(volumeId: String): Int

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Moves a page into another volume, at the end of it -- the caller passes
     * [nextReadingIndex] of the destination. Both columns in one statement so a page is
     * never briefly in the new volume at an index the new volume already uses.
     */
    @Query("UPDATE pages SET volumeId = :volumeId, readingIndex = :readingIndex WHERE id = :id")
    suspend fun setVolume(id: String, volumeId: String, readingIndex: Int)

    /**
     * Pulls the pages after [readingIndex] down one, after the page that held it left the
     * volume -- deleted, or moved somewhere else.
     *
     * Without it, removing page 2 of 5 leaves indices 0, 1, 3, 4 and a grid captioned
     * "Page 1, Page 2, Page 4, Page 5", since the caption is the index plus one. The
     * reader's order is this column, so the gap is not cosmetic either: it is a page
     * number that refers to nothing.
     */
    @Query(
        """
        UPDATE pages SET readingIndex = readingIndex - 1
        WHERE volumeId = :volumeId AND readingIndex > :readingIndex
        """
    )
    suspend fun closeGap(volumeId: String, readingIndex: Int)

    @Query("SELECT COUNT(*) FROM pages")
    suspend fun count(): Int

    @Query("SELECT * FROM pages WHERE id = :id")
    fun observe(id: String): Flow<PageEntity?>

    /**
     * What stage 3 learns about the page as a whole. `kindAuto` guards the user's own
     * cover/interior override: re-analysing must not silently undo it.
     */
    @Query(
        """
        UPDATE pages SET stage = :stage,
                         kind = CASE WHEN kindAuto THEN :kind ELSE kind END,
                         colourFraction = :colourFraction
        WHERE id = :id
        """
    )
    suspend fun markAnalysed(
        id: String,
        stage: PipelineStage,
        kind: PageKind,
        colourFraction: Float,
    )

    /**
     * Stage 7's only change to the page row. Separate from [markAnalysed] because
     * translation learns nothing about the page itself -- not its kind, not its colour --
     * and re-asserting those here would let a translation quietly re-run stage 3's
     * classification.
     */
    @Query("UPDATE pages SET stage = :stage WHERE id = :id")
    suspend fun markStage(id: String, stage: PipelineStage)
}

data class SeriesContextRow(val seriesId: String, val seriesTitle: String, val japaneseTitle: String)

@Dao
interface RegionDao {

    @Query("UPDATE regions SET lexicalUnits=:words, analysisVersion=:version WHERE pageId=:pageId AND regionId=:regionId")
    suspend fun setAnalysis(pageId: String, regionId: Long, words: String, version: Int)

    /** A re-run replaces the page's regions wholesale; [PageAnalyzer] clears first. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(regions: List<RegionEntity>)

    @Query("SELECT * FROM regions WHERE pageId = :pageId ORDER BY readingIndex")
    fun observePage(pageId: String): Flow<List<RegionEntity>>

    @Query("SELECT * FROM regions WHERE pageId = :pageId ORDER BY readingIndex")
    suspend fun byPage(pageId: String): List<RegionEntity>

    @Query("UPDATE regions SET ocrTextEdited = :text WHERE pageId = :pageId AND regionId = :regionId")
    suspend fun editText(pageId: String, regionId: Long, text: String?)

    @Query("UPDATE regions SET sfx = :sfx WHERE pageId = :pageId AND regionId = :regionId")
    suspend fun setSfx(pageId: String, regionId: Long, sfx: String?)

    /**
     * Stage 6's classification for one region.
     *
     * Needed because a Tier 1 text correction can change the verdict -- fixing OCR
     * garbage into a real sentence has to be able to take a region back out of
     * `TEXT_SFX`, and vice versa. Writing only [setSfx] after a re-analysis would leave
     * the kind stale and the sheet showing the wrong tiers. No schema change: `kind` is
     * an existing column and `Converters` stores the enum by name.
     */
    @Query("UPDATE regions SET kind = :kind WHERE pageId = :pageId AND regionId = :regionId")
    suspend fun setKind(pageId: String, regionId: Long, kind: RegionKind)

    /**
     * Stage 7's result for one region. [translatorId] is set even when [text] is null,
     * which is what tells "the model declined this bubble" from "nothing has translated
     * this page yet" -- see the note on `RegionEntity.translation`.
     */
    @Query(
        """
        UPDATE regions SET translation = :text, translatorId = :translatorId, translationKey = :requestKey
        WHERE pageId = :pageId AND regionId = :regionId
        """
    )
    suspend fun setTranslation(
        pageId: String,
        regionId: Long,
        text: String?,
        translatorId: String?,
        requestKey: String? = null,
    )

    /**
     * Drop every translation on a page. Used when the user switches translator: leaving
     * one model's English next to another's on the same page would be indefensible in
     * the sheet, where the tier-3 footnote names the model that produced the line.
     */
    @Query("UPDATE regions SET translation = NULL, translatorId = NULL, translationKey = NULL WHERE pageId = :pageId")
    suspend fun clearTranslations(pageId: String)

    @Query("SELECT * FROM regions WHERE pageId = :pageId AND regionId = :regionId")
    suspend fun byId(pageId: String, regionId: Long): RegionEntity?

    /**
     * How many bubbles each page has, for the page grid's caption.
     *
     * One grouped query rather than a flow per card: a volume can hold a couple of
     * hundred pages, and the grid needs the number for all of them at once.
     */
    @Query("SELECT pageId, COUNT(*) AS regions FROM regions GROUP BY pageId")
    fun observeCounts(): Flow<List<RegionCount>>

    @Query("DELETE FROM regions WHERE pageId = :pageId")
    suspend fun clearPage(pageId: String)
}

/** One row of [RegionDao.observeCounts]. */
data class RegionCount(val pageId: String, val regions: Int)

/**
 * Stage 6's tokens. Written per page by `PageAnalyzer` and per region by the Tier 1
 * edit, which is why [clearRegion] exists alongside [clearPage] -- correcting one
 * bubble must not re-tokenize the other eleven.
 */
@Dao
interface TokenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tokens: List<TokenEntity>)

    @Query("SELECT * FROM tokens WHERE pageId = :pageId AND regionId = :regionId ORDER BY position")
    suspend fun byRegion(pageId: String, regionId: Long): List<TokenEntity>

    @Query("SELECT * FROM tokens WHERE pageId = :pageId ORDER BY regionId, position")
    fun observePage(pageId: String): Flow<List<TokenEntity>>

    @Query("SELECT * FROM tokens WHERE pageId = :pageId ORDER BY regionId, position")
    suspend fun byPage(pageId: String): List<TokenEntity>

    @Query("DELETE FROM tokens WHERE pageId = :pageId")
    suspend fun clearPage(pageId: String)

    @Query("DELETE FROM tokens WHERE pageId = :pageId AND regionId = :regionId")
    suspend fun clearRegion(pageId: String, regionId: Long)
}

/**
 * Section 7's translation cache. Small, and deliberately not observed: nothing renders
 * from it -- the sheet reads `RegionEntity.translation` -- so it is only ever read once,
 * by stage 7, before deciding whether it has any work to do.
 */
@Dao
interface TranslationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: TranslationCacheEntity)

    @Query("SELECT * FROM translations WHERE `key` = :key")
    suspend fun byKey(key: String): TranslationCacheEntity?

    @Query("DELETE FROM translations WHERE pageId = :pageId")
    suspend fun clearPage(pageId: String)

    @Query("SELECT COUNT(*) FROM translations")
    suspend fun count(): Int

    /** Behind the settings screen's "forget cached translations". */
    @Query("DELETE FROM translations")
    suspend fun clearAll()
}

/** Section 3.3 tier 4's cache. See [GrammarNoteEntity] for why only the LLM half. */
@Dao
interface GrammarNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: GrammarNoteEntity)

    @Query("SELECT * FROM grammar_notes WHERE `key` = :key")
    suspend fun byKey(key: String): GrammarNoteEntity?

    @Query("DELETE FROM grammar_notes WHERE pageId = :pageId")
    suspend fun clearPage(pageId: String)

    @Query("SELECT COUNT(*) FROM grammar_notes")
    suspend fun count(): Int

    /** Alongside the settings screen's "forget cached translations". */
    @Query("DELETE FROM grammar_notes")
    suspend fun clearAll()
}

/** Section 3.3 tier 2: "tapping a token pins it / adds it to a saved list". */
@Dao
interface SavedWordDao {

    /** IGNORE, not REPLACE: the unique index is (baseForm, reading), and pinning a
     *  word a second time should keep the context it was first saved with. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(word: SavedWordEntity): Long

    @Query("SELECT * FROM saved_words ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedWordEntity>>

    /** Only a word's saved source page protects that page during volume archiving. */
    @Query(
        "SELECT DISTINCT p.id FROM pages AS p " +
            "INNER JOIN saved_words AS s ON s.sourcePageId = p.id " +
            "WHERE p.volumeId = :volumeId"
    )
    suspend fun sourcePageIds(volumeId: String): List<String>

    @Query("DELETE FROM saved_words WHERE baseForm = :baseForm AND reading = :reading")
    suspend fun delete(baseForm: String, reading: String)

    @Query("DELETE FROM saved_words WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Cuts a deleted page loose from the words saved off it.
     *
     * `sourcePageId` deliberately carries no foreign key -- a pinned word is the user's
     * own work and must outlive the photograph it came from -- so nothing cascades here
     * and the column has to be cleared by hand. Left alone it would point at a page that
     * is gone, and the Words tab would offer a row that opens an empty reader. The word
     * keeps its sentence; it just no longer claims to know where to find it.
     */
    @Query("UPDATE saved_words SET sourcePageId = NULL WHERE sourcePageId = :pageId")
    suspend fun forgetPage(pageId: String)

    @Query("SELECT COUNT(*) FROM saved_words")
    suspend fun count(): Int
}

@Database(
    entities = [
        SeriesEntity::class, VolumeEntity::class, PageEntity::class, RegionEntity::class,
        TokenEntity::class, SavedWordEntity::class, TranslationCacheEntity::class,
        GrammarNoteEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MangaDatabase : RoomDatabase() {

    abstract fun series(): SeriesDao
    abstract fun volumes(): VolumeDao
    abstract fun pages(): PageDao
    abstract fun regions(): RegionDao
    abstract fun tokens(): TokenDao
    abstract fun savedWords(): SavedWordDao
    abstract fun translations(): TranslationDao
    abstract fun grammarNotes(): GrammarNoteDao

    companion object {
        /**
         * The bucket a page lands in when nothing else claims it: what migration 4->5
         * moved the pre-M5 flat library into, and what `PageStore` falls back to. Used as
         * both the series id and the volume id -- they are separate tables, and one
         * well-known string is easier to reason about than two.
         */
        const val UNSORTED_ID = "default"

        const val UNSORTED_TITLE = "Unsorted"

        @Volatile
        private var instance: MangaDatabase? = null

        fun get(context: Context): MangaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MangaDatabase::class.java,
                "manga-reader.db",
            )
                // Real migrations from version 3 on -- see `Migrations.kt`. Up to
                // then this dropped itself on every schema change, which was defensible
                // while everything stored was re-derivable from the user's own
                // photographs. Saved words were the first thing that was not, and cached
                // translations are the second: on tier 2 they cost money to produce,
                // and so do the grammar notes added in 7.
                .addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                )
                .build()
                .also { instance = it }
        }
    }
}
