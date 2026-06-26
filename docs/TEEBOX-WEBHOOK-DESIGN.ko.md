# TeeBox Webhook Delivery 서브시스템 설계

> **상태: MVP 구현됨(아래 "구현 현황"), 확장 기능은 설계 단계.** 작성일 2026-06-26.
> run이 terminal에 도달하면 TeeBox가 호스트 URL로 통지를 보내고, **성공(2xx) 또는 DEAD 전환 전까지 내구적으로 재시도**하는
> 서버사이드 webhook/outbox 서브시스템. 관련 배경·대안 비교는 [TEEBOX-CLIENT-CALLBACK-DESIGN.ko.md](TEEBOX-CLIENT-CALLBACK-DESIGN.ko.md) 참고.

---

## 구현 현황 (MVP, 2026-06-26)

아래 설계 중 **MVP 범위가 코드에 반영됨**. 패키지 `com.flatide.teebox.webhook`:
`WebhookTarget` / `WebhookDelivery` / `WebhookStore` / `WebhookHttpClient` / `WebhookDispatcher`,
통합: `RunRequest.callback`·`RunInfo.callback`(영속), `TeeBoxServer.parseCallback`(submit 검증),
`RunManager`(terminal 훅 enqueue + 부팅/주기 reconcile + shutdown), `TeeBoxConfig`(아래 3키).
테스트: `WebhookDispatcherTest`(validate/enqueue/reconcile/2xx 전달/실패 재시도), `WebhookServerIntegrationTest`(live 서버: terminal 전달 + 콜백 거절 — webhooks disabled/allowlist 위반 시 400), `StandaloneClientIntegrationTest.submitWithCallbackUrlThroughDeployableClient`(임베디드 클라이언트 4-인자 `submitRun(...,callbackUrl)` 종단 검증).

**구현된 config 키:** `webhookEnabled`(기본 false), `webhookUrlAllowlist`(host[:port] **콤마 구분**), `webhookTimeoutMs`(기본 10000).
나머지 §11 키와 튜닝값은 **현재 코드 상수**(`WebhookDispatcher`: backoff base 5s·cap 10m·max 12회·concurrency 4·scan 2s). **tombstone(DELIVERED/DEAD) 보존 = run purge horizon(`runArchiveRetentionMs`) + 24h** 로 잡아 항상 run보다 오래 남긴다 → §9 갭 봉쇄의 근거.
**reconcile 동작:** (a) 부팅 시 비-purge terminal run **전체** 1회 + (b) maintenance마다 **최근 1h** 종료분(둘 다 인메모리 캐시 기반, 디스크 재로딩 없음). 설계 §4의 *in-memory enqueue retry queue*는 **MVP 미구현** — 대신 이 reconcile + tombstone-outlives-run 으로 enqueue 저장 실패/크래시 갭을 회수한다(시간 window 휴리스틱 없음).

**MVP 미구현(설계만):** HMAC 서명, 사용자 인증 헤더/redaction, payloadMode(현재 **SUMMARY 고정**), 고급 SSRF hardening(사설/내부 IP 차단·redirect 대상 재검증·DNS rebinding 완화 — **redirect 비활성·timeout은 MVP에 있음**; 현재 게이트는 scheme+allowlist), Admin API/UI, per-script 기본 webhook, fan-out, webhook index.
payload는 SUMMARY 고정: `event, runId, scriptId, version, status, endedAt, errorMessage, resultSummary, published`.

---

## 0. 목표 / 원칙

- **내구적 push**: 완료 통지를 TeeBox가 책임지고 **성공(2xx) 또는 DEAD 전환 전까지** 재시도(maxAge/maxAttempts 한도 내). 호스트가 잠깐 다운돼도 복귀 후 받는다.
- **run과 분리**: run은 완료 즉시 종료(슬롯 반납). 통지 재시도가 run 슬롯·스레드를 점유하지 않는다(스크립트 루프 방식의 핵심 결함 제거).
- **TeeBox 변경은 자기 영역 안에서**: run 기록과 delivery 기록을 **둘 다 TeeBox가 소유**하므로 submit-gap 류의 결정불가 문제가 없다(§9).
- **실행에 영향 없음**: webhook 서브시스템의 어떤 오류도 run 실행을 실패시키지 않는다(§13).
- **opt-in**: `webhookEnabled=false` 기본. 켠 경우에만 동작. **꺼진 상태에서 `callback`이 실린 submit은 silent ignore가 아니라 400으로 거절**(§12) — 내구 통지 약속을 조용히 깨지 않는다.
- 전달 보증은 **at-least-once** + 수신자 **멱등**(키 = `runId`).

