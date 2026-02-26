# Transaction Isolation Level - READ COMMITTED와 REQUIRES_NEW의 관계

## 개요
Spring의 `@Transactional(propagation = REQUIRES_NEW)`와 DB의 트랜잭션 격리 수준(특히 READ COMMITTED)이 어떻게 상호작용하는지 정리한다.

## 목차

### 1. READ COMMITTED 격리 수준 복습

#### READ COMMITTED의 동작 원리

READ COMMITTED는 SQL 표준에서 정의한 4가지 격리 수준 중 두 번째 단계이며, MySQL InnoDB의 기본 격리 수준인 REPEATABLE READ보다 한 단계 낮은 격리 수준이다.

핵심 원칙은 **"커밋된 데이터만 읽는다"** 는 것이다. 다른 트랜잭션이 아직 커밋하지 않은 변경 사항(Dirty Data)은 절대 읽지 않는다.

MySQL InnoDB에서 READ COMMITTED는 **매번 새로운 스냅샷을 생성**한다. 이는 REPEATABLE READ가 트랜잭션 시작 시점의 스냅샷을 유지하는 것과 대조적이다.

```
REPEATABLE READ: 트랜잭션 시작 시 스냅샷 고정 → 트랜잭션 내내 동일한 데이터 조회
READ COMMITTED:  SELECT 실행 시마다 새 스냅샷 생성 → 그 시점에 커밋된 최신 데이터 조회
```

#### Dirty Read 방지, Non-Repeatable Read 허용

| 현상 | READ UNCOMMITTED | READ COMMITTED | REPEATABLE READ | SERIALIZABLE |
|------|:-:|:-:|:-:|:-:|
| Dirty Read | 발생 | **방지** | 방지 | 방지 |
| Non-Repeatable Read | 발생 | **발생** | 방지 | 방지 |
| Phantom Read | 발생 | 발생 | 부분 방지(InnoDB) | 방지 |

**Non-Repeatable Read 예시:**

```sql
-- 트랜잭션 T1 (READ COMMITTED)
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 결과: 10,000원

-- 다른 트랜잭션 T2가 이 사이에 UPDATE + COMMIT
-- T2: UPDATE accounts SET balance = 5000 WHERE id = 1; COMMIT;

SELECT balance FROM accounts WHERE id = 1;  -- 결과: 5,000원 (같은 트랜잭션 내 다른 결과)
COMMIT;
```

같은 트랜잭션 내에서 동일한 SELECT를 두 번 실행했는데 결과가 달라진다. 이것이 Non-Repeatable Read이다.

#### MVCC 기반 스냅샷 읽기와 커밋된 데이터만 조회

InnoDB는 MVCC(Multi-Version Concurrency Control)를 통해 잠금 없이 일관된 읽기를 제공한다.

- **Undo Log**: 행이 변경될 때 이전 버전을 Undo Log에 기록해 두고, 각 트랜잭션의 격리 수준에 따라 어느 버전을 읽을지 결정한다.
- **READ COMMITTED에서의 MVCC**: 각 SELECT마다 Undo Log를 통해 해당 시점에 커밋된 가장 최신 버전을 읽는다.
- **잠금 최소화**: READ COMMITTED는 Gap Lock을 사용하지 않고 레코드 락만 사용하므로 동시성이 높고 데드락 발생 확률이 낮다.

```
행 최신 버전 (아직 커밋 안 됨, T2가 보유 중)
    ↓ (Undo Log 체인)
커밋된 이전 버전  ← READ COMMITTED는 이 버전을 읽음
    ↓
더 이전 버전
```

---

### 2. Spring @Transactional propagation 종류 정리

#### REQUIRED (기본값) vs REQUIRES_NEW

| 항목 | REQUIRED | REQUIRES_NEW |
|------|----------|--------------|
| 동작 | 기존 트랜잭션이 있으면 참여, 없으면 새로 생성 | 항상 새 트랜잭션 생성 (기존 것 일시 중단) |
| 물리 트랜잭션 | 공유 | 독립적 |
| DB Connection | 동일한 커넥션 사용 | 별도 커넥션 획득 |
| 내부 롤백이 외부에 미치는 영향 | UnexpectedRollbackException 발생 가능 | 영향 없음 |
| 리소스 사용 | 적음 | 많음 (커넥션 2개 점유) |

