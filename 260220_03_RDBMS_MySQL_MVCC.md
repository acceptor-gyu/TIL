# RDBMS(MySQL) MVCC

## 개요
MySQL(InnoDB)의 핵심 동시성 제어 메커니즘인 MVCC(Multi-Version Concurrency Control)의 내부 동작 원리, Undo Log와의 관계, 그리고 트랜잭션 격리 수준별 동작 차이를 정리한다.

## 상세 내용

### 1. MVCC란?

#### 1.1 Multi-Version Concurrency Control의 정의

**MVCC(Multi-Version Concurrency Control)**는 데이터베이스 객체의 **여러 버전**을 유지하여, 읽기와 쓰기 작업이 동시에 발생해도 **블로킹이나 충돌 없이** 접근할 수 있도록 하는 동시성 제어 메커니즘이다.

InnoDB는 **Multi-Version Storage Engine**으로, 변경된 row의 이전 버전 정보를 보관하여 트랜잭션의 동시성(concurrency)과 롤백(rollback)을 지원한다.

#### 1.2 MVCC가 해결하는 문제: 읽기-쓰기 간 블로킹 제거

**전통적인 Lock 기반 동시성 제어의 문제:**
```sql
-- 트랜잭션 T1
UPDATE accounts SET balance = 1000 WHERE id = 1;
-- T1이 id=1에 X Lock 보유

-- 트랜잭션 T2
SELECT balance FROM accounts WHERE id = 1;
-- ❌ T1의 Lock 때문에 대기 (블로킹)
```

**MVCC의 해결책:**
```sql
-- 트랜잭션 T1
UPDATE accounts SET balance = 1000 WHERE id = 1;
-- 새 버전 생성, Undo Log에 이전 버전(500) 보관

-- 트랜잭션 T2
SELECT balance FROM accounts WHERE id = 1;
-- ✅ Undo Log의 이전 버전(500)을 읽음 (블로킹 없음!)
```

**핵심 이점:**
- **읽기 작업이 쓰기 작업을 블로킹하지 않음**
- **쓰기 작업이 읽기 작업을 블로킹하지 않음**
- 높은 동시성과 강력한 트랜잭션 격리 제공
- Lock 경합(Lock Contention) 최소화

#### 1.3 InnoDB에서 MVCC를 구현하는 핵심 구성 요소

```
┌─────────────────────────────────────────┐
│          MVCC 구성 요소                   │
├─────────────────────────────────────────┤
│  1. Hidden Columns (각 row에 저장)        │
│     - DB_TRX_ID: 트랜잭션 ID              │
│     - DB_ROLL_PTR: Undo Log 포인터       │
│     - DB_ROW_ID: 자동 생성 Row ID         │
│                                         │
│  2. Undo Tablespaces                    │
│     - Rollback Segment                  │
│       ├── Insert Undo Log               │
│       └── Update Undo Log               │
│                                         │
│  3. Read View (Snapshot)                │
│     - 트랜잭션이 볼 수 있는 버전 결정          │
└─────────────────────────────────────────┘
```

### 2. InnoDB의 MVCC 내부 구조

#### 2.1 Hidden Columns (DB_TRX_ID, DB_ROLL_PTR, DB_ROW_ID)

InnoDB는 내부적으로 각 row에 **3개의 숨겨진 컬럼**을 추가한다:

| 필드 | 크기 | 목적 |
|------|------|------|
| **`DB_TRX_ID`** | 6 bytes | 해당 row를 마지막으로 **INSERT 또는 UPDATE**한 트랜잭션의 ID. 삭제는 내부적으로 특수 비트를 설정하는 UPDATE로 처리됨 |
| **`DB_ROLL_PTR`** | 7 bytes | "Roll Pointer" — Undo Log의 이전 버전 레코드를 가리키는 포인터. 이를 통해 row의 과거 버전 재구성 가능 |
| **`DB_ROW_ID`** | 6 bytes | 단조 증가하는 Row ID. InnoDB가 자동으로 Clustered Index를 생성할 때만 사용됨 (명시적 PK가 없을 때) |

