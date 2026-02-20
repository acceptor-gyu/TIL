# RDBMS(MySQL) index와 B+Tree

## 개요
MySQL에서 인덱스가 어떻게 동작하며, 왜 B+Tree 구조를 채택했는지 학습한다.

## 상세 내용

### 1. 인덱스란 무엇인가

**인덱스의 정의와 목적**

인덱스(Index)는 데이터베이스 테이블에서 데이터 검색 속도를 향상시키기 위한 자료구조다. 책의 찾아보기(색인)와 유사한 개념으로, 특정 값을 빠르게 찾을 수 있도록 정렬된 별도의 데이터 구조를 유지한다.

MySQL에서 인덱스는 기본적으로 **B-Tree(Balanced Tree)** 구조를 사용하며, 공간 인덱스(Spatial Index)를 제외한 InnoDB의 모든 인덱스는 B+Tree 자료구조로 구현된다.

**Full Table Scan vs Index Scan**

- **Full Table Scan**: 테이블의 모든 행을 처음부터 끝까지 순차적으로 읽는 방식. 테이블이 크면 매우 비효율적
- **Index Scan**: 인덱스를 통해 필요한 데이터의 위치를 빠르게 찾아 접근하는 방식. 특히 범위 검색(Range Query)에서 효율적

인덱스를 사용하면 `=`, `>`, `>=`, `<`, `<=`, `BETWEEN` 연산자를 사용한 쿼리에서 성능 향상을 기대할 수 있다.

### 2. 인덱스의 종류

**Clustered Index (클러스터형 인덱스)**

InnoDB의 각 테이블에는 **Clustered Index**라는 특수한 인덱스가 존재한다. Clustered Index는 단순한 인덱스가 아니라 **테이블 자체**이며, 실제 데이터가 물리적으로 정렬되어 저장되는 구조다.

InnoDB는 다음 우선순위로 Clustered Index를 결정한다:
1. `PRIMARY KEY`가 정의되어 있으면 이를 Clustered Index로 사용
2. `PRIMARY KEY`가 없으면 `NOT NULL`로 정의된 첫 번째 `UNIQUE` 인덱스를 사용
3. 둘 다 없으면 InnoDB가 자동으로 **6바이트 Row ID**를 생성하여 숨겨진 Clustered Index(`GEN_CLUST_INDEX`)를 만듦

Clustered Index의 리프 노드에는 **행의 모든 컬럼 데이터**가 저장되므로, 인덱스 검색만으로 데이터를 직접 가져올 수 있어 디스크 I/O를 절약할 수 있다.

**Secondary Index (보조 인덱스)**

Clustered Index 외의 모든 인덱스는 **Secondary Index(보조 인덱스)**다.

Secondary Index의 구조는 Clustered Index와 거의 동일하지만, 리프 노드에 저장되는 내용이 다르다:
- **Clustered Index**: 리프 노드에 행의 모든 컬럼 값 저장
- **Secondary Index**: 리프 노드에 **인덱스 컬럼 값 + Primary Key 값**만 저장

따라서 Secondary Index를 통한 조회는 다음 2단계를 거친다:
1. Secondary Index를 탐색하여 Primary Key 값을 찾음
2. 해당 Primary Key로 Clustered Index를 다시 탐색하여 실제 데이터를 가져옴

이러한 이유로 **Primary Key가 길면 Secondary Index도 비대해지므로**, 짧은 Primary Key를 사용하는 것이 유리하다.

**Unique Index**

중복을 허용하지 않는 인덱스로, `UNIQUE` 제약을 가진 컬럼에 자동으로 생성된다.

**Composite Index (복합 인덱스)**

여러 컬럼을 조합하여 만든 인덱스. 컬럼 순서가 매우 중요하며, **왼쪽부터 일치하는 컬럼(Leftmost Prefix)**까지만 인덱스가 활용된다.

예: `INDEX(col1, col2, col3)` → `WHERE col1 = ?`, `WHERE col1 = ? AND col2 = ?`는 인덱스 사용 가능하지만, `WHERE col2 = ?`는 인덱스 사용 불가

**Covering Index**

쿼리가 필요로 하는 모든 컬럼이 인덱스에 포함되어 있어, Clustered Index로 접근하지 않고 인덱스만으로 결과를 반환할 수 있는 경우. 성능 최적화에 매우 효과적이다.

**Full-Text Index**

텍스트 검색을 위한 특수 인덱스. B-Tree와는 다른 구조를 사용한다.

### 3. B-Tree vs B+Tree

**B-Tree의 구조와 한계**

B-Tree(Balanced Tree)는 이진 탐색 트리의 확장 버전으로, 하나의 노드가 여러 개의 자식을 가질 수 있는 균형 잡힌 트리 구조다.

B-Tree의 특징:
- 모든 노드(루트, 중간, 리프)에 데이터가 저장됨
- 각 노드는 정렬된 키와 포인터를 가짐
- 트리의 높이가 낮아 검색 효율이 높음

하지만 B-Tree는 다음과 같은 한계가 있다:
- **범위 검색 시 비효율**: 리프 노드가 서로 연결되어 있지 않아 범위 검색 시 트리를 여러 번 순회해야 함
- **순차 접근 어려움**: 정렬된 순서로 데이터를 읽으려면 중위 순회(In-order Traversal)가 필요

**B+Tree의 구조적 차이점**

B+Tree는 B-Tree의 변형으로, RDBMS에 최적화된 구조다.

