package com.jarvis.assistant

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jarvis.assistant.data.SettingsRepository
import com.jarvis.assistant.jni.RustBridge
import com.jarvis.assistant.keepalive.JarvisKeepAliveService
import com.jarvis.assistant.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JarvisApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Settings repository — accessible app-wide for ViewModel factory
    val settingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "JARVIS Application starting...")

        // 0. Initialize PrivacyVault for encrypted API key storage
        settingsRepository.initPrivacyVault(this)

        // 1. Initialize Shizuku (needed before Rust init for permission checks)
        ShizukuManager.init()

        // 2. Initialize the Rust native core with persisted API keys
        appScope.launch {
            initializeRustCore()
        }

        // 3. Start keep-alive service ONLY if user enabled it in settings
        appScope.launch {
            val keepAliveEnabled = settingsRepository.isKeepAliveEnabled()
            if (keepAliveEnabled) {
                startKeepAliveService()
            } else {
                Log.i(TAG, "Keep-alive service NOT started — disabled in settings")
            }
        }

        Log.i(TAG, "JARVIS Application initialized")
    }

    /**
     * Initialize the Rust core with API keys from DataStore.
     * If no keys are persisted yet, initializes with empty strings
     * (user will configure them in Settings later).
     */
    private suspend fun initializeRustCore() {
        val geminiKey = settingsRepository.getGeminiApiKey()
        val elevenLabsKey = settingsRepository.getElevenLabsApiKey()

        if (geminiKey.isNotEmpty() && RustBridge.isNativeReady()) {
            val success = RustBridge.initialize(geminiKey, elevenLabsKey)
            if (success) {
                Log.i(TAG, "Rust core initialized with persisted API keys")
            } else {
                Log.e(TAG, "Rust core initialization failed with persisted keys")
            }
        } else {
            // No keys yet or Rust not built — will be initialized when user sets keys
            Log.i(TAG, "Rust core waiting for API key configuration")
        }
    }

    /**
     * Start the keep-alive foreground service so the OS doesn't kill JARVIS.
     */
    private fun startKeepAliveService() {
        try {
            val serviceIntent = Intent(this, JarvisKeepAliveService::class.java).apply {
                action = JarvisKeepAliveService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "Keep-alive service started")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start keep-alive service: ${e.message}")
        }
    }

    override fun onTerminate() {
        ShizukuManager.destroy()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "JarvisApp"

        @Volatile
        private var instance: JarvisApp? = null

        fun getInstance(): JarvisApp = instance ?: throw IllegalStateException(
            "JarvisApp not initialized"
        )
    }
}
