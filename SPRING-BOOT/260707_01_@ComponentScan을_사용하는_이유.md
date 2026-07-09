# @ComponentScan을 사용하는 이유

## 개요

Spring 애플리케이션에서 빈(Bean)을 IoC 컨테이너에 등록하는 방법은 크게 두 가지다. 하나는 `@Bean` 메서드나 XML 설정처럼 개발자가 직접 명시하는 방식이고, 다른 하나는 `@ComponentScan`을 통해 클래스패스를 자동으로 탐색하는 방식이다. `@ComponentScan`이 없던 시절과 있는 현재를 비교하면 왜 이 메커니즘이 도입되었는지, 그리고 어떤 상황에서 어떻게 제어하는지를 이해할 수 있다.

## 상세 내용

### 1. @ComponentScan이란

`@ComponentScan`은 지정된 패키지를 클래스패스에서 재귀적으로 탐색하여, `@Component` 계열 애노테이션이 붙은 클래스를 발견하고 스프링 빈 정의(`BeanDefinition`)로 등록하는 메커니즘이다.

Spring Boot 환경에서는 `@SpringBootApplication`이 `@ComponentScan`을 메타 애노테이션으로 포함하고 있어, 애플리케이션 진입점 클래스에 `@SpringBootApplication`만 붙이면 자동으로 동작한다.

```java
// @SpringBootApplication의 내부 구성
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
    @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
public @interface SpringBootApplication { }
```

별도 `basePackages`를 지정하지 않으면, `@ComponentScan`을 선언한 클래스의 패키지를 기준으로 하위 패키지 전체를 스캔한다. 이것이 `@SpringBootApplication`을 최상위 패키지에 두도록 권장하는 이유다.

### 2. @ComponentScan을 사용하지 않으면 어떻게 될까

`@ComponentScan` 없이 빈을 등록하려면 개발자가 모든 빈을 명시적으로 등록해야 한다.

**XML 설정 방식 (Spring 초기)**

```xml
<beans>
    <bean id="userService" class="com.example.service.UserService"/>
    <bean id="userRepository" class="com.example.repository.UserRepository"/>
    <bean id="orderService" class="com.example.service.OrderService"/>
    <!-- ... -->
</beans>
```

**@Bean 수동 등록 방식**

```java
@Configuration
public class AppConfig {

    @Bean
    public UserService userService() {
        return new UserService(userRepository());
    }

    @Bean
    public UserRepository userRepository() {
        return new UserRepository();
    }

    @Bean
    public OrderService orderService() {
        return new OrderService(userService());
    }
}
```

클래스가 수십 개를 넘어가면 이 방식은 다음과 같은 문제를 일으킨다.

- 새 클래스를 추가할 때마다 `@Bean` 메서드를 별도로 작성해야 한다.
- 설정 클래스와 실제 비즈니스 클래스가 분리되어 변경 추적이 어렵다.
- 빈 이름 충돌, 의존성 주입 순서 오류 등의 실수가 발생하기 쉽다.

### 3. @ComponentScan을 사용하는 이유

**자동 빈 등록으로 설정 코드 감소**

`@Component` 계열 애노테이션을 클래스에 붙이는 것만으로 스캔 시 자동으로 빈이 등록된다. 설정 클래스에 별도 코드를 추가할 필요가 없다.

```java
@Service  // 이것만으로 빈 등록 완료
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
}
```

**컨벤션 기반 개발**

스테레오타입 애노테이션을 계층별로 구분해 사용하면, 클래스의 역할이 코드를 읽지 않아도 파악된다.

| 애노테이션 | 계층 | 추가 기능 |
|-----------|------|----------|
| `@Component` | 범용 | 없음 |
| `@Service` | 서비스 계층 | 없음 (의미론적 구분) |
| `@Repository` | 데이터 접근 계층 | 예외를 `DataAccessException`으로 자동 변환 |
| `@Controller` | 프레젠테이션 계층 | DispatcherServlet이 요청 매핑에 활용 |
| `@Configuration` | 설정 | 내부 `@Bean` 메서드를 프록시로 처리 |

**패키지 구조와 컴포넌트 관리의 일관성**

`@ComponentScan`은 패키지 구조 자체를 빈 관리의 단위로 삼는다. 패키지를 올바르게 구성하면 스캔 범위가 자연스럽게 결정되고, 외부에 노출할 것과 내부에서만 사용할 것을 패키지 경계로 구분할 수 있다.

### 4. 동작 원리

**스캔 대상 패키지 결정**

1. `basePackages`나 `basePackageClasses`가 명시된 경우 해당 패키지를 기준으로 탐색한다.
2. 아무것도 지정하지 않으면 `@ComponentScan`을 선언한 클래스의 패키지를 기준으로 재귀 탐색한다.

