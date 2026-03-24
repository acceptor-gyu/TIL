# 대규모 트래픽 환경을 운영하는 e-commerce 시스템에서 Elasticsearch 활용 전략

## 개요
대규모 트래픽 환경의 e-commerce 시스템에서 Elasticsearch를 효과적으로 활용하기 위한 전략을 학습한다. 상품 검색, 자동완성, 추천 등 핵심 기능부터 인덱스 설계, 성능 최적화, 데이터 동기화, 운영 전략까지 실무에서 필요한 전반적인 내용을 다룬다.

## 상세 내용

### 1. e-commerce에서 Elasticsearch의 역할

e-commerce 시스템에서 RDBMS만으로는 대규모 전문 검색(Full-text Search) 요구를 충족하기 어렵다. MySQL의 LIKE 검색은 인덱스를 타지 못하거나 성능이 급격히 저하되며, 형태소 분석·동의어 처리·관련성 점수 계산 같은 기능은 RDBMS가 지원하지 않는다. Elasticsearch는 이러한 문제를 해결하는 검색 전용 엔진이다.

**상품 검색 (Full-text Search)**

사용자가 "나이키 런닝화"를 검색할 때 단순 문자열 일치가 아닌, "나이키", "런닝", "화" 등으로 형태소를 분리하고 각 토큰에 매칭되는 문서를 점수 기반으로 랭킹한다. BM25 알고리즘을 기본으로 TF-IDF 점수를 계산해 가장 관련성 높은 상품을 상위에 노출한다.

**자동완성 (Autocomplete / Suggest)**

사용자가 검색창에 글자를 입력하는 순간마다 후보 검색어를 실시간으로 제공한다. Elasticsearch의 `completion` 타입 필드 또는 `edge_ngram` 토크나이저를 활용해 밀리초 단위로 응답한다.

**추천 시스템 연동**

사용자 행동 로그(클릭, 구매, 장바구니 담기)를 Elasticsearch에 색인해두고, `more_like_this` 쿼리나 사전 계산된 추천 벡터를 활용해 "이 상품을 본 고객이 함께 본 상품"을 제공할 수 있다. 최근에는 벡터 검색(kNN Search)을 활용해 의미 유사도 기반 추천도 가능하다.

**로그 분석 및 사용자 행동 추적**

검색 키워드 로그, 구매 전환율 데이터, 에러 로그 등을 Elasticsearch에 저장하고 Kibana로 시각화한다. 이를 통해 "검색했지만 구매로 이어지지 않은 키워드"나 "검색 결과 0건 키워드"를 파악해 상품 데이터와 검색 품질을 개선할 수 있다.

**실시간 집계 및 통계 (Aggregation)**

Aggregation API를 활용해 카테고리별 상품 수, 가격대 분포, 브랜드별 매출 현황 등을 실시간으로 계산한다. 이는 RDBMS의 GROUP BY보다 훨씬 빠르게 대규모 데이터를 집계할 수 있다.

---

### 2. Elasticsearch 기본 아키텍처

**클러스터 (Cluster)**

Elasticsearch는 하나 이상의 노드가 모인 클러스터 단위로 동작한다. 클러스터 내 모든 노드는 동일한 `cluster.name`을 공유하며, 각 노드는 서로를 자동으로 발견(Discovery)한다. 클러스터는 단일 장애점(SPOF) 없이 수평 확장이 가능하다.

**노드 (Node) 종류와 역할**

| 노드 타입 | 역할 |
|---|---|
| Master Node | 클러스터 상태 관리, 인덱스 생성/삭제, 샤드 할당 결정 |
| Data Node | 실제 데이터(샤드)를 저장하고 색인/검색 연산 수행 |
| Coordinating Node | 클라이언트 요청을 받아 적절한 Data Node로 분산, 결과 집계 후 반환. 별도 역할 없이 모든 노드가 기본으로 수행 |
| Ingest Node | 색인 전 문서에 파이프라인 처리(필드 변환, 조건 필터 등) 수행 |

대규모 클러스터에서는 Master Node를 전용으로 분리(Dedicated Master)하여 데이터 처리 부하로부터 격리하는 것이 중요하다. 최소 3개의 Master-eligible 노드를 두어 Split-brain을 방지한다.

**샤드 (Shard)와 레플리카 (Replica)**

인덱스는 여러 개의 Primary Shard로 물리적으로 분할된다. 각 Primary Shard는 하나 이상의 Replica Shard를 가진다.

- **Primary Shard**: 실제 색인(write) 작업이 수행되는 샤드
- **Replica Shard**: Primary Shard의 복사본. 읽기(read) 요청을 분산 처리하며, Primary 장애 시 자동으로 Primary로 승격

