package com.example.orientar.orientation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

private val MetuRed = Color(0xFF8B0000)

data class GroupMember(
    val uid: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val sharePhone: Boolean = false
)

data class OrientationGroup(
    val id: String = "",
    val name: String = "",
    val leaderId: String = "",
    val leaderName: String = "",
    val leaderEmail: String = "",
    val leaderPhone: String = "",
    val whatsappLink: String = "",
    val members: List<GroupMember> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrientationUnitScreen() {
    val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    val db = FirebaseFirestore.getInstance()

    var role by remember { mutableStateOf("student") }
    var group by remember { mutableStateOf<OrientationGroup?>(null) }
    var loading by remember { mutableStateOf(true) }
    var notAssigned by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {

        fun processUserDoc(userDoc: com.google.firebase.firestore.DocumentSnapshot) {
            role = userDoc.getString("role") ?: "student"
            val groupId = userDoc.getString("groupId") ?: ""
            android.util.Log.d("UNIT_DEBUG", "role=$role groupId='$groupId' docId=${userDoc.id}")

            fun loadGroup(gId: String) {
                db.collection("orientation_groups").document(gId).get()
                    .addOnSuccessListener { groupDoc ->
                        val leaderId = groupDoc.getString("leaderId") ?: ""
                        val memberUids = groupDoc.get("members") as? List<String> ?: emptyList()
                        val whatsappLink = groupDoc.getString("whatsappLink") ?: ""
                        val groupName = groupDoc.getString("name") ?: ""

                        if (memberUids.isEmpty()) {
                            group = OrientationGroup(
                                id = gId, name = groupName,
                                leaderId = leaderId, whatsappLink = whatsappLink
                            )
                            loading = false
                            return@addOnSuccessListener
                        }

                        val memberList = mutableListOf<GroupMember>()
                        var fetched = 0
                        var leaderName = ""
                        var leaderEmail = ""
                        var leaderPhone = ""

                        fun finalize() {
                            group = OrientationGroup(
                                id = gId, name = groupName, leaderId = leaderId,
                                leaderName = leaderName, leaderEmail = leaderEmail,
                                leaderPhone = leaderPhone, whatsappLink = whatsappLink,
                                members = memberList.filter { it.uid != leaderId }
                            )
                            loading = false
                        }

                        fun fetchMembers() {
                            memberUids.forEach { uid ->
                                db.collection("users").document(uid).get()
                                    .addOnSuccessListener { mDoc ->
                                        memberList.add(
                                            GroupMember(
                                                uid = uid,
                                                firstName = mDoc.getString("firstName") ?: "",
                                                lastName = mDoc.getString("lastName") ?: "",
                                                email = mDoc.getString("email") ?: "",
                                                phone = mDoc.getString("phone") ?: "",
                                                sharePhone = mDoc.getBoolean("sharePhone") ?: false
                                            )
                                        )
                                        fetched++
                                        if (fetched == memberUids.size) finalize()
                                    }
                                    .addOnFailureListener {
                                        fetched++
                                        if (fetched == memberUids.size) finalize()
                                    }
                            }
                        }

                        if (leaderId.isNotEmpty()) {
                            db.collection("users").document(leaderId).get()
                                .addOnSuccessListener { ld ->
                                    leaderName = "${ld.getString("firstName") ?: ""} ${ld.getString("lastName") ?: ""}".trim()
                                    leaderEmail = ld.getString("email") ?: ""
                                    leaderPhone = ld.getString("phone") ?: ""
                                    fetchMembers()
                                }
                                .addOnFailureListener { fetchMembers() }
                        } else {
                            fetchMembers()
                        }
                    }
                    .addOnFailureListener { loading = false }
            }

            // Leaders may not have groupId — find by leaderId field (using doc ID not auth UID)
            val leaderDocId = userDoc.id
            if (groupId.isEmpty() && role == "leader") {
                db.collection("orientation_groups")
                    .whereEqualTo("leaderId", leaderDocId)
                    .limit(1)
                    .get()
                    .addOnSuccessListener { snap ->
                        if (snap.isEmpty) {
                            notAssigned = true
                            loading = false
                        } else {
                            loadGroup(snap.documents.first().id)
                        }
                    }
                    .addOnFailureListener { loading = false }
                return
            }

            if (groupId.isEmpty()) {
                notAssigned = true
                loading = false
                return
            }

            loadGroup(groupId)
        }

        // Try direct uid lookup first; leaders have different doc ID so fall back to authUid query
        db.collection("users").document(currentUid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    processUserDoc(doc)
                } else {
                    db.collection("users")
                        .whereEqualTo("authUid", currentUid)
                        .limit(1)
                        .get()
                        .addOnSuccessListener { snap ->
                            if (!snap.isEmpty) {
                                processUserDoc(snap.documents.first())
                            } else {
                                notAssigned = true
                                loading = false
                            }
                        }
                        .addOnFailureListener { loading = false }
                }
            }
            .addOnFailureListener { loading = false }
    }

    when {
        loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MetuRed)
            }
        }
        notAssigned -> NotAssignedView()
        group != null -> {
            if (role == "leader") {
                LeaderUnitView(group = group!!, db = db)
            } else {
                StudentUnitView(group = group!!)
            }
        }
    }
}

