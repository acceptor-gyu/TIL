# @Transactional propagation 옵션과 보상 트랜잭션 구체적인 상황 예시

## 개요

Spring의 트랜잭션 전파(Propagation) 옵션을 실제 상황과 함께 정리하고, 단일 트랜잭션으로 묶을 수 없는 분산/외부 연동 환경에서 보상 트랜잭션(Compensating Transaction)이 필요한 이유와 구현 방식을 학습한다.

propagation 옵션 자체의 동작 원리와 CGLIB 프록시와의 관계는 [이전 TIL](260621_02_@Transactional_propagation_옵션과_CGLIB_프록시.md)에서 다뤘다. 이 문서는 **"어떤 상황에서 어떤 옵션을 쓰는가"** 와 **"DB 트랜잭션을 벗어나는 작업을 어떻게 다루는가"** 에 집중한다.

## 상세 내용

### 1. 트랜잭션 전파(Propagation)란

#### 물리 트랜잭션 vs 논리 트랜잭션

전파 옵션을 이해하기 전에 두 개념을 먼저 구분해야 한다.

| 개념 | 설명 |
|------|------|
| **물리 트랜잭션** | 실제 DB 커넥션과 연결된 트랜잭션. `BEGIN` ~ `COMMIT/ROLLBACK` 한 쌍. 커넥션 1개를 점유한다. |
| **논리 트랜잭션** | Spring이 메서드 단위로 관리하는 트랜잭션 스코프. 여러 논리 트랜잭션이 하나의 물리 트랜잭션에 참여할 수 있다. |

```
[외부 메서드 @Transactional]  ← 논리 트랜잭션 1 (물리 트랜잭션 A 시작)
    │
    └── [내부 메서드 @Transactional(REQUIRED)]  ← 논리 트랜잭션 2 (물리 트랜잭션 A에 참여)

결과: 물리 트랜잭션 A 1개로 함께 커밋/롤백
```

논리 트랜잭션 하나가 rollback-only 마킹을 하면, 같은 물리 트랜잭션에 참여한 모든 논리 트랜잭션이 함께 롤백된다.

---

### 2. Propagation 옵션별 동작과 상황 예시

#### REQUIRED (기본값): 기존 트랜잭션에 참여, 없으면 새로 생성

```
부모 트랜잭션 O → 같은 물리 트랜잭션에 참여
부모 트랜잭션 X → 새 물리 트랜잭션 시작
```

**상황 예시: 주문 저장 + 주문 상세 저장을 하나로 묶기**

주문(Order)을 저장하고 주문 상세(OrderItem)를 저장하는 두 메서드가 있다. 주문 상세 저장이 실패하면 주문 자체도 롤백되어야 한다. 두 작업이 단일 물리 트랜잭션에 묶여야 하므로 REQUIRED가 적합하다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemService orderItemService;

    @Transactional // REQUIRED (기본값)
    public void placeOrder(OrderRequest request) {
        Order order = orderRepository.save(request.toOrder());

        // 같은 트랜잭션에 참여 → 둘 중 하나라도 실패하면 함께 롤백
        orderItemService.saveItems(order.getId(), request.getItems());
    }
}

@Service
public class OrderItemService {

    @Transactional // REQUIRED → 외부 트랜잭션에 참여
    public void saveItems(Long orderId, List<OrderItemRequest> items) {
        items.forEach(item -> orderItemRepository.save(item.toEntity(orderId)));
    }
}
```

**rollback-only 마킹과 UnexpectedRollbackException**

REQUIRED로 참여한 내부 논리 트랜잭션에서 예외가 발생하면 해당 물리 트랜잭션 전체에 rollback-only 마크가 설정된다. 외부에서 예외를 잡아도 이미 마크가 설정되어 있어 외부 트랜잭션의 커밋 시 `UnexpectedRollbackException`이 발생한다.

```java
@Transactional
public void outer() {
    try {
        inner(); // 내부에서 RuntimeException → rollback-only 마크 설정
    } catch (RuntimeException e) {
        // 예외를 잡아도 rollback-only 마크는 이미 설정됨
        log.warn("inner failed: {}", e.getMessage());
    }
    // 커밋 시도 → UnexpectedRollbackException 발생
}

@Transactional // REQUIRED → outer의 물리 트랜잭션에 참여
public void inner() {
    throw new RuntimeException("내부 예외");
}
```

이 상황에서 내부 예외가 외부로 전파되지 않으면서 외부 트랜잭션을 살리고 싶다면 `REQUIRES_NEW` 또는 `NESTED`로 전환해야 한다.

---

#### REQUIRES_NEW: 항상 새 트랜잭션 생성, 기존 트랜잭션은 일시 중단

```
부모 트랜잭션 O → 부모 suspend + 새 물리 트랜잭션 시작 (커넥션 2개 점유)
부모 트랜잭션 X → 새 물리 트랜잭션 시작
```

**상황 예시: 실패해도 남겨야 하는 이력/로그 저장**

주문 처리가 실패하더라도 처리 시도 이력(AuditLog)은 반드시 DB에 남아야 한다. 주문 트랜잭션이 롤백되면 같은 트랜잭션에 참여한 이력 저장도 함께 롤백되므로 REQUIRES_NEW로 분리해야 한다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final AuditLogService auditLogService; // 반드시 외부 빈으로 분리

    @Transactional
    public void placeOrder(OrderRequest request) {
        try {
            Order order = orderRepository.save(request.toOrder());
            // ... 후속 처리
        } catch (Exception e) {
            // 실패 이력은 독립 트랜잭션으로 저장 → 주문 롤백과 무관하게 커밋됨
            auditLogService.saveFailureLog("ORDER_FAILED", e.getMessage());
            throw e;
        }
    }
}

@Service
public class AuditLogService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveFailureLog(String action, String reason) {
        // 독립된 물리 트랜잭션 → 주문 트랜잭션이 롤백되어도 이 커밋은 유지됨
        auditLogRepository.save(new AuditLog(action, reason, LocalDateTime.now()));
    }
}
```

