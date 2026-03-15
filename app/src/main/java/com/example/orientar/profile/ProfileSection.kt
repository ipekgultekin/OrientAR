package com.example.orientar.profile

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.auth.WelcomeActivity
import com.example.orientar.treasure.GameState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

private val MetuRed = Color(0xFF8B0000)

data class UserProfile(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val studentNumber: String = "",
    val role: String = "student",
    val groupId: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get()
                .addOnSuccessListener { doc ->
                    profile = UserProfile(
                        firstName = doc.getString("firstName") ?: "",
                        lastName = doc.getString("lastName") ?: "",
                        email = doc.getString("email") ?: FirebaseAuth.getInstance().currentUser?.email ?: "",
                        studentNumber = doc.getString("studentNumber") ?: "",
                        role = doc.getString("role") ?: "student",
                        groupId = doc.getString("groupId") ?: ""
                    )
                    loading = false
                }
                .addOnFailureListener { loading = false }
        } else {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MetuRed)
            }
            return@Column
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Avatar
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MetuRed.copy(alpha = 0.1f))
                .border(2.dp, MetuRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(60.dp),
                tint = MetuRed
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        val fullName = "${profile?.firstName} ${profile?.lastName}".trim()
        Text(
            text = if (fullName.isNotEmpty()) fullName else "—",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        val roleLabel = when (profile?.role) {
            "leader" -> "Orientation Leader"
            "student" -> "Student"
            else -> profile?.role ?: ""
        }
        Text(text = roleLabel, fontSize = 14.sp, color = MetuRed, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = profile?.email ?: "", fontSize = 13.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(28.dp))

        // Info cards
        ProfileInfoCard(label = "Student Number", value = profile?.studentNumber?.ifEmpty { "—" } ?: "—")
        Spacer(modifier = Modifier.height(10.dp))
        ProfileInfoCard(
            label = "Orientation Group",
            value = profile?.groupId?.ifEmpty { "Not assigned yet" } ?: "Not assigned yet"
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Logout
        Button(
            onClick = {
                GameState.clearMemory()
                FirebaseAuth.getInstance().signOut()
                context.startActivity(
                    Intent(context, WelcomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                (context as? Activity)?.finish()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MetuRed),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.ExitToApp, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Logout", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.weight(1f))

        Text("METU NCC Orientation v1.0", fontSize = 12.sp, color = Color.LightGray)
    }
}

@Composable
fun ProfileInfoCard(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9F9F9)),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = label, fontSize = 12.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.Black)
        }
    }
}