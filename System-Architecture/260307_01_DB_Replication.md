# DB Replication

## 개요
데이터베이스 복제(Replication)는 하나의 DB 데이터를 여러 서버에 복제하여 가용성, 읽기 성능, 장애 대응력을 높이는 핵심 아키텍처 전략이다.

## 상세 내용

### 1. Replication이란

#### Master-Slave(Source-Replica) 구조의 기본 개념

MySQL Replication은 하나의 Source(구 Master) 서버에서 발생한 데이터 변경 이벤트를 하나 이상의 Replica(구 Slave) 서버로 전달하여 동일한 데이터를 유지하는 기술이다. MySQL 8.0.26부터 공식적으로 Source/Replica 용어를 채택하였다.

```
[Client Write] → [Source DB] → Binary Log
                                    ↓
                [Replica 1] ← IO Thread ← Relay Log ← SQL Thread
                [Replica 2] ← IO Thread ← Relay Log ← SQL Thread
```

Source는 모든 쓰기(INSERT, UPDATE, DELETE, DDL)를 처리하고, Replica는 Source의 Binary Log를 읽어 동일한 변경을 재실행한다.

#### 왜 Replication이 필요한가

| 목적 | 설명 |
|------|------|
| **읽기 성능 분산** | SELECT 쿼리를 Replica로 분산시켜 Source의 부하를 줄임 |
| **고가용성(HA)** | Source 장애 시 Replica를 Source로 승격하여 서비스 연속성 확보 |
| **데이터 백업** | Replica에서 mysqldump, xtrabackup 수행 시 Source에 영향 없음 |
| **분석/리포팅** | OLAP 쿼리를 Replica로 격리하여 OLTP 성능 보호 |
| **지리적 분산** | 원거리 지역에 Replica를 두어 로컬 읽기 지연 감소 |

---

### 2. Replication 방식

#### 동기(Synchronous) vs 비동기(Asynchronous) vs 반동기(Semi-Synchronous)

**비동기(Asynchronous) 복제 - MySQL 기본값**

Source는 Binary Log에 이벤트를 기록하고 즉시 클라이언트에게 커밋 완료를 응답한다. Replica가 해당 이벤트를 수신했는지 여부를 확인하지 않는다.

```
[Client] → COMMIT → [Source] → 응답 반환 (Replica 확인 없음)
                     ↓ (비동기)
                  [Replica] (언제 받을지 보장 없음)
```

**반동기(Semi-Synchronous) 복제**

Source는 최소 1개 이상의 Replica가 Relay Log에 이벤트를 기록했다는 ACK를 받은 후 클라이언트에게 응답한다. Replica에서 실제 SQL 실행(커밋)을 기다리는 것은 아니다.

```
[Client] → COMMIT → [Source] → Replica ACK 대기 → 응답 반환
                                     ↓
                               [Replica] → Relay Log 수신 후 ACK
```

- `rpl_semi_sync_source_wait_point = AFTER_SYNC` (기본): binlog flush 후 ACK 수신
- `rpl_semi_sync_source_wait_point = AFTER_COMMIT`: 커밋 후 ACK 수신
- 타임아웃(`rpl_semi_sync_source_timeout`) 초과 시 자동으로 비동기 모드로 전환

**동기(Synchronous) 복제**

Source가 커밋을 완료하려면 모든 Replica가 해당 트랜잭션을 커밋해야 한다. MySQL의 단독 Replication에서는 지원하지 않으며, MySQL NDB Cluster 또는 Group Replication의 Paxos 합의 방식에서 사용된다.

#### 각 방식의 장단점과 데이터 정합성 트레이드오프

| 항목 | 비동기 | 반동기 | 동기 |
|------|--------|--------|------|
| **쓰기 지연** | 없음 | 네트워크 왕복 1회 | 가장 느림 |
| **데이터 유실 위험** | 있음 (Source 장애 시) | 최소 1개 Replica에 보장 | 없음 |
| **Failover 안전성** | 낮음 | 중간 | 높음 |
| **네트워크 의존성** | 낮음 | 중간 | 높음 |
| **적합한 환경** | 읽기 분산 목적 | 금융/결제 시스템 | NDB Cluster |

---

### 3. MySQL Replication 동작 원리

#### Binary Log(binlog) 기반 복제 흐름

