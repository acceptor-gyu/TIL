# 실행 시간이 1분 이상인 쿼리와 DB Connection과의 관계

## 개요

실행 시간이 1분 이상 소요되는 장시간 쿼리(Long-Running Query)는 DB Connection을 장시간 점유하여 커넥션 풀 고갈(Pool Exhaustion)을 유발한다. 이는 다른 요청들이 커넥션을 획득하지 못해 전체 애플리케이션이 응답 불가 상태에 빠지는 장애로 이어질 수 있다.

## 상세 내용

### 장시간 쿼리가 Connection에 미치는 영향

#### 기본 동작 흐름

```
[요청 1] ─── getConnection() ─── 쿼리 실행 (1분+) ─── 커넥션 반환
[요청 2] ─── getConnection() ─── 쿼리 실행 (1분+) ─── 커넥션 반환
   ...
[요청 10] ── getConnection() ── 쿼리 실행 (1분+) ── 커넥션 반환

[요청 11] ── getConnection() ── ⏳ 대기... ── connectionTimeout 초과 ── SQLException!
```

커넥션 풀의 `maximumPoolSize`가 10일 때, 1분 이상 걸리는 쿼리 10개가 동시에 실행되면 **풀이 즉시 고갈**된다. 이후 들어오는 모든 요청은 `connectionTimeout`(기본 30초)만큼 대기한 뒤 예외가 발생한다.

#### 시나리오: 풀 사이즈 10, 쿼리 실행 시간 1분

```
시간  0초: 커넥션 10개 모두 사용 중 (장시간 쿼리)
시간  1초: 새 요청 → 대기 시작
시간 30초: connectionTimeout 초과 → SQLException 발생
         "Connection is not available, request timed out after 30000ms"
시간 60초: 최초 쿼리들이 끝나야 커넥션 반환 시작
```

**30초 동안 들어온 모든 요청이 실패**하게 된다.

---

### HikariCP 커넥션 풀의 주요 타임아웃 설정

Spring Boot의 기본 커넥션 풀인 HikariCP의 핵심 설정:

```yaml
spring:
  datasource:
    hikari:
      # 풀에서 커넥션을 얻기 위한 최대 대기 시간 (기본: 30초)
      connection-timeout: 30000

      # 풀의 최대 커넥션 수 (기본: 10)
      maximum-pool-size: 10

      # 커넥션 유휴 상태 최대 유지 시간 (기본: 10분)
      idle-timeout: 600000

      # 커넥션 최대 수명 (기본: 30분)
      max-lifetime: 1800000

      # 커넥션 누수 감지 임계값 (기본: 0, 비활성)
      leak-detection-threshold: 60000
```

#### 각 타임아웃의 역할

| 설정 | 기본값 | 역할 |
|---|---|---|
| `connectionTimeout` | 30초 | 풀에서 커넥션 대기 최대 시간. 초과 시 `SQLException` |
| `maximumPoolSize` | 10 | 동시 사용 가능한 최대 커넥션 수 |
| `idleTimeout` | 10분 | 유휴 커넥션 유지 시간. `minimumIdle` 이하면 미적용 |
| `maxLifetime` | 30분 | 커넥션 최대 수명. **사용 중인 커넥션은 강제 종료되지 않음** |
| `leakDetectionThreshold` | 0 (비활성) | 커넥션 반환 지연 시 경고 로그 출력 임계값 |

---

### 핵심 문제: HikariCP는 실행 중인 쿼리를 강제 종료하지 않는다

```
maxLifetime = 30분으로 설정해도

"An in-use connection will never be retired,
 only when it is closed will it then be removed."

- HikariCP 공식 문서
```

**HikariCP는 사용 중인 커넥션을 절대 회수하지 않는다.** 장시간 쿼리가 끝날 때까지 커넥션은 점유 상태를 유지한다. 따라서 커넥션 풀 설정만으로는 장시간 쿼리 문제를 근본적으로 해결할 수 없다.

---

### 해결 전략

#### 1. 쿼리 레벨 타임아웃 설정

**Spring Data JPA에서의 설정:**
```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    List<Order> findByStatus(String status);
}
```

**JPA 글로벌 설정:**
```yaml
spring:
  jpa:
    properties:
      jakarta.persistence.query.timeout: 5000  # 5초
```