## 1. 아키텍처 개요

```
submit(body.callback)        run 실행(background pool)            완료
   |  parseRunOptions           RunManager.executeRun              | markCompleted/markFailed
   v                                                               v
RunRequest.callback --persist--> RunInfo.callback --terminal hook--> WebhookDispatcher.onRunTerminal(run)
                                                                       |  (enqueue, payload 스냅샷)
                                                                       v
                                                  WebhookStore (dataDir/webhooks/<runId>.json)  [PENDING]
                                                                       ^
        +---------------- Scheduler(1 thread, due 선별) --------------+
        +--> HTTP worker pool(bounded) -- (deliver: allowlist 재검증) WebhookHttpClient.post(timeout, redirect off) --> 호스트 수신 URL
                 2xx -> DELIVERED      실패 -> attempts++, backoff(nextAttemptAt)      >=max -> DEAD
```

핵심: **scan(선별)과 POST(실행)를 분리**한다. POST는 별도 bounded pool에서 — 행오프 수신자가 스케줄러/maintenance 스레드를 막지 않도록(반드시 connect/read timeout).

## 2. 데이터 모델

### WebhookTarget (제출 시 지정, run에 실려 감)
> **MVP는 `url` 1개 필드만** 보유/검증. 제출 본문도 `"callback": "<url>"` 또는 `"callback": { "url": "<url>" }`만 파싱. 아래 `headers`/`payloadMode`·per-script 기본 콜백·`authRef`·서명 시크릿 정책은 모두 **Phase 2(설계)**.
```
url           : String   (필수, allowlist 검증)                    # MVP
headers       : Map<String,String>  (선택, 사용자 정의 헤더)         # Phase 2
payloadMode   : RUN_ID | SUMMARY | FULL   (선택)                    # Phase 2
```
- 제출 본문(설계 전체형): `POST /api/client/scripts/{id}/runs` body 에 `"callback": { "url": ..., "headers": {...}, "payloadMode": "SUMMARY" }`. **MVP는 `"callback": "<url>"` 또는 `{ "url": "<url>" }`만**.
- per-script 기본 콜백 URL(선택)은 registry 실행 설정에 둘 수 있다(미지정 run에 적용).
- **서명 시크릿은 per-run으로 받지 않는다** — 서버 config(§7). 호출자가 서명키를 고르게 하면 위조 방지 의미가 사라짐.
- **인증 헤더(민감정보) 정책**: per-run `headers`에 `Authorization`/`Cookie`/`X-Api-Key` 등이 실릴 수 있다. **권장은 평문 헤더가 아니라 config 키 참조**(`"authRef": "webhook.token.hostA"`) — 시크릿을 store에 저장하지 않고 전송 시점에 `WebhookHttpClient`가 config에서 해소해 주입(재시작에도 안전). 평문 헤더를 받는 경우엔 §7의 redaction을 거쳐 **저장/로그/Admin UI·API 어디서도 평문 노출 금지**.

### WebhookDelivery (영속 레코드, deliveryId == runId)
> **run당 정확히 하나의 delivery**(terminal 이벤트는 run당 1회). `deliveryId=runId` 로 두면 dedup·reconcile이 자명해진다.
> **MVP 실제 필드**(아래에서 `headers`/`payloadMode` 제외, `payloadSnapshot` → `payload` Map):
```
deliveryId(=runId), runId, scriptId, version,
url,                           # MVP   (headers, payloadMode 는 Phase 2)
state         : PENDING | DELIVERED | DEAD,
attempts      : int,
nextAttemptAt : long(ms),
lastStatus    : int|null,     // 마지막 HTTP status (0=transport 실패)
lastError     : String|null,
createdAt, deliveredAt|null,
payload       : Map|null      // enqueue 시점 SUMMARY 스냅샷(보존창과 분리). 설계 명칭 payloadSnapshot
```