**예시:**
```
실제 테이블: accounts (id, balance)
InnoDB 내부 저장:
┌────┬─────────┬────────────┬─────────────┬───────────┐
│ id │ balance │ DB_TRX_ID  │ DB_ROLL_PTR │ DB_ROW_ID │
├────┼─────────┼────────────┼─────────────┼───────────┤
│ 1  │ 1000    │ TRX_100    │ ptr→undo v2 │ 1         │
└────┴─────────┴────────────┴─────────────┴───────────┘
```

#### 2.2 Undo Log와 버전 체인(Version Chain)

**버전 체인의 구조:**

```
현재 Row (Clustered Index)
┌────────────────────────────┐
│ balance: 1000              │
│ DB_TRX_ID: 100             │
│ DB_ROLL_PTR: ─────────┐    │
└────────────────────────│───┘
                         │
                         ▼
              Undo Log Record v2
              ┌──────────────────┐
              │ balance: 500     │
              │ TRX_ID: 80       │
              │ ROLL_PTR: ────┐  │
              └───────────────│──┘
                              │
                              ▼
                   Undo Log Record v1
                   ┌──────────────────┐
                   │ balance: 100     │
                   │ TRX_ID: 50       │
                   │ ROLL_PTR: NULL   │
                   └──────────────────┘
```

**동작 방식:**
1. 트랜잭션이 row를 UPDATE하면 **새 버전을 Clustered Index에 저장**
2. **이전 버전은 Undo Log에 기록**
3. `DB_ROLL_PTR`이 Undo Log를 가리켜 체인 형성
4. 필요 시 체인을 따라가며 과거 버전 재구성

#### 2.3 Read View (Consistent Read View)의 생성과 구조

**Read View**는 트랜잭션이 어떤 버전의 데이터를 볼 수 있는지 결정하는 **스냅샷 메타데이터**이다.

**Read View의 핵심 필드:**
```
Read View {
  m_low_limit_id:  생성 시점의 다음 트랜잭션 ID (미래 트랜잭션)
  m_up_limit_id:   활성 트랜잭션 중 가장 작은 ID
  m_ids[]:         생성 시점의 활성 트랜잭션 ID 리스트
  m_creator_trx_id: Read View를 생성한 트랜잭션 ID
}
```

**가시성 판단 알고리즘:**
```
row의 DB_TRX_ID를 확인:

1. DB_TRX_ID < m_up_limit_id
   → ✅ 보임 (충분히 오래된 커밋된 데이터)

2. DB_TRX_ID >= m_low_limit_id
   → ❌ 안 보임 (미래 트랜잭션의 변경)

3. m_up_limit_id <= DB_TRX_ID < m_low_limit_id
   → DB_TRX_ID가 m_ids[]에 있나?
      - 있음 → ❌ 안 보임 (활성 트랜잭션이 변경)
      - 없음 → ✅ 보임 (이미 커밋됨)

4. 안 보이면 → DB_ROLL_PTR을 따라 Undo Log 체인 탐색
```

### 3. Consistent Read (일관된 읽기)

#### 3.1 Nonlocking Read의 동작 원리

**Consistent Read (일관된 읽기)**는 특정 시점의 **스냅샷**을 읽는 방식으로, **Lock 없이** 수행된다.

**동작 과정:**
```sql
-- 트랜잭션 T1 (TRX_ID = 100)
START TRANSACTION;
SELECT * FROM accounts WHERE id = 1;
```

1. **Read View 생성** (격리 수준에 따라 시점 다름)
2. Row의 `DB_TRX_ID` 확인
3. `DB_TRX_ID`가 Read View의 가시성 범위 내?
   - **YES** → 현재 row 버전 사용
   - **NO** → `DB_ROLL_PTR`을 따라 Undo Log 체인 탐색하여 **가시성 범위 내의 과거 버전** 재구성

**핵심:**
- Lock을 획득하지 않음
- 다른 트랜잭션의 쓰기 작업을 블로킹하지 않음
- Undo Log를 활용한 시점 일관성 보장

#### 3.2 스냅샷 읽기 vs 현재 읽기(Current Read)

