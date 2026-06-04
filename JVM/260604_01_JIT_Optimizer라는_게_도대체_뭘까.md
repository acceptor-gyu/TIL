# JIT Optimizer라는 게 도대체 뭘까

## 개요

JVM은 Java 소스코드를 바이트코드(.class)로 컴파일한 뒤, 이를 런타임에 실행한다. 이 런타임 실행 단계에서 JIT(Just-In-Time) 컴파일러가 개입하여 자주 실행되는 코드 경로(Hot Path)를 네이티브 머신 코드로 변환하고, 그 과정에서 다양한 최적화를 수행한다. 이 문서는 JIT 컴파일러 내부의 Optimizer가 어떤 원리로 동작하는지를 단계별로 설명한다.

## 상세 내용

### 인터프리터 vs JIT 컴파일러

JVM은 처음 코드를 실행할 때 인터프리터를 사용한다. 인터프리터는 바이트코드를 한 줄씩 해석하여 실행하므로 별도의 컴파일 없이 즉시 시작할 수 있다는 장점이 있다. 그러나 같은 코드를 반복 실행할 때도 매번 바이트코드를 해석하므로 성능이 낮다.

JIT 컴파일러는 이 한계를 극복하기 위해 등장했다. 런타임 중 메서드의 호출 횟수와 루프 반복 횟수를 카운터로 추적하다가, 특정 임계치(threshold)를 넘어선 메서드를 "핫 메서드(Hot Method)"로 판단한다. 이 핫 메서드는 네이티브 머신 코드로 컴파일되어 이후 실행 시 인터프리터를 거치지 않고 직접 실행된다.

```
실행 흐름:
바이트코드 → 인터프리터 실행 (프로파일 수집)
                │
                ▼ 호출 횟수 임계치 도달
            C1 컴파일 (빠른 최적화)
                │
                ▼ 호출 횟수 임계치 도달 (더 많이)
            C2 컴파일 (공격적 최적화)
```

### JIT Optimizer란 무엇인가

JIT Optimizer는 JIT 컴파일 과정 중 네이티브 코드를 생성하기 전에 수행하는 최적화 단계를 통칭한다. 인터프리터가 수집한 런타임 프로파일 정보(타입 정보, 분기 통계, 호출 빈도 등)를 기반으로 추측적 가정(Speculative Assumption)을 세우고, 이를 바탕으로 더 빠른 코드를 생성한다.

핵심 특징은 세 가지다.

1. **런타임 정보 활용**: 정적 분석이 아니라 실제 실행 중에 수집한 데이터를 기반으로 최적화한다.
2. **추측적 최적화**: 가정이 맞는 경우에만 빠른 경로로 실행하고, 가정이 깨지면 Deoptimization으로 안전하게 복귀한다.
3. **적응형 재컴파일**: 프로그램 동작이 바뀌면 재컴파일하여 새로운 최적화를 적용한다.

### C1(Client) / C2(Server) 컴파일러와 Tiered Compilation

HotSpot JVM은 두 가지 JIT 컴파일러를 가지고 있다.

**C1 컴파일러 (Client Compiler)**

- 컴파일 속도가 빠르고 적용 최적화 수준이 낮다.
- 호출 임계치가 낮아(기본 1,500회) 빠르게 컴파일을 시작한다.
- 기본적인 최적화(인라이닝, 간단한 데드 코드 제거 등)를 수행한다.
- 애플리케이션 시작 시간을 단축하는 데 목적이 있다.

**C2 컴파일러 (Server Compiler)**

- 컴파일 시간이 길지만 고도로 최적화된 네이티브 코드를 생성한다.
- 호출 임계치가 높아(기본 10,000회) 충분한 프로파일 데이터가 쌓인 후 동작한다.
- 루프 언롤링, 이스케이프 분석, 추측적 최적화 등 공격적인 최적화를 모두 적용한다.
- C1 대비 최적화 성능이 30% 이상 높다.

**Tiered Compilation (Java 8부터 기본 적용)**

두 컴파일러를 순차적으로 활용하는 전략이다. 총 5단계(Tier 0~4)로 구성된다.

