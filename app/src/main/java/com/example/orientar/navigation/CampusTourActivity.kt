package com.example.orientar.navigation

// --- Android Core Imports ---
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.*

// --- AndroidX & Core Imports ---
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

// --- Google Maps Imports ---
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

// --- Google Play Services Location ---
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

// --- Project Logic Imports ---
import com.example.orientar.navigation.logic.CampusGraph
import com.example.orientar.navigation.logic.GeoProjection
import com.example.orientar.navigation.logic.Node
import com.example.orientar.R

/**
 * Main Activity (Launcher) for the OrientAR application.
 *
 * Responsibilities:
 * 1. Displays a Google Map of the campus with markers for all destinations.
 * 2. Allows the user to search a "From" (Start) and "To" (End) destination via AutoCompleteTextViews.
 * 3. Toggles between "Map Mode" (2D external navigation) and "AR Mode" (3D internal navigation).
 * 4. Handles permissions for location access to show the user's position on the map.
 */
class CampusTourActivity : AppCompatActivity(), OnMapReadyCallback {

    // UI Components
    private lateinit var mMap: GoogleMap
    private lateinit var inputFrom: AutoCompleteTextView
    private lateinit var inputTo: AutoCompleteTextView
    private lateinit var btnModeMap: TextView
    private lateinit var btnModeAR: TextView
    private lateinit var btnStart: ImageButton
    private lateinit var btnSwap: ImageButton
    private lateinit var chipMyLocation: LinearLayout
    private lateinit var toggleContainer: FrameLayout
    private lateinit var toggleIndicator: View

    // Data & Graph Logic
    private lateinit var campusGraph: CampusGraph
    private var destinationList: List<Node> = emptyList()

    // Selected node references (tracked via dropdown clicks)
    private var selectedFromNode: Node? = null
    private var selectedToNode: Node? = null

    // State
    private var isARMode = false // default 2D (Map)

    // --- My Location chip state (SCRUM-56 Phase 2) ---
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private var pendingLocationAction: (() -> Unit)? = null
    private var isFetchingLocation = false
    private var locationCancellationSource: CancellationTokenSource? = null

    // Permission Launcher for Location Access. Serves two purposes:
    //   1. Enable the map's "My Location" blue-dot layer (existing behaviour).
    //   2. Resume a pending chip action after a permission prompt (SCRUM-56).
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        val coarse = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        if (fine || coarse) {
            enableMyLocation()
            // Capture-then-null-then-invoke: if the invoked action sets a new
            // pendingLocationAction, it won't be immediately nulled out.
            val action = pendingLocationAction
            pendingLocationAction = null
            action?.invoke()
        } else {
            pendingLocationAction = null
            Toast.makeText(
                this,
                "Location permission required to use current location",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // White status bar with dark icons
        window.statusBarColor = android.graphics.Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        setContentView(R.layout.activity_campus_tour)

        // Push top panel below the status bar
        val topPanel = findViewById<LinearLayout>(R.id.topPanel)
        topPanel.setOnApplyWindowInsetsListener { view, insets ->
            val statusBarHeight = insets.systemWindowInsetTop
            view.setPadding(
                view.paddingLeft,
                statusBarHeight + (14 * resources.displayMetrics.density).toInt(),
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        // Initialize UI Elements
        inputFrom = findViewById(R.id.inputFrom)
        inputTo = findViewById(R.id.inputTo)
        btnModeMap = findViewById(R.id.btnModeMap)
        btnModeAR = findViewById(R.id.btnModeAR)
        btnStart = findViewById(R.id.btnStartNavigation)
        btnSwap = findViewById(R.id.btnSwap)
        chipMyLocation = findViewById(R.id.chipMyLocation)
        toggleContainer = findViewById(R.id.toggleContainer)
        toggleIndicator = findViewById(R.id.toggleIndicator)

        // Position the sliding indicator once layout is measured
        toggleContainer.post {
            positionIndicator(isARMode, animate = false)
        }

        // Bottom bar entrance animation — slide up + fade in
        val bottomBar = findViewById<FrameLayout>(R.id.bottomBar)
        bottomBar.translationY = 60f
        bottomBar.alpha = 0f
        bottomBar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(300)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()

        // Push bottom bar above the system navigation bar
        bottomBar.setOnApplyWindowInsetsListener { view, insets ->
            val navBarHeight = insets.systemWindowInsetBottom
            val params = view.layoutParams as android.widget.RelativeLayout.LayoutParams
            params.bottomMargin = navBarHeight + (16 * resources.displayMetrics.density).toInt()
            view.layoutParams = params
            insets
        }

        // Initialize Graph and Data
        campusGraph = CampusGraph(this)
        destinationList = campusGraph.destinations

        // Initialize Google Map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupInputFields()
        setupButtons()
        selectMode(false) // Start in Map Mode by default
    }

    /**
     * Populates the "From" and "To" AutoCompleteTextViews with destination names
     * and wires up item-click listeners to track the selected Node references.
     */
    private fun setupInputFields() {
        if (destinationList.isEmpty()) {
            Toast.makeText(this, "ERROR: Destination list is empty!", Toast.LENGTH_LONG).show()
            return
        }

        val placeNames = destinationList.map { it.name ?: "Unknown Location" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, placeNames)

        inputFrom.setAdapter(adapter)
        inputTo.setAdapter(adapter)

        // Make fields non-editable — tapping opens a search dialog instead
        inputFrom.isFocusable = false
        inputFrom.isClickable = true
        inputFrom.setOnClickListener { showDestinationSearchDialog(isFromField = true) }

        inputTo.isFocusable = false
        inputTo.isClickable = true
        inputTo.setOnClickListener { showDestinationSearchDialog(isFromField = false) }

        // Fields start blank — users must consciously pick both locations
    }

    /**
     * Sets up click listeners for mode switching, navigation start, swap, and My Location chip.
     */
    private fun setupButtons() {
        btnModeMap.setOnClickListener { selectMode(false) }
        btnModeAR.setOnClickListener { selectMode(true) }

        // Go button press animation (scale-down on press, overshoot on release)
        btnStart.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                }
            }
            true
        }
        btnStart.setOnClickListener { handleNavigationStart() }

        btnSwap.setOnClickListener {
            // Swap text
            val fromText = inputFrom.text.toString()
            val toText = inputTo.text.toString()
            inputFrom.setText(toText, false)
            inputTo.setText(fromText, false)

            // Swap stored nodes
            val tempNode = selectedFromNode
            selectedFromNode = selectedToNode
            selectedToNode = tempNode

            // Animate the swap button (180° rotation)
            btnSwap.animate()
                .rotationBy(180f)
                .setDuration(300)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }

        chipMyLocation.setOnClickListener {
            handleMyLocationTap()
        }
    }