## 3. 영속 — WebhookStore

`RunStore`를 그대로 본뜬다: `dataDir/webhooks/`, 메서드 `synchronized`, 쓰기는 **temp 파일 -> `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`**. **MVP는 index 파일 없이 디렉토리 스캔**(예상 볼륨에서 충분); `index.json` 목록 가속은 Phase 2.
- **MVP**: `save/load/loadAll(디렉토리 스캔)/exists/delete`. 상태 필터 `query(state,...)`는 Phase 2 — 현재는 `loadAll()` 후 `WebhookDispatcher`가 메모리에서 선별.
- 파일명 `<runId>.json`.

## 4. WebhookDispatcher

### enqueue: `onRunTerminal(RunInfo run)`
1. `run.callback == null` -> return(통지 대상 아님).
2. URL allowlist 검증 실패 -> `AUDIT BLOCKED` 로그 + delivery를 `DEAD`로 즉시 기록(가시화).
3. payload 스냅샷 빌드(§6). **MVP는 SUMMARY 고정**; `payloadMode`·`maxPayloadBytes`·STREAM_FILE 디스크립터 분기는 Phase 2.
4. `WebhookStore.save( PENDING, attempts=0, nextAttemptAt=now )`.
5. **모든 단계 try/catch** — 실패해도 run 결과/상태에 영향 없음(§13).

### enqueue 내구성 (WebhookStore 저장 실패 대비)
enqueue의 `WebhookStore.save`가 I/O로 실패하면 **파일이 없어 scanner가 재시도 대상을 알 수 없다**(단순 "다음 tick"만으로는 복원 불가).
- **(MVP) 주기적 reconcile + tombstone-outlives-run**(부팅 시 비-purge terminal run 전체 1회 + maintenance마다 최근 1h, §9): `terminal + callback 있음 + delivery 레코드 없음`인 run을 찾아 enqueue. tombstone(DELIVERED/DEAD)이 run보다 오래 남으므로 "레코드 없음 = 아직 enqueue 안 됨"이 **시간 window 없이** 결정 가능 → 메모리 상태를 들고 죽어도 run 기록(source of truth)으로 결국 복원된다.
- **(미구현, 설계 옵션) in-memory enqueue retry queue**: 저장 실패한 `runId`를 메모리 큐에 적재해 다음 tick에 재저장 — reconcile 주기를 기다리지 않고 '완료 직후' 갭을 더 빨리 메우는 보강책. MVP는 위 reconcile만으로 회수하므로 미구현.

### scan tick (전용 단일 스레드 스케줄러, 주기 `webhookScanIntervalMs`; **MVP 상수 2s**, 설계 기본 1s)
- **MVP**: `loadAll()` 후 메모리에서 `state==PENDING && nextAttemptAt <= now` 만 골라 HTTP worker pool에 제출(중복 제출 방지 = in-flight 집합). 상태 인덱스 `query(PENDING)`는 Phase 2.

### worker: deliver(record)
```
resp = webhookHttpClient.post(url, body, deliveryId=runId, webhookTimeoutMs)   // MVP: redirect 비활성+timeout만. allowlist는 deliver()가 호출 직전 재검증; 서명/커스텀 헤더는 Phase 2
if 2xx:           state=DELIVERED, deliveredAt=now, lastStatus=resp.status      -> save
else:             attempts++, lastStatus=resp.status(또는 0), lastError=...
                  if attempts>=maxAttempts || age>maxAgeMs: state=DEAD
                  else: nextAttemptAt = now + backoff(attempts)                 -> save
```

### backoff
`min(cap, base * 2^(attempts-1))` + **지터**(±20%). **MVP 상수**: `base=5s`, `cap=10m`, `maxAttempts=12`(maxAge 미적용). 설계 목표값 `cap=1h`·`maxAttempts=20`·`maxAgeMs=24h`은 §11 키로 외부화 예정(미구현).

