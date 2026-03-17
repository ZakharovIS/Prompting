from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable

from chunkers import Chunk, fixed_size_chunking, structural_chunking
from compare import calculate_stats, render_comparison_console
from embeddings import EmbeddingGenerator
from index import save_json_index, save_sqlite_index


SUPPORTED_EXTENSIONS = {".md", ".txt", ".kt", ".java", ".py"}


def _safe_chunk_prefix(strategy: str, source: str) -> str:
    safe = []
    for ch in source.lower():
        if ch.isalnum():
            safe.append(ch)
        else:
            safe.append("_")
    normalized = "".join(safe).strip("_")
    if not normalized:
        normalized = "doc"
    return f"{strategy}_{normalized}"


def collect_documents(paths: Iterable[Path]) -> list[Path]:
    files: list[Path] = []
    for p in paths:
        if not p.exists():
            continue
        if p.is_file() and p.suffix.lower() in SUPPORTED_EXTENSIONS:
            files.append(p)
        elif p.is_dir():
            for child in p.rglob("*"):
                if child.is_file() and child.suffix.lower() in SUPPORTED_EXTENSIONS:
                    files.append(child)
    # deterministic order
    return sorted(set(files), key=lambda x: str(x).lower())


def infer_title(text: str, path: Path) -> str:
    if path.suffix.lower() == ".md":
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("#"):
                return line.lstrip("#").strip() or path.stem
    return path.stem


def estimate_pages(total_chars: int, chars_per_page: int = 1800) -> float:
    if chars_per_page <= 0:
        return 0.0
    return round(total_chars / chars_per_page, 2)


def build_chunks(
    files: list[Path],
    root: Path,
    fixed_chunk_size: int,
    structural_max_chars: int,
    structural_overlap: int,
) -> tuple[list[Chunk], list[Chunk], dict]:
    fixed_chunks: list[Chunk] = []
    structural_chunks: list[Chunk] = []
    total_chars = 0
    manifest = []

    for file_path in files:
        text = file_path.read_text(encoding="utf-8", errors="ignore")
        if not text.strip():
            continue

        source = str(file_path.relative_to(root)).replace("\\", "/")
        title = infer_title(text, file_path)
        total_chars += len(text)
        manifest.append({"source": source, "title": title, "chars": len(text)})

        fixed = fixed_size_chunking(
            doc_text=text,
            source=source,
            title=title,
            chunk_size=fixed_chunk_size,
            overlap=0,
            chunk_prefix=_safe_chunk_prefix("fixed", source),
        )
        structural = structural_chunking(
            doc_text=text,
            source=source,
            title=title,
            max_section_chars=structural_max_chars,
            overlap=structural_overlap,
            chunk_prefix=_safe_chunk_prefix("struct", source),
        )

        fixed_chunks.extend(fixed)
        structural_chunks.extend(structural)

    summary = {
        "documents_count": len(manifest),
        "total_chars": total_chars,
        "estimated_pages": estimate_pages(total_chars),
        "documents": manifest,
    }
    return fixed_chunks, structural_chunks, summary


def run_pipeline(args: argparse.Namespace) -> None:
    root = Path(args.project_root).resolve()
    input_paths = [root / p for p in args.inputs]
    files = collect_documents(input_paths)
    if not files:
        raise RuntimeError("Не найдено документов для индексации. Проверь --inputs и расширения файлов.")

    fixed_chunks, structural_chunks, corpus_summary = build_chunks(
        files=files,
        root=root,
        fixed_chunk_size=args.fixed_chunk_size,
        structural_max_chars=args.structural_max_chars,
        structural_overlap=args.structural_overlap,
    )

    embedder = EmbeddingGenerator(
        model=args.embedding_model,
        mock=args.mock_embeddings,
        base_url=args.routerai_base_url,
        local_properties_path=str((root / args.local_properties_path).resolve()),
    )

    fixed_vectors = embedder.embed_texts([c.text for c in fixed_chunks])
    structural_vectors = embedder.embed_texts([c.text for c in structural_chunks])

    out_dir = (root / args.out_dir).resolve()
    fixed_dir = out_dir / "fixed_size"
    structural_dir = out_dir / "structural"

    save_json_index(fixed_dir / "index.json", fixed_chunks, fixed_vectors)
    save_sqlite_index(fixed_dir / "index.sqlite", fixed_chunks, fixed_vectors)

    save_json_index(structural_dir / "index.json", structural_chunks, structural_vectors)
    save_sqlite_index(structural_dir / "index.sqlite", structural_chunks, structural_vectors)

    fixed_stats = calculate_stats("fixed_size", fixed_chunks)
    structural_stats = calculate_stats("structural", structural_chunks)

    comparison_text = render_comparison_console(fixed_stats, structural_stats)
    print(comparison_text)

    report = {
        "embedding_model": args.embedding_model,
        "mock_embeddings": args.mock_embeddings,
        "corpus": corpus_summary,
        "stats": {
            "fixed_size": fixed_stats.to_dict(),
            "structural": structural_stats.to_dict(),
        },
        "output": {
            "fixed_json": str((fixed_dir / "index.json").relative_to(root)).replace("\\", "/"),
            "fixed_sqlite": str((fixed_dir / "index.sqlite").relative_to(root)).replace("\\", "/"),
            "structural_json": str((structural_dir / "index.json").relative_to(root)).replace("\\", "/"),
            "structural_sqlite": str((structural_dir / "index.sqlite").relative_to(root)).replace("\\", "/"),
        },
    }

    report_path = out_dir / "comparison_report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nОтчёт сохранён: {report_path}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="RAG indexing pipeline: chunking + embeddings + index")
    parser.add_argument(
        "--project-root",
        default=".",
        help="Корень проекта (по умолчанию текущая директория)",
    )
    parser.add_argument(
        "--inputs",
        nargs="+",
        default=["README.md", "app/src/main/java", "rag_pipeline"],
        help="Список файлов/директорий для индексации",
    )
    parser.add_argument("--out-dir", default="rag_pipeline/output", help="Куда сохранять индекс")
    parser.add_argument("--embedding-model", default="openai/text-embedding-3-large")
    parser.add_argument("--mock-embeddings", action="store_true", help="Использовать mock эмбеддинги")
    parser.add_argument(
        "--routerai-base-url",
        default="https://routerai.ru/api/v1",
        help="Base URL RouterAI API",
    )
    parser.add_argument(
        "--local-properties-path",
        default="local.properties",
        help="Путь к local.properties с ROUTERAI_API_KEY",
    )
    parser.add_argument("--fixed-chunk-size", type=int, default=1200)
    parser.add_argument("--structural-max-chars", type=int, default=2200)
    parser.add_argument("--structural-overlap", type=int, default=200)
    return parser.parse_args()


if __name__ == "__main__":
    run_pipeline(parse_args())
