package io.legado.app.ui.book.group

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.utils.postEvent

class GroupViewModel(application: Application) : BaseViewModel(application) {

    fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) {
        execute {
            appDb.bookGroupDao.update(*bookGroup)
            // F1 嵌套分组：通知书架强制刷新分组列表
            postEvent(EventBus.BOOK_GROUP_CHANGED, "")
        }.onFinally {
            finally?.invoke()
        }
    }

    fun addGroup(
        groupName: String,
        bookSort: Int,
        enableRefresh: Boolean,
        onlyUpdateRead: Boolean,
        cover: String?,
        parentId: Long = 0L,
        finally: () -> Unit
    ) {
        execute {
            val groupId = appDb.bookGroupDao.getUnusedId()
            val bookGroup = BookGroup(
                groupId = groupId,
                groupName = groupName,
                cover = cover,
                bookSort = bookSort,
                enableRefresh = enableRefresh,
                onlyUpdateRead = onlyUpdateRead,
                order = appDb.bookGroupDao.maxOrder.plus(1),
                parentId = parentId
            )
            appDb.bookGroupDao.getByID(groupId) ?: appDb.bookDao.removeGroup(groupId)
            appDb.bookGroupDao.insert(bookGroup)
            // F1 嵌套分组：通知书架强制刷新分组列表
            postEvent(EventBus.BOOK_GROUP_CHANGED, "")
        }.onFinally {
            finally()
        }
    }

    fun delGroup(bookGroup: BookGroup, finally: () -> Unit) {
        execute {
            // F1: 删除分组时，其子分组提升为顶层分组（parentId=0）
            val children = appDb.bookGroupDao.getByParent(bookGroup.groupId)
            children.forEach { child ->
                child.parentId = 0L
                appDb.bookGroupDao.update(child)
            }
            appDb.bookGroupDao.delete(bookGroup)
            appDb.bookDao.removeGroup(bookGroup.groupId)
            // F1 嵌套分组：通知书架强制刷新分组列表
            postEvent(EventBus.BOOK_GROUP_CHANGED, "")
        }.onFinally {
            finally()
        }
    }


}
