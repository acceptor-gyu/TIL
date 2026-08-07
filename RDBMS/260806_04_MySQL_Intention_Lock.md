# MySQL Intention Lock

## 개요
- Intention Lock(의도 락)은 InnoDB가 **Table-Level Lock**과 **Row-Level Lock**을 함께 사용하는 다중 세분성 잠금(Multiple Granularity Locking)을 효율적으로 조율하기 위해 도입한 메커니즘이다.
- "이 트랜잭션이 곧 테이블 내 어떤 row에 S/X Lock을 걸 예정이다"라는 사실을 테이블 레벨에 미리 알리는 **예고성 락**으로, row를 직접 잠그지 않는다.
- 목적은 단 하나, `LOCK TABLES ... WRITE`와 같은 **테이블 전체 락 요청**이 왔을 때 모든 row를 일일이 스캔하지 않고도 충돌 여부를 즉시 판단할 수 있게 하는 것이다.

## 상세 내용

### 1. Lock Granularity (잠금 단위) 문제
InnoDB는 성능을 위해 기본적으로 **row 단위 Lock**을 사용하지만, `LOCK TABLES` 같은 **테이블 단위 Lock**도 지원한다. 문제는 서로 다른 단위(granularity)의 Lock이 동시에 존재할 때 충돌 여부를 어떻게 빠르게 판단하느냐다.

- 테이블에 10만 개의 row가 있고, 그중 일부 row에 X Lock이 걸려 있는 상황에서 다른 트랜잭션이 `LOCK TABLES t WRITE`를 요청했다고 가정하자.
- Intention Lock이 없다면 해당 요청은 테이블의 **모든 row**를 순회하며 Lock 보유 여부를 확인해야 한다 → O(N) 비용.
- Intention Lock이 있다면 테이블 레벨에 걸려 있는 IX/IS 플래그 하나만 확인하면 충돌 여부를 즉시 알 수 있다 → O(1) 비용.

즉 Intention Lock은 **Row Lock의 존재를 Table Lock 관점에서 빠르게 조회하기 위한 인덱스 같은 역할**을 한다.

### 2. Intention Lock의 두 가지 종류
공식 문서(MySQL Reference Manual §17.7.1)는 다음과 같이 정의한다.

| Lock | 이름 | 의미 |
|------|------|------|
| `IS` | Intention Shared | 트랜잭션이 테이블 내 개별 row에 **S Lock**을 걸 예정임을 표시 |
| `IX` | Intention Exclusive | 트랜잭션이 테이블 내 개별 row에 **X Lock**을 걸 예정임을 표시 |

**획득 프로토콜:**
- row에 **S Lock**을 걸기 전, 반드시 테이블에 `IS` Lock(또는 그보다 강한 Lock)을 먼저 획득해야 한다.
- row에 **X Lock**을 걸기 전, 반드시 테이블에 `IX` Lock을 먼저 획득해야 한다.

**SQL과의 매핑:**
```sql
SELECT * FROM t WHERE id = 10 FOR SHARE;
-- 1) 테이블에 IS Lock 획득
-- 2) 매칭되는 row(인덱스 레코드)에 S Lock 획득

SELECT * FROM t WHERE id = 10 FOR UPDATE;
-- 1) 테이블에 IX Lock 획득
-- 2) 매칭되는 row(인덱스 레코드)에 X Lock 획득
```

### 3. 동작 원리 — 왜 O(1) 판단이 가능한가
Intention Lock 자체는 row를 잠그지 않으므로 **서로 다른 row를 다루는 트랜잭션끼리는 절대 충돌하지 않는다.** IX와 IX는 항상 호환되기 때문에 여러 트랜잭션이 동시에 서로 다른 row를 수정할 수 있다.

```sql
-- T1
SELECT * FROM accounts WHERE id = 1 FOR UPDATE;
-- 테이블 IX 획득 → row(id=1) X Lock 획득

-- T2 (동시 실행)
SELECT * FROM accounts WHERE id = 2 FOR UPDATE;
-- 테이블 IX 획득 (T1의 IX와 호환 ✅) → row(id=2) X Lock 획득 (서로 다른 row라 충돌 없음 ✅)

-- 결과: T1, T2 모두 대기 없이 동시 진행
```

