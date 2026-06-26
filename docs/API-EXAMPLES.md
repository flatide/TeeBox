# TeeBox API curl Examples

API 테스트용 curl 명령어 모음. 모든 응답은 JSON.

## Setup

```bash
# 기본값
HOST=http://127.0.0.1:18080

# Bearer 토큰을 사용하는 경우
TOKEN=your-token
AUTH="-H 'Authorization: Bearer $TOKEN'"

# 토큰 없는 경우 AUTH=""로 두면 됨
AUTH=""
```

토큰 종류:
- `apiToken` — 모든 namespace 공통 fallback
- `clientApiToken` — `/api/client` 전용
- `publisherApiToken` — `/api/publisher` 전용
- `adminApiToken` — `/api/admin` 전용

namespace 별 토큰이 설정되면 fallback은 무시됩니다.

---

## 1. Publisher API — 스크립트 관리

### 1.1 스크립트 등록

```bash
curl -X POST $HOST/api/publisher/scripts \
  -H 'Content-Type: application/json' \
  -d '{
    "scriptId": "hello",
    "version": "v1",
    "content": "PRINT(\"Hello, World!\")\n",
    "description": "first script",
    "labels": ["demo"],
    "activate": true
  }'
```

### 1.2 출력 캡처 규칙과 함께 등록

```bash
curl -X POST $HOST/api/publisher/scripts \
  -H 'Content-Type: application/json' \
  -d '{
    "scriptId": "deploy",
    "version": "v1",
    "content": "result = SHELL(\"./deploy.sh\")\nPRINT(result.value)\n",
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

### 1.3 스크립트 목록

```bash
curl $HOST/api/publisher/scripts
```

### 1.4 스크립트 조회

```bash
curl $HOST/api/publisher/scripts/hello
```

### 1.5 새 버전 추가

```bash
curl -X POST $HOST/api/publisher/scripts/hello/versions \
  -H 'Content-Type: application/json' \
  -d '{
    "scriptId": "hello",
    "version": "v2",
    "content": "PRINT(\"Hello v2!\")\n",
    "activate": true
  }'
```

### 1.6 버전 활성화

```bash
curl -X POST $HOST/api/publisher/scripts/hello/activate \
  -H 'Content-Type: application/json' \
  -d '{"version": "v1"}'
```

### 1.7 실행 설정 변경

```bash
curl -X PUT $HOST/api/publisher/scripts/hello/settings \
  -H 'Content-Type: application/json' \
  -d '{
    "maxConcurrentRuns": 3,
    "immediate": false
  }'
```

### 1.8 스크립트 삭제 (soft-delete)

```bash
curl -X DELETE $HOST/api/publisher/scripts/hello
```

### 1.9 삭제 복원

```bash
curl -X POST $HOST/api/publisher/scripts/hello/restore
```

---

## 2. Client API — Run 제출 및 조회

### 2.1 Run 제출

```bash
curl -X POST $HOST/api/client/scripts/hello/runs \
  -H 'Content-Type: application/json' \
  -d '{
    "props": {"name": "Alice", "count": 3},
    "maxIterations": 1000,
    "warnLoops": false
  }'
```

응답 예:
```json
{
  "runId": "run-20260508-103022-abc",
  "scriptId": "hello",
  "version": "v1",
  "status": "QUEUED",
  "createdAt": 1746701422000
}
```

### 2.2 특정 버전 실행

```bash
curl -X POST $HOST/api/client/scripts/hello/runs \
  -H 'Content-Type: application/json' \
  -d '{
    "version": "v2",
    "props": {}
  }'
```

### 2.3 Run 목록

```bash
curl $HOST/api/client/runs

# 상태 필터
curl "$HOST/api/client/runs?status=RUNNING"

