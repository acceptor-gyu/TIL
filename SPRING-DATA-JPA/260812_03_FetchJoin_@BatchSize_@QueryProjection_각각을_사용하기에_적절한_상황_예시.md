# FetchJoin, @BatchSize, @QueryProjection 각각을 사용하기에 적절한 상황 예시

## 개요
- N+1 문제와 조회 성능 최적화를 위한 세 가지 대표 도구 비교
- 각 도구가 해결하려는 문제와 트레이드오프
- "언제 무엇을 써야 하는가"에 대한 상황별 선택 가이드
- 프롭테크 **주거 관련 정보 제공 서비스**(주거용 부동산 후기·시세·전세 안전 진단) 실무 상황을 예시로 각 도구의 선택 기준을 설명

### 예시 도메인 모델 (주거 관련 정보 제공 서비스)
- `Property`(매물, 원룸·빌라·오피스텔·아파트) — `Building`(건물, ManyToOne), `Region`(지역/행정동, ManyToOne)
- `Building`(건물) — `Review`(실거주 후기) 컬렉션(OneToMany), `PriceQuote`(시세 이력) 컬렉션(OneToMany)
- `Review`(실거주 후기) — `Member`(작성자, ManyToOne)
- `SafetyDiagnosis`(전세 안전 진단) — `Property`와 1:1, 등기·근저당·보증금 기반 안전 등급 보유
- 흐름: 지역/건물 단위로 매물이 등록되고, 건물에는 실거주 후기와 시세가 쌓이며, 전세 매물은 안전 진단 결과를 함께 제공

## 상세 내용

### 1. Fetch Join
#### 개념
- JPQL `join fetch`로 연관 엔티티를 한 번의 쿼리로 즉시 로딩
- 프록시가 아닌 실제 엔티티 그래프를 채워서 반환

#### 적절한 상황
- 연관 관계가 ToOne(ManyToOne, OneToOne)인 경우
- 조회 대상 엔티티와 연관 엔티티를 항상 함께 사용하는 경우
- 컬렉션이 하나일 때(단일 ToMany) 페이징이 필요 없는 경우

#### 주의 상황
- 컬렉션(ToMany) 페치 조인 + 페이징 → 메모리 페이징 위험(경고 로그)
- 둘 이상의 컬렉션 페치 조인 → MultipleBagFetchException / 카티전 곱
- DISTINCT 필요성과 중복 row 처리

#### 주거 관련 정보 제공 서비스 실무 예시
- **상황**: 매물 목록/검색 결과 화면에서 각 `Property` 카드에 소속 `Building`(건물명·준공년도)과 `Region`(행정동)을 항상 함께 표시한다.
- **문제**: `Property`만 조회하면 목록 20건에 대해 `Building` 20번 + `Region` 20번 = N+1 쿼리가 발생한다.
- **해결**: 둘 다 ToOne이므로 Fetch Join으로 한 방에 조회한다.
  ```java
  @Query("select p from Property p " +
         "join fetch p.building b " +
         "join fetch p.region r " +
         "where p.dealType = :dealType")
  List<Property> findWithBuildingAndRegion(@Param("dealType") DealType dealType);
  ```
- **포인트**: ToOne 관계라 카티전 곱이 없고, 목록에서 건물·지역 정보를 항상 함께 쓰므로 Fetch Join이 가장 적합. 단, 여기에 건물의 `Review`(후기) 같은 컬렉션까지 페이징과 함께 붙이려 하면 안 됨 → 그건 @BatchSize로.

### 2. @BatchSize (또는 default_batch_fetch_size)
#### 개념
- 지연 로딩된 프록시/컬렉션을 IN 절로 묶어 한 번에 로딩
- N+1을 N/batchSize + 1 수준으로 완화

#### 적절한 상황
- 컬렉션(ToMany) + 페이징을 동시에 해야 하는 경우
- 여러 연관 관계를 동시에 초기화해야 하는 경우
- 페치 조인의 카티전 곱을 피하면서 N+1을 줄이고 싶은 경우

#### 설정 위치
- 글로벌: `spring.jpa.properties.hibernate.default_batch_fetch_size`
- 개별: 엔티티/컬렉션에 `@BatchSize(size = n)`