```java
// 명시적 패키지 지정
@ComponentScan(basePackages = "com.example.service")

// 타입 안전한 방식 (basePackageClasses로 지정한 클래스의 패키지를 기준으로 탐색)
@ComponentScan(basePackageClasses = UserService.class)
```

**클래스패스 스캐닝과 빈 정의 등록 과정**

내부적으로 `ClassPathBeanDefinitionScanner`가 동작하며, 다음 절차를 거친다.

1. 지정된 패키지의 클래스패스 디렉토리를 재귀적으로 탐색한다.
2. `.class` 파일을 ASM(Java 바이트코드 조작 라이브러리)으로 읽어 실제 클래스 로딩 없이 메타데이터를 분석한다.
3. `includeFilters` / `excludeFilters` 조건을 적용해 후보를 선별한다.
4. 통과한 클래스에 대해 `BeanDefinition`을 생성하고 `ApplicationContext`에 등록한다.
5. `@Autowired`, `@Value` 등의 의존성 주입은 이후 `BeanPostProcessor`가 처리한다.

ASM 기반 분석을 사용하기 때문에 클래스를 JVM에 실제로 로드하지 않고도 애노테이션 정보를 확인할 수 있어, 존재하지 않는 클래스를 참조해도 `ClassNotFoundException`이 발생하지 않는다.

**스테레오타입 어노테이션 탐지**

`@Component`를 메타 애노테이션으로 가진 모든 애노테이션이 스캔 대상이다. `@Service`, `@Repository`, `@Controller`는 모두 내부적으로 `@Component`를 포함한다.

```java
// @Service의 내부 정의
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component  // 이것 때문에 ComponentScan이 인식한다
public @interface Service {
    String value() default "";
}
```

커스텀 애노테이션도 `@Component`를 메타 애노테이션으로 포함하면 자동으로 스캔 대상이 된다.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface UseCase { }

@UseCase  // @ComponentScan이 이 클래스를 빈으로 등록한다
public class PlaceOrderUseCase { }
```

### 5. 스캔 범위 제어

**basePackages와 basePackageClasses**

```java
// 문자열 패키지명 지정 (리팩터링 시 오타 위험)
@ComponentScan(basePackages = {"com.example.service", "com.example.repository"})

