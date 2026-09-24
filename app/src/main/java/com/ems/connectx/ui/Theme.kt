package com.ems.connectx.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ems.connectx.R

// =========================================================================
// MOBBIN-INSPIRED MODERN LIGHT THEME PALETTE
// Pure White (#FFFFFF), Azure Blue rgb(35, 131, 226), Pearl & Slate Accents
// =========================================================================

// Backgrounds & Canvas
val PureWhite = Color(0xFFFFFFFF)
val PearlBg = Color(0xFFF8FAFC)        // Slate-50 / Pearl
val PearlCard = Color(0xFFFFFFFF)      // Crisp white card surface
val PearlSurface = Color(0xFFF1F5F9)   // Slate-100 / Pearl container
val PearlElevated = Color(0xFFE2E8F0)  // Slate-200 / Subtle elevation

// Primary Brand Accent: rgb(35, 131, 226) -> #2383E2
val PrimaryBlue = Color(35, 131, 226)  // 0xFF2383E2
val PrimaryDark = Color(0xFF1B6EC2)
val PrimaryLight = Color(0xFFE0F2FE)
val PrimarySubtle = Color(0xFFEFF6FF)

// Borders & Dividers
val BorderSubtle = Color(0xFFE2E8F0)   // Light subtle border
val BorderMedium = Color(0xFFCBD5E1)   // Slate-300
val BorderHighlight = PrimaryBlue

// Text Hierarchy (Deep Slate for maximum legibility)
val TextPrimary = Color(0xFF0F172A)     // Slate-900 (High contrast)
val TextSecondary = Color(0xFF475569)   // Slate-600
val TextMuted = Color(0xFF94A3B8)       // Slate-400
val TextInverted = Color(0xFFFFFFFF)

// Functional Semantic Accents (Modern Crisp Tones)
val AccentEmerald = Color(0xFF059669)   // Green-600
val AccentEmeraldBg = Color(0xFFECFDF5) // Green-50
val AccentEmeraldBorder = Color(0xFFA7F3D0)

val AccentAmber = Color(0xFFD97706)     // Amber-600
val AccentAmberBg = Color(0xFFFFFBEB)   // Amber-50
val AccentAmberBorder = Color(0xFFFDE68A)

val AccentRose = Color(0xFFDC2626)      // Red-600
val AccentRoseBg = Color(0xFFFEF2F2)    // Red-50
val AccentRoseBorder = Color(0xFFFECACA)
val RoseText = AccentRose

val AccentPurple = Color(0xFF7C3AED)    // Violet-600
val AccentPurpleBg = Color(0xFFF5F3FF)  // Violet-50
val AccentPurpleBorder = Color(0xFFDDD6FE)

val AccentCyan = Color(0xFF0891B2)      // Cyan-600
val AccentCyanBg = Color(0xFFECFEFF)    // Cyan-50
val AccentCyanBorder = Color(0xFFA5F3FC)

val AccentSky = PrimaryBlue

// Backwards-compatible aliases for legacy imports
val CanvasDark = PureWhite
val SurfaceDark = PearlBg
val SurfaceCard = PearlCard
val SurfaceCardStrong = PearlSurface
val SurfaceElevated = PearlElevated
val Navy = PrimaryBlue
val Royal = PrimaryDark
val Blue = PrimaryBlue
val Ice = PrimaryBlue
val Ink = TextPrimary
val Mute = TextSecondary
val Danger = AccentRose

val BrandGradient = Brush.horizontalGradient(
    colors = listOf(PrimaryBlue, Color(0xFF3B82F6))
)

private val AppColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    secondary = PrimaryBlue,
    onSecondary = Color.White,
    tertiary = AccentPurple,
    background = PureWhite,
    onBackground = TextPrimary,
    surface = PureWhite,
    onSurface = TextPrimary,
    surfaceVariant = PearlBg,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    outlineVariant = BorderMedium,
    error = AccentRose,
    onError = Color.White
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
        color = TextPrimary,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp,
        color = TextPrimary
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = TextSecondary
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextMuted
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        color = TextSecondary
    )
)

@Composable
fun ConnectXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content
    )
}

/**
 * Clean modern screen container for Light Theme.
 * Uses pure white background with optimal contrast.
 */
@Composable
fun ScreenBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
    ) {
        content()
    }
}

