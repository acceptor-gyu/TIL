# Connection Pool (커넥션 풀)

## 개요
데이터베이스 커넥션 풀의 개념, 필요성, 동작 원리, 그리고 최적화 방법에 대해 학습합니다.

## 상세 내용

### Connection Pool이란?
**정의**: 데이터베이스 연결(Connection)을 미리 생성하여 풀(Pool)에 보관하고, 필요할 때 재사용하는 기법

### 왜 필요한가?

#### 커넥션 생성 비용
데이터베이스 커넥션을 매번 새로 생성하면 많은 비용이 발생:

1. **TCP 3-way Handshake**: 네트워크 연결 수립
2. **인증**: 사용자 ID/비밀번호 검증
3. **세션 초기화**: DB 세션 생성 및 설정
4. **자원 할당**: 메모리, 버퍼 등 할당

```java
// 커넥션을 매번 생성하는 비효율적인 방법
public User findUser(Long id) {
    Connection conn = null;
    try {
        conn = DriverManager.getConnection(url, user, password); // 비용 큼
        // 쿼리 실행
        return executeQuery(conn, id);
    } finally {
        if (conn != null) conn.close(); // 실제로 연결 종료
    }
}
```

#### 성능 비교
```
커넥션 생성/종료: 약 100ms
커넥션 풀에서 가져오기: 약 1ms
→ 100배 차이!
```

### 데이터베이스 커넥션의 생애주기 (Lifecycle)

커넥션은 생성부터 소멸까지 여러 단계를 거치며, 각 단계마다 비용과 상태가 다릅니다.

#### 1. 생성 (Creation)
```
애플리케이션 → TCP 연결 → 인증 → 세션 초기화 → 커넥션 객체 생성
```

**발생 작업**:
- **네트워크 연결**: TCP 3-way handshake (~10ms)
- **SSL/TLS 핸드셰이크**: 암호화 연결 시 (~50ms)
- **인증 (Authentication)**: 사용자 검증 (~20ms)
- **세션 초기화**: 세션 변수, 캐릭터셋 설정 (~10ms)
- **자원 할당**: 메모리, 버퍼 할당 (~10ms)

**총 비용**: **약 100ms** (SSL 사용 시 더 증가)

```java
// 커넥션 생성 과정
Connection conn = DriverManager.getConnection(
    "jdbc:mysql://localhost:3306/mydb?useSSL=true",
    "username",
    "password"
);
// 이 한 줄에서 위의 모든 작업이 발생!
```

#### 2. 활성화 (Activation / Borrowed)
```
유휴 상태 → 유효성 검증 (선택) → 활성 상태로 전환
```

**발생 작업**:
- **유효성 검증** (connection-test-query 설정 시):
  ```sql
  SELECT 1  -- MySQL
  SELECT 1 FROM DUAL  -- Oracle
  ```
- **상태 변경**: idle → active
- **메타데이터 업데이트**: 마지막 사용 시간, 사용 횟수 기록

**비용**: **약 1ms** (유효성 검증 포함 시 ~5ms)

#### 3. 사용 (In Use / Active)
```
쿼리 실행 → 트랜잭션 처리 → 결과 반환
```

**상태**:
- 애플리케이션이 커넥션을 보유
- 쿼리 실행, 트랜잭션 수행
- 풀에서는 "사용 중" 상태로 관리

**주의사항**:
- 장시간 보유 시 풀 고갈 위험
- 적절한 타임아웃 설정 필요

```java
try (Connection conn = dataSource.getConnection()) {  // 활성화
    // 사용 중 상태
    PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM users");
    ResultSet rs = pstmt.executeQuery();
    // ...
}  // 자동으로 반환 (passivation)
```

#### 4. 반환 (Passivation / Returned)
```
사용 완료 → 상태 초기화 → 유휴 상태로 전환
```

**발생 작업**:
- **상태 초기화**:
  - 트랜잭션 롤백 (미완료 시)
  - Auto-commit 복원
  - 임시 테이블 정리
- **상태 변경**: active → idle
- **통계 업데이트**: 총 사용 시간 기록

**비용**: **약 1ms**

#### 5. 유휴 (Idle)
```
대기 상태 → 주기적 검증 → 타임아웃 확인
```

**상태**:
- 풀에서 다음 요청 대기
- 주기적으로 keepalive 실행 (설정 시)
- idleTimeout 경과 시 종료 대상

**Keepalive (유지 쿼리)**:
```yaml
spring:
  datasource:
    hikari:
      keepalive-time: 300000  # 5분마다 SELECT 1 실행
```

#### 6. 유효성 검증 (Validation)
```
커넥션 상태 확인 → 정상/비정상 판단
```

**검증 시점**:
- **대여 시 (validation-timeout)**: 풀에서 가져올 때
- **반환 시**: 풀에 돌려줄 때
- **유휴 시 (keepalive-time)**: 주기적으로

**검증 방법**:
```java
// 1. 쿼리 실행 방식 (느림)
connection-test-query: SELECT 1

// 2. JDBC4 isValid() 방식 (빠름, 권장)
connection.isValid(timeout)

// 3. Keepalive 방식 (가장 효율적)
keepalive-time: 300000
```

#### 7. 종료 (Termination / Destruction)
```
종료 조건 충족 → 연결 닫기 → 자원 해제
```

