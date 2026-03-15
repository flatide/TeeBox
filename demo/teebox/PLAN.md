# ProperTee HPC Service Evolution Plan

TeeBox를 HPC 망 내 원격 스크립트 실행 서비스로 발전시키기 위한 단계별 계획.

**목표:** 원격 호출에 응답하여 ProperTee 스크립트를 실행하고, 구조화된 결과를 반환하며, 실행 상태를 실시간 모니터링할 수 있는 안정적이고 견고한 서비스.

**원칙:**
- 과도한 추상화 없이, 안정성과 모니터링 가능성을 우선
- UI/조회 경로가 서버 상태를 변경해서는 안 됨
- 운영자(operator)가 판단하고 행동하는 모델 — 시스템은 정보를 제공하고, 자동 개입은 최소화

---

## Phase 0: 운영 결함 정리

현재 발견된 경로/동작 버그 수정. 다른 작업의 전제 조건.

### 0-1. dataDir/tasks/tasks 경로 중복 ✅ (수정 완료)

**문제:** RunManager가 `new File(dataDir, "tasks")`를 TaskEngine에 전달 → TaskEngine이 내부적으로 다시 `/tasks` 추가 → `dataDir/tasks/tasks/` 이중 경로.

**수정:** RunManager에서 `dataDir`를 직접 전달하도록 변경. TaskEngine이 유일하게 `/tasks` 경로를 관리.

**파일:** `RunManager.java`

### 0-2. refreshTask()에서 "lost" 상태 디스크 기록 방지 ✅ (수정 완료)

**문제:** Admin UI 새로고침 시 `refreshTask()` → `finalizeExitedTask()` → "lost" 기록 → 정상 태스크가 영구 lost 처리.

**수정:** `refreshTask()`에서 "lost" 상태는 디스크에 기록하지 않음. "lost" 영속화는 `init()` (서버 재시작) 시에만 수행.

**파일:** `TaskEngine.java`

### 0-3. execute() 이중 saveMeta 레이스 ✅ (수정 완료)

**문제:** `execute()` 내 첫 번째 `saveMeta()`가 pid=0/status=starting으로 기록 → Admin UI가 이 시점에 로드 → 이후 정상 데이터를 덮어씀.

**수정:** 프로세스 완전 초기화 후 한 번만 `saveMeta()` 호출.

**파일:** `TaskEngine.java`

### 0-4. default TaskEngine의 shared baseDir 재분류 문제 ✅ (수정 완료)

**문제:** `BuiltinFunctions.createDefaultTaskEngine()`이 호출마다 새 `hostId`로 `TaskEngine`을 생성하고 `init()`을 호출한다 (`BuiltinFunctions.java:726-735`). 동일 baseDir(`/tmp/propertee-java-task-engine`)을 공유하므로, 두 번째 interpreter가 생성될 때 첫 번째의 running task가 `"detached"`로 재분류된다 (`TaskEngine.java:253-254`). TeeBox server는 host-supplied engine을 쓰므로 무관하지만, CLI나 임베딩 경로에서 깨진다.

**정책:** CLI REPL은 단순 목적의 사용이므로 task에 대한 별도 관리는 중요하지 않다. 따라서 default 경로에서 복잡한 shared engine 관리를 도입할 필요 없이, `init()` 호출을 제거하여 재분류 자체를 방지한다. 서비스 용도에서는 항상 host가 TaskEngine을 주입하고 `init()`도 host가 명시적으로 호출하는 모델.

**수정:**

```java
private static TaskEngine createDefaultTaskEngine() {
    String baseDir = System.getProperty("propertee.task.baseDir");
    if (baseDir == null || baseDir.trim().length() == 0) {
        baseDir = new File(System.getProperty("java.io.tmpdir"),
            "propertee-java-task-engine").getAbsolutePath();
    }
    String hostId = "cli-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date()) +
        "-" + Integer.toHexString((int) (System.nanoTime() & 0xffff));
    return new TaskEngine(baseDir, hostId);
    // init() 호출하지 않음 — CLI 경로에서는 기존 task 재분류 불필요
}
```

`init()`의 역할(기존 running task 상태 정리)은 서버 재시작 시 필요한 로직이므로, 서비스 경로(`RunManager`)에서만 호출. CLI/임베딩 경로에서는 자기가 만든 task만 관리하면 충분.