반면 다른 세션에서 테이블 전체를 잠그려고 하면 즉시 충돌을 감지하고 대기한다.

```sql
-- T3
LOCK TABLES accounts WRITE;
-- X(테이블 전체) Lock을 요청하지만, 테이블에 이미 IX Lock이 존재 → 충돌
-- T1, T2 중 하나라도 COMMIT/ROLLBACK 할 때까지 대기
```

이 대기 판정이 row를 순회하지 않고 테이블에 걸린 IX 플래그만 확인해서 즉시 이루어진다는 점이 Intention Lock의 핵심 가치다.

### 4. 락 호환성 매트릭스 (X / IX / S / IS)

|        | X  | IX | S  | IS |
|--------|----|----|----|----|
| **X**  | ❌ | ❌ | ❌ | ❌ |
| **IX** | ❌ | ✅ | ❌ | ✅ |
| **S**  | ❌ | ❌ | ✅ | ✅ |
| **IS** | ❌ | ✅ | ✅ | ✅ |

핵심 관찰:
- **IX ↔ IX 호환**: row-level 쓰기 동시성을 최대한 보장하는 핵심 규칙.
- **IX ↔ S 충돌**: "누군가 row를 수정하려는 의도"와 "테이블 전체를 읽기 위해 잠그려는 요청"은 양립 불가.
- **IS ↔ 대부분 호환**: 읽기 의도끼리는 서로 방해하지 않는다.
- Intention Lock끼리는 서로 절대 X/S row Lock과 직접 비교되지 않는다. 오직 **같은 granularity(테이블-테이블)** 간에만 이 매트릭스가 적용된다.

### 5. InnoDB에서의 실제 동작

**INSERT 시 Lock 획득 순서:**
```
1. 테이블에 IX Intention Lock 획득
2. 삽입될 gap에 Insert Intention Lock 획득
3. 새 row에 X Record Lock 획득
```

**SELECT ... FOR UPDATE 시 Lock 획득 순서:**
```
1. 테이블에 IX Intention Lock 획득
2. 매칭되는 인덱스 레코드에 Next-Key Lock(Record + Gap) 획득
```

**모니터링: `SHOW ENGINE INNODB STATUS`**
```
TABLE LOCK table `test`.`accounts` trx id 10080 lock mode IX
```

**모니터링: `performance_schema.data_locks` (MySQL 8.0+)**
```sql
SELECT
    ENGINE_TRANSACTION_ID,
    OBJECT_NAME,
    LOCK_TYPE,
    LOCK_MODE,
    LOCK_STATUS,
    LOCK_DATA
FROM performance_schema.data_locks
WHERE OBJECT_NAME = 'accounts';
```
- `LOCK_TYPE = 'TABLE'`이고 `LOCK_MODE`가 `IX` 또는 `IS`로 표기되는 행이 Intention Lock이다.
- `LOCK_TYPE = 'RECORD'`인 행이 실제 row 단위 S/X Lock이다.

### 6. LOCK TABLES와의 상호작용
Intention Lock은 **개별 row 접근을 절대 차단하지 않는다.** 오직 `LOCK TABLES ... READ/WRITE`처럼 테이블 전체를 대상으로 하는 요청과만 충돌한다.

```sql
-- Connection A
START TRANSACTION;
SELECT * FROM child WHERE id > 100 FOR UPDATE;
-- IX Lock 보유 중

-- Connection B
LOCK TABLES child WRITE;
-- X(테이블 전체) 요청 → IX와 충돌 → A가 COMMIT/ROLLBACK 할 때까지 대기
```

## 핵심 정리
- Intention Lock(IS/IX)은 **row Lock 자체가 아니라 테이블 레벨의 "예고" 신호**다.
- 목적은 서로 다른 granularity(테이블 vs row) 간 Lock 충돌 여부를 **row 전체 스캔 없이 O(1)로 판단**하는 것이다.
- `IX ↔ IX`는 서로 호환되므로 여러 트랜잭션이 동시에 서로 다른 row를 수정할 수 있어 **row-level 동시성을 해치지 않는다.**
- Intention Lock은 `LOCK TABLES ...`, `ALTER TABLE` 같은 **테이블 전체 작업**과만 충돌한다.
- `SELECT ... FOR SHARE`는 IS, `SELECT ... FOR UPDATE`는 IX를 유발한다.

