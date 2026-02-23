# Java CountDownLatch

## 개요
Java의 `java.util.concurrent` 패키지에서 제공하는 동기화 도구로, 하나 이상의 스레드가 다른 스레드들의 작업 완료를 기다릴 수 있게 해주는 카운트 기반 래치(Latch) 메커니즘

## 상세 내용

### CountDownLatch의 기본 원리

CountDownLatch는 내부적으로 **카운터**를 사용하여 여러 스레드 간의 동기화를 관리한다.

#### 동작 메커니즘

1. **초기화 단계**
   - 생성자에서 카운트 값(N) 설정
   - 내부적으로 `AbstractQueuedSynchronizer(AQS)` 사용하여 상태 관리

2. **카운트 감소**
   - `countDown()` 호출 시 카운트를 1씩 감소 (원자적 연산)
   - 카운트가 0에 도달하면 대기 중인 모든 스레드 해제

3. **대기 메커니즘**
   - `await()` 호출 시 현재 스레드를 블로킹
   - 카운트가 이미 0이면 즉시 반환
   - 카운트가 0보다 크면 대기 큐에 진입

4. **일회성 특성**
   - 카운트가 한 번 0이 되면 **재설정 불가능**
   - 재사용이 필요하면 새 인스턴스 생성 또는 `CyclicBarrier` 사용

#### 내부 구조 (간소화)

```java
public class CountDownLatch {
    private final Sync sync;  // AbstractQueuedSynchronizer 상속

    public CountDownLatch(int count) {
        if (count < 0) throw new IllegalArgumentException("count < 0");
        this.sync = new Sync(count);
    }

    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);  // count == 0이 될 때까지 대기
    }

    public void countDown() {
        sync.releaseShared(1);  // count를 1 감소
    }

    public long getCount() {
        return sync.getCount();
    }
}
```

#### 메모리 가시성 보장

CountDownLatch는 **happens-before** 관계를 보장한다:
- `countDown()` 호출 → `await()` 반환 사이의 모든 메모리 변경사항이 가시적
- `volatile` 변수나 명시적인 `synchronized` 없이도 안전한 동기화 제공

```java
// Thread 1
sharedData = "initialized";  // 1
latch.countDown();           // 2

// Thread 2
latch.await();               // 3
System.out.println(sharedData);  // 4 - "initialized" 보장됨
// 2 happens-before 3이므로 1의 결과가 4에서 보임
```

### CountDownLatch 핵심 API

#### 1. 생성자

```java
public CountDownLatch(int count)
```

- **count**: 초기 카운트 값 (0 이상의 정수)
- count < 0이면 `IllegalArgumentException` 발생
- 생성 후 카운트 값을 변경할 수 없음

```java
CountDownLatch latch = new CountDownLatch(3);  // 3회 countDown 필요
```

#### 2. await() - 무한 대기

```java
public void await() throws InterruptedException
```

- 카운트가 0이 될 때까지 **무한정 대기**
- 이미 0이면 즉시 반환
- 대기 중 인터럽트되면 `InterruptedException` 발생
- **주의**: 타임아웃 없이 무한 대기하므로 신중하게 사용

```java
try {
    latch.await();  // 다른 스레드가 countDown()을 3번 호출할 때까지 대기
    System.out.println("모든 작업 완료!");
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    System.err.println("대기 중 인터럽트 발생");
}
```

#### 3. await(long timeout, TimeUnit unit) - 타임아웃 대기

```java
public boolean await(long timeout, TimeUnit unit) throws InterruptedException
```

- 지정된 시간 동안만 대기
- 카운트가 0이 되면 `true` 반환
- 타임아웃 발생 시 `false` 반환
- **권장**: 프로덕션 환경에서는 항상 타임아웃 사용

```java
boolean completed = latch.await(30, TimeUnit.SECONDS);
if (completed) {
    System.out.println("30초 내에 완료됨");
} else {
    System.err.println("30초 타임아웃 - 일부 작업 미완료");
    // 에러 처리 로직
}
```

#### 4. countDown() - 카운트 감소

```java
public void countDown()
```

- 카운트를 1 감소 (원자적 연산)
- 카운트가 0에 도달하면 대기 중인 모든 스레드 해제
- 이미 0이면 아무 동작 안 함
- 여러 스레드에서 동시 호출해도 안전 (thread-safe)

```java
// Worker Thread
try {
    performTask();
} finally {
    latch.countDown();  // 성공/실패 무관하게 카운트 감소
}
```

#### 5. getCount() - 현재 카운트 조회

```java
public long getCount()
```

- 현재 카운트 값 반환
- 주로 디버깅이나 모니터링 용도
- **주의**: 조회 시점과 사용 시점 사이에 값이 변경될 수 있음 (race condition)

```java
System.out.println("남은 작업: " + latch.getCount());
// 출력 직후에도 다른 스레드가 countDown()을 호출할 수 있음
```

