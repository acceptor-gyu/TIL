# RDBMS(MySQL) Lock 종류

## 개요
MySQL(InnoDB)에서 동시성 제어를 위해 사용하는 다양한 Lock의 종류와 동작 방식을 정리한다.

## 상세 내용

### 1. Lock의 분류 기준

#### 1.1 Shared Lock (S-Lock) vs Exclusive Lock (X-Lock)
InnoDB의 Row-Level Lock의 기본이 되는 두 가지 락 타입이다.

| Lock 타입 | 목적 | 호환성 |
|-----------|------|--------|
| **Shared (S)** | 트랜잭션이 row를 **읽기** 위해 획득 | 여러 트랜잭션이 동시에 S Lock 보유 가능 |
| **Exclusive (X)** | 트랜잭션이 row를 **수정/삭제**하기 위해 획득 | 다른 Lock(S, X)과 동시 보유 불가 |

**호환성 규칙:**
- T1이 row `r`에 **S Lock** 보유 시: T2는 S Lock 획득 가능 ✅, X Lock 획득 불가 ❌
- T1이 row `r`에 **X Lock** 보유 시: T2는 어떠한 Lock(S, X)도 획득 불가 ❌ → 대기 필요

#### 1.2 호환성 매트릭스
트랜잭션 간 Lock 요청 시 다음 규칙이 적용된다:

|        | X  | IX | S  | IS |
|--------|----|----|----|----|
| **X**  | ❌ | ❌ | ❌ | ❌ |
| **IX** | ❌ | ✅ | ❌ | ✅ |
| **S**  | ❌ | ❌ | ✅ | ✅ |
| **IS** | ❌ | ✅ | ✅ | ✅ |

### 2. InnoDB의 Row-Level Lock

#### 2.1 Record Lock (레코드 락)
**개별 인덱스 레코드**에 대한 Lock이다.

```sql
SELECT c1 FROM t WHERE c1 = 10 FOR UPDATE;
-- t.c1 = 10인 인덱스 레코드에 대해 X Lock 획득
-- 다른 트랜잭션의 INSERT, UPDATE, DELETE 차단
```

**특징:**
- 물리적 row가 아닌 **인덱스 레코드**에 Lock을 건다
- 명시적 인덱스가 없어도 InnoDB는 **hidden clustered index**를 생성하여 사용
- 항상 인덱스를 통해 Lock이 설정된다

**모니터링 출력 예시:**
```
RECORD LOCKS space id 58 page no 3 n bits 72 index `PRIMARY` of table `test`.`t`
trx id 10078 lock_mode X locks rec but not gap
```

#### 2.2 Gap Lock (갭 락)
**인덱스 레코드 사이의 간격(gap)**에 대한 Lock이다.

```sql
SELECT c1 FROM t WHERE c1 BETWEEN 10 AND 20 FOR UPDATE;
-- 10과 20 사이의 모든 gap을 Lock
-- 예를 들어 15를 INSERT 하려는 시도를 차단
```

**핵심 특징:**
- 순수하게 **삽입 방지(inhibitive)** 목적만 가진다
- Gap Lock끼리는 **충돌하지 않는다** (여러 트랜잭션이 같은 gap에 동시에 Gap Lock 보유 가능)
- **Unique Index로 Unique Row 검색** 시에는 사용되지 않는다 (Record Lock만 사용)

```sql
-- id가 unique index인 경우
SELECT * FROM child WHERE id = 100;
-- ✅ Record Lock만 사용

-- id가 non-unique이거나 인덱스가 없는 경우
-- ❌ Gap Lock 적용됨
```

**Gap Lock 비활성화:**
- 격리 수준을 `READ COMMITTED`로 설정하면 검색/스캔에 대한 Gap Lock이 비활성화된다
- Foreign Key 제약 조건 및 중복 키 검사에만 사용된다

#### 2.3 Next-Key Lock (넥스트 키 락)
**Record Lock + 그 레코드 이전의 Gap Lock**을 결합한 형태이다.

