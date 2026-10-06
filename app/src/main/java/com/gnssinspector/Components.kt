package com.gnssinspector

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Plain-language wording shared by every screen
// ---------------------------------------------------------------------------------------------

object Plain {
    fun system(type: Int) = Labels.constellation(type)

    fun satName(type: Int, svid: Int) = "${system(type)} $svid"

    fun origin(type: Int) = when (type) {
        1 -> "USA · worldwide"
        2 -> "Correction satellites · fixed over the equator"
        3 -> "Russia · worldwide"
        4 -> "Japan · Asia-Pacific"
        5 -> "China · worldwide"
        6 -> "European Union · worldwide"
        7 -> "India · India and surroundings"
        else -> "Unknown"
    }

    /**
     * The kind of orbit a satellite is in, as (short name, description). This is general knowledge
     * about each system, not the satellite's actual orbit, which the chip keeps to itself.
     */
    fun orbitType(type: Int, svid: Int): Pair<String, String> = when (type) {
        1 -> MEO to "About 20,200 km up. Circles Earth twice a day, so it rises and sets."
        3 -> MEO to "About 19,100 km up. Circles Earth about twice a day."
        6 -> MEO to "About 23,222 km up. Circles Earth every 14 hours."
        2 -> GEO
        5 -> when (svid) {
            // BeiDou's published satellite-number assignments by orbit type.
            in BEIDOU_GEO -> GEO
            in BEIDOU_IGSO -> IGSO
            else -> MEO to "About 21,500 km up. Circles Earth about twice a day."
        }
        4 -> if (svid == 199) GEO else IGSO.first to "${IGSO.second} QZSS's loop is centred on Japan and Australia."
        7 -> "Geosynchronous" to "About 35,786 km up. NavIC uses geostationary and inclined geosynchronous satellites that stay over India."
        else -> "Unknown" to ""
    }

    private const val MEO = "Medium Earth orbit"
    private val GEO = "Geostationary" to "About 35,786 km up, over the equator. It turns with the Earth, so it stays fixed in your sky."
    private val IGSO = "Inclined geosynchronous" to
        "About 35,786 km up on a tilted orbit. It stays over one region, tracing a figure-8 in the sky each day."
    private val BEIDOU_GEO = setOf(1, 2, 3, 4, 5, 59, 60, 61, 62, 63)
    private val BEIDOU_IGSO = setOf(6, 7, 8, 9, 10, 13, 16, 31, 38, 39, 40, 56)

    /** 0–4 bars, like a phone's signal icon. */
    fun bars(cn0: Float) = when {
        cn0 >= 40 -> 4
        cn0 >= 32 -> 3
        cn0 >= 25 -> 2
        cn0 > 0 -> 1
        else -> 0
    }

    fun strength(cn0: Float) = when {
        cn0 >= 40 -> "Strong"
        cn0 >= 32 -> "Good"
        cn0 >= 25 -> "Fair"
        cn0 > 0 -> "Weak"
        else -> "Not heard"
    }

    fun direction(az: Float) = listOf("North", "North-east", "East", "South-east", "South", "South-west", "West", "North-west")[
        (((az + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]

    fun shortDirection(az: Float) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[
        (((az + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]

    /** Accuracy as 0–4 meter segments: 4 = within 5 m, 3 = 5–10 m, 2 = 10–25 m, 1 = worse; 0 = unknown. */
    fun accuracyLevel(accuracy: Float?): Int {
        if (accuracy == null) return 0
        // Grade the whole-metre value shown on screen, so "±5 m" always gets four segments.
        val shown = Math.round(accuracy)
        return when {
            shown <= 5 -> 4
            shown <= 10 -> 3
            shown <= 25 -> 2
            else -> 1
        }
    }

    fun latLon(lat: Double, lon: Double) =
        "%.5f° %s, %.5f° %s".format(kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")
}

/** One satellite with all its received bands merged, as people think of it. */
data class SatSummary(val constellation: Int, val svid: Int, val signals: List<Sat>) {
    val name get() = Plain.satName(constellation, svid)
    val best get() = signals.maxBy { it.cn0 }
    val cn0 get() = best.cn0
    val heard get() = cn0 > 0
    val used get() = signals.any { it.usedInFix }
    val elevation get() = best.elevation
    val azimuth get() = best.azimuth
    val bands get() = signals.filter { it.cn0 > 0 }.map { it.band }.distinct()
    val hasPosition get() = !(elevation == 0f && azimuth == 0f)
}

fun summarize(sats: List<Sat>): List<SatSummary> =
    sats.groupBy { it.constellation to it.svid }.map { (k, v) -> SatSummary(k.first, k.second, v) }

// ---------------------------------------------------------------------------------------------
// Help sheet: every ⓘ opens a short explanation instead of printing it inline
// ---------------------------------------------------------------------------------------------

val LocalHelp = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

@Composable
fun InfoButton(title: String, body: String) {
    val help = LocalHelp.current
    Icon(
        Icons.Outlined.Info, contentDescription = "What is $title?",
        modifier = Modifier.clip(CircleShape).clickable { help(title, body) }.padding(4.dp).size(18.dp),
        tint = Night.inkMuted,
    )
}

// ---------------------------------------------------------------------------------------------
// Layout building blocks
// ---------------------------------------------------------------------------------------------

@Composable
fun Screen(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) = LazyColumn(
    modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    content = content,
)

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Night.glass,
        border = BorderStroke(1.dp, Night.glassBorder),
    ) {
        Column(
            Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.padding(top = 12.dp, start = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Night.inkMuted,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun PanelTitle(title: String, subtitle: String? = null, help: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (help != null) InfoButton(title, help)
    }
}

private val numberStyle @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")

/** A label/value line; the explanation lives behind an ⓘ. */
@Composable
fun InfoRow(fact: Fact) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // The ⓘ sits inline with the label so it follows the last word, even when the label wraps.
        val label = buildAnnotatedString {
            append(fact.label)
            if (fact.help != null) { append("\u00A0"); appendInlineContent("info", "ⓘ") }
        }
        val inline = if (fact.help == null) emptyMap() else mapOf(
            "info" to InlineTextContent(Placeholder(26.sp, 26.sp, PlaceholderVerticalAlign.Center)) { InfoButton(fact.label, fact.help) },
        )
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Night.inkSoft, inlineContent = inline)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1.1f), contentAlignment = Alignment.CenterEnd) {
            Text(fact.value, style = numberStyle, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
        }
    }
}

@Composable
fun InfoRows(facts: List<Fact>) = facts.forEachIndexed { i, f ->
    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    InfoRow(f)
}

@Composable
fun StatTile(
    label: String,
    value: String,
    sub: String?,
    modifier: Modifier = Modifier,
    help: String? = null,
    extra: @Composable (() -> Unit)? = null,
    below: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Night.glass,
        border = BorderStroke(1.dp, Night.glassBorder),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.3.sp), color = Night.inkSoft,
                    maxLines = 1, softWrap = false, modifier = Modifier.weight(1f),
                )
                if (help != null) InfoButton(label, help)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
                if (extra != null) { Spacer(Modifier.width(8.dp)); extra() }
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (below != null) { Spacer(Modifier.height(6.dp)); below() }
        }
    }
}