주요 차이점:
1. **리프 노드에만 실제 데이터(또는 데이터 포인터) 저장**
   - 내부 노드(Root, Branch)는 키 값과 자식 노드 포인터만 저장
   - 리프 노드에 모든 키가 정렬되어 저장됨

2. **리프 노드 간 Linked List(이중 연결 리스트) 연결**
   - 리프 노드들이 순차적으로 연결되어 있어 순차 접근이 O(1)로 가능
   - 범위 검색 시 첫 번째 값만 찾으면 이후는 리스트 순회로 해결

3. **높은 Fanout(분기 계수)**
   - 내부 노드에 데이터가 없어 더 많은 키를 저장할 수 있음
   - 트리의 높이가 낮아져 디스크 I/O 횟수 감소

**왜 RDBMS는 B+Tree를 선택했는가**

B+Tree가 RDBMS에 적합한 이유:

1. **범위 검색 최적화**: `WHERE age BETWEEN 20 AND 30` 같은 쿼리에서, 첫 번째 값(20)만 B+Tree로 찾은 후 리프 노드의 연결 리스트를 따라가면 됨. B-Tree는 매번 트리를 순회해야 함

2. **순차 접근 효율**: `ORDER BY`나 인덱스 풀 스캔 시 리프 노드만 순차적으로 읽으면 되므로 매우 빠름

3. **페이지 단위 I/O에 최적화**: 데이터베이스는 페이지(보통 16KB) 단위로 디스크를 읽는데, B+Tree는 리프 노드가 연속적이어서 페이지 캐싱 효율이 높음

4. **안정적인 검색 성능**: 모든 데이터가 리프 노드에 있어 검색 깊이가 일정함 (B-Tree는 데이터 위치에 따라 검색 깊이가 달라질 수 있음)

예시: 이진 트리에서 20번의 탐색이 필요한 작업이 B+Tree에서는 루트 페이지가 25개의 자식을 가리킬 수 있어 단 2번의 탐색만으로 가능하다.

**B+Tree의 균형 유지 - 편향 방지**

일반적인 이진 탐색 트리(BST)는 데이터가 정렬된 순서로 삽입되면 트리가 한쪽으로 편향되어 사실상 연결 리스트처럼 동작하게 된다(O(n) 검색 시간).

AVL Tree나 Red-Black Tree는 이를 방지하기 위해 **회전(Rotation)** 연산으로 트리의 균형을 맞춘다. B+Tree도 유사하게 트리의 균형을 유지하는 메커니즘을 가지고 있다:

- **모든 리프 노드가 같은 레벨에 위치**: B+Tree는 항상 균형 잡힌 트리(Balanced Tree)를 유지
- **삽입/삭제 시 자동 재조정**: 노드 분할(Split)과 병합(Merge)을 통해 균형 유지
- **높이가 일정**: 데이터 삽입 순서와 무관하게 모든 검색의 I/O 횟수가 동일함

이진 탐색 트리의 경우:
```
순차 삽입 시 (1, 2, 3, 4, 5)
1              <- 최악의 경우 (편향된 트리)
 \
  2
   \
    3
     \
      4
       \
        5
검색 복잡도: O(n)
```

B+Tree의 경우:
```
순차 삽입 시 (1, 2, 3, 4, 5)
    [3]              <- 항상 균형 유지
   /   \
[1,2]  [3,4,5]
검색 복잡도: O(log n)
```

### 4. B+Tree의 삽입/삭제 동작 원리

B+Tree는 데이터 삽입과 삭제 시 트리의 균형을 유지하기 위해 특수한 동작을 수행한다.

**데이터 삽입 (INSERT) 동작**

B+Tree의 각 노드는 최대 키 개수 제한이 있다 (예: Order=3이면 최대 3개 키). 삽입 시 이 제한을 초과하면 **노드 분할(Page Split)**이 발생한다.

**삽입 과정:**

1. **리프 노드 탐색**: Root부터 시작하여 삽입할 위치의 리프 노드를 찾음
2. **리프 노드에 삽입**: 정렬된 순서로 키를 삽입
3. **오버플로우 확인**: 노드의 키 개수가 최대치를 초과하는지 확인
4. **분할 (필요 시)**: 오버플로우 발생 시 노드를 둘로 분할하고 중간 키를 부모로 올림
5. **상위 전파**: 부모 노드도 오버플로우가 발생하면 재귀적으로 분할 (최악의 경우 Root까지 전파되어 트리 높이 증가)

**예시: Order=3 B+Tree에 7 삽입**

```
초기 상태:
      [5]
     /   \
  [1,3]  [5,9]

7 삽입 시도:
      [5]
     /   \
  [1,3]  [5,7,9]  <- 최대 2개 초과! 분할 필요

분할 후:
      [5, 7]       <- 중간 키 7을 부모로 올림
     /   |   \
  [1,3] [5] [7,9]  <- 노드 분할

만약 부모도 오버플로우:
        [7]        <- 새로운 Root 생성 (트리 높이 +1)
       /   \
     [5]   [9]
    /  \   /  \
  [1,3][5][7][9]
```

**InnoDB의 페이지 분할 최적화**:
- InnoDB 페이지 크기: 16KB
- 페이지가 가득 차면(약 15/16) 분할 발생
- **순차 삽입**: 새 페이지를 오른쪽에 추가 → Fill Factor ~93% (효율적)
- **랜덤 삽입**: 중간에 분할 발생 → Fill Factor ~50-75% (비효율적, 공간 낭비)

**데이터 삭제 (DELETE) 동작**

