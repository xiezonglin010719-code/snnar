package com.example.senar.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Qwen (Alibaba Cloud Model Studio / DashScope) OpenAI-compatible client.
 *
 * CN(Beijing):  https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 * Intl(SG):     https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions
 */
class QwenClient(
    private val apiKey: String,
    private val model: String = "qwen-plus",
    private val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1" // 国内北京; 在海外也可改成 intl
) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    fun chat(messages: List<ChatMessage>): String {
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "Qwen apiKey 为空" }
        require(cleanKey.all { it.code in 32..126 }) { "Qwen apiKey 含有非 ASCII 字符（可能混入中文/换行/空格）" }

        val reqBody = OpenAIChatRequest(model = model, messages = messages)
        val bodyStr = json.encodeToString(reqBody)

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $cleanKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyStr.toRequestBody(mediaType))
            .build()

        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Qwen error ${resp.code}: $body")
            }
            val parsed = json.decodeFromString(OpenAIChatResponse.serializer(), body)
            return parsed.choices.firstOrNull()?.message?.content ?: ""
        }
    }
}
