# TeeBox 분석 보고서

ProperTee TeeBox 서버의 코드 분석 결과. 프로젝트 분리(propertee-java → propertee-teebox) 과정에서 작성됨.

---

## 1. 아키텍처 개요

```
HTTP Request → TeeBoxServer (com.sun.net.httpserver)
                  ├── /admin/*          → AdminPageRenderer (HTML UI)
                  ├── /api/client/*     → Run 제출 및 결과 조회
                  ├── /api/publisher/*  → 스크립트 등록/활성화
                  └── /api/admin/*      → 실행/태스크 관리
                            ↓
                       RunManager (coordinator)
                  ┌────────┴────────┐
            ScriptExecutor    RunRegistry ←→ RunStore (disk)
                  ↓                              ↓
            ProperTeeInterpreter           runs/index.json
            + Scheduler                    runs/{runId}.json
                  ↓
            TaskEngine ←→ tasks/index.json
                              tasks/task-{id}/
            ScriptRegistry ←→ script-registry/
                                {scriptId}/script.json
                                {scriptId}/versions/{ver}.pt
```

### 구성 요소 (19개 Java 파일, ~4,000 LOC)

| 분류 | 클래스 | 역할 |
|---|---|---|
| **HTTP/진입점** | `TeeBoxMain` | 프로세스 진입점. Config 로드 → Server 시작 → shutdown hook |
| | `TeeBoxServer` | HTTP 서버. 3개 컨텍스트 핸들러(`/`, `/admin`, `/api`). 네임스페이스별 Bearer 토큰 인증 |
| | `TeeBoxConfig` | 시스템 프로퍼티/파일 기반 설정. bind, port, scriptsRoot, dataDir, 토큰 등 |
| **Run 관리** | `RunManager` | 중앙 코디네이터. 스크립트 실행 제출, TaskEngine 라이프사이클 관리, 유지보수 스케줄러 |
| | `RunRegistry` | 인메모리 Run 상태 캐시 (ConcurrentHashMap). 로그 링버퍼(200줄), 아카이빙 |
| | `RunStore` | 파일 기반 영속. index.json + {runId}.json. atomic write (tmp+move). `synchronized` |
| **스크립트 관리** | `ScriptRegistry` | 스크립트 버전 관리. 등록/활성화/조회. `synchronized` |
| | `ScriptExecutor` | 무상태 실행기. parse → interpret → schedule → result 수집 |
| **UI** | `AdminPageRenderer` | 서버 렌더링 HTML 페이지 생성 (대시보드, Run/Task 상세, 스크립트 목록) |
| **클라이언트** | `TeeBoxClient` | TeeBox API 프로그래밍 클라이언트 |
| | `TeeBoxUpstreamMockMain` | 외부 시스템 시뮬레이션용 테스트 하네스 |
| **DTO** | `RunInfo`, `RunRequest`, `RunStatus`, `RunThreadInfo`, `ScriptInfo`, `ScriptVersionInfo`, `SystemInfo`, `SystemInfoCollector` | 데이터 모델 |

---

## 2. API 라우트 전체 맵

### 2-1. Client API (`/api/client`)

| Method | Path | 기능 | 요청 Body |
|---|---|---|---|
| POST | `/api/client/runs` | scriptPath로 실행 제출 | `{scriptPath, props?, maxIterations?, warnLoops?}` |
| GET | `/api/client/runs` | Run 목록 | query: `status`, `offset`, `limit` |
| GET | `/api/client/runs/{runId}` | Run 요약 | — |
| GET | `/api/client/runs/{runId}/status` | Run 상태만 | — |
| GET | `/api/client/runs/{runId}/result` | Run 결과 (hasExplicitReturn, resultData) | — |
| GET | `/api/client/runs/{runId}/tasks-summary` | Task 수 요약 | — |
| POST | `/api/client/scripts/{scriptId}/runs` | scriptId+version으로 실행 제출 | `{version?, props?, maxIterations?, warnLoops?}` |
| GET | `/api/client/scripts/{scriptId}/runs` | 특정 스크립트의 Run 목록 | query: `status`, `offset`, `limit` |

### 2-2. Publisher API (`/api/publisher`)

| Method | Path | 기능 | 요청 Body |
|---|---|---|---|
| GET | `/api/publisher/scripts` | 스크립트 목록 | — |
| POST | `/api/publisher/scripts` | 스크립트 버전 등록 | `{scriptId, version, content, description?, labels?, activate?}` |
| GET | `/api/publisher/scripts/{scriptId}` | 스크립트 상세 | — |
| POST | `/api/publisher/scripts/{scriptId}/versions` | 특정 스크립트에 버전 등록 | `{version, content, description?, labels?, activate?}` |
| POST | `/api/publisher/scripts/{scriptId}/activate` | 버전 활성화 | `{version}` |

