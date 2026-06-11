# 자바의 Runtime Data Area 구성

## 개요
JVM이 프로그램을 실행하기 위해 운영체제로부터 할당받아 사용하는 메모리 영역인 Runtime Data Area의 구성 요소를 정리한다.

## 상세 내용

### Runtime Data Area란

Runtime Data Area는 JVM이 자바 프로그램을 실행하는 동안 사용하는 메모리 영역의 총칭이다. JVM은 시작 시 운영체제로부터 메모리를 할당받아 이 영역들을 구성하며, 각 영역은 생명주기와 접근 범위에 따라 두 가지로 분류된다.

**스레드 공유 영역 (Thread-shared)**
- JVM 시작 시 생성되고, JVM 종료 시 소멸된다.
- 모든 스레드가 동시에 접근 가능하므로 동시성 문제가 발생할 수 있다.
- Method Area, Heap이 해당된다.

**스레드별 독립 영역 (Per-thread)**
- 스레드가 생성될 때 만들어지고, 스레드가 종료되면 소멸된다.
- 각 스레드만 접근할 수 있으므로 동시성 문제가 없다.
- JVM Stack, PC Register, Native Method Stack이 해당된다.

```
┌────────────────────────────────────────────┐
│          Runtime Data Area                 │
│                                            │
│  ┌──────────────┐  ┌────────────────────┐  │
│  │  Method Area │  │       Heap         │  │
│  │  (공유)       │  │      (공유)         │  │
│  └──────────────┘  └────────────────────┘  │
│                                            │
│  Thread 1          Thread 2    Thread N    │
│  ┌──────────┐      ┌────────┐  ┌────────┐  │
│  │JVM Stack │      │JVM     │  │JVM     │  │
│  │PC Reg    │      │Stack   │  │Stack   │  │
│  │Native    │      │PC Reg  │  │PC Reg  │  │
│  │Method    │      │Native  │  │Native  │  │
│  │Stack     │      │Stack   │  │Stack   │  │
│  └──────────┘      └────────┘  └────────┘  │
└────────────────────────────────────────────┘
```

---

### 메서드 영역 (Method Area)

메서드 영역은 JVM 명세(JVMS §2.5.4)에서 정의하는 공유 메모리 영역으로, 로드된 클래스와 인터페이스에 대한 메타데이터를 저장한다.

**저장 내용**
- 클래스와 인터페이스 이름, 접근 제어자, 부모 클래스/인터페이스 정보
- 필드(Field) 이름, 타입, 접근 제어자
- 메서드 이름, 파라미터 타입, 반환 타입, 바이트코드
- `static` 변수 (클래스 변수)
- Runtime Constant Pool (런타임 상수 풀)

**Runtime Constant Pool**

각 클래스/인터페이스마다 존재하는 상수 풀 테이블의 런타임 표현이다. 컴파일 타임에 알 수 있는 리터럴 상수(문자열, 숫자)와 런타임에 해석(resolve)해야 하는 메서드/필드 참조를 포함한다. JVM은 실제 메모리 주소가 필요한 시점에 심볼릭 참조를 직접 참조로 교체한다.

**Java 7 PermGen과 Java 8 Metaspace의 차이**

Java 8 이전에는 메서드 영역의 구현체가 PermGen(Permanent Generation)이었다.

| 구분 | PermGen (Java 7 이하) | Metaspace (Java 8 이상) |
|---|---|---|
| 메모리 위치 | Java Heap의 일부 | 네이티브 메모리 (OS 메모리) |
| 기본 최대 크기 | 32비트 JVM: 64MB, 64비트: 82MB | 기본값 무제한 (OS 메모리가 한계) |
| 크기 자동 증가 | 불가 (고정 크기) | 기본적으로 자동 증가 |
| 조정 옵션 | `-XX:PermSize`, `-XX:MaxPermSize` | `-XX:MetaspaceSize`, `-XX:MaxMetaspaceSize` |
| GC 연동 | Heap GC와 함께 수행 | 메타데이터 사용량이 임계치 도달 시 자동 정리 |
| OOM 원인 | 클래스 과다 로딩, 문자열 리터럴 과다 | 클래스 과다 로딩 (크기 제한 설정 시) |