Binary Log는 MySQL Source 서버에서 데이터를 변경하는 모든 이벤트를 기록하는 파일이다. Replication의 핵심 매커니즘이며, Point-in-Time Recovery에도 사용된다.

**Binary Log 포맷 3가지**

| 포맷 | 설명 | 장점 | 단점 |
|------|------|------|------|
| **STATEMENT** | SQL 문 그대로 기록 | 로그 크기 작음 | 비결정적 함수(NOW(), UUID()) 문제 |
| **ROW** | 변경된 행 데이터 기록 | 정확한 복제 보장 | 대량 UPDATE 시 로그 크기 증가 |
| **MIXED** | 상황에 따라 STATEMENT/ROW 선택 | 균형적 | 예측 어려움 |

MySQL 8.0에서는 ROW 포맷이 기본값이다.

**복제 흐름 단계**

```
[Source]
1. 트랜잭션 실행
2. Binary Log에 이벤트 기록 (binlog_format에 따라)
3. 스토리지 엔진에 커밋

[Replica - IO Thread]
4. Source에 연결 → COM_BINLOG_DUMP 명령 전송
5. Source Binary Log 이벤트 수신
6. Relay Log에 순서대로 기록

[Replica - SQL Thread (Applier)]
7. Relay Log에서 이벤트 읽기
8. 순서대로 실행 (적용)
9. Replica 스토리지 엔진에 커밋
```

#### Relay Log의 역할

Relay Log는 Replica의 IO Thread가 Source로부터 수신한 Binary Log 이벤트를 임시로 저장하는 파일이다.

- **IO Thread와 SQL Thread의 비동기 실행을 가능하게 함**: IO Thread는 계속 Source에서 이벤트를 수신하고, SQL Thread는 독립적으로 Relay Log를 소비
- **네트워크 끊김 시 복원력**: 재연결 후 중단 지점부터 재개 가능
- **병렬 복제 지원**: MySQL 5.7+에서 `slave_parallel_workers`(또는 `replica_parallel_workers`) 설정으로 SQL Thread를 여러 Worker로 병렬 실행

#### GTID(Global Transaction ID) 기반 복제

GTID는 Source 서버에서 커밋된 각 트랜잭션에 부여되는 전역 고유 식별자다. MySQL 5.6+에서 도입되었으며 MySQL 8.0에서 권장 방식이다.

**GTID 형식**

```
GTID = source_id:transaction_id
예) 3E11FA47-71CA-11E1-9E33-C80AA9429562:23
```

- `source_id`: Source 서버의 `server_uuid`
- `transaction_id`: 해당 서버에서 커밋된 순번

**GTID 기반 복제의 장점**

```sql
-- 기존 Position 기반 복제
CHANGE MASTER TO
  MASTER_HOST='source_host',
  MASTER_LOG_FILE='binlog.000003',   -- 파일명 직접 지정 필요
  MASTER_LOG_POS=107;

-- GTID 기반 복제 (파일/포지션 불필요)
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='source_host',
  SOURCE_AUTO_POSITION=1;            -- GTID로 자동 위치 추적
```

- Failover 후 새 Source로 전환 시 로그 파일/위치를 수동으로 찾을 필요 없음
- 중복 트랜잭션 적용 방지
- `GTID_EXECUTED` 시스템 변수로 현재 복제 상태 확인 가능

```sql
-- 복제 상태 확인
SHOW REPLICA STATUS\G
-- Executed_Gtid_Set, Retrieved_Gtid_Set 확인

-- Replica가 특정 GTID를 받을 때까지 대기 (최대 5초)
SELECT WAIT_FOR_EXECUTED_GTID_SET('3E11FA47-71CA-11E1-9E33-C80AA9429562:1-100', 5);
```

---

### 4. Replication 토폴로지

#### Single Source → Multi Replica (가장 일반적)

```
         [Source]
        /    |    \
  [R1] [R2] [R3] [R4]
```

- 가장 단순하고 일반적인 구성
- 읽기 요청을 여러 Replica로 분산
- Source가 단일 장애점(SPOF)이므로 Failover 전략 필요

#### Chain Replication (계층형 복제)

```
[Source] → [Replica-1(Relay)] → [Replica-2] → [Replica-3]
```

- 중간 Replica가 다음 Replica의 Source 역할을 수행
- Source의 바이너리 로그 전송 부하를 분산할 수 있음
- 단점: 체인이 길어질수록 복제 지연이 누적됨
- 중간 노드에 `log_slave_updates = ON` 설정 필요 (수신한 이벤트를 자신의 binlog에도 기록)

