# MySQL CHECK 제약

## 개요
`CHECK` 제약은 컬럼에 들어올 수 있는 값의 범위를 데이터베이스 레벨에서 강제하는 도메인 무결성(Domain Integrity) 제약이다. 표준 SQL(SQL-92)에 정의되어 있고 PostgreSQL·Oracle·SQL Server는 오래전부터 정상 동작했지만, MySQL은 **8.0.16 이전까지 `CHECK` 구문을 파싱만 하고 조용히 무시(parsed and ignored)** 했다. 즉 `CREATE TABLE`에 `CHECK (price > 0)`를 적어도 실제로는 아무 검증도 하지 않는 "죽은 문법"이었다. **MySQL 8.0.16(2019-04-25 릴리스)부터** 테이블/컬럼 `CHECK` 제약의 핵심 기능이 모든 스토리지 엔진에 대해 실제로 구현되어 강제(enforced)되기 시작했다.

이 글은 CHECK 제약이 **무엇을 막는지**(문법·NULL 처리·금지 항목·운영 적용)를 다룬다. 서버가 **어디서 누가 어떤 순서로 조건식을 계산하는지**는 [MySQL CHECK 제약의 검증 동작 원리](./260903_01_MySQL_CHECK_제약_검증_동작_원리.md)에서 다룬다.

## 상세 내용

### 1. CHECK 제약이란
- 도메인 무결성: 컬럼(또는 여러 컬럼 조합)이 가질 수 있는 값의 집합을 제한하는 제약. `NOT NULL`이 "NULL 여부"만 다루고, `UNIQUE`가 "중복 여부"만 다루고, `FOREIGN KEY`가 "다른 테이블 참조 무결성"만 다루는 것과 달리, `CHECK`는 임의의 불리언 조건식으로 값의 범위·형식·상호관계를 검증한다.
- 예: 가격은 0보다 커야 한다(`price > 0`), 상태는 정해진 값 중 하나여야 한다(`status IN ('OPEN','CLOSED')`), 시작일은 종료일보다 앞서야 한다(`start_date <= end_date`).
- `CHECK`는 애플리케이션 코드나 API 계층을 거치지 않고 들어오는 모든 쓰기(수동 SQL, 배치, 다른 서비스의 직접 접근)에 대해서도 동일하게 적용되는 "최후 방어선" 역할을 한다.