| 읽기 타입 | 설명 | Lock 사용 | 읽는 버전 |
|-----------|------|----------|----------|
| **Snapshot Read** (Consistent Read) | `SELECT` | ❌ Nonlocking | Read View 기준 과거 버전 |
| **Current Read** (Locking Read) | `SELECT ... FOR UPDATE`<br>`SELECT ... FOR SHARE`<br>`UPDATE`<br>`DELETE` | ✅ Lock 획득 | 최신 커밋된 버전 |

**예시:**
```sql
-- 초기 상태: balance = 500

-- 트랜잭션 T1
START TRANSACTION;
UPDATE accounts SET balance = 1000 WHERE id = 1;
-- 아직 커밋 안 함

-- 트랜잭션 T2 (REPEATABLE READ)
START TRANSACTION;

-- Snapshot Read (Consistent Read)
SELECT balance FROM accounts WHERE id = 1;
-- 결과: 500 (Undo Log의 이전 버전) ✅

-- Current Read (Locking Read)
SELECT balance FROM accounts WHERE id = 1 FOR UPDATE;
-- T1의 X Lock 때문에 대기 ❌ → T1 커밋 후 1000 조회
```

#### 3.3 READ COMMITTED vs REPEATABLE READ에서의 Read View 생성 시점 차이

**MVCC는 `READ COMMITTED`와 `REPEATABLE READ` 격리 수준에서만 동작한다.**

| 격리 수준 | Read View 생성 시점 | 동일 트랜잭션 내 일관성 |
|-----------|---------------------|------------------------|
| **READ COMMITTED** | **매 SELECT 문마다** 새로 생성 | ❌ SELECT마다 다른 데이터 조회 가능 |
| **REPEATABLE READ** | **트랜잭션의 첫 번째 SELECT** 시 한 번만 생성 | ✅ 트랜잭션 내 모든 SELECT가 동일 스냅샷 조회 |

**예시 비교:**
```sql
-- 초기 상태: balance = 500

-- 트랜잭션 T1 (READ COMMITTED)
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 500 (Read View #1)

-- 다른 트랜잭션이 balance를 1000으로 변경 후 COMMIT

SELECT balance FROM accounts WHERE id = 1;  -- 1000 (Read View #2, 새로 생성!)
COMMIT;

-- 트랜잭션 T2 (REPEATABLE READ)
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 500 (Read View 고정)

-- 다른 트랜잭션이 balance를 1000으로 변경 후 COMMIT

SELECT balance FROM accounts WHERE id = 1;  -- 500 (여전히 같은 Read View!)
COMMIT;
```

### 4. Undo Log의 역할과 구조

#### 4.1 Insert Undo Log vs Update Undo Log

**Undo Tablespaces의 구조:**
```
Undo Tablespaces
└── Rollback Segment
    ├── Insert Undo Log
    │   - row 삽입 시 생성
    │   - 롤백에만 사용
    │   - 트랜잭션 커밋 즉시 삭제 가능
    │
    └── Update Undo Log
        - row 수정/삭제 시 생성
        - 롤백 + Consistent Read에 사용
        - 활성 트랜잭션이 필요로 하는 동안 유지
```

| Undo Log 타입 | 생성 시점 | 삭제 가능 시점 | 용도 |
|---------------|----------|---------------|------|
| **Insert Undo Log** | `INSERT` 실행 시 | 트랜잭션 **커밋 즉시** | 롤백만 |
| **Update Undo Log** | `UPDATE` / `DELETE` 실행 시 | **모든 활성 트랜잭션**이 해당 스냅샷을 필요로 하지 않을 때 | 롤백 + Consistent Read |

**예시:**
```sql
-- Insert Undo Log
INSERT INTO accounts (id, balance) VALUES (10, 5000);
-- Undo: DELETE FROM accounts WHERE id = 10;
COMMIT;
-- ✅ Insert Undo Log 즉시 삭제 가능

-- Update Undo Log
UPDATE accounts SET balance = 1000 WHERE id = 1;
-- Undo: balance의 이전 값(500) 저장
COMMIT;
-- ⚠️ 다른 트랜잭션이 Read View를 통해 필요로 할 수 있음
-- → Purge 스레드가 나중에 정리
```