**파일:** `BuiltinFunctions.java`

### 0-5. admin kill의 run 상태 전이 불일치 ✅ (수정 완료)

**문제:** 단일 task kill(`RunManager.killTask`)은 run을 즉시 `FAILED`로 바꾸지만 (`RunManager.java:148-153`), 전체 run task kill(`RunManager.killRunTasks`)은 run 상태를 변경하지 않는다 (`RunManager.java:157-159`). 또한 단일 task kill은 해당 task가 run에서 어떤 역할인지(detached/optional 등) 구분하지 않고 무조건 run을 실패 처리한다.

**정책:** run 상태는 **스크립트 실행 결과만 반영**한다. admin이 task를 kill하는 것은 운영 행위이지 스크립트 실행 실패가 아니다. `04_detached_task.pt`처럼 run이 이미 `COMPLETED`인데 detached task를 kill하면 완료된 run이 `FAILED`로 뒤바뀌는 것은 잘못된 설계.

**수정:** `killTask()`와 `killRunTasks()` 모두에서 run 상태 전이를 제거. task 상태만 `"killed"`로 변경.

```java
// RunManager — run 상태 전이 없이 task만 kill
public boolean killTask(String taskId) {
    return taskEngine.killTask(taskId);
}

public int killRunTasks(String runId) {
    return taskEngine.killRun(runId);
}
```

스크립트가 `WAIT_TASK`로 해당 task를 기다리고 있었다면, task가 `"killed"` 상태로 반환되고 스크립트가 결과를 보고 판단한다. Admin UI에서는 run과 task 상태를 독립적으로 표시 — run이 `COMPLETED`인데 하위 task가 `killed`일 수 있음 (detached 케이스).

**파일:** `RunManager.java`

---

## Phase 1: 안정성 확보 (기반 강화)

현재 구조를 유지하면서 운영 안정성에 직접적으로 영향을 미치는 항목을 수정.

### 1-1. TaskEngine.execute() 동기화 범위 축소 ✅ (수정 완료)

**문제:** `execute()` 전체가 `synchronized` — 서버 전체에서 한 번에 하나의 프로세스만 생성 가능. multi 블록 내 병렬 SHELL 호출도 직렬화됨.

**수정:** taskId 생성(AtomicInteger)만 동기화하고 프로세스 생성은 lock-free로 변경.

```java
// Before
public synchronized Task execute(TaskRequest request) { ... }

// After
public Task execute(TaskRequest request) {
    String taskId = nextTaskId();  // AtomicInteger — already thread-safe
    // ... 나머지는 per-task 디렉토리 기반이므로 lock 불필요
}
```

**파일:** `TaskEngine.java`

### 1-2. 폴링 효율 개선 ✅ (수정 완료)

**문제:** `waitForCompletion`이 100ms 간격으로 `ps` 서브프로세스를 호출. 동시 WAIT_TASK 10개면 초당 100회 ps 호출.

**수정:** 지수 백오프 적용.

```java
public Task waitForCompletion(String taskId, long timeoutMs) throws InterruptedException {
    long start = System.currentTimeMillis();
    long pollMs = 100;
    while (true) {
        Task task = getTask(taskId);
        if (task == null) return null;
        refreshTask(task);
        if (!task.alive) return task;
        if (timeoutMs > 0 && (System.currentTimeMillis() - start) > timeoutMs) return task;
        Thread.sleep(pollMs);
        pollMs = Math.min(pollMs * 2, 2000);  // 100 → 200 → 400 → ... → 2000ms
    }
}
```

**파일:** `TaskEngine.java`

### 1-3. Task.status를 enum으로 전환 ✅ (수정 완료)

**문제:** `task.status`가 String ("running", "completed" 등). 오타나 잘못된 비교 가능.

**수정:**

```java
public enum TaskStatus {
    STARTING, RUNNING, COMPLETED, FAILED, KILLED, LOST, DETACHED
}
```

Gson은 enum을 자동으로 직렬화/역직렬화하므로 기존 meta.json과의 호환성 유지 방안 필요 (소문자 변환 또는 마이그레이션).

**파일:** `Task.java`, `TaskEngine.java`, `BuiltinFunctions.java` 내 status 비교 전체

