package com.dbrij.resolve

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Who is signed in to your app. */
data class ResolveIdentity(
    val email: String,
    val name: String? = null,
    /** Your own user id for them. */
    val externalId: String? = null,
    /**
     * Made on YOUR server, never in the app: HMAC SHA256, in hex, of the lowercase email,
     * or of `externalId + "\n" + email` when you send an externalId, keyed with the desk's
     * identity secret. With it the customer is signed straight in.
     */
    val userHash: String? = null,
) {
    internal val who: String get() = "${(externalId ?: "").trim()}\n${email.trim().lowercase()}"

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("email", email)
        name?.let { put("name", it) }
        externalId?.let { put("externalId", it) }
        userHash?.let { put("userHash", it) }
    }
}

data class ResolveUnread(val count: Int, val ticketIds: List<String>) {
    companion object {
        val NONE = ResolveUnread(0, emptyList())
    }
}

/** What the support screen says to the app. */
sealed class ResolveHostMessage {
    object Close : ResolveHostMessage()
    data class Unread(val count: Int) : ResolveHostMessage()
    data class Session(val token: String) : ResolveHostMessage()

    companion object {
        /** Reads what the screen sent, or null for anything that is not ours. */
        fun parse(raw: String): ResolveHostMessage? {
            val json = try { JSONObject(raw) } catch (_: Exception) { return null }
            return when (json.optString("type")) {
                "dbrij:resolve:close" -> Close
                "dbrij:resolve:unread" -> if (json.has("count")) Unread(maxOf(0, Math.round(json.optDouble("count")).toInt())) else null
                "dbrij:resolve:session" -> json.optString("token").takeIf { it.startsWith("rs_") }?.let { Session(it) }
                else -> null
            }
        }
    }
}

class ResolveException(message: String, val status: Int) : Exception(message)

/**
 * Talks to Resolve for the badge and signed sign in, and builds the screen's address.
 * The kept session lives in this app's private preferences.
 */
class ResolveClient(
    context: Context,
    val desk: String,
    val portalUrl: String = "https://resolve.dbrij.com",
    val apiUrl: String = "https://api.dbrij.com/api/v1",
) {
    private val prefs = context.applicationContext.getSharedPreferences("dbrij_resolve", Context.MODE_PRIVATE)
    private val key = "session.$desk"

    /** The support screen. A signed identity rides in the FRAGMENT, never sent to a server. */
    fun url(identity: ResolveIdentity? = null, app: Boolean = true, color: String? = null): Uri {
        val b = Uri.parse(portalUrl.trimEnd('/')).buildUpon().appendPath("support").appendPath(desk).appendQueryParameter("embed", "1")
        if (app) b.appendQueryParameter("app", "1")
        color?.let { b.appendQueryParameter("color", it) }
        if (identity != null && identity.userHash == null) {
            identity.name?.let { b.appendQueryParameter("name", it) }
            b.appendQueryParameter("email", identity.email)
            identity.externalId?.let { b.appendQueryParameter("externalId", it) }
        }
        if (identity?.userHash != null) {
            val payload = Base64.encodeToString(identity.toJson().toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            b.encodedFragment("identity=$payload")
        }
        return b.build()
    }

    private suspend fun call(path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): JSONObject? =
        withContext(Dispatchers.IO) {
            val conn = URL("${apiUrl.trimEnd('/')}/public/resolve$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("content-type", "application/json")
                token?.let { conn.setRequestProperty("x-resolve-token", it) }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                if (status == 204) return@withContext null
                val text = (if (status >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val json = try { JSONObject(text) } catch (_: Exception) { null }
                if (status >= 400) {
                    val m = json?.opt("message")
                    val message = (m as? JSONArray)?.optString(0) ?: (m as? String) ?: "Resolve answered $status"
                    throw ResolveException(message, status)
                }
                json?.optJSONObject("data") ?: json
            } finally {
                conn.disconnect()
            }
        }

    private fun kept(): Pair<String, String?>? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val j = JSONObject(raw)
            j.getString("token") to (if (j.isNull("who")) null else j.optString("who"))
        } catch (_: Exception) {
            null
        }
    }

    private fun keep(token: String, who: String?) {
        prefs.edit().putString(key, JSONObject().put("token", token).put("who", who ?: JSONObject.NULL).toString()).apply()
    }

    /** Sign the customer in with a userHash from your server. */
    suspend fun identify(identity: ResolveIdentity): String {
        require(identity.userHash != null) { "identify needs a userHash made on your server" }
        val token = call("/desk/${Uri.encode(desk)}/identify", "POST", identity.toJson())?.optString("token")
        if (token.isNullOrEmpty()) throw ResolveException("Resolve did not return a session", 500)
        keep(token, identity.who)
        return token
    }

    /**
     * Team replies the customer has not opened: the number on your help button. With a
     * signed identity it signs them in first when this device has no session for them.
     */
    suspend fun unread(identity: ResolveIdentity? = null): ResolveUnread {
        var current = kept()
        if (identity?.userHash != null && (current == null || (current.second != null && current.second != identity.who))) {
            current = try { identify(identity) to identity.who } catch (_: Exception) { null }
        }
        val token = current?.first ?: return ResolveUnread.NONE
        return try {
            val data = call("/unread", token = token)
            val arr = data?.optJSONArray("ticketIds")
            val ids = if (arr == null) emptyList() else List(arr.length()) { arr.optString(it) }
            ResolveUnread(data?.optInt("count", ids.size) ?: ids.size, ids)
        } catch (e: ResolveException) {
            if (e.status == 401) prefs.edit().remove(key).apply()
            ResolveUnread.NONE
        } catch (_: Exception) {
            ResolveUnread.NONE
        }
    }

    /** Keep a session the support screen reported. */
    fun rememberSession(token: String) {
        if (token.startsWith("rs_")) keep(token, null)
    }

    /** Forget the customer on this device: call it from your own sign out. */
    fun signOut() {
        prefs.edit().remove(key).apply()
    }
}
