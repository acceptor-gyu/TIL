# Record를 DTO로 사용하는 이유는 무엇인가

## 개요
Java 14에서 preview로 도입되고 Java 16에서 정식 릴리스된 Record가 왜 DTO(Data Transfer Object)로 적합한지 알아본다.

## 상세 내용

### 1. Record란 무엇인가

Record는 JEP 395를 통해 Java 16에서 정식 도입된 **불변 데이터 캐리어(immutable data carrier)** 클래스다. 설계 목적은 "데이터를 데이터로 모델링"하는 것이며, 개발자가 반복적으로 작성하던 보일러플레이트 코드를 언어 차원에서 제거하는 데 있다.

JEP 395 명세에 따르면 Record는 "명목적 튜플(nominal tuple)"로, 데이터의 집합을 간결하게 표현하는 수단이다.

**Record 선언 예시**

```java
public record UserResponse(Long id, String name, String email) {}
```

위 한 줄 선언으로 컴파일러가 자동 생성하는 것들은 다음과 같다.

| 자동 생성 항목 | 설명 |
|---|---|
| private final 필드 | 각 컴포넌트에 대한 불변 필드 |
| 정규 생성자 (Canonical Constructor) | 모든 필드를 초기화하는 생성자 |
| 접근자 메서드 (Accessor) | `id()`, `name()`, `email()` 형태의 getter |
| `equals()` / `hashCode()` | 모든 필드 값 기반의 동등성 비교 |
| `toString()` | 모든 컴포넌트를 포함한 문자열 표현 |

**기존 class와의 비교**

```java
// 기존 class 방식 - 수십 줄 필요
public class UserResponse {
    private final Long id;
    private final String name;
    private final String email;

    public UserResponse(Long id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }

    @Override
    public boolean equals(Object o) { /* ... */ }
    @Override
    public int hashCode() { /* ... */ }
    @Override
    public String toString() { /* ... */ }
}

// Record 방식 - 한 줄로 동일한 효과
public record UserResponse(Long id, String name, String email) {}
```

---

### 2. 기존 Class 기반 DTO의 문제점

**보일러플레이트 코드 과다**

전통적인 DTO 클래스는 필드 선언 외에도 생성자, getter/setter, `equals()`, `hashCode()`, `toString()`을 직접 작성해야 한다. 필드가 10개라면 코드는 수백 줄에 달하며, 필드를 추가/삭제할 때마다 관련 메서드를 모두 수정해야 한다.

**Lombok 의존성 문제와 한계**

Lombok은 어노테이션 프로세싱을 통해 보일러플레이트를 줄여주지만 몇 가지 문제가 있다.

- **빌드 도구 의존성**: 컴파일 타임에 Lombok 플러그인이 필요하며, IDE 설정이 별도로 필요하다.
- **디버깅 어려움**: 생성된 코드가 소스에 보이지 않아 문제 추적이 어렵다.
- **Java 버전 호환 문제**: 새로운 Java 버전에서 Lombok이 즉시 동작하지 않을 수 있다.
- **언어 명세 외부**: Lombok은 Java 언어 명세가 아닌 외부 라이브러리이므로 언어 수준의 보장이 없다.

**가변(mutable) 객체로 인한 부작용**

일반 class로 DTO를 만들면 setter를 통해 객체 상태가 의도치 않게 변경될 수 있다. 특히 여러 레이어를 거쳐 전달되는 과정에서 데이터가 변조될 위험이 있다.

```java
// setter가 있으면 어디서든 상태 변경 가능 - 위험
UserResponse response = new UserResponse(1L, "홍길동", "hong@example.com");
response.setName("다른 사람"); // 의도치 않은 변경
```

---

### 3. Record가 DTO에 적합한 이유

**불변 객체 보장**

Record의 모든 필드는 암묵적으로 `private final`이며 setter가 존재하지 않는다. 한 번 생성된 Record 인스턴스는 절대 변경되지 않으므로 여러 레이어를 안전하게 통과할 수 있다.

