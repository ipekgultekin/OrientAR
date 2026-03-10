package com.example.orientar.treasure

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.util.Locale

private val MetuRed = Color(0xFF8B0000)

data class LeaderboardEntry(
    val name: String = "",
    val solvedCount: Int = 0,
    val totalTimeMs: Long = 0L
)

class LeaderboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LeaderboardScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        FirebaseFirestore.getInstance()
            .collection("leaderboard")
            .orderBy("totalTimeMs")
            .limit(50)
            .get()
            .addOnSuccessListener { snapshot ->
                entries = snapshot.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val solved = (doc.getLong("solvedCount") ?: 0L).toInt()
                    val time = doc.getLong("totalTimeMs") ?: 0L
                    LeaderboardEntry(name, solved, time)
                }.sortedWith(
                    compareByDescending<LeaderboardEntry> { it.solvedCount }
                        .thenBy { it.totalTimeMs }
                )
                loading = false
            }
            .addOnFailureListener {
                loading = false
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.metu_logo),
                            contentDescription = "METU Logo",
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "LEADERBOARD",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MetuRed
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { (context as? LeaderboardActivity)?.finish() }) {
                        Text("←", fontSize = 22.sp, color = MetuRed)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
                .padding(paddingValues)
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MetuRed
                    )
                }
                entries.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🏆", fontSize = 64.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No entries yet!\nBe the first to finish!",
                            fontSize = 18.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(entries) { index, entry ->
                            LeaderboardRow(rank = index + 1, entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LeaderboardRow(rank: Int, entry: LeaderboardEntry) {
    val medalEmoji = when (rank) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> "#$rank"
    }
    val isMedal = rank <= 3
    val cardColor = when (rank) {
        1 -> Color(0xFFFFF9C4)
        2 -> Color(0xFFF5F5F5)
        3 -> Color(0xFFFFF3E0)
        else -> Color.White
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(if (isMedal) 4.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = medalEmoji,
                fontSize = if (isMedal) 28.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.Center,
                color = if (!isMedal) Color.Gray else Color.Unspecified
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    text = "${entry.solvedCount} questions solved",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }

            Text(
                text = String.format(Locale.US, "%.1fs", entry.totalTimeMs / 1000.0),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MetuRed
            )
        }
    }
}