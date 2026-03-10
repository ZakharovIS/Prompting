package ru.zis.prompting.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class McpToolCall(
    val tool: String,
    val arguments: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class McpToolResult(
    val isError: Boolean,
    val content: String,
    val payload: JsonObject = JsonObject(emptyMap())
)

interface McpTool {
    val definition: McpToolDefinition
    suspend fun call(arguments: JsonObject): McpToolResult
}
