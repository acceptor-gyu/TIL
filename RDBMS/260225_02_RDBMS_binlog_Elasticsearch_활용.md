# RDBMS, binlog, Elasticsearch 함께 활용해서 성능 좋은 DB 역할하기

## 개요
RDBMS(MySQL)를 데이터의 원본 저장소로 사용하면서, Binary Log(binlog)를 통해 Elasticsearch로 실시간 데이터 동기화를 구현하여 강력한 검색 성능과 데이터 정합성을 동시에 확보하는 아키텍처를 정리한다.

## 상세 내용

### 1. 왜 RDBMS + Elasticsearch 조합인가?

**RDBMS의 한계**

MySQL 같은 RDBMS는 트랜잭션, ACID 보장, 관계형 데이터 관리에 강하지만 검색 성능에는 한계가 있음:

```sql
-- 성능이 매우 느린 검색 쿼리들
SELECT * FROM products WHERE name LIKE '%키워드%';  -- Full Table Scan
SELECT * FROM articles WHERE MATCH(content) AGAINST('검색어');  -- Full-Text Index도 한계
SELECT * FROM users WHERE JSON_EXTRACT(metadata, '$.tags') LIKE '%태그%';  -- JSON 검색 느림
```

**문제점:**
- LIKE 검색: 인덱스를 타지 못하는 경우 Full Table Scan 발생
- Full-Text Search: 한글 형태소 분석 부족, 유사도 검색 미지원
- 복잡한 필터링: 여러 조건을 조합한 검색 시 성능 저하
- 스케일링: 검색 트래픽이 증가하면 RDBMS 부하 집중

**Elasticsearch의 강점**

Elasticsearch는 **역인덱스(Inverted Index)** 기반으로 빠른 검색에 특화됨:

1. **역인덱스**: 단어 → 문서 매핑으로 O(1) 검색 속도
2. **형태소 분석**: 한글/영문 형태소 분석기로 자연어 검색 지원
3. **유사도 검색**: TF-IDF, BM25 알고리즘으로 검색 랭킹
4. **집계(Aggregation)**: 복잡한 통계/분석 쿼리를 빠르게 처리
5. **스케일링**: 샤드 분산으로 수평 확장 용이

**CQRS 관점에서의 역할 분리**

**CQRS (Command Query Responsibility Segregation)**: 쓰기 모델과 읽기 모델을 분리

```
┌─────────────────────────────────────────────────┐
│             Application Layer                   │
└─────────────────────────────────────────────────┘
          │                           │
      [Command]                    [Query]
        (쓰기)                        (읽기)
          │                           │
          ▼                           ▼
   ┌──────────┐                ┌──────────────┐
   │  MySQL   │ ─── CDC ────▶  │Elasticsearch │
   │(Primary) │   (binlog)     │  (검색 전용)   │
   └──────────┘                └──────────────┘
   - ACID 보장                  - 빠른 검색
   - 트랜잭션                   - 형태소 분석
   - 정합성                     - 집계/통계
```

**장점:**
- MySQL: 데이터 정합성과 트랜잭션 보장 (Single Source of Truth)
- Elasticsearch: 검색 성능 극대화
- 각 시스템을 독립적으로 스케일링 가능
- MySQL 장애 시에도 검색 서비스 제공 가능 (읽기 가용성)

### 2. 데이터 동기화 방식 비교

**1) 애플리케이션 레벨 Dual Write (동시 쓰기)**

애플리케이션이 MySQL과 Elasticsearch에 동시에 데이터를 쓰는 방식

```java
@Transactional
public void createProduct(Product product) {
    // 1. MySQL에 저장
    productRepository.save(product);

    // 2. Elasticsearch에 저장
    elasticsearchTemplate.save(product);
}
```

**장점:**
- 구현이 간단하고 직관적
- 실시간 동기화 가능

**단점:**
- **정합성 문제**: MySQL은 성공했지만 Elasticsearch는 실패할 수 있음 (Partial Failure)
- **성능 저하**: 쓰기 작업 시 두 시스템 모두 응답 대기
- **코드 복잡도**: 비즈니스 로직에 인프라 관심사가 섞임
- **트랜잭션 불일치**: 분산 트랜잭션 미지원 시 데이터 불일치 발생

**2) Polling 기반 동기화 (주기적 배치)**

주기적으로 MySQL을 조회하여 변경된 데이터를 Elasticsearch에 동기화

```java
@Scheduled(fixedDelay = 60000)  // 1분마다
public void syncToElasticsearch() {
    LocalDateTime lastSyncTime = getLastSyncTime();
    List<Product> changedProducts = productRepository
        .findByUpdatedAtAfter(lastSyncTime);

    elasticsearchTemplate.saveAll(changedProducts);
    updateLastSyncTime(LocalDateTime.now());
}
```

**장점:**
- 구현이 비교적 간단
- MySQL 부하 조절 가능 (배치 주기 조정)

**단점:**
- **Latency**: 최대 배치 주기만큼 지연 발생 (실시간성 부족)
- **삭제 감지 어려움**: DELETE된 데이터를 추적하기 어려움 (soft delete 필요)
- **MySQL 부하**: 큰 테이블에서 변경 데이터 조회 시 부하 발생
- **스케일링 한계**: 데이터가 많아지면 배치 시간 증가