`REPEATABLE READ` 격리 수준에서 **Phantom Read를 방지**하기 위한 InnoDB의 기본 Lock 전략이다.

**예시:** 인덱스에 값 `10, 11, 13, 20`이 있을 때 Next-Key Lock 구간:
```
(−∞,  10]  ← 10 레코드 + (−∞, 10) gap
(10,  11]  ← 11 레코드 + (10, 11) gap
(11,  13]  ← 13 레코드 + (11, 13) gap
(13,  20]  ← 20 레코드 + (13, 20) gap
(20,  +∞)  ← gap only (supremum pseudo-record)
```
- 소괄호 `(` = exclusive (endpoint 미포함)
- 대괄호 `]` = inclusive (endpoint 포함)

**모니터링 출력 예시:**
```
RECORD LOCKS space id 58 page no 3 n bits 72 index `PRIMARY` of table `test`.`t`
trx id 10080 lock_mode X
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;
```

#### 2.4 Insert Intention Lock (삽입 의도 락)
`INSERT` 작업이 row 삽입 **전**에 설정하는 **특수한 Gap Lock**이다.

**특징:**
- 특정 gap 내에서 삽입할 위치를 알리는 신호
- **같은 gap 내 서로 다른 위치**에 삽입하는 트랜잭션들은 **서로 블로킹하지 않는다**

**예시:**
- 인덱스 값 `4`와 `7` 사이에 gap이 존재
- T1이 `5`를 삽입, T2가 `6`을 삽입 → 둘 다 (4,7) gap에 Insert Intention Lock을 획득하지만 **동시 진행 가능** ✅
- 같은 위치에 삽입할 때만 블로킹된다

**모니터링 출력 예시:**
```
RECORD LOCKS space id 31 page no 3 n bits 72 index `PRIMARY` of table `test`.`child`
trx id 8731 lock_mode X locks gap before rec insert intention waiting
```

### 3. Table-Level Lock

#### 3.1 Intention Lock (의도 락: IS, IX)
**Table-Level Lock**으로, 향후 row에 대해 어떤 타입의 Lock을 걸 것인지를 알리는 신호이다.

| Lock | 기호 | 의미 |
|------|------|------|
| Intention Shared | `IS` | 트랜잭션이 개별 row에 S Lock을 걸 예정 |
| Intention Exclusive | `IX` | 트랜잭션이 개별 row에 X Lock을 걸 예정 |

**왜 필요한가?**

Intention Lock은 **다중 세분성(multi-granularity) Locking**을 가능하게 한다.

```
예: 다른 트랜잭션이 전체 테이블에 Lock을 걸려고 할 때
❌ Intention Lock이 없다면: 모든 row를 일일이 확인해야 함
✅ Intention Lock이 있다면: 테이블의 Intention Lock만 확인하면 충분
```

**프로토콜:**
- **Row S Lock**을 획득하기 전 → 테이블에 `IS` (또는 더 강한 Lock) 먼저 획득 필요
- **Row X Lock**을 획득하기 전 → 테이블에 `IX` 먼저 획득 필요

**호환성 매트릭스에서 IS, IX의 의미:**
- **IX와 IX는 호환 가능** ✅: 여러 트랜잭션이 동시에 서로 다른 row 수정 가능 (높은 동시성)
- **IX와 S는 충돌** ❌: 테이블 전체 읽기 Lock vs row 수정 의도
- **IS와 대부분 호환** ✅: 읽기 작업끼리는 서로 방해하지 않음

**실제 동작 예시:**
```sql
-- 트랜잭션 T1
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
-- ① 테이블에 IX Lock 획득
-- ② row id=1에 X Lock 획득

-- 트랜잭션 T2 (동시 실행)
SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
-- ① 테이블에 IX Lock 획득 ✅ (T1의 IX와 호환!)
-- ② row id=2에 X Lock 획득 ✅ (서로 다른 row)

-- 결과: 두 트랜잭션 모두 동시 실행 가능!
```

