from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


PROJECT_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = PROJECT_ROOT / "file_agent_output"

TEXT_EXTENSIONS = {
    ".kt",
    ".kts",
    ".py",
    ".md",
    ".txt",
    ".json",
    ".xml",
    ".yaml",
    ".yml",
    ".toml",
    ".properties",
    ".bat",
}

EXCLUDED_DIR_NAMES = {
    ".git",
    ".gradle",
    "build",
    "out",
    "venv",
    "__pycache__",
    "file_agent_output",
}


def _is_generated_or_index_file(path: Path) -> bool:
    p = path.as_posix()
    return (
        "rag_pipeline/output/" in p
        or "app/src/main/assets/rag/" in p
        or p.endswith("index.json")
        or p.endswith("index.sqlite")
    )


@dataclass
class MatchLine:
    line_no: int
    line: str
    before: list[str]
    after: list[str]


def _ensure_output_dir() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


def _safe_name(raw: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_\-.]+", "_", raw.strip())
    return cleaned[:80] or "result"


def _iter_text_files(root: Path) -> Iterable[Path]:
    for path in root.rglob("*"):
        if not path.is_file():
            continue

        if any(part in EXCLUDED_DIR_NAMES for part in path.parts):
            continue

        if path.suffix.lower() in TEXT_EXTENSIONS:
            yield path


def _read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return None


def scan_usage(
    pattern: str,
    use_regex: bool = False,
    context_lines: int = 2,
    files_only: bool = False,
    exclude_generated: bool = True,
    save_report: bool = False,
) -> tuple[str, Path | None]:
    compiled = re.compile(pattern if use_regex else re.escape(pattern), re.IGNORECASE)
    result: dict[str, list[MatchLine]] = {}

    for file_path in _iter_text_files(PROJECT_ROOT):
        if exclude_generated and _is_generated_or_index_file(file_path):
            continue

        text = _read_text(file_path)
        if text is None:
            continue

        lines = text.splitlines()
        file_hits: list[MatchLine] = []
        for idx, line in enumerate(lines):
            if compiled.search(line):
                start = max(0, idx - context_lines)
                end = min(len(lines), idx + context_lines + 1)
                file_hits.append(
                    MatchLine(
                        line_no=idx + 1,
                        line=line,
                        before=lines[start:idx],
                        after=lines[idx + 1 : end],
                    )
                )
        if file_hits:
            result[str(file_path.relative_to(PROJECT_ROOT)).replace("\\", "/")] = file_hits

    output_path: Path | None = None
    if save_report:
        _ensure_output_dir()
        output_path = OUTPUT_DIR / f"scan_{_safe_name(pattern)}.md"

    total_files = len(result)
    total_matches = sum(len(v) for v in result.values())

    chunks: list[str] = []
    chunks.append(f"# Scan report: `{pattern}`")
    chunks.append("")
    chunks.append(f"- Matched files: **{total_files}**")
    chunks.append(f"- Total matches: **{total_matches}**")
    chunks.append("")

    if not result:
        chunks.append("Совпадений не найдено.")
    elif files_only:
        chunks.append("## Files")
        chunks.append("")
        for file_name in sorted(result.keys()):
            chunks.append(f"- {file_name}")
    else:
        for file_name in sorted(result.keys()):
            chunks.append(f"## {file_name}")
            chunks.append("")
            for hit in result[file_name]:
                chunks.append(f"- Line {hit.line_no}: `{hit.line.strip()}`")
                if hit.before or hit.after:
                    chunks.append("```text")
                    for b in hit.before:
                        chunks.append(f"  {b}")
                    chunks.append(f"> {hit.line}")
                    for a in hit.after:
                        chunks.append(f"  {a}")
                    chunks.append("```")
            chunks.append("")

    report_text = "\n".join(chunks)
    if output_path is not None:
        output_path.write_text(report_text, encoding="utf-8")
    return report_text, output_path


def _collect_documented_tools(readme_text: str) -> set[str]:
    from_json = set(re.findall(r'"tool"\s*:\s*"([a-z0-9_\-]+)"', readme_text))

    from_numbered_list: set[str] = set()
    for line in readme_text.splitlines():
        m = re.match(r"\s*\d+\.\s+`([a-z][a-z0-9_\-]+)`", line)
        if m:
            from_numbered_list.add(m.group(1))

    return from_json | from_numbered_list


def _collect_implemented_tools() -> set[str]:
    implemented: set[str] = set()
    mcp_dir = PROJECT_ROOT / "app" / "src" / "main" / "java" / "ru" / "zis" / "prompting" / "mcp"
    if not mcp_dir.exists():
        return implemented

    for file_path in mcp_dir.glob("*.kt"):
        text = _read_text(file_path)
        if not text:
            continue
        for match in re.finditer(r'\bname\s*=\s*"([a-z0-9_\-]+)"', text):
            implemented.add(match.group(1))
    return implemented


