# 모든 Entity는 불변 객체여야 할까? JPA에서는 어떨까?

## 개요

불변 객체(Immutable Object)는 스레드 안전성, 예측 가능성, 부수 효과 방지 등 많은 이점을 제공한다. 하지만 JPA 엔티티는 구조적으로 가변성(Mutability)을 요구한다. 이 글에서는 불변 객체의 이점과 JPA의 가변성 요구 사이의 트레이드오프를 분석하고, 실무에서 어떻게 균형을 잡을 수 있는지 정리한다.

## 상세 내용

### 불변 객체의 이점

불변 객체란 **생성 후 상태가 변경되지 않는 객체**를 말한다.

```java
public final class Money {
    private final BigDecimal amount;
    private final Currency currency;

    public Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public BigDecimal getAmount() { return amount; }
    public Currency getCurrency() { return currency; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount.equals(money.amount) && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }
}
```

| 이점 | 설명 |
|---|---|
| **스레드 안전성** | 상태가 변하지 않으므로 동기화 없이 여러 스레드에서 안전하게 공유 |
| **예측 가능성** | 한번 생성되면 상태가 바뀌지 않아 디버깅과 추론이 쉬움 |
| **부수 효과 방지** | 다른 코드에서 의도치 않게 상태를 변경하는 것이 불가능 |
| **안전한 Map/Set 키** | `hashCode`가 변하지 않으므로 컬렉션 키로 안전하게 사용 가능 |
| **캐싱 용이** | 동일 상태면 동일 객체로 취급 가능, 캐싱 효과적 |

> *"Classes should be immutable unless there's a very good reason to make them mutable."* — Joshua Bloch, Effective Java

---

### JPA 엔티티는 왜 가변이어야 하는가?

JPA 스펙(Jakarta Persistence Specification)은 엔티티에 대해 다음을 요구한다:

#### 1. public 또는 protected 기본 생성자 필수

```java
@Entity
public class Member {
    // JPA 스펙 요구: 기본 생성자 필수
    protected Member() {}

    public Member(String name) {
        this.name = name;
    }
}
```

JPA는 **리플렉션**으로 엔티티를 인스턴스화한다. DB에서 데이터를 조회할 때 기본 생성자로 빈 객체를 생성한 후, 필드 값을 주입(Hydration)하는 방식이다.

```
DB 조회 → 기본 생성자로 빈 객체 생성 → 리플렉션으로 필드 값 설정 (Hydration)
```

#### 2. 클래스는 final이 아니어야 함

```java
// 불가: Hibernate가 프록시 서브클래스를 생성할 수 없음
public final class Member { ... }

// 가능: Hibernate가 Member를 상속한 프록시 클래스 생성
public class Member { ... }
```

Hibernate는 **지연 로딩(Lazy Loading)을 위해 프록시 객체**를 생성한다. 프록시는 엔티티 클래스를 **상속받은 서브클래스**이므로, 엔티티 클래스가 `final`이면 프록시를 생성할 수 없다.

```
// Hibernate 내부 프록시 생성 (개념)
class Member$HibernateProxy extends Member {
    @Override
    public String getName() {
        // 실제 DB 조회가 이 시점에 발생 (Lazy Loading)
        initialize();
        return super.getName();
    }
}
```

#### 3. 필드와 메서드도 final이 아니어야 함

```java
@Entity
public class Member {
    // 불가: Hydration 시 리플렉션으로 값 설정 불가
    private final String name;

    // 가능: 리플렉션으로 값 변경 가능
    private String name;
}
```

JPA는 기본 생성자로 빈 객체를 만든 후 리플렉션으로 필드 값을 설정하므로, `final` 필드는 값을 설정할 수 없다.

> **참고:** JEP 500 "*Prepare to Make Final Mean Final*"에서 향후 리플렉션을 통한 final 필드 수정 자체가 금지될 수 있어, JPA와 final 필드는 더욱 양립하기 어려워질 전망이다.

#### 4. Dirty Checking 메커니즘

JPA의 핵심 기능인 **Dirty Checking**은 엔티티의 가변성에 의존한다:

```java
@Transactional
public void updateMemberName(Long id, String newName) {
    Member member = memberRepository.findById(id).orElseThrow();

    member.setName(newName); // 상태 변경

    // save() 호출 없이도 트랜잭션 커밋 시 자동으로 UPDATE 쿼리 발생
    // Hibernate가 영속성 컨텍스트의 스냅샷과 현재 상태를 비교하여 변경 감지
}
```

```
1. 엔티티 조회 → 스냅샷(원본 복사본) 저장
2. 비즈니스 로직에서 엔티티 상태 변경
3. 트랜잭션 커밋 시 스냅샷과 현재 상태 비교 (Dirty Checking)
4. 변경된 필드에 대해 UPDATE SQL 생성 및 실행
```

불변 객체라면 상태 변경 자체가 불가능하므로 Dirty Checking이 무의미하다.

---