삭제 시 노드의 키 개수가 최소치 미만으로 떨어지면 **노드 병합(Page Merge)** 또는 **재분배(Redistribution)**가 발생한다.

**삭제 과정:**

1. **리프 노드 탐색**: 삭제할 키가 있는 리프 노드를 찾음
2. **키 삭제**: 리프 노드에서 키 제거
3. **언더플로우 확인**: 노드의 키 개수가 최소치(보통 Order/2) 미만인지 확인
4. **재조정 (필요 시)**:
   - **재분배**: 형제 노드에 여유가 있으면 키를 빌려옴
   - **병합**: 형제 노드와 병합하여 하나의 노드로 합침
5. **상위 전파**: 병합 시 부모의 키도 제거되어 부모에서도 언더플로우 발생 가능 (재귀적 처리)

**예시: Order=3 B+Tree에서 5 삭제**

```
초기 상태:
      [5, 8]
     /   |   \
  [1,3] [5,7] [8,9]

5 삭제 시도:
      [5, 8]
     /   |   \
  [1,3] [7]  [8,9]  <- 최소 1개 이상 유지 (OK, 병합 불필요)

부모 키 업데이트:
      [7, 8]         <- 5를 7로 변경
     /   |   \
  [1,3] [7]  [8,9]

만약 [7] 노드가 비어서 언더플로우:
재분배 시도:
      [3, 8]
     /   |   \
  [1]  [3,7] [8,9]  <- 형제 [1,3]에서 3을 빌려옴

또는 병합:
      [8]            <- 부모 키 하나 제거
     /   \
  [1,3,7] [8,9]      <- 노드 병합
```

**InnoDB의 MERGE_THRESHOLD**:
- InnoDB는 페이지 Fill Factor가 `MERGE_THRESHOLD`(기본 50%) 미만으로 떨어지면 병합 시도
- 병합을 통해 인덱스 트리를 축소하고 공간 재사용
- 설정 변경 가능:
  ```sql
  CREATE INDEX idx_name ON table_name(col)
    COMMENT 'MERGE_THRESHOLD=40';
  ```

**삽입/삭제의 성능 영향**

| 동작 | 비용 | 설명 |
|------|------|------|
| **순차 삽입** | 낮음 | 페이지 분할이 오른쪽 끝에서만 발생, Fill Factor 높음 |
| **랜덤 삽입** | 높음 | 중간 페이지 분할 빈번, Fill Factor 낮아 공간 낭비 |
| **순차 삭제** | 낮음 | 병합이 한쪽 방향으로 진행 |
| **랜덤 삭제** | 중간 | 재분배/병합이 산발적으로 발생 |
| **대량 삭제** | 높음 | 많은 페이지 병합, 인덱스 트리 재구성 필요 |

**분할/병합 연쇄 반응 (Cascading)**:
- 최악의 경우 리프 노드의 분할/병합이 Root까지 전파
- 트리 높이 변경 시 전체 트리 재구성 필요
- 이로 인해 쓰기 작업(`INSERT`, `UPDATE`, `DELETE`)이 읽기보다 느림

**Auto Increment Primary Key의 장점**:
```sql
-- 권장: 순차 증가 → 오른쪽 끝에만 삽입
id INT AUTO_INCREMENT PRIMARY KEY

-- 비권장: UUID → 랜덤 삽입, 빈번한 페이지 분할
id VARCHAR(36) PRIMARY KEY DEFAULT (UUID())
```

### 5. InnoDB에서의 B+Tree 동작 원리

**페이지(Page) 단위 I/O와 B+Tree의 관계**

InnoDB는 데이터를 **페이지(Page)** 단위로 읽고 쓴다. 페이지는 InnoDB가 데이터를 저장하는 최소 단위로, 기본 크기는 **16KB**다.

B+Tree의 각 노드는 하나의 페이지에 대응되며, 페이지에는 다음이 포함된다:
- **인덱스 레코드**(키 값과 포인터)
- **페이지 헤더**(메타데이터)
- **페이지 디렉터리**(빠른 검색을 위한 슬롯)

페이지 단위 I/O의 장점:
- 한 번의 디스크 읽기로 여러 인덱스 엔트리를 메모리에 로드
- 관련된 데이터가 물리적으로 인접하여 지역성(Locality) 향상
- 버퍼 풀 캐싱 효율 증가

**Clustered Index와 B+Tree 구조**

Clustered Index는 B+Tree 구조로 구현되며, 다음과 같은 계층을 가진다:

1. **Root Node (루트 노드)**
   - 트리의 최상위 노드
   - 키 범위와 하위 노드 포인터 저장

2. **Branch Node (중간 노드)**
   - 루트와 리프 사이의 노드
   - 키 범위와 하위 노드 포인터 저장

3. **Leaf Node (리프 노드)**
   - 실제 데이터가 저장되는 노드
   - Clustered Index의 경우: **Primary Key + 행의 모든 컬럼 값** 저장
   - 리프 노드들은 이중 연결 리스트로 연결되어 순차 접근 지원

Clustered Index는 Primary Key 순서로 데이터가 물리적으로 정렬되므로, 관련된 레코드들이 가까이 위치하여 범위 검색 시 최소한의 페이지만 읽으면 된다.

**Secondary Index의 B+Tree와 Clustered Index 참조 방식**

Secondary Index도 B+Tree 구조를 사용하지만, 리프 노드의 구성이 다르다:

- **Secondary Index의 리프 노드**: `인덱스 컬럼 값 + Primary Key 값`