// 타입 안전한 방식 - 마커 인터페이스나 클래스를 지정
@ComponentScan(basePackageClasses = {ServiceMarker.class, RepositoryMarker.class})
```

`basePackageClasses`는 패키지명을 문자열로 하드코딩하지 않아 IDE 리팩터링과 컴파일 타임 오류 검출에 유리하다. 각 패키지에 아무 내용 없는 마커 인터페이스나 클래스를 두는 방식이 관례다.

**includeFilters / excludeFilters**

기본 필터(`@Component` 계열)를 그대로 유지하면서, 추가로 포함하거나 제외할 대상을 지정한다.

```java
@ComponentScan(
    basePackages = "com.example",
    includeFilters = @Filter(
        type = FilterType.ANNOTATION,
        classes = UseCase.class         // @UseCase가 붙은 클래스를 추가 포함
    ),
    excludeFilters = @Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = InternalService.class // 특정 클래스 타입 제외
    )
)
```

지원하는 `FilterType`은 다음과 같다.

| FilterType | 설명 | 예시 |
|-----------|------|------|
| `ANNOTATION` | 특정 애노테이션이 붙은 클래스 | `@UseCase` |
| `ASSIGNABLE_TYPE` | 특정 클래스/인터페이스를 구현/상속한 클래스 | `BaseService.class` |
| `ASPECTJ` | AspectJ 타입 표현식에 매칭 | `com.example..*Service+` |
| `REGEX` | 클래스명이 정규식에 매칭 | `.*Stub.*Repository` |
| `CUSTOM` | `TypeFilter` 인터페이스를 직접 구현 | `MyCustomFilter.class` |

기본 필터 자체를 끄고 싶으면 `useDefaultFilters = false`를 사용한다.

```java
@ComponentScan(
    basePackages = "com.example",
    useDefaultFilters = false,           // @Component 계열 기본 탐색 비활성화
    includeFilters = @Filter(
        type = FilterType.ANNOTATION,
        classes = UseCase.class           // 오직 @UseCase만 스캔
    )
)
```

### 6. @AutoConfiguration보다 @ComponentScan이 좋은 경우

`@ComponentScan`과 `@AutoConfiguration`은 경쟁 관계가 아니라 역할이 다른 도구다. `@AutoConfiguration`은 외부 라이브러리·스타터가 조건부로 빈을 등록하기 위한 메커니즘이고, `@ComponentScan`은 애플리케이션이 소유한 코드를 등록하기 위한 메커니즘이다. 따라서 "언제 무엇을 쓰는가"는 대체로 **빈의 소유 주체와 등록 조건의 유무**로 갈린다.

**1) 내가 소유한 애플리케이션 코드**

빈이 될 클래스가 애플리케이션 자신의 패키지 안에 있다면 `@ComponentScan`이 정답이다. 애플리케이션 코드를 `AutoConfiguration.imports` 파일에 일일이 등록하는 것은 불필요한 관리 비용만 늘린다. `@AutoConfiguration`은 애초에 "외부 JAR의 빈을 사용자 패키지 밖에서 안전하게 등록"하려고 만들어졌으므로, 소유한 코드에는 과잉 설계다.

**2) 항상 등록되어야 하는 확정적인 빈**

`@Service`, `@Repository`, `@Controller`처럼 조건 없이 언제나 등록되어야 하는 빈은 `@ComponentScan`이 간결하다. `@AutoConfiguration`의 진짜 가치는 `@ConditionalOnClass`, `@ConditionalOnMissingBean` 같은 조건부 등록에 있는데, 조건이 필요 없는 빈에 이를 적용하면 얻는 것 없이 복잡도만 커진다.

**3) 변경이 잦은 코드**

기능 추가·리팩터링이 빈번한 애플리케이션 코드는 클래스에 스테레오타입 애노테이션만 붙이면 등록이 끝나는 `@ComponentScan`이 개발 속도에 유리하다. `@AutoConfiguration` 방식은 클래스를 추가·삭제할 때마다 `imports` 파일을 함께 관리해야 해 변경 지점이 두 곳으로 늘어난다.

**4) 계층 구조를 코드로 드러내고 싶을 때**

`@Service`, `@Repository` 같은 스테레오타입 애노테이션은 빈 등록뿐 아니라 클래스의 계층·역할을 코드에 표현한다. `@AutoConfiguration` + `@Bean` 방식은 이런 의미론적 정보를 설정 클래스로 분리해버려, 도메인 코드만 봐서는 계층을 알기 어렵다.

**판단 기준 요약**

| 상황 | 권장 방식 | 이유 |
|------|----------|------|
| 애플리케이션 자체 패키지의 빈 | `@ComponentScan` | 소유한 코드는 스캔 범위 안, imports 관리 불필요 |
| 조건 없이 항상 등록되는 빈 | `@ComponentScan` | 조건부 등록 기능이 필요 없음 |
| 변경이 잦은 비즈니스 코드 | `@ComponentScan` | 애노테이션만 추가하면 등록 완료 |
| 외부 라이브러리·스타터의 빈 | `@AutoConfiguration` | 사용자 패키지 밖의 빈을 안전하게 등록 |
| 클래스패스·프로퍼티 조건부 등록 | `@AutoConfiguration` | `@Conditional` 계열로 안정적 처리 |
| 사용자가 오버라이드 가능해야 하는 기본 빈 | `@AutoConfiguration` | `@ConditionalOnMissingBean` backing off 패턴 |

즉, **애플리케이션을 개발하는 입장에서는 대부분의 빈을 `@ComponentScan`으로 등록하는 것이 자연스럽고, `@AutoConfiguration`은 라이브러리를 제공하는 입장이 되었을 때 필요한 도구**라고 이해하면 된다. 두 방식의 동작 시점과 한계에 대한 자세한 비교는 [@ComponentScan의 한계와 @AutoConfiguration](./260621_04_@ComponentScan의_한계와_@AutoConfiguration.md)을 참고한다.

## 핵심 정리

- `@ComponentScan`은 클래스패스를 재귀적으로 탐색하여 `@Component` 계열 애노테이션이 붙은 클래스를 자동으로 빈으로 등록한다. 이를 통해 매 클래스마다 `@Bean` 메서드를 작성하는 번거로움을 없앤다.
- `@SpringBootApplication`은 내부에 `@ComponentScan`을 포함하므로, 최상위 패키지에 진입점 클래스를 두면 별도 설정 없이 애플리케이션 전체 패키지가 스캔 대상이 된다.
- 스캔 범위는 `basePackages` / `basePackageClasses`, `includeFilters` / `excludeFilters`로 세밀하게 제어할 수 있다.
- 내부적으로 ASM 기반 바이트코드 분석을 사용하여 클래스 실제 로딩 없이 메타데이터를 읽기 때문에 성능상 부담이 최소화된다.
- `@AutoConfiguration`과는 소유 주체와 조건 유무로 역할이 갈린다. 내가 소유한 애플리케이션 코드, 조건 없이 항상 등록되는 빈, 변경이 잦은 코드는 `@ComponentScan`이 적합하고, 외부 라이브러리·스타터의 조건부 빈 등록은 `@AutoConfiguration`이 적합하다.

## 기술적 한계와 보완 전략

**스캔 범위가 넓을 때의 기동 성능 이슈**

패키지 범위가 넓을수록 탐색해야 할 클래스 수가 증가하여 애플리케이션 시작 시간이 늘어난다. 특히 클래스가 수천 개를 넘는 대형 멀티모듈 프로젝트에서 체감된다. `basePackages`를 최소한의 범위로 좁히거나, Spring Boot 3.x의 AOT(Ahead-of-Time) 컴파일을 활용하면 기동 성능을 개선할 수 있다.

**명시적 등록(@Bean) 대비 추적성/가독성 트레이드오프**

`@ComponentScan`은 편리하지만 어떤 클래스가 빈으로 등록되어 있는지 코드만으로 한눈에 파악하기 어렵다. 반면 `@Bean`으로 명시적으로 등록하면 설정 클래스만 읽어도 전체 빈 구성을 파악할 수 있다. 공개 API나 설정 성격이 강한 빈은 명시적 등록을, 일반 비즈니스 코드 빈은 컴포넌트 스캔을 사용하는 식으로 용도에 따라 혼합하는 전략이 일반적이다.

**@ComponentScan의 한계와 @AutoConfiguration의 보완 관계**

`@ComponentScan`은 외부 JAR에 있는 클래스를 스캔하지 못한다. 스캔은 애플리케이션이 소유한 패키지 범위 안에서만 동작하기 때문이다. Spring Boot의 외부 라이브러리 자동 구성은 이 한계를 `@AutoConfiguration`과 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일 기반 메커니즘으로 보완한다. 자세한 내용은 [@ComponentScan의 한계와 @AutoConfiguration](./260621_04_@ComponentScan의_한계와_@AutoConfiguration.md)을 참고한다.

## 키워드

- **@ComponentScan**: 지정된 베이스 패키지와 그 하위 패키지를 클래스패스에서 재귀적으로 탐색하여, `@Component` 계열 애노테이션이 붙은 클래스를 스프링 빈으로 자동 등록하는 메커니즘. `basePackages`, `includeFilters`, `excludeFilters` 등의 속성으로 탐색 범위와 필터를 세밀하게 제어할 수 있다.
- **@SpringBootApplication**: `@SpringBootConfiguration`, `@EnableAutoConfiguration`, `@ComponentScan`을 합성한 메타 애노테이션. Spring Boot 진입점 클래스에 붙이며, 해당 클래스의 패키지를 기준으로 컴포넌트 스캔이 시작된다.
- **컴포넌트 스캔 (Component Scan)**: 클래스패스를 탐색하여 스프링이 관리할 빈을 자동으로 발견하고 등록하는 과정. `ClassPathBeanDefinitionScanner`가 내부적으로 수행한다.
- **스테레오타입 어노테이션 (@Component, @Service, @Repository, @Controller)**: `@Component`를 메타 애노테이션으로 포함하는 애노테이션 계층. 클래스의 역할(계층)을 명시적으로 표현하며, 일부 애노테이션은 추가 기능(`@Repository`의 예외 변환 등)을 제공한다.
- **basePackages**: `@ComponentScan`의 속성으로, 스캔할 패키지를 문자열로 지정한다. 지정하지 않으면 선언 클래스의 패키지가 기준이 된다. `basePackageClasses`를 사용하면 타입 안전하게 패키지를 지정할 수 있다.
- **클래스패스 스캐닝 (Classpath Scanning)**: JVM 클래스패스 내의 패키지 디렉토리를 재귀 탐색하여 특정 조건에 맞는 클래스를 찾는 과정. Spring은 ASM으로 바이트코드를 분석하므로 클래스를 실제 로드하지 않고 메타데이터만 읽는다.
- **빈 자동 등록**: `@ComponentScan`이 발견한 클래스에 대해 `BeanDefinition`을 생성하고 `ApplicationContext`에 등록하는 과정. 이후 컨테이너가 의존성을 주입하고 생명주기를 관리한다.
- **includeFilters / excludeFilters**: 스캔 대상을 추가(include)하거나 제외(exclude)하는 필터. `FilterType.ANNOTATION`, `ASSIGNABLE_TYPE`, `ASPECTJ`, `REGEX`, `CUSTOM` 다섯 가지 방식을 지원한다.
- **IoC 컨테이너**: 빈의 생성, 의존성 주입, 생명주기 관리를 담당하는 Spring의 핵심 컴포넌트. `ApplicationContext`가 대표적인 구현체이며, `@ComponentScan`으로 발견된 클래스들이 이 컨테이너에 등록되어 관리된다.

## 참고 자료

- [Classpath Scanning and Managed Components - Spring Framework 공식 문서](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html)
- [ComponentScan Javadoc - Spring Framework 7.x API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/ComponentScan.html)
- [Spring Component Scanning - Baeldung](https://www.baeldung.com/spring-component-scanning)
