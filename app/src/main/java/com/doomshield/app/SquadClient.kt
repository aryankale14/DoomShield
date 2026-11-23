package com.doomshield.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SquadInfo(
    val squadId: String,
    val squadName: String,
    val inviteCode: String,
    val myUserId: String,
    val myDisplayName: String
)

data class SquadMemberStat(
    val userId: String,
    val displayName: String,
    val wastedMins: Int,
    val pickups: Int,
    val longestSessionMins: Int,
    val topCulpritApp: String,
    val petStage: PetStage
)

data class ShameFeedItem(
    val id: String,
    val displayName: String,
    val eventType: String,
    val message: String,
    val timeLabel: String
)

data class SquadBoardData(
    val members: List<SquadMemberStat> = emptyList(),
    val feed: List<ShameFeedItem> = emptyList(),
    val isOffline: Boolean = false,
    val error: String? = null
)

object SquadClient {
    // PASTE YOUR SUPABASE CREDENTIALS HERE
    private const val SUPABASE_URL = "YOUR_SUPABASE_URL"
    private const val SUPABASE_ANON_KEY = "YOUR_SUPABASE_ANON_KEY"

    private const val PREFS_NAME = "doom_squad_prefs"
    private const val KEY_USER_ID = "squad_user_id"
    private const val KEY_DISPLAY_NAME = "squad_display_name"
    private const val KEY_SQUAD_ID = "squad_id"
    private const val KEY_SQUAD_NAME = "squad_name"
    private const val KEY_INVITE_CODE = "squad_invite_code"
    private const val KEY_SQUADS_JSON = "squads_list_json"
    private const val KEY_ACTIVE_SQUAD_ID = "active_squad_id"
    private const val KEY_LAST_FAINT_LOG = "last_faint_log_date"
    private const val KEY_CACHE_PREFIX = "board_cache_"

    fun isConfigured(): Boolean {
        return SUPABASE_URL.startsWith("https://") && !SUPABASE_ANON_KEY.contains("YOUR_SUPABASE")
    }