---

## Phase 2: 서비스 기능 강화

HPC 클라이언트가 원격으로 호출하여 사용할 수 있는 수준으로 API와 결과 처리를 강화.

### 2-1. 구조화된 결과 반환 — 명시적 계약 ✅ (수정 완료)

**문제:** `resultSummary`가 300자로 잘린 문자열. HPC 클라이언트가 스크립트 실행 결과를 프로그래밍적으로 소비할 수 없음.

**설계 원칙:** `interpreter.variables` 전체 덤프는 부적절 — 내부 임시 변수까지 노출되고, 스크립트 작성자가 "무엇이 결과인지" 의도를 표현할 수 없음.

**판정 기준 — "return 발생 여부"는 값이 아니라 플래그로:**

ProperTee에서 `return` 없이 종료된 스크립트와 `return {}`을 명시한 스크립트는 동일하게 빈 객체를 반환한다 (`ProperTeeInterpreter.java` RootStepper, `visitReturnStmt`). `PRINT()` 등 내장 함수도 빈 객체를 반환. 따라서 반환값이 빈 객체인지 검사하는 방식은 의도적 `return {}`을 오판한다.

해결: RootStepper에 `hasExplicitReturn` 플래그를 추가하여, `ReturnException`이 발생했을 때만 true로 설정.

**수정:**

```java
// RootStepper 내부
private boolean hasExplicitReturn = false;

// catch (ReturnException e) 블록에서
hasExplicitReturn = true;
result = e.getValue();

// 외부에서 접근
public boolean hasExplicitReturn() { return hasExplicitReturn; }
```

**API shape — `createRootStepper()` 반환형 변경:**

현재 `createRootStepper()`는 `Stepper`를 반환한다 (`ProperTeeInterpreter.java:176`). RunManager도 `Stepper mainStepper`로 받는다 (`RunManager.java:219`). `hasExplicitReturn()`은 `RootStepper`에만 존재하므로 접근 방법을 정해야 한다.

선택지:
1. ~~다운캐스트~~ — `(RootStepper)` 캐스트는 내부 클래스라 외부에서 불가, 타입 안전성도 나쁨
2. **`createRootStepper()` 반환형을 `RootStepper`로 변경** — RootStepper를 public inner class 또는 별도 클래스로 승격
3. ~~별도 실행 결과 객체~~ — Scheduler가 결과를 감싸는 `ExecutionResult(value, hasExplicitReturn)`을 반환

**결정: 방법 2** — `RootStepper`를 public inner class로 승격하고, `createRootStepper()`의 반환형을 `RootStepper`로 변경. RootStepper는 `Stepper`를 구현하므로 Scheduler에 그대로 전달 가능. RunManager만 `RootStepper` 타입으로 받아서 `hasExplicitReturn()` 호출.

```java
// ProperTeeInterpreter.java
public RootStepper createRootStepper(ProperTeeParser.RootContext ctx) {
    return new RootStepper(this, ctx);
}

// RunManager.java
ProperTeeInterpreter.RootStepper rootStepper = visitor.createRootStepper(tree);
Object result = scheduler.run(rootStepper);  // Stepper로 업캐스트
boolean hasReturn = rootStepper.hasExplicitReturn();
```

```java
// RunManager.executeRun()
Object outputData;
if (hasReturn) {
    outputData = result;  // return 값을 그대로 사용 (빈 객체여도 유효)
} else {
    // return이 없었으면 지정 변수 확인 (변수명: "result")
    Object designated = visitor.variables.get("result");
    outputData = designated;  // null이면 결과 없음
}
markRunCompleted(run, outputData);

// API 응답
GET /api/runs/{runId}
{
  "run": {
    "status": "COMPLETED",
    "hasExplicitReturn": true,
    "resultData": { "x": 42, "list": [1,2,3] }
  }
}
```

스크립트 작성자가 반환 의도를 명확히 표현:
```
// 방법 1: return 사용 (resultData = return 값, hasExplicitReturn = true)
return computeResult(data)

// 방법 2: 지정 변수 (resultData = result 변수 값, hasExplicitReturn = false)
result = computeResult(data)

// 방법 3: 둘 다 없으면 resultData = null
PRINT("fire and forget")
```