**종료 조건**:
- **maxLifetime 도달**: 최대 수명 초과
- **idleTimeout 도달**: 유휴 시간 초과
- **유효성 검증 실패**: 연결이 끊어짐
- **애플리케이션 종료**: 풀 종료 시
- **예외 발생**: 복구 불가능한 오류

```java
// 커넥션 종료 과정
connection.close();  // 물리적 연결 종료
// → TCP FIN 패킷 전송
// → DB 서버 세션 종료
// → 자원 해제
```

#### 생애주기 다이어그램

```
┌─────────┐
│ 생성     │ ← 애플리케이션 시작 또는 풀 확장
│ 100ms   │
└────┬────┘
     ↓
┌─────────┐
│ 유휴     │ ⟲ Keepalive (5분마다)
│ Idle    │
└────┬────┘
     ↓ 요청 발생
┌─────────┐
│ 활성화    │ ← 유효성 검증 (선택)
│ 1ms     │
└────┬────┘
     ↓
┌─────────┐
│ 사용 중   │ ← 쿼리 실행, 트랜잭션 처리
│ Active  │
└────┬────┘
     ↓ 작업 완료
┌─────────┐
│ 반환     │ ← 상태 초기화
│ 1ms     │
└────┬────┘
     ↓
   유휴로  ──┐
            │
     ↓      │ maxLifetime 또는
┌─────────┐ │ idleTimeout 도달
│ 종료     │←┘
│ 10ms    │
└─────────┘
```

### Connection Pool 동작 원리

```
[애플리케이션]
      ↓ ① 커넥션 요청
[Connection Pool]
├─ Connection 1 (사용 중)
├─ Connection 2 (유휴)  ← ② 유휴 커넥션 반환
├─ Connection 3 (유휴)
└─ Connection 4 (사용 중)
      ↓ ③ 작업 완료 후 반환
[애플리케이션]
      ↓ ④ 커넥션 풀에 반환 (재사용 가능)
[Connection Pool]
```

#### 주요 프로세스
1. **초기화**: 애플리케이션 시작 시 설정된 수만큼 커넥션 생성
2. **대여**: 요청 시 유휴 커넥션 제공
3. **반환**: 사용 완료 후 커넥션을 풀에 반환 (종료 X)
4. **재사용**: 반환된 커넥션을 다음 요청에 재사용
5. **유지**: Idle 커넥션의 유효성 검증 (Heartbeat)

### DBCP가 성능에 도움이 되는 이유

#### 1. 커넥션 재사용 (Connection Reuse)

**문제**: 매번 커넥션 생성/종료 시 큰 비용 발생
```java
// 풀 없이: 100번 요청 = 100번 생성/종료 = 10초
for (int i = 0; i < 100; i++) {
    Connection conn = DriverManager.getConnection(url, user, pass);  // 100ms
    // 쿼리 실행 (10ms)
    conn.close();  // 10ms
}
// 총 시간: (100ms + 10ms + 10ms) × 100 = 12,000ms
```

**해결**: 커넥션 재사용으로 생성/종료 비용 제거
```java
// 풀 사용: 100번 요청 = 1번 생성 + 100번 재사용 = 1.1초
DataSource ds = // HikariCP 초기화 (10개 커넥션 생성: 1000ms)
for (int i = 0; i < 100; i++) {
    Connection conn = ds.getConnection();  // 1ms (풀에서 가져오기)
    // 쿼리 실행 (10ms)
    conn.close();  // 1ms (풀에 반환)
}
// 총 시간: 1000ms + (1ms + 10ms + 1ms) × 100 = 2,200ms
// 성능 향상: 5.5배
```

#### 2. 초기 비용 분산 (Amortized Cost)

**개념**: 커넥션 생성 비용을 애플리케이션 시작 시 한 번만 지불

```
[풀 없이]
요청1: 생성(100ms) + 쿼리(10ms) = 110ms
요청2: 생성(100ms) + 쿼리(10ms) = 110ms
요청3: 생성(100ms) + 쿼리(10ms) = 110ms
→ 매번 110ms

[풀 사용]
초기화: 생성(100ms) × 10개 = 1000ms (한 번만)
요청1: 가져오기(1ms) + 쿼리(10ms) = 11ms
요청2: 가져오기(1ms) + 쿼리(10ms) = 11ms
요청3: 가져오기(1ms) + 쿼리(10ms) = 11ms
→ 매번 11ms (10배 빠름)
```

#### 3. 동시성 제어 (Concurrency Control)

**문제**: 무제한 커넥션 생성 시 DB 서버 과부하
```java
// 1000명이 동시 접속 → 1000개 커넥션 생성
// DB max_connections = 500 → 500개는 거부
ExecutorService executor = Executors.newFixedThreadPool(1000);
for (int i = 0; i < 1000; i++) {
    executor.submit(() -> {
        Connection conn = DriverManager.getConnection(url, user, pass);
        // SQLException: Too many connections
    });
}
```

**해결**: 최대 커넥션 수 제한으로 DB 보호
```java
// 풀 크기 = 20 → 최대 20개 커넥션만 생성
// 나머지는 대기 (queue)
HikariConfig config = new HikariConfig();
config.setMaximumPoolSize(20);  // DB를 보호
config.setConnectionTimeout(5000);  // 5초 대기 후 실패

// 1000명 접속해도 DB는 최대 20개 커넥션만 처리
// 순차적으로 처리되므로 안정적
```

