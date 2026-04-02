# Elasticsearch를 활용해 검색 엔진 품질 높이기

## 개요
Elasticsearch를 단순 전문 검색 도구가 아닌, 검색 품질을 체계적으로 끌어올리는 엔진으로 활용하기 위한 핵심 전략과 기법을 정리한다.

## 상세 내용

### 1. Analyzer와 Tokenizer 커스터마이징

#### 한국어 형태소 분석기 (nori) 설정

Elasticsearch에서 한국어 검색을 잘 처리하려면 `analysis-nori` 플러그인을 설치해야 한다. nori는 Lucene nori 모듈을 기반으로 mecab-ko-dic 사전을 활용해 한국어 형태소 분석을 수행한다.

```bash
# 플러그인 설치
sudo bin/elasticsearch-plugin install analysis-nori
```

인덱스 생성 시 nori tokenizer를 custom analyzer에 적용할 수 있다.

```json
PUT /my-index
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "nori_tokenizer_custom": {
          "type": "nori_tokenizer",
          "decompound_mode": "mixed",
          "user_dictionary_rules": ["삼성전자", "카카오페이", "쿠팡로켓"]
        }
      },
      "analyzer": {
        "korean_analyzer": {
          "type": "custom",
          "tokenizer": "nori_tokenizer_custom",
          "filter": ["nori_part_of_speech"]
        }
      }
    }
  }
}
```

`user_dictionary_rules`를 통해 기본 사전에 없는 고유명사나 도메인 특화 용어를 inline으로 추가할 수 있다. 수천 개 이상의 단어를 관리한다면 외부 파일(`user_dictionary`)을 사용하는 것이 더 효율적이다.

#### Synonym Filter를 활용한 동의어 처리

동의어 필터를 추가하면 "핸드폰"과 "휴대폰", "스마트폰"을 동일하게 검색할 수 있다.

```json
"filter": {
  "synonym_filter": {
    "type": "synonym",
    "synonyms": [
      "핸드폰, 휴대폰, 스마트폰",
      "노트북, 랩탑"
    ]
  }
}
```

동의어 처리는 인덱싱 시점보다 검색 시점에만 적용하는 편이 유리하다. 인덱싱에 적용하면 인덱스가 커지고, 동의어 목록 변경 시 재색인이 필요하기 때문이다.

#### Edge N-gram을 활용한 자동완성 구현

자동완성은 Edge N-gram 토크나이저를 사용해 구현한다. "삼성"이라는 단어를 입력하면 "삼", "삼성"처럼 앞에서부터 잘라낸 토큰을 인덱싱한다.

```json
"tokenizer": {
  "edge_ngram_tokenizer": {
    "type": "edge_ngram",
    "min_gram": 1,
    "max_gram": 10,
    "token_chars": ["letter", "digit"]
  }
}
```

자동완성 필드는 검색 시 일반 `standard` 분석기를 써야 한다. 그렇지 않으면 검색어도 n-gram으로 쪼개져 의도하지 않은 매칭이 발생한다.

---

### 2. Relevance Scoring 튜닝

#### TF-IDF와 BM25 스코어링 알고리즘의 차이

Elasticsearch는 버전 5.0부터 기본 유사도 알고리즘으로 **BM25(Okapi BM25)**를 사용한다. 이전 버전에서는 TF-IDF를 사용했다.

| 항목 | TF-IDF | BM25 |
|------|--------|------|
| 문서 길이 정규화 | 고려하지 않음 | 평균 문서 길이 대비 현재 문서 길이를 반영 |
| 단어 빈도 포화 | 선형 증가 (제한 없음) | k1 파라미터로 포화점 설정 (비선형 증가) |
| 파라미터 수 | 1개 (IDF) | 3개 (k1, b, d) |
| 장문 문서 처리 | 불리 (무한 증가) | 유리 (포화 처리) |

BM25의 핵심 파라미터:
- `k1` (기본값 1.2): 단어 빈도 포화 속도. 값이 낮을수록 빠르게 포화됨
- `b` (기본값 0.75): 문서 길이 정규화 강도. 0이면 정규화 안 함, 1이면 완전 정규화

