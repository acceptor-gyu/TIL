# MySQL ReentrantLock

## 개요
MySQL에서의 잠금(Lock) 메커니즘과 Java의 ReentrantLock을 활용한 동시성 제어 전략에 대해 학습한다.

## 상세 내용

### 1. ReentrantLock이란

`ReentrantLock`은 Java의 `java.util.concurrent.locks` 패키지에 속한 명시적 잠금 클래스다. `synchronized` 키워드와 동일한 상호 배제(mutual exclusion) 기능을 제공하지만, 더 세밀한 제어가 가능하다.

**재진입(Reentrant)의 의미**

같은 스레드가 이미 보유 중인 락을 다시 획득할 수 있다는 의미다. 재진입 시마다 내부 카운터가 증가하며, `unlock()`을 호출할 때마다 1씩 감소한다. 카운터가 0이 되어야 비로소 락이 해제된다.

```java
ReentrantLock lock = new ReentrantLock();

lock.lock();    // holdCount: 1
lock.lock();    // holdCount: 2
lock.unlock();  // holdCount: 1
lock.unlock();  // holdCount: 0 → 락 해제
```

**synchronized와 ReentrantLock의 차이점**

| 구분 | synchronized | ReentrantLock |
|---|---|---|
| 잠금 방식 | 암묵적(implicit) | 명시적(explicit) |
| 공정성 설정 | 불가 | 가능 (`new ReentrantLock(true)`) |
| 타임아웃 | 불가 | `tryLock(long, TimeUnit)` |
| 인터럽트 처리 | 불가 | `lockInterruptibly()` |
| Condition 활용 | 단일 (`wait/notify`) | 다중 (`newCondition()`) |
| 모니터링 | 제한적 | `getHoldCount()`, `isHeldByCurrentThread()` 등 |
| 락 해제 위치 | 블록 종료 시 자동 | 명시적으로 `unlock()` 호출 필요 |

일반적인 동시성 제어에는 `synchronized`로 충분하지만, 타임아웃 처리, 공정성 보장, 다중 Condition이 필요한 경우 `ReentrantLock`을 선택한다.

---

### 2. MySQL Lock과 ReentrantLock의 관계

**애플리케이션 레벨 Lock vs 데이터베이스 레벨 Lock**

| 구분 | 애플리케이션 레벨 (ReentrantLock) | DB 레벨 (MySQL Lock) |
|---|---|---|
| 적용 범위 | 동일 JVM(프로세스) 내 스레드 간 | 데이터베이스에 접근하는 모든 클라이언트 |
| 분산 환경 대응 | 불가 (단일 서버) | 가능 (Named Lock) |
| 성능 | 빠름 | 네트워크 비용 발생 |
| 관리 | 코드 레벨 제어 | DB 세션 레벨 관리 |

**MySQL Named Lock**

MySQL은 사용자 정의 이름에 대한 잠금을 설정하는 `GET_LOCK()` 함수를 제공한다. 이는 애플리케이션 레벨의 분산 락(Distributed Lock)을 DB만으로 구현할 수 있게 해준다.

```sql
-- 잠금 획득 (최대 10초 대기)
SELECT GET_LOCK('order_lock_1234', 10);
-- 반환값: 1 (성공), 0 (타임아웃), NULL (오류)

-- 잠금 해제
SELECT RELEASE_LOCK('order_lock_1234');
-- 반환값: 1 (해제 성공), 0 (현재 세션이 보유하지 않음), NULL (잠금 없음)

-- 잠금 상태 확인
SELECT IS_FREE_LOCK('order_lock_1234');   -- 1: 사용 가능, 0: 사용 중
SELECT IS_USED_LOCK('order_lock_1234');   -- 잠금 보유 중인 connection ID 반환
```

MySQL Named Lock의 주요 특성:
- 잠금 이름 최대 **64자**
- 잠금은 **세션 단위**로 관리. 세션 종료 시 자동 해제
- 트랜잭션 커밋/롤백으로는 해제되지 않음
- 잠금 획득과 해제는 **같은 커넥션**에서 수행해야 함

**분산 환경에서의 Lock 전략 선택 기준**

