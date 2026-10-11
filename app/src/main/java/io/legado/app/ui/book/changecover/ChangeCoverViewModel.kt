package io.legado.app.ui.book.changecover

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.BookCover
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.min

class ChangeCoverViewModel(application: Application) : BaseViewModel(application) {
    private val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var searchSuccess: ((SearchBook) -> Unit)? = null
    private var upAdapter: (() -> Unit)? = null
    private var bookSourceParts = arrayListOf<BookSourcePart>()

    // F4：搜索引擎搜封面。engine 为空时走原版书源搜索
    var engine: String = ""
    var keyword: String = ""
    val engineNames = mapOf(
        "baidu" to "百度图片",
        "bing" to "必应图片",
        "sogou" to "搜狗图片",
        "google" to "谷歌图片"
    )
    private val pcUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val defaultCover by lazy {
        listOf(
            SearchBook(
                originName = "默认封面",
                name = name,
                author = author,
                coverUrl = "use_default_cover"
            )
        )
    }
    private var task: Job? = null
    val searchStateData = MutableLiveData<Int>()
    var name: String = ""
    var author: String = ""
    val searchBooks: MutableList<SearchBook> = Collections.synchronizedList(arrayListOf())
    val dataFlow = callbackFlow {

        searchSuccess = { searchBook ->
            if (!searchBooks.contains(searchBook)) {
                searchBooks.add(searchBook)
                trySend(defaultCover + searchBooks.sortedBy { it.originOrder })
            }
        }

        upAdapter = {
            trySend(defaultCover + searchBooks.sortedBy { it.originOrder })
        }

        appDb.searchBookDao.getEnableHasCover(name, author).let {
            searchBooks.addAll(it)
            trySend(defaultCover + searchBooks.toList())
        }

        if (engine.isNotEmpty()) {
            searchFromEngine(engine)
        } else if (searchBooks.size <= 1) {
            startSearch()
        }

        awaitClose {
            searchBooks.clear()
            searchSuccess = null
            upAdapter = null
        }
    }.flowOn(IO)

    fun initData(arguments: Bundle?) {
        arguments?.let { bundle ->
            bundle.getString("name")?.let {
                name = it
            }
            bundle.getString("author")?.let {
                author = it.replace(AppPattern.authorRegex, "")
            }
            // F4：本地书默认用百度图片搜封面
            if (bundle.getBoolean("useEngine") && engine.isEmpty()) {
                engine = "baidu"
                keyword = "$name 封面"
            }
        }
    }