#### Multi-Source Replication

```
[Source-A] ──→ [Replica]
[Source-B] ──→ [Replica]
[Source-C] ──→ [Replica]
```

- MySQL 5.7+에서 지원
- 여러 Source로부터 데이터를 하나의 Replica에 통합
- 데이터 집계, 분산 DB 통합, 샤딩 환경에서 보고용 DB 구성에 활용
- 각 Source는 독립적인 Replication Channel로 관리

```sql
-- Multi-Source Replication 채널 설정
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='source_a_host',
  SOURCE_PORT=3306,
  SOURCE_AUTO_POSITION=1
FOR CHANNEL 'source_a';

START REPLICA FOR CHANNEL 'source_a';
```

#### Group Replication (MySQL InnoDB Cluster)

```
┌─────────────────────────────────┐
│  InnoDB Cluster (Paxos Group)   │
│  [Primary] ↔ [Secondary-1]      │
│       ↕          ↕              │
│  [Secondary-2]                  │
└─────────────────────────────────┘
        ↓ (Async Read Replica)
    [Read Replica]
```

- Paxos 합의 알고리즘 기반의 분산 복제
- **Single-Primary Mode**: 1개 Primary(쓰기), 나머지 Secondary(읽기) - 기본값
- **Multi-Primary Mode**: 모든 노드에서 쓰기 가능 (충돌 감지 및 롤백 있음)
- 최소 3개 노드 필요 (과반수 쿼럼)
- MySQL Shell의 AdminAPI로 클러스터 관리
- MySQL 8.0.23+에서 Read Replica를 InnoDB Cluster에 비동기로 추가 가능

---

### 5. Replication Lag과 데이터 정합성 문제

#### Replica Lag이 발생하는 원인

Replica Lag은 `Seconds_Behind_Source`(SHOW REPLICA STATUS) 값으로 확인할 수 있으며, 다음 원인들로 발생한다.

| 원인 | 설명 | 해결 방법 |
|------|------|-----------|
| **단일 스레드 SQL Thread** | SQL Thread가 단일 스레드로 Source의 병렬 쓰기를 직렬로 처리 | `replica_parallel_workers` 값 증가 |
| **대용량 트랜잭션** | 수백만 행 UPDATE/DELETE 시 binlog 전송 후 적용까지 지연 | 트랜잭션 분할, 배치 처리 |
| **Primary Key 없는 테이블** | ROW 기반 복제 시 전체 테이블 스캔으로 적용 (아래 상세 설명 참고) | 모든 테이블에 PK 추가 |
| **네트워크 지연** | Source→Replica 간 대역폭 부족 또는 지연 | 네트워크 최적화, 압축 설정 |
| **Replica 서버 리소스 부족** | CPU, I/O 처리 능력 부족 | 스펙 업그레이드 |
| **Lock 경합** | REPEATABLE READ/SERIALIZABLE에서 Lock 충돌 | READ COMMITTED 격리 수준 사용 |

**ROW 기반 복제에서 PK 유무에 따른 행 탐색 방식**

ROW 기반 복제는 Binary Log에 변경된 행의 Before Image(변경 전 데이터)를 기록한다. Replica의 SQL Thread는 이 Before Image를 사용하여 대상 행을 찾은 뒤 변경을 적용해야 한다. 이때 행을 찾는 방식이 PK 유무에 따라 크게 달라진다.

| 조건 | 탐색 방식 | 시간 복잡도 | 설명 |
|------|-----------|------------|------|
| **PK 있음** | Primary Key Lookup | O(log N) | Before Image의 PK 값으로 클러스터드 인덱스(B+Tree)를 탐색하여 대상 행을 즉시 찾음 |
| **Unique Key만 있음** | Unique Index Lookup | O(log N) | PK가 없으면 Unique Key를 사용하여 인덱스 탐색 |
| **PK/UK 모두 없음** | Full Table Scan | O(N) | 인덱스를 사용할 수 없어 테이블 전체를 순차 스캔하며 Before Image와 일치하는 행을 찾음 |

