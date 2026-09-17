package io.github.leo88q.orbitfall.mwa

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.*
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        private const val KEY_ADDR = "wallet_address"

        // Placeholder identity until the final web domain is chosen by the human
        // (see docs/rebrand/IDENTITY.md and PROJECT_STATUS open decisions).
        private const val IDENTITY_URI = "https://dropfire.example"
        private const val IDENTITY_NAME = "DROPFIRE"
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
                    "tx_create", "tx_join", "tx_consent",
                    "tx_settle", "tx_refund", "tx_cancel" -> doTx(op, payload)
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
                val addr = account?.publicKey?.let { Base58.encode(it) } ?: ""
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ADDR, addr).apply()
                MwaBus.emit("connected", json { put("address", addr) })
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
                val addr = account?.publicKey?.let { Base58.encode(it) } ?: ""
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ADDR, addr).apply()
                MwaBus.emit("signin", json { put("address", addr) })
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

    private suspend fun doTx(op: String, payload: String) {
        val addrB58 = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ADDR, null)
        if (addrB58.isNullOrEmpty()) {
            MwaBus.emit("error", json { put("message", "connect wallet first") })
            return
        }
        val wallet = SolTx.b58decode(addrB58)
        val jo = JSONObject(payload)
        val msg = withContext(Dispatchers.IO) {
            when (op) {
                "tx_create" -> MatchTx.create(wallet, jo.getLong("stake"), jo.getLong("timeout"))
                "tx_join" -> MatchTx.join(wallet, SolTx.b58decode(jo.getString("creator")))
                "tx_consent" -> MatchTx.consent(wallet, SolTx.b58decode(jo.getString("creator")), jo.getInt("winner"))
                "tx_settle" -> MatchTx.settle(wallet, SolTx.b58decode(jo.getString("creator")), SolTx.b58decode(jo.getString("joiner")))
                "tx_refund" -> MatchTx.refund(wallet, SolTx.b58decode(jo.getString("creator")), SolTx.b58decode(jo.getString("joiner")))
                else -> MatchTx.cancel(wallet, SolTx.b58decode(jo.getString("creator")))
            }
        }
        val sender = ActivityResultSender(this)
        val adapter = adapter()
        when (val result = adapter.transact(sender) { signAndSendTransactions(arrayOf(msg)) }) {
            is TransactionResult.Success -> {
                persistToken(adapter)
                val sig = result.successPayload?.signatures?.firstOrNull()
                MwaBus.emit("match_ok", json {
                    put("op", op)
                    put("signature", sig?.let { Base58.encode(it) } ?: "")
                })
            }
            is TransactionResult.NoWalletFound ->
                MwaBus.emit("nowallet", json { put("message", "No MWA compatible wallet app found on device.") })
            is TransactionResult.Failure ->
                MwaBus.emit("error", json { put("message", result.e.message ?: "tx failed") })
            else ->
                MwaBus.emit("error", json { put("message", "unexpected tx result") })
        }
    }

    private inline fun json(block: JSONObject.() -> Unit): String =
        JSONObject().apply(block).toString()
}
