# File Agent (CLI)

Мини-ассистент, который делает реальные операции с файлами проекта:

- читает много файлов рекурсивно;
- ищет использование компонента/API;
- анализирует README и код на согласованность;
- генерирует новый файл `CHANGELOG.md`;
- сохраняет результаты в `file_agent_output/`.

## Запуск

Из корня проекта:

```bash
python file_agent/file_agent.py <command> [options]
```

### Интерактивный CLI-чат с локальной LLM (рекомендуется)

1. Запустите локальный LLM сервис:

```bat
local_llm_service\start_local_llm_service.bat
```

2. Запустите чат-ассистент:

```bash
python file_agent/chat_cli.py
```

3. Пишите цели обычным языком прямо в чате, например:

- `Сгенерируй changelog по 20 коммитам`
- `Найди где используется weather_pipeline`
- `Проверь README на соответствие коду`

Для выхода: `/exit` или `/quit`.

Одноразовый запуск без REPL:

```bash
python file_agent/chat_cli.py "Сгенерируй changelog по 10 коммитам"
```

## Команды

### 0) Постановка задачи на уровне цели (рекомендуется)

Вы задаёте цель на естественном языке, а ассистент сам выбирает нужные операции (поиск, аудит доков, changelog):

```bash
python file_agent/file_agent.py goal "Найди где используется `weather_pipeline`, проверь README и сгенерируй changelog по 20 коммитам"
```

По умолчанию результат выводится прямо в консоль ("в чат").

Если всё-таки нужен файл-отчёт, добавьте флаг:

```bash
python file_agent/file_agent.py goal "..." --save-report
```

Тогда будет создан:

- `file_agent_output/goal_report.md`

### 1) Поиск использования по проекту

```bash
python file_agent/file_agent.py scan weather_pipeline
```

По умолчанию вывод в консоль. Если нужен файл:

```bash
python file_agent/file_agent.py scan weather_pipeline --save-report
```

Тогда отчёт сохранится в:

- `file_agent_output/scan_weather_pipeline.md`

### 2) Проверка актуальности документации

```bash
python file_agent/file_agent.py check-docs
```

По умолчанию вывод в консоль. Если нужен файл:

```bash
python file_agent/file_agent.py check-docs --save-report
```

Тогда отчёт сохранится в:

- `file_agent_output/doc_audit.md`

### 3) Генерация changelog

```bash
python file_agent/file_agent.py gen-changelog --limit 30
```

Будет создан/обновлён файл:

- `CHANGELOG.md`

Опционально можно сохранить summary запуска:

```bash
python file_agent/file_agent.py gen-changelog --limit 30 --save-report
```

## Воспроизводимость

При запуске с флагом `--save-report` обновляется:

- `file_agent_output/last_run.json`

Там фиксируются последняя операция и путь к результату.

Для режима `goal` также фиксируется исходный текст цели.
