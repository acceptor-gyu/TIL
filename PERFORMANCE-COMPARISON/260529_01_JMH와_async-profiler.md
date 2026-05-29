# JMH와 async-profiler

## 개요
- JVM 기반 애플리케이션 성능 측정 도구 소개
- 마이크로 벤치마킹과 프로파일링의 차이
- JMH와 async-profiler의 등장 배경과 역할

## 상세 내용

### 1. 성능 측정의 두 가지 관점

성능 문제를 해결할 때 우리는 두 가지 다른 질문에 답해야 한다.

- **"얼마나 빠른가?"** — 특정 코드 블록의 처리량이나 응답 시간을 정확히 수치화하고 싶을 때
- **"어디에서 느린가?"** — 전체 애플리케이션 중 병목 지점을 찾고 싶을 때

JMH는 첫 번째 질문에, async-profiler는 두 번째 질문에 답한다.

단순히 `System.currentTimeMillis()`로 메서드 실행 시간을 재는 방법은 JIT 컴파일러의 최적화(Dead Code Elimination, Constant Folding, Inlining)로 인해 실제와 다른 결과가 나올 수 있다. 이 문제를 해결하기 위해 JMH가 등장했다.

---

### 2. JMH (Java Microbenchmark Harness)

JMH는 OpenJDK 팀이 직접 개발한 마이크로 벤치마킹 도구다. JVM이 수행하는 다양한 최적화로 인한 측정 왜곡을 피하도록 설계되어 있다.

#### Maven 의존성 추가

```xml
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version>
</dependency>
```

#### 핵심 어노테이션

**@Benchmark**

벤치마크로 측정할 메서드에 붙인다.

```java
@Benchmark
public int measureSum() {
    return 1 + 2;  // 주의: Constant Folding 대상
}
```

**@State**

벤치마크에 필요한 초기화 상태 객체를 선언한다. `Scope`로 공유 범위를 지정한다.

| Scope | 설명 |
|-------|------|
| `Scope.Thread` | 각 스레드가 독립적인 인스턴스를 가짐 |
| `Scope.Benchmark` | 모든 스레드가 인스턴스를 공유 |
| `Scope.Group` | 같은 그룹 내 스레드끼리 공유 |

```java
@State(Scope.Thread)
public static class MyState {
    public int a = 1;
    public int b = 2;
}

@Benchmark
public int measureSum(MyState state) {
    return state.a + state.b;  // 상태에서 읽으므로 Constant Folding 방지
}
```

**@BenchmarkMode**

측정 지표를 결정한다.

| 모드 | 설명 | 적합한 상황 |
|------|------|------------|
| `Throughput` | 초당 처리 연산 수 | 처리량이 중요한 경우 |
| `AverageTime` | 연산당 평균 시간 | 평균 응답시간 비교 |
| `SampleTime` | 시간 분포(min/max/백분위) | 레이턴시 분포 분석 |
| `SingleShotTime` | 단 한 번 실행 시간 | Cold Start 측정 |
| `All` | 모든 모드 동시 측정 | 종합적 분석 |

**@Warmup / @Measurement**

```java
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
```

- `@Warmup`: JIT 컴파일러가 코드를 최적화할 시간을 준다. Warmup 중 수집된 데이터는 결과에 포함되지 않는다.
- `@Measurement`: 실제 성능 데이터를 수집하는 반복 횟수와 시간을 설정한다.

**@Fork**

```java
@Fork(value = 3)
```

독립적인 JVM 프로세스를 `value`만큼 생성해 벤치마크를 실행한다. 각 Fork는 완전히 새로운 JVM에서 시작하므로 이전 실행의 JIT 컴파일 상태가 영향을 주지 않는다. `value = 1` 이상을 권장한다.

#### Dead Code Elimination과 Blackhole

JVM은 결과를 사용하지 않는 연산을 제거(Dead Code Elimination)할 수 있다. 이를 막기 위해 두 가지 방법을 사용한다.

```java
// 방법 1: 반환값으로 소비
@Benchmark
public int measureWithReturn(MyState state) {
    return state.a + state.b;
}

// 방법 2: Blackhole로 소비 (여러 결과가 있을 때)
@Benchmark
public void measureWithBlackhole(MyState state, Blackhole bh) {
    bh.consume(state.a + state.b);
    bh.consume(state.a * state.b);
}
```

