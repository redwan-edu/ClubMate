package com.example.clubmate.e2ee

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local storage for E2EE keys.
 *
 * The X25519 private key is encrypted ("wrapped") with an AES-256-GCM key that lives inside the
 * Android Keystore and can never be exported, then stored in private SharedPreferences. It never
 * leaves the device and is excluded from backups (see res/xml/backup_rules.xml).
 *
 * The vault also remembers every public key the directory has ever shown for a contact, so messages
 * sent before a contact changed keys can still be verified.
 */
internal class KeyVault(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadPrivateKey(uid: String): ByteArray? {
        val stored = prefs.getString(privateKeyPref(uid), null) ?: return null
        return try {
            unwrap(stored)
        } catch (e: Exception) {
            // Happens if the Keystore key was lost (e.g. app data restored onto another device).
            Log.w(TAG, "Stored private key could not be unwrapped; a new one will be created", e)
            null
        }
    }

    @Synchronized
    fun savePrivateKey(uid: String, privateKey: ByteArray) {
        prefs.edit().putString(privateKeyPref(uid), wrap(privateKey)).commit()
    }

    @Synchronized
    fun isKnownPeerKey(peerUid: String, publicKeyB64: String): Boolean =
        prefs.getStringSet(knownKeysPref(peerUid), emptySet())?.contains(publicKeyB64) == true

    @Synchronized
    fun addKnownPeerKey(peerUid: String, publicKeyB64: String) {
        val known = prefs.getStringSet(knownKeysPref(peerUid), emptySet()) ?: emptySet()
        if (publicKeyB64 in known) return
        prefs.edit().putStringSet(knownKeysPref(peerUid), HashSet(known) + publicKeyB64).apply()
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val encrypted = cipher.doFinal(plain)
        return encode(cipher.iv) + SEPARATOR + encode(encrypted)
    }

    private fun unwrap(stored: String): ByteArray {
        val parts = stored.split(SEPARATOR)
        require(parts.size == 2) { "Malformed wrapped key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, decode(parts[0])))
        return cipher.doFinal(decode(parts[1]))
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private fun privateKeyPref(uid: String) = "identity_private_$uid"
    private fun knownKeysPref(peerUid: String) = "known_keys_$peerUid"

    companion object {
        /** Must match the exclusions in res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val PREFS_NAME = "clubmate_e2ee"

        private const val TAG = "KeyVault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAPPING_KEY_ALIAS = "clubmate_e2ee_wrapping_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SEPARATOR = ":"
    }
}