**핵심 인사이트:**
- `IX` Lock끼리는 **호환 가능**하여 동시에 여러 트랜잭션이 row-level 쓰기를 수행할 수 있다
- Intention Lock은 전체 테이블 작업(예: `LOCK TABLES ... WRITE`)만 차단한다
- row-level에서 높은 동시성을 제공하면서도 table-level 작업과의 충돌을 효율적으로 감지

**SQL 명령어별 Intention Lock:**
- `SELECT ... FOR SHARE` → `IS` Lock 설정
- `SELECT ... FOR UPDATE` → `IX` Lock 설정

**모니터링 출력 예시:**
```
TABLE LOCK table `test`.`t` trx id 10080 lock mode IX
```

#### 3.2 AUTO-INC Lock
`AUTO_INCREMENT` 컬럼이 있는 테이블에 대한 **특수한 Table-Level Lock**이다.

**특징:**
- row가 **연속적인 Primary Key 값**을 받도록 보장
- 한 트랜잭션이 삽입 중일 때 다른 트랜잭션은 **대기**해야 한다
- `innodb_autoinc_lock_mode` 설정으로 **순서 예측 가능성 vs 삽입 동시성** 간 트레이드오프 조정 가능

### 4. Lock과 트랜잭션 격리 수준의 관계

#### 4.1 READ COMMITTED에서의 Lock 동작
- **Gap Lock이 비활성화**된다 (검색 및 인덱스 스캔에 대해)
- Gap Lock은 Foreign Key 제약 조건 및 중복 키 검사에만 사용된다
- **Record Lock만 사용**하여 동시성 향상
- WHERE 조건 평가 후 매칭되지 않는 row의 Lock은 즉시 해제

#### 4.2 REPEATABLE READ에서의 Lock 동작
- **Next-Key Lock**을 기본 전략으로 사용
- Phantom Read 방지를 위해 gap에 대해서도 Lock을 건다
- 일관된 읽기 보장

#### 4.3 Phantom Read 방지와 Gap Lock의 역할
```sql
-- 트랜잭션 T1
START TRANSACTION;
SELECT * FROM orders WHERE amount > 1000 FOR UPDATE;
-- REPEATABLE READ: Next-Key Lock으로 gap도 Lock
-- amount > 1000 범위의 gap에 다른 트랜잭션의 INSERT 차단

-- 트랜잭션 T2 (다른 세션)
INSERT INTO orders (amount) VALUES (1500);
-- T1이 Next-Key Lock을 보유 중이므로 대기 ❌
```

### 5. Lock 관련 실무 이슈

#### 5.1 Deadlock 발생 원인과 탐지 방법
**발생 원인:**
- 두 개 이상의 트랜잭션이 서로가 보유한 Lock을 기다리는 순환 대기 상태
- 서로 다른 순서로 리소스 접근 시 발생

**탐지 및 대응:**
```sql
SHOW ENGINE INNODB STATUS;
-- LATEST DETECTED DEADLOCK 섹션에서 확인 가능
```

InnoDB는 자동으로 Deadlock을 탐지하고 **가장 적은 row를 변경한 트랜잭션**을 롤백한다.

#### 5.2 Lock Wait Timeout과 모니터링
```sql
-- Lock 대기 시간 설정 (초 단위)
SET innodb_lock_wait_timeout = 50;

-- Lock 정보 조회
SELECT * FROM performance_schema.data_locks;
SELECT * FROM performance_schema.data_lock_waits;
```

#### 5.3 `SELECT ... FOR UPDATE` vs `SELECT ... FOR SHARE`
| 구문 | Lock 타입 | 다른 트랜잭션 |
|------|-----------|---------------|
| `SELECT ... FOR SHARE` | S Lock (Shared) | 읽기 가능 ✅, 쓰기 대기 ❌ |
| `SELECT ... FOR UPDATE` | X Lock (Exclusive) | 읽기/쓰기 모두 대기 ❌ |

