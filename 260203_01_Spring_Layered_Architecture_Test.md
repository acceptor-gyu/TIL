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

### Mocking과 Stubbing

테스트에서 가장 자주 사용되는 두 가지 핵심 기법인 Mocking과 Stubbing을 이해하는 것이 중요합니다.

#### Mocking이란?

**Mocking**은 실제 객체를 대신하는 가짜 객체(Mock Object)를 생성하는 기법입니다. Mock 객체는 실제 구현 없이 동작을 시뮬레이션하며, 호출 여부와 호출 방식을 검증할 수 있습니다.

**언제 사용하나?**
- 외부 의존성(DB, API, 파일 시스템)을 제거하고 빠른 테스트 실행이 필요할 때
- 특정 레이어를 독립적으로 테스트하고 싶을 때
- 아직 구현되지 않은 컴포넌트와 상호작용하는 코드를 테스트할 때
- 에러 상황이나 특수한 시나리오를 시뮬레이션할 때

**왜 사용하나?**
- **빠른 테스트**: DB 연결이나 네트워크 호출 없이 즉시 실행
- **테스트 격리**: 다른 컴포넌트의 영향 없이 순수한 로직만 검증
- **예측 가능성**: 항상 동일한 결과를 반환하여 일관된 테스트 가능
- **제어 가능성**: 실제로는 재현하기 어려운 예외 상황도 쉽게 테스트

**어떻게 사용하나?**

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;  // Mock 객체 생성

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private OrderService orderService;  // Mock들이 자동으로 주입됨

    @Test
    void createOrder_ValidOrder_Success() {
        // given: Mock의 동작 정의 (Stubbing)
        Order order = new Order(1L, 10000);
        given(orderRepository.save(any(Order.class)))
            .willReturn(order);
        given(paymentService.processPayment(anyLong()))
            .willReturn(true);

        // when: 테스트 대상 메서드 실행
        Order result = orderService.createOrder(order);

        // then: 결과 검증 + Mock 호출 검증
        assertThat(result.getId()).isEqualTo(1L);
        verify(orderRepository).save(any(Order.class));  // 호출 여부 검증
        verify(paymentService).processPayment(10000L);   // 특정 인자로 호출되었는지 검증
    }
}
```

#### Stubbing이란?

**Stubbing**은 Mock 객체의 메서드가 호출될 때 어떤 값을 반환할지 미리 정의하는 것입니다. "이 메서드가 호출되면 이 값을 반환해라"라고 설정하는 과정입니다.

**언제 사용하나?**
- Mock 객체가 특정 값을 반환해야 할 때
- 메서드 호출에 따른 다양한 시나리오를 테스트할 때
- 예외를 던지는 상황을 시뮬레이션할 때

**왜 사용하나?**
- **시나리오 제어**: 성공/실패 등 다양한 상황을 쉽게 재현
- **테스트 데이터 준비 간소화**: 복잡한 객체 생성 과정을 단순화
- **경계 케이스 테스트**: null, 빈 리스트, 예외 등 특수 상황 검증

**어떻게 사용하나?**

```java
@Test
void 다양한_Stubbing_기법() {
    // 1. 단순 값 반환
    given(userRepository.findById(1L))
        .willReturn(Optional.of(new User("홍길동")));

    // 2. 예외 던지기
    given(userRepository.findById(999L))
        .willThrow(new UserNotFoundException("사용자 없음"));

    // 3. 여러 번 호출 시 다른 값 반환
    given(externalApi.getData())
        .willReturn("첫번째")
        .willReturn("두번째")
        .willReturn("세번째");

    // 4. 조건에 따른 다른 반환값
    given(userRepository.findById(anyLong()))
        .willAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (id > 100) throw new IllegalArgumentException();
            return Optional.of(new User("User" + id));
        });

    // 5. void 메서드에 예외 설정
    willThrow(new IOException("파일 없음"))
        .given(fileService).deleteFile(anyString());

    // 6. 아무것도 하지 않기 (기본 동작)
    willDoNothing()
        .given(emailService).sendEmail(any());
}
```

#### Mocking vs Stubbing 비교

| 구분 | Mocking | Stubbing |
|-----|---------|----------|
| **정의** | 가짜 객체 생성 | Mock 객체의 동작 정의 |
| **목적** | 의존성 대체 + 호출 검증 | 특정 값 반환 설정 |
| **주요 메서드** | `@Mock`, `mock()`, `verify()` | `given()`, `when()`, `willReturn()` |
| **사용 시점** | 테스트 대상이 의존하는 객체 | Mock 객체의 반환값 필요 시 |
| **검증** | 메서드 호출 여부/횟수 검증 | 반환값 기반 결과 검증 |

#### 실무 사용 예시

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private UserService userService;

    @Test
    void 회원가입_성공_시_이메일_발송() {
        // given: Stubbing으로 Mock의 동작 정의
        User newUser = new User("test@example.com", "password123");
        given(userRepository.existsByEmail("test@example.com"))
            .willReturn(false);  // 중복 이메일 없음
        given(userRepository.save(any(User.class)))
            .willReturn(newUser);

        // when
        userService.registerUser(newUser);

        // then: Mocking으로 호출 검증
        verify(userRepository).existsByEmail("test@example.com");  // 중복 검사 호출 확인
        verify(userRepository).save(any(User.class));              // 저장 호출 확인
        verify(emailService).sendWelcomeEmail("test@example.com"); // 이메일 발송 확인
    }

    @Test
    void 중복_이메일_회원가입_실패() {
        // given
        given(userRepository.existsByEmail("duplicate@example.com"))
            .willReturn(true);  // Stubbing: 이미 존재하는 이메일

        // when & then
        assertThatThrownBy(() ->
            userService.registerUser(new User("duplicate@example.com", "pwd"))
        ).isInstanceOf(DuplicateEmailException.class);

        // Mocking: save가 호출되지 않았는지 검증
        verify(userRepository, never()).save(any());
        verify(emailService, never()).sendWelcomeEmail(anyString());
    }
}
```