/**
 * Horizontal 4-segment accuracy meter running from a fuzzy circle (rough) to a crosshair
 * (pinpoint). Deliberately not vertical bars, which mean signal strength elsewhere in the app.
 */
@Composable
fun AccuracyMeter(accuracy: Float?, modifier: Modifier = Modifier) {
    val level by animateFloatAsState(Plain.accuracyLevel(accuracy).toFloat(), tween(600), label = "accuracy")
    val off = Color.White.copy(alpha = 0.14f)
    Row(
        modifier.fillMaxWidth().semantics { contentDescription = "Accuracy ${Plain.accuracyLevel(accuracy)} of 4" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(Modifier.size(14.dp)) {
            // Fuzzy: a soft disc inside a dashed ring.
            drawCircle(Brush.radialGradient(listOf(Night.inkMuted.copy(alpha = 0.6f), Color.Transparent)), size.minDimension / 2)
            drawCircle(
                Night.inkMuted, size.minDimension / 2 - 1.dp.toPx(),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
            )
        }
        for (i in 0 until 4) {
            val fill = (level - i).coerceIn(0f, 1f)
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(lerp(off, Night.ice, fill)))
        }
        Canvas(Modifier.size(14.dp)) {
            // Pinpoint: a small dot with crosshair ticks.
            val c = center
            val r = size.minDimension / 2
            val tick = 3.dp.toPx()
            val w = 1.5.dp.toPx()
            drawCircle(Night.ice, 2.dp.toPx(), c)
            drawLine(Night.ice, Offset(c.x, 0f), Offset(c.x, tick), w)
            drawLine(Night.ice, Offset(c.x, 2 * r - tick), Offset(c.x, 2 * r), w)
            drawLine(Night.ice, Offset(0f, c.y), Offset(tick, c.y), w)
            drawLine(Night.ice, Offset(2 * r - tick, c.y), Offset(2 * r, c.y), w)
        }
    }
}

@Composable
fun SignalBars(cn0: Float, modifier: Modifier = Modifier) {
    val level = Plain.bars(cn0)
    val on = Night.ice
    val off = Color.White.copy(alpha = 0.14f)
    Row(modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(Modifier.width(4.dp).fillMaxHeight(0.25f + 0.1875f * i).clip(RoundedCornerShape(1.dp)).background(if (i <= level) on else off))
        }
    }
}

@Composable
fun SystemDot(type: Int, size: Int = 10, hollow: Boolean = false) {
    val c = systemColor(type)
    Canvas(Modifier.size(size.dp)) {
        val r = this.size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), center, r * 2.2f), r * 2.2f)
        if (hollow) drawCircle(c, r - 1.dp.toPx(), style = Stroke(2.dp.toPx())) else drawCircle(c, r)
    }
}

@Composable
fun Pill(text: String) {
    Surface(shape = RoundedCornerShape(50), color = Night.glassStrong, border = BorderStroke(1.dp, Night.glassBorder)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** A row in a list that leads somewhere else. */
@Composable
fun LinkRow(title: String, subtitle: String?, trailing: String? = null, leading: @Composable (() -> Unit)? = null, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ExpandablePanel(
    title: String,
    subtitle: String?,
    key: String,
    startExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(startExpanded) }
    Panel(Modifier.animateContentSize(), onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (expanded) "Collapse" else "Expand")
        }
        if (expanded) content()
    }
}

@Composable
fun Hint(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Night.ice.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, Night.ice.copy(alpha = 0.16f)),
    ) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyMedium, color = Night.inkSoft)
    }
}

@Composable
fun Mono(text: String) = Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono), color = Night.inkSoft)

/** Filter chips for satellite systems; null = all. */
@Composable
fun SystemFilter(systems: List<Int>, selected: Int?, onSelect: (Int?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
        systems.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(if (selected == type) null else type) },
                label = { Text(Plain.system(type)) },
                leadingIcon = { SystemDot(type, 8) },
            )
        }
    }
}

@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    return now
}

val captionStyle: TextStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(color = Night.inkSoft)
