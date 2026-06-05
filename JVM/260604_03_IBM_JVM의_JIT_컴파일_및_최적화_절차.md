# IBM JVM의 JIT 컴파일 및 최적화 절차

## 개요
IBM JVM(IBM J9 / Eclipse OpenJ9)이 HotSpot, JRockit과 달리 어떤 방식으로 JIT 컴파일과 적응형 최적화를 수행하는지 학습한다. TR(Testarossa) 컴파일러를 중심으로 한 다단계 최적화 레벨, 카운트 기반 호출 빈도 측정, 비동기 컴파일 스레드, AOT/공유 클래스 캐시(SCC)와의 연계 등 OpenJ9 특유의 전략을 정리하고, HotSpot/JRockit과 비교한다.

## 상세 내용

### 1. IBM JVM의 계보 (J9 → Eclipse OpenJ9)

IBM J9 JVM의 뿌리는 1990년대 Object Technology International(OTI)에서 개발한 ENVY/Smalltalk 런타임까지 거슬러 올라간다. IBM이 OTI를 인수한 뒤 J9는 WebSphere Application Server, WebSphere Liberty 등 IBM 미들웨어의 핵심 런타임으로 자리 잡았고, 임베디드 장치부터 엔터프라이즈 서버까지 폭넓은 환경을 지원하도록 설계되었다.

**오픈소스화 경과**

| 시점 | 내용 |
|------|------|
| 2016년 초 | J9 런타임의 비-Java 핵심 컴포넌트를 Eclipse OMR 프로젝트로 오픈소스 공개 |
| 2017년 9월 | JavaOne 2017에서 J9 JVM 전체를 Eclipse OpenJ9로 오픈소스화 발표 |
| 현재 | IBM Semeru Runtime(OpenJDK 기반)의 기본 JVM으로 사용 |

**HotSpot 대비 주요 차별점**

- 기동 시간: AOT+SCC 조합으로 HotSpot 대비 약 50% 빠른 시작
- 메모리 풋프린트: 기동 직후 HotSpot 대비 66% 작고, 안정화 후에도 약 28~50% 작음
- 클라우드·컨테이너 환경에 특화된 설계

---

### 2. Testarossa(TR) JIT 컴파일러 개요

Testarossa(TR)는 IBM이 자체 개발한 JIT 컴파일러로, Eclipse OpenJ9의 핵심 최적화 엔진이다.

**HotSpot C1/C2와의 구조 차이**

| 항목 | HotSpot | OpenJ9(Testarossa) |
|------|---------|---------------------|
| 컴파일러 수 | C1(클라이언트), C2(서버) 분리 | TR 단일 컴파일러 |
| 최적화 제어 | 컴파일러 선택으로 레벨 결정 | 동일 컴파일러 내 최적화 레벨 조정 |
| 피크 성능 | C2가 극한 최적화 수행 | scorching 레벨에서 동등한 수준 |

TR 컴파일러는 단일 컴파일러 내에서 최적화 강도(레벨)를 조절하는 방식으로 동작한다. 바이트코드를 파싱해 내부 IR(Intermediate Representation)로 변환하고, 레벨에 따라 다수의 최적화 패스(pass)를 거쳐 최종 네이티브 코드를 생성한다.

**컴파일 파이프라인 요약**

```
바이트코드 파싱
    → IL(Intermediate Language) 생성
    → 최적화 패스 적용 (레벨에 따라 패스 수 결정)
    → 코드 생성(Code Generation)
    → 네이티브 코드 실행
```

---

### 3. 컴파일 최적화 레벨 (Optimization Levels)

TR 컴파일러는 5단계의 최적화 레벨을 제공한다. 레벨이 높을수록 더 좋은 성능을 내지만 컴파일에 소요되는 CPU 및 메모리 비용도 높아진다.

| 레벨 | 트리거 | 설명 |
|------|--------|------|
| **cold** | 기동 초기 다수 메서드 | 빠른 컴파일 속도 우선. 가능한 많은 메서드를 빠르게 네이티브 코드로 변환해 인터프리터 오버헤드 최소화 |
| **warm** | 인터프리터 호출 임계값 도달 | 기동 완료 후 대부분의 메서드가 컴파일되는 기본 레벨. 성능과 컴파일 비용의 균형점 |
| **hot** | 샘플링 스레드: CPU 1% 초과 | 지속적으로 CPU를 많이 차지하는 메서드에 적용. 더 공격적인 최적화 수행 |
| **veryHot(profiling)** | scorching 전 단계 | 상세 프로파일 데이터 수집 목적. scorching 컴파일의 입력 데이터를 만들기 위한 단계 |
| **scorching** | 샘플링 스레드: CPU 12.5% 초과 | 가장 높은 최적화 강도. escape analysis, partial redundancy elimination 등 고급 최적화 총동원 |