## 5. 상태 머신
```
PENDING --2xx--> DELIVERED --(tombstone retention 후)--> 삭제
PENDING --실패, attempts<max--> PENDING(backoff)
PENDING --attempts>=max | allowlist 위반--> DEAD --(tombstone retention 후)--> 삭제
DEAD/PENDING --admin retry(Phase 2)--> PENDING(attempts/nextAttemptAt 리셋)
```

> **MVP**: 종료 조건은 `attempts>=12`만(`maxAge` 미적용). tombstone retention = `runArchiveRetentionMs`+24h. `admin retry`는 Phase 2.

## 6. 페이로드 & 서명

> **MVP 실제 payload**(SUMMARY 고정): `event, runId, scriptId, version, status, endedAt, errorMessage, resultSummary, published`(§"구현 현황"). 아래 JSON·`payloadMode`(RUN_ID/SUMMARY/FULL)·`deliveryId`/`result`/`stream` 필드는 **설계(Phase 2)**.

Body(JSON):
```json
{ "event": "run.terminal",
  "deliveryId": "<runId>", "runId": "<runId>", "scriptId": "...", "version": "...",
  "status": "COMPLETED|FAILED|SERVER_RESTARTED",
  "endedAt": 1750900000000,
  "resultSummary": "...",
  "result": { },
  "stream": { "stream": true, "contentType": "...", "size": 123 },
  "errorMessage": "..." }
```
- `payloadMode`:
  - **RUN_ID**: 최소형(runId/status만) — 수신자가 `/result` 재조회. **24h 결과 보존창 내 전달 필요.**
  - **SUMMARY**(기본): `resultSummary` 포함 — 보통 이걸로 충분, 자기완결.
  - **FULL**: `result` 인라인(캡 초과 시 자동 강등 -> `resultIncluded:false` + SUMMARY). **STREAM_FILE은 절대 바이트 인라인 안 함**(디스크립터만).
- 이벤트 헤더(서버가 항상 부여):
  - `X-TeeBox-Event: run.terminal`  *(MVP)*
  - `X-TeeBox-Delivery: <runId>`  <- 수신자 dedup 키 *(MVP)*
  - `Content-Type: application/json; charset=utf-8`  *(MVP)*
  - `X-TeeBox-Signature: sha256=<HMAC-SHA256(secret, rawBody)>`  *(Phase 2 — MVP 미부여)*

## 7. 보안

> **MVP 적용 범위**: scheme 게이트(**http/https 모두 허용**) + `webhookUrlAllowlist`(host[:port]) **이중 검증**(submit 시점 + enqueue/deliver 시점)뿐. 아래의 사설/내부대역 차단·redirect 재검증·DNS rebinding 완화·HMAC 서명·헤더 redaction·`authRef`는 **모두 설계(Phase 2), MVP 미구현**.

- **URL allowlist + 이중 검증**(`webhookUrlAllowlist`, host[:port] 목록):
  - **submit 시점에 먼저 검증** → 잘못된 콜백 URL로 run이 만들어지는 것을 막음(위반 -> `400`/`409`로 submit 거절).
  - **terminal enqueue 시점에 한 번 더** 검증(그새 allowlist가 바뀌었을 수 있음; 위반 -> `DEAD` + `AUDIT BLOCKED`).
- **(Phase 2) SSRF 방어 기본 ON**(per-run URL을 허용하므로 옵션이 아니라 기본 — MVP는 scheme+allowlist만):
  - **scheme 제한**: `https`만(명시적으로 `webhookAllowHttp=true`일 때만 `http`). 그 외 scheme 거부.
  - **사설/내부 대역 차단**: loopback(`127.0.0.0/8`,`::1`), private(`10/8`,`172.16/12`,`192.168/16`), link-local/메타데이터(`169.254.0.0/16`, `fe80::/10`), ULA(`fc00::/7`) 거부.
  - **redirect 비활성화**(`setInstanceFollowRedirects(false)` — **MVP에 이미 적용됨**); 따라가야 할 경우 **매 홉 대상 IP를 재검증**하는 부분이 Phase 2.
  - **DNS rebinding 완화**: 검증한 IP로 직접 연결(또는 연결 직전 재해석·재검증).
