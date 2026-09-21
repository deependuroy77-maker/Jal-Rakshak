package `in`.jalsense.monitoring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject
import org.json.JSONArray

// ─────────────────────────────────────────────
// Colour palette (unchanged)
// ─────────────────────────────────────────────
private val Ink     = Color(0xFF172322)
private val Teal    = Color(0xFF0B6B63)
private val Surface = Color(0xFFF7FAF9)
private val Line    = Color(0xFFDCE5E2)
private val Warning = Color(0xFF9A5A00)
private val Danger  = Color(0xFFB42318)
private val FaultBg = Color(0xFFFFEDED)

// ─────────────────────────────────────────────
// Enums (unchanged + preserved)
// ─────────────────────────────────────────────
enum class Screen(val label: String) {
    HOME("Home"), DASHBOARD("Dashboard"), ALERTS("Alerts"),
    DEVICES("Devices"), HISTORY("History"), SETTINGS("Settings")
}
enum class SensorState { NORMAL, WARNING, CRITICAL, OFFLINE, INVALID, CALIBRATION_REQUIRED }
enum class Severity   { INFO, WARNING, CRITICAL }
enum class AlertState { TRIGGERED, ACKNOWLEDGED, RESOLVED }

// ─────────────────────────────────────────────
// Legacy Sensor model (kept for Devices screen / backward compat)
// ─────────────────────────────────────────────
data class Sensor(
    val name: String,
    val id: String,
    val value: String,
    val unit: String,
    val state: SensorState,
    val updated: String,
    val quality: String
)

data class Alert(
    val id: String,
    val time: String,
    val device: String,
    val event: String,
    val severity: Severity,
    val state: AlertState
)

// ─────────────────────────────────────────────
// NEW: Sensor value semantics — NEVER coerce FAULT/MISSING to 0
// ─────────────────────────────────────────────
sealed class SensorValue {
    /** A valid numeric reading */
    data class Valid(val raw: String, val unit: String) : SensorValue()
    /** Transmitter explicitly reported FAULT */
    object Fault : SensorValue()
    /** Field was absent / null in the response */
    object Missing : SensorValue()
}

/** Returns a display string, never "0" for fault/missing */
fun SensorValue.displayText(): String = when (this) {
    is SensorValue.Valid   -> "${this.raw} ${this.unit}".trim()
    is SensorValue.Fault   -> "FAULT"
    is SensorValue.Missing -> "—"
}

fun SensorValue.toSensorState(): SensorState = when (this) {
    is SensorValue.Valid   -> SensorState.NORMAL
    is SensorValue.Fault   -> SensorState.CRITICAL
    is SensorValue.Missing -> SensorState.OFFLINE
}

// ─────────────────────────────────────────────
// NEW: Structured reading models
// ─────────────────────────────────────────────

/** Parsed from INPUT packet: P:<n>,PH:...,TDS:...,TURB:...,TEMP:...,RELAY:... */
data class InputReading(
    val packetNumber: Int?,       // P field
    val ph: SensorValue,
    val tds: SensorValue,
    val turbidity: SensorValue,
    val temperature: SensorValue,
    val relay: String             // "ON" / "OFF" / "—"
)

/** Parsed from OUTPUT packet: OUT:<n>,PH:...,TDS:...,TURB:...,RELAY:... */
data class OutputReading(
    val packetNumber: Int?,       // OUT field
    val ph: SensorValue,
    val tds: SensorValue,
    val turbidity: SensorValue,
    val relay: String
)

/** Parsed from OUTLEVEL packet: OUTLEVEL:<n>,LEVEL:...,DIST:...,RELAY:... */
data class OutputLevel(
    val packetNumber: Int?,
    val level: SensorValue,       // percentage
    val distance: SensorValue,    // cm
    val relay: String,
    val receivedAtMs: Long,       // System.currentTimeMillis() when received
    val freshFromServer: Boolean = true   // ESP32's own freshness flag
)

/** Active sensor fault from ALERT:... packet */
data class ActiveFault(
    val sensorKey: String,        // e.g. "OUTPUT_PH", "INPUT_TDS"
    val reason: String            // e.g. "ADC_RANGE", "TIMEOUT"
)

/**
 * Top-level gateway data parsed from the API response.
 * Every field is nullable so the app degrades gracefully when the
 * server doesn't yet expose newer fields.
 */
data class GatewayData(
    val input: InputReading?,
    val output: OutputReading?,
    val outputLevel: OutputLevel?,
    val activeFaults: List<ActiveFault>,
    /** Legacy flat sensor list — kept for Devices screen */
    val legacySensors: List<Sensor>,
    /** Whether the ESP32 gateway reports itself online */
    val gatewayStatus: String = "UNKNOWN",
    /** LoRa RSSI from last received packet */
    val loraRssi: Int? = null,
    /** Whether any LoRa packet has ever been received */
    val loraConnected: Boolean = false
)

