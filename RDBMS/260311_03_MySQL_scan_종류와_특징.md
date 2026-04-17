# MySQL scan 종류와 특징

## 개요
MySQL에서 데이터를 조회할 때 사용되는 다양한 scan 방식의 종류와 각각의 특징, 성능 차이를 학습한다.

## 상세 내용

### 1. Full Table Scan

#### 동작 원리
Full Table Scan은 MySQL이 테이블의 모든 행을 처음부터 끝까지 순차적으로 읽는 방식이다. 인덱스를 사용하지 않고 데이터 파일 전체를 읽기 때문에 디스크 I/O가 많이 발생한다. EXPLAIN 결과의 `type` 컬럼에 `ALL`로 표시된다.

#### Full Table Scan이 발생하는 조건
- `WHERE` 절에 인덱스가 적용되지 않는 조건만 존재하는 경우
- 함수나 연산자로 인덱스 컬럼이 변형된 경우 (`WHERE YEAR(created_at) = 2024`)
- 인덱스 컬럼에 `!=`, `NOT IN`, `NOT LIKE` 등 부정 조건을 사용한 경우
- `LIKE '%keyword'`처럼 와일드카드가 앞에 오는 경우
- 옵티마이저가 인덱스 사용 비용이 Full Table Scan보다 높다고 판단한 경우
  - 예: 전체 데이터의 20~30% 이상을 읽어야 할 때
  - 카디널리티가 낮은 인덱스 (예: boolean 컬럼)
- 테이블 행 수가 매우 적은 경우 (약 10행 이하)

```sql
-- Full Table Scan 유발 예시
SELECT * FROM orders WHERE YEAR(created_at) = 2024;  -- 컬럼 변형
SELECT * FROM users WHERE name LIKE '%kim';           -- 앞 와일드카드
SELECT * FROM products WHERE status != 'active';     -- 부정 조건
```

#### Full Table Scan이 오히려 유리한 경우
- 테이블이 매우 작아서 인덱스 조회 오버헤드보다 전체 스캔이 빠를 때
- 결과 행이 전체 테이블의 대부분을 차지할 때 (인덱스 랜덤 I/O보다 순차 I/O가 빠름)
- InnoDB Buffer Pool에 데이터가 이미 캐시되어 있을 때

---

### 2. Index Range Scan

#### 동작 원리
Index Range Scan은 인덱스의 특정 범위만을 읽는 방식이다. B+Tree 인덱스에서 범위의 시작 지점을 찾은 후, 리프 노드의 링크드 리스트를 따라 범위 끝까지 순차적으로 읽는다. EXPLAIN 결과의 `type` 컬럼에 `range`로 표시된다.

#### B+Tree에서 Range Scan이 수행되는 과정
1. 루트 노드에서 시작해 범위 조건의 시작값에 해당하는 리프 노드를 탐색
2. 리프 노드에서 조건에 맞는 첫 번째 키를 찾음
3. 리프 노드들이 링크드 리스트로 연결되어 있으므로, 다음 리프 노드로 이동하며 범위 끝까지 순차 탐색
4. 각 인덱스 엔트리에 저장된 PK 값(또는 레코드 포인터)으로 실제 데이터 페이지에 접근

#### 관련 연산자
`BETWEEN`, `>`, `<`, `>=`, `<=`, `IN`, `LIKE 'prefix%'`

```sql
-- Index Range Scan 예시
SELECT * FROM orders WHERE created_at BETWEEN '2024-01-01' AND '2024-12-31';
SELECT * FROM products WHERE price > 10000 AND price < 50000;
SELECT * FROM users WHERE name LIKE 'kim%';  -- 앞 고정 LIKE는 range 사용 가능
SELECT * FROM orders WHERE status IN ('PENDING', 'PROCESSING');
```

#### 특징
- Full Table Scan보다 효율적이지만, Index Unique Scan보다는 느림
- 범위가 넓을수록 성능이 저하됨
- 복합 인덱스에서는 선행 컬럼에 범위 조건이 오면 후행 컬럼은 인덱스를 활용하지 못함

---

### 3. Index Full Scan

