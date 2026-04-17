# MySQL I/O 종류 (Random Access vs Sequential Access)

## 개요
MySQL에서 데이터를 읽고 쓸 때 발생하는 I/O의 종류와 Random Access, Sequential Access의 차이점 및 성능 특성을 학습한다.

## 상세 내용

### 1. 디스크 I/O의 기본 개념

**디스크 I/O란** 애플리케이션이 디스크(HDD, SSD)에서 데이터를 읽거나 쓰는 작업이다. MySQL은 데이터를 페이지(기본 16KB) 단위로 관리하며, Buffer Pool에 없는 페이지는 디스크에서 읽어야 한다.

**HDD vs SSD I/O 특성 차이**

| 특성 | HDD | SSD |
|------|-----|-----|
| Random Access | 매우 느림 (기계적 탐색) | 빠름 (전기적 접근) |
| Sequential Access | 빠름 (연속 읽기) | 빠름 (연속 읽기) |
| Seek Time | 수 ms (3~10ms) | 거의 0 (0.1ms 미만) |
| Random IOPS | 수백 | 수만~수십만 |

**IOPS (Input/Output Operations Per Second)**
초당 처리 가능한 I/O 요청 수를 의미한다. HDD는 일반적으로 100~200 IOPS, SSD는 10,000~500,000 IOPS 수준이다. Random Access가 많을수록 IOPS 한계가 병목이 되기 쉽다.

---

### 2. Random Access (랜덤 접근)

**정의**: 디스크의 임의 위치에 있는 데이터를 읽는 방식. 각 I/O 요청이 서로 다른 물리적 위치를 가리킨다.

**HDD에서 Random Access가 느린 이유**
- **Seek Time**: 디스크 헤드가 목표 트랙으로 이동하는 시간 (평균 3~10ms)
- **Rotational Latency**: 목표 섹터가 헤드 아래로 회전해 올 때까지 기다리는 시간 (평균 2~6ms)
- 즉, 요청 하나당 수 ms씩 소요되며, 초당 수백 건밖에 처리하지 못한다

**SSD에서 Random Access가 개선되는 이유**
- 기계적 이동이 없고 전기적으로 주소를 지정하므로 접근 시간이 0.1ms 미만
- 단, HDD 대비 크게 개선되었지만 Sequential Access보다는 여전히 느리다

**MySQL에서 Random Access가 발생하는 대표적인 상황**
- **Secondary Index를 통한 테이블 Lookup**: 세컨더리 인덱스 리프 노드에서 Primary Key를 얻고, 다시 클러스터드 인덱스를 통해 실제 행을 읽는 두 번의 B-Tree 탐색(Row Lookup)이 발생한다
- **Index Range Scan 후 데이터 페이지 접근**: 인덱스 순서와 실제 데이터 물리적 위치가 다를 때
- **Buffer Pool Miss 시 디스크 읽기**: 필요한 페이지가 Buffer Pool에 없으면 디스크에서 랜덤하게 읽어야 한다

---

### 3. Sequential Access (순차 접근)

**정의**: 디스크에서 연속된 위치의 데이터를 순서대로 읽는 방식. OS와 하드웨어 모두 이 패턴에 최적화되어 있다.

**Sequential Access가 빠른 이유**
- **OS Prefetch**: OS가 순차 읽기 패턴을 감지해 다음 블록을 미리 메모리에 올린다
- **디스크 내부 최적화**: HDD는 헤드 이동 없이 연속 섹터를 읽고, SSD도 내부 병렬 처리가 유리하다
- **InnoDB Read-Ahead**: 순차 패턴을 감지하면 다음 익스텐트(1MB = 64 페이지)를 비동기로 미리 로드한다

