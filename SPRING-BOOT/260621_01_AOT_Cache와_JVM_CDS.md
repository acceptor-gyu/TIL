# AOT Cache와 JVM CDS

## 개요

Spring Boot의 기동 시간 단축 기법인 AOT(Ahead-of-Time) 처리와 JVM의 클래스 데이터 공유(CDS, Class Data Sharing)의 개념, 그리고 Spring Boot 3.3+에서 둘을 결합해 콜드 스타트를 최적화하는 방식을 정리한다.

## 상세 내용

### 1. 배경: JVM 애플리케이션의 콜드 스타트 문제

JVM 기반 애플리케이션은 실행될 때마다 다음 세 단계를 반복한다.

1. **클래스 로딩**: JVM이 `.class` 파일을 읽어 바이트코드를 파싱한다.
2. **클래스 검증(Verification)**: 바이트코드가 JVM 명세를 준수하는지 확인한다.
3. **JIT 워밍업**: 인터프리터로 실행하다가 핫스팟이 감지되면 JIT 컴파일을 진행한다.

이 과정은 매 기동마다 처음부터 수행되므로 서버리스, 오토스케일링, 컨테이너 재배포 환경에서 콜드 스타트 시간이 문제가 된다. 특히 Spring Boot와 같은 대형 프레임워크는 수천 개의 클래스를 로딩하기 때문에 이 비용이 크다.

### 2. JVM CDS (Class Data Sharing)

#### CDS란

CDS는 JVM이 클래스 메타데이터(파싱·검증된 클래스 정보)를 **공유 아카이브 파일(.jsa)**에 저장해두고, 다음 기동 시 해당 파일을 메모리 매핑(mmap)으로 불러오는 기술이다. 클래스를 다시 파싱·검증하는 비용을 건너뛸 수 있다.

#### Base CDS vs AppCDS

| 구분 | 설명 |
|------|------|
| **Base CDS** | JDK 기본 제공 아카이브. JDK 설치 시 포함된 `classes.jsa` 파일로, JDK 핵심 클래스를 공유한다. |
| **AppCDS (Application CDS)** | 애플리케이션 클래스까지 포함하는 확장 아카이브. JDK 10+에서 지원. |

#### Dynamic CDS Archive (JDK 13+)

JDK 13부터 `-XX:+AutoCreateSharedArchive` 옵션으로 애플리케이션 종료 시 자동으로 아카이브를 생성할 수 있다. 수동으로 트레이닝 런을 구성하지 않아도 되지만, 최초 실행 시에는 효과가 없다.

#### CDS 아카이브 생성 및 사용 방법

```bash
# 1. 애플리케이션 추출 (layered jar에서 추출)
java -Djarmode=tools -jar my-app.jar extract --destination application
cd application

# 2. 트레이닝 런: 아카이브 생성
java -XX:ArchiveClassesAtExit=application.jsa \
  -Dspring.context.exit=onRefresh \
  -jar my-app.jar

# 3. 아카이브를 활용해 실행
java -XX:SharedArchiveFile=application.jsa -jar my-app.jar
```

- `-Dspring.context.exit=onRefresh`: ApplicationContext가 refresh된 후 즉시 JVM을 종료한다. 실제 요청을 처리하지 않아도 클래스 로딩 패턴을 충분히 캡처할 수 있다.
- 생성된 `.jsa` 파일은 동일한 JVM 버전에서만 재사용 가능하다. 코드나 JVM이 변경되면 아카이브를 다시 생성해야 한다.

### 3. Spring Boot AOT 처리

#### AOT 처리란

Spring Boot의 AOT(Ahead-of-Time) 처리는 **빌드 타임**에 런타임에서 수행할 작업을 미리 처리하는 기법이다. 구체적으로 다음을 생성한다.

- 빈 정의(BeanDefinition) 코드: 런타임 리플렉션 없이 빈을 등록할 수 있는 소스 코드
- 프록시 클래스: CGLIB 기반 프록시를 미리 생성
- 리플렉션 힌트(Reflection Hints): GraalVM Native Image용 설정

`spring-boot:process-aot` 골을 실행하면 `target/spring-aot/` 디렉토리에 산출물이 생성된다.

#### 런타임 리플렉션 감소와 기동 최적화

일반적인 Spring 애플리케이션은 기동 시 리플렉션으로 빈을 탐색하고 등록한다. AOT 처리를 사용하면 이 단계가 빌드 타임으로 이동하므로 런타임 리플렉션이 줄고 ApplicationContext 초기화 속도가 빨라진다.

#### GraalVM Native Image와 AOT의 관계

AOT 처리는 원래 GraalVM Native Image를 위해 도입됐지만, Native Image 없이도 일반 JVM 위에서 AOT 산출물을 활용할 수 있다.

