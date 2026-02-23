# 2-Phase Locking (2PL)

## 개요
트랜잭션의 직렬 가능성(Serializability)을 보장하기 위한 동시성 제어 프로토콜로, 락의 획득과 해제를 두 단계로 나누어 관리하는 기법

## 상세 내용

### 2PL의 기본 원리

2-Phase Locking은 트랜잭션이 락을 획득하고 해제하는 과정을 두 단계로 엄격하게 구분한다.

#### Growing Phase (확장 단계)
- 트랜잭션이 **락을 획득만** 할 수 있는 단계
- 이미 획득한 락을 해제할 수 없음
- 데이터에 접근하기 전 필요한 락을 순차적으로 획득
- 트랜잭션 시작부터 Lock Point까지 지속

#### Shrinking Phase (축소 단계)
- 트랜잭션이 **락을 해제만** 할 수 있는 단계
- 새로운 락을 획득할 수 없음
- Lock Point 이후부터 트랜잭션 종료까지 지속
- 획득했던 락을 순차적으로 해제

#### Lock Point
- Growing Phase에서 Shrinking Phase로 전환되는 시점
- 트랜잭션이 **처음으로 락을 해제하는 순간**
- Lock Point를 기준으로 2PL 프로토콜의 준수 여부 판단
- 이 시점 이후에는 어떤 락도 새로 획득할 수 없음

**2PL이 Serializability를 보장하는 이유:**
- 두 트랜잭션 T1, T2가 동일한 데이터에 접근할 때
- 먼저 락을 획득한 트랜잭션이 해제하기 전까지 다른 트랜잭션은 대기
- 결과적으로 직렬 스케줄과 동일한 실행 순서 보장 (Conflict Serializability)

### 2PL의 종류

#### 1. Basic 2PL
- Growing/Shrinking Phase 규칙만 준수
- 락을 언제든 해제 가능 (첫 해제 시점이 Lock Point)
- **문제점**: Cascading Rollback 발생 가능
  - T1이 데이터를 수정하고 락을 해제
  - T2가 락을 획득하여 T1이 수정한 데이터 읽기
  - T1이 롤백되면 T2도 함께 롤백되어야 함

#### 2. Strict 2PL (S2PL)
- **모든 Exclusive Lock(X-Lock)을 트랜잭션 종료 시점까지 유지**
- Shared Lock(S-Lock)은 Lock Point 이후 해제 가능
- Cascading Rollback 방지 (다른 트랜잭션이 커밋되지 않은 데이터를 읽을 수 없음)
- **실무에서 가장 널리 사용됨** (MySQL InnoDB 포함)
- Recoverable Schedule 보장

#### 3. Rigorous 2PL (SS2PL / Strong Strict 2PL)
- **모든 Lock(S-Lock + X-Lock)을 트랜잭션 종료 시점까지 유지**
- Shrinking Phase가 사실상 존재하지 않음 (커밋/롤백 시점에 한 번에 모든 락 해제)
- 가장 강력한 격리 수준 제공
- 성능 저하 가능성 (락 보유 시간이 가장 김)

#### 4. Conservative 2PL (Static 2PL)
- **트랜잭션 시작 시 필요한 모든 락을 한 번에 획득**
- 하나라도 획득 실패 시 트랜잭션 대기 또는 중단
- **Deadlock 발생하지 않음** (모든 락을 미리 획득하므로 순환 대기 불가)
- 필요한 모든 락을 미리 알아야 함 (실무 적용 어려움)

| 종류 | X-Lock 해제 시점 | S-Lock 해제 시점 | Cascading Rollback | Deadlock |
|------|-----------------|-----------------|-------------------|----------|
| Basic 2PL | Lock Point 이후 가능 | Lock Point 이후 가능 | 발생 가능 | 발생 가능 |
| Strict 2PL | 트랜잭션 종료 시 | Lock Point 이후 가능 | 방지 | 발생 가능 |
| Rigorous 2PL | 트랜잭션 종료 시 | 트랜잭션 종료 시 | 방지 | 발생 가능 |
| Conservative 2PL | 트랜잭션 종료 시 | 트랜잭션 종료 시 | 방지 | **발생 안 함** |

### MySQL/InnoDB에서의 2PL 적용

InnoDB는 **Strict 2PL + MVCC**를 혼합하여 동시성 제어를 수행한다.

#### InnoDB의 락 관리 방식
- **쓰기 작업**: Strict 2PL 사용
  - `UPDATE`, `DELETE`, `INSERT` 실행 시 X-Lock 획득
  - 트랜잭션이 커밋/롤백될 때까지 X-Lock 유지
  - Lock Point 이후에도 락 해제 불가 (Strict 2PL 준수)

- **읽기 작업**: MVCC 기본, 명시적 락 가능
  - 일반 `SELECT`: 락 없이 MVCC로 스냅샷 읽기 (Non-locking Read)
  - `SELECT ... FOR UPDATE`: X-Lock 획득 (2PL 적용)
  - `SELECT ... LOCK IN SHARE MODE` / `FOR SHARE`: S-Lock 획득 (2PL 적용)

#### InnoDB의 락 종류와 2PL

