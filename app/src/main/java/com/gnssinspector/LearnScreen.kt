package com.gnssinspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val steps = listOf(
    "Satellites shout the time" to
        "Each satellite carries atomic clocks and constantly broadcasts \"this is satellite 5, the time is exactly …, and here is my orbit\".",
    "Your phone listens" to
        "The signals are incredibly faint, weaker than the background noise. The phone's GNSS chip digs them out because it knows each " +
        "satellite's unique code. The Signal bars show how clearly each one comes through.",
    "Time becomes distance" to
        "A signal takes about 70 milliseconds to reach you. Light travels 300 km per millisecond, so the delay tells the phone how far " +
        "away each satellite is.",
    "Four distances pin you down" to
        "Knowing your distance from three satellites narrows you down to one point. A fourth is needed because the phone's own clock isn't " +
        "accurate enough, so it solves for the clock error too. More satellites, spread across the sky, give a more accurate position.",
    "Many systems, one receiver" to
        "GPS is only one of several systems. Your phone listens to all of them at once, which is why it can hear dozens of satellites.",
)

@Composable
fun LearnScreen(modifier: Modifier) {
    Screen(modifier) {
        item {
            Text("How satellite positioning works", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        steps.forEachIndexed { i, (title, body) ->
            item {
                Panel {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item { SectionLabel("The systems, and their colours in this app") }
        item {
            Panel {
                listOf(1, 6, 5, 3, 4, 7, 2).forEach { type ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SystemDot(type, 12)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(Plain.system(type), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(Plain.origin(type), style = captionStyle)
                        }
                    }
                }
            }
        }
        item { SectionLabel("Reading this app") }
        item {
            Panel {
                InfoRows(
                    listOf(
                        Fact("Height", "0° to 90°", "How high the satellite is: 0° on the horizon, 90° straight overhead."),
                        Fact("Direction", "N, NE, E…", "The compass direction to look toward."),
                        Fact("Signal bars", "1 to 4", "How clearly the signal comes through. Open sky gives 3–4 bars; indoors usually 1."),
                        Fact("In use", "label", "The satellite is being used to compute your position right now."),
                        Fact("ⓘ", "tap it", "Every ⓘ in the app explains the term next to it."),
                    )
                )
            }
        }
    }
}