#### 동작 원리
Index Full Scan은 인덱스 전체를 처음부터 끝까지 읽는 방식이다. 테이블 데이터 파일 대신 인덱스 파일 전체를 읽는다. EXPLAIN 결과의 `type` 컬럼에 `index`로 표시된다.

#### Full Table Scan과의 차이점
| 구분 | Full Table Scan | Index Full Scan |
|------|----------------|-----------------|
| 읽는 대상 | 테이블 데이터 파일 전체 | 인덱스 파일 전체 |
| I/O 방식 | 순차 I/O (큰 데이터 블록) | 순차 I/O (작은 인덱스 블록) |
| 크기 | 크다 | 테이블보다 작다 |
| EXPLAIN type | `ALL` | `index` |

인덱스 파일이 테이블 데이터 파일보다 훨씬 작기 때문에 일반적으로 Full Table Scan보다는 빠르다. 그러나 테이블의 모든 행을 읽어야 하는 경우라면 성능 차이는 크지 않을 수 있다.

#### Covering Index와의 관계
Index Full Scan이 효율적으로 동작하는 핵심 조건은 **Covering Index**다. 쿼리에서 필요한 모든 컬럼이 인덱스에 포함되어 있으면 실제 데이터 파일에 접근할 필요 없이 인덱스만으로 결과를 반환할 수 있다.

```sql
-- idx(name, email) 인덱스 존재
-- Covering Index로 Index Full Scan → 테이블 접근 불필요
SELECT name, email FROM users ORDER BY name;

-- name 컬럼만 인덱스에 있을 때
-- Index Full Scan 후 테이블 접근 필요 (Extra: Using index → 없어짐)
SELECT name, phone FROM users ORDER BY name;
```

EXPLAIN의 `Extra` 컬럼에 `Using index`가 표시되면 Covering Index로 처리된 것이다.

---

### 4. Index Unique Scan

#### 동작 원리
Index Unique Scan은 유니크 인덱스(UNIQUE INDEX 또는 PRIMARY KEY)에서 단건 조회를 수행하는 방식이다. 인덱스에서 일치하는 값을 찾으면 즉시 탐색을 중단한다. EXPLAIN 결과의 `type` 컬럼에 `const` 또는 `eq_ref`로 표시된다.

- `const`: 조인 없이 단일 테이블에서 PK나 UNIQUE 인덱스로 단건 조회 시
- `eq_ref`: 조인 쿼리에서 드리빈 테이블의 PK나 UNIQUE NOT NULL 인덱스로 단건 조회 시

#### Primary Key 조회 시 동작 방식
InnoDB에서 Primary Key는 클러스터드 인덱스(Clustered Index)로 구성된다. PK로 조회하면 B+Tree를 따라 리프 노드에 도달하고, 리프 노드에 실제 데이터가 저장되어 있어 별도의 테이블 접근 없이 바로 데이터를 가져올 수 있다.

```sql
-- const: 단일 테이블 PK 조회
SELECT * FROM users WHERE id = 1;

-- const: UNIQUE 인덱스 조회
SELECT * FROM users WHERE email = 'test@example.com';  -- email에 UNIQUE 인덱스 존재

-- eq_ref: 조인에서 드리빈 테이블의 PK 조회
SELECT u.name, o.amount
FROM orders o
JOIN users u ON u.id = o.user_id;  -- u.id는 PK
```

#### 특징
- 가장 빠른 인덱스 스캔 방식 (단 한 건의 레코드만 읽음)
- WHERE 절의 조건이 유니크 인덱스의 모든 컬럼에 대해 `=` 비교여야 함
- 복합 유니크 인덱스는 모든 구성 컬럼에 `=` 조건이 있어야 `const`/`eq_ref` 적용

---

### 5. Index Skip Scan (MySQL 8.0+)

#### 동작 원리
Index Skip Scan은 MySQL 8.0.13부터 도입된 최적화 방식이다. 복합 인덱스에서 선행(prefix) 컬럼에 조건이 없어도 후행 컬럼의 조건만으로 인덱스를 활용할 수 있게 한다.

옵티마이저가 선행 컬럼의 고유값(distinct value) 목록을 구한 다음, 각 고유값에 대해 별도의 Range Scan을 수행한다. 마치 여러 번의 Index Range Scan을 이어 붙이는 방식이다.

