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
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

private val MetuRed = Color(0xFF8B0000)

class LeaderLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { LeaderLoginScreen() } }
    }
}

// ── Screens
enum class LeaderLoginStep { LOGIN, CHANGE_PASSWORD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderLoginScreen() {
    val context = LocalContext.current
    val auth    = remember { FirebaseAuth.getInstance() }
    val db      = remember { FirebaseFirestore.getInstance() }

    var step by remember { mutableStateOf(LeaderLoginStep.LOGIN) }
    var leaderUid    by remember { mutableStateOf("") }
    var leaderDocId  by remember { mutableStateOf("") }
    var leaderEmail  by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }

    when (step) {
        LeaderLoginStep.LOGIN -> {
            LeaderLoginForm(
                onLoginSuccess = { uid, docId, email, tempPassword ->
                    leaderUid       = uid
                    leaderDocId     = docId
                    leaderEmail     = email
                    currentPassword = tempPassword
                    step = LeaderLoginStep.CHANGE_PASSWORD
                },
                onDirectLogin = { docId ->
                    // mustChangePassword = false
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra("USER_ROLE", "leader")
                            putExtra("LEADER_DOC_ID", docId)
                        }
                    )
                },
                auth = auth,
                db   = db
            )
        }
        LeaderLoginStep.CHANGE_PASSWORD -> {
            ChangePasswordScreen(
                leaderUid       = leaderUid,
                leaderEmail     = leaderEmail,
                currentPassword = currentPassword,
                leaderDocId     = leaderDocId,
                auth            = auth,
                db              = db,
                onSuccess = {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra("USER_ROLE", "leader")
                            putExtra("LEADER_DOC_ID", leaderDocId)
                        }
                    )
                }
            )
        }
    }
}

// ── Step 1: Login form
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderLoginForm(
    onLoginSuccess: (uid: String, docId: String, email: String, tempPassword: String) -> Unit,
    onDirectLogin: (docId: String) -> Unit,
    auth: FirebaseAuth,
    db: FirebaseFirestore
) {
    val context = LocalContext.current

    var email           by remember { mutableStateOf("") }
    var password        by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage    by remember { mutableStateOf("") }
    var isLoading       by remember { mutableStateOf(false) }

    fun signIn() {
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
                            val doc   = query.documents.first()
                            val docId = doc.id
                            val mustChange = doc.getBoolean("mustChangePassword") ?: false

                            if (mustChange) {
                                onLoginSuccess(uid, docId, email.trim(), password)
                            } else {
                                onDirectLogin(docId)
                            }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MetuRed)
            )
        },
        containerColor = Color(0xFFF5F5F5)
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            Card(Modifier.size(80.dp), RoundedCornerShape(20.dp),
                CardDefaults.cardColors(containerColor = MetuRed)) {
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
                    OutlinedTextField(
                        value = email, onValueChange = { email = it; errorMessage = "" },
                        label = { Text("Email") }, placeholder = { Text("leader@metu.edu.tr") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetuRed, focusedLabelColor = MetuRed, cursorColor = MetuRed)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; errorMessage = "" },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    null, tint = MetuRed)
                            }
                        },
                        isError = errorMessage.isNotEmpty(),
                        supportingText = if (errorMessage.isNotEmpty()) {{ Text(errorMessage, color = MaterialTheme.colorScheme.error) }} else null,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetuRed, focusedLabelColor = MetuRed, cursorColor = MetuRed)
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { signIn() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MetuRed),
                        enabled = !isLoading
                    ) {
                        if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
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

// ── Step 2: Change password screen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(
    leaderUid: String,
    leaderEmail: String,
    currentPassword: String,
    leaderDocId: String,
    auth: FirebaseAuth,
    db: FirebaseFirestore,
    onSuccess: () -> Unit
) {
    var newPassword     by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var newVisible      by remember { mutableStateOf(false) }
    var confirmVisible  by remember { mutableStateOf(false) }
    var errorMessage    by remember { mutableStateOf("") }
    var isLoading       by remember { mutableStateOf(false) }

    fun changePassword() {
        errorMessage = ""
        if (newPassword.length < 6) { errorMessage = "Password must be at least 6 characters."; return }
        if (newPassword != confirmPassword) { errorMessage = "Passwords do not match."; return }
        if (newPassword == currentPassword) { errorMessage = "New password must be different from the temporary one."; return }

        isLoading = true
        val user = auth.currentUser ?: run { isLoading = false; return }

        // Re-authenticate then update password
        val credential = EmailAuthProvider.getCredential(leaderEmail, currentPassword)
        user.reauthenticate(credential)
            .addOnSuccessListener {
                user.updatePassword(newPassword)
                    .addOnSuccessListener {
                        // Mark mustChangePassword = false in Firestore
                        db.collection("users").document(leaderDocId)
                            .update("mustChangePassword", false)
                            .addOnSuccessListener {
                                isLoading = false
                                onSuccess()
                            }
                            .addOnFailureListener {
                                // Even if Firestore update fails, password is changed — proceed
                                isLoading = false
                                onSuccess()
                            }
                    }
                    .addOnFailureListener {
                        isLoading = false
                        errorMessage = "Failed to update password. Please try again."
                    }
            }
            .addOnFailureListener {
                isLoading = false
                errorMessage = "Authentication failed. Please restart and try again."
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set New Password", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MetuRed)
            )
        },
        containerColor = Color(0xFFF5F5F5)
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // Info card
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                CardDefaults.cardColors(Color(0xFFFFF3E0))) {
                Column(Modifier.padding(18.dp)) {
                    Text("🔐 First time sign in", fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, color = Color(0xFF795548))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Your account was set up by the Orientation Office with a temporary password. Please choose a new personal password to continue.",
                        fontSize = 13.sp, color = Color(0xFF795548), lineHeight = 19.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                CardDefaults.cardColors(Color.White), CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(24.dp)) {

                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; errorMessage = "" },
                        label = { Text("New Password") },
                        singleLine = true,
                        visualTransformation = if (newVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { newVisible = !newVisible }) {
                                Icon(if (newVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null, tint = MetuRed)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetuRed, focusedLabelColor = MetuRed, cursorColor = MetuRed)
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; errorMessage = "" },
                        label = { Text("Confirm New Password") },
                        singleLine = true,
                        visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { confirmVisible = !confirmVisible }) {
                                Icon(if (confirmVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null, tint = MetuRed)
                            }
                        },
                        isError = errorMessage.isNotEmpty(),
                        supportingText = if (errorMessage.isNotEmpty()) {{ Text(errorMessage, color = MaterialTheme.colorScheme.error) }} else null,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetuRed, focusedLabelColor = MetuRed, cursorColor = MetuRed)
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("• At least 6 characters", fontSize = 11.sp, color = Color.Gray)
                    Text("• Different from your temporary password", fontSize = 11.sp, color = Color.Gray)

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = { changePassword() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MetuRed),
                        enabled = !isLoading
                    ) {
                        if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Set Password & Continue", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}