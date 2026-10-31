package io.legado.app.help.cloud

import android.net.Uri
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

/**
 * F5：百度网盘（官方开放平台 xpan 接口，implicit OAuth 授权）
 * 授权页返回的 login_success 地址 fragment 中携带 accesstoken，无需 client_secret
 */
object BaiduPanHelp {

    /**
     * 百度网盘开放平台应用凭据（client_id / AppKey）
     * 来源：本项目自行申请（pan.baidu.com/union → 个人使用档，免审核，限10个授权用户）
     * 若需更换：登录开放平台控制台 → 应用详情 → 复制 AppKey 替换下方常量即可
     * 注意：授权回调地址需在开放平台「应用详情 → 安全设置」中配置为
     *       http://openapi.baidu.com/oauth/2.0/login_success
     */
    const val CLIENT_ID = "APi4NwmgHDVEi0u7L4DavCGKvvSVsWHe"

    const val AUTH_URL =
        "https://openapi.baidu.com/oauth/2.0/authorize?response_type=token" +
                "&client_id=$CLIENT_ID" +
                "&redirect_uri=http://openapi.baidu.com/oauth/2.0/login_success" +
                "&scope=basic,netdisk&display=mobile"
    private const val PAN_HOST = "https://pan.baidu.com"
    private const val DOWNLOAD_UA = "pan.baidu.com"
    private const val PREF = "cloud_baidu"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_ACCOUNTS = "accounts_json"
    private const val KEY_ACTIVE = "active_id"

    /**
     * 多账号支持：账号列表 + 当前激活账号；token 全部经由 currentToken() 获取
     */
    class Account(
        val id: String,
        val name: String,
        val token: String,
        val expiresAt: Long
    )

    fun accounts(): List<Account> = try {
        val arr = JSONArray(prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Account(it.optString("id"), it.optString("name"), it.optString("token"), it.optLong("exp"))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveAccounts(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(org.json.JSONObject().put("id", a.id).put("name", a.name)
                .put("token", a.token).put("exp", a.expiresAt))
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    fun activeAccount(): Account? {
        val id = prefs.getString(KEY_ACTIVE, null)
        val list = accounts()
        return (id?.let { aid -> list.find { it.id == aid } }) ?: list.firstOrNull()?.also {
            prefs.edit().putString(KEY_ACTIVE, it.id).apply()
        }
    }

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    private fun currentToken(): String? {
        val a = activeAccount() ?: return null
        return a.token.takeIf { a.expiresAt > System.currentTimeMillis() }
    }

    /**
     * 添加或更新账号（uk 相同视为同一账号），并设为激活；返回是否为新账号
     */
    fun addAccount(name: String, token: String, expiresAt: Long, uk: String): Boolean {
        val list = accounts().toMutableList()
        val idx = list.indexOfFirst { it.id == uk }
        val isNew = idx < 0
        if (isNew) list.add(Account(uk, name, token, expiresAt))
        else list[idx] = Account(uk, name, token, expiresAt)
        saveAccounts(list)
        setActive(uk)
        return isNew
    }

    fun removeActive() {
        val id = activeAccount()?.id
        val list = accounts().toMutableList()
        list.removeAll { it.id == id }
        saveAccounts(list)
        setActive(list.firstOrNull()?.id ?: "")
    }

    fun clearToken() {
        prefs.edit().remove(KEY_ACCOUNTS).remove(KEY_ACTIVE)
            .remove(KEY_TOKEN).remove(KEY_EXPIRES).apply()
    }

    /**
     * 获取账号信息（百度昵称与 uk），token 可显式传入（授权完成时账号尚未入库）
     */
    suspend fun uinfoWithToken(token: String): Pair<String, String> = withContext(IO) {
        val json = JSONObject(
            okHttpClient.newCall(
                Request.Builder().url("$PAN_HOST/rest/2.0/xpan/nas?method=uinfo&access_token=$token")
                    .header("User-Agent", DOWNLOAD_UA)
                    .build()
            ).await().body?.string() ?: "{}"
        )
        val name = json.optString("baidu_name").ifBlank { "百度账号" }
        val uk = json.optLong("uk").let { if (it != 0L) it.toString() else "u${System.currentTimeMillis()}" }
        name to uk
    }

    private fun migrateLegacy() {
        if (prefs.getString(KEY_ACCOUNTS, null) != null) return
        val legacy = prefs.getString(KEY_TOKEN, null) ?: return
        val exp = prefs.getLong(KEY_EXPIRES, 0L)
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).apply()
        if (exp <= System.currentTimeMillis()) return
        saveAccounts(listOf(Account("legacy", "默认账号", legacy, exp)))
        setActive("legacy")
    }

    init {
        migrateLegacy()
    }

    fun hasToken(): Boolean = currentToken() != null

    fun saveToken(token: String, expiresIn: Long) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + (expiresIn - 86400) * 1000)
            .apply()
    }

