# 빌더 패턴 (with Lombok @Builder)

## 개요
객체 생성 시 생성자의 매개변수가 많아질 때 가독성과 유연성을 높여주는 빌더 패턴과, 이를 간편하게 구현해주는 Lombok @Builder 어노테이션에 대해 학습한다.

## 상세 내용

### 빌더 패턴이란
GoF(Gang of Four) 디자인 패턴 중 생성(Creational) 패턴에 속하는 패턴으로, 복잡한 객체의 생성 과정과 표현을 분리한다. 즉, 동일한 생성 절차에서 서로 다른 표현 결과를 만들 수 있게 한다.

> GoF의 빌더 패턴은 "복잡한 객체를 단계별로 생성한다"는 의도를 가지며, 이펙티브 자바(Effective Java)에서 소개된 빌더 패턴은 "매개변수가 많은 생성자를 대체한다"는 의도가 강하다. 실무에서는 후자의 용도로 더 많이 사용된다.

---

### 빌더 패턴이 필요한 이유

빌더 패턴이 등장하기 전, 객체를 생성하는 방법은 크게 두 가지였다.

#### 1. 텔레스코핑 생성자 패턴 (Telescoping Constructor Pattern)
필수 매개변수를 받는 생성자에서 시작해, 선택 매개변수를 하나씩 추가하며 여러 생성자를 오버로딩하는 방식이다.

```java
public class NutritionFacts {
    private final int servingSize;  // 필수
    private final int servings;     // 필수
    private final int calories;     // 선택
    private final int fat;          // 선택
    private final int sodium;       // 선택

    public NutritionFacts(int servingSize, int servings) {
        this(servingSize, servings, 0);
    }

    public NutritionFacts(int servingSize, int servings, int calories) {
        this(servingSize, servings, calories, 0);
    }

    public NutritionFacts(int servingSize, int servings, int calories, int fat) {
        this(servingSize, servings, calories, fat, 0);
    }

    public NutritionFacts(int servingSize, int servings, int calories, int fat, int sodium) {
        this.servingSize = servingSize;
        this.servings = servings;
        this.calories = calories;
        this.fat = fat;
        this.sodium = sodium;
    }
}

// 사용 시 각 인자가 무엇을 의미하는지 알기 어렵다
NutritionFacts cocaCola = new NutritionFacts(240, 8, 100, 0, 35);
```

**문제점**: 매개변수가 늘어날수록 생성자가 폭발적으로 늘어나고, 호출 시 각 값이 어떤 필드에 해당하는지 파악하기 어렵다.

#### 2. JavaBeans 패턴
기본 생성자로 객체를 만들고 setter로 값을 설정하는 방식이다.

```java
NutritionFacts cocaCola = new NutritionFacts();
cocaCola.setServingSize(240);
cocaCola.setServings(8);
cocaCola.setCalories(100);
cocaCola.setSodium(35);
```

**문제점**: 객체 생성과 초기화가 분리되어 객체가 일관성 없는 상태에 놓일 수 있다. setter가 존재하기 때문에 불변(immutable) 객체를 만들 수 없다.

#### 3. 빌더 패턴으로 해결

```java
NutritionFacts cocaCola = NutritionFacts.builder()
        .servingSize(240)
        .servings(8)
        .calories(100)
        .sodium(35)
        .build();
```

가독성이 높고, 불변 객체를 만들 수 있으며, 선택 매개변수를 유연하게 처리할 수 있다.

---

### 빌더 패턴의 구현 방식

#### 전통적인 빌더 패턴 (Inner Static Class)