```sql
-- Record Lock: 특정 인덱스 레코드에 락
SELECT * FROM users WHERE id = 100 FOR UPDATE;

-- Gap Lock: 인덱스 레코드 사이의 간격에 락 (Phantom Read 방지)
SELECT * FROM users WHERE age > 20 FOR UPDATE;

-- Next-Key Lock: Record Lock + Gap Lock (기본값)
-- Repeatable Read에서 Phantom Read 방지
```

#### 트랜잭션 실행 예시

```sql
-- Transaction 1
START TRANSACTION;
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;  -- X-Lock 획득 (Growing Phase)
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
SELECT * FROM accounts WHERE id = 2 FOR UPDATE;  -- X-Lock 획득 (Growing Phase)
UPDATE accounts SET balance = balance + 100 WHERE id = 2;
COMMIT;  -- 모든 락 해제 (Strict 2PL: 커밋 시점에 일괄 해제)
```

#### 격리 수준별 2PL 동작

| 격리 수준 | S-Lock 유지 시간 | X-Lock 유지 시간 | Gap Lock |
|----------|----------------|----------------|----------|
| READ UNCOMMITTED | 획득 안 함 | 트랜잭션 종료 시 | 사용 안 함 |
| READ COMMITTED | 즉시 해제 | 트랜잭션 종료 시 | 사용 안 함 |
| REPEATABLE READ | 트랜잭션 종료 시 | 트랜잭션 종료 시 | **사용** (기본값) |
| SERIALIZABLE | 트랜잭션 종료 시 | 트랜잭션 종료 시 | 사용 |

**InnoDB의 기본 설정 (REPEATABLE READ)**:
- 일반 SELECT는 MVCC 사용 (락 없음)
- 명시적 락 쿼리는 Strict 2PL + Next-Key Lock
- Phantom Read 방지를 위한 Gap Lock 자동 적용

### 2PL과 Deadlock

2PL은 Serializability를 보장하지만, **Deadlock 발생 가능성**이 있다.

#### Deadlock 발생 원리

```sql
-- Transaction 1
START TRANSACTION;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;  -- X-Lock on id=1
-- 대기...
UPDATE accounts SET balance = balance + 100 WHERE id = 2;  -- X-Lock on id=2 대기

-- Transaction 2
START TRANSACTION;
UPDATE accounts SET balance = balance - 50 WHERE id = 2;   -- X-Lock on id=2
-- 대기...
UPDATE accounts SET balance = balance + 50 WHERE id = 1;   -- X-Lock on id=1 대기 → DEADLOCK!
```

**Deadlock 발생 조건 (4가지 모두 충족)**:
1. **Mutual Exclusion**: 락은 배타적으로 사용됨
2. **Hold and Wait**: 락을 보유한 채 다른 락 대기
3. **No Preemption**: 락을 강제로 빼앗을 수 없음
4. **Circular Wait**: T1 → T2 → T1 형태의 순환 대기

#### Deadlock Detection (사후 탐지)

**InnoDB의 Deadlock Detection 방식**:
```sql
-- InnoDB는 주기적으로 Wait-for Graph 검사
-- 순환 구조 발견 시 희생자(Victim) 선택하여 롤백

SHOW ENGINE INNODB STATUS;  -- Deadlock 정보 확인
```

**희생자 선택 기준**:
- 가장 적은 행을 수정한 트랜잭션
- 롤백 비용이 가장 적은 트랜잭션
- 우선순위가 낮은 트랜잭션

```
-- InnoDB Deadlock 로그 예시
------------------------
LATEST DETECTED DEADLOCK
------------------------
*** (1) TRANSACTION:
TRANSACTION 1234, ACTIVE 5 sec starting index read
mysql tables in use 1, locked 1
LOCK WAIT 2 lock struct(s), heap size 1136, 1 row lock(s)
MySQL thread id 10, OS thread handle 123456, query id 100 localhost root updating
UPDATE accounts SET balance = balance + 100 WHERE id = 2

*** (2) TRANSACTION:
TRANSACTION 1235, ACTIVE 3 sec starting index read
mysql tables in use 1, locked 1
3 lock struct(s), heap size 1136, 2 row lock(s), undo log entries 1
MySQL thread id 11, OS thread handle 123457, query id 101 localhost root updating
UPDATE accounts SET balance = balance + 50 WHERE id = 1

*** WE ROLL BACK TRANSACTION (2)
```

#### Deadlock Prevention (사전 예방)

**1. Wait-Die 방식 (Non-Preemptive)**
- 오래된 트랜잭션(Old)이 젊은 트랜잭션(Young)의 락 대기: **Wait**
- 젊은 트랜잭션이 오래된 트랜잭션의 락 대기: **Die (롤백 후 재시작)**
- Timestamp 기반 우선순위 부여
- 오래된 트랜잭션에 우선권 부여

**2. Wound-Wait 방식 (Preemptive)**
- 오래된 트랜잭션이 젊은 트랜잭션의 락 대기: **Wound (상대를 롤백시킴)**
- 젊은 트랜잭션이 오래된 트랜잭션의 락 대기: **Wait**
- 적극적인 선점 방식
- 젊은 트랜잭션이 희생

**3. Timeout 방식 (InnoDB 기본 지원)**
```sql
-- 락 대기 시간 초과 시 에러 발생
SET innodb_lock_wait_timeout = 50;  -- 기본값 50초

-- 애플리케이션 레벨에서 재시도 로직 구현 필요
```