```json
PUT /my-index
{
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "similarity": "BM25"
      }
    }
  },
  "settings": {
    "similarity": {
      "custom_bm25": {
        "type": "BM25",
        "k1": 1.5,
        "b": 0.8
      }
    }
  }
}
```

#### Function Score Query로 비즈니스 로직 반영하기

검색 점수에 순수 텍스트 유사도 외에 비즈니스 지표(최신성, 인기도, 거리 등)를 반영하고 싶을 때 `function_score` 쿼리를 사용한다.

```json
GET /products/_search
{
  "query": {
    "function_score": {
      "query": { "match": { "name": "노트북" } },
      "functions": [
        {
          "field_value_factor": {
            "field": "popularity_score",
            "factor": 1.5,
            "modifier": "log1p",
            "missing": 1
          }
        },
        {
          "gauss": {
            "created_at": {
              "origin": "now",
              "scale": "7d",
              "decay": 0.5
            }
          }
        }
      ],
      "score_mode": "sum",
      "boost_mode": "multiply"
    }
  }
}
```

- `score_mode`: 여러 함수의 결과를 어떻게 합산할지 (`sum`, `avg`, `max`, `min`, `multiply`)
- `boost_mode`: 함수 점수와 쿼리 점수를 어떻게 결합할지 (`multiply`, `replace`, `sum`, `avg`)

#### Boosting을 활용한 필드별 가중치 조정

`^` 기호로 특정 필드에 가중치를 부여할 수 있다.

```json
{
  "multi_match": {
    "query": "스프링 부트",
    "fields": ["title^3", "tags^2", "body"]
  }
}
```

`title` 필드의 매칭은 3배, `tags`는 2배, `body`는 1배의 가중치를 갖는다.

---

### 3. Multi-match와 복합 쿼리 전략

#### best_fields vs most_fields vs cross_fields 차이

세 타입 모두 `multi_match` 쿼리 안에서 사용하며, 검색 대상 데이터의 성격에 따라 선택해야 한다.

| 타입 | 접근 방식 | 적합한 상황 |
|------|-----------|-------------|
| `best_fields` (기본값) | 가장 점수가 높은 단일 필드를 선택 | 검색어가 한 필드 안에 모여 있을 때 의미 있는 경우 |
| `most_fields` | 매칭된 모든 필드의 점수를 합산 | 같은 텍스트를 여러 분석기로 인덱싱한 경우 (동의어, 어간 추출 등) |
| `cross_fields` | 용어 중심 접근, 각 용어를 모든 필드에서 탐색 | first_name/last_name처럼 의미가 여러 필드에 분산된 구조 |

```json
// best_fields: "갤럭시 폴드"가 title 한 필드에 모두 있으면 높은 점수
{
  "multi_match": {
    "query": "갤럭시 폴드",
    "type": "best_fields",
    "fields": ["title", "description"],
    "tie_breaker": 0.3
  }
}

// cross_fields: "John Smith"에서 John은 first_name, Smith는 last_name에서 찾음
{
  "multi_match": {
    "query": "John Smith",
    "type": "cross_fields",
    "fields": ["first_name", "last_name"],
    "operator": "and"
  }
}
```

`tie_breaker`는 `best_fields`에서 최고 점수 필드 외 다른 필드의 기여도를 조절한다. 0이면 최고 필드만, 1이면 `most_fields`와 동일하게 동작한다.

#### Bool Query 조합 (must, should, filter, must_not)

```json
{
  "bool": {
    "must": [
      { "match": { "title": "Elasticsearch" } }
    ],
    "should": [
      { "match": { "tags": "검색엔진" } },
      { "match": { "tags": "오픈소스" } }
    ],
    "filter": [
      { "term": { "status": "published" } },
      { "range": { "price": { "lte": 50000 } } }
    ],
    "must_not": [
      { "term": { "category": "deprecated" } }
    ],
    "minimum_should_match": 1
  }
}
```

- `must`: 반드시 일치해야 하며 스코어에 기여
- `should`: 일치할수록 스코어가 높아지나 필수는 아님
- `filter`: 필수 조건이지만 스코어에 기여하지 않음 (캐싱 효율 좋음)
- `must_not`: 해당 조건이 있으면 제외

