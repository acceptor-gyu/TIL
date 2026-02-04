# Spring Data JPA 저장 방식 - save(), saveAll(), 배치 등

## 개요
Spring Data JPA에서 데이터를 저장하는 다양한 방법과 각 방식의 성능 특성, 주의사항을 정리합니다.

## 상세 내용

### save() 메서드의 동작 원리

#### save() 내부 구현

Spring Data JPA의 `save()` 메서드는 단순히 INSERT만 하는 것이 아닙니다.

```java
@Transactional
public <S extends T> S save(S entity) {
    if (entityInformation.isNew(entity)) {
        em.persist(entity);  // INSERT
        return entity;
    } else {
        return em.merge(entity);  // SELECT + INSERT/UPDATE
    }
}
```

#### isNew() 판별 로직

Entity가 새로운지 판별하는 기준:

```java
// 1. @Id가 null인 경우 → 새로운 Entity
@Entity
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;  // null이면 새로운 Entity
}

// 2. @Version이 있는 경우 → version이 null이면 새로운 Entity
@Entity
public class User {
    @Id
    private String email;  // 직접 할당

    @Version
    private Long version;  // null이면 새로운 Entity
}

// 3. Persistable 인터페이스 구현
@Entity
public class User implements Persistable<String> {
    @Id
    private String email;

    @Transient
    private boolean isNew = true;

    @Override
    public String getId() {
        return email;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    @PostLoad
    public void markNotNew() {
        this.isNew = false;
    }
}
```

#### save()의 문제점

```java
@Transactional
public void saveProblem() {
    // Case 1: @GeneratedValue(IDENTITY) - 정상 동작
    User user1 = new User("hong@example.com");
    userRepository.save(user1);  // persist() → INSERT

    // Case 2: @Id를 직접 할당 - 문제 발생!
    User user2 = new User();
    user2.setEmail("kim@example.com");  // ID 직접 할당
    userRepository.save(user2);
    // isNew() → false (ID가 있으므로)
    // merge() 호출 → SELECT 쿼리 먼저 실행!
    // SELECT * FROM users WHERE email = 'kim@example.com'
    // 없으면 INSERT, 있으면 UPDATE
}
```

**해결 방법**

```java
// 방법 1: Persistable 인터페이스 구현 (권장)
@Entity
public class User implements Persistable<String> {
    @Id
    private String email;

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}

// 방법 2: @Version 사용
@Entity
public class User {
    @Id
    private String email;

    @Version
    private Long version;  // null이면 새 Entity로 판단
}

// 방법 3: CreatedDate 활용
@Entity
public class User implements Persistable<String> {
    @Id
    private String email;

    @CreatedDate
    private LocalDateTime createdAt;

    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}
```

### saveAll() 메서드

#### 기본 동작

```java
@Transactional
public <S extends T> List<S> saveAll(Iterable<S> entities) {
    List<S> result = new ArrayList<>();
    for (S entity : entities) {
        result.add(save(entity));  // 하나씩 save() 호출
    }
    return result;
}
```

#### saveAll()의 문제점

```java
@Transactional
public void saveAllProblem() {
    List<User> users = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
        users.add(new User("user" + i + "@example.com"));
    }

    userRepository.saveAll(users);
    // 1000개의 INSERT가 하나씩 실행됨
    // INSERT INTO users ...
    // INSERT INTO users ...
    // ... (1000번)
}
```

### Batch Insert로 성능 개선

#### 1. application.yml 설정

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50  # 50개씩 묶어서 실행
        order_inserts: true  # INSERT 순서 정렬 (배치 효율 증가)
        order_updates: true  # UPDATE 순서 정렬
    show-sql: true  # SQL 로깅
```

#### 2. @GeneratedValue 전략 변경

**문제: IDENTITY 전략은 Batch Insert 불가**

```java
@Entity
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // ❌ Batch 안됨!
    private Long id;
}

