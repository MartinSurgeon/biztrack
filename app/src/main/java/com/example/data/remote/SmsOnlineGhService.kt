package com.example.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed class SmsSendResult {
    data class Success(val responseMessage: String, val usingSmsOnlineGh: Boolean, val batchId: String? = null) : SmsSendResult()
    data class Failure(val errorMessage: String, val canFallbackToNative: Boolean, val errorCode: String? = null) : SmsSendResult()
}

sealed class SmsBalanceResult {
    data class Success(val balanceText: String, val rawCredit: Double? = null) : SmsBalanceResult()
    data class Failure(val errorMessage: String) : SmsBalanceResult()
}

object SmsService {
    private const val TAG = "SmsService"
    // Official SMSOnlineGH v5 Endpoint as documented at dev.smsonlinegh.com
    private const val SMS_ONLINE_GH_V5_SEND_URL = "https://api.smsonlinegh.com/v5/message/sms/send"
    private const val SMS_ONLINE_GH_V5_BALANCE_URL = "https://api.smsonlinegh.com/v5/account/balance"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Cleans and sanitizes the API key, removing accidental prefixes like 'key ', 'Key ', quotes or whitespace.
     */
    fun sanitizeApiKey(rawKey: String): String {
        return rawKey.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace(Regex("^(key|Key|Bearer|bearer)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * Formats Ghanaian and international phone numbers into standard format (e.g., 233XXXXXXXXX).
     * Correctly handles leading zeros after country codes (e.g. +233024... -> 23324...).
     */
    fun formatGhanaPhoneNumber(rawPhone: String): String {
        var digits = rawPhone.replace(Regex("[^0-9+]"), "")
        if (digits.startsWith("+2330")) {
            digits = "233" + digits.substring(5)
        } else if (digits.startsWith("2330")) {
            digits = "233" + digits.substring(4)
        } else if (digits.startsWith("+233")) {
            digits = digits.removePrefix("+")
        } else if (digits.startsWith("233")) {
            // Already starts with 233
        } else if (digits.startsWith("0") && digits.length >= 10) {
            digits = "233" + digits.substring(1)
        } else if (digits.length == 9) {
            digits = "233$digits"
        } else {
            digits = digits.removePrefix("+")
        }
        return digits
    }

    /**
     * Sends SMS using SMSOnlineGH v5 API.
     * Specification: POST https://api.smsonlinegh.com/v5/message/sms/send
     * Headers:
     *   Content-Type: application/json
     *   Accept: application/json
     *   Authorization: key YOUR_API_KEY
     * Body:
     *   { "text": "...", "type": 0, "sender": "...", "destinations": ["233XXXXXXXXX"] }
     */
    suspend fun sendSmsOnlineGh(
        apiKey: String,
        senderId: String,
        recipientPhone: String,
        message: String
    ): SmsSendResult = withContext(Dispatchers.IO) {
        val cleanKey = sanitizeApiKey(apiKey)
        if (cleanKey.isBlank()) {
            return@withContext SmsSendResult.Failure(
                "SMSOnlineGH API Key is not configured. Please enter your API Key in Settings from dev.smsonlinegh.com.",
                canFallbackToNative = true
            )
        }

        val formattedPhone = formatGhanaPhoneNumber(recipientPhone)
        if (formattedPhone.length < 9) {
            return@withContext SmsSendResult.Failure("Invalid recipient phone number: $recipientPhone", canFallbackToNative = false)
        }

        val effectiveSender = if (senderId.isNotBlank()) senderId.trim().take(11) else "BizTrack"

        try {
            // Build JSON payload
            val jsonPayload = JSONObject().apply {
                put("text", message)
                put("type", 0) // 0 = Standard GSM text
                put("sender", effectiveSender)
                put("destinations", JSONArray().apply { put(formattedPhone) })
            }

            val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(SMS_ONLINE_GH_V5_SEND_URL)
                .addHeader("Host", "api.smsonlinegh.com")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "key $cleanKey")
                .post(body)
                .build()

            Log.d(TAG, "Sending SMS to $formattedPhone via SMSOnlineGH v5. Sender: $effectiveSender")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d(TAG, "SMSOnlineGH Response code: ${response.code}, body: $responseBody")

            // Parse response JSON using JSONObject
            if (responseBody.isNotBlank()) {
                try {
                    val rootJson = JSONObject(responseBody)
                    val handshake = rootJson.optJSONObject("handshake")

                    if (handshake != null) {
                        val handshakeId = handshake.optInt("id", -1)
                        val handshakeLabel = handshake.optString("label", "")
                        val handshakeMsg = handshake.optString("msg", "").ifBlank { handshake.optString("message", "") }

                        if (handshakeId == 0 || handshakeLabel.equals("HSHK_OK", ignoreCase = true)) {
                            val dataObj = rootJson.optJSONObject("data")
                            val batchId = dataObj?.optString("batch")
                            val successMsg = if (handshakeMsg.isNotBlank()) handshakeMsg else "SMS delivered successfully via SMSOnlineGH!"
                            return@withContext SmsSendResult.Success(
                                responseMessage = successMsg,
                                usingSmsOnlineGh = true,
                                batchId = batchId
                            )
                        } else {
                            val friendlyError = mapHandshakeError(handshakeLabel, handshakeMsg, effectiveSender)
                            return@withContext SmsSendResult.Failure(
                                errorMessage = friendlyError,
                                canFallbackToNative = true,
                                errorCode = handshakeLabel
                            )
                        }
                    } else if (response.isSuccessful) {
                        // In case handshake object is omitted but status is 200
                        val status = rootJson.optString("status", "")
                        val msg = rootJson.optString("message", "")
                        if (status.equals("success", ignoreCase = true) || status.equals("OK", ignoreCase = true)) {
                            return@withContext SmsSendResult.Success(
                                responseMessage = msg.ifBlank { "SMS delivered successfully via SMSOnlineGH!" },
                                usingSmsOnlineGh = true
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse JSON response strictly: ${e.message}")
                }
            }

            if (response.isSuccessful) {
                return@withContext SmsSendResult.Success(
                    "SMS sent successfully via SMSOnlineGH!",
                    usingSmsOnlineGh = true
                )
            } else {
                val errorMsg = when (response.code) {
                    401, 403 -> "Authentication failed with SMSOnlineGH (HTTP ${response.code}). Check your API Key in Settings."
                    400 -> "Bad Request (HTTP 400). Please check your Sender ID or phone number."
                    404 -> "SMSOnlineGH endpoint error (HTTP 404)."
                    else -> "SMSOnlineGH returned HTTP ${response.code}: ${responseBody.take(120)}"
                }
                return@withContext SmsSendResult.Failure(
                    errorMessage = errorMsg,
                    canFallbackToNative = true
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error sending SMS", e)
            return@withContext SmsSendResult.Failure(
                "Network connection error to SMSOnlineGH: ${e.localizedMessage ?: "Please check internet connectivity"}",
                canFallbackToNative = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending SMS", e)
            return@withContext SmsSendResult.Failure(
                "Failed to send SMS: ${e.localizedMessage ?: "Unknown error"}",
                canFallbackToNative = true
            )
        }
    }

    /**
     * Checks account credit balance using SMSOnlineGH v5 API.
     */
    suspend fun checkAccountBalance(apiKey: String): SmsBalanceResult = withContext(Dispatchers.IO) {
        val cleanKey = sanitizeApiKey(apiKey)
        if (cleanKey.isBlank()) {
            return@withContext SmsBalanceResult.Failure("Please enter an API Key first.")
        }

        try {
            val emptyBody = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(SMS_ONLINE_GH_V5_BALANCE_URL)
                .addHeader("Host", "api.smsonlinegh.com")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "key $cleanKey")
                .post(emptyBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d(TAG, "Balance Response code: ${response.code}, body: $responseBody")

            if (responseBody.isNotBlank()) {
                val rootJson = JSONObject(responseBody)
                val handshake = rootJson.optJSONObject("handshake")

                if (handshake != null) {
                    val handshakeId = handshake.optInt("id", -1)
                    val handshakeLabel = handshake.optString("label", "")
                    val handshakeMsg = handshake.optString("msg", "")

                    if (handshakeId == 0 || handshakeLabel.equals("HSHK_OK", ignoreCase = true)) {
                        val dataObj = rootJson.optJSONObject("data")
                        val credit = dataObj?.optDouble("credit", -1.0) ?: -1.0
                        val creditText = if (credit >= 0) "$credit SMS Units" else "Active / Connected"
                        return@withContext SmsBalanceResult.Success(creditText, credit.takeIf { it >= 0 })
                    } else {
                        val friendly = mapHandshakeError(handshakeLabel, handshakeMsg, "")
                        return@withContext SmsBalanceResult.Failure(friendly)
                    }
                }
            }

            if (response.isSuccessful) {
                return@withContext SmsBalanceResult.Success("Account Connected (HTTP 200)")
            } else {
                return@withContext SmsBalanceResult.Failure("Authentication failed (HTTP ${response.code}). Check your API Key.")
            }
        } catch (e: Exception) {
            return@withContext SmsBalanceResult.Failure("Connection error: ${e.localizedMessage ?: "Failed to reach server"}")
        }
    }

    /**
     * Maps SMSOnlineGH protocol error labels to user-friendly messages with guidance.
     */
    private fun mapHandshakeError(label: String, msg: String, sender: String): String {
        return when {
            label.contains("AUTH_FAILED", ignoreCase = true) || label.contains("KEY_INVALID", ignoreCase = true) ->
                "Invalid API Key. Please verify and copy your valid API key from dev.smsonlinegh.com."
            label.contains("SENDER", ignoreCase = true) ->
                "Sender ID '$sender' is not approved on your SMSOnlineGH account. Register it under Sender Names at smsonlinegh.com."
            label.contains("INSUFFICIENT", ignoreCase = true) || label.contains("CREDIT", ignoreCase = true) ->
                "Insufficient SMS credits on your SMSOnlineGH account. Please recharge your SMS balance."
            label.contains("DESTINATION", ignoreCase = true) || label.contains("PHONE", ignoreCase = true) ->
                "Recipient phone number is invalid. Format: 024XXXXXXX or 233XXXXXXXXX."
            label.contains("INACTIVE", ignoreCase = true) ->
                "Your SMSOnlineGH account is inactive. Please log in to smsonlinegh.com."
            msg.isNotBlank() -> msg
            label.isNotBlank() -> "SMSOnlineGH Error: $label"
            else -> "SMSOnlineGH message rejection. Please verify your account status."
        }
    }

    /**
     * Launch native SMS app with prefilled text and phone number.
     */
    fun openNativeSms(context: Context, recipientPhone: String, message: String) {
        try {
            val cleanPhone = recipientPhone.replace(Regex("[^0-9+]"), "")
            val uri = Uri.parse("smsto:$cleanPhone")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open native SMS", e)
            val genericIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(genericIntent, "Send SMS via..."))
        }
    }

    /**
     * Share formatted text / receipt directly via WhatsApp.
     */
    fun shareViaWhatsApp(context: Context, recipientPhone: String, messageText: String) {
        val cleanPhone = formatGhanaPhoneNumber(recipientPhone)
        val encodedMessage = URLEncoder.encode(messageText, "UTF-8")
        
        try {
            val whatsappUrl = if (cleanPhone.isNotBlank()) {
                "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMessage"
            } else {
                "https://api.whatsapp.com/send?text=$encodedMessage"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(whatsappUrl)).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanPhone?text=$encodedMessage")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            } catch (e2: Exception) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, messageText)
                    type = "text/plain"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share Receipt via..."))
            }
        }
    }
}

