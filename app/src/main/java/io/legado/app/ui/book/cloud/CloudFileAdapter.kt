package io.legado.app.ui.book.cloud

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemCloudFileBinding
import io.legado.app.help.cloud.BaiduPanHelp.CloudFile
import io.legado.app.help.cloud.CloudDownloadManager

class CloudFileAdapter(
    context: Context,
    val callBack: CallBack
) : DiffRecyclerAdapter<CloudFile, ItemCloudFileBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<CloudFile>
        get() = object : DiffUtil.ItemCallback<CloudFile>() {
            override fun areItemsTheSame(oldItem: CloudFile, newItem: CloudFile): Boolean {
                return oldItem.path == newItem.path
            }

            override fun areContentsTheSame(oldItem: CloudFile, newItem: CloudFile): Boolean {
                return oldItem == newItem
            }
        }

    override fun getViewBinding(parent: ViewGroup): ItemCloudFileBinding {
        return ItemCloudFileBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemCloudFileBinding,
        item: CloudFile,
        payloads: MutableList<Any>
    ) = binding.run {
        val task = callBack.currentTask()
        val isTaskFile = task?.file?.path == item.path
        if (item.isDir) {
            ivIcon.setImageResource(R.drawable.ic_cfg_backup)
            tvSize.text = "文件夹"
        } else {
            ivIcon.setImageResource(R.drawable.ic_cloud_file)
            tvSize.text = when {
                item.size >= 1024 * 1024 -> String.format("%.1f MB", item.size / 1048576f)
                item.size >= 1024 -> String.format("%.0f KB", item.size / 1024f)
                else -> "${item.size} B"
            }
        }
        if (isTaskFile) {
            cvProgress.visibility = android.view.View.VISIBLE
            val pct = if (task.file.size > 0) {
                (task.downloaded * 100 / task.file.size).toInt().coerceIn(0, 100)
            } else 0
            cvProgress.progress = pct
            cvProgress.paused = task.state == CloudDownloadManager.State.PAUSED
            cvProgress.done = task.state == CloudDownloadManager.State.DONE
            cvProgress.onToggle = { callBack.toggleTask() }
            tvSize.visibility = android.view.View.GONE
            tvName.text = when (task.state) {
                CloudDownloadManager.State.RUNNING -> "${item.name}　下载中"
                CloudDownloadManager.State.PAUSED -> "${item.name}　已暂停"
                CloudDownloadManager.State.DONE -> "${item.name}　已完成"
                CloudDownloadManager.State.FAILED -> "${item.name}　失败"
            }
        } else {
            cvProgress.visibility = android.view.View.GONE
            tvSize.visibility = android.view.View.VISIBLE
            tvName.text = item.name
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemCloudFileBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.onFileClick(it)
                }
            }
        }
    }

    interface CallBack {
        fun onFileClick(file: CloudFile)
        fun currentTask(): CloudDownloadManager.Task?
        fun toggleTask()
    }

}
