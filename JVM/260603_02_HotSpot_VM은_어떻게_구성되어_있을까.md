# HotSpot VM은 어떻게 구성되어 있을까?

## 개요
[Oracle/OpenJDK의 대표 JVM 구현체인 HotSpot VM의 내부 아키텍처를 학습한다. "HotSpot"이라는 이름의 유래(핫 코드 탐지)부터, 이를 구성하는 핵심 서브시스템들이 어떻게 협력하여 바이트코드를 고성능 네이티브 코드로 실행하는지 전체 구성도를 그려보는 것이 목표.]

## 상세 내용

### 1. HotSpot VM이란 무엇인가

HotSpot VM은 1999년 Sun Microsystems가 개발하고 이후 Oracle이 인수한 JVM의 대표적인 구현체다. 현재는 OpenJDK의 기본 JVM으로 자리잡고 있다.

이름의 유래는 프로파일링 기반의 "핫스팟 탐지"에서 왔다. JVM이 프로그램 실행 중 자주 호출되는 코드 경로(Hot Code)를 식별하고, 해당 구간만 선택적으로 JIT 컴파일하는 방식에서 비롯된 이름이다. 모든 코드를 컴파일하지 않고 실제 실행 빈도가 높은 부분만 최적화하므로 스타트업 속도와 피크 성능을 동시에 챙길 수 있다.

**다른 JVM 구현체들과의 비교**

| JVM | 개발사 | 특징 |
|-----|--------|------|
| HotSpot | Oracle/OpenJDK | 범용, 가장 널리 사용 |
| OpenJ9 | Eclipse/IBM | 메모리 효율 중심, 컨테이너 환경 강점 |
| GraalVM | Oracle Labs | Polyglot 지원, AOT(Native Image) 가능 |
| Azul Zing (Prime) | Azul Systems | C4 GC로 무중단 압축, 초저지연 |

### 2. HotSpot 전체 아키텍처 한눈에 보기

HotSpot VM은 크게 4개의 핵심 서브시스템으로 구성된다.

```
┌────────────────────────────────────────────────────┐
│                  Java Application                   │
└────────────────────┬───────────────────────────────┘
                     │ .class (bytecode)
┌────────────────────▼───────────────────────────────┐
│           Class Loader Subsystem                    │
│  Bootstrap → Platform → Application Loader          │
└────────────────────┬───────────────────────────────┘
                     │ 로드된 클래스 메타데이터
┌────────────────────▼───────────────────────────────┐
│             Runtime Data Area                       │
│  Heap(Young/Old) │ Metaspace │ JVM Stack            │
│  PC Register     │ Native Method Stack              │
└──────┬───────────────────────────────┬─────────────┘
       │                               │
┌──────▼──────────┐        ┌───────────▼─────────────┐
│ Execution Engine│        │    Native Interface      │
│ Interpreter     │        │    (JNI / JNA)           │
│ JIT (C1/C2)     │        │ Native Method Libraries  │
│ GC              │        └─────────────────────────┘
└─────────────────┘
```

각 서브시스템은 다음 역할을 담당한다.
- **Class Loader Subsystem**: .class 파일을 읽어 메모리에 적재
- **Runtime Data Area**: JVM이 실행 중 사용하는 메모리 영역 전체
- **Execution Engine**: 바이트코드를 해석하거나 네이티브 코드로 컴파일하여 실행
- **Native Interface**: C/C++ 등 네이티브 라이브러리와의 상호 운용

### 3. 클래스 로더 서브시스템

클래스 로더 서브시스템은 로딩(Loading) → 링킹(Linking) → 초기화(Initialization) 3단계로 .class 파일을 JVM 메모리에 올린다.

**로딩-링킹-초기화 단계**

| 단계 | 설명 |
|------|------|
| Loading | .class 바이너리를 읽어 Method Area에 클래스 표현 생성 |
| Linking - Verify | 바이트코드가 JVM 명세에 적합한지 검증 |
| Linking - Prepare | static 변수에 기본값 할당 |
| Linking - Resolve | 심볼릭 레퍼런스를 실제 메모리 주소로 교체 |
| Initialization | static 블록 실행, static 변수에 실제 값 대입 |

**부모 위임 모델 (Parent Delegation Model)**