`filter` 절은 스코어 계산을 생략하므로 날짜 범위, 상태값 필터링 등 정적 조건에는 `filter`를 쓰는 것이 성능상 유리하다.

---

### 4. 검색 품질 측정과 개선 사이클

#### Precision과 Recall의 트레이드오프

- **Precision (정밀도)**: 검색 결과 중 실제로 관련 있는 문서의 비율 → 노이즈가 적은 검색
- **Recall (재현율)**: 실제 관련 문서 중 검색 결과에 포함된 비율 → 누락이 적은 검색

두 지표는 서로 상충한다. 검색 조건을 엄격하게 하면 Precision은 올라가지만 Recall이 떨어지고, 느슨하게 하면 반대가 된다. 서비스 목적에 따라 어느 쪽을 우선할지 결정해야 한다.

#### Search Quality Metrics (DCG, nDCG, MRR)

**DCG (Discounted Cumulative Gain)**
검색 결과 순위와 관련도를 함께 고려한 지표다. 상위에 노출될수록 더 큰 가중치를 부여하며, 관련 문서가 낮은 순위에 있으면 패널티를 준다.

```
DCG@k = sum(rel_i / log2(i+1))  for i=1 to k
```

**nDCG (Normalized DCG)**
DCG를 이상적인 순서(IDCG)로 나누어 0~1 사이의 값으로 정규화한 것. 쿼리 간 비교가 가능해진다.

```
nDCG@k = DCG@k / IDCG@k
```

**MRR (Mean Reciprocal Rank)**
첫 번째 관련 문서가 몇 위에 등장하는지를 측정. 정답이 하나인 FAQ 검색 등에 적합하다.

```
MRR = (1/|Q|) * sum(1 / rank_i)
```

#### Ranking Evaluation API 활용

Elasticsearch는 `_rank_eval` API로 직접 검색 품질을 측정할 수 있다.

```json
POST /products/_rank_eval
{
  "requests": [
    {
      "id": "노트북_검색",
      "request": {
        "query": { "match": { "name": "노트북" } }
      },
      "ratings": [
        { "_index": "products", "_id": "1", "rating": 3 },
        { "_index": "products", "_id": "2", "rating": 1 },
        { "_index": "products", "_id": "3", "rating": 0 }
      ]
    }
  ],
  "metric": {
    "ndcg": {
      "k": 5,
      "normalize": true
    }
  }
}
```

- `rating`은 관련도 점수 (예: 0=무관련, 1=약간 관련, 2=관련, 3=매우 관련)
- 메트릭으로 `precision`, `recall`, `dcg`, `ndcg`, `mean_reciprocal_rank`, `expected_reciprocal_rank` 중 선택 가능

---

### 5. 성능과 품질을 동시에 잡는 인덱스 설계

#### Mapping 설계 시 text vs keyword 필드 타입 선택 기준

| 타입 | 특징 | 사용 사례 |
|------|------|-----------|
| `text` | 분석기를 통해 토크나이징됨 | 전문 검색 (match 쿼리) |
| `keyword` | 원본 문자열 그대로 저장 | 정렬, 집계, 필터링, term 쿼리 |

#### Multi-field Mapping으로 검색과 정렬 동시 지원

같은 필드를 `text`와 `keyword` 두 가지로 매핑하면 검색과 정렬을 모두 지원할 수 있다.

```json
PUT /products
{
  "mappings": {
    "properties": {
      "category": {
        "type": "text",
        "analyzer": "korean_analyzer",
        "fields": {
          "keyword": {
            "type": "keyword"
          }
        }
      }
    }
  }
}
```

전문 검색은 `category` 필드로, 정렬이나 집계(Aggregation)는 `category.keyword` 필드로 한다.

#### Index Template과 Component Template 활용

**Component Template**: 재사용 가능한 설정 블록. 매핑, 세팅 등을 모듈처럼 분리 관리한다.

