package com.example.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SnapTextAccount(
    val id: String,
    val email: String?,
    val name: String?,
    val photoUrl: String? = null,
    val plan: String,
    val status: String,
    val expiresAt: String? = null,
    // "google" or "email" -- read from the website's /api/app-session response once that
    // endpoint starts including it (see note in accountFrom below). Null until then, in
    // which case the UI should show a generic "Connected" label rather than guessing.
    val signInProvider: String? = null,
)

class AccountSessionManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("snaptext_secure_account", Context.MODE_PRIVATE)
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val WEBSITE_URL = "https://snaptext-pearl.vercel.app"
        private const val PREF_TOKEN = "encrypted_app_session"
        private const val PREF_PENDING_VERIFIER = "encrypted_pending_verifier"
        private const val KEY_ALIAS = "snaptext_account_session_key"
        private const val KEYSTORE = "AndroidKeyStore"
    }

    fun savedToken(): String? = runCatching {
        val encoded = prefs.getString(PREF_TOKEN, null) ?: return null
        decrypt(encoded)
    }.getOrNull()

    fun saveToken(token: String) {
        prefs.edit().putString(PREF_TOKEN, encrypt(token)).apply()
    }

    fun clearToken() {
        prefs.edit().remove(PREF_TOKEN).remove(PREF_PENDING_VERIFIER).apply()
    }

    fun createConnectionUrl(): String {
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        prefs.edit().putString(PREF_PENDING_VERIFIER, encrypt(verifier)).apply()
        return Uri.parse("$WEBSITE_URL/app-connect").buildUpon()
            .appendQueryParameter("challenge", challenge)
            .build().toString()
    }

    fun exchangeCode(code: String): SnapTextAccount {
        val encodedVerifier = prefs.getString(PREF_PENDING_VERIFIER, null)
            ?: throw IllegalStateException("Start the connection from the SnapText app and try again.")
        val verifier = decrypt(encodedVerifier)
        val body = JSONObject().put("code", code).put("verifier", verifier).toString().toRequestBody(jsonType)
        val request = Request.Builder().url("$WEBSITE_URL/api/app-session/exchange").post(body).build()
        val json = executeJson(request)
        val token = json.getString("token")
        saveToken(token)
        prefs.edit().remove(PREF_PENDING_VERIFIER).apply()
        return accountFrom(json)
    }

    fun refreshAccount(): SnapTextAccount? {
        val token = savedToken() ?: return null
        val request = Request.Builder().url("$WEBSITE_URL/api/app-session")
            .header("Authorization", "Bearer $token").get().build()
        return try {
            accountFrom(executeJson(request))
        } catch (e: Exception) {
            if (e is SessionExpiredException) clearToken()
            throw e
        }
    }

    fun simulateTestUpgrade(plan: String = "pro_monthly") {
        val token = savedToken() ?: throw IllegalStateException("Connect your SnapText account first.")
        val body = JSONObject().put("plan", plan).toString().toRequestBody(jsonType)
        val request = Request.Builder().url("$WEBSITE_URL/api/app-session/test-upgrade")
            .header("Authorization", "Bearer $token")
            .post(body).build()
        executeJson(request)
    }

    fun signOut() {
        val token = savedToken()
        if (token != null) {
            val request = Request.Builder().url("$WEBSITE_URL/api/app-session")
                .header("Authorization", "Bearer $token").delete().build()
            runCatching { executeJson(request) }
        }
        clearToken()
    }

    /**
     * Permanently deletes the signed-in user's account (auth identity, entitlement,
     * sessions, handoff codes -- everything keyed to their user id) and clears the
     * local session. Requires the website to expose DELETE /api/app-session/account
     * calling the Supabase delete_own_account() RPC (see Supabase migration notes).
     * Throws if there's no saved session or the server call fails; callers should
     * surface the message and NOT clear local state on failure, so the user can retry.
     */
    fun deleteAccount() {
        val token = savedToken() ?: throw IllegalStateException("Connect your SnapText account first.")
        val request = Request.Builder().url("$WEBSITE_URL/api/app-session/account")
            .header("Authorization", "Bearer $token").delete().build()
        executeJson(request)
        clearToken()
    }

    /**
     * Fires a single, anonymous "app installed/opened" ping the first time this is
     * called for this device install (gated by PreferencesManager.markInstallPingSentIfNeeded
     * at the call site, so this itself has no dedupe logic). Best-effort: failures are
     * swallowed since this must never block app startup or show an error to the user.
     */
    fun pingInstallEvent() {
        runCatching {
            val request = Request.Builder().url("$WEBSITE_URL/api/app-install-ping").post(
                JSONObject().toString().toRequestBody(jsonType)
            ).build()
            client.newCall(request).execute().use { /* ignore response body/errors */ }
        }
    }

    private fun accountFrom(json: JSONObject): SnapTextAccount {
        val user = json.getJSONObject("user")
        val entitlement = json.optJSONObject("entitlement") ?: JSONObject()
        val plan = entitlement.optString("plan", "free")
        val status = entitlement.optString("status", "inactive")
        return SnapTextAccount(
            id = user.optString("id", ""),
            email = user.optString("email").takeIf { it.isNotBlank() && it != "null" },
            name = user.optString("name").takeIf { it.isNotBlank() && it != "null" },
            photoUrl = user.optString("photoUrl").takeIf { it.isNotBlank() && it != "null" },
            plan = if (status == "active") plan else "free",
            status = status,
            expiresAt = json.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" },
            // Reads user.provider once the website includes it in the session payload
            // (Supabase Auth already tracks this in auth.users.raw_app_meta_data.provider,
            // it just isn't being forwarded by /api/app-session yet). Safe no-op until then.
            signInProvider = user.optString("provider").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401) throw SessionExpiredException()
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Could not connect to your SnapText account.")
            }
            return JSONObject(text)
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}

class SessionExpiredException : IllegalStateException("Your session expired. Connect your account again.")