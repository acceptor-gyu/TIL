# MySQL MyISAM vs InnoDB Engine

## 개요

MySQL의 대표적인 스토리지 엔진인 MyISAM과 InnoDB의 구조적 차이와 선택 기준을 정리한다.
MySQL 5.5 이후 InnoDB가 기본 엔진이 되었지만, 두 엔진의 차이를 명확히 이해해야 적절한 선택을 할 수 있다.

## 상세 내용

### 1. MySQL 스토리지 엔진이란

MySQL은 **플러그인 스토리지 엔진 아키텍처**를 채택하고 있다. 이는 SQL 레이어와 스토리지 레이어를 분리함으로써, 테이블마다 서로 다른 스토리지 엔진을 선택할 수 있게 해준다.

```sql
-- 사용 가능한 스토리지 엔진 목록 확인
SHOW ENGINES;

-- 결과 예시
+--------------------+---------+----------------------------------------------------------------+
| Engine             | Support | Comment                                                        |
+--------------------+---------+----------------------------------------------------------------+
| InnoDB             | DEFAULT | Supports transactions, row-level locking, and foreign keys     |
| MyISAM             | YES     | MyISAM storage engine                                          |
| MEMORY             | YES     | Hash based, stored in memory, useful for temporary tables      |
| CSV                | YES     | CSV storage engine                                             |
| ARCHIVE            | YES     | Archive storage engine                                         |
+--------------------+---------+----------------------------------------------------------------+

-- 특정 테이블의 엔진 확인
SHOW TABLE STATUS WHERE Name = 'your_table_name'\G

-- 테이블 생성 시 엔진 지정
CREATE TABLE example (id INT PRIMARY KEY) ENGINE=InnoDB;
```

스토리지 엔진은 테이블 단위로 지정할 수 있기 때문에, 같은 데이터베이스 내에서도 테이블마다 다른 엔진을 혼용할 수 있다.

---

### 2. MyISAM 엔진

#### 파일 구성

MyISAM은 테이블 하나에 대해 **3개의 파일**을 생성한다.

| 파일 확장자 | 역할 |
|---|---|
| `.frm` | 테이블 정의(스키마) 정보 (MySQL 8.0에서 제거됨) |
| `.MYD` (My Data) | 실제 행(Row) 데이터 저장 |
| `.MYI` (My Index) | 인덱스 데이터 저장 |

데이터와 인덱스가 별도 파일로 분리되어 있으며, 인덱스의 리프 노드는 실제 데이터가 아닌 **데이터 파일의 물리적 주소(포인터)**를 가리킨다. 이를 **Non-Clustered Index(힙 구조)** 라 한다.

#### 테이블 레벨 락 (Table-level Lock)

MyISAM은 쓰기 작업 시 **테이블 전체에 락**을 건다.

```
[쓰기 요청 발생]
    │
    ▼
테이블 전체 잠금 (Table Lock)
    │
    ├── 다른 읽기 요청도 대기
    └── 다른 쓰기 요청도 대기
```

이 구조로 인해 동시 쓰기 처리가 많은 환경에서는 병목이 심각하게 발생한다.

#### 트랜잭션 미지원

MyISAM은 **트랜잭션을 지원하지 않는다.** `COMMIT`, `ROLLBACK`이 동작하지 않으며, 쓰기 도중 장애가 발생하면 데이터가 손상된 채로 남는다. 복구하려면 `CHECK TABLE` 및 `REPAIR TABLE`을 실행해야 하며, 이 과정에서 데이터 손실이 발생할 수 있다.

#### MyISAM의 장점

- **COUNT(*) 최적화**: 행 개수를 별도로 관리하여 `COUNT(*)` 쿼리가 매우 빠르다.
- **낮은 메모리 사용**: InnoDB에 비해 메모리 오버헤드가 작다.
- **읽기 중심 워크로드**: 동시 쓰기가 없는 단순 조회 환경에서는 충분히 빠르다.
- **Full-Text Index**: MySQL 5.6 이전에는 MyISAM만 Full-Text 인덱스를 지원했다 (현재는 InnoDB도 지원).

---

### 3. InnoDB 엔진

#### 파일 구성

InnoDB는 데이터와 인덱스를 **하나의 Tablespace 파일**에 함께 저장한다.

