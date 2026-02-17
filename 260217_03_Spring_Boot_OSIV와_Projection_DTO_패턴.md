# Spring Boot OSIV와 Projection/DTO 패턴

> Spring Boot에서 Open Session in View (OSIV) 패턴과 Entity 직접 반환의 관계, 그리고 Spring Data JPA Projection (Interface-based, Class-based, Dynamic)의 개념과 DTO와의 비교

## 목차
1. [OSIV (Open Session In View) 개념](#1-osiv-open-session-in-view-개념)
2. [OSIV와 Entity 직접 반환의 문제점](#2-osiv와-entity-직접-반환의-문제점)
3. [OSIV 비활성화와 LazyInitializationException](#3-osiv-비활성화와-lazyinitializationexception)
4. [Spring Data JPA Projection 3가지 타입](#4-spring-data-jpa-projection-3가지-타입)
5. [Projection vs DTO Trade-off](#5-projection-vs-dto-trade-off)
6. [Best Practice 권장사항](#6-best-practice-권장사항)

---

## 1. OSIV (Open Session In View) 개념

### 1.1 OSIV란?

OSIV(Open Session In View)는 **JPA EntityManager(Hibernate Session)를 HTTP 요청의 전체 생명주기 동안 열어두는 패턴**입니다.

- Spring Boot에서는 `OpenEntityManagerInViewInterceptor`를 통해 구현
- HTTP 요청이 들어올 때부터 응답이 렌더링될 때까지 세션 유지
- **Spring Boot에서 기본값은 `true`** (enabled)

```properties
# application.properties
spring.jpa.open-in-view=true  # 기본값
```

### 1.2 OSIV의 동작 방식

```
┌─────────────────────────────────────────────────────────┐
│                    HTTP Request                         │
├─────────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────────┐  │
│  │    EntityManager/Session OPEN (OSIV Filter)       │  │
│  ├───────────────────────────────────────────────────┤  │
│  │  Controller → Service (@Transactional)            │  │
│  │              ↓                                    │  │
│  │         Transaction Commit                        │  │
│  │              ↓                                    │  │
│  │    View Rendering (Lazy Loading 가능)              │  │
│  ├───────────────────────────────────────────────────┤  │
│  │    EntityManager/Session CLOSE                    │  │
│  └───────────────────────────────────────────────────┘  │
│                    HTTP Response                        │
└─────────────────────────────────────────────────────────┘
```

### 1.3 Spring Boot 2.0+ 경고 메시지

Spring Boot 2.0부터는 OSIV가 활성화되어 있으면 다음과 같은 **경고 메시지**를 출력합니다:

```
spring.jpa.open-in-view is enabled by default.
Therefore, database queries may be performed during view rendering.
Explicitly configure spring.jpa.open-in-view to disable this warning.
```

이는 개발자에게 OSIV의 존재를 알리고, 명시적으로 설정하도록 권장하는 메시지입니다.

---

## 2. OSIV와 Entity 직접 반환의 문제점

### 2.1 OSIV 활성화 시 Entity 직접 반환의 문제

#### 1) Connection Pool Exhaustion (커넥션 고갈)

OSIV가 활성화되면 **View 렌더링(JSON 직렬화 포함)까지 DB 커넥션을 점유**합니다.

```java
@RestController
public class UserController {

    @GetMapping("/users/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.findById(id);  // Entity 직접 반환
        // JSON 직렬화 시점까지 DB 커넥션 유지 → 성능 저하!
    }
}
```

**문제점:**
- 응답이 클라이언트로 전송될 때까지 커넥션 점유
- 동시 사용자가 많을 경우 **커넥션 풀 고갈** 발생
- 애플리케이션이 느려지거나 응답 불가 상태 발생

#### 2) N+1 문제 악화

Lazy Loading이 View 레이어에서 동작하면서 **예기치 않은 추가 쿼리**가 실행됩니다.

```java
@Entity
public class User {
    @Id
    private Long id;
    private String name;

    @OneToMany(fetch = FetchType.LAZY)
    private List<Order> orders;  // Lazy Loading
}

// Controller
@GetMapping("/users")
public List<User> getUsers() {
    return userService.findAll();  // 1개 쿼리
    // JSON 직렬화 시 orders 접근 → N개 쿼리 추가 발생!
}
```

**실행되는 쿼리:**
```sql
SELECT * FROM users;                    -- 1번 (users 100명)
SELECT * FROM orders WHERE user_id = 1; -- 2번
SELECT * FROM orders WHERE user_id = 2; -- 3번
...
SELECT * FROM orders WHERE user_id = 100; -- 101번
-- 총 101개 쿼리 실행! (N+1 문제)
```

#### 3) Auto-commit 모드에서의 쿼리 실행

트랜잭션이 종료된 후 View 레이어에서 발생하는 추가 쿼리는 **auto-commit 모드**로 실행됩니다.

- 각 쿼리가 독립적인 트랜잭션으로 처리
- 데이터베이스에 부담 증가
- 성능 저하 및 일관성 문제 발생 가능

#### 4) JSON 직렬화 시 문제

Entity를 직접 반환하면 Jackson이 JSON으로 변환하는 과정에서 문제 발생:

```java
@Entity
public class User {
    private Long id;
    private String password;  // 민감 정보 노출 위험!

    @OneToMany(mappedBy = "user")
    private List<Order> orders;

    // Order도 User 참조 시 → 순환 참조로 StackOverflowError!
}
```

**발생 가능한 문제:**
- **민감 정보 노출**: 의도치 않은 필드(password, 내부 ID 등) 노출
- **순환 참조**: `@JsonManagedReference`, `@JsonBackReference` 필요
- **불필요한 데이터 전송**: 모든 필드가 JSON에 포함되어 네트워크 낭비
- **Lazy Loading 예외**: OSIV 비활성화 시 `LazyInitializationException` 발생

---

## 3. OSIV 비활성화와 LazyInitializationException

### 3.1 OSIV 비활성화 방법

```properties
# application.properties
spring.jpa.open-in-view=false
```

### 3.2 LazyInitializationException 발생 메커니즘

OSIV를 비활성화하면 **트랜잭션 종료와 동시에 EntityManager가 닫힙니다**.

```
┌─────────────────────────────────────────────────────────┐
│                    HTTP Request                         │
├─────────────────────────────────────────────────────────┤
│  Controller                                             │
│      ↓                                                  │
│  Service (@Transactional) - EntityManager OPEN          │
│      ↓                                                  │
│ Repository.findById(1) -- SELECT * FROM users WHERE id=1│
│      ↓                                                  │
│ Transaction Commit & EntityManager CLOSE ← 여기서 세션 종료!│
│      ↓                                                  │
│  Controller return entity                               │
│      ↓                                                  │
│  JSON Serialization (Jackson)                           │
│      ↓                                                  │
│  entity.getOrders()  ← Lazy Loading 시도                 │
│      ↓                                                  │
│  ❌ LazyInitializationException!                        │
│  (could not initialize proxy - no Session)              │
└─────────────────────────────────────────────────────────┘
```

### 3.3 예외 발생 예시

```java
@Service
@Transactional
public class UserService {
    public User findById(Long id) {
        return userRepository.findById(id).orElseThrow();
    } // 여기서 트랜잭션 종료 → EntityManager 닫힘
}

@RestController
public class UserController {
    @GetMapping("/users/{id}")
    public User getUser(@PathVariable Long id) {
        User user = userService.findById(id);
        // 여기서는 이미 세션이 닫힌 상태!
        return user;  // JSON 직렬화 시도
        // user.getOrders() 접근 시 LazyInitializationException!
    }
}
```

**에러 메시지:**
```
org.hibernate.LazyInitializationException:
could not initialize proxy [com.example.Order#1] - no Session
```

### 3.4 해결 방법

#### 1) Fetch Join 사용

```java
@Query("SELECT u FROM User u JOIN FETCH u.orders WHERE u.id = :id")
User findByIdWithOrders(@Param("id") Long id);
```

#### 2) Entity Graph 사용

```java
@EntityGraph(attributePaths = {"orders"})
@Query("SELECT u FROM User u WHERE u.id = :id")
User findByIdWithOrders(@Param("id") Long id);
```

#### 3) DTO 변환 (권장)

```java
@Service
@Transactional
public class UserService {
    public UserDto findById(Long id) {
        User user = userRepository.findById(id).orElseThrow();
        return new UserDto(user);  // 트랜잭션 내에서 DTO 변환
    }
}
```

---

## 3.5 First-Level Cache (1차 캐시) 이해하기

### 3.5.1 First-Level Cache란?

JPA 용어로 **Persistence Context(영속성 컨텍스트)**라고 부르며, Hibernate에서는 **Session**이라고 합니다. First-Level Cache는 **EntityManager가 관리하는 엔티티 저장소**입니다.

```java
// 내부적으로 Map 구조
Map<EntityKey, Object> entitiesByUniqueKey = new HashMap<>();
```

**핵심 특징:**
- **자동 활성화**: 비활성화 불가능
- **세션 스코프**: EntityManager 인스턴스마다 독립적으로 존재
- **스레드 안전하지 않음**: 현재 실행 중인 스레드에만 바인딩
- **EntityManager 종료 시 소멸**: 트랜잭션 범위와 생명주기 동일

### 3.5.2 동작 방식

```java
@Transactional
public void example() {
    User user1 = entityManager.find(User.class, 1L);  // 1번: DB 조회 → 캐시 저장
    User user2 = entityManager.find(User.class, 1L);  // 2번: 캐시에서 반환 (DB 조회 X)

    System.out.println(user1 == user2);  // true (동일 인스턴스)
}
```

**실행되는 SQL:**
```sql
-- 1번 호출 시에만 실행
SELECT * FROM users WHERE id = 1;
-- 2번 호출 시에는 실행되지 않음 (캐시에서 반환)
```

### 3.5.3 Loaded State & Dirty Checking

엔티티가 로드되면 Hibernate는 **스냅샷(Loaded State)**을 1차 캐시에 함께 저장합니다.

```java
@Transactional
public void updateUser(Long id, String newName) {
    User user = entityManager.find(id);  // Loaded State 저장
    // Loaded State: {id: 1, name: "John", email: "john@example.com"}

    user.setName(newName);  // 엔티티 상태 변경

    // 트랜잭션 커밋 시 Dirty Checking
    // 현재 상태와 Loaded State 비교 → UPDATE 쿼리 생성
}
```

**flush 시점에 비교:**
```
Loaded State:   {name: "John"}
Current State:  {name: "Jane"}
          ↓
Dirty Checking 감지 → UPDATE users SET name = 'Jane' WHERE id = 1
```

### 3.5.4 DTO Projection과 1차 캐시

**Entity 조회 시:**
```java
// Entity는 1차 캐시에 저장됨
User user = entityManager.find(User.class, 1L);  // 캐시 저장
User user2 = entityManager.find(User.class, 1L); // 캐시 반환 (DB X)
```

**DTO Projection 조회 시:**
```java
// DTO는 1차 캐시에 저장되지 않음
@Query("SELECT new com.example.UserDto(u.name, u.email) FROM User u WHERE u.id = :id")
UserDto findUserDto(@Param("id") Long id);

UserDto dto1 = repository.findUserDto(1L);  // DB 조회
UserDto dto2 = repository.findUserDto(1L);  // 다시 DB 조회 (캐시 X)
```

**대량 조회 시 메모리 비교:**

| 조회 방식 | 1,000개 엔티티 조회 시 메모리 |
|---|---|
| Entity 조회 | 엔티티 1,000개 + Loaded State 1,000개 = **약 2배 메모리** |
| DTO Projection | DTO 1,000개만 = **메모리 효율적** |

```java
// ❌ 대량 데이터 조회 시 메모리 부담
List<User> users = userRepository.findAll();  // 10,000개
// → 10,000개 엔티티 + 10,000개 Loaded State = 메모리 부담!

// ✅ DTO Projection 사용
List<UserDto> dtos = userRepository.findAllUserDtos();  // 10,000개
// → 10,000개 DTO만 = 메모리 효율적, Loaded State 없음
```

### 3.5.5 쿼리는 1차 캐시를 우회한다

**중요한 함정:**
```java
@Transactional
public void cacheBypass() {
    User user = new User("John");
    entityManager.persist(user);  // 아직 INSERT 안 됨, 1차 캐시에만 존재

    // JPQL 쿼리는 DB를 직접 조회 (1차 캐시 우회)
    List<User> users = entityManager.createQuery(
        "SELECT u FROM User u WHERE u.name = 'John'", User.class
    ).getResultList();

    // users는 비어있음! (아직 DB에 INSERT 안 됨)
}
```

**해결책:**
```java
@Transactional
public void withFlush() {
    User user = new User("John");
    entityManager.persist(user);
    entityManager.flush();  // 강제로 INSERT 실행

    List<User> users = entityManager.createQuery(
        "SELECT u FROM User u WHERE u.name = 'John'", User.class
    ).getResultList();

    // 이제 users에 포함됨
}
```

### 3.5.6 1차 캐시 vs 2차 캐시

| 구분 | 1차 캐시 (Persistence Context) | 2차 캐시 (Second-Level Cache) |
|---|---|---|
| **스코프** | EntityManager 단위 (세션) | 애플리케이션 전체 (공유) |
| **기본 활성화** | ✅ 항상 활성화 | ❌ 명시적 설정 필요 |
| **스레드 안전성** | ❌ 단일 스레드 전용 | ✅ 멀티 스레드 안전 |
| **생명주기** | 트랜잭션과 동일 | 애플리케이션 생명주기 |
| **구현 예** | 내장 (JPA 표준) | Ehcache, Infinispan, Hazelcast |
| **용도** | 동일 트랜잭션 내 반복 조회 | 트랜잭션 간 읽기 전용 데이터 캐싱 |

```java
// 1차 캐시 (같은 트랜잭션 내)
@Transactional
public void firstLevelCache() {
    User user1 = em.find(User.class, 1L);  // DB 조회
    User user2 = em.find(User.class, 1L);  // 1차 캐시 반환
    // user1 == user2 → true
}

// 2차 캐시 (트랜잭션 간)
@Transactional
public void transaction1() {
    User user = em.find(User.class, 1L);  // DB 조회 → 2차 캐시 저장
}

@Transactional
public void transaction2() {
    User user = em.find(User.class, 1L);  // 2차 캐시에서 반환 (DB X)
}
```

### 3.5.7 실무 활용 팁

1. **읽기 전용 조회 시 DTO Projection 선호**
   - 1차 캐시 오버헤드 없음
   - Loaded State 생성하지 않음
   - 메모리 효율적

2. **쓰기 작업 시 Entity 사용**
   - Dirty Checking 활용
   - 1차 캐시로 반복 조회 최적화

3. **대량 데이터 처리 시 배치 크기 조절**
   ```java
   @Transactional
   public void processBatch() {
       for (int i = 0; i < 10000; i++) {
           User user = userRepository.findById(i).orElseThrow();
           user.process();

           if (i % 50 == 0) {
               entityManager.flush();   // DB 동기화
               entityManager.clear();   // 1차 캐시 비우기 (메모리 절약)
           }
       }
   }
   ```

---

## 4. Spring Data JPA Projection 3가지 타입

Spring Data JPA는 **엔티티의 일부 필드만 조회하는 Projection 기능**을 제공합니다.

### 4.1 Interface-based Projection (인터페이스 기반)

**가장 일반적이고 간단한 방식**으로, **동적 프록시 객체**를 생성하여 데이터를 매핑합니다.

#### Closed Projection (닫힌 프로젝션)

인터페이스의 getter 메서드가 **엔티티 필드와 1:1 매핑**되는 경우입니다.

```java
// Projection Interface
public interface UserSummary {
    String getName();
    String getEmail();
    // 정확히 필요한 필드만 SELECT
}

// Repository
public interface UserRepository extends JpaRepository<User, Long> {
    List<UserSummary> findByAgeGreaterThan(int age);
}

// 실행되는 SQL
// SELECT u.name, u.email FROM users u WHERE u.age > ?
```

**특징:**
- Spring Data JPA가 **필요한 컬럼만 SELECT** (성능 최적화)
- 타입 안전성 보장
- 가장 효율적인 방식

#### Open Projection (열린 프로젝션)

**SpEL(Spring Expression Language)** 을 사용하여 계산된 필드를 제공합니다.

```java
public interface UserView {
    @Value("#{target.firstName + ' ' + target.lastName}")
    String getFullName();

    @Value("#{target.orders.size()}")
    int getOrderCount();
}
```

**주의사항:**
- **전체 엔티티를 조회**한 후 메모리에서 계산 (성능 저하)
- SpEL 표현식 평가 오버헤드 발생
- N+1 문제 발생 가능 (`target.orders` 접근 시 추가 쿼리)

### 4.2 Class-based Projection (DTO - 클래스 기반)

**DTO 클래스**를 직접 정의하여 생성자 매핑을 사용하는 방식입니다.

```java
// DTO Class
public class UserDto {
    private final String name;
    private final String email;

    public UserDto(String name, String email) {
        this.name = name;
        this.email = email;
    }

    // getters
}

// Repository with JPQL Constructor Expression
public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT new com.example.dto.UserDto(u.name, u.email) " +
           "FROM User u WHERE u.age > :age")
    List<UserDto> findUserDtos(@Param("age") int age);
}
```

**Java Record 사용 (Java 14+):**

```java
// Record
public record UserDto(String name, String email) {}

// Repository
@Query("SELECT new com.example.dto.UserDto(u.name, u.email) FROM User u")
List<UserDto> findAllUserDtos();
```

**특징:**
- **필요한 컬럼만 SELECT** (성능 최적화)
- 생성자에서 추가 로직 수행 가능 (검증, 변환 등)
- 명시적이고 명확한 코드
- 1st-level cache에 저장되지 않음 (메모리 효율)

### 4.3 Dynamic Projection (동적 프로젝션)

**런타임에 Projection 타입을 결정**하는 방식입니다.

```java
public interface UserRepository extends JpaRepository<User, Long> {

    <T> List<T> findByAgeGreaterThan(int age, Class<T> type);
}

// 사용 예시
List<UserSummary> summaries = userRepository.findByAgeGreaterThan(
    20,
    UserSummary.class
);

List<UserDto> dtos = userRepository.findByAgeGreaterThan(
    20,
    UserDto.class
);

// Java Record와 함께 사용 (메서드 내 지역 레코드)
void someMethod() {
    record UserLocal(String name, String email) {}

    List<UserLocal> users = userRepository.findByAgeGreaterThan(
        20,
        UserLocal.class
    );
}
```

**특징:**
- 하나의 메서드로 여러 Projection 타입 지원
- 컴파일 타임에 타입이 결정되지 않음
- 유연성이 높지만 타입 안전성 약간 감소

---

## 5. Projection vs DTO Trade-off

### 5.1 성능 비교

| 비교 항목 | Interface-based (Closed) | Interface-based (Open) | Class-based DTO |
|---------|------------------------|----------------------|----------------|
| **SELECT 최적화** | ✅ 필요한 컬럼만 | ❌ 전체 엔티티 조회 | ✅ 필요한 컬럼만 |
| **1st-level 캐시** | ❌ (저장 안 됨) | ✅ (엔티티 저장) | ❌ (저장 안 됨) |
| **N+1 위험** | ⚠️ 연관관계 접근 시 | ⚠️ SpEL에서 발생 가능 | ✅ JPQL로 제어 가능 |
| **메모리 효율** | ✅ 높음 | ❌ 낮음 (전체 엔티티) | ✅ 높음 |
| **타입 안전성** | ✅ 높음 | ✅ 높음 | ✅ 높음 |
| **추가 로직** | ❌ 불가 | ⚠️ SpEL만 가능 | ✅ 생성자에서 가능 |

### 5.2 Entity vs DTO 비교

#### Entity Projection의 장점

- **쓰기(Write) 작업에 최적**: Dirty Checking으로 자동 UPDATE
- 코드가 간결함 (별도 DTO 불필요)
- JPA 기능 활용 가능 (영속성 컨텍스트, 변경 감지 등)

#### Entity Projection의 단점

- **1st-level 캐시 오버헤드**: 수백~수천 개 엔티티 조회 시 캐시 관리 비용
- **불필요한 필드 로딩**: 전체 컬럼 SELECT
- **민감 정보 노출 위험**: Controller에서 직접 반환 시 보안 이슈
- **JSON 직렬화 문제**: 순환 참조, Lazy Loading 예외 등

#### DTO Projection의 장점

- **읽기(Read) 작업에 최적**: 필요한 데이터만 조회
- **성능 최적화**:
  - 네트워크 전송량 감소
  - DB 부하 감소
  - 메모리 사용량 감소 (1st-level 캐시 미사용)
- **보안**: 민감 정보 제어 가능
- **명확한 API 계약**: 클라이언트가 받을 데이터 구조가 명확

#### DTO Projection의 단점

- **추가 코드 작성**: DTO 클래스, 변환 로직 필요
- **유지보수 부담**: Entity 변경 시 DTO도 동기화 필요
- **쓰기 작업 불가**: Dirty Checking 불가능

### 5.3 사용 권장사항

| 시나리오 | 권장 방식 |
|---------|---------|
| **단순 조회 (소수 필드)** | Interface-based Closed Projection |
| **복잡한 조회 (JOIN, 계산)** | Class-based DTO with JPQL |
| **CUD 작업** | Entity |
| **API 응답** | DTO (보안, 성능) |
| **대량 데이터 조회** | DTO (1st-level 캐시 오버헤드 방지) |
| **집계 쿼리** | DTO / Tuple |
| **민감 정보 포함** | DTO (필드 제어) |
| **동적 필드 선택** | Dynamic Projection |

---

## 6. Best Practice 권장사항

### 6.1 OSIV 설정

#### 프로덕션 환경

```properties
# application-prod.properties
spring.jpa.open-in-view=false
```

**이유:**
- 커넥션 풀 효율성 향상
- 트랜잭션 경계 명확화
- 예상치 못한 쿼리 방지
- 성능 문제 조기 발견

#### 개발 환경

```properties
# application-dev.properties
spring.jpa.open-in-view=true  # 편의성을 위해 활성화 가능
```

**주의:** 개발 환경에서도 `false`로 설정하는 것을 권장하여 문제를 조기에 발견하는 것이 좋습니다.

### 6.2 프로젝트 시작 시 체크리스트

1. **OSIV 비활성화를 첫 단계로 설정**
   ```properties
   spring.jpa.open-in-view=false
   ```

2. **모든 연관관계를 LAZY로 설정**
   ```java
   @OneToMany(fetch = FetchType.LAZY)  // 기본값이지만 명시적으로
   @ManyToOne(fetch = FetchType.LAZY)  // 기본값 EAGER → LAZY 변경!
   ```

3. **Controller에서 Entity 반환 금지**
   ```java
   // ❌ 나쁜 예
   @GetMapping("/users/{id}")
   public User getUser(@PathVariable Long id) {
       return userService.findById(id);
   }

   // ✅ 좋은 예
   @GetMapping("/users/{id}")
   public UserResponse getUser(@PathVariable Long id) {
       return userService.findUserById(id);  // DTO 반환
   }
   ```

4. **읽기 전용 쿼리는 DTO Projection 사용**
   ```java
   @Query("SELECT new com.example.dto.UserDto(u.name, u.email) " +
          "FROM User u WHERE u.id = :id")
   UserDto findUserDtoById(@Param("id") Long id);
   ```

### 6.3 계층별 책임 분리

```
┌────────────────────────────────────────────────────────┐
│  Controller Layer                                      │
│  - DTO/Response 객체만 반환                             │
│  - @Transactional 사용 금지                             │
└────────────────────────────────────────────────────────┘
                        ↓
┌────────────────────────────────────────────────────────┐
│  Service Layer                                         │
│  - @Transactional 경계 설정                              │
│  - Entity → DTO 변환 (트랜잭션 내부에서)                    │
│  - 비즈니스 로직 처리                                      │
└────────────────────────────────────────────────────────┘
                        ↓
┌────────────────────────────────────────────────────────┐
│  Repository Layer                                      │
│  - Entity 또는 DTO Projection 반환                       │
│  - 쿼리 최적화 (JOIN FETCH, Entity Graph, DTO)            │
└────────────────────────────────────────────────────────┘
```

### 6.4 예제 코드 (Best Practice)

#### Entity

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;
    private String password;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<Order> orders = new ArrayList<>();

    // 생성자, getter, 비즈니스 메서드
}
```

#### DTO

```java
// Record 사용 (Java 14+)
public record UserResponse(
    Long id,
    String name,
    String email
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getId(),
            user.getName(),
            user.getEmail()
        );
    }
}

public record UserDetailResponse(
    Long id,
    String name,
    String email,
    List<OrderSummary> orders
) {
    public static UserDetailResponse from(User user) {
        List<OrderSummary> orderSummaries = user.getOrders().stream()
            .map(OrderSummary::from)
            .toList();

        return new UserDetailResponse(
            user.getId(),
            user.getName(),
            user.getEmail(),
            orderSummaries
        );
    }
}
```

#### Repository

```java
public interface UserRepository extends JpaRepository<User, Long> {

    // DTO Projection (권장)
    @Query("SELECT new com.example.dto.UserResponse(u.id, u.name, u.email) " +
           "FROM User u WHERE u.id = :id")
    Optional<UserResponse> findUserResponseById(@Param("id") Long id);

    // Entity with Fetch Join
    @Query("SELECT u FROM User u JOIN FETCH u.orders WHERE u.id = :id")
    Optional<User> findByIdWithOrders(@Param("id") Long id);

    // Interface-based Projection
    interface UserSummary {
        String getName();
        String getEmail();
    }

    List<UserSummary> findByNameContaining(String name);
}
```

#### Service

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository userRepository;

    // 단순 조회 - DTO Projection 사용
    public UserResponse findUserById(Long id) {
        return userRepository.findUserResponseById(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found"));
    }

    // 상세 조회 - Entity 조회 후 DTO 변환 (트랜잭션 내)
    public UserDetailResponse findUserDetailById(Long id) {
        User user = userRepository.findByIdWithOrders(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found"));

        return UserDetailResponse.from(user);  // 여기서 Lazy Loading 발생 (트랜잭션 내)
    }

    // 쓰기 작업 - Entity 사용
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        User user = User.builder()
            .name(request.name())
            .email(request.email())
            .password(passwordEncoder.encode(request.password()))
            .build();

        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Transactional
    public void updateUserName(Long id, String newName) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found"));

        user.changeName(newName);  // Dirty Checking으로 자동 UPDATE
    }
}
```

#### Controller

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;

    // DTO만 반환 (Entity 직접 반환 금지!)
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable Long id) {
        UserResponse response = userService.findUserById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/detail")
    public ResponseEntity<UserDetailResponse> getUserDetail(@PathVariable Long id) {
        UserDetailResponse response = userService.findUserDetailById(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody @Valid CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
```

---

## 7. 정리 및 핵심 요약

### 7.1 OSIV

| 항목 | OSIV=true | OSIV=false |
|-----|-----------|------------|
| **세션 유지** | View 렌더링까지 | 트랜잭션 종료 시 |
| **Lazy Loading** | View에서 가능 | 트랜잭션 내에서만 |
| **커넥션 점유** | 요청 전체 기간 | 트랜잭션 기간만 |
| **N+1 위험** | 높음 (눈에 안 보임) | 낮음 (즉시 예외 발생) |
| **성능** | 낮음 | 높음 |
| **권장 환경** | 개발 (편의성) | 프로덕션 (필수) |

### 7.2 Projection 선택 가이드

```
읽기 작업인가?
  ├─ YES → 필드가 몇 개인가?
  │         ├─ 소수 (2~3개) → Interface-based Closed Projection
  │         └─ 다수 / 복잡한 JOIN → Class-based DTO
  │
  └─ NO (쓰기) → Entity 사용 (Dirty Checking)
```

### 7.3 핵심 원칙

1. **프로덕션에서는 OSIV 비활성화 필수**
2. **Controller에서 Entity 직접 반환 금지**
3. **읽기는 DTO, 쓰기는 Entity**
4. **모든 연관관계는 LAZY로 설정**
5. **트랜잭션 경계를 명확히 설정**

---

## 참고 자료 (Sources)

### 공식 문서
- [Spring Data JPA - Projections](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)
- [Spring Boot Reference - Data Properties](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html#application-properties.data)

### OSIV 관련
- [A Guide to Spring's Open Session In View | Baeldung](https://www.baeldung.com/spring-open-session-in-view)
- [Understanding OSIV in Spring Boot — Convenience or Hidden Bottleneck? | ercan.dev](https://ercan.dev/blog/notes/spring-boot-open-session-in-view-performance)
- [Spring Boot Best Practice - disable OSIV | Code Soapbox](https://codesoapbox.dev/spring-boot-best-practice-disable-osiv-to-start-receiving-lazyinitializationexception-warnings-again/)
- [Open Session in View (OSIV) in Spring - Enable or Disable? | Backendhance](https://backendhance.com/en/blog/2023/open-session-in-view/)

### LazyInitializationException
- [A (definitive?) guide on LazyInitializationException | Nicolas Fränkel](https://blog.frankel.ch/guide-lazyinitializationexception/)
- [LazyInitializationException - What it is and the best way to fix it | Thorben Janssen](https://thorben-janssen.com/lazyinitializationexception/)

### First-Level Cache (1차 캐시)
- [The JPA and Hibernate first-level cache | Vlad Mihalcea](https://vladmihalcea.com/jpa-hibernate-first-level-cache/)
- [Understanding EntityManager Sessions and First-Level Cache in Spring Data JPA | Medium](https://medium.com/@ayoubseddiki132/understanding-entitymanager-sessions-and-first-level-cache-in-spring-data-jpa-2cf87b8c2df7)
- [Hibernate Entity States and First-Level Cache | Medium](https://medium.com/@enisserbest/understanding-hibernate-entity-states-and-first-level-cache-a-complete-guide-24ddae1fc0fc)
- [JPA Caching Explained: First-Level and Second-Level Caches | prgrmmng.com](https://prgrmmng.com/jpa-caching-first-level-second-level)

### Projection 관련
- [Spring Data JPA Projections | Baeldung](https://www.baeldung.com/spring-data-jpa-projections)
- [Spring Data JPA: Query Projections | Thorben Janssen](https://thorben-janssen.com/spring-data-jpa-query-projections/)
- [Dynamic Projections with Spring Data JPA | Maciej Walkowiak](https://maciejwalkowiak.com/blog/spring-data-jpa-dynamic-projections/)

### DTO vs Projection
- [The best way to fetch a Spring Data JPA DTO Projection | Vlad Mihalcea](https://vladmihalcea.com/spring-jpa-dto-projection/)
- [Entities or DTOs - When should you use which projection? | Thorben Janssen](https://thorben-janssen.com/entities-dtos-use-projection/)
- [Why, When and How to Use DTO Projections with JPA and Hibernate | Thorben Janssen](https://thorben-janssen.com/dto-projections/)

### JSON Serialization
- [Serialization and Deserialization Issues in Spring REST | End Point Dev](https://www.endpointdev.com/blog/2020/03/serialization-issues-spring-rest/)
- [Returning JSON object as response in Spring Boot when returning Entity | ilhicas](https://ilhicas.com/2019/04/27/Returning-JSON-object-as-response-in-Spring-Boot.html)

---

**작성일**: 2026-02-17

**키워드**:
- **SpringBoot**: Java 기반 엔터프라이즈 애플리케이션 개발 프레임워크. Convention over Configuration 원칙으로 빠른 개발 지원
- **JPA (Java Persistence API)**: 자바 객체와 관계형 데이터베이스 간의 매핑을 관리하는 ORM 표준 API
- **OSIV (Open Session In View)**: EntityManager를 HTTP 요청 전체 기간 동안 유지하는 패턴. 커넥션 고갈과 N+1 문제 유발 (프로덕션에서 비활성화 권장)
- **Projection**: Spring Data JPA에서 엔티티의 일부 필드만 조회하는 기능. Interface-based, Class-based, Dynamic 3가지 타입 지원
- **DTO (Data Transfer Object)**: 계층 간 데이터 전송을 위한 객체. 불필요한 필드 제거, 보안 강화, 성능 최적화에 유리
- **LazyInitializationException**: JPA에서 세션이 닫힌 후 Lazy Loading 시도 시 발생하는 예외. OSIV 비활성화 시 트랜잭션 외부에서 주로 발생
- **Performance**: 애플리케이션 성능 최적화. DB 쿼리 최적화, 커넥션 풀 관리, 메모리 효율성 개선 등
