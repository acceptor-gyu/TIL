# JRockit의 JIT 컴파일 및 최적화 절차

## 개요
Oracle JRockit이 HotSpot과 달리 인터프리터 없이 처음부터 JIT 컴파일을 수행하는 방식과, 그 위에서 동작하는 적응형 최적화(Adaptive Optimization) 파이프라인을 학습한다. HotSpot의 Tiered Compilation과 비교하며 JRockit이 선택한 "전면 컴파일 + 점진적 재최적화" 전략의 의미를 정리한다.

## 상세 내용

### 1. JRockit이란

JRockit은 Appeal Virtual Machines가 개발한 서버 사이드 특화 JVM이다. 2002년 BEA Systems가 인수했고, 2008년 Oracle이 BEA를 인수하면서 Oracle의 제품이 되었다. 이후 2010년 Sun Microsystems 인수로 Oracle은 JRockit과 HotSpot이라는 두 개의 JVM을 동시에 보유하게 되었다.

JavaOne 2010에서 Oracle은 JRockit의 핵심 기능을 HotSpot(OpenJDK)에 통합하겠다고 발표했으며, 이 과정을 흔히 **HotRockit 프로젝트**라고 부른다. JRockit은 Java 6까지만 지원했고, 이후 단종되어 현재는 HotSpot 중심의 생태계로 완전히 통합되었다.

**JRockit의 탄생 배경과 철학:**
- 서버 사이드 long-running 워크로드(WAS, 배치 등)에서의 최대 처리량(throughput)을 목표로 설계
- BEA WebLogic Server의 성능 향상을 위해 긴밀하게 최적화
- 기동 시간보다 피크 성능(steady-state performance)을 우선시하는 철학
- HotSpot이 범용(데스크톱 + 서버) JVM인 반면, JRockit은 처음부터 서버 전용으로 설계

### 2. 인터프리터 없는 JIT-only 아키텍처

JRockit의 가장 큰 특징은 **인터프리터가 존재하지 않는다**는 점이다.

**HotSpot의 흐름:**
```
바이트코드 → 인터프리터 실행 → (호출 횟수 임계값 도달) → C1/C2 컴파일
```

**JRockit의 흐름:**
```
바이트코드 → 첫 호출 시점에 즉시 JIT 컴파일(quick JIT) → 실행 → (핫스팟 탐지) → 재최적화
```

JRockit은 메서드가 처음 호출되는 순간 인터프리팅 없이 곧바로 네이티브 코드로 컴파일한다. 이때 사용하는 컴파일러를 **quick JIT** 컴파일러라고 한다. quick JIT는 빠른 컴파일을 목표로 하기 때문에 생성되는 코드의 품질이 최상은 아니지만, 인터프리터보다는 훨씬 빠른 실행 속도를 보장한다.

**트레이드오프:**

| 항목 | JRockit (JIT-only) | HotSpot (인터프리터 + JIT) |
|---|---|---|
| 기동 초기 속도 | 컴파일 오버헤드로 인해 느릴 수 있음 | 인터프리터로 바로 실행하여 빠름 |
| 피크 성능 | 높음 (모든 코드가 네이티브) | 높음 (C2로 최적화된 코드) |
| 메모리 사용 | 코드 캐시 사용량이 더 많음 | 인터프리터 + JIT 코드 양쪽 존재 |
| 서버 워크로드 적합성 | 높음 | 높음 (Tiered Compilation 이후) |

### 3. 컴파일 파이프라인 단계

JRockit의 JIT 컴파일은 다음 단계를 거친다.

**1단계: 바이트코드 → MIR 변환**
- 자바 바이트코드를 JRockit 내부의 중간 표현(IR)인 **MIR(Middle-level Intermediate Representation)**으로 변환
- MIR은 기본 블록(basic block)을 노드로 갖는 방향성 제어 흐름 그래프(directed control flow graph)
- 각 기본 블록은 변수(variable)만을 피연산자로 사용하는 연산(operation)들로 구성
- 플랫폼 독립적인 최적화를 적용하기에 적합한 형태

