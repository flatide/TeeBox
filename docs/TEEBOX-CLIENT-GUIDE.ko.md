# TeeBoxClient 사용 매뉴얼 (한국어)

`client/com/flatide/teebox/client/TeeBoxClient.java` 는 다른 프로그램에 **임베드**해서 ProperTee TeeBox 서버를 호출하기 위한 클라이언트입니다. 이 문서는 이 클라이언트를 가져다 쓰는 개발자를 위한 가이드입니다.

> 서버(`/api/*`) 전체 스펙은 `docs/API-EXAMPLES.md`, `swagger.yaml` 을, 운영은 `docs/OPERATIONS-GUIDE.ko.md` 를 참고하세요. 이 문서는 **클라이언트 라이브러리** 관점만 다룹니다.

---

## 1. 특징

- **단일 파일 · 무의존성(zero-dependency)**: `TeeBoxClient.java` 하나만 복사하면 됩니다. JDK 외 라이브러리(Gson/Jackson 등) 불필요 — `HttpURLConnection` + 내장 미니 JSON 코덱만 사용합니다. 호스트 프로젝트의 JSON 라이브러리와 충돌하지 않습니다.
- **Java 7 소스 호환**: 람다/스트림/`java.time` 미사용. 레거시 서버에 임베드 가능합니다. (최신 JDK는 bytecode 7을 못 만들어 빌드는 `--release 8` 로 검증합니다.)
- **범위**: 스크립트 등록/수정, 실행, 추적. 폐쇄망(trusted internal network) 전제라 **기본 인증 없음**.

---

## 2. 도입 방법

두 가지 중 편한 방식을 고르세요.

### 방법 A — 소스 임베드

1. `client/com/flatide/teebox/client/TeeBoxClient.java` 를 호스트 프로젝트로 복사합니다. **패키지 경로(`com/flatide/teebox/client/`)를 유지**하세요.
2. 별도 빌드 설정·의존성 추가가 필요 없습니다. 그대로 컴파일됩니다.
3. import 후 사용:

```java
import com.flatide.teebox.client.TeeBoxClient;
```

> **Java 7 호스트는 이 방식만 가능합니다** — 호스트의 Java 7 컴파일러로 소스를 함께 컴파일하면 됩니다.

### 방법 B — 미리 빌드된 jar 사용 (소스 임베드가 부담스러울 때)

TeeBox 저장소에서 jar 를 빌드합니다.

```bash
./gradlew clientJar          # → build/libs/teebox-client-<버전>.jar  (예: teebox-client-0.7.0.jar)
./gradlew clientSourcesJar   # (선택) IDE 소스 첨부용 sources jar
```

- 생성된 jar 는 **무의존성**(JDK만 사용)이라 호스트의 JSON 라이브러리와 충돌하지 않습니다.
- 바이트코드는 `--release 8`(Java 8, major 52) 기준입니다 → **Java 8 이상 호스트**에서 사용하세요. (최신 JDK는 bytecode 7 을 생성할 수 없어 jar 의 하한은 Java 8 입니다. 진짜 Java 7 JVM 호스트는 위 **방법 A**(소스 임베드)를 쓰세요.)

호스트 빌드에 jar 를 추가하는 예:

```groovy
// Gradle
dependencies {
    implementation files('libs/teebox-client-0.7.0.jar')
}
```

```xml
<!-- Maven (로컬 설치 후) -->
<dependency>
  <groupId>com.flatide</groupId>
  <artifactId>teebox-client</artifactId>
  <version>0.7.0</version>
</dependency>
```

어느 방식이든 사용 코드는 동일합니다:

```java
import com.flatide.teebox.client.TeeBoxClient;
```

---

## 3. 빠른 시작