```java
public class User {
    private final String name;     // 필수
    private final String email;    // 필수
    private final int age;         // 선택
    private final String phone;    // 선택

    // private 생성자: 오직 Builder를 통해서만 생성 가능
    private User(Builder builder) {
        this.name = builder.name;
        this.email = builder.email;
        this.age = builder.age;
        this.phone = builder.phone;
    }

    // Inner Static Builder Class
    public static class Builder {
        private final String name;
        private final String email;
        private int age = 0;
        private String phone = "";

        // 필수 필드는 Builder 생성자로 받는다
        public Builder(String name, String email) {
            this.name = name;
            this.email = email;
        }

        // Method Chaining: 자기 자신(Builder)을 반환
        public Builder age(int age) {
            this.age = age;
            return this;
        }

        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }

        public User build() {
            return new User(this);
        }
    }
}

// 사용 예시
User user = new User.Builder("홍길동", "hong@example.com")
        .age(30)
        .phone("010-1234-5678")
        .build();
```

#### Method Chaining을 통한 Fluent API

위 예시에서 볼 수 있듯이, 각 setter 메서드가 `this`(빌더 자신)를 반환함으로써 메서드 호출을 연속으로 이어 쓸 수 있다. 이 방식을 **Fluent API** 또는 **Method Chaining**이라 한다.

#### Director 패턴과의 차이점

GoF의 원래 빌더 패턴에는 **Director** 클래스가 존재한다. Director는 빌더를 사용해 정해진 순서대로 객체를 조립하는 역할을 담당한다. 반면, 이펙티브 자바의 빌더 패턴(실무에서 주로 사용하는 방식)은 Director 없이 클라이언트가 직접 빌더를 제어한다.

```
GoF 패턴:     Client -> Director -> Builder -> Product
실무 패턴:    Client -> Builder -> Product
```

---

### Lombok @Builder

Lombok의 `@Builder` 어노테이션을 사용하면 위의 보일러플레이트 코드를 자동으로 생성할 수 있다.

#### @Builder 어노테이션의 동작 원리

`@Builder`를 클래스에 적용하면 컴파일 타임에 다음 7가지 요소가 자동 생성된다.

1. `[클래스명]Builder`라는 이름의 내부 정적(static) 클래스
2. 빌더 클래스 내 각 필드에 대응하는 private 필드
3. 빌더 클래스의 기본 생성자
4. 각 필드마다 메서드 체이닝을 지원하는 설정 메서드
5. 최종 객체를 반환하는 `build()` 메서드
6. 빌더 클래스의 `toString()` 메서드
7. 빌더 인스턴스를 반환하는 `builder()` 정적 팩토리 메서드

```java
// 작성하는 코드
@Builder
public class User {
    private String name;
    private String email;
    private int age;
}

// Lombok이 생성하는 코드 (개념적 표현)
public class User {
    private String name;
    private String email;
    private int age;

    User(String name, String email, int age) {
        this.name = name;
        this.email = email;
        this.age = age;
    }

    public static UserBuilder builder() {
        return new UserBuilder();
    }

    public static class UserBuilder {
        private String name;
        private String email;
        private int age;

        public UserBuilder name(String name) {
            this.name = name;
            return this;
        }

        public UserBuilder email(String email) {
            this.email = email;
            return this;
        }

        public UserBuilder age(int age) {
            this.age = age;
            return this;
        }

        public User build() {
            return new User(name, email, age);
        }
    }
}

// 사용 예시
User user = User.builder()
        .name("홍길동")
        .email("hong@example.com")
        .age(30)
        .build();
```

#### 클래스 레벨 vs 생성자 레벨 @Builder 적용 차이

| 구분 | 클래스 레벨 | 생성자/메서드 레벨 |
|------|-------------|---------------------|
| 적용 대상 | 모든 필드 | 지정한 매개변수만 |
| 자동 생성 생성자 | `@AllArgsConstructor` 효과 발생 | 없음 |
| 활용 | 간단한 객체 | 필드 일부만 빌더로 제어할 때 |

```java
// 생성자 레벨 @Builder: 특정 필드만 빌더 대상으로 지정
@NoArgsConstructor
public class Order {
    private Long id;           // 자동 생성, 빌더 제외
    private String product;
    private int quantity;

    @Builder
    public Order(String product, int quantity) {
        this.product = product;
        this.quantity = quantity;
    }
}
```

#### @Builder.Default로 기본값 설정