클래스를 로드할 때 먼저 부모 로더에게 위임하고, 부모가 실패해야 자신이 처리한다. 이 모델 덕분에 `java.lang.String` 같은 핵심 클래스를 악의적으로 재정의하는 공격을 방지할 수 있다.

```
요청
  └→ Application ClassLoader (classpath)
       └→ Platform ClassLoader (Java SE/JDK 모듈)
            └→ Bootstrap ClassLoader (rt.jar, java.*)
                 ← 실패 시 역방향으로 자신이 로드
```

**세 가지 내장 클래스 로더**

- **Bootstrap ClassLoader**: JVM 자체에 내장, `java.*` 핵심 패키지 로드, C++로 구현
- **Platform ClassLoader**: Java SE 및 JDK 모듈 로드 (Java 9 이후 ExtClassLoader 대체)
- **Application ClassLoader**: `-classpath`에 지정된 클래스 로드, 일반 애플리케이션 클래스

### 4. 런타임 데이터 영역

JVM이 실행 중 사용하는 메모리 공간은 스레드 공유 영역과 스레드별 영역으로 나뉜다.

**스레드 공유 영역**

- **Heap**: 모든 객체 인스턴스와 배열이 할당되는 공간
  - Young Generation (Eden + Survivor S0/S1): 신규 객체 할당, Minor GC 대상
  - Old Generation (Tenured): 오래 살아남은 객체, Major/Full GC 대상
  - TLAB(Thread-Local Allocation Buffer): Eden 내 스레드별 전용 할당 버퍼로 동기화 비용 절감
- **Metaspace**: Java 8부터 PermGen을 대체, 클래스 메타데이터(메서드 정의, 상수 풀 등)를 저장. 네이티브 메모리에 위치하여 기본적으로 동적 확장 가능

**스레드별 영역**

- **JVM Stack**: 각 스레드마다 생성, 메서드 호출마다 Stack Frame 하나씩 추가. Frame에는 지역 변수 테이블, 피연산자 스택, 상수 풀 레퍼런스 포함
- **PC Register (Program Counter)**: 현재 실행 중인 JVM 명령어의 주소를 가리키는 레지스터. 스레드별로 독립 존재
- **Native Method Stack**: JNI를 통해 호출된 C/C++ 네이티브 메서드의 실행 스택

### 5. 실행 엔진 (Interpreter + JIT + GC)

실행 엔진은 HotSpot의 핵심으로, 바이트코드를 실제로 실행하는 책임을 진다.

**템플릿 인터프리터 (Template Interpreter)**

HotSpot의 인터프리터는 단순 switch-case 방식이 아닌 템플릿 기반이다. JVM 시작 시 각 바이트코드 명령어에 대응하는 네이티브 어셈블리 코드 조각(템플릿)을 미리 생성해두고, 실행 시 바로 호출한다. 일반 인터프리터 대비 수배 빠른 성능을 제공한다.

**Tiered Compilation (계층형 JIT 컴파일)**

Java 8부터 기본 활성화된 5단계 컴파일 전략이다. 빠른 시작(C1)과 최고 성능(C2)을 모두 얻을 수 있다.

| 계층 | 실행 방식 | 설명 |
|------|-----------|------|
| Tier 0 | 인터프리터 | 초기 실행, 프로파일링 데이터 수집 시작 |
| Tier 1 | C1 (프로파일링 없음) | 단순하고 빠른 컴파일 |
| Tier 2 | C1 (일부 프로파일링) | 제한된 프로파일링 포함 |
| Tier 3 | C1 (전체 프로파일링) | 완전한 프로파일링 데이터 수집 |
| Tier 4 | C2 | 프로파일링 데이터 기반 최적화 컴파일 |

- **C1 컴파일러**: 호출 횟수 약 1,500회 도달 시 발동. 빠른 컴파일, 경량 최적화(인라이닝, 상수 폴딩)
- **C2 컴파일러**: 호출 횟수 약 10,000회 도달 시 발동. 공격적 최적화(탈출 분석, 루프 펼치기, 추론적 최적화)

**Code Cache**

JIT가 생성한 네이티브 코드를 저장하는 전용 메모리 영역이다. `-XX:ReservedCodeCacheSize`로 크기 조절 가능. Code Cache가 가득 차면 JIT 컴파일이 중단되고 인터프리터로 폴백된다.

### 6. HotSpot의 핵심 최적화 기법

**메서드 인라이닝 (Method Inlining)**

