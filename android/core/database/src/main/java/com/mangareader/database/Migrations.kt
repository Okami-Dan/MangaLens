package com.mangareader.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real migrations, from M4 on.
 *
 * Up to version 3 this database dropped itself on every schema change, and the argument
 * was sound while it held nothing a photograph could not reproduce. It no longer holds
 * only that. Saved words are the user's own work and cannot be re-derived; cached
 * translations cost money on tier 2 to produce a second time; and re-analysing a library
 * is ten seconds a page. Dropping a real library to add two nullable columns is not a
 * trade worth making.
 *
 * The DDL below is Room's own, copied from `schemas/…/4.json` rather than written by
 * hand. Room verifies the result against that schema on open and throws if a migration
 * produced anything different -- down to a column's declared type -- so the schema export
 * is what makes a migration checkable instead of hopeful.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Section 6's two translation columns, held back until there was a stage 7 to
        // fill them. Nullable with no default, so every existing region reads as
        // "not translated yet" -- which is exactly what it is.
        db.execSQL("ALTER TABLE `regions` ADD COLUMN `translation` TEXT")
        db.execSQL("ALTER TABLE `regions` ADD COLUMN `translatorId` TEXT")

        // Section 7's cache. Empty after the migration: nothing has been translated yet,
        // so there is nothing to carry over.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `translations` (" +
                "`key` TEXT NOT NULL, `pageId` TEXT NOT NULL, `translatorId` TEXT NOT NULL, " +
                "`results` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`key`), " +
                "FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_translations_pageId` ON `translations` (`pageId`)",
        )
    }
}

