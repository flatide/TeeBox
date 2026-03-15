# ProperTee 외부 프로세스 실행 아키텍처 검토

## 1. 제품 경계 정의

ProperTee 생태계는 두 개의 독립된 제품으로 구성된다.

**ProperTee (eval/runtime)** — 안전한 임베디드 스크립트 평가 엔진
- 호스트 앱에 임베딩되어 스크립트를 평가하고 결과를 반환
- Thread purity, cooperative scheduling, value semantics로 안전성 보장
- 시스템 접근 최소화 (파일 I/O 없음, 네트워킹 없음)
- 호스트가 기능을 주입 (`registerExternal`, `registerExternalAsync`)
- Java 7+ 호환, 외부 의존성 없음

**TeeBox (job proxy/control plane)** — 폐쇄망 실행 프록시 서비스
- 외부 서버의 요청을 받아 ProperTee 스크립트를 실행하는 HTTP 서비스
- 장/단기 I/O를 안정적으로 수행
- 외부 서버가 상태를 언제든 모니터링 가능해야 함
- 서버 재시작, task 생존, 다중 인스턴스 운영을 처리
- Java 17, 폐쇄망(HPC) 운영

**이 문서의 핵심 질문:** 현재 TaskEngine(1219줄)이 eval/runtime에 위치하면서 job proxy/control plane의 책임까지 수행하고 있는데, 이것이 적절한가?

## 2. 외부 프로세스 실행: 적절한 부분

ProperTee의 SHELL/START_TASK가 Stepper 패턴의 async/await와 통합되는 방식은 독창적이고 적절하다.

```
multi result do
    thread a: SHELL("job1.sh")  // async 투명, 다른 스레드 블로킹 안함
    thread b: SHELL("job2.sh")  // 자동 병렬 실행
end
```

이것은 eval/runtime의 cooperative scheduling 모델을 자연스럽게 활용한 설계이다. **프로세스 실행 자체는 eval/runtime에 속하는 것이 맞다.**

## 3. 문제: eval/runtime에 control plane 기능이 포함됨

### 3.1 TaskEngine 기능의 소속 분석

| 기능 | 줄 수 | eval/runtime? | control plane? | 근거 |
|------|-------|:---:|:---:|------|
| `execute()` — 프로세스 시작 | ~100 | ✓ | | Lua os.execute, Python subprocess와 동등 |
| `waitForCompletion()` — 폴링 대기 | ~40 | ✓ | | 언어의 async/await 통합에 필수 |
| `killTask()` — SIGTERM/SIGKILL | ~60 | ✓ | | CANCEL_TASK 빌트인의 구현체 |
| stdout/stderr 캡처 | ~50 | ✓ | | SHELL 반환값의 기반 |
| PGID 기반 프로세스 그룹 kill | ~50 | ✓ | | 자식 프로세스 정리, 안전 실행에 필수 |
| 인메모리 task 추적 | ~30 | ✓ | | TASK_STATUS, WAIT_TASK가 taskId로 조회 |
| **소계** | **~400** | | | |
| `meta.json` 영속화 | ~150 | | ✓ | CLI에서는 temp에 버려짐, 서버 재시작 복구용 |
| `index.json` 인덱스 관리 | ~120 | | ✓ | API 쿼리용 (runId, status 필터) |
| `init()` — 재시작 복구 | ~80 | | ✓ | 서버 재시작 시 DETACHED/LOST 판별 |
| `isOurProcess()` pidStartTime | ~60 | | ✓ | PID 재사용 방지, 다중 인스턴스 ownership |
| `hostInstanceId` + `ownedTaskIds` | ~40 | | ✓ | 서버 인스턴스 식별 |
| archive/retention 생명주기 | ~100 | | ✓ | 장기 서버 운영 전용 |
| `queryTasks()` 필터 쿼리 | ~80 | | ✓ | admin API 전용 |
| `killRun()` 배치 kill | ~30 | | ✓ | runId는 TeeBox의 run 관리 체계 |
| DETACHED/LOST 상태 전이 | ~50 | | ✓ | 서버 재시작 시나리오 전용 |
| **소계** | **~800** | | | |

TaskEngine의 **65%가 control plane 기능**이다.

### 3.2 타 언어와의 비교

| 언어 | eval/runtime 수준 | 프로세스 관리 책임 |
|------|------------------|------------------|
| Lua | `os.execute()` — 실행+대기 | 호스트 앱에 위임 |
| Python | `subprocess` — 실행+대기+킬 | Celery, Supervisor 등 별도 도구 |
| Tcl | `exec` — 동기 실행 | 애플리케이션 레벨 |
| Bash | 네이티브 fg/bg | OS (systemd, cron) |
| **ProperTee** | SHELL, START_TASK | **1219줄 TaskEngine이 런타임에 내장** |

