package com.gnssinspector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var gnss: GnssCollector
    private var granted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermission()
        if (granted) gnss.start()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gnss = GnssCollector(this)
        granted = hasPermission()
        setContent {
            AppTheme {
                NightSky {
                    if (granted) App(gnss)
                    else PermissionScreen {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        granted = hasPermission()
        if (granted) gnss.start()
    }

    override fun onStop() {
        super.onStop()
        gnss.stop()
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("GNSS Inspector", style = MaterialTheme.typography.headlineLarge)
        Text(
            color = Night.inkSoft,
            text = 
            "See the satellites your phone is listening to, and everything it learns from them.\n\n" +
                "Android only shares satellite data with apps that have precise location access. Nothing leaves your phone.",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("Allow precise location") }
    }
}

private val SkyIcon = ImageVector.Builder("Sky", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
        moveTo(12f, 2f); arcToRelative(10f, 10f, 0f, true, true, 0f, 20f); arcToRelative(10f, 10f, 0f, true, true, 0f, -20f); close()
        moveTo(12f, 7f); arcToRelative(5f, 5f, 0f, true, true, 0f, 10f); arcToRelative(5f, 5f, 0f, true, true, 0f, -10f); close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(17f, 4.5f); arcToRelative(2f, 2f, 0f, true, true, 0f, 4f); arcToRelative(2f, 2f, 0f, true, true, 0f, -4f); close()
    }
}.build()

private data class Tab(val title: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("GNSS Inspector", "Overview", Icons.Filled.Home),
    Tab("Sky view", "Sky", SkyIcon),
    Tab("Satellites", "Satellites", Icons.AutoMirrored.Filled.List),
    Tab("Advanced", "Advanced", Icons.Filled.Build),
)

private fun routeTitle(route: String) = when {
    route.startsWith("sat/") -> route.split('/').let { Plain.satName(it[1].toInt(), it[2].toInt()) }
    route == "adv/position" -> "Position details"
    route == "adv/raw" -> "Raw measurements"
    route == "adv/nav" -> "Navigation messages"
    route == "adv/nmea" -> "NMEA sentences"
    route == "adv/device" -> "Chip & capabilities"
    route == "learn" -> "How it works"
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(g: GnssCollector) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stack by rememberSaveable { mutableStateOf("") } // pushed pages, '|'-separated
    var filter by rememberSaveable { mutableStateOf<Int?>(null) }
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }

    val routes = stack.split('|').filter { it.isNotEmpty() }
    val route = routes.lastOrNull()
    val open: (String) -> Unit = { r ->
        if (r == "sky") { tab = 1; stack = "" } else stack = (routes + r).joinToString("|")
    }
    val openSat: (Int, Int) -> Unit = { c, s -> open("sat/$c/$s") }
    BackHandler(enabled = route != null) { stack = routes.dropLast(1).joinToString("|") }
    BackHandler(enabled = route == null && tab != 0) { tab = 0 }

    CompositionLocalProvider(LocalHelp provides { title, body -> help = title to body }) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        if (route == null && tab == 0) {
                            // The home screen carries the brand: logo mark beside the app name.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(tabs[tab].title, style = MaterialTheme.typography.headlineSmall)
                            }
                        } else {
                            Text(route?.let(::routeTitle) ?: tabs[tab].title, style = MaterialTheme.typography.headlineSmall)
                        }
                    },
                    navigationIcon = {
                        if (route != null) {
                            IconButton(onClick = { stack = routes.dropLast(1).joinToString("|") }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            bottomBar = {
                if (route == null) {
                    NavigationBar(
                        containerColor = Night.skyBottom.copy(alpha = 0.92f),
                        modifier = Modifier.drawBehind { drawLine(Night.glassBorder, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
                    ) {
                        tabs.forEachIndexed { i, t ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Night.ice.copy(alpha = 0.14f),
                                    selectedIconColor = Night.ink, selectedTextColor = Night.ink,
                                    unselectedIconColor = Night.inkMuted, unselectedTextColor = Night.inkMuted,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val m = Modifier.padding(padding)
            when {
                route == null -> when (tab) {
                    0 -> OverviewScreen(g, openSystem = { filter = it; tab = 2 }, open = open, modifier = m)
                    1 -> SkyScreen(g, filter, { filter = it }, openSat, m)
                    2 -> SatellitesScreen(g, filter, { filter = it }, openSat, m)
                    else -> AdvancedScreen(g, open, m)
                }
                route.startsWith("sat/") -> route.split('/').let { SatelliteDetailScreen(g, it[1].toInt(), it[2].toInt(), m) }
                route == "adv/position" -> PositionScreen(g, m)
                route == "adv/raw" -> RawScreen(g, openSat, m)
                route == "adv/nav" -> NavScreen(g, m)
                route == "adv/nmea" -> NmeaScreen(g, m)
                route == "adv/device" -> DeviceScreen(g, m)
                route == "learn" -> LearnScreen(m)
            }
        }

        help?.let { (title, body) ->
            ModalBottomSheet(onDismissRequest = { help = null }, containerColor = Night.sheet) {
                Column(
                    Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = Night.inkSoft)
                }
            }
        }
    }
}