    data class CloudFile(
        val fsId: Long,
        val path: String,
        val name: String,
        val size: Long,
        val isDir: Boolean
    )

    private val prefs get() = appCtx.getSharedPreferences(PREF, 0)


    /**
     * 从授权完成跳转的 login_success 地址中提取 accesstoken
     */
    fun extractToken(url: String): Pair<String, Long>? {
        if (!url.contains("login_success")) return null
        val uri = Uri.parse(url)
        val fragment = uri.fragment ?: return null
        val params = fragment.split("&")
        var token: String? = null
        var expiresIn = 0L
        for (p in params) {
            val kv = p.split("=", limit = 2)
            when (kv.getOrNull(0)) {
                "access_token", "accesstoken" -> token = kv.getOrNull(1)
                "expires_in" -> expiresIn = kv.getOrNull(1)?.toLongOrNull() ?: 0L
            }
        }
        return token?.let { it to expiresIn }
    }

    private suspend fun apiGet(params: Map<String, String>): JSONObject = withContext(IO) {
        val token = currentToken() ?: throw Exception("百度网盘未授权")
        val sb = StringBuilder("$PAN_HOST/rest/2.0/xpan/file?")
        sb.append("access_token=").append(token)
        for ((k, v) in params) {
            sb.append('&').append(k).append('=').append(v)
        }
        val request = Request.Builder().url(sb.toString())
            .header("User-Agent", DOWNLOAD_UA)
            .build()
        val body = okHttpClient.newCall(request).await().body?.string() ?: "{}"
        val json = JSONObject(body)
        val errno = json.optInt("errno", -1)
        if (errno != 0) {
            throw Exception("百度网盘接口错误 errno=$errno" + when (errno) {
                -6 -> "（授权已失效，请重新授权）"
                12 -> "（应用无此目录访问权限，请把书籍文件放到网盘的“我的应用数据”目录下）"
                else -> ""
            })
        }
        json
    }

    /**
     * 创建网盘目录（百度 create 接口会自动创建父目录）
     */
    private suspend fun createDir(path: String): Boolean = withContext(IO) {
        val token = currentToken() ?: return@withContext false
        val url = "$PAN_HOST/rest/2.0/xpan/file?method=create&access_token=$token"
        val body = okhttp3.FormBody.Builder()
            .add("path", path)
            .add("isdir", "1")
            .add("block_list", "[]")
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", DOWNLOAD_UA)
            .post(body)
            .build()
        try {
            val resp = okHttpClient.newCall(request).await().body?.string() ?: return@withContext false
            JSONObject(resp).optInt("errno", -1) == 0
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchList(dir: String): List<CloudFile> {
        val json = apiGet(
            mapOf(
                "method" to "list",
                "dir" to (java.net.URLEncoder.encode(dir, "UTF-8")),
                "limit" to "1000",
                "web" to "1",
                "order" to "name"
            )
        )
        val array = json.optJSONArray("list") ?: JSONArray()
        val result = arrayListOf<CloudFile>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            result.add(
                CloudFile(
                    fsId = item.optLong("fs_id"),
                    path = item.optString("path"),
                    name = item.optString("server_filename"),
                    size = item.optLong("size"),
                    isDir = item.optInt("isdir") == 1
                )
            )
        }
        return result
    }

    /**
     * 列出网盘目录；目录不存在（errno 9/-9）时自动创建后重试一次
     */
    suspend fun list(dir: String): List<CloudFile> {
        val result = try {
            fetchList(dir)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("errno=-9") || msg.contains("errno=9")) {
                createDir(dir)
                fetchList(dir)
            } else {
                throw e
            }
        }
        return result.sortedWith(
            compareByDescending<CloudFile> { it.isDir }.thenBy { it.name }
        )
    }

