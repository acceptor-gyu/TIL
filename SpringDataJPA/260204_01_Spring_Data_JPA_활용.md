# Spring Data JPA 활용

## 개요
Spring Data JPA를 활용한 데이터베이스 접근 및 영속성 관리에 대한 내용을 정리합니다.

## 상세 내용

### JPA와 Spring Data JPA
- **JPA (Java Persistence API)**: 자바 ORM 기술 표준 명세
- **Hibernate**: JPA의 대표적인 구현체
- **Spring Data JPA**: JPA를 한 단계 더 추상화하여 Repository 인터페이스 기반으로 개발 편의성 제공

### 기본 사용법

#### Entity 정의
```java
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(unique = true, nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

#### Repository 정의
```java
public interface UserRepository extends JpaRepository<User, Long> {

    // 메서드 이름 기반 쿼리 생성
    Optional<User> findByEmail(String email);

    List<User> findByStatus(UserStatus status);

    // @Query 어노테이션 활용
    @Query("SELECT u FROM User u WHERE u.createdAt >= :date")
    List<User> findRecentUsers(@Param("date") LocalDateTime date);

    // Native Query
    @Query(value = "SELECT * FROM users WHERE status = :status", nativeQuery = true)
    List<User> findByStatusNative(@Param("status") String status);
}
```

### Entity Lifecycle과 Persistence Context

#### Persistence Context란?

**Persistence Context(영속성 컨텍스트)**는 Entity를 영구 저장하는 환경입니다. JPA는 Persistence Context를 통해 Entity를 관리하며, 이는 애플리케이션과 데이터베이스 사이의 중간 계층 역할을 합니다.

**주요 특징:**
- Entity Manager가 관리하는 Entity 저장소
- 트랜잭션 단위로 생성되고 종료됨
- 1차 캐시, 변경 감지, 지연 로딩 등의 기능 제공
- Spring Data JPA에서는 자동으로 관리됨

```java
@Service
@Transactional
public class UserService {

    @PersistenceContext
    private EntityManager em;

    public void example() {
        // 영속성 컨텍스트에 Entity 저장
        User user = new User("홍길동");
        em.persist(user);  // 영속 상태

        // 1차 캐시에서 조회 (DB 접근 X)
        User found = em.find(User.class, user.getId());

        // 변경 감지 (Dirty Checking) - UPDATE 쿼리 자동 생성
        found.setName("김철수");
        // em.update() 필요 없음!
    }
}
```

#### Entity의 4가지 상태

```
┌─────────────┐
│   비영속     │  new/transient
│ (New)       │  - new로 생성만 한 상태
└──────┬──────┘  - 영속성 컨텍스트와 무관
       │ persist()
       ↓
┌─────────────┐
│    영속      │  managed
│ (Managed)   │  - 영속성 컨텍스트가 관리
└──────┬──────┘  - 1차 캐시, 변경 감지 대상
       │
       ├─ detach() ──→ ┌─────────────┐
       │                │    준영속     │  detached
       │                │ (Detached)  │  - 영속성 컨텍스트에서 분리
       │                └──────┬──────┘  - 더이상 관리되지 않음
       │                       │ merge()
       │                       ↓
       │ remove() ───→ ┌─────────────┐
       │               │    삭제      │  removed
       └───────────────│ (Removed)   │  - 삭제 예정 상태
                       └─────────────┘  - commit 시 DELETE 실행
```

#### 1. 비영속 (New/Transient)

Entity 객체를 생성만 했을 뿐 아직 영속성 컨텍스트에 저장하지 않은 상태

```java
// 비영속 상태
User user = new User("홍길동", "hong@example.com");
// 단순 자바 객체, JPA가 관리하지 않음
```

#### 2. 영속 (Managed)

Entity가 영속성 컨텍스트에 의해 관리되는 상태

```java
@Transactional
public void managedExample() {
    // 1. persist()로 영속 상태 만들기
    User user = new User("홍길동");
    em.persist(user);  // 영속 상태

    // 2. DB에서 조회한 Entity는 자동으로 영속 상태
    User found = em.find(User.class, 1L);  // 영속 상태

    // 3. Repository로 조회한 Entity도 영속 상태
    User fromRepo = userRepository.findById(1L).get();  // 영속 상태

    // 영속 상태의 이점
    // - 1차 캐시: 같은 트랜잭션 내에서 같은 ID 조회 시 캐시 사용
    User cached1 = em.find(User.class, 1L);
    User cached2 = em.find(User.class, 1L);  // DB 조회 없이 캐시에서 반환
    // cached1 == cached2 (동일성 보장)

    // - 변경 감지(Dirty Checking): setter만 호출해도 자동 UPDATE
    found.setName("김철수");
    // 트랜잭션 커밋 시점에 자동으로 UPDATE 쿼리 실행

    // - 쓰기 지연(Write-Behind): INSERT/UPDATE를 모아서 한번에 실행
    User user1 = new User("사용자1");
    User user2 = new User("사용자2");
    em.persist(user1);
    em.persist(user2);
    // 여기까지는 INSERT 쿼리가 실행되지 않음
    // 트랜잭션 커밋 시점에 한번에 실행
}
```

#### 3. 준영속 (Detached)

영속 상태였다가 영속성 컨텍스트에서 분리된 상태

```java
@Transactional
public User detachedExample() {
    User user = em.find(User.class, 1L);  // 영속 상태

    // 준영속 상태로 만드는 방법

    // 1. detach() - 특정 Entity만 분리
    em.detach(user);

    // 2. clear() - 영속성 컨텍스트 전체 초기화
    em.clear();

    // 3. close() - 영속성 컨텍스트 종료
    em.close();

    // 4. 트랜잭션 종료 후 반환
    return user;  // 메서드 종료 시 트랜잭션이 끝나면 준영속 상태
}