```
[PK 있는 경우 - UPDATE 1건 복제]
Before Image: {id=42, name='old', email='old@test.com'}
  → Replica: WHERE id = 42  → B+Tree Index Lookup → 즉시 찾음 (1건 탐색)

[PK 없는 경우 - UPDATE 1건 복제]
Before Image: {name='old', email='old@test.com'}
  → Replica: 전체 테이블을 스캔하며 모든 컬럼이 일치하는 행을 찾음
  → 100만 행 테이블이면 최대 100만 행 비교
```

특히 PK가 없는 테이블에서 대량 UPDATE/DELETE가 발생하면 **변경된 행 수 × 테이블 전체 행 수**만큼의 비교가 필요하여 Replication Lag이 급격히 증가한다. 예를 들어 1,000건 UPDATE × 100만 행 테이블이면 최대 10억 번의 행 비교가 발생할 수 있다.

> MySQL 공식 문서에서도 ROW 기반 복제 시 모든 테이블에 PK를 정의할 것을 강력히 권장한다. `sql_require_primary_key = ON` 설정으로 PK 없는 테이블 생성을 원천 차단할 수 있다.

**병렬 복제 활성화 (MySQL 8.0)**

```sql
-- Replica 서버 설정
SET GLOBAL replica_parallel_workers = 4;
SET GLOBAL replica_parallel_type = 'LOGICAL_CLOCK';  -- binlog group commit 기반
SET GLOBAL replica_preserve_commit_order = ON;        -- 커밋 순서 보장
```

#### Read-after-Write Consistency 보장 전략

사용자가 데이터를 쓴 직후 읽기 요청이 Replica로 라우팅되면 아직 복제되지 않은 데이터를 읽는 문제(Stale Read)가 발생한다.

**전략 1: 쓰기 후 일정 시간 동안 Source 읽기**

```
사용자 쓰기 → Source
사용자 읽기 (쓰기 후 2초 이내) → Source
사용자 읽기 (2초 이후) → Replica
```

Redis에 `user:{id}:hot` 키를 1~3초 TTL로 저장하고, 해당 키가 존재하면 Source에서 읽는 방식으로 구현한다.

**전략 2: GTID Wait 방식**

```java
// 쓰기 트랜잭션 후 실행된 GTID 확인
String gtid = getExecutedGtidFromSource();

// Replica에서 해당 GTID가 적용될 때까지 대기
jdbcTemplate.queryForObject(
    "SELECT WAIT_FOR_EXECUTED_GTID_SET(?, 5)", Integer.class, gtid
);
// 반환값 0: 성공, 1: 타임아웃
```

**전략 3: Monotonic Read (세션 고정)**

동일 사용자의 읽기 요청은 항상 같은 Replica 또는 Source로 라우팅한다. 사용자가 이전보다 오래된 데이터를 읽는 상황(시간 역행)을 방지한다.

**전략 4: Semi-Sync 복제 사용**

쓰기 시점에 최소 1개 Replica의 ACK를 보장하므로, ACK를 응답한 Replica에서 즉시 읽기가 가능하다.

#### 쓰기 후 읽기를 Source로 라우팅하는 패턴

```java
// 트랜잭션 컨텍스트 홀더
public class DataSourceContextHolder {
    private static final ThreadLocal<DataSourceType> context = new ThreadLocal<>();

    public static void setDataSourceType(DataSourceType type) {
        context.set(type);
    }

    public static DataSourceType getDataSourceType() {
        return context.get();
    }

    public static void clearDataSourceType() {
        context.remove();
    }
}

// 쓰기 후 강제 Source 읽기 (AOP 또는 서비스 레이어에서)
public void createAndReadUser(UserDto dto) {
    userRepository.save(dto);  // Source에 쓰기
    DataSourceContextHolder.setDataSourceType(DataSourceType.SOURCE); // 강제 Source 라우팅
    try {
        User user = userRepository.findById(dto.getId()); // Source에서 읽기
    } finally {
        DataSourceContextHolder.clearDataSourceType();
    }
}
```

---

### 6. Failover와 고가용성

#### Source 장애 시 Replica 승격(Promotion) 과정

Failover는 Source 서버 장애 시 Replica 중 하나를 새로운 Source로 전환하는 과정이다.

**수동 Failover 절차 (GTID 기반)**