#### API 사용 예시 - 전체 흐름

```java
public class CountDownLatchExample {
    public static void main(String[] args) throws InterruptedException {
        int workerCount = 5;
        CountDownLatch latch = new CountDownLatch(workerCount);

        // 5개의 워커 스레드 시작
        for (int i = 0; i < workerCount; i++) {
            int workerId = i;
            new Thread(() -> {
                try {
                    System.out.println("Worker " + workerId + " 작업 시작");
                    Thread.sleep(1000 + workerId * 500);  // 작업 시뮬레이션
                    System.out.println("Worker " + workerId + " 작업 완료");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();  // 완료 신호
                    System.out.println("남은 작업: " + latch.getCount());
                }
            }).start();
        }

        System.out.println("모든 워커 대기 중... (최대 10초)");
        boolean finished = latch.await(10, TimeUnit.SECONDS);

        if (finished) {
            System.out.println("✓ 모든 워커 완료!");
        } else {
            System.err.println("✗ 타임아웃 - 남은 작업: " + latch.getCount());
        }
    }
}
```

**출력 예시:**
```
모든 워커 대기 중... (최대 10초)
Worker 0 작업 시작
Worker 1 작업 시작
Worker 2 작업 시작
Worker 3 작업 시작
Worker 4 작업 시작
Worker 0 작업 완료
남은 작업: 4
Worker 1 작업 완료
남은 작업: 3
Worker 2 작업 완료
남은 작업: 2
Worker 3 작업 완료
남은 작업: 1
Worker 4 작업 완료
남은 작업: 0
✓ 모든 워커 완료!
```

### 주요 활용 패턴

#### 패턴 1: Fan-out/Fan-in (병렬 작업 완료 대기)

**시나리오**: 여러 워커 스레드가 병렬로 작업 수행 후 모든 결과를 취합

```java
public class ParallelDataProcessor {
    public List<Result> processInParallel(List<Data> dataList) throws InterruptedException {
        int threadCount = dataList.size();
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Result> results = new CopyOnWriteArrayList<>();  // thread-safe list

        // Fan-out: 각 데이터를 별도 스레드에서 처리
        for (Data data : dataList) {
            executor.submit(() -> {
                try {
                    Result result = processData(data);
                    results.add(result);
                } finally {
                    latch.countDown();  // 작업 완료 신호
                }
            });
        }

        // Fan-in: 모든 작업 완료 대기
        latch.await(30, TimeUnit.SECONDS);
        return results;
    }
}
```

**실제 사용 예시 - 병렬 파일 다운로드:**
```java
public class FileDownloader {
    public void downloadFiles(List<String> urls) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(urls.size());

        for (String url : urls) {
            executor.submit(() -> {
                try {
                    downloadFile(url);
                    System.out.println("Downloaded: " + url);
                } catch (IOException e) {
                    System.err.println("Failed: " + url);
                } finally {
                    latch.countDown();
                }
            });
        }

        if (!latch.await(5, TimeUnit.MINUTES)) {
            System.err.println("일부 다운로드 실패");
        }
    }
}
```

---

#### 패턴 2: Starting Gun (동시 시작 신호)

**시나리오**: 여러 스레드를 동시에 시작시켜 동시성 테스트 수행

```java
public class ConcurrencyTest {
    @Test
    public void testConcurrentAccess() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch startSignal = new CountDownLatch(1);  // 시작 신호
        CountDownLatch doneSignal = new CountDownLatch(threadCount);  // 완료 신호

        AtomicInteger counter = new AtomicInteger(0);

        // 100개 스레드 준비
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startSignal.await();  // 시작 신호 대기
                    // 모든 스레드가 동시에 실행됨
                    counter.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneSignal.countDown();
                }
            }).start();
        }

        Thread.sleep(100);  // 모든 스레드가 대기 상태 진입 확인
        System.out.println("모든 스레드 준비 완료");

        startSignal.countDown();  // 🏁 Starting Gun! - 모든 스레드 동시 실행
        doneSignal.await();  // 모든 스레드 완료 대기

        assertEquals(threadCount, counter.get());
    }
}
```

**Starting Gun 패턴의 핵심:**
- `CountDownLatch(1)`: 단 1회 신호로 모든 대기 스레드 동시 해제
- 동시성 이슈를 재현하거나 테스트할 때 필수적

---

#### 패턴 3: 서비스 초기화 대기

**시나리오**: 애플리케이션 시작 시 여러 서비스가 모두 초기화될 때까지 대기

```java
@Component
public class ApplicationInitializer {
    private final CountDownLatch initLatch = new CountDownLatch(3);

    @PostConstruct
    public void initialize() throws InterruptedException {
        // 비동기로 각 서비스 초기화
        executor.submit(() -> {
            initializeDatabase();
            initLatch.countDown();
        });

        executor.submit(() -> {
            initializeCache();
            initLatch.countDown();
        });

        executor.submit(() -> {
            initializeMessageQueue();
            initLatch.countDown();
        });

        // 모든 서비스 초기화 완료 대기
        boolean initialized = initLatch.await(60, TimeUnit.SECONDS);
        if (!initialized) {
            throw new IllegalStateException("서비스 초기화 실패");
        }

        System.out.println("✓ 모든 서비스 초기화 완료");
    }
}
```

