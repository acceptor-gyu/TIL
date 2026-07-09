# CGLIB 프록시와 @Configuration

## 개요

Spring이 `@Configuration` 클래스를 어떻게 다루는지, 그리고 CGLIB 프록시가 왜 필요한지와 동작 원리를 정리한다.

`@Configuration`은 단순한 마커 어노테이션이 아니다. Spring은 이 어노테이션이 붙은 클래스를 CGLIB으로 서브클래싱하여 `@Bean` 메서드 호출을 가로채고, 싱글톤 인스턴스를 보장한다.

## 상세 내용

### 1. CGLIB 프록시란

`@Configuration`을 이해하기 전에 CGLIB 프록시가 무엇인지 간략히 짚고 넘어간다. (자세한 내용은 [CGLIB 프록시](260619_02_CGLIB_프록시.md) 참고)

Spring은 AOP, 트랜잭션, 캐시 등 부가 기능을 원본 코드에 손대지 않고 적용하기 위해 **프록시 패턴**을 사용한다. 프록시를 만드는 방법은 두 가지다.

| 구분 | JDK Dynamic Proxy | CGLIB 프록시 |
|------|------------------|-------------|
| 기반 | 인터페이스 구현 | 클래스 서브클래싱 |
| 필요 조건 | 인터페이스 필수 | 인터페이스 불필요 |
| 방식 | 런타임에 인터페이스를 구현한 클래스 생성 | 런타임에 대상 클래스를 상속한 서브클래스 생성 |

`@Configuration` 클래스는 인터페이스를 구현하지 않으므로 CGLIB 방식으로 프록시가 생성된다. Spring이 `@Configuration` 클래스를 처리할 때 그 클래스를 상속한 CGLIB 서브클래스를 만들어 빈으로 등록한다.

---

### 2. @Configuration과 프록시

#### proxyBeanMethods = true (기본값)

`@Configuration`의 `proxyBeanMethods` 속성은 기본값이 `true`다. 이 상태에서 Spring은 해당 클래스를 **Full Mode**로 처리한다.

```java
@Configuration
public class AppConfig {

    @Bean
    public MemberRepository memberRepository() {
        return new MemberRepository();
    }

    @Bean
    public MemberService memberService() {
        // memberRepository()를 직접 호출
        return new MemberService(memberRepository());
    }

    @Bean
    public OrderService orderService() {
        // memberRepository()를 또 직접 호출
        return new OrderService(memberRepository());
    }
}
```

위 코드에서 `memberRepository()`가 세 번 호출되는 것처럼 보인다. 하지만 `@Configuration`의 CGLIB 프록시가 이 호출을 가로채기 때문에 실제로는 항상 같은 인스턴스가 반환된다.

#### @Bean 메서드 호출이 가로채지는 과정

Spring이 `AppConfig`를 처리하는 흐름은 다음과 같다.

```
애플리케이션 구동
  → ConfigurationClassPostProcessor가 @Configuration 클래스 탐지
  → ConfigurationClassEnhancer가 CGLIB 서브클래스 생성
  → BeanMethodInterceptor가 @Bean 메서드에 등록됨
  → AppConfig$$EnhancerBySpringCGLIB$$xxxx 인스턴스를 빈으로 등록
```

`memberRepository()`가 호출될 때마다 `BeanMethodInterceptor`가 동작한다.

```
memberRepository() 호출
  → BeanMethodInterceptor.intercept() 실행
  → ApplicationContext에 'memberRepository' 빈이 이미 있는가?
      → 있으면: 기존 인스턴스 반환
      → 없으면: 실제 메서드를 호출하여 빈을 생성하고 등록한 뒤 반환
```

따라서 `memberService()`와 `orderService()`가 각각 `memberRepository()`를 직접 호출해도 항상 같은 `MemberRepository` 인스턴스를 받는다. **싱글톤이 보장된다.**

#### 싱글톤 보장 확인

```java
@SpringBootApplication
public class App {

    public static void main(String[] args) {
        ApplicationContext ctx = SpringApplication.run(App.class, args);

        MemberService memberService = ctx.getBean(MemberService.class);
        OrderService orderService = ctx.getBean(OrderService.class);

        // 두 서비스가 참조하는 MemberRepository가 동일한 인스턴스인지 확인
        System.out.println(memberService.getMemberRepository() == orderService.getMemberRepository());
        // true
    }
}
```

---

### 3. @Configuration vs @Component (Lite Mode)

#### Lite Mode란

`@Bean` 메서드가 CGLIB 프록시 없이 처리되는 모드를 **Lite Mode**라고 한다. 다음 두 경우에 해당한다.

1. `@Component`, `@Service`, `@Repository` 등 `@Configuration`이 아닌 클래스에 `@Bean`을 정의한 경우
2. `@Configuration(proxyBeanMethods = false)`로 명시적으로 프록시를 비활성화한 경우

