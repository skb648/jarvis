package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jarvis.assistant.control.ScreenRecorderService
import com.jarvis.assistant.ui.MainScreen
import com.jarvis.assistant.ui.SettingsScreen
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.ui.theme.accentColor
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // granted map — JARVIS checks permissions on demand anyway
        }

    private val screenRecordLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val code = result.resultCode
            val data = result.data
            if (code == RESULT_OK && data != null) {
                ScreenRecorderService.start(this, code, data)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.getBooleanExtra("screen_record", false) == true) {
            requestScreenRecording()
        }

        setContent {
            val accentName by produceState(initialValue = "cyan") {
                value = runCatching {
                    kotlinx.coroutines.runBlocking { (application as JarvisApp).settings.settings.first().accent }
                }.getOrDefault("cyan")
            }
            JarvisTheme(accent = accentColor(accentName)) {
                var screen by rememberSaveable { mutableIntStateOf(0) }
                Surface(Modifier.fillMaxSize()) {
                    if (screen == 0) {
                        MainScreen(onOpenSettings = { screen = 1 })
                    } else {
                        SettingsScreen(
                            onBack = { screen = 0 },
                            onRequestPermissions = { requestCorePermissions() }
                        )
                    }
                }
            }
        }
    }

    private fun requestScreenRecording() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenRecordLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestCorePermissions() {
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.CAMERA,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    }
}