PermGen은 힙과 같은 공간을 공유하기 때문에 힙 메모리와 경쟁 관계였다. Metaspace는 네이티브 메모리를 사용하므로 이 문제가 해소되었다. 다만 `MaxMetaspaceSize`를 설정하지 않으면 메모리가 무한정 증가할 수 있어 운영 환경에서는 반드시 상한선을 지정해야 한다.

---

### 힙 영역 (Heap)

힙은 자바 프로그램 실행 중 생성되는 모든 객체 인스턴스와 배열이 저장되는 공유 메모리 영역이다(JVMS §2.5.3).

**저장 내용**
- `new` 키워드로 생성된 모든 클래스 인스턴스
- 배열 (기본형 배열, 참조형 배열 모두)
- 실제 객체 데이터 (인스턴스 변수)

**Young / Old Generation 구분**

HotSpot JVM은 GC 효율을 위해 힙을 세대별로 구분한다.

```
┌──────────────────────────────────────────────┐
│                    Heap                      │
│  ┌──────────────────────┐  ┌──────────────┐  │
│  │    Young Generation  │  │     Old      │  │
│  │  ┌───────┬─────────┐ │  │  Generation  │  │
│  │  │ Eden  │Survivor │ │  │  (Tenured)   │  │
│  │  │       │  S0, S1  │ │  │              │  │
│  │  └───────┴─────────┘ │  └──────────────┘  │
│  └──────────────────────┘                    │
└──────────────────────────────────────────────┘
```

- **Eden**: 객체가 처음 생성되는 공간. Minor GC 시 살아남은 객체는 Survivor 영역으로 이동한다.
- **Survivor (S0, S1)**: Minor GC를 여러 번 살아남은 객체가 머무는 공간. 두 영역을 번갈아 가며 사용한다.
- **Old Generation (Tenured)**: 오랫동안 살아남은 객체가 승격(Promotion)되어 저장되는 공간. Major GC (Full GC)의 대상이다.

힙이 가득 차면 GC가 발생하고, GC 후에도 공간이 부족하면 `OutOfMemoryError: Java heap space`가 발생한다.

---

### 스택 영역 (JVM Stack)

JVM Stack은 스레드별로 독립 생성되는 영역으로, 메서드 호출과 복귀에 관한 모든 정보를 Stack Frame 단위로 관리한다(JVMS §2.5.2).

**Stack Frame이란**

메서드가 호출될 때마다 새로운 Stack Frame이 JVM Stack에 push되고, 메서드 실행이 완료(정상 반환 또는 예외 발생)되면 해당 프레임이 pop된다.

Stack Frame은 세 가지 구성 요소로 이루어진다.

**1. Local Variable Array (지역 변수 배열)**

0-based 인덱스의 슬롯 배열로 구성된다. 각 슬롯은 4바이트이며, 인스턴스 메서드의 경우 인덱스 0에는 항상 `this` 참조가 저장된다.

| 타입 | 슬롯 수 | 크기 |
|---|---|---|
| `int`, `float`, `reference`, `returnAddress` | 1 | 4 bytes |
| `long`, `double` | 2 (연속된 슬롯) | 8 bytes |
| `byte`, `short`, `char` | 1 (int로 변환 후 저장) | 4 bytes |

**2. Operand Stack (피연산자 스택)**

JVM이 연산을 수행하는 작업 공간이다. 인덱스로 접근하는 Local Variable Array와 달리, push/pop 명령어로만 접근한다. 예를 들어 `a - b`를 계산할 때 바이트코드 수준에서는 다음 순서로 동작한다.

```
iload_1   // a를 Operand Stack에 push
iload_2   // b를 Operand Stack에 push
isub      // 두 값을 pop하여 뺄셈 후 결과를 push
istore_3  // 결과를 Local Variable Array에 저장
```

**3. Frame Data (프레임 데이터)**

상수 풀 참조 해석, 정상 반환, 예외 처리에 필요한 데이터를 포함한다.
- 현재 클래스의 Runtime Constant Pool에 대한 참조
- 메서드 정상 완료 시 반환 주소
- 예외 발생 시 처리할 catch 블록 정보(Exception Table)

**StackOverflowError 발생 조건**