#### 전체 propagation 간단 비교

| propagation | 설명 |
|-------------|------|
| `REQUIRED` | 기존 트랜잭션 참여, 없으면 새 트랜잭션 생성 (기본값) |
| `REQUIRES_NEW` | 항상 새 트랜잭션 생성, 기존 트랜잭션 일시 중단 |
| `NESTED` | 기존 트랜잭션 내 중첩 트랜잭션(Savepoint), 없으면 새 트랜잭션 |
| `SUPPORTS` | 기존 트랜잭션이 있으면 참여, 없으면 트랜잭션 없이 실행 |
| `NOT_SUPPORTED` | 트랜잭션 없이 실행, 기존 트랜잭션 일시 중단 |
| `MANDATORY` | 기존 트랜잭션이 반드시 있어야 함, 없으면 예외 |
| `NEVER` | 트랜잭션이 없어야 함, 있으면 예외 |

---

### 3. REQUIRES_NEW의 동작 원리

#### 기존 트랜잭션 일시 중단(suspend)과 새 트랜잭션 시작

Spring의 `PlatformTransactionManager`는 REQUIRES_NEW를 만나면 현재 진행 중인 트랜잭션을 `TransactionSynchronizationManager`에 보관(suspend)하고 새로운 트랜잭션을 시작한다.

```
[외부 트랜잭션 시작]
    → DB Connection A 획득
    → 작업 수행
    → REQUIRES_NEW 메서드 호출
        → 외부 트랜잭션 suspend (Connection A 유지, 잠시 대기)
        → DB Connection B 획득 (새 커넥션)
        → 내부 트랜잭션에서 작업 수행
        → 내부 트랜잭션 COMMIT 또는 ROLLBACK
        → Connection B 반환
    → 외부 트랜잭션 resume (Connection A로 계속 작업)
    → 외부 트랜잭션 COMMIT 또는 ROLLBACK
    → Connection A 반환
```

#### 별도의 DB Connection 사용 여부

**REQUIRES_NEW는 반드시 별도의 DB Connection을 사용한다.** Spring의 공식 문서에서도 이를 명시하고 있다.

내부 트랜잭션이 실행되는 동안 외부 트랜잭션의 Connection은 반환되지 않고 Pool에서 계속 점유 상태다. 따라서 동시에 두 개의 커넥션이 필요하다.

```java
@Service
public class OrderService {

    @Transactional  // REQUIRED - Connection A 사용
    public void placeOrder(Order order) {
        orderRepository.save(order);          // Connection A로 INSERT
        auditService.log("주문 생성", order); // 내부에서 Connection B 사용
        // 이 시점: Connection A와 B 동시 점유
        paymentService.charge(order);         // Connection A로 계속 작업
    }
}

@Service
public class AuditService {

    @Transactional(propagation = Propagation.REQUIRES_NEW) // Connection B 사용
    public void log(String action, Order order) {
        auditRepository.save(new AuditLog(action, order.getId()));
        // 이 메서드가 완료되면 Connection B 반환
    }
}
```

#### 내부 트랜잭션 커밋/롤백이 외부 트랜잭션에 미치는 영향

REQUIRES_NEW로 시작된 내부 트랜잭션의 커밋/롤백은 **외부 트랜잭션과 완전히 독립적**이다.

```java
@Transactional
public void outer() {
    // 1. 외부 트랜잭션 작업
    userRepository.save(user);

    try {
        inner(); // REQUIRES_NEW - 별도 트랜잭션
    } catch (Exception e) {
        // 내부 트랜잭션이 롤백되어도 외부 트랜잭션은 계속 진행 가능
        log.warn("내부 트랜잭션 실패, 외부 트랜잭션은 계속 진행");
    }

    // 2. 외부 트랜잭션은 user 저장을 커밋할 수 있음
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void inner() {
    // 이 예외는 내부 트랜잭션만 롤백시킴
    throw new RuntimeException("내부 트랜잭션 오류");
}
```

반대로 외부 트랜잭션이 롤백되어도, 이미 커밋된 내부 트랜잭션의 데이터는 DB에 영구 반영된다.

---

### 4. READ COMMITTED + REQUIRES_NEW 조합 시 발생하는 시나리오

#### 외부 트랜잭션에서 변경한 데이터를 내부 트랜잭션(REQUIRES_NEW)에서 읽을 수 있는가?

