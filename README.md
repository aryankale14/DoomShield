# DoomShield

**A real-time Android dopamine firewall, animated DoomPet companion, 12-month screen-time analytics engine, and multi-squad accountability app built with Kotlin, Jetpack Compose, and Supabase.**

Built to break mindless scrolling loops on Instagram, YouTube, Snapchat, and mobile games by making wasted time live, visible, visually unrewarding, and socially accountable — without root access or heavy accounts.

## Why DoomShield?

Passive tools like Digital Wellbeing only show charts *after* you've already lost an hour — and Android purges raw `UsageEvents` after ~7–10 days. DoomShield runs live on top of your chosen vice apps, escalates friction in real time, and archives 12 months of daily diagnostics:

* **Stealth Micro-Pill:** A matte-black floating capsule displaying a `24dp` animated DoomPet and a live tabular session timer (`MM:SS`). Tap to inspect today's cumulative app time, total vice-app time, a live roast, or close the app in one tap.

* **Side-Edge Docking & Auto-Popup:** Drag the pill to the left or right screen edge to collapse it into a minimal `30dp` peek handle. Set a custom **1–15 minute auto-popup timer** so it reappears periodically as a reality check.

* **4-Stage Dying DoomPet:** A custom 60fps Compose Canvas character that transitions from **Thriving** -> **Stressed (Sweating)** -> **Drained (Sick)** -> **Critical (Fainted)** as your time burns.

* **4 Selectable Visual Punishments:** Combine hardware-level **Heavy Dark Dimming** (`FLAG_DIM_BEHIND`), **Dead-Gray Color Wash**, **Shrinking Tunnel Vision Vignette**, and **CRT Scanlines** with an adjustable intensity slider (45%–92%) to strip the visual dopamine out of Reels and Shorts.

* **5-Second Reality Check:** When you cross your limit, a forced 5-second pause modal interrupts autopilot scrolling.

* **Day / Week / Month Insights & 12-Month Archive:**
  * **Day View:** Wasted time, impulse pickups, longest unbroken session, **Peak Hour** (your highest-risk hour of the day), interactive 24-hour binge heatmap (tap any bar to inspect), culprit apps breakdown, and 7-day pet survival streak.
  * **Week (7D) & Month (30D) Views:** Total wasted time, daily average, **vs. Last Week / Last Month** improvement delta (`%` and hours/minutes saved or lost), **Avg Peak Hour**, interactive 7-day / 30-day bar charts (tap any day for exact usage & top culprit app), and a **Discipline Summary** (clean days count, survival rate `%`, best day, and worst day).
  * **Auto 7-Day Backfill & 12-Month Retention:** Automatically backfills the past 7 days from Android `UsageStatsManager` on first launch and syncs rolling 365-day daily summaries to Supabase (`user_daily_history`) with a local offline cache.

* **Multi-Squad Accountability (Supabase):** Create or join multiple friend squads (e.g. Family, College, Gym) using 6-character invite codes (`DS-XXXX`). Features a horizontal squad switcher, **Most Disciplined** vs. **Shame Crown** highlights, a live daily leaderboard with each friend's DoomPet state, an automatic Wall of Shame feed when pets faint, and offline board caching.

* **Obsidian Minimal UI:** True pitch-black OLED (`#000000`), matte charcoal surfaces, `0.75dp` hairline dividers, spring-physics press interactions, tactile haptics, and a 4-tab floating capsule navigation bar.

## Architecture Overview

| File | Responsibility |
| ----- | ----- |
| `DoomStore.kt` | Exact `UsageEvents` session engine (tracks active activity class sets per package + screen lock events), 7-day auto-backfill, 365-day local rolling history store, `Day / Week / Month` period aggregation (`PeriodAnalytics`), peak-hour detection, and roast generator |
| `OverlayService.kt` | `LifecycleService` managing two `WindowManager` layers: a native 120fps `PunishmentOverlayView` (hardware dimmer + visual friction) and a hardware-accelerated `ComposeView` for the Stealth Micro-Pill, Edge Dock, and Reality Check modal |
| `SquadClient.kt` | Zero-SDK-bloat Supabase PostgREST client (`HttpURLConnection` + `org.json`) handling multi-squad creation/joining, idempotent daily `UPSERT` sync on vice-app exit, 12-month `user_daily_history` cloud sync + pruning, Wall of Shame logging, and offline board caching |
| `DoomPetCanvas.kt` | Scalable 60fps Jetpack Compose vector Canvas character with 4 animated emotional states |
| `MainActivity.kt` | Edge-to-edge Obsidian Minimal 4-tab UI (`Shield`, `Insights`, `Squad`, `Rules`), floating capsule nav bar, Day/Week/Month interactive charts, multi-squad switcher, and installed-app watchlist |
| `ic_doomshield_logo.xml` | Custom adaptive vector app icon |