#### 4.2 Undo Log 체인을 통한 과거 버전 추적

**버전 체인 탐색 과정:**

```
트랜잭션 T1 (TRX_ID = 50):  balance = 100 (원본)
트랜잭션 T2 (TRX_ID = 80):  balance = 500 (UPDATE)
트랜잭션 T3 (TRX_ID = 100): balance = 1000 (UPDATE)

현재 Clustered Index:
balance = 1000, DB_TRX_ID = 100, DB_ROLL_PTR → Undo v2

Undo Log v2 (TRX_ID = 80):
balance = 500, ROLL_PTR → Undo v1

Undo Log v1 (TRX_ID = 50):
balance = 100, ROLL_PTR → NULL
```

**Read View가 TRX_ID = 70인 스냅샷을 읽을 때:**

1. 현재 row 확인: `DB_TRX_ID = 100` → 안 보임 (100 > 70)
2. `DB_ROLL_PTR` 따라 Undo v2 이동: `TRX_ID = 80` → 안 보임 (80 > 70)
3. `ROLL_PTR` 따라 Undo v1 이동: `TRX_ID = 50` → **보임!** (50 < 70)
4. **결과: balance = 100** 반환

#### 4.3 Purge 스레드의 Undo Log 정리

**Purge 스레드의 역할:**

InnoDB의 백그라운드 **Purge 스레드**는 더 이상 필요 없는 Undo Log를 정리한다.

**정리 조건:**
```
Update Undo Log를 삭제할 수 있는 조건:
1. 해당 Undo Log를 생성한 트랜잭션이 커밋됨
2. 모든 활성 트랜잭션의 Read View가 해당 버전을 필요로 하지 않음
```

**DELETE의 물리적 삭제:**

```sql
DELETE FROM accounts WHERE id = 1;
```

1. **논리적 삭제:** `DB_TRX_ID`에 delete-mark 비트 설정 (실제 row는 유지)
2. **Purge 스레드 대기:** Update Undo Log가 정리 가능해질 때까지
3. **물리적 삭제:** Purge 스레드가 row와 인덱스 레코드를 실제로 제거

**Purge Lag 문제:**

높은 INSERT/DELETE 빈도의 테이블에서 Purge가 따라가지 못하면:
```sql
-- Purge 작업을 throttle하여 시스템 안정화
SET GLOBAL innodb_max_purge_lag = 1000;
```

### 5. MVCC와 Lock의 관계

#### 5.1 MVCC가 Lock을 완전히 대체하지 않는 이유

**MVCC의 한계:**

MVCC는 **읽기 일관성**을 제공하지만, **쓰기 충돌**은 방지하지 못한다.

```sql
-- Lost Update 문제 (MVCC로 해결 불가)

-- 트랜잭션 T1
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 1000 (Snapshot Read)
-- balance에서 100 차감 계산: 1000 - 100 = 900

-- 트랜잭션 T2
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 1000 (같은 스냅샷)
UPDATE accounts SET balance = 500 WHERE id = 1;
COMMIT;

-- 트랜잭션 T1
UPDATE accounts SET balance = 900 WHERE id = 1;
-- ❌ T2의 변경사항(500) 손실! (Lost Update)
COMMIT;
```

**해결책: Locking Read 사용**
```sql
-- 트랜잭션 T1
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1 FOR UPDATE;  -- Lock 획득
-- T2가 대기하게 됨 ✅
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
COMMIT;
```

**MVCC와 Lock의 역할 분담:**

| 기능 | MVCC | Lock |
|------|------|------|
| **읽기 일관성** | ✅ Consistent Read로 제공 | - |
| **읽기-쓰기 비블로킹** | ✅ 읽기는 Lock 없이 수행 | - |
| **쓰기-쓰기 충돌 방지** | ❌ 불가능 | ✅ X Lock으로 직렬화 |
| **Phantom Read 방지** | ⚠️ 부분적 (격리 수준 의존) | ✅ Gap Lock으로 방지 |

#### 5.2 Locking Read(SELECT ... FOR UPDATE)와 MVCC의 차이