// IDENTITY 전략은 INSERT 직후 ID를 가져와야 하므로
// 각 INSERT를 즉시 실행해야 함 → Batch 불가
```

**해결: SEQUENCE 또는 TABLE 전략 사용**

```java
// 방법 1: SEQUENCE 전략 (PostgreSQL, Oracle 권장)
@Entity
@SequenceGenerator(
    name = "user_seq_generator",
    sequenceName = "user_seq",
    allocationSize = 50  // 시퀀스를 50개씩 미리 가져옴
)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_seq_generator")
    private Long id;
}

// 방법 2: TABLE 전략 (모든 DB 호환)
@Entity
@TableGenerator(
    name = "user_id_generator",
    table = "id_generator",
    pkColumnValue = "user_id",
    allocationSize = 50
)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "user_id_generator")
    private Long id;
}

// 방법 3: UUID 사용 (애플리케이션에서 생성)
@Entity
public class User {
    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
```

#### 3. Batch Insert 동작 확인

```java
@Transactional
public void batchInsertExample() {
    List<User> users = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
        users.add(new User("user" + i + "@example.com"));
    }

    userRepository.saveAll(users);
    // batch_size = 50 설정 시:
    // Batch 1: INSERT INTO users ... (50개)
    // Batch 2: INSERT INTO users ... (50개)
}
```

**로그 확인**

```properties
# application.properties
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
logging.level.org.hibernate.engine.jdbc.batch.internal.BatchingBatch=DEBUG
```

### JDBC Batch Insert (JdbcTemplate)

JPA를 거치지 않고 JDBC로 직접 Batch Insert하는 방법

```java
@Repository
@RequiredArgsConstructor
public class UserBatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public void batchInsert(List<User> users) {
        String sql = "INSERT INTO users (email, name, created_at) VALUES (?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                User user = users.get(i);
                ps.setString(1, user.getEmail());
                ps.setString(2, user.getName());
                ps.setTimestamp(3, Timestamp.valueOf(user.getCreatedAt()));
            }

            @Override
            public int getBatchSize() {
                return users.size();
            }
        });
    }

    // Java 8+ 람다 스타일
    public void batchInsertLambda(List<User> users) {
        String sql = "INSERT INTO users (email, name, created_at) VALUES (?, ?, ?)";

        List<Object[]> batchArgs = users.stream()
            .map(user -> new Object[]{
                user.getEmail(),
                user.getName(),
                user.getCreatedAt()
            })
            .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
    }
}
```

### EntityManager를 활용한 Batch Insert

```java
@Repository
@RequiredArgsConstructor
public class UserBatchRepository {

    private final EntityManager em;

    @Transactional
    public void batchInsert(List<User> users, int batchSize) {
        for (int i = 0; i < users.size(); i++) {
            em.persist(users.get(i));

            if (i > 0 && i % batchSize == 0) {
                // 배치 단위로 flush & clear
                em.flush();
                em.clear();
            }
        }

        // 나머지 처리
        em.flush();
        em.clear();
    }
}

// 사용
@Service
@Transactional
public class UserService {

    public void bulkInsert() {
        List<User> users = createUsers(10000);
        userBatchRepository.batchInsert(users, 50);
        // 50개씩 묶어서 INSERT 후 영속성 컨텍스트 초기화
    }
}
```

### 성능 비교

#### 벤치마크 예시 (10,000개 INSERT)

| 방식 | 실행 시간 | 특징 |
|------|----------|------|
| **개별 save()** | ~15초 | 10,000번의 개별 INSERT |
| **saveAll() (IDENTITY)** | ~15초 | Batch 적용 안됨 |
| **saveAll() (SEQUENCE) + Batch** | ~2초 | Batch INSERT 적용 |
| **JdbcTemplate Batch** | ~1초 | JPA 오버헤드 없음 |
| **JDBC Prepared Statement** | ~0.8초 | 가장 빠름 |

#### 실측 코드

```java
@SpringBootTest
public class BatchInsertPerformanceTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    public void testBatchInsertPerformance() {
        int count = 10000;