// 준영속 상태의 Entity
public void afterTransaction() {
    User user = detachedExample();  // 준영속 상태

    // 변경 감지 작동 안함
    user.setName("새이름");  // DB에 반영 안됨!

    // 다시 영속 상태로 만들려면 merge()
    User merged = em.merge(user);  // 영속 상태로 변환
}
```

**준영속 상태 예시: Controller에서 받은 DTO**

```java
@RestController
public class UserController {

    @PutMapping("/users/{id}")
    @Transactional
    public void updateUser(@PathVariable Long id, @RequestBody UserUpdateDto dto) {
        // dto는 준영속 상태 (영속성 컨텍스트와 무관한 일반 객체)

        // 방법 1: 조회 후 변경 (권장)
        User user = userRepository.findById(id)
            .orElseThrow(() -> new UserNotFoundException());
        user.updateFrom(dto);  // 영속 상태에서 변경 → 자동 UPDATE

        // 방법 2: merge 사용 (비권장)
        User detached = dto.toEntity();  // 준영속 상태
        em.merge(detached);  // 영속 상태로 변환 후 UPDATE
    }
}
```

#### 4. 삭제 (Removed)

Entity를 영속성 컨텍스트와 데이터베이스에서 삭제하는 상태

```java
@Transactional
public void removeExample() {
    User user = em.find(User.class, 1L);  // 영속 상태

    em.remove(user);  // 삭제 상태
    // 트랜잭션 커밋 시점에 DELETE 쿼리 실행
}
```

#### Persistence Context의 주요 기능

##### 1. 1차 캐시 (First Level Cache)

```java
@Transactional
public void firstLevelCacheExample() {
    // 첫 번째 조회: DB에서 가져와서 1차 캐시에 저장
    User user1 = em.find(User.class, 1L);  // SELECT 쿼리 실행

    // 두 번째 조회: 1차 캐시에서 반환
    User user2 = em.find(User.class, 1L);  // SELECT 쿼리 실행 안함!

    // 동일성 보장
    System.out.println(user1 == user2);  // true

    // 같은 트랜잭션 내에서만 유효
}
```

##### 2. 변경 감지 (Dirty Checking)

```java
@Transactional
public void dirtyCheckingExample() {
    User user = em.find(User.class, 1L);  // 영속 상태

    // 스냅샷: 최초 조회 시점의 상태를 영속성 컨텍스트가 보관

    user.setName("새이름");
    user.setEmail("new@example.com");

    // em.update(user); 필요 없음!
    // 트랜잭션 커밋 시점에 스냅샷과 비교하여
    // 변경된 필드만 UPDATE 쿼리 자동 생성

    // UPDATE users SET name='새이름', email='new@example.com' WHERE id=1
}
```

**동적 업데이트 설정**

```java
@Entity
@DynamicUpdate  // 변경된 필드만 UPDATE
public class User {
    // 이 설정이 없으면 모든 필드를 UPDATE
    // 이 설정이 있으면 변경된 필드만 UPDATE
}
```

##### 3. 지연 로딩 (Lazy Loading)

```java
@Entity
public class User {
    @OneToMany(fetch = FetchType.LAZY, mappedBy = "user")
    private List<Order> orders;
}

@Transactional
public void lazyLoadingExample() {
    User user = em.find(User.class, 1L);
    // SELECT user FROM users WHERE id = 1
    // orders는 아직 로딩 안됨

    System.out.println(user.getName());  // OK

    // orders에 접근하는 시점에 쿼리 실행 (프록시 초기화)
    List<Order> orders = user.getOrders();
    // SELECT * FROM orders WHERE user_id = 1

    // 주의: 트랜잭션 밖에서 접근하면 LazyInitializationException 발생!
}

// LazyInitializationException 방지
@Transactional(readOnly = true)
public UserDto getUserWithOrders(Long id) {
    User user = userRepository.findById(id)
        .orElseThrow(() -> new UserNotFoundException());

    // 트랜잭션 내에서 명시적으로 초기화
    user.getOrders().size();  // 강제 초기화

    return new UserDto(user);
}
```

##### 4. 쓰기 지연 (Write-Behind, Transactional Write-Behind)

```java
@Transactional
public void writeBehindExample() {
    User user1 = new User("사용자1");
    User user2 = new User("사용자2");
    User user3 = new User("사용자3");

    em.persist(user1);  // 쿼리 실행 안함, 쓰기 지연 SQL 저장소에 보관
    em.persist(user2);  // 쿼리 실행 안함
    em.persist(user3);  // 쿼리 실행 안함

    // 트랜잭션 커밋 시점에 한번에 실행
    // INSERT INTO users ...
    // INSERT INTO users ...
    // INSERT INTO users ...
}
```

**Batch Insert 설정**

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50  # 50개씩 배치로 묶어서 실행
        order_inserts: true  # INSERT 순서 정렬
        order_updates: true  # UPDATE 순서 정렬
```