**주의: 커넥션 풀 고갈**

REQUIRES_NEW는 부모 트랜잭션의 커넥션을 반환하지 않은 채 새 커넥션을 획득한다. 동시 요청이 늘어날수록 커넥션을 2배로 소비한다.

```
스레드 10개, 커넥션 풀 크기 10인 환경
→ 모든 스레드가 REQUIRES_NEW 진입 시 커넥션 20개 필요
→ 풀 고갈 → 교착 상태(Deadlock)
```

커넥션 풀을 충분히 크게 설정하거나, 아래에서 다루는 `@TransactionalEventListener(AFTER_COMMIT)` 패턴으로 커넥션 동시 점유 자체를 제거하는 것이 근본 해결책이다.

---

#### NESTED: 부모 트랜잭션 내 Savepoint 기반 중첩

```
부모 트랜잭션 O → 같은 물리 트랜잭션 + Savepoint 생성
부모 트랜잭션 X → REQUIRED처럼 새 트랜잭션 시작
```

**REQUIRES_NEW vs NESTED 비교**

| 구분 | REQUIRES_NEW | NESTED |
|------|-------------|--------|
| 물리 트랜잭션 | 2개 (독립) | 1개 공유 |
| 커넥션 수 | 2개 동시 점유 | 1개 |
| 내부 롤백 시 외부 영향 | 없음 | 없음 (Savepoint 복원) |
| 외부 롤백 시 내부 영향 | 없음 (이미 독립 커밋) | 함께 롤백 |
| JPA 환경 지원 | O | X (DataSourceTransactionManager 전용) |
| 커밋 시점 | 내부 트랜잭션 종료 시 즉시 커밋 | 부모 트랜잭션 커밋 시 함께 커밋 |

**상황 예시: 선택적 부분 롤백 (JDBC 환경)**

상품 이미지를 저장할 때 썸네일 저장이 실패해도 원본 이미지 저장은 유지하고 싶다. 썸네일 저장만 롤백하고 부모 트랜잭션은 계속 진행해야 한다.

```java
@Transactional
public void saveProductImages(Long productId, ImageRequest request) {
    // 원본 이미지 저장 (부모 트랜잭션)
    imageRepository.save(Image.original(productId, request.getUrl()));

    try {
        // 썸네일 저장 → 실패 시 Savepoint까지만 롤백
        thumbnailService.saveThumbnail(productId, request.getUrl());
    } catch (Exception e) {
        // 썸네일 저장 실패는 무시, 원본 저장은 유지
        log.warn("썸네일 저장 실패, 원본만 저장: {}", e.getMessage());
    }
}

// ThumbnailService (JDBC 환경, DataSourceTransactionManager 사용 시)
@Transactional(propagation = Propagation.NESTED)
public void saveThumbnail(Long productId, String originalUrl) {
    String thumbnailUrl = generateThumbnail(originalUrl); // 실패 가능
    thumbnailRepository.save(Thumbnail.of(productId, thumbnailUrl));
}
```

JPA 환경에서는 NESTED 대신 별도 빈으로 분리한 후 REQUIRES_NEW를 사용하여 유사한 효과를 낸다.

---

#### MANDATORY / SUPPORTS / NOT_SUPPORTED / NEVER

**상황 예시 모음**

```java
// MANDATORY: 반드시 트랜잭션 내에서 호출되어야 하는 내부 메서드
// 독립 호출 시 실수를 컴파일 타임이 아닌 런타임에 잡아줌
@Transactional(propagation = Propagation.MANDATORY)
public void deductStock(Long productId, int quantity) {
    // 외부에서 단독 호출 → IllegalTransactionStateException
    // 반드시 다른 @Transactional 메서드 내에서 호출해야 함
    stockRepository.deductStock(productId, quantity);
}

// SUPPORTS: 트랜잭션이 있으면 참여, 없으면 그냥 실행 (조회용)
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
public ProductDto findProduct(Long productId) {
    return productRepository.findById(productId).map(ProductDto::from)
            .orElseThrow(ProductNotFoundException::new);
}

// NOT_SUPPORTED: 트랜잭션 없이 실행해야 하는 대용량 배치 읽기
// 트랜잭션 오버헤드를 피하고 싶을 때, 또는 트랜잭션 격리가 불필요할 때
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public List<ProductSummary> exportAllProducts() {
    return productRepository.findAllForExport(); // 커서 기반 대용량 읽기
}

// NEVER: 외부 호출이나 비동기 작업에서 트랜잭션 개입을 명시적으로 금지
@Transactional(propagation = Propagation.NEVER)
public void sendExternalNotification(String message) {
    // 트랜잭션 컨텍스트에서 실행되면 안 됨을 명시적으로 표현
    externalNotificationClient.send(message);
}
```

