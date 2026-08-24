package com.wkq.bao.core.nas.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.wkq.bao.core.database.AppDatabase
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 使用 Android Keystore 保护 NAS 密码，数据库中只保存密文。 */
object NasCredentialVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "yuanbao_tv_nas_credentials"
    private const val PREFIX = "v1:"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(storedValue: String): String {
        if (storedValue.isEmpty()) return ""
        if (!isEncrypted(storedValue)) return storedValue
        val payload = Base64.decode(storedValue.removePrefix(PREFIX), Base64.NO_WRAP)
        require(payload.size > IV_LENGTH) { "NAS credential payload is invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, payload.copyOfRange(0, IV_LENGTH))
        )
        return cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }
}

object NasCredentialMigration {
    suspend fun migratePlaintextCredentials(database: AppDatabase) {
        database.nasDao().getAllSourcesSync().forEach { source ->
            if (source.passwordEncrypted.isNotEmpty() && !NasCredentialVault.isEncrypted(source.passwordEncrypted)) {
                database.nasDao().updateSource(
                    source.copy(passwordEncrypted = NasCredentialVault.encrypt(source.passwordEncrypted))
                )
            }
        }
    }
}
