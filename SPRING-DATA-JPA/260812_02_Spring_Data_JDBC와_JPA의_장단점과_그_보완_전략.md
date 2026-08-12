# Spring Data JDBC와 JPA의 장단점과 그 보완 전략

## 개요
Spring Data JPA(Hibernate 기반 ORM)와 Spring Data JDBC는 둘 다 "Spring Data" 우산 아래 있는 저장소(Repository) 추상화이지만, 설계 철학이 근본적으로 다르다. JPA는 자바 객체와 RDB 테이블을 매핑하는 **엔티티 그래프 + 영속성 컨텍스트** 모델을 따르는 반면, Spring Data JDBC는 **DDD(Domain-Driven Design)의 Aggregate 개념을 그대로 구현체에 반영**하여 영속성 컨텍스트 없이 "Persistence by Reachability"라는 단순한 규칙으로 동작한다. 두 기술의 트레이드오프를 이해하고, 각 기술의 한계를 실무에서 어떻게 보완하는지 정리한다.

## 상세 내용

### 1. 두 기술의 정체성

**Spring Data JPA (Hibernate/ORM)**
- JPA(Jakarta Persistence API) 표준의 Spring Data 구현체로, 내부적으로 Hibernate 같은 ORM 벤더를 사용
- 자바 객체 그래프를 RDB 테이블에 매핑하고, 1차 캐시를 가진 **영속성 컨텍스트(Persistence Context)**가 엔티티의 생명주기와 상태 변화를 추적
- `EntityManager`가 트랜잭션 범위 내에서 엔티티를 관리하며, 커밋 시점에 변경 사항을 감지해 SQL을 자동 생성(Dirty Checking)

**Spring Data JDBC**
- 2018년 공개된 프로젝트로, 현재는 Spring Data R2DBC와 함께 **Spring Data Relational** 프로젝트로 통합되어 있음
- JDBC를 얇게 감싸 CRUD를 제공하되, **영속성 컨텍스트를 두지 않는다**는 점이 JPA와의 결정적 차이
- 저장할 때마다 실제 DB 상태를 신뢰하고 즉시 SQL을 실행하는 방식(예측 가능한 SQL)

**ORM vs 단순 매핑 접근의 근본적 차이**
- JPA: "객체 그래프를 어떻게 하면 자연스럽게 저장할까"에서 출발 → 지연 로딩, 캐시, 프록시 등 다양한 장치가 필요
- Spring Data JDBC: "Aggregate 단위로 어떻게 하면 단순하고 예측 가능하게 저장할까"에서 출발 → 프록시도, 지연 로딩도, 영속성 컨텍스트도 의도적으로 배제

### 2. Spring Data JPA의 장점과 단점

**장점**
- **지연 로딩(Lazy Loading)**: 연관 엔티티를 실제 사용 시점까지 조회를 미뤄 불필요한 쿼리를 줄임
- **변경 감지(Dirty Checking)**: `save()`를 명시적으로 호출하지 않아도 트랜잭션 커밋 시점에 스냅샷과 비교해 UPDATE SQL을 자동 생성
- **1차 캐시**: 같은 영속성 컨텍스트 내에서 동일 PK 조회 시 추가 쿼리 없이 캐시된 엔티티 반환, 동일성(identity) 보장
- **연관관계 그래프 탐색**: `@OneToMany`, `@ManyToMany` 등을 활용해 객체지향적으로 연관 데이터를 자유롭게 탐색 가능

**단점**
- **학습 곡선**: 영속성 컨텍스트, 프록시, 캐스케이드, 페치 전략 등 개념이 많고 내부 동작을 모르면 예상치 못한 쿼리가 발생
- **영속성 컨텍스트 복잡성**: 준영속/영속 상태 전환, `merge()`, 지연 로딩 예외(`LazyInitializationException`) 등 상태 관리 부담
- **N+1 문제**: 연관관계를 지연 로딩으로 설정했을 때, 컬렉션을 순회하며 각 엔티티마다 추가 쿼리가 발생하는 대표적 성능 함정
- **OSIV(Open Session In View)**: 뷰 렌더링까지 영속성 컨텍스트를 열어두는 기본 설정으로 인해 커넥션 점유 시간이 길어질 수 있음
- **예측 어려운 SQL**: 연관관계 매핑, 캐스케이드 옵션에 따라 실제 실행되는 SQL을 코드만 보고 예측하기 어려움

### 3. Spring Data JDBC의 장점과 단점

**장점**
- **단순한 생명주기(Persistence by Reachability)**: 영속성 컨텍스트가 없어 Aggregate Root에서 도달 가능한(reachable) 모든 엔티티가 그 Aggregate에 속한다는 단일 규칙으로 저장 범위가 결정됨
- **명시적 SQL**: `save()` 호출 시점에 즉시 SQL이 실행되므로 "언제 어떤 쿼리가 나가는지" 예측이 쉬움
- **낮은 학습 비용**: 프록시, 지연 로딩, 캐스케이드 옵션 같은 JPA 특유의 개념을 배울 필요가 없음

