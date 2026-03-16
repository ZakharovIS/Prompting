from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Iterable


@dataclass
class Chunk:
    chunk_id: str
    source: str
    title: str
    section: str
    strategy: str
    text: str
    char_start: int
    char_end: int

    def to_dict(self) -> dict:
        return {
            "chunk_id": self.chunk_id,
            "source": self.source,
            "title": self.title,
            "section": self.section,
            "strategy": self.strategy,
            "char_start": self.char_start,
            "char_end": self.char_end,
            "text": self.text,
        }


def _sliding_windows(text: str, size: int, overlap: int) -> Iterable[tuple[int, int, str]]:
    if size <= 0:
        raise ValueError("size must be > 0")
    if overlap >= size:
        raise ValueError("overlap must be smaller than size")

    step = size - overlap
    pos = 0
    n = len(text)
    while pos < n:
        end = min(pos + size, n)
        yield pos, end, text[pos:end]
        if end == n:
            break
        pos += step


def fixed_size_chunking(
    *,
    doc_text: str,
    source: str,
    title: str,
    chunk_size: int = 1200,
    overlap: int = 0,
    chunk_prefix: str = "fixed",
) -> list[Chunk]:
    chunks: list[Chunk] = []
    i = 0
    for start, end, piece in _sliding_windows(doc_text, chunk_size, overlap):
        trimmed = piece.strip()
        if not trimmed:
            continue
        chunks.append(
            Chunk(
                chunk_id=f"{chunk_prefix}_{i:05d}",
                source=source,
                title=title,
                section="full_document",
                strategy="fixed_size",
                text=trimmed,
                char_start=start,
                char_end=end,
            )
        )
        i += 1
    return chunks


_MD_HEADER_RE = re.compile(r"^(#{1,6})\s+(.*)$", re.MULTILINE)
_CODE_SECTION_RE = re.compile(
    r"^(?:\s*(?:class|object|interface|data\s+class|fun)\s+[A-Za-z0-9_<>]+.*)$",
    re.MULTILINE,
)


def _find_sections(text: str, source: str) -> list[tuple[str, int, int]]:
    matches = list(_MD_HEADER_RE.finditer(text))
    if matches:
        sections: list[tuple[str, int, int]] = []
        for idx, m in enumerate(matches):
            section = m.group(2).strip()
            start = m.start()
            end = matches[idx + 1].start() if idx + 1 < len(matches) else len(text)
            sections.append((section, start, end))
        return sections

    if source.endswith(".kt") or source.endswith(".java"):
        code_matches = list(_CODE_SECTION_RE.finditer(text))
        if code_matches:
            sections = []
            for idx, m in enumerate(code_matches):
                section = m.group(0).strip()
                start = m.start()
                end = code_matches[idx + 1].start() if idx + 1 < len(code_matches) else len(text)
                sections.append((section, start, end))
            return sections

    return [("full_document", 0, len(text))]


def structural_chunking(
    *,
    doc_text: str,
    source: str,
    title: str,
    max_section_chars: int = 2200,
    overlap: int = 200,
    chunk_prefix: str = "struct",
) -> list[Chunk]:
    if max_section_chars <= 0:
        raise ValueError("max_section_chars must be > 0")
    if overlap >= max_section_chars:
        raise ValueError("overlap must be smaller than max_section_chars")

    chunks: list[Chunk] = []
    chunk_i = 0

    for section_name, sec_start, sec_end in _find_sections(doc_text, source):
        raw = doc_text[sec_start:sec_end]
        if not raw.strip():
            continue

        if len(raw) <= max_section_chars:
            chunks.append(
                Chunk(
                    chunk_id=f"{chunk_prefix}_{chunk_i:05d}",
                    source=source,
                    title=title,
                    section=section_name,
                    strategy="structural",
                    text=raw.strip(),
                    char_start=sec_start,
                    char_end=sec_end,
                )
            )
            chunk_i += 1
            continue

        for rel_start, rel_end, piece in _sliding_windows(raw, max_section_chars, overlap):
            trimmed = piece.strip()
            if not trimmed:
                continue
            chunks.append(
                Chunk(
                    chunk_id=f"{chunk_prefix}_{chunk_i:05d}",
                    source=source,
                    title=title,
                    section=section_name,
                    strategy="structural",
                    text=trimmed,
                    char_start=sec_start + rel_start,
                    char_end=sec_start + rel_end,
                )
            )
            chunk_i += 1

    return chunks
