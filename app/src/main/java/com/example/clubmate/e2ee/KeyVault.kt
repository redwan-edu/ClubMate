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
 * The private keys (X25519 for key exchange, Ed25519 for signing) are encrypted ("wrapped") with an
 * AES-256-GCM key that lives inside the Android Keystore and can never be exported, then stored in
 * private SharedPreferences. They never leave the device and are excluded from backups (see
 * res/xml/backup_rules.xml).
 *
 * It also keeps the X3DH signed prekeys (the current one and a few older ones) and the random key
 * that encrypts the on-device chat store.
 *
 * The vault also remembers every public key the directory has ever shown for a contact, so messages
 * sent before a contact changed keys can still be verified.
 */
internal class KeyVault(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadPrivateKey(uid: String): ByteArray? = loadSecret(privateKeyPref(uid))

    @Synchronized
    fun savePrivateKey(uid: String, privateKey: ByteArray) = saveSecret(privateKeyPref(uid), privateKey)

    @Synchronized
    fun loadSigningKey(uid: String): ByteArray? = loadSecret(signingKeyPref(uid))

    @Synchronized
    fun saveSigningKey(uid: String, privateKey: ByteArray) = saveSecret(signingKeyPref(uid), privateKey)

    // ---- signed prekeys (X3DH): a few recent ones are kept so late session starts still work

    @Synchronized
    fun loadSignedPreKey(uid: String, id: Int): ByteArray? = loadSecret(signedPreKeyPref(uid, id))

    /** Stores [privateKey] as the current signed prekey and forgets all but the newest [keep]. */
    @Synchronized
    fun saveSignedPreKey(uid: String, id: Int, privateKey: ByteArray, createdAt: Long, keep: Int) {
        saveSecret(signedPreKeyPref(uid, id), privateKey)
        val ids = (prefs.getString(signedPreKeyListPref(uid), "") ?: "")
            .split(',').filter { it.isNotEmpty() }.map { it.toInt() } + id
        val kept = ids.takeLast(keep)
        val editor = prefs.edit()
        (ids - kept.toSet()).forEach { editor.remove(signedPreKeyPref(uid, it)) }
        editor.putString(signedPreKeyListPref(uid), kept.joinToString(","))
            .putInt(currentSignedPreKeyPref(uid), id)
            .putLong(currentSignedPreKeyTimePref(uid), createdAt)
            .commit()
    }

    /** (id, createdAt) of the current signed prekey, or null if there is none yet. */
    @Synchronized
    fun currentSignedPreKey(uid: String): Pair<Int, Long>? {
        if (!prefs.contains(currentSignedPreKeyPref(uid))) return null
        return prefs.getInt(currentSignedPreKeyPref(uid), 0) to prefs.getLong(currentSignedPreKeyTimePref(uid), 0)
    }

    /** Random key that encrypts the on-device message and session store (see RatchetStore). */
    @Synchronized
    fun storageKey(): ByteArray = loadSecret(STORAGE_KEY_PREF)
        ?: ByteArray(32).also {
            java.security.SecureRandom().nextBytes(it)
            saveSecret(STORAGE_KEY_PREF, it)
        }

    /** [kind] is the directory field the key came from ("publicKey" or "signingKey"). */
    @Synchronized
    fun isKnownPeerKey(peerUid: String, publicKeyB64: String, kind: String): Boolean =
        prefs.getStringSet(knownKeysPref(peerUid, kind), emptySet())?.contains(publicKeyB64) == true

    @Synchronized
    fun addKnownPeerKey(peerUid: String, publicKeyB64: String, kind: String) {
        val pref = knownKeysPref(peerUid, kind)
        val known = prefs.getStringSet(pref, emptySet()) ?: emptySet()
        if (publicKeyB64 in known) return
        prefs.edit().putStringSet(pref, HashSet(known) + publicKeyB64).apply()
    }

    private fun loadSecret(pref: String): ByteArray? {
        val stored = prefs.getString(pref, null) ?: return null
        return try {
            unwrap(stored)
        } catch (e: Exception) {
            // Happens if the Keystore key was lost (e.g. app data restored onto another device).
            Log.w(TAG, "Stored private key could not be unwrapped; a new one will be created", e)
            null
        }
    }

    private fun saveSecret(pref: String, secret: ByteArray) {
        prefs.edit().putString(pref, wrap(secret)).commit()
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
    private fun signingKeyPref(uid: String) = "signing_private_$uid"
    private fun signedPreKeyPref(uid: String, id: Int) = "spk_private_${uid}_$id"
    private fun signedPreKeyListPref(uid: String) = "spk_ids_$uid"
    private fun currentSignedPreKeyPref(uid: String) = "spk_current_$uid"
    private fun currentSignedPreKeyTimePref(uid: String) = "spk_current_time_$uid"
    private fun knownKeysPref(peerUid: String, kind: String) =
        if (kind == "publicKey") "known_keys_$peerUid" else "known_${kind}_$peerUid"

    companion object {
        /** Must match the exclusions in res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val PREFS_NAME = "clubmate_e2ee"

        private const val TAG = "KeyVault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAPPING_KEY_ALIAS = "clubmate_e2ee_wrapping_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SEPARATOR = ":"
        private const val STORAGE_KEY_PREF = "storage_key"
    }
}
