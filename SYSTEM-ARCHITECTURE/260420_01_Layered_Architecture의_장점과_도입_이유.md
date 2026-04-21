# Layered Architecture의 장점과 도입 이유

## 개요
소프트웨어를 계층(Layer)별로 분리하여 설계하는 Layered Architecture의 핵심 개념과, 이를 도입함으로써 얻을 수 있는 구조적 이점을 정리한다.

## 상세 내용

### Layered Architecture란

소프트웨어 시스템을 기능적 역할에 따라 수평적 계층으로 분리하는 아키텍처 패턴이다. 각 계층은 특정 책임 범위만 담당하고, 인접한 계층과만 통신한다.

전통적인 4계층 구분은 다음과 같다.

| 계층 | 역할 | 예시 |
|---|---|---|
| Presentation Layer | 사용자 요청 수신 및 응답 반환 | Controller, View |
| Business Logic Layer | 비즈니스 규칙 처리 및 흐름 제어 | Service |
| Persistence Layer | 데이터 접근 및 저장소 인터페이스 | Repository, DAO |
| Database Layer | 실제 데이터 저장 및 조회 | MySQL, PostgreSQL |

계층 간 의존 방향은 항상 상위 → 하위 단방향이다. Presentation은 Business에 의존하고, Business는 Persistence에 의존하지만 그 역방향은 성립하지 않는다. 이 원칙이 깨지는 순간 계층화의 이점이 사라진다.

---

### 도입 이유

**관심사의 분리 (Separation of Concerns)**

각 계층이 하나의 관심사만 담당하도록 강제한다. Controller는 HTTP 처리만, Service는 비즈니스 로직만, Repository는 데이터 접근만 담당한다. 이렇게 하면 개발자가 특정 계층의 코드를 읽을 때 다른 계층의 세부 사항을 신경 쓰지 않아도 된다.

**변경 영향 범위의 최소화**

데이터베이스 스키마가 변경되더라도 Persistence Layer만 수정하면 Business Logic Layer는 영향을 받지 않는다. 외부 API 명세가 바뀌어도 Presentation Layer만 수정하면 된다. 변경의 파급 범위가 계층 경계 내로 제한된다.

**팀 단위 병렬 개발 가능**

계층 간 인터페이스(API 스펙, 메서드 시그니처)가 정해지면 각 팀이 독립적으로 개발할 수 있다. 프론트엔드 팀이 Controller Layer를 개발하는 동안 백엔드 팀이 Service Layer를 개발할 수 있다.

---

### 장점

**테스트 용이성**

계층이 분리되어 있으므로 Mock 객체를 활용한 단위 테스트 작성이 수월하다. Service 계층을 테스트할 때 Repository를 Mock 처리하면 실제 데이터베이스 없이도 비즈니스 로직만 검증할 수 있다.

```java
// Service 계층 단위 테스트 예시
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void 주문_생성_시_재고가_없으면_예외를_던진다() {
        given(orderRepository.findById(anyLong())).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.createOrder(1L, 5))
            .isInstanceOf(StockNotFoundException.class);
    }
}
```

**유지보수성**

특정 계층의 구현 기술을 교체하더라도 다른 계층에는 영향이 없다. JPA에서 MyBatis로 변경하거나, REST API에서 gRPC로 변경할 때 해당 계층만 수정하면 된다.

**재사용성**

하위 계층을 여러 상위 계층에서 공유할 수 있다. OrderService와 AdminService 둘 다 동일한 ProductRepository를 사용할 수 있다.

**표준화와 온보딩 효율**

계층별 역할이 명확하기 때문에 신규 팀원이 코드 구조를 빠르게 파악할 수 있다. "이 로직은 어디에 둬야 하는가?"라는 질문에 계층 구조가 명확한 답을 제시한다.

---

### 한계와 트레이드오프

**단순 CRUD의 오버헤드**

단순한 조회 요청도 Controller → Service → Repository → Database를 전부 거쳐야 한다. 계층이 늘어날수록 불필요한 코드(Pass-through 메서드)가 많아진다.

```java
// Service가 단순히 Repository를 위임만 하는 경우
public List<UserDto> findAll() {
    return userRepository.findAll().stream()
        .map(UserDto::from)
        .toList();
}
```

**계층 간 DTO 변환 비용**

계층을 넘나들 때마다 데이터 변환이 필요하다. Entity → Domain Object → DTO 변환 과정에서 코드량이 증가하고, MapStruct나 ModelMapper 같은 도구 도입이 필요해진다.

**Strict vs Relaxed Layering 선택**

- **Strict Layering**: 각 계층은 바로 아래 계층만 호출 가능. Controller는 Repository를 직접 호출할 수 없음. 결합도가 낮고 변경에 강하지만 단순한 작업에도 모든 계층을 경유해야 함.
- **Relaxed Layering**: 상위 계층이 모든 하위 계층에 접근 가능. 단순 조회 시 Controller가 Repository를 직접 호출 가능. 구현 속도는 빠르지만 계층 간 결합이 높아짐.

대부분의 실무에서는 기본적으로 Strict Layering을 유지하되, 성능이나 단순성이 중요한 예외 케이스에 한해 Relaxed를 허용하는 방식을 선택한다.

---

### 실무 적용 시 고려사항

**Spring MVC에서의 적용**

