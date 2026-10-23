# FixTok Rework

FixTok is an unofficial TikTok client for Android TV / Google TV, built around a
full-screen WebView. The main screen supports TV navigation and an optional
configurable virtual cursor, with a separate secure Login WebView.

## Features

- 🖥️ Desktop version of TikTok in a full-screen WebView
- 🎮 Main screen: D-pad navigation with optional virtual mouse
- 🔐 Separate Login WebView with strict domain allowlist
- 🖱️ Configurable virtual cursor on the main screen and login screen
- 🍪 Cookie and session persistence (shared WebView cookie store)
- 🔄 Safe session migration: old sessions keep working automatically
- 🚪 Logout from Settings
- 🎬 HTML5 fullscreen video support
- 🚀 Splash screen
- 👤 by Manas

## Remote Control — Main screen

| Remote Button | Action |
|---|---|
| D-pad | Navigate TikTok, or move the cursor when mouse mode is on |
| OK / Enter | Short press: click/action; hold: toggle virtual mouse |
| RED | Short press: mouse on/off; hold: settings |
| GREEN | Toggle clean-screen mode |
| Back | Go back / exit (double-press) |
| Menu | On-screen remote (search, tabs, mouse, mute, login, settings) |
| Touch | Top-bar **Remote** button. In clean-screen mode the same button stays at the bottom-right. |

## Remote Control — Login WebView

| Remote Button | Action |
|---|---|
| D-pad | Move virtual mouse cursor |
| OK / Enter | Click |
| Back | Close popup / close login, return to main screen |

Cursor color (20 choices, including neon colors with a real glow), style (4 shapes: arrow, paper plane, dot, and the original teardrop), size and speed are configurable in Settings, with a live preview. The same cursor is used on the main and login screens. Clicks land on the cursor's hotspot (arrow tip, fingertip, circle center). With the mouse on, push the cursor against the top/bottom screen edge and press Up/Down (or use CH+/CH-, Page Up/Down) to scroll to the previous/next video.

## Security

- Login WebView navigation is restricted to an allowlist of hosts
  (tiktok.com, google.com and its login/captcha domains, facebook.com,
  fb.com, apple.com, cdn-apple.com), checked against the real URI host —
  never `url.contains()`.
- Camera/microphone web permission requests from web pages are always denied.
- The only JavaScript bridge is `FixtokVoice.start()` (voice search); it takes no input from the page and only opens the system speech recognizer. The microphone permission is requested only when you use voice search.
- No secrets in URLs or logs.
- Safe Browsing stays enabled.

## Technology

- Kotlin · Android TV / Google TV · WebView · Gradle

## Architecture

```text
FixTok
  ├── MainActivity   — TikTok WebView, D-pad, optional virtual mouse
  ├── LoginActivity  — Login WebView, allowlist, virtual mouse always on
  └── SettingsActivity — TV settings: bass, account, login/logout
```

## Support

Donations (USDT): `0xf3582caCE9450d8A9d9291D359Af75c89FC86C75`

Thank you for supporting FixTok! ❤️
