package com.ems.connectx.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ems.connectx.R

val Navy = Color(0xFF071428)
val Royal = Color(0xFF1D4ED8)
val Blue = Color(0xFF2563EB)
val Ice = Color(0xFF7DD3FC)
val Ink = Color(0xFFF4F7FF)
val Mute = Color(0xB3E8EEFF)
val Paper = Color(0xFF0B1B3A)
val Card = Color(0x29FFFFFF)
val Danger = Color(0xFFF87171)
val Line = Color(0x33FFFFFF)

@Deprecated("Use Navy / Blue", ReplaceWith("Navy"))
val Forest = Navy
@Deprecated("Use Blue", ReplaceWith("Blue"))
val Teal = Blue
@Deprecated("Use Ice", ReplaceWith("Ice"))
val TealBright = Ice

val BlueGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF071428), Color(0xFF102A62), Color(0xFF1D4ED8), Color(0xFF38BDF8))
)

private val colors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Ice,
    background = Navy,
    surface = Color(0xFF102044),
    onBackground = Ink,
    onSurface = Ink,
    error = Danger,
    outline = Line
)

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, color = Ink),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, color = Ink),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Ink),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Mute),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
)

@Composable
fun ConnectXTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}

@Composable
fun ScreenBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(BlueGradient)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 48.dp, y = (-72).dp)
                .size(300.dp)
                .clip(CircleShape)
                .background(Color(0xFF60A5FA).copy(alpha = 0.55f))
                .blur(88.dp)
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-90).dp, y = 40.dp)
                .size(240.dp)
                .clip(CircleShape)
                .background(Color(0xFF2563EB).copy(alpha = 0.45f))
                .blur(80.dp)
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 40.dp, y = 60.dp)
                .size(260.dp)
                .clip(CircleShape)
                .background(Color(0xFF38BDF8).copy(alpha = 0.40f))
                .blur(92.dp)
        )
        content()
    }
}

@Composable
fun FrostCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    strong: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val fill = when {
        selected -> Color(0xFF1D4ED8).copy(alpha = 0.55f)
        strong -> Color.White.copy(alpha = 0.20f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val stroke = Color.White.copy(alpha = if (selected || strong) 0.48f else 0.30f)
    val m = modifier.fillMaxWidth().border(1.dp, stroke, shape)
    val body = @Composable { Box(Modifier.fillMaxWidth()) { content() } }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = m, shape = shape, color = fill, shadowElevation = 0.dp, tonalElevation = 0.dp, content = body)
    } else {
        Surface(modifier = m, shape = shape, color = fill, shadowElevation = 0.dp, tonalElevation = 0.dp, content = body)
    }
}

@Composable
fun frostFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Ice,
    focusedBorderColor = Ice,
    unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
    focusedLabelColor = Ice,
    unfocusedLabelColor = Mute,
    focusedPlaceholderColor = Mute,
    unfocusedPlaceholderColor = Mute,
    focusedContainerColor = Color.White.copy(alpha = 0.10f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.07f)
)

@Composable
fun BrandMark(size: Dp = 72.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher),
        contentDescription = "ConnectX",
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.22f))
    )
}
