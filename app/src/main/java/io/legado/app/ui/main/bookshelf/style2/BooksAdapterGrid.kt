package io.legado.app.ui.main.bookshelf.style2

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ItemBookshelfGrid2Binding
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.databinding.ItemBookshelfGridGroup2Binding
import io.legado.app.databinding.ItemBookshelfGridGroupBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.views.onLongClick

@Suppress("UNUSED_PARAMETER")
class BooksAdapterGrid(context: Context, callBack: CallBack) :
    BaseBooksAdapter<RecyclerView.ViewHolder>(context, callBack) {
    private val showBookname = AppConfig.showBookname

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> {
                when (showBookname) {
                    2 -> GroupViewHolder2(ItemBookshelfGridGroup2Binding.inflate(inflater, parent, false))
                    else -> GroupViewHolder(ItemBookshelfGridGroupBinding.inflate(inflater, parent, false))
                }
            }
            else -> {
                when (showBookname) {
                    2 -> BookViewHolder2(ItemBookshelfGrid2Binding.inflate(inflater, parent, false))
                    else -> BookViewHolder(ItemBookshelfGridBinding.inflate(inflater, parent, false))
                }
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        when (holder) {
            is BookViewHolder -> (getItem(position) as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }

            is BookViewHolder2 -> (getItem(position) as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }

            is GroupViewHolder -> (getItem(position) as? BookGroup)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }

            is GroupViewHolder2 -> (getItem(position) as? BookGroup)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }
        }
    }

    inner class BookViewHolder(val binding: ItemBookshelfGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book, position: Int) = binding.run {
            if (showBookname == 1) {
                tvName.gone()
            } else {
                tvName.visible()
                tvName.text = item.name
            }
            ivCover.load(item, false)
            upRefresh(this, item)
        }

        fun onBind(item: Book, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvName.text = item.name
                            "cover" -> ivCover.load(
                                item,
                                false
                            )

                            "refresh" -> upRefresh(this, item)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

        private fun upRefresh(binding: ItemBookshelfGridBinding, item: Book) {
            if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                binding.bvUnread.invisible()
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.inVisible()
                if (AppConfig.showUnread) {
                    binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
                    binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                } else {
                    binding.bvUnread.invisible()
                }
            }
        }

    }

    inner class BookViewHolder2(val binding: ItemBookshelfGrid2Binding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book, position: Int) = binding.run {
            tvName.text = item.name
            ivCover.load(item, false)
            upRefresh(this, item)
        }

        fun onBind(item: Book, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvName.text = item.name
                            "cover" -> ivCover.load(
                                item,
                                false
                            )

                            "refresh" -> upRefresh(this, item)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

        private fun upRefresh(binding: ItemBookshelfGrid2Binding, item: Book) {
            if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                binding.bvUnread.invisible()
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.inVisible()
                if (AppConfig.showUnread) {
                    binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
                    binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                } else {
                    binding.bvUnread.invisible()
                }
            }
        }

    }

    inner class GroupViewHolder(val binding: ItemBookshelfGridGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookGroup, position: Int) = binding.run {
            if (showBookname == 1) {
                tvName.gone()
            } else {
                tvName.visible()
                tvName.text = item.groupName
            }
            upCover(item)
        }

        // F2 分组拼图：自定义封面 > 组内书封面拼图(最近读优先) > 默认封面占位
        fun upCover(item: BookGroup) = binding.run {
            val preview = appDb.bookDao.getBooksForGroupPreview(item.groupId, 4)
            if (!item.cover.isNullOrBlank()) {
                ivCover.visible()
                llMosaic.gone()
                ivCover.load(item.cover)
            } else if (preview.isEmpty()) {
                ivCover.visible()
                llMosaic.gone()
                ivCover.load(item.cover)
            } else {
                ivCover.gone()
                llMosaic.visible()
                val views = listOf(ivMosaic1, ivMosaic2, ivMosaic3, ivMosaic4)
                views.forEachIndexed { i, iv ->
                    val book = preview.getOrNull(i)
                    if (book == null) {
                        iv.invisible()
                    } else {
                        iv.visible()
                        iv.load(book, false)
                    }
                }
            }
        }

        fun onBind(item: BookGroup, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "groupName" -> tvName.text = item.groupName
                            "cover" -> upCover(item)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

    }

    inner class GroupViewHolder2(val binding: ItemBookshelfGridGroup2Binding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookGroup, position: Int) = binding.run {
            item.groupName.let {
                if (it.isBlank()) {
                    tvName.gone()
                } else{
                    tvName.visible()
                    tvName.text = it
                }
            }
            upCover(item)
        }

        // F2 分组拼图：自定义封面 > 组内书封面拼图(最近读优先) > 默认封面占位
        fun upCover(item: BookGroup) = binding.run {
            val preview = appDb.bookDao.getBooksForGroupPreview(item.groupId, 4)
            if (!item.cover.isNullOrBlank()) {
                ivCover.visible()
                llMosaic.gone()
                ivCover.load(item.cover)
            } else if (preview.isEmpty()) {
                ivCover.visible()
                llMosaic.gone()
                ivCover.load(item.cover)
            } else {
                ivCover.gone()
                llMosaic.visible()
                val views = listOf(ivMosaic1, ivMosaic2, ivMosaic3, ivMosaic4)
                views.forEachIndexed { i, iv ->
                    val book = preview.getOrNull(i)
                    if (book == null) {
                        iv.invisible()
                    } else {
                        iv.visible()
                        iv.load(book, false)
                    }
                }
            }
        }

        fun onBind(item: BookGroup, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach { key ->
                        when (key) {
                            "groupName" -> item.groupName.let {
                                if (it.isBlank()) {
                                    tvName.gone()
                                } else{
                                    tvName.visible()
                                    tvName.text = it
                                }
                            }
                            "cover" -> upCover(item)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

    }

}