| 구분 | GraalVM Native Image | AOT + JVM |
|------|---------------------|-----------|
| 컴파일 방식 | 네이티브 바이너리로 AOT 컴파일 | 바이트코드 유지, JIT 병행 |
| 기동 속도 | 매우 빠름 (밀리초 단위) | 빠름 (CDS/AOT Cache 조합) |
| 피크 처리량 | JVM 대비 낮음 | JIT 최적화로 높음 |
| 빌드 복잡도 | 높음 | 낮음 |

### 4. Spring Boot에서 AOT + CDS 결합

Spring Boot 3.3부터 CDS와 AOT 처리를 함께 활용하는 방식을 공식 지원한다.

```bash
# AOT 처리 활성화 후 빌드
./mvnw spring-boot:process-aot package

# 트레이닝 런 (AOT 활성화 상태에서 CDS 아카이브 생성)
java -XX:ArchiveClassesAtExit=application.jsa \
  -Dspring.aot.enabled=true \
  -Dspring.context.exit=onRefresh \
  -jar my-app.jar

# AOT + CDS로 실행
java -XX:SharedArchiveFile=application.jsa \
  -Dspring.aot.enabled=true \
  -jar my-app.jar
```

AOT로 미리 생성된 빈 정의 코드를 사용하면 클래스 로딩 패턴이 단순해지므로 CDS 아카이브의 히트율이 높아진다. 두 기술을 조합하면 단독으로 사용할 때보다 더 큰 기동 시간 단축 효과를 얻는다.

#### Layered Jar / Buildpacks와의 연계

Spring Boot의 Layered Jar(`-Djarmode=tools extract`)로 애플리케이션을 풀어낸 후 CDS/AOT Cache 아카이브를 생성하면, 아카이브가 개별 레이어의 클래스 경로를 정확히 추적할 수 있어 효율이 높아진다. Cloud Native Buildpacks를 사용하면 이 과정을 자동화할 수 있다.

#### 적용 시 기대 효과

Spring PetClinic 기준으로 JEP 483(Java 24) 적용 시 기동 속도가 최대 **40% 향상**된다는 측정 결과가 있다. 실제 효과는 애플리케이션 규모와 클래스 수에 따라 달라진다.

### 5. Project Leyden과 향후 방향

#### AOT Cache: CDS의 진화

Project Leyden은 OpenJDK에서 Java 프로그램의 **전체 풋프린트**(기동 시간, 메모리, 최고 성능 도달 시간)를 줄이기 위한 장기 프로젝트다.

| JDK 버전 | 주요 JEP | 내용 |
|----------|----------|------|
| JDK 24 | JEP 483 | Ahead-of-Time Class Loading & Linking |
| JDK 25 | JEP 514, 515 | AOT Method Profiling, 간소화된 2단계 워크플로 |

**CDS vs AOT Cache의 핵심 차이**

- CDS: 클래스를 **파싱(parsed) 상태**로만 저장. 기동 시 여전히 링킹(Linking) 단계가 필요하다.
- AOT Cache (JEP 483): 클래스를 **로딩·검증·링킹이 완료된 상태**로 저장. 기동 시 링킹 단계까지 생략 가능하다.

#### JDK 24 AOT Cache 사용 방법

Java 24에서는 3단계 워크플로를 사용한다.

```bash
# 1. 애플리케이션 추출
java -Djarmode=tools -jar my-app.jar extract --destination application
cd application

# 2. 트레이닝 런 (AOT 캐시 파일 직접 생성)
java -XX:AOTCacheOutput=app.aot \
  -Dspring.context.exit=onRefresh \
  -jar my-app.jar

# 3. AOT Cache 사용해 실행
java -XX:AOTCache=app.aot -jar my-app.jar
```

JDK 25에서는 JEP 514로 더욱 간소화되어 2단계 워크플로로 줄어들었다.

### 6. Native Image와의 비교

| 항목 | AOT + CDS/AOT Cache | GraalVM Native Image |
|------|---------------------|----------------------|
| 기동 속도 | 보통 수백 ms 단축 | 밀리초 단위 (가장 빠름) |
| 피크 처리량 | JIT 덕분에 높음 | JIT 없으므로 상대적으로 낮음 |
| 메모리 | 일반 JVM 수준 | 더 적음 |
| 빌드 복잡도 | 낮음 (기존 빌드 파이프라인 재활용) | 높음 (네이티브 빌드 환경 필요) |
| 디버깅 | 기존 Java 도구 사용 가능 | 제한적 |
| 운영 안정성 | 높음 (기존 JVM 동작과 동일) | 상대적으로 낮음 (제약 많음) |

**결론**: 콜드 스타트가 치명적인 서버리스 환경에서는 Native Image가 최선이지만, 일반 컨테이너 기반 서비스에서 기동 시간을 줄이면서도 JIT 최적화의 이점을 유지하고 싶다면 AOT + CDS/AOT Cache 조합이 현실적인 선택이다.

## 핵심 정리