샤드 수는 인덱스 생성 시 결정되며 이후 변경이 불가하다(Reindex 필요). 반면 Replica 수는 언제든 변경 가능하다.

**인덱스와 도큐먼트 구조**

Elasticsearch의 저장 단위는 다음과 같다.

```
클러스터 (Cluster)
  └── 인덱스 (Index) - RDBMS의 테이블에 해당
        └── 샤드 (Shard) - 내부 물리 분할 단위
              └── 도큐먼트 (Document) - RDBMS의 행(row)에 해당, JSON 형식
```

---

### 3. 인덱스 설계 전략

**매핑 (Mapping) 설계 원칙**

매핑은 RDBMS의 스키마에 해당한다. Elasticsearch는 동적 매핑(Dynamic Mapping)을 지원하지만, 프로덕션 환경에서는 명시적 매핑(Explicit Mapping)을 권장한다. 동적 매핑은 예상치 못한 타입 추론으로 인한 문제(예: 숫자가 문자열로 추론)를 유발할 수 있기 때문이다.

주요 원칙:
- 검색이 필요한 텍스트 필드는 `text` 타입, 집계/정렬이 필요한 필드는 `keyword` 타입으로 구분
- 수치 필드는 적절한 숫자 타입(`integer`, `long`, `float`) 사용
- 불필요한 필드는 `"enabled": false` 또는 `"index": false`로 색인에서 제외해 인덱스 크기 절감
- `_source`에 저장이 불필요한 대형 텍스트 필드는 `"store": false` 검토

**분석기 (Analyzer) 구성**

분석기는 텍스트를 토큰으로 분리하는 파이프라인이다. `character filter → tokenizer → token filter` 순서로 처리된다.

```json
PUT /products
{
  "settings": {
    "analysis": {
      "char_filter": {
        "html_strip_filter": {
          "type": "html_strip"
        }
      },
      "tokenizer": {
        "standard_tokenizer": {
          "type": "standard"
        }
      },
      "filter": {
        "lowercase_filter": { "type": "lowercase" },
        "stop_filter": {
          "type": "stop",
          "stopwords": ["은", "는", "이", "가", "을", "를"]
        }
      },
      "analyzer": {
        "product_analyzer": {
          "type": "custom",
          "char_filter": ["html_strip_filter"],
          "tokenizer": "standard_tokenizer",
          "filter": ["lowercase_filter", "stop_filter"]
        }
      }
    }
  }
}
```

**한국어 형태소 분석기 nori 설정 및 커스터마이징**

`analysis-nori` 플러그인은 Elasticsearch 공식 한국어 형태소 분석 플러그인으로, mecab-ko-dic 사전을 기반으로 동작한다.

```bash
# 플러그인 설치
bin/elasticsearch-plugin install analysis-nori
```

```json
PUT /products
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "nori_tokenizer_mixed": {
          "type": "nori_tokenizer",
          "decompound_mode": "mixed",
          "discard_punctuation": true,
          "user_dictionary_rules": [
            "삼성전자",
            "엘지전자",
            "쿠팡로켓"
          ]
        }
      },
      "filter": {
        "nori_posfilter": {
          "type": "nori_part_of_speech",
          "stoptags": ["E", "IC", "J", "MAG", "MAJ", "MM", "SP", "SSC", "SSO", "SC", "SE", "XPN", "XSA", "XSN", "XSV", "UNA", "NA", "VSV"]
        }
      },
      "analyzer": {
        "korean_analyzer": {
          "type": "custom",
          "tokenizer": "nori_tokenizer_mixed",
          "filter": ["nori_posfilter", "lowercase"]
        }
      }
    }
  }
}
```

`decompound_mode` 옵션:
- `none`: 복합어를 분해하지 않음 (예: "삼성전자" → ["삼성전자"])
- `discard`: 복합어를 분해하고 원형은 제거 (예: "삼성전자" → ["삼성", "전자"])
- `mixed`: 복합어와 분해 결과를 모두 유지 (예: "삼성전자" → ["삼성전자", "삼성", "전자"]) - 검색 품질 면에서 가장 권장

**멀티 필드 (Multi-field) 활용**

동일한 데이터를 목적에 따라 다른 방식으로 색인한다. 상품명을 예로 들면, 전문 검색용 `text` 타입과 정렬/집계용 `keyword` 타입을 동시에 색인할 수 있다.

