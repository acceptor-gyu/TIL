# CDS, CRaC, GC

## 개요
JVM의 시작 시간(startup time)과 런타임 성능을 좌우하는 세 가지 핵심 기술인 CDS(Class Data Sharing), CRaC(Coordinated Restore at Checkpoint), GC(Garbage Collection)에 대해 학습한다. 클라우드 네이티브 환경에서 빠른 기동과 안정적인 메모리 관리가 중요해지면서 이 기술들의 이해와 활용이 중요해지고 있다.

## 상세 내용

### 1. CDS (Class Data Sharing)

#### 개념과 등장 배경

JVM은 애플리케이션을 시작할 때마다 동일한 클래스들을 파싱하고 메타데이터를 생성한다. 수백 개의 JDK 내장 클래스와 프레임워크 클래스를 매 기동마다 반복 처리하는 것은 낭비다. CDS는 이 메타데이터를 한 번만 생성하여 아카이브 파일에 저장하고, 이후 JVM 기동 시 파일을 메모리에 매핑(mmap)하여 재사용한다.

읽기 전용 아카이브이므로 여러 JVM 프로세스가 동일한 물리 메모리 페이지를 공유할 수 있어 메모리 사용량도 절감된다.

#### Default CDS vs AppCDS

| 구분 | Default CDS | AppCDS (Application CDS) |
|------|------------|--------------------------|
| 대상 | JDK 내장 클래스 | 애플리케이션 + 라이브러리 클래스 포함 |
| JDK | JDK 12+에서 기본 활성화 | JDK 10+, 실용적으로는 JDK 13+ Dynamic CDS |
| 설정 | 별도 설정 불필요 | 아카이브 생성 과정 필요 |

#### Dynamic CDS Archive (JDK 13+)

JDK 13부터 도입된 Dynamic CDS는 클래스 목록을 별도로 생성하는 번거로움 없이 애플리케이션 종료 시 자동으로 아카이브를 생성한다. JDK 21에서는 `-XX:+AutoCreateSharedArchive` 옵션이 추가되어 아카이브 파일이 없거나 다른 JDK 버전으로 생성된 경우 자동으로 재생성한다.

```bash
# 방법 1: 종료 시 아카이브 자동 생성 (JDK 13+)
java -XX:ArchiveClassesAtExit=app.jsa -cp app.jar com.example.Main

# 방법 2: 저장된 아카이브를 사용하여 기동 (기동 시간 단축)
java -XX:SharedArchiveFile=app.jsa -cp app.jar com.example.Main

# 방법 3: Auto-Create (JDK 21+) - 없으면 생성, 있으면 재사용
java -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=app.jsa -cp app.jar com.example.Main
```

#### 아카이브 파일 경로 (Default CDS)

| OS | 경로 |
|----|------|
| Linux/macOS | `$JAVA_HOME/lib/[arch]/server/classes.jsa` |
| Windows | `$JAVA_HOME/bin/server/classes.jsa` |

#### 다중 JVM 인스턴스 간 메모리 공유 효과

아카이브를 mmap으로 읽기 전용 매핑하기 때문에 OS가 동일 물리 페이지를 여러 프로세스에 공유한다. 같은 호스트에서 동일 앱이 여러 인스턴스로 실행되는 환경(예: Kubernetes Pod, 컨테이너)에서 효과가 크다.

#### Project Leyden과의 연관성

Project Leyden은 CDS를 기반으로 AOT(Ahead-of-Time) 캐시 개념을 확장한다. JEP 483(JDK 24)에서 첫 번째 성과물인 "Ahead-of-Time Class Loading & Linking"이 출시되어 Spring PetClinic 기준으로 기동 시간이 최대 40% 단축되었다. CDS가 클래스 파싱 결과를 저장한다면, Leyden의 AOT 캐시는 로드 및 링크까지 완료된 클래스를 저장한다.

---

### 2. CRaC (Coordinated Restore at Checkpoint)

