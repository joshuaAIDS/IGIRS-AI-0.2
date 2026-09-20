package com.igirs.ai.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureKeystoreVault {

    private const val TAG = "IGIRS.KeystoreVault"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "igirs_master_vault_key"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    init {
        ensureKeyExists()
    }

    private fun createSpec(useStrongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (useStrongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    fun ensureKeyExists(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    try {
                        keyGenerator.init(createSpec(useStrongBox = true))
                        keyGenerator.generateKey()
                        Log.i(TAG, "Hardware StrongBox-backed AES-256-GCM Master Key generated.")
                        return true
                    } catch (e: Exception) {
                        Log.i(TAG, "StrongBox not available on hardware; falling back to standard TEE KeyStore.")
                    }
                }

                keyGenerator.init(createSpec(useStrongBox = false))
                keyGenerator.generateKey()
                Log.i(TAG, "Hardware TEE AES-256-GCM Master Key generated in AndroidKeyStore.")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AndroidKeyStore: ${e.message}", e)
            false
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                ensureKeyExists()
            }
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve secret key: ${e.message}")
            null
        }
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return try {
            val secretKey = getSecretKey() ?: return plaintext
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            "ENC:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failure: ${e.message}", e)
            plaintext
        }
    }

    fun decrypt(encryptedPayload: String): String {
        if (encryptedPayload.isEmpty()) return ""
        // Check if payload is actually encrypted with our prefix
        if (!encryptedPayload.startsWith("ENC:")) {
            return encryptedPayload // Legacy plaintext migration
        }

        return try {
            val secretKey = getSecretKey() ?: return ""
            val rawBase64 = encryptedPayload.removePrefix("ENC:")
            val combined = Base64.decode(rawBase64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return ""

            val iv = ByteArray(GCM_IV_LENGTH)
            val ciphertext = ByteArray(combined.size - GCM_IV_LENGTH)

            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.size)

            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failure: ${e.message}", e)
            ""
        }
    }

    /**
     * Wipes sensitive data arrays from RAM to prevent memory-dump inspection
     */
    fun zeroize(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    fun zeroize(chars: CharArray?) {
        chars?.fill('0')
    }
}