---

### 3. 단일 트랜잭션의 한계

#### DB 트랜잭션 경계를 벗어나는 작업

DB 트랜잭션은 동일한 DB 커넥션 안에서만 원자성을 보장한다. 아래 작업들은 트랜잭션이 제어할 수 없다.

```
1. 외부 HTTP API 호출 (결제 PG, 택배사 API, 외부 인증 서버)
2. 메시지 발행 (Kafka produce, SQS send)
3. 파일 시스템 조작 (S3 업로드, 로컬 파일 쓰기)
4. 이메일/SMS/푸시 알림 전송
5. 다른 DB 인스턴스에 대한 쓰기
```

예를 들어 결제 PG API를 호출해 결제를 승인하고 나서 DB 저장에 실패하면, PG 쪽은 이미 결제가 완료된 상태이다. `@Transactional`로 DB 저장을 롤백해도 PG 결제 승인은 되돌릴 수 없다.

```java
@Transactional
public void processPayment(PaymentRequest request) {
    // 1. PG 결제 승인 요청 → 실제 돈이 빠져나감
    PaymentResult result = pgClient.approve(request); // HTTP 호출, 롤백 불가

    // 2. DB 저장 → 예외 발생 시 롤백
    paymentRepository.save(Payment.from(result)); // <- 여기서 예외 발생

    // 트랜잭션 롤백 → DB는 원래대로 되돌아가지만 PG 결제는 그대로
    // 사용자 돈은 나갔는데 주문 기록은 없는 상태가 됨
}
```

#### 분산 환경(MSA)에서 2PC를 지양하는 이유

분산 트랜잭션의 표준 해법은 2PC(2-Phase Commit)이지만 MSA 환경에서는 잘 사용하지 않는다.

| 문제 | 설명 |
|------|------|
| **가용성 저하** | 코디네이터가 다운되면 모든 참여 서비스가 블로킹됨 |
| **성능** | 모든 서비스가 응답할 때까지 락을 유지해야 함 |
| **기술 이질성** | 모든 참여 서비스가 XA 프로토콜을 지원해야 함 |
| **강결합** | 서비스 간 강한 의존성 형성 |

이를 해결하기 위해 등장한 것이 **보상 트랜잭션(Compensating Transaction)** 과 **SAGA 패턴**이다.

---

### 4. 보상 트랜잭션(Compensating Transaction)

#### 정의

이미 커밋된 작업을 되돌리기 위해 **반대 동작을 수행하는 별도의 트랜잭션**이다. 트랜잭션 롤백이 아닌 "취소 작업(undo operation)"을 새로 실행한다.

#### 롤백 vs 보상 트랜잭션

| 구분 | 트랜잭션 롤백 | 보상 트랜잭션 |
|------|-------------|-------------|
| 시점 | 커밋 전 | 커밋 이후 |
| 메커니즘 | DB 엔진이 undo log 적용 | 애플리케이션이 반대 동작 실행 |
| 원자성 | 보장 (all-or-nothing) | 미보장 (best-effort) |
| 일관성 | 즉시 일관성 | 최종 일관성 (Eventual Consistency) |
| 외부 작업 | 되돌릴 수 없음 | 보상 로직으로 되돌리려고 시도 |

---

### 5. 구체적인 상황 예시

#### 예시 1: 결제 승인 후 주문 저장 실패 → 결제 취소(환불) 보상

```java
@Service
@RequiredArgsConstructor
public class OrderPaymentService {

    private final PgClient pgClient;
    private final OrderRepository orderRepository;
    private final PaymentCompensationService compensationService;

    public void processPaymentAndOrder(PaymentRequest request) {
        // 1. PG 결제 승인 (DB 트랜잭션 밖에서 실행)
        PaymentResult paymentResult = pgClient.approve(request);

        try {
            // 2. 트랜잭션 내 DB 저장
            saveOrderWithTransaction(paymentResult, request);
        } catch (Exception e) {
            // 3. DB 저장 실패 → 보상 트랜잭션: 결제 취소 요청
            log.error("주문 저장 실패, 결제 취소 보상 시작: {}", paymentResult.getTransactionId());
            compensationService.cancelPayment(paymentResult.getTransactionId(), e.getMessage());
            throw new OrderPaymentFailedException("주문 저장 실패로 결제가 취소되었습니다.", e);
        }
    }

    @Transactional
    public void saveOrderWithTransaction(PaymentResult payment, PaymentRequest request) {
        orderRepository.save(Order.of(request, payment));
        // 추가 저장 로직...
    }
}

@Service
public class PaymentCompensationService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelPayment(String transactionId, String reason) {
        // 독립 트랜잭션으로 취소 이력 저장
        compensationLogRepository.save(CompensationLog.cancelPayment(transactionId, reason));

        try {
            // PG에 환불 요청
            pgClient.cancel(transactionId);
            compensationLogRepository.updateStatus(transactionId, CompensationStatus.SUCCESS);
        } catch (Exception e) {
            // 보상 자체 실패 → 재시도 대상으로 마킹, DLQ 발행
            compensationLogRepository.updateStatus(transactionId, CompensationStatus.FAILED);
            eventPublisher.publish(new CompensationFailedEvent(transactionId));
            log.error("결제 취소 보상 실패 - 수동 개입 필요: {}", transactionId, e);
        }
    }
}
```