#### 개념

CRaC는 OpenJDK 프로젝트로, 완전히 기동되고 JIT 최적화까지 완료된 JVM 상태를 디스크에 스냅샷으로 저장(Checkpoint)했다가, 이후 그 상태를 그대로 복원(Restore)하여 밀리초 단위의 즉시 기동을 가능하게 한다.

기존의 JVM 기동 흐름은 다음과 같다.

```
JVM 시작 → 클래스 로딩 → 의존성 초기화 → 커넥션 풀 생성 → JIT 워밍업 → 서비스 준비
                                                                  ↑
                                                               수 초~수십 초 소요
```

CRaC를 사용하면 워밍업이 끝난 지점을 스냅샷으로 저장하고, 이후 기동 시 해당 지점부터 재개한다.

#### CRIU 기반 동작 원리

CRaC는 Linux의 CRIU(Checkpoint/Restore In Userspace)를 기반으로 동작한다. CRIU는 실행 중인 프로세스의 메모리 이미지, 파일 디스크립터, 네트워크 상태 등을 파일로 직렬화하고 복원하는 기술이다.

CRaC 지원 JDK:
- BellSoft Liberica JDK with CRaC
- Azul Zulu JDK with CRaC

#### Resource 인터페이스와 콜백

열린 파일, 소켓, DB 커넥션 등은 체크포인트 시점에 정리하고 복원 후 재연결해야 한다. CRaC는 `org.crac.Resource` 인터페이스를 통해 이를 명시적으로 처리하게 한다.

```java
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;

public class DatabaseConnectionManager implements Resource {

    private DataSource dataSource;

    public DatabaseConnectionManager() {
        // 전역 CRaC Context에 이 리소스를 등록
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        // 체크포인트 직전: 열린 커넥션, 소켓, 파일 등을 닫는다
        dataSource.close();
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        // 복원 직후: 리소스를 다시 열고 초기화한다
        dataSource = createDataSource();
    }
}
```

`beforeCheckpoint` 콜백은 등록 순서대로, `afterRestore` 콜백은 역순으로 호출된다. 의존 관계가 있는 리소스는 등록 순서를 고려해야 한다.

#### Spring Boot 3.2+ CRaC 지원

Spring 6.1 / Spring Boot 3.2부터 CRaC를 공식 지원한다. Spring의 Lifecycle 계약과 통합되어, 체크포인트 시 `Lifecycle.stop()`이, 복원 시 `Lifecycle.start()`가 자동으로 호출된다. 소켓, 파일, 스레드 풀 등 Spring이 관리하는 리소스는 대부분 자동 처리된다.

자동 체크포인트 생성 방법:

```bash
# 애플리케이션 시작과 동시에 onRefresh 단계에서 체크포인트 생성
java -Dspring.context.checkpoint=onRefresh -jar app.jar
```

이 방식은 모든 싱글턴 빈이 초기화된 직후, 라이프사이클이 시작되기 전 시점에 체크포인트를 생성한다.

#### 제약 사항

- **OS 의존성**: CRIU는 Linux에서만 동작한다. macOS, Windows 미지원.
- **아키텍처 이식성**: 동일하거나 유사한 OS/CPU 아키텍처에서만 복원 가능.
- **보안**: 스냅샷 파일에 메모리 상의 시크릿(JWT 키, DB 비밀번호 등)이 포함될 수 있다. 암호화 또는 복원 후 시크릿 재로드 전략이 필요하다.
- **외부 리소스**: 열린 파일/소켓은 `Resource` 인터페이스로 명시적 처리 필요.
- **AWS Lambda SnapStart**: CRaC와 동일한 원리를 AWS에서 구현한 것. Lambda 함수의 초기화 완료 후 스냅샷을 저장하여 콜드 스타트를 줄인다.

---

### 3. GC (Garbage Collection)

#### 기본 개념과 객체 생명주기

