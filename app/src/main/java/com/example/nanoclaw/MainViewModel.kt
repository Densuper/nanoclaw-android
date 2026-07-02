package com.example.nanoclaw

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.Environment
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Message(
    val sender: String,
    val text: String,
    val isSystem: Boolean = false
)

object MemoryManager {
    private fun getMemoryFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appDir = File(downloadsDir, "NanoClaw")
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return File(appDir, "memory.txt")
    }

    fun saveMemory(messages: List<Message>) {
        try {
            val file = getMemoryFile()
            val sb = StringBuilder()
            for (msg in messages) {
                // Serialize as SENDER|||TEXT|||IS_SYSTEM
                sb.append(msg.sender).append("|||")
                  .append(msg.text).append("|||")
                  .append(msg.isSystem).append("\n")
            }
            file.writeText(sb.toString())
        } catch (e: Exception) {
            Log.e("MemoryManager", "Failed to save memory to public Download/NanoClaw/memory.txt", e)
        }
    }

    fun loadMemory(): List<Message> {
        val list = mutableListOf<Message>()
        try {
            val file = getMemoryFile()
            if (file.exists()) {
                val lines = file.readLines()
                for (line in lines) {
                    val parts = line.split("|||")
                    if (parts.size >= 3) {
                        val sender = parts[0]
                        val text = parts[1]
                        val isSystem = parts[2].toBoolean()
                        list.add(Message(sender, text, isSystem))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MemoryManager", "Failed to load memory from public Download/NanoClaw/memory.txt", e)
        }
        // If file doesn't exist or is empty, return a default initialization message
        if (list.isEmpty()) {
            list.add(Message("System", "NanoClaw Local Agent initialized. Local Gemini Nano connection active.", isSystem = true))
        }
        return list
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val client = GeminiNanoClient(application.applicationContext)
    private val context = application.applicationContext
    private val ioScope = CoroutineScope(Dispatchers.IO)

    // Load initial messages from long-term memory
    private val _messages = MutableStateFlow<List<Message>>(MemoryManager.loadMemory())
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
        - get_ram_info: Returns the current free and total RAM of the device.
        - turn_on_flashlight: Turns the phone's physical flashlight (torch) ON.
        - turn_off_flashlight: Turns the phone's physical flashlight (torch) OFF.
        - set_alarm [HOUR]:[MINUTE] [MESSAGE]: Sets a phone alarm clock for the specified time (24-hour format) with an optional note message.
        - web_search [QUERY]: Searches the web for information using a search engine query string.

        Rules:
        - If the user asks about the battery level or charge percentage and you do not know it, respond ONLY with: TOOL_CALL: get_battery_level
        - If the user asks about storage space, disk space, or memory space and you do not know it, respond ONLY with: TOOL_CALL: get_storage_info
        - If the user asks about the phone model, brand, manufacturer, or OS/Android version and you do not know it, respond ONLY with: TOOL_CALL: get_device_info
        - If the user asks about RAM or memory usage and you do not know it, respond ONLY with: TOOL_CALL: get_ram_info
        - If the user asks to turn on the flashlight/torch, respond ONLY with: TOOL_CALL: turn_on_flashlight
        - If the user asks to turn off the flashlight/torch, respond ONLY with: TOOL_CALL: turn_off_flashlight
        - If the user asks to set an alarm for a specific time, you must output ONLY: TOOL_CALL: set_alarm [HH]:[MM] [Optional Message]
          Example: If user says "Set alarm for 7:30 AM", you respond with ONLY: TOOL_CALL: set_alarm 07:30 Wake up
        - If the user asks a question requiring real-time internet information, news, current facts, or search lookups, you must output ONLY: TOOL_CALL: web_search [QUERY]
          Example: If user says "Who is the president of France?", you respond with ONLY: TOOL_CALL: web_search president of France
          Example: If user says "What is the capital of Japan?", you respond with ONLY: TOOL_CALL: web_search capital of Japan
        - If a tool output is provided under "System Observation", use that information to answer the user directly in a conversational sentence. Do not call the same tool again.
        - For all other messages, greetings, or questions, reply conversationally and do NOT call any tools.
    """.trimIndent()

    private fun updateMessagesAndSave(newMessagesList: List<Message>) {
        _messages.value = newMessagesList
        MemoryManager.saveMemory(newMessagesList)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = Message("You", text)
        updateMessagesAndSave(_messages.value + userMessage)
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
            updateMessagesAndSave(_messages.value + limitWarning)
            _isLoading.value = false
            return
        }

        val prompt = buildPrompt(_messages.value)

        client.generateResponse(prompt) { responseText ->
            val cleanedResponse = responseText.trim()
            val alarmRegex = Regex("TOOL_CALL:\\s*set_alarm\\s+(\\d{2}):(\\d{2})(.*)", RegexOption.IGNORE_CASE)
            val alarmMatch = alarmRegex.find(cleanedResponse)

            val searchRegex = Regex("TOOL_CALL:\\s*web_search\\s+(.*)", RegexOption.IGNORE_CASE)
            val searchMatch = searchRegex.find(cleanedResponse)

            when {
                cleanedResponse.contains("TOOL_CALL: get_battery_level") -> {
                    val toolCallMsg = Message("Tool", "TOOL_CALL: get_battery_level", isSystem = true)
                    val batteryLevel = getBatteryStatus()
                    val observationMsg = Message("System", "Battery level is $batteryLevel%", isSystem = true)
                    
                    updateMessagesAndSave(_messages.value + toolCallMsg + observationMsg)
                    runAgentLoop(step + 1)
                }
                cleanedResponse.contains("TOOL_CALL: get_storage_info") -> {
                    val toolCallMsg = Message("Tool", "TOOL_CALL: get_storage_info", isSystem = true)
                    val storageInfo = getStorageStatus()
                    val observationMsg = Message("System", "Available storage is $storageInfo", isSystem = true)
                    
                    updateMessagesAndSave(_messages.value + toolCallMsg + observationMsg)
                    runAgentLoop(step + 1)
                }
                cleanedResponse.contains("TOOL_CALL: get_device_info") -> {
                    val toolCallMsg = Message("Tool", "TOOL_CALL: get_device_info", isSystem = true)
                    val deviceInfo = getDeviceInfo()
                    val observationMsg = Message("System", "Device info is: $deviceInfo", isSystem = true)
                    
                    updateMessagesAndSave(_messages.value + toolCallMsg + observationMsg)
                    runAgentLoop(step + 1)
                }
                cleanedResponse.contains("TOOL_CALL: get_ram_info") -> {
                    val toolCallMsg = Message("Tool", "TOOL_CALL: get_ram_info", isSystem = true)
                    val ramInfo = getRamStatus()
                    val observationMsg = Message("System", "RAM usage details: $ramInfo", isSystem = true)
                    
                    updateMessagesAndSave(_messages.value + toolCallMsg + observationMsg)
                    runAgentLoop(step + 1)
                }
                cleanedResponse.contains("TOOL_CALL: turn_on_flashlight") -> {
                    val toolCallMsg = Message("Tool", "TOOL_CALL: turn_on_flashlight", isSystem = true)
                    val result = toggleFlashlight(true)
                    val observationMsg = Message("System", result, isSystem = true)
                    
                    updateMessagesAndSave(_messages.value + toolCallMsg + observationMsg)
                    runAgentLoop(step + 1)
                }
                cleanedResponse.contains("TOOL_CALL: turn_off_flashlight") -> {
                    val toolCallMsg = Message("Tool", "TOOL_CALL: turn_off_flashlight", isSystem = true)
                    val result = toggleFlashlight(false)
                    val observationMsg = Message("System", result, isSystem = true)
                    
                    updateMessagesAndSave(_messages.value + toolCallMsg + observationMsg)
                    runAgentLoop(step + 1)
                }
                alarmMatch != null -> {
                    val fullToolCall = alarmMatch.groupValues[0]
                    val hour = alarmMatch.groupValues[1].toIntOrNull() ?: 0
                    val minute = alarmMatch.groupValues[2].toIntOrNull() ?: 0
                    val message = alarmMatch.groupValues[3].trim().ifBlank { "Alarm set by NanoClaw" }

                    val toolCallMsg = Message("Tool", fullToolCall, isSystem = true)
                    val result = setAlarm(hour, minute, message)
                    val observationMsg = Message("System", result, isSystem = true)
                    
                    updateMessagesAndSave(_messages.value + toolCallMsg + observationMsg)
                    runAgentLoop(step + 1)
                }
                searchMatch != null -> {
                    val fullToolCall = searchMatch.groupValues[0]
                    val searchQuery = searchMatch.groupValues[1].trim()

                    val toolCallMsg = Message("Tool", fullToolCall, isSystem = true)
                    _messages.value = _messages.value + toolCallMsg

                    ioScope.launch {
                        val searchResult = performWebSearch(searchQuery)
                        val observationMsg = Message("System", "Web Search result: $searchResult", isSystem = true)
                        
                        withContext(Dispatchers.Main) {
                            updateMessagesAndSave(_messages.value + observationMsg)
                            runAgentLoop(step + 1)
                        }
                    }
                }
                else -> {
                    // Final conversational answer
                    updateMessagesAndSave(_messages.value + Message("NanoClaw", responseText))
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

    private fun getRamStatus(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val freeGB = memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0)
            val totalGB = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            String.format("%.2f GB available / %.2f GB total RAM", freeGB, totalGB)
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun toggleFlashlight(enable: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            if (enable) "Flashlight turned ON" else "Flashlight turned OFF"
        } catch (e: Exception) {
            "Failed to toggle flashlight: ${e.localizedMessage}"
        }
    }

    private fun setAlarm(hour: Int, minute: Int, message: String): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Alarm successfully set for %02d:%02d".format(hour, minute)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to set alarm", e)
            "Failed to set alarm: ${e.localizedMessage}"
        }
    }

    private fun performWebSearch(query: String): String {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()

                val json = JSONObject(sb.toString())
                val abstractText = json.optString("AbstractText", "")
                if (abstractText.isNotBlank()) {
                    abstractText
                } else {
                    val relatedTopics = json.optJSONArray("RelatedTopics")
                    if (relatedTopics != null && relatedTopics.length() > 0) {
                        val firstTopic = relatedTopics.getJSONObject(0)
                        firstTopic.optString("Text", "No search results found.")
                    } else {
                        "No instant search results found on DuckDuckGo."
                    }
                }
            } else {
                "Error contacting search server (HTTP ${conn.responseCode})"
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Web search failed", e)
            "Web search failed: ${e.localizedMessage}"
        }
    }
}
