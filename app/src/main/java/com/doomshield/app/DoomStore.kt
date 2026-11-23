package com.doomshield.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject
enum class PetStage(val label: String, val sublabel: String) {
    CHILL("Thriving", "Focused & energized"),
    SWEATING("Stressed", "Dopamine trap detected"),
    SICK("Drained", "Visual punishment active"),
    FAINTED("Critical", "Brain-rot overload")
}

enum class RoastTone(val label: String) {
    GENTLE("Gentle Nudge"),
    SARCASTIC("Sarcastic"),
    BRUTAL("Brutal Honesty")
}

enum class VisualPunishment(val title: String, val subtitle: String) {
    HEAVY_DIM("Heavy Dark Dimmer", "Hardware dims screen up to 82% darkness"),
    GRAY_WASH("Dead-Gray Color Wash", "Washes out vibrant Reel/Short colors into flat ash gray"),
    TUNNEL_VIGNETTE("Shrinking Tunnel Vision", "Closes a dark vignette ring around the edges of the screen"),
    SCANLINE_GLITCH("Annoying CRT Scanlines", "Overlays horizontal static lines across videos & games")
}

data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val minutesToday: Int,
    val pickupsToday: Int,
    val longestStreakMins: Int
)

data class DoomDiagnostics(
    val totalWastedMinsToday: Int = 0,
    val totalPickupsToday: Int = 0,
    val longestSessionMins: Int = 0,
    val avgSessionMins: Int = 0,
    val hourlyMins: List<Int> = List(24) { 0 },
    val perAppBreakdown: List<AppUsageItem> = emptyList(),
    val pagesLost: Int = 0,
    val pushupsLost: Int = 0,
    val codingProblemsLost: Int = 0
)
data class DailyHistoryRecord(
    val dateKey: String, // "yyyy-MM-dd"
    val wastedMins: Int,
    val pickups: Int,
    val longestSessionMins: Int,
    val topCulpritApp: String,
    val hourlyMins: List<Int> = List(24) { 0 },
    val limitMins: Int = 30
)

data class DailyBarPoint(
    val dateKey: String,
    val shortLabel: String,
    val fullLabel: String,
    val wastedMins: Int,
    val topCulpritApp: String
)

data class PeriodAnalytics(
    val totalMins: Int = 0,
    val dailyAvgMins: Int = 0,
    val hasPrevData: Boolean = false,
    val deltaPercent: Int = 0,   // negative = improved (saved time), positive = worse
    val deltaMins: Int = 0,
    val peakHour: Int? = null,
    val peakHourTotalMins: Int = 0,
    val cleanDaysCount: Int = 0,
    val totalTrackedDays: Int = 0,
    val survivalRatePercent: Int = 100,
    val bestDayLabel: String = "—",
    val bestDayMins: Int = 0,
    val worstDayLabel: String = "—",
    val worstDayMins: Int = 0,
    val dailyBars: List<DailyBarPoint> = emptyList()
)
object DoomStore {
    private const val PREFS_NAME = "doom_shield_prefs"
    private const val KEY_WATCHED_PACKAGES = "watched_packages"
    private const val KEY_WARN_MINS = "warn_mins"
    private const val KEY_DIM_MINS = "dim_mins"
    private const val KEY_LIMIT_MINS = "limit_mins"
    private const val KEY_POPUP_INTERVAL_MINS = "popup_interval_mins"
    private const val KEY_PUNISHMENT_MODES = "punishment_modes"
    private const val KEY_PUNISHMENT_STRENGTH = "punishment_strength"
    private const val KEY_ROAST_TONE = "roast_tone"
    private const val KEY_SURVIVAL_HISTORY = "survival_history"

    val isServiceRunning = MutableStateFlow(false)