**3) Binary Log 기반 CDC (Change Data Capture)** ⭐ 권장

MySQL의 Binary Log(binlog)를 실시간으로 읽어서 변경 사항을 Elasticsearch에 전파

```
[MySQL]
  ├─ Binary Log (INSERT, UPDATE, DELETE 이벤트)
  └─ CDC Tool (Debezium) ─▶ [Kafka] ─▶ [Elasticsearch Sink Connector]
```

**장점:**
- ✅ **정합성**: MySQL이 Single Source of Truth, 모든 변경 사항 캡처
- ✅ **실시간성**: 밀리초 단위 지연으로 거의 실시간 동기화
- ✅ **애플리케이션 독립적**: 코드 변경 없이 동기화 가능
- ✅ **삭제 감지**: DELETE 이벤트도 정확히 캡처
- ✅ **스케일링**: Kafka를 통한 버퍼링으로 대용량 처리 가능
- ✅ **재처리**: Kafka Offset 관리로 실패 시 재처리 가능

**단점:**
- 인프라 복잡도 증가 (Kafka, Debezium 필요)
- 운영 난이도 높음 (Kafka Connect, Schema Registry 등)

**비교표**

| 방식 | 실시간성 | 정합성 | 구현 난이도 | 운영 난이도 | 권장 |
|------|----------|--------|-------------|-------------|------|
| Dual Write | ⭐⭐⭐ | ⚠️ 낮음 | 쉬움 | 쉬움 | ❌ |
| Polling | ⭐ | ⭐⭐ | 쉬움 | 보통 | △ |
| CDC (binlog) | ⭐⭐⭐ | ⭐⭐⭐ | 어려움 | 어려움 | ✅ |

**결론**: 프로덕션 환경에서는 **CDC (binlog) 방식**을 권장

### 3. CDC (Change Data Capture) 핵심 개념

**CDC란 무엇인가**

CDC는 데이터베이스의 **변경 사항(INSERT, UPDATE, DELETE)을 실시간으로 감지하고 캡처**하는 기술

**CDC의 핵심 원리**:
1. 데이터베이스의 트랜잭션 로그(MySQL: binlog, PostgreSQL: WAL)를 모니터링
2. 로그에서 변경 이벤트를 읽어옴
3. 변경 이벤트를 구조화된 데이터로 변환
4. 다른 시스템(Elasticsearch, Data Warehouse 등)으로 전송

**Binary Log를 CDC 소스로 활용하는 원리**

MySQL의 Binary Log는 모든 데이터 변경 작업을 기록하는 트랜잭션 로그:

```sql
-- my.cnf 설정
[mysqld]
log-bin = mysql-bin
binlog_format = ROW  -- CDC를 위해 ROW 포맷 필수
binlog_row_image = FULL  -- 변경 전후 모든 컬럼 기록
server-id = 1
```

**binlog 이벤트 예시**:
```
# INSERT 이벤트
BEGIN
TABLE_MAP(products)
WRITE_ROWS:
  - row: {id: 1, name: "노트북", price: 1000000, created_at: "2026-02-25 10:00:00"}
COMMIT

# UPDATE 이벤트
BEGIN
TABLE_MAP(products)
UPDATE_ROWS:
  - before: {id: 1, name: "노트북", price: 1000000}
  - after:  {id: 1, name: "노트북", price: 900000}
COMMIT

# DELETE 이벤트
BEGIN
TABLE_MAP(products)
DELETE_ROWS:
  - row: {id: 1, name: "노트북", price: 900000}
COMMIT
```

**CDC 도구가 하는 일**:
1. MySQL에 Replication Slave처럼 연결
2. binlog를 실시간으로 읽음
3. 바이너리 이벤트를 JSON 같은 구조화된 포맷으로 변환
4. Kafka 같은 메시지 큐로 전송

**CDC 도구 비교: Debezium, Maxwell, Canal**

**1. Debezium** (가장 인기 있음, 권장)
- **제작사**: Red Hat (오픈소스)
- **지원 DB**: MySQL, PostgreSQL, MongoDB, SQL Server, Oracle 등
- **아키텍처**: Kafka Connect 기반
- **장점**:
  - 엔터프라이즈급 안정성
  - 풍부한 문서와 커뮤니티
  - Kafka Connect 생태계 활용 가능
  - Schema Registry 통합
  - Exactly-Once 시멘틱 지원 (Kafka 트랜잭션)
- **단점**:
  - Kafka 필수 (인프라 복잡도)
  - 러닝 커브 높음
- **사용 사례**: 엔터프라이즈 환경, 다양한 DB 통합

**2. Maxwell**
- **제작사**: Zendesk (오픈소스)
- **지원 DB**: MySQL만 지원
- **아키텍처**: 독립형 애플리케이션 또는 Kafka 연동
- **장점**:
  - 가볍고 간단함
  - Kafka 없이도 사용 가능 (stdout, Kinesis, RabbitMQ 등 지원)
  - JSON 출력이 간결