| 파일 | 설명 |
|---|---|
| `ibdata1` | System Tablespace. undo log, change buffer, data dictionary 등 포함 |
| `table_name.ibd` | File-Per-Table 모드에서 테이블별 데이터와 인덱스를 함께 저장 |

MySQL 8.0부터는 `file_per_table=ON`이 기본값이므로, 각 테이블은 독립된 `.ibd` 파일을 가진다. 또한 MySQL 8.0에서는 `.frm` 파일이 사라지고 스키마 정보가 `mysql.ibd`(data dictionary)에 통합 관리된다.

#### 로우 레벨 락 (Row-level Lock)과 MVCC

InnoDB는 **행(Row) 단위로 락**을 걸기 때문에 여러 트랜잭션이 서로 다른 행을 동시에 수정할 수 있다.

락의 종류는 다음과 같다.

| 락 종류 | 설명 |
|---|---|
| Record Lock | 인덱스 레코드 자체에 대한 락 |
| Gap Lock | 인덱스 레코드 사이의 빈 공간에 대한 락 (Phantom Read 방지) |
| Next-Key Lock | Record Lock + Gap Lock의 조합 (InnoDB 기본 락 방식) |

**MVCC(Multi-Version Concurrency Control)** 는 읽기와 쓰기가 서로를 블로킹하지 않도록 한다.

```
[InnoDB MVCC 동작 방식]

트랜잭션 A (UPDATE 실행)
    │
    ├── 현재 row 수정
    └── 이전 버전을 Undo Log에 보관

트랜잭션 B (SELECT 실행, 동시에)
    │
    └── Read View 생성 → Undo Log에서 자신의 트랜잭션 시작 시점의 버전을 읽음
        (A의 수정 내용이 보이지 않음 → Non-Locking Consistent Read)
```

이로 인해 읽기 작업은 락 없이 과거 버전의 스냅샷을 읽고, 쓰기 작업과 충돌하지 않는다.

#### 트랜잭션(ACID) 지원

InnoDB는 완전한 ACID 트랜잭션을 지원한다.

| 속성 | 설명 | InnoDB 구현 |
|---|---|---|
| **Atomicity** (원자성) | 전부 성공 또는 전부 실패 | Undo Log를 이용한 Rollback |
| **Consistency** (일관성) | 트랜잭션 전후 데이터 무결성 유지 | 제약 조건 검사, 외래 키 |
| **Isolation** (격리성) | 트랜잭션 간 간섭 방지 | MVCC + Lock |
| **Durability** (지속성) | 커밋된 데이터는 영구 저장 | Redo Log (WAL) |

#### 외래 키 (Foreign Key) 지원

InnoDB는 외래 키 제약을 데이터베이스 레벨에서 강제한다.

```sql
CREATE TABLE orders (
    id INT PRIMARY KEY,
    customer_id INT,
    FOREIGN KEY (customer_id) REFERENCES customers(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB;
```

MyISAM에서는 외래 키 구문 자체는 파싱되지만 **무시**된다. 참조 무결성을 애플리케이션 코드에서 직접 보장해야 한다.

#### 클러스터드 인덱스 (Clustered Index) 구조

InnoDB의 가장 중요한 특성 중 하나다.

```
[MyISAM - Non-Clustered Index]
인덱스 B+Tree 리프 노드 → 물리적 주소(포인터) → .MYD 파일에서 실제 데이터 조회

[InnoDB - Clustered Index]
Primary Key B+Tree 리프 노드 → 실제 Row 데이터 자체 (데이터 = 인덱스)
```

Primary Key의 B+Tree 리프 노드에 실제 행 데이터가 저장되므로, Primary Key를 기준으로 한 조회는 별도의 포인터 추적 없이 한 번에 데이터를 가져올 수 있다.

Secondary Index(보조 인덱스)는 리프 노드에 Primary Key 값을 저장하고, 이를 통해 Clustered Index에 한 번 더 접근한다. 따라서 **Primary Key는 짧고 단조 증가하는 값을 사용하는 것이 성능에 유리하다.**

#### Crash Recovery (Redo Log)

InnoDB는 **Write-Ahead Logging(WAL)** 방식으로 Redo Log를 먼저 기록한 뒤 실제 데이터 파일에 반영한다.