클래스 레벨 `@Builder`를 사용할 때, 필드에 기본값을 지정하면 빌더를 통해 해당 필드를 설정하지 않을 경우 기본값이 무시된다. `@Builder.Default`를 사용해야 의도한 기본값이 적용된다.

```java
@Builder
public class Config {
    // @Builder.Default 없이 기본값 지정 시, 빌더로 생성하면 기본값이 무시됨
    // private int timeout = 3000; // 이렇게 하면 안됨

    @Builder.Default
    private int timeout = 3000;

    @Builder.Default
    private String environment = "production";

    @Builder.Default
    private long createdAt = System.currentTimeMillis();
}

// 사용 예시: timeout을 지정하지 않으면 3000이 적용됨
Config config = Config.builder()
        .environment("development")
        .build();
// config.timeout = 3000, config.environment = "development"
```

#### @Singular를 활용한 컬렉션 빌드

`@Singular`를 컬렉션 필드에 적용하면 단수형 이름의 메서드로 요소를 하나씩 추가할 수 있다. 리스트 전체를 한 번에 넘기는 대신, 개별 요소를 추가하는 방식으로 사용한다.

```java
@Builder
public class Article {
    private String title;

    @Singular
    private List<String> tags;

    @Singular("image")  // 자동 단수 변환이 어려울 때 직접 지정
    private List<String> images;
}

// 사용 예시
Article article = Article.builder()
        .title("빌더 패턴 학습")
        .tag("Java")           // 단수형으로 하나씩 추가
        .tag("Design Pattern")
        .image("cover.png")
        .image("diagram.png")
        .build();
```

`@Singular`가 적용된 컬렉션은 `build()` 이후 변경 불가능한(unmodifiable) 컬렉션으로 생성된다. 또한 `clearTags()` 같은 초기화 메서드도 자동으로 생성된다.

---

### @Builder 사용 시 주의사항

#### @AllArgsConstructor와의 관계

클래스 레벨에 `@Builder`를 적용하면 내부적으로 모든 필드를 받는 `@AllArgsConstructor`와 동일한 생성자가 생성된다. 이 생성자는 `package-private` 접근 수준으로 만들어지는데, 동시에 `@NoArgsConstructor`도 사용하고 싶다면 컴파일 오류가 발생할 수 있다.

```java
// 컴파일 오류 발생
@Builder
@NoArgsConstructor
public class User {
    private String name;
}
```

**컴파일 오류가 발생하는 이유:**

`@NoArgsConstructor`를 명시하면 Lombok은 "사용자가 직접 생성자를 관리하겠다"고 판단하여 `@Builder`의 암묵적 All-Args Constructor 생성을 건너뛴다. 결과적으로 `@Builder`가 생성한 `build()` 메서드가 존재하지 않는 All-Args Constructor를 호출하게 되어 컴파일 오류가 발생한다.

```java
// @Builder가 생성하는 build() 메서드
public User build() {
    return new User(name, email); // ← 이 생성자가 없음!
}
```

이를 해결하려면 `@AllArgsConstructor`를 명시적으로 함께 선언하여 `build()`가 호출할 생성자를 보장해야 한다.

```java
// 올바른 방법
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String name;
    private String email;
}
```

#### JPA Entity에서 @Builder 사용 시 기본 생성자 문제

JPA는 엔티티를 조회할 때 리플렉션을 이용해 기본 생성자(no-args constructor)로 객체를 생성하고 setter나 필드 접근으로 값을 채운다. 따라서 JPA 엔티티에는 반드시 기본 생성자가 필요하다.

```java
// 권장 방법: 생성자 레벨에 @Builder 적용
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA를 위한 기본 생성자
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // DB 자동 생성, 빌더에서 제외

    private String username;
    private String email;

    @Builder
    public Member(String username, String email) {
        this.username = username;
        this.email = email;
    }
}
```

`AccessLevel.PROTECTED`로 기본 생성자를 선언하면 JPA 프레임워크에서는 사용 가능하면서, 일반 코드에서 빈 객체를 생성하는 실수를 방지할 수 있다.

#### @Builder와 상속(Inheritance) 시 @SuperBuilder 활용

