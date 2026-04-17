# MySQL 2대 구성 시 Read Replica 데이터 정합성

## 개요
MySQL 인스턴스 2대를 사용하여 하나는 Write(Primary), 하나는 Read Only(Replica)로 운영할 때, 두 인스턴스 간의 데이터 정합성을 보장하는 방법을 정리한다.

## 상세 내용

### 1. MySQL Replication 기본 구조

**Primary(Master) - Replica(Slave) 아키텍처**
- Primary: 모든 쓰기 작업(INSERT, UPDATE, DELETE)을 처리
- Replica: Primary의 데이터를 복제하여 읽기 작업만 처리

**Binary Log 기반 복제 메커니즘**
1. Primary에서 데이터 변경 작업이 발생
2. 변경 사항이 Binary Log에 기록
3. Replica의 I/O Thread가 Primary의 Binary Log를 읽어옴
4. Replica의 Relay Log에 변경 사항 저장
5. Replica의 SQL Thread가 Relay Log를 읽어 변경 사항을 재생(Replay)

**복제 흐름**
```
[Primary]
 ├─ Binary Log (binlog)
 └─ Dump Thread ─┐
                 │ Network
[Replica]        │
 ├─ I/O Thread ──┘
 ├─ Relay Log
 └─ SQL Thread (Apply)
```

이 구조에서 Primary와 Replica 간의 시간차(Lag)가 발생할 수 있으며, 이것이 데이터 정합성 문제의 주요 원인이다.

### 2. Replication 방식 비교

**비동기 복제 (Asynchronous Replication)** - MySQL 기본 방식
- Primary는 트랜잭션을 커밋한 후 Replica의 복제 완료를 기다리지 않음
- **장점**: 높은 성능, Primary에 부하가 적음
- **단점**: Replication Lag 발생 가능, Primary 장애 시 데이터 손실 위험
- **사용 시나리오**: 성능이 중요하고 약간의 데이터 불일치를 허용할 수 있는 경우

**반동기 복제 (Semi-Synchronous Replication)**
- Primary는 트랜잭션을 커밋한 후, 최소 1개 이상의 Replica가 Relay Log에 기록할 때까지 대기
- **장점**: 데이터 손실 위험 감소, 일관성 향상
- **단점**: 약간의 성능 오버헤드 (네트워크 RTT 만큼 대기)
- **설정 방법**:
```sql
INSTALL PLUGIN rpl_semi_sync_master SONAME 'semisync_master.so';
INSTALL PLUGIN rpl_semi_sync_slave SONAME 'semisync_slave.so';
SET GLOBAL rpl_semi_sync_master_enabled = 1;
SET GLOBAL rpl_semi_sync_slave_enabled = 1;
SET GLOBAL rpl_semi_sync_master_timeout = 1000; -- 1초
```

**그룹 복제 (Group Replication / InnoDB Cluster)**
- 여러 MySQL 인스턴스가 그룹을 형성하여 동기화
- Paxos 기반의 분산 합의 프로토콜 사용
- **장점**: 자동 Failover, 높은 가용성, 강한 일관성
- **단점**: 복잡한 설정, 높은 리소스 요구사항
- **사용 시나리오**: 미션 크리티컬한 서비스, 자동 Failover가 필요한 경우

### 3. Binary Log 포맷

**Statement-Based Replication (SBR)** - `binlog_format = STATEMENT`
- SQL 쿼리문 자체를 Binary Log에 기록
- **장점**: Binary Log 크기가 작음
- **단점**:
  - 비결정적 함수(NOW(), UUID(), RAND() 등) 사용 시 Primary와 Replica 간 데이터 불일치 발생 가능
  - 트리거, 저장 프로시저 등에서 예측 불가능한 결과
- **정합성 위험**: 높음 (비결정적 함수 사용 시)

**Row-Based Replication (RBR)** - `binlog_format = ROW` (MySQL 8.0 기본값)
- 실제 변경된 행(Row) 데이터를 Binary Log에 기록
- **장점**:
  - 모든 변경 사항을 정확하게 복제
  - 비결정적 함수 사용 시에도 안전
  - 트리거, 저장 프로시저의 부작용도 정확하게 복제
- **단점**: Binary Log 크기가 클 수 있음 (대량 UPDATE 시)
- **정합성 위험**: 낮음 (권장)