- **단점**:
  - MySQL만 지원
  - Debezium보다 기능이 적음
  - Schema 관리 기능 부족
- **사용 사례**: MySQL 전용, 간단한 CDC 요구사항

**예시 출력**:
```json
{
  "database": "shop",
  "table": "products",
  "type": "insert",
  "ts": 1708848000,
  "data": {"id": 1, "name": "노트북", "price": 1000000}
}
```

**3. Canal**
- **제작사**: Alibaba (오픈소스)
- **지원 DB**: MySQL만 지원
- **아키텍처**: 독립형 애플리케이션
- **장점**:
  - 중국에서 대규모 프로덕션 검증
  - RocketMQ, Kafka 연동
- **단점**:
  - 문서가 주로 중국어
  - 커뮤니티가 중국 중심
  - Debezium보다 글로벌 생태계 작음
- **사용 사례**: Alibaba Cloud 생태계, 중국 시장

**비교표**

| 특성 | Debezium | Maxwell | Canal |
|------|----------|---------|-------|
| **지원 DB** | MySQL, PostgreSQL, MongoDB 등 | MySQL만 | MySQL만 |
| **Kafka 필수** | ✅ | ❌ (선택) | ❌ (선택) |
| **안정성** | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ |
| **문서/커뮤니티** | ⭐⭐⭐ | ⭐⭐ | ⭐ (중국어) |
| **구현 난이도** | 높음 | 낮음 | 중간 |
| **권장** | ✅ 엔터프라이즈 | △ 간단한 용도 | △ Alibaba 생태계 |

**권장**: 대부분의 경우 **Debezium** 사용 권장 (안정성, 확장성, 생태계)

### 4. Debezium을 활용한 binlog → Elasticsearch 파이프라인

**전체 아키텍처**

```
┌──────────┐   binlog    ┌───────────────┐   Kafka     ┌─────────────┐   HTTP     ┌──────────────┐
│  MySQL   │──streaming─▶│  Debezium     │──messages──▶│   Kafka     │───bulk────▶│Elasticsearch │
│ (Primary)│             │MySQL Connector│             │  (buffer)   │            │              │
└──────────┘             └───────────────┘             └─────────────┘            └──────────────┘
     │                           │                           │                           │
     └─ Binary Log               └─ Kafka Connect            └─ Topic: shop.products     └─ Index
```

**구성 요소**:
1. **MySQL**: binlog 활성화 (ROW 포맷)
2. **Debezium MySQL Connector**: binlog를 읽어 Kafka로 전송
3. **Kafka**: 변경 이벤트 버퍼링 및 전달
4. **Elasticsearch Sink Connector**: Kafka에서 Elasticsearch로 색인

**Kafka Connect 기반 파이프라인 구성**

**1단계: MySQL binlog 설정**

```sql
-- my.cnf
[mysqld]
server-id = 1
log_bin = mysql-bin
binlog_format = ROW
binlog_row_image = FULL
expire_logs_days = 7

-- Replication 사용자 생성
CREATE USER 'debezium'@'%' IDENTIFIED BY 'password';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
```

**2단계: Kafka 및 Kafka Connect 실행**

docker-compose.yml:
```yaml
version: '3'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  kafka-connect:
    image: debezium/connect:2.5
    depends_on: [kafka]
    ports:
      - "8083:8083"
    environment:
      BOOTSTRAP_SERVERS: kafka:9092
      GROUP_ID: 1
      CONFIG_STORAGE_TOPIC: connect_configs
      OFFSET_STORAGE_TOPIC: connect_offsets
      STATUS_STORAGE_TOPIC: connect_status

  elasticsearch:
    image: elasticsearch:8.11.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
    ports:
      - "9200:9200"
```

**3단계: Debezium MySQL Connector 설정**

```bash
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d '{
  "name": "mysql-source-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "tasks.max": "1",
    "database.hostname": "mysql",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "password",
    "database.server.id": "184054",
    "database.server.name": "shop",
    "table.include.list": "shop.products,shop.orders",
    "database.history.kafka.bootstrap.servers": "kafka:9092",
    "database.history.kafka.topic": "schema-changes.shop",
    "include.schema.changes": "true",
    "transforms": "route",
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": "([^.]+)\\.([^.]+)\\.([^.]+)",
    "transforms.route.replacement": "$3"
  }
}'
```

**주요 설정 설명**:
- `table.include.list`: 동기화할 테이블 지정
- `database.server.name`: Kafka Topic 접두사 (shop.products → products)
- `transforms`: Topic 이름 변환 (정규식 라우팅)

**4단계: Elasticsearch Sink Connector 설정**

```bash
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d '{
  "name": "elasticsearch-sink-connector",
  "config": {
    "connector.class": "io.confluent.connect.elasticsearch.ElasticsearchSinkConnector",
    "tasks.max": "1",
    "topics": "products,orders",
    "connection.url": "http://elasticsearch:9200",
    "type.name": "_doc",
    "key.ignore": "false",
    "schema.ignore": "true",
    "behavior.on.null.values": "delete",
    "transforms": "unwrap,key",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "transforms.unwrap.drop.tombstones": "false",
    "transforms.key.type": "org.apache.kafka.connect.transforms.ExtractField$Key",
    "transforms.key.field": "id"
  }
}'
```

