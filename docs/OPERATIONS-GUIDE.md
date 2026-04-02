# TeeBox 운영 가이드

## 1. 배포

### 빌드

```bash
cd propertee-teebox && ./gradlew teeBoxZip
# → build/distributions/propertee-teebox-dist.zip
```

### 설치

```bash
unzip propertee-teebox-dist.zip -d /opt/teebox
```

디렉터리 구조:
```
/opt/teebox/
  bin/run-teebox.sh     # 실행 스크립트
  conf/teebox.properties # 설정 파일
  lib/propertee-teebox.jar
```

### 설정

`conf/teebox.properties`:
```properties
propertee.teebox.bind=127.0.0.1
propertee.teebox.port=18080
propertee.teebox.dataDir=/var/lib/teebox
propertee.teebox.maxRuns=64
```

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `bind` | `127.0.0.1` | 바인드 주소 |
| `port` | `18080` | 리스닝 포트 |
| `dataDir` | (필수) | 데이터 디렉터리 (runs, tasks, script-registry) |
| `maxRuns` | `64` | 동시 실행 가능한 최대 run 수 |
| `apiToken` | 없음 | 전체 API 공통 Bearer 토큰 (fallback) |
| `clientApiToken` | 없음 | `/api/client` 전용 토큰 |
| `publisherApiToken` | 없음 | `/api/publisher` 전용 토큰 |
| `adminApiToken` | 없음 | `/api/admin` 전용 토큰 |
| `allowedScriptRoots` | `dataDir` | SHELL 실행 허용 경로 (쉼표 구분) |
| `runRetentionMs` | `86400000` (24h) | run 보관 → archived 전환 |
| `runArchiveRetentionMs` | `604800000` (7d) | archived → 삭제 |
| `maintenanceIntervalMs` | `60000` (1m) | 백그라운드 유지보수 주기 |

환경 변수:
- `PROPERTEE_TEEBOX_CONFIG` — 설정 파일 경로 (기본: `conf/teebox.properties`)
- `JAVA_HOME` — Java 설치 경로
- `JAVA_OPTS` — JVM 옵션 (`-Xmx`, `-D` 등). 시스템 프로퍼티는 설정 파일보다 우선

### 실행

```bash
./bin/run-teebox.sh
```

### 의존성

- Java 17+
- `setsid` (util-linux) — task process group 격리에 필요. Linux에 기본 포함.

---

## 2. API 구조

3개의 독립된 API 네임스페이스, 각각 별도 Bearer 토큰 인증:

| 네임스페이스 | 경로 | 용도 |
|-------------|------|------|
| Client | `/api/client` | Run 제출 및 결과 조회 |
| Publisher | `/api/publisher` | 스크립트 등록 및 버전 관리 |
| Admin | `/api/admin` | 시스템 조회, run/task 상세, kill |

Admin HTML UI: `/admin`

전체 API 스펙: `swagger.yaml` (OpenAPI 3.0)

---

## 3. 스크립트 실행 흐름

```
Publisher API로 스크립트 등록 → Client API로 run 제출 → TeeBox가 실행 → 결과 조회
```

1. **스크립트 등록**: `POST /api/publisher/scripts/{scriptId}/versions/{version}`
2. **버전 활성화**: `POST /api/publisher/scripts/{scriptId}/activate/{version}`
3. **Run 제출**: `POST /api/client/scripts/{scriptId}/runs`
4. **결과 폴링**: `GET /api/client/runs/{runId}`

운영 권장 패턴:
- job submit 스크립트는 job id를 확보하면 바로 종료
- job status polling은 별도 짧은 스크립트로 분리하고 외부 스케줄러나 cron에서 주기 호출
- 하나의 ProperTee run 안에서 background job 후 장시간 `wait` 하거나 polling loop를 유지하는 패턴은 비권장

---

## 4. 프로세스 관리

### Task 실행 구조

TeeBox는 ProperTee 스크립트의 `SHELL()` 호출마다 외부 프로세스(task)를 생성합니다.

```
TeeBox (Java)
  └── setsid /bin/sh command.sh    ← task (독립 process group)
        └── /path/to/user_script.sh
              └── sleep, curl, ...
```

- `setsid`로 task를 TeeBox와 다른 process group으로 격리를 시도
- `command.sh`는 exit code 캡처 용도의 최소 래퍼

### Task Kill

**반드시 TeeBox를 통해 kill해야 합니다.**

- Admin UI: task detail 페이지의 **Kill Task** 버튼
- Admin API: `POST /api/admin/tasks/{taskId}/kill`
- Run 전체 kill: `POST /api/admin/runs/{runId}/kill-tasks`

TeeBox는 process group kill을 우선 시도하고, 필요 시 하위 프로세스 트리를 수집하여 개별 kill하는 fallback을 수행합니다.

### Shell에서 직접 kill (비권장)

