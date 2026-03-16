from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
from typing import Sequence
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

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


class EmbeddingGenerator:
    """Генерация эмбеддингов через RouterAI + fallback mock режим."""

    def __init__(
        self,
        model: str = "openai/text-embedding-3-large",
        mock: bool = False,
        base_url: str = "https://routerai.ru/api/v1",
        local_properties_path: str = "local.properties",
    ):
        load_dotenv()
        self.model = model
        self.mock = mock
        self.base_url = base_url.rstrip("/")
        self.api_key = ""

        if not self.mock:
            api_key = self._resolve_routerai_api_key(local_properties_path)
            if not api_key.strip():
                raise RuntimeError(
                    "ROUTERAI_API_KEY не найден. Добавь ключ в local.properties "
                    "или переменную окружения ROUTERAI_API_KEY. "
                    "Для демо без API используй --mock-embeddings."
                )
            self.api_key = api_key

    def embed_texts(self, texts: Sequence[str], batch_size: int = 64) -> list[list[float]]:
        if self.mock:
            return [self._mock_embedding(t) for t in texts]

        vectors: list[list[float]] = []

        for i in range(0, len(texts), batch_size):
            batch = list(texts[i : i + batch_size])
            batch_vectors = self._request_routerai_embeddings(batch)
            vectors.extend(batch_vectors)

        return vectors

    def _request_routerai_embeddings(self, batch: list[str]) -> list[list[float]]:
        url = f"{self.base_url}/embeddings"
        payload = {
            "model": self.model,
            "input": batch,
            "encoding_format": "float",
        }
        data = json.dumps(payload).encode("utf-8")

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
            with urlopen(req, timeout=90) as resp:
                body = resp.read().decode("utf-8")
                parsed = json.loads(body)
        except HTTPError as e:
            err_body = e.read().decode("utf-8", errors="ignore")
            raise RuntimeError(f"RouterAI embeddings HTTP {e.code}: {err_body}") from e
        except URLError as e:
            raise RuntimeError(f"Ошибка сети при вызове RouterAI embeddings: {e}") from e

        items = parsed.get("data")
        if not isinstance(items, list):
            raise RuntimeError(f"Некорректный ответ RouterAI embeddings: {parsed}")

        vectors: list[list[float]] = []
        for item in items:
            emb = item.get("embedding") if isinstance(item, dict) else None
            if not isinstance(emb, list):
                raise RuntimeError(f"Некорректная структура embedding в ответе: {item}")
            vectors.append([float(x) for x in emb])
        return vectors

    @staticmethod
    def _resolve_routerai_api_key(local_properties_path: str) -> str:
        env_key = os.getenv("ROUTERAI_API_KEY", "").strip()
        if env_key:
            return env_key

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

    @staticmethod
    def _mock_embedding(text: str, dim: int = 256) -> list[float]:
        """Детерминированный mock-вектор для локального теста без API."""
        seed = hashlib.sha256(text.encode("utf-8")).digest()
        nums = []
        cur = seed
        while len(nums) < dim:
            cur = hashlib.sha256(cur).digest()
            for i in range(0, len(cur), 4):
                n = int.from_bytes(cur[i : i + 4], "big", signed=False)
                nums.append((n % 2000) / 1000.0 - 1.0)
                if len(nums) == dim:
                    break
        return _l2_normalize(nums)