# 페이지네이션
curl "$HOST/api/client/runs?offset=0&limit=20"
```

### 2.4 Run 상세

```bash
curl $HOST/api/client/runs/run-20260508-103022-abc
```

### 2.5 Run 상태만 (가벼운 폴링용)

```bash
curl $HOST/api/client/runs/run-20260508-103022-abc/status
```

### 2.6 Run 결과만

```bash
curl $HOST/api/client/runs/run-20260508-103022-abc/result
```

### 2.7 대용량 결과 스트리밍 (STREAM_FILE)

6MB JSON 같은 큰 파일을 `READ_LINES`+`JOIN`+`JSON_PARSE` 후 리턴하면 스크립트 엔진 힙에 전체가 여러 번 복제되어 메모리·속도 문제가 생깁니다. 스크립트에서 **`return STREAM_FILE("/path/to/big.json", "application/json")`** 로 디스크립터만 리턴하면, TeeBox 가 그 파일을 **응답으로 직접 스트리밍**(파싱·전체 버퍼 없음)합니다.

> 파일 경로는 **허용 루트 내**여야 합니다 — `propertee.teebox.streamRoots`(`File.pathSeparator` 로 구분된 목록, 기본 `dataDir`). 밖이면 스크립트가 실패합니다.

```bash
# 1) STREAM_FILE 을 리턴하는 스크립트 등록 (경로는 streamRoots 하위여야 함)
curl -s -X POST $HOST/api/publisher/scripts $AUTH \
  -H 'Content-Type: application/json' \
  -d '{
    "scriptId": "export_report",
    "content": "return STREAM_FILE(\"/data/exports/report.json\", \"application/json\")\n",
    "activate": true
  }'

# 2) Run 제출 → 완료까지 폴링 (아래 2.10 패턴)
RUN_ID=$(curl -s -X POST $HOST/api/client/scripts/export_report/runs $AUTH \
  -H 'Content-Type: application/json' -d '{"props":{}}' \
  | grep -o '"runId":"[^"]*"' | cut -d'"' -f4)

# 3) 일반 /result 는 경로가 가려진 디스크립터를 돌려줌(서버 경로 비노출)
curl $HOST/api/client/runs/$RUN_ID/result
# → { ..., "stream": true, "resultData": {"stream": true, "contentType": "application/json", "size": 7568901} }

# 4) /result-stream 으로 원본 바이트를 그대로 다운로드 (파싱·버퍼링 없음)
curl -s -o report.json $HOST/api/client/runs/$RUN_ID/result-stream
#   - Content-Type: 디스크립터의 contentType, Content-Length: 파일 크기
#   - 스트림 결과가 아니거나 파일이 없으면 HTTP 409
```

### 2.8 Run stdout / stderr (스크립트 PRINT 출력)

```bash
# 캡처된 stdout (최근 MAX_LOG_LINES 줄, 기본 200줄 — ring buffer). RUNNING 중에도 조회 가능
curl $HOST/api/client/runs/run-20260508-103022-abc/stdout
# → { ..., "stream": "stdout", "lines": ["line one", "line two 42"], "lineCount": 2 }
curl $HOST/api/client/runs/run-20260508-103022-abc/stderr
```

### 2.9 Task 요약

```bash
curl $HOST/api/client/runs/run-20260508-103022-abc/tasks-summary
```

### 2.10 Run 완료까지 폴링

```bash
RUN_ID=run-20260508-103022-abc
while true; do
  STATUS=$(curl -s $HOST/api/client/runs/$RUN_ID/status | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
  echo "Status: $STATUS"
  case "$STATUS" in
    COMPLETED|FAILED|SERVER_RESTARTED) break ;;
  esac
  sleep 1
done
curl $HOST/api/client/runs/$RUN_ID/result
```

### 2.11 Webhook 콜백으로 제출 (폴링 대신)

서버에 webhook 이 켜져 있으면(`webhookEnabled=true` + `webhookUrlAllowlist`) 제출 시 `callback` 을 실어 run 종료 통지를 받을 수 있습니다. TeeBox 가 2xx 받을 때까지 내구적으로 재시도합니다.

```bash
# 콜백과 함께 제출 (webhook 꺼짐 또는 host 미허용 시 400)
curl -X POST $HOST/api/client/scripts/nightly_export/runs \
  -H 'Content-Type: application/json' \
  -d '{ "props": {}, "callback": { "url": "https://app.internal/teebox/callback" } }'
# → 202 Accepted

