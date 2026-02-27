# REQUIRES_NEW를 사용해야 하는 상황과 사용할 때 주의할 점

## 개요
Spring의 `@Transactional(propagation = REQUIRES_NEW)`는 기존 트랜잭션과 독립된 새로운 트랜잭션을 생성한다. 언제 사용해야 하는지, 그리고 사용 시 어떤 점을 주의해야 하는지를 정리한다.

## 상세 내용

### 1. REQUIRES_NEW의 동작 원리

`REQUIRES_NEW`가 선언된 메서드가 호출되면 Spring AOP 프록시가 트랜잭션 매니저에 새 트랜잭션 시작을 요청한다. 이때 내부적으로 다음 순서로 동작한다.

1. 현재 활성화된 트랜잭션이 있으면 **일시 정지(suspend)** 처리하고, 해당 트랜잭션에 묶인 커넥션은 반환하지 않은 채 유지
2. 커넥션 풀에서 **별도의 새 커넥션**을 획득하고, `setAutoCommit(false)`로 새 트랜잭션 시작
3. 새 트랜잭션 내 로직 실행 후 **독립적으로 커밋 또는 롤백**
4. 새 커넥션을 반환하고, 일시 정지된 부모 트랜잭션을 **재개(resume)**

```
부모 트랜잭션 시작  ---[커넥션 A 획득]---→
                            │
                   REQUIRES_NEW 메서드 진입
                            │
           부모 트랜잭션 suspend (커넥션 A 유지)
                            │
                   [커넥션 B 획득] 자식 트랜잭션 시작
                            │
                   자식 트랜잭션 커밋/롤백
                            │
                   [커넥션 B 반환]
                            │
           부모 트랜잭션 resume (커넥션 A 계속 사용)
                            │
부모 트랜잭션 커밋/롤백 ---[커넥션 A 반환]---→
```

핵심은 부모-자식 트랜잭션이 **각각 독립된 물리 커넥션**을 점유하며, 커밋과 롤백이 서로에게 영향을 미치지 않는다는 점이다.

```java
// 부모 트랜잭션
@Transactional
public void placeOrder(OrderRequest request) {
    orderRepository.save(request.toEntity());  // 아직 커밋 안 됨

    // 이 시점에 부모 트랜잭션은 suspend, 자식 트랜잭션 시작
    auditLogService.saveLog("ORDER_CREATED", request.getUserId());

    // 만약 여기서 예외 발생해도 auditLog는 이미 커밋된 상태
    throw new RuntimeException("결제 실패");
}

// 자식 트랜잭션 (독립 커밋)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void saveLog(String action, Long userId) {
    auditLogRepository.save(new AuditLog(action, userId));
    // 부모와 무관하게 독립적으로 커밋
}
```

---

### 2. REQUIRES_NEW를 사용해야 하는 상황

비즈니스 로직의 성공/실패와 **무관하게 반드시 저장되어야 하는 데이터**가 있을 때 사용한다.

#### 감사 로그 (Audit Log)
보안 및 컴플라이언스 요구사항으로 인해 비즈니스 로직이 실패하더라도 "누가, 언제, 무엇을 시도했는지" 기록을 남겨야 한다. 부모 트랜잭션이 롤백되어도 감사 로그는 커밋되어야 한다.

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void saveAuditLog(AuditLogEvent event) {
    AuditLog log = AuditLog.builder()
        .userId(event.getUserId())
        .action(event.getAction())
        .resource(event.getResource())
        .occurredAt(LocalDateTime.now())
        .build();
    auditLogRepository.save(log);
}
```

#### 알림/이벤트 발송 이력
푸시 알림, 이메일, SMS 등의 발송 시도 이력은 메인 로직 롤백과 무관하게 보존해야 한다. 발송 자체는 외부 시스템에 이미 전달된 상태일 수 있기 때문이다.

#### 재시도 카운터 / 실패 이력 관리
로그인 실패 횟수, API 호출 실패 카운터 등은 메인 트랜잭션 성공 여부와 별개로 누적되어야 한다.

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void incrementFailCount(Long userId) {
    LoginHistory history = loginHistoryRepository.findByUserId(userId);
    history.incrementFailCount();
    // 로그인 실패 시에도 부모 롤백과 무관하게 카운터 저장
}
```