```sql
-- 1. 장애난 Source 연결 차단 (접근 불가 상태 확인)

-- 2. 가장 최신 Replica 선택 (Retrieved_Gtid_Set이 가장 큰 것)
SHOW REPLICA STATUS\G  -- 각 Replica에서 확인

-- 3. 선택된 Replica에서 복제 중단 및 쓰기 활성화
STOP REPLICA;
RESET REPLICA ALL;
SET GLOBAL read_only = OFF;
SET GLOBAL super_read_only = OFF;

-- 4. 나머지 Replica를 새 Source에 연결
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='new_source_host',
  SOURCE_AUTO_POSITION=1;
START REPLICA;

-- 5. 애플리케이션의 Source 연결 설정 변경
```

**GTID 기반 vs Position 기반 Failover 비교**

| 항목 | Position 기반 | GTID 기반 |
|------|-------------|----------|
| 복잡도 | 높음 (파일명, 위치 수동 계산) | 낮음 (자동 위치 추적) |
| 오류 가능성 | 높음 | 낮음 |
| 자동화 용이성 | 어려움 | 쉬움 |

#### 자동 Failover 도구

**MHA (Master High Availability Manager)**
- Percona에서 관리하는 오픈소스 Failover 도구
- Replica 중 가장 최신 상태의 서버를 자동으로 새 Source로 승격
- 다른 Replica들에게 새 Source를 자동으로 알림
- ProxySQL과 연동하여 애플리케이션 재설정 없이 투명한 Failover 가능

**Orchestrator**
- GitHub에서 개발한 MySQL 토폴로지 관리 도구
- 복제 토폴로지를 시각적으로 표시 및 관리
- 자동 Failover, Replication 재구성, GTID 기반 토폴로지 변경
- REST API, Web UI 제공

**ProxySQL**
- MySQL 전용 고성능 프록시 서버
- `mysql_replication_hostgroups` 테이블로 Writer/Reader 그룹 관리
- `read_only=1` 감지로 자동 Writer/Reader 분류
- Failover 시 애플리케이션 연결 변경 없이 자동 라우팅

```sql
-- ProxySQL 라우팅 규칙 예시
INSERT INTO mysql_replication_hostgroups (writer_hostgroup, reader_hostgroup)
VALUES (10, 20);  -- hostgroup 10: Writer, hostgroup 20: Reader

INSERT INTO mysql_query_rules (rule_id, active, match_pattern, destination_hostgroup)
VALUES (1, 1, '^SELECT', 20);   -- SELECT → Reader
-- 기본적으로 나머지는 Writer로 라우팅
```

#### Split-Brain 문제와 방지 전략

Split-Brain은 네트워크 파티션으로 인해 2개 이상의 노드가 자신이 Source(Primary)라고 믿고 동시에 쓰기를 받는 상황이다. 데이터 불일치와 복구 불가능한 충돌이 발생할 수 있다.

**방지 전략**

| 전략 | 설명 |
|------|------|
| **STONITH (Shoot The Other Node In The Head)** | 장애 감지 시 구 Source 서버를 강제 종료 또는 네트워크 격리 |
| **Quorum 기반 결정** | 과반수 노드의 동의가 있을 때만 Primary 승격 허용 (Group Replication) |
| **VIP(Virtual IP) 단일화** | 하나의 VIP만 새 Source에 할당하여 애플리케이션 접근 제어 |
| **Fencing** | 구 Source의 디스크 또는 네트워크 접근을 물리적으로 차단 |
| **read_only 즉시 설정** | 장애 감지 즉시 구 Source에 `SET GLOBAL super_read_only=ON` |

```sql
-- 구 Source 격리 시 즉시 실행
SET GLOBAL super_read_only = ON;
-- 이후 모든 쓰기 요청이 오류 반환됨
```

---

### 7. 애플리케이션 레벨 라우팅

#### Spring Boot에서 읽기/쓰기 분리 (AbstractRoutingDataSource)

`AbstractRoutingDataSource`는 Spring JDBC가 제공하는 추상 클래스로, `determineCurrentLookupKey()`를 구현하여 런타임에 사용할 DataSource를 동적으로 결정한다.

**1단계: DataSource 타입 정의**

```java
public enum DataSourceType {
    SOURCE, REPLICA
}
```

**2단계: RoutingDataSource 구현**

```java
public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        // 현재 트랜잭션이 readOnly인지 확인
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return isReadOnly ? DataSourceType.REPLICA : DataSourceType.SOURCE;
    }
}
```

**3단계: DataSource 설정**

