# @Transactional 동작 원리 (feat. 프록시)

## 개요
Spring의 `@Transactional`이 어떻게 선언적으로 트랜잭션을 처리하는지, 그 핵심인 프록시(Proxy) 기반 AOP 동작 원리를 정리한다.

프록시 자체(CGLIB)는 [CGLIB 프록시](260619_02_CGLIB_프록시.md), propagation 옵션은 [@Transactional propagation 옵션과 CGLIB 프록시](260621_02_@Transactional_propagation_옵션과_CGLIB_프록시.md)에서 다루므로, 이 문서는 **`@Transactional`이 프록시를 통해 어떻게 동작하는지의 내부 동작 흐름**에 집중한다.

## 상세 내용

### 1. 선언적 트랜잭션이란

#### 명령적(Programmatic) 트랜잭션 vs 선언적(Declarative) 트랜잭션

트랜잭션을 관리하는 방법은 두 가지가 있다.

**명령적 트랜잭션**: 개발자가 코드에서 직접 트랜잭션 경계를 제어한다.

```java
// 방법 1: PlatformTransactionManager 직접 사용
@Service
@RequiredArgsConstructor
public class OrderService {

    private final PlatformTransactionManager transactionManager;
    private final OrderRepository orderRepository;

    public void placeOrder(OrderRequest request) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            orderRepository.save(request.toEntity());
            transactionManager.commit(status);
        } catch (RuntimeException e) {
            transactionManager.rollback(status);
            throw e;
        }
    }
}

// 방법 2: TransactionTemplate 사용 (명령적 방식 중 권장)
@Service
@RequiredArgsConstructor
public class OrderService {

    private final TransactionTemplate transactionTemplate;
    private final OrderRepository orderRepository;

    public void placeOrder(OrderRequest request) {
        transactionTemplate.execute(status -> {
            orderRepository.save(request.toEntity());
            return null;
        });
    }
}
```

**선언적 트랜잭션**: `@Transactional` 어노테이션으로 Spring이 트랜잭션을 자동으로 관리한다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    @Transactional
    public void placeOrder(OrderRequest request) {
        orderRepository.save(request.toEntity());
        // 트랜잭션 시작, 커밋, 롤백은 Spring이 처리
    }
}
```

| 구분 | 명령적 트랜잭션 | 선언적 트랜잭션 |
|------|----------------|----------------|
| 트랜잭션 경계 | 코드에 명시 | 어노테이션으로 선언 |
| 비즈니스 코드와 트랜잭션 코드 분리 | X | O (AOP) |
| 메서드 단위 이하 세밀한 제어 | 가능 | 어려움 |
| 가독성 | 낮음 | 높음 |
| 테스트 용이성 | 낮음 | 높음 |

#### @Transactional이 제공하는 추상화

`@Transactional` 하나가 다음 모든 것을 추상화한다.

- **DB 종류 추상화**: JDBC, JPA, Hibernate 등 구현체에 무관한 트랜잭션 처리
- **예외 변환**: SQL 예외를 Spring의 DataAccessException 계층으로 변환
- **커넥션 관리**: 커넥션 획득, 반환, 스레드 바인딩
- **트랜잭션 전파**: propagation 옵션으로 복잡한 중첩 트랜잭션 관계 처리

---

### 2. 프록시 기반 AOP 구조

#### 프록시가 생성되는 시점

애플리케이션 구동 시 Spring이 `@Transactional` 대상 빈을 프록시로 교체하는 흐름은 다음과 같다.

```
애플리케이션 구동
  → 컴포넌트 스캔으로 빈 후보 탐색 및 BeanDefinition 등록
  → BeanPostProcessor 동작
      └── InfrastructureAdvisorAutoProxyCreator 실행
              → @Transactional 어노테이션이 있는 빈 탐지
              → TransactionAttributeSource로 @Transactional 메타데이터 파싱
              → CGLIB(또는 JDK Dynamic Proxy)로 프록시 클래스 생성
              → 실제 빈 대신 프록시를 ApplicationContext에 등록
