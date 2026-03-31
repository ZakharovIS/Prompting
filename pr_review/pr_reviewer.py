from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
RAG_PIPELINE_DIR = PROJECT_ROOT / "rag_pipeline"
if str(RAG_PIPELINE_DIR) not in sys.path:
    sys.path.insert(0, str(RAG_PIPELINE_DIR))

from rag_query import RagHit, RagSearcher, RouterAiClient, _resolve_api_key  # noqa: E402


def _run_git(args: list[str]) -> str:
    proc = subprocess.run(
        ["git", *args],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return proc.stdout.strip()


def _resolve_index_path(path_arg: str) -> Path:
    candidate = Path(path_arg)
    if candidate.exists():
        return candidate.resolve()

    candidate_from_root = PROJECT_ROOT / path_arg
    if candidate_from_root.exists():
        return candidate_from_root.resolve()

    fallbacks = [
        PROJECT_ROOT / "rag_pipeline" / "output" / "structural" / "index.json",
        PROJECT_ROOT / "app" / "src" / "main" / "assets" / "rag" / "structural_index.json",
    ]
    for fb in fallbacks:
        if fb.exists():
            return fb.resolve()

    raise RuntimeError(
        "Не найден RAG-индекс. Ожидался один из путей: "
        f"{path_arg}, rag_pipeline/output/structural/index.json, "
        "app/src/main/assets/rag/structural_index.json"
    )


def get_changed_files(base_ref: str, head_ref: str) -> list[str]:
    out = _run_git(["diff", "--name-only", f"{base_ref}...{head_ref}"])
    return [x.strip() for x in out.splitlines() if x.strip()]


def get_diff(base_ref: str, head_ref: str) -> str:
    return _run_git(["diff", "--unified=3", f"{base_ref}...{head_ref}"])


def _trim_text(text: str, max_chars: int) -> str:
    if max_chars <= 0 or len(text) <= max_chars:
        return text
    return text[:max_chars] + "\n\n[TRUNCATED]"


def _extract_signal_lines(diff: str, limit: int = 120) -> str:
    lines: list[str] = []
    for raw in diff.splitlines():
        if raw.startswith("+++") or raw.startswith("---"):
            continue
        if raw.startswith("+") or raw.startswith("-"):
            line = raw[1:].strip()
            if not line:
                continue
            lines.append(line)
        if len(lines) >= limit:
            break
    return "\n".join(lines)


def _build_retrieval_queries(changed_files: list[str], diff: str) -> list[str]:
    queries: list[str] = []
    files_part = ", ".join(changed_files[:20]) if changed_files else "(нет файлов)"
    queries.append(
        "Код-ревью PR. Найди потенциальные баги, архитектурные проблемы и рекомендации "
        f"для изменённых файлов: {files_part}"
    )

    diff_signals = _extract_signal_lines(diff, limit=120)
    if diff_signals:
        queries.append(
            "Ключевые изменения в diff:\n"
            f"{_trim_text(diff_signals, 3000)}"
        )

    for f in changed_files[:8]:
        queries.append(f"Архитектура и риски изменений файла {f}")

    return queries


def retrieve_context(
    *,
    client: RouterAiClient,
    searcher: RagSearcher,
    changed_files: list[str],
    diff: str,
    top_k_per_query: int,
    max_hits: int,
) -> list[RagHit]:
    queries = _build_retrieval_queries(changed_files, diff)
    dedup: dict[str, RagHit] = {}

    for q in queries:
        vec = client.embed_query(q)
        hits = searcher.top_k(vec, top_k=top_k_per_query)
        for h in hits:
            prev = dedup.get(h.chunk_id)
            if prev is None or h.score > prev.score:
                dedup[h.chunk_id] = h

    by_source = {f.replace("\\", "/") for f in changed_files}
    ordered = sorted(dedup.values(), key=lambda x: x.score, reverse=True)

    priority: list[RagHit] = []
    secondary: list[RagHit] = []
    for h in ordered:
        source = (h.source or "").replace("\\", "/")
        if source in by_source:
            priority.append(h)
        else:
            secondary.append(h)

    merged = priority + secondary
    return merged[: max(1, max_hits)]


def build_review_prompt(changed_files: list[str], diff: str, hits: list[RagHit]) -> list[dict[str, str]]:
    changed_block = "\n".join(f"- {f}" for f in changed_files) if changed_files else "- нет изменений"
    hits_block = "\n\n".join(
        (
            f"source: {h.source}\n"
            f"section: {h.section}\n"
            f"score: {h.score:.4f}\n"
            "fragment:\n"
            f"{_trim_text(h.text, 1200)}"
        )
        for h in hits
    )

    system_prompt = (
        "Ты senior code reviewer. Твоя задача — сделать ревью PR, опираясь на diff и RAG-контекст "
        "(документация + код проекта).\n"
        "Нельзя придумывать факты вне предоставленного контекста.\n"
        "Если уверенности нет — укажи это явно.\n\n"
        "Верни ответ СТРОГО в формате:\n"
        "## 🐛 Потенциальные баги\n"
        "- ...\n\n"
        "## 🏗️ Архитектурные проблемы\n"
        "- ...\n\n"
        "## 💡 Рекомендации\n"
        "- ...\n"
    )

    user_prompt = (
        "Изменённые файлы:\n"
        f"{changed_block}\n\n"
        "Diff PR:\n"
        f"{_trim_text(diff, 12000)}\n\n"
        "RAG контекст:\n"
        f"{hits_block if hits_block else '(контекст не найден)'}"
    )

    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]