#### 예시 2: 재고 차감 후 배송 등록 실패 → 재고 복원 보상

```java
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final StockRepository stockRepository;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentCompensationService compensationService;

    @Transactional
    public void processShipment(ShipmentRequest request) {
        // 1. 재고 차감
        stockRepository.deductStock(request.getProductId(), request.getQuantity());

        try {
            // 2. 배송사 API 호출 (트랜잭션 밖의 작업)
            String trackingNumber = courierClient.register(request);

            // 3. 배송 정보 저장
            shipmentRepository.save(Shipment.of(request, trackingNumber));
        } catch (Exception e) {
            // 4. 배송 등록 실패 → 보상: 재고 복원
            // 현재 트랜잭션은 롤백되지만 재고 차감은 이미 이 트랜잭션에 포함
            // → 트랜잭션 롤백으로 재고가 자동 복원됨 (같은 DB 트랜잭션)
            throw new ShipmentRegistrationException("배송 등록 실패", e);
        }
    }
}
```

이 예시에서 재고 차감과 배송 저장이 같은 `@Transactional` 내에 있으면 배송 저장 실패 시 트랜잭션 롤백으로 재고도 자동 복원된다. 보상 트랜잭션이 필요한 것은 **이미 다른 서비스/DB에 커밋된 작업**이 있을 때다.

```java
// MSA 환경에서 재고 서비스가 분리된 경우
public void processShipmentMSA(ShipmentRequest request) {
    // 1. 재고 서비스 HTTP 호출 → 다른 DB에 커밋됨 (롤백 불가)
    stockServiceClient.deductStock(request.getProductId(), request.getQuantity());

    try {
        // 2. 배송 서비스 HTTP 호출
        shipmentServiceClient.register(request);
    } catch (Exception e) {
        // 3. 배송 실패 → 보상: 재고 복원 요청 (보상 트랜잭션)
        stockServiceClient.restoreStock(request.getProductId(), request.getQuantity());
        throw e;
    }
}
```

#### 예시 3: SAGA 패턴 (Choreography vs Orchestration)

SAGA 패턴은 각 서비스가 로컬 트랜잭션을 커밋하고 다음 서비스를 호출하거나 이벤트를 발행하는 방식으로, 분산 트랜잭션을 여러 개의 로컬 트랜잭션 체인으로 대체한다.

**Choreography(안무형)**: 각 서비스가 이벤트를 발행하고 다른 서비스가 이를 구독하여 다음 단계를 실행한다.

```
주문 서비스              재고 서비스              결제 서비스
    │                       │                       │
    │── OrderCreated ──────>│                       │
    │                       │── StockReserved ─────>│
    │                       │                       │── PaymentCompleted
    │                       │                       │
    │<── OrderConfirmed ────────────────────────────│

실패 시 역방향 이벤트:
    │<── PaymentFailed ─────────────────────────────│
    │                       │<── StockReleased ─────│ (보상)
    │── OrderCancelled ────>│                       │ (보상)
```

```java
// 재고 서비스 - Choreography 방식
@Service
@RequiredArgsConstructor
public class StockSagaHandler {

    private final StockRepository stockRepository;
    private final ApplicationEventPublisher eventPublisher;

    @KafkaListener(topics = "order-created")
    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event) {
        try {
            stockRepository.reserveStock(event.getProductId(), event.getQuantity());
            // 성공 이벤트 발행 → 결제 서비스가 구독
            eventPublisher.publishEvent(new StockReservedEvent(event.getOrderId()));
        } catch (InsufficientStockException e) {
            // 실패 이벤트 발행 → 주문 서비스가 구독하여 보상
            eventPublisher.publishEvent(new StockReservationFailedEvent(event.getOrderId()));
        }
    }

    @KafkaListener(topics = "payment-failed")
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        // 보상 트랜잭션: 예약된 재고 복원
        stockRepository.releaseReservedStock(event.getOrderId());
    }
}
```

**Orchestration(오케스트레이션형)**: 중앙 조율자(Saga Orchestrator)가 각 서비스를 순서대로 호출하고 실패 시 보상을 지시한다.

```java
@Service
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

    private final StockServiceClient stockClient;
    private final PaymentServiceClient paymentClient;
    private final ShipmentServiceClient shipmentClient;
    private final SagaLogRepository sagaLogRepository;

    public void executeOrderSaga(OrderRequest request) {
        SagaLog sagaLog = sagaLogRepository.save(SagaLog.start(request.getOrderId()));

        try {
            // Step 1: 재고 예약
            stockClient.reserve(request.getProductId(), request.getQuantity());
            sagaLog.markStep(SagaStep.STOCK_RESERVED);

            // Step 2: 결제 처리
            paymentClient.process(request.getPaymentInfo());
            sagaLog.markStep(SagaStep.PAYMENT_COMPLETED);

            // Step 3: 배송 등록
            shipmentClient.register(request.getShippingInfo());
            sagaLog.markStep(SagaStep.SHIPMENT_REGISTERED);

            sagaLog.markCompleted();
        } catch (Exception e) {
            // 실패한 단계까지 역방향으로 보상 실행
            compensate(sagaLog, request);
        }
    }

    private void compensate(SagaLog sagaLog, OrderRequest request) {
        if (sagaLog.isStepCompleted(SagaStep.PAYMENT_COMPLETED)) {
            paymentClient.cancel(request.getPaymentInfo().getTransactionId());
        }
        if (sagaLog.isStepCompleted(SagaStep.STOCK_RESERVED)) {
            stockClient.release(request.getProductId(), request.getQuantity());
        }
        sagaLog.markFailed();
    }
}
```

