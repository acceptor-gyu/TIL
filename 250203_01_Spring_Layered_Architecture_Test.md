# Spring Layered Architecture Test

오늘은 인프라 접근 권한이 극단적으로 제약되어 있어 테스트 코드로 기능을 검증하기 위해 테스트에 대해서 학습했습니다.

## 개요
Spring의 계층형 아키텍처(Layered Architecture)에서 각 레이어를 효과적으로 테스트하는 방법을 정리합니다.

## 상세 내용

### Spring 계층형 아키텍처 구조

```
┌─────────────────────────────────┐
│      Presentation Layer         │  ← Controller
├─────────────────────────────────┤
│        Business Layer           │  ← Service
├─────────────────────────────────┤
│       Persistence Layer         │  ← Repository
├─────────────────────────────────┤
│        Database Layer           │  ← DB
└─────────────────────────────────┘
```

### 레이어별 테스트 전략

#### 1. Controller Layer 테스트
`@WebMvcTest`를 사용하여 웹 레이어만 슬라이스 테스트

```java
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    void getUser_ReturnsUser() throws Exception {
        // given
        given(userService.findById(1L))
            .willReturn(new UserResponse(1L, "홍길동"));

        // when & then
        mockMvc.perform(get("/api/users/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("홍길동"));
    }
}
```

#### 2. Service Layer 테스트
순수 단위 테스트로 비즈니스 로직 검증

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository u거serRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void findById_ExistingUser_ReturnsUser() {
        // given
        User user = new User(1L, "홍길동");
        given(userRepository.findById(1L))
            .willReturn(Optional.of(user));

        // when
        UserResponse result = userService.findById(1L);

        // then
        assertThat(result.getName()).isEqualTo("홍길동");
    }
}
```

#### 3. Repository Layer 테스트
`@DataJpaTest`를 사용하여 JPA 관련 컴포넌트만 로드

```java
@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByEmail_ExistingEmail_ReturnsUser() {
        // given
        User user = new User("홍길동", "hong@example.com");
        entityManager.persistAndFlush(user);

        // when
        Optional<User> found = userRepository.findByEmail("hong@example.com");

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("홍길동");
    }
}
```

### 주요 테스트 어노테이션 비교

| 어노테이션 | 용도 | 로드되는 컴포넌트 |
|-----------|------|------------------|
| `@SpringBootTest` | 통합 테스트 | 모든 Bean |
| `@WebMvcTest` | Controller 테스트 | Web 관련 Bean |
| `@DataJpaTest` | Repository 테스트 | JPA 관련 Bean |
| `@MockBean` | Mock 객체 주입 | - |

### 테스트 피라미드

```
        /\
       /  \      E2E Tests (적은 수)
      /────\
     /      \    Integration Tests
    /────────\
   /          \  Unit Tests (많은 수)
  /────────────\
```

- **Unit Tests**: 빠르고 격리된 테스트, 가장 많이 작성
- **Integration Tests**: 레이어 간 통합 검증
- **E2E Tests**: 전체 시스템 동작 검증, 가장 적게 작성

---

## 실무 적용 가이드

### 1. TestContainers로 실제 DB 환경 테스트

H2 내장 DB는 MySQL/PostgreSQL과 문법 차이로 인한 문제 발생 가능. TestContainers로 실제 DB 환경 구성.

```java
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Test
    void nativeQuery_WorksWithRealMySQL() {
        // MySQL 전용 문법 테스트 가능
    }
}
```

**TestContainers 싱글톤 패턴** - 테스트 속도 개선

```java
public abstract class IntegrationTestSupport {

    static final MySQLContainer<?> MYSQL;

    static {
        MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withReuse(true);  // 컨테이너 재사용
        MYSQL.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
```

### 2. 테스트 격리 전략

#### 문제: 테스트 간 데이터 오염

```java
// ❌ 잘못된 예 - 테스트 순서에 따라 실패 가능
@Test void test1() { userRepository.save(new User("김철수")); }
@Test void test2() { assertThat(userRepository.count()).isZero(); } // 실패!
```

#### 해결 방법 1: @Transactional 롤백

```java
@DataJpaTest  // 자동으로 @Transactional 포함
class UserRepositoryTest {
    // 각 테스트 후 자동 롤백
}
```

#### 해결 방법 2: @Sql로 명시적 초기화

```java
@Test
@Sql(scripts = "/cleanup.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/test-data.sql", executionPhase = BEFORE_TEST_METHOD)
void findAllActiveUsers() {
    List<User> users = userRepository.findAllByStatus(ACTIVE);
    assertThat(users).hasSize(3);
}
```

#### 해결 방법 3: DatabaseCleaner 유틸리티

```java
@Component
public class DatabaseCleaner {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void clear() {
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();

        em.getMetamodel().getEntities().stream()
            .map(e -> e.getName())
            .forEach(table ->
                em.createNativeQuery("TRUNCATE TABLE " + table).executeUpdate()
            );

        em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
    }
}

// 테스트에서 사용
@BeforeEach
void setUp() {
    databaseCleaner.clear();
}
```

### 3. @MockBean 컨텍스트 캐싱 문제

`@MockBean` 사용 시 새로운 ApplicationContext 생성 → 테스트 속도 저하

```java
// ❌ 각각 다른 컨텍스트 생성
@WebMvcTest class UserControllerTest { @MockBean UserService userService; }
@WebMvcTest class OrderControllerTest { @MockBean OrderService orderService; }

// ✅ 공통 설정으로 컨텍스트 재사용
@WebMvcTest
@Import(MockBeanConfig.class)
abstract class ControllerTestSupport {
    @Autowired protected MockMvc mockMvc;
    @Autowired protected UserService userService;
    @Autowired protected OrderService orderService;
}

@TestConfiguration
class MockBeanConfig {
    @Bean UserService userService() { return mock(UserService.class); }
    @Bean OrderService orderService() { return mock(OrderService.class); }
}
```

### 4. 슬라이스 테스트 확장

#### 추가 Bean 로드가 필요한 경우

```java
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class})  // 필요한 설정 추가
class UserControllerTest {
    // Security 설정이 적용된 테스트
}
```

#### Custom Slice 어노테이션 만들기

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)  // Security 필터 비활성화 옵션
public @interface SliceTest {
    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] controllers() default {};
}

// 사용
@SliceTest(controllers = UserController.class)
class UserControllerTest { }
```

