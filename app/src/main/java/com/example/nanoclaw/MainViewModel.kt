package com.example.nanoclaw

import android.app.Application
import android.content.Context
import android.os.BatteryManager
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
    private val context = application.applicationContext

    private val _messages = MutableStateFlow<List<Message>>(
        listOf(Message("System", "NanoClaw Local Agent initialized. Local Gemini Nano connection active.", isSystem = true))
    )
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Safety limit to prevent infinite tool calling loops
    private val MAX_STEPS = 3

    private val systemPrompt = """
        You are NanoClaw, a helpful local AI assistant running on this Android device.
        You have access to the following tool:
        - get_battery_level: Returns the current battery level percentage of the device.

        Instructions:
        1. If the user asks about the battery, power, or charge status, and you DO NOT have the battery level information yet in the conversation history, you MUST output exactly:
        TOOL_CALL: get_battery_level
        Do not output any other text or explanations.
        2. If you see "System Observation: Battery level is [X]%", do NOT call the tool again. Instead, answer the user's question directly in a natural sentence (e.g., "Your battery is currently at 87%.").
    """.trimIndent()

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = Message("You", text)
        _messages.value = _messages.value + userMessage
        _isLoading.value = true

        // Start execution loop
        runAgentLoop(step = 0)
    }

    private fun runAgentLoop(step: Int) {
        // Safety check to remind and protect battery/quota limits
        if (step >= MAX_STEPS) {
            val limitWarning = Message(
                sender = "System",
                text = "⚠️ Warning: Agent exceeded maximum loop steps ($MAX_STEPS). Terminating loop to save battery and prevent quota overuse.",
                isSystem = true
            )
            _messages.value = _messages.value + limitWarning
            _isLoading.value = false
            return
        }

        val prompt = buildPrompt(_messages.value)

        client.generateResponse(prompt) { responseText ->
            val cleanedResponse = responseText.trim()
            if (cleanedResponse.contains("TOOL_CALL: get_battery_level")) {
                // Add the tool invocation log to memory (mapped to Model: TOOL_CALL: get_battery_level)
                val toolCallMsg = Message("Tool", "TOOL_CALL: get_battery_level", isSystem = true)
                _messages.value = _messages.value + toolCallMsg

                // Query and log the real output
                val batteryLevel = getBatteryStatus()
                val observationMsg = Message("System", "Battery level is $batteryLevel%", isSystem = true)
                _messages.value = _messages.value + observationMsg

                // Recurse to next step in the loop
                runAgentLoop(step + 1)
            } else {
                // Final conversational answer
                _messages.value = _messages.value + Message("NanoClaw", responseText)
                _isLoading.value = false
            }
        }
    }

    private fun buildPrompt(history: List<Message>): String {
        val sb = StringBuilder()
        sb.append("System: ").append(systemPrompt).append("\n\n")

        // Include last 12 turns of message history
        val chatHistory = history.takeLast(12)
        for (msg in chatHistory) {
            val role = when {
                msg.isSystem && msg.sender == "Tool" -> "Model"
                msg.isSystem && msg.sender == "System" -> "System Observation"
                msg.sender == "You" -> "User"
                msg.sender == "NanoClaw" -> "Model"
                else -> continue
            }
            sb.append(role).append(": ").append(msg.text).append("\n")
        }

        sb.append("Model: ")
        return sb.toString()
    }

    private fun getBatteryStatus(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }
}