```
인덱스: (gender, age)
쿼리: SELECT * FROM users WHERE age > 20;

// Skip Scan 내부 동작
// 1. gender의 고유값 목록: ['M', 'F']
// 2. gender = 'M' AND age > 20  → Range Scan
// 3. gender = 'F' AND age > 20  → Range Scan
// 결과를 합쳐서 반환
```

#### EXPLAIN에서 확인
`Extra` 컬럼에 `Using index for skip scan`이 표시된다.

```sql
CREATE TABLE t1 (f1 INT NOT NULL, f2 INT NOT NULL, PRIMARY KEY(f1, f2));

-- f1 조건 없이 f2만으로 조회 → Skip Scan 가능
EXPLAIN SELECT f1, f2 FROM t1 WHERE f2 > 40;
-- Extra: Using index for skip scan
```

#### Skip Scan이 활성화되는 조건
- 복합 인덱스 구성: `[A...,] B..., C [, D...]`에서 A에 조건 없이 C에 범위 조건이 있는 경우
- 단일 테이블 쿼리
- `GROUP BY`, `DISTINCT` 없음
- 쿼리에서 참조하는 컬럼이 모두 인덱스에 포함 (Covering Index 형태)
- 선행 컬럼의 카디널리티가 낮을수록 유리 (고유값이 적을수록 반복 횟수가 줄어듦)
- `optimizer_switch`의 `skip_scan` 플래그가 `on` (기본값)

```sql
-- Skip Scan 비활성화/활성화
SET optimizer_switch = 'skip_scan=off';
SET optimizer_switch = 'skip_scan=on';
```

#### 기존 방식 대비 성능 개선 효과
- 기존: 선행 컬럼 조건 없으면 Index Full Scan 또는 Full Table Scan 발생
- Skip Scan 도입 후: 선행 컬럼의 카디널리티가 낮으면 Index Range Scan에 준하는 성능 달성
- 단, 선행 컬럼의 고유값이 많아질수록 Range Scan 반복 횟수가 증가해 오히려 비효율적일 수 있음

---

### 6. Loose Index Scan vs Tight Index Scan

두 방식 모두 `GROUP BY` 최적화 시 MySQL 옵티마이저가 선택하는 인덱스 스캔 전략이다.

#### Loose Index Scan (느슨한 인덱스 스캔)

인덱스의 정렬된 특성을 활용하여 각 그룹에서 필요한 키 값만 선택적으로 읽는 방식이다. 인덱스 전체를 읽지 않고 각 그룹의 첫 번째(또는 마지막) 키만 읽어 그룹 결과를 만든다.

EXPLAIN의 `Extra` 컬럼에 `Using index for group-by`로 표시된다.

**활성화 조건 (모두 충족해야 함)**
- 단일 테이블 쿼리
- `GROUP BY` 컬럼이 인덱스의 최좌측 prefix를 형성
- 집계 함수는 `MIN()` 또는 `MAX()`만 허용하며, 같은 컬럼을 참조
- 집계 함수의 인자는 `GROUP BY` 컬럼 바로 다음 인덱스 컬럼
- 인덱스는 prefix 인덱스가 아닌 전체 컬럼값 포함

```sql
-- 인덱스: idx(c1, c2, c3)
-- Loose Index Scan 가능
SELECT c1, c2 FROM t1 GROUP BY c1, c2;
SELECT c1, MIN(c2) FROM t1 GROUP BY c1;
SELECT MAX(c3), MIN(c3), c1, c2 FROM t1 WHERE c2 > 10 GROUP BY c1, c2;

-- Loose Index Scan 불가
SELECT c1, SUM(c2) FROM t1 GROUP BY c1;   -- SUM() 사용 불가
SELECT c1, c2 FROM t1 GROUP BY c2, c3;    -- 최좌측 prefix 아님
```

#### Tight Index Scan (타이트 인덱스 스캔)

Loose Index Scan 조건을 충족하지 못하지만, WHERE 조건으로 인덱스의 "갭"을 상수값으로 채워 인덱스 prefix를 완성할 수 있을 때 사용하는 방식이다. 범위 조건에 해당하는 모든 인덱스 키를 읽은 후 그룹핑을 수행한다.

