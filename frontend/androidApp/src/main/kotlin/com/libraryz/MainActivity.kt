package com.libraryz

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.libraryz.data.DeepLinkInbox
import com.libraryz.data.api.AndroidContextHolder
import com.libraryz.data.api.DefaultBaseUrl
import com.libraryz.data.api.isLocalNetworkUrl
import com.libraryz.ui.Fullscreen
import com.libraryz.ui.LocalFullscreen

class MainActivity : ComponentActivity() {
    // The UI starts once the answer is in, either way: denied, requests
    // fail as "Can't reach the library".
    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        asking = false
        showApp()
    }
    private var asking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidContextHolder.appContext = applicationContext
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // An emailed link (App Link or libraryz://). Only on a fresh start:
        // a recreated activity would replay a link that was already used.
        if (savedInstanceState == null) DeepLinkInbox.deliver(intent?.dataString)
        when {
            // Recreated while the dialog was up: its answer still arrives.
            savedInstanceState?.getBoolean(AskingKey) == true -> asking = true
            needsLocalNetwork() -> {
                asking = true
                askLocalNetwork.launch(LocalNetworkPermission)
            }
            else -> showApp()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(AskingKey, asking)
    }

    // singleTask: a link tapped while the app is open arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinkInbox.deliver(intent.dataString)
    }

    private fun showApp() {
        val fullscreen = SystemBarsFullscreen()
        setContent {
            CompositionLocalProvider(LocalFullscreen provides fullscreen) { App() }
        }
    }

    /** Immersive mode: system bars hidden, a swipe from the edge shows them for a moment. */
    private inner class SystemBarsFullscreen : Fullscreen {
        private var on by mutableStateOf(false)
        override val isSupported = true
        override val isOn get() = on
        override fun set(on: Boolean) {
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
            this.on = on
        }
    }

    /**
     * Android 17 blocks local-network hosts without ACCESS_LOCAL_NETWORK; ask
     * before the first request, and only when the server is one.
     */
    private fun needsLocalNetwork(): Boolean =
        Build.VERSION.SDK_INT >= 37 &&
            isLocalNetworkUrl(DefaultBaseUrl) &&
            checkSelfPermission(LocalNetworkPermission) != PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LocalNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
        const val AskingKey = "askingLocalNetwork"
    }
}
