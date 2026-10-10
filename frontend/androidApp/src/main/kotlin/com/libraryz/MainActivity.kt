package com.libraryz

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.libraryz.data.DeepLinkInbox
import com.libraryz.data.api.AndroidContextHolder
import com.libraryz.data.api.DefaultBaseUrl
import com.libraryz.data.api.isLocalNetworkUrl

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
        setContent { App() }
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
