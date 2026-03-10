package com.example.orientar.treasure

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import java.util.Locale

private val MetuRed = Color(0xFF8B0000)

class ScoreboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameState.loadProgress(this)
        setContent {
            MaterialTheme {
                TreasureHuntLandingScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreasureHuntLandingScreen() {
    val context = LocalContext.current
    val solved = remember { GameState.totalSolved }
    val total = remember { GameState.totalQuestions() }
    val hasPartialProgress = solved > 0 && solved < total
    val totalSeconds = remember { GameState.totalTimeMs / 1000.0 }

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
                    .padding(horizontal = 28.dp),
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

                if (hasPartialProgress) {
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

                Button(
                    onClick = {
                        val intent = Intent(context, TreasureHuntGameActivity::class.java)
                        intent.putExtra("isNewGame", solved == total && total > 0)
                        context.startActivity(intent)
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
                            hasPartialProgress -> "▶  CONTINUE GAME"
                            solved == total && total > 0 -> "🔄  PLAY AGAIN"
                            else -> "▶  START GAME"
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
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
    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        NavigationBarItem(
            icon = { Text("🏠", fontSize = 24.sp) },
            label = { Text("Home", fontSize = 11.sp) },
            selected = false,
            onClick = { context.startActivity(Intent(context, MainActivity::class.java)) },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray
            )
        )
        NavigationBarItem(
            icon = { Text("📋", fontSize = 24.sp) },
            label = { Text("My Orientation\nUnit", fontSize = 10.sp, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 11.sp) },
            selected = false,
            onClick = { Toast.makeText(context, "Coming Soon...", Toast.LENGTH_SHORT).show() },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray
            )
        )
        NavigationBarItem(
            icon = { Text("👤", fontSize = 24.sp) },
            label = { Text("Profile", fontSize = 11.sp) },
            selected = false,
            onClick = { Toast.makeText(context, "Coming Soon...", Toast.LENGTH_SHORT).show() },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray
            )
        )
    }
}