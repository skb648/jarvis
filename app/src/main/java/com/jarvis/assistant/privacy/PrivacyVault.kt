package com.jarvis.assistant.privacy

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Privacy Vault — AES-256-GCM encrypted storage using Android KeyStore.
 *
 * Security guarantees:
 *   - Keys stored in Android KeyStore (hardware-backed on modern devices)
 *   - AES-256-GCM with 128-bit authentication tag
 *   - Random 12-byte IV per encryption (never reused)
 *   - API 28+: Key requires unlocked device (setUnlockedDeviceRequired)
 *   - Base64 encoded storage in SharedPreferences with enc_ prefix
 */
class PrivacyVault(context: Context) {

    companion object {
        private const val TAG = "JarvisPrivacy"
        private const val KEY_ALIAS = "jarvis_privacy_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "jarvis_privacy_vault"
        private const val KEY_PREFIX = "enc_"
        private const val IV_LENGTH = 12 // bytes (GCM standard)
        private const val GCM_TAG_LENGTH = 128 // bits
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // ─── Key Management ─────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null)
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        return createKey()
    }

    private fun createKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        // Require unlocked device on API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    // ─── Encryption / Decryption ────────────────────────────────

    fun encrypt(plaintext: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Format: [IV (12 bytes)] + [Ciphertext + GCM Tag]
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(ciphertext: String): String {
        val key = getOrCreateKey()
        val combined = Base64.decode(ciphertext, Base64.NO_WRAP)

        if (combined.size < IV_LENGTH) {
            throw IllegalArgumentException("Invalid encrypted data — too short")
        }

        val iv = combined.copyOfRange(0, IV_LENGTH)
        val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    // ─── High-Level Storage API ─────────────────────────────────

    fun storeSecure(key: String, value: String): Boolean {
        return try {
            val encrypted = encrypt(value)
            prefs.edit().putString(KEY_PREFIX + key, encrypted).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store secure value for key: $key", e)
            false
        }
    }

    fun retrieveSecure(key: String): String? {
        return try {
            val encrypted = prefs.getString(KEY_PREFIX + key, null) ?: return null
            decrypt(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve secure value for key: $key", e)
            null
        }
    }

    fun deleteSecure(key: String): Boolean {
        return try {
            prefs.edit().remove(KEY_PREFIX + key).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearAll(): Boolean {
        return try {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach {
                editor.remove(it)
            }
            editor.apply()
            true
        } catch (e: Exception) {
            false
        }
    }
}