#### 외부 API 호출 결과 저장
외부 결제 API나 배송 API를 호출한 결과(트랜잭션 ID, 응답 코드 등)는 이미 외부 시스템에서 처리가 완료된 상태이므로, 내부 비즈니스 로직이 실패해도 외부 API 호출 사실을 별도로 기록해야 한다.

```java
@Transactional
public PaymentResult processPayment(PaymentRequest request) {
    // 외부 결제 API 호출 (이미 외부에서 처리됨)
    ExternalPaymentResult externalResult = paymentGateway.charge(request);

    // 외부 API 결과를 독립 트랜잭션으로 저장
    paymentHistoryService.saveExternalResult(externalResult);

    // 이후 내부 로직에서 예외 발생 → 부모 롤백
    // 하지만 paymentHistoryService는 이미 커밋 완료
    inventoryService.decreaseStock(request.getProductId());

    return new PaymentResult(externalResult);
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void saveExternalResult(ExternalPaymentResult result) {
    paymentHistoryRepository.save(PaymentHistory.from(result));
}
```

---

### 3. REQUIRES_NEW vs 다른 전파 속성 비교

| 속성 | 물리 트랜잭션 | 기존 트랜잭션 참여 | 독립 커밋/롤백 | DB 커넥션 | 롤백 범위 |
|---|---|---|---|---|---|
| `REQUIRED` (기본값) | 공유 | 참여 (없으면 새로 생성) | 불가 (같은 트랜잭션) | 1개 공유 | 부모+자식 전체 |
| `REQUIRES_NEW` | 독립 | 기존 suspend, 새로 생성 | 가능 (완전 독립) | 2개 동시 점유 | 자식만 (또는 부모만) |
| `NESTED` | 1개 (savepoint 활용) | 참여 (savepoint 설정) | 부분 가능 | 1개 공유 | 자식만 rollback to savepoint |

#### REQUIRED - 기본 동작
```java
// 부모, 자식 모두 같은 물리 트랜잭션에 참여
// 자식에서 예외 발생 시 부모까지 전체 롤백
@Transactional // REQUIRED (기본)
public void parentMethod() {
    repository.save(entity1);
    childService.childMethod(); // 같은 트랜잭션 참여
}
```

#### REQUIRES_NEW - 완전 독립
```java
// 자식은 독립된 트랜잭션 → 자식 커밋 후 부모 실패해도 자식 데이터는 보존
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void childMethod() {
    repository.save(auditLog);
}
```

#### NESTED - savepoint 기반 부분 롤백
```java
// NESTED: 자식 롤백 시 savepoint로 되돌아감, 부모 트랜잭션은 계속 진행 가능
// 단, Hibernate(JPA)는 NESTED를 지원하지 않음 → DataSourceTransactionManager 필요
@Transactional(propagation = Propagation.NESTED)
public void nestedMethod() {
    // savepoint 설정 후 실행
    // 여기서 롤백 시 savepoint까지만 롤백, 부모 트랜잭션은 유지
}
```

> **NESTED 주의사항**: JPA(Hibernate)를 사용하는 경우 NESTED 전파 속성을 지원하지 않는다. `DataSourceTransactionManager`를 사용하는 순수 JDBC 환경에서만 사용 가능하다.

#### 선택 가이드
- **REQUIRED**: 대부분의 상황. 모든 로직이 단일 트랜잭션 경계 내에서 성공/실패해야 할 때
- **REQUIRES_NEW**: 비즈니스 로직 실패와 무관하게 반드시 커밋되어야 하는 로직 (감사 로그, 알림 이력 등)
- **NESTED**: 부분 롤백이 필요하고 순수 JDBC 환경일 때. JPA 환경에서는 사용 불가

---

### 4. 사용 시 주의할 점

#### Connection Pool 고갈

`REQUIRES_NEW`는 부모 트랜잭션 커넥션을 반환하지 않고 자식 트랜잭션용 커넥션을 추가로 획득한다. 즉, **하나의 스레드가 2개의 커넥션을 동시에 점유**한다.

```
스레드 10개, HikariCP 최대 커넥션 10개인 환경에서:

스레드 1~10: 부모 트랜잭션 시작 → 각각 커넥션 1개씩 획득 (총 10개 소진)
스레드 1~10: REQUIRES_NEW 메서드 호출 → 커넥션 추가 요청
              → 커넥션 풀 고갈 → connectionTimeout 대기
              → 모든 스레드 블로킹 → 데드락!
```