**Spring Boot 실전 예시:**
```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) throws InterruptedException {
        ConfigurableApplicationContext context =
            SpringApplication.run(Application.class, args);

        // 의존성이 있는 서비스들을 병렬 초기화
        CountDownLatch latch = new CountDownLatch(2);

        // Redis 연결
        new Thread(() -> {
            context.getBean(RedisService.class).connect();
            latch.countDown();
        }).start();

        // Elasticsearch 인덱스 준비
        new Thread(() -> {
            context.getBean(ElasticsearchService.class).createIndexes();
            latch.countDown();
        }).start();

        if (!latch.await(30, TimeUnit.SECONDS)) {
            System.err.println("외부 서비스 연결 실패");
            System.exit(1);
        }

        System.out.println("🚀 애플리케이션 준비 완료");
    }
}
```

---

#### 패턴 4: 배치 처리 구간 나누기

**시나리오**: 대용량 데이터를 여러 배치로 나누어 병렬 처리

```java
public class BatchProcessor {
    private static final int BATCH_SIZE = 1000;

    public void processBigData(List<Record> allRecords) throws InterruptedException {
        // 전체 데이터를 배치로 분할
        List<List<Record>> batches = partition(allRecords, BATCH_SIZE);
        CountDownLatch latch = new CountDownLatch(batches.size());

        for (List<Record> batch : batches) {
            executor.submit(() -> {
                try {
                    processBatch(batch);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();  // 모든 배치 처리 완료 대기
        System.out.println("전체 " + allRecords.size() + "건 처리 완료");
    }

    private List<List<Record>> partition(List<Record> list, int size) {
        List<List<Record>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            batches.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return batches;
    }
}
```

---

#### 패턴 5: Timeout을 활용한 장애 대응

**시나리오**: 외부 API 호출 시 일부 실패해도 전체 프로세스는 계속 진행

```java
public class ResilientApiCaller {
    public Map<String, Response> callApisWithTimeout(List<String> apiUrls) {
        CountDownLatch latch = new CountDownLatch(apiUrls.size());
        Map<String, Response> results = new ConcurrentHashMap<>();

        for (String url : apiUrls) {
            executor.submit(() -> {
                try {
                    Response response = callApi(url);
                    results.put(url, response);
                } catch (Exception e) {
                    System.err.println("API 호출 실패: " + url);
                    results.put(url, null);  // 실패한 경우 null 저장
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            // 최대 3초 대기 - 일부 API가 느려도 전체 프로세스 차단 안 됨
            boolean allCompleted = latch.await(3, TimeUnit.SECONDS);
            if (!allCompleted) {
                System.err.println("일부 API 응답 지연 - 계속 진행");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return results;  // 완료된 것만 반환 (부분 결과)
    }
}
```

---

#### 패턴 활용 선택 가이드

| 패턴 | 사용 시나리오 | CountDownLatch 개수 |
|------|-------------|-------------------|
| **Fan-out/Fan-in** | 병렬 작업 후 결과 취합 | 1개 (워커 개수만큼 카운트) |
| **Starting Gun** | 동시 시작 보장 | 2개 (시작 신호 + 완료 신호) |
| **서비스 초기화** | 여러 서비스 준비 완료 대기 | 1개 (서비스 개수만큼 카운트) |
| **배치 처리** | 대용량 데이터 병렬 처리 | 1개 (배치 개수만큼 카운트) |
| **Timeout 대응** | 부분 실패 허용 | 1개 + await(timeout) |

### CountDownLatch vs CyclicBarrier

두 클래스 모두 스레드 동기화 도구지만, **목적과 동작 방식**이 다르다.

#### 핵심 차이점

| 특성 | CountDownLatch | CyclicBarrier |
|------|----------------|---------------|
| **재사용성** | ❌ 일회용 (카운트 0 후 재설정 불가) | ✅ 재사용 가능 (자동 리셋) |
| **주체 분리** | ✅ countDown()과 await() 주체 다를 수 있음 | ❌ await()만 있음 (모두 동일) |
| **대기 방식** | 외부 이벤트 완료 대기 | 서로를 대기 (상호 대기) |
| **카운트 방향** | 감소 (N → 0) | 증가 후 리셋 (0 → N → 0) |
| **콜백** | ❌ 없음 | ✅ barrierAction 실행 가능 |
| **예외 처리** | 인터럽트만 | BrokenBarrierException 추가 |

#### 동작 방식 비교