## 기술적 한계와 보완 전략
- Intention Lock 자체는 row 간 동시성을 제한하지 않지만, 대량 `ALTER TABLE`, `LOCK TABLES`, 온라인 DDL 등 **테이블 전체 작업과 경합**하면 여전히 병목이 될 수 있다.
- 대량 UPDATE/INSERT가 몰릴 때는 IX Lock 자체보다 그 하위의 Record/Gap/Next-Key Lock 경합과 Deadlock이 실제 병목 원인인 경우가 많다. `performance_schema.data_lock_waits`로 대기 체인을 함께 확인해야 한다.
- 온라인 스키마 변경 도구(gh-ost, pt-online-schema-change)는 실제로는 shadow table + binlog 적용 방식을 사용해 장시간 테이블 락(및 그로 인한 Intention Lock 대기)을 회피한다.

## 키워드

### `Intention Lock` (의도 락)
Table-Level Lock으로, 트랜잭션이 향후 테이블 내 특정 row에 S 또는 X Lock을 걸 것이라는 의도를 미리 테이블 레벨에 표시하는 메커니즘. row 자체를 잠그지 않고 "예고"만 하기 때문에 서로 다른 row를 다루는 트랜잭션끼리는 차단하지 않는다.

### `IS` (Intention Shared)
트랜잭션이 테이블 내 개별 row에 Shared Lock(S)을 걸 예정임을 나타내는 테이블 레벨 락. `SELECT ... FOR SHARE`를 실행하면 자동으로 설정된다.

### `IX` (Intention Exclusive)
트랜잭션이 테이블 내 개별 row에 Exclusive Lock(X)을 걸 예정임을 나타내는 테이블 레벨 락. `SELECT ... FOR UPDATE`, `INSERT`, `UPDATE`, `DELETE` 시 자동으로 설정된다.

### `Multiple Granularity Locking` (다중 세분성 잠금)
테이블 단위 Lock과 row 단위 Lock처럼 서로 다른 세분성(granularity)의 락을 계층적으로 함께 운용하는 기법. Intention Lock은 이 계층 구조에서 상위(테이블) 레벨이 하위(row) 레벨의 잠금 상태를 빠르게 파악할 수 있게 해주는 핵심 장치다.

### `Lock Compatibility Matrix` (락 호환성 매트릭스)
X, IX, S, IS 네 가지 Lock 모드 간에 동시 보유가 가능한지를 나타낸 표. IX-IX, IS-IX, IS-IS, IS-S는 호환되지만 X는 다른 모든 모드와 충돌한다.

### `InnoDB`
MySQL의 기본 스토리지 엔진으로 row-level locking, MVCC, ACID 트랜잭션을 지원한다. Intention Lock은 InnoDB가 테이블 락과 row 락을 함께 지원하기 위해 도입한 내부 메커니즘이다.

### `Table Lock`
테이블 전체를 대상으로 하는 Lock. `LOCK TABLES ... WRITE/READ`로 명시적으로 획득할 수 있으며, Intention Lock과 충돌 여부를 비교하는 대상이 된다.

### `Record Lock`
개별 인덱스 레코드(=row)에 대한 Lock. Intention Lock 획득 이후 실제로 걸리는 하위 레벨의 row Lock이다.

### `data_locks` (performance_schema.data_locks)
MySQL 8.0부터 제공되는 성능 스키마 테이블로, 현재 보유 중이거나 대기 중인 모든 InnoDB Lock(테이블 Intention Lock 포함)의 상세 정보를 조회할 수 있다.

## 참고 자료
- [MySQL 8.4 Reference Manual - 17.7.1 InnoDB Locking (Intention Locks)](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html)
- [MySQL 8.4 Reference Manual - Locks Set by Different SQL Statements in InnoDB](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html)
- [MySQL 8.4 Reference Manual - InnoDB Lock and Lock-Wait Information (data_locks)](https://dev.mysql.com/doc/refman/8.4/en/innodb-information-schema-understanding-innodb-locking.html)
