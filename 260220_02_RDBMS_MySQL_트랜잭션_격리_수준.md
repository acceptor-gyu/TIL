# RDBMS(MySQL) 트랜잭션 격리 수준

## 개요
MySQL(InnoDB)에서 제공하는 4가지 트랜잭션 격리 수준(Transaction Isolation Level)의 동작 방식과 각 수준에서 발생할 수 있는 문제, 그리고 실무에서의 선택 기준을 정리한다.

## 상세 내용

### 1. 트랜잭션 격리 수준이란?

**트랜잭션 격리(Transaction Isolation)**는 ACID의 **"I"**에 해당하며, 여러 트랜잭션이 동시에 실행될 때 **성능과 신뢰성, 일관성, 재현성** 간의 균형을 조정하는 설정이다.

**핵심 개념:**
- 동시에 실행되는 트랜잭션 간 **데이터 가시성(visibility)**을 정의하는 표준
- SQL 표준(SQL-92)에서 정의한 4단계 격리 수준
- **격리 수준이 높을수록**: 데이터 정합성 ↑, 동시성(성능) ↓
- **격리 수준이 낮을수록**: 동시성(성능) ↑, 데이터 정합성 ↓

**InnoDB의 기본값:** `REPEATABLE READ`

### 2. 4가지 격리 수준

InnoDB는 SQL:1992 표준의 4가지 격리 수준을 모두 지원한다.

#### 격리 수준 비교 테이블

| 격리 수준 | Dirty Read | Non-Repeatable Read | Phantom Read | Gap Locking |
|-----------|-----------|---------------------|--------------|-------------|
| **READ UNCOMMITTED** | ✅ 발생 가능 | ✅ 발생 가능 | ✅ 발생 가능 | ❌ 미사용 |
| **READ COMMITTED** | ❌ 방지 | ✅ 발생 가능 | ✅ 발생 가능 | ❌ 미사용 (FK/중복키만) |
| **REPEATABLE READ** | ❌ 방지 | ❌ 방지 | ⚠️ 완화됨 | ✅ 사용 (Next-Key Lock) |
| **SERIALIZABLE** | ❌ 방지 | ❌ 방지 | ❌ 방지 | ✅ 사용 (가장 엄격) |

### 3. 격리 수준별 발생 가능한 문제

#### 3.1 Dirty Read (더티 리드)
**다른 트랜잭션이 아직 커밋하지 않은 데이터를 읽는 현상**

```sql
-- 트랜잭션 T1
START TRANSACTION;
UPDATE accounts SET balance = 1000 WHERE id = 1;
-- 아직 커밋하지 않음

-- 트랜잭션 T2 (READ UNCOMMITTED)
SELECT balance FROM accounts WHERE id = 1;
-- 결과: 1000 (커밋되지 않은 값!)

-- 트랜잭션 T1
ROLLBACK;
-- T2가 읽은 1000은 실제로 존재하지 않는 값
```

**발생 격리 수준:** `READ UNCOMMITTED`만

#### 3.2 Non-Repeatable Read (반복 불가능한 읽기)
**같은 트랜잭션 내에서 같은 쿼리를 두 번 실행했을 때 결과가 다른 현상**

```sql
-- 트랜잭션 T1
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 결과: 500

-- 트랜잭션 T2
UPDATE accounts SET balance = 1000 WHERE id = 1;
COMMIT;

-- 트랜잭션 T1 (같은 트랜잭션 내)
SELECT balance FROM accounts WHERE id = 1;  -- 결과: 1000 (다름!)
```

**발생 격리 수준:** `READ UNCOMMITTED`, `READ COMMITTED`

#### 3.3 Phantom Read (팬텀 리드)
**같은 쿼리를 반복 실행했을 때 이전에 없던 row가 나타나거나 사라지는 현상**

```sql
-- 트랜잭션 T1
START TRANSACTION;
SELECT * FROM accounts WHERE balance > 500;  -- 결과: 2개 row

-- 트랜잭션 T2
INSERT INTO accounts (id, balance) VALUES (3, 600);
COMMIT;

-- 트랜잭션 T1 (같은 트랜잭션 내)
SELECT * FROM accounts WHERE balance > 500;  -- 결과: 3개 row (팬텀!)
```

**발생 격리 수준:** `READ UNCOMMITTED`, `READ COMMITTED`
**완화 격리 수준:** `REPEATABLE READ` (Next-Key Lock으로 대부분 방지)

