package ru.zis.prompting.mcp

import kotlinx.serialization.json.JsonObject

class McpRegistry(
    tools: List<McpTool>
) {
    private val byName: Map<String, McpTool> = tools.associateBy { it.definition.name }

    fun listTools(): List<McpToolDefinition> = byName.values.map { it.definition }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        val tool = byName[name]
            ?: return McpToolResult(
                isError = true,
                content = "Инструмент '$name' не зарегистрирован."
            )

        return tool.call(arguments)
    }

    companion object {
        fun default(): McpRegistry = McpRegistry(
            tools = listOf(
                GeocodingMcpTool()
            )
        )
    }
}
