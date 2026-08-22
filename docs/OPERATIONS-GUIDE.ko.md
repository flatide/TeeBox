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

기본 GitHub 배포 zip에는 Java runtime이 포함되지 않는다. 배포 서버에서 Linux x86_64 Java 25 runtime archive를 별도로 받아 `/opt/teebox/runtime/` 아래에 풀어서 `runtime/bin/java`가 존재하도록 준비해야 한다.

디렉터리 구조:
```
/opt/teebox/
  bin/run-teebox.sh      # 실행 스크립트
  conf/teebox.properties # 설정 파일
  lib/propertee-teebox.jar
  runtime/bin/java       # 별도 설치한 Java 25 runtime
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
| `dataDir` | (필수) | 데이터 디렉터리 (`runs`, `tasks`, `script-registry`, `users`) |
| `maxRuns` | `64` | 동시 실행 가능한 최대 run 수 |
| `apiToken` | 없음 | 전체 API 공통 Bearer 토큰 (fallback) |
| `clientApiToken` | 없음 | `/api/client` 전용 토큰 |
| `publisherApiToken` | 없음 | `/api/publisher` 전용 토큰 |
| `adminApiToken` | 없음 | `/api/admin` 전용 토큰 |
| `adminUser` | 없음 | 관리 UI 부트스트랩 로그인: 사용자 명단이 비어 있을 때 이 관리자를 시드. 아래 "관리 UI 로그인" 참고 |
| `adminPassword` | 없음 | `adminUser` 의 초기 비밀번호(선택 — 미설정 시 해당 관리자의 첫 로그인 때 설정) |
| `streamRoots` | `dataDir` | `STREAM_FILE` 결과의 허용 루트 (`File.pathSeparator` 로 구분된 디렉토리 목록; Linux/macOS `:`, Windows `;`). 스트리밍 파일 경로는 이 중 하나의 하위로 canonicalize 되어야 함. §3 참고. |
| `webhookEnabled` | `false` | run 종료 webhook 전달 활성화(opt-in). 꺼져 있으면 `callback` 이 실린 submit 은 HTTP 400 으로 거절. §3 참고. |
| `webhookUrlAllowlist` | 없음 | 콜백 URL 의 **콤마 구분** `host[:port]` allowlist (활성화 시 필수 — 미설정이면 모든 콜백 거절). `host` 항목은 임의 포트 허용, `host:port` 는 정확히 일치. |
| `webhookTimeoutMs` | `10000` | webhook 전달 per-POST connect/read 타임아웃(ms). |

**duration / 보존 기간 설정 — 시스템 프로퍼티 전용.** 아래 항목들은 `-D` 시스템 프로퍼티로만
읽히며, **`teebox.properties` 에 적으면 조용히 무시됩니다** — `JAVA_OPTS` 로 지정하세요
(예: `JAVA_OPTS="-Dpropertee.teebox.runRetentionMs=48h"`):

| 시스템 프로퍼티 | 기본값 | 설명 |
|-----------------|--------|------|
| `propertee.teebox.runRetentionMs` | `24h` | run 보관 -> archived 전환 |
| `propertee.teebox.runArchiveRetentionMs` | `7d` | archived -> 완전 삭제(purge) |
| `propertee.teebox.maintenanceIntervalMs` | `1m` | 백그라운드 유지보수 주기 |
| `propertee.teebox.scriptRetentionMs` | `7d` | soft-delete 된 스크립트 보관 -> purge |
| `propertee.task.retentionMs` | `24h` | task 보관 -> archive 전환 |
| `propertee.task.archiveRetentionMs` | `7d` | archived task -> 삭제 |
| `propertee.teebox.logDir` | `logs` | 로그 출력 디렉터리 (§7 참고) |

duration 형식: 숫자만 쓰면 ms, suffix 는 `ms`, `s`, `m`, `h`, `d` (예: `500ms`, `30s`, `1m`, `24h`, `7d`).

환경 변수:
- `PROPERTEE_TEEBOX_CONFIG` - 설정 파일 경로 (기본: `conf/teebox.properties`)
- `JAVA_HOME` - Java 설치 경로
- `JAVA_OPTS` - JVM 옵션 (`-Xmx`, `-D` 등). 시스템 프로퍼티는 설정 파일보다 우선

### 관리 UI 로그인 (멀티유저)

`/admin` HTML UI 는 **API Bearer 토큰과 독립된** 쿠키/세션 로그인을 갖습니다:

- **명단이 없으면 완전 개방.** 사용자 명단이 없고(시드할 `adminUser` 도 없으면) 관리 UI 는
  로그인 없이 열립니다 — 폐쇄망 기본값입니다. 토큰이 없는 API 네임스페이스도 마찬가지로
  무인증입니다. 신뢰 네트워크 밖에 노출하기 전에 이 기본 자세를 점검하세요.
- **명단** — `dataDir/users/users.json`, `{"username": ..., "role": "admin"|"user"}` JSON 배열.
  관리 UI(아래)에서 관리하거나 파일을 직접 편집합니다. 로그인 시마다 새로 읽으므로 수동 편집도
  재시작 없이 반영됩니다. `adminUser`(+선택 `adminPassword`)를 설정하면 명단이 비어 있을 때
  기동 시 관리자 1명이 시드됩니다.
- **비밀번호** — TeeBox가 관리하는 `dataDir/users/credentials.json` 에 PBKDF2 해시로 저장(평문
  미보관). 관리자가 사용자별로 선택합니다: 추가/초기화 시 **초기(임시) 비밀번호를 지정**(즉시
  기록 — 타인이 계정을 선점할 수 없음)하거나, 비워 두면 사용자가 **첫 로그인 때** 직접
  설정합니다(주의: 그 첫 로그인 전까지는 username 을 아는 누구든 계정을 선점할 수 있으니, 완전
  신뢰망이 아니라면 초기 비밀번호 지정을 권장). 로그인한 사용자는 우상단 **Password** 버튼
  (`/admin/password`)으로 본인 비밀번호를 변경할 수 있습니다 — 변경 시 그 사용자의 다른 세션은
  로그아웃됩니다. **`credentials.json` 이 손상되면 모든 UI 로그인이 거부**(fail-closed)되며,
  운영자가 파일을 복구하거나 의도적으로 삭제(= 전체 첫-로그인 재설정)할 때까지 유지됩니다.
- **사용자 관리 UI** — **admin** 으로 로그인하면 **Users** 메뉴(`/admin/users`)가 보입니다:
  사용자 추가(역할 `user`/`admin`, 초기 비밀번호 선택 지정), 역할 변경, 비밀번호 초기화(임시
  비밀번호 선택 지정), 삭제. 역할 변경·초기화·삭제는 대상 사용자의 **살아있는 세션을 즉시
  종료**시킵니다(재로그인 필요). **마지막 남은 admin 은 삭제/강등할 수 없습니다.** 메뉴와
  라우트는 명단 모드에서만 존재합니다 — 개방 모드에는 관리할 사용자가 없습니다.
- **로그인이 막는 것** — **`/admin` UI 전체**, GET 페이지 포함(로그인 페이지만 접근 가능).
  스크립트 소스와 run/task 출력이 미인증 클라이언트에 노출되지 않습니다. `user` 역할은 본인이
  등록한 스크립트만 수정/실행할 수 있고, run 의 kill/cancel 은 **본인이 TeeBox UI 에서 직접
  실행한 run 만** 가능합니다 — API 로 제출된 run(origin `api`)은 본인 스크립트의 run 이라도 UI
  에서 admin 전용입니다. `admin` 은 전부 가능하며 서버 shutdown 과 사용자 관리는 admin
  전용입니다. `/api/*` 네임스페이스는 영향 없음(토큰 기반, 소유권 검사 없음).

### 실행

```bash
./bin/run-teebox.sh
```

### 의존성

- Linux x86_64 Java 25 runtime (`runtime/bin/java`) 또는 시스템 Java 25+
- `setsid` (util-linux) - task process group 격리에 필요. Linux에 기본 포함
- 개발 시 형제 저장소 `../propertee2-java` 필요 (composite build; ProperTee v2 런타임)

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

1. **스크립트 등록**: `POST /api/publisher/scripts` (body: `scriptId`, `content`, 선택 `version` — 공란이면 `"1"`, `"2"`, … 자동 증가 — 및 `activate`). 기존 스크립트에 버전 추가는 `POST /api/publisher/scripts/{scriptId}/versions`
2. **버전 활성화**: `POST /api/publisher/scripts/{scriptId}/activate` (body: `{"version": "..."}`). 기존 스크립트에 추가된 버전은 자동 활성화되지 않음 — 활성화는 명시적 단계(스테이징/롤백 용도)이며, 버전 생략 실행은 최신이 아니라 **활성** 버전을 실행
3. **Run 제출**: `POST /api/client/scripts/{scriptId}/runs` (202 + `runId` 반환; 비동기)
4. **결과 폴링**: `GET /api/client/runs/{runId}` (요약), `.../status`, `.../result`

운영 권장 패턴:
- job submit 스크립트는 job id를 확보하면 바로 종료
- job status polling은 별도 짧은 스크립트로 분리하고 외부 스케줄러나 cron에서 주기 호출
- 하나의 ProperTee run 안에서 background job 후 장시간 `wait` 하거나 polling loop를 유지하는 패턴은 비권장

### Run 제출자 식별 (`X-TeeBox-User`)

run 제출 시 선택적으로 **`X-TeeBox-User`** 요청 헤더로 제출자를 식별할 수 있습니다
(`TeeBoxClient` 실행 메서드의 마지막 `userId` 인자로 전달; `null` = 익명, 헤더 미전송).
TeeBox는 값을 정제(제어문자 제거, trim, 128자 제한)해 run에 `submittedBy` 로 기록합니다.
admin UI에서 제출한 run은 로그인한 운영자의 username이 같은 필드에 기록됩니다.

표시 위치:
- admin **Runs 목록** — 전용 **By** 컬럼 (익명 run은 대시)
- admin **run 상세 페이지** — **Submitted By** 필드
- run JSON — 상태/요약/결과 응답의 `submittedBy`

TeeBox는 제출 시점의 **호출자 IP**도 기록합니다 (`submittedFrom`; `X-Forwarded-For` 가 있으면 첫 홉,
없으면 소켓 피어 — access log와 동일한 해석). run 상세 페이지(**From (IP)**)와 admin run 상세 JSON에
표시되며, **client-facing run 응답에는 노출되지 않습니다**.

이 값은 **표시/감사용 메타데이터**입니다 — 호출자가 넣는 값이며 인증되지 않습니다.
인가 판단에 사용하지 마세요. API 접근 제어는 여전히 Bearer 토큰이 담당합니다.

각 run 은 **origin** 도 기록합니다 — `ui`(TeeBox 관리 UI 제출) 또는 `api`(클라이언트 API 제출).
admin Runs 목록에서 제출자 옆 태그로, run 상세 페이지의 Origin 필드로, run 상태/요약/결과
JSON 의 `origin` 으로 표시되며, 위의 user 역할 kill/cancel 권한 판정에 사용됩니다.

### Runs 목록 필터 (admin UI)

`/admin/runs` 목록은 서버측 필터링을 지원합니다:
- **Include instant** 체크박스 — **기본 해제 = `immediate=true` 스크립트의 run("instant run")을 숨김**
  (고빈도 실행이 목록을 뒤덮는 것을 방지). 체크하면 포함되며, instant run 행에는 `instant` 태그가 붙습니다.
- **검색창** — 스크립트명 또는 run ID의 대소문자 무시 부분일치.
- **Status** 셀렉트·페이지네이션과 자유롭게 조합됩니다.

`GET /api/admin/runs` 도 같은 필터를 받습니다: `instant=exclude|only` (미지정 = 전체),
`q=<substring>`, 그리고 기존 `status`/`offset`/`limit`.

### 스크립트별 동시 실행 제어

> **참고:** 두 가지 별개의 동시 실행 제한이 있습니다:
> - **글로벌 제한** (서버 설정 `propertee.teebox.maxRuns`): 전체 스크립트의 총 동시 실행 수. 글로벌 스레드 풀이 관리합니다.
> - **스크립트별 제한** (스크립트 설정 `maxConcurrentRuns`): 특정 스크립트의 최대 동시 실행 수. 글로벌 제한과 독립적으로 적용됩니다.
>
> Immediate 스크립트는 글로벌 스레드 풀 큐를 완전히 우회하지만 (별도 무제한 스레드 풀 사용), 스크립트 자체의 동시 실행 제한은 적용됩니다.

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
      "maxCaptures": 1
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
- 기본은 run에서 생성된 첫 번째 태스크만 감시 (보조 태스크의 오탐 방지). 규칙의 `taskIndex` 로 run 내 `SHELL()` 실행 순서 기준 태스크를 지정 — `0`(기본) = 첫 번째, `1` = 두 번째, … 스크립트 수정 불필요 (순서는 순차 SHELL 호출에서만 결정적; `multi`/`thread` 병렬 SHELL 은 스케줄링 순서)
- 태스크의 stdout.log 파일을 증분 읽기
- 설정 가능한 캡처 그룹으로 라인별 매칭
- `maxCaptures`(1.18.0+)가 유일한 캡처 knob: `1`(기본) = 첫 매치만, `0` = 무제한(태스크 종료까지 전부), `N` = 최대 N개. 모든 키는 `key`(최신 값), `key.values`(캡처 리스트), `key.count`, `key.detectedAt`(마지막 캡처 시각)으로 게시. 1.18 이전의 `firstOnly` boolean 은 deprecated 별칭으로 계속 허용(`true` → 1, maxCaptures 없는 `false` → 0)
- 캡처된 값은 즉시 저장되며 API와 Admin UI에서 확인 가능

### 대용량 결과 스트리밍 (STREAM_FILE)

6MB JSON 같은 큰 파일을 `READ_LINES` + `JOIN` + `JSON_PARSE` + `return` 으로 엔진에 읽어 리턴하면 전체 페이로드가 스크립트 엔진 힙에 여러 번 복제되고(이후 버퍼링된 JSON 응답에도 다시) 메모리·속도 문제가 생깁니다. 대신 스크립트가 파일을 참조하는 작은 **스트림 디스크립터**만 리턴하면, TeeBox 가 그 파일을 **응답으로 직접 스트리밍**(파싱·전체 버퍼링 없음, O(1) 힙)합니다.

**스크립트에서** — 파일을 머터리얼라이즈하지 말고 `STREAM_FILE(path[, contentType])` 리턴:

```
return STREAM_FILE("/var/lib/teebox/exports/report.json", "application/json")
```

**결과 받기:**

```bash
RUN_ID=...   # 제출 + 종료까지 폴링 후

# 일반 /result 는 경로가 가려진(redact) 디스크립터를 돌려줌(서버 경로 비노출)
curl http://host:18080/api/client/runs/$RUN_ID/result
# → { ..., "stream": true, "resultData": {"stream": true, "contentType": "application/json", "size": 7568901} }

# /result-stream 은 원본 바이트를 그대로 스트리밍 (Content-Type=디스크립터, Content-Length=size)
curl -s -o report.json http://host:18080/api/client/runs/$RUN_ID/result-stream
#   - 스트림 결과가 아니거나 파일이 없으면 HTTP 409
```

임베더블 클라이언트는 `streamRunResult(runId, OutputStream)` 와 한 번에 처리하는 `runAndStream(...)` 을 제공합니다.

**보안 — 허용 루트(반드시 이해):**
- 스트리밍 경로는 설정된 `propertee.teebox.streamRoots`(기본 `dataDir`) 중 하나의 하위로 canonicalize 되어야 합니다. 밖이면 스크립트가 명확한 에러로 실패합니다. `STREAM_FILE` 호출 시점과 스트리밍 직전 두 번 검증(TOCTOU 방지).
- `STREAM_FILE` 은 파일 바이트를 API 클라이언트에 노출하므로, `streamRoots` 는 **필요한 최소 디렉토리만** 지정하세요 — 임의 파일 유출을 막는 유일한 경계입니다.

```properties
# exports 디렉토리(+ 공유 마운트)에서만 스트리밍 허용
propertee.teebox.streamRoots=/var/lib/teebox/exports:/mnt/shared
```

**라이프사이클(참조만):** 디스크립터는 경로만 참조하며 TeeBox 가 파일을 복사·소유하지 않습니다. 파일은 결과 조회 전까지 존재해야 합니다. 디스크립터 자체는 다른 run 결과처럼 아카이브를 넘어 유지되므로(1.15.1+ — 이전 버전은 24h 후 아카이브 시 삭제) 스트림 결과는 **참조 파일이 존재하는 한 run 이 purge 될 때까지** 조회 가능합니다.

### Run 종료 Webhook (callback)

폴링 대신, 클라이언트가 **run 종료 시 TeeBox 가 통지를 POST** 하도록 요청할 수 있습니다. 재시도는 TeeBox 가 책임집니다: run 은 즉시 종료(슬롯 반납)되고, 전달은 디스크 outbox 에서 성공 또는 포기까지 내구적으로 재시도되므로 — 수신 서버가 잠깐 다운돼도 복구 후 콜백을 받습니다. `webhookEnabled=true` + `webhookUrlAllowlist` 로 opt-in.

`callback` 을 실어 제출(webhook 이 꺼져 있거나 URL host 가 allowlist 에 없으면 HTTP 400 거절):

```bash
curl -X POST http://host:18080/api/client/scripts/nightly_export/runs \
  -H 'Content-Type: application/json' \
  -d '{ "props": {}, "callback": { "url": "https://app.internal/teebox/callback" } }'
# -> 202 Accepted (run 은 큐잉되고, 종료 시 콜백 발사)
```

run 이 terminal 에 도달하면 TeeBox 가 해당 URL 로 JSON **SUMMARY** 를 POST:

```json
{ "event": "run.terminal", "runId": "...", "scriptId": "nightly_export", "version": "3",
  "status": "COMPLETED", "endedAt": 1750900000000,
  "errorMessage": null, "resultSummary": "...", "published": { } }
```
헤더: `X-TeeBox-Event: run.terminal`, `X-TeeBox-Delivery: <runId>`.

**전달 보증:**
- **at-least-once** — 수신자는 `X-TeeBox-Delivery`(runId) 키로 **멱등** 처리해야 함. 2xx ack 유실 시 재-POST.
- **재시도** — 비-2xx/전송 실패는 지수 backoff(base 5s, cap 10m)로 최대 12회 재시도 후 **DEAD**(더 이상 시도 안 함).
- **재시작 안전** — outbox 는 `${dataDir}/webhooks/` 에 영속. TeeBox 재시작 후 PENDING 전달 재개, reconcile 이 콜백을 못 받은 최근 terminal run(`SERVER_RESTARTED` 포함)을 재enqueue.
- **`status`** 는 run 의 terminal 상태(`COMPLETED`/`FAILED`/`SERVER_RESTARTED`) — 수신자에서 분기.

**보안(allowlist 가 경계):**
- TeeBox 가 임의 URL 로 POST 하므로 콜백 host 는 **반드시** `webhookUrlAllowlist`(콤마 구분 `host[:port]`)에 있어야 함. scheme 는 `http`/`https`. host 는 submit 시점 + 매 전달 직전 재검증 — allowlist 가 바뀌면 진행 중이던 비허용 host 전달은 즉시 종료(DEAD).

```properties
propertee.teebox.webhookEnabled=true
propertee.teebox.webhookUrlAllowlist=app.internal,app.internal:8443
```

> MVP 범위: payload 는 SUMMARY 고정. HMAC 서명·사용자 인증 헤더·per-script 기본 콜백은 아직 미구현. 더 엄격히 하려면 수신자가 동작 전 `GET /api/client/runs/{runId}` 로 runId 를 재확인하세요.

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

### 버전 삭제

개별 버전은 hard delete 됩니다 — Versions 테이블의 비활성 행 **Delete** 버튼, 또는
`DELETE /api/publisher/scripts/{id}/versions/{version}`:

- **active 버전은 보호됨** — 먼저 다른 버전을 활성화해야 합니다 (버전 지정 없는 클라이언트
  run의 대상이 사라지는 것 방지).
- 스크립트 삭제와 달리 soft-delete/복원 창이 없습니다: 버전 메타데이터와 저장된 내용이 즉시
  제거됩니다 (UI에서는 확인 대화상자를 거침).
- 삭제된 버전을 지정한 run 제출은 거부되며, active 버전 실행에는 영향이 없습니다.

### 스크립트 복제 (지원되는 "rename" 경로)

TeeBox는 의도적으로 in-place rename을 제공하지 않습니다 — 호출 계약(클라이언트가 스크립트
id로 제출)이 깨지고 실행 중 run과 경합하기 때문입니다. 이름을 바꾸려면 복제하세요:

1. **복제** — 스크립트 상세 페이지 하단의 **Duplicate Script** 카드, 또는
   `POST /api/publisher/scripts/{id}/duplicate` (`{"newScriptId": "..."}`). 모든 버전
   (내용 + 설명/라벨/sha256/output rules), active 버전 선택, 실행 설정을 복사하며,
   복제본은 즉시 실행 가능합니다. Admin UI에서는 복제한 사용자가 복제본의 owner가 됩니다.
2. **호출자를 새 id로 전환.**
3. **트래픽이 옮겨진 뒤 구 스크립트 삭제.** run 히스토리는 원본에 남습니다 — 과거 run은
   옛 스크립트 id를 유지합니다.

대상 id 충돌, 미존재 소스, soft-delete된 소스는 명시적 에러로 거부됩니다.

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
- task 엔진 하나를 **모든 동시 run이 공유**하며 수명은 서버가 소유합니다: run이 끝나도 엔진을
  닫지 않으므로(run마다 non-closing view 제공), 무관한 run의 종료가 다른 run의 진행 중
  `SHELL()` task에 영향을 주지 않습니다.

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

### `SLEEP()` 동작 (ProperTee v2 런타임)

ProperTee v2 런타임(TeeBox 1.0.0+)에서 **`SLEEP(ms)` 는 위치와 무관하게 완전 협력적**입니다 —
최상위 문장이든, `loop`/`if`/함수 본문 중첩이든, `multi`/`monitor` 블록 안이든 동일합니다.
sleep 중인 fiber 만 그 자리에서 중단되고, 해당 run 의 다른 `multi` worker 와 `monitor` tick 은
계속 진행되며, 다른 run 에는 아무 영향이 없습니다. (중첩 `SLEEP` 이 blocking `Thread.sleep` 으로
처리되던 v1 런타임의 제약은 더 이상 적용되지 않습니다.)

운영 권장은 그대로 유효합니다: 주기 작업은 하나의 run 이 `loop … SLEEP(...)` 으로 오래 도는
대신 **짧은 스크립트를 외부 스케줄러/cron 으로 주기 호출**하세요 — 장수명 run 은 수명 내내
글로벌 `maxRuns` 풀의 슬롯 하나를 점유하고, 서버 재시작 시 진행 상황을 잃습니다
(`SERVER_RESTARTED`).

---

## 6. 데이터 관리

### Retention (보관 주기)

**Run:**
```
Active (0~24h) -> Archived (24h~7d) -> Purged (7d~)
```
- Active: 전체 로그 (stdout/stderr 최대 200줄), 스레드 정보, 입력 properties, 결과 유지
- Archived: 스레드 목록·입력 properties 제거, stdout 50줄/stderr 20줄로 축소. **run 결과(`resultData`)는 purge 까지 유지** (1.15.1+ — 이전 버전은 아카이브 시 삭제되어 300자 `resultSummary` 만 남았음)
- Purged: 디스크에서 삭제

(기간은 시스템 프로퍼티 `propertee.teebox.runRetentionMs` / `runArchiveRetentionMs` — §1 참고.)

**Task:**
- 동일한 retention 구조 (`propertee.task.retentionMs`, `propertee.task.archiveRetentionMs`)

### dataDir 구조

```
dataDir/
  runs/            # run 상태 JSON 파일 (run 당 <runId>.json 하나)
  tasks/           # task 메타데이터, stdout/stderr 로그 (task 당 task-<id>/ 디렉터리)
  script-registry/ # 등록된 스크립트 버전
  users/           # 관리 UI 로그인 명단 + 비밀번호 해시 (§1 참고)
  webhooks/        # webhook 전달 outbox (webhookEnabled 시에만)
```

**index 파일은 사라졌습니다 (1.14+).** `runs/index.json` 과 `tasks/index.json` 은 더 이상
존재하지 않습니다 — run/task 목록은 인메모리 index 로 서빙되며, 기동 시 데이터 파일에서
재구축됩니다. 운영 시 유의점:

- 구버전이 남긴 레거시 `index.json` 은 **기동 시 자동 삭제**됩니다. 삭제가 불가능하면(권한 등)
  **TeeBox 는 해당 파일명을 명시하며 기동을 거부**합니다 — 수동으로 제거하세요. (오래된 index
  가 남으면, 이후 구버전으로 롤백했을 때 그 사이 기록된 run/task 가 목록에서 영구히 숨습니다.)
- 1.14 미만 버전으로의 롤백은 안전합니다: 구버전은 index 파일이 없으면 데이터 파일에서 새로
  재구축합니다.
- `runs/` 에 임의 파일을 두지 마세요 — 기동 시 모든 `*.json` 을 스캔합니다. 손상되었거나
  무관한 파일은 경고 후 건너뛰며(run 파일의 `runId` 는 파일명과 일치해야 함) 기동을 막지는
  않습니다.

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

**Access 로그:** 전용 `access` 로거가 **`/api/*` 요청당 한 줄**을 기록합니다 — 메서드,
경로(+쿼리), 클라이언트 IP(`X-Forwarded-For` 첫 홉 우선), 응답 상태, 소요 ms:

```
GET /api/client/runs?limit=10 from 127.0.0.1 -> 200 (4ms)
```

요청/응답 **본문은 기록하지 않습니다**(토큰·스크립트 소스·대용량 페이로드가 실릴 수 있음).
`/admin`, `/health`, `/` 는 access 로그 대상이 아닙니다. `log4j2.xml` 의
`<Logger name="access" level="..."/>` 로 독립적으로 조정/무음화할 수 있습니다.

**주요 로그 컴포넌트:**

| 컴포넌트 | 내용 |
|----------|------|
| `TeeBox` | 서버 시작/종료 |
| `access` | `/api/*` 요청당 한 줄 (위 참고) |
| `AUDIT` | Task 명령 허용/차단 |
| `API` | API 요청 에러 |
| `AdminUI` | Admin UI 에러 |
| `RunManager` | Run 실행 실패, flush/maintenance 에러 |
| `TaskEngine` | Task 라이프사이클 에러, 프로세스 그룹 kill 실패, 레거시 index 정리 |
| `RunStore` | Run store I/O 에러, 파싱 불가 run 파일 스킵, 레거시 index 정리 |

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