**레벨 전환 흐름**

```
인터프리터 실행
    → 호출 횟수 임계값 도달 → warm 컴파일
    → 샘플링 스레드 감지 (CPU 1%) → hot 컴파일
    → 샘플링 스레드 감지 (CPU 12.5%) → veryHot(profiling) 컴파일 → 프로파일 수집
    → scorching 컴파일 (프로파일 기반 최종 최적화)
```

---

### 4. 핫스팟 탐지 방식 (Invocation Count & Sampling)

OpenJ9는 두 가지 메커니즘을 하이브리드로 운용해 컴파일 대상 메서드를 결정한다.

**인보케이션 카운터 (Invocation Count)**

- JVM은 각 메서드의 호출 횟수를 추적한다.
- 호출 횟수가 사전 정의된 임계값에 도달하면 warm 레벨 JIT 컴파일이 트리거된다.
- `-Xjit:count=0` 설정 시 모든 메서드를 즉시 컴파일 (디버깅·테스트 목적)

**샘플링 스레드 (Sampler Thread)**

- JVM 내부의 전용 스레드가 주기적으로 실행 중인 메서드를 샘플링한다.
- CPU 점유율 1% 초과 메서드 → hot 레벨 재컴파일 예약
- CPU 점유율 12.5% 초과 메서드 → scorching 레벨 재컴파일 예약
- 카운터 방식이 최초 컴파일을 담당하고, 샘플링 방식이 재컴파일(recompilation) 트리거를 담당한다.

**Counting Body / Sampling Body**

OpenJ9 컴파일러 내부에서 컴파일된 메서드 코드는 재컴파일 트리거 방식에 따라 두 종류의 프리-프롤로그(pre-prologue) 형태를 가진다.

- **Counting Body**: 프로파일 컴파일용. 호출 시마다 카운터를 감소시키고 0이 되면 재컴파일을 트리거한다.
- **Sampling Body**: 비-프로파일 컴파일용. 샘플링 스레드, GCR, 명시적 트리거 등 다양한 방식의 재컴파일에 사용된다.

---

### 5. 비동기 컴파일 (Asynchronous Compilation)

OpenJ9의 JIT 컴파일은 기본적으로 **비동기(asynchronous)** 방식으로 수행된다.

**동작 원리**

- JVM은 기본적으로 4개의 전용 컴파일 스레드를 운용한다.
- 컴파일 대상 메서드는 컴파일 큐(compilation queue)에 추가된다.
- 컴파일 스레드가 큐에서 메서드를 꺼내 백그라운드에서 컴파일을 수행한다.
- 컴파일이 완료되기 전까지 애플리케이션 스레드는 인터프리터 모드로 실행을 계속한다.

**장점**

- 애플리케이션 스레드의 일시 정지(STW) 없이 컴파일 수행
- 컴파일 큐 우선순위(priority)를 활용해 중요 메서드를 먼저 처리
- 기동 초기에는 cold 레벨로 빠르게 컴파일해 인터프리터 오버헤드를 줄이면서 애플리케이션 응답성 유지

**HotSpot과의 차이**

HotSpot도 비동기 컴파일을 지원하지만, OpenJ9는 단일 컴파일러(TR)에서 레벨만 조정하는 구조 덕분에 컴파일 스레드 관리가 상대적으로 단순하다.

---

### 6. AOT 컴파일과 공유 클래스 캐시 (SCC)

OpenJ9의 가장 독특한 기능 중 하나는 AOT 컴파일과 SCC(Shared Classes Cache)의 연계다.

**AOT 컴파일 (Ahead-Of-Time Compilation)**