```json
PUT /products
{
  "mappings": {
    "properties": {
      "product_name": {
        "type": "text",
        "analyzer": "korean_analyzer",
        "fields": {
          "keyword": {
            "type": "keyword",
            "ignore_above": 256
          },
          "ngram": {
            "type": "text",
            "analyzer": "ngram_analyzer"
          },
          "suggest": {
            "type": "completion"
          }
        }
      },
      "price": {
        "type": "integer",
        "doc_values": true
      },
      "category": {
        "type": "keyword"
      },
      "brand": {
        "type": "keyword"
      },
      "description": {
        "type": "text",
        "analyzer": "korean_analyzer",
        "index": true,
        "store": false
      }
    }
  }
}
```

**인덱스 템플릿과 인덱스 라이프사이클 관리 (ILM)**

인덱스 템플릿(Index Template)은 새 인덱스 생성 시 매핑, 설정, 별칭을 자동으로 적용한다. 주로 시계열 인덱스(로그, 이벤트)에서 날짜별 인덱스 자동 생성에 활용한다.

ILM(Index Lifecycle Management)은 인덱스를 Hot → Warm → Cold → Frozen → Delete 단계로 자동 전환한다. e-commerce 로그 데이터의 경우, 최근 7일은 Hot 티어(SSD)에, 30일까지는 Warm 티어(HDD)에, 90일 이후는 Cold 티어에 보관하거나 삭제하는 정책을 수립할 수 있다.

---

### 4. 대규모 트래픽 대응을 위한 성능 최적화

**쿼리 캐싱 (Query Cache)**

Elasticsearch는 두 종류의 캐시를 제공한다.

- **Node Query Cache (Filter Cache)**: 필터 쿼리(filter context)의 결과를 노드 단위로 캐싱. `term`, `range`, `exists` 등 변화가 없는 필터에 효과적. 기본값은 JVM heap의 10%
- **Shard Request Cache**: 집계(aggregation) 결과와 `hits.total` 같은 검색 메타데이터를 샤드 단위로 캐싱. `size: 0`으로 hits를 반환하지 않는 집계 전용 쿼리에 최적. 세그먼트 변경 시 무효화

실무 팁: 자주 사용되는 필터(활성 상품 여부, 재고 있는 상품 등)는 반드시 `filter context`에 배치하여 캐시 혜택을 받도록 한다.

```json
GET /products/_search
{
  "query": {
    "bool": {
      "must": {
        "match": { "product_name": "나이키 운동화" }
      },
      "filter": [
        { "term": { "status": "ACTIVE" } },
        { "range": { "stock": { "gt": 0 } } }
      ]
    }
  }
}
```

**샤드 전략 (샤드 수 결정, 라우팅)**

샤드 수는 인덱스 생성 후 변경이 불가능하므로 초기 설계가 중요하다.

- 권장 샤드 크기: 10GB ~ 50GB per shard
- 너무 많은 샤드는 클러스터 메타데이터 부담과 검색 오버헤드를 증가시킴 (Oversharding)
- 최소 1개의 Replica를 유지하여 고가용성 보장

커스텀 라우팅(Routing)을 활용하면 특정 사용자 또는 카테고리의 데이터를 동일한 샤드에 모아 검색 범위를 줄일 수 있다.

```json
PUT /products/_doc/1?routing=category_shoes
{
  "product_name": "나이키 에어포스",
  "category": "shoes"
}

GET /products/_search?routing=category_shoes
{
  "query": {
    "match": { "product_name": "운동화" }
  }
}
```

**Bulk API를 활용한 대량 색인 최적화**

단건 색인 대신 Bulk API를 사용하면 네트워크 왕복 횟수를 줄여 색인 처리량을 수십 배 향상시킬 수 있다.

```json
POST /_bulk
{ "index": { "_index": "products", "_id": "1" } }
{ "product_name": "나이키 에어맥스", "price": 150000, "category": "shoes" }
{ "index": { "_index": "products", "_id": "2" } }
{ "product_name": "아디다스 울트라부스트", "price": 180000, "category": "shoes" }
{ "update": { "_index": "products", "_id": "1" } }
{ "doc": { "price": 140000 } }
```

실무 권장 설정:
- 배치 크기: 1,000 ~ 5,000 건 또는 5MB ~ 15MB 단위
- HTTP 요청 최대 크기: 기본 100MB (변경 가능하나 권장하지 않음)
- 대량 색인 중 `refresh_interval`을 `-1`로 설정하여 실시간 색인을 잠시 비활성화하면 처리량이 대폭 향상됨

**Scroll API vs Search After**

대용량 데이터를 페이지 단위로 순회할 때 두 방식의 차이를 이해해야 한다.

