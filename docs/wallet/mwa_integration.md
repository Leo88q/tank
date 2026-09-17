# Интеграция Solana Mobile Wallet Adapter (Фаза 3)

> Статус: код готов, собирается в CI; **проверка на реальном Android-устройстве —
> за человеком** (см. чек-лист внизу). Документация MWA сверена 2026-09-17
> (docs.solanamobile.com, clientlib-ktx 2.1.0 на Maven Central).

## Архитектура

```
GDScript (WalletBridge autoload)
   │  Engine.get_singleton("OrbitfallMwa").connectWallet() / signMessage(...)
   ▼
OrbitfallMwaPlugin (GodotPlugin v2, Kotlin)         android/orbitfall-mwa/
   │  startActivity(MwaProxyActivity, op/payload)
   ▼
MwaProxyActivity (ComponentActivity, невидимая)
   │  MobileWalletAdapter(...).connect/signIn/transact/disconnect (suspend)
   ▼  результат → MwaBus → сигнал "wallet_event" (JSON-строка) → GDScript
WalletHud (autoload) — кнопки/статус только в главном меню, только на Android
```

**Зачем proxy-activity:** MWA clientlib 2.x требует `ActivityResultCaller`
(`ComponentActivity`), а главная активность Godot (`GodotActivity`) — обычный
`android.app.Activity`. Невидимая `MwaProxyActivity` (translucent-тема,
`excludeFromRecents`) хостит сессию и завершается по результату.

## Контракт событий (сигнал `wallet_event`, аргумент — JSON-строка)

| type | data | когда |
|---|---|---|
| `connected` | `{address}` (base58) | успешный `connect` |
| `signin` | `{address}` | успешный Sign-In-With-Solana (доказывает владение ключом) |
| `signed` | `{signature}` (base64), `{message}` | успешный `signMessagesDetached` |
| `disconnected` | `{ok}` | `disconnect` (authToken сброшен и у нас) |
| `nowallet` | `{message}` | на устройстве нет MWA-кошелька (Phantom/Solflare/Seed Vault) |
| `error` | `{message}` | прочее |

`authToken` персистится в SharedPreferences плагина — повторный коннект без
диалога одобрения, пока пользователь не сделает disconnect.

## Сборка

- CI: шаг `Build OrbitfallMwa plugin AAR` в `fork-build.yml` (Gradle 8.11.1,
  AGP 8.9.1, Kotlin 2.0.21, compileSdk 36, minSdk 24) кладёт AAR в
  `addons/orbitfall_mwa/orbitfall_mwa-release.aar`; Android-пресет экспорта
  включает `gradle_build/use_gradle_build=true`, `plugins/OrbitfallMwa=true`,
  `version/min_sdk=24`, `version/target_sdk=36`.
- Локально: `tools/build_mwa_plugin.sh` (нужны JDK17 + Android SDK + Gradle).
- Maven-зависимости в приложение добавляет `export_plugin.gd`
  (`_get_android_dependencies`): `com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.1.0`,
  `androidx.activity:activity-ktx:1.9.2`, `kotlinx-coroutines-android:1.9.0`.

## Важные ограничения (из живой документации)

- MWA — **только Android**. iOS не поддерживается никак; мобильный web — только
  Chrome-on-Android. На Web/десктоп-сборках `WalletBridge.available() == false`,
  HUD не создаётся, кампания работает офлайн как раньше.
- Кошельки с MWA: Phantom, Solflare, Seed Vault (soon).

## Чек-лист проверки на устройстве (критерий Фазы 3)

1. Установить dev-APK (артефакт `orbitfall-android` из CI) на Android-устройство
   с установленным Phantom или Solflare.
2. Запустить игру → главное меню → кнопка `WALLET: CONNECT` → кошелек показывает
   диалог одобрения с именем `ORBITFALL` → после одобрения статус показывает
   сокращённый base58-адрес.
3. `SIGN TEST` → кошелек подписывает сообщение → статус `signed ok: …`.
4. `DISCONNECT` → статус `not connected`, повторный коннект снова с диалогом.
5. Запустить офлайн-кампанию (любой корпус) — играется без кошелька и с
   подключённым кошельком; HUD в бою не виден.
6. Устройство без MWA-кошелька → кнопка даёт `nowallet`-ошибку, игра работает.

## Открытые решения (человек)

- `IDENTITY_URI` в плагине сейчас `https://orbitfall.example` — заменить на
  реальный домен перед паблишингом (влияет на диалог кошелька и SIWS domain).
- Показывать ли адрес/баланс в бою или только в меню (сейчас — только меню).