| 구분 | Consistent Read (MVCC) | Locking Read |
|------|------------------------|--------------|
| **구문** | `SELECT` | `SELECT ... FOR UPDATE`<br>`SELECT ... FOR SHARE` |
| **Lock 획득** | ❌ 없음 | ✅ X Lock 또는 S Lock |
| **읽는 버전** | Read View 기준 **과거 스냅샷** | **최신 커밋된 버전** |
| **블로킹** | 다른 트랜잭션 블로킹 안 함 | 다른 트랜잭션을 블로킹할 수 있음 |
| **용도** | 단순 조회, 읽기 일관성 | 곧 수정할 데이터 읽기 (Lost Update 방지) |

**예시:**
```sql
-- Consistent Read (MVCC)
SELECT * FROM accounts WHERE id = 1;
-- 스냅샷 읽기, Lock 없음, 다른 트랜잭션의 UPDATE 블로킹 안 함

-- Locking Read
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
-- 최신 버전 읽기, X Lock 획득, 다른 트랜잭션의 UPDATE 블로킹
```

#### 5.3 MVCC와 Gap Lock의 협력 관계

**REPEATABLE READ에서의 협력:**

```sql
-- 초기 상태: id = 1, 3, 5 존재

-- 트랜잭션 T1 (REPEATABLE READ)
START TRANSACTION;
SELECT * FROM accounts WHERE id BETWEEN 2 AND 4 FOR UPDATE;
-- 결과: id = 3 한 개

-- Next-Key Lock 적용:
-- (1, 3] Record Lock + Gap Lock
-- (3, 5) Gap Lock

-- 트랜잭션 T2
INSERT INTO accounts (id, balance) VALUES (2, 1000);
-- ❌ Gap Lock 때문에 대기 (Phantom Read 방지)

-- 트랜잭션 T1
SELECT * FROM accounts WHERE id BETWEEN 2 AND 4;
-- 결과: 여전히 id = 3 한 개 (일관성 유지)
COMMIT;
```

**협력 메커니즘:**
- **MVCC:** Snapshot Read로 Non-Repeatable Read 방지
- **Gap Lock:** 범위 내 새로운 row 삽입 차단으로 Phantom Read 방지

### 6. MVCC의 한계와 주의사항

#### 6.1 Long Transaction이 Undo Log에 미치는 영향

**문제 상황:**

```sql
-- 트랜잭션 T1 (Long Transaction)
START TRANSACTION;
SELECT * FROM accounts WHERE id = 1;  -- Read View 생성
-- ... 10분 동안 다른 작업 수행 ...

-- 이 사이 수많은 트랜잭션이 accounts 테이블 UPDATE
-- 하지만 T1의 Read View가 여전히 살아있음

-- ⚠️ T1이 필요로 하는 Undo Log는 삭제 불가!
-- → Undo Log 계속 누적
```

**공식 문서 경고:**

> ⚠️ **Long-running transactions** (even read-only ones) prevent update undo log cleanup, causing the rollback segment to grow and potentially **fill the undo tablespace**.

**영향:**
- Undo Tablespace 크기 증가
- 디스크 공간 소진 위험
- Purge 스레드 지연
- 전체 시스템 성능 저하

**모범 사례:**
```sql
-- 읽기 전용 트랜잭션도 주기적으로 커밋
START TRANSACTION;
SELECT /* ... */;
COMMIT;  -- ✅ Read View 해제, Undo Log 정리 가능하게 함

-- 또는 autocommit 사용
SET autocommit = 1;
SELECT /* ... */;  -- 자동 커밋
```

#### 6.2 Undo Tablespace 비대화 문제

**모니터링:**
```sql
-- Undo Tablespace 크기 확인
SELECT TABLESPACE_NAME, FILE_NAME,
       TOTAL_EXTENTS * EXTENT_SIZE / 1024 / 1024 AS SIZE_MB
FROM INFORMATION_SCHEMA.FILES
WHERE TABLESPACE_NAME LIKE 'innodb_undo%';

-- 오래된 트랜잭션 확인
SELECT trx_id, trx_started, trx_rows_modified, trx_state
FROM INFORMATION_SCHEMA.INNODB_TRX
WHERE TIMESTAMPDIFF(SECOND, trx_started, NOW()) > 300;  -- 5분 이상
```

