# ALTER TABLE column type 변경 시 RDBMS의 전략

## 개요
`ALTER TABLE`로 컬럼 타입을 변경할 때, RDBMS는 기존 모든 레코드를 새로운 타입으로 변환 가능한지 검증하고 데이터를 실제로 변환해야 한다. RDBMS마다 이 과정에서 사용하는 전략이 다르며, 대용량 테이블에서는 서비스 가용성에 직접적인 영향을 줄 수 있다.

---

## 상세 내용

### 1. MySQL Online DDL 전체 구조

MySQL InnoDB는 ALTER TABLE 수행 시 세 가지 알고리즘을 지원하며, 명시적으로 지정하지 않으면 `INSTANT → INPLACE → COPY` 우선순위로 **해당 작업이 지원하는 가장 효율적인 알고리즘을 자동 선택**한다.

```sql
ALTER TABLE t ADD COLUMN c3 INT, ALGORITHM=INSTANT;           -- 메타데이터만 변경
ALTER TABLE t ADD COLUMN c2 INT, ALGORITHM=INPLACE, LOCK=NONE; -- 테이블 재구성, DML 병행
ALTER TABLE t MODIFY COLUMN c1 BIGINT, ALGORITHM=COPY;         -- 전체 복사
```

| 방식 | 설명 | 대표 지원 작업 |
|------|------|---------------|
| **Instant** | 메타데이터(딕셔너리)만 변경, 행 데이터 미수정 | 컬럼 추가/삭제, 기본값 변경 |
| **In-place** | 기존 테이블을 직접 재구성, DML 병행 허용 | VARCHAR 길이 증가, NOT NULL 변경 |
| **Copy** | 새 스키마의 임시 테이블 생성 후 전체 데이터 복사 | **컬럼 타입 변경** |

모든 작업이 세 가지를 전부 지원하는 것이 아니라, **작업 종류마다 지원하는 알고리즘이 정해져 있다.** 컬럼 타입 변경(MODIFY COLUMN으로 타입 자체를 바꾸는 경우)은 기존 데이터를 새 타입으로 실제 변환해야 하므로, MySQL 공식 문서에 따르면 **ALGORITHM=COPY만 지원**된다.

---

### 2. MySQL 각 알고리즘 상세

#### ALGORITHM=COPY (컬럼 타입 변경 시 강제 적용)

1. 변경된 스키마의 임시 테이블(shadow table)을 생성한다.
2. 원본 테이블의 모든 행을 새 타입으로 변환하며 임시 테이블에 복사한다.
3. 복사 완료 후 두 테이블을 원자적으로 이름 교체(rename)한다.
4. 원본 테이블을 삭제한다.

- 복사 중 `LOCK=EXCLUSIVE` 상태 — 모든 DML이 블록된다.
- 대용량 테이블에서 디스크 공간을 최대 2배 소비한다.

#### ALGORITHM=INPLACE (3단계 동작)

- **Phase 1 (Prepare)**: 새 테이블 구조를 스토리지 엔진에 생성, 병렬 DML 변경을 기록할 온라인 로그 버퍼 설정. 이 시점에 Exclusive MDL을 잠시 획득 후 해제.
- **Phase 2 (Copy)**: 클러스터드 인덱스를 스캔하며 재구성. 복사 도중 DML은 온라인 로그 버퍼에 기록됨. `Shared Upgradable MDL`만 보유하므로 DML 병행 허용.
- **Phase 3 (Commit)**: Exclusive MDL로 업그레이드하여 로그에 쌓인 변경사항을 최종 적용. 짧은 시간(수백ms)만 Lock 보유.

컬럼 타입 변경은 INPLACE를 지원하지 않는다. VARCHAR 길이 증가, NOT NULL/NULL 제약 변경 등에만 적용된다.

#### ALGORITHM=INSTANT (MySQL 8.0.12+)

메타데이터(딕셔너리)에만 변경 사항을 기록하고 실제 행 데이터는 수정하지 않는다. **Row Versioning** 기법으로 구행(old row)과 신행(new row)을 구분한다.

- 컬럼 추가/삭제, 기본값 변경 등에 사용
- 최대 Row Version 수: 64 (MySQL 9.1.0부터 255)
- 컬럼 타입 변경에는 사용 불가

---

### 3. MySQL Online DDL 중 MDL(Metadata Lock) 동작

MDL은 DDL과 DML의 충돌을 방지하는 서버 레벨 잠금이다.

| 단계 | MDL 종류 | DML 허용 여부 |
|------|----------|---------------|
| 초기화 | Shared Upgradable MDL | 허용 |
| 실행(Copy/INPLACE) | Shared Upgradable MDL | 허용 (INPLACE) / 불허 (COPY) |
| 커밋 | Exclusive MDL | 잠시 차단 |