    fun getOrCreateUserId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_USER_ID, null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_USER_ID, created).apply()
        return created
    }

    fun getSavedDisplayName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISPLAY_NAME, "") ?: ""
    }

    /**
     * Returns all squads the user belongs to (automatically migrates old single-squad storage).
     */
    fun getAllSquads(context: Context): List<SquadInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = getOrCreateUserId(context)
        val raw = prefs.getString(KEY_SQUADS_JSON, null)

        if (raw != null) {
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SquadInfo(
                        squadId = o.getString("id"),
                        squadName = o.getString("name"),
                        inviteCode = o.getString("code"),
                        myUserId = userId,
                        myDisplayName = o.optString("displayName", getSavedDisplayName(context).ifBlank { "Player" })
                    )
                }
            }.getOrDefault(emptyList())
        }

        // Migrate legacy single squad (e.g. "Family") seamlessly
        val legacyId = prefs.getString(KEY_SQUAD_ID, null) ?: return emptyList()
        val legacy = SquadInfo(
            squadId = legacyId,
            squadName = prefs.getString(KEY_SQUAD_NAME, "My Squad") ?: "My Squad",
            inviteCode = prefs.getString(KEY_INVITE_CODE, "") ?: "",
            myUserId = userId,
            myDisplayName = prefs.getString(KEY_DISPLAY_NAME, "Player") ?: "Player"
        )
        saveSquadsList(context, listOf(legacy), legacy.squadId)
        return listOf(legacy)
    }

    fun getActiveSquad(context: Context): SquadInfo? {
        val all = getAllSquads(context)
        if (all.isEmpty()) return null
        val activeId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_SQUAD_ID, null)
        return all.find { it.squadId == activeId } ?: all.first()
    }

    fun setActiveSquad(context: Context, squadId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_SQUAD_ID, squadId)
            .apply()
    }

    private fun saveSquadsList(context: Context, squads: List<SquadInfo>, activeId: String?) {
        val arr = JSONArray()
        squads.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.squadId)
                    put("name", s.squadName)
                    put("code", s.inviteCode)
                    put("displayName", s.myDisplayName)
                }
            )
        }
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SQUADS_JSON, arr.toString())
        if (activeId != null) editor.putString(KEY_ACTIVE_SQUAD_ID, activeId)
        else editor.remove(KEY_ACTIVE_SQUAD_ID)
        editor.apply()
    }

    private fun addOrUpdateSquadLocally(context: Context, squad: SquadInfo) {
        val current = getAllSquads(context).filterNot { it.squadId == squad.squadId } + squad
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DISPLAY_NAME, squad.myDisplayName)
            .apply()
        saveSquadsList(context, current, squad.squadId)
    }

    fun leaveSquad(context: Context, squadId: String) {
        val remaining = getAllSquads(context).filterNot { it.squadId == squadId }
        saveSquadsList(context, remaining, remaining.firstOrNull()?.squadId)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CACHE_PREFIX + squadId)
            .apply()
    }

    private fun todayDateString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val suffix = (1..4).map { chars.random() }.joinToString("")
        return "DS-$suffix"
    }

    private fun isNetworkException(t: Throwable): Boolean {
        return t is UnknownHostException || t is ConnectException || t is SocketTimeoutException
    }

    private fun friendlyError(t: Throwable): String {
        return if (isNetworkException(t)) {
            "No internet connection. Connect to Wi-Fi or mobile data and try again."
        } else {
            t.message ?: "Something went wrong. Please try again."
        }
    }

    private fun request(
        endpoint: String,
        method: String = "GET",
        body: String? = null,
        preferHeader: String? = null
    ): Pair<Int, String> {
        val cleanBase = SUPABASE_URL.trimEnd('/')
        val conn = (URL("$cleanBase/rest/v1/$endpoint").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("apikey", SUPABASE_ANON_KEY)
            setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            setRequestProperty("Content-Type", "application/json")
            if (preferHeader != null) setRequestProperty("Prefer", preferHeader)
            doInput = true
        }
        if (body != null) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        conn.disconnect()
        return code to text
    }

    suspend fun createSquad(context: Context, squadName: String, displayName: String): Result<SquadInfo> =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext Result.failure(Exception("Paste your Supabase URL & Key in SquadClient.kt first."))
            try {
                val inviteCode = generateInviteCode()
                val payload = JSONObject().apply {
                    put("name", squadName.trim())
                    put("invite_code", inviteCode)
                }.toString()

                val (code, resp) = request("squads", "POST", payload, "return=representation")
                if (code !in 200..299) return@withContext Result.failure(Exception("Could not create squad right now."))
                val created = JSONArray(resp).getJSONObject(0)
                val squadId = created.getString("id")
                val userId = getOrCreateUserId(context)

                val memberBody = JSONObject().apply {
                    put("squad_id", squadId)
                    put("user_id", userId)
                    put("display_name", displayName.trim())
                    put("daily_limit_mins", DoomStore.getThresholds(context).third)
                }.toString()
                request("squad_members?on_conflict=squad_id,user_id", "POST", memberBody, "resolution=merge-duplicates")

                val info = SquadInfo(squadId, squadName.trim(), inviteCode, userId, displayName.trim())
                addOrUpdateSquadLocally(context, info)
                syncMyStats(context)
                Result.success(info)
            } catch (e: Throwable) {
                Result.failure(Exception(friendlyError(e)))
            }
        }

    suspend fun joinSquad(context: Context, inviteCodeRaw: String, displayName: String): Result<SquadInfo> =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext Result.failure(Exception("Paste your Supabase URL & Key in SquadClient.kt first."))
            try {
                val cleanCode = inviteCodeRaw.trim().uppercase()
                val encoded = URLEncoder.encode(cleanCode, "UTF-8")
                val (code, resp) = request("squads?invite_code=eq.$encoded&select=id,name,invite_code")
                if (code !in 200..299) return@withContext Result.failure(Exception("Could not reach server. Try again."))
                val arr = JSONArray(resp)
                if (arr.length() == 0) return@withContext Result.failure(Exception("No squad found with code $cleanCode"))

                val row = arr.getJSONObject(0)
                val squadId = row.getString("id")
                val squadName = row.getString("name")
                val userId = getOrCreateUserId(context)

                val memberBody = JSONObject().apply {
                    put("squad_id", squadId)
                    put("user_id", userId)
                    put("display_name", displayName.trim())
                    put("daily_limit_mins", DoomStore.getThresholds(context).third)
                }.toString()
                request("squad_members?on_conflict=squad_id,user_id", "POST", memberBody, "resolution=merge-duplicates")

                val info = SquadInfo(squadId, squadName, cleanCode, userId, displayName.trim())
                addOrUpdateSquadLocally(context, info)
                syncMyStats(context)
                postShameEvent(context, squadId, "JOINED", "${displayName.trim()} joined the squad. No hiding now.")
                Result.success(info)
            } catch (e: Throwable) {
                Result.failure(Exception(friendlyError(e)))
            }
        }

    /**
     * Pushes today's local diagnostics to EVERY squad the user belongs to.
     */
    suspend fun syncMyStats(context: Context) = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext
        val squads = getAllSquads(context)
        if (squads.isEmpty()) return@withContext

        runCatching {
            val diag = DoomStore.computeTodayDiagnostics(context)
            val (warn, dim, limit) = DoomStore.getThresholds(context)
            val stage = DoomStore.getStageForMinutes(diag.totalWastedMinsToday, warn, dim, limit)
            val topApp = diag.perAppBreakdown.firstOrNull()?.appName ?: "Clean slate"
            val today = todayDateString()

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val shouldPostFaint = (stage == PetStage.FAINTED && prefs.getString(KEY_LAST_FAINT_LOG, null) != today)

            squads.forEach { squad ->
                val payload = JSONObject().apply {
                    put("squad_id", squad.squadId)
                    put("user_id", squad.myUserId)
                    put("display_name", squad.myDisplayName)
                    put("stat_date", today)
                    put("wasted_mins", diag.totalWastedMinsToday)
                    put("pickups", diag.totalPickupsToday)
                    put("longest_session_mins", diag.longestSessionMins)
                    put("top_culprit_app", topApp)
                    put("pet_stage", stage.name)
                }.toString()

                request(
                    endpoint = "daily_stats?on_conflict=squad_id,user_id,stat_date",
                    method = "POST",
                    body = payload,
                    preferHeader = "resolution=merge-duplicates"
                )

                if (shouldPostFaint) {
                    postShameEvent(
                        context,
                        squad.squadId,
                        "PET_FAINTED",
                        "${squad.myDisplayName}'s DoomPet fainted after ${diag.totalWastedMinsToday}m (top culprit: $topApp)."
                    )
                }
            }

            if (shouldPostFaint) {
                prefs.edit().putString(KEY_LAST_FAINT_LOG, today).apply()
            }
        }
    }

    suspend fun postShameEvent(context: Context, squadId: String, eventType: String, message: String) =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext
            val squad = getAllSquads(context).find { it.squadId == squadId } ?: return@withContext
            runCatching {
                val payload = JSONObject().apply {
                    put("squad_id", squad.squadId)
                    put("user_id", squad.myUserId)
                    put("display_name", squad.myDisplayName)
                    put("event_type", eventType)
                    put("message", message)
                }.toString()
                request("shame_events", "POST", payload)
            }
        }

    private fun saveBoardCache(context: Context, squadId: String, members: List<SquadMemberStat>, feed: List<ShameFeedItem>) {
        val obj = JSONObject().apply {
            put("members", JSONArray().apply {
                members.forEach { m ->
                    put(JSONObject().apply {
                        put("user_id", m.userId)
                        put("display_name", m.displayName)
                        put("wasted_mins", m.wastedMins)
                        put("pickups", m.pickups)
                        put("longest_session_mins", m.longestSessionMins)
                        put("top_culprit_app", m.topCulpritApp)
                        put("pet_stage", m.petStage.name)
                    })
                }
            })
            put("feed", JSONArray().apply {
                feed.forEach { f ->
                    put(JSONObject().apply {
                        put("id", f.id)
                        put("display_name", f.displayName)
                        put("event_type", f.eventType)
                        put("message", f.message)
                        put("time_label", f.timeLabel)
                    })
                }
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHE_PREFIX + squadId, obj.toString())
            .apply()
    }

    private fun loadBoardCache(context: Context, squadId: String): SquadBoardData {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHE_PREFIX + squadId, null) ?: return SquadBoardData(isOffline = true)
        return runCatching {
            val obj = JSONObject(raw)
            val mArr = obj.getJSONArray("members")
            val members = (0 until mArr.length()).map { i ->
                val o = mArr.getJSONObject(i)
                SquadMemberStat(
                    userId = o.getString("user_id"),
                    displayName = o.getString("display_name"),
                    wastedMins = o.optInt("wasted_mins", 0),
                    pickups = o.optInt("pickups", 0),
                    longestSessionMins = o.optInt("longest_session_mins", 0),
                    topCulpritApp = o.optString("top_culprit_app", "Clean slate"),
                    petStage = runCatching { PetStage.valueOf(o.optString("pet_stage", "CHILL")) }.getOrDefault(PetStage.CHILL)
                )
            }
            val fArr = obj.getJSONArray("feed")
            val feed = (0 until fArr.length()).map { i ->
                val o = fArr.getJSONObject(i)
                ShameFeedItem(
                    id = o.optString("id", "$i"),
                    displayName = o.optString("display_name", "Member"),
                    eventType = o.optString("event_type", "ROAST_PING"),
                    message = o.optString("message", ""),
                    timeLabel = o.optString("time_label", "")
                )
            }
            SquadBoardData(members = members, feed = feed, isOffline = true)
        }.getOrDefault(SquadBoardData(isOffline = true))
    }

    suspend fun fetchSquadBoard(context: Context, squadId: String): SquadBoardData = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext SquadBoardData(error = "Add your Supabase URL & Key in SquadClient.kt")

        try {
            val today = todayDateString()
            val (sCode, sResp) = request(
                "daily_stats?squad_id=eq.$squadId&stat_date=eq.$today&order=wasted_mins.asc"
            )
            val members = mutableListOf<SquadMemberStat>()
            if (sCode in 200..299) {
                val arr = JSONArray(sResp)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val stage = runCatching { PetStage.valueOf(o.optString("pet_stage", "CHILL")) }
                        .getOrDefault(PetStage.CHILL)
                    members.add(
                        SquadMemberStat(
                            userId = o.getString("user_id"),
                            displayName = o.getString("display_name"),
                            wastedMins = o.optInt("wasted_mins", 0),
                            pickups = o.optInt("pickups", 0),
                            longestSessionMins = o.optInt("longest_session_mins", 0),
                            topCulpritApp = o.optString("top_culprit_app", "Clean slate"),
                            petStage = stage
                        )
                    )
                }
            }

            val (fCode, fResp) = request(
                "shame_events?squad_id=eq.$squadId&order=created_at.desc&limit=12"
            )
            val feed = mutableListOf<ShameFeedItem>()
            if (fCode in 200..299) {
                val arr = JSONArray(fResp)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val rawTime = o.optString("created_at", "")
                    val shortTime = if (rawTime.length >= 16) rawTime.substring(11, 16) + " UTC" else "Just now"
                    feed.add(
                        ShameFeedItem(
                            id = o.optString("id", "$i"),
                            displayName = o.optString("display_name", "Member"),
                            eventType = o.optString("event_type", "ROAST_PING"),
                            message = o.optString("message", ""),
                            timeLabel = shortTime
                        )
                    )
                }
            }
            saveBoardCache(context, squadId, members, feed)
            SquadBoardData(members = members, feed = feed, isOffline = false)
        } catch (e: Throwable) {
            if (isNetworkException(e)) {
                loadBoardCache(context, squadId)
            } else {
                SquadBoardData(error = friendlyError(e))
            }
        }
    }
    /**
     * Upserts daily history records to Supabase (user_daily_history) and prunes rows older than 12 months.
     */
    suspend fun syncUserHistoryToSupabase(context: Context, records: List<DailyHistoryRecord>) =
        withContext(Dispatchers.IO) {
            if (!isConfigured() || records.isEmpty()) return@withContext
            val userId = getOrCreateUserId(context)
            runCatching {
                val arr = JSONArray()
                records.forEach { rec ->
                    val (peakHr, _) = DoomStore.findPeakHour(rec.hourlyMins)
                    arr.put(JSONObject().apply {
                        put("user_id", userId)
                        put("stat_date", rec.dateKey)
                        put("wasted_mins", rec.wastedMins)
                        put("pickups", rec.pickups)
                        put("longest_session_mins", rec.longestSessionMins)
                        put("top_culprit_app", rec.topCulpritApp)
                        if (peakHr != null) put("peak_hour", peakHr) else put("peak_hour", JSONObject.NULL)
                        put("hourly_mins", JSONArray().apply { rec.hourlyMins.forEach { put(it) } })
                        put("limit_mins", rec.limitMins)
                    })
                }
                request(
                    endpoint = "user_daily_history?on_conflict=user_id,stat_date",
                    method = "POST",
                    body = arr.toString(),
                    preferHeader = "resolution=merge-duplicates"
                )

                // Prune records older than 365 days in Supabase
                val cutoffCal = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, -365)
                }
                val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cutoffCal.time)
                request(
                    endpoint = "user_daily_history?user_id=eq.$userId&stat_date=lt.$cutoff",
                    method = "DELETE"
                )
            }
        }

    /**
     * Pulls up to 12 months of history from Supabase and merges it into local storage.
     */
    suspend fun fetch12MonthHistoryFromSupabase(context: Context) = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext
        val userId = getOrCreateUserId(context)
        runCatching {
            val cutoffCal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, -365)
            }
            val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cutoffCal.time)
            val (code, resp) = request(
                "user_daily_history?user_id=eq.$userId&stat_date=gte.$cutoff&order=stat_date.asc"
            )
            if (code in 200..299) {
                val arr = JSONArray(resp)
                val pulled = mutableListOf<DailyHistoryRecord>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val hArr = o.optJSONArray("hourly_mins")
                    val hourly = List(24) { idx -> hArr?.optInt(idx, 0) ?: 0 }
                    pulled.add(
                        DailyHistoryRecord(
                            dateKey = o.getString("stat_date"),
                            wastedMins = o.optInt("wasted_mins", 0),
                            pickups = o.optInt("pickups", 0),
                            longestSessionMins = o.optInt("longest_session_mins", 0),
                            topCulpritApp = o.optString("top_culprit_app", "Clean slate"),
                            hourlyMins = hourly,
                            limitMins = o.optInt("limit_mins", 30)
                        )
                    )
                }
                if (pulled.isNotEmpty()) {
                    DoomStore.saveHistoryRecordsLocally(context, pulled)
                }
            }
        }
    }
}