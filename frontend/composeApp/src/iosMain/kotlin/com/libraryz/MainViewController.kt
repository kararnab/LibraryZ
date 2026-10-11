package com.libraryz

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import com.libraryz.theme.LocalReduceMotion
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIViewController

// Entry point consumed by SwiftUI / UIKit. From Swift:
//   let vc = MainViewControllerKt.MainViewController()
// then host inside a UIViewControllerRepresentable.
fun MainViewController(): UIViewController = ComposeUIViewController {
    CompositionLocalProvider(LocalReduceMotion provides UIAccessibilityIsReduceMotionEnabled()) { App() }
}
