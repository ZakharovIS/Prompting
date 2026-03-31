# Local LLM Service (Ollama + HTTP API + Web Chat)

Локальный приватный AI-сервис на ПК:

- Ollama как движок моделей
- FastAPI прокси (`/chat`, `/models`, `/health`) с ограничениями
- Web-чат в браузере

## Что реализовано

- **HTTP API** на `:8080`
- **Rate limit** (по IP): `RATE_LIMIT_PER_MINUTE` (по умолчанию `20`)
- **Ограничение контекста**: `MAX_CONTEXT` (по умолчанию `4096`)
- **Web chat (диалоговый)**: `http://<host>:8080/` с историей сообщений в сессии

## Файлы

- `main.py` — API/прокси
- `web/index.html` — веб-чат
- `load_test.py` — тест параллельных запросов
- `rate_limit_test.py` — тест rate limit
- `requirements.txt` — зависимости

## Установка

```bat
python -m pip install -r local_llm_service/requirements.txt
```

## Запуск Ollama для доступа по сети (Windows)

```bat
set OLLAMA_HOST=0.0.0.0:11434
ollama serve
```

## Запуск API сервиса

```bat
set OLLAMA_URL=http://127.0.0.1:11434
set MAX_CONTEXT=4096
set RATE_LIMIT_PER_MINUTE=20
python -m uvicorn local_llm_service.main:app --host 0.0.0.0 --port 8080
```

## Быстрый запуск/остановка через .bat

Из корня проекта:

```bat
local_llm_service\start_local_llm_service.bat
```

Скрипт откроет 2 окна: `ollama serve` и `uvicorn`.

Остановка:

```bat
local_llm_service\stop_local_llm_service.bat
```

Скрипт завершает процессы, слушающие порты `8080` (API) и `11434` (Ollama).

## Проверки

### Health
```bat
curl http://127.0.0.1:8080/health
```

### Список моделей
```bat
curl http://127.0.0.1:8080/models
```

### Чат-запрос
```bat
curl -X POST http://127.0.0.1:8080/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"prompt\":\"Привет! Ответь в 1 предложении\",\"model\":\"llama3.1:8b\",\"max_context\":1024}"
```

### Диалоговый чат-запрос (с историей)
```bat
curl -X POST http://127.0.0.1:8080/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"model\":\"llama3.1:8b\",\"max_context\":1024,\"messages\":[{\"role\":\"user\",\"content\":\"Привет\"},{\"role\":\"assistant\",\"content\":\"Привет! Чем помочь?\"},{\"role\":\"user\",\"content\":\"Сделай короткий план изучения RAG\"}]}"
```

`/chat` теперь поддерживает оба формата:
- простой: `prompt` (один вопрос/ответ)
- диалоговый: `messages[]` (полноценный многосообщный контекст)

### Нагрузочный тест (5 параллельных запросов)
```bat
python local_llm_service/load_test.py
```

### Тест rate limit
Запустить второй инстанс с меньшим лимитом, например 3 req/min, на `:8081`, затем:

```bat
python local_llm_service/rate_limit_test.py
```

## Подключение по сети

Если IP хоста `192.168.1.114`, то:

- API: `http://192.168.1.114:8080/chat`
- Web chat: `http://192.168.1.114:8080/`

Не забудьте разрешить порт в Windows Firewall (если нужно).
