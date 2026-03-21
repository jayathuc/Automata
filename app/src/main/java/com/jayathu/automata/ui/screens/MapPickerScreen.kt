@file:Suppress("DEPRECATION")
package com.jayathu.automata.ui.screens

import android.preference.PreferenceManager
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.openlocationcode.OpenLocationCode
import com.jayathu.automata.data.model.MapProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker as OsmMarker
import java.net.URLEncoder

// Default center: Colombo, Sri Lanka
private const val DEFAULT_LAT = 6.9271
private const val DEFAULT_LNG = 79.8612
private const val DEFAULT_ZOOM = 15.0

private data class SearchResult(
    val displayName: String,
    val lat: Double,
    val lng: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerScreen(
    mapProvider: MapProvider,
    googleMapsApiKey: String,
    onLocationPicked: (plusCode: String) -> Unit,
    onBack: () -> Unit
) {
    var selectedLat by remember { mutableStateOf<Double?>(null) }
    var selectedLng by remember { mutableStateOf<Double?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var showResults by remember { mutableStateOf(false) }

    // Callback to move the map — set by the active map composable
    var moveMapTo by remember { mutableStateOf<((Double, Double) -> Unit)?>(null) }
    var mapLoaded by remember { mutableStateOf(false) }

    // Fall back to OSM if Google Maps selected but no API key
    val effectiveProvider = if (mapProvider == MapProvider.GOOGLE_MAPS && googleMapsApiKey.isBlank()) {
        LaunchedEffect(Unit) {
            snackbarHostState.showSnackbar("No Google Maps API key. Using OpenStreetMap.")
        }
        MapProvider.OPENSTREETMAP
    } else {
        mapProvider
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick Location") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (selectedLat != null && selectedLng != null) {
                FloatingActionButton(
                    onClick = {
                        val plusCode = OpenLocationCode.encode(selectedLat!!, selectedLng!!, 11)
                        onLocationPicked(plusCode)
                    }
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Confirm location")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Map layer
            when (effectiveProvider) {
                MapProvider.OPENSTREETMAP -> {
                    OsmMapView(
                        selectedLat = selectedLat,
                        selectedLng = selectedLng,
                        onLocationSelected = { lat, lng ->
                            selectedLat = lat
                            selectedLng = lng
                            showResults = false
                        },
                        onMapReady = { moveFn ->
                            moveMapTo = moveFn
                            mapLoaded = true
                        }
                    )
                }
                MapProvider.GOOGLE_MAPS -> {
                    GoogleMapView(
                        apiKey = googleMapsApiKey,
                        selectedLat = selectedLat,
                        selectedLng = selectedLng,
                        onLocationSelected = { lat, lng ->
                            selectedLat = lat
                            selectedLng = lng
                            showResults = false
                        },
                        onMapReady = { moveFn ->
                            moveMapTo = moveFn
                            mapLoaded = true
                        }
                    )
                }
            }

            // Loading indicator
            if (!mapLoaded) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Loading map...",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            // Search bar + results overlay
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .align(Alignment.TopCenter)
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search location") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (isSearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(8.dp),
                                    strokeWidth = 2.dp
                                )
                            } else if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    searchResults = emptyList()
                                    showResults = false
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                }

                // Search results dropdown
                if (showResults && searchResults.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 250.dp),
                        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        LazyColumn {
                            items(searchResults) { result ->
                                Text(
                                    text = result.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedLat = result.lat
                                            selectedLng = result.lng
                                            searchQuery = result.displayName
                                            showResults = false
                                            moveMapTo?.invoke(result.lat, result.lng)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    maxLines = 2
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // Plus code label at bottom
            if (selectedLat != null && selectedLng != null) {
                val plusCode = OpenLocationCode.encode(selectedLat!!, selectedLng!!, 11)
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Text(
                        text = plusCode,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // Trigger search when query changes (with debounce)
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 3) {
            searchResults = emptyList()
            showResults = false
            return@LaunchedEffect
        }
        // Simple debounce
        kotlinx.coroutines.delay(500)
        isSearching = true
        try {
            val results = searchNominatim(searchQuery)
            searchResults = results
            showResults = results.isNotEmpty()
        } catch (_: Exception) {
            searchResults = emptyList()
            showResults = false
        }
        isSearching = false
    }
}

private suspend fun searchNominatim(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=5&countrycodes=lk"
    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    connection.setRequestProperty("User-Agent", "Automata-Android-App")
    connection.connectTimeout = 5000
    connection.readTimeout = 5000

    try {
        val response = connection.inputStream.bufferedReader().readText()
        val jsonArray = JSONArray(response)
        val results = mutableListOf<SearchResult>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            results.add(
                SearchResult(
                    displayName = obj.getString("display_name"),
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lon")
                )
            )
        }
        results
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun OsmMapView(
    selectedLat: Double?,
    selectedLng: Double?,
    onLocationSelected: (Double, Double) -> Unit,
    onMapReady: ((Double, Double) -> Unit) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = context.packageName
    }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var currentMarker by remember { mutableStateOf<OsmMarker?>(null) }

    // Expose move function to parent
    LaunchedEffect(mapView) {
        mapView?.let { view ->
            onMapReady { lat, lng ->
                view.controller.animateTo(GeoPoint(lat, lng), DEFAULT_ZOOM, 500L)
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(DEFAULT_ZOOM)
                controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LNG))

                val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        onLocationSelected(p.latitude, p.longitude)
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean = false
                })
                overlays.add(0, eventsOverlay)

                // Fix scroll conflict with parent
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }

                mapView = this
            }
        },
        update = { view ->
            // Update marker when selection changes
            if (selectedLat != null && selectedLng != null) {
                currentMarker?.let { view.overlays.remove(it) }
                val marker = OsmMarker(view).apply {
                    position = GeoPoint(selectedLat, selectedLng)
                    setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                    title = "Selected location"
                }
                view.overlays.add(marker)
                currentMarker = marker
                view.invalidate()
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onDetach()
        }
    }
}

@Composable
private fun GoogleMapView(
    apiKey: String,
    selectedLat: Double?,
    selectedLng: Double?,
    onLocationSelected: (Double, Double) -> Unit,
    onMapReady: ((Double, Double) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Set API key at runtime
    LaunchedEffect(apiKey) {
        try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            appInfo.metaData?.putString("com.google.android.geo.API_KEY", apiKey)
        } catch (_: Exception) { }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(DEFAULT_LAT, DEFAULT_LNG), DEFAULT_ZOOM.toFloat()
        )
    }

    // Expose move function to parent
    LaunchedEffect(Unit) {
        onMapReady { lat, lng ->
            scope.launch {
                cameraPositionState.animate(
                    com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
                        LatLng(lat, lng), DEFAULT_ZOOM.toFloat()
                    )
                )
            }
        }
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        onMapClick = { latLng ->
            onLocationSelected(latLng.latitude, latLng.longitude)
        }
    ) {
        if (selectedLat != null && selectedLng != null) {
            Marker(
                state = MarkerState(position = LatLng(selectedLat, selectedLng)),
                title = "Selected location"
            )
        }
    }
}