```

`@EnableTransactionManagement`를 선언하면(Spring Boot는 자동 설정) 위 인프라 빈들이 활성화된다.

```java
@Configuration
@EnableTransactionManagement // 이 선언으로 트랜잭션 관련 BeanPostProcessor 등록
public class AppConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
```

#### 트랜잭션 어드바이스 구성 요소

프록시 내부에는 세 구성 요소가 협력한다.

| 구성 요소 | 역할 |
|-----------|------|
| `AnnotationTransactionAttributeSource` | `@Transactional` 속성(propagation, isolation, readOnly, timeout, rollbackFor 등) 파싱 |
| `BeanFactoryTransactionAttributeSourceAdvisor` | 어느 메서드에 트랜잭션 어드바이스를 적용할지 결정하는 Advisor(Pointcut + Advice) |
| `TransactionInterceptor` | 실제 트랜잭션 처리 로직을 수행하는 `MethodInterceptor` |

#### TransactionInterceptor의 역할

`TransactionInterceptor`는 `MethodInterceptor`를 구현한 클래스다. 프록시 메서드가 호출되면 `TransactionInterceptor.invoke()`가 실행되며, 내부에서 `invokeWithinTransaction()`을 호출한다.

```
TransactionInterceptor.invoke()
    │
    └── invokeWithinTransaction()
            │
            ├── 1. @Transactional 속성 조회 (TransactionAttribute)
            ├── 2. 사용할 PlatformTransactionManager 결정
            ├── 3. createTransactionIfNecessary() → txManager.getTransaction()
            ├── 4. 실제 메서드 실행 (invocation.proceed())
            ├── 5. 정상 완료 → commitTransactionAfterReturning()
            └── 6. 예외 발생 → completeTransactionAfterThrowing()
```

---

### 3. 트랜잭션 매니저와 동기화

#### PlatformTransactionManager 계층 구조

`PlatformTransactionManager`는 Spring 트랜잭션 추상화의 핵심 인터페이스다.

```
PlatformTransactionManager (인터페이스)
│  - getTransaction(TransactionDefinition): TransactionStatus
│  - commit(TransactionStatus)
│  - rollback(TransactionStatus)
│
└── AbstractPlatformTransactionManager (추상 클래스)
        │  표준 트랜잭션 워크플로우 구현
        │  (getTransaction, commit, rollback의 공통 흐름 처리)
        │
        ├── DataSourceTransactionManager  ← JDBC/MyBatis 환경
        ├── JpaTransactionManager         ← JPA (Hibernate, EclipseLink) 환경
        ├── HibernateTransactionManager   ← Hibernate 직접 사용 환경
        └── JtaTransactionManager         ← 분산 트랜잭션(XA) 환경
```

`AbstractPlatformTransactionManager`가 공통 흐름(전파 처리, suspend/resume, 동기화)을 구현하고, 구체 클래스는 DB별 실제 커넥션 처리(`doBegin`, `doCommit`, `doRollback`)만 구현한다.

**PlatformTransactionManager의 세 핵심 메서드**

```java
public interface PlatformTransactionManager {

    // 현재 스레드에 활성 트랜잭션이 있으면 참여하거나, propagation 옵션에 따라 새로 시작
    TransactionStatus getTransaction(TransactionDefinition definition);

    // 트랜잭션 커밋. status.isRollbackOnly()가 true이면 커밋 대신 롤백 수행
    void commit(TransactionStatus status);

    // 트랜잭션 롤백
    void rollback(TransactionStatus status);
}
```

#### DataSourceTransactionManager의 doBegin 내부 동작

`getTransaction()`이 새 트랜잭션을 시작해야 한다고 판단하면 `doBegin()`을 호출한다.

```
DataSourceTransactionManager.doBegin()
    │
    ├── DataSource.getConnection() → 커넥션 풀에서 Connection 획득
    ├── connection.setAutoCommit(false) → 자동 커밋 비활성화
    ├── connection.setTransactionIsolation(level) → 격리 수준 설정
    ├── ConnectionHolder 생성 (Connection 래퍼)
    └── TransactionSynchronizationManager.bindResource(dataSource, connectionHolder)
             → 현재 스레드의 ThreadLocal에 [DataSource → ConnectionHolder] 바인딩
```

#### TransactionSynchronizationManager와 ThreadLocal 기반 커넥션 바인딩

`TransactionSynchronizationManager`는 트랜잭션 컨텍스트를 ThreadLocal로 관리하는 중앙 레지스트리다.

```java
public abstract class TransactionSynchronizationManager {