**2단계: 최적화 패스(Optimization Pass) 적용**
- MIR 상에서 다양한 플랫폼 독립적 최적화 수행
- 메서드 인라이닝, 상수 전파, 불필요한 로드 제거, 복사 전파, 데드 코드 제거 등
- 최적화 수준은 quick JIT(빠른 컴파일 우선) vs Optimizer(품질 우선)로 구분

**3단계: 레지스터 할당 및 네이티브 코드 생성**
- 대상 아키텍처(x86, x64, SPARC 등)에 맞는 네이티브 명령어로 변환
- 레지스터 할당(register allocation)을 통해 변수를 CPU 레지스터에 매핑
- 플랫폼 의존적 최적화(명령어 스케줄링 등) 적용

**4단계: 코드 캐시 적재**
- 생성된 네이티브 코드를 코드 캐시(code cache)에 저장
- 이후 동일 메서드 호출 시 컴파일 없이 캐시된 네이티브 코드를 직접 실행
- 재최적화 시 기존 캐시를 교체

**최적화 예시 (JRockit 공식 문서 기반):**
```java
// 원본 자바 코드
x = b.get();
y = b.get();
z = x + y;

// 최적화 전 (MIR 변환 직후)
r1 = b.value   // b.get() 인라이닝
x  = r1
r2 = b.value   // b.get() 재호출
y  = r2
z  = x + y

// 1) 불필요한 로드 제거: r2 = b.value → r2 = r1
// 2) 복사 전파: x = r1, y = r1 → z = r1 + r1
// 3) 데드 코드 제거: x, y 변수 할당 제거
z = r1 + r1  // 최종 최적화 결과
```

### 4. 적응형 최적화 (Adaptive Optimization)

quick JIT로 생성된 코드는 실행 가능하지만 최고 품질이 아니다. JRockit은 런타임 중 **Optimizer 스레드**를 백그라운드로 실행하여 핫 메서드를 더 공격적으로 재컴파일한다. 이 과정이 적응형 최적화다.

**핫스팟 탐지: 샘플링 스레드 방식**

JRockit은 HotSpot의 카운터 기반 방식과 달리, **샘플링 스레드(sampler thread)**를 이용한다.

- 전용 샘플링 스레드가 주기적으로 깨어나 여러 애플리케이션 스레드의 현재 실행 상태를 확인
- 어떤 메서드가 자주 실행되고 있는지를 통계적으로 파악
- 오버헤드가 적고(low-cost), 계수기 관리 코드를 메서드 진입부에 삽입할 필요가 없음

**카운터 방식(HotSpot) vs 샘플링 방식(JRockit) 비교:**

| 항목 | 카운터 기반 (HotSpot) | 샘플링 기반 (JRockit) |
|---|---|---|
| 탐지 방식 | 메서드 진입 시마다 카운터 증가 | 주기적으로 스레드 상태를 스냅샷 |
| 오버헤드 | 카운터 증감 코드가 모든 메서드에 삽입 | 샘플링 스레드만 주기적으로 동작 |
| 정확성 | 호출 횟수가 정확히 측정됨 | 통계적 추정(확률적으로 누락 가능) |
| 탐지 기준 | 임계값(threshold) 초과 시 | 빈번하게 관찰된 메서드 |

**Optimizer 스레드 동작:**
1. 샘플링 스레드가 핫 메서드를 식별하여 최적화 큐에 등록
2. Optimizer 스레드가 백그라운드에서 해당 메서드를 더 공격적으로 재컴파일
3. 컴파일 완료 후 코드 캐시의 기존 코드를 새 코드로 교체
4. 애플리케이션 실행을 중단하지 않고 점진적으로 코드 품질을 향상

### 5. 주요 최적화 기법