    private fun initSearchPool() {
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    private fun startSearch() {
        execute {
            stopSearch()
            if(searchStateData.value == 2) {
                upAdapter?.invoke()
                initSearchPool()
                search()
                return@execute
            }
            searchBooks.clear()
            upAdapter?.invoke()
            bookSourceParts.clear()
            bookSourceParts.addAll(appDb.bookSourceDao.allEnabledPart)
            initSearchPool()
            searchWithBookCover()
        }
    }

    private fun searchWithBookCover() {
        viewModelScope.launch(IO) {
            searchStateData.postValue(1)
            try {
                val tempBook = Book(name = name, author = author)
                val coverUrl = BookCover.searchCover(tempBook)
                if (!coverUrl.isNullOrEmpty()) {
                    val searchBook = SearchBook(
                        originName = "封面规则",
                        name = name,
                        author = author,
                        coverUrl = coverUrl,
                        originOrder = -1
                    )
                    searchSuccess?.invoke(searchBook)
                    searchStateData.postValue(2)
                } else {
                    search()
                }
            } catch (e: Exception) {
                AppLog.put("封面规则搜索出错\n${e.localizedMessage}", e)
                search()
            }
        }
    }

    private fun search() {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (bs in bookSourceParts) {
                    bs.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                searchStateData.postValue(1)
            }.mapParallelSafe(threadCount) {
                withTimeout(60000L) {
                    search(it)
                }
            }.onCompletion {
                searchStateData.postValue(0)
            }.catch {
                AppLog.put("封面换源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private suspend fun search(source: BookSource) {
        if (source.getSearchRule().coverUrl.isNullOrBlank()) {
            return
        }
        val searchBook = WebBook.searchBookAwait(
            source, name,
            shouldBreak = { it > 0 }).firstOrNull() ?: return
        if (searchBook.name == name && searchBook.author == author
            && !searchBook.coverUrl.isNullOrEmpty()
        ) {
            appDb.searchBookDao.insert(searchBook)
            searchSuccess?.invoke(searchBook)
        }
    }

    fun startOrStopSearch() {
        if (task == null || !task!!.isActive) {
            if (engine.isNotEmpty()) {
                searchFromEngine(engine)
            } else {
                startSearch()
            }
        } else {
            searchStateData.postValue(0)
            stopSearch()
        }
    }

    /**
     * F4：用搜索引擎图片搜索封面，结果以 SearchBook 形式并入现有列表
     */
    fun searchFromEngine(engineKey: String) {
        stopSearch()
        engine = engineKey
        if (engine.isEmpty()) {
            startSearch()
            return
        }
        keyword = keyword.ifBlank { "$name 封面" }
        execute {
            searchStateData.postValue(1)
            try {
                val covers = fetchEngineCovers(engine, keyword)
                if (covers.isEmpty()) {
                    AppLog.put("${engineNames[engine] ?: engine}：未搜索到图片")
                }
                for (url in covers) {
                    searchSuccess?.invoke(
                        SearchBook(
                            originName = engineNames[engine] ?: engine,
                            name = name,
                            author = author,
                            coverUrl = url,
                            bookUrl = url
                        )
                    )
                }
            } catch (e: Exception) {
                AppLog.put("${engineNames[engine] ?: engine}搜索出错\n${e.localizedMessage}", e)
            } finally {
                searchStateData.postValue(0)
            }
        }
    }

    private suspend fun fetchEngineCovers(engine: String, kw: String): List<String> {
        val urlStr = when (engine) {
            "baidu" ->
                "https://image.baidu.com/search/acjson?tn=resultjson_com&ipn=rj&word=${enc(kw)}&pn=0&rn=30"
            "bing" ->
                "https://cn.bing.com/images/async?q=${enc(kw)}&first=0&count=35&mkt=zh-CN"
            "sogou" ->
                "https://pic.sogou.com/napi/pc/searchList?mode=1&start=0&xml_len=48&query=${enc(kw)}"
            "google" ->
                "https://www.google.com/search?q=${enc(kw)}&tbm=isch&udm=2"
            else -> return emptyList()
        }
        val builder = Request.Builder().url(urlStr).header("User-Agent", pcUa)
        when (engine) {
            "baidu" -> builder.header("Referer", "https://image.baidu.com/")
            "sogou" -> builder.header("Referer", "https://pic.sogou.com/")
        }
        val body = okHttpClient.newCall(builder.build()).await().body?.string()
            ?: return emptyList()
        return when (engine) {
            "baidu" -> parseBaidu(body)
            "bing" -> parseBing(body)
            "sogou" -> parseSogou(body)
            "google" -> parseGoogle(body)
            else -> emptyList()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun parseBaidu(body: String): List<String> {
        val list = linkedSetOf<String>()
        Regex("\"thumbURL\":\"(https?://[^\"]+)\"").findAll(body).forEach {
            list.add(it.groupValues[1].replace("\\/", "/"))
        }
        return list.toList()
    }

    private fun parseBing(body: String): List<String> {
        val unescaped = body.replace("&quot;", "\"")
        val list = linkedSetOf<String>()
        Regex("\"turl\":\"(https?://[^\"]+)\"").findAll(unescaped).forEach {
            list.add(it.groupValues[1])
        }
        return list.toList()
    }

    private fun parseSogou(body: String): List<String> {
        val list = linkedSetOf<String>()
        Regex("\"picUrl\":\"(https?://[^\"]+)\"").findAll(body).forEach {
            list.add(it.groupValues[1].replace("\\/", "/"))
        }
        return list.toList()
    }

    private fun parseGoogle(body: String): List<String> {
        val list = linkedSetOf<String>()
        Regex("https://encrypted-tbn\\d\\.gstatic\\.com/images\\?[^\"]+").findAll(body).forEach {
            list.add(it.value)
        }
        return list.toList()
    }

    private fun stopSearch() {
        task?.cancel()
        searchPool?.close()
    }

    override fun onCleared() {
        super.onCleared()
        searchPool?.close()
    }

}