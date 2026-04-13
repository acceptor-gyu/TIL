# Worker Pool 패턴

## 개요
Worker Pool(워커 풀) 패턴은 미리 생성해둔 고정 개수의 워커 스레드(또는 고루틴, 코루틴)가 공유 작업 큐에서 태스크를 꺼내 처리하는 동시성 디자인 패턴이다. 스레드를 매번 생성/소멸하는 비용을 줄이고, 동시 실행 수를 제한하여 시스템 자원을 안정적으로 관리할 수 있다.

## 상세 내용

### 1. Worker Pool 패턴이란

#### 패턴의 정의와 등장 배경
워커 풀은 미리 생성된 고정 개수의 스레드가 큐에서 태스크를 꺼내 처리하는 방식이다. 스레드 객체는 상당한 양의 메모리를 사용하기 때문에 매 요청마다 스레드를 생성하고 소멸시키면 메모리 관리 오버헤드가 커진다. 워커 풀은 스레드를 재사용함으로써 이 문제를 해결한다.

#### Thread-per-Request 모델의 한계
가장 단순한 동시성 모델인 Thread-per-Request는 요청이 들어올 때마다 새 스레드를 생성하여 처리한다.

| 항목 | Thread-per-Request | Worker Pool |
|---|---|---|
| 스레드 생성 비용 | 요청마다 발생 | 초기화 시 1회 |
| 메모리 사용 | 요청 수에 비례 | 고정 |
| 동시성 제어 | 어려움 | 풀 사이즈로 제어 |
| 트래픽 스파이크 대응 | 스레드 폭발 위험 | 큐 대기로 자연스럽게 제한 |

트래픽이 급증할 경우 Thread-per-Request는 OOM(Out Of Memory) 오류나 컨텍스트 스위칭 폭발로 이어질 수 있다.

#### Producer-Consumer 패턴과의 관계
Worker Pool은 Producer-Consumer 패턴의 특수한 형태다.

- **Producer**: 태스크를 생성하여 작업 큐에 제출하는 주체 (예: HTTP 요청 수신 스레드)
- **Consumer**: 작업 큐에서 태스크를 꺼내 처리하는 워커 스레드
- **Queue**: 두 역할 사이의 버퍼 역할을 하는 BlockingQueue

Producer와 Consumer의 처리 속도 차이를 큐가 완충해주기 때문에 서로 독립적으로 동작할 수 있다.

---

### 2. 핵심 구성 요소

#### Task Queue (작업 큐)
워커들이 처리할 태스크를 보관하는 버퍼다. Java에서는 `BlockingQueue` 인터페이스를 구현한 자료구조가 사용된다. 큐가 가득 차면 제출을 블로킹하거나 거부 정책을 실행한다.

#### Worker Thread (워커 스레드)
큐에서 태스크를 꺼내 실행하는 스레드다. 풀 내 일정 개수가 유지되며, 태스크가 없을 때는 Idle 상태로 대기한다.

#### Task Submitter (작업 제출자)
`ExecutorService.submit()` 또는 `execute()`를 통해 태스크를 풀에 제출하는 주체다. 결과가 필요하면 `Future` 객체를 받아 나중에 결과를 조회한다.

#### Result Collector (결과 수집기)
`Future<T>` 또는 `CompletableFuture<T>`를 통해 비동기 태스크의 결과를 수집한다. 여러 태스크의 결과를 모을 때는 `invokeAll()`을 활용할 수 있다.

---

### 3. 동작 흐름

#### 워커 풀 초기화 과정
1. `ThreadPoolExecutor` 생성 시 `corePoolSize` 만큼 스레드를 즉시 생성하거나, 첫 태스크 제출 시 생성한다.
2. 각 워커 스레드는 내부 루프에서 `workQueue.take()`를 호출하여 태스크를 대기한다.

#### 태스크 제출 -> 큐 대기 -> 워커 할당 -> 처리 -> 결과 반환

```
[Submitter]
    │
    ▼  execute() / submit()
[ThreadPoolExecutor]
    │
    ├─ corePoolSize 미만 → 새 워커 스레드 즉시 생성하여 처리
    │
    ├─ corePoolSize 이상, 큐 여유 있음 → 큐에 적재
    │
    ├─ 큐 가득 참, maximumPoolSize 미만 → 임시 스레드 생성
    │
    └─ 큐 가득 참, maximumPoolSize 초과 → RejectedExecutionHandler 실행
```

#### 워커의 생명주기
- **Idle**: 큐가 비어 있어 `take()`에서 블로킹 대기 중
- **Running**: 태스크를 꺼내 실행 중
- **Shutdown**: `shutdown()` 또는 `shutdownNow()` 호출 후 종료 대기 또는 즉시 인터럽트