```java
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.source")
    public DataSource sourceDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.replica")
    public DataSource replicaDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public DataSource routingDataSource(
            @Qualifier("sourceDataSource") DataSource sourceDataSource,
            @Qualifier("replicaDataSource") DataSource replicaDataSource) {

        Map<Object, Object> dataSources = new HashMap<>();
        dataSources.put(DataSourceType.SOURCE, sourceDataSource);
        dataSources.put(DataSourceType.REPLICA, replicaDataSource);

        RoutingDataSource routingDataSource = new RoutingDataSource();
        routingDataSource.setTargetDataSources(dataSources);
        routingDataSource.setDefaultTargetDataSource(sourceDataSource);
        return routingDataSource;
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("routingDataSource") DataSource routingDataSource) {
        // LazyConnectionDataSourceProxy: 트랜잭션 속성이 결정된 후 실제 연결을 얻음
        // 이를 통해 @Transactional(readOnly=true) 적용 후에 라우팅 키가 결정됨
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
```

**application.yml 설정**

```yaml
spring:
  datasource:
    source:
      jdbc-url: jdbc:mysql://source-host:3306/mydb
      username: app_user
      password: secret
      driver-class-name: com.mysql.cj.jdbc.Driver
    replica:
      jdbc-url: jdbc:mysql://replica-host:3306/mydb
      username: app_user
      password: secret
      driver-class-name: com.mysql.cj.jdbc.Driver
```

#### @Transactional(readOnly = true)와 Replica 라우팅

```java
@Service
@Transactional(readOnly = true)  // 기본적으로 모든 메서드를 Replica로 라우팅
public class UserService {

    private final UserRepository userRepository;

    // readOnly = true → TransactionSynchronizationManager.isCurrentTransactionReadOnly() = true
    // → RoutingDataSource가 REPLICA DataSource 선택
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    @Transactional  // readOnly = false → SOURCE DataSource 선택
    public User createUser(UserCreateRequest request) {
        User user = User.of(request.getName(), request.getEmail());
        return userRepository.save(user);
    }

    @Transactional  // 쓰기 포함 → SOURCE
    public User updateEmail(Long id, String newEmail) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        user.updateEmail(newEmail);
        return user;
    }
}
```

**주의사항**: `@Transactional` 없이 호출되는 메서드는 `TransactionSynchronizationManager`에 트랜잭션 컨텍스트가 없으므로 `isCurrentTransactionReadOnly()`가 `false`를 반환하여 Source로 라우팅된다.

#### Connection Pool 설정 시 고려사항

**HikariCP 개별 Pool 설정**

Source와 Replica는 서로 다른 부하 패턴을 가지므로 독립적인 Pool 크기를 설정한다.

```yaml
spring:
  datasource:
    source:
      jdbc-url: jdbc:mysql://source-host:3306/mydb
      hikari:
        maximum-pool-size: 10        # 쓰기 부하에 맞게 설정
        minimum-idle: 5
        connection-timeout: 3000
        idle-timeout: 600000
        max-lifetime: 1800000
        pool-name: SourcePool
    replica:
      jdbc-url: jdbc:mysql://replica-host:3306/mydb
      hikari:
        maximum-pool-size: 20        # 읽기 부하가 더 크므로 더 크게 설정
        minimum-idle: 5
        connection-timeout: 3000
        idle-timeout: 600000
        max-lifetime: 1800000
        pool-name: ReplicaPool
```

**주요 고려사항**

| 항목 | 내용 |
|------|------|
| **Pool 크기 공식** | `connections = (core_count * 2) + effective_spindle_count` (HikariCP 권장) |
| **Connection Timeout** | Replica 장애 시 Source로 Fallback 처리 로직 추가 권장 |
| **Replica 다중 구성** | 여러 Replica가 있을 경우 Round-Robin 또는 가중치 기반 라우팅 구현 |
| **Health Check** | `connection-test-query: SELECT 1`으로 연결 유효성 검증 |
| **Replica 장애 대응** | Replica 연결 실패 시 Source로 Fallback하는 Circuit Breaker 패턴 적용 |

---

## 핵심 정리