#### 플러시 (Flush)

영속성 컨텍스트의 변경 내용을 데이터베이스에 반영하는 작업

```java
@Transactional
public void flushExample() {
    User user = new User("홍길동");
    em.persist(user);

    // 플러시 발생 시점
    // 1. em.flush() 직접 호출
    em.flush();  // 즉시 DB에 반영

    // 2. 트랜잭션 커밋 시 자동 플러시
    // (메서드 종료 시점)

    // 3. JPQL 쿼리 실행 직전 자동 플러시
    List<User> users = em.createQuery("SELECT u FROM User u", User.class)
        .getResultList();  // JPQL 실행 전 자동 플러시
}
```

**플러시 모드 설정**

```java
// AUTO (기본값): 커밋 또는 쿼리 실행 시 플러시
em.setFlushMode(FlushModeType.AUTO);

// COMMIT: 커밋할 때만 플러시 (JPQL 쿼리 실행 시 플러시 안함)
em.setFlushMode(FlushModeType.COMMIT);
```

#### 실무 활용 예시

##### 1. 벌크 연산 주의사항

```java
@Transactional
public void bulkUpdateProblem() {
    User user = em.find(User.class, 1L);
    System.out.println(user.getStatus());  // ACTIVE

    // 벌크 연산: 영속성 컨텍스트를 거치지 않고 DB에 직접 실행
    em.createQuery("UPDATE User u SET u.status = 'INACTIVE' WHERE u.id = 1")
        .executeUpdate();

    // 영속성 컨텍스트는 여전히 ACTIVE로 알고 있음!
    System.out.println(user.getStatus());  // ACTIVE (문제!)

    // 해결 방법 1: clear()로 영속성 컨텍스트 초기화
    em.clear();
    User refreshed = em.find(User.class, 1L);
    System.out.println(refreshed.getStatus());  // INACTIVE

    // 해결 방법 2: refresh()로 특정 Entity 재조회
    em.refresh(user);
    System.out.println(user.getStatus());  // INACTIVE
}
```

##### 2. @Modifying과 clearAutomatically

```java
public interface UserRepository extends JpaRepository<User, Long> {

    @Modifying(clearAutomatically = true)  // 자동으로 영속성 컨텍스트 클리어
    @Query("UPDATE User u SET u.status = :status WHERE u.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") UserStatus status);
}

@Service
@Transactional
public class UserService {

    public void updateUserStatus(Long id) {
        User user = userRepository.findById(id).get();  // 영속 상태

        userRepository.updateStatus(id, UserStatus.INACTIVE);
        // clearAutomatically = true이므로 자동으로 영속성 컨텍스트 클리어

        // 다시 조회하면 DB에서 새로 가져옴
        User updated = userRepository.findById(id).get();
        System.out.println(updated.getStatus());  // INACTIVE
    }
}
```

##### 3. 트랜잭션 범위와 영속성 컨텍스트

```java
@Service
public class UserService {

    @Transactional
    public void transactionScopeExample() {
        // 트랜잭션 시작 → 영속성 컨텍스트 생성

        User user = userRepository.findById(1L).get();  // 영속 상태
        user.setName("새이름");

        // 메서드 종료 → 트랜잭션 커밋 → 영속성 컨텍스트 종료
        // 변경 감지로 UPDATE 쿼리 자동 실행
    }

    // @Transactional 없으면?
    public void noTransactionExample() {
        User user = userRepository.findById(1L).get();
        // 조회 직후 영속성 컨텍스트가 종료됨 (준영속 상태)

        user.setName("새이름");
        // 변경 감지 작동 안함! DB에 반영 안됨!

        // 명시적으로 save() 호출 필요
        userRepository.save(user);  // merge() 실행
    }
}
```

##### 4. 영속성 전이 (Cascade)

```java
@Entity
public class User {

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Order> orders = new ArrayList<>();

    public void addOrder(Order order) {
        orders.add(order);
        order.setUser(this);
    }
}

@Transactional
public void cascadeExample() {
    User user = new User("홍길동");

    Order order1 = new Order(10000);
    Order order2 = new Order(20000);

    user.addOrder(order1);
    user.addOrder(order2);

    em.persist(user);
    // cascade = ALL이므로 order1, order2도 자동으로 persist됨
    // INSERT INTO users ...
    // INSERT INTO orders ...
    // INSERT INTO orders ...

    // orphanRemoval = true이므로 고아 객체 자동 삭제
    user.getOrders().remove(0);  // order1 제거
    // 트랜잭션 커밋 시 DELETE FROM orders WHERE id = ?
}
```

#### 정리: Entity Lifecycle 상태 전이

```java
// 1. 비영속 → 영속
User user = new User("홍길동");  // 비영속
em.persist(user);                 // 영속

// 2. 영속 → 준영속
em.detach(user);                  // 준영속
em.clear();                       // 모든 Entity 준영속
em.close();                       // 영속성 컨텍스트 종료

// 3. 준영속 → 영속
User merged = em.merge(user);     // 영속 (새로운 영속 Entity 반환)

// 4. 영속 → 삭제
em.remove(user);                  // 삭제 (커밋 시 DELETE)

// 5. 조회는 자동으로 영속
User found = em.find(User.class, 1L);        // 영속
User fromRepo = userRepository.findById(1L)   // 영속
    .orElseThrow();
```