**실제 교착 상태 발생 시나리오:**
1. 스레드 풀 크기가 10, 커넥션 풀 크기도 10으로 동일하게 설정
2. 요청 10개가 동시에 들어와 각각 부모 트랜잭션을 시작, 커넥션 모두 소진
3. 각 요청이 `REQUIRES_NEW` 메서드를 호출 → 커넥션 추가 요청
4. 커넥션을 반납할 스레드가 없으므로 모든 스레드가 영구 대기 상태

**해결 방법:**
```yaml
# application.yml
spring:
  datasource:
    hikari:
      # REQUIRES_NEW를 사용하는 경우 커넥션 풀은 스레드 수보다 충분히 크게 설정
      # 공식 권장: pool-size >= (thread-count * 2) + 여유분
      maximum-pool-size: 30  # 스레드 10개 기준으로 최소 20 이상 권장
      connection-timeout: 30000
```

공식 문서(Spring Framework Reference)에서도 명시적으로 경고한다: "Connection Pool이 동시 스레드 수보다 최소 1 이상 크게 설정된 경우에만 안전하게 사용할 수 있다."

---

#### Self-Invocation 문제

Spring의 `@Transactional`은 **AOP 프록시** 기반으로 동작한다. 같은 클래스 내부에서 `REQUIRES_NEW`가 선언된 메서드를 직접 호출하면 프록시를 거치지 않아 새 트랜잭션이 생성되지 않는다.

```java
@Service
public class OrderService {

    @Transactional
    public void placeOrder(OrderRequest request) {
        orderRepository.save(request.toEntity());

        // 잘못된 예: self-invocation → 프록시 우회 → REQUIRES_NEW 무시됨
        // saveAuditLog()는 부모 트랜잭션에 그냥 참여
        saveAuditLog("ORDER_CREATED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String action) {
        auditLogRepository.save(new AuditLog(action));
    }
}
```

**해결 방법 1: 별도 빈으로 분리**
```java
@Service
public class AuditLogService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String action) {
        auditLogRepository.save(new AuditLog(action));
    }
}

@Service
@RequiredArgsConstructor
public class OrderService {
    private final AuditLogService auditLogService; // 외부 빈 주입

    @Transactional
    public void placeOrder(OrderRequest request) {
        orderRepository.save(request.toEntity());
        auditLogService.saveAuditLog("ORDER_CREATED"); // 프록시를 통해 호출 → REQUIRES_NEW 정상 동작
    }
}
```