**4. Conservative 2PL 사용**
- 트랜잭션 시작 시 모든 락 한 번에 획득
- Deadlock 원천 차단 (순환 대기 불가능)
- 실무 적용 어려움 (필요한 모든 락을 미리 알기 어려움)

#### Deadlock 회피 Best Practices

```sql
-- 1. 락 획득 순서 통일 (가장 효과적)
-- 모든 트랜잭션이 id 오름차순으로 락 획득
START TRANSACTION;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;  -- 항상 작은 id부터
UPDATE accounts SET balance = balance + 100 WHERE id = 2;
COMMIT;

-- 2. 트랜잭션 크기 최소화
-- 짧은 트랜잭션은 락 보유 시간 감소 → Deadlock 확률 감소

-- 3. 인덱스 활용 (락 범위 최소화)
-- 인덱스 없으면 테이블 전체 스캔 시 과도한 락 획득
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;  -- id에 인덱스 필수

-- 4. 배치 크기 제한
-- 대량 업데이트 시 작은 배치로 분할
UPDATE accounts SET status = 'active' WHERE created_at < '2024-01-01' LIMIT 1000;
```

#### 현실적인 Deadlock 발생 상황과 해결 방안

실무에서 자주 마주치는 데드락 시나리오와 구체적인 해결 전략을 살펴본다.

---

**시나리오 1: 주문 처리 시스템의 재고 차감 + 포인트 적립**

```sql
-- 사용자 A의 주문 처리 (Transaction 1)
START TRANSACTION;
-- 1. 상품 100번 재고 차감
UPDATE products SET stock = stock - 1 WHERE id = 100;  -- X-Lock on product 100
-- 2. 사용자 1번 포인트 적립
UPDATE users SET points = points + 500 WHERE id = 1;   -- X-Lock on user 1 대기

-- 사용자 B의 주문 처리 (Transaction 2) - 동시 실행
START TRANSACTION;
-- 1. 사용자 1번 포인트 사용 (다른 주문)
UPDATE users SET points = points - 1000 WHERE id = 1;  -- X-Lock on user 1
-- 2. 상품 100번 재고 차감
UPDATE products SET stock = stock - 1 WHERE id = 100;  -- X-Lock on product 100 대기
-- → DEADLOCK 발생!
```

**문제 분석:**
- T1: Product 100 → User 1 순서로 락 획득
- T2: User 1 → Product 100 순서로 락 획득
- 순환 대기 발생

**해결 방안 1: 락 획득 순서 표준화**
```sql
-- 규칙: 항상 "users 테이블 → products 테이블" 순서로 접근
-- 또는 "테이블명 알파벳 순서" 또는 "id 오름차순"

-- Transaction 1 (수정 후)
START TRANSACTION;
UPDATE users SET points = points + 500 WHERE id = 1;      -- 1. User 먼저
UPDATE products SET stock = stock - 1 WHERE id = 100;     -- 2. Product 나중
COMMIT;

-- Transaction 2 (수정 후)
START TRANSACTION;
UPDATE users SET points = points - 1000 WHERE id = 1;     -- 1. User 먼저
UPDATE products SET stock = stock - 1 WHERE id = 100;     -- 2. Product 나중
COMMIT;

-- 결과: T2가 User 1 락을 먼저 획득하면, T1은 대기 (Deadlock 발생 안 함)
```

**해결 방안 2: 애플리케이션 레벨 재시도 로직**
```java
@Service
public class OrderService {
    private static final int MAX_RETRIES = 3;

    @Transactional
    public void processOrder(Long userId, Long productId) {
        int retryCount = 0;
        while (retryCount < MAX_RETRIES) {
            try {
                // 재고 차감
                productRepository.decreaseStock(productId, 1);
                // 포인트 적립
                userRepository.increasePoints(userId, 500);
                return;  // 성공
            } catch (DeadlockLoserDataAccessException e) {
                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    throw new OrderProcessException("주문 처리 실패", e);
                }
                // 지수 백오프로 재시도
                Thread.sleep((long) Math.pow(2, retryCount) * 100);
                log.warn("Deadlock 발생, 재시도 {}/{}", retryCount, MAX_RETRIES);
            }
        }
    }
}
```

---

**시나리오 2: 송금 트랜잭션 (양방향 송금)**

```sql
-- User A → User B 송금 (Transaction 1)
START TRANSACTION;
UPDATE accounts SET balance = balance - 10000 WHERE user_id = 1;  -- A 출금
UPDATE accounts SET balance = balance + 10000 WHERE user_id = 2;  -- B 입금

-- User B → User A 송금 (Transaction 2) - 거의 동시에 실행
START TRANSACTION;
UPDATE accounts SET balance = balance - 5000 WHERE user_id = 2;   -- B 출금
UPDATE accounts SET balance = balance + 5000 WHERE user_id = 1;   -- A 입금
-- → DEADLOCK!
```

**해결 방안 1: ID 기반 정렬로 락 순서 통일**
```java
@Transactional
public void transfer(Long fromUserId, Long toUserId, BigDecimal amount) {
    // 항상 작은 ID부터 락 획득
    Long firstId = Math.min(fromUserId, toUserId);
    Long secondId = Math.max(fromUserId, toUserId);

    // 1단계: 두 계좌 모두 락 획득 (정렬된 순서로)
    Account first = accountRepository.findByIdForUpdate(firstId);
    Account second = accountRepository.findByIdForUpdate(secondId);

    // 2단계: 실제 송금 처리
    if (fromUserId.equals(firstId)) {
        first.withdraw(amount);
        second.deposit(amount);
    } else {
        second.withdraw(amount);
        first.deposit(amount);
    }
}
```

