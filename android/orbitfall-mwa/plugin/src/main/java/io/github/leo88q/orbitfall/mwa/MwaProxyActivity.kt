package io.github.leo88q.orbitfall.mwa

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.*
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Invisible ComponentActivity that hosts MWA sessions.
 *
 * MWA clientlib 2.x requires an ActivityResultCaller; Godot's GodotActivity is a
 * plain Activity, so every wallet operation is delegated here. The activity has
 * no UI (translucent theme) and finishes as soon as the transaction completes.
 */
class MwaProxyActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OP = "orbitfall_mwa_op"
        const val EXTRA_PAYLOAD = "orbitfall_mwa_payload"
        private const val PREFS = "orbitfall_mwa"
        private const val KEY_AUTH_TOKEN = "auth_token"

        // Placeholder identity until the final web domain is chosen by the human
        // (see docs/rebrand/IDENTITY.md and PROJECT_STATUS open decisions).
        private const val IDENTITY_URI = "https://orbitfall.example"
        private const val IDENTITY_NAME = "ORBITFALL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val op = intent.getStringExtra(EXTRA_OP) ?: "connect"
        val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: ""

        lifecycleScope.launch {
            try {
                when (op) {
                    "connect" -> doConnect()
                    "signin" -> doSignIn()
                    "sign" -> doSign(payload)
                    "disconnect" -> doDisconnect()
                    else -> MwaBus.emit("error", json { put("message", "unknown op $op") })
                }
            } catch (e: Exception) {
                MwaBus.emit("error", json { put("message", e.message ?: e.javaClass.simpleName) })
            } finally {
                finish()
            }
        }
    }

    private fun adapter(): MobileWalletAdapter {
        val adapter = MobileWalletAdapter(
            connectionIdentity = ConnectionIdentity(
                identityUri = Uri.parse(IDENTITY_URI),
                iconUri = Uri.parse("icon.png"),
                identityName = IDENTITY_NAME,
            )
        )
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString(KEY_AUTH_TOKEN, null)?.let { adapter.authToken = it }
        return adapter
    }

    private fun persistToken(adapter: MobileWalletAdapter) {
        adapter.authToken?.let { token ->
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_AUTH_TOKEN, token).apply()
        }
    }

    private fun clearToken() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_AUTH_TOKEN).apply()
    }

    private suspend fun doConnect() {
        val sender = ActivityResultSender(this)
        val adapter = adapter()
        when (val result = adapter.connect(sender)) {
            is TransactionResult.Success -> {
                persistToken(adapter)
                val account = result.authResult.accounts.firstOrNull()
                MwaBus.emit("connected", json {
                    put("address", account?.publicKey?.let { Base58.encode(it) } ?: "")
                })
            }
            is TransactionResult.NoWalletFound ->
                MwaBus.emit("nowallet", json { put("message", "No MWA compatible wallet app found on device.") })
            is TransactionResult.Failure ->
                MwaBus.emit("error", json { put("message", result.e.message ?: "connect failed") })
            else ->
                MwaBus.emit("error", json { put("message", "unexpected connect result") })
        }
    }

    private suspend fun doSignIn() {
        val sender = ActivityResultSender(this)
        val adapter = adapter()
        when (val result = adapter.signIn(
            sender,
            SignInWithSolana.Payload(IDENTITY_URI.removePrefix("https://"), "Sign in to $IDENTITY_NAME")
        )) {
            is TransactionResult.Success -> {
                persistToken(adapter)
                val account = result.authResult.accounts.firstOrNull()
                MwaBus.emit("signin", json {
                    put("address", account?.publicKey?.let { Base58.encode(it) } ?: "")
                })
            }
            is TransactionResult.NoWalletFound ->
                MwaBus.emit("nowallet", json { put("message", "No MWA compatible wallet app found on device.") })
            is TransactionResult.Failure ->
                MwaBus.emit("error", json { put("message", result.e.message ?: "signin failed") })
            else ->
                MwaBus.emit("error", json { put("message", "unexpected signin result") })
        }
    }

    private suspend fun doSign(message: String) {
        val sender = ActivityResultSender(this)
        val adapter = adapter()
        when (val result = adapter.transact(sender) { authResult ->
            val account = authResult.accounts.firstOrNull()
                ?: throw IllegalStateException("no authorized account")
            signMessagesDetached(
                arrayOf(message.toByteArray(Charsets.UTF_8)),
                arrayOf(account.publicKey)
            )
        }) {
            is TransactionResult.Success -> {
                persistToken(adapter)
                val signed = result.successPayload?.messages?.firstOrNull()
                val signature = signed?.signatures?.firstOrNull()
                MwaBus.emit("signed", json {
                    put("signature", signature?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
                    put("message", message)
                })
            }
            is TransactionResult.NoWalletFound ->
                MwaBus.emit("nowallet", json { put("message", "No MWA compatible wallet app found on device.") })
            is TransactionResult.Failure ->
                MwaBus.emit("error", json { put("message", result.e.message ?: "sign failed") })
            else ->
                MwaBus.emit("error", json { put("message", "unexpected sign result") })
        }
    }

    private suspend fun doDisconnect() {
        val sender = ActivityResultSender(this)
        val adapter = adapter()
        when (val result = adapter.disconnect(sender)) {
            is TransactionResult.Success -> {
                clearToken()
                MwaBus.emit("disconnected", json { put("ok", true) })
            }
            is TransactionResult.NoWalletFound -> {
                clearToken()
                MwaBus.emit("disconnected", json { put("ok", true) })
            }
            is TransactionResult.Failure ->
                MwaBus.emit("error", json { put("message", result.e.message ?: "disconnect failed") })
            else ->
                MwaBus.emit("error", json { put("message", "unexpected disconnect result") })
        }
    }

    private inline fun json(block: JSONObject.() -> Unit): String =
        JSONObject().apply(block).toString()
}
