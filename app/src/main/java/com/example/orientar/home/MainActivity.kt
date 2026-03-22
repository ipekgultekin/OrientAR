package com.example.orientar.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

private val MetuRed = Color(0xFF8B0000)

class MainActivity : ComponentActivity() {
    private var currentTab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        val userRole    = intent.getStringExtra("USER_ROLE") ?: "student"
        val leaderDocId = intent.getStringExtra("LEADER_DOC_ID") ?: ""
        currentTab.intValue = intent.getIntExtra("OPEN_TAB", 0)
        setContent {
            MaterialTheme {
                OrientationScreen(
                    userRole    = userRole,
                    leaderDocId = leaderDocId,
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
    leaderDocId: String = "",
    selectedTab: Int = 0,
    onTabChange: (Int) -> Unit = {}
) {
    val isGuest = userRole == "guest"
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
                    0 -> HomeContent(userRole = userRole, leaderDocId = leaderDocId)
                    1 -> GuestProfileScreen()
                }
            } else {
                when (selectedTab) {
                    0 -> HomeContent(userRole = userRole, leaderDocId = leaderDocId)
                    1 -> OrientationUnitScreen()
                    2 -> ProfileScreen()
                }
            }
        }
    }
}

@Composable
fun GuestProfileScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Person, null, modifier = Modifier.size(64.dp), tint = MetuRed.copy(0.4f))
        Spacer(Modifier.height(16.dp))
        Text("You're browsing as a guest", fontSize = 20.sp, fontWeight = FontWeight.Bold,
            color = Color(0xFF333333), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Sign in or register to access your orientation group, treasure hunt, and full profile.",
            fontSize = 14.sp, color = Color(0xFF888888), textAlign = TextAlign.Center, lineHeight = 20.sp)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                context.startActivity(Intent(context, WelcomeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape  = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MetuRed)
        ) { Text("Sign In / Register", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
}

@Composable
fun HomeContent(userRole: String = "student", leaderDocId: String = "") {
    val context  = LocalContext.current
    val isLeader = userRole == "leader"
    val isGuest  = userRole == "guest"

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF7F4F4))
            .verticalScroll(rememberScrollState()).padding(bottom = 80.dp)
    ) {
        // Hero banner
        Box(modifier = Modifier.fillMaxWidth().height(230.dp)) {
            Image(
                painter = painterResource(id = R.drawable.campus_banner),
                contentDescription = "METU NCC Campus",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.metu_logo),
                    contentDescription = "METU Logo",
                    modifier = Modifier.size(30.dp).clip(CircleShape),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Orientation", fontSize = 10.sp, color = Color(0xAAFFFFFF), letterSpacing = 1.sp)
                    Text("METU NCC", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text("Welcome to your new campus life.", fontSize = 19.sp,
                    fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 25.sp)
            }
        }

        // Status badges
        if (isLeader) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(Color(0xFFFFF3E0), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Star, null, tint = Color(0xFFBF6900), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Signed in as Orientation Leader", fontSize = 13.sp,
                    color = Color(0xFF795548), fontWeight = FontWeight.Medium)
            }
        }
        if (isGuest) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(Color(0xFFE3F2FD), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Info, null, tint = Color(0xFF1565C0), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browsing as guest. Sign in for full access.", fontSize = 13.sp, color = Color(0xFF1565C0))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Quick Access", modifier = Modifier.padding(horizontal = 16.dp),
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = Color(0xFF999999), letterSpacing = 1.2.sp)
        Spacer(Modifier.height(12.dp))

        // ── 2x2 grid for everyone ──────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Row 1
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuCard("Campus Tour", Icons.Outlined.Map, Modifier.weight(1f)) {
                    context.startActivity(Intent(context, CampusTourActivity::class.java))
                }
                MenuCard("Chatbot", Icons.Outlined.Chat, Modifier.weight(1f)) {
                    context.startActivity(Intent(context, ChatbotActivity::class.java).apply {
                        putExtra("USER_ROLE", userRole)
                    })
                }
            }
            // Row 2
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuCard("Societies", Icons.Outlined.Groups, Modifier.weight(1f)) {
                    context.startActivity(Intent(context, SocietiesActivity::class.java).apply {
                        putExtra("USER_ROLE", userRole)
                    })
                }
                MenuCard("Announcements", Icons.Outlined.Notifications, Modifier.weight(1f)) {
                    context.startActivity(Intent(context, AnnouncementsActivity::class.java).apply {
                        putExtra("USER_ROLE", userRole)
                        if (isLeader) putExtra("LEADER_DOC_ID", leaderDocId)
                    })
                }
            }
            // Row 3 — only for students and leaders (not guests)
            if (!isGuest) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MenuCard("Treasure Hunt", Icons.Outlined.TravelExplore, Modifier.weight(1f)) {
                        context.startActivity(Intent(context, ScoreboardActivity::class.java))
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MenuCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier  = modifier.height(112.dp).clickable { onClick() },
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(MetuRed.copy(alpha = 0.09f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MetuRed, modifier = Modifier.size(22.dp))
            }
            Text(
                text       = title,
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color      = Color(0xFF1A1A1A),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun MainBottomBar(selectedTab: Int, onTabSelected: (Int) -> Unit, showMyUnit: Boolean = true) {
    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        NavigationBarItem(
            icon     = { Icon(Icons.Outlined.Home, null, modifier = Modifier.size(24.dp)) },
            label    = { Text("Home", fontSize = 11.sp) },
            selected = selectedTab == 0,
            onClick  = { onTabSelected(0) },
            colors   = NavigationBarItemDefaults.colors(
                selectedTextColor   = MetuRed,
                selectedIconColor   = MetuRed,
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor      = MetuRed.copy(alpha = 0.1f)
            )
        )
        if (showMyUnit) {
            NavigationBarItem(
                icon     = { Icon(Icons.Outlined.Groups, null, modifier = Modifier.size(24.dp)) },
                label    = { Text("My Unit", fontSize = 11.sp) },
                selected = selectedTab == 1,
                onClick  = { onTabSelected(1) },
                colors   = NavigationBarItemDefaults.colors(
                    selectedTextColor   = MetuRed,
                    selectedIconColor   = MetuRed,
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor      = MetuRed.copy(alpha = 0.1f)
                )
            )
        }
        NavigationBarItem(
            icon     = { Icon(Icons.Outlined.Person, null, modifier = Modifier.size(24.dp)) },
            label    = { Text("Profile", fontSize = 11.sp) },
            selected = if (showMyUnit) selectedTab == 2 else selectedTab == 1,
            onClick  = { onTabSelected(if (showMyUnit) 2 else 1) },
            colors   = NavigationBarItemDefaults.colors(
                selectedTextColor   = MetuRed,
                selectedIconColor   = MetuRed,
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor      = MetuRed.copy(alpha = 0.1f)
            )
        )
    }
}