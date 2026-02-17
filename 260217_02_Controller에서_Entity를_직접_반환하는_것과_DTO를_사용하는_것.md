# Controller에서 Entity를 직접 반환하는 것과 DTO를 사용하는 것 중 무엇이 좋을까?

## 개요
Spring MVC에서 API 응답 시 Entity를 그대로 반환할지, DTO로 변환하여 반환할지는 실무에서 자주 논의되는 설계 결정이다. 각각의 장단점과 발생할 수 있는 문제, 그리고 프로젝트 규모에 따른 실무 전략을 정리한다.

## 상세 내용

### Entity 직접 반환의 5가지 치명적 문제점

#### 1. 순환 참조 → StackOverflowError

양방향 연관관계를 가진 Entity를 직접 반환하면 Jackson이 JSON 직렬화 중 무한 루프에 빠집니다.

```java
@Entity
public class User {
    @Id
    private Long id;
    private String name;

    @OneToMany(mappedBy = "user")
    private List<Order> orders;  // Order → User 참조
}

@Entity
public class Order {
    @Id
    private Long id;

    @ManyToOne
    private User user;  // User → Order 참조
}

// Controller
@GetMapping("/users/{id}")
public User getUser(@PathVariable Long id) {
    return userService.findById(id);
    // StackOverflowError! User → Order → User → Order → ...
}
```

**우회 방법과 문제점:**
```java
@Entity
public class User {
    @JsonManagedReference
    @OneToMany(mappedBy = "user")
    private List<Order> orders;
}

@Entity
public class Order {
    @JsonBackReference  // 직렬화 시 무시
    @ManyToOne
    private User user;
}
```

이렇게 하면 **Entity에 JSON 어노테이션이 침투**하여 도메인 모델이 오염됩니다.

#### 2. LazyInitializationException

OSIV(Open Session in View)가 비활성화되어 있으면 트랜잭션 종료 후 Lazy Loading 시도 시 예외가 발생합니다.

```properties
# application.properties
spring.jpa.open-in-view=false
```

```java
@Service
@Transactional
public class UserService {
    public User findById(Long id) {
        return userRepository.findById(id).orElseThrow();
    } // 트랜잭션 종료 → EntityManager 닫힘
}

@GetMapping("/users/{id}")
public User getUser(@PathVariable Long id) {
    User user = userService.findById(id);
    return user;  // JSON 직렬화 시 user.getOrders() 접근
    // LazyInitializationException: could not initialize proxy - no Session
}
```

#### 3. 민감 정보 노출

Entity의 모든 필드가 JSON에 포함되어 의도치 않은 정보가 노출됩니다.

```java
@Entity
public class User {
    private Long id;
    private String email;
    private String password;  // ❌ 클라이언트에 노출!
    private String internalNotes;  // ❌ 내부용 필드도 노출!
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

// Response:
// {
//   "id": 1,
//   "email": "user@example.com",
//   "password": "$2a$10$...",  ← 암호화되어도 노출되면 안 됨!
//   "internalNotes": "VIP customer",
//   ...
// }
```

#### 4. API 스펙이 DB 스키마에 종속

Entity 필드를 변경하면 API 스펙이 예고 없이 변경됩니다.

```java
@Entity
public class User {
    private String name;  // 기존 필드

    // 요구사항: 이름을 firstName, lastName으로 분리
    private String firstName;
    private String lastName;
    // name 필드 삭제 시 → API 응답에서 name 사라짐 → 프론트엔드 오류!
}
```

**문제:**
- DB 리팩토링이 API 계약 위반으로 이어짐
- 하위 호환성 유지 불가
- API 버전 관리 불가능

#### 5. N+1 문제 악화 (OSIV 활성화 시)

```java
@GetMapping("/users")
public List<User> getUsers() {
    return userService.findAll();  // SELECT * FROM users (1번)
    // JSON 직렬화 시 각 User마다 orders 접근
    // SELECT * FROM orders WHERE user_id = ? (N번)
}
```