**MDL 연쇄 블록킹 문제:** 오래된 트랜잭션이 Shared MDL을 보유 중이면 DDL의 Exclusive MDL 획득이 블록된다. 그 상태에서 새로운 SELECT조차 DDL 뒤에 줄을 서게 되어 테이블이 마비될 수 있다.

```sql
-- 모니터링
SELECT * FROM performance_schema.metadata_locks;
SHOW FULL PROCESSLIST; -- 'Waiting for table metadata lock' 확인
```

---

### 4. PostgreSQL의 전략

PostgreSQL은 타입 변환 가능성에 따라 두 경로 중 하나를 선택한다.

#### 테이블 전체 재작성(Rewrite)이 필요한 경우
- 대부분의 컬럼 타입 변경 (내부 저장 포맷이 다른 경우)
- `int → bigint`, `float → int` 등

#### 메타데이터만 변경되는 경우 (재작성 불필요)
- **Binary coercible** 타입 변환: 내부 표현이 동일한 경우
  - `varchar(50) → varchar(100)` (길이 증가)
  - `varchar(n) → text`
  - `char(n) → varchar(n)`

#### USING 절을 활용한 명시적 변환

PostgreSQL은 `pg_cast` 시스템 카탈로그로 변환 경로를 조회한다. 암묵적 변환 경로가 없으면 USING 절이 필수다.

```sql
-- 암묵적 변환이 없는 경우: USING 절 필수
ALTER TABLE t ALTER COLUMN status TYPE integer USING status::integer;

-- Unix epoch(정수) → timestamp 변환
ALTER TABLE foo
    ALTER COLUMN foo_timestamp DROP DEFAULT,
    ALTER COLUMN foo_timestamp TYPE timestamp with time zone
    USING timestamp with time zone 'epoch' + foo_timestamp * interval '1 second',
    ALTER COLUMN foo_timestamp SET DEFAULT now();
```

#### AccessExclusiveLock

PostgreSQL의 대부분 ALTER TABLE은 `ACCESS EXCLUSIVE` Lock을 획득한다. 이는 SELECT를 포함한 모든 접근과 충돌한다.

```sql
-- lock_timeout으로 DDL이 오래 기다리지 않도록 제한
SET lock_timeout = '2s';
ALTER TABLE t ALTER COLUMN c TYPE bigint;
```

단일 ALTER TABLE에 여러 변경을 묶으면 **단일 패스(single pass)**로 처리된다.

```sql
-- 비효율: 두 번의 테이블 재작성
ALTER TABLE t ALTER COLUMN address TYPE varchar(80);
ALTER TABLE t ALTER COLUMN name TYPE varchar(100);

-- 효율: 단일 패스
ALTER TABLE t
    ALTER COLUMN address TYPE varchar(80),
    ALTER COLUMN name TYPE varchar(100);
```

---

### 5. 타입 변환 검증 방식

#### MySQL
사전 검증 없이 복사 과정에서 실제 변환을 시도한다. SQL 모드에 따라 동작이 다르다.

| 상황 | Strict Mode OFF | Strict Mode ON |
|------|-----------------|----------------|
| 값이 새 타입 범위 초과 | 최대값으로 자르고 Warning | ERROR, 작업 중단 |
| 문자열 → 숫자 변환 실패 | 0으로 대체하고 Warning | ERROR, 작업 중단 |
| NULL → NOT NULL 충돌 | 기본값으로 대체 | ERROR, 작업 중단 |

```sql
SET sql_mode = 'STRICT_ALL_TABLES';  -- 안전한 변경을 위해 권장
```

#### PostgreSQL
`pg_cast` 카탈로그의 `castcontext` 값으로 변환 경로를 사전 확인한다.

| castcontext 값 | 의미 |
|----------------|------|
| `i` (implicit) | 모든 컨텍스트에서 자동 변환 |
| `a` (assignment) | 대입 컨텍스트에서 자동 변환 |
| `e` (explicit) | CAST()/:: 연산자 필수 |

변환 경로가 없거나 명시적 변환만 가능한데 USING 절이 없으면 즉시 ERROR가 발생한다. 실제 데이터 변환 시 값이 변환 불가능하면 런타임 ERROR가 발생한다.

---

### 6. 대용량 테이블 무중단 스키마 변경 기법

#### pt-online-schema-change (트리거 기반)

1. ghost 테이블(`_원본테이블명_new`) 생성
2. 원본 테이블에 INSERT/UPDATE/DELETE 트리거 3개 설치 → ghost 테이블에 동기 적용
3. 기존 행을 PK 범위로 청크 단위 배치 복사
4. `RENAME TABLE 원본 TO 원본_old, ghost TO 원본`으로 원자적 전환

한계: 트리거 오버헤드, 쓰기 폭주 시 Lock 경합 발생 가능

