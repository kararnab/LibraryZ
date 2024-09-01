package com.libraryz

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

// Entry point consumed by SwiftUI / UIKit. From Swift:
//   let vc = MainViewControllerKt.MainViewController()
// then host inside a UIViewControllerRepresentable.
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
