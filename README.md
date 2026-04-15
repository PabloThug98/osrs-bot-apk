# OSRS Auto Trainer Bot 🤖

An Android bot for Old School RuneScape Mobile that uses the Accessibility API and optional
screen capture to automate common skilling tasks via a floating overlay.

---

## ✅ Changelog — v3.0 (Latest)

> All fixes applied by automated code-review pass. The project now **compiles cleanly**
> and is free of the runtime crashes present in v2.x.

### Bug Fixes

| # | File | Issue | Fix |
|---|------|-------|-----|
| 1 | `ObjectDetector.kt` | Constructor only accepted `AccessibilityService` but `BotService` passed a second `ScreenCaptureManager` arg — **compile error** | Added optional `capture: ScreenCaptureManager? = null` parameter |
| 2 | `ObjectDetector.kt` | Five scripts accessed `detector.pixelDetector` which didn't exist — **compile error** | Exposed `val pixelDetector: PixelDetector? = capture?.let { PixelDetector(it) }` |
| 3 | `WoodcuttingScript.kt` | `InventoryDetector` init used a force non-null `!!` that NPE-crashed whenever screen-capture permission was denied | Changed `invDetect` to nullable `InventoryDetector?`; all usages guarded with `?.` |
| 4 | `CombatScript.kt` / `FishingScript.kt` / `MiningScript.kt` / `ChocolateDustScript.kt` | `private val capture` was inferred as non-nullable even though `detector.pixelDetector?.capture` can return `null` | Explicitly typed as `ScreenCaptureManager?` + added missing import |
| 5 | `MainActivity.kt` | `MiningScript` existed in `BotService` and `ScriptInfo` but was **completely absent from the UI** — no radio button, no card | Added `ScriptOption(R.id.rbMining, R.id.cardMining, "mining")` to `setupScriptToggles()` |
| 6 | `activity_main.xml` | No `rbMining` / `cardMining` views — selecting Mining would crash with `Resources$NotFoundException` | Added Mining card (⛏️ icon, radio button, description) after the Combat card |
| 7 | `BotConfig.kt` / `OverlayManager.kt` | Mixed 2-space / 4-space indentation throughout both files | Normalised to consistent 4-space Kotlin standard |

### CI / CD Added

| Workflow | Trigger | Output |
|----------|---------|--------|
| `build.yml` | Push / PR to `main` | Debug APK uploaded as artifact (7-day retention) |
| `build-release.yml` | Push of `v*` tag | Signed APK attached to a GitHub Release |

---

## 📦 Scripts

| Script | Description | XP/action | GP/action |
|--------|-------------|-----------|-----------|
| 🍫 Chocolate Dust | Cuts chocolate bars with a knife and banks | 0 | 180 |
| 🪓 Woodcutting | Chops trees, detects full inventory, banks logs | 38 | 50 |
| 🎣 Fishing | Fishes spots, detects full inventory, banks fish | 40 | 40 |
| ⚔️ Combat | Kills monsters, monitors HP, eats food, loots | 60 | 20 |
| ⛏️ Mining | Mines ore rocks, detects full inventory, banks ore | 35 | 80 |

---

## ⚙️ How It Works

1. **Accessibility Service** — dispatches gestures (`tapHuman`, `swipeHuman`) using
   Bézier-curved paths for human-like movement.
2. **Screen Capture** *(optional)* — MediaProjection API captures frames; pixel detectors
   read HP/prayer/run orbs and inventory slots directly from screen pixels.
3. **Overlay** — a floating `TYPE_APPLICATION_OVERLAY` panel lets you start/stop the bot
   and view live stats while OSRS is open.
4. **Anti-ban** — random breaks, fatigue simulation, camera rotation, minimap glances,
   accidental misclicks, and auto-stop after N actions or M minutes.

---

## 🔐 Permissions Required

| Permission | Why |
|-----------|-----|
| `SYSTEM_ALERT_WINDOW` | Floating overlay on top of OSRS |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Keep bot alive in background |
| Accessibility Service | Inject gestures into OSRS |
| Screen Capture *(optional)* | Pixel-level HP / inventory detection |

---

## 🚀 Quick Start

1. Download the latest `osrsbot-signed.apk` from [Releases](../../releases).
2. Enable **Install Unknown Apps** for your browser/file manager.
3. Install the APK and open the app.
4. Tap **Grant Permissions** → grant overlay permission.
5. Tap **Open Accessibility** → enable *OSRS Bot* in the list.
6. Select a script, configure anti-ban options, pick a walker area.
7. Tap **Launch Floating Overlay**, switch to OSRS, and press **▶ Start**.

---

## 🏗️ Build from Source

```bash
# Requires Android Studio or JDK 17 + Android SDK
git clone https://github.com/PabloThug98/osrs-bot-apk.git
cd osrs-bot-apk
./gradlew assembleDebug        # debug build
./gradlew assembleRelease      # release build (unsigned)
```

Minimum SDK: **24 (Android 7)**  |  Target SDK: **34 (Android 14)**  |  Language: **Kotlin**

---

## 🗺️ Walker Areas (Woodcutting)

| Area | Trees | Bank |
|------|-------|------|
| None | Stay put (use TARGET button) | — |
| Lumbridge | NE of castle | Lumbridge bank |
| Draynor Village | Willows south of bank | Draynor bank |
| Varrock West | NW of bank | Varrock West bank |
| Varrock East | NE of bank | Varrock East bank |
| Falador | Park centre | Falador West bank |
| Edgeville | SE of bank | Edgeville bank |
| Barbarian Village | Village trees | Edgeville bank |

---

## ⚠️ Disclaimer

This project is for **educational and research purposes only**. Using bots in OSRS violates
Jagex's Terms of Service and may result in your account being banned. Use at your own risk.