### 4. MVCC(Multi-Version Concurrency Control)와 격리 수준

#### 4.1 MVCC란?
InnoDB는 **Undo Log**를 활용하여 **데이터의 여러 버전**을 유지하며, 트랜잭션이 특정 시점의 스냅샷을 읽을 수 있게 한다.

**장점:**
- 읽기 작업이 쓰기 작업을 블로킹하지 않음
- 쓰기 작업이 읽기 작업을 블로킹하지 않음
- 높은 동시성 제공

#### 4.2 Consistent Read와 격리 수준별 차이

**Consistent Read**: 트랜잭션이 Undo Log의 스냅샷을 읽어 일관된 데이터를 제공하는 방식

| 격리 수준 | 스냅샷 시점 | 동작 방식 |
|-----------|------------|-----------|
| **READ COMMITTED** | **매 SELECT마다** 새로운 스냅샷 생성 | 각 쿼리가 가장 최근 커밋된 데이터를 봄 |
| **REPEATABLE READ** | **트랜잭션의 첫 번째 읽기** 시점 고정 | 트랜잭션 내 모든 쿼리가 동일한 스냅샷을 봄 |

#### 4.3 READ COMMITTED vs REPEATABLE READ에서의 스냅샷 시점 차이

```sql
-- 초기 데이터: accounts(id=1, balance=500)

-- READ COMMITTED
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 500 (스냅샷 1)

-- 다른 트랜잭션이 balance를 1000으로 변경 후 COMMIT

SELECT balance FROM accounts WHERE id = 1;  -- 1000 (스냅샷 2, 새로운 스냅샷!)
COMMIT;

-- REPEATABLE READ
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 500 (스냅샷 고정)

-- 다른 트랜잭션이 balance를 1000으로 변경 후 COMMIT

SELECT balance FROM accounts WHERE id = 1;  -- 500 (여전히 첫 번째 스냅샷)
COMMIT;
```

### 5. InnoDB에서의 격리 수준별 Lock 동작

#### 5.1 READ UNCOMMITTED
- `SELECT`는 **nonlocking** 방식으로 수행
- 커밋되지 않은 이전 버전의 row를 읽을 수 있음 (Dirty Read)
- 쓰기 작업은 `READ COMMITTED`와 동일한 방식

#### 5.2 READ COMMITTED

**MVCC 동작:**
- 각 Consistent Read가 **자체 스냅샷**을 생성
- 같은 트랜잭션 내에서도 `SELECT`마다 다른 데이터 조회 가능 (Non-Repeatable Read)

**Lock 동작:**
- **Record Lock만 사용**, Gap Lock 비활성화
- Gap Lock은 **Foreign Key 제약 조건**과 **중복 키 검사**에만 사용
- Gap Lock이 비활성화되어 다른 세션이 자유롭게 새 row 삽입 가능 → **Phantom Read 발생 가능**

**Semi-Consistent Read (UPDATE 최적화):**

`UPDATE` 문에서 InnoDB는 "Semi-Consistent" 읽기를 수행한다:

1. 각 row의 **최신 커밋된 버전**을 MySQL에 반환
2. MySQL이 `WHERE` 조건 평가
3. row가 매칭되면 → InnoDB가 다시 읽고 **Lock 획득 또는 대기**
4. row가 매칭되지 않으면 → **Lock 즉시 해제**

**예시:**
```sql
-- 초기 데이터: t(a, b) = (1,2), (2,3), (3,2), (4,3), (5,2)

-- Session A (READ COMMITTED)
START TRANSACTION;
UPDATE t SET b = 5 WHERE b = 3;
-- x-lock(1,2); unlock(1,2)  ← b=3 아니므로 해제
-- x-lock(2,3); update; retain
-- x-lock(3,2); unlock(3,2)
-- x-lock(4,3); update; retain
-- x-lock(5,2); unlock(5,2)

-- Session B
UPDATE t SET b = 4 WHERE b = 2;
-- x-lock(1,2); update; retain
-- x-lock(2,3); unlock  ← b=2 아니므로 해제
-- x-lock(3,2); update; retain
-- x-lock(4,3); unlock
-- x-lock(5,2); update; retain
-- ✅ 블로킹 없이 실행! (Semi-Consistent Read 덕분)
```