```sql
-- 실제 실행되는 SQL (fromUserId=2, toUserId=1인 경우)
START TRANSACTION;
-- 항상 id=1을 먼저 락
SELECT * FROM accounts WHERE user_id = 1 FOR UPDATE;
-- 그 다음 id=2를 락
SELECT * FROM accounts WHERE user_id = 2 FOR UPDATE;
-- 송금 처리
UPDATE accounts SET balance = balance + 5000 WHERE user_id = 1;
UPDATE accounts SET balance = balance - 5000 WHERE user_id = 2;
COMMIT;
```

**해결 방안 2: 분산 락 활용 (Redis 등)**
```java
@Service
public class TransferService {
    @Autowired
    private RedissonClient redissonClient;

    public void transfer(Long fromUserId, Long toUserId, BigDecimal amount) {
        // 글로벌 락 획득 (알파벳순으로 정렬)
        String[] lockKeys = Stream.of(
            "account:" + fromUserId,
            "account:" + toUserId
        ).sorted().toArray(String[]::new);

        RLock multiLock = redissonClient.getMultiLock(
            Arrays.stream(lockKeys)
                .map(redissonClient::getLock)
                .toArray(RLock[]::new)
        );

        try {
            if (multiLock.tryLock(10, 30, TimeUnit.SECONDS)) {
                try {
                    // DB 트랜잭션 실행 (이미 락 획득했으므로 Deadlock 없음)
                    transferInternal(fromUserId, toUserId, amount);
                } finally {
                    multiLock.unlock();
                }
            } else {
                throw new LockAcquisitionException("락 획득 실패");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransferException("송금 중단됨", e);
        }
    }
}
```

---

**시나리오 3: 좌석 예매 시스템 (인덱스 범위 스캔)**

```sql
-- 사용자 A: 1~10번 좌석 중 선택 (Transaction 1)
START TRANSACTION;
SELECT * FROM seats WHERE id BETWEEN 1 AND 10 AND status = 'available'
FOR UPDATE;  -- Next-Key Lock on id 1~10 + Gap Lock

-- 사용자 B: 5~15번 좌석 중 선택 (Transaction 2)
START TRANSACTION;
SELECT * FROM seats WHERE id BETWEEN 5 AND 15 AND status = 'available'
FOR UPDATE;  -- Next-Key Lock on id 5~15 + Gap Lock
-- → DEADLOCK 가능 (Gap Lock 충돌)
```

**문제 분석:**
- InnoDB의 Next-Key Lock은 레코드 + 간격(Gap)을 모두 잠금
- 겹치는 범위에서 서로 다른 순서로 락 획득 시 데드락 발생

**해결 방안 1: 격리 수준 낮추기 (신중하게)**
```sql
-- READ COMMITTED로 변경하면 Gap Lock 사용 안 함
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;

START TRANSACTION;
SELECT * FROM seats WHERE id = 5 AND status = 'available' FOR UPDATE;
UPDATE seats SET status = 'reserved', user_id = 123 WHERE id = 5;
COMMIT;

-- 단점: Phantom Read 발생 가능, 애플리케이션 레벨에서 중복 예매 검증 필수
```

**해결 방안 2: 낙관적 락 사용 (버전 관리)**
```sql
-- 테이블에 version 컬럼 추가
ALTER TABLE seats ADD COLUMN version INT NOT NULL DEFAULT 0;

-- 트랜잭션 없이 조회
SELECT id, status, version FROM seats
WHERE id = 5 AND status = 'available';
-- 결과: id=5, status='available', version=3

-- 예매 시도 (낙관적 락)
UPDATE seats
SET status = 'reserved', user_id = 123, version = version + 1
WHERE id = 5 AND status = 'available' AND version = 3;

-- 영향받은 행이 0이면 → 다른 사용자가 이미 예매 (재시도)
-- 영향받은 행이 1이면 → 예매 성공
```

**해결 방안 3: 명시적 락 테이블 사용**
```sql
-- 좌석 범위별로 락 테이블 생성
CREATE TABLE seat_range_locks (
    range_id INT PRIMARY KEY,
    range_start INT,
    range_end INT
) ENGINE=InnoDB;

INSERT INTO seat_range_locks VALUES (1, 1, 10), (2, 11, 20), (3, 21, 30);

-- 트랜잭션에서 범위 락 먼저 획득
START TRANSACTION;
SELECT * FROM seat_range_locks WHERE range_id = 1 FOR UPDATE;  -- 1~10번 범위 락
-- 이제 해당 범위 내에서 자유롭게 작업
SELECT * FROM seats WHERE id BETWEEN 1 AND 10 AND status = 'available';
UPDATE seats SET status = 'reserved' WHERE id = 5;
COMMIT;
```

---

**시나리오 4: 외래 키 제약조건으로 인한 Deadlock**