**CountDownLatch:**
```java
// 메인 스레드가 워커들의 완료를 기다림
CountDownLatch latch = new CountDownLatch(3);

// Worker threads
new Thread(() -> {
    doWork();
    latch.countDown();  // 완료 신호만 보냄
}).start();

// Main thread
latch.await();  // 워커들의 완료 대기 (워커는 대기 안 함)
```

**CyclicBarrier:**
```java
// 모든 스레드가 서로를 기다림
CyclicBarrier barrier = new CyclicBarrier(3, () -> {
    System.out.println("모든 스레드가 도착!");
});

// Worker threads
new Thread(() -> {
    doWork();
    barrier.await();  // 다른 스레드들도 기다림 (상호 대기)
    continueWork();   // 모두 도착하면 함께 진행
}).start();
```

#### 사용 시나리오별 선택

**CountDownLatch 사용:**
```java
// ✅ 메인 스레드가 워커들 완료 대기
public void downloadFiles(List<String> urls) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(urls.size());

    for (String url : urls) {
        executor.submit(() -> {
            download(url);
            latch.countDown();  // 완료 신호
            // 다운로드 완료 후 각자 종료 (다른 스레드 안 기다림)
        });
    }

    latch.await();  // 메인 스레드만 대기
    System.out.println("모든 다운로드 완료");
}
```

**CyclicBarrier 사용:**
```java
// ✅ 모든 워커가 동기화 포인트에서 서로 대기
public void runSimulation(int playerCount) {
    CyclicBarrier barrier = new CyclicBarrier(playerCount, () -> {
        System.out.println("라운드 종료 - 점수 집계");
    });

    for (int i = 0; i < playerCount; i++) {
        new Thread(() -> {
            while (gameRunning) {
                playRound();
                barrier.await();  // 다른 플레이어들 대기 (동기화)
                // 모두 라운드 완료하면 함께 다음 라운드로
            }
        }).start();
    }
}
```

#### 재사용성 비교

**CountDownLatch - 일회성:**
```java
CountDownLatch latch = new CountDownLatch(3);

// 첫 번째 사용
latch.countDown();
latch.countDown();
latch.countDown();
latch.await();  // 통과

// 재사용 불가 ❌
latch.await();  // 즉시 통과 (이미 0)
// 카운트를 다시 3으로 설정할 방법 없음

// 재사용하려면 새 인스턴스 생성 필요
latch = new CountDownLatch(3);
```

**CyclicBarrier - 재사용 가능:**
```java
CyclicBarrier barrier = new CyclicBarrier(3);

// 첫 번째 사용
barrier.await();  // Thread 1
barrier.await();  // Thread 2
barrier.await();  // Thread 3 → 모두 통과, 자동 리셋

// 자동으로 재사용 가능 ✅
barrier.await();  // Thread 1 (다시 대기)
barrier.await();  // Thread 2
barrier.await();  // Thread 3 → 다시 통과

// 명시적 리셋도 가능
barrier.reset();
```

#### 실전 예시 - 같은 문제, 다른 접근

**문제**: 5개 파일을 병렬 다운로드 후 압축

**CountDownLatch 방식 (권장):**
```java
public void downloadAndCompress(List<String> files) throws InterruptedException {
    CountDownLatch downloadLatch = new CountDownLatch(files.size());
    List<File> downloadedFiles = new CopyOnWriteArrayList<>();

    // 각 파일 다운로드 (병렬)
    for (String file : files) {
        executor.submit(() -> {
            File downloaded = download(file);
            downloadedFiles.add(downloaded);
            downloadLatch.countDown();
        });
    }

    // 메인 스레드가 모든 다운로드 완료 대기
    downloadLatch.await();

    // 다운로드 완료 후 압축 (단일 스레드)
    compress(downloadedFiles);
}
```

**CyclicBarrier 방식 (부적합):**
```java
// ❌ 이 경우 CyclicBarrier는 적합하지 않음
public void downloadAndCompress(List<String> files) {
    CyclicBarrier barrier = new CyclicBarrier(files.size() + 1);  // +1은 메인 스레드

    for (String file : files) {
        executor.submit(() -> {
            download(file);
            barrier.await();  // 다른 다운로드 대기 (불필요)
        });
    }

    barrier.await();  // 메인 스레드도 대기
    compress(...);    // 압축은 어디서 실행? 파일 참조는?
}
// 문제점: 워커들이 서로를 기다릴 필요 없음, 메인만 기다리면 됨
```

#### 복합 사용 예시 - 반복적인 병렬 작업

```java
public class IterativeParallelProcessor {
    private final CyclicBarrier barrier;
    private final int threadCount = 4;

    public IterativeParallelProcessor() {
        this.barrier = new CyclicBarrier(threadCount, () -> {
            System.out.println("Iteration 완료 - 다음 단계로");
        });
    }

    public void processIterations(int iterations) {
        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                for (int iter = 0; iter < iterations; iter++) {
                    processChunk(threadId, iter);
                    try {
                        barrier.await();  // 모든 스레드가 iteration 완료 대기
                    } catch (Exception e) {
                        break;
                    }
                }
            }).start();
        }
    }
}
```

