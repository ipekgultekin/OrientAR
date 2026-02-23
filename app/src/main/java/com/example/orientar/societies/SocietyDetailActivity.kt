package com.example.orientar.societies

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.R

private val MetuRed = Color(0xFF8B0000)

class SocietyDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val societyId = intent.getStringExtra("society_id") ?: ""
        val societyName = intent.getStringExtra("society_name") ?: "Society"
        val societyEmoji = intent.getStringExtra("society_emoji") ?: "👥"

        setContent {
            MaterialTheme {
                SocietyDetailScreen(
                    societyId = societyId,
                    societyName = societyName,
                    societyEmoji = societyEmoji
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocietyDetailScreen(
    societyId: String,
    societyName: String,
    societyEmoji: String
) {
    val context = LocalContext.current
    val activity = context as? Activity

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MetuRed
                        )
                    }
                },
                title = {
                    // MainActivity ile aynı logo + title
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            color = MetuRed
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = { SocietiesBottomBar() } // societies ile aynı bottom bar
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(padding)
                .padding(16.dp)
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "$societyEmoji  $societyName", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MetuRed)

            Text(
                text = "Details page (dummy for now). Later we can fetch from Firebase using societyId: $societyId",
                fontSize = 14.sp
            )

            Button(
                onClick = { Toast.makeText(context, "Coming Soon...", Toast.LENGTH_SHORT).show() },
                colors = ButtonDefaults.buttonColors(containerColor = MetuRed)
            ) {
                Text("Join / Contact", color = Color.White)
            }
        }
    }
}
