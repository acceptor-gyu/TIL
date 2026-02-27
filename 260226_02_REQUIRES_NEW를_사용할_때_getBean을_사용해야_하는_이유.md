# REQUIRES_NEW를 사용할 때 getBean()을 사용해야 하는 이유

## 개요
Spring의 `@Transactional(propagation = REQUIRES_NEW)`를 같은 클래스 내부에서 호출하면 새로운 트랜잭션이 생성되지 않는 문제와, 이를 해결하기 위해 `getBean()` 또는 자기 주입(Self-Injection)을 사용해야 하는 이유를 정리한다.

## 상세 내용

---

### 1. Spring AOP 프록시 동작 원리

Spring의 `@Transactional`은 AOP(Aspect-Oriented Programming) 기반으로 동작한다. Spring은 `@Transactional`이 붙은 Bean을 등록할 때 실제 객체가 아닌 **프록시 객체**를 Bean으로 등록한다. 이 프록시가 트랜잭션 시작/커밋/롤백을 담당한다.

#### JDK Dynamic Proxy vs CGLIB Proxy

Spring AOP는 두 가지 프록시 방식을 사용한다.

| 구분 | JDK Dynamic Proxy | CGLIB Proxy |
|------|------------------|-------------|
| 조건 | 인터페이스 구현 시 | 인터페이스 없을 때 (또는 강제 설정) |
| 방식 | JDK 내장, 인터페이스 기반 | 클래스를 상속한 서브클래스 생성 |
| `final` 제약 | 인터페이스 메서드만 | `final` 클래스/메서드는 프록시 불가 |
| Spring Boot 기본값 | Spring Boot 2.0+ 부터 CGLIB 기본 사용 | - |

Spring Boot 2.0 이후에는 `spring.aop.proxy-target-class=true`가 기본값이므로, 대부분의 Spring Boot 애플리케이션은 CGLIB 프록시를 사용한다.

#### 프록시 동작 흐름

```
외부 Bean  -->  [CGLIB Proxy]  -->  실제 서비스 객체
                     |
              트랜잭션 시작/종료
              (AOP Advice 적용)
```

외부에서 Bean을 주입받아 메서드를 호출하면 반드시 프록시를 통해 호출된다. 프록시가 트랜잭션 어드바이스(Advice)를 적용한 뒤 실제 메서드를 실행하는 구조다.

---

### 2. Self-Invocation 문제 상세

**Self-Invocation(자기 호출)** 이란 같은 클래스의 메서드가 `this.method()` 형태로 내부 메서드를 호출하는 것을 말한다.

#### 문제 발생 코드

```java
@Service
public class OrderService {

    @Transactional
    public void processOrder(Order order) {
        // 주문 처리 로직 ...

        // 같은 클래스 내부 메서드 직접 호출
        // 의도: 별도 트랜잭션으로 감사 로그 저장 (REQUIRES_NEW)
        // 실제: this.saveAuditLog()는 프록시를 거치지 않음
        this.saveAuditLog(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(Order order) {
        // 감사 로그 저장
        // 기대: 독립된 새 트랜잭션
        // 실제: processOrder의 기존 트랜잭션에 참여
    }
}
```

#### 왜 프록시가 우회되는가?

```
외부 Bean  -->  [CGLIB Proxy]  -->  OrderService 인스턴스
                                          |
                                    processOrder() 실행
                                          |
                                    this.saveAuditLog() 호출
                                          |
                                  [프록시 우회 - 직접 호출]
                                          |
                                    saveAuditLog() 실행
                                    (REQUIRES_NEW 무시됨)
```

`processOrder()`가 프록시를 통해 진입하면, 그 이후의 내부 호출은 이미 프록시를 벗어난 실제 객체(`this`) 위에서 실행된다. `this`는 프록시가 아닌 원본 객체를 가리키므로 `this.saveAuditLog()`는 AOP 어드바이스 없이 직접 실행된다.

#### 결과

