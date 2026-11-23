package com.doomshield.app

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.Groups
import kotlinx.coroutines.launch
import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// --- Obsidian Minimal Design System Tokens ---
private object Obsidian {
    val PitchBlack = Color(0xFF000000)
    val Surface = Color(0xFF0D0D0F)
    val SurfaceElevated = Color(0xFF16161A)
    val Hairline = Color(0x14FFFFFF)       // 8% white
    val HairlineStrong = Color(0x26FFFFFF) // 15% white
    val InkPrimary = Color(0xFFF5F5F7)
    val InkSecondary = Color(0xFF8E8E93)
    val InkMuted = Color(0xFF52525B)

    // Refined Semantic Accents
    val Mint = Color(0xFF34D399)
    val Amber = Color(0xFFF59E0B)
    val Silver = Color(0xFFA1A1AA)
    val Crimson = Color(0xFFEF4444)
}

/**
 * Spring-physics press scale modifier for tactile native feel.
 */
private fun Modifier.springPress(
    interactionSource: MutableInteractionSource
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press_scale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            val obsidianScheme = darkColorScheme(
                primary = Obsidian.InkPrimary,
                onPrimary = Obsidian.PitchBlack,
                secondary = Obsidian.Mint,
                background = Obsidian.PitchBlack,
                surface = Obsidian.Surface,
                surfaceVariant = Obsidian.SurfaceElevated,
                onBackground = Obsidian.InkPrimary,
                onSurface = Obsidian.InkPrimary,
                outline = Obsidian.Hairline
            )
            MaterialTheme(colorScheme = obsidianScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Obsidian.PitchBlack
                ) {
                    DoomShieldApp()
                }
            }
        }
    }
}

@Composable
fun DoomShieldApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var hasUsagePerm by remember { mutableStateOf(DoomStore.hasUsageStatsPermission(context)) }
    var hasOverlayPerm by remember { mutableStateOf(DoomStore.hasOverlayPermission(context)) }
    val isServiceRunning by DoomStore.isServiceRunning.collectAsState()

    var diagnostics by remember { mutableStateOf(DoomDiagnostics()) }
    var watchedPackages by remember { mutableStateOf(DoomStore.getWatchedPackages(context)) }
    var thresholds by remember { mutableStateOf(DoomStore.getThresholds(context)) }
    var roastTone by remember { mutableStateOf(DoomStore.getRoastTone(context)) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsagePerm = DoomStore.hasUsageStatsPermission(context)
                hasOverlayPerm = DoomStore.hasOverlayPermission(context)
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshTick, hasUsagePerm, watchedPackages) {
        if (hasUsagePerm) {
            diagnostics = withContext(Dispatchers.IO) {
                DoomStore.computeTodayDiagnostics(context)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.PitchBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Minimal Editorial Top Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DoomShield",
                            color = Obsidian.InkPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.5).sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Subtle Live Status Pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Obsidian.SurfaceElevated)
                                .border(0.75.dp, Obsidian.Hairline, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isServiceRunning) Obsidian.Mint else Obsidian.InkMuted)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isServiceRunning) "Active" else "Standby",
                                color = if (isServiceRunning) Obsidian.InkPrimary else Obsidian.InkSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                val refreshInteraction = remember { MutableInteractionSource() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .springPress(refreshInteraction)
                        .clip(CircleShape)
                        .background(Obsidian.Surface)
                        .border(0.75.dp, Obsidian.Hairline, CircleShape)
                        .clickable(
                            interactionSource = refreshInteraction,
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            refreshTick++
                        }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh",
                        tint = Obsidian.InkSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Smooth Directional Tab Content Transition
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(240)) + slideInHorizontally(tween(260)) { it / 16 * dir }) togetherWith
                            (fadeOut(tween(180)) + slideOutHorizontally(tween(200)) { -it / 16 * dir })
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "obsidian_tabs"
            ) { tab ->
                when (tab) {
                    0 -> HomeShieldTab(
                        hasUsagePerm = hasUsagePerm,
                        hasOverlayPerm = hasOverlayPerm,
                        isServiceRunning = isServiceRunning,
                        diagnostics = diagnostics,
                        thresholds = thresholds,
                        roastTone = roastTone,
                        onRequestNotifPerm = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                    1 -> DiagnosticsTab(
                        diagnostics = diagnostics,
                        thresholds = thresholds
                    )
                    2 -> SquadTab()

                    3 -> RulesAndAppsTab(
                        watchedPackages = watchedPackages,
                        onTogglePackage = { pkg ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            watchedPackages = DoomStore.toggleWatchedPackage(context, pkg)
                        },
                        thresholds = thresholds,
                        onSaveThresholds = { w, d, l ->
                            DoomStore.saveThresholds(context, w, d, l)
                            thresholds = Triple(w, d, l)
                        },
                        roastTone = roastTone,
                        onChangeTone = { tone ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            DoomStore.setRoastTone(context, tone)
                            roastTone = tone
                        }
                    )
                }
            }
        }

        // Floating Capsule Navigation Bar
        FloatingCapsuleNavBar(
            selectedTab = selectedTab,
            onSelectTab = { index ->
                if (index != selectedTab) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedTab = index
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 14.dp)
        )
    }
}

