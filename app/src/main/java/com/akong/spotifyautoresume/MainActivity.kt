package com.akong.spotifyautoresume

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.provider.Settings
import android.util.Base64
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.akong.spotifyautoresume.databinding.ActivityMainBinding
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.io.IOException
import android.content.ComponentName

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()

    private val clientId =
        "319987ede0484c429ba781dbc41b04e6"

    private val redirectUri =
        "spotifyautoresume://callback"

    private val prefs by lazy {
        getSharedPreferences("spotify_session", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPremium.setOnClickListener {
            startLogin()
        }

        binding.btnStandard.setOnClickListener {
            startStandardMode()
        }

        binding.btnLogin.setOnClickListener {
            startLogin()
        }

        binding.btnLogout.setOnClickListener {
            logout()
        }

        if (intent?.data != null) {
            handleCallback(intent.data!!)
        } else {
            restoreSession()
        }
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val component = ComponentName(
            this,
            SpotifyResumeService::class.java
        ).flattenToString()

        return enabled.contains(component)
    }

    private fun startStandardMode() {

        if (!isListenerEnabled()) {
            showStatus("Aktifkan Notification Access terlebih dahulu")
            binding.txtAccount.text = "Menunggu izin Android"

            try {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            } catch (e: Exception) {
                showStatus("Tidak bisa membuka pengaturan izin")
            }

            return
        }

        openSpotify()
    }

    private fun openSpotify() {
        try {
            val launchIntent =
                packageManager.getLaunchIntentForPackage(
                    "com.spotify.music"
                )

            if (launchIntent != null) {
                startActivity(launchIntent)

                binding.txtAccount.text = "Spotify Standard"
                showStatus("Standard Mode aktif")

            } else {
                showStatus("Aplikasi Spotify tidak ditemukan")

                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://open.spotify.com")
                    )
                )
            }

        } catch (e: Exception) {
            showStatus("Gagal membuka Spotify: ${e.message}")
        }
    }

    private fun randomString(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)

        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or
                Base64.NO_PADDING or
                Base64.NO_WRAP
        )
    }

    private fun startLogin() {
        try {
            val verifier = randomString(64)
            val state = randomString(24)

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray())

            val challenge = Base64.encodeToString(
                digest,
                Base64.URL_SAFE or
                    Base64.NO_PADDING or
                    Base64.NO_WRAP
            )

            prefs.edit()
                .putString("verifier", verifier)
                .putString("state", state)
                .apply()

            val scopes = listOf(
                "user-read-private",
                "user-read-email",
                "playlist-read-private",
                "playlist-read-collaborative"
            ).joinToString(" ")

            val url = "https://accounts.spotify.com/authorize?" +
                "client_id=$clientId" +
                "&response_type=code" +
                "&redirect_uri=${enc(redirectUri)}" +
                "&code_challenge_method=S256" +
                "&code_challenge=$challenge" +
                "&state=$state" +
                "&scope=${enc(scopes)}"

            showStatus("Membuka login Spotify...")

            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            )

        } catch (e: Exception) {
            showStatus("Login gagal: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { handleCallback(it) }
    }

    private fun handleCallback(uri: Uri) {
        val returnedState = uri.getQueryParameter("state")
        val savedState = prefs.getString("state", null)
        val code = uri.getQueryParameter("code")

        if (returnedState == null || returnedState != savedState) {
            showStatus("Login gagal: state tidak cocok")
            return
        }

        prefs.edit().remove("state").apply()

        if (code == null) {
            showStatus("Login dibatalkan")
            return
        }

        val verifier = prefs.getString("verifier", null)

        if (verifier == null) {
            showStatus("Sesi login tidak ditemukan")
            return
        }

        prefs.edit().remove("verifier").apply()

        showStatus("Menghubungkan Spotify...")

        executor.execute {
            try {
                val result = postForm(
                    "https://accounts.spotify.com/api/token",
                    mapOf(
                        "client_id" to clientId,
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to redirectUri,
                        "code_verifier" to verifier
                    )
                )

                val accessToken = result.getString("access_token")

                result.optString("refresh_token")
                    .takeIf { it.isNotEmpty() }
                    ?.let { saveToken(it) }

                loadProfile(accessToken)

            } catch (e: Exception) {
                showStatus("Login error: ${e.message}")
            }
        }
    }

    private fun restoreSession() {
        val refreshToken = readToken() ?: run {
            showStatus("Pilih mode untuk memulai")
            return
        }

        showStatus("Memulihkan sesi...")

        executor.execute {
            try {
                val result = postForm(
                    "https://accounts.spotify.com/api/token",
                    mapOf(
                        "client_id" to clientId,
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken
                    )
                )

                result.optString("refresh_token")
                    .takeIf { it.isNotEmpty() }
                    ?.let { saveToken(it) }

                loadProfile(result.getString("access_token"))

            } catch (e: Exception) {
                showStatus("Sesi berakhir. Login ulang.")
            }
        }
    }

    private fun loadProfile(token: String) {
        val connection = URL(
            "https://api.spotify.com/v1/me"
        ).openConnection() as HttpURLConnection

        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        connection.setRequestProperty(
            "Authorization",
            "Bearer $token"
        )

        try {
            val status = connection.responseCode

            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()
                ?.use { it.readText() } ?: ""

            if (status !in 200..299) {
                throw IOException(
                    "Spotify HTTP $status: $response"
                )
            }

            val json = JSONObject(response)
            val name = json.optString(
                "display_name",
                "Spotify User"
            )

            runOnUiThread {
                binding.txtAccount.text = name
                binding.txtStatus.text =
                    "Spotify Premium mode terhubung"

                binding.btnLogout.visibility = View.VISIBLE
                binding.btnLogin.visibility = View.GONE
            }

        } finally {
            connection.disconnect()
        }
    }

    private fun postForm(
        address: String,
        params: Map<String, String>
    ): JSONObject {

        val connection = URL(address)
            .openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        connection.setRequestProperty(
            "Content-Type",
            "application/x-www-form-urlencoded"
        )

        val body = params.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }

        try {
            connection.outputStream.use {
                it.write(body.toByteArray())
            }

            val status = connection.responseCode

            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream.bufferedReader()
                .use { it.readText() }

            if (status !in 200..299) {
                throw IOException(
                    "HTTP $status: $response"
                )
            }

            return JSONObject(response)

        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)

        val alias = "akong_spotify_key"

        if (store.containsAlias(alias)) {
            return store.getKey(alias, null) as SecretKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .build()
        )

        return generator.generateKey()
    }

    private fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())

        val encrypted = cipher.doFinal(token.toByteArray())
        val combined = cipher.iv + encrypted

        prefs.edit()
            .putString(
                "refresh_token",
                Base64.encodeToString(
                    combined,
                    Base64.NO_WRAP
                )
            )
            .apply()
    }

    private fun readToken(): String? {
        return try {
            val stored = prefs.getString(
                "refresh_token",
                null
            ) ?: return null

            val combined = Base64.decode(
                stored,
                Base64.NO_WRAP
            )

            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(
                12,
                combined.size
            )

            val cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                GCMParameterSpec(128, iv)
            )

            String(cipher.doFinal(encrypted))

        } catch (e: Exception) {
            null
        }
    }

    private fun logout() {
        prefs.edit().clear().apply()

        binding.txtAccount.text = "Spotify Account"
        binding.btnLogin.visibility = View.GONE
        binding.btnLogout.visibility = View.GONE

        showStatus("Sudah logout")
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            binding.txtStatus.text = message
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}