**JDBC 레벨 설정:**
```java
@Transactional(timeout = 10) // 10초
public void processOrder(Long orderId) {
    // 이 트랜잭션 내 모든 쿼리에 10초 타임아웃 적용
}
```

#### 2. MySQL 서버 레벨 타임아웃

```sql
-- 전역 쿼리 타임아웃 (밀리초, SELECT에만 적용)
SET GLOBAL max_execution_time = 60000;  -- 60초

-- 세션별 설정
SET SESSION max_execution_time = 30000;  -- 30초

-- 쿼리별 힌트
SELECT /*+ MAX_EXECUTION_TIME(10000) */ * FROM large_table;

-- 커넥션 유휴 타임아웃
SET GLOBAL wait_timeout = 28800;         -- 8시간 (기본)
SET GLOBAL interactive_timeout = 28800;  -- 대화형 클라이언트용
```

| MySQL 변수 | 기본값 | 역할 |
|---|---|---|
| `max_execution_time` | 0 (무제한) | SELECT 쿼리 최대 실행 시간 (ms) |
| `wait_timeout` | 28800초 (8시간) | 비대화형 커넥션 유휴 대기 시간 |
| `interactive_timeout` | 28800초 (8시간) | 대화형 커넥션 유휴 대기 시간 |
| `long_query_time` | 10초 | Slow Query Log 기록 기준 시간 |

#### 3. 커넥션 누수 감지 활성화

```yaml
spring:
  datasource:
    hikari:
      leak-detection-threshold: 60000  # 60초 이상 미반환 시 경고
```

로그 출력 예시:
```
WARN  com.zaxxer.hikari.pool.ProxyLeakTask -
Connection leak detection triggered for com.mysql.cj.jdbc.ConnectionImpl@1a2b3c4d,
stack trace follows
java.lang.Exception: Apparent connection leak detected
    at com.example.service.OrderService.findSlowOrders(OrderService.java:42)
    at com.example.controller.OrderController.getOrders(OrderController.java:28)
```

이 로그를 통해 **어떤 코드에서 커넥션을 장시간 점유하는지** 추적할 수 있다.

#### 4. Slow Query Log 활성화

```sql
-- Slow Query Log 활성화
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;  -- 1초 이상 쿼리 기록
SET GLOBAL slow_query_log_file = '/var/log/mysql/slow-query.log';

-- 현재 설정 확인
SHOW VARIABLES LIKE 'slow_query%';
SHOW VARIABLES LIKE 'long_query_time';
```

**분석 도구:**
```bash
# mysqldumpslow로 느린 쿼리 분석
mysqldumpslow -s t -t 10 /var/log/mysql/slow-query.log
# -s t: 시간순 정렬, -t 10: 상위 10개
```

#### 5. 실행 중인 쿼리 모니터링 및 강제 종료

```sql
-- 현재 실행 중인 커넥션 확인
SELECT id, user, host, db, command, time, state, info
FROM information_schema.processlist
WHERE command != 'Sleep'
ORDER BY time DESC;

-- 1분 이상 실행 중인 쿼리 확인
SELECT id, user, time, state, info
FROM information_schema.processlist
WHERE command = 'Query' AND time > 60;

-- 특정 프로세스 강제 종료
KILL [process_id];

-- 쿼리만 종료 (커넥션은 유지)
KILL QUERY [process_id];
```

**PostgreSQL의 경우:**
```sql
-- 커넥션 상태 확인
SELECT datname, usename, state, query, now() - query_start AS duration
FROM pg_stat_activity
WHERE state = 'active'
ORDER BY duration DESC;

-- 장시간 쿼리 강제 종료
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE state = 'active' AND now() - query_start > interval '1 minute';
```

---

### 실무 권장 설정 조합

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      connection-timeout: 5000       # 5초 (빠른 실패)
      leak-detection-threshold: 30000 # 30초 이상 미반환 시 경고
      max-lifetime: 1800000          # 30분
  jpa:
    properties:
      jakarta.persistence.query.timeout: 10000  # 쿼리 10초 타임아웃
      hibernate:
        format_sql: true

logging:
  level:
    com.zaxxer.hikari: DEBUG
    com.zaxxer.hikari.HikariConfig: DEBUG
```

**타임아웃 계층 구조:**

```
쿼리 타임아웃 (10초)  ← 가장 먼저 작동
    ↓
@Transactional timeout (10초)  ← 트랜잭션 전체 시간 제한
    ↓
