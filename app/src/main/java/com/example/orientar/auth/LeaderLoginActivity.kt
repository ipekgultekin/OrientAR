package com.example.orientar.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.home.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LeaderLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { LeaderLoginScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderLoginScreen() {
    val context = LocalContext.current
    val auth    = remember { FirebaseAuth.getInstance() }
    val db      = remember { FirebaseFirestore.getInstance() }

    var email           by remember { mutableStateOf("") }
    var password        by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage    by remember { mutableStateOf("") }
    var isLoading       by remember { mutableStateOf(false) }

    // ── Sign in leader and verify role in Firestore ───────────────────────────
    // Leaders have role == "leader" in their Firestore user document.
    // If someone with role "student" tries to log in here, access is denied.
    fun signInLeader() {
        if (email.trim().isEmpty() || password.isEmpty()) {
            errorMessage = "Please fill in all fields."
            return
        }

        isLoading = true
        errorMessage = ""

        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: run {
                    isLoading = false
                    return@addOnSuccessListener
                }

                db.collection("users")
                    .whereEqualTo("authUid", uid)
                    .whereEqualTo("role", "leader")
                    .limit(1)
                    .get()
                    .addOnSuccessListener { query ->
                        isLoading = false
                        if (!query.isEmpty) {
                            val leaderDocId = query.documents.first().id

                            context.startActivity(
                                Intent(context, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    putExtra("USER_ROLE", "leader")
                                    putExtra("LEADER_DOC_ID", leaderDocId)
                                }
                            )
                        } else {
                            auth.signOut()
                            errorMessage = "This account is not registered as an Orientation Leader."
                        }
                    }
                    .addOnFailureListener {
                        isLoading = false
                        errorMessage = "Failed to verify account. Please try again."
                        auth.signOut()
                    }
            }
            .addOnFailureListener {
                isLoading = false
                errorMessage = "Incorrect email or password. Please try again."
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leader Sign In", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { (context as? LeaderLoginActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF8B0000))
            )
        },
        containerColor = Color(0xFFF5F5F5)
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // Leader badge icon
            Card(Modifier.size(80.dp), RoundedCornerShape(20.dp),
                CardDefaults.cardColors(containerColor = Color(0xFF8B0000))) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("🏅", fontSize = 38.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Orientation Leader", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
            Text("Sign in to manage your group", fontSize = 14.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(28.dp))

            Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                CardDefaults.cardColors(Color.White), CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(24.dp)) {

                    // Email field
                    OutlinedTextField(
                        value = email, onValueChange = { email = it; errorMessage = "" },
                        label = { Text("Email") }, placeholder = { Text("leader@metu.edu.tr") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B0000),
                            focusedLabelColor  = Color(0xFF8B0000),
                            cursorColor        = Color(0xFF8B0000)
                        )
                    )
                    Spacer(Modifier.height(12.dp))

                    // Password field with show/hide toggle.
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; errorMessage = "" },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = null, tint = Color(0xFF8B0000))
                            }
                        },
                        isError = errorMessage.isNotEmpty(),
                        supportingText = if (errorMessage.isNotEmpty()) {{ Text(errorMessage, color = MaterialTheme.colorScheme.error) }} else null,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B0000),
                            focusedLabelColor  = Color(0xFF8B0000),
                            cursorColor        = Color(0xFF8B0000)
                        )
                    )
                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = { signInLeader() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                        enabled = !isLoading
                    ) {
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Info note for leaders
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp),
                CardDefaults.cardColors(Color(0xFFFFF8E1))) {
                Text(
                    "ℹ️  Leader accounts are created by the Orientation Office. Contact them if you need access.",
                    fontSize = 12.sp, color = Color(0xFF795548),
                    modifier = Modifier.padding(14.dp), lineHeight = 18.sp
                )
            }
        }
    }
}