```json
PUT /_component_template/korean_analyzer_settings
{
  "template": {
    "settings": {
      "analysis": {
        "analyzer": {
          "korean": {
            "type": "custom",
            "tokenizer": "nori_tokenizer"
          }
        }
      }
    }
  }
}
```

**Index Template**: 인덱스 생성 시 자동으로 적용되는 설정. `composed_of`로 Component Template을 조합한다.

```json
PUT /_index_template/products_template
{
  "index_patterns": ["products-*"],
  "composed_of": ["korean_analyzer_settings", "common_mappings"],
  "priority": 100,
  "template": {
    "settings": {
      "number_of_shards": 3,
      "number_of_replicas": 1
    }
  }
}
```

`priority`가 높은 템플릿이 우선 적용된다. `composed_of`에 나열된 순서대로 병합되며 나중에 오는 Component Template이 이전 것을 덮어쓴다.

---

### 6. 검색 UX 향상 기법

#### Highlight로 검색 결과 강조 표시

사용자가 왜 해당 문서가 검색됐는지 알 수 있도록 매칭된 부분을 강조한다.

```json
GET /articles/_search
{
  "query": { "match": { "body": "Elasticsearch 검색" } },
  "highlight": {
    "fields": {
      "body": {
        "pre_tags": ["<em>"],
        "post_tags": ["</em>"],
        "fragment_size": 150,
        "number_of_fragments": 3
      }
    }
  }
}
```

`fragment_size`로 하이라이트 텍스트 조각의 길이를, `number_of_fragments`로 조각 수를 조절한다.

#### Suggest API (Term, Phrase, Completion)로 오타 교정 및 추천

**Term Suggester**: 개별 단어 오타 교정 (편집 거리 기반)
```json
POST /articles/_search
{
  "suggest": {
    "my_suggestion": {
      "text": "elasticsaerch",
      "term": {
        "field": "body",
        "suggest_mode": "popular"
      }
    }
  }
}
```

**Phrase Suggester**: 구 단위 오타 교정 (n-gram 언어 모델 기반)
- "noble prize" → "nobel prize"처럼 전체 구를 교정
- shingle analyzer와 함께 사용해야 정확도가 높아짐

**Completion Suggester**: 빠른 자동완성 (In-memory FST 기반)
```json
// 매핑 시 completion 타입 지정
"suggest_field": {
  "type": "completion"
}

// 검색 시
POST /products/_search
{
  "suggest": {
    "product_suggest": {
      "prefix": "갤럭",
      "completion": {
        "field": "suggest_field",
        "fuzzy": { "fuzziness": 1 },
        "skip_duplicates": true,
        "size": 5
      }
    }
  }
}
```

Completion Suggester는 FST(Finite State Transducer)를 메모리에 올려두기 때문에 응답 속도가 매우 빠르다. 다만 메모리 사용량이 증가하므로 suggest 필드 데이터를 간결하게 유지해야 한다.

#### Aggregation을 활용한 Faceted Search (필터 카운트)

쇼핑몰의 "브랜드별 상품 수"처럼 검색 결과를 기준으로 카테고리별 카운트를 보여주는 기능이다.

```json
GET /products/_search
{
  "query": { "match": { "name": "노트북" } },
  "aggs": {
    "by_brand": {
      "terms": { "field": "brand.keyword", "size": 10 }
    },
    "price_range": {
      "range": {
        "field": "price",
        "ranges": [
          { "to": 500000 },
          { "from": 500000, "to": 1000000 },
          { "from": 1000000 }
        ]
      }
    }
  }
}
```

Aggregation은 `filter` 절에 영향을 받는다. 사용자가 "삼성" 브랜드를 선택해도 다른 브랜드의 카운트를 보여주고 싶다면 `post_filter`나 `global` aggregation을 활용한다.

---

## 핵심 정리

