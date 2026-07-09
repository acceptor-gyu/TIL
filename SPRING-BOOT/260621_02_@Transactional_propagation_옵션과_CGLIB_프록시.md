# @Transactional propagation 옵션과 CGLIB 프록시

## 개요

Spring의 선언적 트랜잭션이 CGLIB 프록시를 통해 동작하는 원리와, propagation(전파) 옵션이 프록시 경계에서 어떻게 해석되는지 학습한다.

`@Transactional`이 "동작하지 않는다"는 문제는 대부분 프록시의 경계를 이해하지 못해서 발생한다. propagation 옵션은 프록시를 통한 **외부 호출이 있을 때만** 의미를 가진다.

## 상세 내용

### 1. 선언적 트랜잭션과 프록시 AOP

#### @Transactional의 동작 원리

Spring이 `@Transactional`이 붙은 빈을 컨테이너에 등록할 때, 실제 객체 대신 **프록시 객체**를 빈으로 등록한다. 클라이언트가 해당 빈의 메서드를 호출하면 프록시가 먼저 가로채어 트랜잭션 처리를 수행한 뒤 실제 메서드로 위임한다.

```
클라이언트
    │
    ▼
[CGLIB 프록시]  ← 트랜잭션 시작/커밋/롤백
    │
    ▼
[실제 객체의 메서드]
```

#### CGLIB 프록시 vs JDK 동적 프록시 선택 기준

| 구분 | JDK 동적 프록시 | CGLIB 프록시 |
|------|----------------|-------------|
| 기반 | 인터페이스 구현 | 클래스 서브클래싱 |
| 인터페이스 필요 여부 | 필수 | 불필요 |
| Spring Boot 2.x 이후 기본값 | X | O (`proxyTargetClass=true`) |

Spring Boot 2.x부터 `spring.aop.proxy-target-class=true`가 기본값이므로 인터페이스 유무와 무관하게 **CGLIB이 기본**으로 사용된다. 구체 타입으로 주입받을 때 JDK 프록시가 캐스팅 오류를 일으키는 문제를 방지하기 위한 결정이다.

#### TransactionInterceptor의 역할

프록시 내부에는 `TransactionInterceptor`가 `MethodInterceptor`로 등록되어 있다. 메서드 호출이 프록시에 도달하면 다음 순서로 처리된다.

```
프록시 메서드 호출
    │
    ▼
TransactionInterceptor.invoke()
    │
    ├─ 1. 현재 트랜잭션 컨텍스트 조회 (TransactionSynchronizationManager)
    ├─ 2. @Transactional의 propagation 옵션 판단
    ├─ 3. PlatformTransactionManager로 트랜잭션 시작/참여/생략
    │
    ▼
실제 메서드 실행
    │
    ▼
TransactionInterceptor
    │
    ├─ 4. 정상 완료 → 커밋
    └─ 5. RuntimeException / Error 발생 → 롤백
```

`PlatformTransactionManager`는 JDBC 환경에서는 `DataSourceTransactionManager`, JPA 환경에서는 `JpaTransactionManager`를 사용한다.

---

### 2. propagation 옵션 종류와 의미

#### 물리 트랜잭션과 논리 트랜잭션 개념

propagation을 이해하기 위해 먼저 두 개념을 구분해야 한다.

| 개념 | 설명 |
|------|------|
| **물리 트랜잭션** | 실제 DB 커넥션과 연결된 트랜잭션. `BEGIN` ~ `COMMIT/ROLLBACK` 한 쌍. 커넥션 1개를 점유한다. |
| **논리 트랜잭션** | Spring이 메서드 단위로 관리하는 트랜잭션 스코프. 여러 논리 트랜잭션이 하나의 물리 트랜잭션에 참여할 수 있다. |

#### REQUIRED (기본값)

기존 트랜잭션이 있으면 참여하고, 없으면 새로 생성한다.

```java
@Transactional // propagation = Propagation.REQUIRED (기본값)
public void serviceMethod() {
    repository.save(entity);
}
```

