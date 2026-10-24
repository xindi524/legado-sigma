package io.legado.app.help.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import splitties.init.appCtx
import java.io.File

/**
 * 云端书库全盘索引缓存：后台静默扫描全盘存本地，搜索时内存过滤（瞬时）
 * 索引超过 10 分钟自动后台重建，重建期间继续使用旧索引
 */
object CloudIndexManager {

    private const val CACHE_FILE = "cloud_pan_index.json"
    private const val MAX_AGE = 10 * 60 * 1000L

    class Entry(
        val path: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val fsId: Long
    ) {
        fun toCloudFile() = BaiduPanHelp.CloudFile(fsId, path, name, size, isDir)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cacheFile by lazy { File(appCtx.cacheDir, CACHE_FILE) }
    private var building = false
    private var lastNotify = 0L

    var index: List<Entry>? = null
        private set
    var indexTime: Long = 0L
        private set
    var onUpdate: (() -> Unit)? = null

    init {
        load()
    }

    private fun load() {
        try {
            if (!cacheFile.exists()) return
            val arr = JSONArray(cacheFile.readText())
            val list = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    Entry(
                        o.optString("p"),
                        o.optString("n"),
                        o.optInt("d") == 1,
                        o.optLong("s"),
                        o.optLong("f")
                    )
                )
            }
            index = list
            indexTime = cacheFile.lastModified()
        } catch (e: Exception) {
            index = null
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            index?.forEach { e ->
                arr.put(
                    org.json.JSONObject()
                        .put("p", e.path)
                        .put("n", e.name)
                        .put("d", if (e.isDir) 1 else 0)
                        .put("s", e.size)
                        .put("f", e.fsId)
                )
            }
            cacheFile.writeText(arr.toString())
        } catch (e: Exception) {
            // 缓存写失败不影响搜索功能
        }
    }

    fun isFresh(): Boolean =
        index != null && System.currentTimeMillis() - indexTime < MAX_AGE

    fun isBuilding(): Boolean = building

    /**
     * 直接从外部收集的全量列表建立索引（如全盘搜索完成后顺手保存）
     */
    fun saveFrom(list: List<BaiduPanHelp.CloudFile>) {
        index = list.map { Entry(it.path, it.name, it.isDir, it.size, it.fsId) }
        indexTime = System.currentTimeMillis()
        scope.launch {
            withContext(Dispatchers.IO) { save() }
        }
    }

    /**
     * 索引缺失或过期时后台重建；重建期间旧索引继续可用
     */
    fun ensureFresh() {
        if (building || isFresh()) return
        rebuild()
    }

    fun rebuild() {
        if (building) return
        building = true
        notifyUpdate(true)
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    BaiduPanHelp.collectAll("/") { _, _ ->
                        notifyUpdate()
                    }
                }
                index = list.map { Entry(it.path, it.name, it.isDir, it.size, it.fsId) }
                indexTime = System.currentTimeMillis()
                withContext(Dispatchers.IO) { save() }
            } catch (e: Exception) {
                // 保留旧索引
            } finally {
                building = false
                notifyUpdate(true)
            }
        }
    }

    /**
     * 用索引做全盘过滤（内存瞬时完成）
     */
    fun search(keyword: String, fuzzy: Boolean): List<Entry> {
        val kw = keyword.lowercase()
        val all = index ?: return emptyList()
        return all.filter { e ->
            val name = e.name.lowercase()
            if (fuzzy) {
                var hit = 0
                for (c in kw) {
                    if (name.contains(c)) hit++
                }
                hit * 2 >= kw.length
            } else {
                name.contains(kw)
            }
        }.sortedWith(
            compareByDescending<Entry> { it.isDir }.thenBy { it.name }
        )
    }

    private fun notifyUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 300) return
        lastNotify = now
        scope.launch { onUpdate?.invoke() }
    }

}