**Mixed-Based Replication (MBR)** - `binlog_format = MIXED`
- 상황에 따라 STATEMENT와 ROW를 자동 선택
- 기본적으로 STATEMENT 사용, 비결정적 쿼리는 ROW로 기록
- **장점**: 두 방식의 장점을 결합
- **단점**: 예측하기 어려운 동작

**권장 설정**:
```sql
SET GLOBAL binlog_format = 'ROW';
```
데이터 정합성이 중요한 경우 ROW 포맷 사용을 강력히 권장

### 4. Replication Lag과 정합성 문제

**Replication Lag이 발생하는 원인**
1. **네트워크 지연**: Primary와 Replica 간의 물리적 거리 또는 네트워크 대역폭 부족
2. **Replica의 낮은 성능**: CPU, 메모리, 디스크 I/O 부족
3. **대량의 쓰기 작업**: Primary에서 발생한 대량의 트랜잭션을 Replica가 따라가지 못함
4. **단일 스레드 복제**: MySQL 5.6 이전 버전에서 SQL Thread가 단일 스레드로 동작 (MySQL 5.7+는 병렬 복제 지원)
5. **Long Running Transaction**: 큰 트랜잭션이 Replica에서 재생되는 동안 Lag 증가

**Lag 확인 방법**:
```sql
-- Replica에서 실행
SHOW SLAVE STATUS\G
-- Seconds_Behind_Master: Lag 시간 (초 단위)
-- 0이면 완전히 동기화됨
```

**Read-after-Write Consistency 문제**

사용자가 데이터를 쓴(Write) 직후에 읽기(Read) 요청을 Replica로 보낼 때, Replication Lag으로 인해 자신이 방금 쓴 데이터를 볼 수 없는 문제

**시나리오 예시**:
```
1. 사용자가 프로필을 업데이트 (Primary에 쓰기)
2. 업데이트 완료 메시지 표시
3. 프로필 페이지로 리다이렉트 (Replica에서 읽기)
4. Replication Lag으로 인해 이전 프로필 정보가 보임 → 사용자 혼란
```

**Stale Read 시나리오와 대응 전략**

**Stale Read**: Replica에서 오래된(Stale) 데이터를 읽는 현상

**대응 전략**:
1. **Critical Read는 Primary로 라우팅**: 사용자 본인의 데이터는 Primary에서 읽기
2. **Session Consistency**: 같은 세션 내에서는 Primary에서 읽기
3. **GTID 기반 대기**: 특정 트랜잭션이 Replica에 복제될 때까지 대기
4. **Cache 활용**: Write 후 일정 시간 동안 Primary에서 읽거나 캐시 사용
5. **Lag Threshold 설정**: Lag이 임계값을 초과하면 Replica 제외

### 5. 데이터 정합성 보장 전략

**GTID(Global Transaction ID) 기반 복제**

GTID는 전역적으로 고유한 트랜잭션 식별자로, 각 트랜잭션에 부여됨

**GTID 포맷**: `source_id:transaction_id`
- 예: `3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5`

**GTID 활성화**:
```sql
-- my.cnf 설정
gtid_mode = ON
enforce_gtid_consistency = ON
```

**GTID의 장점**:
- 트랜잭션 추적이 쉬움
- Failover 시 Replica 승격이 간편
- 복제 일관성 보장

**`wait_for_executed_gtid_set()` 활용**

특정 GTID가 Replica에 복제될 때까지 대기하는 함수

```sql
-- Primary에서 쓰기 후 GTID 획득
SELECT @@GLOBAL.gtid_executed;
-- 반환: '3E11FA47-71CA-11E1-9E33-C80AA9429562:1-10'

-- Replica에서 해당 GTID가 적용될 때까지 대기
SELECT WAIT_FOR_EXECUTED_GTID_SET('3E11FA47-71CA-11E1-9E33-C80AA9429562:1-10', 1);
-- 1초 내에 복제되지 않으면 타임아웃
-- 반환: 0 (성공), 1 (타임아웃)
```

**애플리케이션 활용 예시**:
```java
// Primary에 쓰기
primaryDataSource.execute("INSERT INTO users ...");
String gtid = primaryDataSource.query("SELECT @@GLOBAL.gtid_executed");

// Replica에서 읽기 전 GTID 대기
replicaDataSource.execute("SELECT WAIT_FOR_EXECUTED_GTID_SET('" + gtid + "', 1)");
List<User> users = replicaDataSource.query("SELECT * FROM users WHERE ...");
```