# run 종료 시 TeeBox 가 콜백 URL 로 POST (헤더: X-TeeBox-Event, X-TeeBox-Delivery: <runId>)
# 본문(SUMMARY):
# { "event":"run.terminal", "runId":"...", "scriptId":"nightly_export", "version":"3",
#   "status":"COMPLETED", "endedAt":1750900000000,
#   "errorMessage":null, "resultSummary":"...", "published":{} }
```

- 전달은 **at-least-once** — 수신자를 `X-TeeBox-Delivery`(runId) 기준 **멱등**하게.
- 비-2xx/실패는 backoff 재시도(최대 12회) 후 **DEAD**. 재시작에도 outbox(`dataDir/webhooks/`)에서 재개.
- 자세한 서버 설정은 운영 가이드 "Run 종료 Webhook (callback)" 참고.

---

## 3. Admin API — 시스템 관리

### 3.1 헬스 체크

```bash
curl $HOST/api/admin/health
```

응답 예:
```json
{
  "healthy": true,
  "uptimeMs": 3600000,
  "activeRuns": 2,
  "queuedRuns": 5,
  "maxConcurrentRuns": 64,
  "completedRuns": 142
}
```

### 3.2 시스템 정보

```bash
curl $HOST/api/admin/system
```

### 3.3 모든 Run 조회 (필터/페이지네이션)

```bash
curl $HOST/api/admin/runs

# 상태 + scriptId 필터
curl "$HOST/api/admin/runs?status=RUNNING&scriptId=deploy&offset=0&limit=50"
```

### 3.4 Run 상세 (admin 전체 정보 포함)

```bash
curl $HOST/api/admin/runs/run-20260508-103022-abc
```

### 3.5 Thread 목록

```bash
curl $HOST/api/admin/runs/run-20260508-103022-abc/threads
```

### 3.6 Run의 모든 Task

```bash
curl $HOST/api/admin/runs/run-20260508-103022-abc/tasks
```

### 3.7 Run의 모든 Task Kill

```bash
curl -X POST $HOST/api/admin/runs/run-20260508-103022-abc/kill-tasks
```

### 3.8 Task 목록

```bash
curl $HOST/api/admin/tasks

# 필터
curl "$HOST/api/admin/tasks?runId=run-xxx&status=RUNNING"
```

### 3.9 Task 상세

```bash
curl $HOST/api/admin/tasks/task-20260508-103030-xyz
```

### 3.10 Task Kill

```bash
curl -X POST $HOST/api/admin/tasks/task-20260508-103030-xyz/kill
```

### 3.11 Graceful Shutdown 시작

```bash
# 기본 5분 타임아웃
curl -X POST $HOST/api/admin/shutdown \
  -H 'Content-Type: application/json' \
  -d '{}'

# 타임아웃 지정 (10분)
curl -X POST $HOST/api/admin/shutdown \
  -H 'Content-Type: application/json' \
  -d '{"maxWaitMs": 600000}'
```

### 3.12 Drain 상태 조회

```bash
curl $HOST/api/admin/drain-status
```

응답 예:
```json
{
  "draining": true,
  "drainStartedAt": 1746701422000,
  "activeRuns": 2,
  "queuedRuns": 0
}
```

---

## 4. 인증 토큰 사용

### 4.1 단일 토큰 (모든 namespace 공통)

```bash
TOKEN=secret123

curl $HOST/api/client/runs -H "Authorization: Bearer $TOKEN"
curl $HOST/api/admin/health -H "Authorization: Bearer $TOKEN"
curl $HOST/api/publisher/scripts -H "Authorization: Bearer $TOKEN"
```

### 4.2 namespace 별 토큰

`teebox.properties`에 별도 토큰 설정:

```properties
propertee.teebox.clientApiToken=client-secret
propertee.teebox.publisherApiToken=publisher-secret
propertee.teebox.adminApiToken=admin-secret
```

각 namespace에 해당 토큰 사용:

```bash
curl $HOST/api/client/runs -H "Authorization: Bearer client-secret"
curl $HOST/api/publisher/scripts -H "Authorization: Bearer publisher-secret"
curl $HOST/api/admin/health -H "Authorization: Bearer admin-secret"
```

### 4.3 인증 실패

| 상태 코드 | 의미 |
|----------|------|
| 401 | 토큰 누락 또는 잘못된 토큰 |
| 403 | namespace에 대한 권한 부족 |

---

## 5. 일반적인 응답 코드

| 코드 | 의미 |
|------|------|
| 200 | 성공 |
| 201 | 생성됨 (스크립트 등록) |
| 202 | 접수됨 (Run 제출) |
| 400 | 잘못된 요청 (validation 실패) |
| 401 | 인증 실패 |
| 403 | 권한 없음 |
| 404 | 리소스 없음 |
| 409 | Conflict (drain 중 submit 등) |
| 500 | 서버 에러 |

---

## 6. 일반적인 워크플로 예시

### 6.1 스크립트 등록 → 실행 → 결과 확인

```bash
HOST=http://127.0.0.1:18080