```
부모 트랜잭션 존재 O → 같은 물리 트랜잭션에 참여 (논리 트랜잭션 추가)
부모 트랜잭션 존재 X → 새 물리 트랜잭션 시작
```

**주의점**: 내부 논리 트랜잭션에서 롤백 마크가 설정되면(`setRollbackOnly()`), 외부에서는 커밋을 시도해도 `UnexpectedRollbackException`이 발생한다. 하나의 물리 트랜잭션에 참여하고 있기 때문이다.

```java
@Transactional
public void outer() {
    try {
        inner(); // 내부에서 예외 발생 → rollbackOnly 마크
    } catch (Exception e) {
        // 잡아도 이미 rollbackOnly가 설정됨
    }
    // 커밋 시도 → UnexpectedRollbackException 발생
}

@Transactional
public void inner() {
    throw new RuntimeException("내부 예외");
}
```

#### REQUIRES_NEW

기존 트랜잭션을 suspend하고 완전히 독립된 새 물리 트랜잭션을 시작한다.

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void saveAuditLog(String action) {
    auditLogRepository.save(new AuditLog(action));
}
```

```
부모 트랜잭션 존재 O → 부모 suspend + 새 커넥션으로 독립 트랜잭션 시작
부모 트랜잭션 존재 X → 새 물리 트랜잭션 시작
```

하나의 스레드가 **커넥션 2개를 동시에 점유**하므로 커넥션 풀 고갈에 주의해야 한다. (자세한 내용은 [REQUIRES_NEW를 사용해야 하는 상황과 주의할 점](260227_01_REQUIRES_NEW를_사용해야_하는_상황과_주의할_점.md) 참고)

#### NESTED

단일 물리 트랜잭션 내에서 JDBC savepoint를 활용한 중첩 트랜잭션을 생성한다.

```java
@Transactional(propagation = Propagation.NESTED)
public void nestedOperation() {
    // savepoint 설정 후 실행
    // 예외 발생 시 savepoint까지만 롤백
    // 부모 트랜잭션은 계속 진행 가능
}
```

```
부모 트랜잭션 존재 O → 같은 물리 트랜잭션 + savepoint 설정
부모 트랜잭션 존재 X → REQUIRED처럼 새 트랜잭션 시작
```

**중요 제약**: `DataSourceTransactionManager`에서만 동작한다. `JpaTransactionManager`(Hibernate)는 NESTED를 지원하지 않는다. JPA 환경에서 부분 롤백이 필요하다면 별도 빈으로 분리하여 `REQUIRES_NEW`를 사용하는 방식으로 대체해야 한다.

#### SUPPORTS

트랜잭션이 있으면 참여하고, 없으면 트랜잭션 없이 실행한다.

```java
@Transactional(propagation = Propagation.SUPPORTS)
public void readOperation() {
    // 트랜잭션 있으면 참여, 없으면 non-transactional로 실행
}
```

#### NOT_SUPPORTED

기존 트랜잭션이 있으면 suspend하고, 트랜잭션 없이 실행한다.

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public void nonTransactionalOperation() {
    // 항상 non-transactional
}
```

#### MANDATORY

반드시 기존 트랜잭션 내에서 호출되어야 한다. 트랜잭션이 없으면 `IllegalTransactionStateException`을 던진다.

```java
@Transactional(propagation = Propagation.MANDATORY)
public void mustRunInTransaction() {
    // 트랜잭션 없으면 예외: No existing transaction found
}
```

내부적으로만 호출되어야 하는 메서드에 선언하여, 트랜잭션 없이 직접 호출하는 실수를 방지할 수 있다.

#### NEVER

트랜잭션이 존재하면 `IllegalTransactionStateException`을 던진다.

```java
@Transactional(propagation = Propagation.NEVER)
public void mustNotRunInTransaction() {
    // 트랜잭션 있으면 예외: Existing transaction found
}
```