**메서드 인라이닝 (Aggressive Inlining)**
- 핫 메서드의 호출 지점에 호출된 메서드의 코드를 직접 삽입
- 메서드 호출 오버헤드(스택 프레임 생성, 인자 전달 등) 제거
- 인라이닝 이후 추가 최적화(상수 전파, 데드 코드 제거)의 기회를 열어줌
- JRockit은 HotSpot보다 더 공격적인 인라이닝을 시도하는 것으로 알려짐

**이스케이프 분석 (Escape Analysis) 및 객체 할당 최적화**
- 어떤 객체가 생성된 메서드 또는 스레드 밖으로 "탈출(escape)"하는지를 분석
- 탈출하지 않는 객체에 대해:
  - **스택 할당(Stack Allocation)**: 힙 대신 스택에 할당 → GC 대상에서 제외
  - **스칼라 교체(Scalar Replacement)**: 객체를 개별 필드 변수로 분해
  - **동기화 제거(Synchronization Removal)**: 스레드 탈출이 없으면 `synchronized` 블록 제거

```java
// 탈출하지 않는 객체 예시
void calculate() {
    Point p = new Point(1, 2);  // p가 메서드 밖으로 나가지 않음
    int result = p.x + p.y;
    // → p를 힙에 할당하지 않고 x=1, y=2 변수로 대체 가능
}
```

**루프 최적화**
- **루프 언롤링(Loop Unrolling)**: 루프 반복 횟수를 줄이고 루프 바디를 반복하여 분기 오버헤드 감소
- **루프 호이스팅(Loop Hoisting / Invariant Code Motion)**: 루프 내부에서 매 반복마다 변하지 않는 연산을 루프 밖으로 이동

```java
// 루프 호이스팅 전
for (int i = 0; i < list.size(); i++) { ... }

// 루프 호이스팅 후 (list.size() 호이스팅)
int len = list.size();
for (int i = 0; i < len; i++) { ... }
```

**역최적화 (Deoptimization)**
- JIT 컴파일러는 런타임 정보를 기반으로 "추정(speculation)"을 포함한 최적화를 적용
- 예: 어떤 가상 메서드 호출이 항상 특정 구현 클래스로 dispatch된다고 가정하여 인라이닝
- 이 가정이 런타임에 깨지면(새로운 서브클래스 로딩 등) 최적화된 코드를 폐기하고 안전한 코드로 되돌려야 함
- JRockit은 이러한 **가정 무효화(assumption invalidation)**를 추적하고, 필요 시 해당 메서드를 역최적화 후 재컴파일

### 6. JRockit Mission Control / Flight Recorder 연계

JRockit은 단순 JVM을 넘어 **JRockit Mission Control(JRMC)**이라는 종합 모니터링 및 진단 도구 스위트를 함께 제공했다.

**JRA (JRockit Runtime Analyzer) → JFR (JRockit Flight Recorder)**
- JRA는 JRockit R27.x 이전 버전의 프로파일링 도구
- R27.x부터 **JRockit Flight Recorder(JFR)**로 교체
  - 항상 켜진 상태(always-on)로 동작하며 오버헤드가 극히 낮음
  - JVM 내부의 컴파일, GC, 스레드, I/O 등의 이벤트를 자기 서술적(self-described) 형태로 기록
  - 기록 파일을 오프라인에서 Mission Control GUI로 분석

**컴파일/최적화 진단 관점:**
- JFR 레코딩에서 어떤 메서드가 컴파일되었는지, 최적화 수준이 어떻게 변경되었는지 확인 가능
- 최적화 의사결정(인라이닝 여부, 역최적화 발생 여부 등)을 사후에 추적

**역사적 의의:**
- JFR과 JRMC는 HotRockit 통합 과정에서 HotSpot에 이식
- JDK 7u40부터 HotSpot JVM에 JFR이 상업용 기능으로 포함
- JDK 11에서 오픈소스화되어 현재의 **JDK Flight Recorder**로 이어짐

### 7. HotSpot Tiered Compilation과의 비교

Java 7에서 HotSpot에 도입된 **Tiered Compilation**은 JRockit의 전략과 비교하면 흥미로운 차이를 보인다.

