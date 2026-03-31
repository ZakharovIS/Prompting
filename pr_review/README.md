# AI PR Review Pipeline (RAG + Diff)

Автоматическое ревью Pull Request с использованием:
- `git diff` и списка изменённых файлов,
- RAG-контекста по коду и документации,
- LLM через RouterAI.

## Что делает пайплайн

При каждом PR (`opened`, `synchronize`, `reopened`) GitHub Action:
1. Получает diff между base/head SHA.
2. Передаёт diff и список изменённых файлов в `pr_review/pr_reviewer.py`.
3. Скрипт делает retrieval по RAG-индексу (`index.json`).
4. LLM формирует ревью в структуре:
   - потенциальные баги,
   - архитектурные проблемы,
   - рекомендации.
5. Результат публикуется комментарием в PR.

## Файлы

- `.github/workflows/pr_review.yml` — GitHub Action
- `pr_review/pr_reviewer.py` — основной скрипт ревью
- `pr_review/requirements.txt` — Python зависимости

## Требования

1. Добавить секрет репозитория:
   - `ROUTERAI_API_KEY`
2. В репозитории должен быть доступен RAG-индекс:
   - `rag_pipeline/output/structural/index.json`
   - или fallback: `app/src/main/assets/rag/structural_index.json`

## Локальный запуск

```bash
python pr_review/pr_reviewer.py \
  --base-ref HEAD~1 \
  --head-ref HEAD \
  --index rag_pipeline/output/structural/index.json \
  --output-file pr_review_output.md
```

Результат будет сохранён в `pr_review_output.md`.