| 구분 | Scroll API | Search After |
|---|---|---|
| 동작 방식 | 스냅샷 기반. 초기 검색 시점의 상태 유지 | 커서 기반. 마지막 도큐먼트의 sort 값을 기준으로 다음 페이지 요청 |
| 실시간 데이터 반영 | X (스냅샷 고정) | O (실시간 데이터 변경 반영) |
| 메모리 부담 | Scroll Context 유지로 서버 메모리 점유 | Stateless, 서버 부담 없음 |
| 적합한 용도 | 전체 데이터 Export, 마이그레이션 | 실시간 서비스의 페이지네이션, 무한 스크롤 |
| 권장 여부 | 신규 개발 시 비권장 (Deprecated 예정) | 권장 |

```json
// Search After 예시
GET /products/_search
{
  "size": 20,
  "query": { "match_all": {} },
  "sort": [
    { "created_at": "desc" },
    { "_id": "asc" }
  ],
  "search_after": ["2024-03-01T00:00:00Z", "abc123"]
}
```

**필드 데이터 캐시와 Doc Values**

- **Doc Values**: 색인 시 디스크에 컬럼 방향으로 저장되는 구조. `keyword`, `numeric`, `date` 등 대부분의 필드에 기본 활성화되어 있으며 정렬/집계 시 효율적으로 동작한다
- **Field Data Cache**: `text` 타입 필드에서 집계나 정렬을 수행할 때 JVM heap에 올라오는 캐시. 매우 많은 메모리를 소비하므로 `text` 필드에서 집계/정렬은 지양해야 한다. 반드시 필요한 경우 `fielddata: true`를 설정하되 heap 사용량을 모니터링해야 한다

---

### 5. RDBMS와 Elasticsearch 데이터 동기화 전략

**CDC (Change Data Capture) 개념과 활용**

CDC는 데이터베이스에서 발생한 변경 이벤트(INSERT, UPDATE, DELETE)를 실시간으로 감지하여 외부 시스템에 전파하는 기술이다. e-commerce에서 상품 정보가 RDBMS에서 변경될 때 Elasticsearch에 즉시 반영하는 데 활용된다.

CDC의 장점:
- 애플리케이션 코드 변경 없이 변경 감지 가능 (DB 레벨에서 동작)
- 밀리초 단위의 낮은 지연(Latency)
- 삭제(DELETE) 이벤트도 안정적으로 감지

**MySQL binlog 기반 동기화**

MySQL의 Binary Log(binlog)는 데이터 변경 이벤트를 순서대로 기록한 로그 파일이다. binlog를 실시간으로 파싱하여 Elasticsearch에 반영하는 것이 CDC의 핵심 원리이다.

```sql
-- MySQL binlog 활성화 확인
SHOW VARIABLES LIKE 'log_bin';
SHOW VARIABLES LIKE 'binlog_format';  -- ROW 방식이 CDC에 적합

-- my.cnf 설정
-- log_bin = /var/log/mysql/mysql-bin.log
-- binlog_format = ROW
-- binlog_row_image = FULL
-- server_id = 1
```

**Logstash를 활용한 파이프라인 구성**

Logstash의 `jdbc` input 플러그인으로 RDBMS를 주기적으로 폴링하는 방식이다. 단순하지만 폴링 간격만큼의 지연이 발생하고, 삭제 이벤트 감지가 어렵다는 단점이 있다.

```ruby
# logstash.conf 예시
input {
  jdbc {
    jdbc_driver_library => "/usr/share/java/mysql-connector-java.jar"
    jdbc_driver_class => "com.mysql.cj.jdbc.Driver"
    jdbc_connection_string => "jdbc:mysql://localhost:3306/ecommerce"
    jdbc_user => "logstash"
    jdbc_password => "password"
    schedule => "*/1 * * * *"
    statement => "SELECT * FROM products WHERE updated_at > :sql_last_value"
    use_column_value => true
    tracking_column => "updated_at"
    tracking_column_type => "timestamp"
  }
}

output {
  elasticsearch {
    hosts => ["https://localhost:9200"]
    index => "products"
    document_id => "%{id}"
    action => "index"
  }
}
```

**Debezium + Kafka Connect 조합**

가장 실무에서 권장되는 아키텍처이다. Debezium은 binlog를 읽어 Kafka 토픽에 변경 이벤트를 발행하는 Kafka Connect Source Connector이다.

```
MySQL (binlog)
    ↓
Debezium MySQL Connector (Kafka Connect Source)
    ↓
Apache Kafka Topic (ecommerce.products)
    ↓
Elasticsearch Sink Connector (Kafka Connect Sink)
    ↓
Elasticsearch
```

