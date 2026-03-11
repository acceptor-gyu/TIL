# MySQL Optimizer Condition Pushdown

## 개요
MySQL 옵티마이저가 WHERE 조건을 스토리지 엔진 레벨로 밀어내려(push down) 불필요한 행의 읽기를 줄이는 최적화 기법에 대해 학습한다.

## 상세 내용

### 1. Condition Pushdown이란

MySQL의 쿼리 처리 구조는 크게 두 개의 레이어로 나뉜다.

```
┌──────────────────────────────────────────────┐
│             MySQL Server Layer               │
│  (파서 → 옵티마이저 → 실행기 → 결과 반환)            │
└──────────────────────┬───────────────────────┘
                       │  Handler API
┌──────────────────────▼───────────────────────┐
│          Storage Engine Layer                │
│       (InnoDB, MyISAM, NDB 등)                │
│    (실제 데이터 읽기 / 인덱스 탐색 수행)             │
└──────────────────────────────────────────────┘
```

**Server Layer**는 SQL 파싱, 옵티마이저, 실행 계획 수립을 담당하고, **Storage Engine Layer**는 실제 디스크에서 데이터를 읽고 인덱스를 탐색하는 역할을 한다.

**Condition Pushdown**이란 원래 Server Layer에서 수행하던 WHERE 조건 평가를 Storage Engine Layer로 "밀어 내려" 불필요한 행을 더 이른 단계에서 걸러내는 최적화 기법이다.

**MySQL 5.6 이전 방식 (ICP 없음)**
1. 스토리지 엔진이 인덱스를 탐색해 조건의 일부(인덱스 선두 컬럼)만 활용
2. 조건을 만족하는지 모르지만 일단 풀 로우(full row)를 Server Layer에 반환
3. Server Layer에서 나머지 WHERE 조건을 평가하고 불필요한 행 제거

**MySQL 5.6 이후 방식 (ICP 적용)**
1. 스토리지 엔진이 인덱스를 탐색
2. **인덱스에 포함된 컬럼으로 평가 가능한 WHERE 조건을 엔진 레벨에서 먼저 평가**
3. 조건을 만족하는 행만 풀 로우를 읽고 Server Layer에 반환

결과적으로 스토리지 엔진이 Server Layer에 반환하는 행 수가 줄어들고, 불필요한 디스크 I/O가 감소한다.

---

### 2. ICP(Index Condition Pushdown) 동작 원리

#### 인덱스 컬럼 조건이 스토리지 엔진으로 전달되는 과정

아래와 같은 테이블과 인덱스가 있다고 가정한다.

```sql
CREATE TABLE people (
    id       INT PRIMARY KEY,
    zipcode  CHAR(5),
    lastname VARCHAR(50),
    address  VARCHAR(100),
    INDEX idx_zip_name (zipcode, lastname)
);

-- 쿼리
SELECT * FROM people
WHERE zipcode = '95054'
  AND lastname LIKE '%etrunia%'
  AND address  LIKE '%Main Street%';
```

인덱스 `idx_zip_name`은 `(zipcode, lastname)` 복합 인덱스다.

**ICP 없이 처리하는 흐름**

```
스토리지 엔진:
  1. idx_zip_name에서 zipcode = '95054' 조건으로 인덱스 범위 스캔
  2. 매칭되는 모든 인덱스 항목에 대해 클러스터 인덱스(PK)를 통해 풀 로우 읽기
  3. 읽은 풀 로우를 Server Layer로 반환

Server Layer:
  4. lastname LIKE '%etrunia%' 조건 평가 → 불일치하면 버림
  5. address LIKE '%Main Street%' 조건 평가 → 불일치하면 버림
```

zipcode='95054'인 행이 10,000개라면 10,000번의 클러스터 인덱스 룩업이 발생한다.

**ICP 적용 시 처리하는 흐름**

```
스토리지 엔진:
  1. idx_zip_name에서 zipcode = '95054' 조건으로 인덱스 범위 스캔
  2. 인덱스 항목(zipcode + lastname)에서 lastname LIKE '%etrunia%' 조건 평가
     → 불일치하면 풀 로우 읽기 없이 다음 인덱스 항목으로 이동
  3. 조건 만족 시에만 클러스터 인덱스 룩업 수행 (풀 로우 읽기)
  4. 읽은 풀 로우를 Server Layer로 반환

Server Layer:
  5. address LIKE '%Main Street%' 조건 평가 (인덱스에 없는 컬럼은 여기서 평가)
```

lastname 조건을 인덱스 레벨에서 먼저 평가함으로써, 클러스터 인덱스 룩업 횟수가 대폭 줄어든다.