Spring Boot의 `@Controller`, `@Service`, `@Repository` 어노테이션은 Layered Architecture를 자연스럽게 강제한다. Spring의 컨벤션이 이미 계층화 패턴을 따르고 있으므로, Spring을 사용하는 팀이라면 별도의 설계 없이도 기본 계층화를 얻을 수 있다.

```
Request
  ↓
@RestController   ← HTTP 요청/응답 처리, 입력 검증
  ↓
@Service          ← 비즈니스 로직, 트랜잭션 관리
  ↓
@Repository       ← 데이터 접근, JPA/SQL 처리
  ↓
Database
```

**도메인 복잡도에 따른 계층 세분화**

도메인 복잡도가 낮은 CRUD 서비스라면 3계층(Controller-Service-Repository)으로도 충분하다. 복잡한 도메인이라면 Domain Model Layer를 별도로 분리하거나, Application Layer와 Domain Layer를 구분하는 방식으로 계층을 세분화한다.

**Hexagonal Architecture, Clean Architecture와의 비교**

| 구분 | Layered Architecture | Hexagonal Architecture | Clean Architecture |
|---|---|---|---|
| 핵심 원칙 | 계층별 관심사 분리 | 포트와 어댑터를 통한 외부 의존성 격리 | 동심원 구조로 비즈니스 규칙 보호 |
| 의존 방향 | 상위 → 하위 단방향 | 외부 → 내부 단방향 | 외부 → 내부 단방향 |
| 테스트 용이성 | 보통 (Mock 필요) | 높음 (포트 교체로 테스트) | 높음 (인프라 독립적) |
| 복잡도 | 낮음 | 중간 | 높음 |
| 적합한 서비스 | 단순 CRUD, MVP | 외부 의존성이 많은 서비스 | 복잡한 도메인, 장기 운영 서비스 |

Layered Architecture는 진입 장벽이 낮고 Spring과의 궁합이 좋다는 장점이 있지만, 도메인 복잡도가 높아질수록 Hexagonal이나 Clean Architecture로의 전환을 고려해야 한다.

---

## 핵심 정리
- Layered Architecture는 관심사 분리를 통해 유지보수성과 테스트 용이성을 확보하는 구조이다
- 계층 간 단방향 의존을 유지하는 것이 핵심 원칙이며, 이를 어기면 계층화의 이점이 사라진다
- Spring Boot의 `@Controller`, `@Service`, `@Repository`는 Layered Architecture를 자연스럽게 구현한 결과물이다
- Strict Layering은 결합도를 낮추지만 단순 작업에도 모든 계층을 경유해야 하고, Relaxed Layering은 유연하지만 결합이 높아진다
- 도메인 복잡도가 낮은 경우 과도한 계층 분리는 오히려 생산성을 저해할 수 있다
- 장기 운영 서비스나 복잡한 도메인에서는 Hexagonal Architecture 또는 Clean Architecture로의 전환을 검토해야 한다

## 키워드

**Layered Architecture**: 소프트웨어를 기능적 역할에 따라 수평적 계층(Presentation, Business Logic, Persistence, Database)으로 분리하는 아키텍처 패턴

**Separation of Concerns**: 각 모듈이 하나의 관심사만 담당하도록 분리하는 설계 원칙. 변경의 영향 범위를 줄이고 코드 이해를 쉽게 만든다

**Presentation Layer**: 사용자 요청을 수신하고 응답을 반환하는 계층. HTTP 처리, 입력 검증, 응답 포맷팅 담당

**Business Logic Layer**: 비즈니스 규칙과 흐름을 처리하는 계층. 트랜잭션 관리, 도메인 로직 수행

**Persistence Layer**: 데이터 저장소와의 인터페이스를 담당하는 계층. CRUD 연산, 쿼리 실행

**의존 방향**: Layered Architecture에서 의존은 항상 상위 → 하위 단방향으로만 흐른다. 하위 계층이 상위 계층을 참조하면 아키텍처 원칙 위반이다

**테스트 용이성**: 계층이 분리되어 있으므로 Mock 객체로 하위 계층을 대체하여 특정 계층만 독립적으로 테스트할 수 있다

**유지보수성**: 특정 계층의 구현을 변경해도 인터페이스가 동일하면 다른 계층에 영향이 없다. 기술 교체 비용이 줄어든다

**Clean Architecture**: 로버트 마틴(Uncle Bob)이 제안한 아키텍처. 동심원 구조로 외부 기술(DB, 프레임워크)로부터 비즈니스 규칙을 보호하며, Layered Architecture보다 높은 테스트 용이성을 제공한다

**Spring MVC**: Spring Framework의 웹 계층 구현체. `@Controller`, `@Service`, `@Repository` 어노테이션을 통해 Layered Architecture를 자연스럽게 강제한다

## 참고 자료
- [Layered Architecture | Baeldung on Computer Science](https://www.baeldung.com/cs/layered-architecture)
- [Layered Architecture Pattern in Java | java-design-patterns.com](https://java-design-patterns.com/patterns/layered-architecture/)
- [Spring Boot Architecture: Controller, Service, Repository | javaguides.net](https://www.javaguides.net/2025/03/spring-boot-architecture.html)
- [Hexagonal/Clean Architecture vs Layered/N-Tier Architecture | systemsarchitect.io](https://www.systemsarchitect.io/blog/hexagonal-clean-architecture-vs-layered-n-tier-architecture-dc025)
- [The pros and cons of a layered architecture pattern | TechTarget](https://www.techtarget.com/searchapparchitecture/tip/The-pros-and-cons-of-a-layered-architecture-pattern)
