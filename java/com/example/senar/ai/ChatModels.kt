package com.example.senar.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,      // "system" | "user" | "assistant"
    val content: String
)

@Serializable
data class OpenAIChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.4,
    @SerialName("max_tokens")
    val maxTokens: Int = 600
)

/**
 * ⚠️ 这是 OpenAI / OpenRouter 标准返回结构
 * {
 *   "choices": [
 *     {
 *       "message": {
 *         "role": "assistant",
 *         "content": "..."
 *       }
 *     }
 *   ]
 * }
 */
@Serializable
data class OpenAIChatResponse(
    val choices: List<Choice>
) {
    @Serializable
    data class Choice(
        val message: Message
    )

    @Serializable
    data class Message(
        val role: String,
        val content: String
    )
}