        // 1. 개별 save()
        long start1 = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            userRepository.save(new User("user" + i + "@example.com"));
        }
        long time1 = System.currentTimeMillis() - start1;
        System.out.println("개별 save(): " + time1 + "ms");

        // 2. saveAll()
        long start2 = System.currentTimeMillis();
        List<User> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            users.add(new User("user" + i + "@example.com"));
        }
        userRepository.saveAll(users);
        long time2 = System.currentTimeMillis() - start2;
        System.out.println("saveAll(): " + time2 + "ms");
    }
}
```

### 대용량 데이터 처리 전략

#### 1. Chunk 단위 처리

```java
@Service
@Transactional
public class UserBulkService {

    private static final int CHUNK_SIZE = 1000;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager em;

    public void processBulkInsert(List<User> users) {
        for (int i = 0; i < users.size(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, users.size());
            List<User> chunk = users.subList(i, end);

            userRepository.saveAll(chunk);

            // 영속성 컨텍스트 초기화 (메모리 관리)
            em.flush();
            em.clear();
        }
    }
}
```

#### 2. Spring Batch 활용

```java
@Configuration
@EnableBatchProcessing
public class UserBatchConfig {

    @Bean
    public Step insertStep(StepBuilderFactory stepBuilderFactory,
                          ItemReader<User> reader,
                          ItemWriter<User> writer) {
        return stepBuilderFactory.get("insertStep")
            .<User, User>chunk(1000)  // 1000개씩 처리
            .reader(reader)
            .writer(writer)
            .build();
    }

    @Bean
    public JpaItemWriter<User> writer(EntityManagerFactory emf) {
        JpaItemWriter<User> writer = new JpaItemWriter<>();
        writer.setEntityManagerFactory(emf);
        return writer;
    }
}
```

#### 3. 병렬 처리

```java
@Service
public class ParallelBatchService {

    @Autowired
    private UserRepository userRepository;

    public void parallelBatchInsert(List<User> users) {
        int processors = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(processors);

        int chunkSize = users.size() / processors;

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < processors; i++) {
            int start = i * chunkSize;
            int end = (i == processors - 1) ? users.size() : (i + 1) * chunkSize;
            List<User> chunk = users.subList(start, end);

            CompletableFuture<Void> future = CompletableFuture.runAsync(
                () -> saveChunk(chunk),
                executor
            );
            futures.add(future);
        }

        // 모든 작업 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();
    }

    @Transactional
    public void saveChunk(List<User> chunk) {
        userRepository.saveAll(chunk);
    }
}
```

### saveAndFlush()

```java
@Transactional
public void saveAndFlushExample() {
    User user = new User("hong@example.com");

    // save(): 영속성 컨텍스트에만 저장
    userRepository.save(user);
    // 아직 DB에 INSERT 안됨

    // saveAndFlush(): 즉시 DB에 반영
    userRepository.saveAndFlush(user);
    // 즉시 INSERT 실행 + FLUSH

    // 사용 케이스: DB 제약조건 검증이 필요한 경우
    try {
        userRepository.saveAndFlush(new User("duplicate@example.com"));
    } catch (DataIntegrityViolationException e) {
        // UNIQUE 제약 위반을 즉시 감지
    }
}
```

### 실무 팁

#### 1. 대량 INSERT 시 인덱스 비활성화

```sql
-- MySQL
ALTER TABLE users DISABLE KEYS;
-- 대량 INSERT
ALTER TABLE users ENABLE KEYS;

-- PostgreSQL
DROP INDEX idx_users_email;
-- 대량 INSERT
CREATE INDEX idx_users_email ON users(email);
```

#### 2. 트랜잭션 크기 조절

```java
@Service
public class UserBulkService {

    @Autowired
    private PlatformTransactionManager transactionManager;