**fallback 변수명:** `result` 하나로 고정. 여러 후보를 탐색하면 예측 불가능성이 증가하므로, 단일 계약으로 문서화.

**파일:** `ProperTeeInterpreter.java` (RootStepper), `RunManager.java`, `RunInfo.java`, `TeeBoxServer.java`

### 2-2. 인증 ✅ (수정 완료)

**문제:** API가 완전 개방. HPC 망 내에서도 최소한의 접근 제어 필요.

**수정:** 공유 토큰 기반 인증. 시스템 프로퍼티로 토큰 설정, API 요청에 Bearer 토큰 필수.

```java
// TeeBoxConfig
public String apiToken;  // propertee.teebox.apiToken (null이면 인증 비활성화)

// ApiHandler.handle()
if (config.apiToken != null) {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    if (auth == null || !auth.equals("Bearer " + config.apiToken)) {
        writeJson(exchange, 401, errorMap("Unauthorized"));
        return;
    }
}
```

Admin UI는 별도 정책 (같은 토큰 또는 로컬 접근만 허용).

**파일:** `TeeBoxConfig.java`, `TeeBoxServer.java`

### 2-3. 저장소 레벨 페이징 및 보존 정책 ✅ (수정 완료)

**문제:**
- `listRuns()`, `listTasks()`가 전체를 메모리에 로드. 대량 데이터 시 OOM 및 느린 응답.
- 완료된 태스크 디렉토리가 무한 축적.
- API 레이어에서 offset/limit만 추가하면 RunManager/TaskEngine은 여전히 전체를 로드 후 잘라내므로 근본 해결이 안 됨.
- 디렉토리 스캔만으로는 `status`, `runId` 필터를 지원할 수 없음 — 이 정보는 `meta.json` 안에만 존재.

**수정 — lightweight index + lazy loading:**

디렉토리 스캔으로 ID 목록만 만드는 것은 정렬/페이징에는 충분하지만, `status=RUNNING`이나 `runId=xxx` 필터에는 각 meta.json을 열어야 한다. 해결: TaskEngine이 관리하는 경량 인덱스 파일.

```java
// tasks/index.json — TaskEngine이 유지하는 인덱스
[
  { "taskId": "001", "runId": "run-abc", "status": "completed", "startTime": 1709..., "endTime": 1709... },
  { "taskId": "002", "runId": "run-abc", "status": "running",   "startTime": 1709... },
  { "taskId": "003", "runId": "run-def", "status": "killed",    "startTime": 1709..., "endTime": 1709... }
]
```

**인덱스 갱신 시점:**
- `execute()` — 새 항목 추가
- `saveMeta()` — status/endTime 변경 시 해당 항목 갱신
- `purge` — 삭제 시 항목 제거

**동시성/원자성:**

Phase 1-1에서 `execute()` 동기화를 풀면 여러 스레드가 동시에 인덱스를 갱신할 수 있다. naive한 전체 rewrite는 lost update/파일 손상을 일으킨다.

원칙:
1. **Atomic write via tmp + move** — 인덱스를 임시 파일(`index.json.tmp`)에 쓴 뒤 교체. `Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)` 우선 사용. `AtomicMoveNotSupportedException` 발생 시 `REPLACE_EXISTING`만으로 fallback (non-atomic이지만 tmp 완전 쓰기 후 교체이므로 truncation 방지).
2. **단일 lock** — 인덱스 읽기-수정-쓰기는 `synchronized(indexLock)` 블록으로 보호. meta.json 개별 쓰기와 달리 인덱스는 공유 자원이므로 lock 필요.
3. **손상 시 재구성** — `init()` 또는 인덱스 파싱 실패 시 전체 task 디렉토리를 스캔하여 인덱스를 재생성. 인덱스는 meta.json의 파생물이므로 언제든 재구성 가능.

```java
private final Object indexLock = new Object();

private void updateIndex(String taskId, String runId, String status, Long startTime, Long endTime) {
    synchronized (indexLock) {
        List<TaskIndexEntry> entries = loadIndex();  // 파싱 실패 시 rebuildIndex()
        // taskId로 찾아 갱신 또는 추가
        writeIndexAtomic(entries);
    }
}

private void writeIndexAtomic(List<TaskIndexEntry> entries) {
    File tmp = new File(tasksDir, "index.json.tmp");
    Path target = new File(tasksDir, "index.json").toPath();
    // write to tmp, flush, close
    try {
        Files.move(tmp.toPath(), target,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
    }
}

private List<TaskIndexEntry> rebuildIndex() {
    // 전체 task 디렉토리 스캔 → meta.json에서 인덱스 재구성
}
```