`Blackhole`은 JIT 컴파일러가 결과를 사용한다고 인식하게 만들어 최적화를 방지한다.

#### Constant Folding 방지

```java
// 나쁜 예: 컴파일 타임에 최적화되어 항상 3을 반환
@Benchmark
public int badBenchmark() {
    return 1 + 2;
}

// 좋은 예: @State에서 값을 읽어 런타임에 계산
@State(Scope.Thread)
public static class MyState {
    public int a = 1;
    public int b = 2;
}

@Benchmark
public int goodBenchmark(MyState state) {
    return state.a + state.b;
}
```

#### 실행 결과 예시

```
Benchmark              Mode  Cnt    Score    Error   Units
MyBenchmark.testList   avgt   10   25.432 ±  0.341  ns/op
MyBenchmark.testArray  avgt   10    4.201 ±  0.051  ns/op
```

- `Score`: 평균 실행 시간
- `Error`: 95% 신뢰구간 오차 범위
- `Units`: 측정 단위 (AverageTime이면 ns/op)

---

### 3. async-profiler

async-profiler는 HotSpot JVM 전용 저오버헤드 샘플링 프로파일러다. 기존 프로파일러의 가장 큰 문제인 Safepoint Bias를 해결한다.

#### Safepoint Bias 문제

전통적인 JVM 프로파일러는 스택 추적을 Safepoint에서만 캡처한다. Safepoint는 GC나 코드 최적화 해제를 위해 JVM이 모든 스레드를 일시 정지시키는 지점이다.

문제는 타이트한 루프(tight loop)처럼 Safepoint에 도달하지 않고 빠르게 실행되는 코드는 프로파일링에서 누락된다는 것이다. 이로 인해 "빠른 코드가 느려 보이고, 느린 코드가 빨라 보이는" 왜곡된 결과를 얻게 된다.

#### AsyncGetCallTrace API

async-profiler는 UNIX 시그널과 HotSpot 내부 API인 `AsyncGetCallTrace`를 활용해 스레드를 임의의 시점에 인터럽트하고 스택 추적을 수집한다. Safepoint와 무관하게 동작하므로 편향 없는 정확한 프로파일링이 가능하다.

추가로 Linux의 `perf_events`와 연동해 Java 스택뿐만 아니라 네이티브 코드, 커널 코드 경로도 추적한다.

#### 설치 및 기본 사용법

```bash
# 최신 릴리즈 다운로드 (v4.4 기준)
wget https://github.com/async-profiler/async-profiler/releases/download/v4.4/async-profiler-4.4-linux-x64.tar.gz
tar -xzf async-profiler-4.4-linux-x64.tar.gz

# 30초 CPU 프로파일링 후 Flame Graph 생성
./asprof -d 30 -f flamegraph.html <PID>
```

#### 프로파일링 모드

| 모드 | 명령어 | 측정 대상 | 사용 시기 |
|------|--------|---------|---------|
| **CPU** | `-e cpu` | CPU를 실제로 사용 중인 시간 | CPU 사용률 높을 때, 응답 느릴 때 |
| **Allocation** | `-e alloc` | 객체 할당량(바이트 단위) | Young GC 빈번, GC 일시 정지 클 때 |
| **Lock** | `-e lock` | 락 대기 중인 스레드 | 스레드 BLOCKED/WAITING 많을 때 |
| **Wall-clock** | `-e wall` | 모든 스레드 상태(I/O 대기 포함) | 요청 전체 레이턴시 분석할 때 |

```bash
# CPU 프로파일링
./asprof -d 60 -e cpu -f cpu.html <PID>

# 메모리 할당 프로파일링
./asprof -d 60 -e alloc -f alloc.html <PID>

# 락 경합 프로파일링
./asprof -d 60 -e lock -f lock.html <PID>

# Wall-clock 프로파일링 (I/O 대기 포함)
./asprof -d 60 -e wall -f wall.html <PID>
```

#### Flame Graph 해석

