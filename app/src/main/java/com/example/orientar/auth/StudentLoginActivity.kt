package com.example.orientar.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.home.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

// ─── Which step the student is on ───────────────────────────────────────────
private enum class StudentScreen { INVITATION, LOGIN, REGISTER }

class StudentLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { StudentLoginScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentLoginScreen() {
    val context = LocalContext.current
    val auth    = remember { FirebaseAuth.getInstance() }
    val db      = remember { FirebaseFirestore.getInstance() }

    var currentScreen by remember { mutableStateOf(StudentScreen.INVITATION) }

    // ── Invitation state ─────────────────────────────────────────────────────
    var invitationCode    by remember { mutableStateOf("") }
    var invitationError   by remember { mutableStateOf("") }
    var invitationLoading by remember { mutableStateOf(false) }

    // Data fetched from Firestore after code verification
    var verifiedGroupId       by remember { mutableStateOf("") }
    var verifiedStudentNumber by remember { mutableStateOf("") }

    // ── Login state ──────────────────────────────────────────────────────────
    var loginEmail           by remember { mutableStateOf("") }
    var loginPassword        by remember { mutableStateOf("") }
    var loginPasswordVisible by remember { mutableStateOf(false) }
    var loginError           by remember { mutableStateOf("") }
    var loginLoading         by remember { mutableStateOf(false) }

    // ── Register state ───────────────────────────────────────────────────────
    var regFirstName       by remember { mutableStateOf("") }
    var regLastName        by remember { mutableStateOf("") }
    var regEmail           by remember { mutableStateOf("") }
    var regPassword        by remember { mutableStateOf("") }
    var regPasswordVisible by remember { mutableStateOf(false) }
    var regError           by remember { mutableStateOf("") }
    var regLoading         by remember { mutableStateOf(false) }

    // ── Password validation: min 8 chars, 1 digit, no . , * ─────────────────
    fun validatePassword(pw: String): String? = when {
        pw.length < 8                              -> "Password must be at least 8 characters."
        !pw.any { it.isDigit() }                   -> "Password must contain at least one number."
        pw.any { it in setOf('.', ',', '*') }      -> "Password cannot contain . , * characters."
        else                                       -> null
    }

    // ── Step 1: Verify invitation code in Firestore ──────────────────────────
    // Looks up invitation_codes/{CODE}, checks it exists and hasn't been used yet
    fun verifyCode() {
        if (invitationCode.trim().isEmpty()) {
            invitationError = "Please enter your invitation code."
            return
        }
        invitationLoading = true
        invitationError   = ""

        db.collection("invitation_codes")
            .document(invitationCode.trim().uppercase())
            .get()
            .addOnSuccessListener { doc ->
                invitationLoading = false
                when {
                    !doc.exists() ->
                        invitationError = "Invalid invitation code. Please check and try again."
                    doc.getBoolean("used") == true ->
                        invitationError = "This invitation code has already been used."
                    else -> {
                        // Cache group and student number attached to this code
                        verifiedGroupId       = doc.getString("groupId")       ?: ""
                        verifiedStudentNumber = doc.getString("studentNumber") ?: ""
                        currentScreen         = StudentScreen.REGISTER
                    }
                }
            }
            .addOnFailureListener { e ->
                invitationLoading = false
                invitationError   = "Connection error. Please try again."
            }
    }

    // ── Step 2a: Sign in existing student via Firebase Auth ──────────────────
    fun signIn() {
        if (loginEmail.trim().isEmpty() || loginPassword.isEmpty()) {
            loginError = "Please fill in all fields."
            return
        }
        loginLoading = true
        loginError   = ""

        auth.signInWithEmailAndPassword(loginEmail.trim(), loginPassword)
            .addOnSuccessListener {
                loginLoading = false
                // Clear back stack so the user can't navigate back to the login screen
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
            }
            .addOnFailureListener {
                loginLoading = false
                loginError   = "Incorrect email or password. Please try again."
            }
    }

