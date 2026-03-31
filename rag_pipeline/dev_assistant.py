from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
from pathlib import Path
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from rag_query import RagSearcher, RouterAiClient, _resolve_api_key, answer_question


DEFAULT_INDEX = "rag_pipeline/output/structural/index.json"


@dataclass
class GitContext:
    branch: str
    last_commit: str


def resolve_local_properties_path(path_arg: str) -> str:
    arg_path = Path(path_arg)
    if arg_path.exists():
        return str(arg_path)

    script_root = Path(__file__).resolve().parent.parent
    candidate = script_root / "local.properties"
    if candidate.exists():
        return str(candidate)

    return path_arg


def resolve_index_path(path_arg: str) -> str:
    arg_path = Path(path_arg)
    if arg_path.exists():
        return str(arg_path)

    project_root = Path(__file__).resolve().parent.parent
    candidate_from_root = project_root / path_arg
    if candidate_from_root.exists():
        return str(candidate_from_root)

    candidate_default = project_root / "rag_pipeline" / "output" / "structural" / "index.json"
    if candidate_default.exists():
        return str(candidate_default)

    candidate_local = project_root / "output" / "structural" / "index.json"
    if candidate_local.exists():
        return str(candidate_local)

    return path_arg


@dataclass
class LexicalHit:
    chunk_id: str
    source: str
    title: str
    section: str
    text: str
    score: float


class LexicalSearcher:
    def __init__(self, index_path: str):
        path = Path(index_path)
        if not path.exists():
            raise RuntimeError(f"Не найден индекс: {path}")
        raw = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(raw, list):
            raise RuntimeError("Ожидался list в index.json")
        self._chunks = [x for x in raw if isinstance(x, dict)]

    @staticmethod
    def _tokens(text: str) -> set[str]:
        return set(re.findall(r"[A-Za-zА-Яа-я0-9_\-]{3,}", text.lower()))

    def top_k(self, query: str, top_k: int = 4) -> list[LexicalHit]:
        q = self._tokens(query)
        if not q:
            return []

        hits: list[LexicalHit] = []
        for item in self._chunks:
            text = str(item.get("text", ""))
            t = self._tokens(text)
            if not t:
                continue
            common = len(q & t)
            if common == 0:
                continue
            score = common / max(1, len(q))
            hits.append(
                LexicalHit(
                    chunk_id=str(item.get("chunk_id", "")),
                    source=str(item.get("source", "")),
                    title=str(item.get("title", "")),
                    section=str(item.get("section", "")),
                    text=text,
                    score=score,
                )
            )
        hits.sort(key=lambda x: x.score, reverse=True)
        return hits[: max(1, top_k)]


