# 바람따라 (windmill)

실시간 변수(혼잡·날씨)에 대응하는 당일치기 여행 일정 서비스입니다.

- **Backend:** Spring Boot 3.3 · Java 21 · H2(로컬) / PostgreSQL(배포)
- **Frontend:** React 19 · Vite 8
- **외부 API:** 한국관광공사 TourAPI · 기상청 예보 · (선택) OpenAI

---

## 필요 도구

| 도구 | 버전 |
|------|------|
| JDK | 21 |
| Maven | 3.9+ |
| Node.js | 20 |
| npm | `frontend/package-lock.json` 기준 |

로컬 DB는 **H2 파일**(`backend/data/windmill`)이라 PostgreSQL 설치는 필요 없습니다.

---

## 환경 변수

`backend/.env.example`을 참고하세요. Spring Boot는 `.env` 파일을 **자동으로 읽지 않습니다.**  
IDE Run Configuration 또는 셸에서 환경변수로 넣어야 합니다.

### 필수

| 변수 | 설명 |
|------|------|
| `TOURAPI_KEY` | [data.go.kr](https://www.data.go.kr) **디코딩(Decoding)** 서비스키 |

같은 키로 아래 API를 **활용신청·승인**해야 핵심 기능이 동작합니다.

1. 한국관광공사 **KorService2** — 장소 목록/상세, 스마트 일정 후보
2. **TarRlteTarService1** — 연관관광지(추천 Stage1)
3. **TatsCnctrRateService** — 관광지 집중률(혼잡도)
4. 기상청 **VilageFcstInfoService_2.0** — 단기예보(비·기온)
5. 기상청 **MidFcstInfoService** — 중기예보(며칠 앞 전망)
6. (선택) **DataLabService** — 지역 방문자수

> WebClient가 쿼리 파라미터를 URL-인코딩합니다. **인코딩된 키**를 넣으면 이중 인코딩으로  
> `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`가 납니다. 반드시 Decoding 키를 사용하세요.

### 선택

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `OPENAI_API_KEY` | (없음) | Stage4 태그/한줄소개, AI 도슨트, AI 초안 일정 |
| `OPENAI_MODEL` | `gpt-4o-mini` | OpenAI 모델 |
| `H2_CONSOLE_ENABLED` | `false` | 로컬에서만 `true` 권장 (`/h2-console`) |

OpenAI 키가 없어도 TourAPI 기반 **스마트 동선·혼잡·날씨 대응**은 동작합니다.

### PowerShell 예시

```powershell
$env:TOURAPI_KEY = "디코딩키"
$env:OPENAI_API_KEY = "sk-..."   # 선택
$env:H2_CONSOLE_ENABLED = "true"
```

---

## 로컬 실행

터미널을 두 개 엽니다.

```powershell
# 1) 백엔드 — http://localhost:8080
cd backend
mvn spring-boot:run
```

```powershell
# 2) 프론트 — Vite가 /api → localhost:8080 으로 프록시
cd frontend
npm install
npm run dev
```

브라우저에서 Vite 개발 서버 주소(보통 `http://localhost:5173`)로 접속합니다.

프론트는 기본으로 `/api`를 사용합니다(`VITE_API_URL` 미설정).  
백엔드를 다른 호스트에 둘 때만 `frontend/.env`에 예를 들어 다음을 넣습니다.

```env
VITE_API_URL=http://localhost:8080/api
```

### 테스트

```powershell
cd backend
mvn test
```

### 통합 빌드 (프론트 → JAR 정적 리소스)

```bash
./build.sh
# 결과: backend/target/windmill-backend-0.0.1-SNAPSHOT.jar
```

또는 Docker:

```bash
docker build -t windmill .
docker run -p 8080:8080 -e TOURAPI_KEY=디코딩키 windmill
```

---

## 배포 (Render 등)

`SPRING_PROFILES_ACTIVE=prod`일 때 PostgreSQL을 사용합니다 (`application-prod.yml`).

| 변수 | 설명 |
|------|------|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `TOURAPI_KEY` | 필수 |
| `OPENAI_API_KEY` | 선택 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | Postgres 접속 정보 |
| `PORT` | 서버 포트(플랫폼이 주입하는 경우 그대로) |

---

## 방문 순서 최적화

당일치기 3~5곳 수준에서는 **Haversine 직선거리 + 순열 전수조사**(≤8곳)로 최단 열린 경로를 고릅니다.
좌표(`mapx`/`mapy`)는 KorService2 `detailCommon2`에서 이미 내려오며, 일정 항목에 저장됩니다.

- `POST /api/itineraries/{id}/optimize-route?date=&originLon=&originLat=`
  - GPS를 넘기면 현재 위치를 시작점(0번)으로 두고 나머지를 재정렬합니다.
- 연관관광지(TarRlte) API에는 **이동시간/Tmap 필드가 없어** 2차 도로시간 보정은 아직 없습니다.
- 좌표가 없는 장소는 최적화에서 제외되고 맨 뒤에 붙습니다(텍스트만 입력한 경우 지오코딩 미구현).

프론트: 「오늘 동선」🔄 버튼으로 GPS 재요청 → 순서 재계산.
여러 장소를 한 번에 담으면(스마트/자동 일정 확정) 자동으로 최단 순서로 재배열하고 안내 문구를 보여 줍니다.

---

## 폴더 구조

```
windmill/
├── backend/          # Spring Boot API
│   ├── .env.example  # 환경변수 안내
│   └── src/main/resources/
│       ├── application.yml
│       └── application-prod.yml
├── frontend/         # React (Vite)
├── Dockerfile
└── build.sh
```

---

## 인수인계 체크리스트

- [ ] GitHub 저장소 접근 권한
- [ ] `TOURAPI_KEY` (Decoding) 및 data.go.kr 활용신청 목록 공유
- [ ] (선택) `OPENAI_API_KEY`
- [ ] (배포 담당) Render 등 대시보드·Postgres 접속 정보
- [ ] 로컬에서 `mvn spring-boot:run` + `npm run dev`로 스마트 동선 생성까지 확인