**HotSpot Tiered Compilation 5단계:**

| 레벨 | 설명 |
|---|---|
| Level 0 | 인터프리터 실행 (프로파일링 없음) |
| Level 1 | C1 컴파일 (프로파일링 없음, 단순 최적화) |
| Level 2 | C1 컴파일 (호출 횟수/백엣지 카운터만) |
| Level 3 | C1 컴파일 (전체 프로파일링) |
| Level 4 | C2 컴파일 (프로파일 데이터 기반 공격적 최적화) |

**JRockit vs HotSpot Tiered Compilation 비교:**

| 항목 | JRockit | HotSpot (Tiered) |
|---|---|---|
| 인터프리터 | 없음 | 있음 (Level 0) |
| 컴파일러 수 | 1개 (quick JIT + Optimizer) | 2개 (C1, C2) |
| 초기 실행 | 즉시 JIT 컴파일 → 느린 기동 | 인터프리터 → 빠른 기동 |
| 핫스팟 탐지 | 샘플링 스레드 | 카운터 기반 |
| 재최적화 | Optimizer 스레드가 백그라운드 수행 | C2로 재컴파일 |
| 현재 상태 | 단종 (Java 6까지 지원) | JDK 8 이후 기본값 |

**설계 선택의 의미:**
- JRockit의 "전면 JIT + 점진적 재최적화"는 long-running 서버 프로세스에서 결국 모든 코드가 최적화된다는 가정 위에 서 있음
- HotSpot Tiered Compilation은 JRockit의 이 철학을 인터프리터와 결합하여 기동 속도와 피크 성능을 동시에 확보하는 방향으로 발전
- JDK 8의 기본값이 Tiered Compilation이 된 것은 JRockit의 영향이 크다

## 핵심 정리
- 핵심 포인트 1: JRockit은 인터프리터 없이 모든 메서드를 처음부터 JIT 컴파일(quick JIT)하고, 이후 Optimizer 스레드가 핫 메서드를 백그라운드에서 재컴파일하는 "전면 컴파일 + 점진적 재최적화" 전략을 사용한다.
- 핵심 포인트 2: 핫스팟 탐지를 카운터 삽입이 아닌 샘플링 스레드로 수행하여, 계측 코드 오버헤드 없이 저비용으로 핫 메서드를 식별한다.
- 핵심 포인트 3: JRockit은 단종되었지만 JFR(Flight Recorder), Mission Control, 공격적 인라이닝, 이스케이프 분석 등 핵심 기술이 HotSpot에 이식되어 현재의 JDK에 살아있다.

## 기술적 한계와 보완 전략
- 인터프리터 부재로 인한 초기 컴파일 비용 → quick JIT(빠른 저품질 컴파일)로 완화, 이후 Optimizer가 품질 향상
- Java 6까지만 지원하고 단종 → HotRockit 통합 및 HotSpot Tiered Compilation / GraalVM으로 기술 계승
- 샘플링 기반 탐지의 통계적 부정확성 → 오버헤드가 낮다는 실용적 이점이 우선시됨

## 키워드

### JRockit
BEA Systems가 개발(원개발사: Appeal Virtual Machines)하고 Oracle이 인수한 서버 사이드 특화 JVM. 인터프리터 없이 JIT-only 방식으로 동작하며 long-running 서버 워크로드의 피크 처리량을 목표로 설계되었다. Java 6까지 지원 후 단종되어 HotSpot에 통합되었다.

### JIT-only Compilation
인터프리터 단계 없이 메서드 최초 호출 시점에 즉시 JIT 컴파일을 수행하는 아키텍처. JRockit의 핵심 특징으로, 인터프리터 실행 없이 모든 코드가 처음부터 네이티브 코드로 실행된다. 기동 시 컴파일 비용이 있지만 실행 중에는 항상 네이티브 속도를 보장한다.