Debezium Connector 설정 예시:

```json
{
  "name": "mysql-products-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "database.hostname": "mysql",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "password",
    "database.server.id": "184054",
    "topic.prefix": "ecommerce",
    "database.include.list": "ecommerce",
    "table.include.list": "ecommerce.products",
    "include.schema.changes": "false",
    "transforms": "unwrap",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "transforms.unwrap.delete.handling.mode": "rewrite",
    "transforms.unwrap.add.fields": "op,ts_ms"
  }
}
```

**동기화 지연(Lag) 대응 전략**

CDC 파이프라인에서 지연이 발생하면 검색 결과에 오래된 데이터가 노출될 수 있다.

대응 방법:
1. **Consumer Lag 모니터링**: Kafka Consumer Group의 Lag을 Prometheus + Grafana로 실시간 모니터링
2. **병렬 처리**: Kafka 파티션 수를 늘리고 Consumer 수를 증가시켜 처리량 향상
3. **Fallback 처리**: 검색 결과에서 특정 상품 ID를 클릭할 때 RDBMS에서 최신 정보를 재조회하는 방어 로직 적용
4. **이중 쓰기(Dual Write)**: 애플리케이션에서 RDBMS 저장 직후 Elasticsearch에도 바로 쓰는 방식. 단, 트랜잭션 보장이 되지 않아 정합성 관리 필요

---

### 6. 검색 품질 향상

**Relevance Tuning (관련성 점수 조정)**

Elasticsearch는 기본적으로 BM25 알고리즘으로 관련성 점수(relevance score)를 계산한다. BM25는 TF(단어 빈도)와 IDF(역문서 빈도)를 기반으로 하며, 짧은 필드에서 단어가 자주 등장할수록 높은 점수를 부여한다.

검색 품질 튜닝의 기본 접근법:
- 어떤 필드가 검색 점수에 더 큰 영향을 주어야 하는지 결정
- 실제 검색 로그를 분석해 사용자 의도를 파악
- 판단 기준(Judgment List) 데이터를 구축하여 점수를 객관적으로 평가

**Boosting (필드별 가중치 부여)**

상품명이 설명보다 중요한 경우, `^` 연산자로 가중치를 부여한다.

```json
GET /products/_search
{
  "query": {
    "multi_match": {
      "query": "나이키 운동화",
      "fields": [
        "product_name^5",
        "brand^3",
        "description^1"
      ],
      "type": "best_fields"
    }
  }
}
```

**Synonym (동의어 사전) 관리**

"운동화"와 "스니커즈", "티셔츠"와 "반팔"처럼 동의어를 처리해 검색 재현율(Recall)을 높인다.

```json
PUT /products
{
  "settings": {
    "analysis": {
      "filter": {
        "synonym_filter": {
          "type": "synonym",
          "synonyms": [
            "운동화, 스니커즈, 스니커",
            "티셔츠, 반팔티, 반팔",
            "노트북, 랩탑",
            "핸드폰, 스마트폰, 휴대폰, 휴대전화"
          ]
        }
      },
      "analyzer": {
        "synonym_analyzer": {
          "tokenizer": "nori_tokenizer",
          "filter": ["synonym_filter", "lowercase"]
        }
      }
    }
  }
}
```

동의어 사전은 파일로 관리하고 `synonyms_path` 옵션으로 참조하는 것이 실무에서 더 유연하다. 단, 동의어 파일 변경 후에는 인덱스의 `_reload_search_analyzers` API를 호출해야 반영된다.

**Function Score Query 활용**

관련성 점수 외에 비즈니스 로직을 점수에 반영할 때 사용한다. 예를 들어, 판매량이 많거나 평점이 높은 상품을 상위에 노출하고 싶은 경우이다.

```json
GET /products/_search
{
  "query": {
    "function_score": {
      "query": {
        "match": { "product_name": "운동화" }
      },
      "functions": [
        {
          "field_value_factor": {
            "field": "sales_count",
            "factor": 0.5,
            "modifier": "log1p",
            "missing": 1
          }
        },
        {
          "field_value_factor": {
            "field": "rating",
            "factor": 1.2,
            "modifier": "sqrt",
            "missing": 3.0
          }
        },
        {
          "gauss": {
            "created_at": {
              "origin": "now",
              "scale": "30d",
              "decay": 0.5
            }
          }
        }
      ],
      "score_mode": "multiply",
      "boost_mode": "multiply"
    }
  }
}
```

**사용자 피드백 기반 검색 품질 개선**