일반 `@Builder`는 부모 클래스의 필드를 빌더에 포함시키지 못한다. 상속 구조에서 부모 클래스 필드까지 빌더로 처리하려면 `@SuperBuilder`를 사용해야 한다.

```java
// @Builder 사용 시 문제점
@Builder
public class Animal {
    private String name;
}

@Builder
public class Dog extends Animal {
    private String breed;
    // 빌더에 name 필드가 포함되지 않음!
}
```

```java
// @SuperBuilder로 해결: 부모와 자식 모두 @SuperBuilder 필요
@SuperBuilder
@Getter
public class Animal {
    private String name;
    private int age;
}

@SuperBuilder
@Getter
public class Dog extends Animal {
    private String breed;
}

// 사용 예시: 부모 필드까지 모두 빌더로 설정 가능
Dog dog = Dog.builder()
        .name("멍멍이")   // Animal 필드
        .age(3)           // Animal 필드
        .breed("진돗개")  // Dog 필드
        .build();
```

주의: `@Builder`와 `@SuperBuilder`는 함께 사용할 수 없다. 상속 계층의 모든 클래스에 `@SuperBuilder`를 일관되게 적용해야 한다.

#### @Builder.ObtainVia로 기존 객체에서 값 복사

`toBuilder = true` 옵션과 함께 `@Builder.ObtainVia`를 사용하면 기존 인스턴스에서 특정 방법으로 값을 가져와 새 빌더를 초기화할 수 있다.

```java
@Builder(toBuilder = true)
public class User {
    private String name;
    private String email;

    // 이 필드는 toBuilder 시 getFullName() 메서드로 값을 가져옴
    @Builder.ObtainVia(method = "getFullName")
    private String displayName;

    public String getFullName() {
        return "[" + name + "]";
    }
}

// 기존 객체에서 일부만 변경한 새 객체 생성 (방어적 복사 패턴)
User original = User.builder().name("홍길동").email("hong@example.com").build();
User updated = original.toBuilder().email("new@example.com").build();
```

---

### 빌더 패턴의 활용 사례

#### DTO/VO 객체 생성

API 요청/응답 DTO나 값 객체(Value Object)에 빌더 패턴을 적용하면 객체 생성 코드의 가독성이 크게 향상된다.

```java
@Builder
@Getter
public class UserResponseDto {
    private Long id;
    private String name;
    private String email;
    private String role;
    private LocalDateTime createdAt;

    // Entity -> DTO 변환 정적 팩토리 메서드
    public static UserResponseDto from(User user) {
        return UserResponseDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
```

#### 테스트 코드에서의 Test Fixture 생성

테스트에서 특정 상태를 가진 객체를 쉽게 만들 수 있다. 불필요한 필드는 기본값을 사용하고, 테스트에 필요한 필드만 명시적으로 지정할 수 있다.

```java
// Test Fixture: 테스트용 기본 객체를 빌더로 생성
public class UserFixture {
    public static User.UserBuilder aUser() {
        return User.builder()
                .name("테스트유저")
                .email("test@example.com")
                .age(25);
    }
}

// 테스트에서 활용
@Test
void 이메일이_없으면_회원가입에_실패한다() {
    User userWithoutEmail = UserFixture.aUser()
            .email(null) // 필요한 부분만 오버라이드
            .build();

    assertThatThrownBy(() -> userService.register(userWithoutEmail))
            .isInstanceOf(IllegalArgumentException.class);
}
```

#### 불변 객체(Immutable Object) 생성과의 관계

빌더 패턴은 불변 객체 생성과 잘 맞는다. 모든 필드를 `final`로 선언하고 setter 없이 빌더로만 객체를 생성하면 완전한 불변 객체를 만들 수 있다.

```java
@Builder
@Getter
public final class Money {
    private final long amount;
    private final String currency;

    // setter가 없고, 모든 필드가 final
    // 생성 후 상태 변경 불가 = 불변 객체
}

Money price = Money.builder()
        .amount(10000L)
        .currency("KRW")
        .build();
```

