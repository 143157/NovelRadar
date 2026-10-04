package com.novelradar.app.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.novelradar.app.data.bootstrap.BuiltInRuleInstaller
import com.novelradar.app.data.bootstrap.DataStoreBundledRuleVersionStore
import com.novelradar.app.data.db.NovelDatabase
import com.novelradar.app.data.files.AtomicChapterFileStore
import com.novelradar.app.data.preferences.DataStoreReaderSettingsRepository
import com.novelradar.app.data.preferences.DataStoreDownloadSettingsRepository
import com.novelradar.app.data.repository.RoomBookRepository
import com.novelradar.app.data.repository.RoomReadingProgressRepository
import com.novelradar.app.data.repository.DefaultCatalogRepository
import com.novelradar.app.data.repository.DefaultChapterRepository
import com.novelradar.app.data.repository.ChapterPageFetcher
import com.novelradar.app.data.repository.RuleBasedChapterContentFetcher
import com.novelradar.app.data.repository.RoomChapterCatalogStore
import com.novelradar.app.data.repository.WorkManagerDownloadRepository
import com.novelradar.app.data.repository.RoomSourceRuleRepository
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.CatalogFetcher
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.DetailsFetcher
import com.novelradar.app.domain.repository.ChapterRepository
import com.novelradar.app.domain.repository.ReaderSettingsRepository
import com.novelradar.app.domain.repository.ReadingProgressRepository
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.domain.search.AggregateSearchUseCase
import com.novelradar.app.domain.search.SourceSearch
import com.novelradar.app.feature.sources.ImportRulesUseCase
import com.novelradar.app.feature.sources.TestSourceUseCase
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
import kotlinx.coroutines.flow.first
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
    ).addMigrations(NovelDatabase.MIGRATION_1_2, NovelDatabase.MIGRATION_2_3, NovelDatabase.MIGRATION_3_4).build()

    val httpClient = OkHttpClient()
    val ruleParser = RuleJsonParser()
    val ruleValidator = RuleValidator()
    val sourceRules: SourceRuleRepository = RoomSourceRuleRepository(database.sourceRuleDao(), ruleValidator)
    val books: BookRepository = RoomBookRepository(database.bookDao())
    val readingProgress: ReadingProgressRepository = RoomReadingProgressRepository(books)
    private val readerPreferenceStore = PreferenceDataStoreFactory.create {
        appContext.preferencesDataStoreFile("novel-radar-reader.preferences_pb")
    }
    val readerSettings: ReaderSettingsRepository = DataStoreReaderSettingsRepository(readerPreferenceStore)
    private val downloadPreferenceStore = PreferenceDataStoreFactory.create {
        appContext.preferencesDataStoreFile("novel-radar-download.preferences_pb")
    }
    val downloadSettings: com.novelradar.app.domain.repository.DownloadSettingsRepository =
        DataStoreDownloadSettingsRepository(downloadPreferenceStore)
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
    private val sourceSearch = SourceSearch { stored, rule, keyword ->
        sourceEngine.search(rule, stored.sourceId, keyword, stored.allowCleartext)
    }
    val aggregateSearch = AggregateSearchUseCase(sourceRules, sourceSearch, ruleParser)
    val testSource = TestSourceUseCase(sourceSearch, ruleParser)
    private val chapterFiles = AtomicChapterFileStore(appContext.filesDir.toPath())
    private val downloadLeases = com.novelradar.app.data.download.BookDownloadLeases()
    private val localChapterCleaner = com.novelradar.app.data.files.LocalChapterCleaner(
        chapterFiles, database.chapterDao()::clearStoredMetadata)
    val coverFetcher = com.novelradar.app.data.repository.SafeCoverFetcher(SourceHttpClient(httpClient))
    private val chapterRepository = DefaultChapterRepository(
        chapters = database.chapterDao(),
        files = chapterFiles,
        fetcher = RuleBasedChapterContentFetcher(sourceRules,
            ChapterPageFetcher { rule, sourceId, url, allowCleartext ->
                sourceEngine.chapter(rule, sourceId, url, allowCleartext)
            }, ruleParser),
        isMeteredNetwork = {
            appContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        },
        allowMeteredPrefetch = { readerSettings.preferences.first().allowMeteredPrefetch },
        contentLeases = downloadLeases,
    )
    val chapters: ChapterRepository = chapterRepository
    private suspend fun crawlPolicy(sourceId: String): com.novelradar.app.data.download.CrawlPolicy {
        val raw = database.sourceRuleDao().getById(sourceId)?.rawJson
        val rule = raw?.let { (ruleParser.parse(it) as? com.novelradar.app.source.rule.RuleParseResult.Success)?.rules?.singleOrNull() }
        return com.novelradar.app.data.download.CrawlPolicy.from(rule?.crawl)
    }
    val downloadStore = com.novelradar.app.data.download.RoomDownloadStore(database)
    val downloadNotifications = com.novelradar.app.data.download.DownloadNotificationFactory(appContext)
    val downloadRunner = com.novelradar.app.data.download.BookDownloadRunner(downloadStore, chapterRepository,
        downloadLeases, com.novelradar.app.data.download.SourceDownloadLimiter(), ::crawlPolicy)
    val downloads: com.novelradar.app.domain.repository.DownloadRepository = WorkManagerDownloadRepository(
        database.downloadTaskDao(), com.novelradar.app.data.download.DownloadCoordinator(downloadStore,
            com.novelradar.app.data.download.WorkManagerDownloadScheduler(appContext, ::crawlPolicy), downloadLeases), localChapterCleaner)
    val catalog: CatalogRepository = DefaultCatalogRepository(
        books, sourceRules, RoomChapterCatalogStore(database.chapterDao()),
        CatalogFetcher { rule, stored, hit, bookId ->
            sourceEngine.toc(rule, stored.sourceId, hit, bookId, stored.allowCleartext)
        }, ruleParser, DetailsFetcher { rule, stored, hit, bookId ->
            sourceEngine.bookDetails(rule, stored.sourceId, hit, bookId, stored.allowCleartext)
        },
    )
    val refreshShelf = com.novelradar.app.domain.library.RefreshShelfUseCase(catalog)

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