```sql
-- 테이블 구조
CREATE TABLE orders (
    id INT PRIMARY KEY,
    user_id INT,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Transaction 1: 주문 삭제
START TRANSACTION;
DELETE FROM orders WHERE id = 100;
-- orders 테이블 X-Lock + users 테이블 S-Lock (FK 검증)

-- Transaction 2: 사용자 정보 업데이트
START TRANSACTION;
UPDATE users SET name = 'New Name' WHERE id = 1;  -- users 테이블 X-Lock
DELETE FROM orders WHERE user_id = 1;  -- orders 테이블 X-Lock 대기
-- → DEADLOCK 가능
```

**해결 방안 1: FK 제약조건 제거하고 애플리케이션에서 관리**
```sql
-- FK 제거 (신중하게 결정)
ALTER TABLE orders DROP FOREIGN KEY fk_orders_users;

-- 애플리케이션 레벨에서 참조 무결성 검증
@Transactional
public void deleteOrder(Long orderId) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow();

    // 명시적으로 user 존재 여부 확인
    if (!userRepository.existsById(order.getUserId())) {
        throw new InvalidOrderException("User not found");
    }

    orderRepository.delete(order);
}
```

**해결 방안 2: 배치 삭제 시 락 순서 통일**
```sql
-- 안티패턴: 각 주문마다 개별 삭제
DELETE FROM orders WHERE id = 100;
DELETE FROM orders WHERE id = 101;  -- 순서 불확실

-- 권장: IN 절로 한 번에 삭제 (락 순서 일관성)
DELETE FROM orders WHERE id IN (100, 101, 102) ORDER BY id;
```

---

**시나리오 5: SELECT ... FOR UPDATE와 일반 UPDATE 혼재**

```sql
-- Transaction 1: 재고 확인 후 차감
START TRANSACTION;
SELECT stock FROM products WHERE id = 1 FOR UPDATE;  -- stock = 10
-- (비즈니스 로직 처리 중...)
UPDATE products SET stock = stock - 5 WHERE id = 1;

-- Transaction 2: 다른 상품 업데이트
START TRANSACTION;
UPDATE products SET price = 20000 WHERE id = 2;  -- 다른 상품
UPDATE products SET stock = stock - 3 WHERE id = 1;  -- T1이 잠근 상품
-- → 대기 (Deadlock은 아니지만 성능 저하)
```

**해결 방안: SELECT FOR UPDATE를 UPDATE로 변경 (원자적 연산)**
```sql
-- 개선 전: SELECT + UPDATE (2단계, 락 보유 시간 김)
START TRANSACTION;
SELECT stock FROM products WHERE id = 1 FOR UPDATE;
-- Java에서 stock >= 5 검증
UPDATE products SET stock = stock - 5 WHERE id = 1;
COMMIT;

-- 개선 후: UPDATE 한 번에 처리 (1단계, 락 보유 시간 짧음)
START TRANSACTION;
UPDATE products SET stock = stock - 5
WHERE id = 1 AND stock >= 5;  -- 조건부 업데이트

-- 영향받은 행 수로 성공 여부 판단
int updated = jdbcTemplate.update(
    "UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?",
    quantity, productId, quantity
);

if (updated == 0) {
    throw new InsufficientStockException("재고 부족");
}
COMMIT;
```

---

**종합 해결 전략 요약**

| 해결 방법 | 적용 난이도 | 효과 | 사용 상황 |
|----------|-----------|------|----------|
| **락 획득 순서 통일** | ⭐⭐ 중간 | ⭐⭐⭐ 높음 | 모든 경우에 최우선 적용 |
| **트랜잭션 크기 최소화** | ⭐ 쉬움 | ⭐⭐ 중간 | 장시간 트랜잭션이 있을 때 |
| **인덱스 최적화** | ⭐⭐ 중간 | ⭐⭐ 중간 | Gap Lock 범위 줄이기 |
| **격리 수준 낮추기** | ⭐ 쉬움 | ⭐⭐ 중간 | READ COMMITTED로 충분한 경우 |
| **낙관적 락 (OCC)** | ⭐⭐⭐ 어려움 | ⭐⭐⭐ 높음 | 충돌이 적은 환경 |
| **분산 락 (Redis)** | ⭐⭐⭐⭐ 매우 어려움 | ⭐⭐⭐ 높음 | 복잡한 비즈니스 로직 |
| **재시도 로직** | ⭐⭐ 중간 | ⭐⭐ 중간 | Deadlock 회피 불가능한 경우 |
| **SELECT ... FOR UPDATE 제거** | ⭐⭐ 중간 | ⭐⭐ 중간 | 단순 조건부 업데이트인 경우 |

**실무 적용 체크리스트:**

```java
// 1. 락 순서 검증 코드 예시
@Aspect
@Component
public class DeadlockPreventionAspect {

    @Around("@annotation(PreventDeadlock)")
    public Object checkLockOrder(ProceedingJoinPoint pjp) throws Throwable {
        PreventDeadlock annotation =
            ((MethodSignature) pjp.getSignature())
                .getMethod()
                .getAnnotation(PreventDeadlock.class);

        String[] expectedOrder = annotation.lockOrder();
        // 실제 락 획득 순서 검증
        // 위반 시 경고 로그 또는 예외 발생

        return pjp.proceed();
    }
}

// 사용 예시
@PreventDeadlock(lockOrder = {"users", "products", "orders"})
@Transactional
public void processOrder(Long userId, Long productId) {
    // 반드시 users → products → orders 순서로 접근
}
```