### 5. 인수 테스트 (Acceptance Test)

RestAssured를 활용한 API 인수 테스트

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UserAcceptanceTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void 회원가입_후_로그인_시나리오() {
        // 1. 회원가입
        ExtractableResponse<Response> signupResponse = RestAssured
            .given().log().all()
                .contentType(ContentType.JSON)
                .body(Map.of(
                    "email", "test@example.com",
                    "password", "password123"
                ))
            .when()
                .post("/api/users/signup")
            .then().log().all()
                .statusCode(201)
                .extract();

        // 2. 로그인
        ExtractableResponse<Response> loginResponse = RestAssured
            .given().log().all()
                .contentType(ContentType.JSON)
                .body(Map.of(
                    "email", "test@example.com",
                    "password", "password123"
                ))
            .when()
                .post("/api/auth/login")
            .then().log().all()
                .statusCode(200)
                .extract();

        String accessToken = loginResponse.jsonPath().getString("accessToken");
        assertThat(accessToken).isNotBlank();
    }
}
```

### 6. 테스트 픽스처 관리

#### Builder 패턴 활용

```java
public class UserFixture {

    public static User createUser() {
        return User.builder()
            .name("홍길동")
            .email("hong@example.com")
            .status(UserStatus.ACTIVE)
            .build();
    }

    public static User createUser(String email) {
        return User.builder()
            .name("홍길동")
            .email(email)
            .status(UserStatus.ACTIVE)
            .build();
    }

    public static UserCreateRequest createUserRequest() {
        return new UserCreateRequest("홍길동", "hong@example.com", "password123");
    }
}

// 테스트에서 사용
@Test
void createUser_ValidRequest_ReturnsCreated() {
    UserCreateRequest request = UserFixture.createUserRequest();
    // ...
}
```

### 7. 테스트 성능 최적화

#### 병렬 테스트 실행

```properties
# junit-platform.properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.mode.classes.default=concurrent
```

#### 느린 테스트 식별

```java
@ExtendWith(TimingExtension.class)
class SlowTestDetector implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final long SLOW_TEST_THRESHOLD_MS = 1000;

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        context.getStore(GLOBAL).put("start", System.currentTimeMillis());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        long start = context.getStore(GLOBAL).get("start", Long.class);
        long duration = System.currentTimeMillis() - start;

        if (duration > SLOW_TEST_THRESHOLD_MS) {
            System.out.printf("⚠️ SLOW TEST: %s took %dms%n",
                context.getDisplayName(), duration);
        }
    }
}
```

### 8. 실무에서 자주 하는 실수

| 실수 | 문제점 | 해결책 |
|-----|-------|-------|
| `@SpringBootTest` 남용 | 느린 테스트, 긴 피드백 루프 | 슬라이스 테스트 활용 |
| `@Transactional` + API 호출 | 트랜잭션 경계 불일치 | 별도 스레드에서 검증 |
| Mock 과다 사용 | 실제 동작과 괴리 | Fake 객체나 통합테스트 병행 |
| 테스트 간 의존성 | 순서 의존적 실패 | 독립적인 테스트 작성 |
| 랜덤 포트 미사용 | 포트 충돌 | `RANDOM_PORT` 사용 |

### 9. 추천 테스트 구조

```
src/test/java
├── acceptance/          # 인수 테스트 (E2E)
│   └── UserAcceptanceTest.java
├── integration/         # 통합 테스트
│   └── UserServiceIntegrationTest.java
├── unit/               # 단위 테스트
│   ├── controller/
│   ├── service/
│   └── domain/
├── fixture/            # 테스트 데이터
│   └── UserFixture.java
└── support/            # 공통 설정
    ├── IntegrationTestSupport.java
    ├── ControllerTestSupport.java
    └── DatabaseCleaner.java
```

## 핵심 정리
- `@WebMvcTest`: Controller 레이어 슬라이스 테스트, Service는 `@MockBean`으로 모킹
- `@DataJpaTest`: Repository 레이어 슬라이스 테스트, 내장 DB 자동 구성
- `@SpringBootTest`: 전체 컨텍스트 로드, 통합 테스트용
- 테스트 피라미드 원칙: Unit > Integration > E2E 순으로 테스트 수 유지
- 각 레이어는 독립적으로 테스트하여 빠른 피드백 확보
- **TestContainers**: 실제 DB 환경 테스트로 H2와의 호환성 문제 해결
- **테스트 격리**: `@Transactional`, `@Sql`, DatabaseCleaner 활용
- **컨텍스트 캐싱**: `@MockBean` 최소화, 공통 설정 클래스로 재사용
- **테스트 픽스처**: Builder 패턴으로 일관된 테스트 데이터 생성
- **인수 테스트**: RestAssured로 사용자 시나리오 기반 E2E 테스트

## 참고 자료
- [Spring Boot Testing - 공식 문서](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [TestContainers - 공식 문서](https://www.testcontainers.org/)
- [RestAssured - 공식 문서](https://rest-assured.io/)
- [Practical Unit Testing - Baeldung](https://www.baeldung.com/java-unit-testing-best-practices)
