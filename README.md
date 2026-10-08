# Halo — Floating Button (Android)

A personal, AssistiveTouch-style floating button for Android. Built in phases.

**Phase 1 (current):** a draggable floating button that sits over every app, kept
alive by a foreground service. Tapping it shows a confirmation for now; the fan-out
controls and volume bar come in Phase 2.

## How the build works

Every push to `main` triggers a GitHub Actions build (`.github/workflows/build.yml`)
that compiles a debug APK and uploads it as a downloadable artifact — no local Android
tooling needed.

## Installing on your phone

1. Open the latest run under the repo's **Actions** tab.
2. Download the **halo-debug-apk** artifact (a `.zip`) and unzip it to get `app-debug.apk`.
3. Copy the APK to your phone, tap it, and allow installing from this source.
4. Open **Halo**, tap the button, and allow **Display over other apps**.
5. The floating button appears. Drag it around; tap it to confirm it works.

## Tech

Kotlin · `WindowManager` overlay (`TYPE_APPLICATION_OVERLAY`) · foreground service ·
`SYSTEM_ALERT_WINDOW` permission. Min SDK 26, target SDK 34.