예시:
```sql
CREATE TABLE users (
  id INT PRIMARY KEY,        -- Clustered Index 키
  email VARCHAR(100),
  name VARCHAR(50),
  INDEX idx_email (email)    -- Secondary Index
);
```

`SELECT * FROM users WHERE email = 'test@example.com'` 실행 시:

1. **Step 1**: Secondary Index(idx_email) B+Tree 탐색
   - Root → Branch → Leaf로 탐색하여 `email = 'test@example.com'`인 레코드 찾기
   - 리프 노드에서 `(email='test@example.com', id=123)` 획득

2. **Step 2**: Clustered Index B+Tree 탐색
   - `id=123`으로 Clustered Index를 탐색
   - 리프 노드에서 `(id=123, email, name, ...)` 전체 데이터 획득

이러한 2단계 조회를 **"북마크 룩업(Bookmark Lookup)"**이라고 부른다.

**인덱스를 통한 검색 과정 (Root → Branch → Leaf)**

예시: `SELECT * FROM users WHERE id = 100` (id는 Primary Key)

```
[Root Page]
├─ 1-50 → Branch Page A
├─ 51-100 → Branch Page B
└─ 101-150 → Branch Page C

[Branch Page B]
├─ 51-75 → Leaf Page X
└─ 76-100 → Leaf Page Y

[Leaf Page Y]
├─ id=76, name='Alice', ...
├─ id=85, name='Bob', ...
├─ id=100, name='Charlie', ...  ← 찾는 데이터
└─ next → Leaf Page Z
```

탐색 과정:
1. **Root Page 읽기** (1 I/O): `id=100`은 51-100 범위 → Branch Page B로 이동
2. **Branch Page B 읽기** (1 I/O): `id=100`은 76-100 범위 → Leaf Page Y로 이동
3. **Leaf Page Y 읽기** (1 I/O): `id=100` 레코드 찾음

총 **3번의 I/O**로 데이터 검색 완료 (트리 높이 = 3)

비교: Full Table Scan은 테이블의 모든 페이지를 읽어야 하므로 수천~수만 번의 I/O 발생 가능

**B+Tree의 높이와 성능**

B+Tree의 높이는 검색 성능에 직접적인 영향을 미친다. 높이가 h이면 최대 h번의 디스크 I/O가 필요하다.

**실제 사례 계산**:

가정:
- 페이지 크기: 16KB
- Primary Key: INT (4바이트)
- 포인터: 6바이트
- 한 노드당 키 개수: 약 16KB / 10바이트 = 1,600개

| 트리 높이 | 최대 저장 가능 행 수 | I/O 횟수 |
|-----------|----------------------|----------|
| 1 (Root만) | 1,600 | 1 |
| 2 | 1,600 × 1,600 = 256만 | 2 |
| 3 | 1,600 × 1,600 × 1,600 = 40억 | 3 |
| 4 | 1,600^4 = 6.5조 | 4 |

**결론**: 수십억 행의 테이블도 B+Tree를 사용하면 **3~4번의 I/O**로 검색 가능! (단, Root와 상위 Branch 노드는 보통 버퍼 풀에 캐시되므로 실제로는 1~2번의 디스크 I/O만 발생)

### 6. B+Tree vs AVL Tree vs Red-Black Tree 비교

**B+Tree가 AVL/Red-Black Tree보다 RDBMS에 적합한 이유**

AVL Tree와 Red-Black Tree는 메모리 기반 자료구조로 설계되었으며, 각 노드가 1개의 키만 저장한다. 반면 B+Tree는 디스크 기반 저장소에 최적화되어 각 노드가 수백~수천 개의 키를 저장한다.

| 특성 | B+Tree | AVL Tree | Red-Black Tree |
|------|--------|----------|----------------|
| **균형 조건** | 모든 리프 동일 레벨 | 좌우 서브트리 높이 차 ≤ 1 | 블랙 노드 높이 균형 |
| **균형 유지 방법** | 분할/병합 | 회전 (단일/이중) | 회전 + 색상 변경 |
| **노드당 키 개수** | 수백~수천 개 | 1개 | 1개 |
| **트리 높이** | 매우 낮음 (log_m n) | 중간 (log₂ n) | 중간 (log₂ n) |
| **100만 행 기준 높이** | 2~3 | ~20 | ~20 |
| **디스크 I/O 횟수** | 2~3번 | ~20번 | ~20번 |
| **디스크 I/O 최적화** | 페이지 단위 (16KB) | 노드별 (수십 바이트) | 노드별 (수십 바이트) |
| **범위 검색** | 매우 빠름 (리스트 순회) | 느림 (중위 순회) | 느림 (중위 순회) |
| **삽입/삭제 복잡도** | O(log_m n) | O(log₂ n) | O(log₂ n) |
| **순차 삽입 성능** | 우수 (분할 최소화) | 보통 (회전 빈번) | 우수 (회전 적음) |
| **캐시 지역성** | 높음 (연속 페이지) | 낮음 (분산 노드) | 낮음 (분산 노드) |
| **주 사용처** | 데이터베이스, 파일 시스템 | 메모리 내 정렬 컨테이너 | C++ STL (map, set), Java TreeMap |

**편향 방지 비교**

모든 트리는 편향을 방지하기 위한 균형 유지 메커니즘을 가진다:

