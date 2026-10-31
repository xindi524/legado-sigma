package io.legado.app.help.cloud

import android.widget.Toast
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
    private var cacheFile = File(appCtx.cacheDir, CACHE_FILE)
    private var accountKey = "default"
    private var building = false
    private var lastNotify = 0L

    var index: List<Entry>? = null
        private set
    var indexTime: Long = 0L
        private set
    var onUpdate: (() -> Unit)? = null

    /** 重建进度（已扫描目录数） */
    var scanned: Int = 0
        private set
    var lastError: String? = null
        private set

    fun progressText(): String = "索引更新中…已扫描 $scanned 个文件夹"

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

    /**
     * 切换账号：加载对应账号的索引缓存
     */
    fun setAccount(id: String) {
        val key = id.ifBlank { "default" }
        if (key == accountKey && index != null) return
        accountKey = key
        index = null
        indexTime = 0
        cacheFile = File(appCtx.cacheDir, "cloud_pan_index_$key.json")
        load()
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
        scanned = 0
        lastError = null
        notifyUpdate(true)
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    BaiduPanHelp.collectAll("/") { s, _ ->
                        scanned = s
                        notifyUpdate()
                    }
                }
                index = list.map { Entry(it.path, it.name, it.isDir, it.size, it.fsId) }
                indexTime = System.currentTimeMillis()
                withContext(Dispatchers.IO) { save() }
                Toast.makeText(appCtx, "网盘索引已就绪（${index?.size ?: 0} 项）", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // 保留旧索引，但把失败显式告知
                lastError = e.message ?: "未知错误"
                Toast.makeText(appCtx, "索引更新失败：$lastError", Toast.LENGTH_LONG).show()
            } finally {
                building = false
                notifyUpdate(true)
            }
        }
    }

    /**
     * 用索引做全盘过滤（内存瞬时完成），按匹配度打分排序
     */
    fun search(lowerKw: String, fuzzy: Boolean): List<Entry> {
        val all = index ?: return emptyList()
        return all.mapNotNull { e ->
            val score = BaiduPanHelp.matchScore(e.name.lowercase(), lowerKw, fuzzy)
            if (score >= 0) score to e else null
        }.sortedByDescending { it.first }
            .map { it.second }
    }

    private fun notifyUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 300) return
        lastNotify = now
        scope.launch { onUpdate?.invoke() }
    }

}