호출 빈도가 높고 크기가 작은 메서드의 본문을 호출 지점에 직접 삽입한다. 메서드 호출 오버헤드(Stack Frame 생성, 점프)를 제거하고, 이후 다른 최적화(상수 전파, 탈출 분석)의 기반이 된다.

```
// 인라이닝 전
int result = add(a, b);  // 메서드 호출

// 인라이닝 후 (JIT 내부)
int result = a + b;      // 직접 삽입
```

**탈출 분석 (Escape Analysis)**

객체가 생성된 메서드 범위를 "탈출"하는지 여부를 분석한다.
- 탈출하지 않는 객체는 힙 대신 스택에 할당(Stack Allocation) → GC 부담 감소
- 단일 스레드만 접근하는 객체의 synchronized 블록 제거(Lock Elision)
- 소규모 객체의 필드를 직접 레지스터로 분리(Scalar Replacement)

**루프 펼치기 (Loop Unrolling)**

반복 횟수가 적은 루프의 반복 본문을 여러 번 복사하여 분기 횟수를 줄인다.

**On-Stack Replacement (OSR)**

이미 실행 중인 루프를 인터프리터 실행 도중에 JIT 컴파일된 코드로 교체하는 기법이다. 메서드 전체가 아닌 루프 역방향 분기(backedge) 횟수가 임계치를 넘을 때 발동한다. 오래 실행되는 루프의 경우 메서드 호출 없이도 JIT 최적화의 혜택을 받을 수 있다.

**역최적화 (Deoptimization)**

C2가 수행한 추론적 최적화(예: 특정 타입만 온다고 가정한 인라이닝)가 런타임에 무효화될 때 발생한다. 컴파일된 코드를 버리고 인터프리터로 되돌아간다. 다형성 호출이 실제로 여러 타입을 받기 시작할 때 흔히 발생한다.

### 7. 객체 메모리 레이아웃

HotSpot에서 모든 Java 객체는 다음 구조로 메모리에 배치된다.

```
┌────────────────────────────────┐
│         Object Header          │
│  Mark Word (8 bytes)           │  ← 해시코드, GC 나이, 잠금 상태 등
│  Klass Pointer (4 or 8 bytes)  │  ← 클래스 메타데이터 포인터
├────────────────────────────────┤
│     Instance Fields            │  ← 실제 필드 값
├────────────────────────────────┤
│     Padding (alignment)        │  ← 8바이트 정렬을 위한 패딩
└────────────────────────────────┘
```

**Mark Word**

Mark Word는 객체의 상태에 따라 비트 레이아웃이 달라지는 다목적 헤더다.

| 상태 | 내용 |
|------|------|
| 일반 | identity hashcode(31bit), GC 나이(4bit), 잠금 비트 |
| 경량 잠금 | 잠금 레코드 포인터 |
| 중량 잠금(monitor) | Monitor 객체 포인터 |
| GC 마킹 중 | 포워딩 포인터 |

**Compressed Oops (Ordinary Object Pointers)**

64비트 JVM에서 객체 참조는 기본적으로 8바이트를 차지한다. 힙 크기가 32GB 이하일 때 `-XX:+UseCompressedOops`(기본 활성화)를 사용하면 참조를 4바이트로 압축한다. JVM이 3비트 시프트를 통해 35비트 주소 공간(최대 32GB)을 4바이트 값으로 표현하는 원리다. 메모리 사용량이 최대 30~40% 감소한다.

**Klass Pointer도 압축 가능**: `-XX:+UseCompressedClassPointers`로 클래스 포인터도 4바이트로 압축.

### 8. HotSpot의 GC 모듈 구성

HotSpot의 GC는 모듈화된 구조로, `src/hotspot/share/gc/` 아래 각 GC별 디렉터리로 분리되어 있다. 공통 인터페이스(`gc/shared/`)를 통해 상위 레이어와 통신한다.

**GC 종류 비교**

| GC | 특징 | 적합한 상황 |
|----|------|------------|
| Serial GC | 단일 스레드, Stop-the-World | 소형 앱, 단일 코어 |
| Parallel GC | 멀티스레드 처리량 중심 | 배치 처리, 처리량 우선 |
| G1 GC | 리전 기반, 예측 가능한 일시 정지 | 대용량 힙, 응답성 균형 (Java 9 기본) |
| ZGC | 대부분 동시 실행, ms 단위 일시 정지 | 초저지연, 대용량 힙 (Java 15 GA) |
| Shenandoah | 동시 압축, ms 단위 일시 정지 | 초저지연, Red Hat 주도 |