**Semi-Sync Replication으로 최소 1개 Replica 동기화 보장**

Semi-Sync를 사용하면 Primary는 최소 1개의 Replica가 Relay Log에 기록할 때까지 대기하므로, Primary 장애 시 데이터 손실을 방지할 수 있음

```sql
-- Primary 설정
SET GLOBAL rpl_semi_sync_master_wait_for_slave_count = 1;
```

**읽기 요청의 Critical/Non-Critical 분류에 따른 라우팅**

| 분류 | 예시 | 라우팅 대상 |
|------|------|-------------|
| **Critical Read** | 사용자 본인의 프로필, 주문 내역, 결제 정보 | Primary |
| **Critical Read** | Write 직후의 Read (Read-after-Write) | Primary 또는 GTID 대기 후 Replica |
| **Non-Critical Read** | 상품 목록, 게시판 목록, 통계 데이터 | Replica |
| **Non-Critical Read** | 검색 결과, 추천 상품 | Replica |

이 전략을 통해 정합성과 성능 사이의 균형을 맞출 수 있음

### 6. 애플리케이션 레벨 대응

**Spring의 `@Transactional(readOnly = true)` 활용한 DataSource 라우팅**

Spring에서는 `@Transactional(readOnly = true)`를 사용하여 읽기 전용 트랜잭션임을 명시하고, 이를 기반으로 Replica로 라우팅할 수 있음

**AbstractRoutingDataSource를 통한 Read/Write 분기**

```java
public class ReplicationRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        // 현재 트랜잭션이 readOnly인지 확인
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return isReadOnly ? "replica" : "primary";
    }
}
```

**DataSource 설정**:
```java
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.primary")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.replica")
    public DataSource replicaDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public DataSource routingDataSource(
            @Qualifier("primaryDataSource") DataSource primary,
            @Qualifier("replicaDataSource") DataSource replica) {

        ReplicationRoutingDataSource routingDataSource = new ReplicationRoutingDataSource();

        Map<Object, Object> dataSourceMap = new HashMap<>();
        dataSourceMap.put("primary", primary);
        dataSourceMap.put("replica", replica);

        routingDataSource.setTargetDataSources(dataSourceMap);
        routingDataSource.setDefaultTargetDataSource(primary);

        return routingDataSource;
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("routingDataSource") DataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
```

**서비스 레이어에서 사용**:
```java
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    // Primary로 라우팅
    @Transactional
    public void updateUser(User user) {
        userRepository.save(user);
    }

    // Replica로 라우팅
    @Transactional(readOnly = true)
    public List<User> findAllUsers() {
        return userRepository.findAll();
    }

    // Critical Read는 명시적으로 Primary 사용
    @Transactional(readOnly = false)  // Primary로 강제
    public User findMyProfile(Long userId) {
        return userRepository.findById(userId).orElseThrow();
    }
}
```

**정합성이 중요한 읽기는 Primary로 라우팅하는 전략**

1. **사용자 본인 데이터**: `readOnly = false` 설정하여 Primary에서 읽기
2. **Write 직후 Read**: 같은 트랜잭션 내에서 처리하거나 Primary로 라우팅
3. **금융 거래, 결제 정보**: 항상 Primary에서 읽기
4. **일반 목록 조회**: Replica로 라우팅하여 Primary 부하 분산

**고급 전략 - ThreadLocal 기반 명시적 라우팅**:
```java
public class DataSourceContextHolder {
    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();

    public static void setDataSourceType(String type) {
        contextHolder.set(type);
    }

    public static String getDataSourceType() {
        return contextHolder.get();
    }

    public static void clearDataSourceType() {
        contextHolder.remove();
    }
}

// 사용 예시
DataSourceContextHolder.setDataSourceType("primary");
try {
    // 이 블록 내의 모든 쿼리는 Primary로 라우팅됨
    userService.findMyProfile(userId);
} finally {
    DataSourceContextHolder.clearDataSourceType();
}
```

### 7. 장애 시나리오와 Failover

**Replica 장애 시 대응**

