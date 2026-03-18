from __future__ import annotations

import argparse
import json
import math
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from reranker import apply_relevance_stage

try:
    from dotenv import load_dotenv
except Exception:  # pragma: no cover
    def load_dotenv() -> bool:
        return False


def _l2_normalize(vec: list[float]) -> list[float]:
    norm = math.sqrt(sum(x * x for x in vec))
    if norm == 0:
        return vec
    return [x / norm for x in vec]


@dataclass
class RagHit:
    chunk_id: str
    source: str
    title: str
    section: str
    text: str
    score: float


class RouterAiClient:
    def __init__(
        self,
        *,
        api_key: str,
        base_url: str = "https://routerai.ru/api/v1",
        embedding_model: str = "openai/text-embedding-3-large",
        response_model: str = "openai/gpt-5.2",
        timeout_seconds: int = 120,
        max_retries: int = 2,
    ):
        self.api_key = api_key.strip()
        self.base_url = base_url.rstrip("/")
        self.embedding_model = embedding_model
        self.response_model = response_model
        self.timeout_seconds = max(10, int(timeout_seconds))
        self.max_retries = max(0, int(max_retries))
        if not self.api_key:
            raise RuntimeError("ROUTERAI_API_KEY пустой")

    def embed_query(self, text: str) -> list[float]:
        payload = {
            "model": self.embedding_model,
            "input": [text],
            "encoding_format": "float",
        }
        parsed = self._post_json("/embeddings", payload)
        items = parsed.get("data") if isinstance(parsed, dict) else None
        if not isinstance(items, list) or not items:
            raise RuntimeError(f"Некорректный ответ embeddings: {parsed}")

        emb = items[0].get("embedding") if isinstance(items[0], dict) else None
        if not isinstance(emb, list) or not emb:
            raise RuntimeError(f"Некорректный embedding: {items[0]}")
        return [float(x) for x in emb]

    def generate(self, messages: list[dict[str, str]], temperature: float = 0.2) -> str:
        payload = {
            "model": self.response_model,
            "input": messages,
            "stream": False,
            "temperature": temperature,
        }
        parsed = self._post_json("/responses", payload)
        return self._extract_text(parsed)

    def _post_json(self, endpoint: str, payload: dict[str, Any]) -> dict[str, Any]:
        url = f"{self.base_url}{endpoint}"
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        last_error: Exception | None = None
        for attempt in range(self.max_retries + 1):
            req = Request(
                url=url,
                data=data,
                method="POST",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json",
                },
            )

            try:
                with urlopen(req, timeout=self.timeout_seconds) as resp:
                    body = resp.read().decode("utf-8")
                    parsed = json.loads(body)
                if isinstance(parsed, dict) and parsed.get("error"):
                    raise RuntimeError(f"RouterAI error: {parsed['error']}")
                return parsed
            except HTTPError as e:
                err = e.read().decode("utf-8", errors="ignore")
                last_error = RuntimeError(f"HTTP {e.code} {endpoint}: {err}")
            except URLError as e:
                last_error = RuntimeError(f"Ошибка сети {endpoint}: {e}")
            except TimeoutError as e:
                last_error = RuntimeError(f"Timeout {endpoint}: {e}")
            except Exception as e:
                last_error = e

            if attempt < self.max_retries:
                time.sleep(1.0 + attempt)

        if last_error is None:
            raise RuntimeError(f"Не удалось выполнить запрос {endpoint}")
        raise RuntimeError(str(last_error)) from last_error

    @staticmethod
    def _extract_text(parsed: dict[str, Any]) -> str:
        if not isinstance(parsed, dict):
            return ""

        if isinstance(parsed.get("output_text"), str):
            return parsed.get("output_text", "").strip()

        output = parsed.get("output")
        if isinstance(output, list):
            texts: list[str] = []
            for item in output:
                if not isinstance(item, dict):
                    continue
                content = item.get("content")
                if not isinstance(content, list):
                    continue
                for part in content:
                    if not isinstance(part, dict):
                        continue
                    text = part.get("text")
                    if isinstance(text, str) and text.strip():
                        texts.append(text.strip())
            if texts:
                return "\n".join(texts)

        choices = parsed.get("choices")
        if isinstance(choices, list) and choices:
            msg = choices[0].get("message") if isinstance(choices[0], dict) else None
            content = msg.get("content") if isinstance(msg, dict) else None
            if isinstance(content, str):
                return content.strip()

        return ""