G1 GC는 힙을 동일 크기의 리전(Region)으로 나누어 관리하며, 사용자가 목표 일시 정지 시간(기본 200ms)을 지정할 수 있다. ZGC와 Shenandoah는 힙 크기와 무관하게 일시 정지 시간을 수 ms 수준으로 유지하는 것이 목표다.

### 9. C++ 레벨에서 본 HotSpot

HotSpot은 C++로 작성된 대규모 프로젝트다. 주요 소스 구조는 다음과 같다.

```
src/hotspot/
  share/          ← 플랫폼 공통 코드
    gc/           ← GC 구현체들 (g1/, z/, shenandoah/ 등)
    runtime/      ← 스레드 관리, Safepoint, 잠금 등
    compiler/     ← JIT 컴파일러 인터페이스
    interpreter/  ← 템플릿 인터프리터
    classfile/    ← 클래스 파일 파싱, 클래스 로더
    memory/       ← 메모리 관리, 힙 구현
  cpu/            ← CPU 아키텍처별 코드 (x86, aarch64 등)
  os/             ← OS별 코드 (linux, windows, macos)
```

**VM Thread와 Safepoint**

VM Thread는 GC, Deoptimization, 클래스 언로딩 등 Stop-the-World 작업을 실행하는 전담 스레드다. 이런 작업을 수행하려면 모든 Java 스레드를 안전한 지점(Safepoint)에서 멈춰야 한다.

Safepoint 동작 방식:
1. VM Thread가 Safepoint 요청 플래그를 설정
2. JIT 컴파일된 코드에는 백분기(backedge), 메서드 진입/반환 시 Safepoint 폴링 코드가 삽입되어 있음
3. 각 Java 스레드가 폴링 지점에 도달하면 일시 중단
4. 모든 스레드가 중단된 후 VM Thread가 작업 수행
5. 작업 완료 후 모든 스레드 재개

**JVMTI (JVM Tool Interface)**

JVMTI는 디버거, 프로파일러 등이 JVM 내부를 관찰하고 제어할 수 있는 표준 인터페이스다. IntelliJ 디버거, JProfiler, YourKit 등이 이 인터페이스를 활용한다. 많은 JVMTI 함수가 Safepoint를 필요로 하여 오버헤드가 발생할 수 있다.

**JFR (Java Flight Recorder)**

JFR은 JVM과 애플리케이션의 런타임 데이터를 낮은 오버헤드(1~2%)로 지속 기록하는 프로파일링 메커니즘이다. JDK 11부터 OpenJDK에 통합되었다. Safepoint 편향(Safepoint Bias) 없이 스택 트레이스를 수집할 수 있어 더 정확한 프로파일링이 가능하다.

## 핵심 정리
- HotSpot VM은 Class Loader Subsystem, Runtime Data Area, Execution Engine, Native Interface 4개 서브시스템으로 구성된다
- 실행 엔진의 Tiered Compilation(0~4계층)은 C1의 빠른 컴파일과 C2의 공격적 최적화를 조합하여 스타트업 속도와 최고 성능을 모두 달성한다
- 탈출 분석, 메서드 인라이닝, OSR은 서로 연계되어 동작하며 JIT 최적화의 핵심 축을 형성한다
- Compressed Oops는 32GB 이하 힙에서 객체 참조를 8바이트에서 4바이트로 줄여 메모리 효율을 높인다
- Safepoint는 GC, Deoptimization 등 STW 작업의 전제 조건으로 VM Thread가 조율한다

## 기술적 한계와 보완 전략

| 한계 | 설명 | 보완 전략 |
|------|------|----------|
| JIT 워밍업 시간 | C2 최적화가 발동하기까지 수천 회 호출 필요, 초기 성능 저하 | CDS/AppCDS로 클래스 로딩 단축, GraalVM AOT(Native Image) 사용 |
| STW 일시 정지 | GC와 Safepoint 진입 시 모든 스레드 멈춤 | ZGC/Shenandoah로 일시 정지 최소화, GC 튜닝 |
| Code Cache 고갈 | JIT 생성 코드가 Code Cache 한도 초과 시 컴파일 중단 | `-XX:ReservedCodeCacheSize` 증가, 불필요한 클래스 제거 |
| Safepoint 편향 | JVMTI 기반 프로파일러는 Safepoint 지점만 샘플링하여 편향 발생 | JFR 또는 async-profiler 사용 |
| Deoptimization 오버헤드 | 다형성 코드에서 추론 최적화 무효화 반복 발생 | 핫 경로의 타입 다형성 최소화, 인터페이스 남용 주의 |