**주의사항:**
- **Row-based Binary Logging만 지원**: `binlog_format=MIXED` 사용 시 자동으로 row-based로 전환
- Indexed Column의 경우 인덱스 키만으로 Lock 판단

#### 5.3 REPEATABLE READ (InnoDB 기본값)

**MVCC 동작:**
- 트랜잭션 내에서 **첫 번째 읽기**에서 생성된 스냅샷 사용
- 같은 트랜잭션 내 모든 nonlocking `SELECT`는 **동일한 일관된 상태** 조회

**Lock 동작:**

| 검색 타입 | 적용되는 Lock |
|-----------|--------------|
| **Unique Index + Unique 검색 조건** | 찾은 인덱스 레코드만 Lock (Gap Lock 없음) |
| **범위 검색 / Non-unique 조건** | **Next-Key Lock** (Record + Gap) 사용 |

**예시:**
```sql
-- Session A (REPEATABLE READ)
START TRANSACTION;
UPDATE t SET b = 5 WHERE b = 3;
-- 모든 row에 x-lock 획득하고 유지
-- x-lock(1,2); retain
-- x-lock(2,3); update; retain
-- x-lock(3,2); retain
-- x-lock(4,3); update; retain
-- x-lock(5,2); retain

-- Session B
UPDATE t SET b = 4 WHERE b = 2;
-- x-lock(1,2); ❌ BLOCK (Session A가 Lock 보유 중)
```

**중요한 경고:**

> 같은 REPEATABLE READ 트랜잭션 내에서 **Locking Statement**(`UPDATE`, `DELETE`, `SELECT ... FOR UPDATE`)와 **nonlocking `SELECT`**를 혼용하는 것은 권장하지 않는다:
> - Nonlocking `SELECT`: 과거의 MVCC 스냅샷 읽기
> - Locking Statement: **최신 데이터베이스 상태** 읽기
> - 두 상태가 **서로 불일치**할 수 있음
>
> 둘 다 필요하면 `SERIALIZABLE` 사용 권장

#### 5.4 SERIALIZABLE

**MVCC 동작:**
- `REPEATABLE READ`와 유사하지만 자동 읽기 Lock 추가

**Lock 동작:**

| `autocommit` 설정 | 동작 |
|-------------------|------|
| **OFF** | 모든 plain `SELECT`가 자동으로 **`SELECT ... FOR SHARE`**로 변환 |
| **ON** | `SELECT`가 독립적인 트랜잭션; Consistent Read로 수행 (블로킹 없음) |

```sql
-- autocommit=OFF인 경우
SELECT * FROM t WHERE id = 1;
-- 자동으로 변환됨 ↓
SELECT * FROM t WHERE id = 1 FOR SHARE;
```

**사용 사례:**
- XA 트랜잭션
- 동시성 및 Deadlock 문제 디버깅
- ACID 준수가 매우 중요한 특수 상황

### 6. 실무에서의 격리 수준 선택 기준

#### 6.1 MySQL 기본값(REPEATABLE READ)을 유지해야 하는 경우

✅ **사용하는 경우:**
- ACID 준수가 중요한 금융, 결제 시스템
- Phantom Read를 방지해야 하는 경우
- 트랜잭션 내에서 일관된 읽기가 필수적인 경우
- Replication에서 Statement-based Binary Logging 사용 시

**장점:**
- Non-Repeatable Read와 대부분의 Phantom Read 방지
- InnoDB 기본값으로 별도 설정 불필요
- Next-Key Lock으로 범위 잠금 지원

**단점:**
- Gap Lock으로 인한 동시성 저하
- Deadlock 발생 가능성 증가

#### 6.2 READ COMMITTED로 변경하는 경우

✅ **사용하는 경우:**
- Oracle 호환성이 필요한 경우 (Oracle 기본값)
- 높은 동시성이 필요한 대용량 트래픽 처리
- Deadlock 발생을 최소화하고 싶은 경우
- Semi-Consistent Read로 UPDATE 성능 향상이 필요한 경우

**장점:**
- Gap Lock 비활성화로 높은 동시성
- Semi-Consistent Read로 Deadlock 감소
- 매칭되지 않는 row의 Lock 즉시 해제

**단점:**
- Non-Repeatable Read, Phantom Read 발생 가능
- **Row-based Binary Logging 필수** (Statement-based 불가)
- 트랜잭션 내 일관성 보장 약화

