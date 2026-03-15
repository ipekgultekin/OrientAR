package com.example.orientar.treasure

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.R
import com.example.orientar.home.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

private val MetuRed = Color(0xFF8B0000)

class ScoreboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forcedSolved = intent.getIntExtra("forcedSolved", -1)
        val forcedTotal = intent.getIntExtra("forcedTotal", -1)

        // If not coming from game session, wipe stale local progress entirely
        // SharedPreferences may have old solved data from previous sessions
        if (forcedSolved < 0) {
            GameState.clearMemory()
            GameState.resetProgress(this)
        }
        GameState.loadQuestionsFromFirestore {}

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
    var questionCount by remember { mutableStateOf(
        if (forcedTotal >= 0) forcedTotal else GameState.totalQuestions()
    )}
    var solvedCount by remember { mutableStateOf(
        if (forcedSolved >= 0) forcedSolved else GameState.totalSolved
    )}
    // Only true if user actually has a Firebase leaderboard entry
    var isOnLeaderboard by remember { mutableStateOf(false) }
    var leaderboardTimeMs by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        // Load questions if needed
        while (questionCount == 0) {
            kotlinx.coroutines.delay(200)
            questionCount = GameState.totalQuestions()
            solvedCount = GameState.totalSolved
        }
        if (forcedSolved < 0) solvedCount = GameState.totalSolved
        if (forcedTotal < 0) questionCount = GameState.totalQuestions()

        // Force Firebase Auth token refresh to ensure currentUser is up to date
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        firebaseUser?.reload()?.addOnCompleteListener {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                FirebaseFirestore.getInstance()
                    .collection("leaderboard")
                    .document(uid)
                    .get()
                    .addOnSuccessListener { doc ->
                        if (doc.exists()) {
                            isOnLeaderboard = true
                            leaderboardTimeMs = doc.getLong("totalTimeMs") ?: 0L
                        } else {
                            isOnLeaderboard = false
                            leaderboardTimeMs = 0L
                        }
                    }
                    .addOnFailureListener {
                        isOnLeaderboard = false
                    }
            }
        }
    }

    val solved = solvedCount
    val total = questionCount
    val hasPartialProgress = total > 0 && solved > 0 && solved < total && !isOnLeaderboard
    // allCompleted = only shown to users with a Firebase leaderboard entry
    val allCompleted = isOnLeaderboard
    val totalSeconds = leaderboardTimeMs / 1000.0

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
                            text = "TREASURE HUNT",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MetuRed
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(context, MainActivity::class.java))
                    }) {
                        Text("✕", fontSize = 18.sp, color = Color.Gray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = { TreasureBottomBar() }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFFFFF8F8), Color(0xFFF5F5F5))
                    )
                )
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(text = "🗺️", fontSize = 72.sp)

                Text(
                    text = "METU NCC\nTreasure Hunt",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MetuRed,
                    textAlign = TextAlign.Center,
                    lineHeight = 34.sp
                )

                Text(
                    text = "Explore the campus, find the clues,\nand unlock AR treasures!",
                    fontSize = 15.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (allCompleted) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "🎉 You completed all questions!",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Your time: ${String.format(Locale.US, "%.1f", totalSeconds)}s",
                                fontSize = 13.sp,
                                color = Color(0xFF388E3C),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else if (hasPartialProgress) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "🔥 You've solved $solved / $total questions!",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Complete all $total questions and claim your spot on the leaderboard!",
                                fontSize = 13.sp,
                                color = Color(0xFFBF360C),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                            if (totalSeconds > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "⏱ Time so far: ${String.format(Locale.US, "%.1f", totalSeconds)}s",
                                    fontSize = 13.sp,
                                    color = Color(0xFF795548),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                var showReplayConfirm by remember { mutableStateOf(false) }

                if (showReplayConfirm) {
                    AlertDialog(
                        onDismissRequest = { showReplayConfirm = false },
                        title = { Text("Play Again?", fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "You've already completed the game! Playing again will remove your current leaderboard score. Are you sure?",
                                lineHeight = 20.sp
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showReplayConfirm = false
                                val intent = Intent(context, TreasureHuntGameActivity::class.java)
                                intent.putExtra("isNewGame", true)
                                context.startActivity(intent)
                            }) {
                                Text("Yes, Play Again", color = MetuRed, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showReplayConfirm = false }) {
                                Text("Cancel", color = Color.Gray)
                            }
                        }
                    )
                }

                Button(
                    onClick = {
                        when {
                            allCompleted -> showReplayConfirm = true
                            else -> {
                                val intent = Intent(context, TreasureHuntGameActivity::class.java)
                                intent.putExtra("isNewGame", false)
                                context.startActivity(intent)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MetuRed),
                    elevation = ButtonDefaults.buttonElevation(6.dp)
                ) {
                    Text(
                        text = when {
                            allCompleted -> "🔄  PLAY AGAIN"
                            hasPartialProgress -> "▶  CONTINUE GAME"
                            else -> "▶  START GAME"
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Show hint text when game is incomplete
                if (hasPartialProgress) {
                    Text(
                        text = "⚠️ You need to find all answers to appear on the leaderboard. No worries, you can play again anytime!",
                        fontSize = 12.sp,
                        color = Color(0xFFBF360C),
                        textAlign = TextAlign.Center,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(context, LeaderboardActivity::class.java))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, MetuRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MetuRed)
                ) {
                    Text(
                        text = "🏆  LEADERBOARD",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun TreasureBottomBar() {
    val context = LocalContext.current

    fun goToMain(tab: Int) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                putExtra("OPEN_TAB", tab)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
    }

    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        NavigationBarItem(
            icon = { Text("🏠", fontSize = 24.sp) },
            label = { Text("Home", fontSize = 11.sp) },
            selected = false,
            onClick = { goToMain(0) },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray
            )
        )
        NavigationBarItem(
            icon = { Text("📋", fontSize = 24.sp) },
            label = { Text("My Unit", fontSize = 11.sp) },
            selected = false,
            onClick = { goToMain(1) },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray
            )
        )
        NavigationBarItem(
            icon = { Text("👤", fontSize = 24.sp) },
            label = { Text("Profile", fontSize = 11.sp) },
            selected = false,
            onClick = { goToMain(2) },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray
            )
        )
    }
}