#### gh-ost (Binlog 스트리밍 기반)

MySQL 슬레이브처럼 동작하며 Row-Based Binlog를 스트리밍으로 읽어 변경사항을 추적한다. 트리거 없이 동작하며 일시 정지/재개가 가능하다. GitHub에서 프로덕션 검증된 도구다.

#### pg_repack (PostgreSQL)

트리거 + 시스템 카탈로그 swap 방식. 테이블 bloat 제거도 가능하며 PRIMARY KEY가 필수다. 대부분의 작업을 `SHARE UPDATE EXCLUSIVE` Lock으로 진행하여 DML을 허용한다.

#### Expand-Contract 패턴

가장 안전한 무중단 마이그레이션 패턴으로, 스키마 변경을 여러 배포 단계로 분산한다.

예시: `price INTEGER → price NUMERIC(10,2)` 변경

```
Phase 1 (Expand):   새 컬럼 price_new 추가 + 애플리케이션 dual-write
Phase 2 (Migrate):  기존 행 데이터를 배치로 새 컬럼에 복사
Phase 3 (Switch):   읽기를 새 컬럼으로 전환 (여전히 dual-write)
Phase 4 (Contract): 기존 컬럼 삭제, 새 컬럼 이름 변경
```

각 단계에서 롤백 가능하며 서비스 중단이 없다.

---

## 핵심 정리
- 컬럼 타입 변경은 기존 데이터를 새 포맷으로 실제 변환해야 하므로, MySQL은 `ALGORITHM=COPY`(전체 테이블 복사)가 강제되고 PostgreSQL은 내부 표현이 동일한 경우(binary coercible)에만 재작성 없이 처리된다.
- DDL 중 MDL(MySQL) 또는 AccessExclusiveLock(PostgreSQL) 획득 전 오래된 트랜잭션이 있으면 DDL이 블록되고, 이후 SELECT마저 DDL 뒤에 줄을 서는 연쇄 블록킹이 발생할 수 있다.
- 대용량 테이블에서는 gh-ost/pt-osc(MySQL), pg_repack(PostgreSQL) 같은 온라인 스키마 변경 도구나 Expand-Contract 패턴을 활용하여 무중단으로 마이그레이션할 수 있다.

## 키워드

| 키워드 | 설명 |
|--------|------|
| `ALTER TABLE` | 테이블 스키마를 변경하는 DDL 명령어 |
| `DDL` (Data Definition Language) | 스키마 구조를 정의/변경하는 SQL 종류. CREATE, ALTER, DROP 등 |
| `Online DDL` | MySQL InnoDB에서 DML을 허용하면서 DDL을 수행하는 기능. ALGORITHM과 LOCK 옵션으로 제어 |
| `Schema Migration` | 데이터베이스 스키마를 변경하는 작업 전반. 운영 환경에서는 가용성을 유지하며 수행해야 함 |
| `Table Rewrite` | 기존 테이블의 모든 행을 새로운 포맷으로 변환하며 재작성하는 작업. 디스크 공간 2배 필요 |
| `In-place` | 임시 테이블 없이 기존 테이블을 직접 재구성하는 방식. 복사 방식보다 효율적 |
| `Instant DDL` | 메타데이터(딕셔너리)만 변경하여 테이블 데이터를 건드리지 않는 방식. MySQL 8.0.12+에서 Row Versioning으로 구현 |
| `MDL` (Metadata Lock) | MySQL 서버 레벨의 잠금으로 DDL과 DML의 충돌을 방지. Exclusive MDL을 기다리는 동안 이후 쿼리가 연쇄 블록될 수 있음 |
| `pt-online-schema-change` | Percona Toolkit의 MySQL 온라인 스키마 변경 도구. 트리거 기반으로 ghost 테이블에 데이터를 복사함 |
| `gh-ost` | GitHub이 개발한 MySQL 온라인 스키마 변경 도구. 트리거 없이 Binlog 스트리밍으로 변경사항을 추적함 |

## 참고 자료
- [MySQL 8.0 Online DDL Operations](https://dev.mysql.com/doc/refman/8.0/en/innodb-online-ddl-operations.html)
- [MySQL 8.0 Online DDL Performance and Concurrency](https://dev.mysql.com/doc/refman/8.0/en/innodb-online-ddl-performance.html)
- [MySQL 8.0 ALTER TABLE Statement](https://dev.mysql.com/doc/refman/8.0/en/alter-table.html)
- [PostgreSQL ALTER TABLE 공식 문서](https://www.postgresql.org/docs/current/sql-altertable.html)
- [PostgreSQL pg_cast 카탈로그](https://www.postgresql.org/docs/current/catalog-pg-cast.html)
- [gh-ost GitHub](https://github.com/github/gh-ost)
- [pg_repack 공식 문서](https://reorg.github.io/pg_repack/)
