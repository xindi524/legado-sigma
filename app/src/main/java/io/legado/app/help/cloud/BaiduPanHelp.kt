package io.legado.app.help.cloud

import android.net.Uri
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import okhttp3.Request
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
                "&scope=basic,netdisk&display=mobile&force_login=1"
    private const val PAN_HOST = "https://pan.baidu.com"
    private const val DOWNLOAD_UA = "pan.baidu.com"
    private const val PREF = "cloud_baidu"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_EXPIRES = "expires_at"

    data class CloudFile(
        val fsId: Long,
        val path: String,
        val name: String,
        val size: Long,
        val isDir: Boolean
    )

    private val prefs get() = appCtx.getSharedPreferences(PREF, 0)

    fun hasToken(): Boolean = prefs.getString(KEY_TOKEN, null)?.let { token ->
        System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES, 0L)
    } == true

    fun clearToken() {
        prefs.edit().clear().apply()
    }

    fun saveToken(token: String, expiresIn: Long) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + (expiresIn - 86400) * 1000)
            .apply()
    }

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
        val token = prefs.getString(KEY_TOKEN, null) ?: throw Exception("百度网盘未授权")
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
        val token = prefs.getString(KEY_TOKEN, null) ?: return@withContext false
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
     * 获取文件下载直链
     */
    suspend fun getDownloadLink(fsId: Long): String {
        val json = apiGet(
            mapOf(
                "method" to "filemetas",
                "fsids" to "[$fsId]",
                "dlink" to "1"
            )
        )
        val item = json.optJSONArray("list")?.optJSONObject(0)
            ?: throw Exception("未获取到下载链接")
        return item.optString("dlink").also {
            if (it.isBlank()) throw Exception("下载链接为空")
        }
    }

    /**
     * 下载文件到指定位置
     */
    suspend fun download(fsId: Long, saveFile: File): File = withContext(IO) {
        val dlink = getDownloadLink(fsId)
        val request = Request.Builder().url(dlink)
            .header("User-Agent", DOWNLOAD_UA)
            .build()
        val response = okHttpClient.newCall(request).await()
        val body = response.body ?: throw Exception("下载失败：响应为空")
        saveFile.parentFile?.mkdirs()
        body.byteStream().use { input ->
            saveFile.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
        saveFile
    }

}