### 2-3. Admin API (`/api/admin`)

| Method | Path | 기능 | 요청 Body |
|---|---|---|---|
| GET | `/api/admin/system` | 시스템 정보 (JVM, 메모리, 디스크) | — |
| GET | `/api/admin/runs` | Run 목록 | query: `status`, `offset`, `limit` |
| GET | `/api/admin/runs/{runId}` | Run 상세 (threads, tasks 포함) | — |
| GET | `/api/admin/runs/{runId}/threads` | Run의 스레드 목록 | — |
| GET | `/api/admin/runs/{runId}/tasks` | Run의 Task 목록 | — |
| POST | `/api/admin/runs/{runId}/kill-tasks` | Run의 모든 Task 종료 | `{}` |
| GET | `/api/admin/tasks` | Task 목록 | query: `runId`, `status`, `offset`, `limit` |
| GET | `/api/admin/tasks/{taskId}` | Task 상세 (observation, stdout/stderr tail) | — |
| POST | `/api/admin/tasks/{taskId}/kill` | Task 종료 | `{}` |

### 2-4. Admin UI (`/admin`)

| Method | Path | 기능 |
|---|---|---|
| GET | `/` | → `/admin`으로 리다이렉트 |
| GET | `/admin` | 대시보드 (실행 폼, 실행 목록, 시스템 정보) |
| POST | `/admin/submit` | 폼 기반 실행 제출 (form-encoded, API와 다름) |
| GET | `/admin/scripts` | 스크립트 레지스트리 목록 |
| GET | `/admin/scripts/{scriptId}` | 스크립트 상세 (버전 목록, 소스 코드) |
| GET | `/admin/runs/{runId}` | Run 상세 페이지 |
| POST | `/admin/runs/{runId}/kill-tasks` | Run의 모든 Task 종료 후 리다이렉트 |
| GET | `/admin/tasks/{taskId}` | Task 상세 페이지 |
| POST | `/admin/tasks/{taskId}/kill` | Task 종료 후 리다이렉트 |

---

## 3. Run 제출 경로: 두 가지 방식

### 3-1. scriptPath 방식 (파일 직접 지정)

```
POST /api/client/runs
Body: {"scriptPath": "01_basic_run.pt", "props": {...}}
         ↓
parseRunRequest()  ← scriptPath만 파싱
         ↓
RunManager.submit()
         ↓
resolveRunTarget() → scriptId=null → resolveScriptPath(scriptPath)
         ↓
scriptsRoot 기준 파일 탐색 → 실행
```

### 3-2. scriptId 방식 (레지스트리 등록 스크립트)

```
POST /api/client/scripts/{scriptId}/runs
Body: {"version": "v1", "props": {...}}
         ↓
parseScriptRunRequest(exchange, scriptId)  ← scriptId는 URL path에서, version은 body에서
         ↓
RunManager.submit()
         ↓
resolveRunTarget() → scriptId!=null → ScriptRegistry.resolve(scriptId, version)
         ↓
script-registry/{scriptId}/versions/{version}.pt → 실행
```

**핵심 차이:** `parseRunRequest()`는 `scriptPath`만 읽고, `parseScriptRunRequest()`는 URL의 `scriptId`와 body의 `version`을 읽는다. `parseRunRequest()`는 body의 `scriptId` 필드를 **무시**한다.

---

## 4. Run 라이프사이클

### 4-1. 상태 전이

```
QUEUED ──→ RUNNING ──→ COMPLETED
                  └──→ FAILED
(서버 재시작 시)
non-terminal ──→ SERVER_RESTARTED
```

### 4-2. 3단계 보존 정책

| 단계 | 조건 | 동작 |
|---|---|---|
| **활성** | 완료 후 0~24시간 | 전체 로그(200줄), 스레드 정보 보존 |
| **아카이브** | 완료 후 24시간~7일 | `archived=true`, 스레드 정보 삭제, stdout 50줄/stderr 20줄로 축소 |
| **삭제** | 완료 후 7일+ | 메모리 및 디스크에서 완전 제거 |

### 4-3. 유지보수 스케줄러

| 태스크 | 주기 | 역할 |
|---|---|---|
| Flush | 2초 | dirty run 데이터 디스크 기록 |
| Maintenance | **60초** | 아카이브/삭제 판정 실행 |

유지보수는 최초 60초 지연 후 시작. `maintainRuns()` 호출 시 전체 터미널 상태 Run을 순회하며 보존 정책 적용.

---

## 5. 스크립트 레지스트리

### 5-1. 저장 구조

```
dataDir/script-registry/
└── {scriptId}/
    ├── script.json          (메타데이터: ScriptInfo)
    └── versions/
        └── {version}.pt     (스크립트 소스)
```

