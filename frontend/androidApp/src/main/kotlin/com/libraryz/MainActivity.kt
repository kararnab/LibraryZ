package com.libraryz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.libraryz.data.DeepLinkInbox
import com.libraryz.data.api.AndroidContextHolder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidContextHolder.appContext = applicationContext
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // An emailed link (App Link or libraryz://). Only on a fresh start:
        // a recreated activity would replay a link that was already used.
        if (savedInstanceState == null) DeepLinkInbox.deliver(intent?.dataString)
        setContent { App() }
    }

    // singleTask: a link tapped while the app is open arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinkInbox.deliver(intent.dataString)
    }
}