- Analyzer 커스터마이징은 검색 품질의 기반이다. nori tokenizer에 user_dictionary를 추가하고, synonym filter로 동의어를 처리하면 한국어 검색 품질이 크게 향상된다.
- BM25는 TF-IDF보다 문서 길이 정규화와 단어 빈도 포화를 잘 처리하며, `function_score`로 인기도나 최신성 같은 비즈니스 로직을 스코어에 추가로 반영할 수 있다.
- `multi_match` 타입은 데이터 구조에 따라 선택해야 한다. 단일 필드 집중형은 `best_fields`, 다중 분석기 필드는 `most_fields`, 분산 구조 데이터는 `cross_fields`가 적합하다.
- `bool` 쿼리에서 필터 조건은 스코어에 영향을 주지 않으므로 `filter` 절을 적극 활용해야 성능을 높일 수 있다.
- `_rank_eval` API로 nDCG, MRR 등의 지표를 측정해 검색 품질을 정량적으로 추적하고 개선 사이클을 돌릴 수 있다.
- Multi-field Mapping으로 `text`와 `keyword`를 함께 정의하고, Index/Component Template으로 설정을 재사용하면 유지보수성이 높아진다.
- Completion Suggester는 FST를 메모리에 올려두어 매우 빠르며, `fuzzy` 옵션으로 오타도 처리할 수 있다.

## 키워드

- `Elasticsearch`: 분산 검색 및 분석 엔진. Apache Lucene 기반으로 동작하며, RESTful API로 대규모 데이터의 전문 검색, 집계, 분석을 지원한다.
- `BM25`: Okapi BM25. Elasticsearch의 기본 유사도 알고리즘. 단어 빈도 포화(k1)와 문서 길이 정규화(b) 파라미터를 통해 TF-IDF보다 정확한 관련도 점수를 산출한다.
- `nori 형태소 분석기`: Elasticsearch 공식 한국어 분석 플러그인. mecab-ko-dic 기반으로 한국어 형태소 분석을 수행하며, user_dictionary_rules로 도메인 특화 단어를 추가할 수 있다.
- `Relevance Scoring`: 검색 쿼리와 문서 간의 관련도를 수치화하는 과정. BM25, Function Score Query, Boosting 등을 조합해 비즈니스 요구에 맞게 튜닝한다.
- `Function Score Query`: 텍스트 유사도 점수에 사용자 정의 함수(field_value_factor, gauss 등)를 결합해 인기도, 최신성, 거리 등의 비즈니스 로직을 검색 순위에 반영하는 쿼리 타입.
- `Multi-match Query`: 여러 필드를 동시에 검색하는 쿼리. `best_fields`, `most_fields`, `cross_fields` 타입으로 검색 전략을 다르게 설정할 수 있다.
- `nDCG`: Normalized Discounted Cumulative Gain. 검색 결과의 순위 품질을 0~1로 정규화한 지표. 상위 결과일수록 높은 가중치를 부여해 순위의 적절성을 평가한다.
- `Analyzer`: 텍스트를 토큰으로 변환하는 파이프라인. Character Filter → Tokenizer → Token Filter 순서로 처리되며, 검색 품질의 핵심 기반이 된다.
- `Suggest API`: Elasticsearch의 추천/교정 기능. Term Suggester(단어 오타 교정), Phrase Suggester(구 단위 교정), Completion Suggester(빠른 자동완성) 세 가지 타입을 제공한다.
- `Faceted Search`: 검색 결과를 카테고리, 가격 범위, 브랜드 등의 속성으로 분류하고 각 항목의 문서 수를 함께 보여주는 UX 패턴. Elasticsearch의 Aggregation으로 구현한다.

## 참고 자료
- [Korean (nori) analysis plugin | Elastic Reference](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori)
- [Practical BM25 - Part 2: The BM25 Algorithm and its Variables | Elastic Blog](https://www.elastic.co/blog/practical-bm25-part-2-the-bm25-algorithm-and-its-variables)
- [Multi-match query | Elasticsearch Reference](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-multi-match-query)
- [Suggesters | Elasticsearch Guide](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-suggesters.html)
- [Evaluate ranked search results | Elasticsearch API documentation](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-rank-eval)
- [How to Use the Ranking Evaluation API in Elasticsearch | Elastic Blog](https://www.elastic.co/blog/made-to-measure-how-to-use-the-ranking-evaluation-api-in-elasticsearch)
- [Templates | Elastic Docs](https://www.elastic.co/docs/manage-data/data-store/templates)