- `REQUIRES_NEW` 전파 속성이 완전히 무시된다.
- `saveAuditLog()`는 `processOrder()`의 기존 트랜잭션에 참여한다.
- `processOrder()`가 롤백되면 `saveAuditLog()`도 함께 롤백된다 (의도한 독립 트랜잭션 분리 실패).

Spring 공식 문서([Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html))에도 이 점을 명시하고 있다.

> "If you have a method in class A that calls another method in the same class, the advice for the second method will not be executed."

---

### 3. 해결 방법 비교

#### 방법 1: `getBean()`으로 프록시 직접 조회

`ApplicationContext`에서 해당 Bean의 프록시 인스턴스를 직접 꺼내 호출하면, 외부 호출과 동일하게 프록시를 거친다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final ApplicationContext applicationContext;

    @Transactional
    public void processOrder(Order order) {
        // 프록시 인스턴스를 직접 가져와 호출
        OrderService proxy = applicationContext.getBean(OrderService.class);
        proxy.saveAuditLog(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(Order order) {
        // 새로운 독립 트랜잭션에서 실행됨
    }
}
```

#### 방법 2: Self-Injection (`@Autowired` 자기 주입)

자기 자신을 `@Autowired`로 주입하면 Spring이 원본 객체가 아닌 프록시를 주입해준다.

```java
@Service
public class OrderService {

    @Lazy
    @Autowired
    private OrderService self;  // 프록시 주입

    @Transactional
    public void processOrder(Order order) {
        self.saveAuditLog(order);  // 프록시를 통한 호출
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(Order order) {
        // 새로운 독립 트랜잭션에서 실행됨
    }
}
```

`@Lazy`를 함께 사용하는 이유: 자기 자신을 주입받는 순환 참조(Circular Dependency)가 발생할 수 있기 때문에, `@Lazy`로 지연 초기화하여 이를 회피한다. Spring 4.3+에서는 `@Lazy` 없이도 동작하는 경우가 있지만, 명시적으로 붙이는 것이 안전하다.

#### 방법 3: 별도 클래스로 분리 (권장)

Spring 공식 문서에서 **가장 권장하는 방법**이다. 별도 클래스로 분리하면 외부 호출이 되므로 프록시를 자연스럽게 통과한다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final AuditLogService auditLogService;

    @Transactional
    public void processOrder(Order order) {
        // 별도 Bean의 메서드 호출 -> 프록시 통과
        auditLogService.saveAuditLog(order);
    }
}

@Service
public class AuditLogService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(Order order) {
        // 새로운 독립 트랜잭션에서 실행됨
    }
}
```

#### 방법 4: `AopContext.currentProxy()`

현재 실행 중인 AOP 프록시를 직접 참조하는 방법이다. Spring 공식 문서에서는 권장하지 않는다.

```java
@Service
public class OrderService {

    @Transactional
    public void processOrder(Order order) {
        // 현재 프록시를 직접 캐스팅하여 호출
        ((OrderService) AopContext.currentProxy()).saveAuditLog(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(Order order) {
        // ...
    }
}
```

이 방법을 사용하려면 `@EnableAspectJAutoProxy(exposeProxy = true)` 설정이 필요하다.

```java
@Configuration
@EnableAspectJAutoProxy(exposeProxy = true)
public class AppConfig { ... }
```

#### 방법 5: `TransactionTemplate` (프로그래밍 방식 트랜잭션)

선언적 `@Transactional` 대신 프로그래밍 방식으로 트랜잭션을 직접 제어하는 방법이다. Self-Invocation 문제 자체를 우회할 수 있다.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final PlatformTransactionManager transactionManager;

    @Transactional
    public void processOrder(Order order) {
        // REQUIRES_NEW 트랜잭션 템플릿 생성
        TransactionTemplate requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        requiresNewTx.execute(status -> {
            // 새로운 독립 트랜잭션에서 실행됨
            saveAuditLog(order);
            return null;
        });
    }

    private void saveAuditLog(Order order) {
        // private 메서드로 선언 가능 (TransactionTemplate이 트랜잭션 관리)
    }
}
```

`TransactionTemplate`은 프록시를 거치지 않고 `PlatformTransactionManager`를 직접 호출하므로 Self-Invocation 문제가 없다.

---

### 4. 각 해결 방법의 장단점과 권장 사항

| 방법 | 장점 | 단점 | 권장 여부 |
|------|------|------|----------|
| **클래스 분리** | 가장 명확한 책임 분리, 테스트 용이, Spring 권장 방식 | 클래스 수 증가 | **최우선 권장** |
| **Self-Injection** | 코드 이동 없이 해결 가능 | 순환 참조 위험, `@Lazy` 필요, 코드 가독성 저하 | 일시적 해결책 |
| **getBean()** | ApplicationContext 접근으로 직관적 | ApplicationContext 의존성 증가, 테스트 어려움 | 제한적 사용 |
| **TransactionTemplate** | 프록시 우회 불필요, 세밀한 트랜잭션 제어 | 선언적 방식보다 코드 복잡, 콜백 구조 | 복잡한 조건 제어 시 적합 |
| **AopContext** | 간단한 코드 | Spring 강결합, `exposeProxy` 설정 필요, 공식 비권장 | **비권장** |

#### 테스트 관점 비교

- **클래스 분리**: 각 클래스를 독립적으로 단위 테스트 가능
- **Self-Injection / getBean()**: 단위 테스트에서 ApplicationContext나 Mock 설정이 복잡해짐
- **TransactionTemplate**: `PlatformTransactionManager`를 Mock으로 교체하면 단위 테스트 가능

---

### 5. 실무 적용 시 주의사항

#### 예외 전파와 롤백 동작

`REQUIRES_NEW`는 부모 트랜잭션과 완전히 독립된 트랜잭션을 생성한다. 따라서 예외 전파 관계를 잘 이해해야 한다.

```java
@Transactional
public void processOrder(Order order) {
    // 부모 트랜잭션

    orderRepository.save(order);

    try {
        auditLogService.saveAuditLog(order); // REQUIRES_NEW - 자식 트랜잭션
    } catch (Exception e) {
        // 자식 트랜잭션이 롤백되더라도 부모 트랜잭션에는 영향 없음
        log.warn("감사 로그 저장 실패", e);
    }

    // 부모 트랜잭션은 정상 커밋됨
}
```

반대로 부모 트랜잭션이 롤백되더라도 이미 커밋된 자식 트랜잭션(`REQUIRES_NEW`)은 롤백되지 않는다.

```
부모 TX 시작
  |-- 작업 A
  |-- 자식 TX (REQUIRES_NEW) 시작
  |     |-- 작업 B
  |     |-- 자식 TX 커밋 (독립적으로 커밋 완료)
  |-- 작업 C
  |-- 부모 TX 롤백 (작업 A, C만 롤백 / 작업 B는 이미 커밋되어 유지)
```

#### Connection Pool 고갈 주의

`REQUIRES_NEW`를 사용하면 **하나의 스레드가 두 개의 DB 커넥션을 동시에 점유**한다. 부모 트랜잭션의 커넥션은 자식 트랜잭션이 완료될 때까지 반납되지 않는다.

```
스레드 A: [커넥션 1 - 부모 TX 보유] ----대기----> [커넥션 2 - REQUIRES_NEW 자식 TX 요청]
스레드 B: [커넥션 3 - 부모 TX 보유] ----대기----> [커넥션 4 - REQUIRES_NEW 자식 TX 요청]
...
스레드 N: [커넥션 N - 부모 TX 보유] ----대기----> [커넥션 없음! -> 데드락]
```

Connection Pool이 10개일 때 동시 요청이 10개 들어오면, 모든 커넥션이 부모 TX에 의해 점유된 상태에서 자식 TX를 위한 커넥션을 기다리다 타임아웃이 발생할 수 있다.

**실무 대응책:**

- HikariCP `maximumPoolSize`를 넉넉하게 설정 (REQUIRES_NEW 빈도를 고려)
- REQUIRES_NEW 사용 빈도를 최소화
- Connection Pool 모니터링으로 고갈 조기 감지
- 트랜잭션 범위를 최대한 짧게 유지

---

## 핵심 정리

1. **Spring `@Transactional`은 프록시 기반으로 동작**한다. 외부 Bean에서 호출할 때만 프록시를 거치며 트랜잭션이 적용된다.
2. **같은 클래스 내부 호출(Self-Invocation)은 프록시를 우회**한다. `this.method()` 형태의 내부 호출은 AOP 어드바이스가 적용되지 않아 `REQUIRES_NEW`와 같은 전파 속성이 무시된다.
3. **가장 권장하는 해결책은 클래스 분리**다. `REQUIRES_NEW`가 필요한 메서드를 별도의 Spring Bean으로 분리하면 자연스럽게 외부 호출이 되어 프록시를 통과한다.
4. **`getBean()` 또는 Self-Injection**은 클래스 분리가 어려운 경우의 차선책이다. `ApplicationContext`에서 꺼낸 인스턴스나 `@Lazy @Autowired`로 주입받은 자기 자신은 프록시 객체이므로 `REQUIRES_NEW`가 정상 동작한다.
5. **`REQUIRES_NEW`는 동시에 DB 커넥션 2개를 점유**하므로, Connection Pool 크기와 동시 요청 수를 반드시 고려해야 한다.

---

## 키워드

- `Spring AOP Proxy`: Spring이 `@Transactional`, `@Async` 등의 어노테이션 처리를 위해 실제 Bean을 감싸 생성하는 프록시 객체. JDK Dynamic Proxy 또는 CGLIB 방식으로 생성된다.
- `@Transactional`: 선언적 트랜잭션 관리 어노테이션. 프록시 기반으로 동작하므로 외부 호출에서만 어드바이스가 적용된다.
- `REQUIRES_NEW`: 트랜잭션 전파 속성 중 하나. 기존 트랜잭션을 일시 정지하고 새로운 독립 트랜잭션을 시작한다. DB 커넥션을 추가로 점유한다.
- `Self-Invocation`: 같은 클래스의 메서드가 `this.method()` 형태로 자신의 다른 메서드를 호출하는 것. Spring AOP 프록시를 우회하여 어드바이스가 적용되지 않는다.
- `getBean()`: `ApplicationContext.getBean()`으로 Spring Container에서 Bean의 프록시 인스턴스를 직접 꺼내 오는 방법. Self-Invocation 문제를 우회하기 위해 사용된다.
- `Self-Injection`: `@Lazy @Autowired`로 자기 자신을 주입받는 패턴. 주입되는 인스턴스는 프록시 객체이므로 AOP 어드바이스가 정상 적용된다.
- `CGLIB Proxy`: 클래스를 상속하여 프록시 서브클래스를 생성하는 방식. Spring Boot 2.0+에서 기본 프록시 방식이며, `final` 클래스나 메서드는 적용 불가하다.
- `TransactionTemplate`: 프로그래밍 방식으로 트랜잭션을 제어하는 Spring 클래스. `PlatformTransactionManager`를 직접 사용하므로 Self-Invocation 문제가 없다.
- `트랜잭션 전파`: 이미 진행 중인 트랜잭션이 있을 때 새로운 트랜잭션 메서드 호출 시 어떻게 동작할지 결정하는 설정. `REQUIRED`, `REQUIRES_NEW`, `NESTED` 등이 있다.
- `Connection Pool`: DB 커넥션을 미리 생성해두고 재사용하는 풀. `REQUIRES_NEW` 사용 시 부모/자식 트랜잭션이 각각 커넥션을 점유하므로 고갈에 주의해야 한다.

---

## 참고 자료

- [Spring Framework - Proxying Mechanisms (공식 문서)](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)
- [Spring Framework - @Transactional Annotation (공식 문서)](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Spring Framework - Programmatic Transaction Management (공식 문서)](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- [Spring Transaction Management In-Depth - Marco Behler](https://www.marcobehler.com/guides/spring-transaction-management-transactional-in-depth)
- [Spring Framework GitHub - REQUIRES_NEW Connection Pool Deadlock Issue](https://github.com/spring-projects/spring-framework/issues/26250)
