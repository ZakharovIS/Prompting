from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path

from rag_query import RagSearcher, RouterAiClient, _resolve_api_key, answer_question


@dataclass
class EvalModeResult:
    mode: str
    answer: str
    hits: list[dict]
    keyword_score: float
    keyword_hits: list[str]
    error: str | None = None


def normalize_text(text: str) -> str:
    return (text or "").lower().strip()


def keyword_match(answer: str, expected_keywords: list[str]) -> tuple[float, list[str]]:
    ans = normalize_text(answer)
    if not expected_keywords:
        return 1.0, []

    hits = []
    for kw in expected_keywords:
        if normalize_text(kw) in ans:
            hits.append(kw)
    return round(len(hits) / len(expected_keywords), 3), hits


def source_coverage(hits: list[dict], expected_sources: list[str]) -> dict:
    rag_hit_sources = [str(h.get("source", "")) for h in hits]
    used_expected_sources = [s for s in expected_sources if s in rag_hit_sources]
    return {
        "expected": expected_sources,
        "rag_topk_sources": rag_hit_sources,
        "matched_expected_sources": used_expected_sources,
        "coverage": round(
            (len(used_expected_sources) / len(expected_sources)) if expected_sources else 1.0,
            3,
        ),
    }


def run_mode(
    *,
    mode_name: str,
    client: RouterAiClient,
    searcher: RagSearcher,
    question: str,
    expected_keywords: list[str],
    use_rag: bool,
    top_k: int,
    top_k_before: int | None = None,
    top_k_after: int | None = None,
    rerank: bool = False,
    rerank_mode: str = "threshold",
    rerank_threshold: float = 0.35,
    rewrite_query: bool = False,
) -> EvalModeResult:
    try:
        answer, hits = answer_question(
            client=client,
            searcher=searcher,
            question=question,
            use_rag=use_rag,
            top_k=top_k,
            top_k_before=top_k_before,
            top_k_after=top_k_after,
            rerank=rerank,
            rerank_mode=rerank_mode,
            rerank_threshold=rerank_threshold,
            rewrite_query=rewrite_query,
        )
        score, kws = keyword_match(answer, expected_keywords)
        return EvalModeResult(
            mode=mode_name,
            answer=answer,
            hits=[asdict(h) for h in hits],
            keyword_score=score,
            keyword_hits=kws,
        )
    except Exception as e:
        return EvalModeResult(
            mode=mode_name,
            answer="",
            hits=[],
            keyword_score=0.0,
            keyword_hits=[],
            error=str(e),
        )