- CDS는 클래스 메타데이터를 아카이브로 저장해 파싱·검증 비용을 절감하고, AOT Cache(JEP 483, JDK 24+)는 링킹 단계까지 캐싱해 더 큰 효과를 낸다.
- Spring Boot AOT 처리는 빌드 타임에 빈 등록 코드와 프록시를 생성해 런타임 리플렉션을 줄이며, CDS/AOT Cache와 함께 사용하면 시너지가 크다.
- GraalVM Native Image는 기동 속도에서 최고이지만 빌드 복잡도와 피크 처리량의 트레이드오프가 있고, AOT + CDS는 기존 JVM 환경에서 덜 침습적으로 기동 성능을 개선할 수 있다.

## 기술적 한계와 보완 전략

- **아카이브 유효성**: 코드 변경이나 JVM 버전 변경 시 아카이브를 다시 생성해야 한다. CI/CD 파이프라인에 아카이브 재생성 단계를 포함시켜야 한다.
- **트레이닝 런의 대표성**: 트레이닝 런이 실제 프로덕션 클래스 로딩 패턴과 다를 경우 히트율이 낮아진다. `-Dspring.context.exit=onRefresh` 대신 실제 트래픽을 일부 처리하는 방식으로 보완할 수 있다.
- **컨테이너 환경**: 컨테이너 이미지 내부에 아카이브를 포함시키거나 마운트해야 한다. 이미지 레이어 구조를 고려해 아카이브가 애플리케이션 레이어에 포함되도록 설계한다.
- **Dynamic CDS**의 경우 첫 번째 실행에서는 효과가 없으므로 오토스케일링 환경에서는 미리 생성된 아카이브를 이미지에 포함하는 방식이 더 적합하다.

## 키워드

- **AOT (Ahead-of-Time)**: 런타임이 아닌 빌드 타임에 작업을 미리 수행하는 컴파일·처리 전략. Spring Boot에서는 빈 정의 코드와 프록시를 빌드 타임에 생성해 기동 속도를 높인다.
- **CDS (Class Data Sharing)**: JVM이 클래스 메타데이터(파싱·검증 결과)를 아카이브 파일에 저장하고 이후 기동 시 메모리 매핑으로 재사용하는 기술. 클래스 파싱 및 검증 비용을 절감한다.
- **AppCDS (Application Class Data Sharing)**: JDK 10+에서 도입된 CDS 확장. JDK 기본 클래스뿐 아니라 애플리케이션 클래스까지 아카이브에 포함할 수 있다.
- **Dynamic CDS Archive**: JDK 13+에서 제공하는 기능으로, `-XX:+AutoCreateSharedArchive` 옵션을 통해 애플리케이션 종료 시 자동으로 CDS 아카이브를 생성한다.
- **AOT Cache (JEP 483)**: JDK 24에서 도입된 Project Leyden의 첫 결과물. CDS보다 진화된 형태로, 클래스를 파싱·검증·링킹이 완료된 상태로 캐싱해 기동 시 링킹 단계까지 생략한다.
- **Project Leyden**: Java 프로그램의 기동 시간, 메모리 풋프린트, 최고 성능 도달 시간을 줄이기 위한 OpenJDK 장기 프로젝트. JEP 483(JDK 24), JEP 514·515(JDK 25)를 통해 단계적으로 메인라인에 통합되고 있다.
- **Training Run**: AOT 캐시 또는 CDS 아카이브를 생성하기 위해 수행하는 사전 실행. 실제 기동 시 어떤 클래스가 로딩되는지 기록하기 위한 목적이다. `-Dspring.context.exit=onRefresh`와 함께 사용하면 ApplicationContext 초기화 직후 종료된다.
- **Cold Start**: 애플리케이션이 처음 기동될 때(또는 캐시가 없는 상태에서 기동될 때) 발생하는 지연 시간. 클래스 로딩, 검증, JIT 워밍업이 모두 포함된다.
- **GraalVM Native Image**: Java 프로그램을 네이티브 실행 파일로 AOT 컴파일하는 기술. 밀리초 단위의 기동 속도를 제공하지만 JIT 최적화가 없어 피크 처리량이 낮고 빌드가 복잡하다.
- **Spring Boot 3.3**: Spring Boot에서 CDS와 AOT 처리를 공식 결합 지원하기 시작한 버전. `spring.aot.enabled=true`와 CDS 트레이닝 런을 조합해 기동 시간을 단축할 수 있다.

## 참고 자료

- [Spring Boot AOT Cache 공식 문서](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html)
- [Spring Boot Class Data Sharing 공식 문서](https://docs.spring.io/spring-boot/3.4/reference/packaging/class-data-sharing.html)
- [Spring Framework JVM AOT Cache 문서](https://docs.spring.io/spring-framework/reference/integration/aot-cache.html)
- [JEP 483: Ahead-of-Time Class Loading & Linking](https://openjdk.org/jeps/483)
- [Java 24 Launches with JEP 483 - InfoQ](https://www.infoq.com/news/2025/03/java-24-leyden-ships/)
- [Project Leyden's AOT Code Cache - Java Code Geeks](https://www.javacodegeeks.com/2026/03/project-leydens-aot-code-cache-how-java-is-solving-its-cold-start-problem-without-graalvm.html)