```
1, 2, 3, 4, 5 순차 삽입 시:

[일반 BST - 편향됨]
1
 \
  2
   \
    3
     \
      4
       \
        5
검색: O(n) - 최악!

[AVL Tree - 회전으로 균형 유지]
    3
   / \
  2   4
 /     \
1       5
검색: O(log n) - 회전 빈번

[Red-Black Tree - 색상+회전으로 균형 유지]
    3(B)
   / \
  2(R) 4(B)
 /       \
1(B)     5(R)
검색: O(log n) - AVL보다 회전 적음

[B+Tree - 분할로 균형 유지]
     [3]
    /   \
 [1,2]  [3,4,5]
검색: O(log_m n) - 높이 최소
```

**왜 RDBMS는 AVL/Red-Black Tree 대신 B+Tree를 사용하는가?**

1. **디스크 I/O 횟수 차이**:
   - AVL/Red-Black Tree: 노드 1개 읽기 = 1번 I/O → 20번 I/O (100만 행 기준)
   - B+Tree: 페이지(노드) 1개에 수백 개 키 → 2~3번 I/O (100만 행 기준)
   - 디스크 I/O는 메모리 접근보다 **10,000배 이상 느리므로** I/O 횟수 감소가 핵심

2. **높이 차이**:
   ```
   100만 행 테이블 기준:
   - AVL Tree: log₂(1,000,000) ≈ 20 (높이 20)
   - B+Tree (Fanout=1600): log₁₆₀₀(1,000,000) ≈ 2.3 (높이 2~3)
   ```

3. **페이지 단위 I/O 활용**:
   - 디스크는 페이지(보통 4KB~16KB) 단위로 읽음
   - AVL/Red-Black Tree: 노드 크기 << 페이지 크기 → 페이지 낭비
   - B+Tree: 노드 크기 = 페이지 크기 → 페이지를 꽉 채워 효율적 활용

4. **범위 검색 효율**:
   ```sql
   SELECT * FROM users WHERE age BETWEEN 20 AND 30;
   ```
   - AVL/Red-Black Tree: 트리를 중위 순회하며 각 노드마다 I/O 발생
   - B+Tree: 20을 찾은 후 리프 노드 연결 리스트를 순차 순회 (연속 페이지 읽기)

5. **순차 삽입 성능**:
   ```sql
   INSERT INTO logs (id, ...) VALUES (AUTO_INCREMENT, ...);
   ```
   - AVL Tree: 삽입마다 회전 연산으로 균형 조정 (빈번한 재조정)
   - Red-Black Tree: 회전 빈도 낮음 (AVL보다 우수)
   - B+Tree: 오른쪽 끝 페이지에만 추가 → 분할 최소화 (가장 우수)

6. **캐시 지역성**:
   - B+Tree: 관련 키들이 같은 페이지에 연속 저장 → CPU 캐시 히트율 높음
   - AVL/Red-Black Tree: 노드들이 메모리에 분산 → 캐시 미스 빈번

**실전 성능 비교 (100만 행 테이블)**

| 연산 | B+Tree | AVL Tree | Red-Black Tree |
|------|--------|----------|----------------|
| 단일 조회 | 2~3 I/O | ~20 I/O | ~20 I/O |
| 범위 조회 (1000건) | 3 I/O + 순차 읽기 | ~20,000 I/O | ~20,000 I/O |
| 순차 삽입 | 분할 드묾 | 회전 빈번 | 회전 적음 |
| 랜덤 삽입 | 분할 빈번 | 회전 빈번 | 회전 적음 |

**결론**: 메모리 기반에서는 AVL/Red-Black Tree가 효율적이지만, **디스크 기반 저장소**에서는 B+Tree가 압도적으로 유리하다. 이것이 모든 주요 RDBMS(MySQL, PostgreSQL, Oracle, SQL Server)와 파일 시스템(NTFS, ext4, HFS+)이 B+Tree를 채택한 이유다.

### 7. 인덱스 설계 시 고려사항

**카디널리티(Cardinality)와 선택도(Selectivity)**

- **카디널리티(Cardinality)**: 컬럼에 저장된 고유한 값의 개수
  - 예: 성별(남/여) → 카디널리티 = 2 (낮음)
  - 예: 이메일(중복 불가) → 카디널리티 = 행 개수 (높음)

- **선택도(Selectivity)**: 전체 레코드 중 특정 값이 차지하는 비율
  - 선택도 = 고유한 값의 개수 / 전체 행 개수
  - 선택도가 높을수록(1에 가까울수록) 인덱스 효율이 좋음

**인덱스 효율 원칙**:
- **카디널리티가 높은 컬럼**에 인덱스를 생성해야 효과적
- 카디널리티가 낮은 컬럼(성별, 활성화 여부 등)은 인덱스 효과가 미미함

**복합 인덱스에서의 컬럼 순서 중요성**

복합 인덱스는 컬럼 순서가 성능에 큰 영향을 미친다.

예시: `INDEX(col1, col2, col3)`

인덱스 사용 가능:
- `WHERE col1 = ?`
- `WHERE col1 = ? AND col2 = ?`
- `WHERE col1 = ? AND col2 = ? AND col3 = ?`
- `WHERE col1 = ? AND col3 = ?` (col1만 인덱스 사용)

인덱스 사용 불가:
- `WHERE col2 = ?`
- `WHERE col3 = ?`
- `WHERE col2 = ? AND col3 = ?`

**복합 인덱스 컬럼 순서 결정 기준**:
1. **등호(=) 조건이 자주 사용되는 컬럼을 앞에**
2. **카디널리티가 높은 컬럼을 앞에**
3. **정렬(ORDER BY)이나 그룹핑(GROUP BY)에 사용되는 컬럼 고려**