- **Replication의 핵심 목적은 읽기 확장과 고가용성이다.** 단일 Source로는 읽기 부하를 감당하기 어려울 때 Replica를 추가하여 SELECT 트래픽을 분산하고, Source 장애 시 Replica 승격으로 서비스 연속성을 확보한다.
- **비동기 복제가 기본이며 Replication Lag은 피할 수 없다.** 데이터 정합성이 중요한 쓰기 직후 읽기는 반드시 Source로 라우팅하거나, GTID Wait 전략을 사용해야 한다.
- **GTID 기반 복제를 사용하면 Failover가 훨씬 단순해진다.** Position 기반 복제는 Failover 시 파일명과 위치를 수동으로 맞춰야 하지만, GTID는 `SOURCE_AUTO_POSITION=1`로 자동 처리된다.
- **Semi-Sync 복제는 데이터 유실과 성능 사이의 균형점이다.** 비동기보다 안전하고 동기보다 빠르며, Source 장애 시 최소 1개 Replica에 데이터가 보존됨을 보장한다.
- **Split-Brain은 Replication 환경의 치명적 문제다.** 자동 Failover 구현 시 구 Source를 즉시 격리(STONITH, Fencing)하거나 Quorum 기반 Group Replication을 사용하여 Split-Brain을 방지해야 한다.
- **Spring Boot에서의 읽기/쓰기 분리는 `AbstractRoutingDataSource` + `LazyConnectionDataSourceProxy` 조합으로 구현한다.** `LazyConnectionDataSourceProxy`가 없으면 `@Transactional(readOnly=true)` 설정이 DataSource 선택에 반영되지 않는다.
- **병렬 복제(`replica_parallel_workers`)와 LOGICAL_CLOCK 방식으로 Replication Lag을 줄일 수 있다.** 단일 SQL Thread의 직렬 처리 한계를 극복하여 Source의 병렬 쓰기를 Replica에서도 병렬로 적용한다.

---

## 키워드

`Replication`: 하나의 Source DB에서 발생한 데이터 변경 이벤트를 하나 이상의 Replica DB에 복제하여 동일한 데이터를 유지하는 기술. 읽기 분산, 고가용성, 백업 목적으로 사용된다.

`Master-Slave`: Replication의 구 용어로, 쓰기를 담당하는 Master(현재: Source)와 복제본인 Slave(현재: Replica)로 구성된 아키텍처 패턴. MySQL 8.0.26부터 Source/Replica 용어로 대체되었다.

`Source-Replica`: MySQL 8.0.26+에서 채택된 공식 용어. Source는 쓰기 연산을 처리하고 Binary Log를 생성하며, Replica는 Source의 Binary Log를 읽어 동일한 변경을 재실행한다.

`Binary Log`: MySQL Source 서버에서 데이터를 변경하는 모든 이벤트(DML, DDL)를 기록하는 로그 파일. Replication과 Point-in-Time Recovery의 핵심 메커니즘이며, STATEMENT/ROW/MIXED 세 가지 포맷이 있다.

`Relay Log`: Replica의 IO Thread가 Source의 Binary Log를 수신하여 로컬에 임시 저장하는 파일. IO Thread와 SQL Thread(Applier)의 비동기 처리를 가능하게 하며, 병렬 복제의 기반이 된다.

`GTID`: Global Transaction Identifier. Source 서버에서 커밋된 각 트랜잭션에 부여되는 `server_uuid:transaction_id` 형식의 전역 고유 식별자. Failover 시 수동 포지션 지정 없이 자동으로 복제 위치를 추적한다.

`Replication Lag`: Replica가 Source의 변경 사항을 적용하는 데 걸리는 지연 시간. `SHOW REPLICA STATUS`의 `Seconds_Behind_Source`로 측정하며, 단일 SQL Thread 한계, 대용량 트랜잭션, 네트워크 지연, PK 없는 테이블 등이 주요 원인이다.

`Failover`: Source 서버 장애 시 Replica 중 하나를 새로운 Source로 승격하여 서비스 연속성을 유지하는 절차. MHA, Orchestrator, Group Replication이 자동 Failover를 지원한다.

`Semi-Synchronous`: 반동기 복제 방식. Source가 커밋을 완료하기 전에 최소 1개 이상의 Replica로부터 Relay Log 수신 확인(ACK)을 받아야 응답한다. 비동기보다 데이터 안전성이 높고 동기보다 성능 저하가 적다.

`AbstractRoutingDataSource`: Spring JDBC에서 제공하는 추상 클래스로, `determineCurrentLookupKey()`를 구현하여 런타임에 동적으로 DataSource를 선택할 수 있게 한다. `@Transactional(readOnly=true)` 여부에 따라 Source/Replica DataSource를 라우팅하는 데 사용된다.

