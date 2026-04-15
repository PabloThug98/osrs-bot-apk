# OSRS Auto Trainer Bot APK

Android bot for Old School RuneScape Mobile using Accessibility Service + floating overlay.

## Features

- **🍫 Chocolate Dust Maker** — Uses Knife on Chocolate Bars (~180K GP/hr)
- **🌲 Woodcutting Bot** — Chops trees, banks logs, detects nearest tree
- **🎣 Fishing Bot** — Finds and clicks fishing spots, banks when full
- **⚔️ Combat Trainer** — Attacks monsters, eats food, loots drops

### Antiban System
- Random breaks with configurable interval & duration
- Auto-stop by time limit or action count
- Stop if player detected nearby
- Human-like click timing & random offsets
- Fatigue simulation (slows down over time like a real player)

### Floating Overlay
- Draggable from the handle or title area
- Start / Stop buttons work without clicking through to the game
- Shows: Script, Actions, XP, GP/hr, Runtime, Status, Current action
- Minimisable to just the header bar
- Target selector supports tap-to-add and tap-again-to-remove

### Object / Tree Detector
- Scans screen via Accessibility Service for trees, items, monsters, fishing spots
- Finds nearest target to screen centre
- Auto re-targets when object depletes

## Requirements
- Android 8.0+ (API 26)
- OSRS Mobile installed
- Overlay permission (`Draw over other apps`)
- Accessibility Service enabled for OSRS Bot

## Build Instructions

1. Open project in **Android Studio**
2. Sync Gradle
3. Connect device or use emulator (API 26+)
4. Run `Build > Build Bundle(s) / APK(s) > Build APK`
5. APK will be in `app/build/outputs/apk/debug/`

## Setup on Phone

1. Install the APK
2. Open OSRS Bot app
3. Tap **Grant Permissions** → allow overlay
4. Tap **Open Accessibility Settings** → enable OSRS Bot
5. Select a money-making or skilling script and configure settings
6. Tap **Launch Floating Overlay**
7. Switch to OSRS — the overlay floats on top
8. Tap ▶ on the overlay to start

## Project Structure

```
app/src/main/java/com/osrsbot/autotrainer/
├── MainActivity.kt              — Main settings UI
├── BotService.kt                — Foreground service running the bot
├── OSRSAccessibilityService.kt  — Performs gestures on screen
├── overlay/
│   └── OverlayManager.kt        — Floating draggable overlay
├── scripts/
│   ├── BotScript.kt             — Base script class
│   ├── ChocolateDustScript.kt   — Chocolate grinder
│   ├── WoodcuttingScript.kt     — Tree chopper
│   ├── FishingScript.kt         — Fisher
│   └── CombatScript.kt          — Fighter
├── antiban/
│   └── AntiBanManager.kt        — Breaks, timing, auto-stop
├── detector/
│   └── ObjectDetector.kt        — Screen object detection
└── utils/
    ├── BotConfig.kt             — Configuration data class
    └── Logger.kt                — Logging utility
```