---

### DTO를 사용해야 하는 이유

Martin Fowler가 제안한 **Data Transfer Object 패턴**은 계층 간 데이터 전송을 위한 전용 객체입니다.

#### 1. 관심사의 분리 (Separation of Concerns)

```
┌──────────────────┐
│   Presentation   │  ← API 스펙 (DTO)
├──────────────────┤
│     Business     │  ← 도메인 로직 (Entity)
├──────────────────┤
│   Persistence    │  ← DB 스키마 (Entity)
└──────────────────┘
```

- **Entity**: 비즈니스 로직과 DB 매핑
- **DTO**: 외부 통신 전용 (JSON 직렬화/역직렬화)

#### 2. 보안

```java
public record UserResponse(
    Long id,
    String email,
    String name
    // password, internalNotes 제외!
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getName()
        );
    }
}
```

#### 3. API 버전 관리

```java
// v1
public record UserResponseV1(String name) {}

// v2 - firstName, lastName 분리해도 v1 유지 가능
public record UserResponseV2(String firstName, String lastName) {}
```

#### 4. 성능 최적화

DTO Projection으로 필요한 컬럼만 SELECT:

```java
@Query("SELECT new com.example.dto.UserResponse(u.id, u.email, u.name) " +
       "FROM User u WHERE u.id = :id")
UserResponse findUserResponseById(@Param("id") Long id);

// 실행되는 SQL:
// SELECT u.id, u.email, u.name FROM users u WHERE u.id = ?
// (password, createdAt 등 불필요한 컬럼 제외)
```

---

### DTO 변환 전략

#### 1. 수동 변환 (정적 팩토리 메서드)

```java
// Java Record (Java 16+) - 권장
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

// Service Layer
@Transactional(readOnly = true)
public UserResponse findUserById(Long id) {
    User user = userRepository.findById(id).orElseThrow();
    return UserResponse.from(user);  // 트랜잭션 내에서 변환
}
```

#### 2. MapStruct 자동 매핑

```java
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserResponse toResponse(User user);

    @Mapping(target = "orders", ignore = true)
    User toEntity(CreateUserRequest request);
}

// Service
@RequiredArgsConstructor
public class UserService {
    private final UserMapper mapper;

    public UserResponse findUserById(Long id) {
        User user = userRepository.findById(id).orElseThrow();
        return mapper.toResponse(user);
    }
}
```

**MapStruct 장점:**
- 컴파일 타임에 코드 생성 → 타입 안전성
- 리플렉션 사용 안 함 → 성능 우수
- 복잡한 매핑 로직 지원 (`@Mapping`, custom methods)

#### 3. 계층별 DTO 분리

```java
// Request DTO (Controller → Service)
public record CreateUserRequest(
    @NotBlank String name,
    @Email String email,
    @Size(min = 8) String password
) {}

// Response DTO (Service → Controller)
public record UserResponse(Long id, String name, String email) {}

// Service Layer DTO (Service ↔ Service)
public record UserServiceDto(
    Long id,
    String name,
    String email,
    List<OrderDto> orders
) {}
```

---

### Spring Data JPA Projection vs DTO

Spring Data JPA는 **필요한 필드만 조회하는 Projection** 기능을 제공합니다.

#### Interface-based Projection (Closed)

```java
public interface UserSummary {
    String getName();
    String getEmail();
}

public interface UserRepository extends JpaRepository<User, Long> {
    List<UserSummary> findByNameContaining(String name);
}

// 실행되는 SQL:
// SELECT u.name, u.email FROM users u WHERE u.name LIKE ?
```

#### Class-based Projection (DTO)

```java
public record UserDto(String name, String email) {}

@Query("SELECT new com.example.dto.UserDto(u.name, u.email) FROM User u")
List<UserDto> findAllUserDtos();
```

#### Dynamic Projection