    // [DataSource → ConnectionHolder] 맵 - 트랜잭션 리소스 저장
    private static final ThreadLocal<Map<Object, Object>> resources =
        new NamedThreadLocal<>("Transactional resources");

    // 트랜잭션 이벤트 리스너 (AFTER_COMMIT 등)
    private static final ThreadLocal<Set<TransactionSynchronization>> synchronizations =
        new NamedThreadLocal<>("Transaction synchronizations");

    // 현재 트랜잭션 이름 (@Transactional의 transactionManager 속성 등)
    private static final ThreadLocal<String> currentTransactionName = ...;

    // 읽기 전용 여부 (@Transactional(readOnly = true))
    private static final ThreadLocal<Boolean> currentTransactionReadOnly = ...;

    // 격리 수준
    private static final ThreadLocal<Integer> currentTransactionIsolationLevel = ...;

    // 실제 트랜잭션이 활성화되어 있는지 (non-transactional 실행과 구분)
    private static final ThreadLocal<Boolean> actualTransactionActive = ...;
}
```

이 ThreadLocal 구조 덕분에 **같은 스레드 내의 모든 DB 접근이 동일한 커넥션을 재사용**한다.

```
스레드 A: OrderService.placeOrder() 실행
    │
    ├── TransactionSynchronizationManager.bindResource(ds, conn_A)
    │
    ├── OrderRepository.save()
    │       └── JdbcTemplate이 TransactionSynchronizationManager에서 conn_A 획득
    │
    ├── InventoryRepository.decrease()
    │       └── JdbcTemplate이 TransactionSynchronizationManager에서 conn_A 획득 (동일)
    │
    └── 커밋 후 TransactionSynchronizationManager.unbindResource(ds)
             → conn_A를 커넥션 풀에 반환

스레드 B: 다른 요청 처리 중
    └── TransactionSynchronizationManager에서 스레드 B만의 conn_B 보유 (독립)
```

---

### 4. 동작 흐름

#### 전체 콜체인 다이어그램

```
클라이언트 (e.g. Controller)
    │
    │  orderService.placeOrder(request) 호출
    ▼
OrderService$$SpringCGLIB$$0 (CGLIB 프록시 빈)
    │
    │  MethodInterceptor 체인 실행
    ▼
TransactionInterceptor.invoke(MethodInvocation)
    │
    ├── 1. @Transactional 속성 파싱
    │       AnnotationTransactionAttributeSource.getTransactionAttribute()
    │       → propagation=REQUIRED, isolation=DEFAULT, readOnly=false, rollbackFor={RuntimeException, Error}
    │
    ├── 2. PlatformTransactionManager 결정
    │       → 컨텍스트에서 JpaTransactionManager (또는 DataSourceTransactionManager) 조회
    │
    ├── 3. 트랜잭션 시작: txManager.getTransaction(txAttr)
    │       │
    │       ├── (현재 스레드에 트랜잭션 없는 경우)
    │       │       AbstractPlatformTransactionManager.startTransaction()
    │       │           → doBegin(): DataSource.getConnection()
    │       │           → connection.setAutoCommit(false)
    │       │           → TransactionSynchronizationManager.bindResource(ds, connHolder)
    │       │           → TransactionSynchronizationManager.setActualTransactionActive(true)
    │       │           → TransactionStatus 반환
    │       │
    │       └── (현재 스레드에 트랜잭션 있는 경우)
    │               propagation에 따라 기존 참여 / suspend + 새 트랜잭션 / 예외 등 처리
    │
    ├── 4. 실제 메서드 실행: invocation.proceed()
    │       OrderService.placeOrder() 내부 실행
    │       └── JPA/JDBC: TransactionSynchronizationManager에서 동일 Connection 획득하여 DB 작업
    │
    ├── 5. 정상 완료 → commitTransactionAfterReturning()
    │       txManager.commit(status)
    │           │
    │           ├── status.isRollbackOnly() = true → doRollback() 후 UnexpectedRollbackException
    │           ├── status.isNewTransaction() = true → doCommit()
    │           │       → connection.commit()
    │           └── status.isNewTransaction() = false → 커밋 생략 (외부 트랜잭션이 커밋)
    │
    └── 6. 예외 발생 → completeTransactionAfterThrowing(txInfo, ex)
            │
            ├── rollbackFor/noRollbackFor 규칙으로 롤백 여부 판단
            │       → RuntimeException, Error: rollback
            │       → Checked Exception: 기본적으로 commit (rollbackFor로 변경 가능)
            │
            ├── 롤백 결정 시: txManager.rollback(status)
            │       → status.isNewTransaction() = true → doRollback()
            │               → connection.rollback()
            │       → status.isNewTransaction() = false → doSetRollbackOnly()
            │               → 외부 트랜잭션에 rollbackOnly 마크
            │
            └── 트랜잭션 정리
                    TransactionSynchronizationManager.unbindResource(ds)
                    TransactionSynchronizationManager.setActualTransactionActive(false)
                    connection.close() (커넥션 풀에 반환)
