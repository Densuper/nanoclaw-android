package com.example.nanoclaw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8E2DE2),
                    background = Color(0xFF0D0E15),
                    surface = Color(0xFF171923)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: MainViewModel) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var textInput by remember { mutableStateOf("") }

    // Pulsing animation for the "Local AICore" status dot
    val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0B10), Color(0xFF12131A), Color(0xFF1A1C29))
                )
            )
    ) {
        // Premium App Bar with status indicator
        CenterAlignedTopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Pulsing status dot
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676).copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NANOCLAW LOCAL",
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color(0xFF11131E).copy(alpha = 0.8f)
            ),
            modifier = Modifier.border(
                BorderStroke(0.5.dp, Color(0xFF2A2D3D)),
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            )
        )

        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF8E2DE2),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        // Input field area (Glowing float bar)
        Surface(
            color = Color(0xFF11131E).copy(alpha = 0.95f),
            border = BorderStroke(1.dp, Color(0xFF2A2D3D)),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Command NanoClaw agent...", color = Color(0xFF6E7191), fontSize = 14.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF8E2DE2),
                        unfocusedBorderColor = Color(0xFF2A2D3D),
                        focusedContainerColor = Color(0xFF0D0E15),
                        unfocusedContainerColor = Color(0xFF0D0E15),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (textInput.isNotBlank()) {
                                viewModel.sendMessage(textInput)
                                textInput = ""
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.width(12.dp))

                IconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            viewModel.sendMessage(textInput)
                            textInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))
                            )
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.sender == "You"
    val isToolLog = message.isSystem && message.sender == "Tool"
    val isSystemObservation = message.isSystem && message.sender == "System"

    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // Premium capsule/bubble styling
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    when {
                        isUser -> Brush.linearGradient(colors = listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)))
                        isToolLog -> Brush.linearGradient(colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                        isSystemObservation -> Brush.linearGradient(colors = listOf(Color(0xFF1E3A8A).copy(alpha = 0.6f), Color(0xFF0F172A)))
                        else -> Brush.linearGradient(colors = listOf(Color(0xFF1C1E2D), Color(0xFF161722)))
                    }
                )
                .then(
                    if (!isUser) {
                        Modifier.border(
                            BorderStroke(
                                1.dp,
                                when {
                                    isToolLog -> Color(0xFF38BDF8).copy(alpha = 0.4f)
                                    isSystemObservation -> Color(0xFF34D399).copy(alpha = 0.4f)
                                    else -> Color(0xFF2A2D3D)
                                }
                            ),
                            shape = RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = 4.dp,
                                bottomEnd = 16.dp
                            )
                        )
                    } else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = message.text,
                color = when {
                    isToolLog -> Color(0xFF38BDF8)
                    isSystemObservation -> Color(0xFF34D399)
                    else -> Color.White
                },
                fontSize = 14.sp,
                fontFamily = if (message.isSystem) FontFamily.Monospace else FontFamily.SansSerif,
                fontWeight = if (message.isSystem) FontWeight.Bold else FontWeight.Normal
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = when {
                isToolLog -> "🤖 AGENT ACTION"
                isSystemObservation -> "⚙️ SYSTEM OBSERVATION"
                else -> message.sender
            },
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                isToolLog -> Color(0xFF38BDF8).copy(alpha = 0.8f)
                isSystemObservation -> Color(0xFF34D399).copy(alpha = 0.8f)
                else -> Color(0xFF94A3B8)
            },
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