```java
<T> List<T> findByAgeGreaterThan(int age, Class<T> type);

// 사용:
List<UserSummary> summaries = repo.findByAgeGreaterThan(20, UserSummary.class);
List<UserDto> dtos = repo.findByAgeGreaterThan(20, UserDto.class);
```

---

### DTO 트레이드오프

#### 장점

| 항목 | 설명 |
|---|---|
| **보안** | 민감 필드 제어 가능 |
| **성능** | 필요한 필드만 SELECT, 네트워크 전송량 감소 |
| **API 안정성** | DB 변경이 API 계약에 영향 없음 |
| **명확한 계약** | 클라이언트가 받을 데이터 명확 |

#### 단점

| 항목 | 설명 |
|---|---|
| **보일러플레이트** | DTO 클래스, 변환 로직 작성 필요 |
| **유지보수** | Entity 변경 시 DTO도 동기화 필요 |
| **초기 비용** | 작은 프로젝트에서는 오버엔지니어링 느낌 |

---

### 실무 권장 전략

#### 프로젝트 규모별 가이드

| 프로젝트 규모 | 권장 전략 |
|---|---|
| **POC / 프로토타입** | Entity 직접 반환 허용 (빠른 개발) |
| **소규모 (1-2인)** | Record + 수동 변환 |
| **중규모 (3-10인)** | Record + MapStruct |
| **대규모 (10인+)** | DTO 레이어 필수, Projection 적극 활용 |

#### 사용 시나리오별 권장

| 시나리오 | 권장 방식 |
|---|---|
| **단순 조회 (소수 필드)** | Interface Projection |
| **복잡한 조회 (JOIN, 계산)** | Class-based DTO |
| **CUD 작업** | Entity (Dirty Checking) |
| **API 응답** | DTO 필수 |
| **대량 데이터 조회** | DTO (1st-level 캐시 오버헤드 방지) |

#### CQRS 패턴 활용

```java
// Command (쓰기) - Entity 사용
@Transactional
public UserResponse createUser(CreateUserRequest request) {
    User user = User.create(request.name(), request.email());
    User saved = userRepository.save(user);
    return UserResponse.from(saved);
}

// Query (읽기) - DTO Projection 사용
@Transactional(readOnly = true)
public List<UserSummary> searchUsers(String keyword) {
    return userRepository.findByNameContaining(keyword);
}
```

---

### Best Practice 체크리스트

```java
// ✅ 좋은 예
@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping("/users/{id}")
    public UserResponse getUser(@PathVariable Long id) {
        return userService.findUserById(id);  // DTO 반환
    }
}

@Service
@Transactional(readOnly = true)
public class UserService {
    public UserResponse findUserById(Long id) {
        User user = userRepository.findById(id).orElseThrow();
        return UserResponse.from(user);  // 트랜잭션 내 변환
    }
}
```

```java
// ❌ 나쁜 예
@GetMapping("/users/{id}")
public User getUser(@PathVariable Long id) {
    return userRepository.findById(id).orElseThrow();  // Entity 직접 반환
    // 문제: 순환 참조, 민감 정보 노출, API 종속
}
```

## 핵심 정리

- **Controller에서 Entity 직접 반환은 5가지 치명적 문제 유발**: 순환 참조, LazyInitializationException, 민감 정보 노출, API 종속, N+1 악화
- **DTO는 관심사 분리의 핵심**: API 스펙과 도메인 모델을 독립적으로 관리 가능
- **Java Record는 2025년 DTO 표준**: 불변, 간결, 보일러플레이트 제거
- **MapStruct는 대규모 프로젝트 필수**: 컴파일 타임 안전성, 자동 매핑
- **Spring Data Projection은 성능 최적화**: 필요한 컬럼만 SELECT하여 네트워크/DB 부하 감소
- **CQRS 패턴**: 쓰기(Entity + Dirty Checking), 읽기(DTO Projection)로 분리
- **프로덕션에서는 DTO 필수, POC는 Entity 허용**: 프로젝트 규모에 따라 유연하게 적용

