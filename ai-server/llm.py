"""OpenAI API 공통 호출 모듈 (채팅 / 임베딩).

키는 환경변수 OPENAI_API_KEY 로 주입한다. 코드에 키를 두지 않는다.
"""
import os
import httpx

OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "")
OPENAI_BASE = "https://api.openai.com/v1"
HEADERS = {"Authorization": f"Bearer {OPENAI_API_KEY}", "Content-Type": "application/json"}

CHAT_MODEL = "gpt-4.1-mini"
EMBED_MODEL = "text-embedding-3-small"
EMBED_DIM = 1536


def chat(messages, temperature=0.7, max_tokens=2000):
    """단발 채팅 호출 → 응답 텍스트."""
    with httpx.Client(timeout=120) as c:
        r = c.post(
            f"{OPENAI_BASE}/chat/completions",
            headers=HEADERS,
            json={
                "model": CHAT_MODEL,
                "messages": messages,
                "temperature": temperature,
                "max_tokens": max_tokens,
            },
        )
        r.raise_for_status()
        return r.json()["choices"][0]["message"]["content"]


def embed(texts):
    """문자열 리스트 → 임베딩 벡터 리스트(1536차원)."""
    if isinstance(texts, str):
        texts = [texts]
    with httpx.Client(timeout=120) as c:
        r = c.post(
            f"{OPENAI_BASE}/embeddings",
            headers=HEADERS,
            json={"model": EMBED_MODEL, "input": texts},
        )
        r.raise_for_status()
        data = sorted(r.json()["data"], key=lambda d: d["index"])
        return [d["embedding"] for d in data]