#### 선택 가이드

**CountDownLatch를 선택하라:**
- ✅ 메인 스레드가 워커들의 완료를 기다리는 경우
- ✅ 워커들이 서로를 기다릴 필요 없는 경우
- ✅ 일회성 이벤트 (서비스 초기화, 파일 다운로드 등)
- ✅ countDown()과 await() 호출 주체가 다른 경우

**CyclicBarrier를 선택하라:**
- ✅ 모든 스레드가 동기화 포인트에서 서로를 기다리는 경우
- ✅ 반복적인 동기화가 필요한 경우 (게임 라운드, 시뮬레이션)
- ✅ 모든 스레드가 완료 후 콜백 실행이 필요한 경우
- ✅ 재사용이 필요한 경우

### CountDownLatch vs Semaphore vs CompletableFuture

세 가지 동시성 도구는 **목적과 사용 패턴**이 완전히 다르다.

#### 전체 비교표

| 특성 | CountDownLatch | Semaphore | CompletableFuture |
|------|----------------|-----------|-------------------|
| **주요 목적** | 작업 완료 대기 | 동시 접근 제어 | 비동기 파이프라인 |
| **카운트** | 감소만 (N→0) | 증가/감소 자유 | 없음 (상태 기반) |
| **재사용** | ❌ 일회성 | ✅ 무한 재사용 | ❌ 일회성 |
| **블로킹** | await() 블로킹 | acquire() 블로킹 | non-blocking |
| **결과 반환** | ❌ 없음 | ❌ 없음 | ✅ 결과값 반환 |
| **체이닝** | ❌ 불가 | ❌ 불가 | ✅ 가능 (fluent API) |
| **에러 처리** | try-catch | try-catch | exceptionally() |

---

#### CountDownLatch - 완료 대기

**목적**: N개의 작업이 모두 완료될 때까지 대기

```java
// 5개 파일 다운로드 완료 대기
CountDownLatch latch = new CountDownLatch(5);

for (String url : urls) {
    executor.submit(() -> {
        downloadFile(url);
        latch.countDown();  // 완료 신호
    });
}

latch.await();  // 5개 모두 완료될 때까지 블로킹
System.out.println("모든 다운로드 완료");
```

**특징:**
- 카운트가 0이 되는 **이벤트를 기다림**
- 일회성: 한 번 0이 되면 재사용 불가
- 결과값 없음 (완료 여부만 확인)

---

#### Semaphore - 동시 접근 제어

**목적**: 동시에 N개까지만 리소스 접근 허용 (허가증 관리)

```java
// 동시에 최대 3개 커넥션만 허용
Semaphore semaphore = new Semaphore(3);

for (int i = 0; i < 10; i++) {
    executor.submit(() -> {
        try {
            semaphore.acquire();  // 허가증 획득 (대기 가능)
            useDatabase();        // 최대 3개 스레드만 동시 실행
        } finally {
            semaphore.release();  // 허가증 반환
        }
    });
}
```

**특징:**
- **동시 실행 개수 제한** (rate limiting)
- 무한 재사용 가능: acquire/release 반복
- 카운트 증가/감소 자유로움

**Semaphore vs CountDownLatch 비교:**
```java
// Semaphore: 동시 실행 제어
Semaphore semaphore = new Semaphore(3);
semaphore.acquire();  // 허가증 1개 획득 (3 → 2)
semaphore.release();  // 허가증 1개 반환 (2 → 3) ✅ 증가 가능!

// CountDownLatch: 완료 신호
CountDownLatch latch = new CountDownLatch(3);
latch.countDown();    // 카운트 감소 (3 → 2)
// latch.countUp();   // ❌ 이런 메서드 없음! (감소만 가능)
```

---

#### CompletableFuture - 비동기 파이프라인

**목적**: 비동기 작업을 체이닝하고 결과값 반환

```java
// 비동기 파이프라인: 다운로드 → 파싱 → 저장
CompletableFuture<Report> future = CompletableFuture
    .supplyAsync(() -> downloadFile(url))     // 1. 다운로드 (비동기)
    .thenApply(content -> parseContent(content))  // 2. 파싱 (체이닝)
    .thenApply(data -> generateReport(data))      // 3. 리포트 생성
    .exceptionally(ex -> {                        // 에러 처리
        log.error("처리 실패", ex);
        return Report.empty();
    });

Report report = future.get();  // 최종 결과 대기
```

**특징:**
- **결과값 반환** (Latch와 가장 큰 차이)
- 체이닝 가능: `thenApply`, `thenCompose`, `thenCombine` 등
- Non-blocking: `thenAccept()` 등으로 콜백 등록 가능

