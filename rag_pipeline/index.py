from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from typing import Iterable

from chunkers import Chunk


def save_json_index(path: Path, chunks: list[Chunk], embeddings: list[list[float]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = []
    for chunk, vector in zip(chunks, embeddings):
        item = chunk.to_dict()
        item["embedding"] = vector
        payload.append(item)

    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def save_sqlite_index(path: Path, chunks: list[Chunk], embeddings: list[list[float]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS chunks (
                chunk_id TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                title TEXT NOT NULL,
                section TEXT NOT NULL,
                strategy TEXT NOT NULL,
                char_start INTEGER NOT NULL,
                char_end INTEGER NOT NULL,
                text TEXT NOT NULL,
                embedding_json TEXT NOT NULL
            )
            """
        )
        conn.execute("DELETE FROM chunks")

        rows: Iterable[tuple] = (
            (
                ch.chunk_id,
                ch.source,
                ch.title,
                ch.section,
                ch.strategy,
                ch.char_start,
                ch.char_end,
                ch.text,
                json.dumps(vec, ensure_ascii=False),
            )
            for ch, vec in zip(chunks, embeddings)
        )

        conn.executemany(
            """
            INSERT INTO chunks (
                chunk_id, source, title, section, strategy,
                char_start, char_end, text, embedding_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            rows,
        )
        conn.commit()
    finally:
        conn.close()