```java
// Lite Mode - @Component
@Component
public class AppConfig {

    @Bean
    public MemberRepository memberRepository() {
        return new MemberRepository();
    }

    @Bean
    public MemberService memberService() {
        return new MemberService(memberRepository()); // 매번 새 인스턴스 생성
    }
}
```

```java
// Lite Mode - proxyBeanMethods = false
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    public MemberRepository memberRepository() {
        return new MemberRepository();
    }

    @Bean
    public MemberService memberService() {
        return new MemberService(memberRepository()); // 매번 새 인스턴스 생성
    }
}
```

Lite Mode에서는 `memberRepository()`가 일반 Java 메서드처럼 동작한다. 호출할 때마다 `new MemberRepository()`가 실행되어 **새로운 인스턴스가 반환된다.** 컨테이너의 싱글톤 캐시를 확인하지 않는다.

#### Lite Mode에서 발생하는 빈 중복 생성 문제

```java
@Component
public class AppConfig {

    @Bean
    public MemberRepository memberRepository() {
        return new MemberRepository();
    }

    @Bean
    public MemberService memberService() {
        return new MemberService(memberRepository()); // 새 MemberRepository 인스턴스 A
    }

    @Bean
    public OrderService orderService() {
        return new OrderService(memberRepository()); // 새 MemberRepository 인스턴스 B
    }
}
```

`memberRepository` 빈은 컨테이너에 하나 등록되지만, `memberService`와 `orderService`가 참조하는 `MemberRepository`는 각각 다른 인스턴스다. 의도치 않은 객체 생성과 상태 불일치가 발생할 수 있다.

#### Lite Mode의 올바른 사용법

Lite Mode에서는 메서드 간 `@Bean` 참조 대신 **파라미터 주입**을 사용해야 한다.

```java
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    public MemberRepository memberRepository() {
        return new MemberRepository();
    }

    @Bean
    public MemberService memberService(MemberRepository memberRepository) {
        // 파라미터로 주입받으면 컨테이너의 싱글톤 빈이 전달됨
        return new MemberService(memberRepository);
    }

    @Bean
    public OrderService orderService(MemberRepository memberRepository) {
        return new OrderService(memberRepository);
    }
}
```

Spring은 `@Bean` 메서드의 파라미터를 자동으로 컨테이너에서 주입한다. 이 방식은 Lite Mode에서도 싱글톤 인스턴스가 올바르게 전달된다.

#### 언제 proxyBeanMethods = false를 사용하는가

| 상황 | 권장 모드 |
|------|----------|
| `@Bean` 메서드 간에 서로를 직접 호출하는 경우 | Full Mode (기본값) |
| `@Bean` 메서드 간 의존이 없거나 파라미터로만 의존하는 경우 | Lite Mode |
| Spring Native / GraalVM 네이티브 이미지 빌드 | Lite Mode 필수 |
| 시작 시간과 메모리 사용량 최적화 | Lite Mode |
| 자동 설정(`@AutoConfiguration`) 클래스 | Lite Mode 권장 |

Spring Boot의 내부 자동 설정 클래스들은 대부분 `@Configuration(proxyBeanMethods = false)`를 사용한다. 자동 설정 클래스는 수백 개이므로 CGLIB 서브클래스 생성 비용을 줄이는 것이 기동 성능에 영향을 준다.

---

### 4. 동작 검증

#### 프록시로 생성된 빈의 클래스명 확인

```java
@SpringBootApplication
public class App {

    public static void main(String[] args) {
        ApplicationContext ctx = SpringApplication.run(App.class, args);

        AppConfig config = ctx.getBean(AppConfig.class);
        System.out.println(config.getClass().getName());
    }
}
```

Full Mode (`proxyBeanMethods = true`) 출력:

```
com.example.AppConfig$$SpringCGLIB$$0
```

Lite Mode (`proxyBeanMethods = false`) 출력:

```
com.example.AppConfig
```

Full Mode에서는 `$$SpringCGLIB$$` 접미사가 붙은 서브클래스가 빈으로 등록된다는 것을 확인할 수 있다. (Spring 6.x부터 네이밍이 `$$EnhancerBySpringCGLIB$$`에서 `$$SpringCGLIB$$`로 변경됨)

#### @Bean 메서드 직접 호출 시 인스턴스 동일성 비교

```java
@Configuration
public class AppConfig {

    @Bean
    public DataSource dataSource() {
        return new EmbeddedDatabaseBuilder().build();
    }
}

// 검증 코드
@SpringBootTest
class AppConfigTest {

    @Autowired
    private AppConfig appConfig;

    @Autowired
    private DataSource dataSource;

    @Test
    void beanMethodCallReturnsSameInstance() {
        // @Configuration 프록시가 intercept하여 컨테이너의 빈을 반환
        assertThat(appConfig.dataSource()).isSameAs(dataSource);
        assertThat(appConfig.dataSource()).isSameAs(appConfig.dataSource()); // true
    }
}
```