- **HMAC 서명**(`webhookSigningSecret`): 수신자 진위 검증. 미설정 시 서명 생략(경고 로그).
- **헤더 redaction**: `Authorization`/`Cookie`/`X-Api-Key`/`Proxy-Authorization` 및 `webhookSensitiveHeaders` 지정 헤더는 **Admin API/UI·로그에서 항상 마스킹**(`***`)으로만 노출. 시크릿은 §2의 `authRef`(config 키 참조)로 받아 **store에 아예 저장하지 않는 것을 권장**. 평문 헤더를 받은 경우 store 파일(dataDir 권한 보호)에는 남되 노출 표면에선 마스킹.
- **timeout 필수**: `webhookTimeoutMs`(기본 10s) — 행오프 수신자가 워커를 잠그지 못하게.
- 아웃바운드는 **TeeBox 전용 `WebhookHttpClient`**(§12)로 보낸다 — ProperTee 런타임용 `TeeBoxPlatformProvider.httpRequest`와 책임 분리. **MVP는 redirect 비활성+timeout만** 담당(allowlist 검증은 `WebhookDispatcher`, SSRF 가드·redaction은 Phase 2).

## 8. 전달 보증 / 멱등 / 보존창

- **at-least-once**: 2xx 응답 유실 시 재전송 가능 -> 수신자는 `X-TeeBox-Delivery(runId)` 키로 **멱등** 처리.
- **순서 보장 없음**: run당 단일 이벤트라 사실상 무관.
- **보존창 결합**(payloadMode는 Phase 2): RUN_ID 모드라면 24h(결과)/7d(status) 내 전달 필요. **MVP는 SUMMARY 고정**이라 enqueue 스냅샷(`payload`)으로 분리 -> 결과 보존창과 무관하게 재시도 가능.

## 9. 재시작 복구 & 갭 봉쇄 (핵심 장점)

- 부팅 시 `WebhookStore`의 `PENDING` 전부 자동 재개(`nextAttemptAt` 과거면 즉시 재시도).
- **enqueue 갭 봉쇄(서버사이드라 결정 가능)**: "run terminal + `callback` 있음 + delivery 레코드 없음"이 **결정 가능**한 이유는 **tombstone이 run보다 오래 남기 때문**(보존 = run purge + 24h). 그래서 "레코드 없음"은 항상 *아직 enqueue 안 됨*을 뜻하고(이미 배달됐다면 tombstone이 남아 `store.exists`로 skip), **시간 window 없이** 안전하게 재enqueue한다.
  - **부팅 reconcile은 비-purge terminal run 전체**를 본다 → run 영속 직후 ~ enqueue 직전 크래시는 **다운 시간과 무관하게**(run 보존창 내) 복원. 주기 reconcile은 런타임 저장 실패(완료 '직후')만 잡으면 되므로 최근 1h만.
  - **잔여 한계**: 다운이 run purge 기간(`runArchiveRetentionMs`, 기본 7d)을 넘기면 run 자체가 purge되어 복원 대상이 사라짐(설계상 당연 — 결과 보존창도 그때 끝남).
  - `deliveryId=runId` 라 "이미 있나?"가 파일 존재 검사로 끝나 멱등.
  - -> 호스트사이드 설계(클라이언트 §5)가 `clientRequestId` 없이는 못 닫던 갭을, **여기선 추가 식별자 없이 닫는다.**
- **SERVER_RESTARTED run도 통지 대상**: 재시작 복구 경로(비-terminal run을 `SERVER_RESTARTED`로 마킹)에서도 `callback`이 있으면 enqueue -> 호스트가 "이 run은 못 끝났다"를 통지받음.

## 10. Admin API / UI (Phase 2 — MVP 미구현)

