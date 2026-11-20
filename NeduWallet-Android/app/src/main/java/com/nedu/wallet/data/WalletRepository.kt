package com.nedu.wallet.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        return signupUser == null && client.auth.currentSessionOrNull() != null
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
)

data class AccountProfile(
    val id: String,
    val name: String,
    val email: String,
    val phone: String?,
    val country: String?,
)

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