Replica가 다운되면:
1. **즉시 대응**: 애플리케이션이 자동으로 Primary 또는 다른 Replica로 라우팅
2. **헬스 체크**: 주기적으로 Replica의 상태를 확인하여 장애 감지
3. **복구**: Replica 복구 후 자동으로 복제 재개

```sql
-- Replica에서 복제 상태 확인
SHOW SLAVE STATUS\G

-- 복제 재시작
START SLAVE;
```

**Primary 장애 시 Replica 승격 (Promotion)**

Primary가 다운되면 Replica 중 하나를 새로운 Primary로 승격:

**수동 Failover 절차**:
1. **가장 최신 Replica 선택**: `Seconds_Behind_Master`가 0에 가까운 Replica
2. **Replica를 Primary로 승격**:
```sql
-- Replica에서 실행
STOP SLAVE;
RESET SLAVE ALL;

-- Read-Only 해제
SET GLOBAL read_only = OFF;
SET GLOBAL super_read_only = OFF;
```
3. **다른 Replica들이 새로운 Primary를 바라보도록 설정**:
```sql
-- 다른 Replica들에서 실행
STOP SLAVE;
CHANGE MASTER TO
  MASTER_HOST='new-primary-host',
  MASTER_USER='repl_user',
  MASTER_PASSWORD='password',
  MASTER_AUTO_POSITION=1;  -- GTID 사용 시
START SLAVE;
```
4. **애플리케이션 설정 변경**: 새로운 Primary 주소로 업데이트

**자동 Failover 도구**

**1. MHA (Master High Availability)**
- Perl 기반의 MySQL 자동 Failover 도구
- 장점: 빠른 Failover (일반적으로 10~30초)
- 단점: 설정이 복잡, 더 이상 활발히 유지보수되지 않음

**2. Orchestrator**
- Go 기반의 MySQL 복제 토폴로지 관리 도구
- 웹 UI 제공
- 자동 Failover 및 수동 Failover 모두 지원
- GitHub에서 활발히 유지보수됨
- 설정 예시:
```json
{
  "RecoveryPeriodBlockSeconds": 3600,
  "RecoverMasterClusterFilters": ["*"],
  "AutoFailover": true
}
```

**3. ProxySQL**
- MySQL 프록시 서버로, 쿼리 라우팅 및 로드 밸런싱 지원
- Read/Write Split 자동화
- Failover 시 자동으로 트래픽 재라우팅
- 설정 예시:
```sql
-- ProxySQL Admin에서 설정
INSERT INTO mysql_servers (hostgroup_id, hostname, port)
VALUES (0, 'primary-host', 3306);  -- Write Group

INSERT INTO mysql_servers (hostgroup_id, hostname, port)
VALUES (1, 'replica1-host', 3306), -- Read Group
       (1, 'replica2-host', 3306);

INSERT INTO mysql_query_rules (rule_id, active, match_pattern, destination_hostgroup)
VALUES (1, 1, '^SELECT.*FOR UPDATE$', 0),  -- Write
       (2, 1, '^SELECT', 1);                -- Read

LOAD MYSQL SERVERS TO RUNTIME;
SAVE MYSQL SERVERS TO DISK;
```

**4. MySQL InnoDB Cluster (공식 솔루션)**
- MySQL 8.0+에서 제공하는 고가용성 솔루션
- Group Replication + MySQL Router + MySQL Shell
- 자동 Failover 및 자동 복구
- 가장 안정적이지만 설정이 복잡하고 리소스 요구사항이 높음

**Failover 시 고려사항**:
- **데이터 손실 최소화**: Semi-Sync Replication 또는 GTID 사용
- **Split-Brain 방지**: 여러 Replica가 동시에 Primary로 승격되지 않도록 주의
- **애플리케이션 연결 끊김 처리**: Connection Pool의 재연결 로직 구현
- **Failover 테스트**: 정기적으로 Failover 시나리오 테스트

## 핵심 정리

**복제 방식 선택**
- 기본은 **비동기 복제**, 데이터 손실 방지가 중요하면 **Semi-Sync** 사용
- Binary Log 포맷은 **ROW** 사용 (정합성 보장)
- GTID 활성화로 Failover 및 복제 관리 간소화

