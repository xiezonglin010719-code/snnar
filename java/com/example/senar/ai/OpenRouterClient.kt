package com.example.senar.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class OpenRouterClient(
    private val apiKey: String,
    private val model: String = "deepseek/deepseek-chat-v3-0324:free"
) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    fun chat(messages: List<ChatMessage>): String {
        val reqBody = OpenAIChatRequest(model = model, messages = messages)
        val bodyStr = json.encodeToString(reqBody)

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/xiezonglin010719-code/snnar")
            .addHeader("X-Title", "SNNAR Sleep Assistant")
            .post(bodyStr.toRequestBody(mediaType))
            .build()

        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("OpenRouter error ${resp.code}: $body")
            }

            val parsed: OpenAIChatResponse = json.decodeFromString(body)
            return parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        }
    }
}
