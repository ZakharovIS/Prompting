from __future__ import annotations

import re
from typing import Any, Protocol


class ScoredHit(Protocol):
    chunk_id: str
    source: str
    title: str
    section: str
    text: str
    score: float


def _safe_float(value: str, default: float = 0.0) -> float:
    try:
        return float(value)
    except Exception:
        return default


def threshold_filter(hits: list[ScoredHit], *, threshold: float, top_k_after: int) -> list[ScoredHit]:
    filtered = [h for h in hits if float(h.score) >= threshold]
    filtered.sort(key=lambda x: float(x.score), reverse=True)
    return filtered[: max(1, top_k_after)]


def _build_llm_rerank_prompt(question: str, hits: list[ScoredHit]) -> str:
    blocks = []
    for h in hits:
        snippet = (h.text or "").strip().replace("\n", " ")
        snippet = " ".join(snippet.split())
        if len(snippet) > 700:
            snippet = snippet[:700] + "..."
        blocks.append(
            (
                f"ID: {h.chunk_id}\n"
                f"SOURCE: {h.source}\n"
                f"TITLE: {h.title}\n"
                f"SECTION: {h.section}\n"
                f"TEXT: {snippet}"
            )
        )

    joined = "\n\n---\n\n".join(blocks)
    return (
        "Оцени релевантность каждого фрагмента к вопросу пользователя.\n"
        "Верни ТОЛЬКО список строк в формате:\n"
        "<chunk_id>\t<score_0_1>\n"
        "Где 1.0 = максимально релевантно, 0.0 = нерелевантно.\n"
        "Никакого дополнительного текста.\n\n"
        f"Вопрос: {question}\n\n"
        "Кандидаты:\n"
        f"{joined}"
    )


def _parse_llm_scores(raw: str, valid_ids: set[str]) -> dict[str, float]:
    scores: dict[str, float] = {}
    if not raw.strip():
        return scores

    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue

        parts = re.split(r"\t+|\s*\|\s*|\s*:\s*", line, maxsplit=1)
        if len(parts) != 2:
            continue
        chunk_id = parts[0].strip()
        if chunk_id not in valid_ids:
            continue
        score = _safe_float(parts[1].strip(), default=-1.0)
        if score < 0:
            continue
        scores[chunk_id] = min(1.0, max(0.0, score))

    return scores


def llm_rerank(
    *,
    client: Any,
    question: str,
    hits: list[ScoredHit],
    threshold: float,
    top_k_after: int,
) -> list[ScoredHit]:
    if not hits:
        return []

    prompt = _build_llm_rerank_prompt(question, hits)
    messages = [
        {
            "role": "system",
            "content": "Ты модуль reranking для retrieval. Отвечай строго в заданном формате.",
        },
        {"role": "user", "content": prompt},
    ]
    raw = client.generate(messages, temperature=0.0)
    score_map = _parse_llm_scores(raw, {h.chunk_id for h in hits})

    rescored: list[ScoredHit] = []
    for h in hits:
        llm_score = score_map.get(h.chunk_id)
        if llm_score is not None:
            h.score = llm_score
        rescored.append(h)

    rescored.sort(key=lambda x: float(x.score), reverse=True)
    rescored = [h for h in rescored if float(h.score) >= threshold]
    return rescored[: max(1, top_k_after)]


def apply_relevance_stage(
    *,
    mode: str,
    hits: list[ScoredHit],
    top_k_after: int,
    threshold: float,
    client: Any | None = None,
    question: str = "",
) -> list[ScoredHit]:
    if not hits:
        return []

    mode = (mode or "threshold").strip().lower()
    if mode == "llm":
        if client is None:
            raise RuntimeError("Для llm reranker требуется client")
        return llm_rerank(
            client=client,
            question=question,
            hits=hits,
            threshold=threshold,
            top_k_after=top_k_after,
        )

    return threshold_filter(hits, threshold=threshold, top_k_after=top_k_after)
