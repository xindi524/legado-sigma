package io.legado.app.model.localBook

import android.net.Uri
import io.legado.app.utils.EncodingDetect
import splitties.init.appCtx
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * F9: 导入时自动扫描书籍元数据（书名/作者）
 * 优先级：EPUB 内嵌 metadata > TXT 文件内容头 > 文件名解析（LocalBook.analyzeNameAuthor 兜底）
 * PDF 雏形阶段走文件名解析，metadata 扫描为后续优化方向。
 * 文件名不改动，书籍详情页"来源"栏本就显示 originName（本地书=文件名）。
 */
object BookMetaScan {

    private const val HEAD_BYTES = 8192

    /**
     * 扫描内嵌元数据，返回 (书名, 作者)，任一项可能为空串；
     * 无法扫描（非支持格式或异常）返回 null，由文件名解析兜底。
     */
    fun scan(uri: Uri, fileName: String): Pair<String, String>? {
        return when {
            fileName.endsWith(".epub", true) -> runCatching { scanEpub(uri) }.getOrNull()
            fileName.endsWith(".txt", true) -> runCatching { scanTxtHead(uri) }.getOrNull()
            else -> null
        }
    }

    /**
     * EPUB：META-INF/container.xml 定位 OPF → 解析 dc:title / dc:creator
     * ZipInputStream 不支持随机访问，OPF 路径来自 container.xml，需两遍遍历。
     */
    private fun scanEpub(uri: Uri): Pair<String, String>? {
        var opfPath: String? = null
        appCtx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry?
                while (opfPath == null) {
                    entry = zip.nextEntry ?: break
                    if (entry.name == "META-INF/container.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        opfPath = Regex("full-path=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
                    }
                }
            }
        } ?: return null
        val path = opfPath ?: return null

        var name: String? = null
        var author: String? = null
        appCtx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry?
                while (name == null || author == null) {
                    entry = zip.nextEntry ?: break
                    if (entry.name == path) {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        name = Regex("<dc:title[^>]*>([^<]+)</dc:title>")
                            .find(xml)?.groupValues?.get(1)?.trim()
                        author = Regex("<dc:creator[^>]*>([^<]+)</dc:creator>")
                            .find(xml)?.groupValues?.get(1)?.trim()
                    }
                }
            }
        }
        if (name.isNullOrBlank() && author.isNullOrBlank()) return null
        return Pair(name ?: "", author ?: "")
    }

    /**
     * TXT：读文件头 8KB，按编码检测解码后匹配常见网文文件头格式
     * 书名：《xxx》 或 书名：xxx；作者：xxx
     */
    private fun scanTxtHead(uri: Uri): Pair<String, String>? {
        appCtx.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(HEAD_BYTES)
            val len = input.read(buffer)
            if (len <= 0) return null
            val headBytes = buffer.copyOf(len)
            val charset = runCatching {
                EncodingDetect.getEncode(headBytes)
            }.getOrNull() ?: "UTF-8"
            val head = String(headBytes, charset(charset))

            val name = Regex("书名[：:]\\s*[《\\[（]?([^》\\]】）\\r\\n]+)[》\\]】）]?")
                .find(head)?.groupValues?.get(1)?.trim()
                ?: Regex("《([^《》\\r\\n]{1,30})》").find(head)?.groupValues?.get(1)?.trim()
            val author = Regex("作者[：:]\\s*([^\\r\\n，,。\\s]{1,30})")
                .find(head)?.groupValues?.get(1)?.trim()

            if (name.isNullOrBlank() && author.isNullOrBlank()) return null
            return Pair(name ?: "", author ?: "")
        }
        return null
    }

    private fun charset(name: String): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
}