    private val defaultVicePackages = setOf(
        "com.instagram.android",
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.snapchat.android",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.pubg.imobile",
        "com.activision.callofduty.shooter",
        "com.supercell.clashofclans"
    )

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun getWatchedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_WATCHED_PACKAGES, null) ?: defaultVicePackages
    }

    fun toggleWatchedPackage(context: Context, pkg: String): Set<String> {
        val current = getWatchedPackages(context).toMutableSet()
        if (current.contains(pkg)) current.remove(pkg) else current.add(pkg)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_WATCHED_PACKAGES, current)
            .apply()
        return current
    }

    fun getThresholds(context: Context): Triple<Int, Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val warn = prefs.getInt(KEY_WARN_MINS, 10)
        val dim = prefs.getInt(KEY_DIM_MINS, 20)
        val limit = prefs.getInt(KEY_LIMIT_MINS, 30)
        return Triple(warn, dim, limit)
    }

    fun saveThresholds(context: Context, warn: Int, dim: Int, limit: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WARN_MINS, warn)
            .putInt(KEY_DIM_MINS, dim)
            .putInt(KEY_LIMIT_MINS, limit)
            .apply()
    }

    /**
     * How often the pill pops back out when pushed/docked to the side edge (1 to 15 minutes).
     */
    fun getEdgePopupIntervalMins(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_POPUP_INTERVAL_MINS, 5).coerceIn(1, 15)
    }

    fun setEdgePopupIntervalMins(context: Context, mins: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_POPUP_INTERVAL_MINS, mins.coerceIn(1, 15))
            .apply()
    }

    /**
     * User-selected visual punishment methods (can combine multiple!).
     */
    fun getSelectedPunishments(context: Context): Set<VisualPunishment> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY_PUNISHMENT_MODES, null)
            ?: setOf(VisualPunishment.HEAVY_DIM.name, VisualPunishment.GRAY_WASH.name)
        val parsed = raw.mapNotNull { runCatching { VisualPunishment.valueOf(it) }.getOrNull() }.toSet()
        return parsed.ifEmpty { setOf(VisualPunishment.HEAVY_DIM) }
    }

    fun togglePunishment(context: Context, mode: VisualPunishment): Set<VisualPunishment> {
        val current = getSelectedPunishments(context).toMutableSet()
        if (current.contains(mode) && current.size > 1) {
            current.remove(mode)
        } else {
            current.add(mode)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_PUNISHMENT_MODES, current.map { it.name }.toSet())
            .apply()
        return current
    }

    /**
     * Maximum intensity of the dimming/gray effect (0.50f to 0.90f, default 0.78f).
     */
    fun getPunishmentStrength(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_PUNISHMENT_STRENGTH, 0.78f).coerceIn(0.45f, 0.92f)
    }

    fun setPunishmentStrength(context: Context, strength: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_PUNISHMENT_STRENGTH, strength.coerceIn(0.45f, 0.92f))
            .apply()
    }

    fun getRoastTone(context: Context): RoastTone {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ROAST_TONE, RoastTone.SARCASTIC.name) ?: RoastTone.SARCASTIC.name
        return runCatching { RoastTone.valueOf(raw) }.getOrDefault(RoastTone.SARCASTIC)
    }

    fun setRoastTone(context: Context, tone: RoastTone) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ROAST_TONE, tone.name)
            .apply()
    }

    fun getStageForMinutes(mins: Int, warn: Int, dim: Int, limit: Int): PetStage {
        return when {
            mins >= limit -> PetStage.FAINTED
            mins >= dim -> PetStage.SICK
            mins >= warn -> PetStage.SWEATING
            else -> PetStage.CHILL
        }
    }

    fun getRoastMessage(appName: String, mins: Int, stage: PetStage, tone: RoastTone): String {
        return when (tone) {
            RoastTone.GENTLE -> when (stage) {
                PetStage.CHILL -> "Mindful check-in on $appName ($mins m)."
                PetStage.SWEATING -> "$mins mins on $appName. Did you find what you came for?"
                PetStage.SICK -> "Screen dimming on. $mins mins gone—your pet needs a break!"
                PetStage.FAINTED -> "$mins mins on $appName! Time to close & reset your focus."
            }
            RoastTone.SARCASTIC -> when (stage) {
                PetStage.CHILL -> "Checking $appName? Clock is ticking..."
                PetStage.SWEATING -> "$mins mins on $appName. That algorithm owns you right now."
                PetStage.SICK -> "$mins mins wasted. Draining colors so your dopamine dries up."
                PetStage.FAINTED -> "RIP your DoomPet ($mins m). Keep scrolling, I'm sure the next Reel changes your life."
            }
            RoastTone.BRUTAL -> when (stage) {
                PetStage.CHILL -> "Don't let $appName turn into another black hole."
                PetStage.SWEATING -> "$mins mins burned. You're trading real goals for cheap pixels."
                PetStage.SICK -> "$mins mins gone forever. You said you'd stop doing this."
                PetStage.FAINTED -> "$mins MINS WASTED. Close $appName right now and get back to work."
            }
        }
    }

    fun getCurrentForegroundApp(context: Context, fallbackPkg: String?): String? {
        if (!hasUsageStatsPermission(context)) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 120_000L, now)
        val event = UsageEvents.Event()

        var latestPkg: String? = fallbackPkg
        var latestClass: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg == "com.android.systemui") continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    latestPkg = pkg
                    latestClass = event.className
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (latestPkg == pkg && (latestClass == null || latestClass == event.className)) {
                        latestPkg = null
                        latestClass = null
                    }
                }
            }
        }
        return latestPkg
    }

    fun computeTodayDiagnostics(context: Context): DoomDiagnostics {
        if (!hasUsageStatsPermission(context)) return DoomDiagnostics()

        val watched = getWatchedPackages(context)
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val now = System.currentTimeMillis()

        val events = usm.queryEvents(startOfDay - 30 * 60_000L, now)
        val event = UsageEvents.Event()

        val appTotalMs = mutableMapOf<String, Long>()
        val appPickups = mutableMapOf<String, Int>()
        val appLongestMs = mutableMapOf<String, Long>()
        val lastClosedTimeMs = mutableMapOf<String, Long>()
        val hourlyMs = LongArray(24)

        val activeClassesForApp = mutableMapOf<String, MutableSet<String>>()
        val appSessionStartMs = mutableMapOf<String, Long>()

        fun commitInterval(pkg: String, rawStartMs: Long, rawEndMs: Long) {
            if (!watched.contains(pkg)) return
            val s = max(rawStartMs, startOfDay)
            val e = rawEndMs.coerceAtMost(now)
            val duration = (e - s).coerceAtLeast(0L)
            if (duration <= 0L || duration > 6 * 3600_000L) return

            appTotalMs[pkg] = (appTotalMs[pkg] ?: 0L) + duration
            if (duration > (appLongestMs[pkg] ?: 0L)) {
                appLongestMs[pkg] = duration
            }

            var cursor = s
            val cursorCal = Calendar.getInstance()
            while (cursor < e) {
                cursorCal.timeInMillis = cursor
                val hr = cursorCal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
                cursorCal.set(Calendar.MINUTE, 59)
                cursorCal.set(Calendar.SECOND, 59)
                cursorCal.set(Calendar.MILLISECOND, 999)
                val endOfHour = (cursorCal.timeInMillis + 1L).coerceAtMost(e)
                val slice = (endOfHour - cursor).coerceAtLeast(0L)
                hourlyMs[hr] += slice
                cursor = endOfHour
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            val pkg = event.packageName
            val cls = event.className ?: "default"

            when (event.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    for ((openPkg, classes) in activeClassesForApp) {
                        if (classes.isNotEmpty()) {
                            val start = appSessionStartMs[openPkg] ?: ts
                            commitInterval(openPkg, start, ts)
                            lastClosedTimeMs[openPkg] = ts
                            classes.clear()
                        }
                    }
                    appSessionStartMs.clear()
                }

                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg == null || pkg == "com.android.systemui") continue

                    for ((otherPkg, otherClasses) in activeClassesForApp) {
                        if (otherPkg != pkg && otherClasses.isNotEmpty()) {
                            val start = appSessionStartMs[otherPkg] ?: ts
                            commitInterval(otherPkg, start, ts)
                            lastClosedTimeMs[otherPkg] = ts
                            otherClasses.clear()
                            appSessionStartMs.remove(otherPkg)
                        }
                    }

                    val set = activeClassesForApp.getOrPut(pkg) { mutableSetOf() }
                    val wasEmpty = set.isEmpty()
                    set.add(cls)

                    if (wasEmpty) {
                        appSessionStartMs[pkg] = ts
                        if (watched.contains(pkg) && ts >= startOfDay) {
                            val lastClose = lastClosedTimeMs[pkg] ?: 0L
                            if (ts - lastClose > 15_000L) {
                                appPickups[pkg] = (appPickups[pkg] ?: 0) + 1
                            }
                        }
                    }
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (pkg == null) continue
                    val set = activeClassesForApp[pkg]
                    if (set != null && set.isNotEmpty()) {
                        set.remove(cls)
                        if (set.isEmpty()) {
                            val start = appSessionStartMs.remove(pkg) ?: ts
                            commitInterval(pkg, start, ts)
                            lastClosedTimeMs[pkg] = ts
                        }
                    }
                }
            }
        }

        for ((openPkg, classes) in activeClassesForApp) {
            if (classes.isNotEmpty()) {
                val start = appSessionStartMs[openPkg] ?: now
                commitInterval(openPkg, start, now)
            }
        }

        val items = watched.mapNotNull { pkg ->
            val totalMs = appTotalMs[pkg] ?: 0L
            val mins = (totalMs / 60_000L).toInt()
            val pickups = (appPickups[pkg] ?: 0).coerceAtLeast(if (mins > 0) 1 else 0)
            val longest = ((appLongestMs[pkg] ?: totalMs) / 60_000L).toInt().coerceAtMost(mins)
            if (mins == 0 && totalMs < 30_000L) return@mapNotNull null
            val displayMins = if (mins == 0) 1 else mins
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
            AppUsageItem(pkg, label, displayMins, pickups, longest.coerceAtLeast(1))
        }.sortedByDescending { it.minutesToday }

        val totalMins = items.sumOf { it.minutesToday }
        val totalPickups = items.sumOf { it.pickupsToday }
        val longestSession = items.maxOfOrNull { it.longestStreakMins } ?: 0
        val avgSession = if (totalPickups > 0) (totalMins / totalPickups).coerceAtLeast(1) else 0
        val hourlyMins = hourlyMs.map { (it / 60_000L).toInt() }

        return DoomDiagnostics(
            totalWastedMinsToday = totalMins,
            totalPickupsToday = totalPickups,
            longestSessionMins = longestSession,
            avgSessionMins = avgSession,
            hourlyMins = hourlyMins,
            perAppBreakdown = items,
            pagesLost = (totalMins * 0.7f).toInt(),
            pushupsLost = totalMins * 4,
            codingProblemsLost = (totalMins / 25).coerceAtLeast(if (totalMins >= 15) 1 else 0)
        )
    }

    fun getAppName(context: Context, pkg: String): String {
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }

    fun getInstalledApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val watched = getWatchedPackages(context)
        return pm.getInstalledApplications(0)
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM == 0) ||
                        watched.contains(it.packageName) ||
                        it.packageName.contains("youtube")
            }
            .filter { it.packageName != context.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .distinctBy { it.first }
            .sortedWith(compareByDescending<Pair<String, String>> { watched.contains(it.first) }.thenBy { it.second.lowercase() })
    }

    fun getLast14DaysSurvival(context: Context, todayTotalMins: Int): List<Pair<String, Boolean>> {
        val (_, _, limit) = getThresholds(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY_SURVIVAL_HISTORY, emptySet())?.toMutableSet() ?: mutableSetOf()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayFmt = SimpleDateFormat("dd", Locale.US)

        val todayKey = fmt.format(Date())
        if (todayTotalMins >= limit) {
            raw.add("$todayKey:FAILED")
            raw.remove("$todayKey:SURVIVED")
        } else {
            raw.add("$todayKey:SURVIVED")
        }
        prefs.edit().putStringSet(KEY_SURVIVAL_HISTORY, raw).apply()

        val result = mutableListOf<Pair<String, Boolean>>()
        for (i in 13 downTo 0) {
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
            val k = fmt.format(c.time)
            val dLabel = dayFmt.format(c.time)
            val failed = raw.contains("$k:FAILED")
            result.add(dLabel to !failed)
        }
        return result
    }
    private const val KEY_DAILY_HISTORY_JSON = "daily_history_365_json"
    private const val KEY_BACKFILLED_7D = "has_backfilled_7d_v1"

    fun formatDuration(mins: Int): String {
        val m = mins.coerceAtLeast(0)
        val hrs = m / 60
        val rem = m % 60
        return when {
            hrs == 0 -> "${rem}m"
            rem == 0 -> "${hrs}h"
            else -> "${hrs}h ${rem}m"
        }
    }

    fun formatHourShort(hr: Int?): String {
        if (hr == null || hr !in 0..23) return "None"
        val amPm = if (hr < 12) "AM" else "PM"
        val h12 = if (hr % 12 == 0) 12 else hr % 12
        return "$h12 $amPm"
    }

    fun findPeakHour(hourlyMins: List<Int>): Pair<Int?, Int> {
        val maxVal = hourlyMins.maxOrNull() ?: 0
        if (maxVal <= 0) return null to 0
        val idx = hourlyMins.indexOfFirst { it == maxVal }
        return (if (idx >= 0) idx else null) to maxVal
    }

    /**
     * Computes exact diagnostics for any past day offset (0 = today, 1 = yesterday, ... up to 6 days ago).
     */
    fun computeDiagnosticsForDaysAgo(context: Context, daysAgo: Int): DailyHistoryRecord {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val endOfDay = if (daysAgo == 0) {
            System.currentTimeMillis()
        } else {
            (startOfDay + 24 * 3600_000L - 1L)
        }
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startOfDay))
        val limit = getThresholds(context).third

        if (!hasUsageStatsPermission(context)) {
            return DailyHistoryRecord(dateKey, 0, 0, 0, "Clean slate", List(24) { 0 }, limit)
        }

        if (daysAgo == 0) {
            val today = computeTodayDiagnostics(context)
            val topApp = today.perAppBreakdown.firstOrNull()?.appName ?: "Clean slate"
            return DailyHistoryRecord(
                dateKey = dateKey,
                wastedMins = today.totalWastedMinsToday,
                pickups = today.totalPickupsToday,
                longestSessionMins = today.longestSessionMins,
                topCulpritApp = topApp,
                hourlyMins = today.hourlyMins,
                limitMins = limit
            )
        }

        val watched = getWatchedPackages(context)
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager
        val events = usm.queryEvents(startOfDay - 30 * 60_000L, endOfDay)
        val event = UsageEvents.Event()

        val appTotalMs = mutableMapOf<String, Long>()
        val appPickups = mutableMapOf<String, Int>()
        val appLongestMs = mutableMapOf<String, Long>()
        val lastClosedTimeMs = mutableMapOf<String, Long>()
        val hourlyMs = LongArray(24)
        val activeClassesForApp = mutableMapOf<String, MutableSet<String>>()
        val appSessionStartMs = mutableMapOf<String, Long>()

        fun commitInterval(pkg: String, rawStartMs: Long, rawEndMs: Long) {
            if (!watched.contains(pkg)) return
            val s = max(rawStartMs, startOfDay)
            val e = rawEndMs.coerceAtMost(endOfDay)
            val duration = (e - s).coerceAtLeast(0L)
            if (duration <= 0L || duration > 6 * 3600_000L) return

            appTotalMs[pkg] = (appTotalMs[pkg] ?: 0L) + duration
            if (duration > (appLongestMs[pkg] ?: 0L)) appLongestMs[pkg] = duration

            var cursor = s
            val c = Calendar.getInstance()
            while (cursor < e) {
                c.timeInMillis = cursor
                val hr = c.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
                c.set(Calendar.MINUTE, 59)
                c.set(Calendar.SECOND, 59)
                c.set(Calendar.MILLISECOND, 999)
                val endHr = (c.timeInMillis + 1L).coerceAtMost(e)
                hourlyMs[hr] += (endHr - cursor).coerceAtLeast(0L)
                cursor = endHr
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            val pkg = event.packageName
            val cls = event.className ?: "default"
            when (event.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    for ((openPkg, classes) in activeClassesForApp) {
                        if (classes.isNotEmpty()) {
                            commitInterval(openPkg, appSessionStartMs[openPkg] ?: ts, ts)
                            lastClosedTimeMs[openPkg] = ts
                            classes.clear()
                        }
                    }
                    appSessionStartMs.clear()
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg == null || pkg == "com.android.systemui") continue
                    for ((otherPkg, otherClasses) in activeClassesForApp) {
                        if (otherPkg != pkg && otherClasses.isNotEmpty()) {
                            commitInterval(otherPkg, appSessionStartMs[otherPkg] ?: ts, ts)
                            lastClosedTimeMs[otherPkg] = ts
                            otherClasses.clear()
                            appSessionStartMs.remove(otherPkg)
                        }
                    }
                    val set = activeClassesForApp.getOrPut(pkg) { mutableSetOf() }
                    val wasEmpty = set.isEmpty()
                    set.add(cls)
                    if (wasEmpty) {
                        appSessionStartMs[pkg] = ts
                        if (watched.contains(pkg) && ts >= startOfDay) {
                            val lastClose = lastClosedTimeMs[pkg] ?: 0L
                            if (ts - lastClose > 15_000L) {
                                appPickups[pkg] = (appPickups[pkg] ?: 0) + 1
                            }
                        }
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (pkg == null) continue
                    val set = activeClassesForApp[pkg]
                    if (set != null && set.isNotEmpty()) {
                        set.remove(cls)
                        if (set.isEmpty()) {
                            commitInterval(pkg, appSessionStartMs.remove(pkg) ?: ts, ts)
                            lastClosedTimeMs[pkg] = ts
                        }
                    }
                }
            }
        }
        for ((openPkg, classes) in activeClassesForApp) {
            if (classes.isNotEmpty()) {
                commitInterval(openPkg, appSessionStartMs[openPkg] ?: endOfDay, endOfDay)
            }
        }

        val totalMins = (appTotalMs.values.sum() / 60_000L).toInt()
        val totalPickups = appPickups.values.sum()
        val longestMins = ((appLongestMs.values.maxOrNull() ?: 0L) / 60_000L).toInt()
        val topPkg = appTotalMs.maxByOrNull { it.value }?.key
        val topAppName = if (topPkg != null && (appTotalMs[topPkg] ?: 0L) >= 60_000L) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(topPkg, 0)).toString() }
                .getOrDefault(topPkg.substringAfterLast('.'))
        } else "Clean slate"

        return DailyHistoryRecord(
            dateKey = dateKey,
            wastedMins = totalMins,
            pickups = totalPickups,
            longestSessionMins = longestMins,
            topCulpritApp = topAppName,
            hourlyMins = hourlyMs.map { (it / 60_000L).toInt() },
            limitMins = limit
        )
    }

    fun getAllLocalHistory(context: Context): Map<String, DailyHistoryRecord> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DAILY_HISTORY_JSON, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val map = mutableMapOf<String, DailyHistoryRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val hArr = o.optJSONArray("hourly")
                val hourly = List(24) { idx -> hArr?.optInt(idx, 0) ?: 0 }
                val rec = DailyHistoryRecord(
                    dateKey = o.getString("date"),
                    wastedMins = o.optInt("wasted", 0),
                    pickups = o.optInt("pickups", 0),
                    longestSessionMins = o.optInt("longest", 0),
                    topCulpritApp = o.optString("topApp", "Clean slate"),
                    hourlyMins = hourly,
                    limitMins = o.optInt("limit", 30)
                )
                map[rec.dateKey] = rec
            }
            map
        }.getOrDefault(emptyMap())
    }

    /**
     * Merges new records and automatically trims anything older than 365 days (12 months).
     */
    fun saveHistoryRecordsLocally(context: Context, records: List<DailyHistoryRecord>) {
        if (records.isEmpty()) return
        val merged = getAllLocalHistory(context).toMutableMap()
        records.forEach { rec -> merged[rec.dateKey] = rec }

        val cutoffCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -365) }
        val cutoffKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cutoffCal.time)
        val pruned = merged.values.filter { it.dateKey >= cutoffKey }.sortedBy { it.dateKey }

        val arr = JSONArray()
        pruned.forEach { r ->
            arr.put(JSONObject().apply {
                put("date", r.dateKey)
                put("wasted", r.wastedMins)
                put("pickups", r.pickups)
                put("longest", r.longestSessionMins)
                put("topApp", r.topCulpritApp)
                put("limit", r.limitMins)
                put("hourly", JSONArray().apply { r.hourlyMins.forEach { put(it) } })
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DAILY_HISTORY_JSON, arr.toString())
            .apply()
    }

    /**
     * Auto-backfills the last 7 days from Android UsageStats on first run so Week view is immediately populated.
     */
    fun recordTodayAndBackfill7Days(context: Context): List<DailyHistoryRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val backfilled = prefs.getBoolean(KEY_BACKFILLED_7D, false)
        val toSave = mutableListOf<DailyHistoryRecord>()

        val todayRec = computeDiagnosticsForDaysAgo(context, 0)
        toSave.add(todayRec)

        if (!backfilled && hasUsageStatsPermission(context)) {
            for (d in 1..6) {
                toSave.add(computeDiagnosticsForDaysAgo(context, d))
            }
            prefs.edit().putBoolean(KEY_BACKFILLED_7D, true).apply()
        }
        saveHistoryRecordsLocally(context, toSave)
        return toSave
    }

    /**
     * Aggregates Weekly (days = 7) or Monthly (days = 30) analytics from the 12-month history store.
     */
    fun computePeriodAnalytics(context: Context, days: Int): PeriodAnalytics {
        val history = getAllLocalHistory(context)
        val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayShortFmt = SimpleDateFormat("EEE", Locale.US)
        val dayNumFmt = SimpleDateFormat("d", Locale.US)
        val fullFmt = SimpleDateFormat("EEE, d MMM", Locale.US)
        val defaultLimit = getThresholds(context).third

        val currentBars = mutableListOf<DailyBarPoint>()
        val currentRecords = mutableListOf<DailyHistoryRecord>()
        val hourlySum = IntArray(24)

        for (offset in (days - 1) downTo 0) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val key = keyFmt.format(cal.time)
            val rec = history[key]
            val mins = rec?.wastedMins ?: 0
            val topApp = rec?.topCulpritApp ?: "No data"
            if (rec != null) {
                currentRecords.add(rec)
                rec.hourlyMins.forEachIndexed { hr, v -> if (hr in 0..23) hourlySum[hr] += v }
            }
            currentBars.add(
                DailyBarPoint(
                    dateKey = key,
                    shortLabel = if (days <= 7) dayShortFmt.format(cal.time).take(3) else dayNumFmt.format(cal.time),
                    fullLabel = fullFmt.format(cal.time),
                    wastedMins = mins,
                    topCulpritApp = topApp
                )
            )
        }

        // Previous period (e.g. days 7..13 ago for Week, or 30..59 ago for Month)
        var prevTotal = 0
        var prevTrackedCount = 0
        for (offset in (days * 2 - 1) downTo days) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val rec = history[keyFmt.format(cal.time)]
            if (rec != null) {
                prevTotal += rec.wastedMins
                prevTrackedCount++
            }
        }

        val totalMins = currentBars.sumOf { it.wastedMins }
        val activeDayCount = currentRecords.size.coerceAtLeast(1)
        val dailyAvg = totalMins / activeDayCount

        val hasPrev = prevTrackedCount > 0 && prevTotal > 0
        val deltaMins = if (hasPrev) totalMins - prevTotal else 0
        val deltaPct = if (hasPrev) ((deltaMins.toFloat() / prevTotal.toFloat()) * 100f).toInt() else 0

        val (peakHr, peakHrSum) = findPeakHour(hourlySum.toList())

        val cleanDays = currentRecords.count { it.wastedMins < it.limitMins.coerceAtLeast(defaultLimit) }
        val survivalPct = if (currentRecords.isNotEmpty()) ((cleanDays * 100f) / currentRecords.size).toInt() else 100

        val bestBar = currentBars.filter { history.containsKey(it.dateKey) }.minByOrNull { it.wastedMins }
        val worstBar = currentBars.filter { history.containsKey(it.dateKey) }.maxByOrNull { it.wastedMins }

        return PeriodAnalytics(
            totalMins = totalMins,
            dailyAvgMins = dailyAvg,
            hasPrevData = hasPrev,
            deltaPercent = deltaPct,
            deltaMins = deltaMins,
            peakHour = peakHr,
            peakHourTotalMins = peakHrSum,
            cleanDaysCount = cleanDays,
            totalTrackedDays = currentRecords.size,
            survivalRatePercent = survivalPct,
            bestDayLabel = bestBar?.fullLabel ?: "—",
            bestDayMins = bestBar?.wastedMins ?: 0,
            worstDayLabel = worstBar?.fullLabel ?: "—",
            worstDayMins = worstBar?.wastedMins ?: 0,
            dailyBars = currentBars
        )
    }
}