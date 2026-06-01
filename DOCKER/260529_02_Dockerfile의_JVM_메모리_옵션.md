# Dockerfile의 JVM 메모리 옵션

## 개요
컨테이너 환경에서 JVM이 호스트의 메모리가 아닌 컨테이너에 할당된 메모리를 올바르게 인식하도록 설정하는 방법과, Dockerfile에서 JVM 메모리 옵션을 지정하는 전략을 정리한다.

## 상세 내용

### 1. 왜 컨테이너에서 JVM 메모리 설정이 중요한가

JVM은 기본적으로 실행 환경의 전체 메모리를 기준으로 힙 크기를 결정한다. Docker 컨테이너는 cgroup을 통해 메모리를 격리하지만, 구버전 JVM은 cgroup 한계를 인식하지 못하고 **호스트 전체 메모리**를 기준으로 힙을 설정했다.

**문제 상황 예시**
- 호스트: 32GB RAM
- 컨테이너 limit: 512MB
- JVM 기본 힙(호스트 기준 25%): 8GB → 컨테이너 limit 초과 → **OOMKilled (exit code 137)**

컨테이너가 종료될 때 exit code 137이 나타나면 Kubernetes/Docker가 메모리 초과로 프로세스를 강제 종료한 것이다.

---

### 2. 컨테이너 메모리 인식의 발전 (JDK 버전별)

| JDK 버전 | 컨테이너 메모리 인식 | 비고 |
|---|---|---|
| 8u131 미만 | 호스트 전체 메모리 기준 | cgroup 미인식 |
| 8u131 ~ 8u190 | `-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap` 필요 | 실험적 플래그 |
| **8u191+ / 10+** | `-XX:+UseContainerSupport` 기본 활성화 | cgroup v1/v2 자동 인식 |
| **Java 17+** | cgroup v2 완전 지원 | Red Hat OpenJDK 개선 포함 |

> **주의**: 8u131~8u242 구간 일부 배포판에서는 `UseContainerSupport`가 기본 비활성화 상태이므로 명시적으로 `-XX:+UseContainerSupport`를 추가해야 한다.
>
> 실험적 플래그였던 `UseCGroupMemoryLimitForHeap`은 현재 deprecated 상태이므로 사용을 중단해야 한다.

---

### 3. 주요 JVM 메모리 옵션

#### 3-1. 고정 힙 설정

```
-Xms<size>   # 초기 힙 크기 (Initial Heap)
-Xmx<size>   # 최대 힙 크기 (Max Heap)
```

**예시**
```
-Xms256m -Xmx512m
```

- 장점: 명확하고 예측 가능
- 단점: 컨테이너 limit이 바뀌면 매번 수동 조정이 필요하고, dev/prod 환경별 값이 달라져 이식성이 낮다

#### 3-2. 비율 기반 설정 (컨테이너 환경 권장)

| 옵션 | 기본값 | 적용 조건 |
|---|---|---|
| `-XX:InitialRAMPercentage` | 1.5625% | 초기 힙 비율 |
| `-XX:MaxRAMPercentage` | **25.0%** | 최대 힙 비율 (컨테이너 메모리 > ~250MB) |
| `-XX:MinRAMPercentage` | 50.0% | 최대 힙 비율 (컨테이너 메모리 ≤ ~250MB) |

> `MaxRAMPercentage` 기본값이 25%이므로, 1GB 컨테이너에서는 힙이 250MB만 할당된다. 실제 서비스에서는 명시적으로 값을 높여야 한다.

**권장 설정 (Spring Boot 기준)**
```
-XX:InitialRAMPercentage=50.0
-XX:MaxRAMPercentage=75.0
```

컨테이너 메모리의 75%를 힙으로 사용하고, 나머지 25%는 비힙(Metaspace, Code Cache, Thread Stack 등)을 위해 남겨둔다.

#### 3-3. 메타스페이스 제한

```
-XX:MaxMetaspaceSize=256m
```

Metaspace는 클래스 메타데이터를 저장하는 비힙 영역으로 기본적으로 무제한이다. 클래스가 동적으로 많이 로딩되는 환경(예: Groovy DSL, Hibernate, Reflection 다수 사용)에서는 무제한 증가를 막기 위해 명시적 상한을 설정해야 한다.

#### 3-4. 힙 외(Off-Heap) 메모리 구성 요소

`-Xmx`는 힙만 제한한다. JVM 전체 메모리는 다음 항목의 합이다:

```
Total JVM Memory = Heap + Metaspace + Code Cache + Thread Stacks + Direct Buffers + JVM Overhead
```

| 영역 | 일반적인 크기 | 설명 |
|---|---|---|
| Heap | `-Xmx` 설정값 | 객체 할당 영역 |
| Metaspace | 100~200MB | 클래스 메타데이터 |
| Code Cache | 50~240MB | JIT 컴파일된 코드 |
| Thread Stack | 스레드 수 × ~1MB | 각 스레드의 스택 |
| Direct Buffers | 가변 | Netty, gRPC 등 I/O |
| JVM Overhead | ~50MB | GC, JVM 내부 |