**해결책:**
```sql
-- 1. Long Transaction 강제 종료
KILL <trx_id>;

-- 2. Undo Tablespace 자동 Truncate 활성화
SET GLOBAL innodb_undo_log_truncate = ON;
SET GLOBAL innodb_max_undo_log_size = 1073741824;  -- 1GB
```

#### 6.3 MVCC로 방지할 수 없는 동시성 문제 (Lost Update 등)

**Lost Update (갱신 손실):**

```sql
-- 계좌 A의 balance = 1000

-- 트랜잭션 T1
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 1000 (MVCC Snapshot)
-- 애플리케이션에서 계산: 1000 - 100 = 900

-- 트랜잭션 T2
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 1000 (같은 스냅샷)
UPDATE accounts SET balance = 1500 WHERE id = 1;
COMMIT;  -- ✅ balance = 1500

-- 트랜잭션 T1
UPDATE accounts SET balance = 900 WHERE id = 1;
COMMIT;  -- ❌ T2의 변경(1500) 손실!
```

**해결책 1: Locking Read**
```sql
-- 트랜잭션 T1
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1 FOR UPDATE;  -- Lock 획득
-- T2가 대기하게 됨
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
COMMIT;
```

**해결책 2: Optimistic Locking (낙관적 락)**
```sql
-- Version 컬럼 활용
SELECT balance, version FROM accounts WHERE id = 1;  -- balance=1000, version=5

UPDATE accounts
SET balance = 900, version = version + 1
WHERE id = 1 AND version = 5;  -- 다른 트랜잭션이 먼저 변경했다면 실패
```

**Write Skew (쓰기 스큐):**

```sql
-- 제약 조건: balance_A + balance_B >= 1000

-- 트랜잭션 T1
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 600 (A)
SELECT balance FROM accounts WHERE id = 2;  -- 500 (B)
-- 합계: 1100 ✅ 제약 조건 만족
UPDATE accounts SET balance = 500 WHERE id = 1;  -- A에서 100 출금

-- 트랜잭션 T2 (동시 실행)
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 600 (A, 스냅샷)
SELECT balance FROM accounts WHERE id = 2;  -- 500 (B)
-- 합계: 1100 ✅ 제약 조건 만족
UPDATE accounts SET balance = 400 WHERE id = 2;  -- B에서 100 출금

COMMIT;  -- T1, T2 모두 커밋
-- ❌ 결과: A=500, B=400, 합계=900 (제약 조건 위반!)
```

**해결책: SERIALIZABLE 격리 수준 또는 애플리케이션 레벨 Lock**
```sql
-- SERIALIZABLE 사용
SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE;
START TRANSACTION;
SELECT balance FROM accounts WHERE id IN (1, 2) FOR SHARE;
-- Gap Lock으로 Phantom Read 및 Write Skew 방지
```

## 핵심 정리

### MVCC의 핵심 메커니즘

```
┌─────────────────────────────────────────────────────┐
│            MVCC 동작 흐름                             │
├─────────────────────────────────────────────────────┤
│  1. 트랜잭션 시작 → Read View 생성 (격리 수준 의존)         │
│  2. SELECT 실행 → Row의 DB_TRX_ID 확인                 │
│  3. 가시성 판단:                                       │
│     - 보임 → 현재 버전 사용                             │
│     - 안 보임 → DB_ROLL_PTR 따라 Undo Log 탐색          │
│  4. 가시성 범위 내 과거 버전 재구성 및 반환                  │
└─────────────────────────────────────────────────────┘
```

### Hidden Columns의 역할

| 컬럼 | 크기 | 핵심 역할 |
|------|------|----------|
| **DB_TRX_ID** | 6 bytes | 버전 식별자 (언제 변경되었나?) |
| **DB_ROLL_PTR** | 7 bytes | 버전 체인 링크 (이전 버전은 어디?) |
| **DB_ROW_ID** | 6 bytes | PK 없을 때 자동 생성 |