```
단일 서버, 단일 JVM
    → ReentrantLock (가장 빠름)

다중 서버, DB 인프라 활용 가능
    → MySQL Named Lock (Redis 없이 분산 락 구현 가능)

다중 서버, 고성능 요구, 확장성 중요
    → Redis Distributed Lock (Redisson, Lettuce)
```

---

### 3. ReentrantLock의 공정성(Fairness)

**Fair Lock vs Non-Fair Lock**

```java
// Non-Fair Lock (기본값) - 처리량 우선
ReentrantLock nonFairLock = new ReentrantLock();
ReentrantLock nonFairLock = new ReentrantLock(false);

// Fair Lock - 순서 보장
ReentrantLock fairLock = new ReentrantLock(true);
```

| 구분 | Fair Lock | Non-Fair Lock |
|---|---|---|
| 스레드 선택 방식 | 대기 시간이 긴 순서 (FIFO) | 임의 선택 (바로 시도 허용) |
| 처리량(Throughput) | 낮음 | 높음 |
| 기아(Starvation) | 없음 | 발생 가능 |
| 컨텍스트 스위칭 | 잦음 | 적음 |

Non-Fair Lock에서 높은 처리량이 나오는 이유는, 락 해제 시점에 이미 CPU에서 실행 중인 스레드가 대기 큐의 스레드보다 먼저 락을 획득할 수 있기 때문이다. 이를 **Barging**이라 한다.

**주의: `tryLock()`은 공정성을 무시한다**

```java
ReentrantLock fairLock = new ReentrantLock(true);

// tryLock()은 fairness=true여도 즉시 락 획득을 시도한다
// 대기 중인 다른 스레드를 무시하고 선점할 수 있다
if (fairLock.tryLock()) {
    try {
        // ...
    } finally {
        fairLock.unlock();
    }
}
```

**MySQL Lock Wait와의 비교**

MySQL의 `innodb_lock_wait_timeout`(기본 50초)은 Non-Fair 방식에 가깝다. 대기 중인 트랜잭션 간의 처리 순서는 보장되지 않으며, InnoDB는 데드락 감지 후 롤백 비용이 가장 작은 트랜잭션을 희생양(victim)으로 선택한다.

---

### 4. ReentrantLock 활용 패턴

**try-finally 패턴: 안전한 Lock 해제**

`ReentrantLock`은 `synchronized`와 달리 명시적으로 `unlock()`을 호출해야 한다. 예외 발생 시에도 반드시 해제되도록 `finally` 블록에 작성한다.

```java
public class OrderService {
    private final ReentrantLock lock = new ReentrantLock();

    public void processOrder(Long orderId) {
        lock.lock();
        try {
            // 주문 처리 로직
            validateOrder(orderId);
            updateStock(orderId);
            saveOrder(orderId);
        } finally {
            lock.unlock(); // 예외 발생 여부와 무관하게 반드시 해제
        }
    }
}
```

**tryLock()을 활용한 타임아웃 처리**

무한 대기를 방지하고 타임아웃 시 대체 로직을 수행할 수 있다.

```java
public class TicketService {
    private final ReentrantLock lock = new ReentrantLock();

    public boolean reserveTicket(Long ticketId) {
        boolean acquired = false;
        try {
            acquired = lock.tryLock(3, TimeUnit.SECONDS); // 최대 3초 대기
            if (!acquired) {
                log.warn("Lock 획득 실패: ticketId={}", ticketId);
                throw new LockAcquisitionException("예매 처리 중입니다. 잠시 후 다시 시도해주세요.");
            }
            // 티켓 예매 로직
            return doReserve(ticketId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
            throw new RuntimeException("예매 처리가 중단되었습니다.", e);
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }
}
```

`tryLock()`(인자 없는 버전)은 즉시 반환되므로, 락을 획득하지 못해도 예외를 던지지 않고 다른 작업을 수행할 수 있다.

```java
// 즉시 반환 - 대기하지 않음
if (lock.tryLock()) {
    try {
        // 락 획득 성공
    } finally {
        lock.unlock();
    }
} else {
    // 락 획득 실패 시 대체 처리
}
```

**Condition을 활용한 세밀한 스레드 제어**

