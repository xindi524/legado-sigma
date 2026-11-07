package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CoroutineScope

/**
 * 检查更新：GitHub Releases（本仓库 xindi524/legado-sigma）
 * 约定：Release 正文（body）首行形如 "version: 3.26.110701"，
 * 与包内 versionName 按（同长度数字段）字典序比较判断新版本。
 */
@Keep
object AppUpdateGitee : AppUpdate.AppUpdateInterface {

    private const val RELEASE_API =
        "https://api.github.com/repos/xindi524/legado-sigma/releases?per_page=5"

    private data class GhAsset(val name: String?, val browser_download_url: String?)
    private data class GhRelease(
        val tag_name: String?,
        val name: String?,
        val body: String?,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList()
    )

    private fun parseVersion(body: String?): String? {
        // 从 Release 正文提取 "version: x.y.zzzz" 行
        body?.lineSequence()?.firstOrNull { it.trim().startsWith("version:", true) }
            ?.let { line ->
                return line.substringAfter(":", "").trim().takeIf { it.isNotEmpty() }
            }
        return null
    }

    private suspend fun getLatestRelease(): AppUpdate.UpdateInfo {
        val res = okHttpClient.newCallResponse {
            url(RELEASE_API)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        val releases = GSON.fromJsonArray<GhRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            }
        for (release in releases) {
            // 优先取 apk 附件，跳过无附件的发布
            val apkUrl = release.assets
                .firstOrNull { it.browser_download_url?.endsWith(".apk") == true }
                ?.browser_download_url ?: continue
            val versionName = parseVersion(release.body) ?: release.tag_name?.removePrefix("v")
            ?: continue
            return AppUpdate.UpdateInfo(
                versionName,
                release.body ?: "",
                apkUrl,
                release.name ?: release.tag_name ?: "阅读deep"
            )
        }
        throw NoStackTraceException("获取新版本出错")
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            getLatestRelease()
                .takeIf { it.versionName > AppConst.appInfo.versionName }
                ?.let {
                    return@async AppUpdate.UpdateInfo(
                        it.versionName,
                        it.note,
                        it.downloadUrl,
                        it.name
                    )
                }
            throw NoStackTraceException("已是最新版本")
        }.timeout(10000)
    }
}