### 주요 기능

#### 1. 메서드 이름 기반 쿼리
Spring Data JPA는 메서드 이름을 분석하여 자동으로 쿼리를 생성합니다.

```java
// 단일 조건
findByName(String name)
findByEmail(String email)

// 복합 조건
findByNameAndEmail(String name, String email)
findByStatusOrCreatedAtAfter(UserStatus status, LocalDateTime date)

// 정렬
findByStatusOrderByCreatedAtDesc(UserStatus status)

// 페이징
Page<User> findByStatus(UserStatus status, Pageable pageable)

// 제한
List<User> findTop10ByStatusOrderByCreatedAtDesc(UserStatus status)
```

#### 2. @Query 어노테이션
복잡한 쿼리는 JPQL이나 Native SQL로 작성할 수 있습니다.

```java
// JPQL
@Query("SELECT u FROM User u WHERE u.name LIKE %:keyword% AND u.status = :status")
List<User> searchUsers(@Param("keyword") String keyword, @Param("status") UserStatus status);

// Native Query
@Query(value = """
    SELECT u.* FROM users u
    JOIN orders o ON u.id = o.user_id
    WHERE o.created_at >= :startDate
    GROUP BY u.id
    HAVING COUNT(o.id) > :minOrderCount
    """, nativeQuery = true)
List<User> findActiveCustomers(
    @Param("startDate") LocalDateTime startDate,
    @Param("minOrderCount") int minOrderCount
);

// 수정 쿼리
@Modifying
@Query("UPDATE User u SET u.status = :status WHERE u.id = :id")
int updateUserStatus(@Param("id") Long id, @Param("status") UserStatus status);
```

#### 3. 페이징과 정렬
```java
// Service Layer
public Page<User> getUsers(int page, int size, String sortBy) {
    Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).descending());
    return userRepository.findAll(pageable);
}

// 복잡한 정렬
Sort sort = Sort.by(
    Sort.Order.desc("createdAt"),
    Sort.Order.asc("name")
);
Pageable pageable = PageRequest.of(0, 10, sort);
```

#### 4. Specifications를 활용한 동적 쿼리
```java
public class UserSpecifications {

    public static Specification<User> hasStatus(UserStatus status) {
        return (root, query, cb) ->
            status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<User> createdAfter(LocalDateTime date) {
        return (root, query, cb) ->
            date == null ? null : cb.greaterThanOrEqualTo(root.get("createdAt"), date);
    }

    public static Specification<User> nameLike(String keyword) {
        return (root, query, cb) ->
            keyword == null ? null : cb.like(root.get("name"), "%" + keyword + "%");
    }
}

// Repository
public interface UserRepository extends JpaRepository<User, Long>,
                                        JpaSpecificationExecutor<User> {
}

// Service
public List<User> searchUsers(UserSearchCriteria criteria) {
    Specification<User> spec = Specification
        .where(UserSpecifications.hasStatus(criteria.getStatus()))
        .and(UserSpecifications.createdAfter(criteria.getStartDate()))
        .and(UserSpecifications.nameLike(criteria.getKeyword()));

    return userRepository.findAll(spec);
}
```

#### 5. QueryDSL 활용
```java
// Q클래스 자동 생성 (빌드 시)
QUser user = QUser.user;

// QueryDSL Predicate
public class UserPredicates {

    public static BooleanExpression statusEquals(UserStatus status) {
        return status == null ? null : user.status.eq(status);
    }

    public static BooleanExpression nameContains(String keyword) {
        return keyword == null ? null : user.name.contains(keyword);
    }
}

// Repository
public interface UserRepository extends JpaRepository<User, Long>,
                                        QuerydslPredicateExecutor<User> {
}

// Service
public List<User> searchUsers(String keyword, UserStatus status) {
    BooleanExpression predicate = UserPredicates.statusEquals(status)
        .and(UserPredicates.nameContains(keyword));

    return (List<User>) userRepository.findAll(predicate);
}
```

### 연관관계 매핑

#### 1. @OneToMany / @ManyToOne
```java
@Entity
public class User {
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Order> orders = new ArrayList<>();
}

@Entity
public class Order {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
}
```

#### 2. @ManyToMany
```java
@Entity
public class Student {
    @ManyToMany
    @JoinTable(
        name = "student_course",
        joinColumns = @JoinColumn(name = "student_id"),
        inverseJoinColumns = @JoinColumn(name = "course_id")
    )
    private Set<Course> courses = new HashSet<>();
}

@Entity
public class Course {
    @ManyToMany(mappedBy = "courses")
    private Set<Student> students = new HashSet<>();
}
```

### 성능 최적화

#### 1. N+1 문제 이해하기

**N+1 문제란?**

1개의 쿼리로 N개의 Entity를 조회한 후, 각 Entity의 연관 데이터를 조회하기 위해 N번의 추가 쿼리가 발생하는 문제입니다.

```java
@Entity
public class User {
    @Id
    private Long id;
    private String name;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<Order> orders = new ArrayList<>();
}

@Entity
public class Order {
    @Id
    private Long id;
    private int amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
}
```

