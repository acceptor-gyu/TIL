# @ComponentScan의 한계와 @AutoConfiguration

## 개요

Spring Boot 애플리케이션에서 빈을 등록하는 방식은 크게 두 가지다. 개발자가 작성한 클래스를 패키지 기준으로 탐색하는 `@ComponentScan`과, 외부 라이브러리나 스타터가 제공하는 빈을 조건부로 자동 등록하는 `@AutoConfiguration`이다. 이 두 방식은 역할과 동작 시점이 다르며, 각각의 한계와 보완 방식을 이해하는 것이 Spring Boot 내부 동작을 파악하는 핵심이다.

## 상세 내용

### @ComponentScan 동작 방식

`@ComponentScan`은 지정된 베이스 패키지 및 하위 패키지를 클래스패스에서 스캔하여 스프링 빈으로 등록한다. `@SpringBootApplication`에 메타 애노테이션으로 포함되어 있기 때문에 별도 설정 없이 동작한다.

스캔 대상 애노테이션은 다음과 같다.

- `@Component`
- `@Service`
- `@Repository`
- `@Controller`
- `@Configuration`

```java
@SpringBootApplication  // 내부적으로 @ComponentScan 포함
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@ComponentScan`은 기본적으로 `@SpringBootApplication`이 위치한 패키지를 베이스 패키지로 삼아 하위 패키지를 모두 탐색한다.

### @ComponentScan의 한계

**1. 외부 JAR의 빈은 스캔 범위 밖**

`@ComponentScan`은 클래스패스 내 패키지 구조를 기준으로 동작한다. 따라서 별도의 JAR로 배포된 라이브러리 내부의 빈은 스캔 대상에 포함되지 않는다. 라이브러리 작성자가 직접 베이스 패키지를 등록하도록 요구하거나, 사용자가 `@ComponentScan(basePackages = "com.external.lib")`를 수동으로 추가해야 한다. 이는 사용성을 해치고, 라이브러리와 사용자 코드 간 결합도를 높인다.

**2. 조건부 빈 등록의 어려움**

`@ComponentScan`으로 등록된 클래스에 `@ConditionalOnMissingBean` 같은 조건 애노테이션을 붙여도 예측 가능하게 동작하지 않는 경우가 있다. 컴포넌트 스캔은 애플리케이션 컨텍스트 로딩 초기에 수행되며, 이 시점에는 다른 빈이 아직 등록되지 않았을 수 있다. 결과적으로 조건 평가가 잘못될 수 있고, "zombie bean" 상태(조건을 통과해야 했지만 통과하지 못한 빈)가 발생하기도 한다.

