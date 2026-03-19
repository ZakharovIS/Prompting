from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path

from rag_query import RagSearcher, RouterAiClient, _resolve_api_key, answer_question


SECTION_ANSWER = "## Ответ"
SECTION_SOURCES = "## Источники"
SECTION_QUOTES = "## Цитаты"
UNKNOWN_PHRASE = (
    "Не знаю. В базе знаний нет достаточно релевантной информации по этому вопросу. "
    "Уточните запрос."
)


@dataclass
class CitationCheckResult:
    id: str
    question: str
    answer: str
    hits: list[dict]
    has_sources_section: bool
    has_quotes_section: bool
    sources_non_empty: bool
    quotes_non_empty: bool
    is_unknown_mode: bool
    meaning_matches_quotes: bool
    notes: list[str]
    error: str | None = None


def _extract_section(text: str, title: str) -> str:
    if not text:
        return ""
    pattern = rf"{re.escape(title)}\s*(.*?)(?=\n##\s|\Z)"
    m = re.search(pattern, text, flags=re.DOTALL)
    return (m.group(1) if m else "").strip()


def _tokenize(text: str) -> set[str]:
    return {
        t.lower()
        for t in re.findall(r"[\wа-яА-ЯёЁ\-]{3,}", text or "", flags=re.UNICODE)
    }


def _meaning_match(answer_section: str, quotes_section: str, min_overlap: float = 0.18) -> tuple[bool, float]:
    answer_tokens = _tokenize(answer_section)
    quote_tokens = _tokenize(quotes_section)
    if not answer_tokens:
        return False, 0.0
    if not quote_tokens:
        return False, 0.0

    overlap = answer_tokens.intersection(quote_tokens)
    ratio = len(overlap) / max(1, len(answer_tokens))
    return ratio >= min_overlap, ratio


def _is_non_empty_block(block: str, empty_markers: tuple[str, ...]) -> bool:
    b = (block or "").strip()
    if not b:
        return False
    lower = b.lower()
    return all(marker.lower() not in lower for marker in empty_markers)


def evaluate_single(
    *,
    q: dict,
    client: RouterAiClient,
    searcher: RagSearcher,
    top_k: int,
    top_k_before: int,
    top_k_after: int,
    rerank_threshold: float,
    low_relevance_threshold: float,
) -> CitationCheckResult:
    qid = str(q.get("id", ""))
    question = str(q.get("question", "")).strip()
    notes: list[str] = []

    try:
        answer, hits = answer_question(
            client=client,
            searcher=searcher,
            question=question,
            use_rag=True,
            top_k=top_k,
            top_k_before=top_k_before,
            top_k_after=top_k_after,
            rerank=True,
            rerank_mode="threshold",
            rerank_threshold=rerank_threshold,
            low_relevance_threshold=low_relevance_threshold,
            rewrite_query=False,
        )

        answer_section = _extract_section(answer, SECTION_ANSWER)
        sources_section = _extract_section(answer, SECTION_SOURCES)
        quotes_section = _extract_section(answer, SECTION_QUOTES)

        has_sources = SECTION_SOURCES in answer
        has_quotes = SECTION_QUOTES in answer
        is_unknown = UNKNOWN_PHRASE.lower() in answer.lower()

        sources_non_empty = _is_non_empty_block(
            sources_section,
            empty_markers=("нет релевантных источников",),
        )
        quotes_non_empty = _is_non_empty_block(
            quotes_section,
            empty_markers=("нет релевантных цитат",),
        )

        if is_unknown:
            meaning_ok = True
            notes.append("Unknown-mode: смысловая проверка по цитатам пропущена.")
            if not has_sources:
                notes.append("В unknown-mode отсутствует секция '## Источники'.")
            if not has_quotes:
                notes.append("В unknown-mode отсутствует секция '## Цитаты'.")
        else:
            meaning_ok, overlap = _meaning_match(answer_section, quotes_section)
            notes.append(f"Token overlap answer/quotes: {overlap:.3f}")
            if not has_sources:
                notes.append("Отсутствует секция '## Источники'.")
            if not has_quotes:
                notes.append("Отсутствует секция '## Цитаты'.")
            if has_sources and not sources_non_empty:
                notes.append("Секция '## Источники' есть, но выглядит пустой.")
            if has_quotes and not quotes_non_empty:
                notes.append("Секция '## Цитаты' есть, но выглядит пустой.")

        return CitationCheckResult(
            id=qid,
            question=question,
            answer=answer,
            hits=hits and [asdict(h) for h in hits] or [],
            has_sources_section=has_sources,
            has_quotes_section=has_quotes,
            sources_non_empty=sources_non_empty,
            quotes_non_empty=quotes_non_empty,
            is_unknown_mode=is_unknown,
            meaning_matches_quotes=meaning_ok,
            notes=notes,
        )
    except Exception as e:  # pragma: no cover
        return CitationCheckResult(
            id=qid,
            question=question,
            answer="",
            hits=[],
            has_sources_section=False,
            has_quotes_section=False,
            sources_non_empty=False,
            quotes_non_empty=False,
            is_unknown_mode=False,
            meaning_matches_quotes=False,
            notes=["Ошибка выполнения сценария"],
            error=str(e),
        )


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Проверка обязательных источников/цитат в RAG-ответах")
    p.add_argument("--questions", default="rag_pipeline/control_questions.json")
    p.add_argument("--index", default="rag_pipeline/output/structural/index.json")
    p.add_argument("--out", default="rag_pipeline/output/citations_eval.json")
    p.add_argument("--top-k", type=int, default=4)
    p.add_argument("--top-k-before", type=int, default=8)
    p.add_argument("--top-k-after", type=int, default=4)
    p.add_argument("--rerank-threshold", type=float, default=0.35)
    p.add_argument("--low-relevance-threshold", type=float, default=0.25)
    p.add_argument("--embedding-model", default="openai/text-embedding-3-large")
    p.add_argument("--response-model", default="openai/gpt-5.2")
    p.add_argument("--routerai-base-url", default="https://routerai.ru/api/v1")
    p.add_argument("--local-properties-path", default="local.properties")
    p.add_argument("--http-timeout", type=int, default=120)
    p.add_argument("--max-retries", type=int, default=2)
    return p.parse_args()