**읽을 수 없다.** 외부 트랜잭션은 아직 커밋되지 않았고, READ COMMITTED는 커밋된 데이터만 읽기 때문이다.

```
외부 TX (Connection A):
  UPDATE accounts SET balance = 5000 WHERE id = 1;  -- 아직 커밋 안 됨

내부 TX (Connection B, REQUIRES_NEW):
  SELECT balance FROM accounts WHERE id = 1;
  → 결과: 10,000원 (외부 TX 커밋 전이므로 변경 전 값을 읽음)
```

이것이 REQUIRES_NEW를 사용할 때 데이터 정합성에서 주의해야 할 핵심 포인트다. 외부 트랜잭션이 변경한 내용을 내부 트랜잭션에서 읽으려면 외부 트랜잭션이 먼저 커밋되어야 한다.

#### 내부 트랜잭션 커밋 후 외부 트랜잭션에서 해당 데이터를 조회하면?

**READ COMMITTED에서는 읽을 수 있다.** 내부 트랜잭션이 커밋되었으므로 그 데이터는 "커밋된 데이터"이고, READ COMMITTED는 매 SELECT마다 최신 커밋 스냅샷을 읽는다.

```
내부 TX (Connection B, REQUIRES_NEW):
  UPDATE accounts SET balance = 3000 WHERE id = 1;
  COMMIT;  ← 커밋 완료

외부 TX (Connection A, READ COMMITTED):
  SELECT balance FROM accounts WHERE id = 1;
  → 결과: 3,000원 (내부 TX가 커밋했으므로 최신 값 읽음)
```

만약 외부 트랜잭션의 격리 수준이 REPEATABLE READ라면, 트랜잭션 시작 시점의 스냅샷을 유지하므로 내부 트랜잭션이 커밋해도 외부 트랜잭션은 변경 전 값(10,000원)을 계속 읽는다.

#### 동일 레코드에 대한 Lock 경합(Deadlock 가능성)

REQUIRES_NEW는 Deadlock의 위험성을 높인다. 두 트랜잭션이 서로 상대방이 가진 Lock을 기다리는 상황이 발생할 수 있다.

```
외부 TX (A): accounts 레코드 1번에 X-Lock 획득 후 아직 커밋 안 함
내부 TX (B): accounts 레코드 1번에 X-Lock 시도 → 외부 TX가 Lock을 가지고 있으므로 대기

문제: 외부 TX는 내부 TX가 완료될 때까지 suspend 상태
     내부 TX는 외부 TX의 Lock이 해제될 때까지 대기
     → 서로를 기다리는 Deadlock 발생!
```

실제 예시 코드:

```java
@Transactional  // 외부 TX: accounts 레코드에 X-Lock
public void processPayment(Long accountId) {
    Account account = accountRepository.findByIdWithLock(accountId); // SELECT FOR UPDATE
    account.deduct(1000);
    accountRepository.save(account);

    // 내부 TX에서 같은 레코드 접근 시도 → Deadlock!
    auditService.recordPayment(accountId, 1000);
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordPayment(Long accountId, int amount) {
    Account account = accountRepository.findByIdWithLock(accountId); // 외부 TX의 X-Lock 대기 → Deadlock
    // ...
}
```

이런 패턴은 반드시 피해야 한다. REQUIRES_NEW 내부에서 외부 트랜잭션이 잠근 레코드에 접근하지 않도록 설계해야 한다.

---

### 5. 실무에서 REQUIRES_NEW를 사용하는 대표 사례

#### 독립적 로깅/이력 저장 (외부 트랜잭션 롤백과 무관하게 기록 유지)

비즈니스 로직이 실패해서 트랜잭션이 롤백되더라도, 실패 이력이나 감사 로그는 반드시 남겨야 하는 경우가 있다.

```java
@Service
public class TransferService {

    @Transactional
    public void transfer(Long fromId, Long toId, BigDecimal amount) {
        try {
            accountRepository.deduct(fromId, amount);
            accountRepository.add(toId, amount);
        } catch (Exception e) {
            // 비즈니스 로직 실패 → 외부 트랜잭션 롤백 예정
            auditService.recordFailure(fromId, toId, amount, e.getMessage());
            // auditService는 REQUIRES_NEW이므로 커밋됨
            throw e;
        }
    }
}

@Service
public class AuditService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long from, Long to, BigDecimal amount, String reason) {
        // 외부 트랜잭션이 롤백되어도 이 로그는 DB에 영구 저장됨
        auditRepository.save(new TransferFailureLog(from, to, amount, reason));
    }
}
```