**문제 발생 시나리오**

```java
@Service
@Transactional(readOnly = true)
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public List<UserDto> getAllUsersWithOrders() {
        // 1. User 전체 조회 (1번의 쿼리)
        List<User> users = userRepository.findAll();
        // SELECT * FROM users

        List<UserDto> result = new ArrayList<>();

        // 2. 각 User의 orders 접근 시 추가 쿼리 발생 (N번의 쿼리)
        for (User user : users) {
            int orderCount = user.getOrders().size();  // 여기서 추가 쿼리!
            // SELECT * FROM orders WHERE user_id = 1
            // SELECT * FROM orders WHERE user_id = 2
            // SELECT * FROM orders WHERE user_id = 3
            // ... (N번 반복)

            result.add(new UserDto(user.getName(), orderCount));
        }

        // 총 1 + N 번의 쿼리 실행!
        return result;
    }
}
```

**실제 로그 예시**

```sql
-- 1번째 쿼리: User 조회
Hibernate: select user0_.id, user0_.name from users user0_

-- 2번째 쿼리: user_id=1의 orders 조회
Hibernate: select orders0_.user_id, orders0_.id, orders0_.amount
           from orders orders0_ where orders0_.user_id=1

-- 3번째 쿼리: user_id=2의 orders 조회
Hibernate: select orders0_.user_id, orders0_.id, orders0_.amount
           from orders orders0_ where orders0_.user_id=2

-- ... (사용자 수만큼 반복)
```

**성능 영향**

- 100명의 사용자 조회 시 → 101번의 쿼리 (1 + 100)
- 1000명의 사용자 조회 시 → 1001번의 쿼리 (1 + 1000)
- 네트워크 라운드트립 증가 → 심각한 성능 저하

#### 2. Fetch 전략 이해하기

**FetchType.LAZY vs FetchType.EAGER**

```java
// LAZY (지연 로딩) - 기본값 (권장)
@OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
private List<Order> orders;
// 연관 Entity를 실제로 사용할 때 쿼리 실행

// EAGER (즉시 로딩) - 사용 지양
@OneToMany(mappedBy = "user", fetch = FetchType.EAGER)
private List<Order> orders;
// 부모 Entity 조회 시 무조건 함께 조회
```

**기본 Fetch 전략**

| 연관관계 | 기본 전략 | 이유 |
|---------|----------|------|
| @ManyToOne | EAGER | 단일 객체 조회 |
| @OneToOne | EAGER | 단일 객체 조회 |
| @OneToMany | LAZY | 컬렉션 조회 |
| @ManyToMany | LAZY | 컬렉션 조회 |

**EAGER의 문제점**

```java
@Entity
public class User {
    @OneToMany(fetch = FetchType.EAGER)  // ❌ 권장하지 않음
    private List<Order> orders;
}

// 문제 1: 불필요한 데이터까지 항상 조회
User user = userRepository.findById(1L).get();
// orders가 필요 없어도 항상 JOIN하여 조회

// 문제 2: 여러 컬렉션 EAGER 시 CartesianProduct 발생
@Entity
public class User {
    @OneToMany(fetch = FetchType.EAGER)
    private List<Order> orders;

    @OneToMany(fetch = FetchType.EAGER)
    private List<Post> posts;
    // 데이터 뻥튀기! orders(100) * posts(50) = 5000행 조회
}

// 문제 3: 성능 예측 불가
// findAll() 호출 시 모든 연관 Entity까지 조회
List<User> users = userRepository.findAll();
// SELECT * FROM users
// JOIN orders ... (사용자마다 JOIN)
```

**권장 사항**

```java
// ✅ 모든 연관관계를 LAZY로 설정
@Entity
public class User {
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<Order> orders;
}

// @ManyToOne도 LAZY로 변경 권장
@Entity
public class Order {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
}
```

#### 3. N+1 문제 해결 방법

##### 해결 1: Fetch Join (JPQL)

**가장 기본적이고 효과적인 방법**

```java
public interface UserRepository extends JpaRepository<User, Long> {

    // 단일 컬렉션 Fetch Join
    @Query("SELECT u FROM User u JOIN FETCH u.orders WHERE u.id = :id")
    Optional<User> findByIdWithOrders(@Param("id") Long id);

    // 전체 조회 시 Fetch Join
    @Query("SELECT DISTINCT u FROM User u JOIN FETCH u.orders")
    List<User> findAllWithOrders();

    // 조건과 함께 사용
    @Query("SELECT u FROM User u JOIN FETCH u.orders o WHERE o.status = :status")
    List<User> findUsersWithActiveOrders(@Param("status") OrderStatus status);

    // LEFT JOIN FETCH (연관 데이터가 없어도 부모 조회)
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.orders")
    List<User> findAllWithOrdersLeftJoin();
}
```

**실행되는 SQL**

```sql
SELECT u.*, o.*
FROM users u
INNER JOIN orders o ON u.id = o.user_id
WHERE u.id = ?
-- 단 1번의 쿼리로 User와 Order 모두 조회!
```

**주의사항: 여러 컬렉션 Fetch Join 불가**