// ─────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JalSenseApp() }
    }
}

// ─────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────
@Composable
private fun JalSenseApp() {
    var screen        by rememberSaveable { mutableStateOf(Screen.HOME) }
    var gatewayUrl    by rememberSaveable { mutableStateOf("http://10.222.230.15/api/v1/measurements") }
    var gatewayData   by remember { mutableStateOf<GatewayData?>(null) }
    var alerts        by remember { mutableStateOf(demoAlerts()) }
    var connectionMsg by remember { mutableStateOf("Connecting to ESP32…") }
    var isConnected   by remember { mutableStateOf(false) }

    LaunchedEffect(gatewayUrl) {
        while (true) {
            try {
                val fetched = fetchGatewayData(gatewayUrl)
                gatewayData = fetched
                isConnected   = true
                connectionMsg = "ESP32 connected • refreshed just now"

                // Merge incoming active faults into alert list if not already present
                if (fetched.activeFaults.isNotEmpty()) {
                    val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val existingEvents = alerts.map { it.event }.toSet()
                    val newAlerts = fetched.activeFaults.mapNotNull { fault ->
                        val eventName = "Fault: ${fault.sensorKey.replace('_', ' ')}" +
                                if (fault.reason.isNotBlank()) " (${fault.reason})" else ""
                        if (eventName in existingEvents) null
                        else {
                            Alert(
                                id       = "FLT-${kotlin.math.abs(fault.sensorKey.hashCode()) % 9000 + 1000}",
                                time     = currentTime,
                                device   = "Sensor Node",
                                event    = eventName,
                                severity = Severity.CRITICAL,
                                state    = AlertState.TRIGGERED
                            )
                        }
                    }
                    if (newAlerts.isNotEmpty()) {
                        alerts = newAlerts + alerts
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isConnected   = false
                connectionMsg = "ESP32 unavailable • ${e.message ?: "check Wi-Fi and IP address"}"
            }
            delay(3000)
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary   = Teal,
            surface   = Surface,
            onSurface = Ink,
            error     = Danger
        )
    ) {
        Scaffold(
            containerColor = Surface,
            topBar         = { TopBar() },
            bottomBar      = { Navigation(screen) { screen = it } }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    Screen.HOME      -> Home { screen = it }
                    Screen.DASHBOARD -> Dashboard(gatewayData, isConnected, connectionMsg)
                    Screen.ALERTS    -> Alerts(alerts) { changed -> alerts = changed }
                    Screen.DEVICES   -> Devices(gatewayData?.legacySensors ?: emptyList())
                    Screen.HISTORY   -> History()
                    Screen.SETTINGS  -> Settings(gatewayUrl) { gatewayUrl = it }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// TopBar / Navigation (unchanged)
// ─────────────────────────────────────────────
@Composable
private fun TopBar() = androidx.compose.material3.Surface(color = Color.White, shadowElevation = 1.dp) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("JAL-SENSE", fontWeight = FontWeight.Bold, color = Teal, letterSpacing = 1.sp)
            Text("Monitoring Platform", fontSize = 12.sp, color = Color.Gray)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("PLANT 01", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Operator", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun Navigation(selected: Screen, select: (Screen) -> Unit) =
    androidx.compose.material3.Surface(color = Color.White, shadowElevation = 2.dp) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items(Screen.entries) { item ->
                Text(
                    item.label,
                    modifier   = Modifier.clickable { select(item) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    color      = if (item == selected) Teal else Color.DarkGray,
                    fontWeight = if (item == selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize   = 12.sp
                )
            }
        }
    }

// ─────────────────────────────────────────────
// Home (unchanged)
// ─────────────────────────────────────────────
@Composable
private fun Home(go: (Screen) -> Unit) = ScrollPage {
    Spacer(Modifier.height(24.dp))
    Text("Sensor Monitoring &\nTreatment System", fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
    Spacer(Modifier.height(12.dp))
    Text("Monitor connected sensors, identify abnormal conditions and manage system alerts.", color = Color.DarkGray, lineHeight = 22.sp)
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { go(Screen.DASHBOARD) }) { Text("Open Dashboard") }
        OutlinedButton(onClick = { go(Screen.ALERTS) }) { Text("View Alerts") }
    }
    Spacer(Modifier.height(34.dp)); SectionTitle("SYSTEM OVERVIEW")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat("Connected devices", "12"); Stat("Active alerts", "2")
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat("Online sensors", "48"); Stat("Sites", "4")
    }
    Spacer(Modifier.height(28.dp)); SectionTitle("RECENT ACTIVITY")
    Event("14:32", "Filter pressure increased")
    Event("14:21", "UV system restored")
    Event("13:48", "Sensor Node 04 disconnected")
    Spacer(Modifier.height(20.dp))
    Text("Values shown in this demonstration are local sample data, not production claims.", fontSize = 12.sp, color = Color.Gray)
}

// ─────────────────────────────────────────────
// Dashboard — updated with tank separation
// ─────────────────────────────────────────────
@Composable
private fun Dashboard(
    data: GatewayData?,
    connected: Boolean,
    connectionMessage: String
) = ScrollPage {
    Spacer(Modifier.height(20.dp))
    Text("Dashboard", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))

    // ── Connection status ──────────────────────────────────────────
    StatusPanel(
        if (connected) "ESP32 CONNECTED" else "CONNECTION UNAVAILABLE",
        buildString {
            append(connectionMessage)
            if (data != null && data.loraConnected) {
                append("\nLoRa link active")
                data.loraRssi?.let { append(" • RSSI: ${it} dBm") }
            } else if (data != null && !data.loraConnected) {
                append("\nLoRa: no packet received yet")
            }
        }
    )

    // ── Active sensor faults (from ALERT packets) ──────────────────
    val faults = data?.activeFaults ?: emptyList()
    if (faults.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        SectionTitle("SENSOR FAULTS / ALERTS")
        faults.forEach { fault -> FaultBanner(fault) }
    }

    // ── A. INPUT TANK ──────────────────────────────────────────────
    Spacer(Modifier.height(22.dp))
    TankSectionHeader("A. INPUT TANK")
    val input = data?.input
    if (input == null) {
        Empty("No input tank data received yet.")
    } else {
        PacketInfo("Input packet", input.packetNumber)
        RelayRow(input.relay)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SensorValueCard("pH",          input.ph,          Modifier.weight(1f))
            SensorValueCard("TDS",         input.tds,         Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SensorValueCard("Turbidity",   input.turbidity,   Modifier.weight(1f))
            SensorValueCard("Temperature", input.temperature, Modifier.weight(1f))
        }
    }

    // ── B. OUTPUT TANK ─────────────────────────────────────────────
    Spacer(Modifier.height(22.dp))
    TankSectionHeader("B. OUTPUT TANK")
    val output = data?.output
    if (output == null) {
        Empty("No output tank data received yet.")
    } else {
        PacketInfo("Output packet", output.packetNumber)
        RelayRow(output.relay)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SensorValueCard("pH",          output.ph,          Modifier.weight(1f))
            SensorValueCard("TDS",         output.tds,         Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SensorValueCard("Turbidity",   output.turbidity,   Modifier.weight(1f))
            Spacer(Modifier.weight(1f))  // temperature not present in output packet
        }
    }

    // ── C. OUTPUT TANK LEVEL ───────────────────────────────────────
    Spacer(Modifier.height(22.dp))
    TankSectionHeader("C. OUTPUT TANK LEVEL")
    val level = data?.outputLevel
    if (level == null) {
        Empty("No output level data received yet.")
    } else {
        val ageMs    = System.currentTimeMillis() - level.receivedAtMs
        val isStale  = !level.freshFromServer || ageMs > 90_000L
        PacketInfo("Level packet", level.packetNumber)
        if (isStale) {
            StaleWarning("Output level data may be stale (last update ${ageMs / 1000}s ago)")
        }
        RelayRow(level.relay)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SensorValueCard("Tank Level",  level.level,    Modifier.weight(1f))
            SensorValueCard("Distance",    level.distance, Modifier.weight(1f))
        }
    }

    // ── D. TREATMENT DECISION (unchanged) ─────────────────────────
    Spacer(Modifier.height(22.dp)); SectionTitle("TREATMENT DECISION")
    StatusPanel("PASS", "Configured process rules are normal. This is an operational indication, not laboratory confirmation.")

    // ── E. EQUIPMENT STATUS (unchanged) ───────────────────────────
    Spacer(Modifier.height(22.dp)); SectionTitle("EQUIPMENT STATUS")
    Equipment("Pump P-01", "RUNNING",   SensorState.NORMAL)
    Equipment("UV-01",     "ACTIVE",    SensorState.NORMAL)
    Equipment("Filter 02", "ATTENTION", SensorState.WARNING)
    Spacer(Modifier.height(20.dp))
}

// ─────────────────────────────────────────────
// NEW: SensorValueCard — correctly shows FAULT / — / numeric
// ─────────────────────────────────────────────
@Composable
private fun SensorValueCard(label: String, value: SensorValue, modifier: Modifier = Modifier) {
    val isFault   = value is SensorValue.Fault
    val isMissing = value is SensorValue.Missing
    val bgColor   = if (isFault) FaultBg else Color.White
    val stateColor = when {
        isFault   -> Danger
        isMissing -> Color.Gray
        else      -> Teal
    }
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = bgColor),
        border   = androidx.compose.foundation.BorderStroke(1.dp, if (isFault) Danger.copy(alpha = .4f) else Line)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.DarkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            when (value) {
                is SensorValue.Valid -> {
                    Text(value.raw,  fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Ink)
                    if (value.unit.isNotBlank())
                        Text(value.unit, color = Color.Gray, fontSize = 12.sp)
                }
                is SensorValue.Fault -> {
                    Text("FAULT", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Danger)
                }
                is SensorValue.Missing -> {
                    Text("—", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.Gray)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                value.toSensorState().name.replace('_', ' '),
                fontSize   = 10.sp,
                color      = stateColor,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier
                    .background(stateColor.copy(alpha = .10f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────
// NEW: Small helper composables for the dashboard
// ─────────────────────────────────────────────
@Composable
private fun TankSectionHeader(title: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Teal, letterSpacing = 0.5.sp)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = Line, thickness = 1.dp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PacketInfo(label: String, number: Int?) {
    if (number != null) {
        Text("$label #$number", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun RelayRow(relay: String) {
    val on = relay.equals("ON", ignoreCase = true) || relay.equals("TRUE", ignoreCase = true) || relay == "1"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Relay: ", fontSize = 12.sp, color = Color.DarkGray)
        Text(
            relay.ifBlank { "—" },
            fontSize   = 12.sp,
            fontWeight = FontWeight.Bold,
            color      = if (on) Teal else Danger,
            modifier   = Modifier
                .background((if (on) Teal else Danger).copy(alpha = .10f), RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun FaultBanner(fault: ActiveFault) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(FaultBg, RoundedCornerShape(6.dp))
            .border(1.dp, Danger.copy(alpha = .4f), RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("FAULT: ${fault.sensorKey.replace('_', ' ')}", fontWeight = FontWeight.Bold, color = Danger, fontSize = 13.sp)
            if (fault.reason.isNotBlank())
                Text("Reason: ${fault.reason}", fontSize = 12.sp, color = Color.DarkGray)
        }
        Text("ACTIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Danger,
            modifier = Modifier.background(Danger.copy(.10f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
private fun StaleWarning(message: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Warning.copy(alpha = .08f), RoundedCornerShape(6.dp))
            .border(1.dp, Warning.copy(alpha = .4f), RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Text("⚠ $message", fontSize = 12.sp, color = Warning, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────
// SensorCard (legacy — still used by Devices screen)
// ─────────────────────────────────────────────
@Composable
private fun SensorCard(sensor: Sensor, modifier: Modifier = Modifier) =
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sensor.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                StateTag(sensor.state)
            }
            Spacer(Modifier.height(14.dp))
            Text(sensor.value, fontWeight = FontWeight.Bold, fontSize = 25.sp)
            Text(sensor.unit, color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Text(sensor.updated, fontSize = 11.sp, color = Color.Gray)
            Text(sensor.quality.replace('_', ' '), fontSize = 10.sp, color = stateColor(sensor.state), fontWeight = FontWeight.Bold)
        }
    }

// ─────────────────────────────────────────────
// Alerts (unchanged)
// ─────────────────────────────────────────────
@Composable
private fun Alerts(alerts: List<Alert>, update: (List<Alert>) -> Unit) = ScrollPage {
    Spacer(Modifier.height(20.dp))
    Text("Alerts", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text("Active conditions and event history", color = Color.Gray)
    Spacer(Modifier.height(18.dp))
    val active = alerts.filter { it.state != AlertState.RESOLVED }
    SectionTitle("ACTIVE (${active.size})")
    if (active.isEmpty()) Empty("No active alerts.")
    else active.forEach { alert ->
        AlertCard(alert) { id, state -> update(alerts.map { if (it.id == id) it.copy(state = state) else it }) }
    }
    Spacer(Modifier.height(18.dp)); SectionTitle("HISTORY")
    alerts.filter { it.state == AlertState.RESOLVED }.forEach { AlertCard(it) { _, _ -> } }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun AlertCard(alert: Alert, update: (String, AlertState) -> Unit) =
    Card(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(alert.event, fontWeight = FontWeight.Bold)
                    Text("${alert.time}  •  ${alert.device}  •  #${alert.id}", fontSize = 12.sp, color = Color.Gray)
                }
                SeverityTag(alert.severity)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                alert.state.name.lowercase().replaceFirstChar { it.uppercase() },
                fontSize   = 12.sp,
                color      = stateColor(if (alert.state == AlertState.RESOLVED) SensorState.NORMAL else SensorState.WARNING),
                fontWeight = FontWeight.Bold
            )
            if (alert.state != AlertState.RESOLVED)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (alert.state == AlertState.TRIGGERED)
                        OutlinedButton(onClick = { update(alert.id, AlertState.ACKNOWLEDGED) }) { Text("Acknowledge") }
                    Button(onClick = { update(alert.id, AlertState.RESOLVED) }) { Text("Resolve") }
                }
        }
    }

// ─────────────────────────────────────────────
// Devices (unchanged — uses legacy Sensor list)
// ─────────────────────────────────────────────
@Composable
private fun Devices(sensors: List<Sensor>) = ScrollPage {
    Spacer(Modifier.height(20.dp))
    Text("Devices", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text("Plant 01 • Node connectivity and configured sensors", color = Color.Gray)
    Spacer(Modifier.height(22.dp)); SectionTitle("SENSOR NODE 01")
    StatusPanel("ONLINE", "Last transmission received seconds ago")
    Spacer(Modifier.height(16.dp))
    sensors.forEach { sensor ->
        Equipment("${sensor.id} • ${sensor.name}", "${sensor.state.name} • ${sensor.quality.replace('_', ' ')}", sensor.state)
    }
    Spacer(Modifier.height(20.dp))
}

// ─────────────────────────────────────────────
// History (unchanged)
// ─────────────────────────────────────────────
@Composable
private fun History() = ScrollPage {
    Spacer(Modifier.height(20.dp))
    Text("History", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text("Historical sensor readings", color = Color.Gray)
    Spacer(Modifier.height(20.dp)); SectionTitle("PH TREND")
    ChartPlaceholder()
    Spacer(Modifier.height(18.dp))
    Text("Select a range", fontWeight = FontWeight.Bold)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("1h", "6h", "24h", "7d").forEach { OutlinedButton(onClick = {}) { Text(it) } }
    }
    Spacer(Modifier.height(14.dp))
    Text("Min 6.7   Average 6.8   Max 6.9", color = Color.DarkGray)
    Spacer(Modifier.height(20.dp))
}

// ─────────────────────────────────────────────
// Settings (unchanged)
// ─────────────────────────────────────────────
@Composable
private fun Settings(gatewayUrl: String, saveGatewayUrl: (String) -> Unit) = ScrollPage {
    var editedUrl by remember(gatewayUrl) { mutableStateOf(gatewayUrl) }
    Spacer(Modifier.height(20.dp))
    Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(20.dp)); SectionTitle("ESP32 CONNECTION")
    Spacer(Modifier.height(8.dp))
    Text("Gateway URL", fontSize = 13.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value       = editedUrl,
        onValueChange = { editedUrl = it },
        modifier    = Modifier.fillMaxWidth().padding(top = 6.dp),
        singleLine  = true,
        supportingText = { Text("Phone and ESP32 must use the same Wi-Fi network.") }
    )
    Button(onClick = { saveGatewayUrl(editedUrl.trim()) }, modifier = Modifier.padding(top = 8.dp)) {
        Text("Connect ESP32")
    }
    Spacer(Modifier.height(20.dp))
    Equipment("Users & roles",         "Operator access",       SensorState.NORMAL)
    Equipment("Alert rules",           "Threshold configuration", SensorState.NORMAL)
    Equipment("System configuration",  "Plant 01",              SensorState.NORMAL)
    Spacer(Modifier.height(22.dp)); SectionTitle("TERMS & DATA LIMITATIONS")
    Text(
        "Sensor readings are provided for monitoring and operational purposes. Indirect sensor measurements are not laboratory-confirmed contaminant concentrations. Final legal terms must be reviewed by an appropriate legal professional before production deployment.",
        lineHeight = 22.sp
    )
    Spacer(Modifier.height(20.dp))
    Text("Terms version 1.0 • 15 Sep 2026", fontSize = 12.sp, color = Color.Gray)
}

// ─────────────────────────────────────────────
// Reusable UI primitives (unchanged)
// ─────────────────────────────────────────────
@Composable private fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp),
        content = content
    )
}

@Composable private fun SectionTitle(text: String) =
    Text(text, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

@Composable private fun Empty(text: String) =
    Text(text, color = Color.Gray, modifier = Modifier.padding(vertical = 16.dp))

@Composable private fun RowScope.Stat(label: String, value: String) =
    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Teal)
            Text(label, fontSize = 12.sp, color = Color.Gray)
        }
    }

@Composable private fun Event(time: String, text: String) =
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).border(0.5.dp, Line, RoundedCornerShape(4.dp)).padding(10.dp)) {
        Text(time, color = Teal, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        Text(text, fontSize = 14.sp)
    }

@Composable private fun StatusPanel(title: String, subtitle: String) =
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Teal, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 13.sp, color = Color.DarkGray, lineHeight = 19.sp)
        }
    }

@Composable private fun Equipment(name: String, state: String, status: SensorState) =
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).border(1.dp, Line, RoundedCornerShape(6.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Bold); Text(state, fontSize = 12.sp, color = Color.Gray) }
        StateTag(status)
    }

@Composable private fun StateTag(state: SensorState) =
    Text(
        state.name.replace('_', ' '),
        fontSize   = 9.sp,
        fontWeight = FontWeight.Bold,
        color      = stateColor(state),
        modifier   = Modifier.background(stateColor(state).copy(alpha = .10f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 4.dp)
    )

@Composable private fun SeverityTag(severity: Severity) =
    Text(severity.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (severity == Severity.CRITICAL) Danger else if (severity == Severity.WARNING) Warning else Teal)

@Composable private fun ChartPlaceholder() =
    Card(Modifier.fillMaxWidth().height(190.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("Threshold range: 6.5 – 8.5", fontSize = 12.sp, color = Color.Gray)
            Text("  7.0  ───╲    ╱───\n  6.8      ╲──╱\n  6.5  ─────────────────",
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Teal)
            Text("10:00        11:00        12:00", fontSize = 11.sp, color = Color.Gray)
        }
    }

private fun stateColor(state: SensorState) = when (state) {
    SensorState.NORMAL -> Teal
    SensorState.WARNING, SensorState.CALIBRATION_REQUIRED -> Warning
    else -> Danger
}

// ─────────────────────────────────────────────
// Networking & Parsing
// ─────────────────────────────────────────────

/**
 * Fetches from the existing endpoint and returns a [GatewayData].
 *
 * The server may return any combination of:
 *   - New structured format with "input", "output", "output_level", "faults", "recoveries" keys
 *   - Old flat "measurements" array (backward compat)
 *   - A bare JSON array (backward compat)
 *
 * All fields are optional — missing fields produce [SensorValue.Missing],
 * "FAULT" strings produce [SensorValue.Fault]. Never produces 0 for either.
 */
private suspend fun fetchGatewayData(endpoint: String): GatewayData = withContext(Dispatchers.IO) {
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod  = "GET"
        connectTimeout = 5000
        readTimeout    = 5000
    }
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        parseGatewayResponse(body)
    } finally {
        connection.disconnect()
    }
}

/**
 * Pure parsing function — matches the exact JSON produced by the ESP32 receiver firmware.
 *
 * ESP32 sends:
 * {
 *   "input_tank":   { "packet":16, "ph":1.74, "tds":0.00, "turbidity":2.58,
 *                     "temperature":28.94, "relay":"ON",
 *                     "ph_status":"VALID", "tds_status":"VALID", ... ,
 *                     "received":true, "fresh":true },
 *   "output_tank":  { "packet":5,  "ph":2.14, "tds":1.31, "turbidity":0.72,
 *                     "relay":"ON", "ph_status":"VALID", ... },
 *   "output_level": { "packet":10, "level":65.2, "distance":8.0, "relay":"ON",
 *                     "level_status":"VALID", "distance_status":"VALID",
 *                     "received":true, "fresh":true },
 *   "faults":       ["OUTPUT_PH", "INPUT_TDS"],   ← plain string array
 *   "measurements": [ ... ]                        ← legacy flat array
 * }
 *
 * All fields optional — missing/null → SensorValue.Missing, "FAULT" → SensorValue.Fault.
 */
internal fun parseGatewayResponse(body: String): GatewayData {
    val root: JSONObject? = runCatching { JSONObject(body) }.getOrNull()

    var inputReading:  InputReading?  = null
    var outputReading: OutputReading? = null
    var outputLevel:   OutputLevel?   = null
    val activeFaults  = mutableListOf<ActiveFault>()
    val legacySensors = mutableListOf<Sensor>()

    if (root != null) {

        // ── INPUT TANK  (key: "input_tank") ─────────────────────
        val inputObj = root.optJSONObject("input_tank")
        if (inputObj != null) {
            inputReading = parseInputTankObject(inputObj)
        }

        // ── OUTPUT TANK  (key: "output_tank") ───────────────────
        val outputObj = root.optJSONObject("output_tank")
        if (outputObj != null) {
            outputReading = parseOutputTankObject(outputObj)
        }

        // ── OUTPUT LEVEL  (key: "output_level") ─────────────────
        val levelObj = root.optJSONObject("output_level")
        if (levelObj != null) {
            outputLevel = parseOutputLevelObject(levelObj)
        }

        // ── FAULTS  — plain string array ["OUTPUT_PH", ...] ─────
        // ESP32 sends faults as a JSON array of strings, not objects.
        val faultsArr = root.optJSONArray("faults")
        if (faultsArr != null) {
            for (i in 0 until faultsArr.length()) {
                val entry = faultsArr.opt(i) ?: continue
                when (entry) {
                    // plain string: "OUTPUT_PH"
                    is String -> {
                        if (entry.isNotBlank())
                            activeFaults += ActiveFault(sensorKey = entry, reason = "")
                    }
                    // object: {"sensor":"OUTPUT_PH","reason":"ADC_RANGE"}
                    is JSONObject -> {
                        val key = entry.optString("sensor", entry.optString("alert", ""))
                        if (key.isNotBlank())
                            activeFaults += ActiveFault(
                                sensorKey = key,
                                reason    = entry.optString("reason", "")
                            )
                    }
                }
            }
        }

        // ── LEGACY measurements array (for Devices screen) ──────
        val measArr = root.optJSONArray("measurements")
        if (measArr != null) {
            legacySensors += parseLegacyArray(measArr)
        }
    } else {
        // Bare JSON array fallback
        val arr = runCatching { JSONArray(body) }.getOrNull()
        if (arr != null) legacySensors += parseLegacyArray(arr)
    }

    // Build legacy sensor list from structured data when the
    // measurements array is empty (Devices screen uses it)
    if (legacySensors.isEmpty()) {
        legacySensors += buildLegacySensors(inputReading, outputReading, outputLevel)
    }

    return GatewayData(
        input         = inputReading,
        output        = outputReading,
        outputLevel   = outputLevel,
        activeFaults  = activeFaults,
        legacySensors = legacySensors,
        gatewayStatus = root?.optString("status", "UNKNOWN") ?: "UNKNOWN",
        loraRssi      = root?.optJSONObject("lora")?.optIntOrNull("rssi"),
        loraConnected = root?.optJSONObject("lora")?.optBoolean("connected", false) ?: false
    )
}

// ── Parse "input_tank" object ─────────────────────────────────────────────────
// ESP32 keys: packet, ph, tds, turbidity, temperature, relay,
//             ph_status, tds_status, turbidity_status, temperature_status
private fun parseInputTankObject(obj: JSONObject): InputReading {
    return InputReading(
        packetNumber = obj.optIntOrNull("packet"),
        ph          = parseSensorFieldWithStatus(obj, "ph",          "ph_status"),
        tds         = parseSensorFieldWithStatus(obj, "tds",         "tds_status"),
        turbidity   = parseSensorFieldWithStatus(obj, "turbidity",   "turbidity_status"),
        temperature = parseSensorFieldWithStatus(obj, "temperature", "temperature_status"),
        relay       = parseRelayField(obj)
    )
}

// ── Parse "output_tank" object ────────────────────────────────────────────────
// ESP32 keys: packet, ph, tds, turbidity, relay,
//             ph_status, tds_status, turbidity_status
private fun parseOutputTankObject(obj: JSONObject): OutputReading {
    return OutputReading(
        packetNumber = obj.optIntOrNull("packet"),
        ph          = parseSensorFieldWithStatus(obj, "ph",        "ph_status"),
        tds         = parseSensorFieldWithStatus(obj, "tds",       "tds_status"),
        turbidity   = parseSensorFieldWithStatus(obj, "turbidity", "turbidity_status"),
        relay       = parseRelayField(obj)
    )
}

// ── Parse "output_level" object ───────────────────────────────────────────────
// ESP32 keys: packet, level, distance, relay,
//             level_status, distance_status
private fun parseOutputLevelObject(obj: JSONObject): OutputLevel {
    return OutputLevel(
        packetNumber    = obj.optIntOrNull("packet"),
        level           = parseSensorFieldWithStatus(obj, "level",    "level_status"),
        distance        = parseSensorFieldWithStatus(obj, "distance", "distance_status"),
        relay           = parseRelayField(obj),
        receivedAtMs    = System.currentTimeMillis(),
        freshFromServer = obj.optBoolean("fresh", true)
    )
}

/**
 * Reads a sensor value from a JSON object using the ESP32's own status field.
 *
 * The ESP32 sends both the value and a companion *_status field:
 *   "ph": 1.74,  "ph_status": "VALID"
 *   "ph": null,  "ph_status": "FAULT"
 *   "ph": null,  "ph_status": "MISSING"
 *
 * Logic (in priority order):
 *  1. If *_status == "FAULT"   → SensorValue.Fault   (regardless of value field)
 *  2. If *_status == "MISSING" → SensorValue.Missing
 *  3. If value field is numeric → SensorValue.Valid
 *  4. If value string == "FAULT" → SensorValue.Fault
 *  5. Otherwise → SensorValue.Missing
 */
private fun parseSensorFieldWithStatus(
    obj:       JSONObject,
    valueKey:  String,
    statusKey: String
): SensorValue {
    // Check companion status field first — most reliable
    val status = obj.optString(statusKey, "").trim().uppercase()
    if (status == "FAULT")   return SensorValue.Fault
    if (status == "MISSING") return SensorValue.Missing

    // Fall through to value field
    return parseSensorField(obj, valueKey)
}

// ── Parse a single sensor field, trying multiple key variants ────────────────
// Returns:
//   SensorValue.Fault   — if value string == "FAULT" (case-insensitive)
//   SensorValue.Valid   — if value is a non-null numeric
//   SensorValue.Missing — if key is absent, null, or JSON null
//
// NOTE: The ESP32 sends numeric values as JSON numbers (not strings),
// e.g. "ph": 1.74  not  "ph": "1.74"
// JSONObject.opt() returns Double/Int for numbers — toString() handles both.
private fun parseSensorField(obj: JSONObject, vararg keys: String): SensorValue {
    for (key in keys) {
        if (!obj.has(key)) continue
        // opt() returns JSONObject.NULL for JSON null — treat that as missing
        val raw = obj.opt(key)
        if (raw == null || raw == JSONObject.NULL) continue
        val str = raw.toString().trim()
        if (str.isBlank() || str.equals("null", ignoreCase = true)) continue
        if (str.equals("FAULT", ignoreCase = true)) return SensorValue.Fault
        // Try numeric (handles both "1.74" and 1.74)
        val num = str.toDoubleOrNull()
        if (num != null) {
            val unit = inferUnit(keys[0])
            return SensorValue.Valid(str, unit)
        }
        // Non-numeric, non-FAULT string — treat as missing rather than crashing
        return SensorValue.Missing
    }
    return SensorValue.Missing
}

private fun inferUnit(key: String): String = when (key.lowercase()) {
    "ph"          -> ""
    "tds"         -> "ppm"
    "turbidity", "turb" -> "NTU"
    "temperature", "temp" -> "°C"
    "level"       -> "%"
    "distance", "dist" -> "cm"
    else          -> ""
}

private fun parseRelayField(obj: JSONObject): String {
    for (key in listOf("relay", "RELAY")) {
        if (obj.has(key)) {
            val v = obj.optString(key, "").trim()
            if (v.isNotBlank()) return v.uppercase()
        }
    }
    return "—"
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key)) return null
    val v = opt(key) ?: return null
    return v.toString().toDoubleOrNull()?.toInt()
}