예시:
```sql
-- 잘못된 순서
INDEX(gender, email)  -- gender는 카디널리티 낮음

-- 올바른 순서
INDEX(email, gender)  -- email은 카디널리티 높음
```

**인덱스가 사용되지 않는 경우 (인덱스 무효화 조건)**

다음 경우 인덱스가 무효화되어 Full Table Scan이 발생한다:

1. **인덱스 컬럼을 함수나 연산에 사용**
   ```sql
   -- 인덱스 사용 불가
   WHERE YEAR(created_at) = 2026
   WHERE price * 1.1 > 10000

   -- 인덱스 사용 가능
   WHERE created_at >= '2026-01-01' AND created_at < '2027-01-01'
   WHERE price > 10000 / 1.1
   ```

2. **LIKE의 선행 와일드카드**
   ```sql
   -- 인덱스 사용 불가
   WHERE name LIKE '%kim'
   WHERE name LIKE '%kim%'

   -- 인덱스 사용 가능
   WHERE name LIKE 'kim%'
   ```

3. **데이터 타입 불일치**
   ```sql
   -- user_id가 VARCHAR인 경우
   WHERE user_id = 123  -- 암묵적 형변환 발생, 인덱스 무효화
   WHERE user_id = '123'  -- 인덱스 사용 가능
   ```

4. **부정형 조건**
   ```sql
   WHERE status != 'DELETED'  -- 인덱스 효율 낮음
   WHERE status NOT IN ('DELETED', 'INACTIVE')  -- 인덱스 효율 낮음
   ```

5. **OR 조건 (인덱스가 없는 컬럼과 함께 사용 시)**
   ```sql
   -- name에만 인덱스가 있는 경우
   WHERE name = 'kim' OR age = 30  -- Full Table Scan 발생
   ```

**인덱스의 쓰기 성능 오버헤드 (INSERT, UPDATE, DELETE)**

인덱스는 조회 성능을 향상시키지만, 쓰기 작업에는 부담을 준다:

- **INSERT**:
  - 테이블에 데이터 삽입
  - 모든 인덱스에도 새 엔트리 추가 (B+Tree 재정렬 및 페이지 분할 가능)

- **UPDATE**:
  - 인덱스 컬럼 값이 변경되면 기존 인덱스 엔트리 삭제 + 새 엔트리 추가

- **DELETE**:
  - 테이블 데이터 삭제
  - 모든 인덱스에서도 해당 엔트리 제거

**페이지 분할(Page Split)**:
- 인덱스 페이지가 꽉 차면 새 페이지를 할당하고 데이터를 분할
- 랜덤 순서로 INSERT하면 페이지 Fill Factor가 50~93%로 낮아져 공간 낭비 발생
- 순차 순서로 INSERT하면 페이지 Fill Factor가 약 93%로 효율적

**인덱스 개수와 성능 트레이드오프**:
- 읽기가 많은 시스템: 인덱스 많이 생성
- 쓰기가 많은 시스템: 필수 인덱스만 유지 (과도한 인덱스는 성능 저하)

일반적으로 테이블당 3~5개의 인덱스가 적절하며, 사용되지 않는 인덱스는 정기적으로 제거해야 한다.

### 8. 실행 계획(EXPLAIN)으로 인덱스 활용 확인

**EXPLAIN이란?**

`EXPLAIN`은 MySQL이 쿼리를 어떻게 실행할 계획인지 보여주는 명령어다. 인덱스가 제대로 사용되는지 확인하는 가장 중요한 도구다.

```sql
EXPLAIN SELECT * FROM users WHERE email = 'test@example.com';
```

**EXPLAIN 결과의 주요 컬럼**

| 컬럼 | 설명 |
|------|------|
| **id** | SELECT 쿼리의 순서 (서브쿼리나 UNION이 있을 때 여러 개 표시) |
| **select_type** | 쿼리 유형 (SIMPLE, PRIMARY, SUBQUERY, DERIVED 등) |
| **table** | 접근하는 테이블 이름 |
| **type** | 조인 타입 (인덱스 사용 방식) - **가장 중요** |
| **possible_keys** | 사용 가능한 인덱스 목록 |
| **key** | 실제 사용된 인덱스 (NULL이면 인덱스 미사용) |
| **key_len** | 사용된 인덱스의 바이트 길이 (복합 인덱스에서 몇 개 컬럼이 사용되었는지 확인 가능) |
| **ref** | 인덱스와 비교되는 컬럼이나 상수 |
| **rows** | 검사할 것으로 예상되는 행 수 (적을수록 좋음) |
| **filtered** | 조건에 의해 필터링될 행의 비율 (%) |
| **Extra** | 추가 정보 (Using index, Using where, Using filesort 등) |

**type 컬럼 값 (성능 좋음 → 나쁨 순서)**

| type | 설명 | 성능 |
|------|------|------|
| **system** | 테이블에 단 하나의 행만 존재 (시스템 테이블) | ⭐⭐⭐⭐⭐ 최고 |
| **const** | PRIMARY KEY나 UNIQUE 인덱스로 단 하나의 행 조회 | ⭐⭐⭐⭐⭐ 최고 |
| **eq_ref** | 조인 시 PRIMARY KEY나 UNIQUE 인덱스로 정확히 1개 행 매칭 | ⭐⭐⭐⭐⭐ 최고 |
| **ref** | 인덱스를 사용하여 여러 행 조회 (=, IN 사용) | ⭐⭐⭐⭐ 좋음 |
| **range** | 인덱스를 사용한 범위 검색 (BETWEEN, >, < 등) | ⭐⭐⭐ 괜찮음 |
| **index** | 인덱스 풀 스캔 (인덱스 전체를 읽음) | ⭐⭐ 느림 |
| **ALL** | Full Table Scan (테이블 전체를 읽음) | ⭐ 매우 느림 |