> **MVP 미구현.** 아래 라우트·UI는 설계만. MVP에서는 webhook 상태를 `${dataDir}/webhooks/<runId>.json` 파일로 직접 확인하거나 `WebhookDispatcher.listDeliveries()`로 인트로스펙션.

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/admin/webhooks` | 목록(state/runId 필터, offset/limit) |
| GET | `/api/admin/webhooks/{runId}` | delivery 상세 |
| POST | `/api/admin/webhooks/{runId}/retry` | 즉시 재시도(state=PENDING, attempts/nextAttemptAt 리셋) |
| POST | `/api/admin/webhooks/{runId}/cancel` | DEAD로 종료 |

- Admin UI: webhook 목록 페이지 + nav에 **dead-letter 카운트** 노출(운영 가시성). `AdminPageRenderer` 패턴 따름.

## 11. Config

`TeeBoxConfig`(`getSetting`)에 추가 — 문자열/불리언. **아래는 설계 전체 키 목록이며, MVP에서 실제 구현된 config 키는 `webhookEnabled`/`webhookUrlAllowlist`/`webhookTimeoutMs` 3개뿐**(나머지는 미구현; 일부는 아래 "구현 현황"의 `WebhookDispatcher` 하드코딩 상수로만 존재):

| 키(`propertee.teebox.`) | 기본 | 설명 |
|---|---|---|
| `webhookEnabled` | `false` | 서브시스템 on/off(opt-in) |
| `webhookUrlAllowlist` | — | host[:port] 목록(**콤마 `,` 구분** — `:`는 host:port와 충돌하므로 streamRoots와 달리 pathSeparator를 쓰지 않음) |
| `webhookSigningSecret` | — | HMAC 시크릿(미설정 시 서명 생략 + 경고) |
| `webhookDefaultUrl` | — | run에 callback 미지정 시 전역 기본 대상(선택) |
| `webhookPayloadMode` | `SUMMARY` | RUN_ID/SUMMARY/FULL |
| `webhookConcurrency` | `4` | HTTP worker pool 크기 |
| `webhookTimeoutMs` | `10000` | per-POST 타임아웃 |
| `webhookAllowHttp` | `false` | `true`라야 `http://` 허용(기본 `https`만). SSRF 가드와 연동 |
| `webhookSensitiveHeaders` | (기본 목록) | 추가로 마스킹할 헤더 이름 목록(`Authorization`/`Cookie`/`X-Api-Key`/`Proxy-Authorization`은 항상 포함) |

system-property 전용 duration(설계 목표, **MVP 미구현** — 현재는 `WebhookDispatcher` 하드코딩 상수):
- `webhookScanIntervalMs`(설계 1s / **MVP 2s**), `webhookBackoffBaseMs`(**5s**), `webhookBackoffCapMs`(설계 1h / **MVP 10m**),
  `webhookMaxAttempts`(설계 20 / **MVP 12**), `webhookMaxAgeMs`(24h, **MVP 미적용**), `webhookRetentionMs`(7d; **MVP tombstone 보존은 `runArchiveRetentionMs`+24h**), `webhookMaxPayloadBytes`(256KB).

## 12. 통합 지점(구체)

| 파일 | 변경 |
|---|---|
| `RunRequest` | `WebhookTarget callback` 필드 추가 |
| `TeeBoxServer.parseCallback` (parseScriptRunRequest 내) | `raw.get("callback")` 파싱(String 또는 `{url}`) -> `request.callback`; **`webhookEnabled=false`인데 callback 있으면 400**; **submit 시점 scheme+allowlist 검증**(위반 400). SSRF 사설대역 차단은 Phase 2 |
| `RunInfo` | `callback`(WebhookTarget, **영속**) 필드 추가 -> `RunStore` 직렬화 포함 |
| `RunManager.executeRun` finally | `markCompleted/markFailed` 뒤 `webhookDispatcher.onRunTerminal(run)`(try/catch) |
| `RunManager`(startup recovery) | `SERVER_RESTARTED` 마킹 경로에도 enqueue; 부팅 시 webhook reconcile 호출 |
| `WebhookDispatcher`/`WebhookStore`/`WebhookTarget`/`WebhookDelivery`/`WebhookHttpClient` | 신규(`WebhookHttpClient` = **MVP는 redirect 비활성+timeout 전용 전송**; allowlist 검증은 `WebhookDispatcher`, SSRF 가드·redaction은 Phase 2. ProperTee용 provider와 분리) |
| `TeeBoxServer` | `/api/admin/webhooks*` 라우트 + Admin UI 페이지/nav 카운트 **(Phase 2 — MVP 미구현)** |
| `TeeBoxConfig` | §11 키 |
| `TeeBoxMain`/`RunManager` 생성부 | `WebhookDispatcher` 배선(platform/config/store), start/stop 통합 |

