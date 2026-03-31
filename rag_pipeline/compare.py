from __future__ import annotations

from dataclasses import asdict, dataclass
from statistics import mean

from chunkers import Chunk


@dataclass
class StrategyStats:
    strategy: str
    chunks_count: int
    avg_chars: float
    min_chars: int
    max_chars: int
    unique_sources: int
    unique_sections: int

    def to_dict(self) -> dict:
        return asdict(self)


def calculate_stats(strategy: str, chunks: list[Chunk]) -> StrategyStats:
    if not chunks:
        return StrategyStats(
            strategy=strategy,
            chunks_count=0,
            avg_chars=0.0,
            min_chars=0,
            max_chars=0,
            unique_sources=0,
            unique_sections=0,
        )

    sizes = [len(c.text) for c in chunks]
    return StrategyStats(
        strategy=strategy,
        chunks_count=len(chunks),
        avg_chars=round(mean(sizes), 2),
        min_chars=min(sizes),
        max_chars=max(sizes),
        unique_sources=len({c.source for c in chunks}),
        unique_sections=len({c.section for c in chunks}),
    )


def render_comparison_console(fixed: StrategyStats, structural: StrategyStats) -> str:
    lines = []
    lines.append("=== СРАВНЕНИЕ СТРАТЕГИЙ CHUNKING ===")
    lines.append("")

    lines.append("Стратегия 1: fixed_size")
    lines.append(f"  Чанков: {fixed.chunks_count}")
    lines.append(f"  Средний размер: {fixed.avg_chars} chars")
    lines.append(f"  Мин/Макс: {fixed.min_chars} / {fixed.max_chars}")
    lines.append(f"  Источников: {fixed.unique_sources}")
    lines.append(f"  Уникальных секций: {fixed.unique_sections}")
    lines.append("")

    lines.append("Стратегия 2: structural")
    lines.append(f"  Чанков: {structural.chunks_count}")
    lines.append(f"  Средний размер: {structural.avg_chars} chars")
    lines.append(f"  Мин/Макс: {structural.min_chars} / {structural.max_chars}")
    lines.append(f"  Источников: {structural.unique_sources}")
    lines.append(f"  Уникальных секций: {structural.unique_sections}")
    lines.append("")

    if fixed.chunks_count > structural.chunks_count:
        lines.append("Вывод: fixed_size даёт больше, но более мелких фрагментов.")
    else:
        lines.append("Вывод: structural даёт больше, но более мелких фрагментов.")

    if structural.unique_sections >= fixed.unique_sections:
        lines.append("Вывод: structural лучше сохраняет структуру исходных документов.")

    return "\n".join(lines)