**CompletableFuture vs CountDownLatch:**
```java
// CountDownLatch: 완료만 확인 (결과 없음)
CountDownLatch latch = new CountDownLatch(1);
executor.submit(() -> {
    String result = processData();  // 결과값을 어디에 저장? 🤔
    latch.countDown();
});
latch.await();
// result는 어떻게 가져올까? → 별도 변수 필요

// CompletableFuture: 완료 + 결과값 반환 ✅
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    return processData();  // 결과값 반환
});
String result = future.get();  // 완료 대기 + 결과 획득
```

---

#### 실전 시나리오별 선택 가이드

**시나리오 1: 병렬 다운로드 후 압축**

```java
// CountDownLatch 방식 (권장 ✅)
public void downloadAndCompress(List<String> urls) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(urls.size());
    List<File> files = new CopyOnWriteArrayList<>();

    for (String url : urls) {
        executor.submit(() -> {
            files.add(download(url));
            latch.countDown();
        });
    }

    latch.await();
    compress(files);  // 모든 다운로드 후 압축
}

// CompletableFuture 방식 (대안)
public CompletableFuture<File> downloadAndCompress(List<String> urls) {
    CompletableFuture<?>[] futures = urls.stream()
        .map(url -> CompletableFuture.supplyAsync(() -> download(url)))
        .toArray(CompletableFuture[]::new);

    return CompletableFuture.allOf(futures)
        .thenApply(v -> futures)
        .thenApply(this::compress);
}
```

**시나리오 2: 데이터베이스 커넥션 풀**

```java
// Semaphore 사용 (권장 ✅)
public class ConnectionPool {
    private final Semaphore semaphore = new Semaphore(10);  // 최대 10개

    public Connection getConnection() throws InterruptedException {
        semaphore.acquire();  // 커넥션 획득 (대기 가능)
        return createConnection();
    }

    public void releaseConnection(Connection conn) {
        closeConnection(conn);
        semaphore.release();  // 커넥션 반환
    }
}

// CountDownLatch로는 불가능 ❌
// - 카운트를 증가시킬 수 없어서 release() 구현 불가
```

**시나리오 3: API 호출 → 변환 → 저장 (파이프라인)**

```java
// CompletableFuture 사용 (권장 ✅)
public CompletableFuture<Void> processUser(Long userId) {
    return CompletableFuture
        .supplyAsync(() -> userApi.fetchUser(userId))      // API 호출
        .thenApply(userDto -> userMapper.toEntity(userDto)) // DTO → Entity
        .thenCompose(user -> saveAsync(user))               // 비동기 저장
        .thenAccept(saved -> publishEvent(saved))           // 이벤트 발행
        .exceptionally(ex -> {
            log.error("사용자 처리 실패: " + userId, ex);
            return null;
        });
}

// CountDownLatch로는 체이닝 불가 ❌
```

**시나리오 4: 동시성 테스트 (N개 스레드 동시 실행)**

```java
// CountDownLatch 사용 (권장 ✅)
@Test
public void testConcurrency() throws InterruptedException {
    int threadCount = 100;
    CountDownLatch startSignal = new CountDownLatch(1);   // Starting Gun
    CountDownLatch doneSignal = new CountDownLatch(threadCount);

    AtomicInteger counter = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
        new Thread(() -> {
            try {
                startSignal.await();  // 시작 신호 대기
                counter.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneSignal.countDown();
            }
        }).start();
    }

    startSignal.countDown();  // 🏁 모든 스레드 동시 시작
    doneSignal.await();
    assertEquals(threadCount, counter.get());
}

// Semaphore나 CompletableFuture로는 "동시 시작"을 보장할 수 없음 ❌
```

---

#### 종합 선택 기준

| 요구사항 | 선택 | 이유 |
|---------|------|------|
| N개 작업 완료 대기 | **CountDownLatch** | 완료 신호 대기에 최적화 |
| 동시 실행 개수 제한 | **Semaphore** | 허가증 관리로 rate limiting |
| 결과값이 필요함 | **CompletableFuture** | 유일하게 결과 반환 가능 |
| 작업 체이닝 필요 | **CompletableFuture** | fluent API로 파이프라인 구성 |
| 동시 시작 보장 | **CountDownLatch** | Starting Gun 패턴 |
| 반복적인 acquire/release | **Semaphore** | 무한 재사용 가능 |
| Non-blocking 처리 | **CompletableFuture** | 콜백 기반 처리 |

**복합 사용 예시:**
```java
public class ComplexProcessor {
    private final Semaphore rateLimiter = new Semaphore(10);  // 동시 실행 제한

    public CompletableFuture<List<Result>> processAll(List<Task> tasks) {
        CountDownLatch latch = new CountDownLatch(tasks.size());
        List<CompletableFuture<Result>> futures = new ArrayList<>();

        for (Task task : tasks) {
            CompletableFuture<Result> future = CompletableFuture.supplyAsync(() -> {
                try {
                    rateLimiter.acquire();  // Semaphore: 동시 실행 제어
                    return process(task);
                } finally {
                    rateLimiter.release();
                    latch.countDown();      // CountDownLatch: 진행 상황 추적
                }
            });
            futures.add(future);
        }

        // CompletableFuture: 모든 결과 취합
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList()));
    }
}
```