- 런타임 중 JIT 컴파일된 코드를 SCC에 AOT 코드 형태로 저장한다.
- 이후 실행 시 JVM은 저장된 AOT 코드를 즉시 로드해 실행할 수 있다.
- AOT 코드는 `-Xshareclasses` 옵션으로 SCC를 활성화하면 자동으로 함께 활성화된다.
- 저장된 AOT 코드는 이후 실행 중 JIT 컴파일러에 의해 추가로 최적화될 수 있다.

**공유 클래스 캐시 (Shared Classes Cache)**

- 여러 JVM 프로세스가 공유 메모리 영역에 클래스 데이터를 저장하고 공유하는 메커니즘이다.
- 캐시 저장 내용: 부트스트랩 클래스, 애플리케이션 클래스, 클래스 메타데이터, AOT 코드, JIT 컴파일 데이터, GC 힙 크기 힌트
- 대부분의 플랫폼에서 메모리 맵 파일(persistent) 형태로 저장된다.
- 기본적으로 부트스트랩 클래스에 대해서는 클래스 데이터 공유가 활성화되어 있다.

**성능 효과 (공식 벤치마크 기준)**

| 항목 | 효과 |
|------|------|
| 기동 시간 | HotSpot 대비 약 50% 단축 (`-Xquickstart` 조합 시 42% 추가 단축) |
| 메모리 풋프린트 | 기동 직후 HotSpot 대비 66% 감소, 안정화 후 약 50% 감소 |
| 워밍업 시간 | HotSpot의 약 30분 대비 7.5분 수준으로 빠른 피크 성능 도달 |

---

### 7. 주요 최적화 기법

**메서드 인라이닝 (Method Inlining)**

- 호출 비용이 높은 소형 메서드를 호출 지점에 직접 삽입하여 호출 오버헤드를 제거한다.
- 인라이닝 후 추가 최적화(상수 전파, 데드 코드 제거 등)가 연쇄적으로 적용된다.

**이스케이프 분석 (Escape Analysis)**

- 객체가 메서드 또는 스레드 경계 밖으로 "탈출"하는지 분석한다.
- 탈출하지 않는 객체는 힙 대신 스택에 할당(Stack Allocation)하거나 스칼라 값으로 분해(Scalar Replacement)할 수 있다.
- hot/scorching 레벨에서 적용.

**부분 중복 제거 (Partial Redundancy Elimination)**

- 프로그램 일부 경로에서만 중복 연산이 발생하는 경우를 탐지해 최소화한다.
- 루프 불변 코드 이동(Loop Invariant Code Motion)을 포함하는 일반화된 최적화 기법이다.

**루프 최적화**

- 루프 언롤링(Loop Unrolling): 루프 반복 횟수를 줄이고 루프 바디를 복제해 브랜치 오버헤드 감소
- 불변 코드 이동(Loop Invariant Code Motion): 루프 내 변하지 않는 연산을 루프 밖으로 이동

**역최적화 (Deoptimization)**

- 투기적 최적화(speculative optimization)가 런타임 조건 변화로 더 이상 유효하지 않을 때 발동된다.
- 예: 단일 구현 클래스를 가정하고 인라이닝했으나 새로운 서브클래스가 로드된 경우
- 해당 메서드를 인터프리터 모드로 되돌리거나 낮은 최적화 레벨로 재컴파일한다.
- OpenJ9에서는 이를 **Mandatory Recompilation** 범주로 분류하며, JVMTI 바이트코드 수정이나 pre-existence 가정 무효화 시에도 발생한다.

---

### 8. HotSpot / JRockit과의 비교

**컴파일러 구조 비교**

| 항목 | HotSpot | JRockit | OpenJ9(TR) |
|------|---------|---------|------------|
| 컴파일러 구조 | C1/C2 분리 | quick JIT + Optimizer 분리 | TR 단일 다단계 |
| 인터프리터 | 템플릿 인터프리터 | 없음 (즉시 JIT) | 있음 |
| 최적화 레벨 수 | Tiered (4단계) | 2단계 | 5단계 |

**핫스팟 탐지 방식 비교**

| JVM | 방식 |
|-----|------|
| HotSpot | 인터프리터 카운터(호출+백엣지) + 티어드 컴파일 전환 |
| JRockit | 샘플링 스레드 전용 (카운터 없음) |
| OpenJ9 | 인보케이션 카운터 + 샘플링 스레드 하이브리드 |

**트레이드오프 요약**

