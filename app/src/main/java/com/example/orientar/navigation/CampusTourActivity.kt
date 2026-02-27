package com.example.orientar.navigation

// --- Android Core Imports ---
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*

// --- AndroidX & Core Imports ---
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

// --- Google Maps Imports ---
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

// --- Project Logic Imports ---
import com.example.orientar.navigation.logic.CampusGraph
import com.example.orientar.navigation.logic.Node
import com.example.orientar.R

/**
 * Main Activity (Launcher) for the OrientAR application.
 *
 * Responsibilities:
 * 1. Displays a Google Map of the campus with markers for all destinations.
 * 2. Allows the user to select a "From" (Start) and "To" (End) destination via Spinners.
 * 3. Toggles between "Map Mode" (2D external navigation) and "AR Mode" (3D internal navigation).
 * 4. Handles permissions for location access to show the user's position on the map.
 */
class CampusTourActivity : AppCompatActivity(), OnMapReadyCallback {

    // UI Components
    private lateinit var mMap: GoogleMap
    private lateinit var spinnerFrom: Spinner
    private lateinit var spinnerTo: Spinner
    private lateinit var btnModeMap: Button
    private lateinit var btnModeAR: Button
    private lateinit var btnStart: Button

    // Data & Graph Logic
    private lateinit var campusGraph: CampusGraph
    private var destinationList: List<Node> = emptyList()

    // State
    private var isARMode = false // default 2D (Map)

    // Permission Launcher for Location Access
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        val coarse = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        if (fine || coarse) enableMyLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_campus_tour)

        // Initialize UI Elements
        spinnerFrom = findViewById(R.id.spinnerFrom)
        spinnerTo = findViewById(R.id.spinnerTo)
        btnModeMap = findViewById(R.id.btnModeMap)
        btnModeAR = findViewById(R.id.btnModeAR)
        btnStart = findViewById(R.id.btnStartNavigation)

        // Initialize Graph and Data
        campusGraph = CampusGraph(this)
        destinationList = campusGraph.destinations

        // Initialize Google Map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupSpinners()
        setupButtons()
        selectMode(false) // Start in Map Mode by default
    }

    /**
     * Populates the "From" and "To" spinners with destination names from the Graph.
     */
    private fun setupSpinners() {
        if (destinationList.isEmpty()) {
            Toast.makeText(this, "ERROR: Destination list is empty!", Toast.LENGTH_LONG).show()
            return
        }

        // Map destination names for the adapter
        val placeNames = destinationList.map { it.name ?: "Unknown Location" }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, placeNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerFrom.adapter = adapter
        spinnerTo.adapter = adapter

        // Set default selection to be different (e.g., first and second item)
        if (placeNames.size > 1) spinnerTo.setSelection(1)
    }

    /**
     * Sets up click listeners for mode switching and navigation start.
     */
    private fun setupButtons() {
        btnModeMap.setOnClickListener { selectMode(false) }
        btnModeAR.setOnClickListener { selectMode(true) }
        btnStart.setOnClickListener { handleNavigationStart() }
    }

    /**
     * Toggles the UI state between Map Mode and AR Mode.
     * Updates button colors to reflect the active mode.
     */
    private fun selectMode(arMode: Boolean) {
        isARMode = arMode
        if (arMode) {
            btnModeAR.setBackgroundColor(Color.parseColor("#D32F2F"))
            btnModeAR.setTextColor(Color.WHITE)
            btnModeMap.setBackgroundColor(Color.parseColor("#EEEEEE"))
            btnModeMap.setTextColor(Color.BLACK)
        } else {
            btnModeMap.setBackgroundColor(Color.parseColor("#D32F2F"))
            btnModeMap.setTextColor(Color.WHITE)
            btnModeAR.setBackgroundColor(Color.parseColor("#EEEEEE"))
            btnModeAR.setTextColor(Color.BLACK)
        }
    }

    /**
     * Validates selections and launches the appropriate navigation flow (AR or 2D Map).
     */
    private fun handleNavigationStart() {
        val fromSelection = spinnerFrom.selectedItem.toString()
        val toSelection = spinnerTo.selectedItem.toString()

        if (fromSelection == toSelection) {
            Toast.makeText(this, "Start and Destination cannot be the same!", Toast.LENGTH_SHORT).show()
            return
        }

        // Find Node objects based on selected names
        val startNode = destinationList.find { (it.name ?: "") == fromSelection }
        val targetNode = destinationList.find { (it.name ?: "") == toSelection }

        if (startNode == null || targetNode == null) return

        if (isARMode) {
            // Launch AR Activity with selected Node IDs
            startArActivity(startNode.id, targetNode.id)
        } else {
            // Launch Google Maps (External Intent)
            launchExternalMap(startNode, targetNode)
        }
    }

    /**
     * Starts the AR Navigation Activity passing the Start and End Node IDs.
     */
    private fun startArActivity(startId: Int, endId: Int) {
        val intent = Intent(this, ArNavigationActivity::class.java)
        intent.putExtra("START_NODE_ID", startId)
        intent.putExtra("END_NODE_ID", endId)
        startActivity(intent)
    }

    /**
     * Opens an external map application (Google Maps) to show walking directions.
     * Uses a universal URL scheme.
     */
    private fun launchExternalMap(from: Node, to: Node) {
        val uriString = "https://www.google.com/maps/dir/?api=1&origin=${from.lat},${from.lng}&destination=${to.lat},${to.lng}&travelmode=walking"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
        intent.setPackage("com.google.android.apps.maps")

        try {
            startActivity(intent)
        } catch (e: Exception) {
            // If Google Maps app is not installed, clear package to allow browser fallback
            intent.setPackage(null)
            startActivity(intent)
        }
    }

    /**
     * Callback when the Google Map is ready to be used.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Center camera on the campus (Default logic: Use first node or hardcoded center)
        val centerLat = if (destinationList.isNotEmpty()) destinationList[0].lat else 35.248
        val centerLng = if (destinationList.isNotEmpty()) destinationList[0].lng else 33.021

        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLng), 16f))

        // Add markers for all destinations in the graph
        for (node in destinationList) {
            mMap.addMarker(
                MarkerOptions()
                    .position(LatLng(node.lat, node.lng))
                    .title(node.name ?: "Node ${node.id}")
            )
        }
        enableMyLocation()
    }

    /**
     * Enables the "My Location" layer on the map if permissions are granted.
     */
    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
        } else {
            locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
}