모든 비교 언어에서 프로세스 관리는 eval/runtime 밖에 있다. ProperTee만 eval/runtime에 Supervisor급 기능을 포함한다.

### 3.3 구체적 문제

**문제 1: CLI에서 불필요한 오버헤드**

CLI에서 `SHELL("echo hello")` 실행 시 발생하는 일:
1. temp 디렉토리에 `tasks/` 생성, `meta.json` 작성, `index.json` 작성
2. `ps -p PID -o lstart=` 프로세스 포크 (시작 시간 파싱)
3. `ps -o pgid=` 프로세스 포크 (프로세스 그룹 조회)

CLI 종료 시 이 파일들은 모두 고아가 된다. Lua의 `os.execute()`는 이 중 어떤 것도 하지 않는다.

**문제 2: 테스트 실패가 control plane 관심사에서 발생**

현재 실패하는 2개 테스트:
- `initShouldNotDetachTasksFromSameHostInstance` — 서버 재시작 복구
- `waitShouldSeeKilledStatusFromExternalKill` — 다중 인스턴스 간 task kill

`isOurProcess()`의 `pidStartTime` 검증이 플랫폼에서 실패. 이 기능은 CLI에서 사용되지 않으며 순수하게 control plane 관심사인데, core에 있어서 core 버전 릴리스가 필요.

**문제 3: Java 7 제약이 최적 구현을 막음**

core는 Java 7 타겟. `ProcessHandle` (Java 9+) 불가 → `ps -p PID -o lstart=` 파싱에 의존 (로케일/플랫폼 의존적). TeeBox는 Java 17이므로 `ProcessHandle.of(pid).info().startInstant()`로 구현 가능.

**문제 4: 언어 개량 시 서버 운영 코드가 장벽**

언어를 계속 개량해야 하는데, 서버 운영 책임(800줄)이 core에 박혀 있으면 변경 영향 범위가 넓어지고, core 릴리스 주기가 서버 버그에도 영향받는다.

## 4. 접근 방식 비교

### 방식 A: 완전 분리

core에 경량 `TaskRunner`만 남기고, 영속화/관리 전체를 TeeBox의 `ManagedTaskEngine`으로 이동.

- 장점: 제품 경계가 가장 깔끔, core가 가장 가벼워짐, 언어 개량 용이성 최고
- 단점: 두 레포 동시 변경, core 인터페이스 변경

### 방식 B: Pluggable Strategy

core의 `TaskEngine`에서 영속화/관리를 strategy/adapter로 분리. core는 기본적으로 lightweight mode로 동작, 호스트가 persistence adapter를 주입하면 managed mode.

- 장점: core API 변경 최소화, 기존 클래스 유지, 점진적 이동 가능
- 단점: core에 strategy 인터페이스가 남아 추상화 비용 발생, 제품 경계 덜 선명

### 방식 C: 상태 유지 + 버그 수정만

`isOurProcess()` 버그만 수정. 구조는 현행 유지.

- 장점: 최소 변경
- 단점: 구조적 문제 미해결, 언어 개량 시 부담 지속

### 비교 요약

| 기준 | A: 완전 분리 | B: Pluggable | C: 버그만 수정 |
|------|:---:|:---:|:---:|
| core 경량화 | 최대 | 중간 | 없음 |
| 제품 경계 선명도 | 최고 | 양호 | 불변 |
| 언어 개량 용이성 | 최고 | 양호 | 불변 |
| 하위 호환성 | 깨짐 (인터페이스 변경) | 유지 | 유지 |
| 변경 범위 | 대규모 | 중간 | 최소 |

## 5. 결정: 방식 A (완전 분리)

제품 경계를 가장 깔끔하게 정립하고, core의 장기적 개량 용이성을 최대화하기 위해 완전 분리를 선택한다.

### 5.1 Core의 책임 (eval/runtime)

프로세스 실행, 대기, 종료, 출력 수집. multi 블록과의 async/await 통합. 인메모리 task 추적 (영속화 없음). `TaskRunner` 인터페이스 + `DefaultTaskRunner` 경량 구현 (~400줄).

`TaskRunner`는 BuiltinFunctions가 호출하는 메서드만 포함한다. `killRun`, `queryTasks`, `listTasks`는 control plane 기능이므로 포함하지 않는다.

