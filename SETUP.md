# SQL

cd Backend\src\main\resources
mysql -u gadang -pgadang --default-character-set=utf8mb4 -e "CREATE DATABASE IF NOT EXISTS gadang DEFAULT CHARSET utf8mb4;"

# BE

cd <프로젝트 경로>\Backend
.\mvnw.cmd clean spring-boot:run "-Dspring-boot.run.profiles=local"

# FE

cd <프로젝트 경로>\frontend
pnpm install
pnpm dev

# AI

cd <프로젝트 경로>\ai-server
.\.venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --port 8000