---

### 4. Java에서의 Worker Pool 구현

#### ExecutorService와 ThreadPoolExecutor

`ExecutorService`는 인터페이스이고, 실제 구현체가 `ThreadPoolExecutor`다. 팩토리 메서드인 `Executors`를 통해 간편하게 생성할 수 있지만, 프로덕션에서는 직접 `ThreadPoolExecutor`를 생성하여 파라미터를 명시적으로 제어하는 것이 권장된다.

```java
// 팩토리 메서드 (프로덕션에서는 지양)
ExecutorService fixedPool = Executors.newFixedThreadPool(10);

// 직접 생성 (권장)
ExecutorService executor = new ThreadPoolExecutor(
    4,                              // corePoolSize
    8,                              // maximumPoolSize
    60L, TimeUnit.SECONDS,          // keepAliveTime
    new ArrayBlockingQueue<>(100),  // workQueue
    new ThreadPoolExecutor.CallerRunsPolicy() // rejectionHandler
);
```

#### corePoolSize, maximumPoolSize, keepAliveTime 설정 전략

| 파라미터 | 역할 | 설정 기준 |
|---|---|---|
| `corePoolSize` | 항상 유지할 기본 스레드 수 | 평균 부하 기준으로 설정 |
| `maximumPoolSize` | 큐 포화 시 생성 가능한 최대 스레드 수 | 피크 부하 기준으로 설정 |
| `keepAliveTime` | core 초과 유휴 스레드의 유지 시간 | 트래픽 패턴에 따라 조정 |

`Executors.newFixedThreadPool()`은 `corePoolSize == maximumPoolSize`이고 `LinkedBlockingQueue`(무제한 큐)를 사용하기 때문에 큐 메모리 폭발 위험이 있다. `Executors.newCachedThreadPool()`은 `SynchronousQueue`를 사용하며 스레드 수 제한이 없어 트래픽 스파이크 시 스레드 폭발 위험이 있다.

#### BlockingQueue 종류별 특성

| 큐 종류 | 용량 | 특성 | 사용 시나리오 |
|---|---|---|---|
| `LinkedBlockingQueue` | 기본 무제한 (Integer.MAX_VALUE) | 높은 처리량, 큐 폭발 위험 | 태스크 유실 허용 불가 시 |
| `ArrayBlockingQueue` | 고정 (생성 시 지정) | 메모리 예측 가능, 포화 시 거부 | 백프레셔 제어가 필요할 때 |
| `SynchronousQueue` | 0 (버퍼 없음) | 즉시 전달 또는 즉시 거부 | `newCachedThreadPool()` |
| `PriorityBlockingQueue` | 무제한 | 우선순위 정렬 | 태스크 우선순위가 있을 때 |

#### RejectedExecutionHandler 정책

큐가 가득 차고 스레드 수도 `maximumPoolSize`에 달했을 때 실행되는 정책이다.

| 정책 | 동작 | 적합한 상황 |
|---|---|---|
| `AbortPolicy` (기본) | `RejectedExecutionException` 예외 발생 | 거부를 명시적으로 처리해야 할 때 |
| `CallerRunsPolicy` | 제출한 스레드가 직접 실행 (자연스러운 백프레셔) | 태스크 유실을 막고 속도 조절이 필요할 때 |
| `DiscardPolicy` | 조용히 태스크 삭제 | 오래된 데이터는 버려도 되는 경우 |
| `DiscardOldestPolicy` | 큐의 가장 오래된 태스크를 삭제하고 재시도 | 최신 태스크가 더 중요한 경우 |

---

### 5. Worker Pool 사이징 전략

#### CPU-bound 작업
CPU를 계속 점유하는 연산 위주 작업은 스레드 수를 코어 수에 맞추는 것이 최적이다. 스레드가 코어보다 많으면 컨텍스트 스위칭 비용만 증가한다.

```
스레드 수 = CPU 코어 수 (+ 1 정도의 여유)
```

`Runtime.getRuntime().availableProcessors()`로 런타임에 코어 수를 조회할 수 있다.

#### I/O-bound 작업
네트워크, 디스크 I/O, DB 쿼리 등 대기 시간이 긴 작업은 스레드가 블로킹 상태로 CPU를 놓아두기 때문에 코어 수보다 많은 스레드를 사용하는 것이 효율적이다.

```
스레드 수 = 코어 수 × 목표 CPU 사용률 × (1 + 대기 시간 / 처리 시간)
```

