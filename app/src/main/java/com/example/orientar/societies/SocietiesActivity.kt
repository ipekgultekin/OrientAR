package com.example.orientar.societies

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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

private val MetuRed = Color(0xFF8B0000)

data class SocietyUiModel(val id: String, val name: String, val logoEmoji: String)

class SocietiesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val userRole = intent.getStringExtra("USER_ROLE") ?: "student"
        setContent {
            MaterialTheme {
                SocietiesScreen(userRole = userRole)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocietiesScreen(userRole: String = "student") {
    val context = LocalContext.current
    val activity = context as? Activity
    var searchQuery by remember { mutableStateOf("") }

    val societies = listOf(
        SocietyUiModel("1", "ACM Student Chapter", "💻"),
        SocietyUiModel("2", "Photography Society", "📷"),
        SocietyUiModel("3", "Dance Club", "💃")
    )
    val filteredSocieties = societies.filter {
        it.name.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MetuRed)
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
                        Text("METU NCC ORIENTATION", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = MetuRed)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = { SocietiesBottomBar(userRole = userRole) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(padding)
                .padding(16.dp)
                .padding(bottom = 80.dp)
        ) {
            Text("Student Societies", fontSize = 22.sp,
                fontWeight = FontWeight.Bold, color = MetuRed)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Search societies") },
                placeholder = { Text("Type a society name...") }
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredSocieties) { society ->
                    SocietyCard(society = society, onDetailsClick = {
                        val intent = Intent(context, SocietyDetailActivity::class.java).apply {
                            putExtra("society_id", society.id)
                            putExtra("society_name", society.name)
                            putExtra("society_emoji", society.logoEmoji)
                        }
                        context.startActivity(intent)
                    })
                }
            }
        }
    }
}

@Composable
fun SocietyCard(society: SocietyUiModel, onDetailsClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().border(2.dp, MetuRed, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = society.logoEmoji, fontSize = 42.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = society.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = onDetailsClick, contentPadding = PaddingValues(0.dp)) {
                Text("Get details", color = MetuRed)
            }
        }
    }
}

@Composable
fun SocietiesBottomBar(userRole: String = "student") {
    val context = LocalContext.current
    val isGuest = userRole == "guest"

    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        NavigationBarItem(
            icon = { Text("🏠", fontSize = 24.sp) },
            label = { Text("Home", fontSize = 11.sp, maxLines = 1) },
            selected = false,
            onClick = {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("USER_ROLE", userRole)
                    }
                )
            },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray,
                indicatorColor = Color.Transparent
            )
        )
        if (!isGuest) {
            NavigationBarItem(
                icon = { Text("📋", fontSize = 24.sp) },
                label = { Text("My Unit", fontSize = 10.sp, maxLines = 2,
                    textAlign = TextAlign.Center, lineHeight = 11.sp) },
                selected = false,
                onClick = {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("USER_ROLE", userRole)
                            putExtra("OPEN_TAB", 1)
                        }
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray,
                    indicatorColor = Color.Transparent
                )
            )
        }
        NavigationBarItem(
            icon = { Text("👤", fontSize = 24.sp) },
            label = { Text("Profile", fontSize = 11.sp, maxLines = 1) },
            selected = false,
            onClick = {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("USER_ROLE", userRole)
                        putExtra("OPEN_TAB", if (isGuest) 1 else 2)
                    }
                )
            },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray,
                indicatorColor = Color.Transparent
            )
        )
    }
}