## Tech Stack

* **Language:** Kotlin (JVM 17)

* **UI:** Jetpack Compose + Material 3 (`compose-bom:2024.09.00`)

* **System APIs:** `UsageStatsManager`, `WindowManager` (`TYPE_APPLICATION_OVERLAY`, `FLAG_DIM_BEHIND`), `LifecycleService`, Native Android `Canvas`

* **Backend (Opt-In Squads & 12-Month Archive):** Supabase PostgreSQL + PostgREST (direct HTTP client, zero external SDK overhead)

* **Min / Target SDK:** API 26 (Android 8.0) / API 35 (Android 15)

* **Offline-First:** Core tracking, overlays, visual friction, and Day/Week/Month local analytics work 100% offline; network syncs opt-in Squad leaderboards and 12-month cloud backups seamlessly when connected.

## Getting Started

### Download Ready-to-Install APK
Grab the latest **`DoomShield-v2.0.apk`** directly from the Releases page to install on any Android 8.0+ phone.

### Build from Source

1. Clone the repository:

   ```bash
   git clone https://github.com/aryankale14/DoomShield.git
   ```

2. Open the folder in **Android Studio** and let Gradle sync.

3. *(Optional for Squad Tab & 12-Month Cloud Sync)* Create a free [Supabase](https://supabase.com) project, run the SQL schema (`squads`, `squad_members`, `daily_stats`, `shame_events`, `user_daily_history`), and paste your Project URL & Publishable/Anon Key into `SquadClient.kt`.

4. Click **Run (`app`)** to install directly on your connected Android device.

5. On first launch, grant the two required Android permissions directly from the **Shield** tab:

   * **Usage Access** (to detect foreground apps & compute Day/Week/Month diagnostics)

   * **Display Over Other Apps** (to render the Stealth Micro-Pill and visual friction layers)

### Export an Installable APK

In Android Studio: **≡ -> Build -> Generate App Bundles or APKs -> Generate APKs**, then locate `app/build/outputs/apk/debug/app-debug.apk`.

## Permissions & Privacy

Core screen-time tracking, overlays, and diagnostics run **on-device**. Network requests sync only daily summary totals (minutes, pickup count, top culprit app name, hourly totals, and pet stage) for 12-month history backup and opt-in Squad leaderboards — never screen content or messages.

| Permission | Why It's Needed |
| ----- | ----- |
| `PACKAGE_USAGE_STATS` | Reads foreground app transitions to measure real-time sessions and Day/Week/Month diagnostics |
| `SYSTEM_ALERT_WINDOW` | Draws the floating micro-pill and non-touch-blocking visual dimmer over watched apps |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the live timer active in the background while you use other apps |
| `POST_NOTIFICATIONS` | Shows the ongoing active-shield notification on Android 13+ |
| `INTERNET` | Syncs 12-month personal history backups and opt-in Squad leaderboards via Supabase |
| `QUERY_ALL_PACKAGES` | Populates the local installed-apps watchlist selector |

## Roadmap

* [x] **V2 Multi-Squad Shame:** Multiple friend squads, invite codes, live daily leaderboards & offline caching

* [x] **V2.1 Day / Week / Month Insights:** Peak Hour tracking, 7-day auto-backfill, period-over-period improvement deltas, and 12-month Supabase history archive

* [ ] Replace `QUERY_ALL_PACKAGES` with scoped `<queries>` launcher intent for Google Play compliance

* [ ] **Time-Window Schedules:** Automatic stricter night-mode friction (`11 PM – 2 AM`)

* [ ] **On-Device / LLM Contextual Coach:** Weekly AI habit breakdown & goal-aware nudges

## License

MIT License — built by [Aaryan Kale](https://github.com/aryankale14).