```java
interface TaskRunner {
    Task execute(TaskRequest request);
    Task getTask(String taskId);  // 현재 프로세스가 아는 task만 조회 (인메모리)
    Task waitForCompletion(String taskId, long timeoutMs);
    boolean killTask(String taskId);
    TaskObservation observe(String taskId);
    String getStdout(String taskId);
    String getStderr(String taskId);
    String getCombinedOutput(String taskId);
    Integer getExitCode(String taskId);
    Map<String, Object> getStatusMap(String taskId);
    void shutdown();
}
```

`TaskRunner.getTask()`는 **현재 프로세스가 실행한 task만 조회**한다. 인메모리 맵에서 찾고, 없으면 null을 반환한다. 재시작 후 과거 task를 디스크에서 복원하여 조회하는 것은 `ManagedTaskEngine`의 확장 동작이며, `TaskRunner` 계약에 포함되지 않는다.

### 5.2 TeeBox의 책임 (control plane)

`ManagedTaskEngine`이 `TaskRunner`를 implement하여 control plane 기능을 추가한다 (~500줄). 내부에 `DefaultTaskRunner`를 갖고 프로세스 실행을 위임하며, 영속화/관리를 그 위에 적층한다.

- **영속화:** meta.json per task, index.json for queries
- **재시작 복구:** `init()` — 디스크에서 task 로드, ownership 판별
- **쿼리:** `queryTasks(runId, status, offset, limit)`, `listTasks()` — ManagedTaskEngine 고유 메서드 (TaskRunner 인터페이스에 없음)
- **배치 kill:** `killRun(runId)` — ManagedTaskEngine 고유 메서드
- **다중 인스턴스:** `hostInstanceId`, DETACHED/LOST 상태 전이
- **archive/retention:** 완료된 task 아카이브 → 보존 기간 후 삭제

### 5.3 Ownership 검증과 실패 모델

TeeBox의 `ManagedTaskEngine`은 서버 재시작 시 기존 task의 소유권을 판별해야 한다. Java 17 `ProcessHandle`을 사용하되, 검증 실패 시의 상태 전이 규칙을 명확히 정의한다.

**Ownership 검증 흐름:**

```
프로세스 alive 체크 (ProcessHandle.of(pid).isPresent())
  ├─ dead → LOST (프로세스 이미 종료)
  └─ alive → startInstant 비교
       ├─ startInstant 확인 성공 + 일치 → 소유권 인정
       │    ├─ 동일 hostInstanceId → RUNNING
       │    └─ 다른 hostInstanceId → DETACHED
       ├─ startInstant 확인 성공 + 불일치 → LOST (PID 재사용 감지)
       └─ startInstant 확인 실패 (Optional.empty)
            ├─ 동일 hostInstanceId → RUNNING (아래 근거 참조)
            ├─ 다른 hostInstanceId → DETACHED + health hint 기록
            │    hint: "Process start time unavailable; ownership assumed by host affinity"
            └─ hostInstanceId 없음 (레거시 데이터) → LOST
```

**설계 원칙:**
- 확인 가능하면 정밀 판별 (startInstant 비교)
- 확인 불가 + 다른 호스트 → DETACHED + hint (관리자가 판단)
- 모든 불확실한 판별은 health hint에 기록하여 admin UI/API에서 확인 가능

**"확인 불가 + 동일 hostInstanceId → RUNNING" 근거:**

동일 hostInstanceId는 같은 TeeBox 인스턴스의 재기동을 의미한다. 이 경우 오판정(false positive)의 비용과 누락(false negative)의 비용을 비교하면:

- **False positive (실제로는 PID 재사용인데 RUNNING으로 판정):** admin UI/API가 kill 가능한 자기 task로 오인할 수 있다. kill을 시도하면 무관한 프로세스가 종료될 위험이 있으나, PGID 기반 kill이 프로세스 그룹을 확인하므로 무관한 프로세스의 단독 kill은 발생하지 않는다. 최악의 경우 kill 실패 후 LOST로 전이된다.
- **False negative (실제로는 자기 task인데 LOST로 판정):** 아직 실행 중인 task가 관리에서 사라진다. 외부 서버가 상태를 조회할 수 없고, kill도 불가능하다. 고아 프로세스가 HPC 자원을 계속 점유한다.

폐쇄망 HPC 환경에서는 **프로세스 가시성을 잃는 것(false negative)이 오인하는 것(false positive)보다 위험하다.** 따라서 false positive를 감수하더라도 task visibility를 유지하는 것이 운영상 더 낫다.

### 5.4 제어 흐름

RunManager, ScriptExecutor, ManagedTaskEngine 간의 관계와 호출 순서:

