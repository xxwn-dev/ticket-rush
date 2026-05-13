# ── 빌드 스테이지 ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY src src

# 테스트는 Docker 빌드 시 제외 (인프라 의존성 때문에 별도 실행)
RUN ./gradlew bootJar -x test --no-daemon

# ── 실행 스테이지 ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

# Docker 내부에서는 Sentinel 프로파일 사용 (container DNS로 redis-master 접근 가능)
ENTRYPOINT ["java", "-jar", "app.jar"]
