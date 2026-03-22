package ru.zis.prompting.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.zis.prompting.data.EmbeddingsRequest
import ru.zis.prompting.network.RouterAiApi
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

class RagRepository(
    private val context: Context,
    private val api: RouterAiApi,
    private val embeddingModel: String = "openai/text-embedding-3-large",
    private val assetPath: String = "rag/structural_index.json",
    private val filePath: String = "rag_pipeline/output/structural/index.json"
) {
    companion object {
        private const val LOW_RELEVANCE_THRESHOLD = 0.25f
        private const val LOW_RELEVANCE_THRESHOLD_PROJECT_STRUCTURE = 0.08f
        private const val LOW_RELEVANCE_FALLBACK = """
## Ответ
Не знаю. В базе знаний нет достаточно релевантной информации по этому вопросу. Уточните запрос.

## Источники
- нет релевантных источников

## Цитаты
- нет релевантных цитат
"""
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val loadMutex = Mutex()

    @Volatile
    private var cachedChunks: List<RagIndexedChunk>? = null

    suspend fun buildRagSystemMessage(question: String, topK: Int = 8): String? {
        val query = question.trim()
        if (query.isBlank()) return null

        val dialogMemoryQuestion = isDialogMemoryQuestion(query)
        val projectStructureQuestion = isProjectStructureQuestion(query)

        val hits = retrieveRelevantChunks(query = query, topK = topK)

        if (dialogMemoryQuestion) {
            val reason = if (hits.isEmpty()) {
                "Это вопрос по состоянию текущего диалога. Контекст в индексной базе не найден. Приоритет — память диалога."
            } else {
                "Это вопрос по состоянию текущего диалога. Приоритет — память диалога, RAG-фрагменты ниже как дополнительная опора."
            }
            return buildDialogMemoryFallbackPrompt(
                query = query,
                reason = reason,
                hits = hits
            )
        }

        if (hits.isEmpty()) {
            if (projectStructureQuestion) {
                return buildDialogMemoryFallbackPrompt(
                    query = query,
                    reason = "Контекст по структуре проекта в индексной базе не найден. Используй память диалога и явно укажи, что ответ может быть неполным.",
                    hits = emptyList()
                )
            }

            return buildString {
                appendLine("=== RAG КОНТЕКСТ ===")
                appendLine("Контекст не найден. Верни строго следующий ответ без изменений:")
                appendLine()
                appendLine(LOW_RELEVANCE_FALLBACK.trim())
                appendLine()
                append("=== КОНЕЦ RAG КОНТЕКСТА ===")
            }
        }

        val maxScore = hits.maxOfOrNull { it.score } ?: 0f
        val threshold = if (projectStructureQuestion) {
            LOW_RELEVANCE_THRESHOLD_PROJECT_STRUCTURE
        } else {
            LOW_RELEVANCE_THRESHOLD
        }

        if (projectStructureQuestion) {
            return buildBestEffortRagContext(query = query, hits = hits, maxScore = maxScore)
        }

        if (maxScore < threshold) {
            return buildString {
                appendLine("=== RAG КОНТЕКСТ ===")
                appendLine("Контекст слишком слабый (maxScore=${"%.4f".format(maxScore)} < $threshold).")
                appendLine("Верни строго следующий ответ без изменений:")
                appendLine()
                appendLine(LOW_RELEVANCE_FALLBACK.trim())
                appendLine()
                append("=== КОНЕЦ RAG КОНТЕКСТА ===")
            }
        }

        return buildStandardRagContext(query = query, hits = hits, maxScore = maxScore)
    }

    private fun buildStandardRagContext(query: String, hits: List<RagChunkHit>, maxScore: Float): String {
        val contextText = hits.joinToString("\n\n") { hit ->
            buildString {
                appendLine("chunk_id: ${hit.chunkId}")
                appendLine("source: ${hit.source}")
                appendLine("title: ${hit.title}")
                appendLine("section: ${hit.section}")
                appendLine("score: ${"%.4f".format(hit.score)}")
                appendLine("fragment:")
                append(hit.text)
            }
        }

        return buildString {
            appendLine("=== RAG КОНТЕКСТ ===")
            appendLine("Ниже — релевантные фрагменты базы знаний проекта.")
            appendLine("Опирайся только на них при ответе.")
            appendLine()
            appendLine("ОБЯЗАТЕЛЬНЫЙ ФОРМАТ ОТВЕТА:")
            appendLine("## Ответ")
            appendLine("<краткий и точный ответ>")
            appendLine()
            appendLine("## Источники")
            appendLine("- source: <путь>, section: <section>, chunk_id: <chunk_id>")
            appendLine("- ...")
            appendLine()
            appendLine("## Цитаты")
            appendLine("> \"<дословный фрагмент из найденных чанков>\"")
            appendLine("> ...")
            appendLine()
            appendLine("Правила:")
            appendLine("1) Источники и цитаты — обязательны в каждом ответе.")
            appendLine("2) Секции 'Источники' и 'Цитаты' должны ссылаться только на реально использованные чанки.")
            appendLine("3) Смысл секции 'Ответ' должен соответствовать приведённым цитатам.")
            appendLine("4) Если данных недостаточно — ответь строго фразой: 'Не знаю. В базе знаний нет достаточно релевантной информации по этому вопросу. Уточните запрос.'")
            appendLine()
            appendLine("Вопрос пользователя: $query")
            appendLine("Максимальная релевантность: ${"%.4f".format(maxScore)}")
            appendLine()
            appendLine("Релевантные фрагменты:")
            appendLine(contextText)
            append("=== КОНЕЦ RAG КОНТЕКСТА ===")
        }
    }

    private fun buildBestEffortRagContext(query: String, hits: List<RagChunkHit>, maxScore: Float): String {
        val contextText = hits.joinToString("\n\n") { hit ->
            buildString {
                appendLine("chunk_id: ${hit.chunkId}")
                appendLine("source: ${hit.source}")
                appendLine("title: ${hit.title}")
                appendLine("section: ${hit.section}")
                appendLine("score: ${"%.4f".format(hit.score)}")
                appendLine("fragment:")
                append(hit.text)
            }
        }

        return buildString {
            appendLine("=== RAG КОНТЕКСТ ===")
            appendLine("Вопрос относится к структуре проекта/архитектуре.")
            appendLine("Дай best-effort ответ по найденным фрагментам. Если данных мало — укажи это явно, но НЕ используй автоматический шаблон 'Не знаю'.")
            appendLine()
            appendLine("ОБЯЗАТЕЛЬНЫЙ ФОРМАТ ОТВЕТА:")
            appendLine("## Ответ")
            appendLine("<краткий и точный ответ>")
            appendLine()
            appendLine("## Источники")
            appendLine("- source: <путь>, section: <section>, chunk_id: <chunk_id>")
            appendLine("- ...")
            appendLine()
            appendLine("## Цитаты")
            appendLine("> \"<дословный фрагмент из найденных чанков>\"")
            appendLine("> \"...\"")
            appendLine()
            appendLine("Правила:")
            appendLine("1) Источники и цитаты обязательны.")
            appendLine("2) Не выдумывай сущности, которых нет в фрагментах.")
            appendLine("3) Если список неполный — явно напиши, что это предварительный список по доступным фрагментам.")
            appendLine()
            appendLine("Вопрос пользователя: $query")
            appendLine("Максимальная релевантность: ${"%.4f".format(maxScore)}")
            appendLine()
            appendLine("Релевантные фрагменты:")
            appendLine(contextText)
            append("=== КОНЕЦ RAG КОНТЕКСТА ===")
        }
    }

    suspend fun retrieveRelevantChunks(query: String, topK: Int = 4): List<RagChunkHit> = withContext(Dispatchers.IO) {
        val chunks = ensureChunksLoaded()
        if (chunks.isEmpty()) return@withContext emptyList()

        val queryEmbedding = runCatching { createQueryEmbedding(query) }.getOrNull()
        val queryTokens = enrichQueryTokens(tokenize(query))

        if (queryEmbedding == null && queryTokens.isEmpty()) return@withContext emptyList()

        chunks.asSequence()
            .map { chunk ->
                val semanticScore = queryEmbedding?.let { dot(it, chunk.embedding) } ?: 0f
                val lexicalScore = lexicalOverlapScore(
                    queryTokens = queryTokens,
                    chunkText = buildString {
                        append(chunk.source)
                        append('\n')
                        append(chunk.title)
                        append('\n')
                        append(chunk.section)
                        append('\n')
                        append(chunk.text)
                    }
                )
                val combinedScore = if (queryEmbedding == null) {
                    lexicalScore
                } else {
                    semanticScore + 0.25f * lexicalScore
                }

                RagChunkHit(
                    chunkId = chunk.chunkId,
                    source = chunk.source,
                    title = chunk.title,
                    section = chunk.section,
                    text = chunk.text,
                    score = combinedScore
                )
            }
            .sortedByDescending { it.score }
            .take(topK.coerceAtLeast(1))
            .toList()
    }

    private suspend fun createQueryEmbedding(query: String): List<Float>? {
        val embeddingResp = api.createEmbeddings(
            EmbeddingsRequest(
                model = embeddingModel,
                input = listOf(query),
                encodingFormat = "float"
            )
        )

        if (embeddingResp.error != null) return null

        val queryVec = embeddingResp.data.firstOrNull()?.embedding?.map { it.toFloat() }.orEmpty()
        if (queryVec.isEmpty()) return null
        return normalize(queryVec)
    }

    private suspend fun ensureChunksLoaded(): List<RagIndexedChunk> {
        cachedChunks?.let { return it }

        return loadMutex.withLock {
            cachedChunks?.let { return@withLock it }

            val raw = readIndexJsonText() ?: run {
                cachedChunks = emptyList()
                return@withLock emptyList()
            }

            val parsed = parseChunks(raw)
            cachedChunks = parsed
            parsed
        }
    }

    private fun readIndexJsonText(): String? {
        val fromAssets = runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (!fromAssets.isNullOrBlank()) return fromAssets

        val projectFile = File(filePath)
        if (projectFile.exists() && projectFile.isFile) {
            return projectFile.readText(Charsets.UTF_8)
        }

        return null
    }

    private fun parseChunks(rawJson: String): List<RagIndexedChunk> {
        val arr = runCatching { json.parseToJsonElement(rawJson).jsonArray }.getOrNull() ?: return emptyList()

        return arr.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null

            val chunkId = runCatching { obj["chunk_id"]?.jsonPrimitive?.content?.trim().orEmpty() }.getOrDefault("")
            val source = runCatching { obj["source"]?.jsonPrimitive?.content?.trim().orEmpty() }.getOrDefault("")
            val title = runCatching { obj["title"]?.jsonPrimitive?.content?.trim().orEmpty() }.getOrDefault("")
            val section = runCatching { obj["section"]?.jsonPrimitive?.content?.trim().orEmpty() }.getOrDefault("")
            val text = runCatching { obj["text"]?.jsonPrimitive?.content?.trim().orEmpty() }.getOrDefault("")
            val embedding = obj["embedding"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.doubleOrNull?.toFloat() }
                .orEmpty()

            if (chunkId.isBlank() || source.isBlank() || text.isBlank() || embedding.isEmpty()) {
                return@mapNotNull null
            }

            RagIndexedChunk(
                chunkId = chunkId,
                source = source,
                title = title,
                section = section,
                text = text,
                embedding = normalize(embedding)
            )
        }
    }

    private fun normalize(vec: List<Float>): List<Float> {
        val norm = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        if (norm <= 0f) return vec
        return vec.map { it / norm }
    }

    private fun dot(a: List<Float>, b: List<Float>): Float {
        val n = minOf(a.size, b.size)
        if (n <= 0) return 0f
        var sum = 0f
        for (i in 0 until n) {
            sum += a[i] * b[i]
        }
        return sum
    }

    private fun tokenize(text: String): Set<String> {
        return Regex("[\\p{L}\\p{N}_]+")
            .findAll(text.lowercase(Locale.ROOT))
            .map { it.value.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun enrichQueryTokens(base: Set<String>): Set<String> {
        if (base.isEmpty()) return base

        val synonyms = linkedMapOf(
            "архитектура" to listOf("architecture", "app", "android", "module", "layer", "layers"),
            "слой" to listOf("layer", "layers", "data", "domain", "ui"),
            "данных" to listOf("data", "database", "db", "room", "entity", "dao"),
            "репозитор" to listOf("repository", "repositories", "chatrepository", "userprofilerepository", "invariantrepository", "weatherrepository"),
            "база" to listOf("database", "db", "room", "appdatabase"),
            "таблиц" to listOf("table", "tables", "entity", "entities"),
            "храни" to listOf("storage", "persist", "save", "load"),
            "dao" to listOf("dao", "chatsessiondao", "weatherrecorddao", "userprofiledao", "invariantdao"),
            "viewmodel" to listOf("viewmodel", "chatviewmodel", "profileviewmodel"),
            "rag" to listOf("rag", "retrieval", "chunk", "embedding", "index")
        )

        val additions = mutableSetOf<String>()
        for (token in base) {
            synonyms.forEach { (key, extra) ->
                if (token.contains(key) || key.contains(token)) {
                    additions += extra
                }
            }
        }

        return base + additions
    }

    private fun isProjectStructureQuestion(query: String): Boolean {
        val q = query.lowercase(Locale.ROOT)
        val markers = listOf(
            "архитектур", "сло", "data layer", "слой данных", "репозитор", "repository",
            "room", "dao", "таблиц", "entity", "база данных", "appdatabase"
        )
        return markers.any { q.contains(it) }
    }

    private fun isDialogMemoryQuestion(query: String): Boolean {
        val q = query.lowercase(Locale.ROOT)
        val markers = listOf(
            "подведи итог", "итог", "что ты узнал", "что мы", "мы уточнили",
            "какие ограничения", "напомни цель", "цель диалога", "за время разговора",
            "в нашем диалоге", "обсуждали", "зафиксировали"
        )
        return markers.any { q.contains(it) }
    }

    private fun buildDialogMemoryFallbackPrompt(query: String, reason: String, hits: List<RagChunkHit>): String {
        val weakRagSection = if (hits.isEmpty()) {
            ""
        } else {
            buildString {
                appendLine()
                appendLine("Дополнительные RAG-фрагменты (используй только при релевантности):")
                hits.forEach { hit ->
                    appendLine("- source: ${hit.source}, section: ${hit.section}, chunk_id: ${hit.chunkId}, score=${"%.4f".format(hit.score)}")
                }
            }
        }

        return buildString {
            appendLine("=== RAG КОНТЕКСТ ===")
            appendLine(reason)
            appendLine("Для этого вопроса разрешено опереться на память диалога (working memory / summary / recent history).")
            appendLine()
            appendLine("ОБЯЗАТЕЛЬНЫЙ ФОРМАТ ОТВЕТА:")
            appendLine("## Ответ")
            appendLine("<краткий и точный ответ по памяти текущего диалога>")
            appendLine()
            appendLine("## Источники")
            appendLine("- source: memory://working-memory, section: working_memory")
            appendLine("- source: memory://summary, section: summary")
            appendLine("- source: memory://recent-history, section: dialog")
            appendLine()
            appendLine("## Цитаты")
            appendLine("> \"<краткая дословная цитата из сообщений пользователя/ассистента или из summary>\"")
            appendLine("> \"...\"")
            appendLine()
            appendLine("Правила:")
            appendLine("1) Источники и цитаты обязательны.")
            appendLine("2) Не выдумывай факты: опирайся только на текущую память диалога.")
            appendLine("3) Если в памяти недостаточно данных, прямо так и скажи, но формат сохрани.")
            appendLine()
            appendLine("Вопрос пользователя: $query")
            append(weakRagSection)
            append("=== КОНЕЦ RAG КОНТЕКСТА ===")
        }
    }

    private fun lexicalOverlapScore(queryTokens: Set<String>, chunkText: String): Float {
        if (queryTokens.isEmpty()) return 0f
        val chunkTokens = tokenize(chunkText)
        if (chunkTokens.isEmpty()) return 0f

        val overlap = queryTokens.count { it in chunkTokens }
        return overlap.toFloat() / queryTokens.size.toFloat()
    }
}

data class RagChunkHit(
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val text: String,
    val score: Float
)

private data class RagIndexedChunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val text: String,
    val embedding: List<Float>
)