#### Handler API를 통한 Server-Engine 간 통신 흐름

MySQL Server와 스토리지 엔진은 **Handler API**를 통해 통신한다. 옵티마이저는 ICP 사용을 결정하면, `handler::pushed_idx_cond` 필드를 통해 평가할 조건(Item 트리)을 스토리지 엔진에 전달한다. 스토리지 엔진은 인덱스 항목을 읽을 때마다 이 조건을 평가하고, 만족하지 않으면 Server Layer에 행을 반환하지 않는다.

```
옵티마이저 → handler->pushed_idx_cond 설정 → InnoDB
                                               ↓
                              인덱스 스캔 중 조건 평가
                                               ↓
                              조건 불만족 → 다음 인덱스 항목
                              조건 만족  → 풀 로우 읽기 후 반환
```

---

### 3. ICP 적용 조건과 제약사항

#### ICP가 적용되는 조건

| 항목 | 설명 |
|------|------|
| **접근 방법** | `range`, `ref`, `eq_ref`, `ref_or_null` 접근 방식일 때 |
| **스토리지 엔진** | InnoDB, MyISAM (파티션 테이블 포함) |
| **인덱스 타입** | B-Tree 인덱스 (복합 인덱스 포함) |
| **InnoDB 특이사항** | 보조 인덱스(Secondary Index)에만 적용, 클러스터 인덱스(PK)에는 미적용 |

ICP가 클러스터 인덱스에 적용되지 않는 이유는, 클러스터 인덱스는 인덱스 자체가 풀 로우를 포함하고 있어 별도의 테이블 룩업이 없기 때문이다.

#### ICP가 적용되지 않는 경우

```sql
-- (1) 가상 생성 컬럼의 보조 인덱스
CREATE TABLE t (
    id   INT PRIMARY KEY,
    json_col JSON,
    v_col INT GENERATED ALWAYS AS (json_col->'$.value') VIRTUAL,
    INDEX (v_col)
);
-- v_col에 대한 인덱스에는 ICP 미적용

-- (2) 조건에 서브쿼리 포함
WHERE col1 = (SELECT MAX(col1) FROM t2)

-- (3) 조건에 스토어드 함수 포함
WHERE my_func(col1) = 'value'

-- (4) 트리거된 조건 (Triggered condition)
-- 내부적으로 옵티마이저가 생성하는 특정 조건 유형

-- (5) 풀 텍스트 인덱스 (FULLTEXT INDEX)
```

#### optimizer_switch 설정

ICP는 기본적으로 활성화되어 있으며, 필요 시 비활성화할 수 있다.

```sql
-- ICP 비활성화 (글로벌)
SET GLOBAL optimizer_switch = 'index_condition_pushdown=off';

-- ICP 비활성화 (세션)
SET SESSION optimizer_switch = 'index_condition_pushdown=off';

-- ICP 활성화 (기본값)
SET SESSION optimizer_switch = 'index_condition_pushdown=on';

-- 현재 설정 확인
SHOW VARIABLES LIKE 'optimizer_switch'\G
```

---

### 4. EXPLAIN으로 확인하기

#### `Using index condition` 표시의 의미

`EXPLAIN` 실행 계획의 `Extra` 컬럼에 `Using index condition`이 표시되면 ICP가 적용되었음을 의미한다.

```sql
EXPLAIN SELECT * FROM people
WHERE zipcode = '95054'
  AND lastname LIKE '%etrunia%'
  AND address  LIKE '%Main Street%'\G

-- 출력 예시
*************************** 1. row ***************************
           id: 1
  select_type: SIMPLE
        table: people
   partitions: NULL
         type: range
possible_keys: idx_zip_name
          key: idx_zip_name
      key_len: 207
          ref: NULL
         rows: 100
     filtered: 10.00
        Extra: Using index condition; Using where
```

#### `Using where`와 `Using index condition`의 차이

| Extra 표시 | 의미 | 조건 평가 위치 |
|-----------|------|--------------|
| `Using where` | WHERE 조건 평가가 Server Layer에서 수행됨 | Server Layer |
| `Using index condition` | ICP 적용으로 일부 WHERE 조건이 Storage Engine에서 평가됨 | Storage Engine Layer |
| `Using index` | 인덱스만으로 쿼리를 처리(Covering Index) | 풀 로우 접근 없음 |

두 항목이 함께 표시되는 경우(`Using index condition; Using where`)는 인덱스 컬럼으로 평가 가능한 조건은 스토리지 엔진에서 처리하고, 인덱스에 없는 컬럼(예: `address`)의 조건은 Server Layer에서 추가로 필터링한다는 의미다.