#### 6.3 격리 수준 변경 시 주의사항

**설정 방법:**
```sql
-- 세션별 설정
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;

-- 글로벌 설정 (새 연결부터 적용)
SET GLOBAL TRANSACTION ISOLATION LEVEL REPEATABLE READ;

-- 설정 파일 (my.cnf / my.ini)
[mysqld]
transaction-isolation = READ-COMMITTED
```

**주의사항:**
1. **Binary Logging 형식 확인**
   - READ COMMITTED는 `binlog_format=ROW` 또는 `MIXED` 필요
   - Statement-based는 지원하지 않음

2. **애플리케이션 로직 재검토**
   - Phantom Read, Non-Repeatable Read가 허용되는지 확인
   - 낙관적 락(Version 컬럼) 도입 검토

3. **성능 테스트 필수**
   - 격리 수준 변경 전후 성능 비교
   - Deadlock 발생 빈도 모니터링

4. **Foreign Key 제약 조건**
   - READ COMMITTED에서도 FK 검사 시 Gap Lock 사용됨을 인지

## 핵심 정리

### 격리 수준별 핵심 특징

| 격리 수준 | 스냅샷 시점 | Lock 전략 | 방지하는 문제 | 주요 사용처 |
|-----------|------------|-----------|--------------|------------|
| **READ UNCOMMITTED** | 매 SELECT (Dirty) | Nonlocking | - | ❌ 비권장 |
| **READ COMMITTED** | 매 SELECT | Record Lock만 | Dirty Read | 높은 동시성, Oracle 호환 |
| **REPEATABLE READ** | 첫 번째 읽기 고정 | Next-Key Lock | Dirty + Non-Repeatable | InnoDB 기본값, ACID 중요 시 |
| **SERIALIZABLE** | 첫 번째 읽기 고정 | SELECT도 FOR SHARE | 모든 문제 | XA 트랜잭션, 디버깅 |

### 핵심 트레이드오프

```
격리 수준 ↑ → 데이터 정합성 ↑, 동시성 ↓, Deadlock 가능성 ↑
격리 수준 ↓ → 동시성 ↑, 성능 ↑, 데이터 정합성 ↓
```

### MVCC 스냅샷 전략 비교

**READ COMMITTED:**
```
T1: START → SELECT (스냅샷 1) → ... → SELECT (스냅샷 2) → COMMIT
                    ↑                        ↑
                 시점 A                    시점 B (다를 수 있음)
```

**REPEATABLE READ:**
```
T1: START → SELECT (스냅샷 고정) → ... → SELECT (같은 스냅샷) → COMMIT
                    ↑                        ↑
                 시점 A                    시점 A (동일)
```

### Lock 전략 비교

| 검색 조건 | READ COMMITTED | REPEATABLE READ |
|-----------|----------------|-----------------|
| **Unique Index + Unique 조건** | Record Lock만 | Record Lock만 |
| **범위 / Non-unique 조건** | Record Lock만 | Next-Key Lock (Record + Gap) |
| **매칭 안 되는 row** | Lock 즉시 해제 | Lock 유지 |

### Semi-Consistent Read (READ COMMITTED 최적화)

```
UPDATE t SET b = 5 WHERE b = 3;

READ COMMITTED:
1. 최신 커밋 버전 읽기
2. WHERE 조건 평가
3. 매칭되면 Lock, 아니면 즉시 해제 ✅ (Deadlock ↓)

REPEATABLE READ:
1. 모든 row Lock 획득
2. WHERE 조건 평가
3. 매칭 여부 무관하게 Lock 유지 ❌ (Deadlock ↑)
```

### 실무 선택 가이드

**REPEATABLE READ 선택:**
- 금융/결제 등 ACID 준수 필수
- Phantom Read 방지 필요
- Statement-based Replication 사용

**READ COMMITTED 선택:**
- 대용량 트래픽, 높은 동시성 필요
- Oracle 호환성 필요
- Deadlock 최소화 우선
- ⚠️ Row-based Binary Logging 필수

## 키워드

### `READ UNCOMMITTED`
가장 낮은 격리 수준으로, 트랜잭션이 커밋되지 않은 다른 트랜잭션의 변경사항을 읽을 수 있다(Dirty Read). Lock 오버헤드가 없어 성능은 높지만 데이터 정합성이 보장되지 않아 실무에서는 거의 사용하지 않는다.