#### 4. 자원 관리 (Resource Management)

**커넥션 누수 방지**:
```java
// 개발자가 close() 누락해도 풀이 관리
try (Connection conn = dataSource.getConnection()) {
    // 작업 수행
    // close() 호출 안 해도 try-with-resources가 자동 호출
}

// 풀 설정으로 누수 감지
hikari.leak-detection-threshold: 60000  // 60초 이상 보유 시 경고
```

**유휴 커넥션 자동 정리**:
```yaml
hikari:
  idle-timeout: 600000  # 10분간 사용 안 하면 자동 종료
  minimum-idle: 10      # 최소 10개는 유지
```

#### 5. 장애 처리 (Fault Tolerance)

**끊어진 커넥션 자동 제거**:
```java
// 풀이 자동으로 유효성 검증
hikari:
  connection-test-query: SELECT 1
  validation-timeout: 5000

// 끊어진 커넥션 감지 시:
// 1. 풀에서 제거
// 2. 새 커넥션 생성
// 3. 애플리케이션에 제공
// → 애플리케이션은 장애를 인식하지 못함
```

**재시도 메커니즘**:
```java
// 커넥션 획득 실패 시 자동 재시도
Connection conn = dataSource.getConnection();
// 내부적으로:
// 1차 시도 → 실패 (끊어진 커넥션)
// 2차 시도 → 성공 (새 커넥션)
```

#### 6. 성능 최적화 기능

**PreparedStatement 캐싱**:
```yaml
hikari:
  data-source-properties:
    cachePrepStmts: true
    prepStmtCacheSize: 250
    prepStmtCacheSqlLimit: 2048

# 같은 쿼리를 반복 실행 시:
# 1회차: 파싱 + 최적화 + 실행 (10ms)
# 2회차: 캐시에서 가져와서 실행 (2ms)
# → 5배 빠름
```

**배치 처리 최적화**:
```java
hikari:
  data-source-properties:
    rewriteBatchedStatements: true

# INSERT 100건:
# 일반: INSERT ... (1ms) × 100 = 100ms
# 배치: INSERT ... , ... , ... (한 번에) = 10ms
# → 10배 빠름
```

#### 성능 비교 요약

| 항목 | 풀 없이 | 풀 사용 | 개선율 |
|------|---------|---------|--------|
| 커넥션 획득 | 100ms | 1ms | 100배 |
| 동시 1000 요청 | 110초 | 11초 | 10배 |
| 메모리 사용 | 불규칙 | 일정 | 안정적 |
| DB 부하 | 높음 | 낮음 | 제어됨 |
| 장애 복구 | 수동 | 자동 | 편리 |

### DBCP 튜닝 팁

#### 1. 풀 크기 최적화

**기본 공식**:
```
최적 크기 = (CPU 코어 수 × 2) + effective_spindle_count
```

**실제 측정 기반 조정**:
```java
@Component
public class ConnectionPoolTuner {

    @Scheduled(fixedDelay = 60000)  // 1분마다
    public void monitorPool() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();

        int active = pool.getActiveConnections();
        int idle = pool.getIdleConnections();
        int waiting = pool.getThreadsAwaitingConnection();
        int total = pool.getTotalConnections();

        // 튜닝 신호
        if (waiting > 0) {
            log.warn("풀 크기 증가 필요: {} threads waiting", waiting);
        }

        if (idle > total * 0.5) {
            log.info("풀 크기 감소 가능: {}% idle", (idle * 100 / total));
        }

        // 최적 크기 = 피크 시 active + 여유분(20%)
        int optimal = (int) (active * 1.2);
        log.info("권장 풀 크기: {}", optimal);
    }
}
```

**작업 유형별 설정**:
```yaml
# CPU 집약적 (복잡한 계산, 암호화)
hikari:
  maximum-pool-size: 8  # CPU 코어 수만큼

# I/O 집약적 (파일, 네트워크, 느린 쿼리)
hikari:
  maximum-pool-size: 20  # 코어 수의 2~3배

# 혼합형
hikari:
  maximum-pool-size: 15  # 중간값
```

#### 2. 타임아웃 최적화

**connection-timeout (커넥션 획득 타임아웃)**:
```yaml
hikari:
  # 너무 길면: 장애 시 느린 응답
  # 너무 짧으면: 정상 상황에서도 실패
  connection-timeout: 5000  # 5초 (권장)

  # 부하가 높은 환경
  connection-timeout: 10000  # 10초

  # 빠른 실패가 필요한 환경 (API Gateway)
  connection-timeout: 3000   # 3초
```

**max-lifetime (커넥션 최대 수명)**:
```yaml
hikari:
  # DB의 wait_timeout보다 짧게 설정
  max-lifetime: 1800000  # 30분

  # MySQL wait_timeout 확인:
  # SHOW VARIABLES LIKE 'wait_timeout';  -- 기본 8시간
  # 권장: DB 타임아웃의 90%
  max-lifetime: 25920000  # 7.2시간 (8시간의 90%)
```

**idle-timeout (유휴 타임아웃)**:
```yaml
hikari:
  # 트래픽 변동이 큰 경우: 짧게 설정
  idle-timeout: 300000   # 5분

  # 안정적인 트래픽: 길게 설정
  idle-timeout: 600000   # 10분

  # 최소 유휴 커넥션과 함께 사용
  minimum-idle: 10       # 최소 10개는 유지
  idle-timeout: 600000   # 10개 초과분만 제거
```

