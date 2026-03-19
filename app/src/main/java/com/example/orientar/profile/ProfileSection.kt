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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.orientar.auth.WelcomeActivity
import com.example.orientar.treasure.GameState
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

private val MetuRed = Color(0xFF8B0000)

enum class ProfileStep { MAIN, CHANGE_PASSWORD }

data class UserProfile(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val role: String = "student",
    val phone: String = "",
    val groupId: String = "",
    val sharePhone: Boolean = false,
    val docId: String = ""
)

@Composable
fun ProfileScreen() {
    var step by remember { mutableStateOf(ProfileStep.MAIN) }
    when (step) {
        ProfileStep.MAIN -> MainProfileScreen(onChangePassword = { step = ProfileStep.CHANGE_PASSWORD })
        ProfileStep.CHANGE_PASSWORD -> ChangePasswordScreen(onBack = { step = ProfileStep.MAIN })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainProfileScreen(onChangePassword: () -> Unit) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val uid = FirebaseAuth.getInstance().currentUser?.uid

    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var phoneInput by remember { mutableStateOf("") }
    var savingPhone by remember { mutableStateOf(false) }
    var phoneSaved by remember { mutableStateOf(false) }
    var sharePhone by remember { mutableStateOf(false) }
    var whatsappInput by remember { mutableStateOf("") }
    var savingWa by remember { mutableStateOf(false) }
    var waSaved by remember { mutableStateOf(false) }

    fun loadWhatsapp(p: UserProfile, resolvedUid: String) {
        val gId = p.groupId
        if (gId.isNotEmpty()) {
            db.collection("orientation_groups").document(gId).get()
                .addOnSuccessListener { gDoc ->
                    val link = gDoc.getString("whatsappLink") ?: ""
                    whatsappInput = link
                    waSaved = link.isNotEmpty()
                }
        } else {
            db.collection("orientation_groups")
                .whereEqualTo("leaderId", resolvedUid)
                .limit(1)
                .get()
                .addOnSuccessListener { snap ->
                    if (!snap.isEmpty) {
                        val gDoc = snap.documents.first()
                        val link = gDoc.getString("whatsappLink") ?: ""
                        whatsappInput = link
                        waSaved = link.isNotEmpty()
                        profile = p.copy(groupId = gDoc.id)
                    }
                }
        }
    }

    fun applyProfile(doc: com.google.firebase.firestore.DocumentSnapshot, docId: String, resolvedUid: String) {
        val p = UserProfile(
            firstName = doc.getString("firstName") ?: "",
            lastName = doc.getString("lastName") ?: "",
            email = doc.getString("email") ?: FirebaseAuth.getInstance().currentUser?.email ?: "",
            role = doc.getString("role") ?: "student",
            phone = doc.getString("phone") ?: "",
            groupId = doc.getString("groupId") ?: "",
            sharePhone = doc.getBoolean("sharePhone") ?: false,
            docId = docId
        )
        profile = p
        phoneInput = p.phone
        phoneSaved = p.phone.isNotEmpty()
        sharePhone = p.sharePhone
        loading = false
        if (p.role == "leader") {
            loadWhatsapp(p, resolvedUid)
        }
    }

    LaunchedEffect(Unit) {
        if (uid == null) { loading = false; return@LaunchedEffect }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    applyProfile(doc, uid, uid)
                } else {
                    db.collection("users").whereEqualTo("authUid", uid).limit(1).get()
                        .addOnSuccessListener { snap ->
                            if (!snap.isEmpty) {
                                val d = snap.documents.first()
                                applyProfile(d, d.id, uid)
                            } else {
                                loading = false
                            }
                        }
                        .addOnFailureListener { loading = false }
                }
            }
            .addOnFailureListener { loading = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MetuRed)
            }
            return@Column
        }

        Spacer(Modifier.height(16.dp))

        Box(
            Modifier.size(100.dp).clip(CircleShape)
                .background(MetuRed.copy(alpha = 0.1f)).border(2.dp, MetuRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, null, modifier = Modifier.size(60.dp), tint = MetuRed)
        }

        Spacer(Modifier.height(12.dp))

        val fullName = "${profile?.firstName} ${profile?.lastName}".trim()
        Text(
            if (fullName.isNotEmpty()) fullName else "—",
            fontSize = 22.sp, fontWeight = FontWeight.Bold
        )
        Text(
            when (profile?.role) { "leader" -> "Orientation Leader"; else -> "Student" },
            fontSize = 14.sp, color = MetuRed, fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(profile?.email ?: "", fontSize = 13.sp, color = Color.Gray)

        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = Color(0xFFEEEEEE))
        Spacer(Modifier.height(24.dp))

        val isLeader = profile?.role == "leader"

        // Phone number
        Text(
            "📞 Phone Number (Required)",
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = MetuRed, modifier = Modifier.align(Alignment.Start)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (isLeader) "Students will contact you via WhatsApp using this number."
            else "Your leader will always see this. You can choose whether to share with your group.",
            fontSize = 12.sp, color = Color.Gray, modifier = Modifier.align(Alignment.Start)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = phoneInput,
            onValueChange = { phoneInput = it; phoneSaved = false },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("+90 5XX XXX XX XX") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MetuRed,
                focusedLabelColor = MetuRed,
                cursorColor = MetuRed
            )
        )

        if (!isLeader) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = sharePhone,
                    onCheckedChange = { checked ->
                        sharePhone = checked
                        val docId = profile?.docId ?: uid ?: ""
                        if (docId.isNotEmpty()) {
                            db.collection("users").document(docId)
                                .set(mapOf("sharePhone" to checked), SetOptions.merge())
                        }
                    },
                    colors = CheckboxDefaults.colors(checkedColor = MetuRed)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Share my phone number with group members",
                    fontSize = 13.sp, color = Color.DarkGray
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                savingPhone = true
                val docId = profile?.docId ?: uid ?: ""
                if (docId.isNotEmpty()) {
                    db.collection("users").document(docId)
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
            if (savingPhone) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(if (phoneSaved) "✓ Saved!" else "Save Phone Number", fontWeight = FontWeight.Bold)
            }
        }

        // WhatsApp group link — leader only
        if (isLeader) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Color(0xFFEEEEEE))
            Spacer(Modifier.height(24.dp))
            Text(
                "💬 WhatsApp Group Link",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = Color.DarkGray, modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Students will see a button to join your WhatsApp group.",
                fontSize = 12.sp, color = Color.Gray, modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = whatsappInput,
                onValueChange = { whatsappInput = it; waSaved = false },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://chat.whatsapp.com/...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MetuRed,
                    focusedLabelColor = MetuRed,
                    cursorColor = MetuRed
                )
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    savingWa = true
                    val gId = profile?.groupId ?: ""
                    fun saveLink(groupDocId: String) {
                        db.collection("orientation_groups").document(groupDocId)
                            .set(mapOf("whatsappLink" to whatsappInput.trim()), SetOptions.merge())
                            .addOnSuccessListener { savingWa = false; waSaved = true }
                            .addOnFailureListener { savingWa = false }
                    }
                    if (gId.isNotEmpty()) {
                        saveLink(gId)
                    } else if (uid != null) {
                        db.collection("orientation_groups")
                            .whereEqualTo("leaderId", uid).limit(1).get()
                            .addOnSuccessListener { snap ->
                                if (!snap.isEmpty) saveLink(snap.documents.first().id)
                                else savingWa = false
                            }
                            .addOnFailureListener { savingWa = false }
                    }
                },
                enabled = !savingWa && whatsappInput.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (savingWa) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        if (waSaved) "✓ Saved!" else "Save WhatsApp Link",
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Color(0xFFEEEEEE))
        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = onChangePassword,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MetuRed)
        ) {
            Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Change Password", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }

        Spacer(Modifier.height(12.dp))

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
            Icon(Icons.Default.ExitToApp, null)
            Spacer(Modifier.width(8.dp))
            Text("Logout", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("METU NCC Orientation v1.0", fontSize = 12.sp, color = Color.LightGray)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(onBack: () -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val email = auth.currentUser?.email ?: ""

    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentVisible by remember { mutableStateOf(false) }
    var newVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }

    fun changePassword() {
        errorMessage = ""
        if (currentPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty()) {
            errorMessage = "Please fill in all fields."
            return
        }
        if (newPassword.length < 6) {
            errorMessage = "New password must be at least 6 characters."
            return
        }
        if (newPassword != confirmPassword) {
            errorMessage = "New passwords do not match."
            return
        }
        if (newPassword == currentPassword) {
            errorMessage = "New password must be different from current password."
            return
        }
        isLoading = true
        val user = auth.currentUser ?: run { isLoading = false; return }
        val credential = EmailAuthProvider.getCredential(email, currentPassword)
        user.reauthenticate(credential)
            .addOnSuccessListener {
                user.updatePassword(newPassword)
                    .addOnSuccessListener { isLoading = false; success = true }
                    .addOnFailureListener { isLoading = false; errorMessage = "Failed to update password." }
            }
            .addOnFailureListener { isLoading = false; errorMessage = "Current password is incorrect." }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) {
                Text("← Back", color = MetuRed, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(8.dp))
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(56.dp), tint = MetuRed.copy(alpha = 0.7f))
        Spacer(Modifier.height(12.dp))
        Text("Change Password", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(email, fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(28.dp))

        if (success) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("✅", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Password Changed!", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    Spacer(Modifier.height(4.dp))
                    Text("Your password has been updated successfully.", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MetuRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Back to Profile", fontWeight = FontWeight.Bold)
                    }
                }
            }
            return@Column
        }

        PasswordField(
            value = currentPassword,
            onChange = { currentPassword = it; errorMessage = "" },
            label = "Current Password",
            visible = currentVisible,
            onToggle = { currentVisible = !currentVisible }
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = newPassword,
            onChange = { newPassword = it; errorMessage = "" },
            label = "New Password",
            visible = newVisible,
            onToggle = { newVisible = !newVisible }
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = confirmPassword,
            onChange = { confirmPassword = it; errorMessage = "" },
            label = "Confirm New Password",
            visible = confirmVisible,
            onToggle = { confirmVisible = !confirmVisible },
            isError = errorMessage.isNotEmpty()
        )

        if (errorMessage.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))
        Text("• At least 6 characters", fontSize = 11.sp, color = Color.Gray)
        Text("• Different from current password", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { changePassword() },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MetuRed),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text("Update Password", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggle: () -> Unit,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    null, tint = MetuRed
                )
            }
        },
        isError = isError,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MetuRed,
            focusedLabelColor = MetuRed,
            cursorColor = MetuRed
        )
    )
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