    public void bulkInsertWithTransactionControl(List<User> users) {
        int chunkSize = 1000;

        for (int i = 0; i < users.size(); i += chunkSize) {
            // 작은 트랜잭션으로 분할
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionTemplate.execute(status -> {
                int end = Math.min(i + chunkSize, users.size());
                List<User> chunk = users.subList(i, end);
                userRepository.saveAll(chunk);
                return null;
            });
        }
    }
}
```

#### 3. 데이터베이스별 최적화

**MySQL**
```properties
# rewriteBatchedStatements: 배치를 하나의 SQL로 합침
spring.datasource.url=jdbc:mysql://localhost:3306/mydb?rewriteBatchedStatements=true
```

**PostgreSQL**
```java
// COPY 명령 활용 (가장 빠름)
@Repository
public class PostgresBatchRepository {

    @Autowired
    private DataSource dataSource;

    public void copyInsert(List<User> users) throws SQLException {
        Connection conn = dataSource.getConnection();
        CopyManager copyManager = new CopyManager((BaseConnection) conn);

        String sql = "COPY users (email, name) FROM STDIN WITH (FORMAT CSV)";
        StringReader reader = new StringReader(
            users.stream()
                .map(u -> u.getEmail() + "," + u.getName())
                .collect(Collectors.joining("\n"))
        );

        copyManager.copyIn(sql, reader);
    }
}
```

#### 4. 메모리 관리

```java
@Service
@Transactional
public class MemoryEfficientBatchService {

    @Autowired
    private EntityManager em;

    public void processLargeFile(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

        List<User> batch = new ArrayList<>();
        String line;
        int count = 0;

        while ((line = reader.readLine()) != null) {
            User user = parseUser(line);
            batch.add(user);

            if (batch.size() >= 100) {
                // 100개씩 처리
                batch.forEach(em::persist);
                em.flush();
                em.clear();  // 메모리 해제
                batch.clear();
                count += 100;

                if (count % 10000 == 0) {
                    log.info("Processed {} records", count);
                }
            }
        }

        // 나머지 처리
        if (!batch.isEmpty()) {
            batch.forEach(em::persist);
            em.flush();
            em.clear();
        }
    }
}
```

## 핵심 정리

### save() 메서드
- **isNew() 판별**: ID가 null이면 persist(), 있으면 merge()
- **merge()의 문제**: 항상 SELECT 먼저 실행 (성능 저하)
- **해결책**: Persistable 구현, @Version 사용, @CreatedDate 활용

### Batch Insert
- **설정**: hibernate.jdbc.batch_size, order_inserts=true
- **IDENTITY 전략**: Batch 불가 → SEQUENCE 또는 TABLE 전략 사용
- **대안**: JdbcTemplate batchUpdate() 또는 EntityManager flush/clear

### 성능 최적화
- **Chunk 단위 처리**: 1000~5000개씩 처리 후 flush/clear
- **트랜잭션 분할**: 큰 트랜잭션을 작은 단위로 분할
- **병렬 처리**: ExecutorService로 멀티스레드 활용
- **DB별 최적화**: MySQL rewriteBatchedStatements, PostgreSQL COPY

### 대용량 처리
- **Spring Batch**: Chunk 기반 처리 프레임워크
- **메모리 관리**: flush/clear로 영속성 컨텍스트 초기화
- **인덱스 제어**: INSERT 전 비활성화, 후 재생성
- **스트림 처리**: 파일 전체를 메모리에 올리지 않고 스트리밍

### 방식별 선택 기준
- **소량 데이터 (< 100)**: save() 또는 saveAll()
- **중간 규모 (100 ~ 10,000)**: saveAll() + Batch 설정
- **대용량 (> 10,000)**: JdbcTemplate Batch 또는 Spring Batch
- **초대용량 (> 1,000,000)**: DB 네이티브 LOAD DATA, COPY 명령

## 참고 자료
- [Spring Data JPA - Batch Insert](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#jpa.entity-persistence)
- [Hibernate Batch Processing](https://docs.jboss.org/hibernate/orm/6.0/userguide/html_single/Hibernate_User_Guide.html#batch)
- [Spring Batch 공식 문서](https://docs.spring.io/spring-batch/docs/current/reference/html/)
- [Vlad Mihalcea - High-Performance Java Persistence](https://vladmihalcea.com/tutorials/hibernate/)