프로세스 중단은 반드시 TeeBox UI 또는 Admin API를 사용해야 합니다. Shell에서 `kill <PID>`로 단일 프로세스만 종료하면 자식 프로세스가 orphan으로 남을 수 있으며, TeeBox의 lifecycle 관리와 불일치가 발생합니다.

---

## 5. 스크립트 작성 가이드

### 보안 제약

- TeeBox는 root로 실행되면 시작 실패
- `sudo`, `su` 명령은 차단
- 경로 형태로 지정한 실행 파일은 `allowedScriptRoots` 내에서만 허용 (기본: `dataDir`)
- 일반적인 shell 문법(`;`, `|`, `&&`, 리다이렉션 등)은 허용
- bare command 실행 허용
- 치명적인 시스템 파괴 명령은 차단 (`shutdown`, `reboot`, 위험한 `rm -rf`, `/dev/*` 대상 `dd` 등)
- 제어 문자 (`\n`, `\r`, `\0`) 차단
- 위험 환경 변수 (`LD_PRELOAD`, `DYLD_*`) 차단

### Background 프로세스 주의사항

| 상황 | TeeBox kill 시 | 정상 종료 시 |
|------|---------------|-------------|
| foreground 명령 | 정리됨 | 정리됨 |
| `cmd &` (단순 background) | 대체로 정리 가능 | 남을 수 있음 |
| `setsid cmd &`, `nohup cmd &`, `disown` | 남을 가능성 큼 | 남음 |

**권장 사항:**

- background process가 task의 일부라면 반드시 `wait`로 회수할 것
- 스크립트 종료 전에 직접 정리해야 하는 background child는 명시적으로 종료할 것
- `setsid`, `nohup`, `disown`으로 분리한 프로세스는 TeeBox task lifecycle 밖으로 간주될 수 있음

### 예시: 올바른 background 사용

```sh
#!/bin/sh
# background 작업 시작
some_work &
WORKER_PID=$!

# 다른 작업
do_something_else

# 반드시 wait로 회수
wait $WORKER_PID
```

---

## 6. 데이터 관리

### Retention (보관 주기)

**Run:**
```
Active (0~24h) → Archived (24h~7d) → Purged (7d~)
```
- Active: 전체 로그 (stdout/stderr 최대 200줄), 스레드 정보 유지
- Archived: 스레드 목록 제거, stdout 50줄/stderr 20줄로 축소
- Purged: 디스크에서 삭제

**Task:**
- 동일한 retention 구조 (`propertee.task.retentionMs`, `propertee.task.archiveRetentionMs`)

### dataDir 구조

```
dataDir/
  runs/           # run 상태 JSON 파일
  tasks/          # task 메타데이터, stdout/stderr 로그
  script-registry/ # 등록된 스크립트 버전
```

---

## 7. 모니터링

### Admin Dashboard

`http://<host>:<port>/admin` — 실시간 대시보드

- Active/Queued run 현황
- JVM 메모리, 디스크 사용량
- Auto-refresh (5초 간격, 토글 가능)

### Health Endpoint

```bash
curl http://127.0.0.1:18080/health
```

### Run Detail

run detail 페이지에서 확인 가능한 정보:
- **Script Output**: ProperTee `PRINT()` 출력
- **Script Errors**: `PRINT_ERR()` 출력
- **Task Output**: 각 task(외부 프로세스)의 stdout/stderr
- **Input Properties**: run에 전달된 입력값
- 실행 중인 run은 auto-refresh로 출력이 실시간 추적됨

### 로깅

Log4j2 기반. 콘솔(stderr)과 파일에 동시 출력.

**로그 파일 위치**: `propertee.teebox.logDir` 시스템 프로퍼티로 지정 (기본: `logs/`)

```
logs/
  teebox.log              # 현재 로그
  teebox-2026-03-24-1.log.gz  # 롤링된 로그
```

**롤링 정책:**
- 파일 크기 50MB 또는 1일 단위로 롤링
- 최대 30개 보관 후 자동 삭제

**설정 변경**: `conf/log4j2.xml` 수정 또는 `PROPERTEE_TEEBOX_LOG4J` 환경 변수로 별도 설정 파일 지정

**로그 형식:**
```
2026-03-24 10:30:15.123 [INFO ] [AUDIT] ALLOWED runId=run-abc command=/path/script.sh
2026-03-24 10:30:20.456 [ERROR] [RunManager] Run failed: run-abc -- RuntimeException: ...
```

**주요 로그 컴포넌트:**

| 컴포넌트 | 내용 |
|----------|------|
| `TeeBox` | 서버 시작/종료 |
| `AUDIT` | task 명령 허용/차단 |
| `API` | API 요청 에러 |
| `AdminUI` | Admin UI 에러 |
| `RunManager` | run 실행 실패, flush/maintenance 에러 |
| `TaskEngine` | task index/lifecycle 에러, process group kill 실패 |
| `RunStore` | run 저장소 I/O 에러 |