#### 주요 Mockito 어노테이션

```java
// 1. @Mock - Mock 객체 생성
@Mock
private UserRepository userRepository;

// 2. @InjectMocks - Mock 객체들을 자동으로 주입받는 테스트 대상
@InjectMocks
private UserService userService;

// 3. @Spy - 실제 객체를 부분적으로 Mocking (일부 메서드만 Stubbing)
@Spy
private UserValidator userValidator;

// 4. @Captor - 메서드 인자를 캡처하여 검증
@Captor
private ArgumentCaptor<User> userCaptor;

@Test
void 인자_캡처_예시() {
    userService.createUser(new User("홍길동"));

    verify(userRepository).save(userCaptor.capture());
    User captured = userCaptor.getValue();
    assertThat(captured.getName()).isEqualTo("홍길동");
}
```

#### BDD 스타일 vs 전통적 스타일

```java
// BDD 스타일 (권장) - 가독성이 좋음
given(userRepository.findById(1L))
    .willReturn(Optional.of(user));
verify(userRepository).findById(1L);

// 전통적 스타일
when(userRepository.findById(1L))
    .thenReturn(Optional.of(user));
verify(userRepository).findById(1L);
```

#### Mock 사용 시 주의사항

1. **Over-Mocking 주의**: Mock을 너무 많이 사용하면 실제 동작과 괴리가 발생할 수 있습니다.
   ```java
   // ❌ 너무 많은 Mock
   @Mock private ServiceA serviceA;
   @Mock private ServiceB serviceB;
   @Mock private ServiceC serviceC;
   @Mock private ServiceD serviceD;
   // ... 실제 통합 테스트가 필요할 수 있음
   ```

2. **구현 세부사항 검증 지양**: "무엇을 하는지"보다 "어떻게 하는지"를 검증하면 리팩토링 시 테스트가 깨집니다.
   ```java
   // ❌ 구현 세부사항에 의존
   verify(userRepository).findById(1L);
   verify(userMapper).toDto(any());
   verify(cache).put(anyString(), any());

   // ✅ 결과에 집중
   assertThat(result.getName()).isEqualTo("홍길동");
   ```

3. **final 클래스/메서드는 Mocking 불가**: Mockito는 기본적으로 final을 Mock할 수 없습니다 (mockito-inline 사용 시 가능).

4. **static 메서드 Mocking**: 특별한 설정 필요 (MockedStatic 사용).

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
- **Mocking**: 실제 객체를 대신하는 가짜 객체 생성, 호출 여부 검증 (`@Mock`, `verify()`)
- **Stubbing**: Mock 객체의 반환값 정의, 다양한 시나리오 시뮬레이션 (`given()`, `willReturn()`)
- Mocking과 Stubbing은 테스트 격리와 빠른 실행을 위해 필수적이며, 적절히 사용해야 실제 동작과의 괴리 방지
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
