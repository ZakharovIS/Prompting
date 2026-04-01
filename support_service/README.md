# Support AI Assistant (мини-сервис поддержки)

Отдельный веб-сервис (не затрагивает `local_llm_service/`) для демонстрации AI-ассистента поддержки пользователей:

- отвечает на вопросы о продукте;
- использует RAG по FAQ (`faq.json`);
- учитывает контекст пользователя/тикета из mock CRM (`crm_data.json`).

## Что реализовано

- **Mock CRM**: пользователи и тикеты в JSON.
- **RAG retrieval**: простой лексический поиск релевантных FAQ (по токенам + буст категории тикета).
- **Контекст тикета**: в system prompt добавляются поля тикета и пользователя.
- **Веб-чат**: выбор тикета + чат с историей в текущей вкладке.

## Структура

```text
support_service/
  crm_data.json
  faq.json
  main.py
  requirements.txt
  start_support_service.bat
  web/
    index.html
```

## API

- `GET /` — веб-интерфейс
- `GET /health` — health-check
- `GET /models` — модели Ollama
- `GET /tickets` — список тикетов
- `GET /ticket/{ticket_id}` — тикет + пользователь
- `GET /user/{user_id}` — пользователь
- `POST /chat` — чат с учётом `ticket_id` и FAQ

Пример `POST /chat`:

```json
{
  "messages": [
    { "role": "user", "content": "Почему не работает авторизация?" }
  ],
  "ticket_id": "t001",
  "model": "llama3.1:8b",
  "max_context": 2048,
  "temperature": 0.2
}
```

## Запуск

1. Убедитесь, что Ollama запущен (`http://127.0.0.1:11434`).
2. Установите зависимости:

```bat
pip install -r support_service/requirements.txt
```

3. Запустите сервис:

```bat
support_service\start_support_service.bat
```

4. Откройте в браузере:

```text
http://127.0.0.1:8001
```

## Примечания

- Это демонстрационный вариант MCP/CRM-интеграции через локальный JSON.
- При выборе тикета ответы ассистента становятся контекстными (ticket + user + FAQ).