#### 결제/포인트 차감 등 부분 커밋이 필요한 경우

주문 처리에서 결제는 성공했지만 재고 차감에 실패했을 때, 결제 내역만 별도로 남겨야 하는 경우가 있다.

```java
@Transactional
public void processOrder(Order order) {
    inventoryService.deductStock(order);   // 재고 차감 (외부 TX)

    // 결제는 독립 트랜잭션으로 처리
    // 결제 성공 후 재고 차감 실패 시, 결제 내역은 남기고 환불 처리 가능
    paymentService.charge(order);
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public PaymentResult charge(Order order) {
    Payment payment = paymentGateway.process(order.getAmount());
    paymentRepository.save(payment);
    return PaymentResult.of(payment);
}
```

#### 이벤트 발행과 트랜잭션 분리

외부 시스템과의 연동(이메일 발송, 슬랙 알림 등)을 트랜잭션과 분리해야 할 때 REQUIRES_NEW를 활용할 수 있다. 단, 이 경우 `@TransactionalEventListener`를 함께 사용하는 패턴이 더 권장된다 (7번 섹션 참고).

```java
@Transactional
public void registerUser(User user) {
    userRepository.save(user);
    notificationService.sendWelcomeEmail(user); // REQUIRES_NEW로 별도 처리
}
```

---

### 6. REQUIRES_NEW 사용 시 주의사항과 안티패턴

#### Connection Pool 고갈 위험 (트랜잭션 중첩 시 2개 커넥션 점유)

REQUIRES_NEW는 외부 트랜잭션의 커넥션을 유지한 채로 새 커넥션을 획득한다. 트랜잭션 중첩이 깊어질수록 동시에 점유하는 커넥션 수가 늘어난다.

```
스레드 1: 외부 TX(Con A) → 내부 TX(Con B) → 더 내부 TX(Con C)  = 3개 커넥션 점유
스레드 2: 외부 TX(Con D) → 내부 TX(Con E) → 더 내부 TX(Con F)  = 3개 커넥션 점유
...

Connection Pool 크기: 10
동시 요청 수: 5
→ 필요 커넥션: 15 → Pool 고갈!
```

이로 인해 Connection Pool에서 커넥션을 기다리는 스레드들이 쌓이고, 서비스 전체가 멈추는 장애로 이어질 수 있다.

**대처 방법:**
- Connection Pool 크기를 충분히 설정 (`maximumPoolSize >= 동시 요청 수 × 트랜잭션 중첩 깊이`)
- REQUIRES_NEW 남용을 피하고 필요한 곳에만 사용
- HikariCP의 `connectionTimeout` 설정으로 무한 대기 방지

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 3000  # 3초 이내에 커넥션 획득 못하면 예외 발생
```

#### Self-invocation 문제 (같은 클래스 내 호출 시 프록시 우회)

Spring의 `@Transactional`은 AOP 프록시 기반으로 동작한다. 같은 클래스 내에서 `this.method()`로 호출하면 프록시를 거치지 않아 `@Transactional`이 무시된다.

```java
@Service
public class OrderService {

    @Transactional
    public void placeOrder(Order order) {
        orderRepository.save(order);
        this.sendNotification(order); // ← 프록시 우회! REQUIRES_NEW 무시됨
        // 결과: 외부 트랜잭션에 그냥 참여함
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendNotification(Order order) {
        // 의도: 새 트랜잭션으로 실행
        // 실제: 외부 트랜잭션에 참여 (Self-invocation으로 인해 프록시 미동작)
        notificationRepository.save(new Notification(order));
    }
}
```

**해결 방법:**

```java
// 방법 1: 별도 클래스로 분리 (권장)
@Service
public class NotificationService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendNotification(Order order) {
        notificationRepository.save(new Notification(order));
    }
}

// 방법 2: ApplicationContext에서 자기 자신의 프록시를 가져와 호출 (비권장)
@Service
public class OrderService {

    @Autowired
    private ApplicationContext context;

