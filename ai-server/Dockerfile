FROM python:3.12-slim
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py llm.py generate_corpus.py ./
# 코퍼스·임베딩 캐시 — 있으면 기동 시 재임베딩 없이 바로 서빙
COPY corpus.json embeddings.npy ./

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]

