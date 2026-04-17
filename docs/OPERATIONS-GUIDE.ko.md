# TeeBox 운영 가이드

## 1. 배포

### 빌드

```bash
cd propertee-teebox && ./gradlew teeBoxZip
# -> build/distributions/propertee-teebox-dist.zip
```

### 설치

```bash
unzip propertee-teebox-dist.zip -d /opt/teebox
```

기본 GitHub 배포 zip에는 Java runtime이 포함되지 않는다. 배포 서버에서 Linux x86_64 Java 21 runtime archive를 별도로 받아 `/opt/teebox/runtime/` 아래에 풀어서 `runtime/bin/java`가 존재하도록 준비해야 한다.

디렉터리 구조:
```
/opt/teebox/
  bin/run-teebox.sh      # 실행 스크립트
  conf/teebox.properties # 설정 파일
  lib/propertee-teebox.jar
  runtime/bin/java       # 별도 설치한 Java 21 runtime
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
| `dataDir` | (필수) | 데이터 디렉터리 (`runs`, `tasks`, `script-registry`) |
| `maxRuns` | `64` | 동시 실행 가능한 최대 run 수 |
| `apiToken` | 없음 | 전체 API 공통 Bearer 토큰 (fallback) |
| `clientApiToken` | 없음 | `/api/client` 전용 토큰 |
| `publisherApiToken` | 없음 | `/api/publisher` 전용 토큰 |
| `adminApiToken` | 없음 | `/api/admin` 전용 토큰 |
| `runRetentionMs` | `24h` | run 보관 -> archived 전환 |
| `runArchiveRetentionMs` | `7d` | archived -> 삭제 |
| `maintenanceIntervalMs` | `1m` | 백그라운드 유지보수 주기 |

환경 변수:
- `PROPERTEE_TEEBOX_CONFIG` - 설정 파일 경로 (기본: `conf/teebox.properties`)
- `JAVA_HOME` - Java 설치 경로
- `JAVA_OPTS` - JVM 옵션 (`-Xmx`, `-D` 등). 시스템 프로퍼티는 설정 파일보다 우선

duration 형식:
- `runRetentionMs`, `runArchiveRetentionMs`, `maintenanceIntervalMs`
- `propertee.task.retentionMs`, `propertee.task.archiveRetentionMs`
- 지원 suffix: `ms`, `s`, `m`, `h`, `d`
- 예: `500ms`, `30s`, `1m`, `24h`, `7d`

### 실행

```bash
./bin/run-teebox.sh
```

### 의존성

- Linux x86_64 Java 21 runtime (`runtime/bin/java`) 또는 시스템 Java 17+
- `setsid` (util-linux) - task process group 격리에 필요. Linux에 기본 포함
- 개발 시 `../propertee-java` composite build 필요

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
Publisher API로 스크립트 등록 -> Client API로 run 제출 -> TeeBox가 실행 -> 결과 조회
```

1. **스크립트 등록**: `POST /api/publisher/scripts/{scriptId}/versions/{version}`
2. **버전 활성화**: `POST /api/publisher/scripts/{scriptId}/activate/{version}`
3. **Run 제출**: `POST /api/client/scripts/{scriptId}/runs`
4. **결과 폴링**: `GET /api/client/runs/{runId}`

운영 권장 패턴:
- job submit 스크립트는 job id를 확보하면 바로 종료
- job status polling은 별도 짧은 스크립트로 분리하고 외부 스케줄러나 cron에서 주기 호출
- 하나의 ProperTee run 안에서 background job 후 장시간 `wait` 하거나 polling loop를 유지하는 패턴은 비권장

### 스크립트별 동시 실행 제어

스크립트별로 실행 설정을 구성할 수 있습니다:

- **maxConcurrentRuns**: 해당 스크립트의 최대 동시 실행 수 (0 = 무제한, 글로벌 제한 사용)
- **immediate**: true로 설정하면 글로벌 큐를 우회하여 별도 스레드 풀에서 실행. 스크립트별 동시 실행 한도(`maxConcurrentRuns`)는 동일하게 적용됩니다.

`immediate` 설정은 글로벌 큐만 우회하며 스크립트별 동시 실행 한도는 동일하게 적용됩니다. `immediate` 플래그는 사용할 executor만 결정하며, 동시 실행 제한 적용 여부에는 영향을 주지 않습니다.