class RagSearcher:
    def __init__(self, index_path: str | Path):
        path = Path(index_path)
        if not path.exists():
            raise RuntimeError(f"Не найден индекс: {path}")
        raw = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(raw, list):
            raise RuntimeError("Ожидался list в index.json")

        prepared = []
        for item in raw:
            if not isinstance(item, dict):
                continue
            emb = item.get("embedding")
            if not isinstance(emb, list) or not emb:
                continue
            vec = _l2_normalize([float(x) for x in emb])
            prepared.append((item, vec))
        self._chunks = prepared

    def top_k(self, query_vec: list[float], top_k: int = 4) -> list[RagHit]:
        q = _l2_normalize(query_vec)
        scored: list[RagHit] = []
        for item, emb in self._chunks:
            n = min(len(q), len(emb))
            score = 0.0
            for i in range(n):
                score += q[i] * emb[i]
            scored.append(
                RagHit(
                    chunk_id=str(item.get("chunk_id", "")),
                    source=str(item.get("source", "")),
                    title=str(item.get("title", "")),
                    section=str(item.get("section", "")),
                    text=str(item.get("text", "")),
                    score=score,
                )
            )

        scored.sort(key=lambda x: x.score, reverse=True)
        return scored[: max(1, top_k)]


def build_rag_system_prompt(question: str, hits: list[RagHit]) -> str:
    if not hits:
        return (
            "=== RAG КОНТЕКСТ ===\n"
            "Релевантные фрагменты не найдены после фильтрации/reranking.\n"
            "Отвечай аккуратно и явно укажи, что в базе нет подходящего контекста.\n"
            f"Вопрос пользователя: {question}\n"
            "=== КОНЕЦ RAG КОНТЕКСТА ==="
        )

    chunks = "\n\n".join(
        (
            f"source: {h.source}\n"
            f"title: {h.title}\n"
            f"section: {h.section}\n"
            f"score: {h.score:.4f}\n"
            "fragment:\n"
            f"{h.text}"
        )
        for h in hits
    )

    return (
        "=== RAG КОНТЕКСТ ===\n"
        "Ниже — релевантные фрагменты базы знаний.\n"
        "Опирайся на них, если они относятся к вопросу.\n"
        "Если контекста недостаточно — явно напиши об этом.\n"
        "По возможности указывай source.\n\n"
        f"Вопрос пользователя: {question}\n\n"
        "Релевантные фрагменты:\n"
        f"{chunks}\n"
        "=== КОНЕЦ RAG КОНТЕКСТА ==="
    )


def rewrite_query_for_retrieval(client: RouterAiClient, question: str) -> str:
    prompt = (
        "Перепиши запрос пользователя в лаконичную форму для retrieval по базе знаний проекта.\n"
        "Требования:\n"
        "1) Сохрани исходный смысл.\n"
        "2) Добавь ключевые технические термины, если они явно следуют из вопроса.\n"
        "3) Убери вводные и разговорные формулировки.\n"
        "4) Верни только одну строку переписанного запроса, без пояснений.\n\n"
        f"Исходный вопрос: {question}"
    )
    messages = [
        {
            "role": "system",
            "content": "Ты модуль query rewriting для retrieval.",
        },
        {"role": "user", "content": prompt},
    ]
    rewritten = client.generate(messages, temperature=0.0).strip()
    if not rewritten:
        return question
    return rewritten


def answer_question(
    *,
    client: RouterAiClient,
    searcher: RagSearcher,
    question: str,
    use_rag: bool,
    top_k: int = 4,
    top_k_before: int | None = None,
    top_k_after: int | None = None,
    rerank: bool = False,
    rerank_mode: str = "threshold",
    rerank_threshold: float = 0.35,
    rewrite_query: bool = False,
) -> tuple[str, list[RagHit]]:
    messages: list[dict[str, str]] = []
    hits: list[RagHit] = []

    if use_rag:
        k_before = max(1, top_k_before if top_k_before is not None else top_k)
        k_after = max(1, top_k_after if top_k_after is not None else top_k)

        retrieval_query = question
        if rewrite_query:
            retrieval_query = rewrite_query_for_retrieval(client, question)

        qvec = client.embed_query(retrieval_query)
        hits_before = searcher.top_k(qvec, top_k=k_before)

        if rerank:
            hits = list(
                apply_relevance_stage(
                    mode=rerank_mode,
                    hits=hits_before,
                    top_k_after=k_after,
                    threshold=rerank_threshold,
                    client=client if rerank_mode == "llm" else None,
                    question=question,
                )
            )
        else:
            hits = hits_before[:k_after]

        rag_system = build_rag_system_prompt(question, hits)
        if rewrite_query and retrieval_query != question:
            rag_system = (
                rag_system
                + "\n\n"
                + f"[technical retrieval query]: {retrieval_query}"
            )
        messages.append({"role": "system", "content": rag_system})

    messages.append({"role": "user", "content": question})
    answer = client.generate(messages)
    return answer, hits