Spring 공식 GitHub 이슈([#15348](https://github.com/spring-projects/spring-boot/issues/15348))에서도 자동 구성 클래스 내부에서 `@ComponentScan` 사용을 강하게 권고하지 않는다.

**3. 스캔 범위 확대 시 부작용**

패키지 범위를 넓히면 의도하지 않은 클래스가 빈으로 등록될 수 있고, 클래스패스 전체 탐색으로 인해 애플리케이션 시작 시간이 증가한다.

**4. 중첩 클래스에서의 조건 처리 문제**

`@ComponentScan`으로 발견된 내부 중첩 클래스는 외부 클래스의 `@Conditional` 조건을 무시하고 자신의 조건만으로 평가된다. 이로 인해 의도하지 않은 빈이 등록될 수 있다.

### @AutoConfiguration 등장 배경

Spring Boot의 핵심 철학 중 하나인 "설정보다 관례(Convention over Configuration)"를 구현하기 위해 자동 구성(Auto Configuration) 개념이 도입되었다. 개발자가 의존성을 추가하기만 하면 관련 빈이 자동으로 등록되어야 하는데, `@ComponentScan`만으로는 이를 안전하게 구현하기 어려웠다.

**스타터(Starter)와의 관계**

Spring Boot Starter는 두 가지 모듈로 구성되는 것이 관례다.

```
my-library-spring-boot/              ← Auto-configuration 구현 모듈
├── MyAutoConfiguration.java
├── MyProperties.java
└── META-INF/spring/
    └── org.springframework.boot.autoconfigure.AutoConfiguration.imports

my-library-spring-boot-starter/      ← 의존성 집합만 선언하는 Starter 모듈
└── pom.xml (위 모듈을 의존성으로 포함)
```

사용자는 starter 의존성만 추가하면 auto-configuration 모듈이 함께 포함되고, 자동 구성이 조건에 따라 빈을 등록한다.

### @AutoConfiguration 동작 원리

**1. `META-INF/spring/AutoConfiguration.imports` 파일 기반 등록**

Spring Boot 2.7부터 자동 구성 클래스는 아래 경로의 파일에 선언된다.

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

파일 형식은 한 줄에 하나의 완전한 클래스명(FQCN)을 작성하며, `#`으로 주석을 달 수 있다.

```
# 예시
com.mycorp.libx.autoconfigure.LibXAutoConfiguration
com.mycorp.libx.autoconfigure.LibXWebAutoConfiguration
```

이 파일에 선언된 클래스만 자동 구성 대상이 되므로, 컴포넌트 스캔과 달리 명시적이고 예측 가능하다.

**2. (구버전) `spring.factories`와의 차이**

Spring Boot 2.7 이전에는 `META-INF/spring.factories` 파일에 Key-Value 형태로 자동 구성 클래스를 등록했다.

| 항목 | spring.factories | AutoConfiguration.imports |
|------|-----------------|--------------------------|
| 위치 | `META-INF/spring.factories` | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| 형식 | `Key=Value` (properties 형식) | 한 줄에 클래스명 하나 |
| 상태 | Spring Boot 2.7+에서 deprecated | 현재 권장 방식 |

```properties
# 구버전 spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  com.mycorp.libx.autoconfigure.LibXAutoConfiguration,\
  com.mycorp.libx.autoconfigure.LibXWebAutoConfiguration
```

**3. @Conditional 계열 조건부 등록**

자동 구성의 핵심은 조건부 빈 등록이다. 클래스패스에 특정 클래스가 있을 때, 특정 프로퍼티가 설정되었을 때, 사용자가 같은 타입의 빈을 등록하지 않았을 때 등 다양한 조건을 선언할 수 있다.

```java
@AutoConfiguration
@ConditionalOnClass(DataSource.class)            // 클래스패스에 DataSource가 있을 때만
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")  // 프로퍼티가 설정된 경우
public class DataSourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean                    // 사용자가 직접 등록하지 않은 경우에만
    public DataSource dataSource() {
        return new HikariDataSource();
    }
}
```

주요 `@Conditional` 계열 애노테이션은 다음과 같다.

| 애노테이션 | 조건 |
|-----------|------|
| `@ConditionalOnClass` | 지정 클래스가 클래스패스에 있을 때 |
| `@ConditionalOnMissingClass` | 지정 클래스가 클래스패스에 없을 때 |
| `@ConditionalOnBean` | 지정 빈이 컨텍스트에 있을 때 |
| `@ConditionalOnMissingBean` | 지정 빈이 컨텍스트에 없을 때 |
| `@ConditionalOnProperty` | 특정 프로퍼티 값이 설정된 경우 |
| `@ConditionalOnWebApplication` | 웹 애플리케이션 환경일 때 |
| `@ConditionalOnResource` | 특정 리소스 파일이 존재할 때 |
| `@ConditionalOnExpression` | SpEL 식이 true일 때 |

`@ConditionalOnClass`는 클래스를 실제로 로드하지 않고 ASM을 사용해 바이트코드만 검사하므로, 해당 클래스가 없어도 예외가 발생하지 않는다.

**4. 자동 구성 적용 순서 (before/after)**

자동 구성 간 의존 관계가 있을 경우 순서를 명시할 수 있다.

```java
@AutoConfiguration(after = WebMvcAutoConfiguration.class)  // 특정 자동 구성 이후에 적용
public class MyWebAutoConfiguration { }

@AutoConfiguration(before = DataSourceAutoConfiguration.class)  // 특정 자동 구성 이전에 적용
public class MyPreDataSourceAutoConfiguration { }
```

애노테이션 속성 외에도 기존 방식인 `@AutoConfigureBefore`, `@AutoConfigureAfter`, `@AutoConfigureOrder`도 사용 가능하다. 단, 이 순서는 빈 정의(bean definition) 등록 순서에만 영향을 주며, 실제 빈 생성 순서는 의존성 그래프에 의해 결정된다.

### @ComponentScan vs @AutoConfiguration

| 항목 | @ComponentScan | @AutoConfiguration |
|------|---------------|-------------------|
| 탐색 방식 | 베이스 패키지 기반 클래스패스 스캔 | `AutoConfiguration.imports` 파일 기반 명시적 선언 |
| 적용 시점 | 컨텍스트 초기화 초반 | 컴포넌트 스캔 이후 |
| 조건부 등록 | 예측 어려움 | `@Conditional`로 안정적 처리 |
| 외부 라이브러리 지원 | 어려움 (패키지 수동 지정 필요) | 설계 목적 자체가 외부 라이브러리 지원 |
| 사용자 설정 우선 | 명시적 처리 필요 | `@ConditionalOnMissingBean`으로 자동 처리 |
| 주요 용도 | 애플리케이션 코드 빈 등록 | 라이브러리/스타터 자동 구성 |

**사용자 정의 빈이 자동 구성 빈을 오버라이드하는 방식**

자동 구성은 `@ConditionalOnMissingBean`을 활용해 사용자가 직접 빈을 등록하면 자동 구성 빈이 등록되지 않도록 설계된다. 이것이 "backing off" 패턴이다. 컴포넌트 스캔으로 등록된 사용자 빈이 먼저 처리된 후 자동 구성이 실행되므로, `@ConditionalOnMissingBean` 평가 시점에 이미 사용자 빈이 존재해 자동 구성 빈은 등록되지 않는다.

```java
// 사용자가 직접 DataSource를 등록하면
@Configuration
public class MyConfig {
    @Bean
    public DataSource dataSource() {
        return new MyCustomDataSource();
    }
}

// 자동 구성의 @ConditionalOnMissingBean이 이를 감지해 자동 등록 생략
@AutoConfiguration
public class DataSourceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public DataSource dataSource() { ... }  // 등록되지 않음
}
```

## 핵심 정리

- `@ComponentScan`은 애플리케이션 코드의 빈을 등록하는 데 적합하지만, 외부 라이브러리 빈 등록이나 조건부 빈 등록에는 한계가 있다.
- `@AutoConfiguration`은 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일을 통해 외부 라이브러리가 안전하게 빈을 자동 등록할 수 있게 한다.
- `@Conditional` 계열 애노테이션을 통해 환경, 클래스패스, 기존 빈 존재 여부에 따라 선택적으로 빈이 등록된다.
- 사용자가 직접 빈을 등록하면 `@ConditionalOnMissingBean`에 의해 자동 구성 빈은 등록되지 않으므로, 자동 구성은 항상 사용자 설정에 양보한다.
- Spring Boot 2.7부터 `spring.factories` 방식은 deprecated되었고, `AutoConfiguration.imports` 방식이 표준이다.

## 기술적 한계와 보완 전략

**자동 구성 내 @ComponentScan 사용 금지**

자동 구성 클래스(`@AutoConfiguration`) 내부에서 `@ComponentScan`을 사용하면 안 된다. 자동 구성이 적용되는 시점에는 컨텍스트가 아직 완전히 초기화되지 않았을 수 있고, 컴포넌트 스캔이 의도치 않은 패키지까지 탐색할 수 있다. Spring 공식 가이드도 이를 강력히 금지한다.

**@ConditionalOnBean 사용 시 순서 주의**

`@ConditionalOnBean`은 해당 빈이 이미 등록되어 있을 때만 동작한다. 자동 구성 간 순서를 명시하지 않으면 조건이 평가되는 시점에 의존 빈이 아직 등록되지 않아 조건이 실패할 수 있다. 반드시 `@AutoConfiguration(after = ...)` 또는 `@AutoConfigureAfter`로 순서를 보장해야 한다.

**중첩 구성 클래스를 이용한 클래스 조건 격리**

`@ConditionalOnClass`를 `@Bean` 메서드에 직접 붙이면 의도대로 동작하지 않을 수 있다. 해당 클래스가 존재하지 않을 때 `@Bean` 메서드의 반환 타입 로딩 자체가 실패하기 때문이다. 이 경우 중첩 `@Configuration` 클래스를 사용해 클래스 조건을 상위 레벨에서 처리하는 것이 권장된다.

```java
@AutoConfiguration
public final class MyAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SomeService.class)       // 클래스 레벨에서 조건 처리
    static class SomeServiceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SomeService someService() {
            return new SomeService();
        }
    }
}
```

## 키워드

- **@ComponentScan**: 지정된 베이스 패키지 및 하위 패키지를 클래스패스에서 탐색하여 `@Component` 계열 애노테이션이 붙은 클래스를 스프링 빈으로 등록하는 메커니즘.
- **@AutoConfiguration**: Spring Boot 2.7에서 도입된 애노테이션으로, `@Configuration`을 메타 애노테이션으로 가지며 자동 구성 클래스임을 명시한다. `AutoConfiguration.imports` 파일을 통해 발견된다.
- **클래스패스 스캐닝**: JVM 클래스패스 내 패키지를 탐색하여 특정 조건에 맞는 클래스를 찾는 과정. `@ComponentScan`의 핵심 동작이다.
- **AutoConfiguration.imports**: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 경로의 파일로, Spring Boot 2.7+에서 자동 구성 클래스를 선언하는 표준 방식이다.
- **spring.factories**: Spring Boot 2.7 이전에 자동 구성 클래스를 선언하던 `META-INF/spring.factories` 파일. 현재는 deprecated.
- **@Conditional**: 빈 등록 조건을 선언하는 기반 애노테이션. 조건이 true일 때만 해당 빈 또는 구성이 등록된다.
- **@ConditionalOnMissingBean**: 지정한 타입의 빈이 컨텍스트에 아직 등록되지 않은 경우에만 해당 빈을 등록한다. 자동 구성이 사용자 설정에 양보하는 "backing off" 패턴의 핵심.
- **Spring Boot Starter**: 특정 기능에 필요한 의존성을 모아놓은 모듈. auto-configuration 모듈과 쌍으로 구성되어 의존성 추가만으로 자동 구성이 활성화된다.
- **빈 등록 우선순위**: `@ComponentScan`으로 등록된 사용자 빈이 먼저 처리되고, 이후 `@AutoConfiguration` 빈이 `@Conditional` 조건을 평가하며 등록된다. 자동 구성 빈은 항상 사용자 빈보다 후순위다.

## 참고 자료

- [Creating Your Own Auto-configuration - Spring Boot 공식 문서](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
- [Spring Boot Auto-configuration GitHub Issue #15348 - @ComponentScan 사용 경고](https://github.com/spring-projects/spring-boot/issues/15348)