| 항목 | HotSpot | OpenJ9 |
|------|---------|--------|
| 기동 속도 | 보통 | 빠름 (AOT+SCC 덕분) |
| 워밍업 시간 | ~30분 | ~7.5분 |
| 피크 처리량 | 가장 높음 (5% 우위) | 약간 낮음 |
| 메모리 풋프린트 | 큼 | 작음 (클라우드에 유리) |
| AOT 지원 | AppCDS + GraalVM | 기본 내장(SCC) |

---

## 핵심 정리

- **핵심 포인트 1**: OpenJ9의 TR 컴파일러는 단일 컴파일러 안에서 cold→warm→hot→veryHot→scorching 5단계 최적화 레벨을 운용한다. 레벨 전환은 인보케이션 카운터(초기 컴파일)와 샘플링 스레드(재컴파일)의 하이브리드로 결정된다.
- **핵심 포인트 2**: AOT 컴파일과 SCC는 OpenJ9의 가장 강력한 차별점이다. 첫 번째 실행에서 컴파일된 AOT 코드를 공유 메모리에 저장하고, 이후 모든 JVM 프로세스가 이를 재사용함으로써 기동 시간을 최대 50% 단축하고 메모리를 최대 66% 절감한다.
- **핵심 포인트 3**: OpenJ9의 비동기 컴파일은 4개의 전용 컴파일 스레드로 애플리케이션 스레드와 독립적으로 수행된다. 컴파일 미완료 메서드는 인터프리터가 처리하므로 STW 없이 점진적으로 최적화 수준이 높아진다.

## 기술적 한계와 보완 전략

- **피크 처리량 격차**: HotSpot C2 대비 최대 처리량은 약 5% 낮다. 장시간 CPU 집약적 워크로드에서는 HotSpot이 유리할 수 있다. OpenJ9는 `–Xtune:throughput` 옵션으로 처리량 우선 모드로 전환할 수 있다.
- **scorching 레벨의 2단계 지연**: scorching 컴파일 전에 반드시 veryHot(profiling) 컴파일을 거쳐 프로파일 데이터를 수집해야 한다. 이 과정에서 한 번의 추가 컴파일 비용이 발생한다.
- **SCC 무효화 문제**: 애플리케이션 코드가 변경되면 캐시에 저장된 AOT 코드 일부가 무효화될 수 있다. 배포 시 SCC를 초기화(`-Xshareclasses:reset`)하거나 레이어드 캐시 전략을 사용해야 한다.
- **컨테이너 환경의 SCC 공유 제한**: 컨테이너 격리로 인해 프로세스 간 공유 메모리 사용이 제한될 수 있다. 이를 보완하기 위해 IBM Semeru에서는 SCC 스냅샷을 컨테이너 이미지에 포함시키는 방식을 권장한다.

## 키워드

### Eclipse OpenJ9
IBM J9 JVM을 2017년 Eclipse 재단으로 이관해 오픈소스화한 JVM 구현체. IBM Semeru Runtime의 기본 JVM으로 사용되며, OpenJDK의 HotSpot 대신 탑재할 수 있다. 작은 메모리 풋프린트와 빠른 기동 속도를 핵심 경쟁력으로 한다.

### Testarossa (TR) Compiler
Eclipse OpenJ9에 내장된 JIT 컴파일러 엔진. HotSpot의 C1/C2처럼 컴파일러를 분리하는 대신, 단일 컴파일러 내에서 최적화 레벨(cold~scorching)을 조정하는 설계를 채택했다. 바이트코드를 IR로 변환한 뒤 레벨에 따라 다수의 최적화 패스를 적용해 네이티브 코드를 생성한다.

### Optimization Levels (cold/warm/hot/scorching)
TR 컴파일러가 지원하는 5단계 최적화 레벨. **cold**: 기동 시 빠른 컴파일 우선, **warm**: 기본 레벨, **hot**: CPU 1% 초과 메서드, **veryHot(profiling)**: scorching 전 프로파일 수집, **scorching**: CPU 12.5% 초과 메서드에 최고 수준 최적화 적용. 레벨이 높을수록 컴파일 비용은 크지만 런타임 성능이 향상된다.

### Invocation Count
JVM이 각 메서드의 호출 횟수를 추적하는 카운터. 사전 정의된 임계값(threshold)에 도달하면 warm 레벨 JIT 컴파일이 트리거된다. `-Xjit:count=N`으로 임계값을 직접 설정할 수 있으며, 0으로 설정 시 모든 메서드를 즉시 컴파일한다.

