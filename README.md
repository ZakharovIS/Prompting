# Prompting — агент с памятью, инвариантами и MCP-инструментами

Проект содержит учебную реализацию Android-агента с:
- 3-слойной памятью,
- отдельным слоем инвариантов,
- MCP-инструментами,
- периодическим weather-сценарием (24/7 сбор + уведомления + summary).

---

## Что реализовано

### 1) Память агента

- **Краткосрочная (Short-term):** текущий диалог в `ChatAgent`.
- **Рабочая (Working):** цель/факты/открытые вопросы, хранится в `chat_sessions.workingMemoryJson`.
- **Долговременная (Long-term):** профильные факты и договорённости, таблица `long_term_memory`.

### 2) Инварианты (Invariant Layer)

- Хранятся отдельно в таблице `invariants`.
- Подмешиваются в system-контекст.
- После генерации выполняется дополнительная LLM-валидация ответа.
- При конфликте ассистент возвращает отказ с объяснением нарушенного инварианта.

### 3) MCP weather-инструменты

Добавлены инструменты:

1. `get_weather_now`
   - разовый запрос погоды через Visual Crossing,
   - опциональное сохранение измерения в БД.

2. `weather_scheduler`
   - `start | stop | status | summary`,
   - запуск периодического сбора через WorkManager,
   - сохранение измерений в Room,
   - агрегация последних результатов (`summary`).

---

## Реализация задачи «агент 24/7»

Сценарий соответствует требованиям:

- **Сохранение данных:** таблица `weather_records` (Room / SQLite).
- **Выполнение по расписанию:** `WorkManager` (`PeriodicWorkRequest`).
- **Агрегированный результат:** MCP action `summary` + экран истории.

### Важный demo-момент

При `weather_scheduler.start` сделано поведение:
1. **сразу** выполняется запрос погоды,
2. **сразу** показывается notification в шторке,
3. затем продолжается периодический запуск с указанным интервалом.

То есть демонстрация заметна мгновенно, а не только после первого периода WorkManager.

---

## База данных

- Версия Room: **v6**.
- Миграции:
  - `2 -> 3`: `workingMemoryJson` + `long_term_memory`
  - `3 -> 4`: `user_profiles`
  - `4 -> 5`: `invariants`
  - `5 -> 6`: `weather_records`

---

## UI

- В чате действия верхней панели перенесены в компактное меню (`⋮`), чтобы избежать наложения кнопок.
- В меню доступны переходы: Профили, MCP, История погоды, memory dump, очистка чата.
- Добавлен экран **«История измерений погоды»**.

---

## Настройка API ключа погоды

Используется `BuildConfig.VISUAL_CROSSING_API_KEY`.

Добавь в `local.properties`:

```properties
VISUAL_CROSSING_API_KEY=your_key_here
```

---

## Примеры MCP-вызовов

### Разовый запрос

```json
{
  "tool": "get_weather_now",
  "arguments": {
    "location": "Moscow",
    "save": true
  }
}
```

### Запуск расписания (с немедленным показом)

```json
{
  "tool": "weather_scheduler",
  "arguments": {
    "action": "start",
    "location": "Moscow",
    "intervalMinutes": 60,
    "runNow": true
  }
}
```

### Сводка

```json
{
  "tool": "weather_scheduler",
  "arguments": {
    "action": "summary",
    "limit": 10
  }
}
```

---

## Сборка

```bash
gradlew.bat :app:assembleDebug
```

Проверка пройдена: **BUILD SUCCESSFUL**.