```java
public record UserResponse(Long id, String name, String email) {}

UserResponse response = new UserResponse(1L, "홍길동", "hong@example.com");
// response.name = "다른 사람"; // 컴파일 에러 - 불변
```

**보일러플레이트 제거로 인한 코드 간결성**

컴파일러가 필요한 코드를 자동 생성하므로 개발자는 데이터 구조 정의에만 집중할 수 있다. 필드를 추가하거나 삭제해도 equals, hashCode, toString이 자동으로 반영된다.

**값 기반 동등성 (Value-based equality)**

Record의 `equals()`는 참조가 아닌 **모든 필드 값**을 기준으로 비교한다. DTO는 동일한 데이터를 담으면 동일한 객체로 간주하는 것이 자연스럽기 때문에 이 특성이 DTO 용도에 정확히 부합한다.

```java
UserResponse a = new UserResponse(1L, "홍길동", "hong@example.com");
UserResponse b = new UserResponse(1L, "홍길동", "hong@example.com");

System.out.println(a.equals(b)); // true - 값 기반 비교
```

**명확한 의도 전달**

코드에서 `record`를 보는 순간 "이 타입은 데이터 전달 목적의 불변 객체"임을 즉시 알 수 있다. 이는 코드의 의도를 명확히 전달하는 자기 문서화(self-documenting) 효과를 제공한다.

---

### 4. Record를 DTO로 사용하는 실전 패턴

**Controller 요청/응답 DTO로 활용**

```java
// 요청 DTO
public record CreateUserRequest(String name, String email, int age) {}

// 응답 DTO
public record UserResponse(Long id, String name, String email) {
    // 정적 팩토리 메서드로 Entity -> DTO 변환
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}

@RestController
@RequestMapping("/users")
public class UserController {

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody @Valid CreateUserRequest request) {
        User user = userService.create(request);
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
```

**Record와 Jackson(JSON 직렬화/역직렬화) 호환성**

Jackson 2.12 이상에서는 Record를 기본으로 지원한다. Record의 접근자 메서드(getter)를 자동으로 인식하여 직렬화하고, 정규 생성자를 통해 역직렬화한다.

```java
// 별도 어노테이션 없이 Jackson 직렬화/역직렬화 동작
public record ProductResponse(Long id, String name, BigDecimal price) {}

// JSON -> Record 역직렬화 시 Jackson이 정규 생성자를 자동 사용
// Record -> JSON 직렬화 시 id(), name(), price() 접근자 메서드 사용
```

Spring Boot에서 jackson-databind를 사용한다면 별도 설정 없이 동작한다. 단, `@JsonProperty`를 통해 JSON 필드명을 커스터마이즈할 수 있다.

```java
public record ProductResponse(
    Long id,
    @JsonProperty("product_name") String name,
    BigDecimal price
) {}
```

**Compact Constructor를 활용한 유효성 검증**

Compact Constructor는 Record의 특별한 생성자 형태로, 매개변수 목록 없이 선언하며 컴파일러가 자동으로 필드 할당을 추가한다. 이를 활용해 객체 생성 시점에 불변식(invariant)을 강제할 수 있다.

```java
public record CreateUserRequest(String name, String email, int age) {

    // Compact Constructor: 매개변수 없이 선언, 검증 로직만 작성
    public CreateUserRequest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름은 필수입니다.");
        }
        if (age < 0 || age > 150) {
            throw new IllegalArgumentException("나이는 0~150 사이여야 합니다.");
        }
        // 필드 정규화도 가능 (name = name.trim()은 컴파일러가 할당 전 수행)
        name = name.trim();
        email = email.toLowerCase();
    }
}
```

Bean Validation(`@Valid`)과 함께 사용하면 더욱 선언적인 방식으로 검증할 수 있다.

```java
public record CreateUserRequest(
    @NotBlank(message = "이름은 필수입니다.")
    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    String name,

    @NotBlank @Email(message = "올바른 이메일 형식이 아닙니다.")
    String email,

    @Min(value = 0) @Max(value = 150)
    int age
) {}
```

