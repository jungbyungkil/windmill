# === 1단계: React 프론트엔드 빌드 ===
FROM node:20-alpine AS frontend-build
WORKDIR /app
COPY frontend/package*.json frontend/
RUN cd frontend && npm ci
COPY frontend frontend
COPY backend/src/main/resources/static backend/src/main/resources/static
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
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-build /app/backend/target/windmill-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
