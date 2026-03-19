package com.example.orientar.societies

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore

private val MetuRed     = Color(0xFF8B0000)
private val MetuRedDark = Color(0xFF5C0000)

data class SocietyDetailUiModel(
    val id: String,
    val name: String,
    val description: String,
    val chairman: String = "",
    val chairmanEmail: String = "",
    val academicAdvisor: String = ""
)

data class SocietyDetailUiModel(
    val id: String,
    val name: String,
    val description: String
)

class SocietyDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val societyId = intent.getStringExtra("society_id") ?: ""
        val userRole  = intent.getStringExtra("USER_ROLE") ?: "student"
        setContent { MaterialTheme { SocietyDetailScreen(societyId = societyId, userRole = userRole) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocietyDetailScreen(societyId: String, userRole: String = "student") {
    val context  = LocalContext.current
    val activity = context as? Activity

    var society   by remember { mutableStateOf<SocietyDetailUiModel?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(societyId) {
        fetchSocietyDetail(
            societyId = societyId,
            onSuccess = { society = it; isLoading = false },
            onError   = { errorMsg = it; isLoading = false }
        )
    }

    Scaffold(
        bottomBar      = { SocietiesBottomBar(userRole = userRole) },
        containerColor = Color(0xFFF7F4F4)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MetuRed)
                }
                errorMsg != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(errorMsg ?: "", color = Color.Red, textAlign = TextAlign.Center)
                }
                society != null -> {
                    val s = society!!

                    // Hero
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(MetuRedDark, MetuRed)))
                            .padding(top = 8.dp, bottom = 28.dp, start = 20.dp, end = 20.dp)
                    ) {
                        Column {
                            IconButton(onClick = { activity?.finish() }) {
                                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(s.name.firstOrNull()?.toString() ?: "S",
                                    fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(s.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                                color = Color.White, lineHeight = 28.sp)
                        }
                    }

                    // Content
                    Column(
                        Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        // President & Advisor card
                        val hasChairman = s.chairman.isNotBlank()
                        val hasAdvisor  = s.academicAdvisor.isNotBlank()

                        if (hasChairman || hasAdvisor) {
                            Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(20.dp),
                                colors    = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Column(Modifier.padding(20.dp)) {
                                    Text("PEOPLE", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        color = MetuRed, letterSpacing = 1.2.sp)
                                    Spacer(Modifier.height(10.dp))
                                    HorizontalDivider(color = Color(0xFFEEEEEE))
                                    Spacer(Modifier.height(14.dp))

                                    if (hasChairman) {
                                        PersonRow(
                                            icon        = Icons.Default.Person,
                                            role        = "President",
                                            name        = s.chairman,
                                            email       = s.chairmanEmail,
                                            accentColor = MetuRed,
                                            context     = context
                                        )
                                    }
                                    if (hasChairman && hasAdvisor) {
                                        Spacer(Modifier.height(12.dp))
                                        HorizontalDivider(color = Color(0xFFF5F5F5))
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    if (hasAdvisor) {
                                        PersonRow(
                                            icon        = Icons.Default.School,
                                            role        = "Academic Advisor",
                                            name        = s.academicAdvisor,
                                            email       = "",
                                            accentColor = Color(0xFF1A237E),
                                            context     = context
                                        )
                                    }
                                }
                            }
                        }

                        // About card
                        Card(
                            modifier  = Modifier.fillMaxWidth(),
                            shape     = RoundedCornerShape(20.dp),
                            colors    = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text("ABOUT", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    color = MetuRed, letterSpacing = 1.2.sp)
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = Color(0xFFEEEEEE))
                                Spacer(Modifier.height(14.dp))

                                if (s.description.isNotBlank()) {
                                    val paragraphs = s.description.split("\n").filter { it.isNotBlank() }
                                    paragraphs.forEachIndexed { i, para ->
                                        Text(para.trim(), fontSize = 15.sp,
                                            color = Color(0xFF2A2A2A), lineHeight = 24.sp)
                                        if (i < paragraphs.lastIndex) Spacer(Modifier.height(12.dp))
                                    }
                                } else {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("📋", fontSize = 40.sp)
                                            Spacer(Modifier.height(8.dp))
                                            Text("No description available yet.",
                                                color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonRow(
    icon: ImageVector,
    role: String,
    name: String,
    email: String,
    accentColor: Color,
    context: android.content.Context
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(accentColor.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(role, fontSize = 11.sp, color = Color.Gray)
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
        }
        if (email.isNotBlank()) {
            IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
            }) {
                Icon(Icons.Default.Email, null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun fetchSocietyDetail(societyId: String, onSuccess: (SocietyDetailUiModel) -> Unit, onError: (String) -> Unit) {
    FirebaseFirestore.getInstance().collection("student_societies").document(societyId).get()
        .addOnSuccessListener { doc ->
            if (!doc.exists()) { onError("Society not found."); return@addOnSuccessListener }
            val details = doc.get("details") as? Map<*, *>
            val name    = details?.get("student association name")?.toString()
                ?: doc.getString("name") ?: "Unnamed Society"
            onSuccess(
                SocietyDetailUiModel(
                    id              = doc.id,
                    name            = name,
                    description     = doc.getString("description").orEmpty(),
                    chairman        = details?.get("chairman")?.toString() ?: "",
                    chairmanEmail   = details?.get("chairman email")?.toString()
                        ?: details?.get("email")?.toString() ?: "",
                    academicAdvisor = details?.get("academic advisor")?.toString() ?: ""
                )
            )
        }
        .addOnFailureListener { onError(it.localizedMessage ?: "Failed to load.") }
}