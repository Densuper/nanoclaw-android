package com.example.nanoclaw

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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
        You are NanoClaw, a helpful local AI assistant. 
        You have access to the following tools:
        - get_battery_level: Returns the battery level percentage of the device.
        - get_storage_info: Returns the free/available storage space on the device.
        - get_device_info: Returns the device model, manufacturer name, and Android version.

        Rules:
        - If the user asks about the battery level or charge percentage and you do not know it, respond ONLY with: TOOL_CALL: get_battery_level
        - If the user asks about storage space, disk space, or memory space and you do not know it, respond ONLY with: TOOL_CALL: get_storage_info
        - If the user asks about the phone model, brand, manufacturer, or OS/Android version and you do not know it, respond ONLY with: TOOL_CALL: get_device_info
        - If a tool output is provided under "System Observation", use that information to answer the user directly in a conversational sentence. Do not call the same tool again.
        - For all other messages, greetings, or questions (like "Hi" or "How are you?"), reply conversationally and do NOT call any tools.
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
            when {
                cleanedResponse.contains("TOOL_CALL: get_battery_level") -> {
                    // Tool call log
                    val toolCallMsg = Message("Tool", "TOOL_CALL: get_battery_level", isSystem = true)
                    _messages.value = _messages.value + toolCallMsg

                    // Query real battery
                    val batteryLevel = getBatteryStatus()
                    val observationMsg = Message("System", "Battery level is $batteryLevel%", isSystem = true)
                    _messages.value = _messages.value + observationMsg

                    // Next step
                    runAgentLoop(step + 1)
                }
                cleanedResponse.contains("TOOL_CALL: get_storage_info") -> {
                    // Tool call log
                    val toolCallMsg = Message("Tool", "TOOL_CALL: get_storage_info", isSystem = true)
                    _messages.value = _messages.value + toolCallMsg

                    // Query real storage
                    val storageInfo = getStorageStatus()
                    val observationMsg = Message("System", "Available storage is $storageInfo", isSystem = true)
                    _messages.value = _messages.value + observationMsg

                    // Next step
                    runAgentLoop(step + 1)
                }
                cleanedResponse.contains("TOOL_CALL: get_device_info") -> {
                    // Tool call log
                    val toolCallMsg = Message("Tool", "TOOL_CALL: get_device_info", isSystem = true)
                    _messages.value = _messages.value + toolCallMsg

                    // Query real device info
                    val deviceInfo = getDeviceInfo()
                    val observationMsg = Message("System", "Device info is: $deviceInfo", isSystem = true)
                    _messages.value = _messages.value + observationMsg

                    // Next step
                    runAgentLoop(step + 1)
                }
                else -> {
                    // Final conversational answer
                    _messages.value = _messages.value + Message("NanoClaw", responseText)
                    _isLoading.value = false
                }
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

    private fun getStorageStatus(): String {
        return try {
            val path: File = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val freeBytes = availableBlocks * blockSize
            val freeGB = freeBytes / (1024.0 * 1024.0 * 1024.0)
            String.format("%.2f GB free", freeGB)
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
    }
}
