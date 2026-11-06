// Port of DSPi-Console release/v1.1.6 `Link/LinkTokenStore.swift`: one token
// per hub, keyed by the hub id (`hid`), not its address (spec 6.2). macOS keeps
// it in the login keychain; Android keeps it in app-private storage encrypted
// with an Android Keystore key (DSPI-LINK-INVESTIGATION.md 3.3).
package se.bofr.dspi.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A pairing as the Networking page lists it. */
data class PairedHub(val hubId: String, val name: String)

interface LinkTokenStore {
    fun token(hubId: String): String?

    fun setToken(hubId: String, name: String, token: String)

    fun remove(hubId: String)

    fun paired(): List<PairedHub>
}

/** Symmetric encryption of a small secret (the Keystore in the app, a fake in tests). */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Null when the data cannot be decrypted (key gone after a restore, tampering). */
    fun decrypt(sealed: ByteArray): ByteArray?
}

/** String key-value storage (SharedPreferences in the app, a map in tests). */
interface KeyValue {
    fun get(key: String): String?

    fun put(key: String, value: String?)

    fun keys(): Set<String>
}

/**
 * [LinkTokenStore] over [KeyValue]: per hub, `v1\n<base64 name>\n<token>`
 * sealed by [cipher] and base64-encoded.
 */
class SealedLinkTokenStore(private val kv: KeyValue, private val cipher: TokenCipher) : LinkTokenStore {
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    private fun key(hubId: String) = "hub:" + hubId.lowercase()

    /** (name, token) or null. */
    private fun entry(key: String): Pair<String, String>? {
        val sealed = kv.get(key) ?: return null
        val bytes = runCatching { b64d.decode(sealed) }.getOrNull() ?: return null
        val parts = cipher.decrypt(bytes)?.toString(Charsets.UTF_8)?.split('\n') ?: return null
        if (parts.size != 3 || parts[0] != "v1") return null
        val name = runCatching { String(b64d.decode(parts[1]), Charsets.UTF_8) }.getOrNull() ?: return null
        return name to parts[2]
    }

    override fun token(hubId: String): String? = entry(key(hubId))?.second?.takeIf { it.isNotEmpty() }

    override fun setToken(hubId: String, name: String, token: String) {
        require('\n' !in token) { "token" }
        val plain = "v1\n" + b64e.encodeToString(name.toByteArray(Charsets.UTF_8)) + "\n" + token
        kv.put(key(hubId), b64e.encodeToString(cipher.encrypt(plain.toByteArray(Charsets.UTF_8))))
    }

    override fun remove(hubId: String) = kv.put(key(hubId), null)

    override fun paired(): List<PairedHub> = kv.keys().filter { it.startsWith("hub:") }.mapNotNull { k ->
        entry(k)?.let { PairedHub(k.removePrefix("hub:"), it.first) }
    }.sortedBy { it.name.lowercase() }
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore. The prefs
 * file is app-private and backups are off (`allowBackup=false`), so tokens
 * are deleted with the app.
 */
class KeystoreTokenCipher(private val alias: String = "dspi_link_tokens") : TokenCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        c.doFinal(sealed, 12, sealed.size - 12)
    }.getOrNull()
}

/** [KeyValue] on a private SharedPreferences file. */
class PrefsKeyValue(context: Context, name: String) : KeyValue {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    override fun keys(): Set<String> = prefs.all.keys
}

/** The app's token store. */
fun keystoreLinkTokenStore(context: Context): LinkTokenStore =
    SealedLinkTokenStore(PrefsKeyValue(context, "dspi_link_tokens"), KeystoreTokenCipher())