EXPLAIN의 `Extra` 컬럼에 `Using index`로 표시된다.

```sql
-- 인덱스: idx(c1, c2, c3)
-- Tight Index Scan 가능 (Loose는 불가)

-- c2 갭을 상수 조건으로 채워 (c1, c3) GROUP BY 가능
SELECT c1, c3 FROM t1 WHERE c2 = 'a' GROUP BY c1, c3;

-- c1을 상수 조건으로 제공해 (c2, c3) GROUP BY 가능
SELECT c2, c3 FROM t1 WHERE c1 = 'x' GROUP BY c2, c3;
```

#### 비교 요약

| 구분 | Loose Index Scan | Tight Index Scan |
|------|-----------------|-----------------|
| 읽는 키 수 | 각 그룹의 첫/마지막 키만 | 범위 내 모든 키 |
| 효율 | 매우 높음 | 중간 |
| 집계 함수 | `MIN()`, `MAX()`만 가능 | 제약 없음 |
| EXPLAIN Extra | `Using index for group-by` | `Using index` |
| 활성화 난이도 | 조건이 엄격함 | 상대적으로 유연 |

---

### 7. EXPLAIN으로 scan 타입 확인하기

EXPLAIN 명령어를 실행하면 옵티마이저가 선택한 실행 계획을 확인할 수 있다. 이 중 `type` 컬럼은 각 테이블에 어떤 방식으로 접근하는지를 나타낸다.

#### EXPLAIN type 컬럼 성능 순서 (빠름 → 느림)

```
system > const > eq_ref > ref > range > index > ALL
```

| type | 설명 | 발생 조건 |
|------|------|----------|
| `system` | 테이블에 행이 1개 | 시스템 테이블, MyISAM/Memory 엔진의 단일 행 테이블 |
| `const` | 최대 1건 반환, 상수처럼 처리 | PK 또는 UNIQUE 인덱스의 모든 컬럼에 `=` 조건 (단일 테이블) |
| `eq_ref` | 조인 시 1건 반환 | 조인의 드리빈 테이블에서 PK 또는 UNIQUE NOT NULL 인덱스로 단건 조회 |
| `ref` | 인덱스 동등 비교, 복수 행 가능 | 비유니크 인덱스 또는 복합 인덱스의 일부 컬럼에 `=` 조건 |
| `range` | 인덱스 범위 스캔 | `BETWEEN`, `>`, `<`, `IN`, `LIKE 'prefix%'` 등 |
| `index` | 인덱스 전체 스캔 | 인덱스만으로 쿼리 처리 가능하나 전체를 읽어야 할 때 |
| `ALL` | 테이블 전체 스캔 | 사용 가능한 인덱스 없거나 옵티마이저가 Full Scan 선택 |

#### 실전 EXPLAIN 예시

```sql
EXPLAIN SELECT * FROM users WHERE id = 1;
-- type: const (PK 조회)

EXPLAIN SELECT u.name FROM orders o JOIN users u ON u.id = o.user_id;
-- type: eq_ref (조인 시 PK 조회)

EXPLAIN SELECT * FROM users WHERE email = 'test@example.com';
-- type: const (UNIQUE 인덱스) 또는 ref (비유니크 인덱스)

EXPLAIN SELECT * FROM orders WHERE created_at BETWEEN '2024-01-01' AND '2024-12-31';
-- type: range

EXPLAIN SELECT name FROM users ORDER BY name;
-- type: index (name 인덱스 존재 시 Covering Index)

EXPLAIN SELECT * FROM users WHERE memo LIKE '%keyword%';
-- type: ALL (앞 와일드카드 LIKE → 인덱스 미사용)
```

#### Extra 컬럼 주요 값
| Extra 값 | 의미 |
|----------|------|
| `Using index` | Covering Index로 처리 (테이블 미접근) |
| `Using where` | 인덱스 이후 WHERE 조건으로 추가 필터링 |
| `Using filesort` | 결과를 정렬하기 위한 추가 정렬 작업 발생 |
| `Using temporary` | 임시 테이블 사용 (GROUP BY, DISTINCT 등) |
| `Using index for group-by` | Loose Index Scan으로 GROUP BY 처리 |
| `Using index for skip scan` | Index Skip Scan으로 처리 |

