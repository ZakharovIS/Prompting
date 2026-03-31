import json

import httpx


URL = "http://127.0.0.1:8081/chat"


def run():
    payload = {
        "prompt": "Ответь коротко: ping",
        "model": "llama3.1:8b",
        "max_context": 1024,
    }

    with httpx.Client(timeout=180) as client:
        for i in range(1, 6):
            r = client.post(URL, json=payload)
            print(f"#{i}: status={r.status_code}")
            try:
                print(json.dumps(r.json(), ensure_ascii=False)[:220])
            except Exception:
                print(r.text[:220])


if __name__ == "__main__":
    run()
