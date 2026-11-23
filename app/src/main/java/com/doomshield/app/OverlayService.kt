package com.doomshield.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Ultra-fast native Canvas view for full-screen visual punishments:
 * Draws Heavy Dark Dimming, Dead-Gray Color Drain Wash, Shrinking Tunnel Vignette, and CRT Scanlines
 * at 120fps with zero Compose overhead.
 */
class PunishmentOverlayView(context: Context) : View(context) {
    var intensity: Float = 0f
    var activeModes: Set<VisualPunishment> = emptySet()

    private val darkPaint = Paint()
    private val grayWashPaint = Paint()
    private val scanlinePaint = Paint().apply {
        color = AndroidColor.argb(95, 0, 0, 0)
        strokeWidth = 3f
    }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        if (intensity <= 0.01f || width == 0 || height == 0) return

        // 1. Dead-Gray Color Drain Wash (Flattens contrast & kills saturated Reel colors)
        if (activeModes.contains(VisualPunishment.GRAY_WASH)) {
            val grayAlpha = (intensity * 205).toInt().coerceIn(0, 225)
            grayWashPaint.color = AndroidColor.argb(grayAlpha, 110, 114, 120)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), grayWashPaint)
        }

        // 2. Heavy Pitch-Black Dimmer
        if (activeModes.contains(VisualPunishment.HEAVY_DIM)) {
            val darkAlpha = (intensity * 215).toInt().coerceIn(0, 235)
            darkPaint.color = AndroidColor.argb(darkAlpha, 6, 8, 12)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), darkPaint)
        }

        // 3. Claustrophobic Tunnel Vision Vignette
        if (activeModes.contains(VisualPunishment.TUNNEL_VIGNETTE)) {
            val cx = width / 2f
            val cy = height / 2f
            val outerRadius = maxOf(width, height) * (0.72f - intensity * 0.25f)
            val edgeAlpha = (intensity * 245).toInt().coerceIn(0, 250)
            vignettePaint.shader = RadialGradient(
                cx,
                cy,
                outerRadius.coerceAtLeast(100f),
                intArrayOf(
                    AndroidColor.TRANSPARENT,
                    AndroidColor.argb((edgeAlpha * 0.6f).toInt(), 5, 5, 8),
                    AndroidColor.argb(edgeAlpha, 0, 0, 0)
                ),
                floatArrayOf(0.22f, 0.58f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
        }

        // 4. Annoying CRT Scanlines
        if (activeModes.contains(VisualPunishment.SCANLINE_GLITCH)) {
            val lineAlpha = (intensity * 150).toInt().coerceIn(40, 180)
            scanlinePaint.color = AndroidColor.argb(lineAlpha, 15, 18, 22)
            var y = 0f
            val step = 10f
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, scanlinePaint)
                y += step
            }
        }
    }
}

enum class DockEdge { NONE, LEFT, RIGHT }

class OverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore
        get() = store

    private lateinit var windowManager: WindowManager
    private var pillComposeView: ComposeView? = null
    private var punishmentView: PunishmentOverlayView? = null
    private lateinit var pillParams: WindowManager.LayoutParams
    private lateinit var punishmentParams: WindowManager.LayoutParams

    private val isTargetAppOpen = mutableStateOf(false)
    private val currentAppName = mutableStateOf("")
    private val activeSessionSeconds = mutableIntStateOf(0)
    private val currentAppBaseMinsToday = mutableIntStateOf(0)
    private val totalWatchedMinsToday = mutableIntStateOf(0)
    private val currentPetStage = mutableStateOf(PetStage.CHILL)
    private val currentRoastText = mutableStateOf("")
    private val punishmentIntensity = mutableFloatStateOf(0f)
    private val showRealityCheckModal = mutableStateOf(false)
    private val isDemoPreview = mutableStateOf(false)

    // Side-Edge Docking + Auto-Pop state
    private val dockedEdge = mutableStateOf(DockEdge.NONE)
    private val dockedAtSecond = mutableIntStateOf(0)
    private var savedFloatingX = 120

    companion object {
        const val ACTION_START = "com.doomshield.app.ACTION_START"
        const val ACTION_STOP = "com.doomshield.app.ACTION_STOP"
        const val ACTION_DEMO_CYCLE = "com.doomshield.app.ACTION_DEMO_CYCLE"
        private const val CHANNEL_ID = "doomshield_live_channel"
        private const val NOTIF_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification("Monitoring vice apps in real time"))
        DoomStore.isServiceRunning.value = true

        if (DoomStore.hasOverlayPermission(this)) {
            setupOverlays()
        }
        startTrackingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DEMO_CYCLE -> {
                triggerDemoPreview()
            }
        }
        return START_STICKY
    }

    private fun triggerDemoPreview() {
        isDemoPreview.value = true
        isTargetAppOpen.value = true
        dockedEdge.value = DockEdge.NONE
        currentAppName.value = "Instagram (Demo)"
        val (warn, dim, limit) = DoomStore.getThresholds(this)
        val tone = DoomStore.getRoastTone(this)
        val maxStrength = DoomStore.getPunishmentStrength(this)

        val nextStage = when (currentPetStage.value) {
            PetStage.CHILL -> PetStage.SWEATING
            PetStage.SWEATING -> PetStage.SICK
            PetStage.SICK -> PetStage.FAINTED
            PetStage.FAINTED -> PetStage.CHILL
        }
        currentPetStage.value = nextStage

        val simulatedMins = when (nextStage) {
            PetStage.CHILL -> 3
            PetStage.SWEATING -> warn + 2
            PetStage.SICK -> dim + 3
            PetStage.FAINTED -> limit + 5
        }
        activeSessionSeconds.intValue = simulatedMins * 60
        currentAppBaseMinsToday.intValue = 36
        currentRoastText.value = DoomStore.getRoastMessage("Instagram", simulatedMins, nextStage, tone)
        punishmentIntensity.floatValue = when (nextStage) {
            PetStage.SICK -> (maxStrength * 0.80f).coerceAtLeast(0.55f)
            PetStage.FAINTED -> maxStrength
            else -> 0f
        }
        showRealityCheckModal.value = (nextStage == PetStage.FAINTED)
        syncWindowVisibilityAndPunishment()
    }

    private fun dockToSide(edge: DockEdge) {
        val screenWidth = resources.displayMetrics.widthPixels
        if (edge == DockEdge.NONE) {
            dockedEdge.value = DockEdge.NONE
            pillParams.x = savedFloatingX.coerceIn(20, (screenWidth - 260).coerceAtLeast(80))
        } else {
            if (dockedEdge.value == DockEdge.NONE) {
                savedFloatingX = pillParams.x
            }
            dockedEdge.value = edge
            dockedAtSecond.intValue = activeSessionSeconds.intValue
            pillParams.x = if (edge == DockEdge.LEFT) 0 else (screenWidth - 48)
        }
        runCatching { windowManager.updateViewLayout(pillComposeView, pillParams) }
    }

    /**
     * Synchronizes both overlay windows and updates the native PunishmentOverlayView + hardware dimAmount.
     */
    private fun syncWindowVisibilityAndPunishment() {
        val showPill = isTargetAppOpen.value
        val intensity = punishmentIntensity.floatValue
        val showPunishment = isTargetAppOpen.value && intensity > 0.02f

        pillComposeView?.visibility = if (showPill) View.VISIBLE else View.GONE

        punishmentView?.let { view ->
            if (showPunishment) {
                val modes = DoomStore.getSelectedPunishments(this)
                view.intensity = intensity
                view.activeModes = modes
                if (view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                }
                // Hardware-level screen dimming via WindowManager.LayoutParams.dimAmount
                val targetHardwareDim = if (modes.contains(VisualPunishment.HEAVY_DIM)) {
                    (intensity * 0.75f).coerceIn(0.15f, 0.80f)
                } else 0f
                if (kotlin.math.abs(punishmentParams.dimAmount - targetHardwareDim) > 0.03f) {
                    punishmentParams.dimAmount = targetHardwareDim
                    runCatching { windowManager.updateViewLayout(view, punishmentParams) }
                }
                view.invalidate()
            } else {
                if (view.visibility != View.GONE) {
                    view.intensity = 0f
                    punishmentParams.dimAmount = 0f
                    view.visibility = View.GONE
                    runCatching { windowManager.updateViewLayout(view, punishmentParams) }
                }
            }
        }
    }

    private fun setupOverlays() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // 1. Native Full-Screen Visual Punishment Window (Hardware Dim + Gray Wash + Vignette + Scanlines)
        punishmentParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0f
        }

        punishmentView = PunishmentOverlayView(this).apply {
            visibility = View.GONE
        }

        // 2. Floating Burn Pill + Edge Dock Window
        savedFloatingX = (screenWidth * 0.12f).roundToInt()
        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedFloatingX
            y = 120
        }

        pillComposeView = ComposeView(this).apply {
            visibility = View.GONE
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            attachLifecycleOwners(this)
            setContent {
                val liveAppTotalMins = currentAppBaseMinsToday.intValue + (activeSessionSeconds.intValue / 60)

                OverlayPillContent(
                    visible = isTargetAppOpen.value,
                    appName = currentAppName.value,
                    sessionSeconds = activeSessionSeconds.intValue,
                    appTotalMinsToday = liveAppTotalMins,
                    allAppsTotalMinsToday = maxOf(totalWatchedMinsToday.intValue, liveAppTotalMins),
                    stage = currentPetStage.value,
                    roastText = currentRoastText.value,
                    showModal = showRealityCheckModal.value,
                    dockEdge = dockedEdge.value,
                    onUndock = { dockToSide(DockEdge.NONE) },
                    onDockManual = {
                        val center = pillParams.x + 120
                        dockToSide(if (center < screenWidth / 2) DockEdge.LEFT else DockEdge.RIGHT)
                    },
                    onDrag = { dx, dy ->
                        if (dockedEdge.value != DockEdge.NONE) {
                            dockedEdge.value = DockEdge.NONE
                        }
                        val maxX = (screenWidth - 180).coerceAtLeast(100)
                        val maxY = (screenHeight - 220).coerceAtLeast(200)
                        pillParams.x = (pillParams.x + dx.roundToInt()).coerceIn(0, maxX)
                        pillParams.y = (pillParams.y + dy.roundToInt()).coerceIn(64, maxY)
                        runCatching { windowManager.updateViewLayout(pillComposeView, pillParams) }
                    },
                    onDragEnd = {
                        // Snap to Left or Right Edge if pushed near the side!
                        val snapMargin = (screenWidth * 0.08f).roundToInt().coerceAtLeast(36)
                        val rightThreshold = screenWidth - 220 - snapMargin
                        if (pillParams.x <= snapMargin) {
                            dockToSide(DockEdge.LEFT)
                        } else if (pillParams.x >= rightThreshold) {
                            dockToSide(DockEdge.RIGHT)
                        } else {
                            savedFloatingX = pillParams.x
                        }
                    },
                    onDismissModal = {
                        showRealityCheckModal.value = false
                    },
                    onExitViceApp = {
                        showRealityCheckModal.value = false
                        isDemoPreview.value = false
                        isTargetAppOpen.value = false
                        punishmentIntensity.floatValue = 0f
                        syncWindowVisibilityAndPunishment()

                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                    }
                )
            }
        }

        runCatching {
            windowManager.addView(punishmentView, punishmentParams)
            windowManager.addView(pillComposeView, pillParams)
        }
    }

    private fun attachLifecycleOwners(view: ComposeView) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    private fun startTrackingLoop() {
        lifecycleScope.launch {
            var lastKnownPkg: String? = null
            var activeVicePkg: String? = null
            var lastModalShownAtMins = -1
            var tickCounter = 0

            while (isActive) {
                val fgPkg = DoomStore.getCurrentForegroundApp(this@OverlayService, lastKnownPkg)
                lastKnownPkg = fgPkg

                val watched = DoomStore.getWatchedPackages(this@OverlayService)
                val (warn, dim, limit) = DoomStore.getThresholds(this@OverlayService)
                val tone = DoomStore.getRoastTone(this@OverlayService)
                val popupIntervalSeconds = DoomStore.getEdgePopupIntervalMins(this@OverlayService) * 60
                val maxStrength = DoomStore.getPunishmentStrength(this@OverlayService)

                if (tickCounter % 15 == 0 || (fgPkg != null && fgPkg != activeVicePkg && watched.contains(fgPkg))) {
                    val diag = withContext(Dispatchers.IO) {
                        DoomStore.computeTodayDiagnostics(this@OverlayService)
                    }
                    totalWatchedMinsToday.intValue = diag.totalWastedMinsToday
                    if (fgPkg != null) {
                        val appItem = diag.perAppBreakdown.find { it.packageName == fgPkg }
                        currentAppBaseMinsToday.intValue = appItem?.minutesToday ?: 0
                    }
                }
                tickCounter++

                if (fgPkg != null && watched.contains(fgPkg)) {
                    isDemoPreview.value = false
                    isTargetAppOpen.value = true

                    if (activeVicePkg != fgPkg) {
                        activeVicePkg = fgPkg
                        activeSessionSeconds.intValue = 0
                        lastModalShownAtMins = -1
                        dockedEdge.value = DockEdge.NONE
                        currentAppName.value = DoomStore.getAppName(this@OverlayService, fgPkg)
                    } else {
                        activeSessionSeconds.intValue += 1
                    }

                    // If docked to the side edge, automatically pop back out every X minutes!
                    if (dockedEdge.value != DockEdge.NONE) {
                        val elapsedWhileDocked = activeSessionSeconds.intValue - dockedAtSecond.intValue
                        if (elapsedWhileDocked >= popupIntervalSeconds) {
                            dockToSide(DockEdge.NONE)
                        }
                    }

                    val sessionMins = activeSessionSeconds.intValue / 60
                    val appTotalMins = currentAppBaseMinsToday.intValue + sessionMins
                    val effectiveMins = maxOf(sessionMins, appTotalMins)

                    val stage = DoomStore.getStageForMinutes(effectiveMins, warn, dim, limit)
                    currentPetStage.value = stage
                    currentRoastText.value = DoomStore.getRoastMessage(
                        currentAppName.value,
                        effectiveMins.coerceAtLeast(1),
                        stage,
                        tone
                    )

                    // Strong gradual ramp from 55% up to user's maxStrength (default 78%, up to 92%)
                    punishmentIntensity.floatValue = when {
                        effectiveMins < dim -> 0f
                        effectiveMins >= limit -> maxStrength
                        else -> {
                            val span = (limit - dim).coerceAtLeast(1).toFloat()
                            val progress = ((effectiveMins - dim) / span).coerceIn(0f, 1f)
                            val minStart = (maxStrength * 0.65f).coerceAtLeast(0.45f)
                            minStart + (progress * (maxStrength - minStart))
                        }
                    }

                    if (effectiveMins >= limit && effectiveMins != lastModalShownAtMins && effectiveMins % 5 == 0) {
                        lastModalShownAtMins = effectiveMins
                        dockToSide(DockEdge.NONE)
                        showRealityCheckModal.value = true
                    }
                    syncWindowVisibilityAndPunishment()
                } else if (!isDemoPreview.value) {
                    if (isTargetAppOpen.value || punishmentIntensity.floatValue > 0f) {
                        isTargetAppOpen.value = false
                        activeVicePkg = null
                        showRealityCheckModal.value = false
                        punishmentIntensity.floatValue = 0f
                        syncWindowVisibilityAndPunishment()
                        // Sync updated stats to Squad Shame leaderboard on app exit
                        lifecycleScope.launch { SquadClient.syncMyStats(this@OverlayService)}
                    }
                }

                delay(1000L)
            }
        }
    }

    private fun buildNotification(content: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DoomShield Live Protection",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("DoomShield Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        DoomStore.isServiceRunning.value = false
        runCatching {
            pillComposeView?.let { windowManager.removeView(it) }
            punishmentView?.let { windowManager.removeView(it) }
        }
        store.clear()
        super.onDestroy()
    }
}