    /**
     * 服务端语义搜索（Readingo 同款 unisearch 接口，瞬时返回全盘匹配）
     * POST /xpan/unisearch?access_token=..&scene=mcpserver&query=..&search_type=1&num=20，body 为 "{}"
     * 响应 data[].list[] 内含 path/size/fsid/filename，可直接用于下载
     */
    suspend fun unisearch(keyword: String): List<CloudFile> = withContext(IO) {
        val token = currentToken() ?: throw Exception("百度网盘未授权")
        val query = mapOf(
            "access_token" to token,
            "scene" to "mcpserver",
            "query" to keyword,
            "search_type" to "1",
            "num" to "20"
        )
        val sb = StringBuilder("$PAN_HOST/xpan/unisearch?")
        sb.append(query.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        })
        val body = "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(sb.toString())
            .header("User-Agent", DOWNLOAD_UA)
            .post(body)
            .build()
        val resp = okHttpClient.newCall(request).await()
        val json = JSONObject(resp.body?.string() ?: "{}")
        if (json.optInt("error_no", -1) != 0) {
            throw Exception("语义搜索失败 errno=${json.optInt("error_no")}")
        }
        val out = ArrayList<CloudFile>()
        val data = json.optJSONArray("data") ?: JSONArray()
        for (i in 0 until data.length()) {
            val list = data.optJSONObject(i)?.optJSONArray("list") ?: continue
            for (j in 0 until list.length()) {
                val o = list.optJSONObject(j) ?: continue
                out.add(
                    CloudFile(
                        fsId = o.optLong("fsid"),
                        path = o.optString("path"),
                        name = o.optString("filename"),
                        size = o.optLong("size"),
                        isDir = o.optInt("isdir") == 1
                    )
                )
            }
        }
        out.sortedWith(
            compareByDescending<CloudFile> { it.isDir }.thenBy { it.name }
        )
    }

    /**
     * 递归收集目录树全部条目（客户端并发池，8 并发，目录上限 400）
     * onProgress 实时回调 (已扫描目录数, 已收集条目数)
     */
    suspend fun collectAll(
        startDir: String = "/",
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> }
    ): List<CloudFile> = coroutineScope {
        val results = java.util.Collections.synchronizedList(ArrayList<CloudFile>())
        val visited = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        val scanned = java.util.concurrent.atomic.AtomicInteger(0)
        val sem = Semaphore(8)
        fun spawn(dir: String): Job = launch {
            if (!visited.add(dir)) return@launch
            val n = scanned.incrementAndGet()
            if (n > 1500) return@launch
            var subdirs: List<String> = emptyList()
            sem.withPermit {
                val items = try {
                    fetchList(dir)
                } catch (e: Exception) {
                    emptyList()
                }
                val subs = ArrayList<String>()
                for (f in items) {
                    results.add(f)
                    if (f.isDir) subs.add(f.path)
                }
                subdirs = subs
            }
            onProgress(n, results.size)
            for (d in subdirs) spawn(d)
        }
        spawn(startDir)
        results
    }

    /**
     * 匹配度打分（用于过滤与排序，分数越高越相关）：
     * 文件名包含完整搜索词：2000 - 首次出现位置（越早越高）
     * 子序列匹配（可缺字漏字有间隔，不能错字乱序）：1000 - 间隔总和*5
     * 模糊（仅 fuzzy 开）：搜索词中≥50%字符命中：500 + 命中字数
     * 不匹配：-1
     */
    fun matchScore(lowerName: String, lowerKw: String, fuzzy: Boolean): Int {
        if (lowerKw.isEmpty()) return 0
        val idx = lowerName.indexOf(lowerKw)
        if (idx >= 0) return 2000 - idx
        var i = 0
        var lastIdx = -1
        var gap = 0
        for ((k, c) in lowerName.withIndex()) {
            if (i < lowerKw.length && c == lowerKw[i]) {
                if (lastIdx >= 0) gap += k - lastIdx - 1
                lastIdx = k
                i++
            }
        }
        if (i == lowerKw.length) return 1000 - gap * 5
        if (fuzzy) {
            var hit = 0
            for (c in lowerKw) {
                if (lowerName.contains(c)) hit++
            }
            if (hit * 2 >= lowerKw.length) return 500 + hit
        }
        return -1
    }

    /**
     * 递归搜索目录树（默认从根目录全盘），按匹配度打分过滤并排序
     */
    suspend fun searchAll(
        startDir: String,
        lowerKw: String,
        fuzzy: Boolean,
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> }
    ): List<CloudFile> {
        val matched = ArrayList<Pair<Int, CloudFile>>()
        for (f in collectAll(startDir) { s, _ ->
            onProgress(s, matched.size)
        }) {
            val score = matchScore(f.name.lowercase(), lowerKw, fuzzy)
            if (score >= 0) matched.add(score to f)
        }
        return matched.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * 精确匹配：子序列规则——搜索词每个字符按原顺序出现在文件名中，
     * 允许缺字漏字与中间间隔，不允许错字与乱序
     */
    fun subsequenceMatch(lowerName: String, lowerKw: String): Boolean {
        var i = 0
        for (c in lowerName) {
            if (i < lowerKw.length && c == lowerKw[i]) i++
        }
        return i == lowerKw.length
    }

    /**
     * 分片上传本地文件到网盘（rtype=3 覆盖同名；>4MB 自动分片）
     * errno -10 明确提示空间不足
     */
    suspend fun uploadFile(localFile: File, remotePath: String) = withContext(IO) {
        val token = currentToken() ?: throw Exception("百度网盘未授权")
        val size = localFile.length()
        val chunk = 4L * 1024 * 1024
        // 计算每片 md5
        val md5s = ArrayList<String>()
        localFile.inputStream().use { input ->
            val buf = ByteArray(chunk.toInt())
            val md = java.security.MessageDigest.getInstance("MD5")
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md5s.add(md.digest(buf.copyOf(n)).joinToString("") { "%02x".format(it) })
            }
        }
        // 预上传
        val pre = apiPostForm(
            "method=precreate",
            mapOf(
                "path" to remotePath, "size" to size.toString(), "isdir" to "0",
                "block_list" to JSONArray(md5s).toString(),
                "autoinit" to "1", "rtype" to "3"
            )
        )
        if (pre.optInt("errno", -1) != 0) throw Exception("预上传失败 errno=${pre.optInt("errno")}")
        val uploadid = pre.optString("uploadid")
        // 分片上传（multipart）
        localFile.inputStream().use { input ->
            val buf = ByteArray(chunk.toInt())
            var seq = 0
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                val boundary = "----legado${System.currentTimeMillis()}$seq"
                val header = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
                val footer = "\r\n--$boundary--\r\n".toByteArray()
                val data = buf.copyOf(n)
                val out = java.io.ByteArrayOutputStream(header.size + data.size + footer.size)
                out.write(header)
                out.write(data)
                out.write(footer)
                val part = out.toByteArray()
                val url = "$PAN_HOST/rest/2.0/pcs/superfile2?method=upload&access_token=$token&type=obj" +
                        "&path=${java.net.URLEncoder.encode(remotePath, "UTF-8")}&uploadid=$uploadid&partseq=$seq"
                val resp = okHttpClient.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", DOWNLOAD_UA)
                        .post(part.toRequestBody("multipart/form-data; boundary=$boundary".toMediaTypeOrNull()))
                        .build()
                ).await()
                val j = JSONObject(resp.body?.string() ?: "{}")
                if (j.optInt("errno", -1) != 0) throw Exception("分片上传失败 errno=${j.optInt("errno")}")
                seq++
            }
        }
        // 创建文件
        val now = (System.currentTimeMillis() / 1000).toString()
        val create = apiPostForm(
            "method=create",
            mapOf(
                "path" to remotePath, "size" to size.toString(), "uploadid" to uploadid,
                "block_list" to JSONArray(md5s).toString(), "rtype" to "3",
                "local_mtime" to now, "local_ctime" to now
            )
        )
        val errno = create.optInt("errno", -1)
        if (errno != 0) {
            throw Exception(
                if (errno == -10) "网盘空间不足，请清理后重试"
                else "上传创建失败 errno=$errno"
            )
        }
    }

    /**
     * xpan/file 的 form POST（method 放 query，参数放 form）
     */
    private suspend fun apiPostForm(method: String, form: Map<String, String>): JSONObject = withContext(IO) {
        val token = currentToken() ?: throw Exception("百度网盘未授权")
        val body = okhttp3.FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url("$PAN_HOST/rest/2.0/xpan/file?$method&access_token=$token")
            .header("User-Agent", DOWNLOAD_UA)
            .post(body)
            .build()
        JSONObject(okHttpClient.newCall(request).await().body?.string() ?: "{}")
    }

    /**
     * 获取文件下载直链（返回 info 数组，UA 须为 pan.baidu.com）
     */
    suspend fun getDownloadLink(fsId: Long): String {
        val token = currentToken() ?: throw Exception("百度网盘未授权")
        val json = apiGet(
            mapOf(
                "method" to "filemetas",
                "fsids" to "[$fsId]",
                "dlink" to "1"
            )
        )
        val item = json.optJSONArray("info")?.optJSONObject(0)
            ?: json.optJSONArray("list")?.optJSONObject(0)
            ?: throw Exception("未获取到下载链接")
        val dlink = item.optString("dlink")
        if (dlink.isBlank()) throw Exception("下载链接为空")
        return if (dlink.contains("access_token=")) dlink else "$dlink&access_token=$token"
    }

    /**
     * 下载到输出流；支持断点续传（Range）与字节进度回调，响应协程取消（暂停）
     */
    suspend fun downloadTo(
        fsId: Long,
        output: java.io.OutputStream,
        startOffset: Long = 0L,
        onProgress: ((Long) -> Unit)? = null
    ) = withContext(IO) {
        val dlink = getDownloadLink(fsId)
        val builder = Request.Builder().url(dlink)
            .header("User-Agent", DOWNLOAD_UA)
        if (startOffset > 0) builder.header("Range", "bytes=$startOffset-")
        val response = okHttpClient.newCall(builder.build()).await()
        val body = response.body ?: throw Exception("下载失败：响应为空")
        var done = startOffset
        body.byteStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                ensureActive()
                val n = input.read(buf)
                if (n == -1) break
                output.write(buf, 0, n)
                done += n
                onProgress?.invoke(done)
            }
        }
    }

}