**조회 경로:**
```java
// 인덱스만으로 필터 + 페이징
public List<TaskIndexEntry> queryTasks(String runId, String status, int offset, int limit) {
    List<TaskIndexEntry> index = loadIndex();
    // runId, status 필터 적용
    // offset/limit 적용
    return filtered;
}

// 상세 조회만 meta.json 로드
public Task getTask(String taskId) { ... }  // 기존과 동일
```

**Run 저장소도 동일 패턴 적용:**

현재 `RunStore.loadAll()`은 모든 run JSON을 전체 로드한다 (`RunStore.java:66`). Task와 동일한 병목이므로 같은 인덱스 패턴 적용:

```java
// runs/index.json — RunStore가 유지하는 인덱스
[
  { "runId": "run-abc", "status": "COMPLETED", "createdAt": 1709..., "endedAt": 1709..., "scriptPath": "calc.pt" },
  { "runId": "run-def", "status": "RUNNING",   "createdAt": 1709..., "scriptPath": "sim.pt" }
]
```

RunStore의 `save()` 시 인덱스 갱신 (동일한 atomic write via tmp + move + lock 패턴). `loadAll()`을 `query(status, offset, limit)`로 대체. 개별 `load(runId)`는 기존과 동일.

**손상 시 재구성:** task 인덱스와 동일 정책. `runs/index.json` 파싱 실패 또는 run JSON 파일과 불일치 감지 시, 전체 `runs/` 디렉토리의 run JSON 파일을 스캔하여 인덱스를 재생성. run 인덱스도 run JSON의 파생물이므로 언제든 재구성 가능.

**보존 정책 — 아카이브 후 삭제:**

완료된 태스크를 즉시 삭제하면 사후 분석이 불가. 2단계 정리:

1. **보존 기간** (예: 24시간) — 전체 디렉토리 유지 (stdout, stderr, meta.json)
2. **아카이브** — 보존 기간 경과 후 compact summary로 축소. 디렉토리를 단일 파일(`archive.json`)로 대체
3. **삭제** — 아카이브 기간 (예: 7일) 경과 후 완전 삭제 (인덱스에서도 제거)

**아카이브 최소 계약 — task 상세 화면에서 보장하는 필드:**

아카이브 후에도 Admin UI의 task 상세 화면과 `GET /api/tasks/{taskId}` API가 동작해야 한다. 아카이브 형식은 live task의 모든 필드를 보존할 필요는 없지만, 운영 조회에 필요한 최소 계약을 정의한다.

| 카테고리 | 필드 | 용도 |
|---|---|---|
| 식별 | `taskId`, `runId`, `threadId`, `threadName` | run 상세에서 task 연계, task 목록 필터 |
| 인프라 | `hostInstanceId` | 어느 호스트에서 실행됐는지 추적 |
| 실행 | `command`, `status`, `exitCode` | 무엇을 실행했고 어떻게 끝났는지 |
| 시간 | `startTime`, `endTime`, `timeoutMs` | 실행 시간, timeout 초과 여부 계산 |
| 출력 | `stdoutTail` (50줄), `stderrTail` (20줄) | 사후 디버깅 최소 정보 |

```java
// archive.json — task 디렉토리를 단일 파일로 축소
{
    "taskId": "001",
    "runId": "run-abc",
    "threadId": 3,
    "threadName": "worker-0",
    "hostInstanceId": "host-20260308-143022-a1b2",
    "command": "python simulate.py",
    "status": "completed",
    "exitCode": 0,
    "startTime": 1709...,
    "endTime": 1709...,
    "timeoutMs": 300000,
    "stdoutTail": "... last 50 lines ...",
    "stderrTail": "... last 20 lines ..."
}
```

`timeoutExceeded`는 아카이브에 포함하지 않음 — `startTime`, `endTime`, `timeoutMs`에서 계산 가능 (진실 원천 단일화 원칙과 일치).

