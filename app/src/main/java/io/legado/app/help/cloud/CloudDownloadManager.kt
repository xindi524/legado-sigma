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
 * 支持多文件队列：多个文件依次下载（并发 1），全部完成状态自动关闭
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

    // 等待队列：当前任务完成后自动逐个开始
    private val pendingFiles = ArrayDeque<BaiduPanHelp.CloudFile>()
    val pendingCount: Int
        get() = pendingFiles.size

    var onUpdate: (() -> Unit)? = null

    private var lastNotify = 0L

    private fun notifyUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 200) return
        lastNotify = now
        scope.launch { onUpdate?.invoke() }
    }

    /**
     * 加入下载队列：空闲立即开始，否则排队（同名文件去重）
     */
    fun start(file: BaiduPanHelp.CloudFile) {
        if (task?.file?.path == file.path) return
        if (pendingFiles.any { it.path == file.path }) return
        if (task == null) {
            download(file)
        } else {
            pendingFiles.addLast(file)
            notifyUpdate(true)
        }
    }

    private fun download(file: BaiduPanHelp.CloudFile) {
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
                // 用户暂停：保留任务与断点，不继续队列
                t.state = State.PAUSED
                notifyUpdate(true)
                return@launch
            } catch (e: NoBooksDirException) {
                t.state = State.FAILED
                t.needReselect = true
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "书籍保存位置不可用，请重新选择后继续", Toast.LENGTH_SHORT).show()
                }
                // 保存位置问题后续任务也会失败：挂起队列等用户处理
                notifyUpdate(true)
                return@launch
            } catch (e: SecurityException) {
                t.state = State.FAILED
                t.needReselect = true
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "书籍保存位置不可用，请重新选择后继续", Toast.LENGTH_SHORT).show()
                }
                notifyUpdate(true)
                return@launch
            } catch (e: Exception) {
                // 保留临时文件供断点续传；单个文件失败不影响队列后续任务
                t.state = State.FAILED
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "「${t.file.name}」下载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            // 当前任务结束（成功/普通失败）：取下一个，全部结束自动关闭
            val next = pendingFiles.removeFirstOrNull()
            task = null
            if (next != null) {
                download(next)
            } else {
                notifyUpdate(true)
            }
        }
    }

    fun pause() {
        if (task?.state == State.RUNNING) job?.cancel()
    }

    fun resume() {
        val t = task ?: return
        when {
            // 继续暂停的下载（断点续传）
            t.state == State.PAUSED -> download(t.file)
            // 保存位置修复后重试当前任务，并恢复后续队列
            t.state == State.FAILED && t.needReselect -> download(t.file)
        }
    }

    fun clear() {
        job?.cancel()
        task = null
        pendingFiles.clear()
        notifyUpdate(true)
    }

}
