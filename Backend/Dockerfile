# ── 1단계: 빌드 (Maven + JDK17) ─────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# pom.xml만 먼저 복사해 의존성 레이어를 캐시 — 소스만 바뀌면 재다운로드 없음
COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q package -DskipTests

# ── 2단계: 실행 (JRE만 — 이미지 경량화) ──────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