### 주의사항과 안티패턴

#### 안티패턴 1: 카운트 불일치로 인한 무한 대기

**문제:**
```java
// ❌ 위험한 코드
CountDownLatch latch = new CountDownLatch(5);

for (int i = 0; i < 5; i++) {
    executor.submit(() -> {
        if (processData()) {  // 실패하면 countDown() 미호출!
            latch.countDown();
        }
    });
}

latch.await();  // 하나라도 실패하면 영원히 대기 💀
```

**해결책:**
```java
// ✅ finally 블록에서 항상 countDown() 호출
CountDownLatch latch = new CountDownLatch(5);

for (int i = 0; i < 5; i++) {
    executor.submit(() -> {
        try {
            processData();  // 성공/실패 무관
        } catch (Exception e) {
            log.error("처리 실패", e);
        } finally {
            latch.countDown();  // 반드시 실행 보장
        }
    });
}

latch.await();
```

---

#### 안티패턴 2: 타임아웃 없는 await()

**문제:**
```java
// ❌ 무한정 대기 - 프로덕션 환경에서 매우 위험
latch.await();

// 만약 다음 상황이 발생하면?
// - 워커 스레드가 예외로 죽음
// - 네트워크 장애로 외부 API 응답 없음
// - 데드락 발생
// → 애플리케이션 전체가 멈춤 (hang)
```

**해결책:**
```java
// ✅ 항상 타임아웃 지정
boolean completed = latch.await(30, TimeUnit.SECONDS);
if (!completed) {
    log.error("작업 타임아웃 - 남은 작업: " + latch.getCount());
    // 장애 대응 로직 (알림, 재시도, fallback 등)
    throw new TimeoutException("작업이 30초 내에 완료되지 않음");
}
```

---

#### 안티패턴 3: 재사용 시도

**문제:**
```java
// ❌ CountDownLatch는 재사용 불가
CountDownLatch latch = new CountDownLatch(3);

// 첫 번째 라운드
latch.countDown();
latch.countDown();
latch.countDown();
latch.await();  // 통과

// 두 번째 라운드 시도
latch.countDown();  // 동작은 하지만 의미 없음 (이미 0)
latch.await();      // 즉시 통과 (대기 안 함) ❌
```

**해결책 1: 매번 새 인스턴스 생성**
```java
// ✅ 반복 작업마다 새 Latch 생성
for (int round = 0; round < 10; round++) {
    CountDownLatch latch = new CountDownLatch(workerCount);  // 매번 새로 생성

    for (int i = 0; i < workerCount; i++) {
        executor.submit(() -> {
            doWork(round);
            latch.countDown();
        });
    }

    latch.await();
}
```

**해결책 2: CyclicBarrier 사용**
```java
// ✅ 재사용이 필요하면 CyclicBarrier 사용
CyclicBarrier barrier = new CyclicBarrier(workerCount);

for (int round = 0; round < 10; round++) {
    for (int i = 0; i < workerCount; i++) {
        executor.submit(() -> {
            doWork(round);
            barrier.await();  // 자동으로 재사용됨
        });
    }
}
```

---

#### 안티패턴 4: getCount()로 조건 분기

**문제:**
```java
// ❌ Race condition 발생 가능
if (latch.getCount() > 0) {
    // 여기서 다른 스레드가 countDown()을 호출하면?
    latch.await();  // 이미 0일 수 있음 (불필요한 분기)
}

// ❌ getCount()로 완료 여부 확인
while (latch.getCount() > 0) {
    // Busy waiting - CPU 낭비
    Thread.sleep(100);
}
```

**해결책:**
```java
// ✅ await()만 사용 (조건 분기 불필요)
latch.await();  // 이미 0이면 즉시 반환됨

// ✅ 타임아웃 0으로 즉시 확인
boolean isZero = latch.await(0, TimeUnit.SECONDS);
if (isZero) {
    System.out.println("이미 완료됨");
}
```

---

#### 안티패턴 5: countDown() 호출 누락

**문제:**
```java
// ❌ 조건부 countDown() - 위험!
executor.submit(() -> {
    Data data = fetchData();
    if (data != null) {  // null이면 countDown() 미호출!
        process(data);
        latch.countDown();
    }
});
```

**해결책:**
```java
// ✅ 항상 호출 보장
executor.submit(() -> {
    try {
        Data data = fetchData();
        if (data != null) {
            process(data);
        }
    } finally {
        latch.countDown();  // 무조건 호출
    }
});
```

---

#### 안티패턴 6: 예외 발생 시 대기 스레드 방치