| 단계 | 설명 |
|------|------|
| Tier 0 | 인터프리터 실행. 기본 프로파일 데이터 수집 |
| Tier 1 | C1 컴파일, 프로파일링 없음 (이미 충분한 데이터 보유 시) |
| Tier 2 | C1 컴파일, 호출/루프 카운터만 수집 |
| Tier 3 | C1 컴파일, 전체 프로파일링 활성화 |
| Tier 4 | C2 컴파일, C1이 수집한 프로파일 기반으로 공격적 최적화 |

애플리케이션 시작 직후에는 Tier 0~3으로 빠르게 코드를 실행하면서 데이터를 모은다. 이후 핫 메서드로 판별되면 Tier 4 C2 컴파일로 최종 최적화된 코드를 생성한다.

### 주요 최적화 기법

**Method Inlining (메서드 인라이닝)**

메서드 호출을 해당 메서드의 본문으로 직접 치환하는 기법이다. 메서드 호출 자체의 오버헤드(스택 프레임 생성, 인자 전달 등)를 제거하고, 인라인된 코드에 추가 최적화를 연쇄적으로 적용할 수 있다.

```java
// 최적화 전
int result = add(a, b);

// 인라이닝 후 (컴파일러가 내부적으로 처리)
int result = a + b;
```

HotSpot은 기본적으로 바이트코드 크기 35 bytes 이하인 메서드를 인라이닝 대상으로 선택한다. 인라이닝은 다른 모든 최적화의 기반이 되므로 가장 중요한 최적화 기법으로 꼽힌다.

**Escape Analysis (이스케이프 분석)**

객체가 생성된 메서드나 스레드 밖으로 "탈출(escape)"하는지 분석하는 기법이다. 탈출하지 않는 객체는 힙 대신 스택에 할당(Stack Allocation)하거나, 객체 자체를 제거하고 필드를 스칼라 값으로 분해(Scalar Replacement)할 수 있다.

- 힙 할당이 없으므로 GC 압력이 감소한다.
- 스택 할당은 메서드 종료 시 자동으로 해제되므로 추가 비용이 없다.

```java
// 이 메서드에서 Point는 외부로 탈출하지 않는다
int compute() {
    Point p = new Point(3, 4); // 스택 할당 가능
    return p.x + p.y;
}
```

**Loop Unrolling (루프 언롤링)**

루프 본문을 여러 번 복제하여 루프 반복 횟수를 줄이는 기법이다. 분기(branch) 횟수가 줄어들어 CPU 파이프라인 효율이 높아지고, 언롤된 코드에 SIMD(벡터화) 등 추가 최적화를 적용할 수 있다.

```java
// 언롤링 전
for (int i = 0; i < 8; i++) {
    arr[i] = i * 2;
}

// 언롤링 후 (컴파일러 내부 처리)
arr[0] = 0; arr[1] = 2; arr[2] = 4; arr[3] = 6;
arr[4] = 8; arr[5] = 10; arr[6] = 12; arr[7] = 14;
```

**Dead Code Elimination (죽은 코드 제거)**

런타임에 절대 실행되지 않는 코드를 제거한다. 정적 컴파일러와 달리 JIT는 실제 실행 중 수집한 분기 통계를 활용하므로, 조건부 코드 중 한 번도 실행되지 않은 분기를 제거할 수 있다.

**Branch Prediction (분기 예측 최적화)**

인터프리터 단계에서 수집한 분기 통계(if/else 중 어느 경로가 더 자주 실행됐는지)를 기반으로 핫 경로(Hot Path)를 패스트 패스로 배치하고 콜드 경로(Cold Path)를 제거하거나 뒤로 밀어내어 CPU의 분기 예측 히트율을 높인다.

### 프로파일 기반 최적화와 Deoptimization

**Profile-Guided Optimization (PGO)**

JVM은 인터프리터와 C1 실행 단계에서 다양한 런타임 정보를 수집한다.

- 메서드 호출 횟수, 루프 반복 횟수
- 타입 정보 (실제 어떤 구현체가 호출되는지)
- 분기 방향 통계 (if가 true/false 중 어느 쪽으로 더 많이 흘렀는지)
- null 체크 발생 여부

