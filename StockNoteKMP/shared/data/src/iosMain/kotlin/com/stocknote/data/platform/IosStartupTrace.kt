package com.stocknote.data.platform

/**
 * iOS **启动阶段上报**（2026-10-03，老周真机报「打开白屏，一会儿就退出」）。
 *
 * ## 为什么需要它
 * 那一版的 iOS 启动是**同步长链**：读 Keychain → 开加密库（SQLCipher）→ 建仓储 → 播种演示数据
 * → 起 Compose，全程在 `mainViewController()` 里一气呵成。出问题时有三种可能，而**白屏上一个字都没有**：
 *  1. 某一步**抛异常** —— 但当时那句"失败就把堆栈弹到屏幕上"的代码，弹窗走的是
 *     `dispatch_async(主队列)`、紧接着**在主线程**上 `sleep(12)`，block 永远排不到队
 *     （用户看到的就是"白屏十几秒然后退出"，那个 12 秒正是这么来的）；
 *  2. 某一步**卡住**（watchdog 超时杀进程）—— 没有任何阶段信息，无法判断卡在哪；
 *  3. 崩在 Compose 初始化 —— 这时才会走进程级的未捕获异常钩子。
 *
 * ⇒ 把阶段名**实时显示在屏幕上**，三种情况就都能定位：卡住时它就是"卡在哪一步"，
 *   抛异常时它被换成错误详情，Compose 崩则由钩子兜住。
 *
 * ## 为什么放在 `data` 模块
 * 阶段点里有两个（Keychain、SQLCipher）落在 `data` 的 iOS 实现里，而注册方（弹窗）在
 * `feature` 的 iOS 外壳。`data` **不能**依赖 `feature`（依赖方向），所以这里放一个**中性回调**：
 * `data` 只管 `stage(...)`，谁注册谁处理。
 *
 * ⚠️ 只在**主线程**使用（启动链本来就是主线程；弹窗更新也必须在主线程）。
 */
object IosStartupTrace {

    /** 由 iOS 外壳（`feature/iosMain/MainViewController.kt`）注册；未注册时什么都不做。 */
    var onStage: ((String) -> Unit)? = null

    fun stage(text: String) {
        onStage?.invoke(text)
    }
}