- **CTR(Click-Through Rate) 분석**: 어떤 검색어에서 어떤 상품이 클릭되었는지 로그로 수집. 클릭이 많은 상품-검색어 쌍을 Pinned Query로 고정 노출
- **No-Result 키워드 분석**: 결과 0건인 검색어를 주기적으로 분석해 동의어 추가 또는 상품 보강
- **A/B 테스트**: 서로 다른 매핑 설정이나 쿼리 전략을 동시에 운영하며 전환율 비교

---

### 7. 운영 및 모니터링

**클러스터 헬스 체크 (_cluster/health)**

클러스터 상태는 세 단계로 표현된다.

| 상태 | 의미 |
|---|---|
| Green | 모든 Primary 및 Replica Shard 정상 할당 |
| Yellow | 모든 Primary Shard 정상, 일부 Replica Shard 미할당 (데이터 손실 없음, 가용성 위협) |
| Red | 일부 Primary Shard 미할당 (해당 인덱스 데이터 일부 검색 불가) |

```bash
# 클러스터 헬스 확인
GET /_cluster/health

# 인덱스별 헬스 확인
GET /_cluster/health/products?level=shards

# 할당되지 않은 샤드 원인 확인
GET /_cluster/allocation/explain
```

**느린 쿼리 로그 (Slow Log) 설정 및 분석**

검색 성능 병목을 찾기 위해 일정 시간 이상 걸리는 쿼리를 로그로 기록한다.

```json
PUT /products/_settings
{
  "index.search.slowlog.threshold.query.warn": "1s",
  "index.search.slowlog.threshold.query.info": "500ms",
  "index.search.slowlog.threshold.fetch.warn": "500ms",
  "index.indexing.slowlog.threshold.index.warn": "2s",
  "index.indexing.slowlog.threshold.index.info": "1s"
}
```

느린 쿼리 로그 파일(`elasticsearch_index_search_slowlog.json`)을 정기적으로 분석하여 와일드카드 남용, 필터 미적용, fielddata 사용 등 비효율적인 패턴을 찾는다.

**디스크 용량 관리와 Watermark**

Elasticsearch는 디스크 사용률에 따라 세 단계의 Watermark를 적용한다.

| 단계 | 기본값 | 동작 |
|---|---|---|
| Low Watermark | 85% | 해당 노드에 새 샤드 할당 중단 |
| High Watermark | 90% | 해당 노드에서 다른 노드로 샤드 이동 시도 |
| Flood Stage Watermark | 95% | 해당 노드의 인덱스를 read-only로 전환 |

```json
PUT /_cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.disk.watermark.low": "80%",
    "cluster.routing.allocation.disk.watermark.high": "85%",
    "cluster.routing.allocation.disk.watermark.flood_stage": "90%"
  }
}
```

Flood Stage에 걸려 read-only가 된 인덱스는 디스크 공간 확보 후 수동으로 해제해야 한다.

```json
PUT /products/_settings
{
  "index.blocks.read_only_allow_delete": null
}
```

**인덱스 모니터링 지표**

주요 모니터링 지표:
- **Indexing Rate**: 초당 색인 건수. 급격한 하락은 색인 병목 신호
- **Search Latency (p95, p99)**: 검색 응답 시간. 백분위수 기준으로 SLA 설정 권장
- **JVM Heap Usage**: 75% 이상 지속 시 GC 부담 증가 및 OOM 위험
- **Segment Count**: 세그먼트가 너무 많으면 Force Merge로 최적화
- **Rejected Threads**: Thread Pool 큐가 가득 차 요청이 거절된 횟수

**Kibana / Grafana 대시보드 구성**

- Kibana Stack Monitoring: Elasticsearch 클러스터 기본 모니터링 내장 제공
- Prometheus Elasticsearch Exporter + Grafana: 메트릭을 Prometheus로 수집해 커스텀 대시보드 구성. 알림(Alert) 규칙 설정에 유리

---

### 8. 장애 대응 및 고가용성 전략

**클러스터 장애 시나리오별 대응 방안**

| 시나리오 | 증상 | 대응 |
|---|---|---|
| 노드 일시 중단 | 클러스터 Yellow/Red | `delayed_timeout` 설정으로 샤드 재할당 지연. 노드 복구 후 자동 복구 |
| 마스터 노드 장애 | 마스터 선출 과정 | `minimum_master_nodes` 설정(또는 8.x의 quorum) 준수. 전용 마스터 3노드 유지 |
| 네트워크 파티션 (Split-brain) | 클러스터 분리 | `cluster.initial_master_nodes` 올바른 설정. Dedicated Master 노드 분리 |
| 디스크 가득 참 | Flood Stage, read-only | Watermark 사전 알림, ILM 정책, 오래된 인덱스 삭제 |