예를 들어, 코어가 4개이고 요청 처리 시간이 10ms, DB 대기 시간이 40ms라면:

```
스레드 수 = 4 × 1.0 × (1 + 40/10) = 4 × 5 = 20
```

#### Little's Law를 활용한 최적 풀 사이즈 계산

Little's Law: **L = λ × W**

- **L**: 시스템 내 평균 동시 요청 수 (필요한 스레드 수)
- **λ**: 초당 요청 도착률
- **W**: 요청 처리 평균 소요 시간 (초)

예시: 초당 100개의 요청이 들어오고, 각 요청 처리에 평균 0.2초가 걸린다면 필요한 스레드 수는 100 × 0.2 = **20개**다.

이 수치는 풀 사이즈의 하한선이며, 여기에 여유분과 큐 크기를 더해 설정한다.

#### 동적 풀 사이징과 모니터링
고정 사이즈만으로는 부족한 경우, 다음 메트릭을 모니터링하여 동적으로 조정한다.

- `getActiveCount()`: 현재 실행 중인 스레드 수
- `getQueue().size()`: 대기 중인 태스크 수
- `getCompletedTaskCount()`: 처리 완료된 태스크 수
- `getTaskCount()`: 전체 제출된 태스크 수

큐 사이즈가 지속적으로 증가하면 스레드 부족, 지속적으로 0에 가까우면 스레드 과잉의 신호다.

---

### 6. 주의사항과 안티패턴

#### 데드락 (Thread Starvation Deadlock)
가장 흔한 워커 풀 데드락은 풀 내 태스크가 같은 풀에 새 태스크를 제출하고 그 결과를 블로킹으로 기다리는 경우다.

```java
// 위험한 패턴: 워커가 같은 풀에 태스크를 제출하고 블로킹 대기
Future<?> inner = executor.submit(() -> { ... });
inner.get(); // 풀이 가득 차면 영원히 대기 → 데드락
```

해결책: 상호 의존적인 태스크는 별도의 스레드 풀로 격리하거나, 비동기 콜백 방식(`CompletableFuture.thenApply()`)으로 변경한다.

#### 스레드 누수 (Thread Leak)
태스크 실행 중 예외가 발생했는데 제대로 처리되지 않으면, 일부 구현에서 워커 스레드가 비정상 종료될 수 있다. `ThreadPoolExecutor`는 내부적으로 예외로 종료된 워커를 교체하지만, `try-catch`로 예외를 명시적으로 처리하는 것이 바람직하다.

#### 큐 메모리 폭발 (Queue Overflow)
`Executors.newFixedThreadPool()`의 내부 큐는 `LinkedBlockingQueue`로 기본 용량이 `Integer.MAX_VALUE`다. 소비 속도보다 빠르게 태스크가 쌓이면 GC 압박과 OOM으로 이어진다. 반드시 `ArrayBlockingQueue`로 용량을 제한하고 거부 정책을 설정해야 한다.

#### 스레드 로컬 변수 오염 (ThreadLocal Pollution)
워커 스레드는 재사용되기 때문에 이전 태스크가 설정한 `ThreadLocal` 값이 다음 태스크에 남아 있을 수 있다. 태스크 완료 후 반드시 `ThreadLocal.remove()`를 호출해야 한다.

```java
// 안전한 패턴
try {
    threadLocal.set(value);
    // ... 처리 ...
} finally {
    threadLocal.remove(); // 반드시 정리
}
```

---

### 7. 다른 언어에서의 Worker Pool

#### Go: goroutine + channel 기반 워커 풀
Go는 goroutine이 경량(스택 초기 크기 ~2KB)이지만, 무제한으로 생성하면 메모리 문제가 발생한다. channel을 버퍼로 사용하여 간결하게 워커 풀을 구현할 수 있다.

```go
func worker(id int, jobs <-chan int, results chan<- int) {
    for j := range jobs {
        results <- process(j)
    }
}

func main() {
    jobs := make(chan int, 100)
    results := make(chan int, 100)

    // 3개의 워커 생성
    for w := 1; w <= 3; w++ {
        go worker(w, jobs, results)
    }

    // 태스크 제출
    for j := 1; j <= 9; j++ {
        jobs <- j
    }
    close(jobs)

    // 결과 수집
    for a := 1; a <= 9; a++ {
        <-results
    }
}
```

`jobs` 채널이 닫히면 워커들은 자연스럽게 종료된다. 채널의 버퍼 크기가 실질적인 큐 역할을 한다.

#### Kotlin: Coroutine Dispatcher
Kotlin Coroutine에서는 `CoroutineDispatcher`가 워커 풀의 역할을 담당한다.