    @Transactional
    public void placeOrder(Order order) {
        orderRepository.save(order);
        context.getBean(OrderService.class).sendNotification(order); // 프록시 경유
    }
}
```

#### 불필요한 REQUIRES_NEW 남용으로 인한 데이터 정합성 깨짐

REQUIRES_NEW는 트랜잭션이 분리되기 때문에, 외부 트랜잭션이 롤백되어도 내부 트랜잭션의 커밋은 취소되지 않는다. 이를 무분별하게 사용하면 데이터 정합성이 깨진다.

```java
// 안티패턴 예시
@Transactional
public void createUserWithProfile(User user, Profile profile) {
    userRepository.save(user);
    profileService.createProfile(user.getId(), profile); // REQUIRES_NEW로 커밋

    // 이후 비즈니스 로직에서 예외 발생 → 외부 트랜잭션 롤백
    // 결과: user는 롤백되었지만 profile은 이미 커밋됨 → 고아 profile 발생
    validateUserLimit(); // 예외 던짐
}
```

REQUIRES_NEW는 "이 작업은 외부 트랜잭션의 성공/실패와 무관하게 독립적으로 커밋되어야 한다"는 명확한 비즈니스 요구사항이 있을 때만 사용해야 한다.

---

### 7. REQUIRES_NEW vs EventListener(@TransactionalEventListener) 비교

#### 트랜잭션 분리의 두 가지 접근법

**방법 1: REQUIRES_NEW**

```java
@Transactional
public void completeOrder(Order order) {
    order.complete();
    orderRepository.save(order);
    emailService.sendConfirmation(order); // REQUIRES_NEW - 동기 실행
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void sendConfirmation(Order order) {
    emailRepository.save(new EmailLog(order));
    smtpClient.send(order.getUserEmail(), "주문 완료");
}
```

**방법 2: @TransactionalEventListener**

```java
@Transactional
public void completeOrder(Order order) {
    order.complete();
    orderRepository.save(order);
    // 이벤트만 발행 (실제 처리는 커밋 후 트리거)
    eventPublisher.publishEvent(new OrderCompletedEvent(order));
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void onOrderCompleted(OrderCompletedEvent event) {
    // 외부 트랜잭션 커밋 완료 후 새 트랜잭션으로 실행
    emailRepository.save(new EmailLog(event.getOrder()));
    smtpClient.send(event.getOrder().getUserEmail(), "주문 완료");
}
```

#### 각각의 장단점과 적합한 상황

| 항목 | REQUIRES_NEW | @TransactionalEventListener |
|------|-------------|------------------------------|
| 실행 타이밍 | 호출 시점에 즉시 실행 | 외부 트랜잭션 커밋/롤백 이후 실행 |
| 외부 TX 커밋 전 내부 TX 실행 | 가능 | 불가능 (AFTER_COMMIT 기준) |
| 결합도 | 직접 의존 (강결합) | 이벤트 기반 (약결합) |
| 외부 TX 데이터 보장 | 외부 TX 미커밋 데이터 조회 불가 | 커밋된 데이터 안전하게 조회 가능 |
| 예외 처리 | 호출자가 직접 try-catch | 이벤트 핸들러 내 예외 처리 |
| 비동기 처리 | `@Async` 별도 조합 필요 | `@Async` 조합으로 간편하게 비동기 전환 |
| 테스트 용이성 | 비교적 단순 | 이벤트 발행/구독 구조 이해 필요 |

**적합한 상황:**

- **REQUIRES_NEW**: 내부 작업 결과(반환값)가 외부 트랜잭션에 즉시 필요하거나, 외부 트랜잭션 커밋 전에 별도 커밋이 되어야 하는 경우 (예: 분산 시스템의 사전 작업)
- **@TransactionalEventListener**: 외부 트랜잭션 커밋을 보장한 후 후처리(이메일, 알림, 통계)를 수행하는 경우. 도메인 이벤트 기반 아키텍처(DDD)에 적합

**@TransactionalEventListener 주의사항:**

`@TransactionalEventListener(phase = AFTER_COMMIT)`으로 등록된 핸들러에서 DB 작업을 하려면 반드시 `@Transactional(propagation = REQUIRES_NEW)`를 함께 붙여야 한다. 외부 트랜잭션이 이미 완료된 상태이므로 기본 REQUIRED로는 트랜잭션이 열리지 않기 때문이다.

```java
// 잘못된 예시 - DB 작업이 트랜잭션 없이 실행됨
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderCompleted(OrderCompletedEvent event) {
    emailRepository.save(new EmailLog(event.getOrder())); // 트랜잭션 없음!
}

// 올바른 예시
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW) // 새 트랜잭션 명시
public void onOrderCompleted(OrderCompletedEvent event) {
    emailRepository.save(new EmailLog(event.getOrder())); // 새 트랜잭션으로 실행
}
```

---

## 핵심 정리

- READ COMMITTED에서 REQUIRES_NEW 내부 트랜잭션은 **외부 트랜잭션이 아직 커밋하지 않은 데이터를 읽을 수 없다.** 반대로, 내부 트랜잭션이 커밋한 데이터는 외부 트랜잭션에서 바로 읽힐 수 있다(Non-Repeatable Read). 이 두 가지 특성을 항상 염두에 두고 데이터 정합성을 설계해야 한다.
- REQUIRES_NEW는 **반드시 별도의 DB Connection을 점유**하므로, 트랜잭션 중첩 시 Connection Pool 고갈 위험이 있다. 명확한 비즈니스 요구사항(로그/감사는 항상 남겨야 함 등)이 있을 때만 사용하고, Self-invocation 함정을 피하기 위해 별도 클래스로 분리하는 것이 원칙이다.

## 키워드

- `Transaction Isolation Level`: 여러 트랜잭션이 동시에 실행될 때 서로에게 얼마나 영향을 주는지를 정의하는 4단계 격리 수준 (READ UNCOMMITTED / READ COMMITTED / REPEATABLE READ / SERIALIZABLE)
- `READ COMMITTED`: 커밋된 데이터만 읽는 격리 수준. 매 SELECT마다 새 스냅샷을 사용하므로 Non-Repeatable Read가 발생하지만, Dirty Read는 방지
- `REQUIRES_NEW`: Spring @Transactional propagation 옵션. 기존 트랜잭션을 일시 중단하고 항상 새로운 독립 물리 트랜잭션을 시작함
- `Spring @Transactional`: 스프링에서 선언적으로 트랜잭션을 관리하는 어노테이션. AOP 프록시 기반으로 동작
- `Propagation`: Spring 트랜잭션이 기존 트랜잭션과 어떻게 관계를 맺을지 정의하는 전파 설정 (REQUIRED, REQUIRES_NEW, NESTED 등)
- `MVCC`: Multi-Version Concurrency Control. 데이터의 여러 버전을 유지해 잠금 없이 일관된 읽기를 제공하는 동시성 제어 기법
- `Connection Pool`: DB 커넥션을 미리 생성해 풀로 관리하는 기법. REQUIRES_NEW 중첩 시 커넥션을 2개 이상 점유하므로 고갈 위험 존재
- `Deadlock`: 두 트랜잭션이 서로 상대방이 보유한 Lock을 기다려 영원히 진행하지 못하는 교착 상태. REQUIRES_NEW + 동일 레코드 Lock 접근 시 발생 가능
- `Self-invocation`: 같은 클래스 내에서 this로 메서드를 호출할 때 AOP 프록시가 동작하지 않아 @Transactional이 무시되는 현상
- `TransactionalEventListener`: Spring 이벤트 리스너를 트랜잭션 생명주기(BEFORE_COMMIT, AFTER_COMMIT 등)에 연동시키는 어노테이션. REQUIRES_NEW보다 약결합 방식으로 트랜잭션을 분리할 수 있음

## 참고 자료

- [Spring Framework - Transaction Propagation (공식 문서)](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [MySQL 8.4 Reference Manual - Transaction Isolation Levels (공식 문서)](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)
- [Baeldung - Transaction Propagation and Isolation in Spring @Transactional](https://www.baeldung.com/spring-transactional-propagation-isolation)
- [Medium - Transactional REQUIRES_NEW considered harmful](https://medium.com/@paul.klingelhuber/transactional-requires-new-considered-harmful-spring-java-transaction-handling-pitfalls-3ed109b3f4f5)
- [Medium - DB Lock Issues with Transactional REQUIRES_NEW](https://medium.com/@paul.klingelhuber/db-lock-issues-with-transactional-requires-new-more-spring-java-transaction-handling-pitfalls-e6430d8a8d30)
- [PlanetScale - MySQL isolation levels and how they work](https://planetscale.com/blog/mysql-isolation-levels-and-how-they-work)