# 1. 등록
curl -X POST $HOST/api/publisher/scripts \
  -H 'Content-Type: application/json' \
  -d '{
    "scriptId": "calc",
    "version": "v1",
    "content": "result = ::a + ::b\nPRINT(result)\nreturn result\n",
    "activate": true
  }'

# 2. 실행
RUN=$(curl -sX POST $HOST/api/client/scripts/calc/runs \
  -H 'Content-Type: application/json' \
  -d '{"props": {"a": 10, "b": 20}}')

RUN_ID=$(echo $RUN | grep -o '"runId":"[^"]*"' | cut -d'"' -f4)
echo "Submitted: $RUN_ID"

# 3. 완료 대기
while true; do
  STATUS=$(curl -s $HOST/api/client/runs/$RUN_ID/status | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
  [ "$STATUS" = "COMPLETED" ] || [ "$STATUS" = "FAILED" ] && break
  sleep 0.5
done

# 4. 결과
curl $HOST/api/client/runs/$RUN_ID/result
```

### 6.2 SHELL 스크립트 + 출력 캡처

```bash
# SHELL 호출 + jobId 캡처 규칙 등록
curl -X POST $HOST/api/publisher/scripts \
  -H 'Content-Type: application/json' \
  -d '{
    "scriptId": "submit-job",
    "version": "v1",
    "content": "result = SHELL(\"echo Job \\<12345\\> is submitted\")\nPRINT(result.value)\n",
    "activate": true,
    "outputRules": [{
      "stream": "stdout",
      "pattern": "Job <(\\d+)> is submitted",
      "captureGroup": 1,
      "publishKey": "jobId",
      "firstOnly": true
    }]
  }'

# 실행
RUN=$(curl -sX POST $HOST/api/client/scripts/submit-job/runs \
  -H 'Content-Type: application/json' -d '{"props": {}}')
RUN_ID=$(echo $RUN | grep -o '"runId":"[^"]*"' | cut -d'"' -f4)

# 완료 후 published 값 확인
sleep 2
curl $HOST/api/client/runs/$RUN_ID
# response includes: "published": {"jobId": "12345", "jobId.detectedAt": ...}
```

### 6.3 Drain 후 종료

```bash
# 1. Drain 시작
curl -X POST $HOST/api/admin/shutdown \
  -H 'Content-Type: application/json' \
  -d '{"maxWaitMs": 300000}'

# 2. Drain 진행 모니터링
while true; do
  STATUS=$(curl -s $HOST/api/admin/drain-status)
  echo $STATUS
  ACTIVE=$(echo $STATUS | grep -o '"activeRuns":[0-9]*' | cut -d: -f2)
  QUEUED=$(echo $STATUS | grep -o '"queuedRuns":[0-9]*' | cut -d: -f2)
  if [ "$ACTIVE" = "0" ] && [ "$QUEUED" = "0" ]; then
    echo "Drained, server will exit"
    break
  fi
  sleep 2
done
```

---

## 7. JSON 도구 (jq) 활용

`jq`가 설치되어 있다면 응답 파싱이 훨씬 편합니다:

```bash
# Run ID만 추출
RUN_ID=$(curl -sX POST $HOST/api/client/scripts/hello/runs \
  -H 'Content-Type: application/json' -d '{}' | jq -r .runId)

# 상태만 확인
curl -s $HOST/api/client/runs/$RUN_ID/status | jq -r .status

# 활성 run의 ID 목록
curl -s "$HOST/api/admin/runs?status=RUNNING" | jq -r '.[] | .runId'

# Published 값
curl -s $HOST/api/client/runs/$RUN_ID | jq .published

# 헬스 체크 + 종합 상태
curl -s $HOST/api/admin/health | jq '{healthy, active: .activeRuns, queued: .queuedRuns}'
```
