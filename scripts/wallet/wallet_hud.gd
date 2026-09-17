extends Node
## Minimal wallet UI, autoload name: WalletHud.
## Visible only on the main menu and only when the native MWA bridge exists
## (Android build with the OrbitfallMwa plugin). Never gates gameplay:
## the offline campaign runs regardless of wallet state.

var root: CanvasLayer
var panel: PanelContainer
var status_label: Label
var btn_connect: Button
var btn_sign: Button
var btn_disconnect: Button

func _ready() -> void:
	if not WalletBridge.available():
		return
	root = CanvasLayer.new()
	root.layer = 100
	add_child(root)

	panel = PanelContainer.new()
	panel.anchor_left = 0.0
	panel.anchor_right = 1.0
	panel.anchor_top = 1.0
	panel.anchor_bottom = 1.0
	panel.offset_top = -64.0
	panel.offset_bottom = 0.0
	panel.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(panel)

	var hbox := HBoxContainer.new()
	hbox.alignment = BoxContainer.ALIGNMENT_CENTER
	hbox.add_theme_constant_override("separation", 12)
	panel.add_child(hbox)

	btn_connect = Button.new()
	btn_connect.text = "WALLET: CONNECT"
	btn_connect.pressed.connect(func(): WalletBridge.sign_in())
	hbox.add_child(btn_connect)

	btn_sign = Button.new()
	btn_sign.text = "SIGN TEST"
	btn_sign.pressed.connect(func(): WalletBridge.sign_message("DROPFIRE wallet check " + Time.get_datetime_string_from_system()))
	hbox.add_child(btn_sign)

	btn_disconnect = Button.new()
	btn_disconnect.text = "DISCONNECT"
	btn_disconnect.pressed.connect(func(): WalletBridge.disconnect_wallet())
	hbox.add_child(btn_disconnect)

	status_label = Label.new()
	status_label.text = "wallet: not connected"
	hbox.add_child(status_label)

	WalletBridge.connected.connect(_on_connected)
	WalletBridge.disconnected.connect(_on_disconnected)
	WalletBridge.sign_failed.connect(_on_sign_failed)
	WalletBridge.wallet_event.connect(_on_event)

	get_tree().scene_changed.connect(_on_scene_changed)
	_on_scene_changed(get_tree().current_scene)

func _on_sign_failed(m: String) -> void:
	_set_status("wallet error: " + m)

func _on_event(ev: Dictionary) -> void:
	if ev.get("type") == "signed":
		var data = ev.get("data", {})
		var sig := ""
		if data is Dictionary:
			sig = str(data.get("signature", ""))
		_set_status("signed ok: ..." + sig.right(8))

func _on_connected(a: String) -> void:
	_set_status("wallet: " + a.substr(0, 4) + "…" + a.right(4) if a.length() > 12 else "wallet: " + a)

func _on_disconnected() -> void:
	_set_status("wallet: not connected")

func _set_status(t: String) -> void:
	if status_label:
		status_label.text = t

func _on_scene_changed(scene: Node) -> void:
	if root == null:
		return
	var in_menu := false
	if scene != null:
		in_menu = scene.scene_file_path.contains("main_menu")
	root.visible = in_menu
