package com.coderred.andclaw.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.coderred.andclaw.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

object OpenRouterAuth {

    private const val AUTH_URL = "https://openrouter.ai/auth"
    private const val TOKEN_URL = "https://openrouter.ai/api/v1/auth/keys"
    val CALLBACK_SCHEME: String
        get() = BuildConfig.OAUTH_CALLBACK_SCHEME
    const val CALLBACK_HOST = "auth"
    const val CALLBACK_PATH = "/callback"
    // Cloudflare Worker가 intent:// URI로 리다이렉트해서 앱으로 돌아옴
    private val CALLBACK_URL: String
        get() = BuildConfig.OPENROUTER_CALLBACK_URL

    private const val PREFS_NAME = "openrouter_auth"
    private const val KEY_CODE_VERIFIER = "code_verifier"

    fun buildAuthUri(context: Context): Uri {
        val codeVerifier = generateCodeVerifier()

        // SharedPreferences에 저장 (프로세스 복원 대비)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .apply()

        val codeChallenge = generateCodeChallenge(codeVerifier)

        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("callback_url", CALLBACK_URL)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("title", "andClaw")
            .build()
    }

    fun extractCode(uri: Uri): String? {
        if (uri.scheme != CALLBACK_SCHEME) return null
        return uri.getQueryParameter("code")
    }

    suspend fun exchangeCodeForKey(code: String, context: Context): String =
        withContext(Dispatchers.IO) {
            val verifier = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CODE_VERIFIER, null)
                ?: throw IllegalStateException("No pending code verifier")

            val url = URL(TOKEN_URL)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000

                val body = JSONObject().apply {
                    put("code", code)
                    put("code_verifier", verifier)
                    put("code_challenge_method", "S256")
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(body.toString())
                    writer.flush()
                }

                if (connection.responseCode != 200) {
                    val error =
                        connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    throw Exception("HTTP ${connection.responseCode}: $error")
                }

                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val key = json.getString("key")

                // 사용 후 삭제
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_CODE_VERIFIER)
                    .apply()

                key
            } finally {
                connection.disconnect()
            }
        }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