**ref, range, index, ALL의 차이**

1. **const / eq_ref**
   ```sql
   -- const: PRIMARY KEY로 단일 행 조회
   SELECT * FROM users WHERE id = 100;

   -- eq_ref: 조인 시 정확히 1개 매칭
   SELECT * FROM orders o
   JOIN users u ON o.user_id = u.id;  -- u 테이블이 eq_ref
   ```

2. **ref**
   ```sql
   -- Non-unique 인덱스를 사용하여 여러 행 조회
   SELECT * FROM users WHERE status = 'ACTIVE';
   -- status 컬럼에 인덱스가 있으면 ref 타입

   SELECT * FROM users WHERE email = 'test@example.com';
   -- email에 일반 인덱스가 있으면 ref, UNIQUE 인덱스면 const
   ```

3. **range**
   ```sql
   -- 범위 조건으로 인덱스 사용
   SELECT * FROM users WHERE age BETWEEN 20 AND 30;
   SELECT * FROM users WHERE created_at > '2026-01-01';
   SELECT * FROM users WHERE id IN (1, 2, 3);
   ```

4. **index**
   ```sql
   -- 인덱스는 사용하지만 인덱스 전체를 스캔
   SELECT id FROM users;  -- Covering Index면 index 타입
   -- 테이블 접근 없이 인덱스만으로 해결 가능한 경우
   ```

5. **ALL**
   ```sql
   -- Full Table Scan (인덱스 미사용)
   SELECT * FROM users WHERE YEAR(created_at) = 2026;
   -- 함수 사용으로 인덱스 무효화
   ```

**Extra 컬럼의 주요 값**

| Extra | 의미 |
|-------|------|
| **Using index** | Covering Index로 인덱스만으로 쿼리 해결 (매우 좋음) |
| **Using where** | WHERE 절로 필터링 (일반적) |
| **Using index condition** | Index Condition Pushdown (ICP) 사용 |
| **Using filesort** | 정렬을 위해 별도 정렬 작업 수행 (느림) |
| **Using temporary** | 임시 테이블 사용 (매우 느림) |
| **Using join buffer** | 조인 버퍼 사용 (인덱스 없이 조인, 느림) |

**실전 예시**

```sql
EXPLAIN SELECT name, email FROM users WHERE status = 'ACTIVE' ORDER BY created_at;
```

```
+----+-------------+-------+------+---------------+-------------+---------+-------+------+-----------------------------+
| id | select_type | table | type | possible_keys | key         | key_len | ref   | rows | Extra                       |
+----+-------------+-------+------+---------------+-------------+---------+-------+------+-----------------------------+
|  1 | SIMPLE      | users | ref  | idx_status    | idx_status  | 10      | const |  500 | Using where; Using filesort |
+----+-------------+-------+------+---------------+-------------+---------+-------+------+-----------------------------+
```

분석:
- `type = ref`: status 인덱스 사용 ✅
- `rows = 500`: 500개 행 검사 예상
- `Using filesort`: 정렬을 위해 추가 작업 필요 ⚠️
  - 개선: `INDEX(status, created_at)` 복합 인덱스 생성

개선 후:
```sql
CREATE INDEX idx_status_created ON users(status, created_at);
EXPLAIN SELECT name, email FROM users WHERE status = 'ACTIVE' ORDER BY created_at;
```

```
+----+-------------+-------+------+---------------+--------------------+---------+-------+------+-------------+
| id | select_type | table | type | possible_keys | key                | key_len | ref   | rows | Extra       |
+----+-------------+-------+------+---------------+--------------------+---------+-------+------+-------------+
|  1 | SIMPLE      | users | ref  | idx_status_.. | idx_status_created | 10      | const |  500 | Using where |
+----+-------------+-------+------+---------------+--------------------+---------+-------+------+-------------+
```

- `Using filesort` 사라짐 ✅ (인덱스 순서가 created_at 정렬 순서와 일치)

## 핵심 정리