**Replication Lag 대응**
- Lag은 불가피하게 발생하므로, 애플리케이션 레벨에서 대응 전략 필요
- Critical Read는 Primary로, Non-Critical Read는 Replica로 라우팅
- `WAIT_FOR_EXECUTED_GTID_SET()`으로 특정 트랜잭션 동기화 보장 가능

**Spring 애플리케이션 통합**
- `AbstractRoutingDataSource`로 Read/Write Split 구현
- `@Transactional(readOnly = true)`로 Replica 라우팅
- 정합성이 중요한 읽기는 명시적으로 Primary 사용

**고가용성 확보**
- 자동 Failover 도구 도입 (Orchestrator, ProxySQL, InnoDB Cluster)
- 정기적인 Failover 테스트 수행
- 모니터링으로 Replication Lag 및 장애 감지

**데이터 정합성 vs 성능 트레이드오프**
- 완벽한 정합성은 성능 희생 필요 (Semi-Sync, Primary 읽기)
- 비즈니스 요구사항에 따라 적절한 균형점 찾기
- 대부분의 읽기는 Replica로 처리하되, Critical 읽기만 Primary로 라우팅하는 하이브리드 전략 권장

## 키워드

**`MySQL Replication`**
- MySQL에서 Primary 서버의 데이터를 하나 이상의 Replica 서버로 복제하는 기능. Binary Log를 기반으로 데이터 변경 사항을 전파함.

**`Primary-Replica`**
- Primary는 쓰기 작업을 처리하고, Replica는 Primary의 데이터를 복제하여 읽기 작업을 처리하는 아키텍처. Master-Slave라고도 불림.

**`Binary Log`**
- MySQL에서 데이터 변경 작업(INSERT, UPDATE, DELETE)을 기록하는 로그 파일. Replication의 기반이 되며, Point-in-Time Recovery에도 사용됨.

**`Relay Log`**
- Replica 서버에서 Primary의 Binary Log 내용을 임시로 저장하는 로그 파일. SQL Thread가 이를 읽어 변경 사항을 재생함.

**`Replication Lag`**
- Primary와 Replica 간의 데이터 동기화 시간 차이. 네트워크 지연, Replica 성능 부족 등으로 발생하며, Stale Read의 원인이 됨.

**`Semi-Synchronous Replication`**
- Primary가 트랜잭션을 커밋한 후, 최소 1개 이상의 Replica가 Relay Log에 기록할 때까지 대기하는 복제 방식. 데이터 손실 위험을 줄임.

**`GTID (Global Transaction ID)`**
- 각 트랜잭션에 부여되는 전역 고유 식별자. 복제 관리를 간소화하고 Failover 시 Replica 승격을 쉽게 만듦.

**`Read-after-Write Consistency`**
- 사용자가 데이터를 쓴 직후 읽기 요청 시, 자신이 방금 쓴 데이터를 볼 수 있어야 한다는 일관성 요구사항. Replication Lag으로 인해 위반될 수 있음.

**`AbstractRoutingDataSource`**
- Spring에서 제공하는 DataSource 라우팅 클래스. 런타임에 트랜잭션 속성(readOnly 등)에 따라 Primary 또는 Replica로 동적 라우팅.

**`Failover`**
- Primary 서버 장애 시, Replica를 새로운 Primary로 승격하여 서비스 연속성을 보장하는 프로세스. 자동 또는 수동으로 수행됨.

## 참고 자료
- [MySQL 8.0 Reference Manual - Replication](https://dev.mysql.com/doc/refman/8.0/en/replication.html)
- [MySQL 8.0 Reference Manual - Binary Log](https://dev.mysql.com/doc/refman/8.0/en/binary-log.html)
- [MySQL 8.0 Reference Manual - GTID](https://dev.mysql.com/doc/refman/8.0/en/replication-gtids.html)
- [MySQL 8.0 Reference Manual - Semi-Synchronous Replication](https://dev.mysql.com/doc/refman/8.0/en/replication-semisync.html)
- [MySQL 8.0 Reference Manual - Group Replication](https://dev.mysql.com/doc/refman/8.0/en/group-replication.html)
- [GitHub - Orchestrator](https://github.com/openark/orchestrator)
- [ProxySQL Documentation](https://proxysql.com/documentation/)
- [Spring Framework - AbstractRoutingDataSource](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/lookup/AbstractRoutingDataSource.html)