```java
import com.flatide.teebox.client.TeeBoxClient;
import java.util.LinkedHashMap;
import java.util.Map;

TeeBoxClient teebox = new TeeBoxClient("http://teebox-host:18080");

// 1) 스크립트 등록 + 활성화 (버전 생략 → 서버가 "1", "2" ... 자동 증가)
teebox.registerScript("calc_sum", "return {\"sum\": a + b}\n", true);

// 2) 입력값(props)과 함께 실행하고 끝날 때까지 대기 (짧은 스크립트에 적합)
Map<String, Object> props = new LinkedHashMap<String, Object>();
props.put("a", 40);
props.put("b", 2);
Map<String, Object> result = teebox.runAndWait("calc_sum", null, props, 30000L);

Map<?, ?> data = (Map<?, ?>) result.get("resultData");
Object sum = data.get("sum");   // 42.0  (숫자는 Double 로 파싱됨에 유의)
```

---

## 4. 클라이언트 생성 & 설정

### 생성자

```java
TeeBoxClient teebox = new TeeBoxClient("http://teebox-host:18080");
```

- `baseUrl` 은 필수입니다(`null`/빈 문자열이면 `IllegalArgumentException`). 끝의 `/` 는 자동으로 제거됩니다.

### 타임아웃 (체이닝 가능)

| 메서드 | 기본값 | 설명 |
|--------|--------|------|
| `setConnectTimeoutMs(int)` | 5000 | TCP 연결 타임아웃(ms) |
| `setReadTimeoutMs(int)` | 15000 | 응답 읽기 타임아웃(ms) |

```java
teebox.setConnectTimeoutMs(3000).setReadTimeoutMs(20000);
```

> ⚠️ 폴링 헬퍼(`runAndWait`, `waitForRunTerminal`, `waitForPublished`)의 전체 대기 시간은 메서드 인자 `timeoutMs` 로 별도 지정합니다. `readTimeoutMs` 는 **개별 HTTP 호출 1건**의 타임아웃입니다.

### 스레드 사용

설정값(타임아웃·토큰)은 동기화되지 않은 일반 가변 필드입니다. **인스턴스를 공유하기 전에 생성 시점에 한 번만 설정**하세요. 설정이 끝난 뒤에는 여러 스레드에서 동시에 요청해도 안전합니다(요청마다 자체 커넥션을 열고 per-request 상태를 갖지 않음). 단, in-flight 요청과 setter 를 동시에 호출하는 것은 보장되지 않습니다.

---

## 5. 인증 (선택)

폐쇄망 기본 배포는 인증이 없습니다. 운영자가 토큰을 설정한 경우에만 지정하면 됩니다.

- 공용(shared) 토큰 하나로 충분한 경우:

```java
teebox.setBearerToken("shared-token");
```

- 운영자가 네임스페이스별 토큰을 나눠 둔 경우(서버의 `clientApiToken`/`publisherApiToken`/`adminApiToken`):

```java
teebox.setClientApiToken("client-secret")
      .setPublisherApiToken("publisher-secret")
      .setAdminApiToken("admin-secret");
```

**토큰 해석 규칙**: 요청 경로에 맞는 네임스페이스 토큰을 먼저 사용하고, 없으면 `setBearerToken` 의 공용 토큰으로 폴백합니다(서버의 `apiToken` 폴백과 동일).

| 호출 경로 | 사용 토큰 | 폴백 |
|-----------|-----------|------|
| `/api/client/*` (실행/추적) | `clientApiToken` | `bearerToken` |
| `/api/publisher/*` (스크립트 관리) | `publisherApiToken` | `bearerToken` |
| `/api/admin/*` | `adminApiToken` | `bearerToken` |

---

## 6. 스크립트 관리

### 6.1 버전 정책 (중요)

- **버전 생략 시 자동 증가**: 정수 라벨 `"1"`, `"2"`, … 가 자동 부여됩니다(기존 최대 정수 + 1). 명시적 라벨(`"v1"` 같은 문자열 포함)도 그대로 사용 가능합니다.
- **활성(active) 버전 개념**: 실행 시 버전을 생략하면 **가장 최신 버전이 아니라 "활성" 버전**이 실행됩니다. 새 버전을 추가해도 `activate=true` 로 활성화하기 전까지는 기존 활성 버전이 계속 서비스됩니다(스테이징/롤백 용도).