```sql
-- 2. Deadlock 모니터링 쿼리
-- 최근 Deadlock 발생 빈도 확인
SELECT
    DATE(created_time) as date,
    COUNT(*) as deadlock_count
FROM information_schema.INNODB_METRICS
WHERE NAME = 'lock_deadlocks'
GROUP BY DATE(created_time)
ORDER BY date DESC
LIMIT 7;

-- 3. 현재 대기 중인 락 확인
SELECT
    waiting_trx_id,
    waiting_query,
    blocking_trx_id,
    blocking_query
FROM sys.innodb_lock_waits;
```

### 2PL vs MVCC

#### 2PL 방식

**동작 원리:**
- 읽기/쓰기 모두 락 기반 동시성 제어
- S-Lock (Shared Lock): 여러 트랜잭션이 동시에 읽기 가능
- X-Lock (Exclusive Lock): 하나의 트랜잭션만 쓰기 가능
- 락 충돌 시 대기 (Blocking)

**장점:**
- 구현이 단순하고 직관적
- 쓰기 작업이 많은 워크로드에서 효율적
- 충돌 직렬 가능성(Conflict Serializability) 보장

**단점:**
- Reader와 Writer가 서로 블로킹 → **동시성 저하**
- Deadlock 발생 가능
- 락 대기 시간으로 인한 성능 저하

#### MVCC 방식

**동작 원리:**
- 데이터의 여러 버전을 유지 (Snapshot)
- 읽기: 트랜잭션 시작 시점의 스냅샷 읽기 (락 없음)
- 쓰기: 새로운 버전 생성 (이전 버전 유지)
- 버전 관리를 통해 Reader와 Writer 간 충돌 회피

**장점:**
- Reader와 Writer가 서로 블로킹하지 않음 → **높은 동시성**
- 읽기 작업이 쓰기 작업을 방해하지 않음
- Deadlock 발생 확률 감소
- 읽기 성능 우수

**단점:**
- 버전 관리 오버헤드 (Undo Log 크기 증가)
- Write Skew, Phantom Read 등 이상 현상 발생 가능
- Garbage Collection 필요 (오래된 버전 정리)

#### 비교표

| 특성 | 2PL | MVCC |
|------|-----|------|
| 읽기-쓰기 충돌 | **블로킹** | 블로킹 없음 |
| 쓰기-쓰기 충돌 | 블로킹 | 블로킹 |
| Deadlock | 발생 가능 | 발생 가능 (낮음) |
| 읽기 성능 | 낮음 (락 대기) | **높음** (락 없음) |
| 쓰기 성능 | 높음 | 중간 (버전 생성) |
| 메모리 사용 | 적음 | 많음 (다중 버전) |
| 구현 복잡도 | 낮음 | **높음** |
| 대표 DBMS | SQL Server (일부) | PostgreSQL, Oracle |

#### MySQL InnoDB의 혼합 방식 (Strict 2PL + MVCC)

InnoDB는 **읽기는 MVCC, 쓰기는 Strict 2PL**을 사용하여 양쪽의 장점을 결합한다.

```sql
-- Transaction 1: 읽기 (MVCC 사용, 락 없음)
START TRANSACTION;
SELECT * FROM accounts WHERE id = 1;  -- 스냅샷 읽기, 블로킹 없음
-- ... (긴 작업)
COMMIT;

-- Transaction 2: 쓰기 (Strict 2PL 사용)
START TRANSACTION;
UPDATE accounts SET balance = balance + 100 WHERE id = 1;  -- X-Lock 획득
COMMIT;  -- 커밋 시점에 락 해제

-- T1과 T2는 서로 블로킹하지 않음!
```

**격리 수준별 동작:**

```sql
-- REPEATABLE READ (기본값): MVCC + Strict 2PL
START TRANSACTION;
SELECT * FROM accounts WHERE id = 1;  -- MVCC 스냅샷 읽기
SELECT * FROM accounts WHERE id = 1;  -- 동일한 스냅샷 읽기 (일관성 보장)

-- 명시적 락 사용 시 2PL로 전환
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;  -- X-Lock 획득, 2PL 적용
```

**Undo Log를 통한 MVCC 구현:**
- 각 행에 트랜잭션 ID와 롤백 포인터 저장
- 읽기 시 트랜잭션 시작 시점 이전의 버전을 Undo Log에서 재구성
- 오래된 버전은 Purge 스레드가 주기적으로 정리

```
Row (Latest Version):
  id=1, balance=1000, TRX_ID=100, ROLL_PTR -> Undo Log

Undo Log:
  TRX_ID=90: balance=900
  TRX_ID=80: balance=800
  ...

-- TRX_ID=85인 트랜잭션이 읽을 때:
-- Undo Log를 따라가서 TRX_ID=80 버전 반환 (balance=800)
```

#### 트레이드오프

**읽기 위주 워크로드 (Read-Heavy)**:
- MVCC 유리 (InnoDB 기본 SELECT)
- 락 경합 없이 높은 동시성 달성
- 예: 대시보드, 통계 조회, 리포트 생성

**쓰기 위주 워크로드 (Write-Heavy)**:
- 2PL 유리 (InnoDB UPDATE/DELETE)
- 버전 관리 오버헤드 없음
- 예: 주문 처리, 재고 차감, 결제

**혼합 워크로드 (Mixed)**:
- InnoDB 방식이 최적 (MVCC + Strict 2PL)
- 읽기는 MVCC로 빠르게, 쓰기는 2PL로 안전하게
- 대부분의 실무 환경이 이에 해당