`synchronized`의 `wait()/notify()`는 단일 대기 집합만 가지지만, `Condition`은 여러 개를 만들어 조건별로 스레드를 제어할 수 있다.

```java
public class BoundedBuffer<T> {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition(); // 버퍼가 가득 찼을 때 생산자 대기
    private final Condition notEmpty = lock.newCondition(); // 버퍼가 비었을 때 소비자 대기

    private final Queue<T> buffer = new LinkedList<>();
    private final int capacity;

    public BoundedBuffer(int capacity) {
        this.capacity = capacity;
    }

    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (buffer.size() == capacity) {
                notFull.await(); // 버퍼 여유 공간이 생길 때까지 대기
            }
            buffer.offer(item);
            notEmpty.signal(); // 소비자에게 신호
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (buffer.isEmpty()) {
                notEmpty.await(); // 버퍼에 아이템이 생길 때까지 대기
            }
            T item = buffer.poll();
            notFull.signal(); // 생산자에게 신호
            return item;
        } finally {
            lock.unlock();
        }
    }
}
```

`synchronized`의 `notifyAll()`은 모든 대기 스레드를 깨우지만, `Condition`의 `signal()`은 해당 Condition을 대기 중인 스레드만 선택적으로 깨울 수 있어 불필요한 컨텍스트 스위칭을 줄인다.

---

### 5. MySQL과 함께 사용할 때의 주의점

**단일 서버 vs 다중 서버 환경에서의 한계**

`ReentrantLock`은 JVM 내부에서만 동작한다. 서버가 2대 이상인 환경에서는 각 서버의 락이 독립적으로 존재하므로 동시성을 보장할 수 없다.

```
[단일 서버]
  ┌─────────────┐
  │  Server A   │
  │ ReentrantLock ← 모든 요청이 이 락을 통과 (정상)
  └─────────────┘

[다중 서버 - 문제 발생]
  ┌─────────────┐     ┌─────────────┐
  │  Server A   │     │  Server B   │
  │  Lock(A)    │     │  Lock(B)    │ ← 서로 다른 락! 동시 접근 가능
  └──────┬──────┘     └──────┬──────┘
         └──────────┬─────────┘
                 [DB]
```

다중 서버 환경에서 MySQL Named Lock을 사용할 경우, 커넥션 풀과의 연동에 주의해야 한다.

```java
// Named Lock 전용 DataSource를 별도로 구성하는 것을 권장
@Bean(name = "lockDataSource")
public DataSource lockDataSource() {
    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setMaximumPoolSize(1); // 락 전용 커넥션을 고정하여 같은 세션 보장
    // ...
    return dataSource;
}
```

**DB Lock과 애플리케이션 Lock의 이중 잠금 문제**

두 레벨에서 동시에 락을 사용하면 데드락이 발생할 가능성이 높아진다.

```
Thread A: ReentrantLock 획득 → DB Row Lock 대기
Thread B: DB Row Lock 획득 → ReentrantLock 대기
→ 데드락 발생
```

이를 방지하려면 락의 획득 순서를 항상 일정하게 유지해야 한다. 일반적으로 하나의 레벨만 사용하거나, 반드시 필요한 경우 애플리케이션 락을 먼저 획득하는 순서를 강제한다.

**데드락 방지 전략**

1. **항상 같은 순서로 락 획득**: 여러 자원에 대한 락을 획득할 때 순서를 일관되게 유지
2. **타임아웃 설정**: `tryLock(timeout)`으로 무한 대기 방지
3. **락 범위 최소화**: 락을 보유하는 시간을 최대한 짧게 유지
4. **단일 레벨 원칙**: 가능하면 애플리케이션 락과 DB 락 중 하나만 사용