### JPA 스펙 요구사항 요약

| 요구사항 | 이유 | 불변 객체와 충돌 |
|---|---|---|
| 기본 생성자 (public/protected) | 리플렉션으로 인스턴스 생성 | 생성자에서 모든 필드 초기화 불가 |
| 클래스 non-final | 프록시 서브클래스 생성 (지연 로딩) | `final class` 사용 불가 |
| 필드 non-final | Hydration 시 리플렉션으로 값 주입 | `final` 필드 사용 불가 |
| 메서드 non-final | 프록시가 메서드 오버라이드 | `final` 메서드 사용 불가 |

---

### 그럼에도 불변성을 최대한 활용하는 전략

JPA 엔티티를 완전한 불변 객체로 만들 수는 없지만, **불변성의 이점을 최대한 취하는 방법**이 있다.

#### 전략 1: Setter를 제거하고 의미 있는 메서드로 상태 변경

```java
@Entity
public class Member {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String email;

    protected Member() {} // JPA용 기본 생성자

    public Member(String name, String email) {
        this.name = name;
        this.email = email;
    }

    // Setter 대신 도메인 의미가 있는 메서드
    public void changeName(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("이름은 비어있을 수 없습니다");
        }
        this.name = newName;
    }

    // Getter만 제공
    public String getName() { return name; }
    public String getEmail() { return email; }
}
```

#### 전략 2: Value Object(VO)는 불변으로, Entity는 VO를 조합

DDD에서 **Value Object는 불변**, **Entity는 식별자 기반**으로 설계한다:

```java
@Embeddable
public class Address {
    private String city;
    private String street;
    private String zipCode;

    protected Address() {}

    public Address(String city, String street, String zipCode) {
        this.city = city;
        this.street = street;
        this.zipCode = zipCode;
    }

    // Setter 없음 - 불변 Value Object
    // 주소 변경 시 새 객체로 교체
    public String getCity() { return city; }
    public String getStreet() { return street; }
    public String getZipCode() { return zipCode; }

    @Override
    public boolean equals(Object o) { ... }

    @Override
    public int hashCode() { ... }
}

@Entity
public class Member {
    @Id @GeneratedValue
    private Long id;

    @Embedded
    private Address address;

    // 주소 변경 시 새 Value Object로 교체
    public void changeAddress(Address newAddress) {
        this.address = newAddress;
    }
}
```

```java
// 사용 시: 기존 VO를 수정하지 않고 새 객체로 교체
member.changeAddress(new Address("서울", "강남대로", "06000"));
```

#### 전략 3: Hibernate @Immutable로 읽기 전용 엔티티

한번 저장된 후 **절대 변경되지 않는 데이터**에는 `@Immutable`을 사용한다:

```java
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue
    private Long id;
    private String action;
    private String detail;
    private LocalDateTime createdAt;

    protected AuditLog() {}

    public AuditLog(String action, String detail) {
        this.action = action;
        this.detail = detail;
        this.createdAt = LocalDateTime.now();
    }
}
```

`@Immutable`의 효과:

| 동작 | 결과 |
|---|---|
| 필드 변경 후 커밋 | **무시됨** (UPDATE 쿼리 생성하지 않음) |
| Dirty Checking | **스킵** (성능 향상) |
| 메모리 스냅샷 | **생성하지 않음** (메모리 절약) |
| DELETE | **허용됨** (삭제는 가능) |
| 컬렉션 수정 | **HibernateException 발생** |

**적합한 사용처:**
- 감사 로그 (Audit Log)
- 이벤트 소싱의 이벤트
- 코드 테이블 (국가 코드, 통화 코드 등)
- 변경 이력 테이블

#### 전략 4: 접근 수준으로 가변성 범위 제한

```java
@Entity
public class Order {
    @Id @GeneratedValue
    private Long id;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    // package-private Setter: 같은 패키지의 서비스에서만 변경 가능
    void changeStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

    // public Getter
    public OrderStatus getStatus() { return status; }
}
```

---

### DDD 관점에서의 정리: Entity vs Value Object

| 구분 | Entity | Value Object |
|---|---|---|
| **정체성** | 식별자(ID)로 구분 | 속성 값으로 구분 |
| **가변성** | 가변 (상태 변경 가능) | **불변** (새 객체로 교체) |
| **생명주기** | 독립적 생명주기 보유 | Entity에 종속 |
| **equals/hashCode** | ID 기반 | 모든 속성 기반 |
| **JPA 매핑** | `@Entity` | `@Embeddable` |
| **예시** | Member, Order, Product | Address, Money, DateRange |

```
Entity (가변, ID 기반)          Value Object (불변, 값 기반)
┌──────────────────┐          ┌──────────────────┐
│ Member           │          │ Address          │
│ ─────────────    │          │ ─────────────    │
│ id: 1 (식별자)    │──────────│  city: "서울"      │
│ name: "홍길동"    │          │  street: "강남대로" │
│ address:  ──────>│          │ zipCode: "06000" │
└──────────────────┘          └──────────────────┘
  상태 변경 가능                 변경 시 새 객체로 교체
```

