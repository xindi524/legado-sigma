package io.legado.app.help

import android.content.Context
import android.net.Uri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import splitties.init.appCtx

/**
 * F7：PDF 转 TXT
 * 使用 Apache PDFBox (pdfbox-android, Apache-2.0) 提取文字层内容，
 * 逐页提取后以空行分隔，转换结果保存到书籍保存位置并返回 TXT 的 Uri。
 * 扫描版（无文字层）PDF 会抛出明确错误提示。
 */
object PdfConverter {

    @Volatile
    private var inited = false

    private fun ensureInit() {
        if (!inited) {
            synchronized(this) {
                if (!inited) {
                    PDFBoxResourceLoader.init(appCtx)
                    inited = true
                }
            }
        }
    }

    /**
     * 将 PDF 转为 TXT 并保存到书籍保存位置，返回 TXT 的 Uri。
     * fileName 为原始 PDF 文件名（含 .pdf 后缀），输出同名 .txt。
     */
    suspend fun convertToTxt(pdfUri: Uri, fileName: String): Uri = withContext(Dispatchers.IO) {
        ensureInit()
        val text = extractText(pdfUri)
        if (text.trim().length < 20) {
            throw NoStackTraceException("该 PDF 没有可提取的文字内容（可能为扫描版/图片版），暂不支持转换")
        }
        val txtName = fileName.removeSuffix(".pdf").removeSuffix(".PDF") + ".txt"
        LocalBook.saveBookFile(text, txtName)
    }

    /**
     * 提取 PDF 全部文字，页与页之间以空行分隔
     */
    fun extractText(pdfUri: Uri): String {
        ensureInit()
        val context: Context = appCtx
        PDDocument.load(context.contentResolver.openInputStream(pdfUri)!!).use { doc ->
            val sb = StringBuilder()
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val pages = doc.numberOfPages
            for (page in 1..pages) {
                stripper.startPage = page
                stripper.endPage = page
                sb.append(stripper.getText(doc))
                if (page < pages) {
                    sb.append("\n\n")
                }
            }
            return sb.toString()
        }
    }
}