**주요 설정 설명**:
- `transforms.unwrap`: Debezium 이벤트에서 실제 데이터만 추출 (before/after 제거)
- `behavior.on.null.values`: DELETE 이벤트 시 Elasticsearch에서도 삭제
- `transforms.key.field`: Elasticsearch 문서 ID로 사용할 필드

**동작 흐름**

1. MySQL에 INSERT:
```sql
INSERT INTO products (id, name, price) VALUES (1, '노트북', 1000000);
```

2. Debezium이 binlog를 읽고 Kafka에 이벤트 발행:
```json
{
  "before": null,
  "after": {
    "id": 1,
    "name": "노트북",
    "price": 1000000
  },
  "op": "c",  // create
  "ts_ms": 1708848000000
}
```

3. Elasticsearch Sink Connector가 Elasticsearch에 색인:
```json
PUT /products/_doc/1
{
  "id": 1,
  "name": "노트북",
  "price": 1000000
}
```

**모니터링**

```bash
# Connector 상태 확인
curl http://localhost:8083/connectors/mysql-source-connector/status

# Kafka Topic 메시지 확인
kafka-console-consumer --bootstrap-server kafka:9092 --topic products --from-beginning

# Elasticsearch 데이터 확인
curl http://localhost:9200/products/_search?pretty
```

### 5. 데이터 정합성 보장 전략

**이벤트 순서 보장 (Kafka Partition Key 전략)**

같은 레코드(예: id=1)에 대한 변경 이벤트는 순서가 중요함:
```
1. UPDATE products SET price=1000 WHERE id=1;
2. UPDATE products SET price=900 WHERE id=1;
```
만약 2번이 1번보다 먼저 처리되면 최종 가격이 1000원이 되는 문제 발생!

**해결책: Kafka Partition Key**

Debezium은 기본적으로 **Primary Key를 Partition Key**로 사용:
```json
{
  "key": {"id": 1},  // Partition Key
  "value": {
    "after": {"id": 1, "name": "노트북", "price": 900}
  }
}
```

Kafka는 **같은 Partition Key를 가진 메시지를 같은 Partition으로 전송**하여 순서 보장:
```
Partition 0: id=1 메시지 (순서 보장 ✅)
Partition 1: id=2 메시지 (순서 보장 ✅)
Partition 2: id=3 메시지 (순서 보장 ✅)
```

**장애 시 데이터 불일치 대응**

**1) 초기 스냅샷 (Initial Snapshot)**

Debezium 시작 시 기존 데이터를 모두 읽어와 Elasticsearch에 색인:
```json
{
  "snapshot.mode": "initial"  // 첫 시작 시 전체 스냅샷
}
```

**스냅샷 모드**:
- `initial`: 처음 시작 시 전체 테이블 스냅샷
- `schema_only`: 스키마만 읽고 데이터는 건너뜀
- `never`: 스냅샷 안 함 (binlog만 읽음)
- `when_needed`: 오프셋이 없으면 스냅샷

**2) 재동기화 (Re-sync)**

Elasticsearch 데이터가 손상되거나 불일치가 발생한 경우:

**방법 1: Debezium Connector 재시작 (스냅샷)**
```bash
# Connector 삭제
curl -X DELETE http://localhost:8083/connectors/mysql-source-connector

# Elasticsearch 인덱스 삭제
curl -X DELETE http://localhost:9200/products

# Connector 재생성 (snapshot.mode=initial)
# 전체 데이터를 다시 읽어옴
```

**방법 2: Kafka Offset Reset**
```bash
# 특정 Topic의 Offset을 처음부터 재설정
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group connect-elasticsearch-sink \
  --topic products \
  --reset-offsets --to-earliest --execute
```

**Exactly-Once vs At-Least-Once 시멘틱**

**At-Least-Once (기본값)**
- 메시지가 **최소 한 번** 전달됨
- 장애 시 재전송으로 인해 중복 가능
- Elasticsearch는 **Idempotent**(멱등성) 덕분에 중복 색인 문제 없음:
```
PUT /products/_doc/1  # 첫 번째 색인
PUT /products/_doc/1  # 중복 색인 (덮어쓰기, 문제 없음)
```

**Exactly-Once (Kafka 트랜잭션)**
- Kafka 트랜잭션으로 **정확히 한 번** 전달 보장
- 설정:
```json
{
  "producer.override.enable.idempotence": "true",
  "producer.override.transactional.id": "mysql-connector-1"
}
```
- 단점: 성능 오버헤드, 모든 Connector가 지원하지 않음

**권장**: Elasticsearch는 Idempotent하므로 **At-Least-Once**로 충분

**Elasticsearch 인덱스 스키마 관리**

**1) 명시적 Mapping 정의**

자동 Mapping은 타입 추론 오류 가능, 명시적 정의 권장:

```json
PUT /products
{
  "mappings": {
    "properties": {
      "id": { "type": "long" },
      "name": {
        "type": "text",
        "fields": {
          "keyword": { "type": "keyword" }  // 정렬/필터용
        },
        "analyzer": "nori"  // 한글 형태소 분석
      },
      "description": {
        "type": "text",
        "analyzer": "nori"
      },
      "price": { "type": "long" },
      "category": { "type": "keyword" },
      "tags": { "type": "keyword" },
      "created_at": { "type": "date" },
      "updated_at": { "type": "date" }
    }
  },
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1,
    "refresh_interval": "1s"
  }
}
```

**2) 스키마 변경 대응**

MySQL 스키마가 변경되면 Elasticsearch도 업데이트 필요:

**컬럼 추가** (하위 호환):
```sql
ALTER TABLE products ADD COLUMN stock INT DEFAULT 0;
```
→ Elasticsearch는 자동으로 새 필드 추가 (Dynamic Mapping)

**컬럼 타입 변경** (비호환):
```sql
ALTER TABLE products MODIFY COLUMN price DECIMAL(10,2);
```
→ Elasticsearch 인덱스 재생성 필요 (Reindex API)

```json
POST /_reindex
{
  "source": { "index": "products" },
  "dest": { "index": "products_v2" }
}
```

**3) 버전 관리**

인덱스 이름에 버전 추가:
```
products_v1 → products_v2 → products_v3
```

Alias로 애플리케이션은 `products` 사용:
```json
POST /_aliases
{
  "actions": [
    { "remove": { "index": "products_v1", "alias": "products" } },
    { "add": { "index": "products_v2", "alias": "products" } }
  ]
}
```

### 6. 성능 최적화 전략

**Elasticsearch 인덱스 설계 (Mapping, Shard 전략)**

**1) Shard 전략**

Shard는 Elasticsearch의 데이터 분산 단위:

```json
{
  "settings": {
    "number_of_shards": 3,      // Primary Shard 개수
    "number_of_replicas": 1     // Replica Shard 개수
  }
}
```

**Shard 개수 결정 기준**:
- **데이터 크기**: 각 Shard는 10~50GB 권장
- **쿼리 성능**: Shard가 많을수록 병렬 검색 가능
- **너무 많으면**: 오버헤드 증가 (각 Shard마다 리소스 필요)
- **너무 적으면**: 핫스팟 발생, 스케일링 한계

**예시**:
- 1TB 데이터 → 20~30개 Shard (30~50GB/Shard)
- 10GB 데이터 → 1~3개 Shard

**2) Mapping 최적화**

```json
{
  "mappings": {
    "properties": {
      "id": {
        "type": "long",
        "index": false  // 검색 안 하는 필드는 인덱스 비활성화
      },
      "name": {
        "type": "text",
        "analyzer": "nori",
        "search_analyzer": "nori",
        "fields": {
          "keyword": { "type": "keyword" },  // 정렬/집계용
          "ngram": {  // 자동완성용
            "type": "text",
            "analyzer": "ngram_analyzer"
          }
        }
      },
      "description": {
        "type": "text",
        "analyzer": "nori",
        "index_options": "offsets"  // Highlight 성능 향상
      },
      "price": { "type": "long" },
      "internal_metadata": {
        "type": "object",
        "enabled": false  // 검색/색인 안 하는 필드
      }
    }
  }
}
```

**Bulk API를 활용한 대량 색인**

Elasticsearch Sink Connector 설정:
```json
{
  "batch.size": 2000,               // 한 번에 처리할 메시지 개수
  "linger.ms": 1000,                // 배치 대기 시간 (1초)
  "max.buffered.records": 20000,    // 버퍼링할 최대 레코드
  "flush.timeout.ms": 10000         // Flush 타임아웃
}
```

**Bulk API 동작**:
```json
POST /_bulk
{ "index": { "_index": "products", "_id": "1" } }
{ "name": "노트북", "price": 1000000 }
{ "index": { "_index": "products", "_id": "2" } }
{ "name": "마우스", "price": 30000 }
{ "delete": { "_index": "products", "_id": "3" } }
```

**성능 비교**:
- 단일 색인: 1000개 문서 → 10초
- Bulk 색인: 1000개 문서 → 0.5초 (20배 빠름)

**RDBMS는 쓰기 + 정합성, Elasticsearch는 읽기 + 검색으로 역할 분리**

**애플리케이션 레이어 분리**:

```java
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository mysqlRepository;  // MySQL
    private final ProductSearchRepository esRepository;  // Elasticsearch

    // 쓰기: MySQL만 사용 (CDC가 자동으로 Elasticsearch 동기화)
    @Transactional
    public Product createProduct(ProductCreateRequest request) {
        Product product = Product.builder()
            .name(request.getName())
            .price(request.getPrice())
            .build();

        return mysqlRepository.save(product);  // MySQL에만 저장
        // Debezium이 자동으로 Elasticsearch에 동기화 ✅
    }

    // 읽기 (검색): Elasticsearch 사용
    public Page<Product> searchProducts(String keyword, Pageable pageable) {
        return esRepository.findByNameContaining(keyword, pageable);
    }

    // 읽기 (상세): MySQL 사용 (최신 데이터 보장)
    @Transactional(readOnly = true)
    public Product getProduct(Long id) {
        return mysqlRepository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException(id));
    }

    // 집계/통계: Elasticsearch 사용
    public ProductStatistics getStatistics() {
        return esRepository.aggregateStatistics();
    }
}
```