    // ── Step 2b: Register new student ────────────────────────────────────────
    // Flow: validate fields → Firebase Auth createUser
    //       → mark code as used → save user doc in Firestore → add to group
    fun register() {
        when {
            regFirstName.trim().isEmpty() -> { regError = "Please enter your first name.";     return }
            regLastName.trim().isEmpty()  -> { regError = "Please enter your last name.";      return }
            regEmail.trim().isEmpty()     -> { regError = "Please enter your email address.";  return }
            !regEmail.trim().endsWith("@metu.edu.tr") -> {
                // Only METU institutional email addresses are accepted
                regError = "You must use your METU email address (@metu.edu.tr)."
                return
            }
            else -> validatePassword(regPassword)?.let { regError = it; return }
        }

        regLoading = true
        regError   = ""

        // Create Firebase Auth account
        auth.createUserWithEmailAndPassword(regEmail.trim(), regPassword)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: run { regLoading = false; return@addOnSuccessListener }

                // Mark invitation code as used so it cannot be reused
                db.collection("invitation_codes")
                    .document(invitationCode.trim().uppercase())
                    .update(
                        mapOf(
                            "used"       to true,
                            "usedByUid"  to uid,
                            "usedAt"     to FieldValue.serverTimestamp()
                        )
                    )

                // Save student profile in Firestore
                val userDoc = hashMapOf(
                    "firstName"     to regFirstName.trim(),
                    "lastName"      to regLastName.trim(),
                    "email"         to regEmail.trim(),
                    "role"          to "student",
                    "studentNumber" to verifiedStudentNumber,
                    "groupId"       to verifiedGroupId,
                    "createdAt"     to FieldValue.serverTimestamp()
                )

                db.collection("users").document(uid)
                    .set(userDoc)
                    .addOnSuccessListener {
                        regLoading = false

                        // Add this student's uid to the group's members array
                        if (verifiedGroupId.isNotEmpty()) {
                            db.collection("orientation_groups").document(verifiedGroupId)
                                .update("members", FieldValue.arrayUnion(uid))
                        }

                        // Navigate to main app, clear back stack
                        context.startActivity(
                            Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                        )
                    }
                    .addOnFailureListener { e ->
                        regLoading = false
                        regError   = "Failed to save profile. Please try again."
                    }
            }
            .addOnFailureListener { e ->
                regLoading = false
                regError = when {
                    e.message?.contains("email address is already in use") == true ->
                        "This email is already registered. Please sign in instead."
                    else -> "Registration failed. Please try again."
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentScreen) {
                            StudentScreen.INVITATION -> "Student Access"
                            StudentScreen.LOGIN -> "Sign In"
                            StudentScreen.REGISTER -> "Create Account"
                        },
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (currentScreen) {
                            StudentScreen.INVITATION -> (context as? StudentLoginActivity)?.finish()
                            else -> currentScreen = StudentScreen.INVITATION
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF8B0000))
            )
        },
        containerColor = Color(0xFFF5F5F5)
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {

            // ── SCREEN 1: INVITATION CODE ────────────────────────────────
            AnimatedVisibility(
                visible = currentScreen == StudentScreen.INVITATION,
                enter = fadeIn(tween(300)) + slideInHorizontally { it },
                exit  = fadeOut(tween(200)) + slideOutHorizontally { -it }
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(24.dp))
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                        CardDefaults.cardColors(Color.White), CardDefaults.cardElevation(2.dp)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🔑", fontSize = 40.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Enter your invitation code", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Check your METU email inbox for the invitation code sent by the Orientation Coordinator.",
                                fontSize = 13.sp, color = Color(0xFF888888),
                                textAlign = TextAlign.Center, lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(20.dp))
                            OutlinedTextField(
                                value = invitationCode,
                                onValueChange = { invitationCode = it.uppercase(); invitationError = "" },
                                label = { Text("Invitation Code") },
                                placeholder = { Text("e.g. ORL-2024-ABC") },
                                singleLine = true,
                                isError = invitationError.isNotEmpty(),
                                supportingText = if (invitationError.isNotEmpty()) {{ Text(invitationError, color = MaterialTheme.colorScheme.error) }} else null,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF8B0000),
                                    focusedLabelColor  = Color(0xFF8B0000),
                                    cursorColor        = Color(0xFF8B0000)
                                )
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { verifyCode() },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                                enabled = !invitationLoading
                            ) {
                                if (invitationLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                else Text("Verify Code", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f), color = Color(0xFFDDDDDD))
                        Text("  already have an account?  ", color = Color(0xFF999999), fontSize = 12.sp)
                        HorizontalDivider(Modifier.weight(1f), color = Color(0xFFDDDDDD))
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { currentScreen = StudentScreen.LOGIN },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF8B0000)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8B0000))
                    ) { Text("Sign In to existing account", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                }
            }

            // ── SCREEN 2: LOGIN ──────────────────────────────────────────
            AnimatedVisibility(
                visible = currentScreen == StudentScreen.LOGIN,
                enter = fadeIn(tween(300)) + slideInHorizontally { it },
                exit  = fadeOut(tween(200)) + slideOutHorizontally { -it }
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(24.dp))
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                        CardDefaults.cardColors(Color.White), CardDefaults.cardElevation(2.dp)) {
                        Column(Modifier.padding(24.dp)) {
                            Text("👋", fontSize = 36.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Welcome back!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
                            Text("Sign in to continue", fontSize = 14.sp, color = Color(0xFF888888))
                            Spacer(Modifier.height(24.dp))
                            OutlinedTextField(
                                value = loginEmail,
                                onValueChange = { loginEmail = it; loginError = "" },
                                label = { Text("METU Email") },
                                placeholder = { Text("e12345678@metu.edu.tr") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF8B0000),
                                    focusedLabelColor  = Color(0xFF8B0000),
                                    cursorColor        = Color(0xFF8B0000)
                                )
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = loginPassword,
                                onValueChange = { loginPassword = it; loginError = "" },
                                label = { Text("Password") }, singleLine = true,
                                visualTransformation = if (loginPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { loginPasswordVisible = !loginPasswordVisible }) {
                                        Icon(if (loginPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null, tint = Color(0xFF8B0000))
                                    }
                                },
                                isError = loginError.isNotEmpty(),
                                supportingText = if (loginError.isNotEmpty()) {{ Text(loginError, color = MaterialTheme.colorScheme.error) }} else null,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF8B0000),
                                    focusedLabelColor  = Color(0xFF8B0000),
                                    cursorColor        = Color(0xFF8B0000)
                                )
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = { signIn() },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                                enabled = !loginLoading
                            ) {
                                if (loginLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                else Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }

            // ── SCREEN 3: REGISTER ───────────────────────────────────────
            AnimatedVisibility(
                visible = currentScreen == StudentScreen.REGISTER,
                enter = fadeIn(tween(300)) + slideInHorizontally { it },
                exit  = fadeOut(tween(200)) + slideOutHorizontally { -it }
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(24.dp))
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp),
                        CardDefaults.cardColors(Color(0xFFE8F5E9))) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("✅", fontSize = 20.sp); Spacer(Modifier.width(10.dp))
                            Text("Invitation code verified! Now create your account.",
                                fontSize = 13.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                        CardDefaults.cardColors(Color.White), CardDefaults.cardElevation(2.dp)) {
                        Column(Modifier.padding(24.dp)) {
                            Text("📝", fontSize = 32.sp); Spacer(Modifier.height(6.dp))
                            Text("Create your account", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
                            Text("Fill in your details below", fontSize = 13.sp, color = Color(0xFF888888))
                            Spacer(Modifier.height(20.dp))

                            // First name field
                            OutlinedTextField(
                                value = regFirstName, onValueChange = { regFirstName = it; regError = "" },
                                label = { Text("First Name") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF8B0000), focusedLabelColor = Color(0xFF8B0000), cursorColor = Color(0xFF8B0000))
                            )
                            Spacer(Modifier.height(12.dp))

                            // Last name field
                            OutlinedTextField(
                                value = regLastName, onValueChange = { regLastName = it; regError = "" },
                                label = { Text("Last Name") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF8B0000), focusedLabelColor = Color(0xFF8B0000), cursorColor = Color(0xFF8B0000))
                            )
                            Spacer(Modifier.height(12.dp))

                            // METU email — must end with @metu.edu.tr
                            OutlinedTextField(
                                value = regEmail, onValueChange = { regEmail = it; regError = "" },
                                label = { Text("METU Email") }, placeholder = { Text("e12345678@metu.edu.tr") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF8B0000), focusedLabelColor = Color(0xFF8B0000), cursorColor = Color(0xFF8B0000))
                            )
                            Spacer(Modifier.height(12.dp))

                            // Password field with show/hide toggle
                            OutlinedTextField(
                                value = regPassword, onValueChange = { regPassword = it; regError = "" },
                                label = { Text("Password") }, singleLine = true,
                                visualTransformation = if (regPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { regPasswordVisible = !regPasswordVisible }) {
                                        Icon(if (regPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null, tint = Color(0xFF8B0000))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF8B0000), focusedLabelColor = Color(0xFF8B0000), cursorColor = Color(0xFF8B0000))
                            )
                            Spacer(Modifier.height(6.dp))

                            // Constraints reminder
                            Card(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), CardDefaults.cardColors(Color(0xFFFFF3E0))) {
                                Text(
                                    "ℹ️  Must use @metu.edu.tr email. Password: min. 8 chars, at least 1 number, no . , * symbols.",
                                    fontSize = 11.sp, color = Color(0xFFE65100),
                                    modifier = Modifier.padding(10.dp), lineHeight = 16.sp
                                )
                            }

                            if (regError.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(regError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(20.dp))

                            Button(
                                onClick = { register() },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                                enabled = !regLoading
                            ) {
                                if (regLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                else Text("Create Account", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}