```sql
-- 읽기 일관성만 보장하고 싶을 때
SELECT * FROM accounts WHERE id = 1 FOR SHARE;

-- 곧 수정할 예정일 때
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
```

#### 5.4 `SHOW ENGINE INNODB STATUS`를 통한 Lock 확인
```sql
SHOW ENGINE INNODB STATUS\G

-- 출력 예시 섹션:
-- TRANSACTIONS: 현재 활성 트랜잭션
-- LATEST DETECTED DEADLOCK: 최근 Deadlock 정보
-- Lock 타입, 대기 중인 트랜잭션, Lock이 걸린 테이블/인덱스 확인 가능
```

### 6. 비관적 락 vs 낙관적 락과의 비교

#### 6.1 InnoDB 내부 Lock과 애플리케이션 레벨 락의 차이

| 구분 | InnoDB 내부 Lock | 애플리케이션 레벨 락 |
|------|------------------|----------------------|
| **구현 위치** | DB 엔진 내부 (InnoDB) | 애플리케이션 로직 (JPA 등) |
| **비관적 락** | `SELECT ... FOR UPDATE` | 데이터 읽기 전 Lock 획득 |
| **낙관적 락** | 미지원 (Lock 없음) | Version 컬럼으로 충돌 감지 |
| **성능** | Lock 대기 시간 발생 가능 | 충돌 시에만 재시도 |
| **사용 시나리오** | 충돌 빈번, 데이터 정합성 최우선 | 충돌 드묾, 동시성 최우선 |

**비관적 락 (InnoDB Lock 활용):**
```sql
-- 데이터 읽기 시점에 Lock 획득
SELECT * FROM product WHERE id = 1 FOR UPDATE;
UPDATE product SET stock = stock - 1 WHERE id = 1;
COMMIT;
```

**낙관적 락 (애플리케이션 레벨):**
```sql
-- Version 컬럼 활용
SELECT id, stock, version FROM product WHERE id = 1;
-- version = 5

UPDATE product
SET stock = stock - 1, version = version + 1
WHERE id = 1 AND version = 5;
-- 다른 트랜잭션이 먼저 수정했다면 UPDATE 실패 (affected rows = 0)
```

**Lock 작동 흐름 비교:**
```
[INSERT 작업 시 Lock 획득 순서]
1. 테이블에 IX Intention Lock 획득
2. Gap에 Insert Intention Lock 획득
3. 새 row에 X Record Lock 획득
4. (AUTO_INCREMENT인 경우) 잠시 AUTO-INC Table Lock 보유

[SELECT ... FOR UPDATE 시 Lock 획득 순서]
1. 테이블에 IX Intention Lock 획득
2. 매칭되는 인덱스 레코드에 Next-Key Lock 획득 (Record + Gap)
   → REPEATABLE READ에서 Phantom Read 방지
```

## 핵심 정리

### Row-Level Lock 계층 구조
1. **Shared Lock (S)** / **Exclusive Lock (X)**: Row-level의 기본 Lock
2. **Record Lock**: 인덱스 레코드 자체에 대한 Lock
3. **Gap Lock**: 인덱스 레코드 간 간격에 대한 Lock (삽입 방지)
4. **Next-Key Lock**: Record Lock + Gap Lock 조합 (Phantom Read 방지)
5. **Insert Intention Lock**: 같은 gap 내 다른 위치 삽입은 동시 허용

### Table-Level Lock의 역할
- **Intention Lock (IS, IX)**: Row Lock을 걸기 전 테이블 레벨에서 의도를 알리는 신호
- **AUTO-INC Lock**: AUTO_INCREMENT 값의 연속성 보장

### 격리 수준에 따른 차이
| 격리 수준 | Lock 전략 | 특징 |
|-----------|-----------|------|
| **READ COMMITTED** | Record Lock만 사용 | Gap Lock 비활성화, 높은 동시성 |
| **REPEATABLE READ** | Next-Key Lock 사용 | Phantom Read 방지, InnoDB 기본값 |