```java
// 올바른 예: 타임아웃을 설정하여 데드락 회피
public void transferPoints(Long fromId, Long toId, int amount) {
    // 항상 id가 작은 쪽을 먼저 잠금 (순서 일관성)
    Long firstId = Math.min(fromId, toId);
    Long secondId = Math.max(fromId, toId);

    boolean firstLocked = false, secondLocked = false;
    try {
        firstLocked = getLock(firstId).tryLock(5, TimeUnit.SECONDS);
        if (!firstLocked) throw new LockAcquisitionException("Lock 획득 실패");

        secondLocked = getLock(secondId).tryLock(5, TimeUnit.SECONDS);
        if (!secondLocked) throw new LockAcquisitionException("Lock 획득 실패");

        doTransfer(fromId, toId, amount);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } finally {
        if (secondLocked) getLock(secondId).unlock();
        if (firstLocked)  getLock(firstId).unlock();
    }
}
```

---

## 핵심 정리
- `ReentrantLock`은 `synchronized`보다 세밀한 동시성 제어(공정성, 타임아웃, 다중 Condition)를 제공하지만, `unlock()`을 반드시 `finally` 블록에서 명시적으로 호출해야 한다.
- MySQL Named Lock(`GET_LOCK`)은 DB 하나만으로 분산 락을 구현할 수 있는 실용적인 방법이지만, 커넥션 풀과 동일 세션 유지 문제를 반드시 고려해야 한다.
- `ReentrantLock`은 단일 JVM 내에서만 유효하므로, 다중 서버 환경에서는 DB 레벨 락이나 Redis 분산 락으로 대체해야 한다.
- Fair Lock은 기아 상태를 방지하지만 처리량이 낮아지므로, 대부분의 경우 Non-Fair Lock을 사용하고 특별히 순서 보장이 필요할 때만 Fair Lock을 선택한다.
- 애플리케이션 락과 DB 락을 동시에 사용할 경우 데드락 위험이 높아지며, 이를 방지하려면 락 획득 순서를 일관되게 유지하고 타임아웃을 설정해야 한다.

---

## 키워드
- `ReentrantLock`: `java.util.concurrent.locks` 패키지의 명시적 재진입 잠금 클래스로, `synchronized`보다 세밀한 동시성 제어를 제공한다.
- `synchronized`: Java의 암묵적 잠금 키워드로, 메서드나 블록 단위로 상호 배제를 보장하며 JVM이 자동으로 락 획득/해제를 관리한다.
- `Fair Lock`: 대기 시간이 긴 스레드부터 순서대로 락을 부여하는 방식으로 기아 현상을 방지하지만 처리량이 낮다.
- `Non-Fair Lock`: 락 획득 순서를 보장하지 않고 즉시 시도를 허용하는 방식으로 처리량이 높지만 특정 스레드의 기아 현상이 발생할 수 있다.
- `MySQL Named Lock`: `GET_LOCK(name, timeout)` 함수를 통해 문자열 이름에 잠금을 설정하는 MySQL의 사용자 레벨 락으로, Redis 없이 분산 락을 구현할 때 활용된다.
- `tryLock`: 락 획득을 즉시 시도하거나 지정 시간 동안 시도하고 결과를 boolean으로 반환하는 메서드로, 타임아웃 처리와 무한 대기 방지에 사용된다.
- `Condition`: `ReentrantLock`으로 생성되는 조건 변수 객체로, `await()`/`signal()`을 통해 `Object.wait()/notify()`보다 세밀하게 스레드 대기와 신호를 제어한다.
- `동시성 제어`: 여러 스레드 또는 프로세스가 공유 자원에 동시 접근할 때 데이터 일관성을 보장하기 위한 기법의 총칭이다.
- `데드락`: 두 개 이상의 스레드가 서로 상대방의 락이 해제되기를 기다리며 영원히 진행되지 못하는 교착 상태다.

---

## 참고 자료
- [ReentrantLock (Java SE 21) - Oracle Docs](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html)
- [Lock (Java SE 21) - Oracle Docs](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/Lock.html)
- [Condition (Java SE 21) - Oracle Docs](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/Condition.html)
- [MySQL 8.4 Locking Functions - MySQL Docs](https://dev.mysql.com/doc/refman/8.4/en/locking-functions.html)
- [Guide to java.util.concurrent.Locks - Baeldung](https://www.baeldung.com/java-concurrent-locks)
- [MySQL을 이용한 분산락으로 여러 서버에 걸친 동시성 관리 - 우아한형제들 기술블로그](https://techblog.woowahan.com/2631/)