#### 주거 관련 정보 제공 서비스 실무 예시
- **상황**: 지역 상세 화면에서 해당 지역의 `Building`(건물)을 페이지 단위(한 페이지 20건)로 조회하고, 각 건물에 달린 `Review`(실거주 후기) 컬렉션을 미리보기로 함께 펼쳐 보여줘야 한다.
- **문제**: `Building`과 컬렉션 `Review`를 Fetch Join하면 컬렉션 페이징이 되지 않아 Hibernate가 전체를 메모리로 읽어 페이징(경고 로그, OOM 위험). 그렇다고 지연 로딩으로 두면 건물 20건마다 후기 쿼리가 나가 N+1 발생.
- **해결**: 페이징은 `Building` 기준으로 정상 수행하고, 컬렉션은 @BatchSize로 IN 절 묶음 조회.
  ```java
  // application.yml
  // spring.jpa.properties.hibernate.default_batch_fetch_size: 100

  @Entity
  class Building {
      @BatchSize(size = 100)
      @OneToMany(mappedBy = "building")
      private List<Review> reviews = new ArrayList<>();
  }

  Page<Building> page = buildingRepository.findByRegionId(regionId, pageable);
  // 건물 페이지 1번 조회 + 후기는 where building_id in (...) 형태로 묶여 조회
  ```
- **포인트**: 컬렉션 + 페이징 조합의 정석. 건물에 `Review`뿐 아니라 `PriceQuote`(시세 이력) 컬렉션까지 함께 초기화해도 default_batch_fetch_size 전역 설정으로 모두 배치 로딩되어 N+1이 크게 완화된다.

### 3. @QueryProjection (QueryDSL) / DTO Projection
#### 개념
- 필요한 컬럼만 선택해 DTO로 직접 조회
- 엔티티를 거치지 않아 영속성 컨텍스트/지연 로딩 비용 제거

#### 적절한 상황
- 조회 전용(read-only) 화면/API로 엔티티가 필요 없는 경우
- 필요한 필드만 select 해서 네트워크/메모리 비용을 줄이고 싶은 경우
- 통계/집계, 목록 조회 등 성능이 중요한 조회

#### 트레이드오프
- DTO가 Q타입 생성 의존(@QueryProjection) → DTO가 QueryDSL에 의존
- 엔티티 변경 감지(dirty checking) 불가 → 순수 조회에 한정

#### 주거 관련 정보 제공 서비스 실무 예시
- **상황**: "지역별 시세 대시보드"에서 행정동명, 매물 수, 평균 보증금/월세, 평균 후기 평점만 집계해 보여준다. 화면은 읽기 전용이고 엔티티 그래프가 전혀 필요 없다.
- **문제**: `Region` → `Building` → `Property`/`Review` 엔티티를 다 로딩하면 불필요한 컬럼·연관까지 끌어와 메모리와 쿼리 비용이 크다.
- **해결**: 필요한 컬럼만 집계해 DTO로 직접 조회.
  ```java
  @QueryProjection
  public RegionPriceStatDto(String regionName, long propertyCount,
                            double avgDeposit, double avgMonthlyRent, double avgRating) { ... }

  queryFactory
      .select(new QRegionPriceStatDto(
          region.name, property.count(),
          property.deposit.avg(), property.monthlyRent.avg(), review.rating.avg()))
      .from(property)
      .join(property.region, region)
      .leftJoin(property.building.reviews, review)
      .groupBy(region.id)
      .fetch();
  ```
- **포인트**: 조회 전용 통계/목록은 엔티티를 거치지 않고 DTO로 바로 받는 게 가장 가볍다. 매물 목록 카드(제목·대표사진·보증금/월세·평균평점·전세 안전 등급만 표시)처럼 "화면에 뿌리기만 하는" 조회에도 동일하게 적합. 단 이 DTO는 수정 저장 용도로는 쓰지 않는다.

### 4. 상황별 선택 요약 표
- ToOne 연관 함께 조회 → Fetch Join
- ToMany + 페이징 → @BatchSize
- 조회 전용 최소 필드 → @QueryProjection/DTO
- 복합(엔티티 필요 + 페이징) → Fetch Join(ToOne) + @BatchSize(ToMany) 조합

| 주거 관련 정보 제공 서비스 화면/기능 | 특징 | 선택 |
| --- | --- | --- |
| 매물 목록/검색 결과 (건물명·행정동 동반) | ToOne 연관, 항상 함께 사용 | Fetch Join |
| 지역 상세 - 건물 페이지 (후기 미리보기 포함) | ToMany + 페이징 | @BatchSize |
| 지역별 시세 대시보드 / 매물 카드 목록 | 조회 전용, 필요한 컬럼만 | @QueryProjection/DTO |
| 매물 상세 (건물·지역 + 후기 목록 페이징) | ToOne + ToMany 복합 | Fetch Join(building) + @BatchSize(reviews) |