@Composable
private fun FloatingCapsuleNavBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        Triple(0, "Shield", Icons.Outlined.Shield),
        Triple(1, "Insights", Icons.Outlined.BarChart),
        Triple(2, "Squad", Icons.Outlined.Groups),
        Triple(3, "Rules", Icons.Outlined.Tune)
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xEB121215))
            .border(0.75.dp, Obsidian.HairlineStrong, CircleShape)
            .padding(5.dp)
    ) {
        items.forEach { (index, label, icon) ->
            val selected = (selectedTab == index)
            val bgColor by animateColorAsState(
                targetValue = if (selected) Obsidian.InkPrimary else Color.Transparent,
                animationSpec = tween(220),
                label = "nav_bg"
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) Obsidian.PitchBlack else Obsidian.InkSecondary,
                animationSpec = tween(220),
                label = "nav_fg"
            )
            val interaction = remember { MutableInteractionSource() }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .springPress(interaction)
                    .clip(CircleShape)
                    .background(bgColor)
                    .clickable(
                        interactionSource = interaction,
                        indication = null
                    ) { onSelectTab(index) }
                    .padding(horizontal = if (selected) 18.dp else 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                if (selected) {
                    Text(
                        text = label,
                        color = contentColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Obsidian.InkMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun ObsidianCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val baseMod = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(22.dp))
        .background(Obsidian.Surface)
        .border(0.75.dp, Obsidian.Hairline, RoundedCornerShape(22.dp))

    val finalMod = if (onClick != null) {
        baseMod.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    } else baseMod

    Column(
        modifier = finalMod.padding(20.dp),
        content = content
    )
}

@Composable
private fun HomeShieldTab(
    hasUsagePerm: Boolean,
    hasOverlayPerm: Boolean,
    isServiceRunning: Boolean,
    diagnostics: DoomDiagnostics,
    thresholds: Triple<Int, Int, Int>,
    roastTone: RoastTone,
    onRequestNotifPerm: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val (warn, dim, limit) = thresholds
    val wasted = diagnostics.totalWastedMinsToday
    val animatedWasted by animateIntAsState(
        targetValue = wasted,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "wasted_ticker"
    )
    val stage = DoomStore.getStageForMinutes(wasted, warn, dim, limit)
    val roast = DoomStore.getRoastMessage("your vice apps", wasted, stage, roastTone)

    val stageColor by animateColorAsState(
        targetValue = when (stage) {
            PetStage.CHILL -> Obsidian.Mint
            PetStage.SWEATING -> Obsidian.Amber
            PetStage.SICK -> Obsidian.Silver
            PetStage.FAINTED -> Obsidian.Crimson
        },
        animationSpec = tween(450),
        label = "stage_color"
    )

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. Subtle Permission Setup Banner (only shown if needed)
        if (!hasUsagePerm || !hasOverlayPerm) {
            item {
                ObsidianCard {
                    Text(
                        text = "Permissions required",
                        color = Obsidian.InkPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Enable Usage Access and Display Over Apps so DoomShield can run over Instagram, YouTube, and games.",
                        color = Obsidian.InkSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            },
                            enabled = !hasUsagePerm,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Obsidian.InkPrimary,
                                contentColor = Obsidian.PitchBlack,
                                disabledContainerColor = Obsidian.SurfaceElevated,
                                disabledContentColor = Obsidian.Mint
                            ),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Text(
                                text = if (hasUsagePerm) "Usage ✓" else "Usage access",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            },
                            enabled = !hasOverlayPerm,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Obsidian.InkPrimary,
                                contentColor = Obsidian.PitchBlack,
                                disabledContainerColor = Obsidian.SurfaceElevated,
                                disabledContentColor = Obsidian.Mint
                            ),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Text(
                                text = if (hasOverlayPerm) "Overlay ✓" else "Overlay access",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // 2. Hero Pet & Burn Budget Card
        item {
            val progress by animateFloatAsState(
                targetValue = (wasted.toFloat() / limit.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
                label = "ring_progress"
            )

            ObsidianCard {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                ) {
                    Canvas(modifier = Modifier.size(196.dp)) {
                        val stroke = 8.dp.toPx()
                        drawArc(
                            color = Color(0xFF1C1C21),
                            startAngle = -215f,
                            sweepAngle = 250f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = stageColor,
                            startAngle = -215f,
                            sweepAngle = 250f * progress,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DoomPetAvatar(stage = stage, size = 84.dp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${animatedWasted}m",
                                color = Obsidian.InkPrimary,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-1).sp,
                                style = TextStyle(fontFeatureSettings = "tnum")
                            )
                            Text(
                                text = " / ${limit}m",
                                color = Obsidian.InkMuted,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 4.dp),
                                style = TextStyle(fontFeatureSettings = "tnum")
                            )
                        }
                        Text(
                            text = stage.label,
                            color = stageColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Subtle Roast Note
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Obsidian.SurfaceElevated)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = roast,
                        color = Obsidian.InkSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Primary Action Button (Crisp Ivory when starting, Matte Dark when active)
                val shieldInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRequestNotifPerm()
                        val intent = Intent(context, OverlayService::class.java).apply {
                            action = if (isServiceRunning) OverlayService.ACTION_STOP else OverlayService.ACTION_START
                        }
                        if (isServiceRunning) {
                            context.startService(intent)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    },
                    enabled = hasUsagePerm && hasOverlayPerm,
                    interactionSource = shieldInteraction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) Obsidian.SurfaceElevated else Obsidian.InkPrimary,
                        contentColor = if (isServiceRunning) Obsidian.Crimson else Obsidian.PitchBlack
                    ),
                    border = if (isServiceRunning) BorderStroke(0.75.dp, Obsidian.Crimson.copy(alpha = 0.4f)) else null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .springPress(shieldInteraction)
                ) {
                    Text(
                        text = if (isServiceRunning) "Pause Shield" else "Activate Shield",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Secondary Demo Action
                val demoInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRequestNotifPerm()
                        val intent = Intent(context, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_DEMO_CYCLE
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    },
                    enabled = hasOverlayPerm,
                    interactionSource = demoInteraction,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.75.dp, Obsidian.Hairline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .springPress(demoInteraction)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = Obsidian.InkSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Preview overlay stages",
                        color = Obsidian.InkSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 3. Clean Escalation Timeline
        item {
            SectionLabel("Escalation Curve")
            ObsidianCard {
                EscalationRow("0 – ${warn}m", "Stealth micro-pill & calm pet", Obsidian.Mint)
                HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 11.dp))
                EscalationRow("${warn} – ${dim}m", "Sweating warning & subtle roasts", Obsidian.Amber)
                HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 11.dp))
                EscalationRow("${dim} – ${limit}m", "Screen dimming & visual friction", Obsidian.Silver)
                HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 11.dp))
                EscalationRow("${limit}m+", "Pet faints & 5s reality check", Obsidian.Crimson)
            }
        }
    }
}

