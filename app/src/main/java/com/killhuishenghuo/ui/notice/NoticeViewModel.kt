package com.killhuishenghuo.ui.notice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.killhuishenghuo.core.net.NetworkModule
import com.killhuishenghuo.data.local.PreferencesStore
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.notice.Notice
import com.killhuishenghuo.data.notice.NoticeFeed
import com.killhuishenghuo.data.notice.NoticeRepository
import com.killhuishenghuo.data.notice.NoticeRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 当前该显示哪条公告。
 *
 * 顺序是「缓存先出现,网络再覆盖」,而不是「等网络回来再说」:公告文件在 GitHub Pages
 * 上,宿舍网连不上它的时候很多,而那时候**上一次读到的公告依然有效** —— 它是"某人写
 * 给你的一句话",不是一个必须此刻校验的状态。
 *
 * 拉取失败不改任何东西:既不清空已显示的,也不给"公告加载失败"的提示。没有公告就是
 * 没有公告,这条线路失败不值得占用主界面的注意力。
 */
class NoticeViewModel(
    private val repository: NoticeRepository,
    private val preferences: PreferencesStore,
    private val versionName: String,
) : ViewModel() {

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    /**
     * 设置里「历史公告」的列表。
     *
     * 和 [notice] 从同一份 feed 算出来,但它们回答的是两个问题:`notice` 是"现在
     * 该不该在顶上贴一条",这个是"发过些什么"。点掉公告不会让它变短 —— 见
     * [NoticeRules.history]。
     */
    private val _history = MutableStateFlow<List<Notice>>(emptyList())
    val history: StateFlow<List<Notice>> = _history.asStateFlow()

    /** 最近一次拿到的 feed,用于点「知道了」之后立刻算出下一条,不必再求网络。 */
    private var feed: NoticeFeed? = null

    init {
        feed = preferences.noticeFeedJson?.let(::parseOrNull)
        publish()
    }

    fun load() {
        viewModelScope.launch {
            val result = repository.fetch()
            if (result is Outcome.Success) {
                feed = result.value
                // 存**原文**而不是重新序列化:这样将来加字段时,旧版本读到的缓存里
                // 那些字段仍然在,不会因为在中间转一手而丢掉。
                preferences.noticeFeedJson = NetworkModule.json.encodeToString(
                    NoticeFeed.serializer(),
                    result.value,
                )
                publish()
            }
        }
    }

    /**
     * 用户点了「知道了」。
     *
     * 累加而不是覆盖:文件里同时挂着两条公告时,覆盖会让两条轮流冒出来。只记 id
     * 不记时间,是因为同一句话没必要说第二遍,但明天换一条新的必须还能出来。
     */
    fun dismiss(id: String) {
        preferences.noticeDismissedIds = preferences.noticeDismissedIds + id
        publish()
    }

    private fun publish() {
        val current = feed
        _notice.value = current?.let {
            NoticeRules.visible(
                feed = it,
                today = NoticeRules.todayIso(),
                versionName = versionName,
                dismissedIds = preferences.noticeDismissedIds,
            )
        }
        _history.value = current?.let { NoticeRules.history(it, versionName) }.orEmpty()
    }

    private fun parseOrNull(raw: String): NoticeFeed? =
        runCatching { NetworkModule.json.decodeFromString<NoticeFeed>(raw) }.getOrNull()
}