| 구분 | Choreography | Orchestration |
|------|-------------|--------------|
| 제어 흐름 | 이벤트 기반, 분산 | 중앙 조율자 |
| 서비스 결합도 | 낮음 | 조율자에 의존 |
| 흐름 파악 | 어려움 | 조율자 코드에서 파악 가능 |
| 실패 추적 | 복잡 | 상대적으로 쉬움 |
| 적합한 규모 | 소규모 단순 흐름 | 복잡한 비즈니스 흐름 |

#### 예시 4: 외부 API 호출과 DB 커밋 순서 설계 (@TransactionalEventListener AFTER_COMMIT 활용)

DB 커밋 전에 외부 API를 호출하면 DB 저장 실패 시 외부 호출 결과를 되돌려야 하는 문제가 생긴다. DB 커밋이 완료된 이후에 외부 API를 호출하면 이 문제를 회피할 수 있다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void placeOrder(OrderRequest request) {
        Order order = orderRepository.save(request.toOrder());

        // 트랜잭션 내에서 이벤트 발행
        // → 실제 실행은 트랜잭션 커밋 이후로 지연됨
        eventPublisher.publishEvent(new OrderPlacedEvent(order.getId()));
    }
}

@Component
@RequiredArgsConstructor
public class OrderNotificationHandler {

    private final NotificationClient notificationClient;

    // DB 커밋이 완료된 이후에만 실행됨
    // 커밋 전 예외 발생 시 이 메서드는 실행되지 않음
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderPlaced(OrderPlacedEvent event) {
        // DB에 이미 커밋된 상태이므로 외부 알림 전송
        notificationClient.sendOrderConfirmation(event.getOrderId());
    }
}
```

**AFTER_COMMIT 동작 원리**

```
@Transactional 메서드 실행
    │
    ├── 1. eventPublisher.publishEvent() 호출
    │       → 이벤트가 즉시 전달되지 않고 TransactionSynchronization에 등록됨
    │
    ├── 2. 트랜잭션 커밋 (DB에 데이터 반영)
    │
    └── 3. AFTER_COMMIT 단계: @TransactionalEventListener 메서드 실행
             → 커밋이 이미 완료된 상태에서 외부 API 호출
```

**주의사항**

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleOrderPlaced(OrderPlacedEvent event) {
    // 이 메서드 내에서 DB를 수정하려면 새 트랜잭션이 필요
    // AFTER_COMMIT 이후에는 기존 트랜잭션의 리소스가 닫혀 있음
    // 수정이 필요하다면 REQUIRES_NEW 트랜잭션을 가진 다른 빈을 호출해야 함
    notificationClient.send(event.getOrderId()); // 외부 호출은 가능

    // 아래처럼 DB 수정이 필요한 경우:
    // orderRepository.updateStatus(...) // 기존 트랜잭션 없어서 작동하지 않을 수 있음
    // → 별도 @Transactional(REQUIRES_NEW) 메서드로 위임해야 함
}
```

트랜잭션이 없는 컨텍스트에서 이벤트가 발행되면 `@TransactionalEventListener`는 기본적으로 실행되지 않는다. 트랜잭션 없이도 실행되어야 한다면 `fallbackExecution = true`를 설정한다.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
public void handleEvent(OrderPlacedEvent event) {
    // 트랜잭션이 없어도 이벤트 처리 로직 실행
}
```

#### 예시 5: 회의실 예약 후 예약자에게 알림 발송

회의실을 예약하고 예약자에게 알림(이메일/푸시)을 발송하는 흐름은 "DB 저장 + 외부 작업"의 전형적인 상황이다. 알림 발송을 어디에 두느냐에 따라 정합성이 크게 달라진다.

**잘못된 설계: 커밋 전에 알림 발송**

```java
@Transactional
public void reserveRoom(ReservationRequest request) {
    // 1. 예약 저장
    Reservation reservation = reservationRepository.save(Reservation.of(request));

    // 2. 같은 트랜잭션 안에서 알림 발송 (외부 호출)
    notificationClient.send(request.getReserverId(), "회의실 예약이 완료되었습니다.");

    // 3. 정원 초과 검증 등 후속 로직에서 예외 발생 가능
    validateRoomCapacity(reservation); // ← 여기서 예외 → 트랜잭션 롤백
    // 결과: 예약은 롤백되어 DB에 없는데, 예약자는 "예약 완료" 알림을 이미 받음 (유령 알림)
}
```

커밋 전에 알림을 보내면 두 가지 문제가 생긴다. 첫째, 위처럼 이후 단계에서 롤백되면 **존재하지 않는 예약에 대한 알림**이 나간다. 둘째, 반대로 알림 발송 자체가 실패(외부 알림 서버 장애)하면 예외가 전파되어 **정상적으로 가능한 예약까지 롤백**된다. 알림은 예약의 부가 작업일 뿐인데 핵심 작업을 막아버리는 것이다.

**올바른 설계: AFTER_COMMIT으로 커밋 이후 알림 발송**

```java
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void reserveRoom(ReservationRequest request) {
        // 예약 저장 + 검증까지 모두 트랜잭션 안에서 처리
        Reservation reservation = reservationRepository.save(Reservation.of(request));
        validateRoomCapacity(reservation);

        // 커밋이 확정된 이후에 알림이 나가도록 이벤트만 발행
        eventPublisher.publishEvent(new ReservationConfirmedEvent(reservation.getId(), request.getReserverId()));
    }
}