#### EXPLAIN ANALYZE로 실제 비용 확인 (MySQL 8.0+)

```sql
EXPLAIN ANALYZE SELECT * FROM people
WHERE zipcode = '95054'
  AND lastname LIKE '%etrunia%'\G

-- 출력 예시
-> Filter: (people.address like '%Main Street%')
     (cost=12.50 rows=10) (actual time=0.523..1.203 rows=3 loops=1)
   -> Index range scan on people using idx_zip_name
        with pushed condition: (people.lastname like '%etrunia%')
        (cost=10.00 rows=100) (actual time=0.201..1.089 rows=8 loops=1)
```

"pushed condition"이라는 표현에서 ICP가 실제로 동작하고 있음을 확인할 수 있다.

---

### 5. Derived Table Condition Pushdown

#### MySQL 8.0.22+에서 도입된 파생 테이블 조건 푸시다운

파생 테이블(Derived Table)이란 FROM 절에 사용된 서브쿼리나 뷰로 생성된 임시 결과 집합이다. MySQL 8.0.22 이전에는 외부 쿼리의 WHERE 조건이 파생 테이블 내부로 전달되지 않아 불필요하게 많은 행을 처리했다.

MySQL 8.0.22부터는 외부 WHERE 조건을 파생 테이블 내부로 푸시다운하는 최적화가 도입되었다.

#### 시나리오 1: 단순 파생 테이블

```sql
-- 원래 쿼리
SELECT * FROM (SELECT i, j FROM t1) AS dt
WHERE i > 10;

-- 최적화 후 내부 처리
SELECT * FROM (SELECT i, j FROM t1 WHERE i > 10) AS dt;
```

#### 시나리오 2: GROUP BY + 집계 함수 (비GROUP BY 컬럼 조건)

GROUP BY에 포함되지 않은 컬럼의 외부 조건은 HAVING으로 변환되어 파생 테이블 내부로 푸시된다.

```sql
-- 원래 쿼리
SELECT * FROM (
    SELECT i, j, SUM(k) AS sum FROM t1
    GROUP BY i, j
) AS dt
WHERE sum > 100;

-- 최적화 후 내부 처리
SELECT * FROM (
    SELECT i, j, SUM(k) AS sum FROM t1
    GROUP BY i, j
    HAVING sum > 100        -- HAVING으로 변환
) AS dt;
```

#### 시나리오 3: GROUP BY + 혼합 조건 (GROUP BY 컬럼 + 집계 컬럼)

```sql
-- 원래 쿼리
SELECT * FROM (
    SELECT i, j, SUM(k) AS sum FROM t1
    GROUP BY i, j
) AS dt
WHERE i > 10 AND sum > 100;

-- 최적화 후 내부 처리
SELECT * FROM (
    SELECT i, j, SUM(k) AS sum FROM t1
    WHERE i > 10            -- GROUP BY 컬럼 조건은 WHERE로
    GROUP BY i, j
    HAVING sum > 100        -- 집계 컬럼 조건은 HAVING으로
) AS dt;
```

#### Materialized vs Merged 파생 테이블에서의 차이

| 구분 | Merged | Materialized |
|------|--------|-------------|
| **처리 방식** | 파생 테이블을 외부 쿼리와 합쳐 하나의 쿼리로 처리 | 파생 테이블을 임시 테이블에 구체화 후 외부 쿼리 실행 |
| **Condition Pushdown 의미** | Merge 자체가 조건을 내부로 전달 (pushdown과 동일 효과) | 외부 조건을 임시 테이블 생성 전에 내부 쿼리에 적용 |
| **적용 가능 시점** | 집계 없는 단순 서브쿼리 | GROUP BY, DISTINCT, 집계 함수 등 포함 시 |

#### optimizer_switch와 힌트

```sql
-- 기본값: ON
SET optimizer_switch = 'derived_condition_pushdown=on';

-- 특정 쿼리에서만 비활성화
SELECT /*+ NO_DERIVED_CONDITION_PUSHDOWN() */ *
FROM (SELECT i, j FROM t1) AS dt
WHERE i > 10;

-- 특정 쿼리에서만 활성화 (비활성 상태일 때)
SELECT /*+ DERIVED_CONDITION_PUSHDOWN() */ *
FROM (SELECT i, j FROM t1) AS dt
WHERE i > 10;
```

#### Derived Table Condition Pushdown 제약사항