| 설정 | Executor | 동시 실행 제한 |
|------|----------|----------------|
| `immediate=true, maxConcurrentRuns=0` | Immediate executor | 무제한 |
| `immediate=true, maxConcurrentRuns=3` | Immediate executor | 3개 초과 시 PENDING |
| `immediate=false, maxConcurrentRuns=3` | Global executor | 3개 초과 시 PENDING |
| `immediate=false, maxConcurrentRuns=0` | Global executor | 무제한 (글로벌 풀 제한) |

Admin UI (스크립트 상세 → Execution Settings) 또는 REST API로 설정:

```bash
# 최대 3개 동시 실행 설정
curl -X PUT http://host:18080/api/publisher/scripts/my-script/settings \
  -H 'Content-Type: application/json' \
  -d '{"maxConcurrentRuns": 3, "immediate": false}'
```

스크립트별 제한 초과 시 새 run은 PENDING 상태로 대기하며, 이전 run 완료 시 자동으로 다음 run이 실행됩니다.

### Run 상태 생명 주기

Run은 다음 상태를 거칩니다:

| 상태 | 의미 |
|------|------|
| QUEUED | 글로벌 스레드 풀 큐에서 worker 대기 중 |
| PENDING | 스크립트별 동시 실행 한도(`maxConcurrentRuns`)에 도달하여 대기 중 |
| RUNNING | 실행 중 |
| COMPLETED | 성공 완료 |
| FAILED | 에러로 종료 |
| SERVER_RESTARTED | 서버 재시작으로 중단됨 |

**일반적인 전이:**
- `QUEUED → RUNNING → COMPLETED/FAILED` — 정상 흐름
- `PENDING → QUEUED → RUNNING → COMPLETED/FAILED` — 스크립트 한도로 대기한 경우
- `RUNNING → SERVER_RESTARTED` — 실행 중 서버가 강제 종료된 경우

Dashboard의 Active Runs 섹션에는 QUEUED + PENDING + RUNNING이 표시됩니다. 상단 바의 "queued" 카운터는 QUEUED + PENDING 합산입니다.

### 태스크 출력 캡처

TeeBox는 태스크의 stdout/stderr에서 정규식 패턴을 감시하고 매치된 값을 run 메타데이터에 publish합니다. 스크립트 버전별로 output rule을 설정합니다.

**출력 규칙이 있는 스크립트 등록:**

```bash
curl -X POST http://host:18080/api/publisher/scripts \
  -H 'Content-Type: application/json' \
  -d '{
    "scriptId": "deploy",
    "version": "v1",
    "content": "result = SHELL(\"./deploy.sh\")",
    "activate": true,
    "outputRules": [{
      "stream": "stdout",
      "pattern": "Job <(\\d+)> is submitted",
      "captureGroup": 1,
      "publishKey": "jobId",
      "firstOnly": true
    }]
  }'
```

**캡처된 값 조회:**

```bash
curl http://host:18080/api/client/runs/{runId}
# 응답에 포함: "published": {"jobId": "12345", "jobId.detectedAt": 1712345678000}
```

Admin UI의 스크립트 상세 페이지에서도 규칙을 설정할 수 있습니다.

**동작 방식:**
- run에서 생성된 첫 번째 태스크만 감시 (보조 태스크의 오탐 방지)
- 태스크의 stdout.log 파일을 증분 읽기
- 설정 가능한 캡처 그룹으로 라인별 매칭
- `firstOnly: true`는 첫 매치만 publish (권장)
- 캡처된 값은 즉시 저장되며 API와 Admin UI에서 확인 가능

### 스크립트 삭제

스크립트는 soft-delete 방식으로 삭제되며 보존 기간 후 영구 삭제됩니다:

1. **삭제** (Admin UI "Delete" 또는 `DELETE /api/publisher/scripts/{id}`):
   - `deletedAt = now` 로 표시
   - 일반 목록에서 숨김
   - 실행 불가 (resolve 실패)
   - "Deleted Scripts" 섹션에 표시됨

2. **보존 기간** (기본 7일, `propertee.teebox.scriptRetentionMs` 로 설정):
   - 스크립트 데이터는 디스크에 유지
   - 이 기간 동안 복원 가능

3. **복원** (Admin UI "Restore" 또는 `POST /api/publisher/scripts/{id}/restore`):
   - `deletedAt` 초기화
   - 스크립트 활성화

4. **영구 삭제** (자동):
   - 백그라운드 maintenance (60초마다)가 보존 기간 경과한 스크립트를 영구 제거
   - 스크립트 디렉터리와 모든 버전 삭제

---

