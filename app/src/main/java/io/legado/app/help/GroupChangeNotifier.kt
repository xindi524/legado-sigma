package io.legado.app.help

import io.legado.app.constant.AppLog
import java.lang.ref.WeakReference

/**
 * F1 嵌套分组：分组数据变更通知器
 * 分组保存/新建/删除后同步直调所有书架页面的刷新回调，绕过事件总线的时序问题
 */
object GroupChangeNotifier {

    private val listeners = mutableListOf<WeakReference<() -> Unit>>()

    fun register(listener: () -> Unit) {
        listeners.add(WeakReference(listener))
        // 清理已被回收的引用
        listeners.removeAll { it.get() == null }
    }

    fun notifyChanged() {
        AppLog.put("F1诊断: GroupChangeNotifier.notifyChanged 开始")
        listeners.removeAll { it.get() == null }
        listeners.forEach {
            try {
                AppLog.put("F1诊断: 直调书架刷新回调")
                it.get()?.invoke()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