**중첩 Record를 활용한 복합 응답 구조**

```java
public record OrderResponse(
    Long orderId,
    UserResponse user,
    List<OrderItemResponse> items,
    BigDecimal totalPrice
) {
    public record UserResponse(Long id, String name) {}
    public record OrderItemResponse(Long productId, String productName, int quantity) {}
}
```

---

### 5. Record를 DTO로 사용할 때 주의할 점

**JPA Entity로는 사용할 수 없는 이유**

JPA/Hibernate는 엔티티 클래스에 다음 조건을 요구한다.

1. **기본 생성자(no-args constructor)**: Hibernate가 리플렉션으로 인스턴스를 생성할 때 필요
2. **non-final 클래스**: Hibernate가 지연 로딩(lazy loading)을 위해 프록시 클래스를 생성할 때 엔티티 클래스를 상속해야 하는데, Record는 `final`이므로 상속 불가
3. **가변 필드(non-final fields)**: Hibernate가 dirty checking으로 변경된 필드를 감지하고 UPDATE 쿼리를 생성하려면 필드 값이 변경될 수 있어야 함

Record는 세 조건 모두를 위반하므로 JPA Entity로 사용할 수 없다. 단, Hibernate 6 이상에서는 `@Embeddable`로 Record를 제한적으로 사용할 수 있다.

**상속 불가 (Record는 암묵적으로 final)**

Record는 내부적으로 `java.lang.Record`를 상속하며 암묵적으로 `final`이다. 다른 클래스를 상속하거나 다른 Record에 의해 상속될 수 없다. 공통 필드를 공유해야 할 경우 인터페이스(interface)를 활용해야 한다.

```java
// 인터페이스로 공통 계약 정의
public interface Identifiable {
    Long id();
}

public record UserResponse(Long id, String name) implements Identifiable {}
public record ProductResponse(Long id, String productName) implements Identifiable {}
```

**Spring의 @ModelAttribute 바인딩 시 주의사항**

`@RequestBody`(JSON 바인딩)는 Jackson이 처리하므로 Record와 잘 동작하지만, HTML 폼 데이터를 처리하는 `@ModelAttribute`는 기본적으로 setter 기반 바인딩을 사용한다.

Spring Framework 6.1 이상에서는 Record를 `@ModelAttribute`에서 생성자 기반으로 바인딩하는 것을 지원하지만, 이전 버전에서는 동작하지 않을 수 있다. REST API 위주의 개발에서는 `@RequestBody`를 사용하므로 이 제약이 실질적으로 문제가 되는 경우는 드물다.

**Record와 Bean Validation(@Valid) 활용 방법**

Bean Validation 어노테이션은 Record의 컴포넌트(파라미터)에 직접 선언한다. Spring MVC에서 `@Valid` 또는 `@Validated`와 함께 사용하면 Controller에 요청이 들어올 때 자동으로 검증된다.

```java
public record CreatePostRequest(
    @NotBlank String title,
    @NotNull @Size(min = 10) String content
) {}

@PostMapping("/posts")
public ResponseEntity<PostResponse> create(@RequestBody @Valid CreatePostRequest request) {
    // 이 시점에서 request는 이미 검증 완료된 상태
    return ResponseEntity.ok(postService.create(request));
}
```

---

### 6. Record vs Lombok @Value vs 일반 Class DTO 비교

| 비교 항목 | Record | Lombok @Value | 일반 Class |
|---|---|---|---|
| 코드량 | 최소 (한 줄) | 적음 (어노테이션) | 많음 (수십~수백 줄) |
| 불변성 | 언어 수준 보장 | 어노테이션으로 보장 | 개발자가 직접 구현 |
| 외부 의존성 | 없음 (JDK 내장) | Lombok 필요 | 없음 |
| 상속 | 불가 (final) | 불가 (final) | 가능 |
| Builder 패턴 | 미지원 (직접 구현) | @Builder 어노테이션 지원 | 직접 구현 |
| 가독성 | 매우 높음 | 높음 | 낮음 |
| IDE 지원 | JDK 내장이므로 완벽 | 플러그인 필요 | 기본 지원 |
| Java 최소 버전 | Java 16 | Java 8+ | Java 8+ |
| 의도 명확성 | "데이터 전달 객체"임을 명시 | 불분명 | 불분명 |