### `READ COMMITTED`
각 SELECT 문마다 새로운 스냅샷을 생성하여 최신 커밋된 데이터를 읽는다. Record Lock만 사용하고 Gap Lock은 비활성화되어 높은 동시성을 제공한다. Oracle의 기본 격리 수준이며, Semi-Consistent Read를 통해 UPDATE 시 Deadlock을 줄인다. Non-Repeatable Read와 Phantom Read가 발생할 수 있다.

### `REPEATABLE READ`
InnoDB의 기본 격리 수준으로, 트랜잭션의 첫 번째 읽기에서 생성된 스냅샷을 트랜잭션 종료까지 유지한다. Next-Key Lock(Record + Gap Lock)을 사용하여 Non-Repeatable Read와 대부분의 Phantom Read를 방지한다. ACID 준수가 중요한 시스템에 적합하다.

### `SERIALIZABLE`
가장 높은 격리 수준으로, autocommit이 OFF인 경우 모든 plain SELECT가 자동으로 `SELECT ... FOR SHARE`로 변환된다. 모든 유형의 읽기 이상 현상(Dirty Read, Non-Repeatable Read, Phantom Read)을 방지하지만, 동시성이 가장 낮다. XA 트랜잭션이나 동시성 문제 디버깅 시 주로 사용한다.

### `Dirty Read`
한 트랜잭션이 다른 트랜잭션이 아직 커밋하지 않은 데이터를 읽는 현상. 읽은 데이터가 롤백될 경우 존재하지 않는 값을 읽게 되어 심각한 데이터 불일치를 초래할 수 있다. READ UNCOMMITTED 격리 수준에서만 발생한다.

### `Non-Repeatable Read`
같은 트랜잭션 내에서 동일한 쿼리를 두 번 실행했을 때 서로 다른 결과를 얻는 현상. 첫 번째 읽기와 두 번째 읽기 사이에 다른 트랜잭션이 해당 row를 수정하고 커밋하면 발생한다. READ UNCOMMITTED와 READ COMMITTED에서 발생 가능하다.

### `Phantom Read`
같은 쿼리를 반복 실행했을 때 이전에 없던 row가 나타나거나 기존 row가 사라지는 현상. 다른 트랜잭션이 범위 검색 조건에 해당하는 새로운 row를 삽입하거나 삭제할 때 발생한다. REPEATABLE READ는 Next-Key Lock으로 대부분 방지하지만, READ UNCOMMITTED와 READ COMMITTED에서는 자유롭게 발생한다.

### `MVCC`
Multi-Version Concurrency Control의 약자로, InnoDB가 Undo Log를 활용하여 데이터의 여러 버전을 유지하는 메커니즘이다. 각 트랜잭션은 특정 시점의 스냅샷을 읽을 수 있으며, 읽기 작업이 쓰기 작업을 블로킹하지 않고 쓰기 작업도 읽기 작업을 블로킹하지 않아 높은 동시성을 제공한다.

### `Consistent Read`
트랜잭션이 Undo Log의 스냅샷을 읽어 일관된 데이터를 제공받는 읽기 방식. Locking 없이 수행되며(nonlocking), 격리 수준에 따라 스냅샷 생성 시점이 다르다. READ COMMITTED는 매 SELECT마다 새 스냅샷을, REPEATABLE READ는 트랜잭션의 첫 번째 읽기 시점 스냅샷을 사용한다.

### `Undo Log`
InnoDB가 트랜잭션의 롤백과 MVCC를 지원하기 위해 유지하는 로그. 데이터 변경 전의 이전 버전을 저장하여, 트랜잭션이 롤백되거나 Consistent Read를 수행할 때 과거 시점의 데이터를 복원할 수 있게 한다. MVCC의 핵심 인프라이며, Purge 스레드가 주기적으로 불필요한 Undo Log를 정리한다.

## 참고 자료
- [MySQL 8.4 Reference Manual - Transaction Isolation Levels](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)
- [MySQL 9.3 Reference Manual - Transaction Isolation Levels](https://dev.mysql.com/doc/refman/9.3/en/innodb-transaction-isolation-levels.html)
- [MySQL 8.4 Reference Manual - SET TRANSACTION Statement](https://dev.mysql.com/doc/refman/8.4/en/set-transaction.html)
