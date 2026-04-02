from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any
from urllib import request

from file_agent import check_docs_sync, generate_changelog, scan_usage


LLM_CHAT_URL = os.getenv("FILE_AGENT_LLM_URL", "http://127.0.0.1:8080/chat")
LLM_MODEL = os.getenv("FILE_AGENT_MODEL", "llama3.1:8b")
LLM_MAX_CONTEXT = int(os.getenv("FILE_AGENT_MAX_CONTEXT", "2048"))


SYSTEM_ROUTER_PROMPT = """Ты - CLI ассистент для проекта. Твоя задача: понять цель пользователя и выбрать действие.

Доступные действия:
1) scan_usage(pattern) - найти, где используется компонент/API по проекту.
2) check_docs_sync() - проверить согласованность README и инструментов в коде.
3) generate_changelog(limit) - обновить файл CHANGELOG.md по git log.
4) answer - если действие не требуется.

Верни СТРОГО JSON (без markdown), формат:
{
  "action": "scan|check_docs|gen_changelog|answer",
  "pattern": "строка или пусто",
  "limit": 20,
  "files_only": false,
  "reply": "кратко что собираешься сделать"
}

Правила:
- Если пользователь просит changelog/список изменений/релиз -> gen_changelog.
- Если просит проверить документацию/README/инварианты -> check_docs.
- Если просит найти где используется компонент/API -> scan (pattern постарайся извлечь).
- Если явной операции нет -> answer.
"""


TOOL_ROUTER_PROMPT = """Ты — роутер инструментов. Выбери ОДИН шаг.

Доступные инструменты:
1) scan_usage
   args:
   - pattern: string (что искать)
   - files_only: boolean (только список файлов)

2) check_docs_sync
   args: {}

3) generate_changelog
   args:
   - limit: integer >= 1

4) access_info
   args: {}

5) project_overview
   args: {}

6) answer
   args:
   - message: string

Верни строго JSON (без markdown):
{
  "tool": "scan_usage|check_docs_sync|generate_changelog|access_info|project_overview|answer",
  "args": { ... },
  "reason": "кратко"
}

Правила:
- Если пользователь просит найти использование чего-либо -> scan_usage.
- Если просит список файлов -> scan_usage с files_only=true.
- Если просит changelog/список изменений -> generate_changelog.
- Если просит проверить README/документацию/инварианты -> check_docs_sync.
- Если спрашивает есть ли доступ к файлам -> access_info.
- Если просит рассказать/проанализировать проект -> project_overview.
- Если неясно, верни answer с вежливым уточняющим message.
"""