## 키워드

- **DTO (Data Transfer Object)**: Martin Fowler가 제안한 패턴으로, 계층 간 데이터 전송을 위한 전용 객체. API 스펙과 도메인 모델을 분리
- **Entity 직접 반환**: Controller에서 JPA Entity를 그대로 JSON으로 반환하는 안티패턴. 순환 참조, 민감 정보 노출, API 종속 문제 유발
- **API 스펙 분리**: DTO를 사용하여 API 계약과 DB 스키마를 독립적으로 관리하는 설계 원칙
- **순환 참조 (Circular Reference)**: 양방향 연관관계에서 A→B→A 무한 루프로 인한 StackOverflowError 발생
- **LazyInitializationException**: 트랜잭션 종료 후 Lazy Loading 프록시 접근 시 발생하는 Hibernate 예외 (OSIV 비활성화 시 주의)
- **MapStruct**: 컴파일 타임에 매핑 코드를 자동 생성하는 DTO 변환 라이브러리. 리플렉션 없이 타입 안전하게 동작
- **Record (Java 16+)**: 불변 데이터 클래스를 간결하게 정의하는 Java 언어 기능. equals/hashCode/toString 자동 생성
- **Open Session in View (OSIV)**: EntityManager를 HTTP 요청 전체 기간 동안 유지하는 패턴. 커넥션 고갈과 N+1 문제 유발 (프로덕션에서 비활성화 권장)
- **Projection**: Spring Data JPA에서 엔티티의 일부 필드만 조회하는 기능. Interface-based, Class-based, Dynamic 3가지 타입
- **CQRS (Command Query Responsibility Segregation)**: 쓰기(Command)와 읽기(Query)를 분리하는 패턴. Entity는 쓰기, DTO는 읽기에 최적화

## 참고 자료

### 관련 TIL 문서
- **[Spring Boot OSIV와 Projection/DTO 패턴](./260217_03_Spring_Boot_OSIV와_Projection_DTO_패턴.md)** - OSIV, LazyInitializationException, Projection 상세 설명

### 외부 자료
- [What Is a DTO? - Igor Venturelli](https://igventurelli.io/what-is-a-dto-and-why-you-shouldnt-return-your-entities-in-spring-boot/)
- [The Art of the DTO: Mastering Data Transfer in Modern Spring Boot - Medium](https://balkrishan-nagpal.medium.com/the-art-of-the-dto-mastering-data-transfer-in-modern-spring-boot-20887d33fd8a)
- [Avoiding Circular References in Spring Data JPA - Medium](https://medium.com/@raksmey_koung/avoiding-circular-references-or-cycles-spring-data-jpa-35ca137ad3cb)
- [Mapping JPA Entities into DTOs with MapStruct - Auth0](https://auth0.com/blog/how-to-automatically-map-jpa-entities-into-dtos-in-spring-boot-using-mapstruct/)
- [Create DTOs with Java Records & MapStruct - TechWasti](https://techwasti.com/effortlessly-create-dtos-with-java-records-and-mapstruct-in-spring-boot)
- [Spring Data JPA Projections - Baeldung](https://www.baeldung.com/spring-data-jpa-projections)
- [A Guide to Spring's Open Session In View - Baeldung](https://www.baeldung.com/spring-open-session-in-view)
- [Spring Boot Best Practice - Disable OSIV - Code Soapbox](https://codesoapbox.dev/spring-boot-best-practice-disable-osiv-to-start-receiving-lazyinitializationexception-warnings-again/)
- [The best way to fetch a Spring Data JPA DTO Projection - Vlad Mihalcea](https://vladmihalcea.com/spring-jpa-dto-projection/)
- [Understanding DTOs in Spring Boot - Medium](https://medium.com/@roshanfarakate/understanding-dtos-in-spring-boot-a-comprehensive-guide-20e2b8101ee6)