### Undo Log 타입별 특징

| | Insert Undo Log | Update Undo Log |
|-|----------------|-----------------|
| **생성 시점** | INSERT | UPDATE / DELETE |
| **용도** | 롤백만 | 롤백 + Consistent Read |
| **삭제 시점** | 커밋 즉시 | 모든 활성 트랜잭션이 불필요해질 때 |
| **영향** | 낮음 | Long Transaction 시 비대화 위험 |

### Read View 생성 시점 비교

```
READ COMMITTED:
T1: START → SELECT (View #1) → ... → SELECT (View #2) → COMMIT
                   ↓                         ↓
               매번 새로 생성            최신 커밋 데이터 조회

REPEATABLE READ:
T1: START → SELECT (View 고정) → ... → SELECT (같은 View) → COMMIT
                   ↓                         ↓
               한 번만 생성              일관된 스냅샷 조회
```

### MVCC vs Lock 역할 분담

| 동시성 문제 | MVCC 해결 여부 | Lock 필요 여부 |
|------------|---------------|---------------|
| **Dirty Read** | ✅ 방지 (Snapshot) | - |
| **Non-Repeatable Read** | ✅ 방지 (REPEATABLE READ) | - |
| **Phantom Read** | ⚠️ 부분적 | ✅ Gap Lock 필요 |
| **Lost Update** | ❌ 방지 불가 | ✅ Locking Read 필요 |
| **Write Skew** | ❌ 방지 불가 | ✅ SERIALIZABLE 또는 앱 레벨 Lock |

### 읽기 타입 비교

| | Snapshot Read | Current Read |
|-|--------------|--------------|
| **구문** | `SELECT` | `SELECT ... FOR UPDATE/SHARE` |
| **Lock** | ❌ Nonlocking | ✅ X Lock / S Lock |
| **읽는 버전** | Read View 기준 과거 스냅샷 | 최신 커밋 버전 |
| **용도** | 단순 조회 | 곧 수정할 데이터 읽기 |

### Long Transaction의 위험성

```
Long Transaction (10분 동안 유지)
    ↓
Read View가 살아있음
    ↓
해당 Read View가 필요로 하는 Undo Log 삭제 불가
    ↓
Undo Tablespace 비대화
    ↓
디스크 공간 소진, 성능 저하
```

**모범 사례:**
```sql
-- 읽기 전용 트랜잭션도 주기적으로 커밋
START TRANSACTION;
SELECT /* ... */;
COMMIT;  -- ✅ Undo Log 정리 가능
```

### Secondary Index와 MVCC

```
Secondary Index Read 흐름:
1. Secondary Index 검색
2. Delete-mark 확인 또는 최신 버전 확인
   ↓ YES
3. Clustered Index 조회 (DB_TRX_ID 확인)
4. Undo Log 체인 탐색 (필요 시)
5. 가시성 범위 내 버전 반환
```

## 키워드

### `MVCC`
Multi-Version Concurrency Control의 약자로, 데이터베이스 객체의 여러 버전을 유지하여 읽기와 쓰기 작업이 서로 블로킹하지 않도록 하는 동시성 제어 메커니즘이다. InnoDB는 Undo Log를 활용하여 MVCC를 구현하며, 읽기 작업은 특정 시점의 스냅샷을 Lock 없이 조회할 수 있다. 이를 통해 높은 동시성과 강력한 트랜잭션 격리를 동시에 제공한다.

### `Undo Log`
InnoDB가 트랜잭션의 롤백과 MVCC를 지원하기 위해 Undo Tablespace에 저장하는 이전 버전의 데이터 로그이다. UPDATE나 DELETE 시 변경 전 값을 저장하며, DB_ROLL_PTR로 연결된 버전 체인을 형성한다. Insert Undo Log는 커밋 즉시 삭제되지만, Update Undo Log는 활성 트랜잭션이 필요로 하는 동안 유지되며 Purge 스레드가 나중에 정리한다.