```

#### 롤백 정책

`@Transactional`의 기본 롤백 정책은 **Unchecked Exception(RuntimeException, Error)에만 롤백**이다.

```java
// 기본 동작
@Transactional
public void process() throws IOException {
    repository.save(entity);   // DB 작업
    Files.readAllBytes(path);  // IOException(Checked) 발생 → 기본값은 커밋
}

// Checked Exception도 롤백하려면 rollbackFor 명시
@Transactional(rollbackFor = IOException.class)
public void process() throws IOException {
    repository.save(entity);
    Files.readAllBytes(path);  // IOException 발생 → 롤백
}

// 특정 예외는 롤백에서 제외
@Transactional(noRollbackFor = EntityNotFoundException.class)
public void process(Long id) {
    // EntityNotFoundException 발생해도 롤백하지 않음
}
```

이 설계의 배경: Checked Exception은 일반적으로 "비즈니스 예외"로 예측 가능한 상황을 나타내며, 반드시 복구 로직이 있어야 한다는 철학에서 기인한다. Unchecked Exception은 예측하지 못한 프로그래밍 오류이므로 트랜잭션을 롤백하는 것이 더 안전하다.

#### UnexpectedRollbackException이 발생하는 상황

REQUIRED 전파로 외부-내부 트랜잭션이 같은 물리 트랜잭션을 공유할 때, 내부에서 rollbackOnly 마크가 설정된 상태에서 외부에서 commit을 시도하면 `UnexpectedRollbackException`이 발생한다.

```java
@Transactional
public void outer() {
    try {
        inner(); // RuntimeException 발생 → rollbackOnly 마크
    } catch (RuntimeException e) {
        // 예외를 잡아도 이미 rollbackOnly가 설정되어 있음
        log.warn("내부 오류 처리", e);
    }
    // 여기서 outer의 트랜잭션 커밋 시도
    // → commit() 내부에서 isRollbackOnly() = true 확인
    // → UnexpectedRollbackException 발생
}

