package com.example.orientar.profile

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.auth.WelcomeActivity
import com.example.orientar.treasure.GameState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

private val MetuRed = Color(0xFF8B0000)

data class UserProfile(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val role: String = "student",
    val phone: String = "",
    val groupId: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val uid = FirebaseAuth.getInstance().currentUser?.uid

    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Phone
    var phoneInput by remember { mutableStateOf("") }
    var savingPhone by remember { mutableStateOf(false) }
    var phoneSaved by remember { mutableStateOf(false) }

    // WhatsApp group link (leader only)
    var whatsappInput by remember { mutableStateOf("") }
    var savingWa by remember { mutableStateOf(false) }
    var waSaved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (uid == null) { loading = false; return@LaunchedEffect }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val p = UserProfile(
                    firstName = doc.getString("firstName") ?: "",
                    lastName = doc.getString("lastName") ?: "",
                    email = doc.getString("email") ?: FirebaseAuth.getInstance().currentUser?.email ?: "",
                    role = doc.getString("role") ?: "student",
                    phone = doc.getString("phone") ?: "",
                    groupId = doc.getString("groupId") ?: ""
                )
                profile = p
                phoneInput = p.phone
                phoneSaved = p.phone.isNotEmpty()
                loading = false

                // If leader, fetch whatsapp link from their group
                if (p.role == "leader" && p.groupId.isNotEmpty()) {
                    db.collection("orientation_groups").document(p.groupId).get()
                        .addOnSuccessListener { gDoc ->
                            val link = gDoc.getString("whatsappLink") ?: ""
                            whatsappInput = link
                            waSaved = link.isNotEmpty()
                        }
                }
            }
            .addOnFailureListener { loading = false }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.White)
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MetuRed)
            }
            return@Column
        }

        Spacer(Modifier.height(16.dp))

        // Avatar
        Box(
            Modifier.size(100.dp).clip(CircleShape)
                .background(MetuRed.copy(alpha = 0.1f)).border(2.dp, MetuRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(60.dp), tint = MetuRed)
        }

        Spacer(Modifier.height(12.dp))

        val fullName = "${profile?.firstName} ${profile?.lastName}".trim()
        Text(if (fullName.isNotEmpty()) fullName else "—", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            when (profile?.role) { "leader" -> "Orientation Leader"; else -> "Student" },
            fontSize = 14.sp, color = MetuRed, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(profile?.email ?: "", fontSize = 13.sp, color = Color.Gray)

        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = Color(0xFFEEEEEE))
        Spacer(Modifier.height(24.dp))

        // ── Phone number
        val isLeader = profile?.role == "leader"
        Text(
            if (isLeader) "📞 Phone Number (Required)" else "📞 Phone Number (Optional)",
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (isLeader) MetuRed else Color.DarkGray,
            modifier = Modifier.align(Alignment.Start)
        )
        if (isLeader) {
            Spacer(Modifier.height(4.dp))
            Text("Students will contact you via WhatsApp using this number.",
                fontSize = 12.sp, color = Color.Gray, modifier = Modifier.align(Alignment.Start))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = phoneInput,
            onValueChange = { phoneInput = it; phoneSaved = false },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("+90 5XX XXX XX XX") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                savingPhone = true
                uid?.let {
                    db.collection("users").document(it)
                        .set(mapOf("phone" to phoneInput.trim()), SetOptions.merge())
                        .addOnSuccessListener { savingPhone = false; phoneSaved = true }
                        .addOnFailureListener { savingPhone = false }
                }
            },
            enabled = !savingPhone && phoneInput.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(46.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MetuRed),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (savingPhone) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(if (phoneSaved) "✓ Saved!" else "Save Phone Number", fontWeight = FontWeight.Bold)
        }

        // ── WhatsApp group link (leader only)
        if (isLeader) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Color(0xFFEEEEEE))
            Spacer(Modifier.height(24.dp))

            Text("💬 WhatsApp Group Link", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = Color.DarkGray,
                modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(4.dp))
            Text("Students will see a button to join your WhatsApp group.",
                fontSize = 12.sp, color = Color.Gray, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = whatsappInput,
                onValueChange = { whatsappInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://chat.whatsapp.com/...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    savingWa = true
                    val groupId = profile?.groupId ?: ""
                    if (groupId.isNotEmpty()) {
                        db.collection("orientation_groups").document(groupId)
                            .set(mapOf("whatsappLink" to whatsappInput.trim()), SetOptions.merge())
                            .addOnSuccessListener { savingWa = false; waSaved = true }
                            .addOnFailureListener { savingWa = false }
                    }
                },
                enabled = !savingWa && whatsappInput.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (savingWa) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (waSaved) "✓ Saved!" else "Save WhatsApp Link",
                    fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = Color(0xFFEEEEEE))
        Spacer(Modifier.height(24.dp))

        // Logout
        Button(
            onClick = {
                GameState.clearMemory()
                FirebaseAuth.getInstance().signOut()
                context.startActivity(Intent(context, WelcomeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                (context as? Activity)?.finish()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MetuRed),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.ExitToApp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Logout", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(Modifier.height(16.dp))
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
            Text(label, fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.Black)
        }
    }
}