#### 3. 유효성 검증 최적화

**유효성 검증 비활성화 (성능 최우선)**:
```yaml
hikari:
  connection-test-query: null  # 검증 쿼리 비활성화

  # 대신 keepalive 사용
  keepalive-time: 300000       # 5분마다 유지 쿼리
```

**JDBC4 isValid() 사용 (권장)**:
```yaml
hikari:
  # connection-test-query 설정 안 함
  # → JDBC4 Connection.isValid() 자동 사용

  validation-timeout: 5000  # 5초 타임아웃
```

**최소 검증 전략**:
```yaml
hikari:
  # 대여 시 검증 안 함 (빠름)
  connection-test-query: null

  # 주기적 검증만 수행
  keepalive-time: 300000

  # maxLifetime으로 오래된 커넥션 교체
  max-lifetime: 1800000
```

#### 4. 동시성 최적화

**공정성 vs 성능**:
```java
HikariConfig config = new HikariConfig();

// 공정성 우선 (FIFO): 먼저 요청한 순서대로
// - 장점: 공평한 분배
// - 단점: 약간 느림
// HikariCP는 기본적으로 공정성 제공

// 성능 우선: 가능한 커넥션 즉시 제공
// - 장점: 빠름
// - 단점: 일부 요청이 오래 대기 가능
// HikariCP는 자동으로 최적화
```

**대기 큐 모니터링**:
```java
if (pool.getThreadsAwaitingConnection() > 0) {
    // 대기 중인 스레드가 있음
    // → 풀 크기 증가 고려
    // 또는 쿼리 성능 개선
}
```

#### 5. 메모리 최적화

**커넥션당 메모리 사용량**:
```
기본 커넥션: ~1MB
PreparedStatement 캐시: ~500KB (250개 × 2KB)
총: ~1.5MB per connection

풀 크기 20 → 약 30MB
풀 크기 100 → 약 150MB
```

**PreparedStatement 캐시 조정**:
```yaml
hikari:
  data-source-properties:
    # 작은 애플리케이션
    prepStmtCacheSize: 25        # 25개
    prepStmtCacheSqlLimit: 256   # 256바이트

    # 중간 규모
    prepStmtCacheSize: 250
    prepStmtCacheSqlLimit: 2048

    # 대규모 (다양한 쿼리)
    prepStmtCacheSize: 500
    prepStmtCacheSqlLimit: 4096
```

#### 6. 모니터링 기반 튜닝

**핵심 메트릭**:
```java
@Component
public class PoolMetricsCollector {

    @Scheduled(fixedDelay = 10000)  // 10초마다
    public void collectMetrics() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();

        // 1. 활용률 (Utilization)
        int total = pool.getTotalConnections();
        int active = pool.getActiveConnections();
        double utilization = (double) active / total * 100;

        // 2. 대기 시간 (Wait Time)
        int waiting = pool.getThreadsAwaitingConnection();

        // 3. 생성 속도 (Creation Rate)
        // → 자주 생성된다면 풀 크기 부족

        // 튜닝 기준
        if (utilization > 80) {
            log.warn("풀 활용률 높음: {}% → 크기 증가 고려", utilization);
        }

        if (waiting > 0) {
            log.error("커넥션 대기 발생: {} threads → 긴급 조치 필요", waiting);
        }

        if (utilization < 30) {
            log.info("풀 활용률 낮음: {}% → 크기 감소 가능", utilization);
        }
    }
}
```

**Prometheus + Grafana 연동**:
```yaml
management:
  metrics:
    export:
      prometheus:
        enabled: true
  endpoints:
    web:
      exposure:
        include: prometheus,health,metrics

# 모니터링 항목:
# - hikaricp_connections_active
# - hikaricp_connections_idle
# - hikaricp_connections_pending
# - hikaricp_connections_timeout_total
# - hikaricp_connections_creation_seconds
```

#### 7. 환경별 튜닝 전략

**개발 환경**:
```yaml
hikari:
  minimum-idle: 2
  maximum-pool-size: 5
  connection-timeout: 10000
  # 빠른 피드백, 적은 자원 사용
```

**스테이징 환경**:
```yaml
hikari:
  minimum-idle: 5
  maximum-pool-size: 10
  connection-timeout: 5000
  # 프로덕션과 유사하게 테스트
```

**프로덕션 환경**:
```yaml
hikari:
  minimum-idle: 10
  maximum-pool-size: 20
  connection-timeout: 5000
  max-lifetime: 1800000
  idle-timeout: 600000
  leak-detection-threshold: 60000
  # 안정성과 성능 균형
```

#### 튜닝 체크리스트

- [ ] 부하 테스트로 피크 시 필요한 커넥션 수 측정
- [ ] `(코어 수 × 2) + 1` 공식을 기준점으로 사용
- [ ] 활용률 70~80%를 목표로 조정
- [ ] DB max_connections 설정 확인 (서버 수 × 풀 크기 < max_connections)
- [ ] 대기 스레드(waiting) 발생 시 즉시 대응
- [ ] maxLifetime을 DB wait_timeout의 90%로 설정
- [ ] 유효성 검증 최소화 (keepalive 사용)
- [ ] PreparedStatement 캐싱 활성화
- [ ] 메트릭 수집 및 알림 설정
- [ ] 정기적으로 메트릭 리뷰 및 재조정