```
[외부 서버] → TeeBoxServer
                  │
                  └─ RunManager
                       │
                       ├── managedTaskEngine: ManagedTaskEngine (implements TaskRunner)
                       │     │
                       │     ├── 내부: DefaultTaskRunner (프로세스 실행/대기/킬)
                       │     └── 추가: 영속화, init, archive, ownership, 쿼리
                       │
                       └── ScriptExecutor.execute(..., taskRunner = managedTaskEngine)
                             │
                             └── BuiltinFunctions(taskRunner = managedTaskEngine)
                                   │
                                   ├─ START_TASK("cmd")
                                   │    → taskRunner.execute(request)
                                   │    → ManagedTaskEngine.execute()
                                   │      1. DefaultTaskRunner.execute() → 프로세스 시작
                                   │      2. meta.json 저장 (영속화)
                                   │      3. index.json 업데이트
                                   │      → Task 반환 (taskId 포함)
                                   │
                                   ├─ WAIT_TASK(taskId, timeout)
                                   │    → taskRunner.waitForCompletion()
                                   │    → ManagedTaskEngine.waitForCompletion()
                                   │      1. DefaultTaskRunner.waitForCompletion() → 폴링
                                   │      2. 완료 시 meta.json 업데이트
                                   │      → Task 반환 (결과 포함)
                                   │
                                   └─ CANCEL_TASK(taskId)
                                        → taskRunner.killTask()
                                        → ManagedTaskEngine.killTask()
                                          1. DefaultTaskRunner.killTask() → SIGTERM/SIGKILL
                                          2. meta.json에 KILLED 상태 저장
                                          → boolean 반환
```

**핵심 설계:**
- `ManagedTaskEngine`은 `TaskRunner`를 implement한다
- `ScriptExecutor`는 `TaskRunner` 타입으로 받으므로 영속화를 모른다
- 실제로는 `ManagedTaskEngine`을 통과하므로 모든 task가 자동으로 영속화된다
- Task 메타 저장 시점 = `ManagedTaskEngine`의 각 메서드 내부 (execute 후, 완료 후, kill 후)
- Run 연계 = `TaskRequest.runId`를 통해 execute 시점에 연결

**RunManager의 직접 호출 (API 계층):**
```
GET /api/admin/tasks?runId=xxx  → RunManager → managedTaskEngine.queryTasks(runId, ...)
POST /api/admin/tasks/{id}/kill → RunManager → managedTaskEngine.killTask(taskId)
POST /api/admin/runs/{id}/kill  → RunManager → managedTaskEngine.killRun(runId)
GET /api/admin/tasks             → RunManager → managedTaskEngine.listTasks()
```

`queryTasks`, `killRun`, `listTasks`는 `ManagedTaskEngine`의 고유 메서드이며 `TaskRunner` 인터페이스에 없다. RunManager는 `ManagedTaskEngine` 타입으로 직접 참조한다.

### 5.5 TeeBox가 보장해야 하는 최소 계약

TeeBox는 폐쇄망 실행 프록시이므로 단순 실행기만으로는 부족하다. 외부 서버가 다음을 안정적으로 조회/제어할 수 있어야 한다:

| 항목 | 설명 | 보장 방식 |
|------|------|----------|
| `taskId` | task 고유 식별자 | `ManagedTaskEngine.getTask()` → 디스크에서 로드 |
| `runId` | task가 속한 run 식별 | `managedTaskEngine.queryTasks(runId, ...)` → index.json |
| 생성 시각 | task 시작 시점 | Task.startTime (meta.json에 영속) |
| 현재 상태 | running/completed/failed/killed/lost | `observe()` → 프로세스 alive 체크 + 상태 반환 |
| 출력 접근 | stdout/stderr | `getStdout/Stderr()` → task 디렉토리 파일 |
| kill 가능 여부 | alive 플래그 | `observe().alive` |
| kill 실행 | 프로세스 종료 | `killTask()` → DefaultTaskRunner에 위임 + 상태 영속화 |
| 경과 시간 | 실행 시간 추적 | `observe().elapsedMs` |
| timeout 초과 | 설정된 timeout 대비 상태 | `observe().timeoutExceeded` |
| health 힌트 | 진단 정보 | `observe().healthHints` |

### 5.6 호환성 정책