### 실무 포인트
- **Unique Index로 Unique Row 검색** 시 Record Lock만 사용 (Gap Lock 불필요)
- **Non-unique Index 또는 범위 검색** 시 Next-Key Lock 사용
- **Deadlock**은 InnoDB가 자동 탐지 및 롤백 (최소 변경 트랜잭션 선택)
- **Gap Lock끼리는 충돌하지 않음** (순수하게 삽입 방지 목적)

## 키워드

### `Shared Lock` (공유 락, S-Lock)
트랜잭션이 row를 읽기 위해 획득하는 Lock. 여러 트랜잭션이 동시에 같은 row에 대해 S Lock을 보유할 수 있다. 읽기 일관성을 보장하면서도 다른 읽기 작업을 차단하지 않는다.

### `Exclusive Lock` (배타 락, X-Lock)
트랜잭션이 row를 수정하거나 삭제하기 위해 획득하는 Lock. X Lock이 걸린 row에는 다른 트랜잭션이 어떠한 Lock(S, X)도 획득할 수 없어 완전한 배타적 접근을 보장한다.

### `Record Lock`
개별 인덱스 레코드에 대한 Lock. 물리적 row가 아닌 인덱스 레코드를 Lock하며, 명시적 인덱스가 없는 경우 InnoDB의 hidden clustered index를 사용한다.

### `Gap Lock`
인덱스 레코드 사이의 간격(gap)에 대한 Lock으로, 순수하게 해당 구간으로의 새로운 row 삽입을 방지하는 목적을 가진다. Gap Lock끼리는 충돌하지 않으며, Unique Index로 Unique Row를 검색할 때는 사용되지 않는다.

### `Next-Key Lock`
Record Lock과 그 레코드 이전의 Gap Lock을 결합한 형태. InnoDB의 REPEATABLE READ 격리 수준에서 Phantom Read를 방지하기 위한 기본 Lock 전략이다.

### `Insert Intention Lock`
INSERT 작업이 row 삽입 전에 설정하는 특수한 Gap Lock. 같은 gap 내에서 서로 다른 위치에 삽입하는 트랜잭션들은 서로 블로킹하지 않아 동시 삽입 성능을 향상시킨다.

### `Intention Lock`
Table-Level Lock으로, 트랜잭션이 향후 특정 row에 어떤 타입의 Lock(S 또는 X)을 걸 것인지를 테이블 레벨에서 알리는 신호다. IS(Intention Shared)와 IX(Intention Exclusive) 두 가지가 있으며, 다중 세분성(multi-granularity) Locking을 가능하게 한다.

### `Deadlock`
두 개 이상의 트랜잭션이 서로가 보유한 Lock을 기다리며 순환 대기 상태에 빠지는 현상. InnoDB는 자동으로 Deadlock을 탐지하고 가장 적은 row를 변경한 트랜잭션을 롤백하여 해결한다.

### `InnoDB`
MySQL의 기본 스토리지 엔진으로, ACID 특성을 지원하며 트랜잭션과 외래 키 제약 조건을 제공한다. Row-level Lock을 통해 높은 동시성을 제공하며, MVCC(Multi-Version Concurrency Control)를 구현한다.

### `Transaction Isolation Level`
트랜잭션 간 격리 정도를 정의하는 수준. MySQL InnoDB는 READ UNCOMMITTED, READ COMMITTED, REPEATABLE READ(기본값), SERIALIZABLE 네 가지 격리 수준을 제공하며, 각 수준에 따라 Lock 전략이 달라진다.

## 참고 자료
- [MySQL 8.4 Reference Manual - InnoDB Locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html)
- [MySQL 8.4 Reference Manual - Locks Set by Different SQL Statements in InnoDB](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html)
- [MySQL 8.4 Reference Manual - InnoDB Lock and Lock-Wait Information](https://dev.mysql.com/doc/refman/8.4/en/innodb-information-schema-understanding-innodb-locking.html)
