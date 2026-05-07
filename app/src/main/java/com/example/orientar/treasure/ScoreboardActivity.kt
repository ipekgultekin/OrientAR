package com.example.orientar.treasure
import android.widget.Toast
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.home.MainActivity
import com.example.orientar.home.SharedBottomBar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

private val MetuRed     = Color(0xFF8B0000)
private val MetuRedDark = Color(0xFF5C0000)

class ScoreboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forcedSolved = intent.getIntExtra("forcedSolved", -1)
        val forcedTotal  = intent.getIntExtra("forcedTotal", -1)

        setContent {
            MaterialTheme {
                TreasureHuntLandingScreen(
                    forcedSolved = forcedSolved,
                    forcedTotal = forcedTotal
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreasureHuntLandingScreen(forcedSolved: Int = -1, forcedTotal: Int = -1) {
    val context = LocalContext.current
    var questionCount    by remember { mutableStateOf(if (forcedTotal >= 0) forcedTotal else GameState.totalQuestions()) }
    var solvedCount      by remember { mutableStateOf(if (forcedSolved >= 0) forcedSolved else GameState.totalSolved) }
    var isOnLeaderboard  by remember { mutableStateOf(false) }
    var leaderboardTimeMs by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        GameState.loadQuestionsFromFirestore {
            GameState.loadProgress(context)

            if (forcedSolved < 0) solvedCount = GameState.totalSolved
            if (forcedTotal < 0) questionCount = GameState.totalQuestions()

            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid == null) {
                isOnLeaderboard = false
                return@loadQuestionsFromFirestore
            }

            FirebaseFirestore.getInstance()
                .collection("leaderboard")
                .document(uid)
                .get()
                .addOnSuccessListener { doc ->
                    isOnLeaderboard = doc.exists()
                    leaderboardTimeMs = if (doc.exists()) doc.getLong("totalTimeMs") ?: 0L else 0L
                }
                .addOnFailureListener {
                    isOnLeaderboard = false
                }
        }
    }

    val solved       = solvedCount
    val total        = questionCount
    val allCompleted = isOnLeaderboard
    val hasPartial   = total > 0 && solved > 0 && solved < total && !isOnLeaderboard
    val totalSeconds = leaderboardTimeMs / 1000.0
    var showReplayConfirm by remember { mutableStateOf(false) }

    //I added this one since the testing process
    fun resetTreasureHuntForTesting() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid

        GameState.resetProgress(context)
        solvedCount = 0
        leaderboardTimeMs = 0L
        isOnLeaderboard = false

        if (uid != null) {
            FirebaseFirestore.getInstance()
                .collection("leaderboard")
                .document(uid)
                .delete()
                .addOnCompleteListener {
                    Toast.makeText(context, "Treasure Hunt progress reset.", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(context, "Treasure Hunt progress reset.", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        bottomBar = { SharedBottomBar(userRole = "student") },
        containerColor = Color(0xFFF7F4F4)
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // ── Hero header ───────────────────────────────────────────────────
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(MetuRedDark, MetuRed)))
                    .padding(top = 8.dp, bottom = 28.dp, start = 20.dp, end = 20.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { context.startActivity(Intent(context, MainActivity::class.java)) }) {
                            Text("✕", fontSize = 18.sp, color = Color.White.copy(0.8f))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Map, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Treasure Hunt", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text("METU NCC Campus", fontSize = 13.sp, color = Color.White.copy(0.7f))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Explore the campus, find the clues,\nand unlock AR treasures.",
                        fontSize = 14.sp, color = Color.White.copy(0.8f), lineHeight = 20.sp
                    )
                }
            }

            // ── Content ───────────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Progress / completion card
                if (allCompleted) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(16.dp),
                        colors   = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.EmojiEvents, null,
                                tint = Color(0xFF2E7D32), modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("All questions completed!", fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                Text("Your time: ${String.format(Locale.US, "%.1f", totalSeconds)}s",
                                    fontSize = 13.sp, color = Color(0xFF388E3C))
                            }
                        }
                    }
                } else if (hasPartial) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(16.dp),
                        colors   = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("You solved $solved of $total clues",
                                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { solved.toFloat() / total.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color    = Color(0xFFE65100),
                                trackColor = Color(0xFFFFE0B2)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Tap Continue Game to keep searching from where you left off.",
                                fontSize = 12.sp,
                                color = Color(0xFFBF360C),
                                lineHeight = 17.sp
                            )
                        }
                    }
                } else if (total > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(16.dp),
                        colors   = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📍", fontSize = 24.sp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("$total clues hidden around campus",
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
                                Text("Use AR to find and unlock each one.",
                                    fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Primary action button
                Button(
                    onClick = {
                        when {
                            allCompleted -> showReplayConfirm = true
                            else -> {
                                context.startActivity(Intent(context, TreasureHuntGameActivity::class.java).apply {
                                    putExtra("isNewGame", false)
                                })
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape  = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MetuRed),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Icon(
                        if (allCompleted) Icons.Outlined.Replay else Icons.Outlined.PlayArrow,
                        null, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            allCompleted -> "Play Again"
                            hasPartial   -> "Continue Game"
                            else         -> "Start Game"
                        },
                        fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }

                // Leaderboard button
                OutlinedButton(
                    onClick = { context.startActivity(Intent(context, LeaderboardActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape  = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MetuRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MetuRed)
                ) {
                    Icon(Icons.Outlined.EmojiEvents, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Leaderboard", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                //Reset button for testing process
                OutlinedButton(
                    onClick = { resetTreasureHuntForTesting() },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.Gray),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
                ) {
                    Icon(Icons.Outlined.Replay, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restart Game (Test)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showReplayConfirm) {
        AlertDialog(
            onDismissRequest = { showReplayConfirm = false },
            title = { Text("Play Again?", fontWeight = FontWeight.Bold) },
            text  = { Text("Playing again will remove your current leaderboard score. Are you sure?", lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showReplayConfirm = false

                    val uid = FirebaseAuth.getInstance().currentUser?.uid
                    GameState.resetProgress(context)

                    if (uid != null) {
                        FirebaseFirestore.getInstance()
                            .collection("leaderboard")
                            .document(uid)
                            .delete()
                            .addOnCompleteListener {
                                context.startActivity(Intent(context, TreasureHuntGameActivity::class.java).apply {
                                    putExtra("isNewGame", true)
                                })
                            }
                    } else {
                        context.startActivity(Intent(context, TreasureHuntGameActivity::class.java).apply {
                            putExtra("isNewGame", true)
                        })
                    }
                }) {
                    Text("Yes, Play Again", color = MetuRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplayConfirm = false }) { Text("Cancel", color = Color.Gray) }
            }
        )
    }
}