def _fix_windows_encoding() -> None:
    if os.name == "nt":
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def _post_chat(messages: list[dict[str, str]]) -> str:
    payload = {
        "model": LLM_MODEL,
        "max_context": LLM_MAX_CONTEXT,
        "messages": messages,
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = request.Request(
        LLM_CHAT_URL,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with request.urlopen(req, timeout=180) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
    parsed = json.loads(raw)
    return str(parsed.get("response", "")).strip()


def _try_parse_json(text: str) -> dict[str, Any] | None:
    text = text.strip()
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            return obj
    except Exception:
        pass

    m = re.search(r"\{[\s\S]*\}", text)
    if not m:
        return None
    try:
        obj = json.loads(m.group(0))
        return obj if isinstance(obj, dict) else None
    except Exception:
        return None


def _json_tool_router(user_text: str) -> dict[str, Any] | None:
    try:
        raw = _post_chat(
            [
                {"role": "system", "content": TOOL_ROUTER_PROMPT},
                {"role": "user", "content": user_text},
            ]
        )
    except Exception:
        return None

    parsed = _try_parse_json(raw)
    if not parsed:
        return None

    tool = str(parsed.get("tool", "")).strip()
    args = parsed.get("args", {})
    if not isinstance(args, dict):
        args = {}

    allowed = {
        "scan_usage",
        "check_docs_sync",
        "generate_changelog",
        "access_info",
        "project_overview",
        "answer",
    }
    if tool not in allowed:
        return None

    # Нормализация аргументов
    if tool == "scan_usage":
        pattern = str(args.get("pattern", "") or "").strip()
        files_only = bool(args.get("files_only", False))
        return {"tool": tool, "args": {"pattern": pattern, "files_only": files_only}}

    if tool == "generate_changelog":
        try:
            limit = max(1, int(args.get("limit", 20) or 20))
        except Exception:
            limit = 20
        return {"tool": tool, "args": {"limit": limit}}

    if tool == "answer":
        return {"tool": tool, "args": {"message": str(args.get("message", ""))}}

    return {"tool": tool, "args": {}}


def _rule_router(user_text: str) -> dict[str, Any]:
    low = user_text.lower()

    def _extract_limit_from_text(text: str, default: int = 20) -> int:
        # Поддерживаем разные формы: "10 коммитов", "10 коммитам", "10 commits"
        m = re.search(r"(\d+)\s*(?:коммит\w*|commit\w*)", text, flags=re.IGNORECASE)
        if m:
            try:
                return max(1, int(m.group(1)))
            except ValueError:
                return default

        # Фолбэк: если в запросе есть число, используем его
        n = re.search(r"\b(\d{1,4})\b", text)
        if n:
            try:
                return max(1, int(n.group(1)))
            except ValueError:
                return default
        return default

    if any(x in low for x in ["доступ к файлам", "есть доступ к файлам", "умеешь читать файлы"]):
        return {
            "action": "access_info",
            "limit": 20,
            "pattern": "",
            "reply": "Да, у меня есть доступ к файлам проекта через локальные операции чтения/поиска/изменения файлов.",
        }

    if (
        any(x in low for x in ["расскажи про проект", "проанализируй файлы проекта", "анализируй файлы проекта", "project overview"])
        or ("проект" in low and any(v in low for v in ["расскажи", "опиши", "обзор", "проанализируй", "анализ"]))
    ):
        return {
            "action": "project_overview",
            "limit": 20,
            "pattern": "",
            "reply": "Соберу краткий обзор проекта по файлам и README.",
        }

    if any(x in low for x in ["changelog", "список изменений", "релиз", "release"]):
        limit = _extract_limit_from_text(low, default=20)
        return {"action": "gen_changelog", "limit": max(1, limit), "pattern": "", "reply": "Сгенерирую changelog."}

    if any(x in low for x in ["readme", "док", "документац", "инвариант", "правил"]):
        return {"action": "check_docs", "limit": 20, "pattern": "", "reply": "Проверю согласованность документации."}

    if any(x in low for x in ["где используется", "найди", "используется", "usage", "find"]):
        files_only = any(x in low for x in ["список файлов", "только файлы", "list files", "files only"])
        m = re.search(r"`([a-zA-Z0-9_\-.]+)`", user_text)
        if m:
            pattern = m.group(1)
        else:
            m2 = re.search(r"(?:где используется|найди|используется|usage|find)\s+([a-zA-Z0-9_\-.]+)", low)
            pattern = m2.group(1) if m2 else "weather_pipeline"
        return {
            "action": "scan",
            "limit": 20,
            "pattern": pattern,
            "files_only": files_only,
            "reply": f"Поищу использование `{pattern}`.",
        }

    return {"action": "answer", "limit": 20, "pattern": "", "reply": "Сформулируйте цель: поиск использования, проверка README или генерация changelog."}


def _route_with_llm(user_text: str) -> dict[str, Any]:
    # 1) Сначала пытаемся tool-calling через LLM
    llm_tool = _json_tool_router(user_text)
    if llm_tool is not None:
        return _tool_plan_to_action(llm_tool)

    # 2) Fallback: детерминированные правила
    by_rule = _rule_router(user_text)
    if by_rule.get("action") != "answer":
        return by_rule

    try:
        raw = _post_chat(
            [
                {"role": "system", "content": SYSTEM_ROUTER_PROMPT},
                {"role": "user", "content": user_text},
            ]
        )
        parsed = _try_parse_json(raw)
        if not parsed:
            return by_rule
        return {
            "action": str(parsed.get("action", "answer")),
            "pattern": str(parsed.get("pattern", "") or ""),
            "limit": int(parsed.get("limit", 20) or 20),
            "files_only": bool(parsed.get("files_only", False)),
            "reply": str(parsed.get("reply", "")),
        }
    except Exception:
        return by_rule


def _tool_plan_to_action(tool_plan: dict[str, Any]) -> dict[str, Any]:
    tool = tool_plan.get("tool")
    args = tool_plan.get("args", {})

    if tool == "scan_usage":
        return {
            "action": "scan",
            "pattern": str(args.get("pattern", "") or ""),
            "files_only": bool(args.get("files_only", False)),
            "limit": 20,
            "reply": "",
        }
    if tool == "check_docs_sync":
        return {"action": "check_docs", "pattern": "", "limit": 20, "reply": ""}
    if tool == "generate_changelog":
        return {
            "action": "gen_changelog",
            "pattern": "",
            "limit": max(1, int(args.get("limit", 20) or 20)),
            "reply": "",
        }
    if tool == "access_info":
        return {"action": "access_info", "pattern": "", "limit": 20, "reply": ""}
    if tool == "project_overview":
        return {"action": "project_overview", "pattern": "", "limit": 20, "reply": ""}

    return {
        "action": "answer",
        "pattern": "",
        "limit": 20,
        "reply": str(args.get("message", "Опишите цель, и я выполню её по проекту.")),
    }


def _run_action(plan: dict[str, Any]) -> str:
    action = plan.get("action", "answer")

    if action == "access_info":
        return "Да. У меня есть доступ к файлам проекта в этом репозитории: я могу читать, искать по нескольким файлам и изменять файлы по запросу."

    if action == "project_overview":
        return _build_project_overview()

    if action == "scan":
        pattern = (plan.get("pattern") or "").strip()
        files_only = bool(plan.get("files_only", False))
        if not pattern:
            return "Не удалось извлечь pattern для поиска. Укажите, что искать, например: `weather_pipeline`."
        text, _ = scan_usage(pattern, files_only=files_only, exclude_generated=True, save_report=False)
        return text

    if action == "check_docs":
        text, _ = check_docs_sync(save_report=False)
        return text

    if action == "gen_changelog":
        limit = max(1, int(plan.get("limit", 20)))
        path = generate_changelog(limit=limit)
        return f"Готово. Обновил файл: {path}"

    return str(plan.get("reply", "Опишите цель, и я выполню её по проекту."))


def _read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except Exception:
        return ""


def _build_project_overview() -> str:
    root = Path(__file__).resolve().parent.parent

    modules = [
        ("Android app", root / "app"),
        ("RAG pipeline", root / "rag_pipeline"),
        ("Local LLM service", root / "local_llm_service"),
        ("Support service", root / "support_service"),
        ("PR review", root / "pr_review"),
        ("File agent", root / "file_agent"),
    ]

    root_readme = _read_text(root / "README.md")
    top_level = [p.name for p in root.iterdir() if p.is_dir()]

    lines: list[str] = []
    lines.append("# Краткий обзор проекта")
    lines.append("")
    lines.append("Я проанализировал файлы проекта и вижу, что это мульти-модульный репозиторий:")
    lines.append("")

    for title, path in modules:
        if not path.exists():
            continue
        file_count = sum(1 for _ in path.rglob("*") if _.is_file())
        readme_exists = (path / "README.md").exists()
        suffix = "(есть README)" if readme_exists else ""
        lines.append(f"- **{title}**: `{path.name}` — файлов: {file_count} {suffix}".rstrip())

    lines.append("")
    lines.append("## Что есть по функционалу")
    lines.append("")
    lines.append("- Android-приложение с агентом/чатом и MCP-инструментами погоды.")
    lines.append("- Python-сервисы: локальный LLM proxy, support-service, RAG pipeline, PR-reviewer.")
    lines.append("- CLI file-agent для операций с файлами проекта (поиск, аудит docs, changelog).")

    if root_readme.strip():
        first_lines = [x.strip() for x in root_readme.splitlines() if x.strip()][:4]
        if first_lines:
            lines.append("")
            lines.append("## README (верхнеуровневый, начало)")
            lines.append("")
            for x in first_lines:
                lines.append(f"- {x}")

    lines.append("")
    lines.append("## Top-level директории")
    lines.append("")
    for name in sorted(top_level):
        lines.append(f"- {name}")

    return "\n".join(lines)


def run_once(user_text: str) -> str:
    plan = _route_with_llm(user_text)
    result = _run_action(plan)
    # Возвращаем фактический результат инструмента напрямую,
    # чтобы ответ всегда соответствовал реальным операциям с файлами.
    return result


def repl() -> None:
    print("File Agent Chat CLI")
    print("Пиши задачу обычным языком. Команды: /exit, /quit")
    print(f"LLM endpoint: {LLM_CHAT_URL}")
    while True:
        try:
            user_text = input("\nВы> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nПока!")
            return

        if not user_text:
            continue
        if user_text.lower() in {"/exit", "/quit"}:
            print("Пока!")
            return

        answer = run_once(user_text)
        print(f"\nАссистент> {answer}")


if __name__ == "__main__":
    _fix_windows_encoding()
    if len(sys.argv) > 1:
        print(run_once(" ".join(sys.argv[1:])))
    else:
        repl()
