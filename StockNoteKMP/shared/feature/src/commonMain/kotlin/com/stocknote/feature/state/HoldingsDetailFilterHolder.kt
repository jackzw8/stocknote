package com.stocknote.feature.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 持仓明细页的筛选 / 排序 / 搜索 / 页码状态（老周 2026-10-04）。
 *
 * **为什么必须提升成 holder**：本页是二级路由 —— 点某行会 push `SecurityDetail`，
 * 而 `App.kt` 里 `is AppRoute.HoldingsDetail ->` 分支的组合会随路由切换**被销毁**，
 * 原先用 `remember` 存在页面里的筛选 / 排序 / 搜索词会**连同页面一起丢**，返回时全部复位。
 * 提到 App 顶层（与 `NewsHolder` / `PlanHolder` 同法）后即可跨路由保留。
 *
 * 行为约定：筛选 / 排序 / 搜索任一变化都要**回到第 1 页**（与原页面把 page 挂在
 * `remember(filterIndex, sortIndex, searchText)` 上的语义一致），故三个 setter 里都顺带把 [page] 归零。
 */
class HoldingsDetailFilterHolder {

    /** 选中的**筛选** chip 下标（对应页面里的 filters 列表） */
    var filterIndex by mutableStateOf(0)
        private set

    /** 选中的**排序** chip 下标（对应页面里的 sorts 列表） */
    var sortIndex by mutableStateOf(0)
        private set

    var searchText by mutableStateOf("")
        private set

    var searchVisible by mutableStateOf(false)
        private set

    var page by mutableIntStateOf(0)
        private set

    fun selectFilter(index: Int) {
        filterIndex = index
        page = 0
    }

    fun selectSort(index: Int) {
        sortIndex = index
        page = 0
    }

    fun setSearch(text: String) {
        searchText = text
        page = 0
    }

    /** 展开 / 收起搜索框；收起时清空搜索词并回到第 1 页 */
    fun toggleSearch() {
        searchVisible = !searchVisible
        if (!searchVisible) {
            searchText = ""
            page = 0
        }
    }

    /** 翻页（⚠️ 不能叫 `setPage` —— 会与 `var page` 的合成 setter 撞 JVM 签名） */
    fun goToPage(page: Int) {
        this.page = page
    }
}

@Composable
fun rememberHoldingsDetailFilterHolder(): HoldingsDetailFilterHolder =
    remember { HoldingsDetailFilterHolder() }
