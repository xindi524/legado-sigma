package io.legado.app.ui.book.cloud

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemCloudFileBinding
import io.legado.app.help.cloud.BaiduPanHelp.CloudFile
import io.legado.app.lib.theme.primaryColor

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
        ivIcon.setColorFilter(root.context.primaryColor)
        tvName.text = item.name
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
    }

}