`appConfig.dataSource()`를 몇 번을 호출해도 같은 인스턴스가 반환된다.

---

## 핵심 정리

- `@Configuration`은 기본적으로 CGLIB 프록시로 감싸져 `@Bean` 메서드 호출의 싱글톤을 보장한다.
- CGLIB은 상속 기반이므로 `final` 클래스/메서드에는 적용할 수 없다.
- `proxyBeanMethods = false`(Lite Mode)로 두면 CGLIB 서브클래스 생성 비용이 사라지지만, 메서드 간 `@Bean` 참조 시 빈이 중복 생성될 수 있다.
- Lite Mode에서는 반드시 **파라미터 주입**으로 빈 간 의존을 표현해야 한다.
- GraalVM 네이티브 이미지, 자동 설정 클래스에는 `proxyBeanMethods = false`가 권장된다.

## 기술적 한계와 보완 전략

| 한계 | 보완 전략 |
|------|----------|
| `final` 클래스/메서드는 CGLIB 프록시 대상이 될 수 없음 | `final` 키워드 제거, 또는 `proxyBeanMethods = false`로 전환 후 파라미터 주입 방식으로 변경 |
| CGLIB 서브클래스 생성으로 기동 시간 증가 | `proxyBeanMethods = false` 사용. 특히 자동 설정 클래스에 권장 |
| Lite Mode에서 메서드 간 직접 호출 시 빈 중복 생성 | `@Bean` 메서드 파라미터로 의존성을 선언하여 컨테이너에서 주입 |
| 생성자에서 부작용이 있는 객체의 이중 초기화 위험 | Spring 4.0+ Objenesis로 해결됨. 단, 의존 관계 복잡성 최소화 권장 |

## 키워드

- **CGLIB (Code Generation Library)**: 바이트코드를 동적으로 생성하여 런타임에 클래스의 서브클래스를 만들어내는 라이브러리. `@Configuration` 처리에서 핵심 역할을 한다.

- **JDK Dynamic Proxy**: `java.lang.reflect.Proxy`를 이용한 인터페이스 기반 동적 프록시. `@Configuration`은 인터페이스가 없으므로 해당되지 않는다.

- **@Configuration**: 해당 클래스가 빈 정의의 소스임을 나타내는 어노테이션. 기본적으로 CGLIB 서브클래스가 생성되어 `@Bean` 메서드 호출을 가로채고 싱글톤을 보장한다.

- **proxyBeanMethods**: `@Configuration`의 속성. `true`(기본값)이면 CGLIB 프록시를 생성하여 `@Bean` 메서드 호출을 인터셉트하고, `false`이면 CGLIB 서브클래스를 생성하지 않는다.

- **Lite Mode**: `proxyBeanMethods = false`이거나 `@Component` 등 `@Configuration`이 아닌 클래스에 `@Bean`을 정의할 때 적용되는 처리 모드. 메서드 간 직접 호출 시 새 인스턴스가 생성된다.

- **싱글톤 (Singleton)**: Spring의 기본 빈 스코프. 애플리케이션 컨텍스트 내에서 하나의 인스턴스만 존재한다. CGLIB 프록시는 `@Bean` 메서드 직접 호출 시에도 이 싱글톤을 보장한다.

- **@Bean**: Spring 컨테이너에 빈으로 등록할 객체를 반환하는 메서드에 붙이는 어노테이션. XML의 `<bean/>` 요소에 대응한다.

- **바이트코드 조작**: 소스 코드 컴파일 없이 런타임에 JVM 바이트코드를 직접 생성하거나 변환하는 기법. CGLIB은 이를 통해 `@Configuration` 클래스의 서브클래스를 만든다.

- **서브클래싱 (Subclassing)**: CGLIB이 프록시를 생성하는 방식. 대상 클래스를 상속한 하위 클래스를 동적으로 생성하여 메서드를 오버라이드한다.

- **ConfigurationClassPostProcessor**: Spring 내부의 `BeanFactoryPostProcessor`. `@Configuration` 클래스를 탐지하고 `ConfigurationClassEnhancer`를 통해 CGLIB 서브클래스를 생성하는 역할을 한다.

## 참고 자료

- [Spring Framework 공식 문서 - Basic Concepts: @Bean and @Configuration](https://docs.spring.io/spring-framework/reference/core/beans/java/basic-concepts.html)
- [Spring Framework JavaDoc - @Configuration](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Configuration.html)
- [CGLIB Proxying in Spring @Configuration - Java By Examples](https://www.javabyexamples.com/cglib-proxying-in-spring-configuration)
- [Spring Boot Configuration proxy bean methods - Dan Vega](https://www.danvega.dev/blog/spring-proxy-bean-methods)
