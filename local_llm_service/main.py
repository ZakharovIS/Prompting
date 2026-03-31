from __future__ import annotations

import os
import time
from collections import defaultdict, deque
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field


OLLAMA_URL = os.getenv("OLLAMA_URL", "http://127.0.0.1:11434")
DEFAULT_MODEL = os.getenv("DEFAULT_MODEL", "llama3.1:8b")
MAX_CONTEXT = int(os.getenv("MAX_CONTEXT", "4096"))
RATE_LIMIT_PER_MINUTE = int(os.getenv("RATE_LIMIT_PER_MINUTE", "20"))


class RateLimiter:
    def __init__(self, requests_per_minute: int):
        self.limit = requests_per_minute
        self.windows: dict[str, deque[float]] = defaultdict(deque)

    def check(self, key: str) -> tuple[bool, int]:
        now = time.time()
        cutoff = now - 60.0
        q = self.windows[key]
        while q and q[0] < cutoff:
            q.popleft()
        if len(q) >= self.limit:
            retry_after = int(60 - (now - q[0])) if q else 60
            return False, max(retry_after, 1)
        q.append(now)
        return True, 0


limiter = RateLimiter(RATE_LIMIT_PER_MINUTE)


class ChatRequest(BaseModel):
    prompt: str | None = None
    messages: list["ChatMessage"] = Field(default_factory=list)
    model: str = DEFAULT_MODEL
    stream: bool = False
    max_context: int = MAX_CONTEXT
    temperature: float = 0.7


class ChatMessage(BaseModel):
    role: str
    content: str = Field(min_length=1)


app = FastAPI(title="Local LLM Proxy", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

static_dir = Path(__file__).parent / "web"
app.mount("/web", StaticFiles(directory=static_dir), name="web")


@app.get("/")
def index() -> FileResponse:
    return FileResponse(static_dir / "index.html")


@app.get("/health")
async def health() -> dict[str, str]:
    async with httpx.AsyncClient(timeout=10.0) as client:
        try:
            r = await client.get(f"{OLLAMA_URL}/api/tags")
            r.raise_for_status()
            return {"status": "ok", "ollama": "reachable"}
        except Exception:
            return {"status": "degraded", "ollama": "unreachable"}


@app.get("/models")
async def models() -> dict[str, Any]:
    async with httpx.AsyncClient(timeout=30.0) as client:
        try:
            r = await client.get(f"{OLLAMA_URL}/api/tags")
            r.raise_for_status()
            return r.json()
        except Exception as e:
            raise HTTPException(
                status_code=503,
                detail={
                    "error": "ollama_unreachable",
                    "message": f"Cannot reach Ollama at {OLLAMA_URL}",
                    "hint": "Start Ollama with: set OLLAMA_HOST=0.0.0.0:11434 && ollama serve",
                    "details": str(e),
                },
            )


@app.post("/chat")
async def chat(request: Request, payload: ChatRequest) -> dict[str, Any]:
    client_ip = request.client.host if request.client else "unknown"
    allowed, retry_after = limiter.check(client_ip)
    if not allowed:
        raise HTTPException(
            status_code=429,
            detail={
                "error": "rate_limit_exceeded",
                "message": f"Too many requests. Retry in {retry_after} sec",
            },
            headers={"Retry-After": str(retry_after)},
        )

    if payload.max_context > MAX_CONTEXT:
        raise HTTPException(
            status_code=400,
            detail={
                "error": "max_context_exceeded",
                "message": f"Requested context {payload.max_context} > allowed {MAX_CONTEXT}",
            },
        )

    messages: list[dict[str, str]] = [
        {"role": m.role, "content": m.content} for m in payload.messages if m.content.strip()
    ]
    if payload.prompt and payload.prompt.strip():
        messages.append({"role": "user", "content": payload.prompt.strip()})

    if not messages:
        raise HTTPException(
            status_code=400,
            detail={
                "error": "empty_chat",
                "message": "Provide either prompt or messages[]",
            },
        )

    body = {
        "model": payload.model,
        "messages": messages,
        "stream": payload.stream,
        "options": {
            "num_ctx": min(payload.max_context, MAX_CONTEXT),
            "temperature": payload.temperature,
        },
    }

    async with httpx.AsyncClient(timeout=180.0) as client:
        try:
            r = await client.post(f"{OLLAMA_URL}/api/chat", json=body)
            if r.status_code >= 400:
                raise HTTPException(status_code=r.status_code, detail=r.text)
            data = r.json()
        except HTTPException:
            raise
        except Exception as e:
            raise HTTPException(
                status_code=503,
                detail={
                    "error": "ollama_unreachable",
                    "message": f"Cannot reach Ollama at {OLLAMA_URL}",
                    "hint": "Start Ollama with: set OLLAMA_HOST=0.0.0.0:11434 && ollama serve",
                    "details": str(e),
                },
            )

    assistant_text = (
        (data.get("message") or {}).get("content")
        if isinstance(data.get("message"), dict)
        else ""
    )

    return {
        "model": data.get("model", payload.model),
        "response": assistant_text,
        "done": data.get("done", True),
        "total_duration": data.get("total_duration"),
        "eval_count": data.get("eval_count"),
        "messages_used": len(messages),
    }
