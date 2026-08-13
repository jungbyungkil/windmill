# === 1단계: React 프론트엔드 빌드 ===
FROM node:20-alpine AS frontend-build
WORKDIR /app
COPY frontend/package*.json frontend/
RUN cd frontend && npm ci
COPY frontend frontend
# Render가 서비스 env를 Docker build-arg로 넘김 → Vite에 주입 (JS 키는 도메인 제한된 공개 키)
ARG VITE_KAKAO_JS_KEY=
ENV VITE_KAKAO_JS_KEY=$VITE_KAKAO_JS_KEY
# 에러 리포팅(sentry.io) - DSN은 공개돼도 안전한 값. AUTH_TOKEN/ORG/PROJECT는 소스맵 업로드용
# (선택, 셋 다 있어야 활성화 - vite.config.js 참고)
ARG VITE_SENTRY_DSN=
ENV VITE_SENTRY_DSN=$VITE_SENTRY_DSN
ARG SENTRY_AUTH_TOKEN=
ENV SENTRY_AUTH_TOKEN=$SENTRY_AUTH_TOKEN
ARG SENTRY_ORG=
ENV SENTRY_ORG=$SENTRY_ORG
ARG SENTRY_PROJECT=
ENV SENTRY_PROJECT=$SENTRY_PROJECT
RUN cd frontend && npm run build

# === 2단계: Spring Boot 백엔드 빌드 ===
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app
COPY backend/pom.xml backend/pom.xml
RUN cd backend && mvn -B dependency:go-offline
COPY backend backend
COPY --from=frontend-build /app/backend/src/main/resources/static backend/src/main/resources/static
RUN cd backend && mvn -B clean package -DskipTests

# === 3단계: 실행 이미지 ===
# KAKAO_REST_API_KEY / TOURAPI_KEY 등은 런타임 env로만 주입 (이미지에 베이크하지 않음)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-build /app/backend/target/windmill-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
