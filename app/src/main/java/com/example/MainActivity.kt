package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*
import java.time.Instant
import java.time.Duration
import java.time.format.DateTimeParseException

// Dataclass represents the structure of track coordinates
data class KmlPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0
)

class MainActivity : ComponentActivity() {
    private var sharedIntentText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Required for OSMDroid to function correctly
        Configuration.getInstance().userAgentValue = packageName
        enableEdgeToEdge()
        
        handleIntent(intent)
        
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        sharedText = sharedIntentText,
                        onSharedTextConsumed = { sharedIntentText = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                sharedIntentText = it
            }
        }
    }
}

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier, 
    sharedText: String?, 
    onSharedTextConsumed: () -> Unit
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf("LIVE") } // tabs: LIVE, GRAPH, FILE

    // Source of Truth states
    var kmlInput by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf<String?>(null) }
    var kmlPoints by remember { mutableStateOf<List<KmlPoint>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("No KML loaded. Upload a KML file, paste a KML string under ‘File details’ tab, or import a Google Maps directions link.") }
    var isError by remember { mutableStateOf(false) }

    var isProcessingSharedRoute by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    // Log initialization event
    LaunchedEffect(Unit) {
        Log.i("MainActivity", "DashboardScreen initialized in waiting state (Empty Initial KML).")
    }

    // Listen for Shared Routes
    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            Log.i("MainActivity", "Captured shared Maps route link intent: '$sharedText'")
            isProcessingSharedRoute = true
            statusMessage = "Processing shared Google Maps link..."
            isError = false
            activeTab = "FILE"
            
            val result = processSharedMapsText(context, sharedText)
            if (result.errorMessage != null) {
                statusMessage = result.errorMessage
                isError = true
                Log.e("MainActivity", "Failed to resolve deep-link shared route: ${result.errorMessage}")
            } else if (result.points.isNotEmpty()) {
                val newXml = generateKmlFromPoints(result.points)
                kmlInput = newXml
                fileName = "Imported_Maps_Route.kml"
                statusMessage = "Success: Generated route with ${result.points.size} pts and downloaded elevation."
                isError = false
                activeTab = "LIVE"
                Log.i("MainActivity", "Successfully imported deep-link route maps with ${result.points.size} coordinates.")
            } else {
                statusMessage = "Could not find a valid route in the shared text."
                isError = true
                Log.e("MainActivity", "Deep-linked share text could not be resolved into any valid path points.")
            }
            isProcessingSharedRoute = false
            onSharedTextConsumed()
        }
    }

    // Metadata states
    var trackStartDatetime by remember { mutableStateOf("") }

    // Active Node highlighted/edited
    var selectedIndex by remember { mutableStateOf<Int?>(0) }

    // Floating temporary node edit form states
    var editLatStr by remember { mutableStateOf("") }
    var editLonStr by remember { mutableStateOf("") }
    var editAltStr by remember { mutableStateOf("") }

    // Handle string inputs update on index change
    LaunchedEffect(selectedIndex, kmlPoints) {
        val current = kmlPoints.getOrNull(selectedIndex ?: -1)
        if (current != null) {
            editLatStr = current.latitude.toString()
            editLonStr = current.longitude.toString()
            editAltStr = current.altitude.toString()
        }
    }

    // Direct synchronization of updates
    fun commitPointsUpdate(newPoints: List<KmlPoint>) {
        Log.i("MainActivity", "track points update requested. Size: ${kmlPoints.size} -> ${newPoints.size} points.")
        kmlPoints = newPoints
        try {
            val updatedInput = updateKmlCoordinates(kmlInput, newPoints)
            kmlInput = updatedInput
            Log.i("MainActivity", "Successfully synchronized updated coordinate points with in-memory KML XML (XML Character length: ${updatedInput.length}).")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to update KML track XML with modified coordinate list.", e)
        }
    }

    // File picker launcher
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            Log.i("MainActivity", "KML file picker was cancelled by the user.")
            return@rememberLauncherForActivityResult
        }
        uri.let {
            Log.i("MainActivity", "KML file selected via picker. Uri: $it")
            try {
                val contentResolver = context.contentResolver
                
                var name: String? = null
                val cursor = contentResolver.query(it, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = c.getString(nameIndex)
                        }
                    }
                }
                if (name == null) {
                    name = it.path?.substringAfterLast('/') ?: "imported_track.kml"
                }
                fileName = name
                Log.i("MainActivity", "Importing KML file named: $name")
                
                contentResolver.openInputStream(it)?.use { inputStream ->
                    val text = inputStream.bufferedReader().use { r -> r.readText() }
                    if (text.isBlank()) {
                        throw IllegalArgumentException("The KML file is empty.")
                    }
                    kmlInput = text
                    statusMessage = "Successfully imported '$name'."
                    isError = false
                    Log.i("MainActivity", "KML file '$name' successfully parsed in-memory. XML Length: ${text.length} chars.")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to import/open selected KML file.", e)
                statusMessage = "Error importing KML: ${e.localizedMessage}"
                isError = true
                kmlInput = ""
                fileName = null
                kmlPoints = emptyList()
                selectedIndex = null
            }
        }
    }

    // Sync state layout from XML changes
    LaunchedEffect(kmlInput) {
        if (kmlInput.isBlank()) {
            kmlPoints = emptyList()
            selectedIndex = null
            statusMessage = "Ready to receive file. Upload a KML file below."
            isError = false
            trackStartDatetime = ""
            Log.i("MainActivity", "kmlInput is blank. Cleaned coordinates list and reset status description.")
            return@LaunchedEffect
        }
        
        Log.i("MainActivity", "KML input changed/entered. XML size: ${kmlInput.length} chars. Extracting metadata...")
        trackStartDatetime = extractFirstWhen(kmlInput)
        
        delay(300)
        try {
            val parsed = parseKmlPoints(kmlInput)
            if (parsed.isNotEmpty()) {
                if (parsed != kmlPoints) {
                    kmlPoints = parsed
                    selectedIndex = null
                }
                statusMessage = if (fileName != null) {
                    "Success: Loaded ${parsed.size} track points from '$fileName'."
                } else {
                    "Success: Loaded ${parsed.size} track points."
                }
                isError = false
                Log.i("MainActivity", "Successfully parsed $statusMessage")
            } else {
                statusMessage = "Warning: KML structure parsed but no coordinates found."
                isError = true
                Log.w("MainActivity", "Invalid KML layout parsed: No coordinates could be parsed/found inside <coordinates> tag.")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "KML parsing error: ${e.message}", e)
            statusMessage = "Unsupported/Invalid File: ${e.message}"
            isError = true
        }
    }

    // Conversion to maps standard GeoPoints
    val mapGeoPoints = remember(kmlPoints) {
        kmlPoints.map { GeoPoint(it.latitude, it.longitude, it.altitude) }
    }

    Column(modifier = modifier.fillMaxSize().background(GeoBackground)) {
        // Universal Modern M3 Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(GeoSurface)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(40.dp).background(GeoPrimaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = "Map Navigation Icon",
                        tint = GeoOnPrimaryContainer
                    )
                }
                Column {
                    Text(
                        text = "KML Explorer",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = GeoOnSurface,
                            letterSpacing = (-0.5).sp
                        )
                    )
                    Text(
                        text = if (fileName != null) "File: $fileName" else "No track file selected",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GeoSlate600,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isProcessingSharedRoute) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(end = 12.dp),
                        color = GeoPrimary,
                        strokeWidth = 2.dp
                    )
                }
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu Icon", tint = GeoSlate600)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Import Maps Link") },
                        onClick = {
                            menuExpanded = false
                            showUrlDialog = true
                        }
                    )
                }
            }
        }
        HorizontalDivider(color = GeoSlate200)

        // Main Dashboard Frame based on active tab
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                "LIVE" -> {
                    // LIVE TAB: Split map and interactive visual editor
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Map half
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1.2f)
                                .background(GeoMapAreaBg)
                        ) {
                            OsmMapView(
                                points = mapGeoPoints,
                                highlightedIndex = selectedIndex,
                                onNodeSelected = { idx -> selectedIndex = idx },
                                onNodeMoved = { idx, geoPt ->
                                    val updatedList = kmlPoints.toMutableList()
                                    val oldPt = updatedList.getOrNull(idx)
                                    if (oldPt != null) {
                                        updatedList[idx] = KmlPoint(geoPt.latitude, geoPt.longitude, oldPt.altitude)
                                        commitPointsUpdate(updatedList)
                                        statusMessage = "Success: Moved Node ${idx + 1} to new coordinates."
                                        isError = false
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // Floating status badge
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp)
                                    .background(Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(modifier = Modifier.size(8.dp).background(if (isError) Color.Red else Color.Green, CircleShape))
                                Text(
                                    text = if (isError) "KML FAULT" else if (fileName != null) "$fileName (${kmlPoints.size} PTS)" else "READY",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 1.sp
                                    )
                                )
                            }
                        }

                        // Bottom visual coordinate inspector card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation = 16.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), spotColor = Color.Black.copy(alpha = 0.08f))
                                .background(GeoSurface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                .padding(16.dp)
                        ) {
                            if (kmlPoints.isEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = "No Coordinates Icon", tint = GeoSlate400, modifier = Modifier.size(36.dp))
                                    Text(
                                        "No track points loaded.",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = GeoSlate600, fontWeight = FontWeight.Bold)
                                    )
                                    Button(
                                        onClick = { fileLauncher.launch("*/*") },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = GeoPrimaryContainer, contentColor = GeoOnPrimaryContainer)
                                    ) {
                                        Text("LOAD FILE")
                                    }
                                }
                            } else {
                                val currentIdx = selectedIndex ?: 0
                                val activePt = kmlPoints.getOrNull(currentIdx)

                                // Node carousel slider controls
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    IconButton(
                                        onClick = { 
                                            if (currentIdx > 0) selectedIndex = currentIdx - 1
                                        },
                                        enabled = currentIdx > 0
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous Node")
                                    }

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "TRACK NODE CARD ${currentIdx + 1} OF ${kmlPoints.size}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = GeoPrimary,
                                                letterSpacing = 1.2.sp
                                            )
                                        )
                                        if (activePt != null) {
                                            Text(
                                                text = "${activePt.altitude} m altitude • approx node",
                                                style = MaterialTheme.typography.labelMedium.copy(color = GeoSlate600)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { 
                                            if (currentIdx < kmlPoints.size - 1) selectedIndex = currentIdx + 1
                                        },
                                        enabled = currentIdx < kmlPoints.size - 1
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next Node")
                                    }
                                }

                                // Interactive scrub slider
                                Slider(
                                    value = currentIdx.toFloat(),
                                    onValueChange = { selectedIndex = it.roundToInt().coerceIn(0, kmlPoints.size - 1) },
                                    valueRange = 0f..(kmlPoints.size - 1).coerceAtLeast(1).toFloat(),
                                    steps = (kmlPoints.size - 2).coerceAtLeast(0),
                                    colors = SliderDefaults.colors(
                                        activeTrackColor = GeoPrimary,
                                        thumbColor = GeoPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Coordinate manual textboxes for intuitive modifications
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = editLatStr,
                                        onValueChange = { editLatStr = it },
                                        label = { Text("Latitude", fontSize = 11.sp) },
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GeoPrimary),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = editLonStr,
                                        onValueChange = { editLonStr = it },
                                        label = { Text("Longitude", fontSize = 11.sp) },
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GeoPrimary),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = editAltStr,
                                        onValueChange = { editAltStr = it },
                                        label = { Text("Altitude (m)", fontSize = 11.sp) },
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GeoPrimary),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Modify Track Actions Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Update Button (Save edits)
                                    Button(
                                        onClick = {
                                            val lat = editLatStr.toDoubleOrNull()
                                            val lon = editLonStr.toDoubleOrNull()
                                            val alt = editAltStr.toDoubleOrNull() ?: 0.0
                                            
                                            if (lat == null || lon == null) {
                                                statusMessage = "Error: Invalid Latitude/Longitude numbers!"
                                                isError = true
                                            } else {
                                                val updatedList = kmlPoints.toMutableList()
                                                updatedList[currentIdx] = KmlPoint(lat, lon, alt)
                                                commitPointsUpdate(updatedList)
                                                statusMessage = "Success: Updated Node ${currentIdx + 1}."
                                                isError = false
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = GeoPrimary),
                                        modifier = Modifier.weight(1.5f).height(48.dp)
                                    ) {
                                        Text("UPDATE NODE", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                                    }

                                    // Add/Insert adjacent point
                                    Button(
                                        onClick = {
                                            val current = kmlPoints.getOrNull(currentIdx)
                                            val updatedList = kmlPoints.toMutableList()
                                            val newPt = if (current != null) {
                                                KmlPoint(current.latitude + 0.0001, current.longitude + 0.0001, current.altitude)
                                            } else {
                                                KmlPoint(32.7563, 75.4733, 1499.0)
                                            }
                                            updatedList.add(currentIdx + 1, newPt)
                                            commitPointsUpdate(updatedList)
                                            selectedIndex = currentIdx + 1
                                            statusMessage = "Success: Inserted new point."
                                            isError = false
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GeoSlate100,
                                            contentColor = GeoSlate900
                                        ),
                                        modifier = Modifier.weight(1.2f).height(48.dp)
                                    ) {
                                        Text("+ INSERT", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                                    }

                                    // Delete active point button
                                    Button(
                                        onClick = {
                                            val updatedList = kmlPoints.toMutableList()
                                            updatedList.removeAt(currentIdx)
                                            if (updatedList.isEmpty()) {
                                                selectedIndex = null
                                            } else if (currentIdx >= updatedList.size) {
                                                selectedIndex = updatedList.size - 1
                                            }
                                            commitPointsUpdate(updatedList)
                                            statusMessage = "Success: Deleted node ${currentIdx + 1}."
                                            isError = false
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        ),
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Node")
                                    }
                                }
                            }
                        }
                    }
                }
                "GRAPH" -> {
                    // GRAPH TAB: Elevation stats and Spreadsheet coordinate scroll table
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Analytics calculations
                        val elevationStats = remember(kmlPoints) { calculateElevationStats(kmlPoints) }
                        val totalDistVal = remember(kmlPoints) { calculateTotalDistance(kmlPoints) }

                        // 1. Analytics Card Overview
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = GeoSurface),
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "TRACK PERFORMANCE SUMMARY",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = GeoSlate600,
                                        letterSpacing = 1.sp
                                    )
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("TOTAL TRACK DISTANCE", style = MaterialTheme.typography.labelSmall.copy(color = GeoSlate400))
                                        Text(
                                            text = String.format("%.3f KM", totalDistVal),
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, color = GeoPrimary)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("NODE SAMPLES Count", style = MaterialTheme.typography.labelSmall.copy(color = GeoSlate400))
                                        Text(
                                            text = "${kmlPoints.size} NODES",
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, color = GeoSlate900)
                                        )
                                    }
                                }
                                HorizontalDivider(color = GeoSlate100, modifier = Modifier.padding(vertical = 12.dp))
                                
                                // Altitude summary grid
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("MIN ALTITUDE", style = MaterialTheme.typography.labelSmall.copy(color = GeoSlate400))
                                        Text("${elevationStats.first.roundToInt()} m", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                    Column {
                                        Text("MAX ALTITUDE", style = MaterialTheme.typography.labelSmall.copy(color = GeoSlate400))
                                        Text("${elevationStats.second.roundToInt()} m", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("ELEVATION GAIN", style = MaterialTheme.typography.labelSmall.copy(color = GeoSlate400))
                                        Text(
                                            "+${elevationStats.third.roundToInt()} m", 
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Linear point spreadsheet editor section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "COORDINATES LIST / SPREADSHEET",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = GeoSlate600)
                            )
                            Button(
                                onClick = {
                                    val updatedList = kmlPoints.toMutableList()
                                    val last = kmlPoints.lastOrNull()
                                    val nextPt = if (last != null) {
                                        KmlPoint(last.latitude + 0.0001, last.longitude + 0.0001, last.altitude)
                                    } else {
                                        KmlPoint(32.7563, 75.4733, 1499.0)
                                    }
                                    updatedList.add(nextPt)
                                    commitPointsUpdate(updatedList)
                                    selectedIndex = updatedList.size - 1
                                    statusMessage = "Success: Appended new coordinate to the track end."
                                    isError = false
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GeoPrimaryContainer, contentColor = GeoOnPrimaryContainer)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Icon", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("APPEND PT", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }
                        }

                        // Scrollable grid list showing coordinate values
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            itemsIndexed(kmlPoints) { idx, point ->
                                val isSelectedNode = idx == selectedIndex
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = if (isSelectedNode) 2.dp else 1.dp,
                                            color = if (isSelectedNode) GeoPrimary else GeoSlate200,
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelectedNode) GeoSlate50 else GeoSurface
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    onClick = { selectedIndex = idx }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Index badge circle
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(
                                                    if (isSelectedNode) GeoPrimary else GeoSlate200, 
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                color = if (isSelectedNode) Color.White else GeoSlate900,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        // Coordinate details
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(
                                                    text = "Lat: ${String.format("%.6f", point.latitude)}",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                                                )
                                                Text(
                                                    text = "Lon: ${String.format("%.6f", point.longitude)}",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                                                )
                                            }
                                            Text(
                                                text = "Altitude: ${point.altitude} m",
                                                style = MaterialTheme.typography.labelSmall.copy(color = GeoSlate600)
                                            )
                                        }

                                        // Edit / Delete quick buttons
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    selectedIndex = idx
                                                    activeTab = "LIVE" // Navigate user to live map to view edit coordinates
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Visual Edit in Map",
                                                    tint = GeoPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    val updatedList = kmlPoints.toMutableList()
                                                    updatedList.removeAt(idx)
                                                    if (updatedList.isEmpty()) {
                                                        selectedIndex = null
                                                    } else if (selectedIndex != null && selectedIndex!! >= updatedList.size) {
                                                        selectedIndex = updatedList.size -1
                                                    }
                                                    commitPointsUpdate(updatedList)
                                                    statusMessage = "Deleted Node ${idx + 1}."
                                                    isError = false
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete Node entry",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "FILE" -> {
                    // FILE TAB: Live source synchronization explorer & copy/share exports
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Action row for importing/exporting files
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Upload Button
                            Button(
                                onClick = { fileLauncher.launch("*/*") },
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GeoPrimaryContainer, contentColor = GeoOnPrimaryContainer)
                            ) {
                                Icon(Icons.Default.Place, contentDescription = "Upload Icon", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("OPEN KML FILE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }

                            // Share track button (triggers universal android share sheet)
                            Button(
                                onClick = { 
                                    if (kmlInput.isNotBlank()) {
                                        downloadKmlFile(context, kmlInput, fileName ?: "KmlExplorer_Track.kml")
                                    } else {
                                        Toast.makeText(context, "No KML data to download!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GeoPrimary, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Download KML", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("DOWNLOAD KML", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }
                        }

                        // Metadata Editors - Shift Timeline Engine
                        Text(
                            text = "TRACK START DATETIME (GLOBAL SHIFT ENGINE)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = GeoSlate600)
                        )
                        Text(
                            text = "Updating the start date/time below will dynamically adjust and shift every tracking timestamp (<when> element) throughout the entire KML track automatically.",
                            style = MaterialTheme.typography.bodySmall.copy(color = GeoSlate600),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        val currentDateTime = try {
                            if (trackStartDatetime.isNotBlank()) 
                                java.time.OffsetDateTime.parse(trackStartDatetime).toLocalDateTime()
                            else java.time.LocalDateTime.now()
                        } catch(e:Exception) { java.time.LocalDateTime.now() }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    android.app.DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val newTime = currentDateTime.withYear(y).withMonth(m+1).withDayOfMonth(d)
                                            val iso = newTime.atOffset(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_INSTANT)
                                            trackStartDatetime = iso
                                            val updated = updateWhens(kmlInput, iso)
                                            if (updated != kmlInput) kmlInput = updated
                                        },
                                        currentDateTime.year, currentDateTime.monthValue - 1, currentDateTime.dayOfMonth
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Date: ${currentDateTime.toLocalDate()}")
                            }

                            OutlinedButton(
                                onClick = {
                                    android.app.TimePickerDialog(
                                        context,
                                        { _, h, m ->
                                            val newTime = currentDateTime.withHour(h).withMinute(m).withSecond(0)
                                            val iso = newTime.atOffset(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_INSTANT)
                                            trackStartDatetime = iso
                                            val updated = updateWhens(kmlInput, iso)
                                            if (updated != kmlInput) kmlInput = updated
                                        },
                                        currentDateTime.hour, currentDateTime.minute, true
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Time: ${currentDateTime.toLocalTime()}")
                            }
                        }
                        
                        HorizontalDivider(color = GeoSlate200, modifier = Modifier.padding(vertical = 4.dp))

                        // Code details metadata
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RAW TRACK XML SYNTAX",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = GeoSlate600)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = {
                                        if (kmlInput.isNotBlank()) {
                                            copyToClipboard(context, kmlInput)
                                            Log.i("MainActivity", "User copied KML XML content of size ${kmlInput.length} to clipboard.")
                                            Toast.makeText(context, "KML XML text copied to Clipboard!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = "Copy XML text", tint = GeoSlate600, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // raw text field editor code display
                        TextField(
                            value = kmlInput,
                            onValueChange = { 
                                kmlInput = it
                                if (fileName != null && kmlInput != it) {
                                    fileName = "Edited Track"
                                }
                            },
                            placeholder = { Text("Paste KML XML track data here. You can edit coordinates directly.", color = GeoSlate400) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .border(1.dp, GeoSlate200, RoundedCornerShape(12.dp)),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = GeoSlate50,
                                focusedContainerColor = GeoSlate50,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = GeoPrimary,
                                focusedTextColor = androidx.compose.ui.graphics.Color.Black,
                                unfocusedTextColor = androidx.compose.ui.graphics.Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Status notification bar
                        Text(
                            text = statusMessage,
                            color = if (isError) MaterialTheme.colorScheme.error else GeoSlate600,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            maxLines = 2
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = GeoSlate200)

        // Fully functional interactive Bottom Tabs Navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(GeoSurface)
                .padding(vertical = 12.dp, horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Surface(
                onClick = { activeTab = "LIVE" },
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                color = Color.Transparent
            ) {
                BottomNavItem(icon = Icons.Default.Place, label = "LIVE", selected = activeTab == "LIVE")
            }
            Surface(
                onClick = { activeTab = "GRAPH" },
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                color = Color.Transparent
            ) {
                BottomNavItem(icon = Icons.AutoMirrored.Filled.List, label = "GRAPH", selected = activeTab == "GRAPH")
            }
            Surface(
                onClick = { activeTab = "FILE" },
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                color = Color.Transparent
            ) {
                BottomNavItem(icon = Icons.Default.Info, label = "FILE", selected = activeTab == "FILE")
            }
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Import from Maps Link", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text("Paste the shared Google Maps link below:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://maps.app.goo.gl/...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUrlDialog = false
                    if (urlInput.isNotBlank()) {
                        Log.i("MainActivity", "User entered and confirmed import URL: '$urlInput'")
                        isProcessingSharedRoute = true
                        statusMessage = "Processing shared Google Maps link..."
                        isError = false
                        activeTab = "FILE"
                        
                        coroutineScope.launch {
                            val result = processSharedMapsText(context, urlInput)
                            if (result.errorMessage != null) {
                                statusMessage = result.errorMessage
                                isError = true
                                Log.e("MainActivity", "Manual URL processing failed: ${result.errorMessage}")
                            } else if (result.points.isNotEmpty()) {
                                val newXml = generateKmlFromPoints(result.points)
                                kmlInput = newXml
                                fileName = "Imported_Maps_Route.kml"
                                statusMessage = "Success: Generated route with ${result.points.size} pts and downloaded elevation."
                                isError = false
                                activeTab = "LIVE"
                                Log.i("MainActivity", "Successfully resolved manual URL input into KML containing ${result.points.size} points.")
                            } else {
                                statusMessage = "Could not find a valid route in the shared text."
                                isError = true
                                Log.e("MainActivity", "Manual URL input resolved but no direction points could be produced.")
                            }
                            isProcessingSharedRoute = false
                        }
                        urlInput = ""
                    } else {
                        Log.w("MainActivity", "User pressed import but the URL text input was blank.")
                    }
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BottomNavItem(icon: ImageVector, label: String, selected: Boolean) {
    val color = if (selected) GeoPrimary else GeoSlate400
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = color
            )
        )
    }
}

// Haversine spherical distance between two points in KM
fun calculateTotalDistance(points: List<KmlPoint>): Double {
    var distanceKm = 0.0
    for (i in 0 until points.size - 1) {
        val p1 = points[i]
        val p2 = points[i + 1]
        distanceKm += haversineDistance(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
    }
    return distanceKm
}

fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0 // Earth radius in km
    val degToRad = Math.PI / 180.0
    val dLat = (lat2 - lat1) * degToRad
    val dLon = (lon2 - lon1) * degToRad
    val a = sin(dLat / 2).pow(2) +
            cos(lat1 * degToRad) * cos(lat2 * degToRad) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

// Compute standard Min, Max elevation and accumulated cumulative height gains
fun calculateElevationStats(points: List<KmlPoint>): Triple<Double, Double, Double> {
    if (points.isEmpty()) return Triple(0.0, 0.0, 0.0)
    var minAlt = Double.MAX_VALUE
    var maxAlt = -Double.MAX_VALUE
    var gain = 0.0
    
    for (i in points.indices) {
        val alt = points[i].altitude
        if (alt < minAlt) minAlt = alt
        if (alt > maxAlt) maxAlt = alt
        
        if (i > 0) {
            val diff = alt - points[i - 1].altitude
            if (diff > 0) {
                gain += diff
            }
        }
    }
    
    if (minAlt == Double.MAX_VALUE) minAlt = 0.0
    if (maxAlt == -Double.MAX_VALUE) maxAlt = 0.0
    
    return Triple(minAlt, maxAlt, gain)
}

// Parses coordinates with optional high-fidelity Altitude extraction
fun parseKmlPoints(xml: String): List<KmlPoint> {
    val results = mutableListOf<KmlPoint>()
    val matches = "<coordinates>([\\s\\S]*?)</coordinates>".toRegex().findAll(xml)
    
    var tagFound = false
    for (match in matches) {
        tagFound = true
        val coordsStr = match.groupValues[1]
        val points = coordsStr.trim().split("\\s+".toRegex())
        for ((index, point) in points.withIndex()) {
            if (point.isBlank()) continue
            val parts = point.split(",")
            if (parts.size >= 2) {
                val lon = parts[0].trim().toDoubleOrNull()
                val lat = parts[1].trim().toDoubleOrNull()
                val alt = if (parts.size >= 3) parts[2].trim().toDoubleOrNull() ?: 0.0 else 0.0
                if (lon != null && lat != null) {
                    results.add(KmlPoint(latitude = lat, longitude = lon, altitude = alt))
                } else {
                    throw NumberFormatException("Malformed coordinate element at track point index $index: '$point'. Expected numerical 'longitude,latitude'.")
                }
            } else {
                throw IllegalArgumentException("Invalid coordinate element at track point index $index: '$point'. Expected structure 'longitude,latitude' or 'longitude,latitude,altitude'.")
            }
        }
    }
    
    if (!tagFound) {
       throw IllegalArgumentException("No <coordinates> container found in raw text. Check track integrity.")
    }
    return results
}

// Replaces inside segment coordinates of <coordinates> block to synchronize KML XML output
fun updateKmlCoordinates(originalXml: String, newPoints: List<KmlPoint>): String {
    val startTag = "<coordinates>"
    val endTag = "</coordinates>"
    
    val startIndex = originalXml.indexOf(startTag)
    val endIndex = originalXml.indexOf(endTag)
    
    if (startIndex == -1 || endIndex == -1 || endIndex < startIndex) {
        return buildMinimalKml(newPoints)
    }
    
    val formattedCoords = StringBuilder("\n")
    newPoints.forEach { pt ->
        formattedCoords.append("${pt.longitude},${pt.latitude},${pt.altitude} ")
    }
    formattedCoords.append("\n")
    
    val before = originalXml.substring(0, startIndex + startTag.length)
    val after = originalXml.substring(endIndex)
    var modifiedXml = before + formattedCoords.toString() + after

    val gxTrackRegex = "<gx:Track>([\\s\\S]*?)</gx:Track>".toRegex()
    if (gxTrackRegex.containsMatchIn(modifiedXml)) {
        val baseTimeStr = extractFirstWhen(originalXml)
        val baseTime = try { java.time.Instant.parse(baseTimeStr) } catch(e:Exception) { java.time.Instant.now() }
        
        var trackNodes = "\n"
        for (i in newPoints.indices) {
            val ptTime = baseTime.plusSeconds((i * 8).toLong())
            trackNodes += "<when>${ptTime}</when>\n"
        }
        for (i in newPoints.indices) {
            val pt = newPoints[i]
            trackNodes += "<gx:coord>${pt.longitude} ${pt.latitude} ${pt.altitude}</gx:coord>\n"
        }
        
        modifiedXml = gxTrackRegex.replaceFirst(modifiedXml, "<gx:Track>$trackNodes</gx:Track>")
    }

    return modifiedXml
}

fun buildMinimalKml(points: List<KmlPoint>): String {
    val date = java.time.Instant.now()
    var coordsStr = ""
    var trackNodes = ""
    for (i in points.indices) {
        val pt = points[i]
        coordsStr += "${pt.longitude},${pt.latitude},${pt.altitude}\n"
        val ptTime = date.plusSeconds((i * 8).toLong())
        val iso = ptTime.toString()
        trackNodes += "<when>$iso</when>\n"
    }
    for (i in points.indices) {
        val pt = points[i]
        trackNodes += "<gx:coord>${pt.longitude} ${pt.latitude} ${pt.altitude}</gx:coord>\n"
    }

    return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2" xmlns:kml="http://www.opengis.net/kml/2.2">
<Document>
<description><![CDATA[AlpineQuest Track 6/5/26 11:08:30 AM<br /><br />Generated by AlpineQuest®<br /><a href="https://www.alpinequest.net" alt="https://www.alpinequest.net">https://www.alpinequest.net</a>]]></description>
<visibility>1</visibility>
<open>1</open>
<Placemark>
<visibility>1</visibility>
<Style>
	<LineStyle>
		<width>3</width>
	</LineStyle>
</Style>
<LineString>
<tessellate>1</tessellate>
<altitudeMode>clampToGround</altitudeMode>
<coordinates>
$coordsStr</coordinates>
</LineString>
</Placemark>
<Placemark>
<visibility>1</visibility>
<Style>
	<LineStyle>
		<width>5</width>
	</LineStyle>
</Style>
<gx:MultiTrack>
<altitudeMode>clampToGround</altitudeMode>
<gx:Track>
$trackNodes</gx:Track>
</gx:MultiTrack>
</Placemark>
</Document>
</kml>"""
}

// Helper functions for metadata
fun extractDescription(xml: String): String {
    val matches = "<description><!\\[CDATA\\[(.*?)\\]\\]></description>".toRegex(RegexOption.DOT_MATCHES_ALL).find(xml)
    if (matches != null) return matches.groupValues[1]
    val plainMatches = "<description>(.*?)</description>".toRegex(RegexOption.DOT_MATCHES_ALL).find(xml)
    return plainMatches?.groupValues?.get(1) ?: ""
}

fun updateDescription(xml: String, newDesc: String): String {
    val cdataRegex = "<description><!\\[CDATA\\[.*?\\]\\]></description>".toRegex(RegexOption.DOT_MATCHES_ALL)
    if (cdataRegex.containsMatchIn(xml)) {
        return cdataRegex.replaceFirst(xml, "<description><![CDATA[\$newDesc]]></description>")
    }
    val plainRegex = "<description>.*?</description>".toRegex(RegexOption.DOT_MATCHES_ALL)
    if (plainRegex.containsMatchIn(xml)) {
        return plainRegex.replaceFirst(xml, "<description>\$newDesc</description>")
    }
    return xml
}

fun extractFirstWhen(xml: String): String {
    val match = "<when>(.*?)</when>".toRegex().find(xml)
    return match?.groupValues?.get(1) ?: ""
}

fun updateWhens(xml: String, newStartStr: String): String {
    val whenRegex = "<when>(.*?)</when>".toRegex()
    val match = whenRegex.find(xml)
    if (match != null) {
        try {
            val oldInst = Instant.parse(match.groupValues[1])
            val newInst = Instant.parse(newStartStr)
            val offset = Duration.between(oldInst, newInst)
            
            return whenRegex.replace(xml) { m ->
                try {
                    val inst = Instant.parse(m.groupValues[1])
                    "<when>\${inst.plus(offset)}</when>"
                } catch (e: Exception) {
                    m.value
                }
            }
        } catch (e: Exception) {
             return xml
        }
    }
    return xml
}

// Sharing XML KML Content
fun downloadKmlFile(context: Context, kmlContent: String, fileName: String) {
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/vnd.google-earth.kml+xml")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { os ->
                    os.write(kmlContent.toByteArray(Charsets.UTF_8))
                }
                android.widget.Toast.makeText(context, "Saved to Downloads", android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(downloadsDir, fileName)
            java.io.FileOutputStream(file).use {
                it.write(kmlContent.toByteArray(Charsets.UTF_8))
            }
            android.widget.Toast.makeText(context, "Saved to Downloads", android.widget.Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Failed to save: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("KML Track XML", text)
    clipboard.setPrimaryClip(clip)
}

@Composable
fun OsmMapView(
    points: List<GeoPoint>,
    highlightedIndex: Int?,
    onNodeSelected: (Int) -> Unit = {},
    onNodeMoved: (Int, GeoPoint) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(32.7563, 75.4733))
        }
    }

    LaunchedEffect(points, highlightedIndex) {
        if (points.isNotEmpty()) {
            mapView.overlays.clear()
            
            // Render path trail
            val polyline = Polyline()
            polyline.setPoints(points)
            polyline.outlinePaint.color = android.graphics.Color.BLUE
            polyline.outlinePaint.strokeWidth = 12f
            polyline.setOnClickListener { _, _, eventPos ->
                // Find closest point to click
                var minDist = Double.MAX_VALUE
                var closestIdx = 0
                points.forEachIndexed { i, p ->
                    val dist = haversineDistance(p.latitude, p.longitude, eventPos.latitude, eventPos.longitude)
                    if (dist < minDist) {
                        minDist = dist
                        closestIdx = i
                    }
                }
                onNodeSelected(closestIdx)
                true
            }
            mapView.overlays.add(polyline)
            
            // Draw Start Anchor
            val startMarker = Marker(mapView)
            startMarker.position = points.first()
            startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            startMarker.title = "Start Point"
            mapView.overlays.add(startMarker)
            
            // Draw End Anchor
            if (points.size > 1) {
                val endMarker = Marker(mapView)
                endMarker.position = points.last()
                endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                endMarker.title = "End Point"
                mapView.overlays.add(endMarker)
            }
            
            // Add custom active inspector pointer with smooth animated panning
            if (highlightedIndex != null && highlightedIndex in points.indices) {
                val activePoint = points[highlightedIndex]
                val highlightMarker = Marker(mapView).apply {
                    position = activePoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Selected Node #${highlightedIndex + 1}"
                    isDraggable = true
                    setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDragStart(marker: Marker) {}
                        override fun onMarkerDrag(marker: Marker) {}
                        override fun onMarkerDragEnd(marker: Marker) {
                            onNodeMoved(highlightedIndex, marker.position)
                        }
                    })
                }
                mapView.overlays.add(highlightMarker)
                
                // Smooth pan to active pointer position
                mapView.controller.animateTo(activePoint)
            } else {
                // Auto bound views fitting coordinates
                mapView.post {
                    try {
                        val boundingBox = BoundingBox.fromGeoPointsSafe(points)
                        if (boundingBox != null) {
                            mapView.zoomToBoundingBox(boundingBox, true, 100)
                        } else if (points.isNotEmpty()) {
                            mapView.controller.setCenter(points.first())
                            mapView.controller.setZoom(15.0)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            mapView.invalidate()
        } else {
             mapView.overlays.clear()
             mapView.invalidate()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}
