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
├── fixed_size/
│   ├── index.json
│   └── index.sqlite
└── structural/
    ├── index.json
    └── index.sqlite
```

## Что показать при сдаче

1. Консольный вывод `pipeline.py` с блоком `=== СРАВНЕНИЕ СТРАТЕГИЙ CHUNKING ===`
2. Файл `comparison_report.json` (там статистика и estimated pages)
3. Любой из индексов (`fixed_size/index.json`, `structural/index.json`) — показать, что у чанков есть эмбеддинг и метаданные.

## Комментарий по требованию 20–30 страниц

По умолчанию индексируются:

- `README.md`
- `app/src/main/java` (весь код проекта)

Это значительно превышает 20–30 страниц в эквиваленте кода.