    /**
     * Slides the toggle indicator to the active tab. Pass animate=false on first layout
     * (the View hasn't been measured yet, so we just set translationX without animating).
     */
    private fun positionIndicator(arMode: Boolean, animate: Boolean) {
        val targetView = if (arMode) btnModeAR else btnModeMap

        val targetLeft = targetView.left
        val targetWidth = targetView.width
        val targetHeight = targetView.height

        if (!animate) {
            val params = toggleIndicator.layoutParams as FrameLayout.LayoutParams
            params.width = targetWidth
            params.height = targetHeight
            toggleIndicator.layoutParams = params
            toggleIndicator.translationX = targetLeft.toFloat()
        } else {
            // Resize indicator to target width
            val params = toggleIndicator.layoutParams as FrameLayout.LayoutParams
            params.width = targetWidth
            params.height = targetHeight
            toggleIndicator.layoutParams = params

            // Slide animation
            toggleIndicator.animate()
                .translationX(targetLeft.toFloat())
                .setDuration(200)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
    }

    /**
     * Toggles the UI state between Map Mode and AR Mode.
     * Slides the indicator, crossfades text colors, and re-tints icons.
     */
    private fun selectMode(arMode: Boolean) {
        isARMode = arMode

        // Slide the indicator
        positionIndicator(arMode, animate = true)

        // Crossfade text colors
        val activeColor = resources.getColor(R.color.campus_toggle_border, theme)
        val inactiveColor = resources.getColor(R.color.campus_inactive_text, theme)

        val activeBtn = if (arMode) btnModeAR else btnModeMap
        val inactiveBtn = if (arMode) btnModeMap else btnModeAR

        activeBtn.setTextColor(activeColor)
        activeBtn.setTypeface(null, android.graphics.Typeface.BOLD)
        inactiveBtn.setTextColor(inactiveColor)
        inactiveBtn.setTypeface(null, android.graphics.Typeface.NORMAL)

        // Reset icons (so we can re-tint a fresh mutated copy)
        activeBtn.setCompoundDrawablesWithIntrinsicBounds(
            if (arMode) R.drawable.ic_campus_ar else R.drawable.ic_campus_map, 0, 0, 0
        )
        inactiveBtn.setCompoundDrawablesWithIntrinsicBounds(
            if (arMode) R.drawable.ic_campus_map else R.drawable.ic_campus_ar, 0, 0, 0
        )

        activeBtn.compoundDrawables[0]?.let {
            val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(it.mutate())
            androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, activeColor)
        }
        inactiveBtn.compoundDrawables[0]?.let {
            val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(it.mutate())
            androidx.core.graphics.drawable.DrawableCompat.setTint(wrapped, inactiveColor)
        }
    }