JVM은 힙 영역의 객체를 GC가 자동으로 해제한다. GC 루트(GC Root: 스택 변수, 정적 필드 등)에서 도달 가능한(Reachable) 객체는 살아있고, 도달 불가능한 객체는 수거 대상이다.

#### Stop-The-World(STW)와 GC 평가 지표

GC가 실행될 때 모든 애플리케이션 스레드를 일시 정지시키는 것을 STW(Stop-The-World)라 한다. GC 성능을 평가하는 세 가지 지표:

| 지표 | 설명 | 트레이드오프 |
|------|------|------------|
| Throughput (처리량) | GC가 아닌 시간의 비율 | 높일수록 STW 길어짐 |
| Latency (지연) | 개별 STW 최대 시간 | 낮출수록 CPU 오버헤드 증가 |
| Footprint (메모리) | 힙 및 GC 메타데이터 사용량 | 줄일수록 GC 빈도 증가 |

이 세 가지는 동시에 최적화할 수 없어 워크로드에 맞는 GC를 선택해야 한다.

#### 힙 영역 구조

```
[Young Generation]               [Old Generation]
┌────────────────────────────┐   ┌─────────────────────────┐
│  Eden  │ Survivor0 │ Survivor1│   │  Tenured (Old) Objects  │
└────────────────────────────┘   └─────────────────────────┘

- Eden: 새로운 객체 할당
- Survivor: Minor GC 생존 객체 이동
- Old: 여러 번 GC를 살아남은 장수 객체
```

Minor GC는 Young 영역만, Major(Full) GC는 전체 힙을 대상으로 한다.

#### 주요 GC 비교

**Serial GC**
- 단일 스레드. 소형 애플리케이션, 클라이언트 환경용.
- `-XX:+UseSerialGC`

**Parallel GC**
- 멀티스레드로 처리량 극대화. 배치 작업에 적합.
- `-XX:+UseParallelGC`

**G1 GC (Garbage-First, JDK 9+ 기본)**
- 힙을 고정 크기 Region으로 나누어 관리. Eden/Survivor/Old를 유연하게 할당.
- 목표 STW 시간을 `-XX:MaxGCPauseMillis`로 설정 가능(기본 200ms).
- 대부분의 애플리케이션에 적합한 범용 GC.

```bash
-XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

**ZGC (Z Garbage Collector, JDK 15+ 프로덕션)**
- 목표: 힙 크기와 무관하게 STW 1ms 이하 유지.
- Colored Pointer와 Load Barrier를 사용해 GC 작업 대부분을 애플리케이션 스레드와 동시에 수행.
- JDK 21에서 Generational ZGC 도입(Young/Old 세대 구분). JDK 23에서 기본 모드.
- 대용량 힙(수십~수백 GB)의 지연 민감 서비스에 적합.

```bash
# JDK 21: Generational ZGC 활성화
-XX:+UseZGC -XX:+ZGenerational
```

**Shenandoah GC**
- Red Hat이 개발, OpenJDK에 통합.
- Root 스캔까지 동시에 수행하여 ZGC보다 더 짧은 STW 가능(목표 10ms 이하).
- ZGC가 없거나 적합하지 않은 환경(Red Hat, OpenJDK 배포판)에서 대안.

```bash
-XX:+UseShenandoahGC
```

#### GC 선택 가이드

```
워크로드 분석
    │
    ├─ 소규모 배치 / 단순 작업
    │       → Serial GC / Parallel GC
    │
    ├─ 일반적인 웹 서비스 (수 GB 힙)
    │       → G1 GC (기본값, 대부분 적합)
    │
    ├─ 지연 민감 서비스 (100ms 이하 응답 요구)
    │       → ZGC (JDK 21+, Generational)
    │       → Shenandoah (Red Hat/OpenJDK 환경)
    │
    └─ 대용량 힙 (수십~수백 GB)
            → ZGC (힙 크기에 무관한 짧은 STW)