#### propagation 옵션 요약

| 옵션 | 기존 트랜잭션 있을 때 | 기존 트랜잭션 없을 때 | 물리 트랜잭션 |
|------|----------------------|----------------------|--------------|
| `REQUIRED` | 참여 | 새로 생성 | 1개 공유 |
| `REQUIRES_NEW` | suspend + 새로 생성 | 새로 생성 | 2개 동시 점유 |
| `NESTED` | savepoint 설정 후 참여 | 새로 생성 | 1개 공유 |
| `SUPPORTS` | 참여 | non-transactional | - |
| `NOT_SUPPORTED` | suspend + non-transactional | non-transactional | - |
| `MANDATORY` | 참여 | 예외 발생 | 1개 공유 |
| `NEVER` | 예외 발생 | non-transactional | - |

---

### 3. propagation이 프록시 경계에서 동작하는 방식

#### 프록시를 거쳐야만 트랜잭션 어드바이스가 적용되는 이유

Spring AOP는 **프록시 객체에 대한 외부 호출**을 인터셉트하는 방식으로 동작한다. 컨테이너에 등록된 빈은 실제 객체가 아닌 프록시다. 따라서 다른 빈에서 이 빈의 메서드를 호출하면 반드시 프록시를 통과하게 되어 `TransactionInterceptor`가 동작한다.

```
OrderController → [OrderService 프록시] → (TransactionInterceptor 동작) → OrderService 실제 객체
```

#### 같은 클래스 내부 호출(self-invocation) 시 프록시 우회 문제

같은 빈 안에서 `this`로 다른 메서드를 호출하면, 해당 호출은 **프록시를 거치지 않고** 실제 객체 위에서 직접 실행된다.

```java
@Service
public class OrderService {

    @Transactional
    public void placeOrder(OrderRequest request) {
        orderRepository.save(request.toEntity());

        // this.saveAuditLog()를 직접 호출
        // → 프록시가 아닌 실제 객체의 메서드 호출
        // → TransactionInterceptor 미동작
        // → propagation = REQUIRES_NEW 무시
        saveAuditLog("ORDER_CREATED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String action) {
        // REQUIRES_NEW가 선언되어 있지만, 프록시를 거치지 않았으므로
        // 새 트랜잭션이 생성되지 않는다.
        // 부모 트랜잭션에 그냥 참여하거나, non-transactional로 실행된다.
        auditLogRepository.save(new AuditLog(action));
    }
}
```

```
외부 호출: 클라이언트 → [프록시] → placeOrder() 실행
내부 호출: placeOrder() 안에서 this.saveAuditLog() 직접 호출
                                    ↑
                             프록시를 거치지 않음
                             TransactionInterceptor 미동작
```

#### REQUIRES_NEW가 내부 호출에서 동작하지 않는 사례

```java
@Service
public class PaymentService {

    @Transactional
    public void processPayment(PaymentRequest request) {
        // 1) 결제 처리
        paymentRepository.save(request.toEntity());

        // 2) 결제 이력 저장 (REQUIRES_NEW로 독립 커밋하려 했으나...)
        //    self-invocation이므로 REQUIRES_NEW 무시됨
        //    결제 이력도 같은 트랜잭션에 참여
        //    processPayment가 롤백되면 결제 이력도 함께 롤백됨
        this.savePaymentHistory(request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void savePaymentHistory(PaymentRequest request) {
        paymentHistoryRepository.save(PaymentHistory.from(request));
    }
}
```

---

### 4. self-invocation 문제 해결 전략

#### 전략 1: 메서드를 다른 빈으로 분리 (권장)

가장 명확하고 테스트하기 쉬운 방법이다. 관심사 분리도 자연스럽게 이루어진다.