### Adaptive Optimization
런타임 프로파일링 정보를 바탕으로 핫 코드를 식별하고 점진적으로 더 공격적인 최적화를 적용하는 기법. JRockit에서는 quick JIT로 컴파일된 코드 중 자주 실행되는 메서드를 Optimizer 스레드가 재컴파일하는 방식으로 구현된다.

### Sampling-based Hotspot Detection
전용 샘플링 스레드가 주기적으로 애플리케이션 스레드의 실행 상태를 관찰하여 핫 메서드를 통계적으로 탐지하는 방식. HotSpot의 카운터 삽입 방식 대비 메서드 진입 오버헤드가 없다는 장점이 있다.

### Aggressive Inlining
핫 메서드의 호출 지점에 호출된 메서드의 코드를 직접 삽입하는 최적화. 메서드 호출 오버헤드를 제거하고 이후 상수 전파, 데드 코드 제거 등 추가 최적화의 기회를 제공한다. JRockit은 HotSpot 대비 더 공격적인 인라이닝 정책을 적용한다.

### Deoptimization
JIT 컴파일러가 적용한 투기적 최적화(speculative optimization)의 전제 가정이 런타임에 깨질 때, 최적화된 코드를 폐기하고 안전한 코드로 복귀하는 메커니즘. 예를 들어, 단형적(monomorphic) 가상 메서드 호출로 가정하여 인라이닝했는데 새로운 서브클래스가 로드되면 해당 최적화를 무효화해야 한다.

### JRockit Mission Control (JRA)
JRockit JVM에 내장된 종합 모니터링 및 진단 도구 스위트. JRockit Runtime Analyzer(JRA)에서 JRockit Flight Recorder(JFR)로 발전했으며, 낮은 오버헤드로 컴파일, GC, 스레드 등의 JVM 내부 이벤트를 기록하고 분석한다. 이 기술은 HotSpot에 이식되어 현재의 JDK Flight Recorder(JFR)와 JDK Mission Control(JMC)로 이어진다.

### Tiered Compilation
HotSpot JVM이 Java 7에서 도입하고 Java 8에서 기본값으로 채택한 다단계 컴파일 전략. 인터프리터(Level 0), C1 컴파일러(Level 1~3), C2 컴파일러(Level 4)를 조합하여 빠른 기동 속도와 높은 피크 성능을 동시에 확보한다. JRockit의 "전면 JIT + 재최적화" 철학을 인터프리터와 결합하여 개선한 형태로 볼 수 있다.

### HotRockit
Oracle이 JRockit을 인수한 뒤 JRockit의 장점(JFR, Mission Control, 공격적 최적화 기법 등)을 HotSpot(OpenJDK)에 통합하는 프로젝트의 별칭. JavaOne 2010에서 공식 발표되었으며, JDK 7u40의 JFR 포함, JDK 8의 Tiered Compilation 기본 활성화, JDK 11의 JFR 오픈소스화 등을 통해 결실을 맺었다.

### Intermediate Representation (IR)
컴파일러가 소스 코드(또는 바이트코드)를 네이티브 코드로 변환하는 중간 단계에서 사용하는 내부 표현 형식. JRockit에서는 MIR(Middle-level IR)이라고 불리며, 기본 블록과 변수만을 사용하는 제어 흐름 그래프 형태다. 플랫폼 독립적인 최적화를 이 단계에서 수행한다.

## 참고 자료
- [Understanding JIT Compilation and Optimizations - Oracle JRockit Diagnostics Guide](https://docs.oracle.com/cd/E13150_01/jrockit_jvm/jrockit/geninfo/diagnos/underst_jit.html)
- [Compilation Optimization - JRockit to HotSpot Migration Guide](https://docs.oracle.com/javacomponents/jrockit-hotspot/migration-guide/comp-opt.htm)
- [JRockit - Wikipedia](https://en.wikipedia.org/wiki/JRockit)
- [A comparison of Java Virtual Machines: HotSpot JVM vs JRockit JVM](https://www.dbi-services.com/blog/a-comparison-of-java-virtual-machines-hotspot-jvm-vs-jrockit-jvm/)