| 제약사항 | 설명 |
|---------|------|
| UNION 포함 파생 테이블 | 8.0.29 이전에는 불가, 8.0.29+부터 재귀 CTE 제외 가능 |
| LIMIT 절 포함 | 파생 테이블에 LIMIT가 있으면 불가 |
| 조건에 서브쿼리 포함 | 푸시 불가 |
| OUTER JOIN의 이너 테이블 | 불가 |
| 여러 번 참조되는 CTE | 불가 |
| 비결정적 표현식 | RAND() 등 포함 시 불가 |

---

### 6. 성능 비교와 실무 활용

#### ICP 적용 전후 I/O 횟수 비교

`zipcode = '95054'`인 행이 10,000개이고 그 중 `lastname LIKE '%etrunia%'`를 만족하는 행이 100개라고 가정한다.

| 항목 | ICP 미적용 | ICP 적용 |
|------|-----------|---------|
| 인덱스 스캔 횟수 | 10,000 | 10,000 |
| 클러스터 인덱스 룩업 횟수 | 10,000 | 100 |
| Server Layer로 전달된 행 수 | 10,000 | 100 |
| 디스크 랜덤 I/O (예상) | 매우 높음 | 1/100로 감소 |

클러스터 인덱스 룩업은 디스크 랜덤 I/O를 유발하기 때문에, ICP를 통한 룩업 횟수 감소는 실질적인 성능 향상으로 이어진다.

#### 복합 인덱스 설계 시 ICP를 고려한 컬럼 순서 전략

B-Tree 인덱스는 leftmost prefix rule을 따른다. 인덱스 `(A, B, C)`가 있을 때 `A = 1 AND B > 10 AND C = 5` 쿼리를 처리하는 경우:

- `A = 1`: 인덱스 탐색에 사용
- `B > 10`: 범위 조건으로 인덱스 범위 스캔
- `C = 5`: 범위 조건 이후는 인덱스 탐색으로 활용 불가, **하지만 ICP로 인덱스 레벨에서 평가 가능**

```sql
-- 인덱스: (zipcode, lastname, firstname)
-- 아래 쿼리에서 ICP가 lastname 조건을 인덱스 레벨에서 평가
SELECT * FROM people
WHERE zipcode = '95054'
  AND lastname LIKE 'A%'    -- ICP로 인덱스에서 평가
  AND firstname = 'John';   -- ICP로 인덱스에서 평가 (lastname과 함께)
```

**복합 인덱스 설계 원칙 (ICP 관점)**
1. **등치 조건 컬럼을 앞에** 배치: 인덱스 탐색 효율 극대화
2. **범위 조건 컬럼을 뒤에** 배치: 범위 이후 컬럼도 ICP로 활용 가능
3. **자주 사용되는 필터 컬럼을 인덱스에 포함**: 인덱스 레벨에서 평가 가능하도록

```sql
-- 비효율적 인덱스 구성 예시
INDEX (address, zipcode, lastname)
-- address는 선택도가 낮고 LIKE '%..%' 패턴이 많아 인덱스 활용 어려움

-- 효율적 인덱스 구성 예시
INDEX (zipcode, lastname, address)
-- zipcode(등치) → lastname(범위/LIKE) → address(ICP로 보조 평가)
```

#### NDB Cluster에서의 Condition Pushdown 특이사항

NDB Cluster는 ICP 외에도 **Engine Condition Pushdown (ECP)** 이라는 별도의 최적화를 지원한다.

| 항목 | ICP (InnoDB/MyISAM) | ECP (NDB Cluster) |
|------|--------------------|--------------------|
| 대상 컬럼 | 인덱스 컬럼 | 비인덱스 컬럼도 포함 |
| 주요 목적 | 디스크 I/O 감소 | 네트워크 전송 데이터 감소 |
| 성능 개선 효과 | 중간~높음 | 최대 5~10배 향상 |

NDB는 분산 아키텍처로 MySQL 서버와 데이터 노드 간 네트워크 통신이 발생하기 때문에, 조건을 데이터 노드에서 평가하여 불필요한 행을 네트워크로 전송하지 않는 것이 훨씬 큰 성능 효과를 낸다.

```sql
-- NDB Engine Condition Pushdown 예시
CREATE TABLE t1 (a INT, b INT, KEY(a)) ENGINE=NDB;

EXPLAIN SELECT a, b FROM t1 WHERE b = 10\G
-- Extra: Using where with pushed condition (`t1`.`b` = 10)
--        b는 인덱스 컬럼이 아니지만 NDB 데이터 노드에서 평가됨
```

---