### 2PL의 한계와 대안

#### 1. Phantom Read 문제

**문제 상황:**
```sql
-- Transaction 1
START TRANSACTION;
SELECT COUNT(*) FROM students WHERE age >= 20;  -- 결과: 100명
-- ... (다른 작업)
SELECT COUNT(*) FROM students WHERE age >= 20;  -- 결과: 101명 (Phantom!)

-- Transaction 2 (T1 실행 중간에 실행)
START TRANSACTION;
INSERT INTO students (name, age) VALUES ('Alice', 22);  -- 새 행 삽입
COMMIT;
```

Basic 2PL로는 Phantom Read를 방지할 수 없다:
- 아직 존재하지 않는 행에는 락을 걸 수 없음
- S-Lock은 기존 행에만 적용

**해결 방법:**

**1) Predicate Locking (술어 잠금)**
- 조건식 자체에 락을 거는 개념적 방법
- `WHERE age >= 20` 조건을 만족하는 모든 현재/미래 행에 락
- 구현 복잡도가 매우 높아 실무에서 거의 사용 안 함

**2) Index Locking (인덱스 잠금)**
- 조건을 만족하는 인덱스 범위에 락
- InnoDB의 Gap Lock, Next-Key Lock이 이 방식

```sql
-- InnoDB의 Next-Key Lock 예시
SELECT * FROM students WHERE age >= 20 FOR UPDATE;

-- 다음 범위에 모두 락 걸림:
-- 1. age >= 20인 모든 레코드 (Record Lock)
-- 2. age >= 20 범위의 간격 (Gap Lock)
-- 3. 결과적으로 새 행 삽입 차단 (Phantom Read 방지)
```

**3) SERIALIZABLE 격리 수준**
```sql
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
START TRANSACTION;
SELECT * FROM students WHERE age >= 20;  -- 자동으로 S-Lock + Gap Lock
-- 다른 트랜잭션의 INSERT 차단됨
COMMIT;
```

#### 2. Lock Escalation (락 에스컬레이션)

**문제:**
- 대량의 행을 처리할 때 개별 행 락이 과도하게 증가
- 락 관리 오버헤드 증가 (메모리, CPU)
- DBMS가 자동으로 행 락 → 페이지 락 → 테이블 락으로 승격

```sql
-- 예: 100만 행을 업데이트하는 경우
UPDATE students SET status = 'graduated' WHERE year = 2020;

-- 초기: 100만 개의 행 락
-- Lock Escalation 발생 → 테이블 락으로 전환
-- 결과: 전체 테이블이 잠겨 다른 트랜잭션 블로킹
```

**InnoDB의 접근:**
- InnoDB는 Lock Escalation을 하지 않음
- 대신 락 메모리 제한이 있음 (`innodb_buffer_pool_size`)
- 과도한 락 발생 시 메모리 부족 에러 발생 가능

**해결 방법:**
```sql
-- 1. 배치 처리로 분할
UPDATE students SET status = 'graduated'
WHERE year = 2020
ORDER BY id
LIMIT 10000;  -- 여러 번 반복 실행

-- 2. 인덱스 활용으로 락 범위 최소화
CREATE INDEX idx_year ON students(year);

-- 3. 트랜잭션 크기 최소화
-- 짧은 트랜잭션으로 분할하여 락 보유 시간 감소
```

#### 3. Concurrency vs Consistency 트레이드오프

2PL은 강한 일관성을 보장하지만 동시성이 낮다:
- 락 대기로 인한 처리량(Throughput) 감소
- 응답 시간(Latency) 증가
- Deadlock 발생 가능

**워크로드별 최적 선택:**

| 워크로드 | 최적 전략 | 이유 |
|---------|----------|------|
| 금융 거래 | Strict 2PL | 정확성이 최우선 |
| 재고 관리 | Strict 2PL + 낙관적 락 | 충돌 적고 정확성 필요 |
| SNS 피드 | MVCC (기본 SELECT) | 읽기 위주, 약간의 불일치 허용 |
| 통계 조회 | MVCC | 실시간 정확성 불필요 |
| 좌석 예매 | Strict 2PL | 동시 예매 방지 필수 |

#### 4. Optimistic Concurrency Control (OCC, 낙관적 동시성 제어)

2PL의 대안으로, 충돌이 거의 없을 것으로 가정하는 방식

**OCC 동작 방식:**
1. **Read Phase**: 락 없이 자유롭게 읽기
2. **Validation Phase**: 커밋 시점에 충돌 검사
3. **Write Phase**: 충돌 없으면 커밋, 있으면 롤백 후 재시도

```sql
-- JPA/Hibernate의 낙관적 락 예시
@Entity
public class Product {
    @Id
    private Long id;

    private Integer stock;

    @Version  -- 버전 필드
    private Long version;
}

-- 실행되는 SQL
UPDATE product
SET stock = 90, version = version + 1
WHERE id = 1 AND version = 10;  -- 버전 검증

-- 업데이트 실패 시 (version 불일치) → OptimisticLockException
-- 애플리케이션에서 재시도
```

**2PL vs OCC 비교:**

