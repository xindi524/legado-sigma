package io.legado.app.ui.main.bookshelf.style2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ItemBookshelfGrid2Binding
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.databinding.ItemBookshelfGridGroup2Binding
import io.legado.app.databinding.ItemBookshelfGridGroupBinding
import io.legado.app.help.book.isLocal
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.views.onLongClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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

        // F2 分组拼图：拼图素材 = 直属书封面(最近读优先) + 直属子分组封面(子组自定义封面,无则系统默认封面)
        // 全空组显示组名文字封面
        fun upCover(item: BookGroup) = binding.run {
            ivCover.tag = item.groupId
            val preview = appDb.bookDao.getBooksForGroupPreview(item.groupId, 8)
            val childGroups = appDb.bookGroupDao.getByParent(item.groupId)
            if (!item.cover.isNullOrBlank()) {
                ivCover.load(item.cover)
            } else if (preview.isEmpty() && childGroups.isEmpty()) {
                // 空组：生成"组名文字封面"（主题底色+组名居中），醒目且与整体风格统一
                val iv = ivCover
                val ctx = iv.context
                val bmp = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(ctx.primaryColor)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ctx.primaryTextColor
                    textSize = 44f
                    textAlign = Paint.Align.CENTER
                }
                var name = item.groupName
                while (name.isNotEmpty() && p.measureText(name) > 240f) {
                    name = name.dropLast(1)
                }
                if (name != item.groupName && name.length > 1) {
                    name = name.dropLast(1) + "…"
                }
                c.drawText(name, 150f, 215f, p)
                iv.setImageBitmap(bmp)
            } else {
                // IO 线程拼合：直属书在前，直属子分组在后（子组封面=自定义>系统默认），共4格
                val key = item.groupId
                val iv = ivCover
                val ctx = iv.context
                GlobalScope.launch(Dispatchers.IO) {
                    val h = 400
                    val w = h * 3 / 4
                    val bgColor = ctx.primaryColor
                    // 书与子分组按最后阅读时间混排（子分组时间=组内书的最新阅读时间，真进书阅读才更新），取前4
                    val entries = mutableListOf<Pair<Any, Long>>()
                    preview.forEach { b ->
                        entries.add(Pair(b.getDisplayCover(), b.durChapterTime))
                    }
                    childGroups.forEach { cg ->
                        entries.add(
                            Pair(cg.cover ?: R.drawable.image_cover_default, appDb.bookDao.getGroupLastReadTime(cg.groupId))
                        )
                    }
                    val models = entries.sortedByDescending { it.second }.take(4).map { it.first }
                    val bitmaps = models.mapNotNull { m ->
                        try {
                            Glide.with(ctx).asBitmap().load(m)
                                .centerCrop().submit(w / 2, h / 2).get(10, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bitmaps.isEmpty()) return@launch
                    val mosaic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    mosaic.eraseColor(bgColor)
                    val canvas = Canvas(mosaic)
                    val gap = 14f
                    val cw = w / 2f
                    val ch = h / 2f
                    bitmaps.forEachIndexed { i, bmp ->
                        val l = (i % 2) * cw
                        val t = (i / 2) * ch
                        canvas.drawBitmap(bmp, null, RectF(l + gap / 2, t + gap / 2, l + cw - gap / 2, t + ch - gap / 2), null)
                    }
                    withContext(Dispatchers.Main) {
                        if (iv.tag == key) {
                            iv.setImageBitmap(mosaic)
                        }
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

        // F2 分组拼图：拼图素材 = 直属书封面(最近读优先) + 直属子分组封面(子组自定义封面,无则系统默认封面)
        // 全空组显示组名文字封面
        fun upCover(item: BookGroup) = binding.run {
            ivCover.tag = item.groupId
            val preview = appDb.bookDao.getBooksForGroupPreview(item.groupId, 8)
            val childGroups = appDb.bookGroupDao.getByParent(item.groupId)
            if (!item.cover.isNullOrBlank()) {
                ivCover.load(item.cover)
            } else if (preview.isEmpty() && childGroups.isEmpty()) {
                // 空组：生成"组名文字封面"（主题底色+组名居中），醒目且与整体风格统一
                val iv = ivCover
                val ctx = iv.context
                val bmp = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(ctx.primaryColor)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ctx.primaryTextColor
                    textSize = 44f
                    textAlign = Paint.Align.CENTER
                }
                var name = item.groupName
                while (name.isNotEmpty() && p.measureText(name) > 240f) {
                    name = name.dropLast(1)
                }
                if (name != item.groupName && name.length > 1) {
                    name = name.dropLast(1) + "…"
                }
                c.drawText(name, 150f, 215f, p)
                iv.setImageBitmap(bmp)
            } else {
                // IO 线程拼合：直属书在前，直属子分组在后（子组封面=自定义>系统默认），共4格
                val key = item.groupId
                val iv = ivCover
                val ctx = iv.context
                GlobalScope.launch(Dispatchers.IO) {
                    val h = 400
                    val w = h * 3 / 4
                    val bgColor = ctx.primaryColor
                    // 书与子分组按最后阅读时间混排（子分组时间=组内书的最新阅读时间，真进书阅读才更新），取前4
                    val entries = mutableListOf<Pair<Any, Long>>()
                    preview.forEach { b ->
                        entries.add(Pair(b.getDisplayCover(), b.durChapterTime))
                    }
                    childGroups.forEach { cg ->
                        entries.add(
                            Pair(cg.cover ?: R.drawable.image_cover_default, appDb.bookDao.getGroupLastReadTime(cg.groupId))
                        )
                    }
                    val models = entries.sortedByDescending { it.second }.take(4).map { it.first }
                    val bitmaps = models.mapNotNull { m ->
                        try {
                            Glide.with(ctx).asBitmap().load(m)
                                .centerCrop().submit(w / 2, h / 2).get(10, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bitmaps.isEmpty()) return@launch
                    val mosaic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    mosaic.eraseColor(bgColor)
                    val canvas = Canvas(mosaic)
                    val gap = 14f
                    val cw = w / 2f
                    val ch = h / 2f
                    bitmaps.forEachIndexed { i, bmp ->
                        val l = (i % 2) * cw
                        val t = (i / 2) * ch
                        canvas.drawBitmap(bmp, null, RectF(l + gap / 2, t + gap / 2, l + cw - gap / 2, t + ch - gap / 2), null)
                    }
                    withContext(Dispatchers.Main) {
                        if (iv.tag == key) {
                            iv.setImageBitmap(mosaic)
                        }
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