### 2. MySQL에서의 CHECK 지원 히스토리
공식 문서([CHECK Constraints](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html))와 [8.0.16 릴리스 노트](https://dev.mysql.com/doc/relnotes/mysql/8.0/en/news-8-0-16.html)에 명시된 내용:

> Previously, MySQL permitted a limited form of `CHECK` constraint syntax, but parsed and ignored it. MySQL now implements the core features of table and column `CHECK` constraints, for all storage engines.

- **MySQL 5.7 이하(그리고 8.0.15까지)**: `CREATE TABLE ... CHECK (...)` 문법 자체는 파싱 에러 없이 허용되지만, 서버가 실제로 이 조건을 평가하지 않는다. 즉 `price < 0` 같은 위반 값도 `INSERT`가 그대로 성공한다. "제약이 있는 척"만 하는 상태였다.
- **MySQL 8.0.16 이상**: 테이블 `CHECK`와 컬럼 `CHECK`가 모든 스토리지 엔진(InnoDB, MyISAM 등)에서 실제로 강제된다. `INFORMATION_SCHEMA.CHECK_CONSTRAINTS` 테이블이 신설되어 정의된 제약을 조회할 수 있게 되었다.
- 이 변화는 "Incompatible Change"로 분류되어 있다. 과거에 (무시될 것이라 믿고) 실제로는 위반되는 `CHECK` 구문을 넣어둔 채 운영하던 스키마가 8.0.16 이상으로 마이그레이션되는 순간 기존 데이터 검증에 걸려 `ALTER`/`INSERT`가 실패할 수 있다.
- MariaDB는 MySQL과 갈라진 이후 **10.2.1부터** `CHECK` 제약을 실제로 지원했다.

버전을 반드시 확인해야 하는 이유: `SHOW VARIABLES LIKE 'version'` 또는 `SELECT VERSION()`으로 실행 중인 MySQL이 8.0.16 이상인지 먼저 확인하지 않으면, "제약을 걸었으니 안전하다"는 착각 속에서 실제로는 아무 방어도 되지 않는 상태로 운영할 수 있다.

### 3. 문법과 정의 방식
```sql
-- 컬럼 레벨 CHECK: 해당 컬럼만 참조 가능
CREATE TABLE product (
    id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    price INT CHECK (price > 0),
    stock INT CONSTRAINT stock_non_negative CHECK (stock >= 0)
);

-- 테이블 레벨 CHECK: 여러 컬럼을 함께 참조 가능
CREATE TABLE reservation (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    start_date DATE NOT NULL,
    end_date   DATE NOT NULL,
    CONSTRAINT chk_period CHECK (start_date <= end_date)
);

-- 기존 테이블에 나중에 추가
ALTER TABLE product
    ADD CONSTRAINT chk_price_positive CHECK (price > 0);

-- 제약 삭제
ALTER TABLE product DROP CONSTRAINT chk_price_positive;
```
- `CONSTRAINT [symbol]`로 이름을 지정하지 않으면 MySQL이 `테이블명_chk_1`, `테이블명_chk_2` 형태로 자동 이름을 부여한다(스키마 내 유일, 최대 64자).
- 이름을 명시적으로 부여해두면 이후 위반 발생 시 에러 메시지에서 어떤 제약인지 즉시 알 수 있고, `ALTER TABLE ... DROP CONSTRAINT`로 특정하기도 쉽다.

### 4. NOT ENFORCED 옵션
```sql
ALTER TABLE product
    ADD CONSTRAINT chk_price_positive CHECK (price > 0) NOT ENFORCED;

-- 필요할 때만 다시 활성화
ALTER TABLE product ALTER CHECK chk_price_positive ENFORCED;

-- 다시 비활성화
ALTER TABLE product ALTER CHECK chk_price_positive NOT ENFORCED;
```
- 기본값은 `ENFORCED`(즉시 강제 적용).
- `NOT ENFORCED`는 제약을 카탈로그에 정의해두되 검증은 하지 않는 상태다. 대량 마이그레이션, 배치성 데이터 적재처럼 일시적으로 검증을 끄고 싶을 때, 또는 검증 로직을 미리 문서화해두고 점진적으로 활성화하고 싶을 때 사용한다.
- 8.0.16 이전 버전의 "파싱만 되고 무시" 동작과 결과가 비슷해 보이지만 다르다. `NOT ENFORCED`는 명시적으로 선택한 상태이고 `INFORMATION_SCHEMA.CHECK_CONSTRAINTS`에서 `ENFORCED = 'NO'`로 조회되어 의도가 드러난다.

### 5. CHECK 제약의 평가 규칙 — NULL 3값 논리 함정
SQL의 조건식은 `TRUE`/`FALSE`/`UNKNOWN`(NULL 관련 3값 논리)로 평가된다. MySQL 공식 문서는 CHECK 제약이 `TRUE` 또는 `UNKNOWN`일 때 통과(satisfied)하고, `FALSE`일 때만 위반으로 처리한다고 명시한다.

```sql
CREATE TABLE product (
    id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    price INT CHECK (price > 0)
);

-- (1) 위반: price = -100 → FALSE → 에러 발생 (정상 동작)
INSERT INTO product (price) VALUES (-100);
-- ERROR 3819 (HY000): Check constraint 'product_chk_1' is violated.

-- (2) 함정: price = NULL → (NULL > 0)은 UNKNOWN → CHECK 통과 → INSERT 성공
INSERT INTO product (price) VALUES (NULL);
-- Query OK, 1 row affected

SELECT * FROM product;
-- +----+-------+
-- | id | price |
-- +----+-------+
-- |  2 |  NULL |
-- +----+-------+
```
`price > 0`이라는 조건만 보면 "가격은 반드시 양수"라고 기대하지만, `price`가 `NULL`이면 비교 자체가 성립/불성립을 판단할 수 없는 `UNKNOWN`이 되고, MySQL은 `UNKNOWN`을 "제약 위반이 아님"으로 처리한다. **즉 `CHECK`만으로는 NULL 유입을 막지 못한다.**

이를 막으려면 `NOT NULL`을 반드시 함께 선언해야 한다.
```sql
CREATE TABLE product (
    id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    price INT NOT NULL CHECK (price > 0)
);

INSERT INTO product (price) VALUES (NULL);
-- ERROR 1048 (23000): Column 'price' cannot be null
```
`NOT NULL`이 먼저 NULL 유입을 차단하고, `CHECK`가 값의 범위를 검증하는 2단 방어 구조가 되어야 "가격은 반드시 존재하며 양수"라는 의도가 실제로 강제된다.

### 6. CHECK에서 사용할 수 없는 것들
공식 문서 기준으로 CHECK 표현식에서 금지되는 것들:
- **서브쿼리**(`SELECT` 포함 불가)
- **다른 테이블의 컬럼 참조** — CHECK는 오직 정의된 테이블 내부 컬럼만 참조 가능(다중 테이블 무결성은 FK나 트리거로 해결해야 함)
- **AUTO_INCREMENT 컬럼**
- **비결정적(non-deterministic) 함수**: `NOW()`, `CURRENT_TIMESTAMP()`, `RAND()`, `UUID()`, `CONNECTION_ID()`, `CURRENT_USER()` 등. 실행 시점마다 값이 달라지는 함수는 제약의 일관성을 깨뜨리기 때문.
- **저장 함수(stored function), 로더블 함수, 사용자 정의 변수, 시스템 변수, 저장 프로그램 파라미터**
- **FK 참조 액션(`ON UPDATE`/`ON DELETE`)이 걸린 컬럼** — 공식 문서: "Foreign key referential actions (`ON UPDATE`, `ON DELETE`) are prohibited on columns used in `CHECK` constraints. Likewise, `CHECK` constraints are prohibited on columns used in foreign key referential actions." 즉 `ON DELETE SET NULL`, `ON UPDATE CASCADE` 같은 액션이 걸린 컬럼에는 CHECK를 걸 수 없고 그 역도 성립한다.
- 허용되는 것: 리터럴, 결정적(deterministic) 내장 함수, 연산자, 일반 컬럼과 생성 컬럼(Generated Column).

이 목록이 임의적으로 보이지만 하나의 원인에서 파생된다. MySQL은 CHECK 제약을 **스토리지 엔진에 행을 넘기기 전(`handler::write_row()` 직전) SQL 레이어에서** 평가한다. 그래서 그 시점의 행 버퍼만 보고 부작용 없이 항상 같은 답을 낼 수 없는 것은 모두 금지된다.
- `AUTO_INCREMENT`: 값이 `write_row()` 안에서 엔진이 정하므로 평가 시점에 아직 존재하지 않는다.
- 서브쿼리·타 테이블 컬럼: 행 쓰기 경로 한복판에서 실행기가 다른 테이블을 다시 읽어야 한다.
- 비결정적 함수: `ALTER TABLE`의 전수 검증과 이후 DML 검증이 다른 결과를 낼 수 있다.
- FK 참조 액션: 참조 액션은 엔진이 SQL 레이어를 거치지 않고 자식 행을 직접 수정하므로, 그 행에는 CHECK를 돌릴 방법이 없다. 서버가 런타임에 막을 수 없어 DDL 단계에서 조합 자체를 거부한다.

자세한 근거는 [검증 동작 원리](./260903_01_MySQL_CHECK_제약_검증_동작_원리.md)를 참고.

### 7. 다른 DBMS와의 비교
- **PostgreSQL / Oracle / SQL Server**: CHECK 제약을 오랫동안(사실상 표준 도입 초기부터) 완전히 지원해왔다. NULL 처리 규칙(3값 논리로 UNKNOWN 통과)도 표준 SQL과 동일하게 동작한다.
- **MariaDB**: MySQL에서 포크된 이후 **10.2.1(2016)**부터 CHECK 제약을 실제로 지원했다. MySQL(8.0.16, 2019)보다 약 3년 앞섰다.
- MySQL이 8.0.16 이전에는 CHECK를 무시했기 때문에, 그 시기 실무에서는 값 범위 제한을 위해 `ENUM`/`SET` 타입, 애플리케이션 검증, 또는 `BEFORE INSERT`/`BEFORE UPDATE` 트리거로 대체하는 관행이 자리 잡았다. 지금도 다중 테이블에 걸친 검증이나 서브쿼리가 필요한 복잡한 규칙은 여전히 트리거나 애플리케이션 레이어가 담당해야 한다.

### 8. 애플리케이션 검증 vs DB CHECK 제약
- Bean Validation(`@Min`, `@Max`, `@Positive`, `@NotNull` 등)은 애플리케이션 인스턴스 안에서 요청이 들어올 때만 동작한다. 다중 인스턴스 환경에서 하나의 서비스가 검증을 우회하거나, 배치 잡이 JPA를 거치지 않고 JDBC로 직접 쓰거나, DBA가 수동 SQL로 데이터를 수정하는 경로는 애플리케이션 검증을 전혀 거치지 않는다.
- `CHECK` 제약은 이런 우회 경로까지 포함해 데이터베이스에 도달하는 모든 쓰기 경로에 동일하게 적용되는 최후 방어선이다.
- 역할 분담: 애플리케이션 검증은 사용자에게 즉각적이고 친절한 에러 메시지를 주는 역할(UX), DB CHECK 제약은 데이터 정합성을 보장하는 최종 안전망(데이터 무결성) 역할로 이해하는 것이 합리적이다. 어느 한쪽만으로는 완전하지 않다.

### 9. JPA/Hibernate에서의 CHECK 제약
Hibernate 6 공식 Javadoc([org.hibernate.annotations.Check](https://docs.hibernate.org/orm/6.5/javadocs/org/hibernate/annotations/Check.html)) 기준 시그니처:
```java
@Target({TYPE, METHOD, FIELD})
@Retention(RUNTIME)
@Repeatable(Checks.class)
public @interface Check {
    String constraints();   // 필수: native SQL로 작성하는 체크 조건식
    String name() default "";  // 선택: 제약 이름
}
```
- `TYPE`(엔티티 클래스), `METHOD`, `FIELD`(기본 타입 필드, 연관관계, 컬렉션)에 적용 가능하며 `@Repeatable`이라 하나의 대상에 여러 개 선언할 수 있다.

```java
@Entity
@Table(name = "product")
@Check(name = "chk_price_positive", constraints = "price > 0")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Check(constraints = "price > 0")
    private Integer price;
}
```
- `hibernate.hbm2ddl.auto` 등 DDL 자동 생성 시 `@Check`에 선언한 조건이 `CREATE TABLE`의 `CHECK (...)` 절로 반영된다. 즉 실행 중인 MySQL이 8.0.16 미만이면 Hibernate가 DDL을 만들어줘도 여전히 무시된다는 점을 기억해야 한다.
- 제약 위반 시 MySQL은 `SQLState 23000` 계열 에러(`Check constraint '...' is violated.`)를 던지고, Hibernate/JPA는 이를 `org.hibernate.exception.ConstraintViolationException`(JPA 표준의 `jakarta.persistence.PersistenceException`으로 래핑)으로 전파한다. Bean Validation의 `jakarta.validation.ConstraintViolationException`과 이름이 유사하지만 별개의 예외이므로 예외 처리 시 혼동하지 않도록 주의한다.

### 10. 운영 관점: 기존 테이블에 CHECK 추가하기
- `ALTER TABLE ... ADD CONSTRAINT ... CHECK (...)`를 실행하면 MySQL은 기존에 저장된 모든 행에 대해 해당 조건을 전수 검증한다. 조건을 위반하는 기존 데이터가 하나라도 있으면 `ALTER`가 실패한다.
- 대용량 테이블에서는 사전에 위반 데이터를 조회해서 정리해야 한다.
```sql
SELECT id, price FROM product WHERE NOT (price > 0) OR price IS NULL;
```
- 이 전수 검증은 별도 검증 쿼리가 아니라 **테이블 복사 루프 안에서 행마다 수행**된다(WL#929 F.4.6: "When copying table rows, validate all enforced check constraints on the table row."). 그래서 다음 경우 in-place ALTER이 설계상 **차단되고 COPY가 강제**된다(F.4.4).
  1. 새 CHECK 제약 생성
  2. `NOT ENFORCED → ENFORCED` 전환
  3. 제약이 참조하는 컬럼의 타입 변경
  4. 제약이 참조하는 컬럼의 속성 변경
- 즉 `ALGORITHM=INPLACE, LOCK=NONE`으로 신규 CHECK를 추가하려는 시도는 조건이 맞으면 되는 것이 아니라 **원칙적으로 실패**한다. 대용량 테이블에서 `gh-ost`·`pt-online-schema-change`는 "검토 대상"이 아니라 사실상 필수 경로다. 검증을 생략하는 `WITHOUT VALIDATION` 절도 지원되지 않는다(WL#12802로 분리).
- 처음부터 `NOT ENFORCED`로 제약을 추가해 배포한 뒤, 위반 데이터를 정리하고 나서 `ALTER CHECK ... ENFORCED`로 전환하는 점진적 롤아웃도 실무에서 유효하다. 단 이 전략은 **전수 검증과 복사 비용을 없애는 것이 아니라 미루는 것**이다. `ENFORCED`로 켜는 순간 위 2번에 해당해 결국 한 번은 복사를 겪는다. 실제 가치는 "그 사이에 위반 데이터를 정리할 시간을 확보하고, 실패 지점을 애플리케이션 배포와 분리하는 것"에 있다.
- 켜지 않은 `NOT ENFORCED` 제약은 문서화된 의도일 뿐 방어가 아니다. 도입 시 "언제 `ENFORCED`로 전환할 것인가"를 함께 티켓으로 남겨야 한다.

### 11. 성능과 옵티마이저 영향
- `CHECK` 제약이 평가되는 문장은 `INSERT`, `UPDATE`, `REPLACE`, `LOAD DATA`, `LOAD XML` 다섯 가지다. 이 시점마다 평가되므로 쓰기 경로에 약간의 CPU 오버헤드가 추가된다. 조건식이 단순 비교(`price > 0`)라면 무시할 수준이지만, 복잡한 함수 호출이 포함되면 대량 삽입 시 누적 비용을 고려해야 한다.
- 비용은 **행 수 × ENFORCED 제약 개수 × 조건식 평가 비용**이고 전부 CPU다. I/O도 없고 락도 잡지 않으므로 동시성에는 영향이 없다.
- 조회(`SELECT`) 성능에는 직접적인 영향이 없다. `CHECK`는 인덱스가 아니라 쓰기 시점 검증 로직이기 때문이다. 옵티마이저가 CHECK 조건을 조회 최적화에 활용하지도 않는다(PostgreSQL의 constraint exclusion 같은 최적화는 없다).
- `INSERT IGNORE` / `UPDATE IGNORE` / `LOAD DATA ... IGNORE` / `LOAD XML ... IGNORE`를 사용하면 위반 행에 대해 에러 대신 경고만 발생시키고 해당 행을 건너뛴다. 대량 적재 배치에서 일부 불량 데이터 때문에 전체가 롤백되는 것을 막고 싶을 때 활용할 수 있다.

### 12. 실무 활용 예시
```sql
-- 금액/수량 음수 방지
price INT NOT NULL CHECK (price > 0)
stock INT NOT NULL CHECK (stock >= 0)

-- 상태값 화이트리스트 (ENUM 대신 CHECK + VARCHAR로 유연하게 관리하고 싶을 때)
status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','PAID','CANCELLED'))

-- 기간 컬럼의 start <= end
CONSTRAINT chk_period CHECK (start_date <= end_date)

-- 생성 컬럼(Generated Column) + CHECK 조합: JSON 필드 안의 값 검증
CREATE TABLE order_item (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    payload  JSON NOT NULL,
    qty      INT AS (payload->>'$.qty') STORED,
    CHECK (qty > 0)
);
```
`ENUM` 대신 `VARCHAR + CHECK (... IN (...))`을 쓰면 향후 값 추가/삭제 시 `ALTER TABLE ... MODIFY COLUMN`으로 `ENUM` 정의 전체를 바꾸는 것보다 `ALTER TABLE ... DROP/ADD CONSTRAINT`로 조건만 갈아끼우는 편이 스키마 변경 비용이 낮은 경우가 많다.

## 핵심 정리
- MySQL은 **5.7 이하(8.0.15까지)에서 CHECK 구문을 파싱만 하고 무시**했고, **8.0.16(2019-04-25)부터 실제로 강제(enforced)**한다. 운영 중인 MySQL 버전을 반드시 먼저 확인해야 한다.
- CHECK는 SQL 3값 논리를 따르므로 조건식이 `TRUE`/`UNKNOWN`일 때 통과하고 `FALSE`일 때만 위반이다. **컬럼 값이 NULL이면 어떤 CHECK 조건도 통과**하므로, "반드시 값이 있고 조건을 만족해야 한다"는 의도를 지키려면 `NOT NULL`을 CHECK와 항상 함께 선언해야 한다.
- CHECK는 서브쿼리, 다른 테이블 참조, AUTO_INCREMENT 컬럼, 비결정적 함수(`NOW()`, `RAND()`, `UUID()` 등)를 사용할 수 없고, **FK 참조 액션이 걸린 컬럼과도 함께 쓸 수 없다.** 이 금지 목록은 CHECK가 `write_row()` 직전 SQL 레이어에서 평가된다는 설계에서 파생된 결과다. 이런 검증이 필요하면 트리거나 애플리케이션 레이어로 보완해야 한다.
- 기존 테이블에 CHECK를 추가하거나 `NOT ENFORCED → ENFORCED`로 전환하는 것은 **설계상 테이블 복사가 강제**되며 복사 루프에서 행마다 검증한다. `ALGORITHM=INPLACE`로는 되지 않으므로 대용량 테이블에서는 온라인 스키마 변경 도구가 사실상 필수다.
- Hibernate 6의 `@Check(constraints = "...", name = "...")`는 DDL 자동 생성 시 CHECK 절을 만들어주지만, 실제로 강제되는지는 여전히 MySQL 서버 버전에 달려 있다.

## 기술적 한계와 보완 전략
- **서브쿼리/다중 테이블 검증 불가** → 트리거(`BEFORE INSERT`/`BEFORE UPDATE`) 또는 애플리케이션 서비스 레이어에서 처리
- **NULL이 항상 통과하는 3값 논리 문제** → 검증이 필요한 컬럼에는 반드시 `NOT NULL`을 병행 선언
- **테이블 간 참조 무결성 불가** → `FOREIGN KEY` 제약이나 도메인 로직(애플리케이션 트랜잭션)으로 보완
- **FK 참조 액션과 동일 컬럼에 병행 불가**(우회로 없음) → 참조 액션과 CHECK 중 무엇이 더 중요한지 결정하고, 포기한 쪽의 검증을 애플리케이션 트랜잭션이나 트리거로 옮긴다
- **`sql_mode`에 따라 판정 결과가 달라질 수 있음**(조건식은 테이블에 속하지만 평가 규칙은 세션에 속한다) → 커넥션 풀 초기화와 마이그레이션 실행 환경의 `sql_mode`를 동일하게 고정
- **에러 메시지 가독성 문제**(`Check constraint 'product_chk_1' is violated.`처럼 자동 생성 이름은 원인 파악이 어려움) → 제약 이름을 의미 있게 명시(`CONSTRAINT chk_price_positive CHECK ...`)하고, 서버/애플리케이션에서 `ConstraintViolationException`을 캐치해 사용자 친화적 메시지로 변환하는 예외 핸들러 구성

## 키워드
- **CHECK Constraint**: 컬럼(또는 여러 컬럼)이 가질 수 있는 값의 범위를 DB 레벨에서 강제하는 도메인 무결성 제약. INSERT/UPDATE 시점에 조건식을 평가한다.
- **Domain Integrity**: 컬럼에 저장될 수 있는 값의 유효 범위를 보장하는 무결성 개념으로, NOT NULL·UNIQUE·CHECK·FK 등이 함께 데이터베이스 전체 무결성을 구성한다.
- **MySQL 8.0.16**: CHECK 제약이 "파싱만 되고 무시"되던 상태에서 실제로 강제되도록 바뀐 기준 버전(2019-04-25 릴리스). `INFORMATION_SCHEMA.CHECK_CONSTRAINTS`도 이 버전에서 신설됨.
- **NOT ENFORCED**: CHECK 제약을 정의는 해두되 검증을 비활성화하는 옵션. `ALTER TABLE ... ALTER CHECK ... [NOT] ENFORCED`로 켜고 끌 수 있다.
- **Three-Valued Logic (NULL)**: SQL 조건식이 TRUE/FALSE/UNKNOWN 세 값을 가지는 논리 체계. NULL이 관여한 비교는 UNKNOWN이 되고, CHECK 제약은 UNKNOWN을 위반으로 취급하지 않는다.
- **Non-deterministic Function**: 같은 인자로 호출해도 매번 다른 결과를 반환하는 함수(`NOW()`, `RAND()`, `UUID()` 등). CHECK 표현식에는 사용할 수 없다.
- **ALTER TABLE ADD CONSTRAINT**: 기존 테이블에 이름이 부여된 제약(CHECK 포함)을 추가하는 DDL 문. 기존 데이터 전체를 대상으로 즉시 검증이 수행된다.
- **Online DDL**: 테이블 락을 최소화하며 스키마를 변경하는 기법/도구(InnoDB의 INPLACE/INSTANT 알고리즘, gh-ost, pt-online-schema-change 등). 대용량 테이블에 CHECK를 추가할 때 고려 대상.
- **Generated Column**: 다른 컬럼이나 표현식(JSON 경로 포함)으로부터 값이 자동 계산되는 컬럼(STORED/VIRTUAL). CHECK와 조합해 JSON 필드 내부 값을 검증하는 데 활용할 수 있다.
- **Bean Validation**: `@Min`, `@Positive`, `@NotNull` 등 애플리케이션 레이어에서 동작하는 검증 표준(Jakarta Bean Validation). DB CHECK 제약과 역할을 분담하되 서로를 대체하지 않는다.

## 참고 자료
- [MySQL 8.0 Reference Manual - 15.1.20.6 CHECK Constraints](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)
- [MySQL 8.0 Release Notes - Changes in MySQL 8.0.16 (2019-04-25)](https://dev.mysql.com/doc/relnotes/mysql/8.0/en/news-8-0-16.html)
- [MySQL Blog - MySQL 8.0.16: Introducing CHECK constraint](https://dev.mysql.com/blog-archive/mysql-8-0-16-introducing-check-constraint/)
- [MySQL Worklog - WL#929: CHECK constraints](https://dev.mysql.com/worklog/task/?id=929) — CHECK 제약의 공식 설계 문서(평가 시점, COPY 강제 조건, 복제 동작)
- [Hibernate ORM 6.5 Javadoc - org.hibernate.annotations.Check](https://docs.hibernate.org/orm/6.5/javadocs/org/hibernate/annotations/Check.html)
- [MySQL 8.0 Reference Manual - INFORMATION_SCHEMA.CHECK_CONSTRAINTS Table](https://dev.mysql.com/doc/refman/8.0/en/information-schema-check-constraints-table.html)
