import SwiftUI
import SharedUI

/// iOS 外壳。
///
/// 整个文件就是 iOS 侧的全部平台代码 —— 与 Android 的 MainActivity 对称。
/// 页面、状态、账本逻辑、加密数据库全部来自 shared 模块。
@main
struct StockNoteApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeRootView()
                .ignoresSafeArea(.keyboard)
        }
    }
}

/// 把 Kotlin 侧产出的 UIViewController 包进 SwiftUI。
struct ComposeRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // MainViewControllerKt 由 Kotlin/Native 导出（文件名 MainViewController.kt）
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // 共享 UI 自己管理状态，这里无需同步
    }
}