### 6.2 등록 (register)

```java
// (A) 버전 자동 증가 + 활성화
Map<String, Object> detail = teebox.registerScript("calc_sum", source, true);
// 부여된 버전은 detail.get("activeVersion") (활성 시) 또는 versions 목록의 최신 항목

// (B) 버전 명시
teebox.registerScript("calc_sum", "v1", source, true);

// (C) description / labels 까지 지정
teebox.registerScript("calc_sum", "v1", source, "합계 계산", labels, true);
```

### 6.3 버전 추가 (= 업데이트)

```java
// 버전 자동 증가
teebox.addScriptVersion("calc_sum", newSource, true);   // 다음 정수 버전, 즉시 활성화

// 버전 명시
teebox.addScriptVersion("calc_sum", "v2", newSource, true);
```

### 6.4 활성 버전 변경

```java
teebox.activateScriptVersion("calc_sum", "1");
```

### 6.5 조회

```java
List<Object> scripts = teebox.listScripts();          // 전체 스크립트 목록
Map<String, Object> one = teebox.getScript("calc_sum"); // 상세(versions/active/settings)
String src = teebox.getScriptContent("calc_sum");        // 활성 버전 소스
String srcV1 = teebox.getScriptContent("calc_sum", "1"); // 특정 버전 소스
```

---

## 7. 실행 & 추적

### 7.1 실행 모델 (동기 vs 비동기)

TeeBox 서버는 **비동기**입니다.

- `submitRun(...)` 은 즉시 반환하고 `runId` 를 줍니다(상태 `QUEUED`, 또는 스크립트별 동시 실행 제한에 걸리면 `PENDING`). **이 시점에 스크립트는 아직 실행 전입니다.**
- 결과는 `runId` 로 폴링해서 얻습니다. 이 클라이언트의 대기 헬퍼는 모두 **클라이언트 측 폴링**이라, 타임아웃이나 연결 끊김이 발생해도 **서버의 실행은 중단되지 않습니다** — 같은 `runId` 로 다시 폴링하면 됩니다.
- 종료 상태(terminal): `COMPLETED` / `FAILED` / `SERVER_RESTARTED`.

### 7.2 제출

```java
// 버전 생략 → 활성 버전 실행
Map<String, Object> submitted = teebox.submitRun("calc_sum", props);
String runId = (String) submitted.get("runId");

// 버전 명시
teebox.submitRun("calc_sum", "1", props);
```

### 7.3 상태/결과 조회

```java
Map<String, Object> summary = teebox.getRun(runId);              // 전체 요약(published 포함)
Map<String, Object> status  = teebox.getRunStatus(runId);        // 상태만
Map<String, Object> result  = teebox.getRunResult(runId);        // 결과(종료 후)
Map<String, Object> tasks   = teebox.getRunTasksSummary(runId);  // 태스크 상태별 개수
List<Object> runs = teebox.listScriptRuns("calc_sum");           // 스크립트의 실행 목록
```

### 7.4 종료까지 대기

```java
// (A) 상태 payload 를 받고 싶을 때
Map<String, Object> terminalStatus = teebox.waitForRunTerminal(runId, 30000L);

// (B) "제출 + 대기 + 결과 반환" 한 번에 (짧은/immediate 스크립트에 적합)
Map<String, Object> result = teebox.runAndWait("calc_sum", null, props, 30000L);
Map<?, ?> data = (Map<?, ?>) result.get("resultData");
```

- `waitForRunTerminal` 은 50ms→1s 백오프로 폴링하며, 종료 상태가 되면 **상태 payload** 를 반환합니다. `timeoutMs` 초과 시 `IOException`(메시지에 `runId` 포함 → 재폴링 가능).
- `runAndWait` 는 제출→대기→결과 조회까지 수행하고 **결과 payload**(`getRunResult`)를 반환합니다. 실행이 `COMPLETED` 가 아니면 `IOException`(서버 `errorMessage` 포함)을 던집니다.