/**
 * M5's two folder levels. `books` becomes `series` + `volumes`, and `pages.bookId` becomes
 * `pages.volumeId`.
 *
 * The page table has to be rebuilt rather than altered: the column is renamed *and* its
 * foreign key points somewhere new, and SQLite will do neither in place. That means the
 * create/copy/drop/rename dance, and the dance is why this migration deserves a test.
 *
 * `regions`, `tokens` and `translations` all hold `ON DELETE CASCADE` foreign keys onto
 * `pages`. Dropping the old page table with foreign keys enforced would take every OCR
 * result, every token and every paid translation with it. Room runs migrations with
 * `PRAGMA foreign_keys` already off and issues a `foreign_key_check` afterwards, so the
 * recipe below is safe as written -- but nothing here may turn them back on, and
 * `MigrationTest` asserts the children survived rather than trusting that.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `series` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `japaneseTitle` TEXT NOT NULL, " +
                "`wikiUrl` TEXT NOT NULL, `coverPath` TEXT, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `volumes` (" +
                "`id` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `number` INTEGER NOT NULL, " +
                "`coverPath` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`seriesId`) REFERENCES `series`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_volumes_seriesId` ON `volumes` (`seriesId`)",
        )

        // Every existing book becomes a series holding one volume, both keeping the book's
        // id so the page rows below need no id translation. In practice this is the single
        // "default"/"Unsorted" row `PageStore` has been writing since M1, but driving it
        // off the table rather than hardcoding that id carries a hand-seeded book across
        // too -- and costs nothing.
        db.execSQL(
            "INSERT OR IGNORE INTO `series` (`id`, `title`, `japaneseTitle`, `wikiUrl`, " +
                "`coverPath`, `createdAt`) " +
                "SELECT `id`, `title`, '', '', NULL, `createdAt` FROM `books`",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO `volumes` (`id`, `seriesId`, `number`, `coverPath`, " +
                "`createdAt`) SELECT `id`, `id`, 1, NULL, `createdAt` FROM `books`",
        )

        // The new table is built under a temporary name and renamed into place, rather
        // than the old one being renamed aside first. That order is not cosmetic.
        //
        // Since SQLite 3.25 -- unless `legacy_alter_table` is on, and it is not --
        // `ALTER TABLE ... RENAME` rewrites every REFERENCES clause in *every other table*
        // that names the table being renamed. Disabling foreign keys does not switch that
        // off. So renaming `pages` aside would silently repoint `regions.pageId` and
        // `translations.pageId` at a table this migration is about to drop, and leave a
        // database whose foreign keys name something that no longer exists. Renaming
        // `pages_new` into place is safe for the mirror-image reason: nothing references
        // `pages_new`, so there is nothing for that rule to rewrite.
        //
        // The one visible cost is that SQLite rewrites the renamed table's own stored DDL
        // to `CREATE TABLE "pages"` with double quotes where Room's schema says backticks.
        // Room reads `PRAGMA table_info` and `PRAGMA foreign_key_list`, never the DDL text,
        // so its validation is unaffected.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pages_new` (" +
                "`id` TEXT NOT NULL, `volumeId` TEXT NOT NULL, `readingIndex` INTEGER NOT NULL, " +
                "`imagePath` TEXT NOT NULL, `rawImagePath` TEXT, `thumbnailPath` TEXT, " +
                "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `stage` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `kindAuto` INTEGER NOT NULL, " +
                "`colourFraction` REAL NOT NULL, `rectified` INTEGER NOT NULL, `quad` TEXT, " +
                "`title` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, `notes` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`volumeId`) REFERENCES `volumes`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `pages_new` SELECT `id`, `bookId`, `readingIndex`, `imagePath`, " +
                "`rawImagePath`, `thumbnailPath`, `width`, `height`, `stage`, `kind`, " +
                "`kindAuto`, `colourFraction`, `rectified`, `quad`, `title`, `capturedAt`, " +
                "`notes` FROM `pages`",
        )
        db.execSQL("DROP TABLE `pages`")
        db.execSQL("ALTER TABLE `pages_new` RENAME TO `pages`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_volumeId` ON `pages` (`volumeId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pages_readingIndex` ON `pages` (`readingIndex`)",
        )

        db.execSQL("DROP TABLE `books`")
    }
}

/**
 * M6's vault. Every stored path becomes relative to `Vault.root` instead of absolute.
 *
 * No column changes -- this migration only rewrites data, which is unusual enough to
 * justify saying why it has to be a migration at all. The library moved out of
 * `filesDir` and into `Android/media/<package>/library` so that reinstalling the app no
 * longer deletes it, and an absolute path pinned to the old location would survive the
 * move as a row pointing at a file that is not there any more. Worse, the internal path
 * embeds the Android user id (`/data/user/0/...`), so it is not even stable across the
 * reinstall this whole feature exists to survive. A relative path resolved against
 * whatever root the vault reports today is the only form that keeps working.
 *
 * `rtrim(x, replace(x, '/', ''))` is the SQLite basename idiom: `replace` gives every
 * character of the path except the separators, and `rtrim` strips those from the right
 * until it hits a `/`, leaving the directory prefix. Its length plus one is where the
 * file name starts. SQLite has no regex and no `basename`, and this is the standard way
 * around that.
 *
 * The `LIKE '/%'` guards make it idempotent: a row already rewritten is left alone, so
 * running this twice -- or against a database that a newer build already touched -- is
 * a no-op rather than `pages/pages/ab12.jpg`.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {

    override fun migrate(db: SupportSQLiteDatabase) {
        relativize(db, "pages", "imagePath", "pages")
        relativize(db, "pages", "rawImagePath", "pages")
        relativize(db, "pages", "thumbnailPath", "pages")
        relativize(db, "series", "coverPath", "covers")
        relativize(db, "volumes", "coverPath", "covers")
    }

    private fun relativize(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        prefix: String,
    ) {
        db.execSQL(
            "UPDATE `" + table + "` SET `" + column + "` = '" + prefix + "/' || " +
                "substr(`" + column + "`, length(rtrim(`" + column + "`, " +
                "replace(`" + column + "`, '/', ''))) + 1) " +
                "WHERE `" + column + "` LIKE '/%'",
        )
    }
}

/**
 * M7's grammar notes. One new table, nothing existing touched.
 *
 * Section 3.3 tier 4 stores only the notes a model wrote, and this table is empty after
 * the migration for the same reason `translations` was empty after 3->4: nothing has been
 * explained yet, so there is nothing to carry over. The offline half of the breakdown is
 * not here at all -- it is recomputed from the token rows on every sheet open, which is
 * what keeps it in step with a rule table that grows between versions.
 *
 * DDL copied from `schemas/…/7.json`, as this file's header requires.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `grammar_notes` (" +
                "`key` TEXT NOT NULL, `pageId` TEXT NOT NULL, `regionId` INTEGER NOT NULL, " +
                "`explainerId` TEXT NOT NULL, `notes` TEXT NOT NULL, `at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`key`), FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_grammar_notes_pageId` " +
                "ON `grammar_notes` (`pageId`)",
        )
    }
}

/**
 * v7 -> v8: the two inflection columns stage 6 was computing and throwing away.
 *
 * `Token.conjugationForm` has existed since the register classifier needed it -- IPADIC
 * tags 来い and 来る both as 動詞,自立 and only 活用形 tells them apart -- but it was never
 * given a column, so every reload of a page silently dropped it. The rules keyed on it
 * fired once, during the analysis that captured the page, and never again.
 *
 * `conjugationType` (活用型) is new to v8. Kuromoji has always emitted it; tier 4 is the
 * first thing that needed to say *which kind* of verb a word is rather than merely that
 * it is one.
 *
 * Both default to the empty string, which is exactly what a v7 row means: not recorded.
 * A page analysed before the update shows no inflection detail until it is re-analysed,
 * which is honest -- inventing a conjugation for a token whose form was never stored
 * would be worse than leaving the line off.
 *
 * DDL copied from `schemas/…/8.json`, as this file's header requires.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `tokens` ADD COLUMN `conjugationForm` TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "ALTER TABLE `tokens` ADD COLUMN `conjugationType` TEXT NOT NULL DEFAULT ''",
        )
    }
}

/** A nullable reading correction on a token. Existing readings remain analyser-owned. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tokens` ADD COLUMN `userReading` TEXT DEFAULT NULL")
    }
}

/** Stored lexical analysis is refreshed from OCR text on first opening, never from images. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tokens ADD COLUMN start INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE tokens ADD COLUMN end INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE regions ADD COLUMN lexicalUnits TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE regions ADD COLUMN analysisVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE regions ADD COLUMN translationKey TEXT DEFAULT NULL")
    }
}
