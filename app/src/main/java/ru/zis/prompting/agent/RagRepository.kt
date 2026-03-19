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

    suspend fun buildRagSystemMessage(question: String, topK: Int = 4): String? {
        val query = question.trim()
        if (query.isBlank()) return null

        val hits = retrieveRelevantChunks(query = query, topK = topK)
        if (hits.isEmpty()) {
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
        if (maxScore < LOW_RELEVANCE_THRESHOLD) {
            return buildString {
                appendLine("=== RAG КОНТЕКСТ ===")
                appendLine("Контекст слишком слабый (maxScore=${"%.4f".format(maxScore)} < $LOW_RELEVANCE_THRESHOLD).")
                appendLine("Верни строго следующий ответ без изменений:")
                appendLine()
                appendLine(LOW_RELEVANCE_FALLBACK.trim())
                appendLine()
                append("=== КОНЕЦ RAG КОНТЕКСТА ===")
            }
        }

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
            appendLine("")
            appendLine("ОБЯЗАТЕЛЬНЫЙ ФОРМАТ ОТВЕТА:")
            appendLine("## Ответ")
            appendLine("<краткий и точный ответ>")
            appendLine("")
            appendLine("## Источники")
            appendLine("- source: <путь>, section: <section>, chunk_id: <chunk_id>")
            appendLine("- ...")
            appendLine("")
            appendLine("## Цитаты")
            appendLine("> \"<дословный фрагмент из найденных чанков>\"")
            appendLine("> ...")
            appendLine("")
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

    suspend fun retrieveRelevantChunks(query: String, topK: Int = 4): List<RagChunkHit> = withContext(Dispatchers.IO) {
        val chunks = ensureChunksLoaded()
        if (chunks.isEmpty()) return@withContext emptyList()

        val queryEmbedding = runCatching { createQueryEmbedding(query) }.getOrNull()
        val queryTokens = tokenize(query)

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
                // hybrid: если embeddings недоступны, остаётся lexical;
                // если доступны — lexical помогает вытаскивать точные имена методов/файлов.
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

        if (embeddingResp.error != null) {
            return null
        }

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