## 4. 프로세스 관리

### Task 실행 구조

TeeBox는 ProperTee 스크립트의 `SHELL()` 호출마다 외부 프로세스(task)를 생성합니다.

```
TeeBox (Java)
  └── [setsid] /bin/sh <generated command file>
        └── user command
```

- Linux/macOS에서는 `UnixTaskRunner`가 `/bin/sh` 기반으로 task를 실행
- `setsid`가 있으면 별도 process group 격리를 시도
- Windows는 실제 외부 실행 대신 simulated task runner 사용

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
- 일반적인 shell 문법(`;`, `|`, `&&`, 리다이렉션 등)은 허용
- bare command 실행 허용
- 치명적인 시스템 파괴 명령은 차단 (`shutdown`, `reboot`, 위험한 `rm -rf`, `/dev/*` 대상 `dd` 등)
- 제어 문자 (`\n`, `\r`, `\0`) 차단
- 위험 환경 변수 (`LD_PRELOAD`, `DYLD_*`) 차단
- `ENV`, `FILE_*`, `READ_LINES`, `WRITE_*`, `MKDIR`, `LIST_DIR`, `DELETE_FILE`은 TeeBox가 주입하는 `PlatformProvider`를 통해 host 환경에 접근

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
Active (0~24h) -> Archived (24h~7d) -> Purged (7d~)
```
- Active: 전체 로그 (stdout/stderr 최대 200줄), 스레드 정보 유지
- Archived: 스레드 목록 제거, stdout 50줄/stderr 20줄로 축소
- Purged: 디스크에서 삭제

**Task:**
- 동일한 retention 구조 (`propertee.task.retentionMs`, `propertee.task.archiveRetentionMs`)

### dataDir 구조

```
dataDir/
  runs/            # run 상태 JSON 파일
  tasks/           # task 메타데이터, stdout/stderr 로그
  script-registry/ # 등록된 스크립트 버전
```

---

## 7. 모니터링

### Admin Dashboard

`http://<host>:<port>/admin` - 실시간 대시보드

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

**로그 파일 위치**: `propertee.teebox.logDir` 시스템 프로퍼티로 설정 (기본: `logs/`)

```
logs/
  teebox.log              # 현재 로그
  teebox-2026-03-24-1.log.gz  # 롤링된 로그
```

**롤링 정책:**
- 50MB 또는 매일 롤링
- 최대 30개 파일 보관, 이후 자동 삭제

**설정 변경**: `conf/log4j2.xml` 편집, 또는 `PROPERTEE_TEEBOX_LOG4J` 환경 변수로 별도 설정 파일 지정.

**로그 형식:**
```
2026-03-24 10:30:15.123 [INFO ] [AUDIT] ALLOWED runId=run-abc command=/path/script.sh
2026-03-24 10:30:20.456 [ERROR] [RunManager] Run failed: run-abc -- RuntimeException: ...
```

**주요 로그 컴포넌트:**

| 컴포넌트 | 내용 |
|----------|------|
| `TeeBox` | 서버 시작/종료 |
| `AUDIT` | Task 명령 허용/차단 |
| `API` | API 요청 에러 |
| `AdminUI` | Admin UI 에러 |
| `RunManager` | Run 실행 실패, flush/maintenance 에러 |
| `TaskEngine` | Task 인덱스/라이프사이클 에러, 프로세스 그룹 kill 실패 |
| `RunStore` | Run store I/O 에러 |

### 안전한 종료 (Graceful Shutdown)

유지보수를 위해 drain 모드를 시작하면 신규 run을 거부하고 진행 중인 run이 모두 완료된 후 종료합니다:

**Admin UI:** Dashboard → "Graceful Shutdown" 버튼

**REST API:**
```bash
curl -X POST http://host:18080/api/admin/shutdown \
  -H 'Content-Type: application/json' \
  -d '{"maxWaitMs": 300000}'
```

**동작:**
- 즉시 `draining=true` 설정; 모든 신규 `submit()` 호출은 HTTP 409 Conflict 반환
- 백그라운드 스레드가 1초마다 active/queued/pending 카운트 확인
- 모든 카운트가 0이 되면 `System.exit(0)` → JVM shutdown hook 실행
- `maxWaitMs` (기본 5분) 경과 시 강제 종료

**Drain 진행 상황 모니터링:**
```bash
curl http://host:18080/api/admin/drain-status
```

**주의:** drain 취소는 지원하지 않습니다. 시작하면 서버가 종료됩니다.