---

## 8. 출력 캡처 (긴 작업의 ID 등을 중간에 받기)

스크립트가 stdout 으로 출력하는 값(예: 백그라운드 잡 ID)을 정규식으로 캡처해 `published` 맵에 노출시키고, 클라이언트가 그 값을 기다릴 수 있습니다.

### 8.1 outputRule 생성

```java
import java.util.List;
import java.util.ArrayList;

List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();

// 간단형: stdout 에서 pattern 의 첫 매치(캡처 그룹 1)를 jobId 로 게시
rules.add(TeeBoxClient.outputRule("jobId", "JOB_ID=(\\S+)"));

// 전체 지정형: outputRule(publishKey, pattern, stream, captureGroup, firstOnly)
rules.add(TeeBoxClient.outputRule("token", "TOKEN:(\\w+)", "stdout", 1, true));
```

| 파라미터 | 의미 | 기본값(간단형) |
|----------|------|----------------|
| `publishKey` | `published` 맵에 게시될 키 | (필수) |
| `pattern` | 정규식 | (필수) |
| `stream` | `stdout` / `stderr` | `stdout` |
| `captureGroup` | 사용할 캡처 그룹 번호 | `1` |
| `firstOnly` | 첫 매치만 게시할지 | `true` |

### 8.2 규칙과 함께 등록

```java
teebox.registerScript("long_job", source, true, rules);
// 또는 버전 추가 시: teebox.addScriptVersion("long_job", source, true, rules);
```

### 8.3 게시된 값 대기

```java
String runId = (String) teebox.submitRun("long_job", props).get("runId");

// 스크립트가 실행 도중 jobId 를 출력하면 그 값을 받아옴 (실행은 계속됨)
Object jobId = teebox.waitForPublished(runId, "jobId", 60000L);
```

- `waitForPublished` 는 `getRun` 의 `published` 맵을 폴링합니다. 키가 게시되면 그 값을 반환합니다(반환해도 실행은 멈추지 않음). 실행이 키를 게시하지 못한 채 종료되거나 `timeoutMs` 초과 시 `IOException`.

---

## 9. 반환값 형태 & 예외

### 반환 타입

내장 JSON 코덱이 응답을 다음 타입으로 매핑합니다.

| JSON | Java |
|------|------|
| object | `Map<String, Object>` (`LinkedHashMap`, 입력 순서 유지) |
| array | `List<Object>` |
| string | `String` |
| number | `Double` ← **정수처럼 보여도 `Double`** (예: `42` → `42.0`) |
| true/false | `Boolean` |
| null | `null` |

> 숫자를 정수로 다루려면 `((Number) v).intValue()` / `.longValue()` 로 변환하세요.

### 예외

| 예외 | 발생 상황 |
|------|-----------|
| `IllegalArgumentException` | 필수 인자 누락(`scriptId`/`content`/`runId` 등), 잘못된 `baseUrl` |
| `IOException` | HTTP 상태 불일치(메시지에 `메서드 경로 -> HTTP 코드: 본문`), 네트워크 오류, 대기 타임아웃, 비-`COMPLETED` 종료(`runAndWait`) |
| `InterruptedException` | 대기 헬퍼(`runAndWait`/`waitForRunTerminal`/`waitForPublished`)가 `Thread.sleep` 중 인터럽트됨 |

