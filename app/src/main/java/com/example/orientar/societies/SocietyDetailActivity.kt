package com.example.orientar.societies

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.R
import com.google.firebase.firestore.FirebaseFirestore

private val MetuRed = Color(0xFF8B0000)

data class SocietyDetailUiModel(
    val id: String,
    val name: String,
    val description: String
)

class SocietyDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val societyId = intent.getStringExtra("society_id") ?: ""
        val userRole = intent.getStringExtra("USER_ROLE") ?: "student"

        setContent {
            MaterialTheme {
                SocietyDetailScreen(
                    societyId = societyId,
                    userRole = userRole
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocietyDetailScreen(
    societyId: String,
    userRole: String = "student"
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var society by remember { mutableStateOf<SocietyDetailUiModel?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(societyId) {
        fetchSocietyDetail(
            societyId = societyId,
            onSuccess = {
                society = it
                isLoading = false
            },
            onError = { error ->
                errorMessage = error
                isLoading = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
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
                            text = "METU NCC ORIENTATION",
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(padding)
                .padding(16.dp)
                .padding(bottom = 80.dp)
        ) {
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
                            text = errorMessage ?: "Failed to load society details.",
                            color = Color.Red
                        )
                    }
                }

                society != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "🏛️  ${society!!.name}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MetuRed
                        )

                        HorizontalDivider(color = Color(0xFFE0E0E0))

                        Text(
                            text = if (society!!.description.isNotBlank()) {
                                society!!.description
                            } else {
                                "No description has been added for this society yet."
                            },
                            fontSize = 15.sp,
                            color = Color.Black,
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

private fun fetchSocietyDetail(
    societyId: String,
    onSuccess: (SocietyDetailUiModel) -> Unit,
    onError: (String) -> Unit
) {
    FirebaseFirestore.getInstance()
        .collection("student_societies")
        .document(societyId)
        .get()
        .addOnSuccessListener { doc ->
            if (!doc.exists()) {
                onError("Society not found.")
                return@addOnSuccessListener
            }

            val details = doc.get("details") as? Map<*, *>
            val name =
                details?.get("student association name")?.toString()
                    ?: doc.getString("name")
                    ?: "Unnamed Society"

            val description = doc.getString("description").orEmpty()

            onSuccess(
                SocietyDetailUiModel(
                    id = doc.id,
                    name = name,
                    description = description
                )
            )
        }
        .addOnFailureListener { exception ->
            onError(exception.localizedMessage ?: "Failed to load society details.")
        }
}