#!/bin/bash
# 전체 빌드 스크립트: React -> Spring Boot JAR

set -e

echo "=== [1/2] React 빌드 ==="
cd frontend
npm install
npm run build
cd ..

echo "=== [2/2] Spring Boot JAR 빌드 ==="
cd backend
mvn clean package -DskipTests
cd ..

echo ""
echo "✅ 빌드 완료: backend/target/windmill-backend-0.0.1-SNAPSHOT.jar"