Flame Graph는 수집된 스택 추적 샘플을 집계해 시각화한 것이다.

- **X축**: 시간의 흐름이 아닌 샘플 비율을 나타낸다. 프레임이 넓을수록 해당 메서드에서 더 많은 CPU 시간이 소비됨
- **Y축**: 호출 스택 깊이. 위로 갈수록 더 안쪽(내부) 메서드
- **상단의 넓은 프레임**: 실제 CPU 시간이 집중되는 핫스팟
- **색상 의미**:
  - 초록색: Java 코드
  - 주황색: 네이티브 코드 (JNI 등)
  - 노란색: 커널 코드
  - 빨간색: C++ (JVM 내부)

```
최적화 대상 찾기: 상단에 넓은 프레임을 찾아라
  ┌─────────────────────────────────────────────────────┐
  │       someHotMethod() — 전체의 45% 시간 소비          │  ← 핫스팟
  ├──────────────┬──────────────────────────────────────┤
  │ callerA() 30%│          callerB() 70%               │
  ├──────────────┴──────────────────────────────────────┤
  │                  main()                             │
  └─────────────────────────────────────────────────────┘
```

#### 낮은 오버헤드의 이유

- 샘플링 기반이라 매 메서드 호출마다 인터셉트하지 않음 (기본 샘플링 간격: 10ms)
- JVMTI를 사용하지 않아 VM 내부 오버헤드 없음
- 운영 환경에서도 대부분 1~3% 미만의 CPU 오버헤드

---

### 4. 두 도구의 비교

| 항목 | JMH | async-profiler |
|------|-----|----------------|
| 목적 | 마이크로 벤치마킹 | 프로파일링 |
| 측정 단위 | 특정 메서드/코드 블록 | 전체 애플리케이션 |
| 결과 형태 | 처리량/응답시간 수치 | 호출 스택, Flame Graph |
| 사용 시점 | 코드 최적화 검증 | 병목 지점 탐색 |
| 환경 | 테스트/개발 환경 | 개발 + 운영 환경 모두 가능 |
| 오버헤드 | 전용 JVM 프로세스 필요 | 1~3% 미만 |
| JIT 대응 | Warmup/Fork로 통제 | 실제 JIT 상태 그대로 측정 |

---

### 5. 실전 활용 시나리오

#### 시나리오 1: 컬렉션 선택 비교 (JMH)

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(1)
public class CollectionBenchmark {

    private List<Integer> arrayList;
    private LinkedList<Integer> linkedList;

    @Setup
    public void setup() {
        arrayList = new ArrayList<>(IntStream.range(0, 10000)
            .boxed().collect(Collectors.toList()));
        linkedList = new LinkedList<>(arrayList);
    }

    @Benchmark
    public int arrayListGet() {
        return arrayList.get(5000);
    }

    @Benchmark
    public int linkedListGet() {
        return linkedList.get(5000);
    }
}
```

#### 시나리오 2: 운영 환경 병목 분석 (async-profiler)

```bash
# 1단계: 서버 PID 확인
jps -l

# 2단계: 60초 Wall-clock 프로파일링 (I/O 포함)
./asprof -d 60 -e wall -f wall-profile.html 12345

# 3단계: CPU 프로파일링으로 핫스팟 확인
./asprof -d 60 -e cpu -f cpu-profile.html 12345

# 4단계: GC 빈번하면 Allocation 프로파일링
./asprof -d 60 -e alloc -f alloc-profile.html 12345
```

#### 시나리오 3: 두 도구를 조합한 최적화 워크플로우

```
1. async-profiler로 병목 메서드 발견
        ↓
2. 해당 메서드를 JMH 벤치마크 작성
        ↓
3. 최적화 후보 코드와 기존 코드를 JMH로 비교
        ↓
4. 성능 향상 확인 후 적용
        ↓