```
[InnoDB Crash Recovery 절차]

서버 재시작
    │
    ├── 1. Redo Log 재실행 → 커밋되었으나 디스크에 반영 안 된 변경 복구
    └── 2. Undo Log 적용  → 커밋되지 않은 트랜잭션 롤백
```

이 구조 덕분에 서버가 갑자기 종료되어도 데이터 일관성을 자동으로 복구할 수 있다.

---

### 4. 핵심 비교

| 특성 | MyISAM | InnoDB |
|---|---|---|
| **락 단위** | Table-level Lock | Row-level Lock |
| **트랜잭션** | 미지원 | 지원 (ACID) |
| **MVCC** | 미지원 | 지원 |
| **외래 키** | 미지원 | 지원 |
| **Crash Recovery** | 미지원 (REPAIR 필요) | 자동 복구 (Redo/Undo Log) |
| **인덱스 구조** | Non-Clustered (힙) | Clustered Index |
| **Full-Text Index** | 지원 | 지원 (MySQL 5.6+) |
| **COUNT(\*)** | 매우 빠름 (별도 저장) | 상대적으로 느림 (전체 스캔) |
| **동시성** | 낮음 | 높음 |
| **메모리 사용** | 낮음 | 높음 (Buffer Pool) |
| **파일 구조** | .MYD + .MYI + .frm | .ibd (데이터+인덱스 통합) |
| **기본 엔진 여부** | 아니요 (구버전 기본값) | 예 (MySQL 5.5+) |

---

### 5. 선택 기준

#### InnoDB를 선택해야 하는 경우 (대부분의 상황)

- 동시 쓰기가 발생하는 OLTP 환경
- 데이터 정합성과 트랜잭션이 중요한 서비스
- 외래 키로 참조 무결성을 보장해야 하는 경우
- 장애 복구가 자동으로 이루어져야 하는 경우
- MySQL 5.5 이상을 사용하는 모든 신규 프로젝트

#### MyISAM이 고려될 수 있는 경우 (극히 제한적)

- 동시 쓰기가 전혀 없는 읽기 전용 데이터 웨어하우스
- 레거시 시스템 유지보수
- 극히 적은 메모리 환경에서 소형 테이블 관리

> MySQL 5.6부터 Full-Text Index도 InnoDB에서 지원되므로, MyISAM을 선택해야 할 이유는 사실상 거의 없다.

#### MySQL 5.5 이후 InnoDB가 기본 엔진이 된 이유

2010년 Oracle이 MySQL을 인수한 후 InnoDB를 MySQL 5.5의 기본 엔진으로 변경했다. 당시 웹 서비스의 트래픽과 복잡성이 급격히 증가하면서, 동시성 처리와 데이터 안정성이 가장 중요한 요소가 되었기 때문이다. MyISAM의 테이블 레벨 락은 다중 사용자 환경에서 근본적인 한계를 가지고 있었다.

---

## 핵심 정리

- **핵심 포인트 1**: MyISAM은 테이블 락, InnoDB는 로우 락으로 동시성 처리에서 큰 차이가 난다. 쓰기 트래픽이 조금이라도 있다면 InnoDB를 선택해야 한다.
- **핵심 포인트 2**: InnoDB만 트랜잭션(ACID), 외래 키, Crash Recovery를 지원한다. MyISAM에서 장애가 나면 수동 복구가 필요하다.
- **핵심 포인트 3**: InnoDB의 Clustered Index는 Primary Key 기반 조회를 매우 효율적으로 처리하지만, 보조 인덱스는 Primary Key를 한 번 더 참조하므로 Primary Key 설계가 중요하다.
- **핵심 포인트 4**: 현대 MySQL의 기본 엔진은 InnoDB이며, MySQL 5.6 이후 Full-Text Index도 InnoDB에서 지원되어 MyISAM을 써야 할 이유가 거의 없다.

---

## 기술적 한계와 보완 전략

### InnoDB의 한계

**1. COUNT(*) 성능**

InnoDB는 행 수를 별도로 관리하지 않기 때문에, `COUNT(*)`는 내부적으로 인덱스 스캔이 발생한다. 대용량 테이블에서 빈번한 `COUNT(*)`가 필요하다면 별도 카운터 테이블을 관리하거나 캐시를 활용해야 한다.