### HikariCP 설정 옵션 완벽 가이드

#### 1. 기본 연결 설정 (Essential Configuration)

##### jdbcUrl
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb?useSSL=true&serverTimezone=Asia/Seoul
```
- **설명**: JDBC 연결 URL
- **필수**: Yes
- **형식**: `jdbc:{dbms}://{host}:{port}/{database}?{params}`
- **DB별 예시**:
  ```
  MySQL:      jdbc:mysql://localhost:3306/mydb
  PostgreSQL: jdbc:postgresql://localhost:5432/mydb
  Oracle:     jdbc:oracle:thin:@localhost:1521:orcl
  H2:         jdbc:h2:mem:testdb
  ```

##### username / password
```yaml
spring:
  datasource:
    username: myuser
    password: mypassword
```
- **설명**: DB 접속 인증 정보
- **필수**: DB에 따라 다름
- **보안**: 환경 변수나 암호화 설정 권장
  ```yaml
  username: ${DB_USER:root}
  password: ${DB_PASSWORD}
  ```

##### driverClassName
```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
```
- **설명**: JDBC 드라이버 클래스명
- **필수**: No (URL에서 자동 감지)
- **드라이버 목록**:
  ```
  MySQL:      com.mysql.cj.jdbc.Driver
  PostgreSQL: org.postgresql.Driver
  Oracle:     oracle.jdbc.OracleDriver
  H2:         org.h2.Driver
  ```

#### 2. 풀 크기 설정 (Pool Sizing)

##### minimumIdle
```yaml
hikari:
  minimum-idle: 10
```
- **설명**: 풀에 유지할 최소 유휴 커넥션 수
- **기본값**: maximumPoolSize와 동일
- **범위**: 0 ~ maximumPoolSize
- **권장**: 평균 동시 요청 수
- **튜닝**:
  - 너무 높으면: 불필요한 자원 사용
  - 너무 낮으면: 급격한 부하 시 지연
- **패턴별 설정**:
  ```yaml
  # 안정적인 트래픽
  minimum-idle: 10
  maximum-pool-size: 20

  # 변동이 큰 트래픽
  minimum-idle: 5
  maximum-pool-size: 30
  ```

##### maximumPoolSize
```yaml
hikari:
  maximum-pool-size: 20
```
- **설명**: 풀이 유지할 최대 커넥션 수 (idle + active)
- **기본값**: 10
- **권장 공식**: `(CPU 코어 × 2) + effective_spindle_count`
- **계산 예시**:
  ```
  4코어 + SSD(1) = 9개
  8코어 + HDD(4) = 20개
  ```
- **주의사항**:
  - DB max_connections 확인 필수
  - 다중 서버: `서버 수 × 풀 크기 < DB max_connections`

#### 3. 타임아웃 설정 (Timeout Configuration)

##### connectionTimeout
```yaml
hikari:
  connection-timeout: 5000  # 밀리초
```
- **설명**: 풀에서 커넥션 획득 대기 최대 시간
- **기본값**: 30000ms (30초)
- **최소값**: 250ms
- **권장**: 5000ms (5초)
- **동작**: 타임아웃 초과 시 `SQLException` 발생
- **환경별 설정**:
  ```yaml
  # 일반
  connection-timeout: 5000

  # 고부하
  connection-timeout: 10000

  # API Gateway (빠른 실패)
  connection-timeout: 3000
  ```

##### idleTimeout
```yaml
hikari:
  idle-timeout: 600000  # 10분
```
- **설명**: 유휴 커넥션이 제거되기까지의 시간
- **기본값**: 600000ms (10분)
- **최소값**: 10000ms
- **조건**: minimumIdle 초과분만 제거
- **권장**: 300000~600000ms (5~10분)

##### maxLifetime
```yaml
hikari:
  max-lifetime: 1800000  # 30분
```
- **설명**: 커넥션 최대 수명
- **기본값**: 1800000ms (30분)
- **권장**: DB wait_timeout보다 2~3분 짧게
- **DB별 확인**:
  ```sql
  -- MySQL
  SHOW VARIABLES LIKE 'wait_timeout';
  -- 기본: 28800초 (8시간)

  -- 권장 설정 (90%)
  max-lifetime: 25920000  # 7.2시간
  ```

##### validationTimeout
```yaml
hikari:
  validation-timeout: 5000
```
- **설명**: 유효성 검증 타임아웃
- **기본값**: 5000ms
- **최소값**: 250ms

#### 4. 유효성 검증 (Validation)

##### connectionTestQuery
```yaml
hikari:
  connection-test-query: SELECT 1
```
- **설명**: 유효성 검증 쿼리
- **기본값**: null (JDBC4 isValid() 사용)
- **권장**: null (성능 향상)
- **DB별 쿼리**:
  ```sql
  MySQL:      SELECT 1
  PostgreSQL: SELECT 1
  Oracle:     SELECT 1 FROM DUAL
  ```
- **성능 비교**:
  ```
  SELECT 1:  ~5ms
  isValid(): ~1ms (5배 빠름)
  ```

##### keepaliveTime
```yaml
hikari:
  keepalive-time: 300000  # 5분
```
- **설명**: 유휴 커넥션 주기적 검증 간격
- **기본값**: 0 (비활성화)
- **권장**: 300000ms (5분)
- **효과**: 끊어진 커넥션 미리 감지

#### 5. 성능 최적화 (Performance)