@Composable
fun NotAssignedView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("👥", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Not assigned to a group yet",
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color.Gray, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your coordinator will assign you soon.",
                fontSize = 14.sp, color = Color.LightGray, textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentUnitView(group: OrientationGroup) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        Box(
            Modifier.fillMaxWidth().background(MetuRed).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(group.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.White,
            contentColor = MetuRed,
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = MetuRed
                )
            }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        "My Leader",
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Text(
                        "My Group",
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }

        when (selectedTab) {
            0 -> LeaderTab(group = group)
            1 -> GroupTab(members = group.members)
        }
    }
}

@Composable
fun LeaderTab(group: OrientationGroup) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.size(90.dp).clip(CircleShape)
                .background(MetuRed.copy(alpha = 0.1f)).border(2.dp, MetuRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("⭐", fontSize = 40.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(group.leaderName.ifEmpty { "—" }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Orientation Leader", fontSize = 13.sp, color = MetuRed, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(28.dp))

        if (group.leaderPhone.isEmpty() && group.leaderEmail.isEmpty() && group.whatsappLink.isEmpty()) {
            Text(
                "Contact information not added yet.",
                fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center
            )
        } else {
            Text(
                "Contact",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = Color.DarkGray, modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(10.dp))

            if (group.leaderPhone.isNotEmpty()) {
                val cleaned = group.leaderPhone.replace(Regex("[^0-9+]"), "")
                ContactRow(icon = Icons.Default.Send, label = "WhatsApp", value = "Message on WhatsApp") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleaned")))
                }
                Spacer(Modifier.height(8.dp))
                ContactRow(icon = Icons.Default.Phone, label = "Phone", value = group.leaderPhone) {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${group.leaderPhone}")))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (group.leaderEmail.isNotEmpty()) {
                ContactRow(icon = Icons.Default.Email, label = "Email", value = group.leaderEmail) {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${group.leaderEmail}")))
                }
            }

            if (group.whatsappLink.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ContactRow(
                    icon = Icons.Default.People,
                    label = "WhatsApp Group",
                    value = "Join Group Chat"
                ) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(group.whatsappLink)))
                }
            }
        }
    }
}

@Composable
fun GroupTab(members: List<GroupMember>) {
    if (members.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No members yet.", color = Color.Gray)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "${members.size} members",
                fontSize = 13.sp, color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(members) { member ->
            MemberCard(member = member, showContact = true, isLeaderView = false)
        }
    }
}

@Composable
fun LeaderUnitView(group: OrientationGroup, db: FirebaseFirestore) {
    if (group.members.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text("👥", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "No members assigned yet.",
                    fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        Box(
            Modifier.fillMaxWidth().background(MetuRed).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(group.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "${group.members.size} members",
                    fontSize = 13.sp, color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(group.members) { member ->
                MemberCard(member = member, showContact = true, isLeaderView = true)
            }
        }
    }
}

@Composable
fun MemberCard(member: GroupMember, showContact: Boolean, isLeaderView: Boolean = false) {
    val context = LocalContext.current
    val fullName = "${member.firstName} ${member.lastName}".trim()
    val showPhone = showContact && member.phone.isNotEmpty() && (isLeaderView || member.sharePhone)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(MetuRed.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (member.firstName.isNotEmpty()) member.firstName.first().toString() else "?",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MetuRed
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(fullName.ifEmpty { "—" }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (showContact) {
                    Text(member.email, fontSize = 12.sp, color = Color.Gray)
                    if (showPhone) {
                        Text(member.phone, fontSize = 12.sp, color = MetuRed)
                    }
                }
            }
            if (showContact && member.email.isNotEmpty()) {
                IconButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${member.email}"))
                    )
                }) {
                    Icon(
                        Icons.Default.Email,
                        contentDescription = null,
                        tint = MetuRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFEEEEEE), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9F9F9)),
        onClick = onClick,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MetuRed, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = Color.Gray)
                Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint = Color.LightGray,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}