## 핵심 정리
- Fetch Join: ToOne 즉시 로딩, 페이징 불가한 컬렉션 주의
- @BatchSize: 컬렉션 + 페이징 상황의 N+1 완화 최적
- @QueryProjection: 조회 전용, 최소 컬럼, 엔티티 불필요할 때
- 실무에서는 배타적 선택이 아니라 조합해서 사용

## 기술적 한계와 보완 전략
- Fetch Join: 컬렉션 페이징 한계 → ToOne만 페치 조인 + ToMany는 @BatchSize
- @BatchSize: 여전히 추가 쿼리 발생 → batch size 튜닝, IN 절 파라미터 개수 고려
- @QueryProjection: DTO의 QueryDSL 의존 → 순수 DTO는 Projections.constructor/bean 활용
- 공통: 실제 실행 쿼리를 로그(show_sql, p6spy)로 검증

## 키워드
- **Fetch Join**: JPQL의 `join fetch` 절로 연관 엔티티를 프록시가 아닌 실제 데이터로 한 번의 SELECT에 즉시 로딩하는 문법. 지연 로딩 설정을 무시하고 즉시 함께 조회한다.
- **N+1 문제**: 연관 엔티티를 지연 로딩으로 설정했을 때, 최초 1번의 쿼리로 목록을 조회한 후 각 엔티티마다 연관 데이터를 조회하는 추가 쿼리(N번)가 발생해 총 N+1번의 쿼리가 실행되는 문제.
- **@BatchSize**: Hibernate 전용 어노테이션으로, 지연 로딩되는 프록시/컬렉션을 개별 조회하는 대신 지정한 size만큼 묶어서 `WHERE id IN (...)` 형태로 조회해 쿼리 수를 줄인다.
- **default_batch_fetch_size**: `spring.jpa.properties.hibernate.default_batch_fetch_size`로 애플리케이션 전역에 배치 사이즈를 설정하는 옵션. 엔티티마다 `@BatchSize`를 붙이지 않아도 일괄 적용된다.
- **@QueryProjection**: QueryDSL이 제공하는 어노테이션으로, DTO 생성자에 붙이면 컴파일 시점에 해당 DTO 전용 Q타입(QDto)이 생성되어 타입 안전하게 DTO로 직접 조회할 수 있다.
- **DTO Projection**: 엔티티 전체가 아닌 필요한 컬럼만 select해 DTO로 매핑하는 조회 방식. `Projections.constructor`, `Projections.bean`, `@QueryProjection` 등으로 구현한다.
- **QueryDSL**: JPQL을 자바 코드로 타입 안전하게 작성할 수 있게 해주는 프레임워크. Q타입 메타모델을 기반으로 동적 쿼리와 프로젝션을 지원한다.
- **지연 로딩 / 즉시 로딩(Lazy/Eager Loading)**: 연관 엔티티를 실제 사용 시점에 조회할지(LAZY, 프록시 반환), 연관 엔티티 조회 시점에 함께 조회할지(EAGER)를 결정하는 FetchType 전략.
- **MultipleBagFetchException**: 두 개 이상의 컬렉션(List 등 Bag 타입)을 동시에 Fetch Join할 때 Hibernate가 카티전 곱으로 인한 데이터 정합성 문제를 막기 위해 던지는 예외.
- **카티전 곱(Cartesian Product)**: 컬렉션을 Fetch Join할 때 조인되는 로우 수만큼 부모 엔티티 row가 중복되어 반환되는 현상. 결과 row 수가 급증하고 메모리 사용량이 늘어난다.

## 참고 자료
- [Hibernate ORM User Guide - Fetching](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#fetching)
- [Hibernate Javadocs - @BatchSize](https://docs.hibernate.org/orm/6.5/javadocs/org/hibernate/annotations/BatchSize.html)
- [Hibernate Javadocs - FetchSettings (default_batch_fetch_size)](https://docs.hibernate.org/orm/6.5/javadocs/org/hibernate/cfg/FetchSettings.html)
- [Jakarta Persistence Specification - JPQL BNF (JOIN FETCH)](https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1)
- [Querydsl Reference Guide - Result handling / Bean population](http://querydsl.com/static/querydsl/latest/reference/html_single/#d0e2074)
- [Spring Data JPA Reference - Projections](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)