// ── Legacy flat sensor array parser (backward compat) ────────────────────────
private fun parseLegacyArray(array: JSONArray): List<Sensor> =
    List(array.length()) { index ->
        val item = array.getJSONObject(index)
        // FAULT string → CRITICAL state, not "0"
        val rawValue  = item.opt("value")?.toString() ?: "—"
        val isFault   = rawValue.equals("FAULT", ignoreCase = true)
        val dispValue = if (isFault) "FAULT" else rawValue
        Sensor(
            name    = item.optString("name", item.optString("id", "Sensor ${index + 1}")),
            id      = item.optString("sensor_id", item.optString("id", "ID-$index")),
            value   = dispValue,
            unit    = item.optString("unit", ""),
            state   = if (isFault) SensorState.CRITICAL
                      else sensorState(item.optString("status", item.optString("state", "NORMAL"))),
            updated = "Live data",
            quality = item.optString("quality", item.optString("quality_flag", if (isFault) "FAULT" else "VALID"))
        )
    }

// ── Build legacy Sensor list from structured data (for Devices screen) ────────
private fun buildLegacySensors(
    input:  InputReading?,
    output: OutputReading?,
    level:  OutputLevel?
): List<Sensor> {
    val list = mutableListOf<Sensor>()
    fun sv(name: String, id: String, sv: SensorValue): Sensor = Sensor(
        name    = name,
        id      = id,
        value   = when (sv) { is SensorValue.Valid -> sv.raw; is SensorValue.Fault -> "FAULT"; is SensorValue.Missing -> "—" },
        unit    = when (sv) { is SensorValue.Valid -> sv.unit; else -> "" },
        state   = sv.toSensorState(),
        updated = "Live data",
        quality = when (sv) { is SensorValue.Fault -> "FAULT"; is SensorValue.Missing -> "MISSING"; else -> "VALID" }
    )
    input?.let {
        list += sv("Input pH",          "IN-PH",   it.ph)
        list += sv("Input TDS",         "IN-TDS",  it.tds)
        list += sv("Input Turbidity",   "IN-TURB", it.turbidity)
        list += sv("Input Temperature", "IN-TEMP", it.temperature)
    }
    output?.let {
        list += sv("Output pH",        "OUT-PH",   it.ph)
        list += sv("Output TDS",       "OUT-TDS",  it.tds)
        list += sv("Output Turbidity", "OUT-TURB", it.turbidity)
    }
    level?.let {
        list += sv("Output Level",    "OUT-LVL",  it.level)
        list += sv("Output Distance", "OUT-DIST", it.distance)
    }
    return list
}

private fun sensorState(value: String): SensorState = runCatching {
    SensorState.valueOf(value.trim().uppercase().replace(' ', '_'))
}.getOrDefault(SensorState.INVALID)

private fun demoAlerts() = listOf(
    Alert("1042", "14:32", "Filter 02",  "High differential pressure", Severity.WARNING,  AlertState.TRIGGERED),
    Alert("1041", "14:21", "UV-01",      "Lamp status fault",          Severity.CRITICAL, AlertState.RESOLVED),
    Alert("1040", "13:48", "Node 04",    "Sensor offline",             Severity.WARNING,  AlertState.ACKNOWLEDGED)
)