**해결 방법 2: ApplicationContext에서 직접 빈 조회 (getBean)**
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
        // 프록시 빈을 직접 조회하여 호출
        applicationContext.getBean(OrderService.class).saveAuditLog("ORDER_CREATED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(String action) {
        auditLogRepository.save(new AuditLog(action));
    }
}
```

> 별도 클래스 분리가 더 권장되는 방법이다. `getBean()`은 테스트 어려움, 관심사 혼재 문제가 있다.

---

#### 예외 전파와 롤백 관계

`REQUIRES_NEW`로 실행된 자식 트랜잭션에서 예외가 발생하면, `try-catch`로 처리하지 않을 경우 예외가 부모 트랜잭션까지 전파되어 부모도 롤백될 수 있다.

```java
@Transactional
public void placeOrder(OrderRequest request) {
    orderRepository.save(request.toEntity());

    // 자식에서 예외 발생 → 자식 롤백 → 예외가 부모로 전파 → 부모도 롤백
    // 결과: 감사 로그도 없고, 주문도 없음
    auditLogService.saveAuditLog("ORDER_CREATED");
}
```

**올바른 처리 - 부모에서 자식 예외를 try-catch로 처리:**
```java
@Transactional
public void placeOrder(OrderRequest request) {
    orderRepository.save(request.toEntity());

    try {
        auditLogService.saveAuditLog("ORDER_CREATED");
    } catch (Exception e) {
        // 감사 로그 실패는 비즈니스 로직에 영향 안 줌
        log.error("감사 로그 저장 실패: {}", e.getMessage());
    }
}
```

---

#### 데드락 (Deadlock) 위험

부모 트랜잭션과 자식 트랜잭션이 **같은 테이블의 같은 Row를 접근**할 때 데드락이 발생할 수 있다.

```
시나리오:
1. 부모 트랜잭션: Row A에 X-Lock 획득
2. REQUIRES_NEW → 자식 트랜잭션 시작
3. 자식 트랜잭션: Row A에 X-Lock 요청
4. Row A의 X-Lock은 부모가 점유 중 (부모는 suspend 상태이지만 Lock은 유지)
5. 자식은 Lock 대기 → timeout 또는 데드락 감지
```

Row 단위 데드락 외에도, 앞서 설명한 커넥션 풀 고갈로 인한 **커넥션 레벨 데드락**도 `REQUIRES_NEW` 과다 사용 시 빈번하게 발생한다.

---

### 5. 실무 설계 가이드라인

#### 이벤트 기반 비동기 처리로 대체

`REQUIRES_NEW`의 Connection Pool 고갈 문제를 근본적으로 해결하는 방법은 **이벤트 기반 비동기 처리**로 전환하는 것이다. Spring의 `ApplicationEventPublisher`와 `@TransactionalEventListener`를 활용하면 된다.

**동작 원리:**
- `@TransactionalEventListener`는 기본적으로 `AFTER_COMMIT` 페이즈에서 실행됨
- 부모 트랜잭션이 커밋된 **이후**에 리스너가 실행되므로, 커넥션 A는 이미 반환된 상태
- 리스너에서 `REQUIRES_NEW`를 선언하면 새 커넥션을 안전하게 획득

```java
// 이벤트 클래스
public record AuditLogEvent(String action, Long userId, String resource) {}
```

```java
// 이벤트 발행 (부모 트랜잭션 내)
@Service
@RequiredArgsConstructor
public class OrderService {
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void placeOrder(OrderRequest request) {
        orderRepository.save(request.toEntity());

        // 이벤트만 발행. 실제 저장은 트랜잭션 커밋 이후에 비동기로 처리
        eventPublisher.publishEvent(
            new AuditLogEvent("ORDER_CREATED", request.getUserId(), "ORDER")
        );
        // 부모 트랜잭션 커밋 → 커넥션 A 반환
    }
}
```

```java
// 이벤트 리스너 (부모 커밋 이후 실행)
@Component
@RequiredArgsConstructor
public class AuditLogEventListener {