##### dataSourceProperties
```yaml
hikari:
  data-source-properties:
    # PreparedStatement 캐싱
    cachePrepStmts: true
    prepStmtCacheSize: 250
    prepStmtCacheSqlLimit: 2048
    useServerPrepStmts: true

    # 배치 최적화
    rewriteBatchedStatements: true

    # 결과 캐싱
    cacheResultSetMetadata: true
    cacheServerConfiguration: true

    # 네트워크
    tcpKeepAlive: true
    tcpNoDelay: true
```

**각 속성 설명**:

**cachePrepStmts** (기본: false, 권장: true)
- PreparedStatement 캐싱 활성화
- 효과: 동일 쿼리 5배 빠름

**prepStmtCacheSize** (기본: 25, 권장: 250)
- 캐시할 PreparedStatement 개수
- 메모리: 1개당 약 2KB

**prepStmtCacheSqlLimit** (기본: 256, 권장: 2048)
- 캐시할 SQL 최대 길이 (바이트)

**useServerPrepStmts** (기본: false, 권장: true)
- 서버 측 PreparedStatement 사용
- 쿼리 파싱을 서버에서 한 번만

**rewriteBatchedStatements** (기본: false, 권장: true)
- 배치 INSERT를 단일 쿼리로
- 효과: 10배 빠름

#### 6. 모니터링 및 디버깅

##### leakDetectionThreshold
```yaml
hikari:
  leak-detection-threshold: 60000  # 60초
```
- **설명**: 커넥션 누수 감지 임계값
- **기본값**: 0 (비활성화)
- **권장**: 60000ms (60초)
- **동작**: 임계값 초과 시 경고 로그
- **환경별**:
  ```yaml
  # 개발
  leak-detection-threshold: 10000

  # 프로덕션
  leak-detection-threshold: 60000
  ```

##### poolName
```yaml
hikari:
  pool-name: MyApp-HikariPool
```
- **설명**: 풀 이름 (로그, JMX 표시)
- **기본값**: HikariPool-{sequence}
- **권장**: {AppName}-HikariPool

##### registerMbeans
```yaml
hikari:
  register-mbeans: true
```
- **설명**: JMX MBeans 등록
- **기본값**: false
- **권장**: 프로덕션에서 true
- **용도**: JConsole, VisualVM 모니터링

#### 7. 트랜잭션 설정

##### autoCommit
```yaml
hikari:
  auto-commit: true
```
- **설명**: 기본 autoCommit 설정
- **기본값**: true
- **권장**: true (Spring이 관리)

##### transactionIsolation
```yaml
hikari:
  transaction-isolation: TRANSACTION_READ_COMMITTED
```
- **설명**: 트랜잭션 격리 수준
- **옵션**:
  ```
  TRANSACTION_READ_UNCOMMITTED  (레벨 0)
  TRANSACTION_READ_COMMITTED    (레벨 1) ← 권장
  TRANSACTION_REPEATABLE_READ   (레벨 2)
  TRANSACTION_SERIALIZABLE      (레벨 3)
  ```
- **DB별 기본값**:
  ```
  MySQL:      REPEATABLE_READ
  PostgreSQL: READ_COMMITTED
  Oracle:     READ_COMMITTED
  ```

##### readOnly
```yaml
hikari:
  read-only: false
```
- **설명**: 읽기 전용 커넥션
- **기본값**: false
- **용도**: 읽기 복제본 연결

##### catalog / schema
```yaml
hikari:
  catalog: my_catalog
  schema: my_schema
```
- **설명**: 기본 catalog/schema
- **용도**: 다중 스키마 환경

#### 8. 초기화 설정

##### initializationFailTimeout
```yaml
hikari:
  initialization-fail-timeout: 1
```
- **설명**: 초기화 실패 타임아웃 (ms)
- **기본값**: 1 (즉시 실패)
- **특수값**:
  - `1`: 즉시 실패
  - `0`: 무한 재시도
  - `양수`: 지정 시간 재시도

##### connectionInitSql
```yaml
hikari:
  connection-init-sql: SET NAMES utf8mb4
```
- **설명**: 커넥션 생성 후 실행 SQL
- **용도**: 세션 변수 설정
- **예시**:
  ```sql
  SET NAMES utf8mb4;
  SET time_zone = '+09:00';
  ```

### 환경별 설정 예시

#### 개발 환경
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    hikari:
      minimum-idle: 2
      maximum-pool-size: 5
      connection-timeout: 10000
      pool-name: Dev-Pool
```

#### 테스트 환경
```yaml
spring:
  datasource:
    url: jdbc:mysql://test-db:3306/mydb
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      minimum-idle: 5
      maximum-pool-size: 10
      connection-timeout: 5000
      leak-detection-threshold: 10000
      pool-name: Test-Pool
```

#### 프로덕션 환경 (최적화)
```yaml
spring:
  datasource:
    url: jdbc:mysql://prod-db:3306/mydb
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      # 풀 크기
      minimum-idle: 10
      maximum-pool-size: 20

      # 타임아웃
      connection-timeout: 5000
      idle-timeout: 600000
      max-lifetime: 1800000

      # 유효성
      keepalive-time: 300000

      # 모니터링
      leak-detection-threshold: 60000
      pool-name: Prod-Pool
      register-mbeans: true

      # 성능
      data-source-properties:
        cachePrepStmts: true
        prepStmtCacheSize: 250
        prepStmtCacheSqlLimit: 2048
        useServerPrepStmts: true
        rewriteBatchedStatements: true