---

### 결론: 모든 Entity는 불변이어야 할까?

**아니다.** JPA 엔티티는 구조적으로 완전한 불변이 될 수 없다. 하지만 **불변성의 원칙을 최대한 적용**할 수 있다:

```
완전 불변 ←──────────── 스펙트럼 ────────────→ 완전 가변
  (불가)         VO(@Embeddable)       Setter 제거    무분별한 Setter
                  @Immutable         의미 있는 메서드    public 필드
                                       접근 제한
                                         ↑
                                    실무 권장 지점
```

1. **Setter를 제거**하고 도메인 의미가 있는 메서드로 상태 변경
2. **Value Object는 불변 `@Embeddable`**로, 변경 시 새 객체로 교체
3. **읽기 전용 엔티티는 `@Immutable`**로 Dirty Checking 비용 제거
4. **접근 제한자**로 가변성의 범위를 최소화
5. 엔티티 자체를 불변으로 만들기보다, **불변성의 이점(예측 가능성, 안전성)을 설계 패턴으로 확보**

## 핵심 정리

- JPA 엔티티는 **기본 생성자, non-final 클래스/필드** 등 스펙 요구사항 때문에 완전한 불변 객체가 될 수 없다
- Hibernate의 **Dirty Checking, 프록시 기반 Lazy Loading**이 가변성에 의존하는 핵심 메커니즘
- **Setter를 제거**하고 도메인 의미 있는 메서드로 상태를 변경하면 불변에 가까운 설계 가능
- DDD의 **Value Object(`@Embeddable`)는 불변으로 설계**하고, 변경 시 새 객체로 교체
- 읽기 전용 엔티티에는 **`@Immutable`을 적용**하여 Dirty Checking 스킵 및 메모리 절약
- **완전한 불변이 목표가 아니라, 가변성의 범위를 최소화하는 것**이 실무적 해답

## 키워드

- **불변 객체(Immutable Object)**: 생성 후 상태가 변경되지 않는 객체. 스레드 안전성, 예측 가능성, 부수 효과 방지 등의 이점 제공
- **JPA Entity**: 데이터베이스 테이블과 매핑되는 영속성 객체. 식별자(ID)를 가지며 생명주기 동안 상태 변경 가능
- **Dirty Checking**: JPA/Hibernate가 영속성 컨텍스트에 저장된 엔티티의 스냅샷과 현재 상태를 비교하여 변경 사항을 자동으로 감지하고 UPDATE 쿼리를 생성하는 메커니즘
- **@Immutable**: Hibernate 애노테이션으로, 엔티티나 컬렉션을 읽기 전용으로 표시. Dirty Checking을 스킵하여 성능 향상 및 메모리 절약
- **@Embeddable**: JPA 애노테이션으로, 독립적 테이블이 아닌 다른 엔티티에 포함되는 Value Object를 정의. 주로 불변 객체로 설계
- **Value Object(VO)**: DDD에서 식별자 없이 속성 값으로만 구분되는 불변 객체. 주소, 금액, 날짜 범위 등을 표현
- **Proxy**: Hibernate가 지연 로딩(Lazy Loading)을 위해 엔티티 클래스를 상속받아 동적으로 생성하는 서브클래스 객체
- **Lazy Loading**: 연관된 엔티티를 실제로 사용하는 시점까지 DB 조회를 지연시키는 전략. 프록시 객체를 통해 구현
- **Hydration**: JPA가 기본 생성자로 빈 엔티티 객체를 생성한 후, 리플렉션을 사용하여 DB에서 조회한 데이터를 필드에 주입하는 과정
- **도메인 주도 설계(DDD)**: 비즈니스 도메인을 중심으로 소프트웨어를 설계하는 방법론. Entity, Value Object, Aggregate 등의 전술적 패턴 활용

## 참고 자료

- [JPA Specification - Entity Requirements](https://jakarta.ee/specifications/persistence/)
- [Vlad Mihalcea - How to Map an Immutable Entity with JPA and Hibernate](https://vladmihalcea.com/immutable-entity-jpa-hibernate/)
- [Baeldung - @Immutable in Hibernate](https://www.baeldung.com/hibernate-immutable)
- [Baeldung - Need for Default Constructor in JPA Entities](https://www.baeldung.com/jpa-no-argument-constructor-entity-class)
- [Hibernate ORM - Entity Mapping Guide](https://docs.jboss.org/hibernate/orm/5.0/mappingGuide/en-US/html/ch02.html)
- [Is It Possible to Have Immutable JPA Entities?](https://www.codestudy.net/blog/is-it-possible-to-have-immutable-jpa-entities/)
- [Effective Java - Joshua Bloch (Item 17: Minimize Mutability)](https://www.oreilly.com/library/view/effective-java/9780134686097/)