스레드의 JVM Stack 크기는 `-Xss` 옵션으로 설정하며, 기본값은 JVM 구현체와 플랫폼에 따라 다르다(보통 512KB~1MB). 재귀 호출이 무한정 계속되거나, 호출 깊이가 너무 깊어 Stack의 허용 크기를 초과하면 `StackOverflowError`가 발생한다.

```java
// StackOverflowError 예시: 종료 조건 없는 재귀
public void infiniteRecursion() {
    infiniteRecursion(); // 매 호출마다 새 Stack Frame push → 결국 Stack 초과
}
```

---

### PC 레지스터 (Program Counter Register)

PC Register는 스레드별로 독립 생성되는 영역으로, 현재 스레드가 실행 중인 JVM 명령어의 주소를 보관한다(JVMS §2.5.1).

**동작 방식**
- JVM이 바이트코드를 하나씩 실행할 때마다 PC Register의 값이 다음 명령어 주소로 갱신된다.
- 멀티 스레드 환경에서 각 스레드가 독립적인 PC Register를 가지기 때문에, 컨텍스트 스위칭 후에도 자신이 실행하던 위치를 정확히 기억할 수 있다.

**네이티브 메서드 실행 시의 동작**

현재 실행 중인 메서드가 네이티브 메서드(JNI를 통해 C/C++ 코드를 실행)인 경우, PC Register의 값은 정의되지 않은 상태(undefined)가 된다. 이는 JVM 명세(JVMS)에서 명시적으로 정의한 동작이다. 네이티브 메서드의 실행 주소 추적은 Native Method Stack이 담당한다.

---

### 네이티브 메서드 스택 (Native Method Stack)

Native Method Stack은 자바가 아닌 언어(주로 C, C++)로 작성된 네이티브 메서드를 실행할 때 사용하는 스레드별 독립 영역이다(JVMS §2.5.6). "C 스택"이라고도 불린다.

**JNI와의 관계**

자바 코드에서 `native` 키워드로 선언된 메서드를 호출하면, JVM은 JNI(Java Native Interface)를 통해 해당 네이티브 코드로 제어를 넘긴다. 이 시점에 JVM Stack 대신 Native Method Stack이 사용된다.

```java
// 네이티브 메서드 선언 예시
public class FileSystem {
    public native int open(String path, int flags);
}
```

**예외**

JVM Stack과 동일하게, 깊이 초과 시 `StackOverflowError`, 메모리 부족 시 `OutOfMemoryError`가 발생할 수 있다. 네이티브 메서드 스택을 지원하지 않는 JVM 구현체도 존재하며, 이 경우 네이티브 메서드 호출 시 `UnsatisfiedLinkError`가 발생한다.

---

### 영역별 비교 정리

| 영역 | 공유 여부 | 생명주기 | 저장 데이터 | 발생 가능 에러 |
|---|---|---|---|---|
| Method Area | 전체 공유 | JVM 시작~종료 | 클래스 메타데이터, static 변수, Runtime Constant Pool | `OutOfMemoryError` |
| Heap | 전체 공유 | JVM 시작~종료 | 객체 인스턴스, 배열 | `OutOfMemoryError` |
| JVM Stack | 스레드별 독립 | 스레드 생성~종료 | Stack Frame (지역 변수, 피연산자 스택, 프레임 데이터) | `StackOverflowError`, `OutOfMemoryError` |
| PC Register | 스레드별 독립 | 스레드 생성~종료 | 현재 실행 중인 명령어 주소 | 없음 |
| Native Method Stack | 스레드별 독립 | 스레드 생성~종료 | 네이티브 메서드 호출 정보 | `StackOverflowError`, `OutOfMemoryError` |

---

## 핵심 정리
- Runtime Data Area는 스레드 공유 영역(Method Area, Heap)과 스레드별 영역(Stack, PC Register, Native Method Stack)으로 나뉜다
- 객체 인스턴스는 Heap에, 클래스 메타데이터는 Method Area에 저장된다
- JVM Stack은 메서드 호출마다 Stack Frame을 push하고, 완료 시 pop한다. Stack Frame은 Local Variable Array, Operand Stack, Frame Data로 구성된다
- Java 8부터 Method Area의 구현체가 PermGen(힙 내)에서 Metaspace(네이티브 메모리)로 교체되어, PermGen OOM 문제가 해소되고 메모리가 동적으로 증가할 수 있게 되었다
- 각 영역은 메모리 부족 시 `OutOfMemoryError` 또는 `StackOverflowError`를 발생시킨다