컨테이너 limit은 이 모든 항목의 합을 수용할 수 있어야 한다.

---

### 4. Dockerfile에서 옵션 지정 방법

#### 4-1. ENV + ENTRYPOINT exec form (권장)

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/app.jar app.jar

ENV JAVA_OPTS="-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:MaxMetaspaceSize=256m"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

`exec`를 사용해 Java 프로세스가 PID 1이 되도록 해야 SIGTERM이 JVM으로 직접 전달된다.

#### 4-2. ENTRYPOINT exec form 직접 지정

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/app.jar app.jar

ENTRYPOINT [ \
  "java", \
  "-XX:InitialRAMPercentage=50.0", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:MaxMetaspaceSize=256m", \
  "-jar", "app.jar" \
]
```

JSON 배열 형식(exec form)은 쉘을 거치지 않아 Java 프로세스가 직접 PID 1이 된다.

#### 4-3. 쉘 폼 vs exec 폼 차이

| 구분 | 쉘 폼 (`ENTRYPOINT java ...`) | exec 폼 (`ENTRYPOINT ["java", ...]`) |
|---|---|---|
| PID 1 | `/bin/sh` | `java` 프로세스 |
| SIGTERM 전달 | 쉘이 가로채거나 무시 가능 | JVM 직접 수신 |
| 환경 변수 치환 | 지원 (`$VAR`) | 미지원 (exec form 내에서) |
| Graceful Shutdown | 불안정 | 안정적 |

> Kubernetes에서 Pod가 종료될 때 SIGTERM을 보내고 30초 후 SIGKILL을 보낸다. exec form을 사용해야 JVM이 SIGTERM을 받아 graceful shutdown(커넥션 정리, 진행 중인 요청 완료)을 수행할 수 있다.

---

### 5. 고정값(-Xmx) vs 비율(MaxRAMPercentage) 선택 기준

| 기준 | `-Xmx` 고정값 | `MaxRAMPercentage` 비율 |
|---|---|---|
| 예측 가능성 | 높음 | 컨테이너 limit에 따라 변동 |
| 이식성 (dev/prod) | 낮음 (환경별 수동 조정) | 높음 (limit만 바꾸면 자동 조정) |
| Kubernetes HPA | 불리 (limit 변경 시 재배포 필요) | 유리 |
| 소규모 서비스 | 적합 | 과도할 수 있음 |

**권장**: 대부분의 컨테이너 환경에서는 `MaxRAMPercentage`를 사용하고, 성능에 민감한 워크로드에서만 고정값을 사용한다.

---

### 6. 메모리 limit 설정과의 정합성

#### Docker 실행 시
```bash
docker run -m 1g \
  -e JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
  myapp:latest
```
컨테이너 메모리 1GB → 힙 최대 750MB → 비힙 ~250MB 여유

#### Kubernetes 리소스 설정 예시
```yaml
resources:
  requests:
    memory: "1Gi"
  limits:
    memory: "1Gi"
```

> Kubernetes에서 `requests == limits`로 설정하면 QoS class가 `Guaranteed`가 되어 메모리 경합 시 OOMKill 우선순위에서 가장 안전하다.

**계산 공식 (안전 마진 포함)**

```
컨테이너 limit = (Xmx 또는 MaxRAMPercentage 환산값) + 비힙 예상치 + 안전 마진(10~20%)
```

예: Xmx=512MB + 비힙 200MB + 안전 마진 10% = 약 **790MB → 1GB limit 설정**

---

## 핵심 정리
- JDK 8u191+/10+부터 `UseContainerSupport`가 기본 활성화되어 JVM이 cgroup 메모리 limit을 자동 인식한다
- `MaxRAMPercentage` 기본값은 25%이므로, 실제 서비스에서는 명시적으로 높여야 한다 (일반적으로 70~75% 권장)
- Dockerfile의 ENTRYPOINT는 **exec form**을 사용해 JVM이 PID 1이 되도록 해야 SIGTERM을 올바르게 처리한다
- 힙(`-Xmx`) 외에도 Metaspace, Code Cache, Thread Stack, Direct Buffer 등 비힙 메모리까지 합산해 컨테이너 limit을 설정해야 OOMKilled를 방지할 수 있다

## 기술적 한계와 보완 전략
- 비율 설정만으로는 비힙 메모리 폭증(스레드 수 급증, 네이티브 메모리 누수)을 막기 어려움 → NMT(Native Memory Tracking)로 모니터링
  ```
  -XX:NativeMemoryTracking=summary
  # 실행 중 스냅샷 확인
  jcmd <pid> VM.native_memory summary
  ```
- 컨테이너 limit과 JVM 설정의 불일치 시 조용히 성능 저하 또는 OOMKill 발생 → 표준화된 베이스 이미지와 엔트리포인트 스크립트로 통제
- Kubernetes HPA(Horizontal Pod Autoscaler) 사용 시 `MaxRAMPercentage` 기반 설정이 limit 변경에 자동 적응하므로 유리하다

## 키워드

**UseContainerSupport**
JDK 8u191+/10+부터 기본 활성화되는 JVM 플래그. cgroup v1/v2에서 메모리·CPU limit을 읽어 JVM 자원 설정에 반영한다. 이 플래그 덕분에 JVM이 호스트 전체 메모리가 아닌 컨테이너에 할당된 메모리를 기준으로 힙을 설정한다.

**MaxRAMPercentage**
컨테이너(또는 호스트) 메모리 대비 최대 힙 크기를 비율(%)로 지정하는 JVM 옵션. 기본값은 25.0%이며, `UseContainerSupport`와 함께 동작한다. `-Xmx` 고정값 대신 이 옵션을 사용하면 컨테이너 메모리 limit이 바뀌어도 힙 비율이 자동 조정된다.

**Xmx**
JVM 최대 힙 크기를 바이트 단위로 직접 지정하는 옵션(`-Xmx512m`, `-Xmx2g`). 힙 영역만 제한하며 Metaspace, Thread Stack 등 비힙 영역은 별도로 제어해야 한다.

**cgroup**
Linux 커널의 Control Groups 기능. 프로세스 그룹에 CPU, 메모리, I/O 등 자원 사용량 한계를 설정한다. Docker/Kubernetes는 cgroup을 이용해 컨테이너별 메모리 limit을 강제하며, JVM의 `UseContainerSupport`는 cgroup에서 limit 값을 읽어 자원을 조정한다.

**OOMKilled**
Kubernetes/Docker가 컨테이너가 메모리 limit을 초과했을 때 해당 컨테이너를 강제 종료하는 동작. exit code 137로 나타난다. 힙뿐 아니라 비힙 메모리까지 포함한 JVM 전체 메모리가 컨테이너 limit을 초과하면 발생한다.

**JAVA_OPTS**
Java 애플리케이션 실행 시 JVM에 전달할 옵션을 담는 환경 변수. Dockerfile의 `ENV`로 정의하고 `ENTRYPOINT`에서 `$JAVA_OPTS`로 참조한다. Spring Boot의 경우 `JAVA_TOOL_OPTIONS` 환경 변수도 동일 역할을 하며, JVM이 시작할 때 자동으로 읽는다.

**ENTRYPOINT**
Dockerfile 명령어로, 컨테이너가 시작될 때 실행할 기본 커맨드를 지정한다. exec form(`["java", "-jar", "app.jar"]`)을 사용하면 지정한 프로세스가 PID 1이 되어 SIGTERM 등 OS 시그널을 직접 수신하고 graceful shutdown을 수행할 수 있다.

**MaxMetaspaceSize**
JVM Metaspace(클래스 메타데이터 저장 영역)의 최대 크기를 제한하는 옵션. 기본값은 무제한이므로, 동적 클래스 로딩이 많은 환경(Hibernate, Spring, Groovy 등)에서는 명시적으로 상한을 설정해야 메모리 누수를 방지할 수 있다.

**Native Memory Tracking (NMT)**
JVM이 내부적으로 사용하는 네이티브 메모리(힙 외 영역)를 추적하는 진단 도구. `-XX:NativeMemoryTracking=summary` 또는 `detail`로 활성화하고, `jcmd <pid> VM.native_memory` 명령으로 힙·Metaspace·Code Cache·Thread Stack·GC 등의 메모리 사용량을 확인할 수 있다. 컨테이너에서 원인 불명의 OOMKill 디버깅에 유용하다.

## 참고 자료
- [JVM Parameters: InitialRAMPercentage, MinRAMPercentage, and MaxRAMPercentage - Baeldung](https://www.baeldung.com/java-jvm-parameters-rampercentage)
- [JVM Memory Settings in a Container Environment - atamanroman.dev](https://www.atamanroman.dev/jvm-memory-settings-container-environment/)
- [Java 17: What's new in OpenJDK's container awareness - Red Hat Developer](https://developers.redhat.com/articles/2022/04/19/java-17-whats-new-openjdks-container-awareness)
- [Native Memory Tracking in JVM - Baeldung](https://www.baeldung.com/native-memory-tracking-in-jvm)
- [Docker Best Practices: Choosing Between RUN, CMD, and ENTRYPOINT - Docker](https://www.docker.com/blog/docker-best-practices-choosing-between-run-cmd-and-entrypoint/)
- [Understanding and Resolving OOMKilled errors in JVM microservices - Endowus Tech](https://tech.endowus.com/oomkilled/)
- [Best Practices: Java Memory Arguments for Containers - DZone](https://dzone.com/articles/best-practices-java-memory-arguments-for-container)