```

---

### 4. 세 기술의 시너지

#### 시작 시간 최적화

클라우드 네이티브 환경에서 JVM 기동 시간을 단축하는 세 기술의 역할:

```
기동 시간 단축 전략 (효과 크기 기준)

CRaC          ████████████████████  워밍업 완료 상태 즉시 복원 (밀리초 단위)
CDS/AppCDS    ████████              클래스 메타데이터 캐시 (수백ms 단축)
Project Leyden████████████          AOT 캐시로 로딩+링크까지 캐시 (40% 이상 단축)
```

#### 컨테이너/서버리스 환경에서의 활용

| 기술 | 컨테이너 활용 사례 |
|------|-----------------|
| CDS | Dockerfile에서 아카이브 사전 생성, 이미지에 포함 |
| CRaC | 스냅샷을 컨테이너 이미지 레이어로 저장, 새 인스턴스에 복원 |
| Generational ZGC | 짧은 생명의 요청 객체를 Young 세대에서 빠르게 수거 |

#### CDS + AppCDS Docker 활용 예시

```dockerfile
FROM eclipse-temurin:21-jre AS builder
COPY app.jar /app.jar
# 아카이브 사전 생성 (빌드 시간에 수행)
RUN java -XX:ArchiveClassesAtExit=/app.jsa -jar /app.jar --spring.context.checkpoint=onRefresh || true