**스냅샷 (Snapshot)과 복구 (Restore)**

스냅샷은 인덱스를 외부 저장소(S3, GCS, Azure Blob)에 백업하는 기능이다.

```json
// 스냅샷 저장소 등록 (AWS S3 예시)
PUT /_snapshot/my-s3-repository
{
  "type": "s3",
  "settings": {
    "bucket": "my-elasticsearch-snapshots",
    "region": "ap-northeast-2"
  }
}

// 스냅샷 생성
PUT /_snapshot/my-s3-repository/snapshot-2024-03-24
{
  "indices": "products,orders",
  "ignore_unavailable": true,
  "include_global_state": false
}

// 스냅샷 복구
POST /_snapshot/my-s3-repository/snapshot-2024-03-24/_restore
{
  "indices": "products",
  "rename_pattern": "products",
  "rename_replacement": "products-restored"
}
```

SLM(Snapshot Lifecycle Management)으로 스냅샷 정책을 자동화하는 것을 권장한다.

```json
PUT /_slm/policy/nightly-snapshots
{
  "schedule": "0 0 2 * * ?",
  "name": "<products-snapshot-{now/d}>",
  "repository": "my-s3-repository",
  "config": {
    "indices": ["products"],
    "ignore_unavailable": true
  },
  "retention": {
    "expire_after": "30d",
    "min_count": 5,
    "max_count": 30
  }
}
```

**Cross-Cluster Replication (CCR)**

CCR은 원격 클러스터의 인덱스를 실시간으로 다른 클러스터에 복제하는 기능이다. e-commerce에서는 다음 용도로 활용한다.

- **재해 복구(DR)**: 리전 장애 시 다른 리전 클러스터로 즉시 전환
- **지역 분산**: 사용자와 가까운 리전에서 읽기 처리 (글로벌 e-commerce)
- **데이터 센터 마이그레이션**: 무중단 데이터 이전

```json
// 원격 클러스터 등록
PUT /_cluster/settings
{
  "persistent": {
    "cluster.remote.secondary-cluster.seeds": ["remote-host:9300"]
  }
}

// Follower Index 생성
PUT /products-follower/_ccr/follow
{
  "remote_cluster": "secondary-cluster",
  "leader_index": "products"
}
```

**Circuit Breaker 설정**

Circuit Breaker는 JVM heap 메모리를 과도하게 사용하는 요청을 차단해 OOM(Out Of Memory)을 방지한다.

주요 Circuit Breaker 종류:
- **Parent Circuit Breaker**: 전체 heap 사용량 기준. 기본 95% 초과 시 요청 차단
- **Fielddata Circuit Breaker**: fielddata 캐시 크기 제한. 기본 heap의 40%
- **Request Circuit Breaker**: 단일 요청이 사용하는 메모리 제한. 기본 heap의 60%

```json
PUT /_cluster/settings
{
  "persistent": {
    "indices.breaker.total.limit": "70%",
    "indices.breaker.fielddata.limit": "30%",
    "indices.breaker.request.limit": "50%"
  }
}
```

Circuit Breaker 오류가 빈번하게 발생한다면, fielddata 사용 필드를 `keyword`로 교체하거나 힙 메모리를 증가시키는 것을 검토해야 한다.

**Rolling Restart 전략**

클러스터 설정 변경이나 버전 업그레이드 시 무중단으로 노드를 순차적으로 재시작한다.

```json
// 1단계: 샤드 자동 재할당 비활성화 (불필요한 샤드 이동 방지)
PUT /_cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.enable": "none"
  }
}

// 2단계: Synced Flush (안전한 셧다운 준비)
POST /_flush

// 3단계: 노드 재시작 후 클러스터 Green 확인
GET /_cluster/health?wait_for_status=yellow&timeout=60s

// 4단계: 샤드 재할당 재활성화
PUT /_cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.enable": null
  }
}
```

위 과정을 노드별로 반복하면 서비스 중단 없이 클러스터 전체를 재시작할 수 있다.

---

## 핵심 정리
- e-commerce 시스템에서 Elasticsearch는 단순 검색 엔진을 넘어 자동완성, 추천, 로그 분석 등 다양한 역할을 수행한다
- 인덱스 설계 시 한국어 형태소 분석기(nori) 설정과 멀티 필드 활용이 검색 품질에 직접적인 영향을 미친다
- RDBMS와의 데이터 동기화는 CDC 기반(binlog, Debezium)이 실시간성과 안정성 면에서 유리하다
- 대규모 트래픽 환경에서는 샤드 전략, 캐싱, Bulk API 최적화가 성능의 핵심이다
- 운영 단계에서 클러스터 헬스, 느린 쿼리 로그, 디스크 Watermark 모니터링은 필수이다
- 장애 대응을 위해 스냅샷 정책과 Cross-Cluster Replication을 사전에 구성해야 한다