**MySQL에서 Sequential Access가 발생하는 대표적인 상황**
- **Full Table Scan**: 테이블의 모든 페이지를 순서대로 읽는다
- **Clustered Index (Primary Key) 순서대로 읽기**: PK 범위 스캔 시 데이터가 물리적으로 정렬된 순서로 저장되어 순차 접근이 가능하다
- **Redo Log, Undo Log 쓰기**: InnoDB의 로그 파일은 항상 Sequential Write로 기록된다

**InnoDB의 Read-Ahead 메커니즘**

InnoDB는 두 가지 Read-Ahead 방식을 제공한다.

| 방식 | 트리거 조건 | 설정 변수 | 기본값 |
|------|------------|----------|--------|
| Linear Read-Ahead | 현재 익스텐트에서 N개 이상 순차 접근 | `innodb_read_ahead_threshold` | 56 |
| Random Read-Ahead | 같은 익스텐트의 13개 이상 페이지가 이미 Buffer Pool에 존재 | `innodb_random_read_ahead` | OFF |

```sql
-- Linear Read-Ahead 임계값 조정 (낮출수록 더 공격적으로 프리페치)
SET GLOBAL innodb_read_ahead_threshold = 48;

-- Random Read-Ahead 활성화 (기본 OFF)
SET GLOBAL innodb_random_read_ahead = ON;
```

---

### 4. MySQL InnoDB에서의 I/O 최적화

**Buffer Pool과 I/O의 관계**

Buffer Pool은 InnoDB가 테이블과 인덱스 데이터를 캐싱하는 메인 메모리 영역이다. 필요한 페이지가 Buffer Pool에 있으면 디스크 I/O 없이 처리된다. 전용 DB 서버에서는 물리 메모리의 최대 80%까지 할당을 권장한다.

```ini
innodb_buffer_pool_size = 8G  # 물리 메모리의 80%
innodb_buffer_pool_instances = 8  # 멀티 인스턴스로 경합 감소
```

**Change Buffer를 통한 Random I/O 감소**

세컨더리 인덱스(NUSI, Non-Unique Secondary Index)에 대한 DML(INSERT, UPDATE, DELETE) 시, 해당 인덱스 페이지가 Buffer Pool에 없으면 Change Buffer에 변경 내용을 임시 저장한다. 이후 해당 페이지가 다른 읽기 작업으로 Buffer Pool에 로드될 때 일괄 병합(merge)한다.

- 세컨더리 인덱스는 삽입 순서가 무작위이므로 Random I/O가 빈번하게 발생한다
- Change Buffer는 이 Random I/O를 지연시켜 일괄 처리함으로써 성능을 최대 15배까지 개선할 수 있다
- Change Buffer는 디스크에서는 시스템 테이블스페이스(ibdata)에, 메모리에서는 Buffer Pool의 일부를 차지한다

**Log 기반 Sequential Write (Redo Log, Undo Log)**

InnoDB는 모든 변경 사항을 먼저 Redo Log에 Sequential Write로 기록하고, 이후 실제 데이터 파일에 반영한다. 이를 통해 비용이 큰 Random Write를 Sequential Write로 대체하는 효과를 얻는다(Write-Ahead Logging 패턴).

---

### 5. Random I/O를 Sequential I/O로 전환하는 전략

**Covering Index 활용**

쿼리에 필요한 컬럼을 모두 인덱스에 포함시켜, 세컨더리 인덱스만으로 결과를 반환한다. 클러스터드 인덱스(데이터 페이지)를 추가로 읽는 Random Access(Row Lookup)를 완전히 제거할 수 있다.

```sql
-- 기존: name 인덱스 스캔 후 age를 위해 Row Lookup (Random I/O 발생)
SELECT name, age FROM users WHERE name = 'alice';

-- 개선: (name, age) Covering Index로 Row Lookup 없이 처리
CREATE INDEX idx_name_age ON users (name, age);
SELECT name, age FROM users WHERE name = 'alice';
-- EXPLAIN Extra: "Using index" (커버링 인덱스 사용)
```

**Clustered Index 설계 전략**

