package com.example.orientar.societies

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.home.MainActivity
import com.example.orientar.home.SharedBottomBar
import com.google.firebase.firestore.FirebaseFirestore

private val MetuRed     = Color(0xFF8B0000)
private val MetuRedDark = Color(0xFF5C0000)
private val Surface     = Color(0xFFF7F4F4)

private val cardTints = listOf(
    Color(0xFFFFF3F3), Color(0xFFF3F3FF), Color(0xFFF3FFF3),
    Color(0xFFFFF9F0), Color(0xFFF0FEFF), Color(0xFFFFF0F9)
)
private val accentColors = listOf(
    Color(0xFF8B0000), Color(0xFF1A237E), Color(0xFF1B5E20),
    Color(0xFFE65100), Color(0xFF006064), Color(0xFF880E4F)
)

data class SocietyUiModel(
    val id: String,
    val name: String,
    val description: String,
    val chairman: String = ""
)

class SocietiesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val userRole = intent.getStringExtra("USER_ROLE") ?: "student"
        setContent { MaterialTheme { SocietiesScreen(userRole = userRole) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocietiesScreen(userRole: String = "student") {
    val context  = LocalContext.current
    val activity = context as? Activity

    var searchQuery by remember { mutableStateOf("") }
    var societies   by remember { mutableStateOf<List<SocietyUiModel>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }
    var errorMsg    by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        fetchSocieties(
            onSuccess = { societies = it; isLoading = false },
            onError   = { errorMsg = it; isLoading = false }
        )
    }

    val filtered = societies.filter { it.name.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        bottomBar = { SharedBottomBar(userRole = userRole) },
        containerColor = Surface
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(MetuRedDark, MetuRed)))
                    .padding(top = 8.dp, bottom = 20.dp, start = 20.dp, end = 20.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { activity?.finish() }) {
                            Text("←", fontSize = 22.sp, color = Color.White)
                        }
                    }
                    Text("Student Societies", fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = (-0.5).sp)
                    Text("${societies.size} clubs & associations",
                        fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        placeholder = { Text("Search societies…", fontSize = 14.sp, color = Color.White.copy(0.6f)) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(18.dp))
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor      = Color.White.copy(0.6f),
                            unfocusedBorderColor    = Color.White.copy(0.3f),
                            focusedTextColor        = Color.White,
                            unfocusedTextColor      = Color.White,
                            cursorColor             = Color.White,
                            focusedContainerColor   = Color.White.copy(0.15f),
                            unfocusedContainerColor = Color.White.copy(0.1f)
                        )
                    )
                }
            }

            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MetuRed)
                }
                errorMsg != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(errorMsg ?: "", color = Color.Red, textAlign = TextAlign.Center)
                }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔍", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(if (searchQuery.isBlank()) "No societies found." else "No results for \"$searchQuery\"",
                            color = Color.Gray, textAlign = TextAlign.Center)
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(filtered) { index, society ->
                        SocietyCard(
                            society = society,
                            tint    = cardTints[index % cardTints.size],
                            accent  = accentColors[index % accentColors.size],
                            onClick = {
                                context.startActivity(
                                    Intent(context, SocietyDetailActivity::class.java).apply {
                                        putExtra("society_id", society.id)
                                        putExtra("USER_ROLE", userRole)
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SocietyCard(society: SocietyUiModel, tint: Color, accent: Color, onClick: () -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth().clickable { onClick() },
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = tint),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(society.name.firstOrNull()?.toString() ?: "S",
                    fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(society.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1A1A1A), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (society.chairman.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text("President: ${society.chairman}", fontSize = 12.sp,
                        color = Color(0xFF777777), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, null, tint = accent.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }
}

private fun fetchSocieties(onSuccess: (List<SocietyUiModel>) -> Unit, onError: (String) -> Unit) {
    FirebaseFirestore.getInstance().collection("student_societies").get()
        .addOnSuccessListener { result ->
            val list = result.documents.map { doc ->
                val details  = doc.get("details") as? Map<*, *>
                val name     = details?.get("student association name")?.toString()
                    ?: doc.getString("name") ?: "Unnamed Society"
                val chairman = details?.get("chairman")?.toString() ?: ""
                SocietyUiModel(
                    id          = doc.id,
                    name        = name,
                    description = doc.getString("description").orEmpty(),
                    chairman    = chairman
                )
            }.sortedBy { it.name.lowercase() }
            onSuccess(list)
        }
        .addOnFailureListener { onError(it.localizedMessage ?: "Failed to load.") }
}