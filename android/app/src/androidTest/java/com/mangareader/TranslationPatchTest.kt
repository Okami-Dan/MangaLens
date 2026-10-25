package com.mangareader

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangareader.analysis.*
import com.mangareader.database.*
import com.mangareader.model.*
import com.mangareader.pipeline.*
import com.mangareader.translate.Translator
import com.mangareader.translate.cacheKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranslationPatchTest {
    @Test fun lateTranslationCannotOverwriteRenamedMovedOrEditedRequest(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MangaDatabase.get(target)
        val id = "translation-request-" + UUID.randomUUID()
        val second = "$id-other"
        var note = "original note"
        try {
            for (series in listOf(id,second)) {
                database.series().insert(SeriesEntity(series,series,"日本語","",null,1))
                database.volumes().insert(VolumeEntity(series,series,1,null,1))
            }
            database.pages().insert(PageEntity(id,id,0,"nonexistent.jpg",null,null,10,10,
                PipelineStage.ANALYSED,PageKind.COVER,true,0f,false,null,"Test",1,""))
            val region = Region(1,RegionKind.TEXT_BUBBLE,Box(0f,0f,1f,1f),1f,ocrText="俺が読む")
            database.regions().insertAll(listOf(RegionEntity.of(id,region)))
            val pipeline = PageTranslator(target)
            for (change in 0..3) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val model = object : Translator {
                    override val id = "test:delayed-$change"
                    override val label = "Test"
                    override val configured = true
                    override suspend fun translatePage(regions: List<Region>, pageContext: String,
                        cover: Boolean, onProgress: (Int,Int) -> Unit): Map<Long,String?> {
                        assertTrue(cover)
                        assertTrue(pageContext.contains("Japanese series title: 日本語"))
                        entered.complete(Unit)
                        release.await()
                        return mapOf(1L to "obsolete result")
                    }
                }
                val snapshot = database.regions().byPage(id).map { it.toRegion() }
                val context = database.translationContext(id,note).promptNote()
                val pending = async { pipeline.translate(id,model,note,currentPageContext={ note }) }
                entered.await()
                when (change) {
                    0 -> database.series().edit(id,"Renamed","日本語","")
                    1 -> database.pages().setVolume(id,second,0)
                    2 -> note = "new note"
                    3 -> database.regions().editText(id,1,"君が読む")
                }
                release.complete(Unit)
                pending.await()
                assertNull(database.regions().byId(id,1)!!.translation)
                assertNotNull(database.translations().byKey(cacheKey(snapshot,model.id,true,context)))
            }
        } finally {
            database.pages().delete(id)
            database.series().delete(id)
            database.series().delete(second)
        }
    }

    @Test fun dictionaryRejectsHomophonesAndFiltersRestrictedSenses() {
        val sqlite = SQLiteDatabase.create(null)
        sqlite.execSQL("CREATE TABLE meta(key TEXT,value TEXT)")
        sqlite.execSQL("INSERT INTO meta VALUES('schema_version','2')")
        sqlite.execSQL("CREATE TABLE entries(id INTEGER,common INTEGER)")
        sqlite.execSQL("CREATE TABLE forms(entry_id INTEGER,text TEXT,kind TEXT,common INTEGER,applies_to TEXT)")
        sqlite.execSQL("CREATE TABLE senses(entry_id INTEGER,position INTEGER,pos TEXT,misc TEXT,gloss TEXT,kanji_restrictions TEXT,reading_restrictions TEXT)")
        sqlite.execSQL("INSERT INTO entries VALUES(1,1)")
        sqlite.execSQL("INSERT INTO forms VALUES(1,'生','kanji',1,''),(1,'せい','kana',1,'生'),(1,'なま','kana',1,'生')")
        sqlite.execSQL("INSERT INTO senses VALUES(1,0,'n','','life','生','せい'),(1,1,'n','','raw','生','なま')")
        Jmdict(sqlite).use { dictionary ->
            assertEquals(listOf("raw"), dictionary.lookup("生", "なま", "名詞").glosses.map { it.text })
            assertEquals(listOf("life"), dictionary.lookup("生", "せい", "名詞").glosses.map { it.text })
            assertNull(dictionary.lookup("未収録", "なま", "名詞").id)
            assertNull(dictionary.lookup("生", "なま", "動詞").id)
        }
    }

    @Test fun lexicalAndModelAnswersRoundTripWithRepeatedWords() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionaries = DictionaryStore(target)
        runBlocking { dictionaries.install() }
        dictionaries.analyser().use { analyser ->
            val region = analyser.analyse(Region(1, RegionKind.TEXT_BUBBLE, Box(0f,0f,1f,1f),1f,
                ocrText="俺俺"))
            assertEquals(region.lexicalUnits, LexicalSerial.decode(LexicalSerial.encode(region.lexicalUnits)))
            assertEquals(listOf(0,1), region.lexicalUnits.map { it.start })
            val answer = GrammarAnswer(mapOf(0 to "Speaker"), mapOf(0 to 1), mapOf(1 to "I"))
            assertEquals(answer, GrammarNoteEntity.of("key","page",1,"model",answer).answer())
        }
    }

    @Test fun lazyRefreshNeedsNoImageAndPreservesCorrectionsAndSeriesContext(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MangaDatabase.get(target)
        val id = "translation-patch-" + UUID.randomUUID()
        val dictionary = DictionaryStore(target)
        dictionary.install()
        try {
            database.series().insert(SeriesEntity(id,"Test series","日本語の題名","",null,1))
            database.volumes().insert(VolumeEntity(id,id,1,null,1))
            database.pages().insert(PageEntity(id,id,0,"pages/nonexistent-test.jpg",null,null,10,10,
                PipelineStage.ANALYSED,PageKind.INTERIOR,true,0f,false,null,"Test",1,""))
            val region = Region(1,RegionKind.TEXT_BUBBLE,Box(0f,0f,1f,1f),1f,ocrText="もう読んじゃったの？")
            database.regions().insertAll(listOf(RegionEntity.of(id,region)))
            val rows = dictionary.analyser().use { it.tokenize(region.text) }.map {
                TokenEntity.of(id,1,it).copy(start=-1,end=-1,
                    userReading=if (it.surface == "読ん") "よん" else null)
            }
            database.tokens().insertAll(rows)
            val saved = database.savedWords().count()
            PageAnalyzer(target).refreshAnalysis(id)
            val refreshed = database.regions().byId(id,1)!!
            assertEquals(ANALYSIS_VERSION,refreshed.analysisVersion)
            assertTrue(LexicalSerial.decode(refreshed.lexicalUnits).any { it.lemma == "読む" })
            assertEquals("よん",database.tokens().byRegion(id,1).first { it.surface == "読ん" }.userReading)
            assertEquals(saved,database.savedWords().count())
            assertEquals(TranslationContext("Test series","日本語の題名","note"),database.translationContext(id,"note"))
            database.series().edit(id,"Renamed","日本語の題名","")
            assertEquals("Renamed",database.translationContext(id).seriesTitle)
            val before = database.tokens().byRegion(id,1)
            PageAnalyzer(target).refreshAnalysis(id)
            assertEquals(before,database.tokens().byRegion(id,1))
        } finally { database.series().delete(id) }
    }
}