@Composable
private fun EscalationRow(window: String, desc: String, dotColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = window,
            color = Obsidian.InkPrimary,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.width(74.dp)
        )
        Text(
            text = desc,
            color = Obsidian.InkSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun DiagnosticsTab(
    diagnostics: DoomDiagnostics,
    thresholds: Triple<Int, Int, Int>
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // 0 = Day, 1 = Week, 2 = Month
    var selectedPeriod by remember { mutableIntStateOf(0) }
    var weekAnalytics by remember { mutableStateOf(PeriodAnalytics()) }
    var monthAnalytics by remember { mutableStateOf(PeriodAnalytics()) }
    var isSyncingHistory by remember { mutableStateOf(false) }

    // Record today, auto-backfill last 7 days locally, and sync 12-month history with Supabase
    LaunchedEffect(diagnostics.totalWastedMinsToday) {
        isSyncingHistory = true
        val saved = withContext(Dispatchers.IO) {
            DoomStore.recordTodayAndBackfill7Days(context)
        }
        weekAnalytics = withContext(Dispatchers.IO) { DoomStore.computePeriodAnalytics(context, 7) }
        monthAnalytics = withContext(Dispatchers.IO) { DoomStore.computePeriodAnalytics(context, 30) }

        // Background Supabase 12-month sync
        SquadClient.syncUserHistoryToSupabase(context, saved)
        SquadClient.fetch12MonthHistoryFromSupabase(context)

        weekAnalytics = withContext(Dispatchers.IO) { DoomStore.computePeriodAnalytics(context, 7) }
        monthAnalytics = withContext(Dispatchers.IO) { DoomStore.computePeriodAnalytics(context, 30) }
        isSyncingHistory = false
    }

    val survivalDays = remember(diagnostics.totalWastedMinsToday) {
        DoomStore.getLast14DaysSurvival(context, diagnostics.totalWastedMinsToday)
    }

    val animWasted by animateIntAsState(diagnostics.totalWastedMinsToday, tween(500), label = "w")
    val animPickups by animateIntAsState(diagnostics.totalPickupsToday, tween(500), label = "p")
    val animLongest by animateIntAsState(diagnostics.longestSessionMins, tween(500), label = "l")
    val (todayPeakHr, todayPeakMins) = remember(diagnostics.hourlyMins) {
        DoomStore.findPeakHour(diagnostics.hourlyMins)
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 0. Day | Week | Month Obsidian Segmented Control
        item {
            val labels = listOf("Day", "Week", "Month")
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Obsidian.Surface)
                    .border(0.75.dp, Obsidian.Hairline, RoundedCornerShape(16.dp))
                    .padding(5.dp)
            ) {
                labels.forEachIndexed { idx, label ->
                    val selected = (selectedPeriod == idx)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Obsidian.InkPrimary else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedPeriod = idx
                            }
                            .padding(vertical = 9.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (selected) Obsidian.PitchBlack else Obsidian.InkSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (selectedPeriod == 0) {
            // ================= DAY VIEW =================
            item {
                SectionLabel("Today's Snapshot")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MetricBox(
                        title = "Wasted time",
                        value = DoomStore.formatDuration(animWasted),
                        subtitle = "Limit ${thresholds.third}m",
                        indicator = Obsidian.Crimson,
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        title = "Impulse pickups",
                        value = "${animPickups}×",
                        subtitle = "Avg ${diagnostics.avgSessionMins}m each",
                        indicator = Obsidian.Amber,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MetricBox(
                        title = "Longest session",
                        value = DoomStore.formatDuration(animLongest),
                        subtitle = "Unbroken scroll",
                        indicator = Obsidian.Silver,
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        title = "Peak hour",
                        value = DoomStore.formatHourShort(todayPeakHr),
                        subtitle = if (todayPeakMins > 0) "${todayPeakMins}m in this hour" else "Clean day so far",
                        indicator = Obsidian.Mint,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Interactive 24-Hour Binge Heatmap
            item {
                var selectedHour by remember { mutableStateOf<Int?>(null) }

                fun formatHourSlot(hr: Int): String {
                    val startAmPm = if (hr < 12) "AM" else "PM"
                    val endHr24 = (hr + 1) % 24
                    val endAmPm = if (endHr24 < 12) "AM" else "PM"
                    val start12 = if (hr % 12 == 0) 12 else hr % 12
                    val end12 = if (endHr24 % 12 == 0) 12 else endHr24 % 12
                    return "$start12 $startAmPm – $end12 $endAmPm"
                }

                SectionLabel("Hourly Activity")
                ObsidianCard(onClick = { selectedHour = null }) {
                    Text(
                        text = "24-hour distribution",
                        color = Obsidian.InkPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Tap any bar to inspect",
                        color = Obsidian.InkMuted,
                        fontSize = 12.sp
                    )

                    AnimatedVisibility(
                        visible = selectedHour != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        val hr = selectedHour ?: 0
                        val mins = diagnostics.hourlyMins.getOrElse(hr) { 0 }
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Obsidian.SurfaceElevated)
                                .border(0.75.dp, Obsidian.HairlineStrong, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = formatHourSlot(hr),
                                color = Obsidian.InkSecondary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (mins > 0) "${mins}m wasted" else "Clean hour",
                                color = if (mins > 0) Obsidian.InkPrimary else Obsidian.Mint,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = TextStyle(fontFeatureSettings = "tnum")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    val maxHr = (diagnostics.hourlyMins.maxOrNull() ?: 1).coerceAtLeast(15)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(116.dp)
                    ) {
                        diagnostics.hourlyMins.forEachIndexed { hr, mins ->
                            val isSelected = (hr == selectedHour)
                            val ratio = (mins.toFloat() / maxHr.toFloat()).coerceIn(0.06f, 1f)
                            val barColor = when {
                                isSelected -> Obsidian.InkPrimary
                                mins >= 25 -> Obsidian.Crimson
                                mins >= 10 -> Obsidian.Amber
                                mins > 0 -> Obsidian.Mint.copy(alpha = 0.85f)
                                else -> Color(0xFF1C1C21)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedHour = if (selectedHour == hr) null else hr
                                    }
                            ) {
                                if (isSelected && mins > 0) {
                                    Text(
                                        text = "${mins}m",
                                        color = Obsidian.InkPrimary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(ratio)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                        .background(barColor)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("12A", "6A", "12P", "6P", "11P").forEach { lbl ->
                            Text(lbl, color = Obsidian.InkMuted, fontSize = 10.sp)
                        }
                    }
                }
            }

            // Per-App Breakdown
            item {
                SectionLabel("Culprit Apps")
                ObsidianCard {
                    if (diagnostics.perAppBreakdown.isEmpty()) {
                        Text(
                            text = "No watched apps opened today. Clean slate.",
                            color = Obsidian.Mint,
                            fontSize = 13.sp
                        )
                    } else {
                        val maxAppMins = diagnostics.perAppBreakdown.maxOf { it.minutesToday }.coerceAtLeast(1)
                        diagnostics.perAppBreakdown.forEachIndexed { idx, item ->
                            if (idx > 0) {
                                HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 12.dp))
                            }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.appName,
                                            color = Obsidian.InkPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "${item.pickupsToday} pickups · longest ${item.longestStreakMins}m",
                                            color = Obsidian.InkMuted,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                    Text(
                                        text = DoomStore.formatDuration(item.minutesToday),
                                        color = Obsidian.InkPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        style = TextStyle(fontFeatureSettings = "tnum")
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1C1C21))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((item.minutesToday.toFloat() / maxAppMins.toFloat()).coerceIn(0.08f, 1f))
                                            .fillMaxHeight()
                                            .clip(CircleShape)
                                            .background(if (item.minutesToday >= 20) Obsidian.Crimson else Obsidian.Amber)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7-Day Pet Survival & Share Card
            item {
                SectionLabel("Pet Survival Streak")
                ObsidianCard {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        survivalDays.takeLast(7).forEach { (dayLabel, survived) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Obsidian.SurfaceElevated)
                                        .border(
                                            width = 0.75.dp,
                                            color = if (survived) Obsidian.Mint.copy(alpha = 0.45f) else Obsidian.Crimson.copy(alpha = 0.45f),
                                            shape = CircleShape
                                        )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (survived) Obsidian.Mint else Obsidian.Crimson)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(dayLabel, color = Obsidian.InkMuted, fontSize = 10.5.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    val shareInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val shareReport = buildString {
                                appendLine("DoomShield Daily Report")
                                appendLine("• Wasted: ${DoomStore.formatDuration(diagnostics.totalWastedMinsToday)}")
                                appendLine("• Pickups: ${diagnostics.totalPickupsToday}x")
                                appendLine("• Longest scroll: ${diagnostics.longestSessionMins}m")
                                appendLine("• Peak hour: ${DoomStore.formatHourShort(todayPeakHr)}")
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareReport)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Report"))
                        },
                        interactionSource = shareInteraction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Obsidian.InkPrimary,
                            contentColor = Obsidian.PitchBlack
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .springPress(shareInteraction)
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share daily report", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                    }
                }
            }
        } else {
            // ================= WEEK (7D) & MONTH (30D) VIEW =================
            val isWeek = (selectedPeriod == 1)
            val data = if (isWeek) weekAnalytics else monthAnalytics
            val periodLabel = if (isWeek) "week" else "month"

            val deltaTitle = if (isWeek) "vs. Last week" else "vs. Last month"
            val improved = data.deltaMins <= 0
            val deltaValueText = when {
                !data.hasPrevData -> "Baseline"
                data.deltaPercent == 0 -> "0%"
                improved -> "${data.deltaPercent}%"
                else -> "+${data.deltaPercent}%"
            }
            val deltaSubText = when {
                !data.hasPrevData -> "Tracking first $periodLabel"
                improved -> "${DoomStore.formatDuration(kotlin.math.abs(data.deltaMins))} saved"
                else -> "${DoomStore.formatDuration(data.deltaMins)} more wasted"
            }

            item {
                SectionLabel(if (isWeek) "Last 7 Days" else "Last 30 Days (12-Month Archive)")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MetricBox(
                        title = "Total wasted",
                        value = DoomStore.formatDuration(data.totalMins),
                        subtitle = "${data.totalTrackedDays} days logged",
                        indicator = Obsidian.Crimson,
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        title = "Daily average",
                        value = DoomStore.formatDuration(data.dailyAvgMins),
                        subtitle = "Target ${thresholds.third}m / day",
                        indicator = Obsidian.Amber,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MetricBox(
                        title = deltaTitle,
                        value = deltaValueText,
                        subtitle = deltaSubText,
                        indicator = if (!data.hasPrevData) Obsidian.Silver else if (improved) Obsidian.Mint else Obsidian.Crimson,
                        modifier = Modifier.weight(1f)
                    )
                    MetricBox(
                        title = "Avg peak hour",
                        value = DoomStore.formatHourShort(data.peakHour),
                        subtitle = if (data.peakHourTotalMins > 0) "${DoomStore.formatDuration(data.peakHourTotalMins)} total" else "No peak yet",
                        indicator = Obsidian.Mint,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Interactive 7-Day or 30-Day Trend Bar Chart
            item {
                var selectedBarIdx by remember(selectedPeriod) { mutableStateOf<Int?>(null) }

                SectionLabel(if (isWeek) "Daily Breakdown (7 Days)" else "Daily Trend (30 Days)")
                ObsidianCard(onClick = { selectedBarIdx = null }) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = if (isWeek) "Time wasted per day" else "30-day scroll trend",
                                color = Obsidian.InkPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (isSyncingHistory) "Syncing 12-month history..." else "Tap any day bar to inspect",
                                color = Obsidian.InkMuted,
                                fontSize = 12.sp
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = selectedBarIdx != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        val pt = selectedBarIdx?.let { data.dailyBars.getOrNull(it) }
                        if (pt != null) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Obsidian.SurfaceElevated)
                                    .border(0.75.dp, Obsidian.HairlineStrong, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Column {
                                    Text(pt.fullLabel, color = Obsidian.InkPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Top app: ${pt.topCulpritApp}", color = Obsidian.InkMuted, fontSize = 11.sp)
                                }
                                Text(
                                    text = if (pt.wastedMins > 0) DoomStore.formatDuration(pt.wastedMins) else "Clean day",
                                    color = if (pt.wastedMins >= thresholds.third) Obsidian.Crimson else Obsidian.Mint,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    style = TextStyle(fontFeatureSettings = "tnum")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    val maxDayMins = (data.dailyBars.maxOfOrNull { it.wastedMins } ?: 1).coerceAtLeast(thresholds.third)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(if (isWeek) 8.dp else 2.dp),
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(126.dp)
                    ) {
                        data.dailyBars.forEachIndexed { idx, pt ->
                            val isSelected = (selectedBarIdx == idx)
                            val ratio = (pt.wastedMins.toFloat() / maxDayMins.toFloat()).coerceIn(0.06f, 1f)
                            val barColor = when {
                                isSelected -> Obsidian.InkPrimary
                                pt.wastedMins >= thresholds.third -> Obsidian.Crimson
                                pt.wastedMins >= thresholds.first -> Obsidian.Amber
                                pt.wastedMins > 0 -> Obsidian.Mint.copy(alpha = 0.85f)
                                else -> Color(0xFF1C1C21)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedBarIdx = if (selectedBarIdx == idx) null else idx
                                    }
                            ) {
                                // Bar track reserves fixed space above the label so every bar shares the exact same baseline!
                                Box(
                                    contentAlignment = Alignment.BottomCenter,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(ratio)
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                            .background(barColor)
                                    )
                                }
                                if (isWeek) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = pt.shortLabel,
                                        color = if (isSelected) Obsidian.InkPrimary else Obsidian.InkMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    if (!isWeek && data.dailyBars.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(data.dailyBars.first().fullLabel.substringAfter(", "), color = Obsidian.InkMuted, fontSize = 10.sp)
                            Text("15 days ago", color = Obsidian.InkMuted, fontSize = 10.sp)
                            Text("Today", color = Obsidian.InkMuted, fontSize = 10.sp)
                        }
                    }
                }
            }

            // Discipline Summary Card (Survival Rate, Clean Days, Best & Worst Day)
            item {
                SectionLabel("Discipline Summary")
                ObsidianCard {
                    EscalationRow("Clean days", "${data.cleanDaysCount} / ${data.totalTrackedDays} under ${thresholds.third}m (${data.survivalRatePercent}%)", Obsidian.Mint)
                    HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 11.dp))
                    EscalationRow("Best day", "${data.bestDayLabel} · ${DoomStore.formatDuration(data.bestDayMins)}", Obsidian.Silver)
                    HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 11.dp))
                    EscalationRow("Worst day", "${data.worstDayLabel} · ${DoomStore.formatDuration(data.worstDayMins)}", Obsidian.Crimson)
                }
            }
        }
    }
}

@Composable
private fun MetricBox(
    title: String,
    value: String,
    subtitle: String,
    indicator: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Obsidian.Surface)
            .border(0.75.dp, Obsidian.Hairline, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(indicator)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(title, color = Obsidian.InkSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = value,
            color = Obsidian.InkPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1).sp,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(subtitle, color = Obsidian.InkMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RulesAndAppsTab(
    watchedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    thresholds: Triple<Int, Int, Int>,
    onSaveThresholds: (Int, Int, Int) -> Unit,
    roastTone: RoastTone,
    onChangeTone: (RoastTone) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var warnMins by remember(thresholds) { mutableFloatStateOf(thresholds.first.toFloat()) }
    var dimMins by remember(thresholds) { mutableFloatStateOf(thresholds.second.toFloat()) }
    var limitMins by remember(thresholds) { mutableFloatStateOf(thresholds.third.toFloat()) }

    var popupIntervalMins by remember {
        mutableFloatStateOf(DoomStore.getEdgePopupIntervalMins(context).toFloat())
    }
    var selectedPunishments by remember {
        mutableStateOf(DoomStore.getSelectedPunishments(context))
    }
    var punishmentStrength by remember {
        mutableFloatStateOf(DoomStore.getPunishmentStrength(context))
    }

    var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(watchedPackages) {
        installedApps = withContext(Dispatchers.IO) {
            DoomStore.getInstalledApps(context)
        }
    }

    val sliderColors = SliderDefaults.colors(
        thumbColor = Obsidian.InkPrimary,
        activeTrackColor = Obsidian.InkPrimary,
        inactiveTrackColor = Color(0xFF232329)
    )

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. Timers Card
        item {
            SectionLabel("Thresholds & Edge Dock")
            ObsidianCard {
                SliderHeader("Sweating warning", "${warnMins.toInt()} min")
                Slider(
                    value = warnMins,
                    onValueChange = {
                        warnMins = it
                        if (dimMins <= warnMins) dimMins = warnMins + 5f
                        if (limitMins <= dimMins) limitMins = dimMins + 5f
                        onSaveThresholds(warnMins.toInt(), dimMins.toInt(), limitMins.toInt())
                    },
                    valueRange = 1f..30f,
                    colors = sliderColors
                )

                Spacer(modifier = Modifier.height(6.dp))
                SliderHeader("Visual friction starts", "${dimMins.toInt()} min")
                Slider(
                    value = dimMins,
                    onValueChange = {
                        dimMins = it.coerceAtLeast(warnMins + 1f)
                        if (limitMins <= dimMins) limitMins = dimMins + 5f
                        onSaveThresholds(warnMins.toInt(), dimMins.toInt(), limitMins.toInt())
                    },
                    valueRange = 2f..60f,
                    colors = sliderColors
                )

                Spacer(modifier = Modifier.height(6.dp))
                SliderHeader("Reality check pause", "${limitMins.toInt()} min")
                Slider(
                    value = limitMins,
                    onValueChange = {
                        limitMins = it.coerceAtLeast(dimMins + 1f)
                        onSaveThresholds(warnMins.toInt(), dimMins.toInt(), limitMins.toInt())
                    },
                    valueRange = 5f..90f,
                    colors = sliderColors
                )

                HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 12.dp))

                SliderHeader("Edge-dock auto-popup", "Every ${popupIntervalMins.toInt()}m")
                Text(
                    text = "Push pill to left/right edge to hide; pops out after this interval.",
                    color = Obsidian.InkMuted,
                    fontSize = 11.5.sp
                )
                Slider(
                    value = popupIntervalMins,
                    onValueChange = {
                        val rounded = it.roundToInt()
                        if (rounded != popupIntervalMins.roundToInt()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        popupIntervalMins = it
                        DoomStore.setEdgePopupIntervalMins(context, rounded)
                    },
                    valueRange = 1f..15f,
                    steps = 13,
                    colors = sliderColors
                )
            }
        }

        // 2. Visual Punishments Card
        item {
            SectionLabel("Visual Friction")
            ObsidianCard {
                VisualPunishment.entries.forEachIndexed { idx, mode ->
                    if (idx > 0) {
                        HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 10.dp))
                    }
                    val isSelected = selectedPunishments.contains(mode)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedPunishments = DoomStore.togglePunishment(context, mode)
                            }
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.title,
                                color = Obsidian.InkPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                            Text(
                                text = mode.subtitle,
                                color = Obsidian.InkMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = isSelected,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedPunishments = DoomStore.togglePunishment(context, mode)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Obsidian.PitchBlack,
                                checkedTrackColor = Obsidian.InkPrimary,
                                uncheckedThumbColor = Obsidian.InkSecondary,
                                uncheckedTrackColor = Obsidian.SurfaceElevated,
                                uncheckedBorderColor = Obsidian.Hairline
                            )
                        )
                    }
                }

                HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 12.dp))
                SliderHeader("Effect intensity", "${(punishmentStrength * 100).roundToInt()}%")
                Slider(
                    value = punishmentStrength,
                    onValueChange = {
                        punishmentStrength = it
                        DoomStore.setPunishmentStrength(context, it)
                    },
                    valueRange = 0.45f..0.92f,
                    colors = sliderColors
                )
            }
        }

        // 3. Segmented Roast Personality Selector
        item {
            SectionLabel("Roast Tone")
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Obsidian.Surface)
                    .border(0.75.dp, Obsidian.Hairline, RoundedCornerShape(16.dp))
                    .padding(5.dp)
            ) {
                RoastTone.entries.forEach { tone ->
                    val selected = tone == roastTone
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Obsidian.InkPrimary else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onChangeTone(tone) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = tone.label.substringBefore(" "),
                            color = if (selected) Obsidian.PitchBlack else Obsidian.InkSecondary,
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 4. Watched Apps List
        item {
            SectionLabel("Monitored Apps (${watchedPackages.size})")
        }

        items(installedApps, key = { it.first }) { (pkg, appName) ->
            val isWatched = watchedPackages.contains(pkg)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isWatched) Obsidian.SurfaceElevated else Obsidian.Surface)
                    .border(
                        width = 0.75.dp,
                        color = if (isWatched) Obsidian.HairlineStrong else Obsidian.Hairline,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onTogglePackage(pkg) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appName,
                        color = Obsidian.InkPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pkg,
                        color = Obsidian.InkMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = isWatched,
                    onCheckedChange = { onTogglePackage(pkg) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Obsidian.PitchBlack,
                        checkedTrackColor = Obsidian.InkPrimary,
                        uncheckedThumbColor = Obsidian.InkSecondary,
                        uncheckedTrackColor = Obsidian.PitchBlack,
                        uncheckedBorderColor = Obsidian.Hairline
                    )
                )
            }
        }
    }
}