## 키워드

- **HotSpot VM**: Oracle/OpenJDK의 표준 JVM 구현체. 실행 빈도가 높은 "핫 코드"를 탐지해 선택적으로 JIT 컴파일하는 방식에서 이름이 유래. Class Loader, Runtime Data Area, Execution Engine, Native Interface의 4대 서브시스템으로 구성된다.
- **실행 엔진 (Execution Engine)**: 바이트코드를 실제로 실행하는 HotSpot의 핵심 구성요소. 템플릿 인터프리터, C1/C2 JIT 컴파일러, GC를 포함하며, 프로파일링 데이터를 기반으로 Tiered Compilation을 수행한다.
- **Tiered Compilation**: Java 8부터 기본 활성화된 5계층(0~4) JIT 컴파일 전략. Tier 0(인터프리터)에서 시작해 Tier 3(C1 + 전체 프로파일링)까지 올라가며 데이터를 수집하고, 충분히 "핫"해지면 Tier 4(C2)의 공격적 최적화를 적용한다.
- **Code Cache**: JIT 컴파일러가 생성한 네이티브 기계어 코드를 저장하는 전용 메모리 영역. 가득 차면 JIT 컴파일이 중단되어 성능이 급락할 수 있다. `-XX:ReservedCodeCacheSize`로 크기를 조절한다.
- **탈출 분석 (Escape Analysis)**: 객체가 생성 메서드 범위 밖으로 탈출하는지 분석하는 C2 최적화 기법. 탈출하지 않는 객체는 힙 대신 스택에 할당(Stack Allocation)하거나, 필드를 레지스터로 분리(Scalar Replacement)하거나, 불필요한 잠금을 제거(Lock Elision)한다.
- **On-Stack Replacement (OSR)**: 이미 인터프리터로 실행 중인 루프를, 루프가 끝나기를 기다리지 않고 실행 도중에 JIT 컴파일된 코드로 교체하는 기법. 루프 역방향 분기(backedge) 횟수가 임계치를 초과할 때 발동한다.
- **Object Header / Compressed Oops**: HotSpot 객체는 Mark Word(잠금 상태, GC 나이, 해시코드)와 Klass Pointer(클래스 메타데이터 포인터)로 이루어진 헤더를 가진다. Compressed Oops는 64비트 JVM에서 힙이 32GB 이하일 때 객체 참조를 8바이트에서 4바이트로 압축하는 기술이다.
- **Safepoint**: JVM이 GC, Deoptimization, 클래스 언로딩 등 Stop-the-World 작업을 위해 모든 Java 스레드를 안전하게 일시 중단시키는 메커니즘. JIT 컴파일된 코드에는 폴링 포인트가 삽입되어 있어 스레드가 이 지점에 도달할 때 중단 요청을 확인한다.

## 참고 자료
- [HotSpot Runtime Overview - OpenJDK](https://openjdk.org/groups/hotspot/docs/RuntimeOverview.html)
- [HotSpot Glossary of Terms - OpenJDK](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html)
- [CompressedOops - HotSpot Wiki - OpenJDK](https://wiki.openjdk.org/display/HotSpot/CompressedOops)
- [The Java HotSpot Performance Engine Architecture - Oracle](https://www.oracle.com/java/technologies/whitepaper.html)
- [Introduction to HotSpot JVM C2 JIT Compiler - Emanuel's Blog](https://eme64.github.io/blog/2024/12/24/Intro-to-C2-Part01.html)
- [Tiered Compilation in JVM - Baeldung](https://www.baeldung.com/jvm-tiered-compilation)
- [Compressed OOPs in the JVM - Baeldung](https://www.baeldung.com/jvm-compressed-oops)
- [Understanding OSR in HotSpot C1 - AdoptOpenJDK/jitwatch](https://github.com/AdoptOpenJDK/jitwatch/wiki/Understanding-the-On-Stack-Replacement-(OSR)-optimisation-in-the-HotSpot-C1-compiler)
