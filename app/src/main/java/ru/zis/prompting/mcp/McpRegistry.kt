package ru.zis.prompting.mcp

import android.content.Context
import kotlinx.serialization.json.JsonObject
import ru.zis.prompting.db.WeatherRepository
import ru.zis.prompting.weather.WeatherScheduler

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
        fun default(
            context: Context? = null,
            weatherRepository: WeatherRepository? = null
        ): McpRegistry {
            val tools = mutableListOf<McpTool>(
                GeocodingMcpTool()
            )

            if (context != null && weatherRepository != null) {
                val multiFetchTool = WeatherMultiFetchMcpTool()
                val summarizeTool = WeatherSummarizeMcpTool()
                val saveReportTool = WeatherSaveReportMcpTool(weatherRepository = weatherRepository)

                tools += WeatherMcpTool(weatherRepository = weatherRepository)
                tools += WeatherSchedulerMcpTool(
                    weatherRepository = weatherRepository,
                    scheduler = WeatherScheduler(context.applicationContext)
                )
                tools += multiFetchTool
                tools += summarizeTool
                tools += saveReportTool
                tools += WeatherPipelineMcpTool(
                    multiFetchTool = multiFetchTool,
                    summarizeTool = summarizeTool,
                    saveReportTool = saveReportTool
                )
            }

            return McpRegistry(tools)
        }
    }
}
