package com.doomshield.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun DoomPetAvatar(
    stage: PetStage,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "pet_anim")

    val floatPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (stage) {
                    PetStage.CHILL -> 2800
                    PetStage.SWEATING -> 1100
                    PetStage.SICK -> 3400
                    PetStage.FAINTED -> 4200
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "float_phase"
    )

    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweat_pulse"
    )

    // Refined studio tones (desaturated, calm, non-neon)
    val primaryColor by animateColorAsState(
        targetValue = when (stage) {
            PetStage.CHILL -> Color(0xFF34D399)    // Soft Studio Mint
            PetStage.SWEATING -> Color(0xFFF59E0B) // Warm Amber
            PetStage.SICK -> Color(0xFFA1A1AA)     // Muted Zinc Silver
            PetStage.FAINTED -> Color(0xFFEF4444)  // Matte Crimson
        },
        animationSpec = tween(550),
        label = "pet_color"
    )

    val glowColor by animateColorAsState(
        targetValue = primaryColor.copy(alpha = 0.16f),
        animationSpec = tween(550),
        label = "glow_color"
    )

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bobY = sin(floatPhase) * (h * 0.035f)
        val jitterX = if (stage == PetStage.SWEATING) sin(floatPhase * 4f) * (w * 0.015f) else 0f

        val cx = w / 2f + jitterX
        val cy = h / 2f + bobY

        // Subtle ambient OLED halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor, Color.Transparent),
                center = Offset(cx, cy),
                radius = w * 0.50f
            ),
            radius = w * 0.50f,
            center = Offset(cx, cy)
        )

        val bodyW = w * 0.62f
        val bodyH = if (stage == PetStage.FAINTED) h * 0.46f else h * 0.56f
        val left = cx - bodyW / 2f
        val top = cy - bodyH / 2f

        val earPath = Path().apply {
            moveTo(left + bodyW * 0.14f, top + bodyH * 0.16f)
            lineTo(left + bodyW * 0.04f, top - bodyH * 0.16f)
            lineTo(left + bodyW * 0.34f, top + bodyH * 0.04f)
            close()

            moveTo(left + bodyW * 0.86f, top + bodyH * 0.16f)
            lineTo(left + bodyW * 0.96f, top - bodyH * 0.16f)
            lineTo(left + bodyW * 0.66f, top + bodyH * 0.04f)
            close()
        }
        drawPath(earPath, color = primaryColor)

        // Main matte rounded silhouette
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(bodyW * 0.44f, bodyW * 0.44f)
        )

        // Pitch-black visor
        val visorW = bodyW * 0.78f
        val visorH = bodyH * 0.56f
        drawRoundRect(
            color = Color(0xFF09090B),
            topLeft = Offset(cx - visorW / 2f, cy - visorH * 0.46f),
            size = Size(visorW, visorH),
            cornerRadius = CornerRadius(visorH * 0.45f, visorH * 0.45f)
        )

        val eyeY = cy - visorH * 0.08f
        val leftEyeX = cx - visorW * 0.24f
        val rightEyeX = cx + visorW * 0.24f
        val eyeRadius = w * 0.052f

        when (stage) {
            PetStage.CHILL -> {
                drawCircle(primaryColor, radius = eyeRadius, center = Offset(leftEyeX, eyeY))
                drawCircle(primaryColor, radius = eyeRadius, center = Offset(rightEyeX, eyeY))
                drawArc(
                    color = primaryColor,
                    startAngle = 22f,
                    sweepAngle = 136f,
                    useCenter = false,
                    topLeft = Offset(cx - w * 0.085f, eyeY + eyeRadius * 0.45f),
                    size = Size(w * 0.17f, h * 0.095f),
                    style = Stroke(width = w * 0.032f, cap = StrokeCap.Round)
                )
            }

            PetStage.SWEATING -> {
                drawCircle(Color(0xFFF5F5F7), radius = eyeRadius * 1.12f, center = Offset(leftEyeX, eyeY))
                drawCircle(Color(0xFFF5F5F7), radius = eyeRadius * 1.12f, center = Offset(rightEyeX, eyeY))
                drawCircle(Color(0xFF09090B), radius = eyeRadius * 0.48f, center = Offset(leftEyeX, eyeY))
                drawCircle(Color(0xFF09090B), radius = eyeRadius * 0.48f, center = Offset(rightEyeX, eyeY))

                drawLine(
                    color = primaryColor,
                    start = Offset(cx - w * 0.085f, eyeY + h * 0.105f),
                    end = Offset(cx + w * 0.085f, eyeY + h * 0.09f),
                    strokeWidth = w * 0.032f,
                    cap = StrokeCap.Round
                )

                val dropY = top + (pulse * bodyH * 0.55f)
                drawCircle(
                    color = Color(0xFF93C5FD).copy(alpha = 0.9f - pulse * 0.65f),
                    radius = w * 0.04f,
                    center = Offset(left + bodyW * 0.94f, dropY)
                )
            }

            PetStage.SICK -> {
                drawLine(
                    color = primaryColor,
                    start = Offset(leftEyeX - eyeRadius, eyeY),
                    end = Offset(leftEyeX + eyeRadius, eyeY + eyeRadius * 0.3f),
                    strokeWidth = w * 0.038f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = primaryColor,
                    start = Offset(rightEyeX - eyeRadius, eyeY + eyeRadius * 0.3f),
                    end = Offset(rightEyeX + eyeRadius, eyeY),
                    strokeWidth = w * 0.038f,
                    cap = StrokeCap.Round
                )
                drawArc(
                    color = primaryColor,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(cx - w * 0.085f, eyeY + h * 0.08f),
                    size = Size(w * 0.17f, h * 0.095f),
                    style = Stroke(width = w * 0.032f, cap = StrokeCap.Round)
                )
            }

            PetStage.FAINTED -> {
                val s = eyeRadius * 0.92f
                val stroke = w * 0.034f
                drawLine(primaryColor, Offset(leftEyeX - s, eyeY - s), Offset(leftEyeX + s, eyeY + s), stroke, StrokeCap.Round)
                drawLine(primaryColor, Offset(leftEyeX + s, eyeY - s), Offset(leftEyeX - s, eyeY + s), stroke, StrokeCap.Round)
                drawLine(primaryColor, Offset(rightEyeX - s, eyeY - s), Offset(rightEyeX + s, eyeY + s), stroke, StrokeCap.Round)
                drawLine(primaryColor, Offset(rightEyeX + s, eyeY - s), Offset(rightEyeX - s, eyeY + s), stroke, StrokeCap.Round)
                drawLine(
                    color = primaryColor,
                    start = Offset(cx - w * 0.095f, eyeY + h * 0.10f),
                    end = Offset(cx + w * 0.095f, eyeY + h * 0.10f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}