```java
// ❌ MultipleBagFetchException 발생
@Query("SELECT u FROM User u " +
       "JOIN FETCH u.orders " +
       "JOIN FETCH u.posts")
List<User> findAllWithOrdersAndPosts();

// ✅ 해결: 하나는 Fetch Join, 나머지는 @BatchSize
@Entity
public class User {
    @OneToMany(mappedBy = "user")
    private List<Order> orders;

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "user")
    private List<Post> posts;
}

@Query("SELECT DISTINCT u FROM User u JOIN FETCH u.orders")
List<User> findAllWithOrders();
// posts는 @BatchSize로 최적화
```

**DISTINCT 키워드**

```java
// DISTINCT 없이 조회 시
@Query("SELECT u FROM User u JOIN FETCH u.orders")
List<User> findAllWithOrders();
// 1:N 관계로 인해 User가 중복되어 반환됨
// User1 - Order1
// User1 - Order2
// User1 - Order3  → User1이 3번 나옴!

// DISTINCT 추가
@Query("SELECT DISTINCT u FROM User u JOIN FETCH u.orders")
List<User> findAllWithOrders();
// 애플리케이션 레벨에서 중복 제거
// User1 (orders: [Order1, Order2, Order3])
```

##### 해결 2: @EntityGraph

**간편한 Fetch Join 대안**

```java
public interface UserRepository extends JpaRepository<User, Long> {

    // 기본 사용
    @EntityGraph(attributePaths = {"orders"})
    List<User> findAll();

    // 특정 메서드에 적용
    @EntityGraph(attributePaths = {"orders"})
    Optional<User> findById(Long id);

    // 여러 연관관계 동시 로딩
    @EntityGraph(attributePaths = {"orders", "profile"})
    List<User> findAllWithOrdersAndProfile();

    // 중첩된 연관관계
    @EntityGraph(attributePaths = {"orders", "orders.product"})
    List<User> findAllWithOrdersAndProducts();

    // 쿼리 메서드와 함께 사용
    @EntityGraph(attributePaths = {"orders"})
    List<User> findByStatus(UserStatus status);
}
```

**@NamedEntityGraph 활용**

```java
@Entity
@NamedEntityGraph(
    name = "User.withOrders",
    attributeNodes = @NamedAttributeNode("orders")
)
@NamedEntityGraph(
    name = "User.withOrdersAndProfile",
    attributeNodes = {
        @NamedAttributeNode("orders"),
        @NamedAttributeNode("profile")
    }
)
public class User {
    @OneToMany(mappedBy = "user")
    private List<Order> orders;

    @OneToOne
    private UserProfile profile;
}

// Repository에서 사용
public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph("User.withOrders")
    List<User> findAll();

    @EntityGraph("User.withOrdersAndProfile")
    Optional<User> findById(Long id);
}
```

**EntityGraphType 설정**

```java
// FETCH (기본값): attributePaths만 EAGER, 나머지는 LAZY
@EntityGraph(attributePaths = {"orders"}, type = EntityGraphType.FETCH)
List<User> findAll();

// LOAD: attributePaths는 EAGER, 나머지는 Entity 설정 따름
@EntityGraph(attributePaths = {"orders"}, type = EntityGraphType.LOAD)
List<User> findAll();
```

##### 해결 3: @BatchSize

**IN 절을 사용한 배치 로딩**

```java
@Entity
public class User {

    @BatchSize(size = 100)  // 100개씩 묶어서 조회
    @OneToMany(mappedBy = "user")
    private List<Order> orders;
}

// 또는 전역 설정
// application.yml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 100
```

**동작 원리**

```java
// 100명의 사용자 조회
List<User> users = userRepository.findAll();
// SELECT * FROM users

// orders 접근 시
for (User user : users) {
    user.getOrders().size();
}

// @BatchSize 없을 때: 100번의 쿼리
// SELECT * FROM orders WHERE user_id = 1
// SELECT * FROM orders WHERE user_id = 2
// ... (100번)

// @BatchSize(100) 있을 때: 1번의 쿼리
// SELECT * FROM orders WHERE user_id IN (1, 2, 3, ..., 100)
```

**BatchSize 최적값**

```java
// 너무 작으면: 쿼리가 여러 번 실행
@BatchSize(size = 10)  // 100명 조회 시 10번의 IN 쿼리

// 적절한 크기: 100 ~ 1000
@BatchSize(size = 100)  // 권장

// 너무 크면: IN 절이 너무 길어져 DB 부담
@BatchSize(size = 10000)  // 비권장
```

##### 해결 4: @Fetch(FetchMode.SUBSELECT)

**서브쿼리로 한번에 조회**

```java
@Entity
public class User {

    @Fetch(FetchMode.SUBSELECT)
    @OneToMany(mappedBy = "user")
    private List<Order> orders;
}

// 실행되는 SQL
// 1. User 조회
SELECT * FROM users

// 2. Order 한번에 조회
SELECT * FROM orders
WHERE user_id IN (SELECT id FROM users)
```

**주의사항**

- JPQL의 IN 절 서브쿼리로 변환
- 대량 데이터 시 서브쿼리 성능 저하 가능
- BatchSize가 더 범용적으로 사용됨

##### 해결 5: DTO Projection

**필요한 데이터만 조회**

