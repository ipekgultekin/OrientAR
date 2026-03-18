package com.example.orientar.home

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.R
import com.example.orientar.announcements.AnnouncementsActivity
import com.example.orientar.auth.WelcomeActivity
import com.example.orientar.chatbot.ChatbotActivity
import com.example.orientar.navigation.CampusTourActivity
import com.example.orientar.orientation.OrientationUnitScreen
import com.example.orientar.profile.ProfileScreen
import com.example.orientar.societies.SocietiesActivity
import com.example.orientar.treasure.ScoreboardActivity
import com.google.firebase.FirebaseApp

class MainActivity : ComponentActivity() {

    private var currentTab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        val userRole = intent.getStringExtra("USER_ROLE") ?: "student"
        currentTab.intValue = intent.getIntExtra("OPEN_TAB", 0)
        setContent {
            MaterialTheme {
                OrientationScreen(
                    userRole    = userRole,
                    selectedTab = currentTab.intValue,
                    onTabChange = { currentTab.intValue = it }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentTab.intValue = intent.getIntExtra("OPEN_TAB", 0)
    }
}

@Composable
fun OrientationScreen(
    userRole: String = "student",
    selectedTab: Int = 0,
    onTabChange: (Int) -> Unit = {}
) {
    val isGuest  = userRole == "guest"

    Scaffold(
        bottomBar = {
            MainBottomBar(
                selectedTab   = selectedTab,
                onTabSelected = { onTabChange(it) },
                showMyUnit    = !isGuest
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (isGuest) {
                when (selectedTab) {
                    0 -> HomeContent(userRole = userRole)
                    1 -> GuestProfileScreen()
                }
            } else {
                when (selectedTab) {
                    0 -> HomeContent(userRole = userRole)
                    1 -> OrientationUnitScreen()
                    2 -> ProfileScreen()
                }
            }
        }
    }
}

// ── Guest profile screen
@Composable
fun GuestProfileScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("👤", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "You're browsing as a guest",
            fontSize   = 20.sp,
            fontWeight = FontWeight.Bold,
            color      = Color(0xFF333333),
            textAlign  = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in or register to access your orientation group, treasure hunt, and full profile.",
            fontSize   = 14.sp,
            color      = Color(0xFF888888),
            textAlign  = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                context.startActivity(
                    Intent(context, WelcomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
        ) {
            Text("Sign In / Register", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

// ── Home content
@Composable
fun HomeContent(userRole: String = "student") {
    val context  = LocalContext.current
    val isLeader = userRole == "leader"
    val isGuest  = userRole == "guest"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 80.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
        ) {
            Image(
                painter            = painterResource(id = R.drawable.campus_banner),
                contentDescription = "METU NCC Campus",
                modifier           = Modifier.fillMaxSize(),
                contentScale       = ContentScale.Crop
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xBB000000), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter            = painterResource(id = R.drawable.metu_logo),
                    contentDescription = "METU Logo",
                    modifier           = Modifier
                        .size(32.dp)
                        .clip(CircleShape),
                    contentScale       = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text          = "Orientation",
                        fontSize      = 10.sp,
                        color         = Color(0xAAFFFFFF),
                        letterSpacing = 0.06.sp
                    )
                    Text(
                        text          = "METU NCC ",
                        fontSize      = 15.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = Color.White,
                        letterSpacing = (-0.2).sp
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xDD000000))
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text          = "Welcome to your new campus life.",
                    fontSize      = 20.sp,
                    fontWeight    = FontWeight.Bold,
                    color         = Color.White,
                    letterSpacing = (-0.3).sp,
                    lineHeight    = 26.sp
                )
            }
        }

        // Leader badge
        if (isLeader) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🏅", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "You are signed in as an Orientation Leader.",
                        fontSize   = 13.sp,
                        color      = Color(0xFF795548),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Guest notice
        if (isGuest) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ℹ️", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "You're browsing as a guest. Sign in for full access.",
                        fontSize = 13.sp,
                        color    = Color(0xFF1565C0)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Menu cards
        Column(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isGuest) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MenuCard("Campus Tour", "🔍", Modifier.weight(1f)) {
                        context.startActivity(Intent(context, CampusTourActivity::class.java))
                    }
                    MenuCard("Chatbot", "💬", Modifier.weight(1f)) {
                        context.startActivity(Intent(context, ChatbotActivity::class.java).apply {
                            putExtra("USER_ROLE", userRole)
                        })
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MenuCard("Student Societies", "👥", Modifier.weight(1f)) {
                        context.startActivity(Intent(context, SocietiesActivity::class.java).apply {
                            putExtra("USER_ROLE", userRole)
                        })
                    }
                    MenuCard("Announcements", "🔔", Modifier.weight(1f)) {
                        context.startActivity(Intent(context, AnnouncementsActivity::class.java))
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MenuCard("Campus Tour", "🔍", Modifier.weight(1f)) {
                        context.startActivity(Intent(context, CampusTourActivity::class.java))
                    }
                    MenuCard("Chatbot", "💬", Modifier.weight(1f)) {
                        context.startActivity(Intent(context, ChatbotActivity::class.java).apply {
                            putExtra("USER_ROLE", userRole)
                        })
                    }
                    MenuCard("Treasure Hunt", "🎁", Modifier.weight(1f)) {
                        context.startActivity(Intent(context, ScoreboardActivity::class.java))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MenuCard("Student Societies", "👥", Modifier.weight(1f)) {
                        context.startActivity(Intent(context, SocietiesActivity::class.java).apply {
                            putExtra("USER_ROLE", userRole)
                        })
                    }
                    MenuCard(
                        title    = if (isLeader) "Post Announcement" else "Announcements",
                        icon     = "🔔",
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isLeader) {
                            Toast.makeText(context, "Leader announcement feature coming soon", Toast.LENGTH_SHORT).show()
                        } else {
                            context.startActivity(Intent(context, AnnouncementsActivity::class.java))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── Bottom navigation bar
@Composable
fun MainBottomBar(
    selectedTab  : Int,
    onTabSelected: (Int) -> Unit,
    showMyUnit   : Boolean = true
) {
    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        NavigationBarItem(
            icon     = { Text("🏠", fontSize = 24.sp) },
            label    = { Text("Home", fontSize = 11.sp) },
            selected = selectedTab == 0,
            onClick  = { onTabSelected(0) },
            colors   = NavigationBarItemDefaults.colors(
                selectedTextColor   = Color(0xFF8B0000),
                unselectedIconColor = Color.Gray,
                indicatorColor      = Color.Transparent
            )
        )
        if (showMyUnit) {
            NavigationBarItem(
                icon     = { Text("📋", fontSize = 24.sp) },
                label    = { Text("My Unit", fontSize = 11.sp) },
                selected = selectedTab == 1,
                onClick  = { onTabSelected(1) },
                colors   = NavigationBarItemDefaults.colors(
                    selectedTextColor   = Color(0xFF8B0000),
                    unselectedIconColor = Color.Gray,
                    indicatorColor      = Color.Transparent
                )
            )
        }
        NavigationBarItem(
            icon     = { Text("👤", fontSize = 24.sp) },
            label    = { Text("Profile", fontSize = 11.sp) },
            selected = if (showMyUnit) selectedTab == 2 else selectedTab == 1,
            onClick  = { onTabSelected(if (showMyUnit) 2 else 1) },
            colors   = NavigationBarItemDefaults.colors(
                selectedTextColor   = Color(0xFF8B0000),
                unselectedIconColor = Color.Gray,
                indicatorColor      = Color.Transparent
            )
        )
    }
}

// ── Reusable menu card
@Composable
fun MenuCard(title: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick   = onClick,
        modifier  = modifier
            .height(100.dp)
            .border(1.dp, Color(0xFFEEEEEE), RoundedCornerShape(12.dp)),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = icon, fontSize = 32.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text       = title,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign  = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}