InnoDB의 Clustered Index는 데이터를 Primary Key 순서대로 물리적으로 저장한다. 따라서 PK 기반의 범위 조회는 Sequential Access로 처리된다. 단조 증가하는 PK(AUTO_INCREMENT, UUID v7 등)를 사용하면 INSERT 시에도 Random Write를 줄일 수 있다.

```sql
-- 단조 증가 PK 사용: INSERT 시 Sequential Write, 범위 조회 시 Sequential Read
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ...
);
```

**Multi-Range Read (MRR) 최적화**

세컨더리 인덱스를 통한 범위 스캔에서, MySQL은 먼저 인덱스만 스캔해 Row ID(Primary Key)를 수집하고, 이를 PK 순서로 정렬한 뒤 데이터 페이지를 순차적으로 읽는다. Random Access를 Sequential Access에 가깝게 변환하는 기법이다.

```sql
-- MRR 활성화 여부 확인
SET optimizer_switch = 'mrr=on, mrr_cost_based=on';

-- EXPLAIN에서 "Using MRR" 확인
EXPLAIN SELECT * FROM orders WHERE status = 'PENDING' AND created_at > '2024-01-01'\G
-- Extra: Using index condition; Using MRR
```

**ORDER BY를 통한 접근 패턴 변경**

인덱스 순서와 동일한 ORDER BY를 사용하면, 옵티마이저가 인덱스를 활용한 순차 접근으로 실행 계획을 세울 수 있다.

---

### 6. 실무에서의 I/O 모니터링

**SHOW ENGINE INNODB STATUS에서 I/O 관련 지표**

```sql
SHOW ENGINE INNODB STATUS\G
```

출력 중 `BUFFER POOL AND MEMORY` 섹션에서 주요 지표를 확인할 수 있다.

```
Buffer pool hit rate   1000 / 1000   -- 히트율 (999~1000이면 양호)
Pages read             197           -- 디스크에서 읽은 페이지 수
Pages read ahead       0.00/s        -- Read-Ahead로 읽은 페이지 수
Pages evicted without access 0.00/s -- 접근 없이 제거된 페이지 수
```

**상태 변수를 통한 I/O 분석**

```sql
-- Buffer Pool 히트율 계산
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%';
```

| 변수 | 설명 |
|------|------|
| `Innodb_buffer_pool_read_requests` | Buffer Pool에서 처리된 논리적 읽기 요청 수 |
| `Innodb_buffer_pool_reads` | Buffer Pool Miss로 인해 디스크에서 읽은 횟수 |
| `Innodb_buffer_pool_read_ahead` | Linear Read-Ahead로 프리페치된 페이지 수 |

```sql
-- 히트율(%) = (1 - reads / read_requests) * 100
-- 99% 이상을 목표로 한다
SELECT
    (1 - innodb_buffer_pool_reads / innodb_buffer_pool_read_requests) * 100
        AS buffer_pool_hit_rate
FROM (
    SELECT
        VARIABLE_VALUE AS innodb_buffer_pool_reads
    FROM performance_schema.global_status
    WHERE VARIABLE_NAME = 'Innodb_buffer_pool_reads'
) r,
(
    SELECT
        VARIABLE_VALUE AS innodb_buffer_pool_read_requests
    FROM performance_schema.global_status
    WHERE VARIABLE_NAME = 'Innodb_buffer_pool_read_requests'
) rr;
```

---