| 특성 | 2PL (Pessimistic) | OCC (Optimistic) |
|------|-------------------|------------------|
| 충돌 가정 | 충돌이 자주 발생 | 충돌이 거의 없음 |
| 락 획득 시점 | 읽기/쓰기 시작 시 | 커밋 시점 (검증용) |
| 충돌 감지 | 사전 방지 (락 대기) | 사후 감지 (롤백) |
| 높은 충돌 환경 | **유리** (재시도 비용 없음) | 불리 (잦은 롤백) |
| 낮은 충돌 환경 | 불리 (불필요한 락) | **유리** (락 오버헤드 없음) |
| Deadlock | 발생 가능 | 발생 안 함 |
| 구현 복잡도 | 중간 | 높음 (재시도 로직) |

**사용 예시:**
```sql
-- 2PL (Pessimistic Locking): 좌석 예매 (충돌 많음)
START TRANSACTION;
SELECT * FROM seats WHERE id = 100 FOR UPDATE;  -- 즉시 락 획득
UPDATE seats SET status = 'reserved' WHERE id = 100;
COMMIT;

-- OCC (Optimistic Locking): 게시글 조회수 증가 (충돌 적음)
START TRANSACTION;
SELECT view_count, version FROM posts WHERE id = 100;  -- 락 없음
UPDATE posts
SET view_count = view_count + 1, version = version + 1
WHERE id = 100 AND version = 10;  -- 커밋 시점에 버전 검증
COMMIT;
```

#### 5. 최신 동시성 제어 기법

**1) Snapshot Isolation (SI)**
- MVCC 기반으로 트랜잭션 시작 시점의 스냅샷 제공
- PostgreSQL의 기본 격리 수준
- Write Skew 이상 현상 발생 가능

**2) Serializable Snapshot Isolation (SSI)**
- Snapshot Isolation + 직렬 가능성 보장
- PostgreSQL의 SERIALIZABLE 레벨
- 2PL보다 높은 동시성, MVCC보다 강한 일관성

**3) Deterministic Database**
- 트랜잭션 실행 순서를 미리 결정
- 단일 스레드 실행으로 락 불필요
- VoltDB, Calvin 등
- 극도로 높은 처리량, but 제한적 사용 사례

## 핵심 정리
- 2PL은 트랜잭션 직렬 가능성을 보장하는 가장 대표적인 동시성 제어 프로토콜이다
- Growing Phase와 Shrinking Phase를 분리하여 충돌 직렬 가능성(Conflict Serializability)을 보장한다
- Strict 2PL은 Cascading Rollback을 방지하여 실무에서 가장 널리 사용된다
- MySQL InnoDB는 Strict 2PL과 MVCC를 함께 사용하여 읽기/쓰기 성능을 최적화한다
- 2PL 환경에서는 Deadlock 발생 가능성이 있으므로 Deadlock Detection 메커니즘이 필수적이다

## 키워드

- **2-Phase Locking (2PL)**: 트랜잭션이 락을 획득하는 Growing Phase와 해제하는 Shrinking Phase로 나누어 직렬 가능성을 보장하는 동시성 제어 프로토콜
- **Growing Phase**: 트랜잭션이 락을 획득만 할 수 있고 해제할 수 없는 단계. 첫 번째 락 해제 시점(Lock Point)까지 지속됨
- **Shrinking Phase**: 트랜잭션이 락을 해제만 할 수 있고 새로 획득할 수 없는 단계. Lock Point 이후부터 트랜잭션 종료까지 지속됨
- **Strict 2PL**: 모든 Exclusive Lock을 트랜잭션 커밋/롤백 시점까지 유지하는 방식. Cascading Rollback을 방지하며 실무에서 가장 널리 사용됨
- **Rigorous 2PL**: 모든 Lock(Shared + Exclusive)을 트랜잭션 종료 시점까지 유지하는 가장 강력한 2PL 방식
- **Serializability (직렬 가능성)**: 동시 실행되는 트랜잭션들의 결과가 어떤 순서로 직렬 실행한 것과 동일함을 보장하는 속성. 2PL은 Conflict Serializability를 보장함
- **Deadlock**: 두 개 이상의 트랜잭션이 서로가 보유한 락을 무한정 대기하는 상태. 2PL에서는 락 획득 순서 차이로 인해 발생 가능
- **MVCC (Multi-Version Concurrency Control)**: 데이터의 여러 버전을 유지하여 읽기와 쓰기 간 충돌을 회피하는 동시성 제어 기법. InnoDB는 2PL과 MVCC를 혼합 사용
- **InnoDB Lock**: MySQL InnoDB의 락 메커니즘으로 Record Lock, Gap Lock, Next-Key Lock 등을 제공하여 Strict 2PL과 Phantom Read 방지를 구현함
- **Cascading Rollback**: 한 트랜잭션의 롤백이 다른 트랜잭션들의 연쇄 롤백을 유발하는 현상. Strict 2PL로 방지 가능

## 참고 자료
- [MySQL 8.0 Reference Manual - InnoDB Locking](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)
- [MySQL 8.0 Reference Manual - Transaction Isolation Levels](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html)
- [MySQL 8.0 Reference Manual - Deadlocks in InnoDB](https://dev.mysql.com/doc/refman/8.0/en/innodb-deadlocks.html)
- Database System Concepts (7th Edition) - Silberschatz, Korth, Sudarshan - Chapter 15: Concurrency Control
- Transaction Processing: Concepts and Techniques - Gray, Reuter - Chapter 7: Isolation Concepts