### 5-2. 등록 흐름

1. scriptId/version 포맷 검증 (`[A-Za-z0-9._-]+`)
2. 스크립트 구문 검증 (`ScriptParser.parse()`)
3. 기존 ScriptInfo 로드 또는 신규 생성
4. 중복 버전 체크
5. 버전 파일 기록 → SHA-256 해시 계산 → 메타데이터 갱신
6. `activate=true`이면 활성 버전으로 설정

### 5-3. 실행 해석 (resolve)

```
resolve(scriptId, version)
  → version이 null이면 activeVersion 사용
  → ScriptVersionInfo에서 버전 확인
  → .pt 파일 존재 확인
  → ResolvedScript {scriptId, version, displayPath, file} 반환
```

---

## 6. 설정 (TeeBoxConfig)

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `propertee.teebox.bind` | `127.0.0.1` | 바인드 주소 |
| `propertee.teebox.port` | `18080` | 포트 |
| `propertee.teebox.scriptsRoot` | *필수* | 스크립트 루트 디렉토리 |
| `propertee.teebox.dataDir` | *필수* | 데이터 저장 디렉토리 |
| `propertee.teebox.maxRuns` | `4` | 최대 동시 실행 수 |
| `propertee.teebox.apiToken` | — | 전체 API 기본 토큰 |
| `propertee.teebox.clientApiToken` | — | Client API 전용 토큰 (apiToken 폴백) |
| `propertee.teebox.publisherApiToken` | — | Publisher API 전용 토큰 (apiToken 폴백) |
| `propertee.teebox.adminApiToken` | — | Admin API 전용 토큰 (apiToken 폴백) |
| `propertee.teebox.runRetentionMs` | `86400000` (24h) | 아카이브까지 보존 시간 |
| `propertee.teebox.runArchiveRetentionMs` | `604800000` (7d) | 삭제까지 보존 시간 |

설정 우선순위: 시스템 프로퍼티 (`-D`) > 설정 파일 (`--config`) > 기본값

---

## 7. 발견된 버그

### Bug 1: `TeeBoxClient.submitRun()`이 잘못된 엔드포인트로 전송 [치명적]

**현상:** `submitRun(scriptId, version, props)` 호출 시 HTTP 400

**원인:**
```java
// TeeBoxClient.submitRun() — line 70-76
payload.put("scriptId", scriptId);  // body에 scriptId 포함
payload.put("version", version);
return postJson("/api/client/runs", payload, 202);  // POST /api/client/runs로 전송
```

그러나 서버의 `POST /api/client/runs` 핸들러:
```java
// TeeBoxServer.parseRunRequest() — line 440-447
Object scriptPath = raw.get("scriptPath");  // scriptPath만 읽음
request.scriptPath = scriptPath instanceof String ? ...  // scriptId 무시
```

`scriptPath`가 null이 되어 `resolveScriptPath(null)` → `"scriptPath is required"` → 400 에러.

**올바른 동작:** `submitRun()`은 `POST /api/client/scripts/{scriptId}/runs`로 전송해야 함.

**영향받는 테스트 3개:**
- `serverShouldRequireNamespaceSpecificTokensWhenConfigured`
- `serverShouldSupportPublisherClientAndAdminNamespaces`
- `serverShouldRunActivatedPublisherVersionByDefault`

**수정 방안 (택 1):**

A. **클라이언트 수정** — `submitRun()`이 올바른 엔드포인트 사용:
```java
public Map<String, Object> submitRun(String scriptId, String version, Map<String, Object> props) throws IOException {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("version", version);
    payload.put("props", props != null ? props : new LinkedHashMap<String, Object>());
    return postJson("/api/client/scripts/" + urlPath(scriptId) + "/runs", payload, 202);
}
```

B. **서버 수정** — `parseRunRequest()`에서 `scriptId`와 `version`도 파싱:
```java
private RunRequest parseRunRequest(HttpExchange exchange) throws IOException {
    Map<String, Object> raw = parseJsonBody(exchange);
    RunRequest request = new RunRequest();
    Object scriptPath = raw.get("scriptPath");
    request.scriptPath = scriptPath instanceof String ? ((String) scriptPath).trim() : null;
    Object scriptId = raw.get("scriptId");
    request.scriptId = scriptId instanceof String ? ((String) scriptId).trim() : null;
    Object version = raw.get("version");
    request.version = version instanceof String ? ((String) version).trim() : null;
    parseRunOptions(raw, request);
    return request;
}
```

C. **양쪽 모두** — 서버는 두 방식 모두 허용, 클라이언트는 정확한 엔드포인트 사용.

