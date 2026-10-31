package io.legado.app.help.cloud

import android.widget.Toast
import io.legado.app.exception.NoBooksDirException
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

/**
 * 云端书库下载管理：应用级后台执行（不绑定页面生命周期）、断点续传、暂停/继续
 * 下载完成后自动保存到书籍保存位置并导入书架
 */
object CloudDownloadManager {

    enum class State { RUNNING, PAUSED, DONE, FAILED }

    class Task(
        val file: BaiduPanHelp.CloudFile,
        var state: State = State.RUNNING,
        var downloaded: Long = 0L,
        var needReselect: Boolean = false
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    var task: Task? = null
        private set
    var onUpdate: (() -> Unit)? = null

    private var lastNotify = 0L

    private fun notifyUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 200) return
        lastNotify = now
        scope.launch { onUpdate?.invoke() }
    }

    /**
     * 开始（或续传）下载；已有任务运行中时忽略
     */
    fun start(file: BaiduPanHelp.CloudFile) {
        if (task?.state == State.RUNNING) return
        task = Task(file)
        notifyUpdate(true)
        job = scope.launch {
            val t = task ?: return@launch
            val tmp = File(appCtx.cacheDir, "cloud_${t.file.name}")
            try {
                val bookName = withContext(Dispatchers.IO) {
                    val startOffset = if (tmp.exists()) tmp.length() else 0L
                    t.downloaded = startOffset
                    if (startOffset < t.file.size) {
                        FileOutputStream(tmp, true).use { out ->
                            BaiduPanHelp.downloadTo(t.file.fsId, out, startOffset) { done ->
                                t.downloaded = done
                                notifyUpdate()
                            }
                        }
                    }
                    val uri = LocalBook.saveBookFile(tmp.inputStream(), t.file.name)
                    tmp.delete()
                    val book = LocalBook.importFile(uri)
                    book.name
                }
                t.state = State.DONE
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "《$bookName》已加入书架", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                t.state = State.PAUSED
            } catch (e: NoBooksDirException) {
                t.state = State.FAILED
                t.needReselect = true
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "书籍保存位置不可用，请重新选择", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                t.state = State.FAILED
                t.needReselect = true
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "书籍保存位置不可用，请重新选择", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // 保留临时文件供断点续传
                t.state = State.FAILED
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            notifyUpdate(true)
        }
    }

    fun pause() {
        if (task?.state == State.RUNNING) job?.cancel()
    }

    fun resume() {
        task?.let { t ->
            if (t.state == State.PAUSED) start(t.file)
        }
    }

    fun clear() {
        job?.cancel()
        task = null
        notifyUpdate(true)
    }

}
