package com.novelradar.app.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.novelradar.app.data.bootstrap.BuiltInRuleInstaller
import com.novelradar.app.data.bootstrap.DataStoreBundledRuleVersionStore
import com.novelradar.app.data.db.NovelDatabase
import com.novelradar.app.data.repository.RoomBookRepository
import com.novelradar.app.data.repository.RoomSourceRuleRepository
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.feature.sources.ImportRulesUseCase
import com.novelradar.app.source.SourceEngine
import com.novelradar.app.source.http.SearchRequestFactory
import com.novelradar.app.source.http.SourceHttpClient
import com.novelradar.app.source.parse.BookPageParser
import com.novelradar.app.source.parse.ChapterPageParser
import com.novelradar.app.source.parse.HtmlSelector
import com.novelradar.app.source.parse.SearchPageParser
import com.novelradar.app.source.parse.TocPageParser
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleValidator
import com.novelradar.app.source.importer.RuleImporter
import com.novelradar.app.source.script.QuickJsScriptExecutor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/** One process-wide graph, owned by [com.novelradar.app.NovelRadarApplication]. */
class AppContainer(
    context: Context,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    val database: NovelDatabase = Room.databaseBuilder(
        appContext,
        NovelDatabase::class.java,
        NovelDatabase.NAME,
    ).build()

    val httpClient = OkHttpClient()
    val ruleParser = RuleJsonParser()
    val ruleValidator = RuleValidator()
    val sourceRules: SourceRuleRepository = RoomSourceRuleRepository(database.sourceRuleDao(), ruleValidator)
    val books: BookRepository = RoomBookRepository(database.bookDao())
    val importRules = ImportRulesUseCase(
        appContext.contentResolver,
        RuleImporter(sourceRules, ruleParser, ruleValidator),
        ioDispatcher,
    )

    private val selector = HtmlSelector()
    private val scripts = QuickJsScriptExecutor()
    val sourceEngine = SourceEngine(
        requests = SearchRequestFactory(),
        http = SourceHttpClient(httpClient),
        searchParser = SearchPageParser(selector, scripts),
        bookParser = BookPageParser(selector, scripts),
        tocParser = TocPageParser(selector, scripts),
        chapterParser = ChapterPageParser(selector, scripts),
        selector = selector,
        scripts = scripts,
    )

    private val preferences = PreferenceDataStoreFactory.create {
        appContext.preferencesDataStoreFile("novel-radar-bootstrap.preferences_pb")
    }
    val builtInInstaller = BuiltInRuleInstaller(
        dao = database.sourceRuleDao(),
        readAsset = {
            appContext.assets.open("sources/main.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        versions = DataStoreBundledRuleVersionStore(preferences),
        dispatcher = ioDispatcher,
        parser = ruleParser,
        validator = ruleValidator,
    )
}