**선택 기준**

- **Record**: Java 16 이상 환경, 간단한 불변 DTO, 외부 의존성을 최소화하고 싶을 때
- **Lombok @Value**: Java 16 미만 환경 또는 @Builder와 함께 복잡한 생성 패턴이 필요할 때
- **일반 Class**: JPA Entity처럼 가변성이 필요하거나, 복잡한 상속 구조가 필요할 때

---

## 핵심 정리

- Record는 불변 데이터 캐리어를 간결하게 표현하기 위해 Java 16에서 도입된 기능으로, 생성자/getter/equals/hashCode/toString을 자동 생성한다.
- DTO는 본질적으로 "데이터를 전달하는 불변 객체"이므로 Record의 설계 철학과 정확히 일치하며, 보일러플레이트 없이 안전하게 사용할 수 있다.
- JPA Entity에는 사용할 수 없으며 (final 클래스, 기본 생성자 없음, 불변 필드), @ModelAttribute 바인딩은 Spring 버전에 따라 제약이 있을 수 있다.

## 키워드

`Java Record` - Java 16(JEP 395)에서 정식 도입된 불변 데이터 캐리어 클래스. 보일러플레이트를 자동 생성하며, 암묵적으로 final이다.

`DTO` - Data Transfer Object. 레이어 간 데이터 전달을 위한 객체로, 비즈니스 로직 없이 데이터 구조만 표현한다.

`Data Transfer Object` - 계층(Controller, Service, Repository) 사이에서 데이터를 전달하는 역할을 하는 객체 패턴.

`불변 객체` - 생성 후 상태가 변경되지 않는 객체. 스레드 안전성을 보장하고 부작용을 방지한다.

`Immutable` - 불변성(Immutability). 객체 생성 이후 내부 상태가 절대 변하지 않는 성질.

`보일러플레이트` - 반복적으로 작성해야 하는 상투적인 코드. getter/setter/equals/hashCode/toString 등이 대표적이다.

`Lombok` - 어노테이션 프로세싱을 통해 Java 보일러플레이트 코드를 자동 생성하는 외부 라이브러리.

`값 객체` - 식별자가 아닌 필드 값으로 동등성을 판단하는 객체. Record의 equals()가 이 방식으로 동작한다.

`Compact Constructor` - Record 전용 생성자 선언 방식. 매개변수 목록을 생략하고 검증/정규화 로직만 작성하면 컴파일러가 필드 할당 코드를 자동 추가한다.

`Jackson 직렬화` - Jackson 라이브러리를 사용해 Java 객체를 JSON으로 변환(직렬화)하거나 JSON을 Java 객체로 변환(역직렬화)하는 과정. Jackson 2.12+는 Record를 기본 지원한다.

## 참고 자료
- [JEP 395: Records (OpenJDK 공식 명세)](https://openjdk.org/jeps/395)
- [JEP 359: Records Preview (OpenJDK)](https://openjdk.org/jeps/359)
- [Java Records - Oracle Java Magazine](https://blogs.oracle.com/javamagazine/post/diving-into-java-records-serialization-marshaling-and-bean-state-validation)
- [Using Java Records with JPA - Baeldung](https://www.baeldung.com/spring-jpa-java-records)
- [Java Record vs. Lombok - Baeldung](https://www.baeldung.com/java-record-vs-lombok)
- [Java Records - How to use them with Hibernate and JPA - Thorben Janssen](https://thorben-janssen.com/java-records-hibernate-jpa/)
- [Enforcing Java Record Invariants With Bean Validation - Gunnar Morling](https://www.morling.dev/blog/enforcing-java-record-invariants-with-bean-validation/)
- [Custom Constructor in Java Records - Baeldung](https://www.baeldung.com/java-records-custom-constructor)
