package com.nedu.wallet.data

import com.nedu.wallet.BuildConfig

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.util.UUID
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Read-only Android access to the current user's existing wallet rows.
 * RLS remains responsible for restricting rows to the signed-in user.
 */
class WalletRepository(
    private val client: io.github.jan.supabase.SupabaseClient =
        SupabaseClientProvider.client,
) {
    suspend fun signIn(email: String, password: String) {
        require(email.isNotBlank()) { "Enter your email address." }
        require(password.isNotBlank()) { "Enter your password." }

        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    /**
     * Creates an Auth user in the same Supabase project used by iOS.
     * The auth.users database trigger provisions the profile and seven wallets.
     * Returns true only when Supabase has also established a session;
     * email-confirmation projects return false until the user confirms.
     */
    suspend fun signUp(
        fullName: String,
        phone: String,
        email: String,
        password: String,
    ): Boolean {
        require(fullName.isNotBlank()) { "Enter your full name." }
        require(email.isNotBlank()) { "Enter your email address." }
        require(password.isNotBlank()) { "Enter a password." }

        val signupUser = client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("full_name", fullName.trim())
                if (phone.isNotBlank()) put("phone", phone.trim())
            }
        }

        // With email confirmation enabled Supabase returns a User but no
        // session. Without confirmation it returns null and creates a session.
        // Check both signals so an older persisted session is not mistaken
        // for a newly authenticated signup.
        val session = client.auth.currentSessionOrNull()
        return signupUser?.id != null && session?.user?.id == signupUser.id
    }

    /**
     * Updates only the authenticated user's display name and phone through
     * the profile-update Edge Function. The service-role key never leaves Supabase.
     */
    suspend fun updateMyProfile(name: String, phone: String?) = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.length in 2..120) { "Enter a name between 2 and 120 characters." }
        val session = client.auth.currentSessionOrNull()
            ?: throw IllegalStateException("Please sign in again.")

        val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/profile-update"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val payload = JSONObject().apply {
                put("name", cleanName)
                put("phone", phone?.trim().orEmpty())
            }.toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(response) }.getOrNull()
            if (status !in 200..299 || body?.optBoolean("success") != true) {
                throw IllegalStateException(
                    body?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: "We could not save your profile. Please try again."
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun uploadMyAvatar(bytes: ByteArray, mimeType: String): String {
        require(bytes.isNotEmpty()) { "Choose a profile photo first." }
        require(bytes.size <= 5 * 1024 * 1024) { "Choose an image smaller than 5 MB." }
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp")) {
            "Choose a JPG, PNG or WEBP image."
        }
        val userId = currentUserId()
            ?: throw IllegalStateException("Please sign in again.")
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val path = userId + "/" + UUID.randomUUID().toString() + "." + extension
        val bucket = client.storage["avatars"]
        bucket.upload(path, bytes) {
            contentType = ContentType.parse(mimeType)
        }
        val publicUrl = bucket.publicUrl(path)
        client.from("profiles").update(AvatarUpdate(publicUrl)) {
            filter { eq("id", userId) }
        }
        return publicUrl
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    fun currentUserId(): String? =
        client.auth.currentSessionOrNull()?.user?.id

    suspend fun loadMyProfile(): AccountProfile? {
        val userId = currentUserId()
            ?: throw IllegalStateException("Please sign in again.")

        return client.from("profiles")
            .select {
                filter {
                    eq("id", userId)
                }
            }
            .decodeList<ProfileRow>()
            .firstOrNull()
            ?.let { row ->
                AccountProfile(
                    id = row.id,
                    name = row.name,
                    email = row.email,
                    phone = row.phone,
                    country = row.country,
                    avatarUrl = row.avatarUrl,
                )
            }
    }

    suspend fun loadMyWallets(): List<WalletBalance> {
        val userId = currentUserId()
            ?: throw IllegalStateException("Please sign in again.")

        return client.from("wallets")
            .select {
                filter {
                    eq("user_id", userId)
                }
                order("code", Order.ASCENDING)
            }
            .decodeList<WalletRow>()
            .map { row ->
                WalletBalance(
                    id = row.id,
                    code = row.code,
                    name = row.name,
                    balance = row.balance?.jsonPrimitive?.content ?: "0",
                    isCrypto = row.isCrypto ?: false,
                    address = row.address,
                )
            }
    }
}

@Serializable
private data class ProfileRow(
    val id: String,
    val name: String,
    val email: String,
    val phone: String? = null,
    val country: String? = null,
    val avatarUrl: String? = null,
)

data class AccountProfile(
    val id: String,
    val name: String,
    val email: String,
    val phone: String?,
    val country: String?,
    val avatarUrl: String?,
)

@Serializable
private data class AvatarUpdate(val avatarUrl: String)

@Serializable
private data class WalletRow(
    val id: String,
    val code: String,
    val name: String,
    val balance: JsonElement? = null,
    @SerialName("is_crypto") val isCrypto: Boolean? = null,
    val address: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

data class WalletBalance(
    val id: String,
    val code: String,
    val name: String,
    /** Original JSON numeric text; do not convert money to Double. */
    val balance: String,
    val isCrypto: Boolean,
    val address: String?,
)
