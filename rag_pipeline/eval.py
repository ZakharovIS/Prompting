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


def evaluate_question(
    *,
    q: dict,
    client: RouterAiClient,
    searcher: RagSearcher,
    top_k: int,
) -> dict:
    question = str(q.get("question", "")).strip()
    expected_keywords = [str(x) for x in q.get("expected_keywords", [])]
    expected_sources = [str(x) for x in q.get("expected_sources", [])]

    no_rag_answer, no_rag_hits = answer_question(
        client=client,
        searcher=searcher,
        question=question,
        use_rag=False,
        top_k=top_k,
    )
    rag_answer, rag_hits = answer_question(
        client=client,
        searcher=searcher,
        question=question,
        use_rag=True,
        top_k=top_k,
    )

    no_rag_score, no_rag_kw = keyword_match(no_rag_answer, expected_keywords)
    rag_score, rag_kw = keyword_match(rag_answer, expected_keywords)

    rag_hit_sources = [h.source for h in rag_hits]
    used_expected_sources = [s for s in expected_sources if s in rag_hit_sources]

    return {
        "id": q.get("id"),
        "question": question,
        "expectation": q.get("expectation"),
        "expected_sources": expected_sources,
        "comparison": {
            "without_rag": asdict(
                EvalModeResult(
                    mode="without_rag",
                    answer=no_rag_answer,
                    hits=[asdict(h) for h in no_rag_hits],
                    keyword_score=no_rag_score,
                    keyword_hits=no_rag_kw,
                )
            ),
            "with_rag": asdict(
                EvalModeResult(
                    mode="with_rag",
                    answer=rag_answer,
                    hits=[asdict(h) for h in rag_hits],
                    keyword_score=rag_score,
                    keyword_hits=rag_kw,
                )
            ),
        },
        "source_coverage": {
            "expected": expected_sources,
            "rag_topk_sources": rag_hit_sources,
            "matched_expected_sources": used_expected_sources,
            "coverage": round(
                (len(used_expected_sources) / len(expected_sources)) if expected_sources else 1.0,
                3,
            ),
        },
        "delta_keyword_score": round(rag_score - no_rag_score, 3),
    }


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Evaluate RAG vs non-RAG on control questions")
    p.add_argument("--questions", default="rag_pipeline/control_questions.json")
    p.add_argument("--index", default="rag_pipeline/output/structural/index.json")
    p.add_argument("--out", default="rag_pipeline/output/rag_vs_no_rag_eval.json")
    p.add_argument("--top-k", type=int, default=4)
    p.add_argument("--embedding-model", default="openai/text-embedding-3-large")
    p.add_argument("--response-model", default="openai/gpt-5.2")
    p.add_argument("--routerai-base-url", default="https://routerai.ru/api/v1")
    p.add_argument("--local-properties-path", default="local.properties")
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
            )
        )

    avg_no_rag = round(
        sum(r["comparison"]["without_rag"]["keyword_score"] for r in results) / max(1, len(results)),
        3,
    )
    avg_rag = round(
        sum(r["comparison"]["with_rag"]["keyword_score"] for r in results) / max(1, len(results)),
        3,
    )
    avg_delta = round(avg_rag - avg_no_rag, 3)

    report = {
        "summary": {
            "questions_count": len(results),
            "avg_keyword_score_without_rag": avg_no_rag,
            "avg_keyword_score_with_rag": avg_rag,
            "avg_delta": avg_delta,
        },
        "results": results,
    }

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("=== RAG VS NO-RAG EVAL ===")
    print(f"Вопросов: {len(results)}")
    print(f"Avg keyword score without RAG: {avg_no_rag}")
    print(f"Avg keyword score with RAG:    {avg_rag}")
    print(f"Delta:                         {avg_delta}")
    print(f"Отчёт: {out_path}")


if __name__ == "__main__":
    main()