def build_markdown_result(changed_files: list[str], review_text: str) -> str:
    files_md = "\n".join(f"- `{f}`" for f in changed_files) if changed_files else "- Нет изменений"
    return (
        "## 🤖 AI Code Review\n\n"
        "> Автоматическое ревью сгенерировано на основе diff + RAG (код и документация проекта).\n\n"
        "### 📁 Изменённые файлы\n"
        f"{files_md}\n\n"
        "---\n\n"
        f"{review_text.strip()}\n"
    )


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="PR reviewer with RAG context")
    p.add_argument("--base-ref", required=True, help="Базовый git ref (например base SHA)")
    p.add_argument("--head-ref", required=True, help="Head git ref (например head SHA)")
    p.add_argument("--index", default="rag_pipeline/output/structural/index.json")
    p.add_argument("--output-file", default="pr_review_output.md")
    p.add_argument("--local-properties-path", default="local.properties")
    p.add_argument("--routerai-base-url", default="https://routerai.ru/api/v1")
    p.add_argument("--embedding-model", default="openai/text-embedding-3-large")
    p.add_argument("--response-model", default="openai/gpt-5.2")
    p.add_argument("--http-timeout", type=int, default=120)
    p.add_argument("--max-retries", type=int, default=2)
    p.add_argument("--top-k-per-query", type=int, default=4)
    p.add_argument("--max-hits", type=int, default=12)
    return p.parse_args()


def main() -> None:
    args = parse_args()

    changed_files = get_changed_files(args.base_ref, args.head_ref)
    diff = get_diff(args.base_ref, args.head_ref)
    if not diff.strip():
        result = (
            "## 🤖 AI Code Review\n\n"
            "Изменений между base и head не найдено."
        )
        Path(args.output_file).write_text(result, encoding="utf-8")
        print("PR review saved:", args.output_file)
        return

    api_key = os.getenv("ROUTERAI_API_KEY", "").strip() or _resolve_api_key(args.local_properties_path)
    if not api_key:
        raise RuntimeError("ROUTERAI_API_KEY не найден (env или local.properties)")

    index_path = _resolve_index_path(args.index)
    client = RouterAiClient(
        api_key=api_key,
        base_url=args.routerai_base_url,
        embedding_model=args.embedding_model,
        response_model=args.response_model,
        timeout_seconds=args.http_timeout,
        max_retries=args.max_retries,
    )
    searcher = RagSearcher(str(index_path))

    hits = retrieve_context(
        client=client,
        searcher=searcher,
        changed_files=changed_files,
        diff=diff,
        top_k_per_query=max(1, args.top_k_per_query),
        max_hits=max(1, args.max_hits),
    )

    messages = build_review_prompt(changed_files=changed_files, diff=diff, hits=hits)
    review_text = client.generate(messages, temperature=0.1)

    if not re.search(r"##\s*🐛\s*Потенциальные баги", review_text, flags=re.IGNORECASE):
        review_text = (
            "## 🐛 Потенциальные баги\n"
            "- Не удалось надёжно извлечь структурированный ответ модели.\n\n"
            "## 🏗️ Архитектурные проблемы\n"
            "- См. исходный ответ модели ниже.\n\n"
            "## 💡 Рекомендации\n"
            "- Перезапустить ревью с тем же diff.\n\n"
            "---\n"
            f"{review_text.strip()}"
        )

    result = build_markdown_result(changed_files=changed_files, review_text=review_text)
    Path(args.output_file).write_text(result, encoding="utf-8")
    print("PR review saved:", args.output_file)


if __name__ == "__main__":
    main()