---

## 추가 용어 설명

`Failover`: 운영 중인 시스템(Source)에 장애가 발생했을 때 대기 중인 시스템(Replica)으로 자동 또는 수동 전환하여 서비스 중단을 최소화하는 절차. 수동 Failover는 DBA가 직접 수행하고, 자동 Failover는 MHA, Orchestrator 등의 도구가 장애를 감지하여 Replica 승격과 라우팅 변경을 자동 처리한다.

`Point-in-Time Recovery (PITR)`: 특정 시점의 데이터 상태로 복원하는 기법. 전체 백업(mysqldump, xtrabackup)을 먼저 복원한 뒤, Binary Log를 특정 타임스탬프 또는 GTID 지점까지 순차 재실행(`mysqlbinlog --stop-datetime`)하여 원하는 시점의 데이터를 복구한다. 실수로 인한 데이터 삭제나 장애 복구에 사용된다.

`OLTP (Online Transaction Processing)`: 짧고 빈번한 트랜잭션(INSERT, UPDATE, DELETE, 단건 SELECT)을 빠르게 처리하는 워크로드 유형. 수강신청, 결제, 주문 등 실시간 서비스의 핵심 데이터 처리를 담당하며, 낮은 지연시간과 높은 동시성이 중요하다. MySQL InnoDB가 대표적인 OLTP 엔진이다.

`OLAP (Online Analytical Processing)`: 대량의 데이터를 집계·분석하는 읽기 중심 워크로드 유형. GROUP BY, JOIN, 서브쿼리 등 복잡한 분석 쿼리를 수행하며, 매출 리포트·사용자 통계 등에 사용된다. OLTP 서버에서 직접 실행하면 서비스 성능에 영향을 주므로, Replica나 별도 분석 DB(ClickHouse, BigQuery 등)로 격리하는 것이 일반적이다.

`MySQL NDB Cluster`: MySQL의 분산 인메모리 스토리지 엔진(NDB = Network DataBase)을 사용하는 클러스터 솔루션. 데이터를 여러 Data Node에 자동 샤딩하여 저장하고, 동기 복제와 자동 Failover를 네이티브로 지원한다. 통신사 과금 시스템 등 극한의 고가용성과 낮은 지연이 요구되는 환경에 적합하지만, JOIN 성능 제약과 인메모리 한계로 일반 웹 서비스에서는 InnoDB Cluster를 더 많이 사용한다.

`Paxos (Group Replication)`: MySQL Group Replication이 노드 간 합의에 사용하는 분산 합의 알고리즘. 트랜잭션이 커밋되기 전에 과반수 노드(3대 중 2대, 5대 중 3대)의 동의를 얻어야 하며, 이를 통해 네트워크 파티션 상황에서도 데이터 일관성을 보장하고 Split-Brain을 방지한다. Group Replication은 Paxos의 변형인 XCom(eXtended COMmunication) 프로토콜을 내부적으로 사용한다.

---

## 참고 자료
- [MySQL 8.4 Reference Manual - Replication](https://dev.mysql.com/doc/en/replication.html)
- [MySQL 8.4 Reference Manual - Semisynchronous Replication](https://dev.mysql.com/doc/refman/8.4/en/replication-semisync.html)
- [MySQL 8.0 Reference Manual - Replication with Global Transaction Identifiers](https://dev.mysql.com/doc/refman/8.0/en/replication-gtids.html)
- [MySQL 8.0 Reference Manual - Group Replication](https://dev.mysql.com/doc/refman/8.0/en/group-replication.html)
- [MySQL 8.0 Reference Manual - InnoDB Cluster](https://dev.mysql.com/doc/refman/8.0/en/mysql-innodb-cluster-introduction.html)
- [Spring AbstractRoutingDataSource Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/lookup/AbstractRoutingDataSource.html)
- [Read-Write and Read-Only Transaction Routing with Spring - Vlad Mihalcea](https://vladmihalcea.com/read-write-read-only-transaction-routing-spring/)
- [MySQL High Availability - ProxySQL Blog](https://proxysql.com/blog/mysql-high-availability/)
- [What to Look for if Your MySQL Replication is Lagging - Severalnines](https://severalnines.com/blog/what-look-if-your-mysql-replication-lagging/)