/**
 * Clean modern card component for Light Mode with subtle border and crisp elevation.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color = PureWhite,
    borderColor: Color = BorderSubtle,
    borderWidth: Dp = 1.dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val bg = when {
        selected -> PrimarySubtle
        else -> containerColor
    }
    val stroke = when {
        selected -> PrimaryBlue
        else -> borderColor
    }
    val m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(bg)
        .border(borderWidth, stroke, shape)

    if (onClick != null) {
        Box(modifier = m.clickable(onClick = onClick)) {
            content()
        }
    } else {
        Box(modifier = m) {
            content()
        }
    }
}

// Backwards-compatible alias for existing code references
@Composable
fun FrostCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    strong: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    AppCard(
        modifier = modifier,
        shape = shape,
        containerColor = if (strong) PearlSurface else PureWhite,
        borderColor = if (selected) PrimaryBlue else if (strong) BorderMedium else BorderSubtle,
        selected = selected,
        onClick = onClick,
        content = content
    )
}

@Composable
fun modernFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = PrimaryBlue,
    focusedBorderColor = PrimaryBlue,
    unfocusedBorderColor = BorderSubtle,
    focusedLabelColor = PrimaryBlue,
    unfocusedLabelColor = TextSecondary,
    focusedPlaceholderColor = TextMuted,
    unfocusedPlaceholderColor = TextMuted,
    focusedContainerColor = PearlBg,
    unfocusedContainerColor = PearlBg
)

@Composable
fun frostFieldColors(): TextFieldColors = modernFieldColors()

@Composable
fun BrandMark(size: Dp = 64.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher),
        contentDescription = "ConnectX Gateway",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .border(1.dp, BorderSubtle, RoundedCornerShape(size * 0.22f))
    )
}

/**
 * Modern light shimmer skeleton loader for content loading.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp)
) {
    val shimmerColors = listOf(
        Color(0xFFE2E8F0),
        Color(0xFFF1F5F9),
        Color(0xFFE2E8F0)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim)
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
    )
}

/**
 * Best-in-class animated loader with primary azure blue gradient.
 */
@Composable
fun ConnectXLoader(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    strokeWidth: Dp = 3.5.dp,
    color: Color = PrimaryBlue
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(size),
            color = color,
            trackColor = PrimarySubtle,
            strokeWidth = strokeWidth,
            strokeCap = StrokeCap.Round
        )
    }
}

/**
 * Modern pill badge for status (Sent, Queued, Failed, Online, Up to date, etc.) in Light Theme
 */
@Composable
fun StatusPill(
    text: String,
    customColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val (bg, fg, border) = if (customColor != null) {
        Triple(
            customColor.copy(alpha = 0.12f),
            customColor,
            customColor.copy(alpha = 0.35f)
        )
    } else {
        when (text.lowercase()) {
            "sent", "connected", "active", "online", "success", "up to date" -> Triple(
                AccentEmeraldBg,
                AccentEmerald,
                AccentEmeraldBorder
            )
            "failed", "error", "revoked", "cancelled", "suspended", "update required" -> Triple(
                AccentRoseBg,
                AccentRose,
                AccentRoseBorder
            )
            "queued", "pending", "sending", "waiting", "update available" -> Triple(
                PrimarySubtle,
                PrimaryBlue,
                Color(0xFFBFDBFE)
            )
            else -> Triple(
                PearlSurface,
                TextSecondary,
                BorderSubtle
            )
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg
            )
        }
    }
}

/**
 * Message Type Badge with clean light styling & distinctive category colors
 */
@Composable
fun MessageTypeBadge(
    formattedType: String,
    modifier: Modifier = Modifier
) {
    val (color, bg, border) = when {
        formattedType.contains("Sale", ignoreCase = true) -> Triple(AccentEmerald, AccentEmeraldBg, AccentEmeraldBorder)
        formattedType.contains("Due", ignoreCase = true) -> Triple(AccentAmber, AccentAmberBg, AccentAmberBorder)
        formattedType.contains("Exchange", ignoreCase = true) -> Triple(AccentPurple, AccentPurpleBg, AccentPurpleBorder)
        formattedType.contains("Return", ignoreCase = true) || formattedType.contains("Refund", ignoreCase = true) -> Triple(AccentCyan, AccentCyanBg, AccentCyanBorder)
        formattedType.contains("Payment", ignoreCase = true) -> Triple(PrimaryBlue, PrimarySubtle, Color(0xFFBFDBFE))
        formattedType.contains("Test", ignoreCase = true) -> Triple(TextSecondary, PearlSurface, BorderSubtle)
        else -> Triple(PrimaryBlue, PrimarySubtle, Color(0xFFBFDBFE))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = formattedType,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