def check_docs_sync(save_report: bool = False) -> tuple[str, Path | None]:
    root_readme = PROJECT_ROOT / "README.md"
    project_readme = _read_text(root_readme) or ""
    documented = _collect_documented_tools(project_readme)
    implemented = _collect_implemented_tools()

    only_docs = sorted(documented - implemented)
    only_code = sorted(implemented - documented)
    in_both = sorted(documented & implemented)

    output_path: Path | None = None
    if save_report:
        _ensure_output_dir()
        output_path = OUTPUT_DIR / "doc_audit.md"

    lines: list[str] = []
    lines.append("# Documentation audit report")
    lines.append("")
    lines.append("Проверка согласованности README и MCP-инструментов в коде.")
    lines.append("")
    lines.append(f"- Инструментов в README: **{len(documented)}**")
    lines.append(f"- Инструментов в коде: **{len(implemented)}**")
    lines.append(f"- Совпали: **{len(in_both)}**")
    lines.append("")

    lines.append("## В README есть, в коде не найдено")
    lines.append("")
    if only_docs:
        lines.extend(f"- `{x}`" for x in only_docs)
    else:
        lines.append("- Нет")
    lines.append("")

    lines.append("## В коде есть, в README не найдено")
    lines.append("")
    if only_code:
        lines.extend(f"- `{x}`" for x in only_code)
    else:
        lines.append("- Нет")
    lines.append("")

    lines.append("## Совпадающие инструменты")
    lines.append("")
    if in_both:
        lines.extend(f"- `{x}`" for x in in_both)
    else:
        lines.append("- Нет")

    report_text = "\n".join(lines)
    if output_path is not None:
        output_path.write_text(report_text, encoding="utf-8")
    return report_text, output_path


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


def generate_changelog(limit: int = 30) -> Path:
    raw = _run_git(["log", f"--max-count={max(1, limit)}", "--pretty=format:%h\t%s"])
    commits = []
    for line in raw.splitlines():
        if not line.strip() or "\t" not in line:
            continue
        sha, subj = line.split("\t", 1)
        commits.append((sha.strip(), subj.strip()))

    groups = {
        "Features": [],
        "Fixes": [],
        "Refactor/Chore": [],
        "Other": [],
    }

    for sha, subject in commits:
        low = subject.lower()
        row = f"- {subject} (`{sha}`)"
        if any(k in low for k in ["feat", "feature", "add"]):
            groups["Features"].append(row)
        elif any(k in low for k in ["fix", "bug", "error"]):
            groups["Fixes"].append(row)
        elif any(k in low for k in ["refactor", "cleanup", "chore", "docs", "test"]):
            groups["Refactor/Chore"].append(row)
        else:
            groups["Other"].append(row)

    changelog_path = PROJECT_ROOT / "CHANGELOG.md"
    lines = [
        "# Changelog",
        "",
        "Автосгенерировано file-agent по истории git.",
        "",
        f"Источник: последние {len(commits)} коммитов.",
        "",
    ]
    for title, rows in groups.items():
        lines.append(f"## {title}")
        lines.append("")
        if rows:
            lines.extend(rows)
        else:
            lines.append("- Нет записей")
        lines.append("")

    changelog_path.write_text("\n".join(lines), encoding="utf-8")
    return changelog_path


def write_summary(operation: str, output_path: Path, extra: dict[str, str] | None = None) -> Path:
    _ensure_output_dir()
    summary_path = OUTPUT_DIR / "last_run.json"
    payload = {
        "operation": operation,
        "output": str(output_path.relative_to(PROJECT_ROOT)).replace("\\", "/"),
    }
    if extra:
        payload["meta"] = extra
    summary_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary_path


def _extract_scan_pattern(goal: str) -> str | None:
    # 1) В бэктиках: `weather_pipeline`
    m = re.search(r"`([a-zA-Z0-9_\-.]+)`", goal)
    if m:
        return m.group(1)

    # 2) В кавычках: "weather_pipeline" или 'weather_pipeline'
    m = re.search(r'"([a-zA-Z0-9_\-.]+)"', goal)
    if m:
        return m.group(1)
    m = re.search(r"'([a-zA-Z0-9_\-.]+)'", goal)
    if m:
        return m.group(1)

    # 3) После ключевых слов
    m = re.search(
        r"(?:компонент|api|инструмент|tool|endpoint)\s+([a-zA-Z0-9_\-.]+)",
        goal,
        flags=re.IGNORECASE,
    )
    if m:
        return m.group(1)

    return None


def _extract_limit(goal: str, default: int = 30) -> int:
    m = re.search(r"(\d+)\s*(?:коммит|коммитов|commits?)", goal, flags=re.IGNORECASE)
    if not m:
        return default
    try:
        return max(1, int(m.group(1)))
    except ValueError:
        return default