**역할 분리 원칙**:
| 작업 | 데이터 소스 | 이유 |
|------|-------------|------|
| **생성/수정/삭제** | MySQL | ACID 보장, Single Source of Truth |
| **단건 조회** | MySQL | 최신 데이터 보장 (Replication Lag 없음) |
| **검색** | Elasticsearch | 빠른 전문 검색, 형태소 분석 |
| **필터링/정렬** | Elasticsearch | 인덱스 기반 빠른 처리 |
| **집계/통계** | Elasticsearch | Aggregation 기능 |

**캐시 레이어와의 조합 (Redis + Elasticsearch)**

**3-Tier 아키텍처**:

```
┌──────────┐
│ 애플리케이션│
└─────┬────┘
      │ 1. 캐시 확인
      ▼
┌──────────┐
│  Redis   │  ← Hot Data (TTL: 5분)
│  (Cache) │
└─────┬────┘
      │ 2. 캐시 미스 시 검색
      ▼
┌──────────────┐
│Elasticsearch │  ← 검색 + 집계
│   (Search)   │
└──────────────┘
      │ 3. CDC 동기화
      ▼
┌──────────┐
│  MySQL   │  ← 원본 데이터 (ACID)
│ (Primary)│
└──────────┘
```

**구현 예시**:
```java
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final RedisTemplate<String, Product> redisTemplate;
    private final ProductSearchRepository esRepository;

    public List<Product> searchProducts(String keyword) {
        String cacheKey = "search:" + keyword;

        // 1. Redis 캐시 확인
        List<Product> cached = redisTemplate.opsForList()
            .range(cacheKey, 0, -1);

        if (!cached.isEmpty()) {
            return cached;  // 캐시 히트
        }

        // 2. Elasticsearch 검색
        List<Product> products = esRepository.searchByKeyword(keyword);

        // 3. Redis에 캐싱 (5분 TTL)
        redisTemplate.opsForList().rightPushAll(cacheKey, products);
        redisTemplate.expire(cacheKey, Duration.ofMinutes(5));

        return products;
    }
}
```

**캐시 전략**:
- **인기 검색어**: Redis에 캐싱 (TTL: 5분)
- **개인화 검색**: Elasticsearch 직접 조회
- **실시간성 중요**: MySQL 직접 조회
- **집계/통계**: Elasticsearch (캐싱하면 오래된 데이터)

### 7. 실전 아키텍처 예시

**1) 상품 검색 시스템 (커머스)**

**요구사항**:
- 상품명, 설명으로 전문 검색
- 카테고리, 가격, 평점으로 필터링
- 인기순, 가격순 정렬
- 자동완성 제공
- 10만 QPS 처리

**아키텍처**:
```
[User] → [API Gateway] → [Product Service]
                              ├─ 쓰기 → [MySQL]
                              │           ↓ CDC (Debezium)
                              │         [Kafka]
                              │           ↓
                              └─ 읽기 → [Elasticsearch Cluster]
                                          ↓ 캐싱
                                        [Redis]
```

**MySQL 스키마**:
```sql
CREATE TABLE products (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  price DECIMAL(10, 2) NOT NULL,
  category VARCHAR(100),
  rating DECIMAL(2, 1),
  stock INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

**Elasticsearch Mapping**:
```json
{
  "mappings": {
    "properties": {
      "id": { "type": "long" },
      "name": {
        "type": "text",
        "analyzer": "nori",
        "fields": {
          "keyword": { "type": "keyword" },
          "ngram": {
            "type": "text",
            "analyzer": "autocomplete_analyzer"
          }
        }
      },
      "description": { "type": "text", "analyzer": "nori" },
      "price": { "type": "long" },
      "category": { "type": "keyword" },
      "rating": { "type": "float" },
      "stock": { "type": "integer" },
      "created_at": { "type": "date" }
    }
  },
  "settings": {
    "number_of_shards": 5,
    "number_of_replicas": 2,
    "analysis": {
      "analyzer": {
        "autocomplete_analyzer": {
          "type": "custom",
          "tokenizer": "ngram_tokenizer",
          "filter": ["lowercase"]
        }
      },
      "tokenizer": {
        "ngram_tokenizer": {
          "type": "ngram",
          "min_gram": 2,
          "max_gram": 10
        }
      }
    }
  }
}
```

**검색 쿼리**:
```json
GET /products/_search
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "노트북",
            "fields": ["name^2", "description"],
            "type": "best_fields"
          }
        }
      ],
      "filter": [
        { "term": { "category": "electronics" } },
        { "range": { "price": { "gte": 500000, "lte": 2000000 } } },
        { "range": { "rating": { "gte": 4.0 } } }
      ]
    }
  },
  "sort": [
    { "rating": "desc" },
    { "_score": "desc" }
  ],
  "size": 20
}
```

**2) 로그/이벤트 검색 시스템**

**요구사항**:
- 애플리케이션 로그를 실시간 검색
- 에러 로그 필터링 및 알림
- 시계열 데이터 분석
- 대용량 로그 처리 (하루 수억 건)

**아키텍처**:
```
[Application] → [MySQL logs 테이블]
                       ↓ CDC (Debezium)
                    [Kafka]
                       ↓
              [Elasticsearch Cluster]
                (ILM: Index Lifecycle Management)
                  ├─ hot: logs-2026.02.25 (최근 7일)
                  ├─ warm: logs-2026.02.* (최근 30일)
                  └─ cold: logs-2026.01.* (30일 이후)
