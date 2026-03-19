package com.example.orientar.societies

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.R
import com.example.orientar.home.MainActivity
import com.google.firebase.firestore.FirebaseFirestore

private val MetuRed = Color(0xFF8B0000)

data class SocietyUiModel(
    val id: String,
    val name: String,
    val description: String
)

class SocietiesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val userRole = intent.getStringExtra("USER_ROLE") ?: "student"

        setContent {
            MaterialTheme {
                SocietiesScreen(userRole = userRole)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocietiesScreen(userRole: String = "student") {
    val context = LocalContext.current
    val activity = context as? Activity
    var searchQuery by remember { mutableStateOf("") }

    var societies by remember { mutableStateOf<List<SocietyUiModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        fetchSocieties(
            onSuccess = { fetched ->
                societies = fetched
                isLoading = false
            },
            onError = { error ->
                errorMessage = error
                isLoading = false
            }
        )
    }

    val filteredSocieties = societies.filter {
        it.name.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MetuRed
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.metu_logo),
                            contentDescription = "METU Logo",
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "METU NCC ORIENTATION",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MetuRed
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = { SocietiesBottomBar(userRole = userRole) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(padding)
                .padding(16.dp)
                .padding(bottom = 80.dp)
        ) {
            Text(
                "Student Societies",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MetuRed
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search societies") },
                placeholder = { Text("Type a society name...") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MetuRed)
                    }
                }

                errorMessage != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = errorMessage ?: "An unexpected error occurred.",
                            color = Color.Red,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                filteredSocieties.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank())
                                "No societies found."
                            else
                                "No matching society found.",
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredSocieties) { society ->
                            SocietyCard(
                                society = society,
                                onDetailsClick = {
                                    val intent = Intent(context, SocietyDetailActivity::class.java).apply {
                                        putExtra("society_id", society.id)
                                        putExtra("USER_ROLE", userRole)
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fetchSocieties(
    onSuccess: (List<SocietyUiModel>) -> Unit,
    onError: (String) -> Unit
) {
    FirebaseFirestore.getInstance()
        .collection("student_societies")
        .get()
        .addOnSuccessListener { result ->
            val societies = result.documents.map { doc ->
                val details = doc.get("details") as? Map<*, *>
                val name =
                    details?.get("student association name")?.toString()
                        ?: doc.getString("name")
                        ?: "Unnamed Society"

                val description = doc.getString("description").orEmpty()

                SocietyUiModel(
                    id = doc.id,
                    name = name,
                    description = description
                )
            }.sortedBy { it.name.lowercase() }

            onSuccess(societies)
        }
        .addOnFailureListener { exception ->
            onError(exception.localizedMessage ?: "Failed to load societies.")
        }
}

@Composable
fun SocietyCard(
    society: SocietyUiModel,
    onDetailsClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, MetuRed, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🏛️", fontSize = 34.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = society.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            TextButton(
                onClick = onDetailsClick,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Get details", color = MetuRed)
            }
        }
    }
}

@Composable
fun SocietiesBottomBar(userRole: String = "student") {
    val context = LocalContext.current
    val isGuest = userRole == "guest"

    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        NavigationBarItem(
            icon = { Text("🏠", fontSize = 24.sp) },
            label = { Text("Home", fontSize = 11.sp, maxLines = 1) },
            selected = false,
            onClick = {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("USER_ROLE", userRole)
                    }
                )
            },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor = Color.Transparent
            )
        )

        if (!isGuest) {
            NavigationBarItem(
                icon = { Text("📋", fontSize = 24.sp) },
                label = {
                    Text(
                        "My Unit",
                        fontSize = 10.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        lineHeight = 11.sp
                    )
                },
                selected = false,
                onClick = {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("USER_ROLE", userRole)
                            putExtra("OPEN_TAB", 1)
                        }
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = Color.Transparent
                )
            )
        }

        NavigationBarItem(
            icon = { Text("👤", fontSize = 24.sp) },
            label = { Text("Profile", fontSize = 11.sp, maxLines = 1) },
            selected = false,
            onClick = {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("USER_ROLE", userRole)
                        putExtra("OPEN_TAB", if (isGuest) 1 else 2)
                    }
                )
            },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor = Color.Transparent
            )
        )
    }
}