### `Read View`
트랜잭션이 어떤 버전의 데이터를 볼 수 있는지 결정하는 스냅샷 메타데이터 구조체이다. m_low_limit_id(미래 트랜잭션 경계), m_up_limit_id(가장 오래된 활성 트랜잭션), m_ids[](활성 트랜잭션 목록) 등의 필드를 포함한다. READ COMMITTED는 매 SELECT마다 새로 생성하고, REPEATABLE READ는 트랜잭션의 첫 SELECT에서 한 번만 생성하여 일관성을 보장한다.

### `DB_TRX_ID`
InnoDB가 각 row에 자동으로 추가하는 6바이트 크기의 숨겨진 컬럼으로, 해당 row를 마지막으로 INSERT 또는 UPDATE한 트랜잭션의 ID를 저장한다. MVCC에서 row의 버전을 식별하는 핵심 필드이며, Read View와 비교하여 현재 트랜잭션이 해당 버전을 볼 수 있는지 판단한다. DELETE는 내부적으로 delete-mark 비트를 설정하는 UPDATE로 처리된다.

### `DB_ROLL_PTR`
7바이트 크기의 Roll Pointer로, Undo Log의 이전 버전 레코드를 가리키는 포인터이다. 이 포인터를 따라가면 버전 체인(Version Chain)을 형성하여 과거의 모든 버전을 추적할 수 있다. Consistent Read 시 현재 버전이 Read View 범위를 벗어나면 DB_ROLL_PTR을 따라 Undo Log를 탐색하여 가시성 범위 내의 과거 버전을 재구성한다.

### `Version Chain`
DB_ROLL_PTR로 연결된 Undo Log 레코드들의 연결 체인으로, 하나의 row에 대한 시간순 변경 이력을 나타낸다. 현재 Clustered Index의 row에서 시작하여 Undo Log v2 → Undo Log v1 → ... 순으로 과거 버전을 추적할 수 있다. MVCC는 이 체인을 따라가며 Read View의 가시성 범위에 맞는 적절한 버전을 찾아 반환한다.

### `Consistent Read`
특정 시점의 스냅샷(Read View)을 기준으로 일관된 데이터를 Lock 없이 읽는 방식이다. Nonlocking Read라고도 하며, Undo Log를 활용하여 과거 버전을 재구성한다. 읽기 작업이 쓰기 작업을 블로킹하지 않고, 쓰기 작업도 읽기 작업을 블로킹하지 않아 높은 동시성을 제공한다. READ COMMITTED와 REPEATABLE READ 격리 수준에서 동작한다.

### `Snapshot Read`
Consistent Read와 같은 의미로, Read View 기준의 과거 스냅샷을 읽는 비잠금 읽기 방식이다. 일반적인 `SELECT` 문이 이에 해당하며, Lock을 획득하지 않고 MVCC 메커니즘을 통해 시점 일관성을 보장한다. Current Read(Locking Read)와 대비되는 개념이다.

### `Current Read`
Locking Read라고도 하며, 최신 커밋된 버전을 Lock을 획득하며 읽는 방식이다. `SELECT ... FOR UPDATE`, `SELECT ... FOR SHARE`, `UPDATE`, `DELETE` 문이 이에 해당한다. Snapshot Read와 달리 과거 버전이 아닌 현재 시점의 최신 데이터를 읽으며, 곧 수정할 데이터를 읽거나 Lost Update 문제를 방지하기 위해 사용한다.

### `Purge`
InnoDB의 백그라운드 Purge 스레드가 수행하는 Undo Log 정리 작업이다. 모든 활성 트랜잭션이 더 이상 필요로 하지 않는 Update Undo Log를 삭제하고, DELETE된 row의 delete-mark를 제거하여 물리적으로 row와 인덱스 레코드를 삭제한다. Long Transaction이 존재하면 Purge가 지연되어 Undo Tablespace가 비대해질 수 있다.

## 참고 자료
- [MySQL 8.4 Reference Manual - InnoDB Multi-Versioning](https://dev.mysql.com/doc/refman/8.4/en/innodb-multi-versioning.html)
- [MySQL 8.0 Reference Manual - InnoDB Multi-Versioning](https://dev.mysql.com/doc/refman/8.0/en/innodb-multi-versioning.html)