## 13. 실패 격리

- enqueue·scan·POST 어느 것도 run 실행 스레드에서 동작하지 않거나(스케줄러/워커), 동작하더라도(enqueue) **try/catch로 삼켜** run 결과·상태를 변경하지 않는다.
- **enqueue 저장 실패**는 파일이 없어 scanner가 모르므로 → **부팅 전체 reconcile + 주기 reconcile + tombstone-outlives-run**(§9)으로 회수(인메모리 retry queue는 미구현 — 설계 옵션). 그 외 store I/O 오류는 로깅 후 다음 tick 재시도. 디스패처 전체 장애가 run 처리량에 영향 주지 않음.

## 14. 구현 단계

1. **모델·영속**: WebhookTarget/Delivery + WebhookStore(+테스트: 원자적 저장/조회/reconcile).
2. **디스패처 코어**: enqueue + scan + worker + backoff + 상태머신(+테스트: mock `WebhookHttpClient`로 2xx/실패/DEAD/재시도).
3. **통합**: RunRequest/RunInfo/parseRunOptions/executeRun 훅 + 부팅 reconcile + SERVER_RESTARTED.
4. **보안 — MVP**: allowlist 이중검증 + redirect 비활성 + timeout(+테스트: 위반->DEAD). **Phase 2**: HMAC 서명·SSRF hardening.
5. **Admin API/UI (Phase 2)**: 목록/상세/retry/cancel + dead-letter 카운트.
6. **Config·문서**: §11 키, OPERATIONS-GUIDE 반영, swagger.

## 15. 테스트 계획

**MVP(구현됨):**
- `WebhookDispatcherTest`: mock `WebhookHttpClient`(StubClient) 주입 -> 2xx=DELIVERED, 5xx/transport 실패=backoff·재시도(PENDING 유지), allowlist/scheme/host 검증, 위반=DEAD, enqueue 멱등+payload 스냅샷, `reconcile`이 terminal+callback+no-delivery run만 enqueue하고 **기존 tombstone(DELIVERED)은 건드리지 않음**(재배달 방지).
- `WebhookServerIntegrationTest`(live server): submit(callback) -> 로컬 수신 스텁이 terminal payload + `X-TeeBox-Delivery(runId)` 헤더 수신; webhooks disabled/allowlist 위반 submit은 400.
- `StandaloneClientIntegrationTest.submitWithCallbackUrlThroughDeployableClient`: 임베디드 4-인자 `submitRun(...,callbackUrl)` 종단 전달.

**Phase 2(계획):**
- `WebhookStoreTest`: 원자적 저장/조회 단위 분리(파일 존재 멱등).
- `WebhookSecurityTest`: HMAC 서명값, loopback/private/link-local/메타데이터 IP 차단, redirect 재검증, 민감 헤더 redaction(store/API/로그) 및 `authRef` 미저장.
- `RunManager` 통합: 부팅 전체 + 주기 1h reconcile 분리, restart 후 PENDING 재개.

---

## 부록: 세 방식 비교(재확인)

| | 스크립트 push 루프 | 호스트 pull | **TeeBox webhook(본 설계)** |
|---|---|---|---|
| run 슬롯 점유 | 다운 내내 | 잠깐 | 잠깐 |
| TeeBox 재시작 견딤 | x | o | o(PENDING 재개) |
| 호스트 장기 다운 | x | 보존창 내 o | 보존창/maxAge 내 o |
| enqueue 갭 | — | submit 갭(clientRequestId 필요) | **reconcile + tombstone-outlives-run으로 봉쇄(추가 식별자 불필요)** |
| 호스트 측 작업 | 없음 | pull 북마크 | **멱등 수신 엔드포인트만** |
| 비용 | 낮음(but 풀 고갈) | 낮음 | 신규 서브시스템 + SSRF/서명 관리 |