## 핵심 정리
- Random Access는 디스크의 임의 위치를 읽는 방식으로, HDD 환경에서는 Seek Time과 Rotational Latency로 인해 매우 느리다. SSD에서 크게 개선되었으나 Sequential Access보다는 여전히 비용이 크다.
- InnoDB의 Buffer Pool은 디스크 I/O를 줄이는 핵심 장치다. 히트율이 99% 미만으로 떨어지면 Random I/O가 병목이 될 수 있으므로, `innodb_buffer_pool_size`를 충분히 확보해야 한다.
- Secondary Index를 통한 Row Lookup은 대표적인 Random Access 원인이다. Covering Index를 활용하면 이 단계를 생략하여 I/O를 크게 줄일 수 있다.
- MRR(Multi-Range Read)은 세컨더리 인덱스 스캔 후 Primary Key를 정렬해 데이터 페이지를 순차적으로 읽음으로써 Random Access를 Sequential Access에 가깝게 전환한다.
- Change Buffer는 세컨더리 인덱스에 대한 Random Write I/O를 지연·병합 처리하여 실시간 I/O 비용을 절감한다. Clustered Index와 Redo Log는 Sequential I/O를 활용하는 InnoDB의 핵심 설계 원리다.

## 키워드
- `Random Access`: 디스크의 임의 위치에 있는 데이터를 읽는 방식. 요청마다 물리적 위치가 달라 HDD에서는 Seek Time이 발생해 IOPS가 낮다.
- `Sequential Access`: 연속된 물리적 위치의 데이터를 순서대로 읽는 방식. OS Prefetch와 Read-Ahead가 적용되어 처리량이 높다.
- `Disk I/O`: 디스크에서 데이터를 읽거나 쓰는 작업. InnoDB는 페이지(16KB) 단위로 I/O를 수행한다.
- `IOPS`: 초당 처리 가능한 I/O 요청 수(Input/Output Operations Per Second). HDD는 수백, SSD는 수만~수십만 수준이다.
- `InnoDB Buffer Pool`: 테이블과 인덱스 데이터를 캐싱하는 메모리 영역. 페이지가 Buffer Pool에 있으면 디스크 I/O가 발생하지 않는다.
- `Read-Ahead`: 순차 접근 패턴을 감지해 다음 익스텐트(64 페이지)를 비동기로 미리 로드하는 InnoDB의 프리페치 기법. Linear와 Random 두 방식이 있다.
- `Covering Index`: 쿼리에 필요한 모든 컬럼을 포함하는 인덱스. 데이터 페이지 접근(Row Lookup) 없이 인덱스만으로 결과를 반환해 Random I/O를 제거한다.
- `Multi-Range Read`: 세컨더리 인덱스에서 Row ID를 수집·정렬 후 Primary Key 순서로 데이터를 읽는 최적화. Random Access를 Sequential Access에 가깝게 변환한다.
- `Change Buffer`: Buffer Pool에 없는 세컨더리 인덱스 페이지에 대한 DML 변경을 임시 저장해두고, 나중에 일괄 병합함으로써 Random Write I/O를 감소시키는 구조.
- `Clustered Index`: InnoDB에서 Primary Key 순서로 데이터를 물리적으로 정렬하여 저장하는 인덱스. PK 기반 범위 조회 시 Sequential Access가 가능하다.

## 참고 자료
- [MySQL 8.4 공식 문서 - Optimizing InnoDB Disk I/O](https://dev.mysql.com/doc/refman/8.4/en/optimizing-innodb-diskio.html)
- [MySQL 8.4 공식 문서 - InnoDB Buffer Pool](https://dev.mysql.com/doc/refman/8.4/en/innodb-buffer-pool.html)
- [MySQL 8.4 공식 문서 - InnoDB Buffer Pool Prefetching (Read-Ahead)](https://dev.mysql.com/doc/refman/8.4/en/innodb-performance-read_ahead.html)
- [MySQL 8.4 공식 문서 - Change Buffer](https://dev.mysql.com/doc/refman/8.4/en/innodb-change-buffer.html)
- [MySQL 8.4 공식 문서 - Multi-Range Read Optimization](https://dev.mysql.com/doc/refman/8.4/en/mrr-optimization.html)
- [MySQL 8.4 공식 문서 - Clustered and Secondary Indexes](https://dev.mysql.com/doc/refman/8.4/en/innodb-index-types.html)