@Composable
private fun OverlayPillContent(
    visible: Boolean,
    appName: String,
    sessionSeconds: Int,
    appTotalMinsToday: Int,
    allAppsTotalMinsToday: Int,
    stage: PetStage,
    roastText: String,
    showModal: Boolean,
    dockEdge: DockEdge,
    onUndock: () -> Unit,
    onDockManual: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDismissModal: () -> Unit,
    onExitViceApp: () -> Unit
) {
    if (!visible) return

    var expanded by remember { mutableStateOf(false) }
    val noRippleInteraction = remember { MutableInteractionSource() }

    val accentColor by animateColorAsState(
        targetValue = when (stage) {
            PetStage.CHILL -> Color(0xFF34D399)
            PetStage.SWEATING -> Color(0xFFF59E0B)
            PetStage.SICK -> Color(0xFFA1A1AA)
            PetStage.FAINTED -> Color(0xFFEF4444)
        },
        animationSpec = tween(350),
        label = "pill_accent"
    )

    val mins = sessionSeconds / 60
    val secs = sessionSeconds % 60
    val timerFormatted = String.format("%02d:%02d", mins, secs)

    // 1. ULTRA-SLIM EDGE PEEK HANDLE (When docked to left or right screen edge)
    if (dockEdge != DockEdge.NONE && !showModal) {
        val handleShape = if (dockEdge == DockEdge.LEFT) {
            RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .width(30.dp)
                .clip(handleShape)
                .background(Color(0xF509090B))
                .border(0.75.dp, Color(0x28FFFFFF), handleShape)
                .clickable(
                    interactionSource = noRippleInteraction,
                    indication = null
                ) { onUndock() }
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .padding(vertical = 10.dp, horizontal = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${mins}m",
                color = Color(0xFFF5F5F7),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        return
    }

    // 2. STEALTH MICRO-PILL (Tiny pet + tabular timer only until tapped)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        if (!expanded) {
            // Ultra-minimal Stealth Micro-Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xF509090B))
                    .border(0.75.dp, Color(0x26FFFFFF), CircleShape)
                    .clickable(
                        interactionSource = noRippleInteraction,
                        indication = null
                    ) { expanded = true }
                    .padding(start = 7.dp, end = 11.dp, top = 5.dp, bottom = 5.dp)
            ) {
                DoomPetAvatar(stage = stage, size = 24.dp)
                Text(
                    text = timerFormatted,
                    color = Color(0xFFF5F5F7),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        } else {
            // Expanded Obsidian Minimal Card (Instant zero-lag toggle)
            Column(
                modifier = Modifier
                    .width(268.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFA09090B))
                    .border(0.75.dp, Color(0x24FFFFFF), RoundedCornerShape(22.dp))
                    .clickable(
                        interactionSource = noRippleInteraction,
                        indication = null
                    ) { expanded = false }
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DoomPetAvatar(stage = stage, size = 34.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = appName,
                            color = Color(0xFFF5F5F7),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Session $timerFormatted • ${stage.label}",
                            color = accentColor,
                            fontSize = 11.sp,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Matte charcoal stats strip
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141417))
                        .border(0.5.dp, Color(0x14FFFFFF), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            text = "TODAY ON APP",
                            color = Color(0xFF71717A),
                            fontSize = 9.sp,
                            letterSpacing = 0.6.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${appTotalMinsToday}m",
                            color = Color(0xFFF5F5F7),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "ALL VICE APPS",
                            color = Color(0xFF71717A),
                            fontSize = 9.sp,
                            letterSpacing = 0.6.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${allAppsTotalMinsToday}m",
                            color = accentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = roastText,
                    color = Color(0xFFA1A1AA),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            expanded = false
                            onExitViceApp()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF5F5F7),
                            contentColor = Color(0xFF09090B)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Icon(
                            Icons.Default.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Close app", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            expanded = false
                            onDockManual()
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(0.75.dp, Color(0x28FFFFFF)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Dock", color = Color(0xFFD4D4D8), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // 5-Second Reality Check Modal when DoomPet Faints
        if (showModal) {
            RealityCheckCard(
                appName = appName,
                appTotalMinsToday = appTotalMinsToday,
                roastText = roastText,
                onCloseViceApp = onExitViceApp,
                onKeepScrolling = onDismissModal
            )
        }
    }
}

@Composable
private fun RealityCheckCard(
    appName: String,
    appTotalMinsToday: Int,
    roastText: String,
    onCloseViceApp: () -> Unit,
    onKeepScrolling: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000L)
            countdown--
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(top = 12.dp)
            .width(284.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFC09090B))
            .border(0.75.dp, Color(0x45EF4444), RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        DoomPetAvatar(stage = PetStage.FAINTED, size = 56.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Time limit reached",
            color = Color(0xFFF5F5F7),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "${appTotalMinsToday}m spent on$appName today",
            color = Color(0xFFEF4444),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = roastText,
            color = Color(0xFFA1A1AA),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onCloseViceApp,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFF5F5F7),
                contentColor = Color(0xFF09090B)
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            Text(
                text = "Close $appName",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        TextButton(
            onClick = { if (countdown == 0) onKeepScrolling() },
            enabled = countdown == 0
        ) {
            Text(
                text = if (countdown > 0) "Continue in ${countdown}s" else "Keep scrolling anyway",
                color = if (countdown > 0) Color(0xFF52525B) else Color(0xFFEF4444),
                fontSize = 11.5.sp
            )
        }
    }
}