### Bug 2: 아카이브/삭제 테스트 타이밍 실패 [테스트 설계 결함]

**현상:** `serverShouldArchiveOldRuns`와 `serverShouldPurgeArchivedRuns` 실패

**원인:** 유지보수 스케줄러가 **60초 간격**으로 실행되지만, 테스트 타임아웃은 **8초**.

- `runRetentionMs=0` (즉시 아카이브) 설정해도 `maintainRuns()`가 60초 후에야 실행
- `runArchiveRetentionMs=100` (100ms 후 삭제) 설정해도 maintenance cycle 내에서만 삭제 판정

**타임라인 (purge 테스트):**
```
T=0ms     Run 제출
T=~100ms  Run 완료 (COMPLETED)
T=~200ms  테스트 polling 시작 (100ms 간격)
T=8000ms  테스트 타임아웃 → FAIL
T=60000ms 첫 maintenance cycle → archive → 즉시 purge 조건 충족
T=120000ms 두 번째 maintenance → purge 실행
```

**수정 방안:**

A. **테스트에서 maintenance 직접 호출:**
```java
// RunManager에 maintainNow() 메서드 추가 또는
// 테스트에서 maintenance interval을 짧게 설정 가능하도록 변경
```

B. **RunManager에 설정 가능한 maintenance 간격 추가:**
```java
long maintenanceIntervalMs = parseDurationProperty("maintenanceIntervalMs", MAINTENANCE_INTERVAL_MS);
```

C. **Run 완료 시 즉시 아카이브 판정:**
```java
// markCompleted() 또는 markFailed() 후 즉시 retentionMs 체크
```

---

## 8. 설계 고려사항 (Java 17+ 전환 시)

### 현재 제약과 전환 기회

| 항목 | 현재 (Java 7/8 호환) | Java 17+ 전환 후 |
|---|---|---|
| HTTP 서버 | `com.sun.net.httpserver` | Spring Boot 또는 Javalin/Spark |
| JSON | Gson 2.8.9 | Gson 최신 또는 Jackson |
| 컬렉션 | 수동 for 루프, anonymous class | Stream API, lambda, `var` |
| 동시성 | `synchronized`, `ConcurrentHashMap` | `StampedLock`, `CompletableFuture` |
| 파일 I/O | `File`, 수동 stream 관리 | `Path`, `Files`, try-with-resources |
| 설정 | 시스템 프로퍼티 직접 파싱 | Spring `@ConfigurationProperties` |
| 테스트 | JUnit 4 | JUnit 5 (`@ParameterizedTest`, `@TempDir`) |

### AdminPageRenderer (42KB) 리팩토링

현재 HTML을 Java 문자열 연결로 생성. 대안:
- Thymeleaf/Mustache 템플릿 엔진
- SPA 프론트엔드 (React/Vue) + REST API
- HTMX + 서버 렌더링 (가벼운 전환)

### TeeBoxClient 개선

현재 `HttpURLConnection` 사용. 대안:
- Java 11+ `HttpClient` (비동기 지원)
- OkHttp / Retrofit

---

## 9. 파일 목록

### 소스 (19개)

```
src/main/java/com/propertee/teebox/
├── TeeBoxMain.java              (진입점)
├── TeeBoxServer.java            (HTTP 서버, 37KB)
├── TeeBoxConfig.java            (설정)
├── TeeBoxClient.java            (API 클라이언트)
├── TeeBoxUpstreamMockMain.java  (테스트 하네스)
├── RunManager.java              (중앙 코디네이터, 17KB)
├── RunRegistry.java             (인메모리 캐시)
├── RunStore.java                (파일 영속)
├── ScriptRegistry.java          (버전 관리, 12KB)
├── ScriptExecutor.java          (스크립트 실행기)
├── AdminPageRenderer.java       (HTML UI, 42KB)
├── RunInfo.java                 (DTO)
├── RunRequest.java              (DTO)
├── RunStatus.java               (enum)
├── RunThreadInfo.java           (DTO)
├── ScriptInfo.java              (DTO)
├── ScriptVersionInfo.java       (DTO)
├── SystemInfo.java              (DTO)
└── SystemInfoCollector.java     (시스템 정보 수집)
```

### 테스트 (2개)

```
src/test/java/com/propertee/tests/
├── TeeBoxServerTest.java        (통합 테스트, 14개 케이스)
└── TeeBoxConfigTest.java        (단위 테스트, 3개 케이스)
```

### 기타

```
build.gradle                     (빌드 설정)
swagger.yaml                     (OpenAPI 3.0 스펙, 1146줄)
README.md                        (모듈 문서)
demo/teebox/                     (데모 스크립트 5개 + 문서)
deploy/teebox/                   (배포 설정 + 실행 스크립트)
```