    /**
     * Validates selections and launches the appropriate navigation flow (AR or 2D Map).
     */
    private fun handleNavigationStart() {
        val fromText = inputFrom.text.toString().trim()
        val toText = inputTo.text.toString().trim()

        if (fromText.isEmpty() || toText.isEmpty()) {
            Toast.makeText(this, "Please select both start and destination", Toast.LENGTH_SHORT).show()
            return
        }

        if (fromText == toText) {
            Toast.makeText(this, "Start and destination must be different", Toast.LENGTH_SHORT).show()
            return
        }

        // Validate that text matches an actual destination
        val startNode = selectedFromNode ?: destinationList.find { (it.name ?: "") == fromText }
        val targetNode = selectedToNode ?: destinationList.find { (it.name ?: "") == toText }

        if (startNode == null) {
            Toast.makeText(this, "Invalid starting point: \"$fromText\"", Toast.LENGTH_SHORT).show()
            return
        }
        if (targetNode == null) {
            Toast.makeText(this, "Invalid destination: \"$toText\"", Toast.LENGTH_SHORT).show()
            return
        }

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

        // Step 1: Tap marker → just show info window
        mMap.setOnMarkerClickListener { marker ->
            marker.showInfoWindow()
            true
        }

        // Step 2: Tap the info window → ask whether to set as start or destination
        mMap.setOnInfoWindowClickListener { marker ->
            val markerTitle = marker.title ?: return@setOnInfoWindowClickListener
            val selectedNode = destinationList.find { (it.name ?: "") == markerTitle }
                ?: return@setOnInfoWindowClickListener

            val dialogView = layoutInflater.inflate(R.layout.dialog_marker_choice, null)
            dialogView.findViewById<TextView>(R.id.markerName).text = markerTitle

            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .create()

            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_campus_dialog)

            dialogView.findViewById<LinearLayout>(R.id.btnSetStart).setOnClickListener {
                inputFrom.setText(markerTitle, false)
                selectedFromNode = selectedNode
                Toast.makeText(this, "Start: $markerTitle", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }

            dialogView.findViewById<LinearLayout>(R.id.btnSetDestination).setOnClickListener {
                inputTo.setText(markerTitle, false)
                selectedToNode = selectedNode
                Toast.makeText(this, "Destination: $markerTitle", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }

            dialog.show()
        }

        enableMyLocation()
    }

    /**
     * Opens a search dialog with a searchable, scrollable list of destinations.
     * @param isFromField true to set the "From" field, false to set the "To" field.
     */
    private fun showDestinationSearchDialog(isFromField: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_destination_search, null)
        val titleText = dialogView.findViewById<TextView>(R.id.dialogTitle)
        titleText.text = if (isFromField) "Select starting point" else "Select destination"
        val searchEditText = dialogView.findViewById<EditText>(R.id.searchEditText)
        val listView = dialogView.findViewById<ListView>(R.id.destinationListView)

        val placeNames = destinationList.map { it.name ?: "Unknown Location" }

        // Capture the current value so we can highlight it in the list
        val currentValue = if (isFromField) inputFrom.text.toString() else inputTo.text.toString()

        val adapter = object : ArrayAdapter<String>(this, R.layout.item_destination, android.R.id.text1, placeNames.toMutableList()) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val itemText = getItem(position) ?: ""
                val checkIcon = view.findViewById<ImageView>(R.id.checkIcon)

                if (itemText == currentValue) {
                    view.setBackgroundColor(android.graphics.Color.parseColor("#0A4CAF50"))
                    checkIcon.visibility = View.VISIBLE
                    checkIcon.setImageResource(R.drawable.ic_campus_check)
                } else {
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    checkIcon.visibility = View.GONE
                }
                return view
            }
        }
        listView.adapter = adapter

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Apply rounded corners to the dialog window
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_campus_dialog)

        // Clear (X) button — tap the end drawable to clear the search text
        searchEditText.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val drawableEnd = searchEditText.compoundDrawablesRelative[2]
                if (drawableEnd != null) {
                    val clearBtnStart = searchEditText.width - searchEditText.paddingEnd - drawableEnd.intrinsicWidth
                    if (event.x >= clearBtnStart) {
                        searchEditText.text.clear()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        // Filter list as user types, and toggle the clear X icon based on content
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter.filter(s)
                val clearIcon = if (s?.isNotEmpty() == true) {
                    resources.getDrawable(android.R.drawable.ic_menu_close_clear_cancel, theme)
                } else null
                searchEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    resources.getDrawable(R.drawable.ic_campus_search, theme),
                    null, clearIcon, null
                )
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // When user taps a destination in the list
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedName = adapter.getItem(position) ?: return@setOnItemClickListener
            val selectedNode = destinationList.find { (it.name ?: "") == selectedName }

            if (isFromField) {
                inputFrom.setText(selectedName, false)
                selectedFromNode = selectedNode
            } else {
                inputTo.setText(selectedName, false)
                selectedToNode = selectedNode
            }

            dialog.dismiss()
        }

        dialog.show()
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

    // ============================================================================================
    // MY LOCATION CHIP — phantom-node virtual start (SCRUM-56 Phase 2)
    // ============================================================================================

    /**
     * Entry point for the My Location chip tap. Fetches the user's current GPS position,
     * validates accuracy, and launches AR with a virtual start intent.
     *
     * Unlike Phase 1 (which populated the From field with the nearest named destination), this
     * handler launches AR directly — the phantom-node routing in [ArNavigationActivity] projects
     * the user's GPS onto the nearest path edge and begins the route there.
     *
     * Required precondition: the user must have picked a destination first (selectedToNode
     * non-null). Otherwise we show a clear, actionable Toast.
     */
    @SuppressLint("MissingPermission")
    private fun handleMyLocationTap() {
        // Guard 1: destination must be picked before we can route.
        val targetNode = selectedToNode
        if (targetNode == null) {
            Toast.makeText(
                this,
                "Please select a destination first, then tap My Location.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Guard 2: double-tap / parallel-fetch protection.
        if (isFetchingLocation) {
            Toast.makeText(this, "Already fetching location...", Toast.LENGTH_SHORT).show()
            return
        }

        // Guard 3: permission — accept FINE or COARSE. FINE is preferred for high accuracy;
        // COARSE is sufficient to avoid a permission-request loop if the user only granted
        // approximate location.
        val hasFine = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            pendingLocationAction = { handleMyLocationTap() }
            locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }

        // Guard 4: device-level location services must be on (at least one provider).
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gpsEnabled && !netEnabled) {
            Toast.makeText(
                this,
                "Please enable location services in device settings",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Kick off the fetch.
        isFetchingLocation = true
        Toast.makeText(this, "Getting your location...", Toast.LENGTH_SHORT).show()

        // Cancel any previous in-flight fetch; start a fresh cancellation source.
        locationCancellationSource?.cancel()
        val source = CancellationTokenSource()
        locationCancellationSource = source

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, source.token)
            .addOnSuccessListener { location ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                isFetchingLocation = false

                if (location == null) {
                    Toast.makeText(
                        this,
                        "Could not get your location, please try again",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addOnSuccessListener
                }

                // D11: accuracy gate — refuse if GPS uncertainty exceeds the snap tolerance cap.
                if (location.accuracy > GeoProjection.MAX_ACCURACY_M) {
                    Toast.makeText(
                        this,
                        "GPS signal too weak (${location.accuracy.toInt()}m). Please move to an open area and try again.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@addOnSuccessListener
                }

                // All gates passed — launch AR with virtual-start intent.
                startArActivityFromVirtual(
                    lat = location.latitude,
                    lng = location.longitude,
                    accuracy = location.accuracy,
                    endId = targetNode.id
                )
            }
            .addOnFailureListener { e ->
                if (isFinishing || isDestroyed) return@addOnFailureListener
                isFetchingLocation = false
                android.util.Log.w("CampusTour", "getCurrentLocation failed", e)
                Toast.makeText(
                    this,
                    "Could not get your location, please try again",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    /**
     * Launches AR navigation with a virtual (GPS-projected) start. Parallel to [startArActivity]
     * for the named-destination flow — kept as a separate helper (D15) so both handoff modes
     * are discoverable in one place and the intent-extras knowledge stays localized.
     */
    private fun startArActivityFromVirtual(
        lat: Double,
        lng: Double,
        accuracy: Float,
        endId: Int
    ) {
        val intent = Intent(this, ArNavigationActivity::class.java)
        intent.putExtra("START_MODE", "VIRTUAL")
        intent.putExtra("VIRTUAL_LAT", lat)
        intent.putExtra("VIRTUAL_LNG", lng)
        intent.putExtra("VIRTUAL_ACCURACY", accuracy)
        intent.putExtra("END_NODE_ID", endId)
        startActivity(intent)
    }

    override fun onDestroy() {
        locationCancellationSource?.cancel()
        locationCancellationSource = null
        super.onDestroy()
    }
}