```java
// 인터페이스 기반 Projection
public interface UserOrderSummary {
    String getName();
    Long getOrderCount();
    Integer getTotalAmount();
}

@Query("""
    SELECT u.name as name,
           COUNT(o.id) as orderCount,
           SUM(o.amount) as totalAmount
    FROM User u
    LEFT JOIN u.orders o
    GROUP BY u.id, u.name
    """)
List<UserOrderSummary> findUserOrderSummaries();

// 클래스 기반 Projection
public record UserOrderDto(
    String name,
    Long orderCount,
    Integer totalAmount
) {}

@Query("""
    SELECT new com.example.UserOrderDto(
        u.name,
        COUNT(o.id),
        SUM(o.amount)
    )
    FROM User u
    LEFT JOIN u.orders o
    GROUP BY u.id, u.name
    """)
List<UserOrderDto> findUserOrderDtos();
```

##### 해결 6: QueryDSL로 동적 Fetch Join

```java
@Repository
@RequiredArgsConstructor
public class UserRepositoryCustomImpl implements UserRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<User> findAllWithDynamicFetch(boolean includeOrders) {
        QUser user = QUser.user;
        QOrder order = QOrder.order;

        JPAQuery<User> query = queryFactory
            .selectFrom(user)
            .distinct();

        if (includeOrders) {
            query.leftJoin(user.orders, order).fetchJoin();
        }

        return query.fetch();
    }

    @Override
    public List<User> findUsersWithOrdersGreaterThan(int minOrderCount) {
        QUser user = QUser.user;
        QOrder order = QOrder.order;

        return queryFactory
            .selectFrom(user)
            .distinct()
            .leftJoin(user.orders, order).fetchJoin()
            .groupBy(user.id)
            .having(order.count().goe(minOrderCount))
            .fetch();
    }
}
```

#### 4. 실무 패턴 및 Best Practices

##### 패턴 1: Fetch Join + DTO 변환

```java
@Service
@Transactional(readOnly = true)
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public List<UserDto> getUsersWithOrders() {
        // 1. Fetch Join으로 한번에 조회
        List<User> users = userRepository.findAllWithOrders();

        // 2. DTO로 변환
        return users.stream()
            .map(UserDto::from)
            .toList();
    }
}

public record UserDto(
    Long id,
    String name,
    List<OrderDto> orders
) {
    public static UserDto from(User user) {
        return new UserDto(
            user.getId(),
            user.getName(),
            user.getOrders().stream()
                .map(OrderDto::from)
                .toList()
        );
    }
}
```

##### 패턴 2: 조건부 Fetch

```java
@Service
@Transactional(readOnly = true)
public class UserService {

    public List<UserDto> getUsers(boolean includeOrders) {
        List<User> users;

        if (includeOrders) {
            // 필요할 때만 Fetch Join
            users = userRepository.findAllWithOrders();
        } else {
            // 기본 조회
            users = userRepository.findAll();
        }

        return users.stream()
            .map(user -> new UserDto(
                user.getId(),
                user.getName(),
                includeOrders ? user.getOrders().size() : 0
            ))
            .toList();
    }
}
```

##### 패턴 3: 다단계 연관관계 최적화

```java
// User → Order → Product 3단계 연관관계

// ❌ N+1+1 문제 발생
@Query("SELECT u FROM User u JOIN FETCH u.orders")
List<User> findAllWithOrders();
// User, Order는 한번에 조회하지만
// Product는 각 Order마다 조회됨

// ✅ 한번에 모두 조회
@Query("""
    SELECT DISTINCT u
    FROM User u
    JOIN FETCH u.orders o
    JOIN FETCH o.product
    """)
List<User> findAllWithOrdersAndProducts();

// ✅ 또는 BatchSize 활용
@Entity
public class Order {
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @BatchSize(size = 100)
    @ManyToOne
    @JoinColumn(name = "product_id")
    private Product product;
}
```

##### 패턴 4: 읽기 전용 최적화

```java
@Transactional(readOnly = true)
public List<UserDto> getUsers() {
    // readOnly = true:
    // 1. Flush 모드를 MANUAL로 설정 (변경 감지 스킵)
    // 2. 성능 향상 (특히 대량 조회 시)

    return userRepository.findAllWithOrders()
        .stream()
        .map(UserDto::from)
        .toList();
}
```

#### 5. 방식별 선택 가이드

| 상황 | 권장 방식 | 이유 |
|-----|----------|------|
| 단일 Entity 조회 시 연관 데이터 필요 | Fetch Join | 가장 효율적 |
| 전체 조회 시 연관 데이터 필요 | Fetch Join + DISTINCT | 중복 제거 필요 |
| 여러 컬렉션 동시 로딩 | Fetch Join 1개 + @BatchSize | MultipleBag 회피 |
| 조건부로 연관 데이터 필요 | @EntityGraph | 간편함 |
| 집계 쿼리 | DTO Projection | 불필요한 Entity 로딩 X |
| 복잡한 동적 쿼리 | QueryDSL | 유연성 |
| 기존 코드 최소 변경 | @BatchSize (전역 설정) | 간단한 적용 |

#### 6. 성능 측정 및 모니터링

