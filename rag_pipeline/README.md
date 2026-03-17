# RAG Indexing Pipeline (для сдачи задания)

Этот модуль реализует требуемый пайплайн:

- загрузка документов (`README`, код, `txt/md`)
- **2 стратегии chunking**:
  - `fixed_size` (фиксированный размер)
  - `structural` (по структуре: заголовки markdown / сигнатуры классов и функций в коде)
- генерация эмбеддингов через **RouterAI** (`openai/text-embedding-3-large`)
- сохранение индекса в:
  - `JSON`
  - `SQLite`
- метаданные для каждого чанка:
  - `source`
  - `title`
  - `section`
  - `chunk_id`

Дополнительно реализовано для задания по агенту:

- подключение RAG к Android чат-агенту (режимы **с RAG / без RAG**)
- отдельный Python-скрипт запроса в 2 режимах (`rag_query.py`)
- автоматическая оценка качества на 10 контрольных вопросах (`eval.py`)
- отчет сравнения качества ответов (`rag_vs_no_rag_eval.json`)

## Установка

```bash
python -m pip install -r rag_pipeline/requirements.txt
```

## Запуск (демо без API ключа)

```bash
python rag_pipeline/pipeline.py --mock-embeddings
```

## Запуск с RouterAI embeddings

Пайплайн читает ключ из `local.properties` (в корне проекта):

```bash
ROUTERAI_API_KEY=your_key_here
```

Также поддерживается переменная окружения `ROUTERAI_API_KEY`.

Запуск:

```bash
python rag_pipeline/pipeline.py --embedding-model openai/text-embedding-3-large
```

Доп. параметры (если нужно):

```bash
python rag_pipeline/pipeline.py \
  --routerai-base-url https://routerai.ru/api/v1 \
  --local-properties-path local.properties
```

## Что будет на выходе

```
rag_pipeline/output/
├── comparison_report.json
├── rag_vs_no_rag_eval.json
├── fixed_size/
│   ├── index.json
│   └── index.sqlite
└── structural/
    ├── index.json
    └── index.sqlite
```

Также в `rag_pipeline/` добавлены:

- `rag_query.py` — «вопрос → retrieval → объединение с вопросом → LLM»
- `control_questions.json` — 10 контрольных вопросов с ожиданиями и expected sources
- `eval.py` — прогон вопросов в двух режимах и расчет метрик

## Интеграция с чат-агентом Android

RAG подключен в агенте через `RagRepository`:

- `question` берется как последнее user-сообщение
- `retrieveRelevantChunks(...)` ищет top-k релевантных чанков по cosine similarity
- `buildRagSystemMessage(...)` собирает system-контекст с найденными источниками
- в `ChatAgent.buildContextMessages()` этот system-контекст добавляется перед user-сообщением

Переключение режима:

- UI `ChatScreen` (Switch) → `ChatViewModel.updateRagEnabled(...)`
- далее `ChatAgent.setRagEnabled(...)`
- если `ragEnabled = false`, запрос идет в модель без RAG-контекста

## Запуск сравнения «без RAG / с RAG» для одного вопроса

Без RAG:

```bash
python rag_pipeline/rag_query.py --question "Опиши последовательность RAG в проекте"
```

С RAG:

```bash
python rag_pipeline/rag_query.py --question "Опиши последовательность RAG в проекте" --use-rag --top-k 4
```

## Мини-набор из 10 контрольных вопросов

Файл: `rag_pipeline/control_questions.json`

Для каждого вопроса зафиксированы:

- `expectation` — что должно быть в ответе
- `expected_sources` — какие источники желательно использовать
- `expected_keywords` — базовая автоматическая проверка полноты

## Автооценка качества (RAG vs no-RAG)

Запуск:

```bash
python rag_pipeline/eval.py \
  --questions rag_pipeline/control_questions.json \
  --index rag_pipeline/output/structural/index.json \
  --out rag_pipeline/output/rag_vs_no_rag_eval.json
```

Что делает `eval.py`:

1. Для каждого из 10 вопросов получает 2 ответа:
   - `without_rag`
   - `with_rag`
2. Сохраняет retrieved hits (для RAG-режима)
3. Считает простую метрику `keyword_score`
4. Считает `source_coverage` по expected sources
5. Формирует общий summary с дельтой качества

Пример полученного результата в этом проекте:

- `avg_keyword_score_without_rag = 0.262`
- `avg_keyword_score_with_rag = 0.852`
- `avg_delta = +0.59`

То есть в среднем режим с RAG дал более релевантные ответы по контрольному набору.

## Важно: реиндексация после изменений кода

Если добавились новые файлы (например, `RagRepository.kt`, `rag_query.py`, `eval.py` и т.д.),
нужно пересобрать индекс и обновить assets для Android-приложения.

1) Пересобрать индекс (по умолчанию уже включает `rag_pipeline`):

```bash
python rag_pipeline/pipeline.py --embedding-model openai/text-embedding-3-large
```

2) Пересчитать eval-отчет:

```bash
python rag_pipeline/eval.py \
  --questions rag_pipeline/control_questions.json \
  --index rag_pipeline/output/structural/index.json \
  --out rag_pipeline/output/rag_vs_no_rag_eval.json
```

3) Синхронизировать индекс в assets (чтобы `RagRepository` читал актуальную базу):

```bash
copy /Y rag_pipeline\output\structural\index.json app\src\main\assets\rag\structural_index.json
```

Примечание: в `pipeline.py` используются уникальные `chunk_id` на основе полного пути файла,
чтобы избежать коллизий при индексации разных файлов с одинаковым именем.

## Что показать при сдаче

1. Консольный вывод `pipeline.py` с блоком `=== СРАВНЕНИЕ СТРАТЕГИЙ CHUNKING ===`
2. Файл `comparison_report.json` (там статистика и estimated pages)
3. Любой из индексов (`fixed_size/index.json`, `structural/index.json`) — показать, что у чанков есть эмбеддинг и метаданные.
4. Переключение режима RAG в Android-чате (сравнение ответа в 2 режимах на одном вопросе)
5. Файл `control_questions.json` (10 вопросов + ожидания + expected sources)
6. Файл `rag_vs_no_rag_eval.json` с итоговой сводкой качества

## Комментарий по требованию 20–30 страниц

По умолчанию индексируются:

- `README.md`
- `app/src/main/java` (весь код проекта)
- `rag_pipeline` (скрипты RAG, eval и контрольные вопросы)

Это значительно превышает 20–30 страниц в эквиваленте кода.