```java
// 별도 빈으로 분리
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String action) {
        auditLogRepository.save(new AuditLog(action));
    }
}

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final AuditLogService auditLogService; // 외부 빈 주입

    @Transactional
    public void placeOrder(OrderRequest request) {
        orderRepository.save(request.toEntity());
        // 외부 빈을 통한 호출 → 프록시를 경유 → REQUIRES_NEW 정상 동작
        auditLogService.saveAuditLog("ORDER_CREATED");
    }
}
```

#### 전략 2: self-injection 활용

자기 자신을 빈으로 주입받아 프록시를 통해 메서드를 호출한다. Spring 4.3+부터 순환 의존 감지가 완화되어 가능하다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    @Lazy
    @Autowired
    private OrderService self; // 자기 자신의 프록시 주입

    @Transactional
    public void placeOrder(OrderRequest request) {
        orderRepository.save(request.toEntity());
        // 프록시를 통해 호출 → REQUIRES_NEW 동작
        self.saveAuditLog("ORDER_CREATED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String action) {
        auditLogRepository.save(new AuditLog(action));
    }
}
```

`@Lazy`를 함께 사용해야 순환 의존 문제를 피할 수 있다. 가독성이 낮고 코드 의도가 불명확해지므로 빈 분리가 더 권장된다.

#### 전략 3: ApplicationContext.getBean() 활용

```java
@Service
public class OrderService implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.applicationContext = ctx;
    }

    @Transactional
    public void placeOrder(OrderRequest request) {
        orderRepository.save(request.toEntity());
        // 컨테이너에서 프록시 빈을 직접 조회
        applicationContext.getBean(OrderService.class).saveAuditLog("ORDER_CREATED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String action) {
        auditLogRepository.save(new AuditLog(action));
    }
}
```

`ApplicationContextAware`에 대한 의존이 생기고 테스트가 어려워진다. 빈 분리가 불가능한 레거시 환경에서 임시로 사용하는 수준으로 제한하는 것이 좋다.

#### 전략 4: AopContext.currentProxy() 활용

```java
@Service
@EnableAspectJAutoProxy(exposeProxy = true) // exposeProxy 활성화 필요
public class OrderService {

    @Transactional
    public void placeOrder(OrderRequest request) {
        orderRepository.save(request.toEntity());
        // 현재 스레드의 프록시를 직접 조회
        ((OrderService) AopContext.currentProxy()).saveAuditLog("ORDER_CREATED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String action) {
        auditLogRepository.save(new AuditLog(action));
    }
}
```

`exposeProxy = true` 설정이 필요하고, ThreadLocal을 통해 프록시를 전달하므로 성능 오버헤드가 있다. 이 방법도 빈 분리가 최우선이며 이 방법은 비권장이다.

#### 전략 5: AspectJ 로드타임/컴파일타임 위빙

프록시 기반 AOP를 완전히 벗어나 AspectJ 위빙을 적용하면 `this` 호출도 어드바이스가 적용된다. Spring AOP 방식이 아닌 AspectJ가 바이트코드 단에서 직접 코드를 삽입하기 때문이다.

```xml
<!-- Maven: AspectJ 컴파일타임 위빙 설정 -->
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>aspectj-maven-plugin</artifactId>
</plugin>
```

```java
// @EnableTransactionManagement(mode = AdviceMode.ASPECTJ) 선언 필요
@Configuration
@EnableTransactionManagement(mode = AdviceMode.ASPECTJ)
public class TransactionConfig { }
```

설정 복잡도가 높고 빌드 파이프라인에 영향을 준다. self-invocation 문제가 구조적으로 반복 발생하는 경우에 고려한다.

---

### 5. 실무에서 자주 마주치는 함정

#### private/final 메서드에 @Transactional이 무시되는 이유

CGLIB은 상속(서브클래싱)으로 프록시를 만든다. `private` 메서드는 서브클래스에서 오버라이드할 수 없고, `final` 메서드는 오버라이드가 금지되어 있다. 따라서 이 두 경우 `@Transactional`이 완전히 무시된다.

```java
@Service
public class OrderService {

