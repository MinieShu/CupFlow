package com.cupflow.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Stores the Rokid token in app-private preferences, encrypted by Android Keystore. */
class AuthTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("cupflow_auth", Context.MODE_PRIVATE)

    fun load(): String? = runCatching {
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString().takeIf { it.isNotBlank() }
    }.getOrElse {
        clear()
        null
    }

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        preferences.edit()
            .putString(KEY_TOKEN, Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private val secretKey: SecretKey
        get() {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
        }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_IV = "iv"
        private const val ALIAS = "cupflow_rokid_token"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