connectionTimeout (5초)  ← 커넥션 획득 대기 시간
    ↓
MySQL max_execution_time (60초)  ← DB 서버 최후 방어선
    ↓
leakDetectionThreshold (30초)  ← 모니터링 및 경고
```

**풀 사이즈 산정 공식 (HikariCP 권장):**

```
connections = (core_count * 2) + effective_spindle_count

예시: 4코어 서버, SSD 1개
connections = (4 * 2) + 1 = 9 ~ 10
```

풀 사이즈를 무작정 키우면 오히려 DB 서버의 리소스 경합이 심해져 **전체 쿼리 성능이 하락**할 수 있다.

## 핵심 정리

- 장시간 쿼리는 커넥션을 점유하여 **커넥션 풀 고갈** → **전체 서비스 장애**로 이어진다
- HikariCP는 **사용 중인 커넥션을 강제 회수하지 않으므로**, 풀 설정만으로는 해결 불가
- **쿼리 타임아웃**(JPA/JDBC)이 장시간 쿼리에 대한 가장 효과적인 1차 방어선
- **leakDetectionThreshold**를 활성화하여 커넥션 장기 점유 코드를 추적
- **Slow Query Log**로 1분 이상 걸리는 쿼리를 식별하고 인덱스/쿼리 최적화
- 풀 사이즈를 무작정 키우면 DB 서버 리소스 경합으로 오히려 성능 하락
- 타임아웃은 **쿼리 → 트랜잭션 → 커넥션 획득 → DB 서버** 순으로 계층적 적용

## 키워드

- **Connection Pool Exhaustion**: 커넥션 풀의 모든 커넥션이 사용 중이어서 새 요청이 커넥션을 획득하지 못하는 상태. 장시간 쿼리나 커넥션 누수로 발생
- **HikariCP**: Spring Boot의 기본 JDBC 커넥션 풀 라이브러리. 빠른 성능과 안정성으로 가장 널리 사용됨
- **connectionTimeout**: HikariCP에서 풀로부터 커넥션을 얻기 위해 대기하는 최대 시간(밀리초). 초과 시 SQLException 발생
- **maximumPoolSize**: 커넥션 풀이 유지할 수 있는 최대 커넥션 수. 풀 사이즈를 무작정 키우면 DB 서버 리소스 경합으로 성능 하락 가능
- **leakDetectionThreshold**: 커넥션이 풀에서 체크아웃된 후 반환되지 않은 상태로 유지되는 시간 임계값. 초과 시 경고 로그 출력하여 누수 감지
- **Slow Query Log**: MySQL에서 `long_query_time` 이상 소요된 쿼리를 기록하는 로그. 성능 문제 쿼리 식별에 활용
- **max_execution_time**: MySQL에서 SELECT 쿼리의 최대 실행 시간(밀리초). 초과 시 쿼리 자동 종료
- **wait_timeout**: MySQL에서 비대화형 커넥션이 유휴 상태로 유지될 수 있는 최대 시간(초). 초과 시 서버가 커넥션 강제 종료
- **@Transactional timeout**: Spring의 트랜잭션 전체 실행 시간 제한(초). 초과 시 롤백 및 예외 발생
- **쿼리 타임아웃**: 개별 SQL 쿼리의 최대 실행 시간 제한. JPA의 `query.timeout` 또는 JDBC의 `setQueryTimeout()` 등으로 설정

## 참고 자료

- [HikariCP GitHub - 공식 문서](https://github.com/brettwooldridge/HikariCP)
- [MySQL 8.4 Reference Manual - The Slow Query Log](https://dev.mysql.com/doc/refman/8.4/en/slow-query-log.html)
- [Baeldung - Configuring a Hikari Connection Pool with Spring Boot](https://www.baeldung.com/spring-boot-hikari)
- [Tideways - Use Timeouts to Prevent Long-Running SELECT Queries](https://tideways.com/profiler/blog/use-timeouts-to-prevent-long-running-select-queries-from-taking-down-your-mysql)
- [Kir Shatrov - Scaling MySQL Stack: Timeouts](https://kirshatrov.com/posts/scaling-mysql-stack-part-1-timeouts)
- [Navigating HikariCP Connection Pool Issues](https://medium.com/@raphy.26.007/navigating-hikaricp-connection-pool-issues-when-your-database-says-no-more-connections-3203217a14a0)
