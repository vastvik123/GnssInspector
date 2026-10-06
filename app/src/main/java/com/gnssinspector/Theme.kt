package com.gnssinspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/** The night-sky palette. Colour is reserved for satellite systems; chrome stays in ice tones. */
object Night {
    val skyTop = Color(0xFF050A18)
    val skyBottom = Color(0xFF0C1934)
    val glass = Color.White.copy(alpha = 0.05f)
    val glassStrong = Color.White.copy(alpha = 0.09f)
    val glassBorder = Color.White.copy(alpha = 0.09f)
    val hairline = Color.White.copy(alpha = 0.07f)
    val ink = Color(0xFFEAF0FF)
    val inkSoft = Color(0xFFA9B6D3)
    val inkMuted = Color(0xFF6B7A9C)
    val ice = Color(0xFFCFE0FF)
    val sheet = Color(0xFF0F1B36)
}

// IBM Plex throughout: Sans for all interface text, Mono for raw data (NMEA, hex).
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun plexSans(weight: Int) = Font(
    R.font.plex_sans, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val PlexSans = FontFamily(plexSans(400), plexSans(500), plexSans(600), plexSans(700))
val PlexMono = FontFamily(Font(R.font.plex_mono_regular, FontWeight.Normal), Font(R.font.plex_mono_medium, FontWeight.Medium))

private val scheme = darkColorScheme(
    primary = Night.ice,
    onPrimary = Night.skyTop,
    primaryContainer = Night.glassStrong,
    onPrimaryContainer = Night.ink,
    secondaryContainer = Color.White.copy(alpha = 0.12f),
    onSecondaryContainer = Night.ink,
    background = Night.skyTop,
    onBackground = Night.ink,
    surface = Night.skyTop,
    onSurface = Night.ink,
    surfaceVariant = Night.glassStrong,
    onSurfaceVariant = Night.inkSoft,
    surfaceContainerLowest = Night.glass,
    surfaceContainerLow = Night.glass,
    surfaceContainer = Night.glassStrong,
    surfaceContainerHigh = Night.glassStrong,
    surfaceContainerHighest = Color.White.copy(alpha = 0.12f),
    outline = Color.White.copy(alpha = 0.22f),
    outlineVariant = Night.hairline,
)

/** Every Material text style in Plex Sans, so no screen falls back to the system font. */
private fun plexTypography(): Typography {
    val t = Typography()
    fun TextStyle.p(weight: FontWeight) = copy(fontFamily = PlexSans, fontWeight = weight)
    return t.copy(
        displayLarge = t.displayLarge.p(FontWeight.SemiBold),
        displayMedium = t.displayMedium.p(FontWeight.SemiBold),
        displaySmall = t.displaySmall.p(FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.p(FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.p(FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.p(FontWeight.SemiBold),
        titleLarge = t.titleLarge.p(FontWeight.SemiBold),
        titleMedium = t.titleMedium.p(FontWeight.SemiBold),
        titleSmall = t.titleSmall.p(FontWeight.Medium),
        bodyLarge = t.bodyLarge.p(FontWeight.Normal),
        bodyMedium = t.bodyMedium.p(FontWeight.Normal),
        bodySmall = t.bodySmall.p(FontWeight.Normal),
        labelLarge = t.labelLarge.p(FontWeight.Medium),
        labelMedium = t.labelMedium.p(FontWeight.Medium).copy(letterSpacing = 0.8.sp),
        labelSmall = t.labelSmall.p(FontWeight.Medium),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = plexTypography(), content = content)
}

/** Deep-navy gradient with a fixed, faint starfield. */
@Composable
fun NightSky(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val stars = remember {
        val r = Random(7)
        List(140) { Triple(Offset(r.nextFloat(), r.nextFloat()), 0.4f + r.nextFloat() * 1.1f, 0.08f + r.nextFloat() * 0.35f) }
    }
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Night.skyTop, Night.skyBottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            for ((p, radius, alpha) in stars) {
                drawCircle(Color.White.copy(alpha = alpha), radius * density, Offset(p.x * size.width, p.y * size.height))
            }
        }
        CompositionLocalProvider(LocalContentColor provides Night.ink) { content() }
    }
}

// Categorical slots 1–7 of the validated reference palette (dark-mode steps), in a fixed order.
private val SystemColors = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF2FA82F, 0xFF9085E9)

private fun systemSlot(type: Int) = when (type) {
    1 -> 0 // GPS
    6 -> 1 // Galileo
    5 -> 2 // BeiDou
    3 -> 3 // GLONASS
    4 -> 4 // QZSS
    7 -> 5 // NavIC
    2 -> 6 // SBAS
    else -> -1
}

fun systemColor(type: Int): Color {
    val slot = systemSlot(type)
    return if (slot < 0) Night.inkMuted else Color(SystemColors[slot])
}
