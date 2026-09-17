extends Node
## Thin GDScript facade over the OrbitfallMwa Android plugin (MWA 2.x).
## Autoload name: WalletBridge. On non-Android platforms every method is a no-op
## and available() == false, so the offline campaign is unaffected.

signal wallet_event(ev: Dictionary)
signal connected(address: String)
signal disconnected()
signal sign_failed(message: String)

var _plugin: Object = null
var address: String = ""

func _ready() -> void:
	if Engine.has_singleton("OrbitfallMwa"):
		_plugin = Engine.get_singleton("OrbitfallMwa")
		_plugin.connect("wallet_event", Callable(self, "_on_wallet_event"))

func available() -> bool:
	return _plugin != null

func connect_wallet() -> void:
	if _plugin:
		_plugin.connectWallet()

## Connect + prove key ownership in one wallet round trip (SIWS).
func sign_in() -> void:
	if _plugin:
		_plugin.signInWallet()

func sign_message(message: String) -> void:
	if _plugin:
		_plugin.signMessage(message)

func disconnect_wallet() -> void:
	if _plugin:
		_plugin.disconnectWallet()

func _on_wallet_event(json_text: String) -> void:
	var ev = JSON.parse_string(json_text)
	if not ev is Dictionary:
		return
	var type: String = str(ev.get("type", ""))
	var data = ev.get("data", {})
	if not data is Dictionary:
		data = {}
	match type:
		"connected", "signin":
			address = str(data.get("address", ""))
			connected.emit(address)
		"disconnected":
			address = ""
			disconnected.emit()
		"error", "nowallet":
			sign_failed.emit(str(data.get("message", type)))
	wallet_event.emit(ev)