def _resolve_api_key(local_properties_path: str) -> str:
    load_dotenv()

    env = os.getenv("ROUTERAI_API_KEY", "").strip()
    if env:
        return env

    path = Path(local_properties_path)
    if not path.exists():
        return ""

    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == "ROUTERAI_API_KEY":
            return value.strip()
    return ""


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="RAG query: with/without RAG modes")
    p.add_argument("--question", required=True)
    p.add_argument("--use-rag", action="store_true")
    p.add_argument("--top-k", type=int, default=4, help="Legacy: top-K для retrieval и final, если не заданы отдельные")
    p.add_argument("--top-k-before", type=int, default=None, help="Сколько кандидатов взять до фильтра/rerank")
    p.add_argument("--top-k-after", type=int, default=None, help="Сколько кандидатов оставить после фильтра/rerank")
    p.add_argument("--rewrite-query", action="store_true", help="Включить query rewriting перед retrieval")
    p.add_argument("--rerank", action="store_true", help="Включить второй этап релевантности (filter/rerank)")
    p.add_argument("--rerank-mode", choices=["threshold", "llm"], default="threshold")
    p.add_argument("--rerank-threshold", type=float, default=0.35)
    p.add_argument("--index", default="rag_pipeline/output/structural/index.json")
    p.add_argument("--embedding-model", default="openai/text-embedding-3-large")
    p.add_argument("--response-model", default="openai/gpt-5.2")
    p.add_argument("--routerai-base-url", default="https://routerai.ru/api/v1")
    p.add_argument("--local-properties-path", default="local.properties")
    p.add_argument("--http-timeout", type=int, default=120, help="HTTP timeout в секундах для запросов к RouterAI")
    p.add_argument("--max-retries", type=int, default=2, help="Количество retry при сетевых ошибках")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    api_key = _resolve_api_key(args.local_properties_path)
    if not api_key:
        raise RuntimeError("ROUTERAI_API_KEY не найден")

    client = RouterAiClient(
        api_key=api_key,
        base_url=args.routerai_base_url,
        embedding_model=args.embedding_model,
        response_model=args.response_model,
        timeout_seconds=args.http_timeout,
        max_retries=args.max_retries,
    )
    searcher = RagSearcher(args.index)

    answer, hits = answer_question(
        client=client,
        searcher=searcher,
        question=args.question,
        use_rag=args.use_rag,
        top_k=args.top_k,
        top_k_before=args.top_k_before,
        top_k_after=args.top_k_after,
        rerank=args.rerank,
        rerank_mode=args.rerank_mode,
        rerank_threshold=args.rerank_threshold,
        rewrite_query=args.rewrite_query,
    )

    print("=== MODE ===")
    print("RAG" if args.use_rag else "NO_RAG")
    print()
    if args.use_rag:
        print("settings:")
        print(f"  rewrite_query={args.rewrite_query}")
        print(f"  rerank={args.rerank}")
        if args.rerank:
            print(f"  rerank_mode={args.rerank_mode}")
            print(f"  rerank_threshold={args.rerank_threshold}")
        print(f"  top_k_before={args.top_k_before if args.top_k_before is not None else args.top_k}")
        print(f"  top_k_after={args.top_k_after if args.top_k_after is not None else args.top_k}")
        print()
        print("=== HITS ===")
        for i, h in enumerate(hits, 1):
            print(f"{i}. {h.score:.4f} | {h.source} | {h.section}")
        print()
    print("=== ANSWER ===")
    print(answer)


if __name__ == "__main__":
    main()