인덱스의 해당 항목에 `"archived": true`를 추가하여 아카이브 여부 표시. `GET /api/tasks?runId=run-abc`는 아카이브된 태스크도 포함하되, 상세 조회 시 `archive.json`에서 로드. UI는 아카이브 태스크에 `[archived]` 표시를 붙여 원본 stdout/stderr가 축약됐음을 알림.

**API 쿼리:**

```
GET /api/runs?offset=0&limit=20
GET /api/runs?status=RUNNING
GET /api/tasks?runId=xxx&offset=0&limit=50
GET /api/tasks?status=running
```

**파일:** `TaskEngine.java`, `TeeBoxServer.java`, `RunManager.java`

### 2-4. 타임아웃 — 관찰 및 알림 ✅ (관찰 구현 완료, autoKill 데몬 미구현)

**문제:** `timeoutMs`는 메타데이터일 뿐, 아무 강제도 없음. 런어웨이 프로세스 가능성.

**설계 원칙:** 자동 kill은 HPC에서 위험 — 장기 계산을 의도적으로 수행 중일 수 있음. 시스템은 **관찰하고 알리되, 최종 kill 결정은 운영자**가 내림.

**진실 원천 결정:** `timeoutExceeded`는 현재 `toObservation()`에서 매번 계산한다 (`TaskEngine.java:483`). 영속 필드로 추가하면 두 소스가 생겨 불일치 위험. **계산형을 유일한 진실 원천으로 유지.** 영속 필드는 추가하지 않으며, API와 UI는 모두 `toObservation()` 결과를 사용.

이유: timeout 판정은 항상 `(now - startTime) > timeoutMs`로 결정론적이므로 캐싱 불필요. 영속 플래그는 상태 불일치만 만든다.

**수정:**

1. `toObservation()`의 계산형 `timeoutExceeded`를 유지 (현재 동작 그대로)
2. Admin UI에서 시각적 경고 (타임아웃 초과 태스크 강조 표시)
3. 선택적: 운영자가 자동 kill 정책을 명시적으로 opt-in (`propertee.task.autoKillOnTimeout=true`)
4. 자동 kill opt-in 시에만 데몬 스레드가 주기적으로 스캔하여 kill 실행

**`autoKillOnTimeout` 정책:**
- **기본값: `false`** — 명시적 opt-in 없이는 타임아웃 초과 태스크를 kill하지 않음
- 시스템 프로퍼티 `propertee.task.autoKillOnTimeout=true`로 활성화
- 활성화 시 UI와 API에 정책 상태를 표시:
  - Admin UI 헤더: `⚠ 자동 종료 정책 활성화됨` 배너
  - `GET /api/health` 응답에 `"autoKillOnTimeout": true` 포함
  - task 상세에서 자동 kill로 종료된 경우 status `"killed"` + healthHint `"AUTO_KILLED_TIMEOUT"` 추가
- 비활성화 시에도 `toObservation().timeoutExceeded`는 계속 계산/표시됨 — 운영자에게 정보 제공

```java
// TaskEngine 생성자
private final boolean autoKillOnTimeout;

public TaskEngine(String baseDir, String hostInstanceId) {
    // ...
    this.autoKillOnTimeout = "true".equals(
        System.getProperty("propertee.task.autoKillOnTimeout", "false"));
}

// 데몬 스레드 (opt-in 시에만 실행)
private void checkTimeouts() {
    if (!autoKillOnTimeout) return;
    for (Task task : loadRunningTasks()) {
        if (task.timeoutMs > 0) {
            long elapsed = System.currentTimeMillis() - task.startTime;
            if (elapsed > task.timeoutMs) {
                killTask(task.taskId);
            }
        }
    }
}
```

**파일:** `TaskEngine.java` (데몬 스레드, opt-in 로직), `TeeBoxServer.java` (UI 경고/배너)

---

## Phase 3: 구조 정리

서비스 확장 시 변경 영향을 최소화하기 위한 내부 구조 개선.

### 3-1. RunManager 책임 분리 ✅ (수정 완료)

**문제:** RunManager가 스크립트 파싱, 실행, 로그, 스레드 추적, 태스크 위임, 영속성을 모두 담당. 변경 이유가 너무 많음.

**분리 방향:**