| Dispatcher | 내부 동작 | 사용 시나리오 |
|---|---|---|
| `Dispatchers.Default` | 공유 스레드 풀 (코어 수 기준) | CPU-bound 작업 |
| `Dispatchers.IO` | 확장 가능한 스레드 풀 (최대 64개) | I/O-bound 작업 |
| `Dispatchers.Main` | 메인(UI) 스레드 | Android UI 업데이트 |

```kotlin
// I/O 작업을 IO Dispatcher에서 실행
val result = withContext(Dispatchers.IO) {
    fetchDataFromNetwork()
}
```

#### Node.js: worker_threads 모듈
Node.js는 단일 스레드 이벤트 루프 기반이지만, `worker_threads` 모듈로 CPU-bound 작업을 별도 스레드에서 처리할 수 있다. 이벤트 루프 블로킹을 방지하는 것이 주목적이다.

---

## 핵심 정리
- Worker Pool은 스레드 재사용을 통해 생성/소멸 비용을 절감하고 동시성을 제어하는 패턴이다
- 풀 사이즈는 작업 특성(CPU-bound vs I/O-bound)에 따라 다르게 설정해야 하며, Little's Law로 이론적 근거를 마련할 수 있다
- Java에서는 `ThreadPoolExecutor`가 표준 구현이며, 큐 전략(`ArrayBlockingQueue` 권장)과 거부 정책을 적절히 조합해야 한다
- 데드락(스레드 기아), 스레드 누수, 큐 폭발, ThreadLocal 오염 등 흔한 함정을 이해하고 방지해야 한다
- Go는 goroutine + channel, Kotlin은 CoroutineDispatcher로 언어 수준에서 워커 풀 추상화를 제공한다

## 키워드

- **Worker Pool**: 미리 생성된 고정 개수의 워커 스레드가 공유 큐에서 태스크를 꺼내 처리하는 동시성 패턴
- **Thread Pool**: 스레드를 재사용하기 위해 풀로 관리하는 방식. Worker Pool과 동의어로 사용되는 경우가 많다
- **ExecutorService**: Java의 스레드 풀 추상화 인터페이스. `submit()`, `shutdown()` 등의 생명주기 메서드를 제공한다
- **ThreadPoolExecutor**: Java에서 `ExecutorService`의 주요 구현체. corePoolSize, maximumPoolSize, workQueue, rejectionHandler 등을 직접 설정할 수 있다
- **BlockingQueue**: 워커 풀의 작업 큐로 사용되는 인터페이스. 큐가 비어 있으면 `take()`가 블로킹되고, 가득 차면 `put()`이 블로킹되거나 거부된다
- **Producer-Consumer**: 태스크를 생산하는 측과 소비하는 측을 분리하는 패턴. Worker Pool은 이 패턴의 구체적인 구현이다
- **RejectedExecutionHandler**: ThreadPoolExecutor에서 큐가 가득 차고 스레드 수도 최대에 달했을 때 태스크 처리 방식을 결정하는 정책 인터페이스
- **Little's Law**: 시스템 내 평균 요청 수(L) = 도착률(λ) × 처리 시간(W). 필요한 스레드 수를 계산하는 데 활용된다
- **CPU-bound vs I/O-bound**: CPU-bound 태스크는 스레드 수를 코어 수에 맞추고, I/O-bound 태스크는 대기 시간 비율을 고려해 더 많은 스레드를 사용한다
- **Thread Reuse**: 스레드 생성/소멸 비용을 줄이기 위해 처리가 끝난 스레드를 종료하지 않고 다음 태스크에 재사용하는 전략

## 참고 자료
- [ThreadPoolExecutor (Java SE 17 & JDK 17) - Oracle 공식 문서](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)
- [Thread Pools - The Java Tutorials (Oracle)](https://docs.oracle.com/javase/tutorial/essential/concurrency/pools.html)
- [Go by Example: Worker Pools](https://gobyexample.com/worker-pools)
- [How to set an ideal thread pool size - Zalando Engineering Blog](https://engineering.zalando.com/posts/2019/04/how-to-set-an-ideal-thread-pool-size.html)
- [Thread Pool Self-Induced Deadlocks - DZone](https://dzone.com/articles/thread-pool-self-induced-deadlocks)
- [TPS01-J. Do not execute interdependent tasks in a bounded thread pool - SEI CERT Oracle Coding Standard for Java](https://wiki.sei.cmu.edu/confluence/display/java/TPS01-J.+Do+not+execute+interdependent+tasks+in+a+bounded+thread+pool)