    private final AuditLogRepository auditLogRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    // @Async  // 필요 시 비동기로 처리
    public void handleAuditLogEvent(AuditLogEvent event) {
        // 부모 트랜잭션 커밋 이후 실행
        // 커넥션 A는 이미 반환된 상태 → 새 커넥션 B만 점유
        auditLogRepository.save(
            AuditLog.builder()
                .action(event.action())
                .userId(event.userId())
                .resource(event.resource())
                .occurredAt(LocalDateTime.now())
                .build()
        );
    }
}
```

**이 패턴의 장점:**
- 부모 트랜잭션과 리스너 트랜잭션이 **동시에 커넥션을 점유하지 않음** → 커넥션 풀 고갈 방지
- 부모 트랜잭션 커밋 이후에만 실행되므로 **데이터 정합성 보장**
- `@Async` 추가 시 완전한 비동기 처리 가능

**주의사항:**
- `AFTER_COMMIT` 단계에서 `@Transactional(propagation = REQUIRED)`를 사용하면 이미 커밋된 트랜잭션에 참여하게 되어 플러시가 발생하지 않아 데이터가 저장되지 않는다. 반드시 `REQUIRES_NEW`를 함께 사용해야 한다.
- 부모 트랜잭션이 롤백되면 `AFTER_COMMIT` 리스너는 실행되지 않음. 롤백 후에도 실행이 필요하면 `AFTER_ROLLBACK` 또는 `AFTER_COMPLETION` 페이즈를 사용

#### REQUIRES_NEW 사용을 최소화하는 설계 원칙

1. **먼저 이벤트 기반 설계를 검토**: 감사 로그, 알림 이력 등은 `ApplicationEventPublisher` + `@TransactionalEventListener`로 대체 가능한지 먼저 검토
2. **반드시 별도 빈으로 분리**: self-invocation 문제를 방지하기 위해 `REQUIRES_NEW` 로직은 항상 별도 `@Service` 클래스로 분리
3. **Connection Pool 사이징**: `REQUIRES_NEW`를 사용하는 경우 HikariCP `maximum-pool-size`를 `(최대 동시 스레드 수 × 2) + 여유분` 이상으로 설정
4. **예외 처리 명시**: 부모 트랜잭션에서 자식 예외를 명시적으로 `try-catch`하여 불필요한 롤백 전파 방지

---

## 핵심 정리

- `REQUIRES_NEW`는 부모 트랜잭션을 **suspend**하고 새 물리 커넥션으로 **독립된 트랜잭션**을 시작한다. 두 트랜잭션은 커밋/롤백이 서로 영향을 미치지 않는다.
- 하나의 스레드가 **커넥션 2개를 동시 점유**하므로, 동시 요청 수가 커넥션 풀 크기에 근접할 경우 **커넥션 풀 고갈 → 데드락**이 발생할 수 있다.
- **같은 클래스 내부 호출(Self-Invocation)** 시 AOP 프록시를 우회하므로 `REQUIRES_NEW`가 동작하지 않는다. 반드시 별도 빈으로 분리해야 한다.
- 자식 트랜잭션에서 예외 발생 시 **try-catch 없이 방치하면 부모까지 전파**되어 의도치 않은 롤백이 발생한다.
- 감사 로그, 알림 이력 등 `REQUIRES_NEW`가 필요한 대부분의 케이스는 `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` 조합으로 **커넥션 동시 점유 없이** 안전하게 처리할 수 있다.

---

## 키워드

- `REQUIRES_NEW`: Spring 트랜잭션 전파 속성 중 하나. 기존 트랜잭션을 suspend하고 완전히 독립된 새 트랜잭션(새 커넥션)을 시작한다.
- `트랜잭션 전파(Propagation)`: 트랜잭션이 이미 존재할 때 새 트랜잭션 메서드가 어떻게 동작할지를 정의하는 설정. `REQUIRED`, `REQUIRES_NEW`, `NESTED` 등이 있다.
- `Connection Pool 고갈`: `REQUIRES_NEW` 사용 시 하나의 스레드가 커넥션 2개를 동시에 점유하여, 동시 요청이 많아지면 커넥션 풀이 고갈되고 모든 스레드가 대기 상태로 빠지는 현상.
- `Self-Invocation`: 같은 클래스 내에서 자신의 메서드를 직접 호출하는 것. Spring AOP 프록시를 우회하므로 `@Transactional` 어노테이션이 동작하지 않는다.
- `NESTED`: JDBC savepoint를 활용한 전파 속성. 자식 트랜잭션이 롤백되어도 savepoint까지만 롤백되고 부모는 계속 진행 가능하다. JPA(Hibernate) 환경에서는 미지원.
- `@TransactionalEventListener`: Spring 4.2+에서 제공. 트랜잭션의 특정 페이즈(AFTER_COMMIT, AFTER_ROLLBACK 등)에 바인딩된 이벤트 리스너 어노테이션.
- `트랜잭션 롤백`: 트랜잭션 내 작업을 모두 취소하는 동작. `REQUIRES_NEW`에서는 부모/자식 롤백이 서로 독립적이나, 예외 전파 시 부모에게 영향을 줄 수 있다.
- `데드락(Deadlock)`: 두 개 이상의 트랜잭션(또는 스레드)이 서로가 점유한 자원을 기다리며 영구적으로 블로킹되는 상태. `REQUIRES_NEW` 과다 사용 시 커넥션 풀 레벨 데드락 발생 가능.
- `감사 로그(Audit Log)`: 시스템에서 발생한 중요 이벤트를 보안/컴플라이언스 목적으로 기록하는 로그. 비즈니스 로직 실패와 무관하게 반드시 저장되어야 하므로 `REQUIRES_NEW`의 대표적인 사용 사례다.
- `ApplicationEventPublisher`: Spring의 이벤트 발행 인터페이스. `publishEvent()`를 통해 이벤트를 발행하고, `@EventListener` 또는 `@TransactionalEventListener`로 수신한다.

---

## 참고 자료
- [Spring Framework Reference - Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [Spring Framework Reference - Transaction-bound Events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [TransactionalEventListener Javadoc (Spring Framework 7.0.5 API)](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html)
- [Spring Framework Issue #26250 - Propagation REQUIRES_NEW may cause connection pool deadlock](https://github.com/spring-projects/spring-framework/issues/26250)
- [Marco Behler - Spring Transaction Management In-Depth](https://www.marcobehler.com/guides/spring-transaction-management-transactional-in-depth)
- [Baeldung - Transaction Propagation and Isolation in Spring @Transactional](https://www.baeldung.com/spring-transactional-propagation-isolation)