```java
try {
    Map<String, Object> result = teebox.runAndWait("calc_sum", null, props, 30000L);
} catch (IOException e) {
    // HTTP 오류 / 실패 종료 / 타임아웃 — e.getMessage() 에 진단 정보
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

---

## 10. 전체 메서드 레퍼런스

### 설정 (체이닝 가능, `TeeBoxClient` 반환)
- `setConnectTimeoutMs(int)` / `setReadTimeoutMs(int)`
- `setBearerToken(String)` / `setClientApiToken(String)` / `setPublisherApiToken(String)` / `setAdminApiToken(String)`

### 스크립트
- `registerScript(scriptId, content, activate)` — 버전 자동 증가
- `registerScript(scriptId, content, activate, outputRules)` — 자동 증가 + 출력 규칙
- `registerScript(scriptId, version, content, activate)` — 버전 명시
- `registerScript(scriptId, version, content, description, labels, activate)`
- `addScriptVersion(scriptId, content, activate)` — 자동 증가
- `addScriptVersion(scriptId, content, activate, outputRules)`
- `addScriptVersion(scriptId, version, content, activate)` — 버전 명시
- `activateScriptVersion(scriptId, version)`
- `listScripts()` → `List<Object>`
- `getScript(scriptId)` → `Map`
- `getScriptContent(scriptId)` / `getScriptContent(scriptId, version)` → `String`
- `static outputRule(publishKey, pattern)` / `static outputRule(publishKey, pattern, stream, captureGroup, firstOnly)` → `Map`

### 실행/추적
- `submitRun(scriptId, props)` / `submitRun(scriptId, version, props)` → `Map`(`runId`)
- `getRun(runId)` / `getRunStatus(runId)` / `getRunResult(runId)` / `getRunTasksSummary(runId)` → `Map`
- `listScriptRuns(scriptId)` → `List<Object>`
- `waitForRunTerminal(runId, timeoutMs)` → 상태 `Map`
- `runAndWait(scriptId, version, props, timeoutMs)` → 결과 `Map`
- `waitForPublished(runId, key, timeoutMs)` → 게시된 값 `Object`

### JSON 유틸 (선택)
- `TeeBoxClient.Json.parse(String)` → `Object`
- `TeeBoxClient.Json.write(Object)` → `String`

---

## 11. 엔드투엔드 예제 — 긴 작업 추적

```java
import com.flatide.teebox.client.TeeBoxClient;
import java.util.*;

TeeBoxClient teebox = new TeeBoxClient("http://teebox-host:18080");

// stdout 의 "JOB_ID=xxxx" 를 jobId 로 게시하는 출력 규칙
List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
rules.add(TeeBoxClient.outputRule("jobId", "JOB_ID=(\\S+)"));

// 등록 + 활성화 (자동 버전)
teebox.registerScript("batch", batchSource, true, rules);

// 실행 제출 (비동기)
Map<String, Object> props = new LinkedHashMap<String, Object>();
props.put("target", "/data/in");
String runId = (String) teebox.submitRun("batch", props).get("runId");

try {
    // 실행 중간에 jobId 확보 (실행은 계속 진행)
    Object jobId = teebox.waitForPublished(runId, "jobId", 60000L);
    System.out.println("started job: " + jobId);

    // 최종 종료까지 대기
    Map<String, Object> status = teebox.waitForRunTerminal(runId, 600000L);
    if ("COMPLETED".equals(String.valueOf(status.get("status")))) {
        Map<String, Object> result = teebox.getRunResult(runId);
        System.out.println("result: " + result.get("resultData"));
    } else {
        System.out.println("run ended: " + status.get("status"));
    }
} catch (IOException e) {
    // HTTP/타임아웃 오류
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

---

## 12. 주의사항 요약

- **숫자는 `Double`** 로 들어옵니다. 정수 변환 필요.
- **버전 생략 실행 = 활성 버전** (최신 아님).
- 대기 헬퍼의 타임아웃은 **클라이언트 측**일 뿐, 서버 실행을 멈추지 않습니다. 같은 `runId` 로 재폴링하세요.
- 프로세스 중단(kill)은 클라이언트 범위 밖입니다 — TeeBox 관리 UI/`/api/admin/...` 또는 admin API 를 사용하세요.
- 파일을 직접 수정할 경우 **Java 7 호환**(람다/스트림/`java.time` 금지)을 유지하세요.