    // 프록시 불가 - CGLIB이 오버라이드 불가
    @Transactional
    private void saveOrder(Order order) {
        orderRepository.save(order);
    }

    // 프록시 불가 - final 메서드는 상속 시 오버라이드 금지
    @Transactional
    public final void cancelOrder(Long orderId) {
        orderRepository.deleteById(orderId);
    }
}
```

컴파일 오류는 발생하지 않지만 `@Transactional`이 조용히 무시된다. Kotlin 클래스는 기본적으로 `final`이므로 Spring에서 사용할 때 `open` 키워드를 붙이거나 `kotlin-allopen` 플러그인을 적용해야 한다.

#### 트랜잭션 롤백 전파와 rollbackFor 설정

기본적으로 `@Transactional`은 **Unchecked Exception**(RuntimeException, Error)에서만 롤백한다. Checked Exception은 롤백되지 않는다.

```java
// IOException(Checked Exception) 발생 시 롤백되지 않음 (기본 동작)
@Transactional
public void processFile(File file) throws IOException {
    repository.save(entity);
    Files.readAllBytes(file.toPath()); // IOException 발생 → 롤백 안 됨
}

// rollbackFor로 명시하면 Checked Exception도 롤백
@Transactional(rollbackFor = IOException.class)
public void processFile(File file) throws IOException {
    repository.save(entity);
    Files.readAllBytes(file.toPath()); // IOException 발생 → 롤백됨
}
```

반대로 특정 예외에서 롤백을 막으려면 `noRollbackFor`를 사용한다.

```java
@Transactional(noRollbackFor = EntityNotFoundException.class)
public void process(Long id) {
    // EntityNotFoundException이 발생해도 롤백하지 않음
}
```

#### REQUIRES_NEW 남용 시 커넥션 풀 고갈

`REQUIRES_NEW`는 호출 시점에 기존 트랜잭션 커넥션을 반환하지 않고 추가 커넥션을 획득한다. 동시 요청이 증가할수록 필요한 커넥션 수가 선형으로 늘어난다.

```
스레드 수 = 10, 커넥션 풀 크기 = 10인 환경에서
모든 스레드가 REQUIRES_NEW 메서드를 동시에 호출하면:

스레드 1~10: 부모 트랜잭션 커넥션 획득 (10개 소진)
스레드 1~10: REQUIRES_NEW 진입 → 추가 커넥션 요청
             → 풀 고갈 → 모든 스레드 대기 → 교착 상태
```

HikariCP 공식 권장사항과 Spring 문서 모두 REQUIRES_NEW 사용 시 커넥션 풀 크기를 동시 스레드 수보다 충분히 크게 설정할 것을 명시한다.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30  # 스레드 10개 기준 최소 20 이상 권장
```

근본적인 해결책은 `@TransactionalEventListener`를 활용한 이벤트 기반 처리로 커넥션 동시 점유 자체를 제거하는 것이다.

---

## 핵심 정리

- `@Transactional`은 CGLIB 프록시가 감싼 **외부 호출**을 통해서만 적용된다. 프록시를 경유해야 `TransactionInterceptor`가 동작한다.
- `propagation`은 메서드 호출 시점에 **기존 트랜잭션 존재 여부**를 확인하여 물리/논리 트랜잭션 동작을 결정한다.
- `self-invocation`은 프록시를 우회하므로 `propagation` 옵션이 무시된다. 메서드를 별도 빈으로 분리하는 것이 가장 명확한 해결책이다.
- `private`, `final` 메서드에 `@Transactional`을 선언해도 CGLIB이 오버라이드할 수 없어 조용히 무시된다.
- `NESTED`는 JPA 환경에서 사용할 수 없다. `DataSourceTransactionManager` 전용이다.

## 기술적 한계와 보완 전략

| 한계 | 보완 전략 |
|------|----------|
| CGLIB 상속 기반 → `final`/`private` 메서드 프록시 불가 | `final` 키워드 제거, Kotlin `open` 또는 `kotlin-allopen` 플러그인 적용 |
| self-invocation → propagation 옵션 무시 | 별도 빈으로 분리 (권장), `self-injection`, `AopContext.currentProxy()` |
| `REQUIRES_NEW` 남용 → 커넥션 풀 고갈 | `@TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW` 조합으로 대체, 커넥션 풀 사이즈 조정 |
| JPA 환경에서 `NESTED` 미지원 | 별도 빈 분리 후 `REQUIRES_NEW` 사용 또는 순수 JDBC로 전환 |
| Checked Exception 기본 롤백 미적용 | `rollbackFor` 명시 |

## 키워드

- **@Transactional**: Spring의 선언적 트랜잭션 어노테이션. 메서드 또는 클래스에 선언하면 Spring AOP가 CGLIB 프록시를 통해 트랜잭션을 자동으로 관리한다.

- **propagation (전파)**: 트랜잭션이 이미 존재할 때 새로운 `@Transactional` 메서드가 어떻게 동작할지를 결정하는 옵션. `REQUIRED`, `REQUIRES_NEW`, `NESTED`, `SUPPORTS`, `NOT_SUPPORTED`, `MANDATORY`, `NEVER` 7가지가 있다.

- **CGLIB 프록시**: Code Generation Library를 이용해 대상 클래스를 상속한 서브클래스를 동적으로 생성하는 프록시 방식. Spring Boot 2.x 이후 기본 AOP 프록시 방식이다.

- **TransactionInterceptor**: Spring AOP에서 `@Transactional` 메서드 호출을 가로채는 핵심 인터셉터. `PlatformTransactionManager`를 통해 트랜잭션을 시작, 커밋, 롤백한다.

- **self-invocation**: 같은 클래스 안에서 `this`를 통해 자신의 다른 메서드를 호출하는 것. 프록시를 거치지 않아 `@Transactional` 및 propagation 옵션이 적용되지 않는다.

- **REQUIRES_NEW**: 기존 트랜잭션을 suspend하고 완전히 독립된 새 물리 트랜잭션(새 DB 커넥션)을 생성하는 전파 옵션. 커밋/롤백이 부모 트랜잭션과 완전히 독립되지만 커넥션을 2개 동시에 점유한다.

- **물리/논리 트랜잭션**: 물리 트랜잭션은 실제 DB 커넥션과 연결된 `BEGIN` ~ `COMMIT/ROLLBACK` 한 쌍이고, 논리 트랜잭션은 Spring이 메서드 단위로 관리하는 스코프다. `REQUIRED`에서는 여러 논리 트랜잭션이 하나의 물리 트랜잭션을 공유한다.

- **프록시 기반 AOP**: Spring AOP의 구현 방식. 실제 객체 대신 프록시 객체를 컨테이너에 등록하여 메서드 호출을 가로채는 방식으로 부가 기능을 적용한다. 프록시를 경유하는 **외부 호출**에만 동작한다.

- **AspectJ 위빙**: AspectJ가 바이트코드 수준에서 어드바이스를 삽입하는 방식. 컴파일타임 또는 로드타임에 적용되어 self-invocation에도 어드바이스가 동작한다. Spring AOP의 프록시 방식 한계를 근본적으로 우회한다.

- **트랜잭션 전파**: 트랜잭션 경계가 여러 메서드에 걸쳐 있을 때 각 메서드가 기존 트랜잭션에 어떻게 참여하거나 새 트랜잭션을 생성할지를 정의하는 개념.

## 참고 자료

- [Spring Framework 공식 문서 - Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [Spring Framework 공식 문서 - Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)
- [Spring Framework 공식 문서 - Declarative Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html)
- [Baeldung - Transaction Propagation and Isolation in Spring @Transactional](https://www.baeldung.com/spring-transactional-propagation-isolation)
- [Marco Behler - Spring Transaction Management: @Transactional In-Depth](https://www.marcobehler.com/guides/spring-transaction-management-transactional-in-depth)