C2는 이 데이터를 기반으로 추측적 가정을 세운다. 예를 들어 인터페이스를 구현한 구체 타입이 하나뿐이라면 가상 메서드 호출(virtual dispatch) 대신 직접 호출(static call)로 최적화한다. 이를 **추측적 최적화(Speculative Optimization)** 또는 **추측적 인라이닝(Speculative Inlining)**이라 한다.

**Deoptimization (역최적화)**

추측적 가정이 깨지면 컴파일된 코드는 더 이상 올바르지 않다. 이때 JVM은 해당 코드를 무효화하고 인터프리터로 복귀한다. 이 과정을 Deoptimization이라 한다.

Deoptimization은 두 가지 방식으로 발생한다.

- **동기식(Synchronous)**: 실행 중인 스레드 스스로 가정 위반을 감지했을 때 (예: null 체크 실패)
- **비동기식(Asynchronous)**: 다른 스레드의 행동(예: 새로운 서브클래스 로드)으로 인해 외부에서 무효화 요청이 올 때

Deoptimization은 Safepoint에서만 수행된다. Safepoint는 JVM이 스레드 실행 상태를 안전하게 조사·수정할 수 있는 특정 실행 지점이다. 역최적화 이후 JVM은 다시 프로파일 데이터를 쌓아 재컴파일할 수 있다.

```
추측적 최적화 흐름:
런타임 프로파일 수집
    → 가정 세우기 (예: List의 구현체는 ArrayList 뿐이다)
        → 직접 호출로 최적화된 코드 생성
            → 새로운 구현체 로드 감지
                → Deoptimization → 인터프리터로 복귀
                    → 재프로파일링 → 재컴파일
```

## 핵심 정리

- JIT 컴파일러는 런타임에 핫 메서드를 네이티브 코드로 컴파일하며, 이 과정에서 Optimizer가 다양한 최적화를 적용한다.
- Tiered Compilation은 C1(빠른 시작)과 C2(공격적 최적화)를 순차 적용하여 시작 성능과 최고 처리량을 모두 확보한다.
- 인라이닝, 이스케이프 분석, 루프 언롤링, 죽은 코드 제거는 JIT Optimizer의 핵심 기법이다.
- 추측적 최적화는 런타임 프로파일을 기반으로 공격적 가정을 세우고, 가정이 깨지면 Deoptimization으로 안전하게 복귀한다.
- 이 모든 과정이 투명하게 작동하므로 개발자는 별도 코드 변경 없이 JVM의 최적화 혜택을 받는다.

## 기술적 한계와 보완 전략

**Warm-up 비용**

JIT 최적화는 충분한 프로파일 데이터가 쌓인 후에야 적용된다. 애플리케이션이 최고 성능에 도달하기까지 수십 초에서 수 분이 소요된다. 이 시간 동안에는 인터프리터 또는 C1 수준의 성능으로만 동작한다.

Spring Boot 서비스 기준 워밍업 성능 변화 예시:
- 0~10초: ~4,000 req/s (인터프리터)
- 10~30초: ~12,000 req/s (C1 적용)
- 5분 이후: ~28,000 req/s (C2 완전 적용)

**메서드 임계치와 컴파일 큐 경합**

JIT 컴파일러는 애플리케이션과 같은 프로세스 내에서 실행되므로 CPU 자원을 놓고 경합한다. 컴파일 큐가 밀리면 최적화 적용이 지연될 수 있다.

**보완 전략**

| 전략 | 설명 |
|------|------|
| **AOT (Ahead-of-Time) 컴파일** | 빌드 시점에 네이티브 코드 생성. 워밍업 없이 즉시 최고 성능 근처에 도달 |
| **GraalVM Native Image** | AOT 기반으로 네이티브 실행 파일을 생성. 시작 시간 10~50배 단축 (수 초 → 50~200ms) |
| **CDS (Class Data Sharing)** | JVM 시작 시 공유 아카이브를 메모리 매핑하여 클래스 로딩 비용 절감 |
| **PGO (Profile-Guided Optimization) in GraalVM** | 프로파일 데이터를 사전 수집하여 AOT 컴파일에 활용. JIT 대비 5~15% 이내의 처리량으로 격차 줄이기 가능 |