5. async-profiler로 전체 시스템 영향 재확인
```

---

## 핵심 정리
- JMH는 "정확한 측정"을 위한 마이크로 벤치마킹 도구이며 JIT 최적화 함정을 피하도록 설계됨
- async-profiler는 "어디서 시간을 쓰는지" 파악하는 저오버헤드 프로파일러
- 두 도구는 경쟁 관계가 아니라 상호 보완적
- 마이크로 벤치마킹의 결과는 실제 워크로드에서 다를 수 있음을 인지해야 함
- Flame Graph 해석 능력은 실전 성능 분석의 핵심 스킬

## 기술적 한계와 보완 전략
- JMH 한계: 마이크로 벤치마크 결과가 실서비스 성능을 대표하지 못할 수 있음 → 통합 부하 테스트 병행
- async-profiler 한계: 샘플링 방식이라 짧은 함수는 누락 가능 → 샘플링 간격 조정 또는 다른 프로파일러 병행
- JIT 컴파일 단계별 성능 편차 → 충분한 warmup과 다중 fork로 분산 측정
- 운영 환경 프로파일링 부담 → 짧은 시간 샘플링, on-demand 활성화 전략
- 측정값의 통계적 신뢰성 → 표준편차, 신뢰구간 함께 분석

## 키워드

### JMH
JVM 기반 마이크로 벤치마킹 도구. OpenJDK 팀이 개발했으며 JIT 최적화로 인한 측정 왜곡을 방지하는 다양한 메커니즘을 내장한다. Maven 아키타입으로 독립 프로젝트를 생성해 사용하는 방식을 권장한다.

### async-profiler
HotSpot JVM 전용 저오버헤드 샘플링 프로파일러. Safepoint Bias 없이 임의 시점에서 스택 추적을 수집하며 CPU, Allocation, Lock, Wall-clock 등 다양한 프로파일링 모드를 지원한다.

### Microbenchmark
개별 메서드나 작은 코드 블록의 성능을 독립적으로 측정하는 기법. JVM 최적화로 인해 단순한 방법으로는 정확한 측정이 어려우며 JMH가 이를 해결한다.

### Profiling
실행 중인 애플리케이션에서 메서드 호출 빈도, 실행 시간, 메모리 사용량 등을 수집해 병목 지점을 찾는 분석 기법.

### Flame Graph
프로파일링 샘플을 집계해 시각화한 그래프. X축은 샘플 비율, Y축은 호출 스택 깊이를 나타내며 상단의 넓은 프레임이 실제 시간이 소비되는 핫스팟이다.

### JIT Compiler
Just-In-Time 컴파일러. 자주 실행되는 코드를 런타임에 네이티브 코드로 컴파일해 성능을 향상시킨다. Dead Code Elimination, Constant Folding 등의 최적화를 수행하며 마이크로 벤치마킹 시 측정 왜곡의 주요 원인이다.

### Safepoint Bias
전통적인 JVM 프로파일러가 Safepoint에서만 스택 추적을 캡처하기 때문에 타이트한 루프 등 Safepoint에 도달하지 않는 코드가 프로파일링에서 누락되는 편향 현상.

### Warmup
JMH에서 실제 측정 전 JVM이 JIT 컴파일 등의 최적화를 완료하도록 코드를 미리 실행시키는 단계. Warmup 동안 수집된 데이터는 최종 결과에 포함되지 않는다.

### Throughput
단위 시간당 처리 가능한 연산 수. JMH의 측정 모드 중 하나로 ops/sec 단위로 표시된다.

### AsyncGetCallTrace
HotSpot JVM 내부 API. Safepoint와 무관하게 임의 시점에서 Java 스택 추적을 수집할 수 있게 해주며 async-profiler가 Safepoint Bias 문제를 해결하는 핵심 메커니즘이다.

## 참고 자료
- [JMH GitHub (OpenJDK)](https://github.com/openjdk/jmh)
- [async-profiler GitHub](https://github.com/async-profiler/async-profiler)
- [JMH Tutorial - jenkov.com](https://jenkov.com/tutorials/java-performance/jmh.html)
- [async-profiler Guide - Baeldung](https://www.baeldung.com/java-async-profiler)
- [async-profiler CPU/Allocation/Lock Profiling](https://cscode.io/java/jvm/async-profiler/)
- [Analyzing Java performance with async-profiler on Amazon EKS](https://aws.amazon.com/blogs/containers/analyzing-java-applications-performance-async-profiler-amazon-eks/)
