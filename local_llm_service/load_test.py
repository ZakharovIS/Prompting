import asyncio
import time

import httpx


URL = "http://127.0.0.1:8080/chat"


async def one_request(i: int, client: httpx.AsyncClient):
    payload = {
        "prompt": f"Ответь одним словом: test-{i}",
        "model": "llama3.1:8b",
        "max_context": 1024,
    }
    started = time.perf_counter()
    r = await client.post(URL, json=payload, timeout=180)
    elapsed = time.perf_counter() - started
    return i, r.status_code, elapsed, r.json()


async def main():
    async with httpx.AsyncClient() as client:
        tasks = [one_request(i, client) for i in range(1, 6)]
        results = await asyncio.gather(*tasks, return_exceptions=True)

    ok = 0
    for res in results:
        if isinstance(res, Exception):
            print(f"ERROR: {res}")
            continue
        i, code, elapsed, data = res
        print(f"#{i}: status={code}, t={elapsed:.2f}s")
        if code == 200:
            ok += 1
            text = (data.get("response") or "").strip().replace("\n", " ")
            print(f"    response: {text[:120]}")
        else:
            print(f"    body: {data}")

    print(f"\nSummary: {ok}/5 successful")


if __name__ == "__main__":
    asyncio.run(main())