---

## 핵심 정리
- `EXPLAIN`의 `type` 컬럼은 `system > const > eq_ref > ref > range > index > ALL` 순으로 성능이 좋으며, `range` 이상이면 인덱스를 활용하고 있다고 판단할 수 있다. `ALL`과 `index`는 전체 스캔이므로 쿼리 튜닝의 첫 번째 타깃이 된다.
- Full Table Scan이 항상 나쁜 것은 아니다. 전체 데이터의 20~30% 이상을 읽어야 하거나 테이블이 작으면 옵티마이저가 인덱스 대신 Full Table Scan을 선택하는 것이 더 효율적일 수 있다.
- 복합 인덱스를 설계할 때는 카디널리티가 높은 컬럼을 선행에 두는 것이 일반적이지만, `GROUP BY` 최적화(Loose Index Scan)나 Index Skip Scan을 활용하려면 쿼리 패턴에 따라 컬럼 순서 전략이 달라질 수 있다.
- `SELECT` 컬럼을 인덱스 구성 컬럼으로만 제한하면 Covering Index로 처리되어 테이블 접근 없이 인덱스만으로 쿼리가 완결된다. EXPLAIN Extra에서 `Using index`로 확인할 수 있으며, 성능 개선 효과가 크다.
- Index Skip Scan은 선행 컬럼의 카디널리티가 낮을 때 효과적이다. 선행 컬럼의 고유값이 많아질수록 내부적으로 더 많은 Range Scan을 반복하므로 오히려 비효율적일 수 있다.

## 키워드
- `Full Table Scan`: 인덱스를 사용하지 않고 테이블의 모든 행을 처음부터 끝까지 읽는 방식. EXPLAIN type `ALL`
- `Index Range Scan`: B+Tree 인덱스에서 특정 범위에 해당하는 키만 선택적으로 읽는 방식. EXPLAIN type `range`
- `Index Full Scan`: 인덱스 파일 전체를 처음부터 끝까지 읽는 방식. Full Table Scan보다 빠르나 전체를 읽는다는 점은 동일. EXPLAIN type `index`
- `Index Unique Scan`: 유니크 인덱스에서 단건을 조회하는 가장 빠른 인덱스 스캔 방식. EXPLAIN type `const` 또는 `eq_ref`
- `Index Skip Scan`: MySQL 8.0.13+에서 도입. 복합 인덱스의 선행 컬럼 조건 없이 후행 컬럼 조건만으로 인덱스를 활용하는 방식
- `Covering Index`: 쿼리에 필요한 모든 컬럼이 인덱스에 포함되어 실제 테이블 접근 없이 인덱스만으로 결과를 반환하는 인덱스. EXPLAIN Extra에 `Using index`로 표시
- `EXPLAIN`: MySQL 쿼리의 실행 계획을 분석하는 명령어. `type`, `key`, `rows`, `Extra` 컬럼을 중심으로 분석
- `B+Tree`: MySQL InnoDB의 기본 인덱스 자료구조. 내부 노드는 키만, 리프 노드는 키와 데이터(또는 포인터)를 저장하며, 리프 노드들이 링크드 리스트로 연결되어 Range Scan에 유리
- `MySQL Optimizer`: 주어진 SQL을 가장 효율적으로 실행하기 위한 실행 계획을 수립하는 MySQL 내부 컴포넌트. 비용 기반(Cost-Based) 방식으로 동작
- `Query Execution Plan`: 옵티마이저가 SQL을 어떻게 실행할지 결정한 계획. EXPLAIN 명령어로 확인 가능

## 참고 자료
- [MySQL 8.0 공식 문서 - Avoiding Full Table Scans](https://dev.mysql.com/doc/refman/8.0/en/table-scan-avoidance.html)
- [MySQL 8.0 공식 문서 - Range Optimization (Index Skip Scan 포함)](https://dev.mysql.com/doc/refman/8.0/en/range-optimization.html)
- [MySQL 8.0 공식 문서 - GROUP BY Optimization (Loose/Tight Index Scan)](https://dev.mysql.com/doc/refman/8.0/en/group-by-optimization.html)
- [MySQL 8.0 공식 문서 - EXPLAIN Output Format](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html)