```

### 설정 검증 체크리스트

**필수 확인**:
- [ ] jdbcUrl 올바른지 확인
- [ ] username/password 설정
- [ ] maximumPoolSize < DB max_connections

**성능 최적화**:
- [ ] cachePrepStmts: true
- [ ] rewriteBatchedStatements: true
- [ ] keepaliveTime 설정

**안정성**:
- [ ] connectionTimeout 적절히 설정
- [ ] maxLifetime < DB wait_timeout
- [ ] leakDetectionThreshold 활성화

**모니터링**:
- [ ] poolName 설정
- [ ] registerMbeans: true (프로덕션)
- [ ] 메트릭 수집 설정

### HikariCP 설정 예시

HikariCP는 Spring Boot 2.0부터 기본 커넥션 풀 라이브러리

```yaml
# application.yml
spring:
  datasource:
    hikari:
      # 최소 유휴 커넥션 수
      minimum-idle: 10

      # 최대 풀 크기
      maximum-pool-size: 20

      # 커넥션 획득 타임아웃 (밀리초)
      connection-timeout: 5000

      # 커넥션 최대 수명 (밀리초)
      max-lifetime: 1800000  # 30분

      # 유휴 커넥션 타임아웃 (밀리초)
      idle-timeout: 600000   # 10분

      # 커넥션 테스트 쿼리
      connection-test-query: SELECT 1

      # 풀 이름
      pool-name: HikariCP-Pool
```

### Java 설정

```java
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariConfig hikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/mydb");
        config.setUsername("user");
        config.setPassword("password");

        // 풀 크기 설정
        config.setMinimumIdle(10);
        config.setMaximumPoolSize(20);

        // 타임아웃 설정
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        // 커넥션 테스트
        config.setConnectionTestQuery("SELECT 1");

        // 성능 최적화
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        return config;
    }

    @Bean
    public DataSource dataSource(HikariConfig hikariConfig) {
        return new HikariDataSource(hikariConfig);
    }
}
```

### 최적 풀 크기 결정

#### 공식 (PostgreSQL Wiki)
```
connections = (core_count * 2) + effective_spindle_count
```
- `core_count`: CPU 코어 수
- `effective_spindle_count`: 디스크 스핀들 수 (SSD는 1)

#### 예시
- 4코어 CPU, SSD 사용
- 최적 커넥션 수 = (4 × 2) + 1 = **9개**

#### 중요 원칙
> **"커넥션이 많다고 좋은 것이 아니다"**

**이유:**
1. **DB 서버 부하**: 각 커넥션마다 DB 서버 자원 소비
2. **Context Switching**: 과도한 커넥션은 오히려 성능 저하
3. **메모리 소비**: 커넥션마다 메모리 필요

### 실무 시나리오별 설정

#### 1. 작은 애플리케이션 (단일 서버)
```yaml
spring:
  datasource:
    hikari:
      minimum-idle: 5
      maximum-pool-size: 10
      connection-timeout: 5000
```

#### 2. 중간 규모 애플리케이션
```yaml
spring:
  datasource:
    hikari:
      minimum-idle: 10
      maximum-pool-size: 20
      connection-timeout: 5000
      idle-timeout: 300000    # 5분
      max-lifetime: 1800000   # 30분
```

#### 3. 대규모 애플리케이션 (다중 서버)
```yaml
spring:
  datasource:
    hikari:
      minimum-idle: 15
      maximum-pool-size: 30
      connection-timeout: 3000
      idle-timeout: 180000    # 3분
      max-lifetime: 1200000   # 20분

      # 추가 최적화
      leak-detection-threshold: 60000  # 커넥션 누수 감지
```

**주의**: 다중 서버 환경에서는 전체 커넥션 수 고려
```
총 커넥션 = 서버 수 × 서버당 풀 크기
예: 5대 서버 × 30개 = 150개
→ DB 최대 커넥션 설정(max_connections) 확인 필수
```

### 커넥션 풀 모니터링

#### HikariCP 메트릭 수집
```java
@Configuration
public class HikariMetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsConfig(HikariDataSource dataSource) {
        return registry -> {
            dataSource.setMetricRegistry((MetricRegistry) registry);
        };
    }
}
```

#### 주요 모니터링 메트릭
```java
@Service
public class ConnectionPoolMonitor {

    @Autowired
    private HikariDataSource dataSource;

    public PoolStats getPoolStats() {
        HikariPoolMXBean poolProxy = dataSource.getHikariPoolMXBean();

        return PoolStats.builder()
            .totalConnections(poolProxy.getTotalConnections())      // 전체 커넥션 수
            .activeConnections(poolProxy.getActiveConnections())    // 사용 중인 커넥션
            .idleConnections(poolProxy.getIdleConnections())        // 유휴 커넥션
            .threadsAwaitingConnection(poolProxy.getThreadsAwaitingConnection()) // 대기 중인 스레드
            .build();
    }
}
```

#### 로깅 설정
```yaml
logging:
  level:
    com.zaxxer.hikari: DEBUG
    com.zaxxer.hikari.HikariConfig: DEBUG