GraalVM Native Image는 강력하지만 Java의 동적 기능(리플렉션, JNI, 동적 프록시 등)을 제한적으로 지원하므로, 장기 실행 서비스에서는 여전히 JIT 기반 JVM이 최고 처리량 면에서 10~25% 더 높은 성능을 보인다.

## 키워드

- **JIT(Just-In-Time) Compilation**: 런타임에 바이트코드를 네이티브 머신 코드로 변환하는 컴파일 방식. 인터프리터 방식의 느린 반복 실행 문제를 해결한다.
- **Tiered Compilation**: C1과 C2 컴파일러를 Tier 0~4 단계로 조합하여 시작 속도와 최고 성능을 동시에 확보하는 전략. Java 8부터 기본 적용된다.
- **C1 / C2 Compiler**: C1은 빠른 컴파일과 경량 최적화 담당(임계치 1,500회), C2는 공격적 최적화 담당(임계치 10,000회). HotSpot JVM의 두 JIT 컴파일러.
- **Method Inlining**: 메서드 호출을 호출 지점에 본문으로 직접 치환하는 최적화. 호출 오버헤드 제거 및 연쇄 최적화의 기반이 된다.
- **Escape Analysis**: 객체가 메서드나 스레드 밖으로 탈출하는지 분석하여 스택 할당이나 스칼라 치환을 적용, GC 압력을 줄이는 기법.
- **Profile-Guided Optimization**: 인터프리터와 C1 단계에서 수집한 런타임 프로파일 데이터(타입 정보, 분기 통계, 호출 빈도)를 C2 최적화에 활용하는 전략.
- **Deoptimization**: 추측적 최적화의 가정이 깨졌을 때 컴파일된 코드를 무효화하고 인터프리터로 복귀하는 메커니즘. Safepoint에서만 발생한다.
- **HotSpot**: Oracle/OpenJDK의 JVM 구현체 이름. C1, C2 JIT 컴파일러를 포함하며 핫 메서드를 감지하고 최적화하는 적응형 실행 엔진이다.
- **Loop Unrolling**: 루프 본문을 복제하여 반복 횟수를 줄이고 분기 비용을 감소시키는 최적화 기법. CPU 파이프라인 효율과 벡터화를 개선한다.
- **Warm-up Cost**: JIT 최적화가 충분히 적용되기 전까지 발생하는 초기 성능 저하 기간. AOT/GraalVM Native Image로 우회하거나 사전 트래픽 유입으로 완화할 수 있다.

## 참고 자료

- [JVM Just-In-Time (JIT) Compilation: How HotSpot Optimizes Java Code](https://prgrmmng.com/jvm-just-in-time-jit-compilation-how-hotspot-optimizes-code)
- [How the JIT compiler boosts Java performance in OpenJDK - Red Hat Developer](https://developers.redhat.com/articles/2021/06/23/how-jit-compiler-boosts-java-performance-openjdk)
- [Tiered Compilation in JVM: Understanding C1 vs C2 Compilers](https://prgrmmng.com/tiered-compilation-in-jvm-c1-vs-c2-compilers)
- [How Tiered Compilation works in OpenJDK - Microsoft for Java Developers](https://devblogs.microsoft.com/java/how-tiered-compilation-works-in-openjdk/)
- [Deoptimization in JVM: Understanding When Optimizations Are Rolled Back](https://prgrmmng.com/deoptimization-in-jvm-when-optimizations-are-rolled-back)
- [Introduction to HotSpot JVM C2 JIT Compiler, Part 0 - Emanuel's Blog](https://eme64.github.io/blog/2024/12/24/Intro-to-C2-Part00.html)
- [Introduction to HotSpot JVM C2 JIT Compiler, Part 1 - Emanuel's Blog](https://eme64.github.io/blog/2024/12/24/Intro-to-C2-Part01.html)
- [Runtime profiling in OpenJDK's HotSpot JVM - Red Hat Developer](https://developers.redhat.com/articles/2021/11/18/runtime-profiling-openjdks-hotspot-jvm)
- [GraalVM Native Image vs Traditional JVM: Understanding the Trade-offs](https://www.javacodegeeks.com/2025/12/graalvm-native-image-vs-traditional-jvm-understanding-the-trade-offs.html)
