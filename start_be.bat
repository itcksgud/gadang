@echo off
rem 백엔드 로컬 실행 (JDK 17 필요). 전체 스택은 docker compose up -d --build 권장.
cd /d "%~dp0Backend"
call mvnw.cmd spring-boot:run -Dmaven.test.skip=true