```

### 커넥션 누수 (Connection Leak) 방지

#### 문제 상황
```java
// 잘못된 예: 커넥션을 닫지 않음
public User findUser(Long id) {
    Connection conn = dataSource.getConnection();
    // 쿼리 실행
    return executeQuery(conn, id);
    // conn.close() 누락! → 커넥션 누수
}
```

#### 올바른 사용: try-with-resources
```java
public User findUser(Long id) {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {

        pstmt.setLong(1, id);
        ResultSet rs = pstmt.executeQuery();

        if (rs.next()) {
            return mapToUser(rs);
        }
        return null;
    } catch (SQLException e) {
        throw new DataAccessException("Failed to find user", e);
    }
    // 자동으로 close() 호출 → 커넥션 풀에 반환
}
```

#### Spring JdbcTemplate 사용 (권장)
```java
@Repository
public class UserRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public User findUser(Long id) {
        return jdbcTemplate.queryForObject(
            "SELECT * FROM users WHERE id = ?",
            new Object[]{id},
            (rs, rowNum) -> mapToUser(rs)
        );
        // JdbcTemplate이 자동으로 커넥션 관리
    }
}
```

#### 커넥션 누수 감지 설정
```yaml
spring:
  datasource:
    hikari:
      # 커넥션이 60초 이상 대여되면 경고
      leak-detection-threshold: 60000
```

### 트러블슈팅

#### 문제 1: "Connection timeout" 오류
**원인**: 풀의 모든 커넥션이 사용 중
**해결책**:
1. 커넥션 누수 확인 (leak-detection-threshold 활성화)
2. maximumPoolSize 증가 검토
3. 쿼리 성능 최적화 (느린 쿼리 개선)
4. 트랜잭션 범위 최소화

#### 문제 2: 너무 많은 유휴 커넥션
**원인**: minimumIdle이 너무 높게 설정
**해결책**:
1. 실제 동시 요청 수 모니터링
2. minimumIdle 조정
3. idleTimeout 단축

#### 문제 3: DB 최대 커넥션 초과
**원인**: 여러 서버의 커넥션 풀 크기 합이 DB max_connections 초과
**해결책**:
```sql
-- MySQL 최대 커넥션 확인
SHOW VARIABLES LIKE 'max_connections';

-- 현재 커넥션 수 확인
SHOW STATUS LIKE 'Threads_connected';

-- 최대 커넥션 증가 (필요시)
SET GLOBAL max_connections = 500;
```

### 성능 최적화 팁

#### 1. PreparedStatement 캐싱
```yaml
spring:
  datasource:
    hikari:
      data-source-properties:
        cachePrepStmts: true
        prepStmtCacheSize: 250
        prepStmtCacheSqlLimit: 2048
```

#### 2. 커넥션 유효성 검사 최소화
```yaml
spring:
  datasource:
    hikari:
      # 커넥션을 풀에서 가져올 때 검사하지 않음 (성능 향상)
      connection-test-query: null
      # 대신 keepalive 사용
      keepalive-time: 300000  # 5분마다 검사
```

#### 3. 읽기 전용 트랜잭션 최적화
```java
@Transactional(readOnly = true)
public List<User> findAllUsers() {
    return userRepository.findAll();
    // readOnly=true로 최적화
}
```

### 다른 Connection Pool 라이브러리

#### 1. HikariCP (권장)
- Spring Boot 2.0+ 기본
- 가장 빠른 성능
- 적은 메모리 사용

#### 2. Tomcat JDBC Pool
- Spring Boot 1.x 기본
- 안정적이지만 HikariCP보다 느림

#### 3. Apache Commons DBCP2
- 레거시 프로젝트에서 사용
- 설정이 복잡함

#### 성능 비교 (벤치마크)
```
HikariCP:      100ms
Tomcat Pool:   150ms
Commons DBCP2: 200ms
```

## 핵심 정리

### 커넥션 풀의 이점
- **성능 향상**: 커넥션 생성/종료 비용 절감 (100배 차이)
- **자원 관리**: 동시 DB 연결 수 제한
- **안정성**: 커넥션 재사용으로 신뢰성 향상

### 최적 설정 원칙
- **풀 크기**: 많다고 좋은 것이 아님
  - 권장: `(CPU 코어 수 × 2) + 디스크 수`
  - 예: 4코어, SSD → 9개
- **타임아웃**: 적절한 타임아웃 설정으로 장애 빠른 감지
- **모니터링**: 실시간 메트릭 수집 및 분석 필수

### 주의사항
- 커넥션 누수 방지 (try-with-resources 또는 JdbcTemplate 사용)
- 다중 서버 환경에서 전체 커넥션 수 고려
- DB의 max_connections 설정 확인
- 실제 부하 테스트로 최적 값 찾기

### HikariCP 권장 설정
```yaml
spring:
  datasource:
    hikari:
      minimum-idle: 10
      maximum-pool-size: 20
      connection-timeout: 5000
      max-lifetime: 1800000
      idle-timeout: 600000
      leak-detection-threshold: 60000
```

## 참고 자료
- [HikariCP Official Documentation](https://github.com/brettwooldridge/HikariCP)
- [HikariCP Wiki - About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [Spring Boot Reference - Data Access](https://docs.spring.io/spring-boot/docs/current/reference/html/data.html)
- [PostgreSQL Wiki - Number of Database Connections](https://wiki.postgresql.org/wiki/Number_Of_Database_Connections)
- [쉬운코드 - DBCP](https://www.youtube.com/watch?v=zowzVqx3MQ4)
