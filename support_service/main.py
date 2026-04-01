from __future__ import annotations

import json
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
RATE_LIMIT_PER_MINUTE = int(os.getenv("RATE_LIMIT_PER_MINUTE", "30"))

BASE_DIR = Path(__file__).parent
CRM_PATH = BASE_DIR / "crm_data.json"
FAQ_PATH = BASE_DIR / "faq.json"


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


class ChatMessage(BaseModel):
    role: str
    content: str = Field(min_length=1)


class SupportChatRequest(BaseModel):
    messages: list[ChatMessage] = Field(default_factory=list)
    prompt: str | None = None
    ticket_id: str | None = None
    model: str = DEFAULT_MODEL
    max_context: int = MAX_CONTEXT
    temperature: float = 0.2
    stream: bool = False


def load_crm() -> dict[str, Any]:
    if not CRM_PATH.exists():
        return {"users": [], "tickets": []}
    return json.loads(CRM_PATH.read_text(encoding="utf-8"))


def load_faq() -> list[dict[str, Any]]:
    if not FAQ_PATH.exists():
        return []
    return json.loads(FAQ_PATH.read_text(encoding="utf-8"))


def build_crm_indexes(crm: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    users = {u.get("id", ""): u for u in crm.get("users", []) if u.get("id")}
    tickets = {t.get("id", ""): t for t in crm.get("tickets", []) if t.get("id")}
    return users, tickets


def tokenize(text: str) -> set[str]:
    cleaned = []
    for ch in text.lower():
        if ch.isalnum() or ch in {"_", "-"}:
            cleaned.append(ch)
        else:
            cleaned.append(" ")
    return {t for t in "".join(cleaned).split() if len(t) >= 2}


def retrieve_faq(question: str, faq_items: list[dict[str, Any]], category: str | None = None, top_k: int = 4) -> list[dict[str, Any]]:
    q_tokens = tokenize(question)
    if not q_tokens:
        return []

    scored: list[tuple[float, dict[str, Any]]] = []
    for item in faq_items:
        text = " ".join(
            [
                str(item.get("question", "")),
                str(item.get("answer", "")),
                " ".join(item.get("keywords", [])),
            ]
        )
        item_tokens = tokenize(text)
        if not item_tokens:
            continue

        overlap = len(q_tokens.intersection(item_tokens))
        score = overlap / max(1, len(q_tokens))

        if category and item.get("category") == category:
            score += 0.25

        if score > 0:
            scored.append((score, item))

    scored.sort(key=lambda x: x[0], reverse=True)
    return [item for _, item in scored[:top_k]]


def build_system_prompt(ticket: dict[str, Any] | None, user: dict[str, Any] | None, faq_hits: list[dict[str, Any]]) -> str:
    lines: list[str] = []

    lines.append("Ты — AI-ассистент службы поддержки продукта.")
    lines.append("Отвечай по делу, вежливо и понятно.")
    lines.append("Опирайся на контекст тикета и FAQ ниже.")
    lines.append("Если данных недостаточно — прямо скажи, что нужно уточнить.")
    lines.append("Не выдумывай факты, которых нет в данных тикета/FAQ.")
    lines.append("")

    if ticket and user:
        lines.append("=== КОНТЕКСТ ТИКЕТА ===")
        lines.append(f"Тикет: #{ticket.get('id')} | Статус: {ticket.get('status')} | Категория: {ticket.get('category')}")
        lines.append(f"Заголовок: {ticket.get('title')}")
        lines.append(f"Описание: {ticket.get('description')}")
        lines.append(f"Создан: {ticket.get('created_at')}")
        lines.append("")
        lines.append(f"Пользователь: {user.get('name')} | Email: {user.get('email')} | Тариф: {user.get('plan')}")
        lines.append("========================")
        lines.append("")
    elif ticket:
        lines.append("=== КОНТЕКСТ ТИКЕТА ===")
        lines.append(f"Тикет: #{ticket.get('id')} | Статус: {ticket.get('status')} | Категория: {ticket.get('category')}")
        lines.append(f"Заголовок: {ticket.get('title')}")
        lines.append(f"Описание: {ticket.get('description')}")
        lines.append("Пользователь по ticket.user_id не найден в CRM.")
        lines.append("========================")
        lines.append("")

    lines.append("=== БАЗА ЗНАНИЙ (FAQ) ===")
    if not faq_hits:
        lines.append("Релевантные FAQ не найдены.")
    else:
        for item in faq_hits:
            lines.append(f"[{item.get('id')}] ({item.get('category')}) {item.get('question')}")
            lines.append(f"→ {item.get('answer')}")
            lines.append("")
    lines.append("=========================")

    return "\n".join(lines)


app = FastAPI(title="Support AI Assistant", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

static_dir = BASE_DIR / "web"
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


@app.get("/tickets")
def tickets() -> dict[str, Any]:
    crm = load_crm()
    users, _ = build_crm_indexes(crm)
    items = []
    for t in crm.get("tickets", []):
        user = users.get(t.get("user_id", ""), {})
        items.append(
            {
                "id": t.get("id"),
                "title": t.get("title"),
                "status": t.get("status"),
                "category": t.get("category"),
                "user_id": t.get("user_id"),
                "user_name": user.get("name"),
                "created_at": t.get("created_at"),
            }
        )
    return {"tickets": items}


@app.get("/ticket/{ticket_id}")
def ticket_by_id(ticket_id: str) -> dict[str, Any]:
    crm = load_crm()
    users, tickets_index = build_crm_indexes(crm)
    ticket = tickets_index.get(ticket_id)
    if not ticket:
        raise HTTPException(status_code=404, detail={"error": "ticket_not_found", "ticket_id": ticket_id})

    user = users.get(ticket.get("user_id", ""))
    return {"ticket": ticket, "user": user}


@app.get("/user/{user_id}")
def user_by_id(user_id: str) -> dict[str, Any]:
    crm = load_crm()
    users, _ = build_crm_indexes(crm)
    user = users.get(user_id)
    if not user:
        raise HTTPException(status_code=404, detail={"error": "user_not_found", "user_id": user_id})
    return {"user": user}


@app.post("/chat")
async def chat(request: Request, payload: SupportChatRequest) -> dict[str, Any]:
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

    crm = load_crm()
    faq = load_faq()
    users, tickets_index = build_crm_indexes(crm)

    ticket = tickets_index.get(payload.ticket_id) if payload.ticket_id else None
    user = users.get(ticket.get("user_id", "")) if ticket else None

    chat_messages = [
        {"role": m.role, "content": m.content} for m in payload.messages if m.content.strip()
    ]
    if payload.prompt and payload.prompt.strip():
        chat_messages.append({"role": "user", "content": payload.prompt.strip()})

    if not chat_messages:
        raise HTTPException(
            status_code=400,
            detail={"error": "empty_chat", "message": "Provide either prompt or messages[]"},
        )

    last_user_text = ""
    for m in reversed(chat_messages):
        if m.get("role") == "user":
            last_user_text = m.get("content", "")
            break

    faq_hits = retrieve_faq(
        question=last_user_text,
        faq_items=faq,
        category=ticket.get("category") if ticket else None,
        top_k=4,
    )

    system_prompt = build_system_prompt(ticket=ticket, user=user, faq_hits=faq_hits)
    messages_to_model = [{"role": "system", "content": system_prompt}] + chat_messages

    body = {
        "model": payload.model,
        "messages": messages_to_model,
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
        "messages_used": len(messages_to_model),
        "ticket": ticket,
        "user": user,
        "faq_used": faq_hits,
    }
