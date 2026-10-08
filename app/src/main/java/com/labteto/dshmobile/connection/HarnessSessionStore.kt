package com.labteto.dshmobile.connection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.labteto.dshmobile.core.wire.HarnessSession
import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Harness browser sessions, one per host, encrypted at rest.
 *
 * The harness answers 401 to every `/api` request without a session, so nothing works until this
 * store holds a cookie for that host. The cookie is obtained once, by exchanging the auth token the
 * harness prints at startup, and survives harness restarts — the signing secret is durable upstream
 * even though the token itself rotates.
 *
 * The cookie is the whole credential, and it is sent over the internet when the harness is reached
 * through a Cloudflare Tunnel. Holding it grants the same power as a shell on the harness computer,
 * so it is treated like a password: the AES key lives in the `AndroidKeyStore` and never leaves it,
 * and only ciphertext reaches DataStore.
 *
 * User authentication is deliberately *not* required on the key: the foreground service and
 * [KeepAliveWorker] reconnect while the screen is locked, and a key that needed a present user would
 * turn every background reconnect into a silent failure.
 */
@Singleton
class HarnessSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val okHttpClient: OkHttpClient,
) {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /** Whether this device holds a session for [hostId]. */
    suspend fun has(hostId: String): Boolean = cookie(hostId) != null

    /**
     * The `Cookie` value for [hostId], or null when this device has no readable session for it.
     *
     * A blob that will not decrypt is dropped rather than raised. That happens when the Keystore key
     * was invalidated (a device restore, or every screen lock removed) and the result is the same as
     * never having signed in: paste the startup token again.
     */
    suspend fun cookie(hostId: String): String? {
        val blob = blobs()[hostId] ?: return null
        val plain = withContext(Dispatchers.Default) { runCatching { decrypt(blob) }.getOrNull() }
        if (plain == null) remove(hostId)
        return plain
    }

    /**
     * Exchange [tokenInput] for a session and remember it.
     *
     * [tokenInput] may be the bare token, the whole `?token=…` URL, or the entire startup line the
     * harness printed — see [HarnessSession.tokenFrom].
     */
    suspend fun pair(hostId: String, baseUrl: String, tokenInput: String): SessionExchange {
        val token = HarnessSession.tokenFrom(tokenInput)
            ?: return SessionExchange.Refused(0)
        val outcome = HarnessSession.exchange(baseUrl, token, okHttpClient)
        if (outcome is SessionExchange.Granted) {
            val blob = withContext(Dispatchers.Default) { encrypt(outcome.cookie) }
            write(blobs() + (hostId to blob))
        }
        return outcome
    }

    /** Forget the session for [hostId]. Safe to call when there is none. */
    suspend fun remove(hostId: String) {
        val current = blobs()
        if (hostId !in current) return
        write(current - hostId)
    }

    /** Forget every session — the Settings "clear data" action. */
    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY)
            it.remove(LEGACY_PLAINTEXT_KEY)
        }
    }

    private suspend fun blobs(): Map<String, String> {
        val prefs = dataStore.data.first()
        // Earlier builds stored cookies in the clear. They are never read back: dropping them costs
        // one re-sign-in, and keeps no plaintext credential on disk.
        if (prefs[LEGACY_PLAINTEXT_KEY] != null) dataStore.edit { it.remove(LEGACY_PLAINTEXT_KEY) }
        val raw = prefs[KEY] ?: return emptyMap()
        return runCatching { WireJson.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    private suspend fun write(next: Map<String, String>) {
        dataStore.edit { it[KEY] = WireJson.encodeToString(serializer, next) }
    }

    /** AES-GCM, with the IV prefixed to the ciphertext. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(cipher.iv) + SEPARATOR + encoder.encodeToString(body)
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(SEPARATOR)
        if (parts.size != 2) throw GeneralSecurityException("malformed session blob")
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, decoder.decode(parts[0])))
        return String(cipher.doFinal(decoder.decode(parts[1])), Charsets.UTF_8)
    }

    /** The Keystore key, generated on first use. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        val KEY = stringPreferencesKey("harness_sessions_enc_json")
        val LEGACY_PLAINTEXT_KEY = stringPreferencesKey("harness_sessions_json")
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "dsh_harness_sessions"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        /** Not valid base64, so it cannot occur inside either half. */
        const val SEPARATOR = ":"
    }
}