---

## 키워드

- **Elasticsearch**: Apache Lucene 기반의 분산 오픈소스 검색 및 분석 엔진. RESTful API와 JSON 도큐먼트 형식으로 동작하며, 수평 확장이 가능한 분산 아키텍처를 제공한다
- **e-commerce**: 전자상거래. 인터넷을 통한 상품·서비스 거래 환경으로, 검색·추천·개인화가 구매 전환율에 직접적인 영향을 미친다
- **검색엔진**: 텍스트 데이터를 색인(Inverted Index)하고 관련성 기반으로 순위를 매겨 결과를 반환하는 시스템. RDBMS의 LIKE 검색과 달리 형태소 분석, 동의어 처리, 관련성 점수 계산 등을 지원한다
- **인덱스 설계**: Elasticsearch에서 데이터 저장 구조를 정의하는 작업. 매핑(타입 정의), 분석기(텍스트 처리 방식), 샤드 수 등을 결정하며, 초기 설계가 이후 검색 품질과 성능에 결정적인 영향을 준다
- **샤드 (Shard)**: 인덱스를 물리적으로 분할한 단위. Primary Shard는 쓰기를 담당하고 Replica Shard는 가용성과 읽기 분산을 담당한다. 샤드 수는 인덱스 생성 시 고정되므로 초기 결정이 중요하다
- **nori 형태소 분석기**: Elasticsearch 공식 한국어 형태소 분석 플러그인. mecab-ko-dic 사전을 기반으로 한국어 문장을 형태소 단위로 분리하며, 사용자 사전 추가와 복합어 분해 모드 설정이 가능하다
- **CDC (Change Data Capture)**: 데이터베이스의 변경 이벤트(INSERT/UPDATE/DELETE)를 실시간으로 감지하여 다른 시스템에 전파하는 기술. MySQL binlog를 기반으로 동작하며, Debezium이 대표적인 구현체이다
- **데이터 동기화**: RDBMS의 원본 데이터를 Elasticsearch에 지속적으로 반영하는 프로세스. Logstash JDBC 폴링 방식과 Debezium + Kafka Connect 기반 CDC 방식으로 구현할 수 있으며, 후자가 실시간성과 안정성에서 우수하다
- **검색 품질**: 사용자가 원하는 결과를 얼마나 정확하고 완전하게 반환하는지를 나타내는 지표. 정밀도(Precision)와 재현율(Recall)로 측정하며, 관련성 점수 튜닝·동의어·Boosting으로 개선한다
- **클러스터 운영**: Elasticsearch 클러스터의 상태 모니터링, 성능 최적화, 장애 대응, 스냅샷 관리 등을 포함한 전반적인 운영 업무. 클러스터 헬스(Green/Yellow/Red), Slow Log, 디스크 Watermark 관리가 핵심이다

---

## 참고 자료
- [Elasticsearch 공식 문서](https://www.elastic.co/docs/reference/elasticsearch)
- [Korean (nori) Analysis Plugin 공식 문서](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori)
- [nori analyzer 설정 가이드](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori-analyzer)
- [Mapping 공식 문서](https://www.elastic.co/docs/manage-data/data-store/mapping)
- [Multi-fields 공식 문서](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/multi-fields)
- [Doc Values 공식 문서](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/doc-values)
- [Bulk API 공식 문서](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html)
- [Scroll API 공식 문서](https://www.elastic.co/guide/en/elasticsearch/reference/current/scroll-api.html)
- [Shard 크기 가이드](https://www.elastic.co/docs/deploy-manage/production-guidance/optimize-performance/size-shards)
- [Function Score Query 공식 문서](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-function-score-query.html)
- [Circuit Breaker 오류 문서](https://www.elastic.co/guide/en/elasticsearch/reference/current/circuit-breaker-errors.html)
- [Watermark 오류 및 해결 가이드](https://www.elastic.co/docs/troubleshoot/elasticsearch/fix-watermark-errors)
- [Debezium 아키텍처 공식 문서](https://debezium.io/documentation/reference/stable/architecture.html)
- [Debezium + Kafka + Elasticsearch 연동 가이드](https://debezium.io/blog/2018/01/17/streaming-to-elasticsearch/)