**단점**
- **지연 로딩/양방향 연관관계 미지원**: 모든 연관 엔티티는 즉시 로딩되며, 양방향 매핑을 지원하지 않아(오직 Aggregate Root → 자식 방향만) 설계 시 방향성을 명확히 정해야 함
- **캐싱 부재**: 영속성 컨텍스트(1차 캐시)가 없으므로 동일 트랜잭션 내에서도 같은 PK를 다시 조회하면 새로운 쿼리가 나감
- **복잡한 도메인 표현의 한계**: 공식 문서에서도 명시하듯, 기존 구현은 Aggregate Root를 저장할 때 참조된 자식 엔티티들을 **모두 삭제 후 재삽입(delete-and-reinsert)** 하는 방식이라 일부만 변경되어도 전체가 재작성되는 비효율이 있음(Spring Data JDBC 3.2+의 Single Query Loading 등으로 일부 개선 중)

### 4. Aggregate와 도메인 모델링 관점

- **DDD Aggregate Root 중심 설계와 Spring Data JDBC**: Spring Data JDBC는 리포지토리를 Aggregate Root 단위로만 만들도록 강제한다. 즉 `Repository` 하나당 Aggregate Root 하나가 원칙이며, 그 외 엔티티는 Aggregate Root를 통해서만 접근해야 한다. 이는 Eric Evans가 정의한 DDD의 Aggregate 개념(트랜잭션 일관성 경계)을 프레임워크 레벨에서 강제로 구현한 것에 가깝다.
- **영속성 컨텍스트가 도메인 순수성에 미치는 영향**: JPA는 엔티티가 프록시, 지연 로딩 필드, `@Id` 자동 생성 전략 등 영속성 프레임워크의 관심사를 도메인 객체 내부에 침투시키는 경향이 있다(Anemic vs Rich 모델 논쟁, 프록시로 인한 `equals/hashCode` 문제 등). 반면 Spring Data JDBC는 엔티티를 순수 POJO(불변 객체, 생성자 기반)로 유지하기 쉽고, `AggregateReference` 같은 값 타입으로 다른 Aggregate에 대한 참조를 ID로만 표현하도록 권장해 Aggregate 간 결합도를 낮춘다.

### 5. 선택 기준과 상황별 비교

| 상황 | 적합한 선택 | 이유 |
|---|---|---|
| 단순 CRUD, 명확한 Aggregate 경계 | Spring Data JDBC | 예측 가능한 SQL, 낮은 러닝커브, 트랜잭션 경계와 코드 경계 일치 |
| 복잡한 연관관계, 다단계 그래프 탐색 | Spring Data JPA | 지연 로딩, 캐시, 연관관계 자동 관리로 생산성 높음 |
| 대량 배치/조회 위주 | 둘 다 순수 SQL/QueryDSL 등으로 보완 필요 | ORM 자동 생성 SQL은 배치 최적화에 한계가 있음 |
| 성능 예측 가능성이 중요한 도메인(정산, 결제) | Spring Data JDBC 또는 JPA + 명시적 쿼리 | "무슨 쿼리가 나갈지 모른다"는 리스크를 줄여야 함 |
| 유지보수 관점(팀 전체가 JPA에 익숙) | Spring Data JPA | 팀 러닝커브와 생태계(QueryDSL, Auditing 등) 성숙도 고려 |

## 핵심 정리
- JPA는 "영속성 컨텍스트 기반 객체 그래프 관리"로 생산성은 높지만 내부 동작을 모르면 N+1, OSIV 같은 성능 함정에 빠지기 쉽다.
- Spring Data JDBC는 "Aggregate 단위 Persistence by Reachability"로 단순하고 예측 가능하지만, 지연 로딩·캐시가 없고 자식 엔티티를 delete-and-reinsert 하는 구조적 비효율이 있다.
- 두 기술 모두 단독으로 모든 상황을 커버하지 못하므로, 도메인 성격(단순 CRUD vs 복잡한 그래프)에 따라 선택하거나 하이브리드로 조합하는 전략이 필요하다.

## 기술적 한계와 보완 전략

**JPA의 N+1/OSIV 보완**
- **Fetch Join**: JPQL에서 `join fetch`로 연관 엔티티를 한 번의 쿼리로 함께 조회해 N+1을 제거(단, 컬렉션 다중 Fetch Join은 카티전 곱과 페이징 제약에 주의)
- **EntityGraph**: `@EntityGraph`로 특정 쿼리 메서드에서만 필요한 연관관계를 즉시 로딩하도록 지정, 엔티티 매핑 자체를 건드리지 않고 유연하게 페치 전략을 오버라이드
- **DTO Projection**: 필요한 컬럼만 조회하는 인터페이스/클래스 기반 Projection이나 QueryDSL의 `Projections.constructor()`로 연관관계 탐색 자체를 피함
- **OSIV off**: `spring.jpa.open-in-view=false`로 설정해 트랜잭션 종료와 동시에 영속성 컨텍스트를 닫고, 서비스 계층에서 필요한 데이터를 모두 확정한 뒤 컨트롤러로 넘김(커넥션 점유 시간 단축)

