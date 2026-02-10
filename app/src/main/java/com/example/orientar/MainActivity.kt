package com.example.orientar

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.* // mutableStateOf ve remember için
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.google.firebase.FirebaseApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        setContent {
            MaterialTheme {
                OrientationScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrientationScreen() {
    val metuRed = Color(0xFF8B0000)

    // Hangi tabın seçili olduğunu tutan state (0: Home, 1: Unit, 2: Profile)
    var selectedTab by remember { mutableIntStateOf(0) }

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
                            text = "METU NCC ORIENTATION",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = metuRed
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = {
            // State'i ve değişim fonksiyonunu bura aktarıyoruz
            MainBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { index -> selectedTab = index }
            )
        }
    ) { paddingValues ->
        // Seçilen taba göre içeriği gösteriyoruz
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> HomeContent() // Eski kodundaki ana içerik
                1 -> OrientationUnitScreen() // Yeni yazdığımız ekran
                2 -> ProfileScreen() // Son halini attığım profil ekranı
            }
        }
    }
}

@Composable
fun HomeContent() {
    val context = LocalContext.current

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
                .height(240.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.campus_banner),
                contentDescription = "METU NCC Campus",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color(0xAA8B0000))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Welcome to your new life!", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Let's discover the campus", color = Color.White, fontSize = 16.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Kartlar Grid yapısı
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MenuCard("Campus Tour", "🔍", Modifier.weight(1f)) { Toast.makeText(context, "Soon", Toast.LENGTH_SHORT).show() }
                MenuCard("FAQ", "💬", Modifier.weight(1f)) { context.startActivity(Intent(context, ChatbotActivity::class.java)) }
                MenuCard("Treasure Hunt", "🎁", Modifier.weight(1f)) { context.startActivity(Intent(context, ScoreboardActivity::class.java)) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MenuCard("Student Societies", "👥", Modifier.weight(1f)) { context.startActivity(Intent(context, SocietiesActivity::class.java)) }
                MenuCard("Announcements", "🔔", Modifier.weight(1f)) { Toast.makeText(context, "Soon", Toast.LENGTH_SHORT).show() }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MainBottomBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        NavigationBarItem(
            icon = { Text("🏠", fontSize = 24.sp) },
            label = { Text("Home", fontSize = 11.sp) },
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF8B0000),
                selectedTextColor = Color(0xFF8B0000),
                unselectedIconColor = Color.Gray,
                indicatorColor = Color.Transparent
            )
        )
        NavigationBarItem(
            icon = { Text("📋", fontSize = 24.sp) },
            label = { Text("My Unit", fontSize = 11.sp) },
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF8B0000),
                selectedTextColor = Color(0xFF8B0000),
                unselectedIconColor = Color.Gray,
                indicatorColor = Color.Transparent
            )
        )
        NavigationBarItem(
            icon = { Text("👤", fontSize = 24.sp) },
            label = { Text("Profile", fontSize = 11.sp) },
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF8B0000),
                selectedTextColor = Color(0xFF8B0000),
                unselectedIconColor = Color.Gray,
                indicatorColor = Color.Transparent
            )
        )
    }
}