@Component
@RequiredArgsConstructor
public class ReservationNotificationHandler {

    private final NotificationClient notificationClient;

    // 예약이 DB에 확정된 이후에만 알림 발송 → 유령 알림 방지
    // 알림 발송이 실패해도 예약 트랜잭션은 이미 커밋되어 안전
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleReservationConfirmed(ReservationConfirmedEvent event) {
        notificationClient.send(event.getReserverId(), "회의실 예약이 완료되었습니다.");
    }
}
```

이렇게 하면 예약이 실제로 DB에 커밋된 경우에만 알림이 발송되고, 알림 서버 장애가 예약을 롤백시키지도 않는다. 알림 발송 실패에 대비해야 한다면 핸들러 안에서 재시도 큐에 적재하거나 발송 실패 이력을 별도로 남긴다(`REQUIRES_NEW` 빈에 위임).

**외부 캘린더 연동까지 있는 경우: 보상 트랜잭션**

예약 시 사내 캘린더나 Google Calendar 같은 외부 시스템에 일정을 등록한다면, 외부 일정 등록은 DB 트랜잭션으로 되돌릴 수 없으므로 보상이 필요하다.

```java
public void reserveRoomWithCalendar(ReservationRequest request) {
    // 1. 외부 캘린더에 일정 등록 (롤백 불가) → 이벤트 ID 확보
    String calendarEventId = calendarClient.createEvent(request);

    try {
        // 2. 예약 정보 DB 저장 (캘린더 이벤트 ID 포함)
        saveReservation(request, calendarEventId);
    } catch (Exception e) {
        // 3. DB 저장 실패 → 보상: 등록한 캘린더 일정 삭제
        log.error("예약 저장 실패, 캘린더 일정 삭제 보상: {}", calendarEventId);
        calendarClient.deleteEvent(calendarEventId);
        throw new ReservationFailedException("예약 저장 실패로 캘린더 일정을 취소했습니다.", e);
    }
}
```

**보상으로 정합성을 맞추는 과정 — @Transactional / propagation 경계 설계**

위 코드를 실무에서 안전하게 동작시키려면 **어떤 작업을 어떤 트랜잭션 경계에 둘 것인가**가 핵심이다. 외부 호출(캘린더), 핵심 DB 저장, 보상 이력 저장이 각각 다른 propagation 옵션을 가져야 한다.

```
┌─────────────────────────────────────────────────────────────┐
│ 조율 메서드 reserveRoomWithCalendar()  ← @Transactional 없음   │
│  (외부 호출이 포함되므로 DB 커넥션을 오래 점유하면 안 됨)        │
│                                                              │
│  1. calendarClient.createEvent()      ← 외부, 트랜잭션 밖     │
│                                                              │
│  2. saveReservation()                 ← @Transactional       │
│     ├─ REQUIRED: 예약 저장 + 검증을 하나의 물리 트랜잭션으로    │
│     └─ 실패 시 이 트랜잭션만 롤백 (DB는 깨끗)                  │
│                                                              │
│  3. (실패 시) 보상                                            │
│     ├─ compensationLog 저장 ← REQUIRES_NEW (반드시 남아야 함)  │
│     └─ calendarClient.deleteEvent()   ← 외부, 트랜잭션 밖     │
└─────────────────────────────────────────────────────────────┘
```

각 경계를 정하는 기준은 다음과 같다.

| 작업 | 트랜잭션 경계 | 이유 |
|------|--------------|------|
| 조율 메서드 자체 | **트랜잭션 없음** | 외부 호출을 트랜잭션 안에 두면 커넥션을 외부 응답 시간만큼 점유 → 풀 고갈 |
| 핵심 DB 저장(예약+검증) | `@Transactional` (REQUIRED) | 예약 저장과 검증을 원자적으로 묶어, 실패 시 DB는 자동 롤백 |
| 보상 이력 저장 | `@Transactional(REQUIRES_NEW)` | 보상 시도/결과 기록은 다른 작업의 롤백과 무관하게 반드시 영속화 |
| 외부 보상 호출(캘린더 삭제) | 트랜잭션 밖 | 외부 시스템은 트랜잭션으로 되돌릴 수 없어 명시적 반대 동작으로 처리 |

이를 코드로 옮기면 보상 단계가 다음처럼 분리된다.

```java
@Service
@RequiredArgsConstructor
public class RoomReservationService {

    private final ReservationRepository reservationRepository;
    private final CalendarClient calendarClient;
    private final ReservationCompensationService compensationService;

    // 조율 메서드: @Transactional 없음 (외부 호출 포함)
    public void reserveRoomWithCalendar(ReservationRequest request) {
        String calendarEventId = calendarClient.createEvent(request); // 외부, 롤백 불가

        try {
            saveReservation(request, calendarEventId); // 핵심 DB 트랜잭션
        } catch (Exception e) {
            // 보상: 이력 영속화 + 외부 일정 삭제
            compensationService.compensate(calendarEventId, e.getMessage());
            throw new ReservationFailedException("예약 저장 실패로 캘린더 일정을 취소했습니다.", e);
        }
    }