```sql
-- 성능이 더 나은 방법: 커버링 인덱스 활용
SELECT COUNT(*) FROM orders USE INDEX (PRIMARY);

-- 또는 근사치를 허용한다면
SELECT TABLE_ROWS FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_NAME = 'orders';
```

**2. Undo Log 팽창 (Long-running Transaction)**

MVCC 특성상, 오래된 트랜잭션이 살아있으면 Undo Log를 정리하지 못해 ibdata 파일이 계속 커진다. 트랜잭션을 짧게 유지하고, 불필요하게 트랜잭션을 열어두지 않아야 한다.

**3. Clustered Index와 무작위 INSERT**

Primary Key가 UUID 같은 무작위 값이면 B+Tree의 페이지 분할(Page Split)이 빈번하게 발생하여 쓰기 성능이 저하된다. 이를 방지하려면 `AUTO_INCREMENT`처럼 단조 증가하는 Primary Key를 사용하거나, UUID v7처럼 시간 기반으로 정렬 가능한 UUID를 사용하는 것이 좋다.

```sql
-- 권장: AUTO_INCREMENT Primary Key
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ...
) ENGINE=InnoDB;
```

---

## 키워드

- **MyISAM**: MySQL의 레거시 스토리지 엔진. 테이블 레벨 락과 트랜잭션 미지원이 특징이며, 각 테이블이 .MYD(데이터), .MYI(인덱스), .frm(스키마) 파일로 저장된다.
- **InnoDB**: MySQL 5.5 이후 기본 스토리지 엔진. ACID 트랜잭션, 행 레벨 락, MVCC, 외래 키, Crash Recovery를 지원한다.
- **Storage Engine**: MySQL의 플러그인 아키텍처에서 데이터 저장/조회 방식을 담당하는 컴포넌트. 테이블마다 다른 엔진을 지정할 수 있다.
- **Table-level Lock**: 쓰기 작업 시 테이블 전체에 락을 거는 방식. 동시 쓰기 환경에서 병목을 유발한다. MyISAM의 기본 락 방식.
- **Row-level Lock**: 행 단위로 락을 거는 방식. 여러 트랜잭션이 서로 다른 행을 동시에 수정할 수 있어 동시성이 높다. InnoDB의 기본 락 방식.
- **MVCC (Multi-Version Concurrency Control)**: 트랜잭션이 데이터를 수정할 때 이전 버전을 Undo Log에 보관하고, 읽기 트랜잭션은 자신의 시작 시점에 맞는 버전을 읽는 방식. 읽기와 쓰기가 서로를 블로킹하지 않는다.
- **Clustered Index**: Primary Key B+Tree의 리프 노드에 실제 행 데이터를 저장하는 구조. InnoDB는 항상 Clustered Index를 사용한다. Primary Key 기준 조회 시 포인터 추적 없이 데이터를 직접 얻는다.
- **ACID Transaction**: 데이터베이스 트랜잭션의 4가지 속성(원자성, 일관성, 격리성, 지속성). InnoDB는 Undo Log, Redo Log, MVCC, 락으로 이를 보장한다.
- **Foreign Key**: 테이블 간 참조 무결성을 데이터베이스 레벨에서 강제하는 제약 조건. InnoDB만 지원하며, MyISAM에서는 구문을 파싱하지만 무시된다.
- **Crash Recovery**: 서버 비정상 종료 후 데이터 일관성을 자동으로 복구하는 기능. InnoDB는 Redo Log(WAL)로 커밋된 변경을 재적용하고, Undo Log로 미완료 트랜잭션을 롤백한다.

## 참고 자료

- [MySQL 8.0 공식 문서 - Storage Engines](https://dev.mysql.com/doc/refman/8.0/en/storage-engines.html)
- [MySQL 8.0 공식 문서 - InnoDB Storage Engine](https://dev.mysql.com/doc/refman/8.0/en/innodb-storage-engine.html)
- [MySQL 8.0 공식 문서 - The MyISAM Storage Engine](https://dev.mysql.com/doc/refman/8.0/en/myisam-storage-engine.html)
- [MySQL 8.0 공식 문서 - InnoDB MVCC](https://dev.mysql.com/doc/refman/8.0/en/innodb-multi-versioning.html)
- [MySQL 공식 블로그 - MySQL 8.0: all you need to know about SDI](https://dev.mysql.com/blog-archive/mysql-80-all-you-need-to-know-about-sdi/)
