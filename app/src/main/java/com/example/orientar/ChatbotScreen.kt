package com.example.orientar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.orientar.network.*

private val MetuRed = Color(0xFF8B0000)
private val UserBubble = Color(0xFFE6CACA)
private val BotBubble = Color(0xFFF1F1F1)

@Composable
fun ChatbotScreen() {
    var userInput by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<Pair<String, Boolean>>() }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {

        /* 🔴 HEADER */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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

        /* 💬 CHAT */
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { (text, isUser) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser)
                        Arrangement.End else Arrangement.Start
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

        /* ✍️ INPUT */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            TextField(
                value = userInput,
                onValueChange = { userInput = it },
                placeholder = { Text("Type your question here") },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .border(1.dp, MetuRed, RoundedCornerShape(26.dp)),
                shape = RoundedCornerShape(26.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MetuRed
                )
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
                            val response = ApiClient.api.askQuestion(
                                ChatRequest(question)
                            )

                            if (messages.isNotEmpty())
                                messages.removeAt(messages.size - 1)

                            messages.add(response.message to false)

                        } catch (e: Exception) {

                            if (messages.isNotEmpty())
                                messages.removeAt(messages.size - 1)

                            messages.add("Sorry, I couldn't reach the server." to false)
                        }
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(MetuRed, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.White
                )

            }
        }
    }
}