**문제:**
```java
// ❌ 워커 스레드에서 예외 발생 시 대기 중인 메인 스레드 영원히 블로킹
CountDownLatch latch = new CountDownLatch(3);

executor.submit(() -> {
    throw new RuntimeException("치명적 오류!");  // countDown() 미호출
});

latch.await();  // 영원히 대기 💀
```

**해결책 1: finally + 타임아웃**
```java
// ✅ finally와 타임아웃 조합
CountDownLatch latch = new CountDownLatch(3);
AtomicInteger errorCount = new AtomicInteger(0);

for (int i = 0; i < 3; i++) {
    executor.submit(() -> {
        try {
            riskyOperation();
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("작업 실패", e);
        } finally {
            latch.countDown();  // 예외 발생해도 카운트 감소
        }
    });
}

boolean completed = latch.await(10, TimeUnit.SECONDS);
if (!completed || errorCount.get() > 0) {
    throw new RuntimeException("작업 실패: " + errorCount.get() + "건");
}
```

**해결책 2: CompletableFuture 사용**
```java
// ✅ CompletableFuture는 예외를 자동으로 전파
List<CompletableFuture<Void>> futures = IntStream.range(0, 3)
    .mapToObj(i -> CompletableFuture.runAsync(() -> riskyOperation()))
    .collect(Collectors.toList());

try {
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .get(10, TimeUnit.SECONDS);
} catch (ExecutionException e) {
    log.error("작업 중 예외 발생", e.getCause());
}
```

---

#### 안티패턴 7: 너무 많은 스레드가 await() 호출

**문제:**
```java
// ❌ 1000개 스레드가 모두 대기 - 리소스 낭비
CountDownLatch latch = new CountDownLatch(1000);

for (int i = 0; i < 1000; i++) {
    new Thread(() -> {
        doWork();
        latch.countDown();
        latch.await();  // 모든 스레드가 대기 - 불필요!
    }).start();
}
```

**해결책:**
```java
// ✅ 메인 스레드만 대기
CountDownLatch latch = new CountDownLatch(1000);

for (int i = 0; i < 1000; i++) {
    executor.submit(() -> {  // Thread pool 사용
        doWork();
        latch.countDown();
        // await() 호출 안 함 - 작업 완료 후 종료
    });
}

latch.await();  // 메인 스레드만 대기
System.out.println("모든 작업 완료");
```

---

#### 베스트 프랙티스 체크리스트

```java
public class CountDownLatchBestPractice {
    public void processData(List<Data> dataList) throws InterruptedException, TimeoutException {
        CountDownLatch latch = new CountDownLatch(dataList.size());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (Data data : dataList) {
            executor.submit(() -> {
                try {
                    // ✅ 1. 비즈니스 로직
                    process(data);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    // ✅ 2. 예외 처리 및 로깅
                    errorCount.incrementAndGet();
                    log.error("데이터 처리 실패: " + data.getId(), e);

                } finally {
                    // ✅ 3. 무조건 countDown() (finally 블록)
                    latch.countDown();
                }
            });
        }

        // ✅ 4. 타임아웃 지정 대기
        boolean completed = latch.await(30, TimeUnit.SECONDS);

        // ✅ 5. 완료 여부 검증
        if (!completed) {
            long remaining = latch.getCount();
            throw new TimeoutException(
                String.format("타임아웃: %d/%d 완료, %d 대기 중",
                    successCount.get(), dataList.size(), remaining)
            );
        }

        // ✅ 6. 에러 카운트 확인
        if (errorCount.get() > 0) {
            log.warn("일부 작업 실패: " + errorCount.get() + "건");
        }

        log.info("전체 처리 완료: 성공 {}, 실패 {}",
            successCount.get(), errorCount.get());
    }
}
```

**체크리스트:**
- [ ] `finally` 블록에서 `countDown()` 호출
- [ ] `await()`에 타임아웃 지정
- [ ] 타임아웃 발생 시 에러 처리 로직
- [ ] 예외 발생 시에도 카운트 감소 보장
- [ ] 성공/실패 카운트 별도 관리
- [ ] 로깅 및 모니터링 추가
- [ ] Thread Pool 사용 (무분별한 Thread 생성 방지)
- [ ] 재사용이 필요하면 CyclicBarrier 고려

## 핵심 정리
- CountDownLatch는 특정 개수의 이벤트가 완료될 때까지 스레드를 대기시키는 동기화 도구이다
- 일회성 사용으로, 카운트가 0이 되면 재설정할 수 없다
- 동시성 테스트에서 N개의 스레드를 동시에 시작시키는 Starting Gun 패턴으로 많이 활용된다
- countDown()과 await()이 분리되어 있어 호출 주체가 다를 수 있다는 점이 CyclicBarrier와의 핵심 차이점이다

## 키워드
`CountDownLatch`, `java.util.concurrent`, `await`, `countDown`, `CyclicBarrier`, `Semaphore`, `동시성 테스트`, `Starting Gun 패턴`, `Fan-out Fan-in`, `Thread Synchronization`

## 참고 자료
-