def run_goal(goal: str, save_report: bool = False) -> tuple[str, Path | None]:
    low = goal.lower()
    produced: list[tuple[str, str, Path | None]] = []

    wants_scan = any(x in low for x in ["где используется", "найди", "используется", "usage", "find"])
    wants_docs = any(x in low for x in ["док", "readme", "согласован", "инвариант", "правил"])
    wants_changelog = any(x in low for x in ["changelog", "чейнджлог", "список изменений", "релиз", "release"])

    if wants_scan:
        pattern = _extract_scan_pattern(goal)
        if pattern:
            scan_text, scan_path = scan_usage(pattern, save_report=save_report)
            produced.append((f"scan:{pattern}", scan_text, scan_path))

    if wants_docs:
        docs_text, docs_path = check_docs_sync(save_report=save_report)
        produced.append(("check-docs", docs_text, docs_path))

    if wants_changelog:
        limit = _extract_limit(goal)
        ch_path = generate_changelog(limit=limit)
        produced.append((f"gen-changelog:{limit}", f"Сгенерирован файл: {ch_path.name}", ch_path))

    # Если цель не распознана — безопасный дефолт: аудит документации
    if not produced:
        docs_text, docs_path = check_docs_sync(save_report=save_report)
        produced.append(("check-docs", docs_text, docs_path))

    lines: list[str] = [
        "# Goal execution report",
        "",
        f"Цель: {goal}",
        "",
        "## Выполненные операции",
        "",
    ]
    for name, text, path in produced:
        if path is not None:
            rel = str(path.relative_to(PROJECT_ROOT)).replace("\\", "/")
            lines.append(f"- `{name}` -> `{rel}`")
        else:
            lines.append(f"- `{name}`")
        brief = [x for x in text.splitlines()[:4] if x.strip()]
        for b in brief:
            lines.append(f"  - {b}")

    goal_text = "\n".join(lines)

    goal_report: Path | None = None
    if save_report:
        _ensure_output_dir()
        goal_report = OUTPUT_DIR / "goal_report.md"
        goal_report.write_text(goal_text, encoding="utf-8")

    return goal_text, goal_report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="File-focused assistant for project automation")
    sub = parser.add_subparsers(dest="command", required=True)

    p_scan = sub.add_parser("scan", help="Найти все вхождения паттерна по проекту")
    p_scan.add_argument("pattern", help="Компонент/API для поиска")
    p_scan.add_argument("--regex", action="store_true", help="Использовать pattern как regex")
    p_scan.add_argument("--context", type=int, default=2, help="Количество строк контекста")
    p_scan.add_argument("--save-report", action="store_true", help="Сохранить markdown-отчёт в file_agent_output")

    p_docs = sub.add_parser("check-docs", help="Сверить README и инструменты в коде")
    p_docs.add_argument("--save-report", action="store_true", help="Сохранить markdown-отчёт в file_agent_output")

    p_ch = sub.add_parser("gen-changelog", help="Сгенерировать CHANGELOG.md из git")
    p_ch.add_argument("--limit", type=int, default=30, help="Сколько последних коммитов учитывать")
    p_ch.add_argument("--save-report", action="store_true", help="Сохранить summary последнего запуска в file_agent_output/last_run.json")

    p_goal = sub.add_parser("goal", help="Поставить цель на естественном языке")
    p_goal.add_argument("goal", nargs="+", help="Текст цели, например: найти где используется `weather_pipeline` и обновить changelog")
    p_goal.add_argument("--save-report", action="store_true", help="Сохранить markdown-отчёт выполнения цели")

    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if args.command == "scan":
        text, out = scan_usage(
            args.pattern,
            use_regex=args.regex,
            context_lines=max(0, args.context),
            save_report=bool(args.save_report),
        )
        print(text)
        if out is not None:
            summary = write_summary(
                "scan",
                out,
                extra={"pattern": args.pattern, "regex": str(bool(args.regex))},
            )
            print(f"\nSaved report: {out}")
            print(f"Run summary: {summary}")
        return

    if args.command == "check-docs":
        text, out = check_docs_sync(save_report=bool(args.save_report))
        print(text)
        if out is not None:
            summary = write_summary("check-docs", out)
            print(f"\nSaved report: {out}")
            print(f"Run summary: {summary}")
        return

    if args.command == "gen-changelog":
        out = generate_changelog(limit=args.limit)
        print(f"Generated: {out}")
        if bool(args.save_report):
            summary = write_summary("gen-changelog", out, extra={"limit": str(max(1, args.limit))})
            print(f"Run summary: {summary}")
        return

    if args.command == "goal":
        goal_text = " ".join(args.goal).strip()
        text, out = run_goal(goal_text, save_report=bool(args.save_report))
        print(text)
        if out is not None:
            summary = write_summary("goal", out, extra={"goal": goal_text})
            print(f"\nSaved report: {out}")
            print(f"Run summary: {summary}")
        return

    raise RuntimeError("Unknown command")


if __name__ == "__main__":
    main()