## 핵심 정리
- **Condition Pushdown**은 WHERE 조건 평가를 Server Layer 대신 Storage Engine Layer에서 수행하여 불필요한 행 읽기를 줄이는 최적화다.
- **ICP(Index Condition Pushdown)**는 MySQL 5.6부터 도입되었으며 B-Tree 인덱스 스캔 시 인덱스에 포함된 컬럼의 조건을 스토리지 엔진 레벨에서 평가한다.
- ICP는 클러스터 인덱스 룩업(풀 로우 읽기) 횟수를 줄여 랜덤 I/O를 감소시키고, 이는 특히 선택도가 낮은 인덱스 컬럼에서 큰 효과를 발휘한다.
- `EXPLAIN`의 `Extra` 컬럼에서 `Using index condition`이 보이면 ICP가 적용된 것이다.
- 복합 인덱스를 설계할 때 등치 조건 컬럼을 앞에, 범위 조건 컬럼을 뒤에 배치하면 ICP 효과를 극대화할 수 있다.
- **Derived Table Condition Pushdown**은 MySQL 8.0.22+에서 지원되며, 파생 테이블 외부의 WHERE 조건을 파생 테이블 내부의 WHERE 또는 HAVING으로 변환해 처리 행 수를 줄인다.
- NDB Cluster는 ICP 외에도 ECP(Engine Condition Pushdown)를 지원하며, 네트워크 전송량을 줄여 최대 5~10배 성능 향상이 가능하다.

## 키워드
- `Condition Pushdown`: WHERE 조건 평가를 Storage Engine Layer로 밀어내려 불필요한 행 읽기를 줄이는 MySQL 옵티마이저 최적화 전략
- `ICP`: Index Condition Pushdown의 약자. 인덱스 스캔 중 인덱스 컬럼에 해당하는 조건을 스토리지 엔진이 평가하는 방식
- `Index Condition Pushdown`: MySQL 5.6에서 도입된 최적화로, 인덱스에 포함된 컬럼에 대한 WHERE 조건을 스토리지 엔진 레벨에서 평가해 클러스터 인덱스 룩업 횟수를 줄임
- `MySQL Optimizer`: SQL 쿼리를 가장 효율적으로 실행하기 위한 실행 계획을 수립하는 MySQL 내부 모듈
- `스토리지 엔진`: MySQL 데이터를 실제로 저장하고 읽는 레이어 (InnoDB, MyISAM, NDB 등). Handler API로 Server Layer와 통신
- `Using index condition`: EXPLAIN Extra 컬럼에 표시되는 값으로, ICP가 적용되어 조건 평가가 Storage Engine Layer에서 수행됨을 의미
- `EXPLAIN`: MySQL 쿼리의 실행 계획을 출력하는 명령어. type, key, Extra 등으로 옵티마이저의 선택을 확인
- `복합 인덱스`: 두 개 이상의 컬럼으로 구성된 인덱스. leftmost prefix rule을 따르며 ICP와 함께 사용 시 범위 조건 이후 컬럼도 인덱스 레벨에서 필터링 가능
- `Derived Table Pushdown`: MySQL 8.0.22+에서 지원. 파생 테이블 외부의 WHERE 조건을 내부 서브쿼리의 WHERE/HAVING으로 변환해 처리 행 수를 줄이는 최적화
- `optimizer_switch`: MySQL 옵티마이저의 개별 최적화 기능을 ON/OFF 할 수 있는 시스템 변수. `index_condition_pushdown`, `derived_condition_pushdown` 등이 포함됨

## 참고 자료
- [MySQL 8.0 Reference Manual - Index Condition Pushdown Optimization](https://dev.mysql.com/doc/refman/8.0/en/index-condition-pushdown-optimization.html)
- [MySQL 8.4 Reference Manual - Index Condition Pushdown Optimization](https://dev.mysql.com/doc/en/index-condition-pushdown-optimization.html)
- [MySQL 8.0 Reference Manual - Derived Condition Pushdown Optimization](https://dev.mysql.com/doc/refman/8.0/en/derived-condition-pushdown-optimization.html)
- [MySQL 8.0 Reference Manual - Engine Condition Pushdown Optimization (NDB)](https://dev.mysql.com/doc/refman/8.0/en/engine-condition-pushdown-optimization.html)
- [Percona Blog - Index Condition Pushdown in MySQL 5.6 and MariaDB 5.5](https://www.percona.com/blog/index-condition-pushdown-in-mysql-5-6-and-mariadb-5-5-and-its-performance-impact/)
- [SQL Authority - MySQL's Index Condition Pushdown (ICP) Optimization](https://blog.sqlauthority.com/2023/08/29/mysqls-index-condition-pushdown-icp-optimization/)