    // 핵심 작업: 예약 저장과 검증을 하나의 물리 트랜잭션으로 묶음
    @Transactional // REQUIRED → 실패 시 이 트랜잭션만 깔끔히 롤백
    public void saveReservation(ReservationRequest request, String calendarEventId) {
        Reservation reservation = reservationRepository.save(Reservation.of(request, calendarEventId));
        validateRoomCapacity(reservation); // 중복/정원 초과 시 예외 → 롤백
    }
}

@Service
@RequiredArgsConstructor
public class ReservationCompensationService {

    private final CompensationLogRepository compensationLogRepository;
    private final CalendarClient calendarClient;

    // 보상 이력은 호출자 트랜잭션과 독립적으로 반드시 남아야 함
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensate(String calendarEventId, String reason) {
        // 1. 보상 시도 이력 먼저 기록 (추적/재시도 근거)
        CompensationLog log = compensationLogRepository.save(
                CompensationLog.start(calendarEventId, reason));

        try {
            // 2. 외부 캘린더 일정 삭제 (실제 반대 동작)
            calendarClient.deleteEvent(calendarEventId);
            log.markSuccess(); // 변경 감지로 커밋 시 반영
        } catch (Exception e) {
            // 3. 보상 자체 실패 → FAILED로 남겨 재시도/수동 개입 대상으로
            log.markFailed();
        }
    }
}
```

정합성이 맞춰지는 흐름을 단계로 정리하면 이렇다.

1. **외부 작업을 먼저, 트랜잭션 밖에서 실행한다.** 캘린더 등록을 트랜잭션 안에 넣으면 외부 응답을 기다리는 동안 DB 커넥션을 잡고 있어 풀이 고갈된다. 그래서 조율 메서드에는 `@Transactional`을 붙이지 않는다.
2. **핵심 DB 작업은 REQUIRED 트랜잭션으로 원자적으로 처리한다.** 예약 저장과 검증을 한 트랜잭션에 묶으면, 검증 실패 시 DB는 트랜잭션 롤백으로 자동 정합성이 맞는다. 보상이 필요한 대상은 오직 **이미 커밋된 외부 작업(캘린더)** 뿐이다.
3. **보상 이력은 REQUIRES_NEW로 분리해 반드시 남긴다.** 보상 단계는 독립 트랜잭션에서 "보상 시도 → 외부 삭제 → 결과 기록" 순으로 진행한다. 외부 삭제가 실패해도 `FAILED` 이력이 남아 재시도나 수동 개입의 근거가 된다.
4. **최종 일관성에 도달한다.** 보상이 성공하면 캘린더와 DB 모두 "예약 없음" 상태로 수렴한다. 보상이 실패하면 잠시 불일치하지만, 영속화된 보상 로그를 기반으로 재시도하여 결국 일관된 상태로 맞춘다.

정리하면 **알림 같은 단방향 부가 작업은 AFTER_COMMIT**으로 커밋 이후로 미루고, **캘린더 등록처럼 되돌려야 하는 외부 작업은 보상 트랜잭션**으로 맞추되, 그 보상 과정은 `@Transactional` 경계를 **조율(트랜잭션 없음) / 핵심(REQUIRED) / 보상 이력(REQUIRES_NEW)** 세 층으로 나눠 설계해야 정합성이 안정적으로 유지된다.

---

### 6. 구현 시 고려사항

#### 멱등성(Idempotency) 보장

보상 트랜잭션과 재시도 로직에서 같은 요청이 여러 번 도착했을 때 동일한 결과가 나와야 한다.

```java
@Service
public class PaymentCompensationService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelPayment(String transactionId) {
        // 멱등성 보장: 이미 취소된 건이면 중복 처리하지 않음
        Optional<CompensationLog> existing = compensationLogRepository.findByTransactionId(transactionId);
        if (existing.isPresent() && existing.get().isCompleted()) {
            log.info("이미 취소 처리된 트랜잭션: {}", transactionId);
            return; // 중복 실행 방지
        }

        pgClient.cancel(transactionId);
        compensationLogRepository.save(CompensationLog.completed(transactionId));
    }
}
```

#### 보상 실패 시 재시도와 DLQ(Dead Letter Queue)

보상 트랜잭션 자체도 실패할 수 있다. 재시도 전략과 최종 실패 시 수동 개입 경로를 설계해야 한다.

```java
@Component
@RequiredArgsConstructor
public class CompensationRetryHandler {

    private final PaymentCompensationService compensationService;