@Transactional // REQUIRED (기본값) - 외부 트랜잭션에 참여
public void inner() {
    throw new RuntimeException("내부 오류");
    // REQUIRED이므로 새 물리 트랜잭션 없이 외부 트랜잭션에 rollbackOnly 마크
}
```

이 문제를 피하려면:
1. 내부 메서드를 `REQUIRES_NEW`로 독립 트랜잭션으로 분리한다.
2. 또는 내부 예외를 잡고 커밋을 원하면 내부 메서드에 `NESTED`를 사용한다(JDBC only).

---

## 핵심 정리

- `@Transactional`은 명령적 트랜잭션 코드(Connection 획득, autoCommit=false, commit/rollback)를 AOP로 추상화한 것이다.
- 애플리케이션 구동 시 `InfrastructureAdvisorAutoProxyCreator`가 `@Transactional` 대상 빈을 프록시로 교체한다.
- `TransactionInterceptor.invoke()` → `PlatformTransactionManager.getTransaction()` → `doBegin()` → DB 작업 → `commit()/rollback()` 순서로 동작한다.
- `TransactionSynchronizationManager`는 ThreadLocal로 `[DataSource → ConnectionHolder]` 맵을 보관하여, 같은 스레드 내 모든 DB 접근이 동일한 커넥션을 재사용하도록 한다.
- 기본 롤백 대상은 `RuntimeException`과 `Error`이며, Checked Exception은 `rollbackFor`로 명시해야 롤백된다.
- REQUIRED 전파에서 내부 트랜잭션이 rollbackOnly를 마크하면 외부 commit 시 `UnexpectedRollbackException`이 발생한다.

## 기술적 한계와 보완 전략

| 한계 | 보완 전략 |
|------|----------|
| self-invocation 시 프록시 미경유로 트랜잭션 미적용 | 별도 빈으로 분리 (권장), self-injection, `AopContext.currentProxy()` |
| `private`/`final` 메서드는 CGLIB 프록시 오버라이드 불가 | `public`/`protected`로 변경, AspectJ 컴파일타임 위빙 |
| Checked Exception 기본 롤백 미적용 | `rollbackFor` 명시 또는 Unchecked Exception으로 래핑 |
| REQUIRED 참여 후 내부 롤백 → `UnexpectedRollbackException` | 내부 메서드를 `REQUIRES_NEW`로 분리, 또는 예외 전파 흐름 재설계 |
| 멀티스레드 환경에서 ThreadLocal 트랜잭션 컨텍스트 미공유 | `@Async` 메서드는 별도 트랜잭션 경계로 처리, `CompletableFuture` + 명시적 트랜잭션 |

## 키워드

- **@Transactional**: Spring의 선언적 트랜잭션 어노테이션. 프록시 기반 AOP를 통해 트랜잭션 경계(begin/commit/rollback)를 메서드 단위로 자동 관리한다.

- **Proxy (프록시)**: 실제 객체를 감싸는 대리 객체. Spring은 `@Transactional` 대상 빈을 프록시로 교체하여 메서드 호출 시 `TransactionInterceptor`가 먼저 실행되도록 한다.

- **Spring AOP**: 런타임 프록시 기반의 AOP 구현체. `@Transactional`, `@Cacheable`, `@Async` 등 부가 기능을 원본 코드에 손대지 않고 적용한다. 프록시를 경유하는 외부 호출에만 동작한다.

- **TransactionInterceptor**: `MethodInterceptor`를 구현한 클래스. 프록시 메서드 호출을 가로채어 `@Transactional` 속성을 읽고, `PlatformTransactionManager`에게 트랜잭션 시작·커밋·롤백을 위임한다.

- **PlatformTransactionManager**: Spring 트랜잭션 추상화의 핵심 인터페이스. JDBC, JPA, Hibernate, JTA 등 구현체에 무관하게 동일한 트랜잭션 워크플로우를 제공한다. `getTransaction()`, `commit()`, `rollback()` 세 메서드로 구성된다.

- **TransactionSynchronizationManager**: ThreadLocal 기반 트랜잭션 컨텍스트 레지스트리. `[DataSource → ConnectionHolder]` 맵을 스레드별로 관리하여, 같은 트랜잭션 내의 모든 DB 접근이 동일한 커넥션을 재사용하도록 보장한다.

- **CGLIB**: 바이트코드를 동적으로 생성하여 대상 클래스를 상속한 서브클래스(프록시)를 만드는 라이브러리. Spring Boot 2.x 이후 기본 AOP 프록시 방식이다.

- **JDK Dynamic Proxy**: `java.lang.reflect.Proxy`를 이용한 인터페이스 기반 동적 프록시. 대상 클래스가 인터페이스를 구현해야 한다.

- **Self-invocation (내부 호출)**: 같은 클래스 내에서 `this`를 통해 다른 메서드를 호출하는 것. 프록시를 거치지 않으므로 `@Transactional`이 적용되지 않는다.

- **ThreadLocal**: 각 스레드마다 독립된 변수 저장소를 제공하는 Java 구조. Spring의 `TransactionSynchronizationManager`는 ThreadLocal로 트랜잭션 컨텍스트(커넥션, 동기화, 읽기전용 여부 등)를 스레드별로 격리·관리한다.

## 참고 자료

- [Spring Framework 공식 문서 - Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Spring Framework 공식 문서 - Declarative Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html)
- [Spring Framework 공식 문서 - Programmatic Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- [PlatformTransactionManager JavaDoc (Spring Framework 7.0.4)](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/PlatformTransactionManager.html)
- [Spring Transaction Management: @Transactional In-Depth - Marco Behler](https://www.marcobehler.com/guides/spring-transaction-management-transactional-in-depth)
- [Spring Transaction Internals — What Really Happens Under the Hood - Medium](https://codefarm0.medium.com/spring-transaction-internals-what-really-happens-under-the-hood-175b09a32db8)
