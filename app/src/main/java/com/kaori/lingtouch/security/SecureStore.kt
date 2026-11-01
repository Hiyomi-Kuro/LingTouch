package com.kaori.lingtouch.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.kaori.lingtouch.util.Logx
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys are kept in private SharedPreferences, encrypted with a non-exportable
 * Android Keystore AES/GCM key. Nothing is ever written in plain text or logged.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("lt_secure", Context.MODE_PRIVATE)

    fun saveApiKey(key: String) {
        if (key.isBlank()) return
        try {
            prefs.edit().putString(KEY_API, encrypt(key.trim())).apply()
        } catch (t: Throwable) {
            Logx.e("SecureStore", "无法保存 API Key（Keystore 不可用）: ${t.javaClass.simpleName}")
        }
    }

    fun getApiKey(): String? {
        val stored = prefs.getString(KEY_API, null) ?: return null
        return try {
            decrypt(stored)
        } catch (t: Throwable) {
            Logx.e("SecureStore", "无法解密 API Key，将要求重新输入: ${t.javaClass.simpleName}")
            null
        }
    }

    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_BYTES)
        val body = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "lingtouch_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_API = "api_key_enc"
    }
}