@Composable
private fun SliderHeader(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = Obsidian.InkPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
        Text(
            text = value,
            color = Obsidian.InkSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}
@Composable
private fun SquadTab() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var squads by remember { mutableStateOf(SquadClient.getAllSquads(context)) }
    var activeSquad by remember { mutableStateOf(SquadClient.getActiveSquad(context)) }
    var showAddSquadForm by remember { mutableStateOf(squads.isEmpty()) }

    var board by remember { mutableStateOf(SquadBoardData()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var displayName by remember { mutableStateOf(SquadClient.getSavedDisplayName(context)) }
    var squadName by remember { mutableStateOf("") }
    var inviteCodeInput by remember { mutableStateOf("") }

    fun refreshBoard(targetSquad: SquadInfo?) {
        if (targetSquad == null) return
        scope.launch {
            isLoading = true
            SquadClient.syncMyStats(context)
            board = SquadClient.fetchSquadBoard(context, targetSquad.squadId)
            isLoading = false
        }
    }

    LaunchedEffect(activeSquad?.squadId) {
        if (activeSquad != null && !showAddSquadForm) {
            refreshBoard(activeSquad)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Horizontal Multi-Squad Switcher Bar (shown whenever user has 1+ squads)
        if (squads.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(squads, key = { it.squadId }) { sq ->
                        val isSelected = (!showAddSquadForm && activeSquad?.squadId == sq.squadId)
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isSelected) Obsidian.InkPrimary else Obsidian.Surface)
                                .border(
                                    0.75.dp,
                                    if (isSelected) Obsidian.InkPrimary else Obsidian.Hairline,
                                    CircleShape
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showAddSquadForm = false
                                    activeSquad = sq
                                    SquadClient.setActiveSquad(context, sq.squadId)
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = sq.squadName,
                                color = if (isSelected) Obsidian.PitchBlack else Obsidian.InkSecondary,
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }

                    // "+ Add / Join" Pill
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (showAddSquadForm) Obsidian.SurfaceElevated else Obsidian.Surface)
                                .border(
                                    0.75.dp,
                                    if (showAddSquadForm) Obsidian.HairlineStrong else Obsidian.Hairline,
                                    CircleShape
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    statusMessage = null
                                    showAddSquadForm = !showAddSquadForm
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "Add squad",
                                tint = Obsidian.InkPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (showAddSquadForm) "Close" else "New / Join",
                                color = Obsidian.InkPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        if (showAddSquadForm || activeSquad == null) {
            // --- CREATE OR JOIN SQUAD CARD ---
            item {
                SectionLabel(if (squads.isEmpty()) "Squad Shame" else "Add Another Squad")
                ObsidianCard {
                    Text(
                        text = if (squads.isEmpty()) "Compete with friends" else "Create or join a squad",
                        color = Obsidian.InkPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Your daily stats sync across all squads you join (e.g. Family, College, Gym).",
                        color = Obsidian.InkSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Your display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = Obsidian.Hairline)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Create a new squad", color = Obsidian.InkPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = squadName,
                        onValueChange = { squadName = it },
                        label = { Text("Squad name (e.g. College Friends)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                isLoading = true
                                statusMessage = null
                                val res = SquadClient.createSquad(
                                    context,
                                    squadName.ifBlank { "Doom Squad" },
                                    displayName.ifBlank { "Player" }
                                )
                                res.onSuccess { created ->
                                    squads = SquadClient.getAllSquads(context)
                                    activeSquad = created
                                    squadName = ""
                                    showAddSquadForm = false
                                }.onFailure { statusMessage = it.message }
                                isLoading = false
                            }
                        },
                        enabled = !isLoading && displayName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Obsidian.InkPrimary,
                            contentColor = Obsidian.PitchBlack
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text(if (isLoading) "Creating..." else "Create squad", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Obsidian.Hairline)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Or join with an invite code", color = Obsidian.InkPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inviteCodeInput,
                        onValueChange = { inviteCodeInput = it.uppercase() },
                        label = { Text("Invite code (e.g. DS-7K4M)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                isLoading = true
                                statusMessage = null
                                val res = SquadClient.joinSquad(
                                    context,
                                    inviteCodeInput,
                                    displayName.ifBlank { "Player" }
                                )
                                res.onSuccess { joined ->
                                    squads = SquadClient.getAllSquads(context)
                                    activeSquad = joined
                                    inviteCodeInput = ""
                                    showAddSquadForm = false
                                }.onFailure { statusMessage = it.message }
                                isLoading = false
                            }
                        },
                        enabled = !isLoading && displayName.isNotBlank() && inviteCodeInput.isNotBlank(),
                        border = BorderStroke(0.75.dp, Obsidian.HairlineStrong),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Join squad", color = Obsidian.InkPrimary, fontWeight = FontWeight.Medium)
                    }

                    if (statusMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(statusMessage!!, color = Obsidian.Amber, fontSize = 12.sp)
                    }
                }
            }
        } else {
            val currentSquad = activeSquad!!

            // 1. Squad Header + Clean Offline Banner
            item {
                ObsidianCard {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentSquad.squadName,
                                color = Obsidian.InkPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Code: ${currentSquad.inviteCode} · ${board.members.size} active today",
                                color = Obsidian.InkSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Join my DoomShield squad '${currentSquad.squadName}'! Invite code: ${currentSquad.inviteCode}"
                                        )
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Invite Friends"))
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.75.dp, Obsidian.HairlineStrong),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Invite", color = Obsidian.InkPrimary, fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = {
                                    SquadClient.leaveSquad(context, currentSquad.squadId)
                                    squads = SquadClient.getAllSquads(context)
                                    activeSquad = SquadClient.getActiveSquad(context)
                                    if (squads.isEmpty()) showAddSquadForm = true
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Leave", color = Obsidian.InkMuted, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            refreshBoard(currentSquad)
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Obsidian.SurfaceElevated,
                            contentColor = Obsidian.InkPrimary
                        ),
                        border = BorderStroke(0.75.dp, Obsidian.Hairline),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text(if (isLoading) "Syncing live board..." else "Sync & refresh leaderboard", fontSize = 12.5.sp)
                    }

                    // Clean, Friendly Offline Pill (never shows raw hostnames!)
                    if (board.isOffline) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Obsidian.SurfaceElevated)
                                .border(0.75.dp, Obsidian.Amber.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WifiOff,
                                contentDescription = "Offline",
                                tint = Obsidian.Amber,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (board.members.isNotEmpty())
                                    "You're offline · showing last synced board"
                                else
                                    "You're offline · connect to sync squad stats",
                                color = Obsidian.InkSecondary,
                                fontSize = 12.sp
                            )
                        }
                    } else if (board.error != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(board.error!!, color = Obsidian.Amber, fontSize = 12.sp)
                    }
                }
            }

            // 2. Most Disciplined vs. Shame Crown
            if (board.members.size >= 2) {
                item {
                    val best = board.members.first()
                    val worst = board.members.last()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricBox(
                            title = "Most disciplined",
                            value = best.displayName,
                            subtitle = "${best.wastedMins}m wasted today",
                            indicator = Obsidian.Mint,
                            modifier = Modifier.weight(1f)
                        )
                        MetricBox(
                            title = "Shame crown",
                            value = worst.displayName,
                            subtitle = "${worst.wastedMins}m on ${worst.topCulpritApp}",
                            indicator = Obsidian.Crimson,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 3. Live Ranked Leaderboard
            item {
                SectionLabel("Today's Leaderboard")
                ObsidianCard {
                    if (board.members.isEmpty()) {
                        Text(
                            text = if (board.isOffline) "Connect to the internet to load today's leaderboard."
                            else "No stats synced today yet. Tap Sync above!",
                            color = Obsidian.InkSecondary,
                            fontSize = 13.sp
                        )
                    } else {
                        board.members.forEachIndexed { idx, member ->
                            if (idx > 0) {
                                HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 12.dp))
                            }
                            val isMe = member.userId == currentSquad.myUserId
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "#${idx + 1}",
                                    color = if (idx == 0) Obsidian.Mint else Obsidian.InkMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(26.dp)
                                )
                                DoomPetAvatar(stage = member.petStage, size = 30.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isMe) "${member.displayName} (You)" else member.displayName,
                                        color = Obsidian.InkPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${member.topCulpritApp} · ${member.pickups} pickups",
                                        color = Obsidian.InkMuted,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "${member.wastedMins}m",
                                    color = if (member.petStage == PetStage.FAINTED) Obsidian.Crimson else Obsidian.InkPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    style = TextStyle(fontFeatureSettings = "tnum")
                                )

                            }
                        }
                    }
                }
            }

            // 4. Live Wall of Shame Feed
            item {
                SectionLabel("Wall of Shame Feed")
                ObsidianCard {
                    if (board.feed.isEmpty()) {
                        Text(
                            text = "Quiet so far. Squad joins and pet faints appear here.",
                            color = Obsidian.InkMuted,
                            fontSize = 12.5.sp
                        )
                    } else {
                        board.feed.forEachIndexed { idx, ev ->
                            if (idx > 0) {
                                HorizontalDivider(color = Obsidian.Hairline, modifier = Modifier.padding(vertical = 10.dp))
                            }
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 5.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (ev.eventType) {
                                                "PET_FAINTED" -> Obsidian.Crimson
                                                "ROAST_PING" -> Obsidian.Amber
                                                else -> Obsidian.Mint
                                            }
                                        )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(ev.message, color = Obsidian.InkSecondary, fontSize = 12.5.sp, lineHeight = 17.sp)
                                    Text(ev.timeLabel, color = Obsidian.InkMuted, fontSize = 10.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}