    // 보상 실패 이벤트 → 재시도 (예: Kafka 재시도 또는 @Scheduled 폴링)
    @KafkaListener(topics = "compensation-failed-dlq")
    public void retryCompensation(CompensationFailedEvent event) {
        try {
            compensationService.cancelPayment(event.getTransactionId());
        } catch (Exception e) {
            // 재시도도 실패 → 운영팀 알림, 수동 개입 요청
            alertService.notifyManualInterventionRequired(event.getTransactionId());
        }
    }
}
```

#### TransactionSynchronization

`@TransactionalEventListener`보다 저수준의 API로, 트랜잭션 생명주기 이벤트에 직접 콜백을 등록한다. Spring 4.2 이후에는 `@TransactionalEventListener`가 더 선언적이고 관리하기 편하다.

```java
// 저수준 방식 (Spring 4.2 이전 스타일)
@Transactional
public void placeOrder(OrderRequest request) {
    Order order = orderRepository.save(request.toOrder());

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                // 커밋 이후 실행
                notificationClient.send(order.getId());
            }
        }
    );
}
```

---

## 핵심 정리

- `propagation`은 "트랜잭션을 새로 만들지, 참여할지, 중단할지"를 결정한다. 기본값은 `REQUIRED`이며 호출 스택 전체를 하나의 물리 트랜잭션으로 묶는다.
- `REQUIRES_NEW`는 실패해도 반드시 커밋되어야 하는 이력/로그에, `NESTED`는 부분 롤백이 필요한 JDBC 환경에 적합하다.
- DB 경계를 벗어나는 작업(PG 결제, 외부 API, 메시지 발행)은 트랜잭션 롤백으로 되돌릴 수 없으므로 보상 트랜잭션으로 정합성을 맞춘다.
- `@TransactionalEventListener(AFTER_COMMIT)`으로 DB 커밋 이후에 외부 API를 호출하면 "롤백된 작업에 대한 외부 호출" 문제를 회피할 수 있다.
- 보상 로직에는 멱등성과 재시도 전략이 반드시 함께 설계되어야 한다.

## 기술적 한계와 보완 전략

- 보상 트랜잭션은 즉시 일관성이 아닌 최종 일관성(Eventual Consistency)만 보장한다. 보상이 완료될 때까지 중간 상태가 외부에 노출된다.
- 보상 자체가 실패할 수 있어 재시도/DLQ/수동 개입 절차가 필요하다. 재시도를 위해 보상 로그를 DB에 영속화해야 한다.
- 중간 상태가 외부에 노출될 수 있어 상태 머신(State Machine) 또는 상태 플래그(`PENDING`, `CONFIRMED`, `CANCELLED`) 관리로 클라이언트에게 일관된 상태를 전달해야 한다.
- Orchestration SAGA는 중앙 조율자가 SPOF(단일 장애점)가 될 수 있어 조율자 자체의 고가용성 설계가 필요하다.

## 키워드

- **Transaction Propagation**: 트랜잭션이 이미 존재할 때 새로운 `@Transactional` 메서드가 어떻게 동작할지를 결정하는 옵션. `REQUIRED`, `REQUIRES_NEW`, `NESTED`, `SUPPORTS`, `NOT_SUPPORTED`, `MANDATORY`, `NEVER` 7가지가 있다.

- **REQUIRES_NEW**: 기존 트랜잭션을 suspend하고 완전히 독립된 새 물리 트랜잭션을 시작하는 전파 옵션. 커밋/롤백이 부모 트랜잭션과 완전히 독립되지만 DB 커넥션을 2개 동시에 점유한다.

- **NESTED / Savepoint**: 단일 물리 트랜잭션 내에 JDBC Savepoint를 생성하여 내부 스코프만 부분 롤백이 가능하게 하는 전파 옵션. `DataSourceTransactionManager` 전용이며 JPA 환경에서는 동작하지 않는다.

- **보상 트랜잭션 (Compensating Transaction)**: 이미 커밋된 작업을 되돌리기 위해 반대 동작을 수행하는 별도의 트랜잭션. DB 트랜잭션 경계를 벗어난 외부 API 호출이나 MSA 환경에서 정합성을 맞추기 위해 사용한다.

- **SAGA 패턴**: 분산 트랜잭션을 여러 로컬 트랜잭션과 보상 트랜잭션의 체인으로 대체하는 패턴. 각 서비스가 이벤트로 통신하는 Choreography와 중앙 조율자가 흐름을 제어하는 Orchestration 두 가지 방식이 있다.

- **Eventual Consistency (최종 일관성)**: 즉시 일관성이 아닌, 일정 시간이 지난 후 모든 노드가 동일한 상태에 도달하는 것을 보장하는 일관성 모델. 보상 트랜잭션은 즉시 일관성 대신 최종 일관성만 제공한다.

- **멱등성 (Idempotency)**: 동일한 요청을 여러 번 실행해도 결과가 동일한 성질. 보상 트랜잭션과 재시도 로직에서 중복 실행에 의한 부작용을 방지하기 위해 반드시 보장해야 한다.

- **TransactionSynchronization**: Spring 트랜잭션 생명주기 이벤트에 직접 콜백을 등록하는 저수준 API. Spring 4.2 이후에는 `@TransactionalEventListener`가 더 선언적이고 권장되는 방식이다.

- **AFTER_COMMIT 이벤트**: `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`으로 등록하는 이벤트 핸들러. DB 커밋이 성공한 이후에만 실행되어, 커밋 전 실패 시 외부 API 호출이 발생하는 문제를 방지한다.

## 참고 자료

- [Spring Framework 공식 문서 - Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [Spring Framework 공식 문서 - Transaction-bound Events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [Spring Framework API - TransactionalEventListener](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html)
- [Microservices.io - Saga Pattern](https://microservices.io/patterns/data/saga.html)
- [Baeldung - Saga Pattern in Microservices](https://www.baeldung.com/cs/saga-pattern-microservices)
- [Baeldung - Transaction Propagation and Isolation in Spring @Transactional](https://www.baeldung.com/spring-transactional-propagation-isolation)
