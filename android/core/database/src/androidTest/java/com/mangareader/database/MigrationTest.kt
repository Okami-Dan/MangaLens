package com.mangareader.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations with real libraries behind them, against the exported schemas.
 *
 * The interesting assertion is not that the folders appeared -- it is that everything
 * hanging off `pages` is still there afterwards. The migration rebuilds the page table,
 * and `regions` and `translations` cascade on delete from it -- `tokens` in turn from
 * `regions` -- so a version of this migration that ran with foreign keys enforced would
 * take the user's OCR output and their paid translations with it and still look like it
 * worked. That is exactly the kind of failure that only shows up on a real library,
 * months later.
 *
 * `MigrationTestHelper` also runs Room's own schema validation at the end: open the
 * migrated database through [MangaDatabase] and Room compares the result column by column
 * against `schemas/…/5.json` and throws on any difference. That check is worth less for
 * 5 -> 6, which changes no columns at all -- so that test asserts on the data instead.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MangaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate4To5_movesPagesIntoFoldersAndKeepsTheirWork() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL("INSERT INTO books VALUES ('default', 'Unsorted', 1000)")
            db.execSQL(
                "INSERT INTO pages VALUES ('page1', 'default', 0, '/img.jpg', NULL, NULL, " +
                    "1200, 1600, 'ANALYSED', 'INTERIOR', 1, 0.1, 1, NULL, 'Page', 2000, '')",
            )
            // One row in each table that hangs off `pages`: a region and a cached
            // translation directly, and a token through the region. A saved word goes in
            // too -- it has no foreign key at all, so it is the control that tells "the
            // cascade fired" apart from "the insert never happened".
            db.execSQL(
                "INSERT INTO regions (pageId, regionId, kind, box, score, readingIndex, " +
                    "panelIndex, bubbleBox, ocrText, ocrTextEdited, ocrConfidence, sfx, " +
                    "translation, translatorId) VALUES ('page1', 1, 'TEXT_BUBBLE', " +
                    "'0.1,0.1,0.2,0.2', 0.9, 0, 0, NULL, 'こんにちは', NULL, NULL, NULL, " +
                    "'Hello', 'remote:gpt')",
            )
            db.execSQL(
                "INSERT INTO tokens VALUES ('page1', 1, 0, 'こんにちは', 'コンニチハ', " +
                    "'こんにちは', 'コンニチハ', 'interjection', 'interjection', '', " +
                    "'konnichiwa', NULL, 'hello', 1)",
            )
            db.execSQL(
                "INSERT INTO translations VALUES ('key1', 'page1', 'remote:gpt', '1=Hello', 3000)",
            )
            db.execSQL(
                "INSERT INTO saved_words (baseForm, reading, gloss, posLabel, jmdictEntryId, " +
                    "sourcePageId, sourceRegionId, context, savedAt) " +
                    "VALUES ('こんにちは', 'こんにちは', 'hello', 'interjection', NULL, " +
                    "'page1', 1, 'こんにちは', 4000)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5)

        // The book became a series holding one volume, and the page is in that volume.
        assertEquals(1L, db.count("SELECT COUNT(*) FROM series WHERE id = 'default'"))
        assertEquals(
            1L,
            db.count("SELECT COUNT(*) FROM volumes WHERE id = 'default' AND seriesId = 'default' AND number = 1"),
        )
        assertEquals(1L, db.count("SELECT COUNT(*) FROM pages WHERE volumeId = 'default'"))
        assertEquals(0L, db.count("SELECT COUNT(*) FROM sqlite_master WHERE name = 'books'"))

        // Nothing hanging off the rebuilt page table was cascaded away.
        assertEquals(1L, db.count("SELECT COUNT(*) FROM regions WHERE pageId = 'page1'"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM tokens WHERE pageId = 'page1'"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM translations WHERE pageId = 'page1'"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM saved_words"))

        // And nothing still names the scaffolding. `ALTER TABLE ... RENAME` rewrites
        // REFERENCES clauses in every other table that names the one being renamed, and
        // it does so whether or not foreign keys are enforced -- so a migration that
        // renamed `pages` aside instead of renaming `pages_new` into place would leave
        // `regions` and `translations` pointing at a table it had already dropped. That
        // passes every count above and only shows up here.
        assertEquals(
            0L,
            db.count(
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE sql LIKE '%pages_old%' OR sql LIKE '%pages_new%'",
            ),
        )
        assertEquals(
            "Hello",
            db.query("SELECT translation FROM regions WHERE pageId = 'page1'").use {
                it.moveToFirst()
                it.getString(0)
            },
        )
    }

    @Test
    fun migrate4To5_onAnEmptyLibraryLeavesNoStrayFolders() {
        helper.createDatabase(DB, 4).close()
        val db = helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5)

        // No books meant no pages, so there is nothing to file and the grid opens empty
        // rather than showing an "Unsorted" card holding nothing. `PageStore` creates
        // that folder only when a page actually needs it.
        assertEquals(0L, db.count("SELECT COUNT(*) FROM series"))
        assertEquals(0L, db.count("SELECT COUNT(*) FROM volumes"))
    }

    /**
     * Migration 5 -> 6: absolute paths become relative to the vault.
     *
     * The columns do not change, which makes this the rare data-only migration and makes
     * it worth stating what would go wrong without it. M6 moved the library out of
     * `filesDir` and into `Android/media/<package>` so a reinstall stops deleting it. A
     * row still holding `/data/user/0/com.mangareader/files/pages/page1.jpg` would name a
     * file that is no longer there -- and that path is not even stable across the
     * reinstall the move exists to survive, because it embeds the Android user id.
     *
     * The second assertion is the one that catches a careless rewrite: run twice, the
     * result has to be `pages/page1.jpg` and not `pages/pages/page1.jpg`.
     */
    @Test
    fun migrate5To6_makesStoredPathsRelativeToTheVault() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO series VALUES ('s1', 'Yotsuba', '', '', " +
                    "'/data/user/0/com.mangareader/files/covers/s1.jpg', 1000)",
            )
            db.execSQL(
                "INSERT INTO volumes VALUES ('v1', 's1', 1, " +
                    "'/data/user/0/com.mangareader/files/covers/v1.jpg', 1000)",
            )
            db.execSQL(
                "INSERT INTO pages VALUES ('page1', 'v1', 0, " +
                    "'/data/user/0/com.mangareader/files/pages/page1.jpg', " +
                    "'/data/user/0/com.mangareader/files/pages/page1_raw.jpg', " +
                    "'/data/user/0/com.mangareader/files/pages/page1_thumb.jpg', " +
                    "1200, 1600, 'ANALYSED', 'INTERIOR', 1, 0.1, 1, NULL, 'Page', 2000, '')",
            )
            // A page with no cover and no raw copy -- the shape `keepRaw = false` writes.
            // NULL must stay NULL rather than becoming the string "pages/".
            db.execSQL(
                "INSERT INTO pages VALUES ('page2', 'v1', 1, " +
                    "'/data/user/0/com.mangareader/files/pages/page2.jpg', NULL, NULL, " +
                    "1200, 1600, 'RECTIFIED', 'INTERIOR', 1, 0.1, 1, NULL, 'Page', 2001, '')",
            )
            db.execSQL(
                "INSERT INTO regions (pageId, regionId, kind, box, score, readingIndex, " +
                    "panelIndex, bubbleBox, ocrText, ocrTextEdited, ocrConfidence, sfx, " +
                    "translation, translatorId) VALUES ('page1', 1, 'TEXT_BUBBLE', " +
                    "'0.1,0.1,0.2,0.2', 0.9, 0, 0, NULL, 'kon', NULL, NULL, NULL, " +
                    "'Hello', 'remote:gpt')",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6)

        assertEquals("pages/page1.jpg", db.text("SELECT imagePath FROM pages WHERE id = 'page1'"))
        assertEquals(
            "pages/page1_raw.jpg",
            db.text("SELECT rawImagePath FROM pages WHERE id = 'page1'"),
        )
        assertEquals(
            "pages/page1_thumb.jpg",
            db.text("SELECT thumbnailPath FROM pages WHERE id = 'page1'"),
        )
        assertEquals("covers/s1.jpg", db.text("SELECT coverPath FROM series WHERE id = 's1'"))
        assertEquals("covers/v1.jpg", db.text("SELECT coverPath FROM volumes WHERE id = 'v1'"))
        assertNull(db.text("SELECT rawImagePath FROM pages WHERE id = 'page2'"))

        // Nothing hanging off the pages was disturbed -- this migration touches columns,
        // not tables, and a rewrite that dropped rows would still pass the checks above.
        assertEquals(1L, db.count("SELECT COUNT(*) FROM regions WHERE pageId = 'page1'"))

        // Idempotent: the LIKE '/%' guard means a row already rewritten is left alone.
        MIGRATION_5_6.migrate(db)
        assertEquals("pages/page1.jpg", db.text("SELECT imagePath FROM pages WHERE id = 'page1'"))
        assertEquals("covers/s1.jpg", db.text("SELECT coverPath FROM series WHERE id = 's1'"))
    }

    /**
     * 6 -> 7 adds `grammar_notes` and touches nothing else.
     *
     * Two things worth asserting beyond "the table appeared". The library has to survive
     * a migration that only adds -- a CREATE TABLE cannot lose rows, but a future edit to
     * this migration could, and the check costs one line. And the new table's own cascade
     * has to be wired to `pages`, because tier 4's notes are per bubble and a deleted page
     * must not leave them behind; `runMigrationsAndValidate` checks the foreign key is
     * declared, and deleting the page here checks it actually fires.
     */
    @Test
    fun migrate6To7_addsGrammarNotesAndKeepsTheLibrary() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL("INSERT INTO series VALUES ('s1', 'Yotsuba', '', '', NULL, 1000)")
            db.execSQL("INSERT INTO volumes VALUES ('v1', 's1', 1, NULL, 1000)")
            db.execSQL(
                "INSERT INTO pages VALUES ('page1', 'v1', 0, 'pages/page1.jpg', NULL, NULL, " +
                    "1200, 1600, 'ANALYSED', 'INTERIOR', 1, 0.1, 1, NULL, 'Page', 2000, '')",
            )
            db.execSQL(
                "INSERT INTO regions (pageId, regionId, kind, box, score, readingIndex, " +
                    "panelIndex, bubbleBox, ocrText, ocrTextEdited, ocrConfidence, sfx, " +
                    "translation, translatorId) VALUES ('page1', 1, 'TEXT_BUBBLE', " +
                    "'0.1,0.1,0.2,0.2', 0.9, 0, 0, NULL, 'こんにちは', NULL, NULL, NULL, " +
                    "'Hello', 'remote:gpt')",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7)

        // The new table exists and is empty: nothing has been explained yet, so there is
        // nothing to carry over.
        assertEquals(0L, db.count("SELECT COUNT(*) FROM grammar_notes"))

        // Nothing that was already there was disturbed.
        assertEquals(1L, db.count("SELECT COUNT(*) FROM pages"))
        assertEquals(1L, db.count("SELECT COUNT(*) FROM regions WHERE pageId = 'page1'"))

        db.execSQL(
            "INSERT INTO grammar_notes VALUES ('k1', 'page1', 1, 'remote:gpt', 'note', 3000)",
        )
        assertEquals(1L, db.count("SELECT COUNT(*) FROM grammar_notes"))

        // The cascade is real, not merely declared.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM pages WHERE id = 'page1'")
        assertEquals(0L, db.count("SELECT COUNT(*) FROM grammar_notes"))
    }

    @Test
    fun migrate8To9_keepsTokensAndStartsWithoutReadingOverrides() {
        helper.createDatabase(DB, 8).use { db ->
            db.execSQL("INSERT INTO series VALUES ('s1', 'Series', '', '', NULL, 1000)")
            db.execSQL("INSERT INTO volumes VALUES ('v1', 's1', 1, NULL, 1000)")
            db.execSQL(
                "INSERT INTO pages VALUES ('p1', 'v1', 0, 'pages/p1.jpg', NULL, NULL, " +
                    "1200, 1600, 'ANALYSED', 'INTERIOR', 1, 0.1, 1, NULL, 'Page', 2000, '')",
            )
            db.execSQL(
                "INSERT INTO regions (pageId, regionId, kind, box, score, readingIndex, " +
                    "panelIndex, bubbleBox, ocrText, ocrTextEdited, ocrConfidence, sfx, " +
                    "translation, translatorId) VALUES ('p1', 1, 'TEXT_BUBBLE', " +
                    "'0.1,0.1,0.2,0.2', 0.9, 0, 0, NULL, 'word', NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO tokens (pageId, regionId, position, surface, reading, baseForm, " +
                    "baseReading, partOfSpeech, posLabel, furigana, romaji, jmdictEntryId, " +
                    "glosses, isContentWord, conjugationForm, conjugationType) VALUES " +
                    "('p1', 1, 0, 'word', 'reading', 'word', 'reading', 'noun', 'noun', '', " +
                    "'reading', NULL, '', 1, '', '')",
            )
        }
        val db = helper.runMigrationsAndValidate(DB, 9, true, MIGRATION_8_9)
        assertEquals(1L, db.count("SELECT COUNT(*) FROM tokens WHERE pageId = 'p1'"))
        assertNull(db.text("SELECT userReading FROM tokens WHERE pageId = 'p1'"))
    }

    @Test
    fun migrate9To10_addsAnalysisWithoutDiscardingTokens() {
        helper.createDatabase(DB, 9).use { db ->
            db.execSQL("INSERT INTO series VALUES ('s1', 'Series', '', '', NULL, 1000)")
            db.execSQL("INSERT INTO volumes VALUES ('v1', 's1', 1, NULL, 1000)")
            db.execSQL("INSERT INTO pages VALUES ('p1', 'v1', 0, 'pages/p1.jpg', NULL, NULL, " +
                "1200, 1600, 'ANALYSED', 'INTERIOR', 1, 0.1, 1, NULL, 'Page', 2000, '')")
            db.execSQL("INSERT INTO regions (pageId,regionId,kind,box,score,readingIndex,panelIndex," +
                "ocrText,translation,translatorId) VALUES ('p1',1,'TEXT_BUBBLE','0;0;1;1',1,0,0,'俺','I','m')")
            db.execSQL("INSERT INTO tokens (pageId,regionId,position,surface,reading,baseForm,baseReading," +
                "partOfSpeech,posLabel,furigana,romaji,glosses,isContentWord,conjugationForm,conjugationType,userReading) " +
                "VALUES ('p1',1,0,'俺','おれ','俺','おれ','名詞','noun','','ore','',1,'','','おれ')")
        }
        val db = helper.runMigrationsAndValidate(DB, 10, true, MIGRATION_9_10)
        assertEquals("おれ", db.text("SELECT userReading FROM tokens"))
        assertEquals(-1L, db.count("SELECT start FROM tokens"))
        assertEquals("[]", db.text("SELECT lexicalUnits FROM regions"))
        assertEquals(0L, db.count("SELECT analysisVersion FROM regions"))
        assertEquals("I", db.text("SELECT translation FROM regions"))
        assertNull(db.text("SELECT translationKey FROM regions"))
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(sql: String): Long =
        query(sql).use {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.text(sql: String): String? =
        query(sql).use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getString(0)
        }

    private companion object {
        const val DB = "migration-test.db"
    }
}
