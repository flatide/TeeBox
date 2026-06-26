# TeeBoxClient Durable Callback 설계 메모

> **상태: 보류(Deferred) — 미구현 설계 기록.**
> 작성일 2026-06-26. 현재 운영 채택안은 [§1](#1-현재-채택안-스크립트-종료-시-http-콜백)의 "스크립트 종료 시 HTTP 콜백"이며,
> 아래 `RunTracker` 설계는 그 방식이 불가능해질 때(인증벽으로 인바운드가 막히는 환경 등)를 위한
> **향후 구현 청사진**으로 남긴다. 구현 시 이 문서를 출발점으로 삼는다.

---

## 0. 배경 / 문제

- TeeBox 스크립트 중 **장시간 실행**되는 것이 있고, 이를 호출한 **호스트 서버**는 스크립트 완료 후
  특정 후속 작업을 자기 프로세스 안에서 수행해야 한다.
- 호스트 서버의 배치 스케줄러는 레거시라 쓰기 어렵다.
- TeeBox 서버는 **비동기**다: `POST /api/client/scripts/{id}/runs` 는 `202`와 `runId`만 즉시 반환하고
  실행은 백그라운드에서 진행된다. 동기 "run-and-wait" 엔드포인트는 없다(서버가 커넥션에 블로킹하지 않으므로
  클라이언트가 끊겨도 run은 계속 진행되고 같은 `runId`로 재폴링 가능 — 의도된 설계).
- 따라서 "완료 시 호스트가 무언가 한다"는 **누군가 완료를 감지해 호스트 코드를 실행**해야 성립한다.

### 검토했던 경로와 결론

| 경로 | 결과 |
|------|------|
| 스크립트에서 HTTP로 호스트 API 콜백 | 처음엔 호스트의 외부 API가 인증으로 안 열려 보류 → **이후 외부 호출 가능한 경로 확인되어 채택**(§1) |
| TeeBox → 호스트 push 콜백 | 인바운드가 막힌 환경에선 불가(또한 TeeBox가 호스트를 부르는 것도 동일 벽) |
| 호스트 loopback(`http://localhost/...`)로 자기 콜백 | **앱 레벨 인증 필터가 loopback도 거부**하여 불가로 확인됨 |
| 호스트 내 **durable callback dispatcher**(in-process Java 콜백) | 인증벽을 아예 안 거침. 본 문서 §2 이하의 `RunTracker` 설계 |

---

## 1. 현재 채택안: 스크립트 종료 시 HTTP 콜백

호스트 서버에서 **외부에서 호출 가능한 API 경로가 확인**되어, 후속 통지는 **스크립트 실행 마지막에서 HTTP로
호스트 API를 콜백**하는 방식으로 한다. (TeeBox HTTP 빌트인은 폐쇄망 default-allow 라 호스트 API를 호출 가능.)

```
// long_export.tee (개념 예시)
result = <... 장시간 작업 ...>
HTTP_POST(
  "https://host.internal/api/callbacks/reindex",
  { "runId": _SYS.runId, "status": "done", "summary": result.summary },
  { "headers": { "Authorization": "Bearer <token>", "Content-Type": "application/json" } }
)
return result
```

운영 시 권고(스크립트 측):
- **멱등 처리**: 호스트 콜백 엔드포인트는 `runId`를 idempotency key로 멱등하게. (스크립트 재실행/재시도 대비)
- **콜백 실패 처리**: HTTP 콜백이 비-2xx여도 run 자체는 COMPLETED일 수 있다. 콜백 결과를 stdout/`PRINT`로
  남기거나, 재시도 루프(`HTTP` + backoff)를 두는 것을 고려.
- **인증 토큰**은 스크립트 소스에 하드코딩하지 말고 `_PROPS`(제출 시 주입)로 전달하는 편이 안전.
- 이 방식의 한계: **호스트가 콜백 수신 시점에 다운**돼 있으면 그 통지는 유실된다(스크립트는 한 번 쏘고 끝).
  내구성(재시작 후 재시도)이 필요해지면 §2의 `RunTracker`로 전환한다.

---

## 2. 보류 설계: 호스트 내 Durable Callback Dispatcher (`RunTracker`)

> 인바운드가 막혀 스크립트→호스트 HTTP 콜백이 불가능한 환경을 위한 대안.
> **TeeBox는 변경하지 않는다**(단, §5의 한 가지 한계는 TeeBox 변경 없이는 완전히 못 막는다).

### 2.1 핵심 인식

- 완료 여부와 결과의 **"진실"은 이미 TeeBox에 내구 저장**되어 있다(`GET /runs/{runId}`로 재조회 가능).
- 호스트가 재시작 시 **유실하는 것은 결과가 아니라 "내가 runId X에 콜백을 빚지고 있다"는 의도(intent)뿐**이다.
- 그 의도(작은 to-do)만 **로컬 파일 저널**에 내구 기록하고, **호스트가 pull**(폴링)하여 완료를 감지하면
  in-process 콜백을 실행한다. → DB 불필요, 인증벽 미통과.

### 2.2 책임 분리

- `TeeBoxClient` 본체는 **stateless HTTP wrapper**로 유지(스레드/디스크/lifecycle 없음).
- lifecycle(저널·워커·콜백)은 **별도 nested helper `TeeBoxClient.RunTracker`** 가 가진다.
- `client.newRunTracker(File journalDir)` 팩토리로 생성, tracker가 client를 참조해 HTTP를 위임.

### 2.3 콜백 계약 — "클로저는 직렬화 불가" → 키로 우회

살아있는 람다/콜백 객체는 파일에 영속화할 수 없다. 저널엔 **`handlerKey`(문자열) + `context`(JSON 가능한 작은 Map)**
만 저장하고, 호스트는 부팅 시 코드에서 핸들러를 **이름으로 재등록**한다.

```java
public interface RunCallback {            // Java 7, 무의존
    void onTerminal(RunOutcome outcome) throws Exception;
}

public static final class RunOutcome {
    public final String trackingId, runId, scriptId, version;
    public final String status;                 // COMPLETED / FAILED / SERVER_RESTARTED
    public final boolean resultAvailable;
    public final String lossReason;             // NONE / RUN_PURGED / RESULT_EXPIRED / SERVER_RESTARTED
    public final Map<String,Object> result;     // 인라인 스냅샷(소형); 없으면 null
    public final Object resultStream;           // STREAM_FILE 결과면 redacted 디스크립터
    public final java.io.File resultFile;       // downloadStreamTo 옵션 시 사이드카 파일
    public final String errorMessage;
    public final Map<String,Object> context;
}
```

### 2.4 API 형태(목표)

```java
TeeBoxClient client = new TeeBoxClient(url);
TeeBoxClient.RunTracker tracker = client.newRunTracker(new File("/var/lib/host/teebox-callbacks"))
    .setMaxSnapshotBytes(256 * 1024)
    .setProbePoolSize(2);

tracker.registerCallback("reindex-done", new TeeBoxClient.RunCallback() {
    public void onTerminal(TeeBoxClient.RunOutcome o) throws Exception {
        // o.status 로 분기, o.runId / o.trackingId 로 멱등 처리
        legacy.rebuildIndex(o.context.get("tenant"), o.result);
    }
});

tracker.start();   // 부팅 reconcile(§2.8 결정표) + scheduler/probe/callback 가동

String trackingId = tracker.submitAndTrack(
    "long_export", null, props, "reindex-done", context);

// 운영용 introspection
tracker.listPending();
tracker.listDeadLetter();
tracker.listNeedsAttention();

tracker.close();   // graceful shutdown
```

`journalDir` 미설정 시 `submitAndTrack`은 예외(내구성이 핵심이라 강제). 임베디드 클라이언트엔 `dataDir`
개념이 없으므로 **호스트가 디렉토리를 지정**해야 한다.

### 2.5 폴링 모델 — 역할 3분리(스레드/카디널리티 분리)

run당 스레드 1개(블로킹 폴 루프)는 장시간 작업이 많을 때 폴 스레드를 점유해 다른 run의 감지를 늦춘다. 대신:

- **Scheduler**(1 스레드): `nextPollAt <= now` 인 엔트리만 선별.
- **Probe pool**(소수, bounded): 선별분에 대해 `getRunStatus` **1회씩만**(짧게, 블로킹 X) → backoff로 `nextPollAt` 갱신.
- **Callback executor**(별도): terminal 감지분만 콜백 실행 → 느린 콜백이 폴링을 막지 않음.

→ pending 수천 개도 스레드 2~3개로 폴링 가능. 폴 간격은 forget 모드답게 max를 길게(예: 5s) 두어 장시간 작업의
TeeBox 부하를 줄인다.

### 2.6 저널 엔트리

`<journalDir>/<trackingId>.json` (PREPARED 시점엔 runId가 없으므로 **파일명은 trackingId** — runId는 submit 후 채움).

```json
{
  "trackingId": "t-7b21...",
  "runId": "r-8f3c...",
  "scriptId": "long_export",
  "version": null,
  "handlerKey": "reindex-done",
  "context": { "tenant": "acme" },
  "state": "SUBMITTED",
  "status": null,
  "result": null,
  "resultAvailable": false,
  "lossReason": "NONE",
  "attempts": 0,
  "nextPollAt": 1750900000000,
  "nextCallbackAt": 0,
  "lastError": null,
  "submittedAt": 1750900000000
}
```

- `state`(라이프사이클: PREPARED/SUBMITTED/READY/DONE/DEAD)와 `status`(TeeBox terminal)는 **분리 유지**.
- 쓰기 내구성: temp 파일 작성 → `FileOutputStream.getFD().sync()` → rename → **베스트에포트 디렉토리 fsync**
  (POSIX는 rename 내구성에 dir fsync 필요; Java 7 NIO로 Linux 가능, Windows는 skip).

### 2.7 상태 머신

```
PREPARED  ──submit 성공(runId 확보)──► SUBMITTED
SUBMITTED ──TeeBox terminal 감지──► (결과 스냅샷, §2.9) READY
SUBMITTED ──run purged / SERVER_RESTARTED──► READY (status 보존, lossReason 설정, result=null)
READY     ──콜백 성공──► DONE(짧은 tombstone) ──► 파일 삭제
READY     ──콜백 throw, attempts++<N──► READY (nextCallbackAt backoff)
READY     ──attempts>=N──► DEAD (dead-letter 디렉토리)
```

- **핵심: 콜백 호출 전에 결과를 READY로 스냅샷**한다. 이후 TeeBox가 24h archive로 `resultData`를 null화해도
  호스트가 이미 결과를 보유 → 콜백 재시도가 TeeBox 보존창과 **분리**된다.
- **at-least-once**: 콜백 성공↔파일 삭제 사이 크래시 시 재발사 가능. `DONE` tombstone이 1회 더 줄여주지만,
  최종 안전망은 **호스트 콜백의 멱등성**(runId/trackingId 키)이다.
- **transient probe 실패(네트워크 오류)는 terminal도 lossReason도 아니다** — `lastError` 기록 + backoff 재시도일 뿐.
  보존창을 넘겨 영구 확인 불가가 됐을 때만 `lossReason`을 확정한다.

### 2.8 부팅 reconcile / 복구 결정표

`start()` 내부에서 journalDir를 1회 스캔:

| 엔트리 상태 | clientRequestId 지원 O(§5) | 지원 X (client-only) |
|---|---|---|
| `PREPARED` (submit 도달 여부 불명) | 동일 trackingId로 **재제출 → dedup-safe** → SUBMITTED | **재제출 안 함** → `needs-attention/`로 격리 + alert |
| `SUBMITTED` (runId 있음) | 폴링 재개 | 폴링 재개 |
| `READY` (결과 스냅샷됨) | 콜백 재호출(TeeBox 조회 불필요) | 동일 |
| `handlerKey` 미등록(코드 삭제됨) | `dead-letter`로 이동 + 경고 | 동일 |

### 2.9 결과 스냅샷 크기 정책

journal 무제한 인라인 금지:

- `snapshotResult`(기본 true), `maxSnapshotBytes` 캡.
- 캡 초과 → 인라인 안 함, `resultAvailable=false` + (a) 사이드카 파일 `results/<trackingId>.bin` spill,
  또는 (b) 콜백이 보존창 내 TeeBox 재조회.
- **STREAM_FILE 결과는 바이트 인라인 절대 금지.** 기본은 **redacted 디스크립터만 기록 → 콜백이 `streamRunResult`로
  직접 스트림**. 옵션 `downloadStreamTo(dir)` 시 사이드카로 받아 `outcome.resultFile`로 전달.

---

## 3. 보장과 한계

| 항목 | 내용 |
|------|------|
| 전달 보증 | **at-least-once.** 콜백은 runId/trackingId 키로 **멱등**해야 함 |
| 보존창 결합 | terminal을 **24h 내** 캐치해야 결과 확보. READY 스냅샷 후엔 무관. 호스트가 24h+ 다운 시 `RESULT_EXPIRED` |
| 인증벽 | **전혀 안 거침** — 직접 in-process 메서드 호출 |
| TeeBox 변경 | **0** (단 §5의 submit 갭 제외) |
| 멀티 인스턴스 | 로컬 파일은 자기 노드만 복구. 노드 영구사망 인계가 필요하면 공유 스토어(또는 §5 server-side 저장) |

---

## 4. TeeBox 보존창(Retention) 참고

`RunTracker`가 의존하는 TeeBox 보존 정책(설계 시 반드시 고려):

- **active**(terminal < 24h): 풀 데이터.
- **archived**(24h–7d): `resultData` null화(300자 `resultSummary`만 유지), logs/threads 축소.
- **purged**(> 7d): 캐시·디스크에서 제거.
- 비-terminal run은 **TeeBox 재시작 시 `SERVER_RESTARTED`** 로 마킹됨.

→ 결과 데이터가 필요한 콜백은 terminal을 24h 안에 캐치해야 한다(READY 스냅샷이 이 이후를 분리).

---

## 5. 남는 한 가지 한계: submit ↔ journal 갭 (TeeBox 변경 없이는 불가)

`submit` 성공 직후 ~ journal 기록 전에 호스트가 죽으면:

- journal-after-submit → run은 돌고 호스트엔 기록 0 = **조용한 유실**.
- **PREPARED→SUBMITTED 2단계**(submit 전에 trackingId를 디스크 먼저 기록) → 복구 시 "돌았는지 모르는
  **모호한 엔트리**"로 바뀜. 재제출하면 중복 실행, 버리면 유실. **모호성 자체는 client-only로는 결정 불가.**

즉 2단계 journal은 *조용한 유실 → 가시적 모호성(alert 가능)* 으로 성격을 바꿀 뿐, 결정은 못 한다.

### 정공법: `clientRequestId` 멱등 submit (권장 fast-follow)

`trackingId`를 그대로 **`clientRequestId`로** 사용:

1. PREPARED 시 `trackingId` 생성 → **디스크 먼저 기록**(submit 전).
2. submit에 `trackingId`를 `clientRequestId`로 전달.
3. 복구 시 같은 `trackingId`로 재제출 → TeeBox가 **dedup**(이미 만들었으면 기존 runId 반환, 아니면 생성).

→ 갭이 *조용한 유실*도 *중복 실행*도 아닌 **정확히-한-번 제출**로 닫힌다(콜백은 여전히 at-least-once + 멱등).

**TeeBox 측 작업 규모(modest·contained):** submit 경로에 `clientRequestId → runId` **영속 인덱스**(run 생성과
원자적으로 기록) + TTL 정리. 장시간·고비용 작업에서 가장 무서운 실패가 **중복 실행**이므로, 본 기능을 실제
구현할 때는 이 server-side 멱등 submit을 함께 넣는 것을 권장한다.

### 구현 단계 권고

- **v1**: client-only `RunTracker`(PREPARED 2단계 + 갭 문서화 + 모호 엔트리는 자동 재제출 안 하고 `needs-attention/` 격리·alert).
- **fast-follow**: TeeBox에 `clientRequestId` 멱등 submit 추가 → 복구 결정표의 `PREPARED` 행이 dedup-safe 재제출로 승격.

---

## 6. 요약

- 현재는 **스크립트 종료 시 HTTP 콜백**(§1)으로 충분 — `RunTracker` 구현은 **보류**.
- 인바운드가 막히는 환경이 오면 §2의 **호스트 내 durable callback dispatcher**로 전환.
- 그 경우에도 **submit 갭/중복 실행을 완전히 막으려면 TeeBox의 `clientRequestId` 멱등 submit**(§5)이 정답.