## 기술적 한계와 보완 전략

**Metaspace 무제한 증가 문제**

Java 8 이후 Metaspace는 기본적으로 크기 제한이 없어 네이티브 메모리를 무한정 소비할 수 있다. 특히 동적으로 클래스를 생성하는 프레임워크(리플렉션, 코드 생성 라이브러리)를 많이 사용하는 환경에서 문제가 된다. 운영 환경에서는 반드시 `-XX:MaxMetaspaceSize`를 설정하고, 클래스 로더 누수(ClassLoader leak)를 모니터링해야 한다.

**힙 크기 튜닝**

`-Xms`(초기 힙 크기)와 `-Xmx`(최대 힙 크기)를 동일하게 설정하면 JVM이 힙 크기를 늘리고 줄이는 과정에서 발생하는 오버헤드를 제거할 수 있다. 컨테이너 환경에서는 `-XX:+UseContainerSupport`를 활성화하거나, `-XX:MaxRAMPercentage`로 컨테이너 메모리 대비 비율을 설정하는 것이 권장된다.

**Stack 크기 조정**

스레드 수가 매우 많은 서버 환경에서는 `-Xss`를 줄여 전체 스레드 스택 메모리를 절약할 수 있다. 단, 너무 작게 설정하면 StackOverflowError가 발생하므로 적절한 값을 프로파일링을 통해 결정해야 한다.

## 키워드

- **Runtime Data Area**: JVM이 프로그램 실행을 위해 운영체제로부터 할당받아 사용하는 메모리 영역의 총칭. 스레드 공유 영역과 스레드별 독립 영역으로 구분된다.
- **Heap**: 모든 객체 인스턴스와 배열이 저장되는 공유 메모리 영역. Young Generation(Eden, Survivor)과 Old Generation으로 세분화되며, GC의 주 대상이다.
- **Method Area**: 클래스 메타데이터, static 변수, Runtime Constant Pool을 저장하는 공유 영역. Java 8부터 Metaspace로 구현된다.
- **Metaspace**: Java 8에서 PermGen을 대체한 Method Area의 구현체. 힙이 아닌 네이티브 메모리에 위치하며 기본적으로 크기가 자동 증가한다.
- **JVM Stack**: 스레드별로 독립 생성되며, 메서드 호출 시 Stack Frame을 push하고 완료 시 pop한다.
- **Stack Frame**: JVM Stack의 기본 단위. 메서드 호출마다 생성되며, Local Variable Array, Operand Stack, Frame Data로 구성된다.
- **PC Register**: 스레드별로 현재 실행 중인 JVM 명령어의 주소를 보관하는 영역. 네이티브 메서드 실행 시에는 값이 undefined이다.
- **Native Method Stack**: JNI를 통해 C/C++ 등의 네이티브 메서드를 실행할 때 사용하는 스레드별 독립 영역.
- **OutOfMemoryError**: Heap, Method Area(Metaspace), Native Method Stack 등에서 메모리를 더 이상 할당할 수 없을 때 JVM이 발생시키는 에러.
- **StackOverflowError**: JVM Stack 또는 Native Method Stack의 허용 깊이를 초과했을 때(주로 무한 재귀) JVM이 발생시키는 에러.

## 참고 자료
- [Chapter 2. The Structure of the Java Virtual Machine - Java SE 8](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-2.html)
- [JVM Runtime Data Areas - herongyang.com](https://www.herongyang.com/JVM/Data-Area-JVM-Runtime-Data-Areas.html)
- [Java Virtual Machine (JVM) Stack Area - GeeksforGeeks](https://www.geeksforgeeks.org/java/java-virtual-machine-jvm-stack-area/)
- [MetaSpace in Java 8 with Examples - GeeksforGeeks](https://www.geeksforgeeks.org/java/metaspace-in-java-8-with-examples/)
- [Permgen vs Metaspace in Java - Baeldung](https://www.baeldung.com/java-permgen-metaspace)