```java
// 쿼리 카운트 검증 테스트
@SpringBootTest
@Transactional
class NPlusOneTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void nPlusOneProblemTest() {
        // 쿼리 카운터 (HibernateStatisticsCollector 사용)
        Statistics stats = sessionFactory.getStatistics();
        stats.clear();

        // N+1 발생 코드
        List<User> users = userRepository.findAll();
        users.forEach(user -> user.getOrders().size());

        long queryCount = stats.getPrepareStatementCount();
        System.out.println("Total queries: " + queryCount);
        // N+1 발생 시: 101 (1 + 100)
        // 해결 후: 1
    }
}
```

**로깅 설정**

```yaml
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.type.descriptor.sql.BasicBinder: TRACE
    org.hibernate.stat: DEBUG

spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true
        format_sql: true
        use_sql_comments: true
```

#### 2. Projection 활용
```java
// DTO Projection
public interface UserSummary {
    Long getId();
    String getName();
    String getEmail();
}

@Query("SELECT u.id as id, u.name as name, u.email as email FROM User u")
List<UserSummary> findAllSummaries();

// Class-based Projection
public record UserDto(Long id, String name, String email) {}

@Query("SELECT new com.example.UserDto(u.id, u.name, u.email) FROM User u")
List<UserDto> findAllDto();
```

#### 3. 읽기 전용 쿼리
```java
@Transactional(readOnly = true)
public List<User> findAllUsers() {
    return userRepository.findAll();
}
```

### 실무 팁

#### 1. Auditing 설정
```java
@Configuration
@EnableJpaAuditing
public class JpaConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> Optional.of(SecurityContextHolder.getContext()
            .getAuthentication()
            .getName());
    }
}

@EntityListeners(AuditingEntityListener.class)
@MappedSuperclass
public abstract class BaseEntity {

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(updatable = false)
    private String createdBy;

    @LastModifiedBy
    private String updatedBy;
}
```

#### 2. Soft Delete 구현
```java
@Entity
@SQLDelete(sql = "UPDATE users SET deleted = true WHERE id = ?")
@Where(clause = "deleted = false")
public class User {

    @Column(nullable = false)
    private Boolean deleted = false;
}
```

#### 3. 낙관적 잠금 (Optimistic Lock)
```java
@Entity
public class User {

    @Version
    private Long version;
}
```

#### 4. 비관적 잠금 (Pessimistic Lock)
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT u FROM User u WHERE u.id = :id")
Optional<User> findByIdWithLock(@Param("id") Long id);
```

## 핵심 정리

### Entity Lifecycle & Persistence Context
- **Persistence Context**: Entity를 관리하는 영속성 컨텍스트, 1차 캐시/변경 감지/지연 로딩 제공
- **Entity 4가지 상태**: 비영속(New) → 영속(Managed) → 준영속(Detached) → 삭제(Removed)
- **영속 상태의 이점**: 1차 캐시(동일성 보장), 변경 감지(Dirty Checking), 쓰기 지연(Write-Behind)
- **준영속 상태**: 트랜잭션 종료 후 또는 detach() 호출 시, 변경 감지 작동 안함
- **플러시(Flush)**: 영속성 컨텍스트 변경 내용을 DB에 반영 (커밋/JPQL 실행 시 자동)
- **벌크 연산 주의**: 영속성 컨텍스트를 거치지 않으므로 clear() 또는 @Modifying(clearAutomatically=true) 필요

### Spring Data JPA 활용
- **Spring Data JPA**는 JPA를 추상화하여 Repository 기반 개발 제공
- **메서드 이름 기반 쿼리**: 간단한 조회는 메서드 이름으로 자동 생성
- **@Query**: 복잡한 쿼리는 JPQL 또는 Native SQL로 작성
- **Specifications/QueryDSL**: 동적 쿼리 작성에 활용

### 성능 최적화
- **N+1 문제**: 1번의 쿼리로 N개 조회 후, 각각의 연관 데이터를 N번 추가 조회하는 문제
- **Fetch 전략**: 모든 연관관계를 LAZY로 설정 권장 (EAGER는 불필요한 조인 발생)
- **Fetch Join**: JPQL에서 JOIN FETCH로 한번에 조회 (가장 효과적)
- **@EntityGraph**: 간편한 Fetch Join 대안, attributePaths로 연관 Entity 지정
- **@BatchSize**: IN 절로 N번 쿼리를 1번으로 축소 (100~1000 권장)
- **여러 컬렉션**: 하나는 Fetch Join, 나머지는 @BatchSize (MultipleBag 회피)
- **Projection**: 필요한 필드만 조회하여 성능 최적화
- **@Transactional(readOnly = true)**: Flush 모드 MANUAL, 변경 감지 스킵

### 실무 팁
- **Auditing**: 생성/수정 시간과 사용자 자동 기록
- **Soft Delete**: @SQLDelete와 @Where로 논리 삭제 구현
- **낙관적/비관적 잠금**: 동시성 제어 전략
- **Cascade & OrphanRemoval**: 연관 Entity 생명주기 자동 관리

## 참고 자료
- [Spring Data JPA 공식 문서](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/)
- [Hibernate 공식 문서](https://hibernate.org/orm/documentation/)
- [QueryDSL 공식 문서](http://querydsl.com/static/querydsl/latest/reference/html/)
- [JPA 프로그래밍 - 김영한](https://www.inflearn.com/course/ORM-JPA-Basic)
