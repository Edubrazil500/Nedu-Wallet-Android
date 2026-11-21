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

    /** Sends identity details and a selected selfie only to the authenticated server function. */
    suspend fun verifyIdentity(idType: String, idNumber: String, selfieDataUri: String): String =
        withContext(Dispatchers.IO) {
            require(idType in setOf("bvn", "nin")) { "Choose BVN or NIN." }
            require(idNumber.filterNot { it.isWhitespace() }.matches(Regex("\\d{11}"))) {
                "Enter a valid 11-digit identity number."
            }
            require(selfieDataUri.startsWith("data:image/")) { "Choose a selfie first." }
            val session = client.auth.currentSessionOrNull()
                ?: throw IllegalStateException("Please sign in again.")
            val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/verify-identity"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 35_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                val payload = JSONObject().apply {
                    put("id_type", idType)
                    put("id_number", idNumber.filterNot { it.isWhitespace() })
                    put("selfie_base64", selfieDataUri)
                }.toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bodyText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val body = runCatching { JSONObject(bodyText) }.getOrNull()
                if (status !in 200..299) {
                    throw IllegalStateException(
                        body?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: "Identity verification could not be completed. Please try again."
                    )
                }
                if (body == null || !body.optBoolean("verified", false)) {
                    throw IllegalStateException(
                        body?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: "Your identity details could not be verified."
                    )
                }
                body.optString("message").ifBlank { "Identity verified." }
            } finally {
                connection.disconnect()
            }
        }

    suspend fun loadBillCategories(): List<BillCategory> {
        val body = callPurchaseFunction(JSONObject().put("action", "categories"))
        val rows = body.optJSONArray("categories") ?: org.json.JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val code = item.optString("category_code").ifBlank { item.optString("code") }
            val name = item.optString("name").ifBlank { item.optString("category_name") }
            if (code.isBlank() || name.isBlank()) null else BillCategory(code, name)
        }
    }

    suspend fun loadBillers(categoryCode: String): List<BillProvider> {
        val body = callPurchaseFunction(
            JSONObject().put("action", "billers").put("category_code", categoryCode)
        )
        val rows = body.optJSONArray("billers") ?: org.json.JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val code = item.optString("biller_code")
            val name = item.optString("name").ifBlank { item.optString("biller_name") }
            if (code.isBlank() || name.isBlank()) null else BillProvider(code, name)
        }
    }

    suspend fun loadBillItems(billerCode: String): List<BillItem> {
        val body = callPurchaseFunction(
            JSONObject().put("action", "items").put("biller_code", billerCode)
        )
        val rows = body.optJSONArray("items") ?: org.json.JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val code = item.optString("item_code")
            val name = item.optString("name").ifBlank { item.optString("biller_name") }
            val amount = item.optString("amount", "0")
            val price = amount.toBigDecimalOrNull()
            if (code.isBlank() || name.isBlank() || price == null || price.signum() <= 0) {
                null
            } else {
                BillItem(
                    code = code,
                    name = name,
                    amount = amount,
                    isAirtime = item.optBoolean("is_airtime", false),
                    isResolvable = item.optBoolean("is_resolvable", false),
                )
            }
        }
    }

    suspend fun isBillPurchaseEnabled(): Boolean =
        callPurchaseFunction(JSONObject().put("action", "status"))
            .optBoolean("purchase_enabled", false)

    suspend fun validateBillCustomer(itemCode: String, customerId: String): JSONObject =
        callPurchaseFunction(
            JSONObject()
                .put("action", "validate")
                .put("item_code", itemCode)
                .put("customer_id", customerId.trim())
        )

    suspend fun purchaseBill(
        categoryCode: String,
        billerCode: String,
        itemCode: String,
        customerId: String,
        amount: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): JSONObject = callPurchaseFunction(
        JSONObject()
            .put("action", "buy")
            .put("category_code", categoryCode)
            .put("biller_code", billerCode)
            .put("item_code", itemCode)
            .put("customer_id", customerId.trim())
            .put("amount", amount)
            .put("idempotency_key", idempotencyKey)
    )

    private suspend fun callPurchaseFunction(payload: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val session = client.auth.currentSessionOrNull()
                ?: throw IllegalStateException("Please sign in again.")
            val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/purchase"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 25_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val body = runCatching { JSONObject(text) }.getOrNull()
                if (status !in 200..299) {
                    throw IllegalStateException(
                        body?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: "The bill service is temporarily unavailable."
                    )
                }
                body ?: throw IllegalStateException("The bill service returned an invalid response.")
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
                    kycStatus = row.kycStatus,
                    bvnVerifiedAt = row.bvnVerifiedAt,
                    ninVerifiedAt = row.ninVerifiedAt,
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
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("kyc_status") val kycStatus: String? = null,
    @SerialName("kyc_bvn_verified_at") val bvnVerifiedAt: String? = null,
    @SerialName("kyc_nin_verified_at") val ninVerifiedAt: String? = null,
)

data class AccountProfile(
    val id: String,
    val name: String,
    val email: String,
    val phone: String?,
    val country: String?,
    val avatarUrl: String?,
    val kycStatus: String?,
    val bvnVerifiedAt: String?,
    val ninVerifiedAt: String?,
) {
    val fiatKycComplete: Boolean
        get() = kycStatus.equals("Verified", ignoreCase = true) &&
            !bvnVerifiedAt.isNullOrBlank() && !ninVerifiedAt.isNullOrBlank()
}

data class BillCategory(val code: String, val name: String)

data class BillProvider(val code: String, val name: String)

data class BillItem(
    val code: String,
    val name: String,
    val amount: String,
    val isAirtime: Boolean,
    val isResolvable: Boolean,
)

@Serializable
private data class AvatarUpdate(@SerialName("avatar_url") val avatarUrl: String)

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