- **인덱스는 B+Tree 구조**로 구현되며, 리프 노드만 데이터를 저장하고 리프 노드 간 연결 리스트로 범위 검색을 최적화한다
- **B+Tree는 AVL Tree처럼 균형을 유지**하여 트리 편향을 방지하며, 노드 분할(Split)과 병합(Merge)을 통해 모든 리프 노드를 같은 레벨에 유지한다
- **삽입 시 페이지 분할**, **삭제 시 페이지 병합**이 발생하며, 순차 삽입은 효율적이지만 랜덤 삽입은 빈번한 분할로 인해 Fill Factor가 낮아진다
- **Clustered Index**는 테이블 자체이며 Primary Key 순서로 물리적 정렬되고, 리프 노드에 모든 컬럼 값을 저장한다
- **Secondary Index**는 인덱스 컬럼과 Primary Key만 저장하며, 조회 시 Clustered Index를 2차 탐색한다 (Bookmark Lookup)
- **복합 인덱스**는 왼쪽부터 순서대로 매칭되어야 사용 가능하며(Leftmost Prefix), 컬럼 순서가 성능에 큰 영향을 미친다
- **카디널리티가 높은 컬럼**에 인덱스를 생성해야 효과적이며, 인덱스 컬럼에 함수나 연산을 사용하면 인덱스가 무효화된다
- 페이지 기본 크기는 **16KB**이며, B+Tree의 각 노드는 하나의 페이지에 대응되어 디스크 I/O를 최소화한다
- **B+Tree는 AVL/Red-Black Tree보다 디스크 I/O 최적화**에 유리하며, 100만 행 기준 2~3번 I/O vs ~20번 I/O의 차이를 보인다
- 인덱스는 **조회 성능**을 향상시키지만 `INSERT`, `UPDATE`, `DELETE` 시 모든 인덱스를 갱신해야 하므로 쓰기 성능에 오버헤드가 발생한다
- **EXPLAIN**의 `type` 컬럼은 인덱스 사용 방식을 보여주며, `const` > `ref` > `range` > `index` > `ALL` 순으로 성능이 좋다
- **Covering Index**를 활용하면 인덱스만으로 쿼리를 해결할 수 있어(`Using index`) 테이블 접근을 생략하고 최고의 성능을 달성할 수 있다
- 인덱스 개수는 적절히 유지해야 하며(보통 3~5개), 사용되지 않는 인덱스는 정기적으로 제거해야 한다

## 키워드

### Index (인덱스)
데이터베이스 테이블에서 검색 속도를 향상시키기 위한 자료구조. MySQL에서는 기본적으로 B+Tree 구조를 사용하며, 책의 색인과 유사하게 특정 값을 빠르게 찾을 수 있도록 정렬된 별도 구조를 유지한다.

### B+Tree
B-Tree의 개선된 형태로, 리프 노드에만 실제 데이터를 저장하고 리프 노드들을 연결 리스트로 연결한 자료구조. 범위 검색과 순차 접근에 최적화되어 RDBMS에서 인덱스 구조로 채택되었다.

### B-Tree
Balanced Tree의 약자로, 하나의 노드가 여러 자식을 가질 수 있는 균형 잡힌 트리 구조. 모든 노드에 데이터가 저장되지만, 범위 검색 시 비효율적이어서 B+Tree에 비해 RDBMS에서 덜 사용된다.

### Clustered Index (클러스터형 인덱스)
InnoDB 테이블의 물리적 저장 구조를 결정하는 특수한 인덱스. Primary Key 순서로 데이터를 물리적으로 정렬하며, 리프 노드에 행의 모든 컬럼 값을 저장한다. 테이블당 1개만 존재하며, Clustered Index 자체가 테이블이다.

### Secondary Index (보조 인덱스)
Clustered Index 외의 모든 인덱스. 리프 노드에 인덱스 컬럼 값과 Primary Key 값만 저장하며, 실제 데이터 조회 시 Clustered Index를 2차 탐색한다. Primary Key가 길면 Secondary Index도 비대해지므로 짧은 Primary Key 사용이 권장된다.

### Covering Index (커버링 인덱스)
쿼리가 필요로 하는 모든 컬럼이 인덱스에 포함되어, Clustered Index 접근 없이 인덱스만으로 결과를 반환할 수 있는 상태. EXPLAIN 결과에서 `Using index`로 표시되며, 최고의 조회 성능을 제공한다.

### Cardinality (카디널리티)
컬럼에 저장된 고유한 값의 개수. 카디널리티가 높을수록(고유 값이 많을수록) 인덱스 효율이 좋다. 예를 들어 이메일은 카디널리티가 높지만, 성별(남/여)은 카디널리티가 낮아 인덱스 효과가 미미하다.

### EXPLAIN (실행 계획)
MySQL이 쿼리를 어떻게 실행할 계획인지 보여주는 명령어. `type`, `key`, `rows`, `Extra` 등의 컬럼을 통해 인덱스 사용 여부와 성능을 분석할 수 있다. `type`이 `const`, `ref`, `range`이면 인덱스를 잘 사용하는 것이고, `ALL`이면 Full Table Scan이 발생한다.

### Page I/O (페이지 입출력)
InnoDB는 데이터를 페이지(기본 16KB) 단위로 읽고 쓴다. B+Tree의 각 노드는 하나의 페이지에 대응되며, 한 번의 디스크 I/O로 여러 인덱스 엔트리를 메모리에 로드할 수 있다. 페이지 단위 I/O는 캐싱 효율과 지역성을 향상시킨다.

### Composite Index (복합 인덱스)
여러 컬럼을 조합하여 만든 인덱스. 컬럼 순서가 매우 중요하며, 왼쪽부터 일치하는 컬럼까지만 인덱스가 활용된다(Leftmost Prefix Rule). 예를 들어 `INDEX(a, b, c)`는 `WHERE a=?`, `WHERE a=? AND b=?`에는 사용되지만, `WHERE b=?`에는 사용되지 않는다.

## 참고 자료
- [MySQL 8.4 Reference Manual - Comparison of B-Tree and Hash Indexes](https://dev.mysql.com/doc/en/index-btree-hash.html)
- [MySQL 8.0 Reference Manual - The Physical Structure of an InnoDB Index](https://dev.mysql.com/doc/refman/8.0/en/innodb-physical-structure.html)
- [MySQL 9.1 Reference Manual - Clustered and Secondary Indexes](https://dev.mysql.com/doc/refman/9.1/en/innodb-index-types.html)
- [PlanetScale - B+ trees (MySQL for Developers)](https://planetscale.com/learn/courses/mysql-for-developers/indexes/b-trees)