FROM eclipse-temurin:21-jre
COPY --from=builder /app.jar /app.jar
COPY --from=builder /app.jsa /app.jsa
# 아카이브를 사용하여 기동
CMD ["java", "-XX:SharedArchiveFile=/app.jsa", "-jar", "/app.jar"]
```

---

## 핵심 정리

- CDS는 클래스 메타데이터를 공유 아카이브로 만들어 클래스 로딩 비용과 메모리 사용량을 줄인다. JDK 12+에서 기본 활성화, AppCDS로 애플리케이션 클래스까지 확장 가능하다.
- CRaC는 워밍업된 JVM 상태를 스냅샷으로 저장했다가 복원하여 밀리초 단위의 즉시 기동을 가능하게 한다. Spring Boot 3.2+에서 공식 지원한다.
- GC는 자동 메모리 관리를 담당하며, JDK 21 기준 G1이 범용 기본값, ZGC(Generational)은 지연 민감 서비스, Shenandoah는 Red Hat 환경에 적합하다.
- 세 기술 모두 클라우드 네이티브 환경에서 빠른 기동과 효율적인 자원 사용을 위한 핵심 수단이다.

## 기술적 한계와 보완 전략

- **CDS**: 클래스 변경 시 아카이브 재생성 필요. 동적으로 로드되는 클래스 커버리지 한계 → Dynamic CDS 또는 Project Leyden AOT 캐시로 보완.
- **CRaC**: Linux 전용(CRIU 의존). 스냅샷에 시크릿 포함 위험 → `Resource` 콜백에서 명시적 정리, 복원 후 시크릿 재로드. 소켓/파일 핸들 상태 직접 관리 필요.
- **GC**: STW로 인한 지연과 처리량 사이의 트레이드오프 → 워크로드별 GC 선택 및 힙 사이징 튜닝으로 완화. Generational ZGC(JDK 21+)로 지연과 처리량 균형 개선.

## 키워드

### Class Data Sharing (CDS)
JVM이 클래스 메타데이터(클래스 구조, 메서드 정보 등)를 파일로 저장하고 이후 기동 시 파일을 메모리에 매핑하여 재사용하는 기술. JDK 12+에서 기본 활성화. 여러 JVM 프로세스가 읽기 전용 아카이브를 공유하여 메모리도 절감한다.

### AppCDS
CDS를 애플리케이션 클래스와 서드파티 라이브러리 클래스까지 확장한 것. `-XX:ArchiveClassesAtExit`로 아카이브를 생성하고 `-XX:SharedArchiveFile`로 사용한다. Dynamic CDS Archive(JDK 13+)로 클래스 목록 생성 단계를 생략할 수 있다.

### CRaC (Coordinated Restore at Checkpoint)
완전히 초기화되고 JIT 최적화까지 완료된 JVM 상태를 체크포인트로 저장하고, 이후 그 상태로 즉시 복원하는 OpenJDK 기술. Spring Boot 3.2+에서 공식 지원. 콜드 스타트 문제를 근본적으로 해결한다.

### CRIU (Checkpoint/Restore In Userspace)
Linux에서 실행 중인 프로세스를 스냅샷으로 저장하고 복원하는 기술. CRaC의 기반 기술. 프로세스의 메모리 상태, 파일 디스크립터, 네트워크 소켓 등을 파일로 직렬화한다.

### Garbage Collection
JVM이 힙 메모리에서 더 이상 참조되지 않는 객체를 자동으로 식별하고 해제하는 메커니즘. GC Root에서 도달 가능한 객체만 살아있고, 나머지는 수거 대상이 된다.

### Stop-The-World (STW)
GC가 힙을 안전하게 처리하기 위해 모든 애플리케이션 스레드를 일시 정지시키는 것. STW 시간이 길수록 응답 지연이 발생한다. ZGC와 Shenandoah는 STW를 최소화하는 것을 목표로 설계되었다.

### G1 GC (Garbage-First GC)
JDK 9부터 기본 GC. 힙을 동일한 크기의 Region으로 나누어 유연하게 Eden/Survivor/Old 역할을 부여한다. `MaxGCPauseMillis`로 목표 STW 시간을 설정할 수 있어 처리량과 지연의 균형을 맞추기 좋다.

### ZGC
JDK 15에서 프로덕션 준비 완료, JDK 21에서 Generational ZGC 도입. Colored Pointer와 Load Barrier를 활용해 힙 크기와 무관하게 STW를 1ms 이하로 유지한다. 지연 민감 서비스와 대용량 힙 환경에 적합하다.

### Project Leyden
CDS를 확장하여 클래스 로딩, 링킹, JIT 프로파일링 결과까지 AOT 캐시에 저장하는 OpenJDK 프로젝트. JEP 483(JDK 24)에서 첫 결과물로 기동 시간 최대 40% 단축을 달성했다.

### Warm-up
JVM이 시작 후 JIT 컴파일러가 핫 메서드를 최적화하고, 커넥션 풀이 채워지고, 캐시가 적재되는 과정. 워밍업이 완료되기 전까지 성능이 저하된다. CRaC는 워밍업이 완료된 상태를 스냅샷으로 저장하여 이 문제를 해결한다.

## 참고 자료
- [JDK 21 Class Data Sharing - Oracle 공식 문서](https://docs.oracle.com/en/java/javase/21/vm/class-data-sharing.html)
- [CDS and AppCDS in HotSpot - Dev.java](https://dev.java/learn/jvm/cds-appcds/)
- [JEP 310: Application Class-Data Sharing](https://openjdk.org/jeps/310)
- [CDS with Spring Framework 6.1 - Spring Blog](https://spring.io/blog/2023/12/04/cds-with-spring-framework-6-1/)
- [Checkpoint and Restore With the JVM - Spring Boot 공식 문서](https://docs.spring.io/spring-boot/reference/packaging/checkpoint-restore.html)
- [JVM Checkpoint Restore - Spring Framework 공식 문서](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
- [What is CRaC? - Azul 공식 문서](https://docs.azul.com/core/crac/crac-introduction)
- [Project Leyden - OpenJDK](https://openjdk.org/projects/leyden/)
- [JEP 483: Ahead-of-Time Class Loading & Linking](https://bell-sw.com/videos/jep-483-ahead-of-time-class-loading-linking-project-leyden-in-jdk-24/)
- [Java GC Performance: G1 vs ZGC vs Shenandoah - Java Code Geeks](https://www.javacodegeeks.com/2025/08/java-gc-performance-g1-vs-zgc-vs-shenandoah.html)
