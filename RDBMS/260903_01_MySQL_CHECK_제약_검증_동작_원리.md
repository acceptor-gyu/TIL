# MySQL CHECK 제약의 검증 동작 원리

## 개요
[MySQL CHECK 제약](./260902_01_MySQL_CHECK_제약.md)에서는 CHECK 제약이 **무엇을 막는지**(문법, NULL 3값 논리, 금지 항목, 운영 적용)를 정리했다. 이 글은 그 다음 질문을 다룬다. **누가, 어디서, 어떤 순서로 조건식을 계산하고, 결과를 어떻게 되돌려주는가.**

CHECK 제약은 **스토리지 엔진이 아니라 SQL 레이어에서, `handler::write_row()` 호출 직전에** 평가된다. 공식 문서의 "for **all storage engines**"라는 표현이 그 힌트다. 엔진마다 따로 구현한 것이 아니라 엔진 위쪽 서버 레이어에 한 번 구현했기 때문에 InnoDB·MyISAM 모두에서 동일하게 동작한다.

이 글의 내부 동작 서술은 대부분 MySQL 공식 설계 문서인 [WL#929: CHECK constraints](https://dev.mysql.com/worklog/task/?id=929)에 근거한다.

## 상세 내용

### 1. 핵심 문장
WL#929의 DML Operations 절:

> "For DML operations **after the assignments of defaults and invoking BEFORE trigger**, check constraints are evaluated."

이 한 문장에 이 글의 내용이 거의 다 압축되어 있다. 아래는 이 문장을 앞뒤로 펼친 것이다.

### 2. DDL 시점 — 조건식은 어디에 저장되는가
`CREATE TABLE product (price INT CHECK (price > 0))`를 실행하면:

1. 파서가 조건식을 `Sql_check_constraint_spec` 객체로 만든다(제약 이름 + 표현식 + ENFORCED 상태).
2. 이 시점에 **정적 검증**이 수행된다 — 서브쿼리 사용 여부, 비결정적 함수, `AUTO_INCREMENT` 컬럼 참조, 타 테이블 컬럼 참조, 그리고 식이 boolean 타입으로 평가되는지.
3. 통과하면 데이터 딕셔너리 테이블에 한 행으로 저장된다.

```sql
CREATE TABLE mysql.check_constraints {
  id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  schema_id BIGINT UNSIGNED NOT NULL,
  table_id  BIGINT UNSIGNED NOT NULL,
  name      VARCHAR(64) NOT NULL COLLATE utf8_tolower_ci,
  enforced  ENUM('YES', 'NO') NOT NULL,   -- 제약 상태
  check_clause      longblob NOT NULL,    -- 실제 평가에 쓰이는 원본
  check_clause_utf8 LONGTEXT NOT NULL     -- INFORMATION_SCHEMA 노출용
}
```

- 컬럼 레벨 CHECK와 테이블 레벨 CHECK가 **구분 없이 같은 테이블의 한 행**으로 저장된다. 정의 위치는 문법적 편의일 뿐 저장 구조와 평가 방식은 동일하다는 뜻이다.
- `INFORMATION_SCHEMA.CHECK_CONSTRAINTS`는 이 테이블을 `mysql.tables`·`mysql.schemata`·`mysql.catalogs`와 조인한 **뷰**다. 즉 조회 시 `check_clause_utf8` 컬럼을 읽는 것이고, 실제 평가에 쓰이는 값은 `check_clause`다.
- 제약 이름 충돌을 막기 위해 `CHECK_CONSTRAINT`라는 **MDL 네임스페이스**가 신설되었다. 인덱스·FK와는 별개의 이름 공간이므로 같은 스키마 안에서만 유일하면 된다.

### 3. 테이블 open 시점 — 문자열에서 Item 트리로
조건식은 SQL마다 재파싱되지 않는다. 테이블이 열릴 때 한 번 실행 가능한 트리로 변환되어 캐시된다.

```
TABLE_SHARE  →  Sql_check_constraint_share    (조건식을 문자열 형태로 보관)
                        |
                open_table_from_share()
                        └─ unpack_value_generator()
                           ↑ 생성 컬럼 / DEFAULT 식과 동일한 메커니즘
                        |
TABLE        →  Sql_table_check_constraint    (언팩된 Item 트리 + 이름 + 상태)
```

WL#929이 도입한 세 클래스의 역할 분담:

| 클래스 | 사는 곳 | 보관 형태 | 생애 |
|---|---|---|---|
| `Sql_check_constraint_spec` | DDL 실행 중 | 파싱된 스펙 | `CREATE`/`ALTER` 문 처리 동안 |
| `Sql_check_constraint_share` | `TABLE_SHARE` | **문자열** | 테이블 정의 캐시에 상주 |
| `Sql_table_check_constraint` | `TABLE` 인스턴스 | **Item 트리** | 커넥션이 테이블을 쓰는 동안 |

- 즉 각 커넥션은 자기 `TABLE` 인스턴스에 **평가 준비가 끝난 Item 트리**를 들고 있다. DML마다 문자열을 파싱하지 않는다.
- 언팩 시점에도 비결정적 함수·`AUTO_INCREMENT` 참조·boolean 타입 검증이 **다시** 수행된다. 하위 버전에서 만들어진 정의나 손상된 딕셔너리를 방어하기 위한 이중 검증이다.
- 생성 컬럼(Generated Column)과 `DEFAULT` 표현식이 같은 `unpack_value_generator()`를 쓴다는 점이 중요하다. 세 기능이 같은 "행 버퍼 위에서 표현식을 계산한다"는 인프라를 공유한다.

### 4. DML 실행 시점 — 정확한 평가 순서
`INSERT INTO product (price) VALUES (-100)`이 처리되는 전체 경로:

```
클라이언트
   ↓  커넥션 스레드(THD)
파서 → 옵티마이저 → 실행기 ─────────────────────────  SQL 레이어
   ↓
① 값 파싱 / 선언된 컬럼 타입으로 암묵적 형변환
② 생략된 컬럼에 DEFAULT 값 할당
③ 생성 컬럼(Generated Column) 값 계산
④ BEFORE INSERT / BEFORE UPDATE 트리거 실행   ← NEW.* 수정 가능
   ↓
⑤ ★ CHECK 제약 평가 ★
     · TABLE의 행 버퍼(record[0])를 대상으로 Item 트리 평가
     · ENFORCED 상태인 제약만 대상
     · 결과가 FALSE인 순간 중단
   ↓  (통과한 경우에만)
⑥ handler::write_row() / update_row() ────────────  스토리지 엔진
     · AUTO_INCREMENT 값 확정
     · 인덱스 / UNIQUE / FK 검사
     · redo · undo 로그 기록
   ↓
⑦ AFTER 트리거
```

**계산의 주체와 위치**: 요청을 받은 그 커넥션 스레드가, 자기 메모리에 있는 행 버퍼를 대상으로, 자기 `TABLE` 객체에 매달린 Item 트리를 직접 호출해 평가한다. 별도 프로세스도, 스토리지 엔진 왕복도, 인덱스 조회도 없다.

### 5. 평가 순서에서 파생되는 동작 4가지
④와 ⑤와 ⑥의 순서가 실제 동작을 결정한다.

**(1) BEFORE 트리거가 고친 값을 CHECK가 본다**
트리거로 값을 보정해서 CHECK를 통과시키는 패턴이 성립한다.
```sql
CREATE TABLE product (id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      stock INT NOT NULL CHECK (stock >= 0));

CREATE TRIGGER trg_clamp BEFORE INSERT ON product FOR EACH ROW
  SET NEW.stock = GREATEST(NEW.stock, 0);

INSERT INTO product (stock) VALUES (-5);  -- 트리거가 0으로 보정 → CHECK 통과
```
반대로 말하면 트리거가 값을 망가뜨릴 수도 있으므로, CHECK를 최후 방어선으로 신뢰하려면 트리거 로직까지 함께 봐야 한다.

**(2) DEFAULT 값도 CHECK 대상이다**
②가 ⑤보다 앞이므로 컬럼을 생략한 INSERT에서도 DEFAULT 값이 검증된다.
```sql
-- 이 조합은 컬럼을 생략한 INSERT가 항상 실패한다
price INT NOT NULL DEFAULT 0 CHECK (price > 0)

INSERT INTO product (id) VALUES (1);
-- ERROR 3819 (HY000): Check constraint 'product_chk_1' is violated.
```
DEFAULT와 CHECK가 서로 모순되는 스키마는 문법 오류가 아니라 **런타임에만 드러나는** 오류다.

**(3) 위반 시 스토리지 엔진은 그 행을 본 적조차 없다**
⑥이 호출되지 않으므로 `AUTO_INCREMENT` 값도 소비되지 않고, undo/redo 로그도 남지 않으며, 락도 잡히지 않는다. CHECK 위반은 "썼다가 되돌리는" 것이 아니라 애초에 쓰지 않는 것이다.

**(4) 평가는 행 단위다**
100건 다중 행 INSERT의 50번째에서 위반이면 그 지점에서 멈춘다. 이후 처리는 §8에서 다룬다.

### 6. 왜 표준을 어겼는가 — 금지 목록의 진짜 원인
WL#929이 스스로 표준 이탈을 명시하고 근거를 밝힌 부분이다.

> "According to standards, constraints are checked **after** execution of operation, before invoking the AFTER trigger. However, thanks to the fact that we **do not allow sub-query and reference to auto-increment column** in check constraint, it is acceptable to replace standard complaint checking of check constraint with **constraint check before carrying out row operation** (after executing BEFORE trigger)."

표준 SQL은 ⑥ **이후** ⑦ 이전에 검사하라고 한다. MySQL은 ⑥ **이전**에 검사한다.

여기서 인과관계가 흔한 서술과 반대라는 점이 중요하다. "CHECK 제약이라서 그런 것들을 못 쓴다"가 아니라, **"행을 엔진에 넘기기 전에 검사한다"는 아키텍처를 택했기 때문에 금지 목록이 파생됐다.**

| 금지 항목 | 아키텍처적 원인 |
|---|---|
| `AUTO_INCREMENT` 컬럼 참조 | WL#929 R.3 — "auto_increment value is determined in `write_row()` by engine". ⑤ 시점에는 값이 아직 존재하지 않는다 |
| 서브쿼리 / 타 테이블 컬럼 | 행 쓰기 경로 한복판에서 실행기가 다른 테이블을 다시 읽어야 한다 |
| 비결정적 함수(`NOW()`, `RAND()`, `UUID()`) | `ALTER TABLE` 시점의 전수 검증과 이후 DML 검증이 다른 결과를 낼 수 있다 |
| 저장 함수 / UDF / 사용자·시스템 변수 | 평가가 세션 상태나 외부 코드에 의존하면 같은 데이터가 세션마다 다르게 판정된다 |

이 표가 금지 목록을 외우지 않고 이해하는 방법이다. **"⑤ 시점의 행 버퍼만 보고, 부작용 없이, 항상 같은 답을 내야 한다"**는 조건을 만족하지 못하는 것은 모두 금지된다.

### 7. FK 참조 액션 금지의 근거
레퍼런스 매뉴얼은 사실만 말한다.

> "Foreign key referential actions (`ON UPDATE`, `ON DELETE`) are prohibited on columns used in `CHECK` constraints. Likewise, `CHECK` constraints are prohibited on columns used in foreign key referential actions."

WL#929은 이유를 밝힌다.

> "Check constraints are evaluated **before write_row()** but referential actions are applied **by the storage engine**. Check constraints cannot be evaluated for the rows modified by engine with the referential actions."

`ON DELETE SET NULL`, `ON UPDATE CASCADE`, `ON DELETE SET DEFAULT` 같은 참조 액션은 InnoDB가 SQL 레이어를 거치지 않고 자식 테이블의 행을 직접 수정한다. 그러면 ⑤를 통과하지 않은 값이 저장되어 제약이 조용히 뚫린다. 서버는 이 구멍을 런타임에 막을 수 없으므로 **DDL 단계에서 조합 자체를 거부한다.**

즉 이 금지는 기능 미완성이 아니라, "SQL 레이어 검증"이라는 설계가 스토리지 엔진의 자율적 행 수정과 만나는 지점을 정직하게 차단한 결과다.

### 8. 결과 반환 — 에러와 경고
**ENFORCED + 일반 문장** (`INSERT`, `UPDATE`, `REPLACE`, `LOAD DATA`, `LOAD XML`)

```
ERROR 3819 (HY000): Check constraint 'product_chk_1' is violated.
```
- 에러 심볼: `ER_CHECK_CONSTRAINT_VIOLATED`, SQLSTATE `HY000`.
- 메시지에는 **제약 이름만** 담긴다. 어떤 컬럼의 어떤 값이 문제였는지는 없다. 조건식이 여러 컬럼을 참조할 수 있어 서버가 원인을 특정할 수 없기 때문이다. 이것이 제약 이름을 의미 있게 붙여야 하는 실질적 이유다.
- MySQL 8.0.19 이상에서 `JSON_SCHEMA_VALID()`를 CHECK로 쓴 경우에는 `SHOW WARNINGS`로 어느 스키마 규칙이 깨졌는지 추가 정보를 얻을 수 있다(WL#13195).

**IGNORE 계열** (`INSERT IGNORE`, `UPDATE IGNORE`, `LOAD DATA ... IGNORE`, `LOAD XML ... IGNORE`)

동일한 3819가 **Warning으로 강등**되고 해당 행만 건너뛰며 문장은 계속된다.
```sql
INSERT IGNORE INTO product (price) VALUES (-100);
-- Query OK, 0 rows affected, 1 warning

SHOW WARNINGS;
-- +---------+------+-----------------------------------------------+
-- | Level   | Code | Message                                       |
-- +---------+------+-----------------------------------------------+
-- | Warning | 3819 | Check constraint 'product_chk_1' is violated. |
-- +---------+------+-----------------------------------------------+
```

**이미 적용된 변경의 처리** — 레퍼런스 매뉴얼:

> "If an error occurs, handling of changes already applied differs for **transactional and nontransactional storage engines**, and also depends on whether **strict SQL mode** is in effect."

- InnoDB: 문장 단위 롤백으로 위반 이전 행까지 함께 취소된다.
- MyISAM: 위반 이전에 쓰인 행은 그대로 남는다. 대량 적재 중 실패하면 부분 적재 상태가 된다.

### 9. SQL mode 의존성 — 자주 놓치는 함정
> "Constraint expression evaluation uses the SQL mode **in effect at evaluation time**. If any component of the expression depends on the SQL mode, different results may occur for different uses of the table unless the SQL mode is the same during all uses."

조건식은 테이블에 붙어 있지만 **평가 규칙은 세션에 붙어 있다.** 같은 테이블·같은 제약이 세션의 `sql_mode`에 따라 다르게 판정될 수 있다.

실무 함의: `ALTER TABLE`로 제약을 걸 때의 mode와 애플리케이션 커넥션의 mode가 다르면, 스테이징에서 재현되지 않는 위반이 프로덕션에서만 발생할 수 있다. 커넥션 풀 초기화 시 `sql_mode`를 명시적으로 고정하는 것이 안전하다.

또한 타입 변환 규칙도 여기에 얽힌다.
> "If the constraint expression evaluates to a data type that differs from the declared column type, implicit coercion to the declared type occurs according to the usual MySQL type-conversion rules. If type conversion fails or results in a loss of precision, an error occurs."

### 10. ALTER TABLE 경로 — 기존 데이터는 어떻게 검증되는가
기존 테이블에 제약을 추가하는 것은 DML 경로와 완전히 다른 코드 경로를 탄다.

- **FR36**: "ALTER TABLE to ADD or ENFORCE check constraints must report an error if existing rows violated the check constraint."
- **F.4.6**: "**When copying table rows, validate all enforced check constraints on the table row.**"

두 번째 문장이 핵심이다. 별도의 검증 쿼리를 돌리는 것이 아니라 **테이블 복사 루프 안에서 행마다 ⑤와 같은 평가를 수행**한다. 그래서 다음 경우 in-place ALTER이 **차단되고 테이블 복사가 강제**된다(F.4.4).

1. 새 CHECK 제약 생성
2. 기존 제약을 `NOT ENFORCED → ENFORCED`로 전환
3. 제약이 참조하는 컬럼의 타입 변경
4. 제약이 참조하는 컬럼의 속성 변경

또한 당시 `WITHOUT VALIDATION`(검증 생략) 절은 지원되지 않았다(WL#12802로 분리).

**운영상 결론 두 가지**

- 신규 CHECK 추가와 ENFORCED 전환은 설계상 COPY가 강제된다. 대용량 테이블에서 `gh-ost`·`pt-online-schema-change`는 "검토 대상"이 아니라 사실상 필수 경로다.
- `NOT ENFORCED`로 먼저 추가하는 점진적 롤아웃은 **전수 검증 비용을 없애는 것이 아니라 미루는 것**이다. 나중에 `ENFORCED`로 전환할 때 결국 복사와 검증을 한 번 겪는다. 이 전략의 실제 가치는 "그 사이에 위반 데이터를 정리할 시간을 확보하고, 실패 지점을 배포와 분리하는 것"이다.

사전 조회로 위반 행을 먼저 확인하는 것이 여전히 가장 값싼 단계다.
```sql
SELECT id, price FROM product WHERE NOT (price > 0) OR price IS NULL;
```

### 11. 복제 경로 — replica에서 다시 평가된다
WL#929 (O):

> "For DML events, the check constraints are applied while applying rows through the **BINLOG statements** and while applying SQL statements or **row events** through the **replication applier thread(s)** at the slave(s)."

Row-based replication의 행 이벤트조차 applier 스레드가 SQL 레이어를 통과하므로 replica에서 CHECK가 **다시** 평가된다.

- 정상 운영에서는 무해하다. 정의가 같으면 primary를 통과한 행은 replica에서도 통과한다.
- **primary와 replica의 CHECK 정의가 어긋나면 복제가 멈춘다.** replica에만 제약을 걸어 실험하거나, `ENFORCED` 상태가 한쪽만 다르면 applier 에러로 복제가 중단된다. 마이그레이션 도구가 replica에 먼저 적용되는 구성이라면 특히 주의해야 한다.
- 업그레이드 시(O.1): "While upgrading a replication setup, if DDL event is from the older server then check constraints in DDL events are **ignored (not created)** at the slave(s) with check constraint feature." 즉 8.0.16 미만 primary → 8.0.16+ replica 구성에서는 replica에 제약이 생성되지 않는다.

### 12. 성능 모델
평가 위치가 정해지면 비용 구조도 자연히 따라온다.

- 비용 = **행 수 × ENFORCED 제약 개수 × Item 트리 평가 비용**. 전부 CPU이며 I/O가 없다.
- 락도 잡지 않고 인덱스도 타지 않으므로 동시성에 영향이 없다.
- `price > 0` 같은 단순 비교는 무시할 수준이다. 반면 문자열 함수·`JSON_SCHEMA_VALID()`·정규식이 들어가면 대량 적재에서 누적 비용이 드러난다.
- `SELECT` 성능에는 영향이 전혀 없다. 옵티마이저는 CHECK 조건을 조회 조건으로 활용하지 않는다(PostgreSQL의 constraint exclusion 같은 최적화가 없다).
- `NOT ENFORCED`는 평가 자체를 건너뛴다. 대량 적재 시 임시로 끄는 것이 유효한 이유다. 단 §10대로 다시 켤 때 복사 비용을 지불한다.

## 핵심 정리
- CHECK 제약은 **SQL 레이어에서 평가된다.** 요청을 받은 커넥션 스레드가 자기 행 버퍼(`record[0]`)를 대상으로 Item 트리를 직접 호출한다. 스토리지 엔진은 검증에 관여하지 않으며, 그래서 "for all storage engines"가 성립한다.
- 조건식은 `mysql.check_constraints`에 문자열로 저장되고, 테이블 open 시 `unpack_value_generator()`로 Item 트리가 되어 `TABLE` 인스턴스에 캐시된다. DML마다 재파싱되지 않는다.
- 평가 순서는 **DEFAULT 할당 → 생성 컬럼 계산 → BEFORE 트리거 → CHECK 평가 → `write_row()` → AFTER 트리거**다. 표준 SQL은 `write_row()` 이후 검사를 요구하지만 MySQL은 이전에 검사하며, WL#929은 이 이탈을 명시적으로 인정한다.
- `AUTO_INCREMENT`·서브쿼리·비결정적 함수·FK 참조 액션 금지는 임의의 제한이 아니라 **"엔진에 넘기기 전 SQL 레이어에서 계산한다"는 설계에서 파생된 결과**다.
- 위반 시 `ER_CHECK_CONSTRAINT_VIOLATED`(**3819**, SQLSTATE `HY000`)가 발생하고 `write_row()`는 호출되지 않는다. `IGNORE` 계열에서는 같은 코드가 Warning으로 강등되고 해당 행만 건너뛴다.
- 신규 CHECK 추가와 `ENFORCED` 전환은 **테이블 복사가 강제**되며, 복사 루프에서 행마다 검증한다. Replica에서도 applier 스레드가 제약을 다시 평가하므로 primary와 정의가 어긋나면 복제가 중단된다.

## 기술적 한계와 보완 전략
- **에러 메시지에 위반 값·컬럼 정보가 없다**(제약 이름만) → 제약 이름을 의미 있게 명시하고, 애플리케이션 예외 핸들러에서 이름 → 사용자 메시지 매핑 테이블을 두어 변환한다. 애초에 사용자에게 3819가 노출되면 앞단 검증이 뚫린 것이므로 알림 대상으로 삼는 것도 방법이다.
- **DEFAULT 값과 CHECK가 모순되는 스키마를 DDL이 잡아주지 않는다** → 마이그레이션 리뷰 시 `DEFAULT`와 `CHECK`를 같이 보고, 컬럼을 생략한 INSERT를 통합 테스트에 포함한다.
- **FK 참조 액션과 함께 쓸 수 없다** → 참조 액션이 필요한 컬럼의 검증은 애플리케이션 트랜잭션이나 트리거로 옮긴다. 두 기능 중 무엇이 더 중요한지 결정해야 하는 문제이며 우회로가 없다.
- **`ENFORCED` 전환 비용이 뒤로 미뤄진다** → `NOT ENFORCED` 롤아웃 시 "언제 켤 것인가"를 티켓으로 남긴다. 켜지 않은 `NOT ENFORCED` 제약은 문서화된 의도일 뿐 방어가 아니다.
- **`sql_mode`에 따라 판정이 달라질 수 있다** → 커넥션 풀 초기화와 마이그레이션 실행 환경의 `sql_mode`를 동일하게 고정한다.
- **Primary/replica 정의 불일치가 복제를 멈춘다** → 스키마 변경은 항상 같은 마이그레이션 도구·같은 순서로 전파하고, `INFORMATION_SCHEMA.TABLE_CONSTRAINTS`의 `ENFORCED` 컬럼을 양쪽에서 비교하는 점검을 운영 체크리스트에 넣는다.

## 키워드
- **SQL 레이어 평가**: CHECK 제약이 스토리지 엔진이 아니라 서버의 SQL 레이어에서 계산된다는 사실. 모든 스토리지 엔진에서 동작하는 이유이자, 엔진이 자율적으로 수정하는 행(FK 참조 액션)에는 적용되지 않는 이유.
- **`mysql.check_constraints`**: 제약 정의를 저장하는 데이터 딕셔너리 테이블. `check_clause`(평가용)와 `check_clause_utf8`(I_S 노출용)을 분리해 보관한다.
- **`Sql_table_check_constraint`**: `TABLE` 인스턴스가 들고 있는, 언팩된 Item 트리 형태의 제약 표현. DML마다 재파싱하지 않고 이것을 재사용한다.
- **`unpack_value_generator()`**: 딕셔너리에 저장된 표현식 문자열을 실행 가능한 Item 트리로 복원하는 함수. 생성 컬럼·`DEFAULT` 식·CHECK 제약이 공유한다.
- **`handler::write_row()`**: SQL 레이어가 스토리지 엔진에 행을 넘기는 handler API 진입점. CHECK 평가는 이 호출 **직전**에 일어나고, 위반 시 이 호출 자체가 발생하지 않는다.
- **평가 순서(DEFAULT → 생성 컬럼 → BEFORE 트리거 → CHECK)**: BEFORE 트리거가 보정한 값과 DEFAULT 값이 모두 검증 대상이 되는 근거.
- **표준 SQL 이탈**: 표준은 행 연산 후 검사를 요구하지만 MySQL은 연산 전에 검사한다. 서브쿼리·`AUTO_INCREMENT` 참조를 금지한 것이 이 이탈을 정당화하는 전제다.
- **`ER_CHECK_CONSTRAINT_VIOLATED` (3819)**: 위반 시 발생하는 에러. SQLSTATE `HY000`. `IGNORE` 계열에서는 같은 코드가 Warning으로 강등된다.
- **F.4.4 / F.4.6 (COPY 강제)**: 신규 CHECK 생성과 `ENFORCED` 전환이 in-place ALTER을 차단하고 테이블 복사 중 행별 검증을 수행하도록 규정한 설계 항목.
- **Replication applier 재평가**: replica의 applier 스레드가 행 이벤트에도 CHECK를 다시 적용한다. Primary/replica 정의 불일치가 복제 중단으로 이어지는 원인.
- **`sql_mode` 평가 시점 의존성**: 조건식은 테이블에 속하지만 평가 규칙은 세션에 속한다. 같은 데이터가 세션마다 다르게 판정될 수 있다.

## 참고 자료
- [MySQL Worklog - WL#929: CHECK constraints](https://dev.mysql.com/worklog/task/?id=929) — 이 글의 내부 동작 서술 대부분의 출처
- [MySQL 8.0 Reference Manual - 15.1.20.6 CHECK Constraints](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)
- [MySQL Blog - MySQL 8.0.16: Introducing CHECK constraint](https://dev.mysql.com/blog-archive/mysql-8-0-16-introducing-check-constraint/)
- [MySQL Worklog - WL#13195: Table with JSON schema validation constraint should return error for concrete row](https://dev.mysql.com/worklog/task/?id=13195)
- [MySQL 8.0 Reference Manual - Strict SQL Mode](https://dev.mysql.com/doc/refman/8.0/en/sql-mode.html#sql-mode-strict)
- [MySQL 8.0 Reference Manual - INFORMATION_SCHEMA.TABLE_CONSTRAINTS Table](https://dev.mysql.com/doc/refman/8.0/en/information-schema-table-constraints-table.html)
