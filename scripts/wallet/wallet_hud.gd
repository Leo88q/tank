extends Node
## Wallet + staked-match UI, autoload name: WalletHud.
## Visible only on the main menu and only when the native MWA bridge exists
## (Android build with the OrbitfallMwa plugin). Never gates gameplay:
## the offline campaign runs regardless of wallet state.

const STAKE_LAMPORTS := 10_000_000  # 0.01 SOL demo stake
const MATCH_TIMEOUT := 3600

var root: CanvasLayer
var panel: PanelContainer
var status_label: Label
var addr_edit: LineEdit
var match_buttons: Array = []

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
	panel.offset_top = -118.0
	panel.offset_bottom = 0.0
	panel.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(panel)

	var vbox := VBoxContainer.new()
	vbox.add_theme_constant_override("separation", 4)
	panel.add_child(vbox)

	# row 1: wallet
	var hbox := HBoxContainer.new()
	hbox.alignment = BoxContainer.ALIGNMENT_CENTER
	hbox.add_theme_constant_override("separation", 10)
	vbox.add_child(hbox)
	_add_button(hbox, "WALLET: CONNECT", func(): WalletBridge.sign_in())
	_add_button(hbox, "DISCONNECT", func(): WalletBridge.disconnect_wallet())
	status_label = Label.new()
	status_label.text = "wallet: not connected"
	hbox.add_child(status_label)

	# row 2: staked match (devnet)
	var hbox2 := HBoxContainer.new()
	hbox2.alignment = BoxContainer.ALIGNMENT_CENTER
	hbox2.add_theme_constant_override("separation", 6)
	vbox.add_child(hbox2)

	addr_edit = LineEdit.new()
	addr_edit.placeholder_text = "creator address (for join/consent/settle)"
	addr_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	hbox2.add_child(addr_edit)

	_add_button(hbox2, "CREATE", func(): WalletBridge._plugin.matchCreate(STAKE_LAMPORTS, MATCH_TIMEOUT))
	_add_button(hbox2, "JOIN", func(): WalletBridge._plugin.matchJoin(addr_edit.text.strip_edges()))
	_add_button(hbox2, "I WON", func(): WalletBridge._plugin.matchConsent(addr_edit.text.strip_edges(), 1 if _i_am_joiner() else 0))
	_add_button(hbox2, "SETTLE", func(): WalletBridge._plugin.matchSettle(addr_edit.text.strip_edges(), WalletBridge.address))
	_add_button(hbox2, "REFUND", func(): WalletBridge._plugin.matchRefund(addr_edit.text.strip_edges(), WalletBridge.address))
	_add_button(hbox2, "CANCEL", func(): WalletBridge._plugin.matchCancel(addr_edit.text.strip_edges()))

	WalletBridge.connected.connect(_on_connected)
	WalletBridge.disconnected.connect(_on_disconnected)
	WalletBridge.sign_failed.connect(func(m): _set_status("wallet error: " + m))
	WalletBridge.wallet_event.connect(_on_event)

	get_tree().scene_changed.connect(_on_scene_changed)
	_on_scene_changed(get_tree().current_scene)

func _i_am_joiner() -> bool:
	# if the address in the field is not ours, we are the joiner
	return addr_edit.text.strip_edges() != WalletBridge.address

func _add_button(parent: HBoxContainer, text: String, cb: Callable) -> void:
	var b := Button.new()
	b.text = text
	b.pressed.connect(cb)
	parent.add_child(b)
	match_buttons.append(b)

func _on_event(ev: Dictionary) -> void:
	var t = ev.get("type")
	if t == "signed":
		var data = ev.get("data", {})
		_set_status("signed ok: ..." + str(data.get("signature", "")).right(8))
	elif t == "match_ok":
		var data = ev.get("data", {})
		_set_status(str(data.get("op", "tx")) + " ok: " + str(data.get("signature", "")).left(12) + "…")
	elif t == "error":
		var data = ev.get("data", {})
		_set_status("error: " + str(data.get("message", "")))

func _on_connected(a: String) -> void:
	_set_status("wallet: " + (a.substr(0, 4) + "…" + a.right(4) if a.length() > 12 else a))

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
