package com.example.orientar.orientation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OrientationUnitScreen() {
    val metuRed = Color(0xFF8B0000)
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = metuRed),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Unit: Northern Shields", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Group: G-14", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(metuRed.copy(alpha = 0.1f))
                .border(3.dp, metuRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(60.dp), tint = metuRed)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Orientation Leader", color = metuRed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("İpek Gültekin", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Contact Information",
            modifier = Modifier.align(Alignment.Start).padding(start = 4.dp, bottom = 8.dp),
            fontWeight = FontWeight.Bold,
            color = Color.DarkGray
        )

        ContactInfoItem(
            icon = Icons.Default.Phone,
            label = "TRNC Phone",
            value = "+90 533 123 45 67",
            onClick = {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+905331234567"))
                context.startActivity(intent)
            }
        )

        ContactInfoItem(
            icon = Icons.Default.Send, // WhatsApp icon
            label = "WhatsApp",
            value = "Message on WhatsApp",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/905331234567"))
                context.startActivity(intent)
            }
        )

        ContactInfoItem(
            icon = Icons.Default.Email,
            label = "Email Address",
            value = "ipek.gultekin@metu.edu.tr",
            onClick = {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ipek.gultekin@metu.edu.tr"))
                context.startActivity(intent)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoItem(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF8B0000), modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(label, fontSize = 12.sp, color = Color.Gray)
                Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
        }
    }
}