- **스크립트 API와 단일 프로세스 내 실행 의미는 유지한다.** SHELL, START_TASK, WAIT_TASK, CANCEL_TASK, TASK_STATUS의 문법, 파라미터, 반환값은 변경 없이 동작한다. 하나의 스크립트 실행 내에서 task를 시작하고, 대기하고, 상태를 조회하고, 취소하는 모든 동작은 동일하다.
- **Persistence semantics는 host 기능으로 이동한다.** taskId의 생존 범위가 변경된다: core에서는 프로세스 생존 기간 내로 한정되며, 프로세스 종료 후 taskId로 조회할 수 없다. 재시작 후 task 조회, 디스크 영속화, 아카이브는 TeeBox 같은 호스트 앱의 책임이다. 이것은 구현 교체가 아닌 **계약 축소**이며, 의도적인 설계이다.
- **CLI/embedder는 lightweight mode가 기본이다.** 영속화, 인덱스, 아카이브 없음. temp 디렉토리에 고아 파일 미생성.
- **기존 `TaskEngine`은 deprecated + `implements TaskRunner`로 유지** (1 릴리스 사이클 호환). 기존 embedder가 TaskEngine을 직접 사용하는 경우 경고와 함께 동작하며, 다음 메이저 버전에서 제거한다.

## 6. 구현 계획

### Phase 1: propertee-core (propertee-java 레포)

1. `TaskRunner` 인터페이스 정의 (`com.propertee.task.TaskRunner`)
   - execute, getTask, waitForCompletion, killTask, observe, stdout/stderr/combinedOutput, exitCode, statusMap, shutdown
   - killRun, queryTasks, listTasks는 **포함하지 않음** (control plane 전용)
2. `DefaultTaskRunner` 구현 — 현재 TaskEngine에서 경량 부분 추출
   - 제거: hostInstanceId, ownedTaskIds, isOurProcess(), init(), meta.json, index.json, archive/retention, DETACHED/LOST 전이, queryTasks, killRun, listTasks
   - 유지: execute, wait, kill, observe, stdout/stderr, 인메모리 추적, PGID kill
3. `BuiltinFunctions`: `TaskEngine` → `TaskRunner` 파라미터 변경
4. 기존 `TaskEngine`: deprecated + `implements TaskRunner`
5. 테스트: multi-instance 테스트 2개 제거, DefaultTaskRunner 테스트로 전환
6. 버전: 0.3.0 → 0.4.0, `publishToMavenLocal`

### Phase 2: propertee-teebox (propertee-teebox 레포)

1. `ManagedTaskEngine` 구현 (implements TaskRunner)
   - 내부에 `DefaultTaskRunner` 보유, 프로세스 실행 위임
   - TaskRunner 메서드 구현: execute/wait/kill 시 영속화 추가
   - 고유 메서드: `init()`, `archiveExpiredTasks()`, `queryTasks()`, `killRun()`, `listTasks()`
   - ownership 검증: Java 17 `ProcessHandle` 기반 + 섹션 5.3의 실패 모델 적용
2. `RunManager` 수정
   - 필드: `ManagedTaskEngine managedTaskEngine` (구체 타입으로 보유, 쿼리/배치 kill 호출용)
   - ScriptExecutor에는 `TaskRunner` 타입으로 전달
3. `ScriptExecutor`: `TaskEngine` → `TaskRunner`
4. 의존성: `propertee-core:0.4.0`
5. multi-instance 테스트를 TeeBox로 이동 + ownership 실패 모델 테스트 추가

## 7. 결론

| 측면 | 소속 | 현재 위치 | 변경 후 위치 |
|------|------|----------|------------|
| 프로세스 실행 (SHELL, START_TASK) | eval/runtime | Core ✓ | Core ✓ |
| async/await 통합 | eval/runtime | Core ✓ | Core ✓ |
| 인메모리 task 추적 | eval/runtime | Core ✓ | Core ✓ |
| 프로세스 영속화 (meta.json, index) | control plane | Core ✗ | TeeBox |
| 쿼리 (queryTasks, listTasks) | control plane | Core ✗ | TeeBox |
| 배치 kill (killRun) | control plane | Core ✗ | TeeBox |
| 멀티 인스턴스 (hostInstanceId) | control plane | Core ✗ | TeeBox |
| 아카이브/보존 (retention) | control plane | Core ✗ | TeeBox |
| 재시작 복구 (init, DETACHED/LOST) | control plane | Core ✗ | TeeBox |
| 상태 조회 API | control plane | TeeBox ✓ | TeeBox ✓ |

ProperTee의 외부 프로세스 **실행**은 eval/runtime 설계에 적합하다. async/await와의 투명한 통합은 다른 임베디드 언어에 없는 강점이다. 그러나 프로세스 **관리**(영속화, 멀티인스턴스, 아카이브, 재시작 복구, 쿼리)는 control plane(TeeBox)의 책임이다. 완전 분리를 통해 제품 경계를 명확히 하고, 언어의 장기적 개량 용이성을 확보한다.