class LocalLlmClient:
    def __init__(self, *, chat_url: str, model: str, timeout_seconds: int = 120):
        self.chat_url = chat_url
        self.model = model
        self.timeout_seconds = max(10, int(timeout_seconds))

    def generate(self, messages: list[dict[str, str]], temperature: float = 0.2) -> str:
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "max_context": 4096,
        }
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = Request(
            url=self.chat_url,
            data=data,
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        try:
            with urlopen(req, timeout=self.timeout_seconds) as resp:
                body = resp.read().decode("utf-8")
                parsed = json.loads(body)
            text = parsed.get("response") if isinstance(parsed, dict) else None
            return str(text or "").strip()
        except HTTPError as e:
            err = e.read().decode("utf-8", errors="ignore")
            raise RuntimeError(f"HTTP {e.code} local LLM: {err}") from e
        except URLError as e:
            raise RuntimeError(f"Ошибка сети local LLM: {e}") from e
        except Exception as e:
            raise RuntimeError(f"Ошибка local LLM: {e}") from e


def _run_git(args: list[str]) -> str:
    try:
        proc = subprocess.run(
            ["git", *args],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        return proc.stdout.strip()
    except Exception as e:
        return f"(git error: {e})"


def get_git_context() -> GitContext:
    branch = _run_git(["rev-parse", "--abbrev-ref", "HEAD"]) or "unknown"
    last_commit = _run_git(["log", "-1", "--pretty=format:%h %s"]) or "unknown"
    return GitContext(branch=branch, last_commit=last_commit)


def cmd_branch() -> None:
    print(f"Текущая ветка: {_run_git(['rev-parse', '--abbrev-ref', 'HEAD'])}")


def cmd_files(limit: int = 20) -> None:
    out = _run_git(["ls-files"])
    if out.startswith("(git error"):
        print(out)
        return
    files = out.splitlines()
    if not files:
        print("Файлы не найдены")
        return
    print(f"Файлы проекта (первые {min(limit, len(files))} из {len(files)}):")
    for f in files[:limit]:
        print(f"- {f}")


def cmd_diff() -> None:
    out = _run_git(["diff", "--stat", "HEAD~1"])
    if not out:
        out = "Нет изменений относительно HEAD~1"
    print("Diff (git diff --stat HEAD~1):")
    print(out)


def build_help_system_prompt(ctx: GitContext) -> str:
    return (
        "Ты ассистент разработчика по проекту Prompting. "
        "Отвечай по структуре и логике проекта, строго опираясь на RAG-контекст и git-контекст. "
        "Если данных недостаточно — честно скажи, что в найденных источниках нет точной информации.\n\n"
        f"Git branch: {ctx.branch}\n"
        f"Last commit: {ctx.last_commit}\n"
    )


def answer_help(
    *,
    question: str,
    client: RouterAiClient | None,
    searcher: RagSearcher | None,
    local_client: LocalLlmClient | None,
    lexical_searcher: LexicalSearcher | None,
    top_k: int,
) -> None:
    ctx = get_git_context()

    if client is not None and searcher is not None:
        scoped_question = (
            f"[PROJECT_HELP_MODE]\n"
            f"{build_help_system_prompt(ctx)}\n"
            f"Вопрос пользователя: {question}"
        )

        try:
            answer, hits = answer_question(
                client=client,
                searcher=searcher,
                question=scoped_question,
                use_rag=True,
                top_k=top_k,
                top_k_before=max(top_k * 2, 6),
                top_k_after=top_k,
                rerank=True,
                rerank_mode="threshold",
                rerank_threshold=0.35,
                low_relevance_threshold=0.20,
                rewrite_query=True,
            )
        except Exception as e:
            print(f"Ошибка /help через RouterAI: {e}")
            print(
                "Проверьте: 1) валидность ROUTERAI_API_KEY, "
                "2) доступ в интернет, 3) что индекс существует."
            )
            return

        max_score = max((h.score for h in hits), default=0.0)
        print(f"Retrieval: {len(hits)} чанков, max score={max_score:.3f}")
        print("Ответ:")
        print(answer)
        return

    if local_client is not None and lexical_searcher is not None:
        hits = lexical_searcher.top_k(question, top_k=top_k)
        max_score = max((h.score for h in hits), default=0.0)
        context = "\n\n".join(
            (
                f"source: {h.source}\n"
                f"section: {h.section}\n"
                f"chunk_id: {h.chunk_id}\n"
                f"fragment:\n{h.text}"
            )
            for h in hits
        )
        system_prompt = (
            f"{build_help_system_prompt(ctx)}\n"
            "Ниже релевантные фрагменты RAG-индекса. Отвечай только по ним.\n\n"
            f"{context if context else 'Нет релевантных фрагментов.'}"
        )
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": question},
        ]
        try:
            answer = local_client.generate(messages, temperature=0.2)
        except Exception as e:
            print(f"Ошибка /help через local LLM: {e}")
            return
        print(f"Retrieval: {len(hits)} чанков, max score={max_score:.3f} (lexical)")
        print("Ответ:")
        print(answer)
        return

    print(
        "Для /help нужен либо ROUTERAI_API_KEY (backend=routerai), "
        "либо --llm-backend local и поднятый local_llm_service."
    )


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Dev Assistant (RAG + git MCP)")
    p.add_argument("--index", default=DEFAULT_INDEX, help="Путь к RAG index.json")
    p.add_argument("--embedding-model", default="openai/text-embedding-3-large")
    p.add_argument("--response-model", default="openai/gpt-5.2")
    p.add_argument("--routerai-base-url", default="https://routerai.ru/api/v1")
    p.add_argument("--local-properties-path", default="local.properties")
    p.add_argument("--http-timeout", type=int, default=120)
    p.add_argument("--max-retries", type=int, default=2)
    p.add_argument("--top-k", type=int, default=4)
    p.add_argument("--llm-backend", choices=["routerai", "local"], default="routerai")
    p.add_argument("--local-llm-url", default="http://127.0.0.1:8080/chat")
    p.add_argument("--local-llm-model", default="llama3.1:8b")
    return p.parse_args()


def print_banner() -> None:
    ctx = get_git_context()
    print("Dev Assistant (RAG + MCP)")
    print(f"Ветка: {ctx.branch}")
    print(f"Коммит: {ctx.last_commit}")
    print("Команды: /help <вопрос>, /branch, /files, /diff, /exit")
    print("Любой текст без команды считается /help-вопросом.")


def main() -> None:
    args = parse_args()
    resolved_index = resolve_index_path(args.index)
    client: RouterAiClient | None = None
    searcher: RagSearcher | None = None
    local_client: LocalLlmClient | None = None
    lexical_searcher: LexicalSearcher | None = None

    if args.llm_backend == "routerai":
        resolved_props = resolve_local_properties_path(args.local_properties_path)
        api_key = _resolve_api_key(resolved_props)
        if api_key:
            client = RouterAiClient(
                api_key=api_key,
                base_url=args.routerai_base_url,
                embedding_model=args.embedding_model,
                response_model=args.response_model,
                timeout_seconds=args.http_timeout,
                max_retries=args.max_retries,
            )
            searcher = RagSearcher(resolved_index)
        else:
            print(
                "ROUTERAI_API_KEY не найден. Команды /branch, /files, /diff доступны, "
                "а /help временно отключен."
            )
            print(f"Проверен путь local.properties: {resolved_props}")
    else:
        local_client = LocalLlmClient(
            chat_url=args.local_llm_url,
            model=args.local_llm_model,
            timeout_seconds=args.http_timeout,
        )
        lexical_searcher = LexicalSearcher(resolved_index)

    print_banner()
    while True:
        try:
            raw = input("\n> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nДо свидания!")
            break

        if not raw:
            continue
        if raw in {"/exit", "exit", "quit", "/quit"}:
            print("До свидания!")
            break
        if raw == "/branch":
            cmd_branch()
            continue
        if raw == "/files":
            cmd_files()
            continue
        if raw == "/diff":
            cmd_diff()
            continue

        question = raw
        if raw.startswith("/help"):
            parts = shlex.split(raw)
            question = " ".join(parts[1:]).strip() if len(parts) > 1 else ""
            if not question:
                print("Использование: /help <вопрос о проекте>")
                continue

        answer_help(
            question=question,
            client=client,
            searcher=searcher,
            local_client=local_client,
            lexical_searcher=lexical_searcher,
            top_k=args.top_k,
        )


if __name__ == "__main__":
    main()
