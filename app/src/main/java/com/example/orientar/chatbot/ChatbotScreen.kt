package com.example.orientar.chatbot

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import com.example.orientar.network.ApiClient
import com.example.orientar.network.ChatRequest
import kotlinx.coroutines.launch

private val MetuRed = Color(0xFF8B0000)
private val UserBubble = Color(0xFFE6CACA)
private val BotBubble = Color(0xFFF1F1F1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatbotScreen(userRole: String = "student") {
    var userInput by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf<Pair<String, Boolean>>(
            "Hi! How can I help you today? Feel free to ask me anything about METU NCC!" to false
        )
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        bottomBar = {
            ChatbotBottomBar(userRole = userRole)
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(paddingValues)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 4.dp,
                color = Color.White
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.metu_logo),
                        contentDescription = "METU Logo",
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "METU NCC ORIENTATION",
                        color = MetuRed,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(messages) { (text, isUser) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isUser) UserBubble else BotBubble,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(12.dp)
                                .widthIn(max = 260.dp)
                        ) {
                            Text(text = text)
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = Color.White
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    TextField(
                        value = userInput,
                        onValueChange = { userInput = it },
                        placeholder = { Text("Type your question here") },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp, max = 120.dp)
                            .border(1.dp, MetuRed, RoundedCornerShape(26.dp)),
                        shape = RoundedCornerShape(26.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = MetuRed
                        ),
                        maxLines = 5
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (userInput.isBlank()) return@IconButton
                            val question = userInput
                            messages.add(question to true)
                            messages.add("Thinking…" to false)
                            userInput = ""
                            scope.launch {
                                try {
                                    val response = ApiClient.api.askQuestion(ChatRequest(question))
                                    if (messages.isNotEmpty()) messages.removeAt(messages.size - 1)
                                    messages.add(response.answer to false)
                                } catch (e: Exception) {
                                    if (messages.isNotEmpty()) messages.removeAt(messages.size - 1)
                                    messages.add("Sorry, I couldn't reach the server." to false)
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp).background(MetuRed, CircleShape)
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatbotBottomBar(userRole: String = "student") {
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
                unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray
            )
        )
        if (!isGuest) {
            NavigationBarItem(
                icon = { Text("📋", fontSize = 24.sp) },
                label = {
                    Text("My Orientation\nUnit", fontSize = 10.sp, maxLines = 2,
                        textAlign = TextAlign.Center, lineHeight = 11.sp)
                },
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
                    unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray
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
                unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray
            )
        )
    }
}