### Sampling Thread
OpenJ9 JVM 내부에서 실행 중인 메서드의 CPU 점유율을 주기적으로 측정하는 전용 스레드. 측정 결과를 기반으로 hot(1% 초과) 또는 scorching(12.5% 초과) 재컴파일을 트리거한다. 초기 컴파일을 담당하는 인보케이션 카운터와 함께 하이브리드 방식으로 핫 메서드를 탐지한다.

### Asynchronous Compilation
JIT 컴파일을 애플리케이션 스레드와 분리된 전용 컴파일 스레드(기본 4개)에서 백그라운드로 수행하는 방식. 컴파일 큐에 등록된 메서드는 컴파일 완료 전까지 인터프리터가 처리하며, 컴파일 완료 후 다음 호출부터 네이티브 코드가 실행된다. 애플리케이션 스레드의 STW 없이 점진적 최적화가 가능하다.

### AOT Compilation
OpenJ9에서 런타임 중 JIT 컴파일된 메서드의 네이티브 코드를 SCC에 저장하는 기능. 이후 JVM 실행 시 인터프리터 실행 없이 저장된 AOT 코드를 즉시 로드해 실행할 수 있어 기동 속도가 크게 향상된다. `-Xshareclasses` 옵션으로 SCC를 활성화하면 AOT도 자동으로 활성화된다.

### Shared Classes Cache (SCC)
여러 JVM 프로세스가 공유 메모리 영역을 통해 클래스 데이터, 메타데이터, AOT 코드를 공유하는 OpenJ9의 핵심 기능. 메모리 맵 파일로 영속적으로 저장되므로 JVM이 종료된 후에도 캐시가 유지된다. 동일 머신의 여러 JVM 프로세스가 하나의 SCC를 공유함으로써 메모리 중복을 줄이고 기동 시간을 단축한다.

### Eclipse OMR
IBM이 2016년 Eclipse 재단에 기증한 언어 독립적(language-agnostic) 런타임 컴포넌트 라이브러리. 플랫폼 이식 레이어, 스레드 라이브러리, 진단 서비스, JIT 컴파일러(TR), 가비지 컬렉터(GC) 등을 포함한다. OpenJ9는 OMR을 하위 레이어로 사용하고 그 위에 Java 전용 시맨틱을 추가하는 구조다. Ruby, Python 등 다른 언어 런타임도 OMR을 기반으로 구축할 수 있다.

### Deoptimization
투기적 최적화의 가정이 무효화될 때 컴파일된 메서드를 인터프리터 모드로 되돌리거나 낮은 최적화 레벨로 재컴파일하는 메커니즘. OpenJ9에서는 이를 **Mandatory Recompilation**으로 분류하며, 단일 구현 클래스 가정 하에 인라이닝된 메서드에 새로운 서브클래스가 로드되거나, JVMTI 에이전트가 바이트코드를 수정하는 경우 발동된다.

## 참고 자료
- [Eclipse OpenJ9 JIT Compiler 공식 문서](https://eclipse.dev/openj9/docs/jit/)
- [Eclipse OpenJ9 AOT Compiler 공식 문서](https://eclipse.dev/openj9/docs/aot/)
- [Eclipse OpenJ9 Shared Classes Cache 공식 문서](https://eclipse.dev/openj9/docs/shrc/)
- [OpenJ9 Recompilation 내부 구조 (GitHub)](https://github.com/eclipse-openj9/openj9/blob/master/doc/compiler/runtime/Recompilation.md)
- [Eclipse OpenJ9 Performance](https://eclipse.dev/openj9/performance/)
- [Eclipse OpenJ9 Newsletter (The Eclipse Foundation)](https://www.eclipse.org/community/eclipse_newsletter/2018/april/openj9.php)
- [HotSpot vs OpenJ9 Performance Comparison (BellSoft)](https://bell-sw.com/announcements/2022/06/28/hotspot-vs-openj9-performance-comparison/)
- [What is Eclipse OMR?](https://eclipse.dev/omr/starter/whatisomr.html)
- [-Xjit / -Xnojit 옵션 레퍼런스](https://eclipse.dev/openj9/docs/xjit/)