```

**MySQL 스키마**:
```sql
CREATE TABLE application_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  level ENUM('DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'),
  logger VARCHAR(255),
  message TEXT,
  exception TEXT,
  user_id BIGINT,
  request_id VARCHAR(100),
  INDEX idx_timestamp (timestamp),
  INDEX idx_level (level)
);
```

**Elasticsearch Index Template**:
```json
PUT /_index_template/logs_template
{
  "index_patterns": ["logs-*"],
  "template": {
    "mappings": {
      "properties": {
        "timestamp": { "type": "date" },
        "level": { "type": "keyword" },
        "logger": { "type": "keyword" },
        "message": { "type": "text" },
        "exception": { "type": "text" },
        "user_id": { "type": "long" },
        "request_id": { "type": "keyword" }
      }
    },
    "settings": {
      "number_of_shards": 3,
      "number_of_replicas": 1,
      "index.lifecycle.name": "logs_policy"
    }
  }
}
```

**ILM 정책** (데이터 수명 관리):
```json
PUT /_ilm/policy/logs_policy
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_size": "50GB",
            "max_age": "1d"
          }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "shrink": { "number_of_shards": 1 },
          "forcemerge": { "max_num_segments": 1 }
        }
      },
      "cold": {
        "min_age": "30d",
        "actions": {
          "freeze": {}
        }
      },
      "delete": {
        "min_age": "90d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
```

**3) 통합 검색 시스템 (게시글 + 댓글 + 사용자)**

**요구사항**:
- 게시글, 댓글, 사용자를 통합 검색
- 한 번의 검색으로 모든 엔티티 결과 반환
- 엔티티 타입별 가중치 적용

**아키텍처**:
```
[MySQL]
  ├─ posts 테이블
  ├─ comments 테이블
  └─ users 테이블
       ↓ CDC (Debezium) - 3개 Connector
    [Kafka]
       ├─ posts Topic
       ├─ comments Topic
       └─ users Topic
       ↓
[Elasticsearch]
  └─ unified_search 인덱스 (모든 타입 통합)
```

**Elasticsearch 통합 인덱스**:
```json
{
  "mappings": {
    "properties": {
      "id": { "type": "long" },
      "type": { "type": "keyword" },  // "post", "comment", "user"
      "title": { "type": "text", "analyzer": "nori" },
      "content": { "type": "text", "analyzer": "nori" },
      "author_id": { "type": "long" },
      "author_name": { "type": "text" },
      "created_at": { "type": "date" }
    }
  }
}
```

**데이터 변환 (Kafka Streams)**:
```java
// posts 이벤트 → 통합 인덱스
{
  "id": "post:1",
  "type": "post",
  "title": "게시글 제목",
  "content": "게시글 내용",
  "author_id": 100,
  "author_name": "홍길동",
  "created_at": "2026-02-25T10:00:00Z"
}

// comments 이벤트 → 통합 인덱스
{
  "id": "comment:1",
  "type": "comment",
  "content": "댓글 내용",
  "post_id": 1,
  "author_id": 200,
  "author_name": "김철수",
  "created_at": "2026-02-25T11:00:00Z"
}
```

**통합 검색 쿼리**:
```json
GET /unified_search/_search
{
  "query": {
    "function_score": {
      "query": {
        "multi_match": {
          "query": "검색 키워드",
          "fields": ["title^3", "content^2", "author_name"]
        }
      },
      "functions": [
        {
          "filter": { "term": { "type": "post" } },
          "weight": 3
        },
        {
          "filter": { "term": { "type": "comment" } },
          "weight": 2
        },
        {
          "filter": { "term": { "type": "user" } },
          "weight": 1
        }
      ],
      "boost_mode": "multiply"
    }
  },
  "aggs": {
    "by_type": {
      "terms": { "field": "type" }
    }
  }
}
```

**결과 예시**:
```json
{
  "hits": [
    { "_source": { "type": "post", "title": "검색 키워드 관련 게시글" } },
    { "_source": { "type": "comment", "content": "검색 키워드 댓글" } },
    { "_source": { "type": "user", "author_name": "검색 키워드" } }
  ],
  "aggregations": {
    "by_type": {
      "buckets": [
        { "key": "post", "doc_count": 15 },
        { "key": "comment", "doc_count": 8 },
        { "key": "user", "doc_count": 3 }
      ]
    }
  }
}
```

## 핵심 정리

**아키텍처 선택**
- MySQL을 Single Source of Truth로 사용하고, Elasticsearch를 검색 전용으로 분리 (CQRS)
- 데이터 동기화는 **CDC (binlog 기반)** 방식 사용 권장 (Debezium)
- Kafka를 버퍼로 사용하여 대용량 이벤트 처리 및 장애 복구 지원

**정합성 vs 성능 트레이드오프**
- MySQL: 쓰기 작업 및 정합성이 중요한 읽기 (ACID 보장)
- Elasticsearch: 검색, 필터링, 집계 (빠른 성능)
- Redis: 인기 검색어 캐싱 (초고속 응답)

**데이터 정합성 보장**
- Kafka Partition Key로 이벤트 순서 보장 (같은 PK는 같은 Partition)
- At-Least-Once 시멘틱 (Elasticsearch는 Idempotent하므로 중복 색인 문제 없음)
- 초기 스냅샷 및 재동기화 메커니즘 구현

**성능 최적화**
- Elasticsearch Shard 전략: 10~50GB/Shard 권장
- Bulk API로 대량 색인 (20배 성능 향상)
- Mapping 최적화: 검색 안 하는 필드는 `index: false`
- ILM(Index Lifecycle Management)으로 오래된 데이터 자동 정리

**운영 고려사항**
- Debezium Connector 모니터링 (Lag, 장애 감지)
- Elasticsearch 클러스터 헬스 체크
- Kafka Offset 관리 및 백업
- 스키마 변경 시 Elasticsearch Mapping 업데이트 전략

## 키워드

**`CDC (Change Data Capture)`**
- 데이터베이스의 변경 사항(INSERT, UPDATE, DELETE)을 실시간으로 감지하고 캡처하는 기술. 트랜잭션 로그(MySQL binlog, PostgreSQL WAL)를 모니터링하여 다른 시스템으로 변경 이벤트를 전파함.

**`Debezium`**
- Red Hat이 만든 오픈소스 CDC 플랫폼. Kafka Connect 기반으로 MySQL, PostgreSQL, MongoDB 등의 변경 사항을 Kafka로 스트리밍. 엔터프라이즈급 안정성과 풍부한 기능 제공.

**`Binary Log (binlog)`**
- MySQL에서 모든 데이터 변경 작업을 기록하는 트랜잭션 로그. Replication, Point-in-Time Recovery, CDC의 기반이 됨. ROW 포맷 사용 시 변경된 행 데이터를 정확히 기록.

**`Elasticsearch`**
- Apache Lucene 기반의 분산 검색 및 분석 엔진. 역인덱스(Inverted Index)를 사용하여 빠른 전문 검색, 형태소 분석, 집계 기능을 제공. RESTful API로 쉽게 사용 가능.

**`Kafka Connect`**
- Apache Kafka의 데이터 통합 프레임워크. Source Connector(DB → Kafka)와 Sink Connector(Kafka → Target)로 구성. Debezium은 Source Connector로 동작.

**`CQRS (Command Query Responsibility Segregation)`**
- 쓰기 모델(Command)과 읽기 모델(Query)을 분리하는 아키텍처 패턴. MySQL은 쓰기와 정합성, Elasticsearch는 읽기와 검색으로 역할을 분리하여 각 시스템의 강점을 극대화.

**`역인덱스 (Inverted Index)`**
- 단어 → 문서 매핑 구조. 일반 인덱스(문서 → 단어)와 반대로, 각 단어가 어떤 문서에 포함되어 있는지 기록. 전문 검색에서 O(1) 시간 복잡도로 빠른 검색 가능.

**`Dual Write`**
- 애플리케이션이 두 개의 데이터 저장소(예: MySQL과 Elasticsearch)에 동시에 쓰기를 수행하는 방식. 구현은 간단하지만 정합성 문제(Partial Failure) 발생 가능.

**`Bulk API`**
- Elasticsearch에서 여러 문서를 한 번의 HTTP 요청으로 색인/업데이트/삭제하는 API. 네트워크 오버헤드를 줄여 색인 성능을 크게 향상시킴 (20배 이상).

**`데이터 동기화`**
- 서로 다른 데이터 저장소(MySQL, Elasticsearch) 간에 데이터를 일관되게 유지하는 프로세스. Dual Write, Polling, CDC 등 다양한 방식이 있으며, 정합성과 실시간성이 핵심 고려사항.

## 참고 자료
- [Debezium Documentation](https://debezium.io/documentation/)
- [Debezium MySQL Connector](https://debezium.io/documentation/reference/stable/connectors/mysql.html)
- [MySQL 8.0 Reference Manual - Binary Log](https://dev.mysql.com/doc/refman/8.0/en/binary-log.html)
- [Elasticsearch Guide](https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html)
- [Elasticsearch Mapping](https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping.html)
- [Kafka Connect Documentation](https://kafka.apache.org/documentation/#connect)
- [Confluent Elasticsearch Sink Connector](https://docs.confluent.io/kafka-connectors/elasticsearch/current/overview.html)
- [Martin Fowler - CQRS](https://martinfowler.com/bliki/CQRS.html)
- [Elasticsearch Index Lifecycle Management (ILM)](https://www.elastic.co/guide/en/elasticsearch/reference/current/index-lifecycle-management.html)
- [Maxwell's Daemon (CDC Tool)](https://maxwells-daemon.io/)
- [Alibaba Canal (CDC Tool)](https://github.com/alibaba/canal)
