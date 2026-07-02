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

    private val systemPrompt = """
        You are NanoClaw, a helpful local AI assistant running on this Android device.
        You have access to the following tool:
        - get_battery_level: Returns the current battery level percentage of the device.

        Instructions:
        1. If the user asks about battery level, power status, or charge percentage, you must output exactly:
        TOOL_CALL: get_battery_level
        Do not say anything else. Do not explain.
        2. If you receive a "System Observation" containing the battery level, use that information to answer the user's question.
    """.trimIndent()

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = Message("You", text)
        val currentHistory = _messages.value + userMessage
        _messages.value = currentHistory
        _isLoading.value = true

        // Build first prompt to model
        val initialPrompt = buildPrompt(currentHistory)

        client.generateResponse(initialPrompt) { responseText ->
            val cleanedResponse = responseText.trim()
            if (cleanedResponse.contains("TOOL_CALL: get_battery_level")) {
                // Intercept tool call, update UI
                val toolMessage = Message("Tool", "🤖 Executing tool: get_battery_level", isSystem = true)
                _messages.value = _messages.value + toolMessage

                // Run real battery query
                val batteryLevel = getBatteryStatus()
                val observation = "Battery level is $batteryLevel%"
                val observationMessage = Message("System", "Tool Output: $observation", isSystem = true)
                _messages.value = _messages.value + observationMessage

                // Build secondary prompt containing the history, the tool call, and the observation
                val secondPrompt = buildPrompt(
                    history = currentHistory,
                    toolCallText = "TOOL_CALL: get_battery_level",
                    observationText = observation
                )

                // Call model again with the tool observation
                client.generateResponse(secondPrompt) { finalResponse ->
                    _messages.value = _messages.value + Message("NanoClaw", finalResponse)
                    _isLoading.value = false
                }
            } else {
                // Standard conversational response
                _messages.value = _messages.value + Message("NanoClaw", responseText)
                _isLoading.value = false
            }
        }
    }

    private fun buildPrompt(
        history: List<Message>,
        toolCallText: String? = null,
        observationText: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("System: ").append(systemPrompt).append("\n\n")

        // Include last 10 messages for context
        val chatHistory = history.takeLast(10)
        for (msg in chatHistory) {
            if (msg.isSystem) continue
            val role = if (msg.sender == "You") "User" else "Model"
            sb.append(role).append(": ").append(msg.text).append("\n")
        }

        if (toolCallText != null && observationText != null) {
            sb.append("Model: ").append(toolCallText).append("\n")
            sb.append("System Observation: ").append(observationText).append("\n")
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