---

## 핵심 정리
- 빌더 패턴은 텔레스코핑 생성자의 가독성 문제와 JavaBeans 패턴의 불변성 보장 불가 문제를 동시에 해결한다.
- Lombok `@Builder`는 컴파일 타임에 빌더 클래스, `builder()` 정적 메서드, 각 필드 설정 메서드, `build()` 메서드 등 7가지 요소를 자동 생성한다.
- `@Builder.Default`를 사용하지 않으면 필드 초기화 기본값이 빌더를 통한 객체 생성 시 무시되는 함정에 빠질 수 있다.
- JPA 엔티티에 `@Builder`를 적용할 때는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`를 함께 선언하고, 클래스 레벨보다 생성자 레벨에 `@Builder`를 적용해 DB 자동 생성 필드를 빌더에서 제외하는 것이 좋다.
- 상속 구조에서는 `@Builder` 대신 `@SuperBuilder`를 부모-자식 클래스 모두에 적용해야 부모 필드까지 빌더로 설정할 수 있다.
- `toBuilder = true` 옵션으로 기존 객체를 기반으로 일부 값만 변경한 새 객체를 쉽게 생성할 수 있어, 방어적 복사 패턴 구현에 유용하다.

## 키워드
- **`빌더 패턴`**: 복잡한 객체를 단계적으로 생성하는 GoF 생성 패턴. 생성 과정과 표현을 분리해 동일한 절차로 다양한 객체를 만들 수 있다.
- **`Builder Pattern`**: 빌더 패턴의 영문 명칭. 특히 이펙티브 자바에서 소개된 방식은 매개변수가 많은 생성자를 대체하는 용도로 널리 사용된다.
- **`Lombok @Builder`**: 빌더 패턴 구현을 위한 보일러플레이트 코드를 컴파일 타임에 자동 생성해주는 Lombok 어노테이션.
- **`생성 패턴`**: GoF 디자인 패턴 분류 중 하나. 객체 생성 메커니즘을 다루며, Builder, Singleton, Factory Method, Abstract Factory, Prototype이 포함된다.
- **`Creational Pattern`**: 생성 패턴의 영문 명칭.
- **`텔레스코핑 생성자`**: 필수 매개변수부터 시작해 선택 매개변수를 점점 추가하는 방식으로 생성자를 오버로딩하는 안티 패턴. 매개변수가 늘어날수록 관리가 어려워진다.
- **`@SuperBuilder`**: 상속 계층에서 부모 클래스 필드까지 포함한 빌더를 생성해주는 Lombok 어노테이션. 부모와 자식 클래스 모두에 적용해야 한다.
- **`@Builder.Default`**: `@Builder` 적용 시 기본값이 무시되는 문제를 해결하기 위한 Lombok 어노테이션. 해당 필드를 빌더에서 설정하지 않으면 지정한 기본값이 사용된다.
- **`Fluent API`**: 메서드 호출을 연속으로 이어 쓸 수 있도록 메서드가 자기 자신(혹은 관련 객체)을 반환하는 인터페이스 설계 방식. Method Chaining이라고도 한다.
- **`불변 객체`**: 생성 이후 상태가 변경되지 않는 객체. 빌더 패턴과 함께 사용하면 setter 없이도 초기화 시 모든 값을 설정할 수 있어 불변성을 보장하기 용이하다.

## 참고 자료
- [Lombok 공식 문서 - @Builder](https://projectlombok.org/features/Builder)
- [Lombok 공식 문서 - @SuperBuilder](https://projectlombok.org/features/experimental/SuperBuilder)
- [Baeldung - Lombok @Builder with Inheritance](https://www.baeldung.com/lombok-builder-inheritance)
- [Baeldung - Lombok Builder Default Value](https://www.baeldung.com/lombok-builder-default-value)
- [기계인간 John Grib - 빌더 패턴](https://johngrib.github.io/wiki/pattern/builder/)
- [실무에서 Lombok 사용법 - Yun Blog](https://cheese10yun.github.io/lombok/)