def main() -> None:
    args = parse_args()

    api_key = _resolve_api_key(args.local_properties_path)
    if not api_key:
        raise RuntimeError("ROUTERAI_API_KEY не найден")

    questions = json.loads(Path(args.questions).read_text(encoding="utf-8"))
    if not isinstance(questions, list) or len(questions) < 10:
        raise RuntimeError("Ожидается JSON список минимум из 10 контрольных вопросов")

    test_questions = [q for q in questions if isinstance(q, dict)][:10]

    client = RouterAiClient(
        api_key=api_key,
        base_url=args.routerai_base_url,
        embedding_model=args.embedding_model,
        response_model=args.response_model,
        timeout_seconds=args.http_timeout,
        max_retries=args.max_retries,
    )
    searcher = RagSearcher(args.index)

    results = [
        asdict(
            evaluate_single(
                q=q,
                client=client,
                searcher=searcher,
                top_k=args.top_k,
                top_k_before=args.top_k_before,
                top_k_after=args.top_k_after,
                rerank_threshold=args.rerank_threshold,
                low_relevance_threshold=args.low_relevance_threshold,
            )
        )
        for q in test_questions
    ]

    ok_sources = sum(1 for r in results if r["has_sources_section"])
    ok_quotes = sum(1 for r in results if r["has_quotes_section"])
    ok_meaning = sum(1 for r in results if r["meaning_matches_quotes"])
    unknown_count = sum(1 for r in results if r["is_unknown_mode"])
    errors_count = sum(1 for r in results if r.get("error"))

    report = {
        "summary": {
            "questions_count": len(results),
            "checked_first_n": 10,
            "has_sources_in_each": {
                "ok": ok_sources == len(results),
                "count": ok_sources,
                "total": len(results),
            },
            "has_quotes_in_each": {
                "ok": ok_quotes == len(results),
                "count": ok_quotes,
                "total": len(results),
            },
            "meaning_matches_quotes": {
                "ok_count": ok_meaning,
                "total": len(results),
            },
            "unknown_mode_count": unknown_count,
            "errors_count": errors_count,
            "settings": {
                "top_k": args.top_k,
                "top_k_before": args.top_k_before,
                "top_k_after": args.top_k_after,
                "rerank_threshold": args.rerank_threshold,
                "low_relevance_threshold": args.low_relevance_threshold,
            },
        },
        "results": results,
    }

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("=== RAG CITATIONS EVAL ===")
    print(f"Вопросов (первые 10): {len(results)}")
    print(f"Источники в каждом ответе: {ok_sources}/{len(results)}")
    print(f"Цитаты в каждом ответе:    {ok_quotes}/{len(results)}")
    print(f"Смысл ~ цитаты:            {ok_meaning}/{len(results)}")
    print(f"Unknown-mode ответов:      {unknown_count}")
    print(f"Ошибки:                    {errors_count}")
    print(f"Отчёт: {out_path}")


if __name__ == "__main__":
    main()
