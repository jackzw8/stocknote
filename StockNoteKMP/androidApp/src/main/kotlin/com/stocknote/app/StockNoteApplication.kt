package com.stocknote.app

import android.app.Application
import com.stocknote.data.platform.AndroidPlatformContext

/**
 * Android 外壳的启动入口。
 *
 * 这里只做一件事：把 applicationContext 交给共享层。
 * 共享层不能引用 android.content.Context，所以由外壳在最早的时机注入，
 * 之后 AppContainer 才能在纯共享代码里创建加密数据库与 Keystore。
 */
class StockNoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidPlatformContext.install(this)
    }
}
