package com.example.nanoclaw

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Interface to communicate with the local Gemini Nano model via Google Play Services / AICore.
 */
class GeminiNanoClient(private val context: Context) {

    private val generativeModel = Generation.getClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun checkAvailability(onResult: (Boolean) -> Unit) {
        onResult(true)
    }

    fun generateResponse(prompt: String, onResult: (String) -> Unit) {
        scope.launch {
            try {
                // Build the GenerateContentRequest using TextPart
                val request = generateContentRequest(TextPart(prompt)) {}

                // Run on-device inference using Gemini Nano (suspend function)
                val response = generativeModel.generateContent(request)
                
                // Get the text from the first candidate in the response
                val text = response.candidates
                    .firstOrNull()
                    ?.text
                    .orEmpty()
                
                if (text.isBlank()) {
                    onResult("Gemini Nano generated an empty response.")
                } else {
                    onResult(text)
                }
            } catch (e: Exception) {
                Log.e("GeminiNanoClient", "Local inference failed", e)
                onResult("Inference failed: ${e.localizedMessage}\n\nMake sure your device supports Gemini Nano, and 'Enable on-device GenAI' is turned on in AICore developer options.")
            }
        }
    }
}
