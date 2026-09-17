package io.github.leo88q.orbitfall.mwa

import android.content.Intent
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot

/**
 * ORBITFALL <-> Solana Mobile Wallet Adapter bridge (Godot Android plugin v2).
 *
 * GDScript side (scripts/wallet/wallet_bridge.gd) calls the @UsedByGodot methods
 * and listens to the "wallet_event" signal (single String argument: JSON object
 * {"type": "...", "data": {...}}).
 *
 * The actual MWA session runs inside [MwaProxyActivity], because MWA clientlib
 * requires an ActivityResultCaller (ComponentActivity) while Godot's main
 * activity is a plain android.app.Activity.
 */
class OrbitfallMwaPlugin(godot: Godot) : GodotPlugin(godot) {

    companion object {
        @Volatile
        var bus: ((String) -> Unit)? = null
    }

    override fun getPluginName(): String = "OrbitfallMwa"

    override fun getPluginSignals(): Set<SignalInfo> {
        return setOf(SignalInfo("wallet_event", String::class.java))
    }

    init {
        bus = { json ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                emitSignal("wallet_event", json)
            }
        }
    }

    override fun onMainDestroy() {
        bus = null
        super.onMainDestroy()
    }

    /** True when the native MWA bridge is present (Android build with plugin). */
    @UsedByGodot
    fun walletBridgeAvailable(): Boolean = true

    /** Open the invisible proxy activity and run an MWA `connect` session. */
    @UsedByGodot
    fun connectWallet() {
        launchProxy("connect", "")
    }

    /** Connect + Sign-In-With-Solana in one round trip (proves key ownership). */
    @UsedByGodot
    fun signInWallet() {
        launchProxy("signin", "")
    }

    /** Ask the wallet to sign an arbitrary UTF-8 message (base64-agnostic: raw bytes). */
    @UsedByGodot
    fun signMessage(message: String) {
        launchProxy("sign", message)
    }

    /** Revoke the current authorization on the wallet side. */
    @UsedByGodot
    fun disconnectWallet() {
        launchProxy("disconnect", "")
    }

    private fun launchProxy(op: String, payload: String) {
        val activity = getActivity() ?: return
        activity.runOnUiThread {
            val intent = Intent(activity, MwaProxyActivity::class.java).apply {
                putExtra(MwaProxyActivity.EXTRA_OP, op)
                putExtra(MwaProxyActivity.EXTRA_PAYLOAD, payload)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                MwaBus.emit("error", """{"message":"${esc(e.message ?: "startActivity failed")}"}""")
            }
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

/** Tiny static channel proxy activity -> plugin signal. */
object MwaBus {
    fun emit(type: String, dataJson: String) {
        OrbitfallMwaPlugin.bus?.invoke("""{"type":"$type","data":$dataJson}""")
    }
}