**Spring Data JDBC의 연관관계 한계 보완**
- **Aggregate 경계 재설계**: 지연 로딩이 필요한 상황이라면 애초에 Aggregate가 너무 크게 설계된 것일 수 있음 → Aggregate를 더 작게 쪼개고 다른 Aggregate는 ID 참조(`AggregateReference`)로만 연결
- **명시적 조회**: 필요한 연관 데이터는 별도 리포지토리 메서드나 커스텀 쿼리로 명시적으로 조회(자동 지연 로딩 대신 애플리케이션 코드에서 조합)

**하이브리드 전략**
- 저장(Command)은 ORM/Spring Data JDBC로 Aggregate 단위 일관성을 보장하고, 조회(Query)는 `JdbcTemplate`이나 QueryDSL로 필요한 컬럼만 뽑아내는 **CQRS 스타일 분리**가 실무에서 자주 쓰이는 절충안
- 읽기 전용 API는 Projection/DTO 기반으로 N+1 자체를 발생시키지 않도록 설계하고, 쓰기 API만 도메인 객체 중심으로 처리

## 키워드

- **Spring Data JDBC**: JDBC를 감싼 Spring Data 계열의 경량 리포지토리 구현체. 영속성 컨텍스트 없이 Aggregate 단위로 즉시 SQL을 실행하며, 현재는 Spring Data R2DBC와 함께 Spring Data Relational 프로젝트로 통합되어 있다.
- **Spring Data JPA**: JPA(Jakarta Persistence API) 표준을 감싸는 Spring Data 구현체. 내부적으로 Hibernate 같은 벤더를 사용해 엔티티 그래프와 영속성 컨텍스트 기반의 자동 SQL 생성, 캐시, 변경 감지를 제공한다.
- **Hibernate ORM**: 가장 널리 쓰이는 JPA 구현체. 프록시 기반 지연 로딩, 1차/2차 캐시, Dirty Checking 등을 제공하며 Spring Data JPA의 기본 구현체로 흔히 사용된다.
- **영속성 컨텍스트(Persistence Context)**: `EntityManager`가 관리하는 엔티티의 논리적 저장소. 동일 트랜잭션 내 엔티티의 동일성(identity)을 보장하고, 스냅샷 비교를 통해 변경 감지를 수행한다.
- **Persistence by Reachability**: Spring Data JDBC의 핵심 원칙. Aggregate Root에서 참조를 따라가며 도달(reachable) 가능한 모든 엔티티는 그 Aggregate에 속한 것으로 간주되어 함께 저장/삭제된다.
- **Aggregate Root (DDD)**: Eric Evans의 DDD에서 정의한 개념으로, 트랜잭션 일관성 경계를 이루는 엔티티 묶음의 대표 엔티티. Spring Data JDBC는 Repository를 Aggregate Root 단위로만 만들도록 강제한다.
- **Dirty Checking (변경 감지)**: JPA/Hibernate가 트랜잭션 커밋 시점에 영속성 컨텍스트 내 엔티티의 최초 스냅샷과 현재 상태를 비교해 변경분에 대한 UPDATE SQL을 자동 생성하는 메커니즘.
- **N+1 문제**: 1번의 조회 쿼리로 N개의 엔티티를 가져온 뒤, 각 엔티티의 지연 로딩된 연관관계를 사용할 때마다 추가로 N번의 쿼리가 발생하는 성능 문제.
- **OSIV (Open Session In View)**: 컨트롤러/뷰 렌더링이 끝날 때까지 영속성 컨텍스트(Hibernate Session)를 열어 지연 로딩을 허용하는 전략. 편리하지만 DB 커넥션을 오래 점유해 커넥션 풀 고갈 위험이 있다.
- **지연 로딩(Lazy Loading)**: 연관 엔티티를 즉시 조회하지 않고 실제로 접근하는 시점에 프록시를 통해 조회하는 전략. `FetchType.LAZY`로 설정하며, JPA에서는 지원되지만 Spring Data JDBC에서는 지원되지 않는다.

## 참고 자료
- [Spring Data Relational - Domain Driven Design and Relational Databases](https://docs.spring.io/spring-data/relational/reference/jdbc/domain-driven-design.html)
- [Spring Data Relational - Persisting Entities](https://docs.spring.io/spring-data/relational/reference/jdbc/entity-persistence.html)
- [Spring Data JPA Reference Documentation](https://docs.spring.io/spring-data/jpa/reference/index.html)
- [Spring Data JDBC, References, and Aggregates (Spring Blog)](https://spring.io/blog/2018/09/24/spring-data-jdbc-references-and-aggregates/)
- [Spring Data JDBC Project Page](https://spring.io/projects/spring-data-jdbc/)