```
RunManager (현재 ~500줄)
    ↓
ScriptExecutor   — 파싱, 인터프리터 생성, Scheduler 실행
RunRegistry      — 런 상태 저장/조회, 로그 관리, 영속성
```

RunManager는 이 둘을 조합하는 얇은 코디네이터로 축소.

**기준:** 각 클래스가 하나의 이유로만 변경되도록:
- ScriptExecutor: 실행 방식 변경 시
- RunRegistry: 저장/조회 방식 변경 시
- RunManager: 조합 정책 변경 시

### 3-2. TeeBoxServer 정리 ✅ (수정 완료)

**문제:** 868줄 단일 파일에 HTTP 라우팅, HTML 렌더링, JSON API, CSS가 혼재.

**분리 방향:**

```
TeeBoxServer (라우팅 + 서버 설정)
AdminPageRenderer (HTML 생성)
```

API 핸들러는 현재 규모에서는 분리 불필요. HTML 렌더링만 별도 클래스로 추출.

---

## Phase 4: HPC 특화 (필요 시)

실제 HPC 운영 환경 요구사항에 따라 선택적으로 적용.

### 4-1. 작업 큐잉 및 우선순위

현재 `ThreadPoolExecutor.newFixedThreadPool`으로 단순 FIFO 큐. HPC에서는 우선순위 기반 큐잉이 필요할 수 있음.

```java
// PriorityBlockingQueue + 우선순위 필드
runExecutor = new ThreadPoolExecutor(
    maxConcurrentRuns, maxConcurrentRuns,
    0L, TimeUnit.MILLISECONDS,
    new PriorityBlockingQueue<Runnable>(11, priorityComparator)
);
```

### 4-2. 결과 콜백 (Webhook)

스크립트 완료 시 클라이언트에게 HTTP POST로 결과 전송.

```
POST /api/runs
{
  "scriptPath": "calculate.pt",
  "props": { "input": 42 },
  "callbackUrl": "http://client-node:8080/results"
}
```

완료 시 callbackUrl로 결과 JSON을 POST.

### 4-3. 자원 모니터링

실행 중인 스크립트/프로세스의 CPU, 메모리 사용량 추적.

```
GET /api/health
{
  "activeRuns": 3,
  "queuedRuns": 5,
  "runningTasks": 7,
  "systemLoad": 2.4,
  "availableMemoryMb": 1024
}
```

---

## 우선순위 요약

| 순위 | 항목 | Phase | 상태 |
|---|---|---|---|
| 1 | 경로/운영 결함 정리 (dataDir, lost, saveMeta race) | 0-1~3 | ✅ 완료 |
| 2 | default TaskEngine 재분류 방지 | 0-4 | ✅ 완료 |
| 3 | admin kill의 run 상태 전이 제거 | 0-5 | ✅ 완료 |
| 4 | 구조화 결과의 명시적 계약 정의 | 2-1 | ✅ 완료 |
| 5 | 인증 추가 | 2-2 | ✅ 완료 |
| 6 | 저장소 레벨 pagination/retention 설계 | 2-3 | ✅ 완료 |
| 7 | execute() 동기화 축소 | 1-1 | ✅ 완료 |
| 8 | 폴링 백오프 | 1-2 | ✅ 완료 |
| 9 | Task.status enum | 1-3 | ✅ 완료 |
| 10 | 타임아웃 관찰/알림 (자동 kill은 마지막에 판단) | 2-4 | ✅ 관찰 완료 (autoKill 데몬 미구현) |
| 11 | RunManager 분리 | 3-1 | ✅ 완료 |
| 12 | AdminServer 분리 | 3-2 | ✅ 완료 |

---

## 유지할 것

- **TaskEngine 프로세스 관리** — PID 검증, 시그널 에스컬레이션, 프로세스 그룹 처리
- **SchedulerListener 인터페이스** — 실행 상태 관찰의 깔끔한 추상화
- **파일 기반 영속성** — HPC 노드에서 DB 없이 동작
- **SHELL/START_TASK 함수 체계** — 스크립트 내 외부 도구 호출 패턴
- **Admin UI** — 운영 모니터링/디버깅 도구
- **AsyncPendingException 패턴** — 스크립트의 비동기 프로세스 협력
- **"UI는 관찰만" 원칙** — 조회/새로고침이 서버 상태를 영구 변경하지 않음
