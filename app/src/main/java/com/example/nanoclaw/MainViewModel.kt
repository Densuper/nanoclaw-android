package com.example.nanoclaw

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Message(
    val sender: String,
    val text: String,
    val isSystem: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val client = GeminiNanoClient(application.applicationContext)
    
    private val _messages = MutableStateFlow<List<Message>>(
        listOf(Message("System", "NanoClaw Local Agent initialized. Local Gemini Nano connection active.", isSystem = true))
    )
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        val userMessage = Message("You", text)
        _messages.value = _messages.value + userMessage
        _isLoading.value = true

        client.generateResponse(text) { responseText ->
            _messages.value = _messages.value + Message("NanoClaw", responseText)
            _isLoading.value = false
        }
    }
}