def evaluate_question(
    *,
    q: dict,
    client: RouterAiClient,
    searcher: RagSearcher,
    top_k: int,
    top_k_before: int,
    top_k_after: int,
    threshold: float,
) -> dict:
    question = str(q.get("question", "")).strip()
    expected_keywords = [str(x) for x in q.get("expected_keywords", [])]
    expected_sources = [str(x) for x in q.get("expected_sources", [])]

    without_rag = run_mode(
        mode_name="without_rag",
        client=client,
        searcher=searcher,
        question=question,
        expected_keywords=expected_keywords,
        use_rag=False,
        top_k=top_k,
    )

    with_rag = run_mode(
        mode_name="with_rag",
        client=client,
        searcher=searcher,
        question=question,
        expected_keywords=expected_keywords,
        use_rag=True,
        top_k=top_k,
    )

    with_rag_filtered = run_mode(
        mode_name="with_rag_filtered",
        client=client,
        searcher=searcher,
        question=question,
        expected_keywords=expected_keywords,
        use_rag=True,
        top_k=top_k,
        top_k_before=top_k_before,
        top_k_after=top_k_after,
        rerank=True,
        rerank_mode="threshold",
        rerank_threshold=threshold,
        rewrite_query=False,
    )

    with_rag_reranked = run_mode(
        mode_name="with_rag_reranked",
        client=client,
        searcher=searcher,
        question=question,
        expected_keywords=expected_keywords,
        use_rag=True,
        top_k=top_k,
        top_k_before=top_k_before,
        top_k_after=top_k_after,
        rerank=True,
        rerank_mode="llm",
        rerank_threshold=threshold,
        rewrite_query=True,
    )

    return {
        "id": q.get("id"),
        "question": question,
        "expectation": q.get("expectation"),
        "expected_sources": expected_sources,
        "comparison": {
            "without_rag": asdict(without_rag),
            "with_rag": asdict(with_rag),
            "with_rag_filtered": asdict(with_rag_filtered),
            "with_rag_reranked": asdict(with_rag_reranked),
        },
        "source_coverage": {
            "with_rag": source_coverage(with_rag.hits, expected_sources),
            "with_rag_filtered": source_coverage(with_rag_filtered.hits, expected_sources),
            "with_rag_reranked": source_coverage(with_rag_reranked.hits, expected_sources),
        },
        "delta_keyword_score": {
            "with_rag_vs_without_rag": round(with_rag.keyword_score - without_rag.keyword_score, 3),
            "with_rag_filtered_vs_with_rag": round(with_rag_filtered.keyword_score - with_rag.keyword_score, 3),
            "with_rag_reranked_vs_with_rag": round(with_rag_reranked.keyword_score - with_rag.keyword_score, 3),
        },
    }


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Evaluate RAG vs non-RAG on control questions")
    p.add_argument("--questions", default="rag_pipeline/control_questions.json")
    p.add_argument("--index", default="rag_pipeline/output/structural/index.json")
    p.add_argument("--out", default="rag_pipeline/output/rag_vs_no_rag_eval.json")
    p.add_argument("--top-k", type=int, default=4)
    p.add_argument("--top-k-before", type=int, default=8)
    p.add_argument("--top-k-after", type=int, default=4)
    p.add_argument("--rerank-threshold", type=float, default=0.35)
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

    questions_path = Path(args.questions)
    questions = json.loads(questions_path.read_text(encoding="utf-8"))
    if not isinstance(questions, list) or len(questions) < 10:
        raise RuntimeError("Ожидается JSON список минимум из 10 контрольных вопросов")

    client = RouterAiClient(
        api_key=api_key,
        base_url=args.routerai_base_url,
        embedding_model=args.embedding_model,
        response_model=args.response_model,
        timeout_seconds=args.http_timeout,
        max_retries=args.max_retries,
    )
    searcher = RagSearcher(args.index)

    results = []
    for q in questions:
        if not isinstance(q, dict):
            continue
        results.append(
            evaluate_question(
                q=q,
                client=client,
                searcher=searcher,
                top_k=args.top_k,
                top_k_before=args.top_k_before,
                top_k_after=args.top_k_after,
                threshold=args.rerank_threshold,
            )
        )

    avg_without_rag = round(
        sum(r["comparison"]["without_rag"]["keyword_score"] for r in results) / max(1, len(results)),
        3,
    )
    avg_with_rag = round(
        sum(r["comparison"]["with_rag"]["keyword_score"] for r in results) / max(1, len(results)),
        3,
    )
    avg_with_rag_filtered = round(
        sum(r["comparison"]["with_rag_filtered"]["keyword_score"] for r in results) / max(1, len(results)),
        3,
    )
    avg_with_rag_reranked = round(
        sum(r["comparison"]["with_rag_reranked"]["keyword_score"] for r in results) / max(1, len(results)),
        3,
    )

    avg_delta_rag_vs_without = round(avg_with_rag - avg_without_rag, 3)
    avg_delta_filtered_vs_rag = round(avg_with_rag_filtered - avg_with_rag, 3)
    avg_delta_reranked_vs_rag = round(avg_with_rag_reranked - avg_with_rag, 3)

    mode_error_counts = {
        "without_rag": sum(1 for r in results if r["comparison"]["without_rag"].get("error")),
        "with_rag": sum(1 for r in results if r["comparison"]["with_rag"].get("error")),
        "with_rag_filtered": sum(1 for r in results if r["comparison"]["with_rag_filtered"].get("error")),
        "with_rag_reranked": sum(1 for r in results if r["comparison"]["with_rag_reranked"].get("error")),
    }

    report = {
        "summary": {
            "questions_count": len(results),
            "settings": {
                "top_k": args.top_k,
                "top_k_before": args.top_k_before,
                "top_k_after": args.top_k_after,
                "rerank_threshold": args.rerank_threshold,
            },
            "avg_keyword_score_without_rag": avg_without_rag,
            "avg_keyword_score_with_rag": avg_with_rag,
            "avg_keyword_score_with_rag_filtered": avg_with_rag_filtered,
            "avg_keyword_score_with_rag_reranked": avg_with_rag_reranked,
            "avg_delta_with_rag_vs_without_rag": avg_delta_rag_vs_without,
            "avg_delta_with_rag_filtered_vs_with_rag": avg_delta_filtered_vs_rag,
            "avg_delta_with_rag_reranked_vs_with_rag": avg_delta_reranked_vs_rag,
            "mode_error_counts": mode_error_counts,
        },
        "results": results,
    }

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("=== RAG VS NO-RAG EVAL ===")
    print(f"Вопросов: {len(results)}")
    print(f"Avg keyword score without RAG:         {avg_without_rag}")
    print(f"Avg keyword score with RAG:            {avg_with_rag}")
    print(f"Avg keyword score with RAG+filter:     {avg_with_rag_filtered}")
    print(f"Avg keyword score with RAG+rerank+RW:  {avg_with_rag_reranked}")
    print(f"Delta with_rag - without_rag:          {avg_delta_rag_vs_without}")
    print(f"Delta with_rag_filtered - with_rag:    {avg_delta_filtered_vs_rag}")
    print(f"Delta with_rag_reranked - with_rag:    {avg_delta_reranked_vs_rag}")
    print(f"Ошибки по режимам:                     {mode_error_counts}")
    print(f"Отчёт: {out_path}")


if __name__ == "__main__":
    main()
