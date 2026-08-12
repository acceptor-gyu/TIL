# Redis Sorted Set 활용되는 곳

## 개요
Redis Sorted Set(ZSet)은 **고유한 member 문자열들이 score(부동소수점)를 기준으로 항상 정렬된 상태로 유지되는 자료구조**다. Set처럼 중복 없는 멤버 집합이면서, 동시에 Hash처럼 각 멤버가 값(score)에 매핑되어 있어 "정렬된 Set + Hash"의 특성을 함께 가진다. 별도의 정렬 연산 없이 항상 정렬된 결과를 O(log N)에 조회할 수 있다는 점 덕분에 리더보드, 우선순위 큐, 지연 큐, Rate Limiting, GEO 등 실무에서 매우 폭넓게 활용된다.

## 상세 내용

### 1. Sorted Set이란

- **정의**: `ZADD key score member`로 추가하며, 동일한 score가 여러 개 존재할 수 있지만 member는 유일해야 한다. score가 다르면 score 오름차순으로, score가 같으면 member 문자열의 사전식(lexicographical, `memcmp` 기준) 순서로 정렬된다.
- **Set과의 차이**: 일반 Set은 멤버 간 순서 개념이 없지만, Sorted Set은 삽입 시점과 무관하게 항상 score 순으로 정렬된 상태를 유지한다. "정렬을 요청 시점에 수행하는 것이 아니라, 자료구조 자체가 이미 정렬되어 있다"는 점이 핵심이다.
- **내부 구현(dual-ported)**: Redis 공식 문서에 따르면 Sorted Set은 **Skip List + Hash Table의 이중 자료구조**로 구현된다.
  - Hash Table: member → score 매핑을 O(1)에 조회(`ZSCORE`)하기 위해 사용.
  - Skip List: score 순서를 유지하며 범위 조회(`ZRANGE`, `ZRANGEBYSCORE` 등)를 O(log N)에 수행하기 위해 사용.
  - 다만 이 이중 구조는 멤버 수/값 크기가 임계치를 넘었을 때만 사용되며, 작은 Sorted Set은 아래 4번에서 다루는 **listpack** 인코딩으로 훨씬 가볍게 저장된다.

### 2. 주요 명령어

| 명령어 | 설명 | 시간 복잡도 |
|---|---|---|
| `ZADD key score member [score member ...]` | 멤버 추가/score 갱신, 키 없으면 생성 | O(log N) (멤버당) |
| `ZREM key member` | 멤버 제거 | O(M log N) |
| `ZINCRBY key increment member` | 멤버 score를 원자적으로 증감 | O(log N) |
| `ZSCORE key member` | 특정 멤버의 score 조회 | O(1) |
| `ZRANGE key start stop [WITHSCORES]` | 인덱스(순위) 범위로 오름차순 조회 (Redis 6.2+부터 `REV`/`BYSCORE`/`BYLEX` 옵션 통합) | O(log N + M) |
| `ZREVRANGE key start stop` | 인덱스 범위 내림차순 조회 | O(log N + M) |
| `ZRANGEBYSCORE key min max` | score 범위 조회 (`-inf`, `+inf`, `(` 로 exclusive 지정 가능) | O(log N + M) |
| `ZRANK key member` / `ZREVRANK key member` | 오름차순/내림차순 기준 순위(0-base) 조회 | O(log N) |
| `ZCARD key` | 전체 멤버 수 | O(1) |
| `ZCOUNT key min max` | score 범위 내 멤버 개수 | O(log N) |
| `ZRANGEBYLEX` / `ZREVRANGEBYLEX` | score가 모두 동일할 때 사전식 범위 조회 | O(log N + M) |
| `ZPOPMIN` / `ZPOPMAX` | score가 가장 낮은/높은 멤버를 꺼내며 제거 | O(log N · M) |
| `ZREMRANGEBYSCORE` / `ZREMRANGEBYRANK` | score/순위 범위에 해당하는 멤버 일괄 삭제 | O(log N + M) |
| `ZUNIONSTORE` / `ZINTERSTORE` | 여러 Sorted Set의 합집합/교집합을 score 합산 등으로 저장 | O(N) ~ O(N·K) |

`ZADD`, `ZRANGE` 등 대부분의 연산은 N(전체 멤버 수)에 대해 O(log N)이며, 여러 멤버를 반환/처리하는 연산은 반환 개수 M이 추가되어 O(log N + M) 형태를 가진다. 이 성질 덕분에 "정렬된 상위/하위 K개"를 뽑는 연산이 매우 빠르다.

### 3. 활용되는 곳

#### 1) 실시간 랭킹 / 리더보드
- score를 게임 점수, 좋아요 수, 조회수 등으로 두면 `ZADD`/`ZINCRBY`로 점수를 갱신하고, `ZREVRANGE key 0 9 WITHSCORES`로 상위 10명을 O(log N)에 즉시 조회할 수 있다.
- `ZRANK`/`ZREVRANK`로 특정 유저의 현재 순위를, `ZSCORE`로 특정 유저의 점수를 O(1)~O(log N)에 조회할 수 있어, 별도의 정렬/집계 배치 없이 실시간 랭킹 서비스를 구현할 수 있다.

```
ZADD leaderboard:game1 1500 "user:1001"
ZINCRBY leaderboard:game1 50 "user:1001"
ZREVRANGE leaderboard:game1 0 9 WITHSCORES   # TOP 10
ZREVRANK leaderboard:game1 "user:1001"       # 내 순위
```

#### 2) 우선순위 큐 (Priority Queue)
- score를 우선순위 값으로 사용하면, `ZADD`로 작업을 등록하고 `ZPOPMIN`(가장 낮은 score = 가장 높은 우선순위)으로 원자적으로 꺼내 처리할 수 있다.
- List 기반 큐(`LPUSH`/`RPOP`)와 달리 우선순위 재조정(re-prioritize)이 `ZADD`(같은 멤버에 다른 score) 한 번으로 가능하다.

#### 3) 지연 작업 스케줄링 (Delayed Queue)
- score를 "실행되어야 할 시각(Unix timestamp)"으로 저장해두고, 워커가 주기적으로 `ZRANGEBYSCORE key -inf <현재시각>`으로 실행 시각이 지난 작업만 조회 후 `ZREM`으로 제거하며 처리한다.
- 이메일 재전송, 주문 자동 취소, 알림 예약 발송 등 "특정 시점에 한 번 실행"되어야 하는 지연 작업 큐로 널리 쓰인다.

#### 4) Rate Limiting (Sliding Window Log)
- 요청마다 score를 요청 시각(timestamp)으로 하여 `ZADD`로 기록하고, 매 요청 시 `ZREMRANGEBYSCORE key -inf <현재시각-윈도우>`로 윈도우 밖의 오래된 기록을 제거한 뒤 `ZCARD`(또는 `ZCOUNT`)로 윈도우 내 요청 수를 세어 제한 여부를 판단한다.
- Fixed Window 방식과 달리 윈도우 경계에서 순간적으로 2배 트래픽이 몰리는 문제(burst)가 없고, 개별 요청 단위로 정확한 "슬라이딩" 카운트를 셀 수 있다.
- 이 세 단계(`ZREMRANGEBYSCORE` → `ZADD` → `ZCARD`/`ZRANGE`)는 서로 다른 요청이 끼어들면 카운트가 부정확해질 수 있으므로, `MULTI/EXEC` 트랜잭션이나 Lua 스크립트(`EVAL`)로 원자적으로 묶어 실행하는 것이 일반적이다.

```
ZREMRANGEBYSCORE rate:user:1001 -inf (now-window)
ZADD rate:user:1001 now now              # score와 member 모두 now(고유값 필요시 now:uuid)
ZCARD rate:user:1001                     # 윈도우 내 요청 수
```

#### 5) 시계열 데이터 / 최근 활동 이력
- score를 이벤트 발생 시각으로 사용하면, "최근 N분간 발생한 이벤트"나 "특정 구간의 이벤트 목록"을 `ZRANGEBYSCORE`로 즉시 조회할 수 있어 최근 접속 목록, 활동 로그, 알림 피드 등에 활용된다.

#### 6) 지리 정보 (GEO 명령)
- `GEOADD`, `GEOSEARCH`, `GEODIST` 등 Redis의 GEO 계열 명령은 실제로는 별도 자료구조가 아니라 **Sorted Set 위에 구현**되어 있다. 위경도를 52비트 Geohash 값으로 인코딩해 score로 저장하기 때문에, `GEOADD`로 추가된 위치 데이터는 `ZSCORE`, `ZRANGE` 등 Sorted Set 명령으로도 그대로 다룰 수 있다.

#### 7) 자동완성 / 사전식 범위 조회 (ZRANGEBYLEX)
- 모든 멤버에 동일한 score(예: 0)를 부여하면 Sorted Set은 사전식으로 정렬되며, `ZRANGEBYLEX`로 특정 접두사 범위(`[A [L` 처럼 부등호 형태)를 조회할 수 있다. 이를 이용해 자동완성(autocomplete)이나 문자열 기반 2차 인덱스를 구현할 수 있다.
- Redis 2.8부터 지원되며, `ZLEXCOUNT`/`ZREMRANGEBYLEX`와 함께 사용된다.

### 4. 성능 특성 및 메모리 인코딩

- 대부분의 연산은 O(log N)이며, 다수 결과를 반환하는 연산은 O(log N + M)이다. `ZRANGE`처럼 대량(수만 건 이상)의 결과를 반환하는 호출은 M이 커지면서 비용이 커질 수 있어 페이지네이션(`LIMIT` 개념의 `ZRANGEBYSCORE ... LIMIT offset count`)을 권장한다.
- **listpack 인코딩**: Sorted Set의 멤버 수가 `zset-max-listpack-entries`(기본 128) 이하이고, 각 멤버 값 크기가 `zset-max-listpack-value`(기본 64바이트) 이하이면 Redis는 Skip List + Hash Table 대신 **listpack**(단일 연속 메모리 블록에 `member, score` 쌍을 직렬화한 압축 구조)으로 저장한다. 작은 컬렉션에서는 포인터 오버헤드가 없어 메모리 효율이 훨씬 높고, 캐시 지역성 덕분에 오히려 빠르다.
- 임계치를 초과하면 Redis가 자동으로 Skip List + Hash Table 인코딩(내부적으로 `skiplist`)으로 전환한다. 이 전환은 단방향(작아져도 다시 listpack으로 되돌아가지 않음)이다.
- (참고) listpack은 과거 `ziplist`를 대체한 구조로, Redis 7.0부터 Sorted Set/Hash/List 등 다수 자료구조에서 ziplist 대신 listpack이 기본 인코딩으로 사용된다.
- `zset-max-listpack-entries`/`zset-max-listpack-value`를 조정해 메모리-CPU 트레이드오프를 튜닝할 수 있다. 값을 낮추면 더 빨리 skiplist로 전환되어 범위 조회 성능은 좋아지지만 메모리 사용량이 늘어난다.

## 핵심 정리
- Sorted Set은 "score로 항상 정렬된 유일 멤버 집합"이며, 내부적으로 (큰 경우) Skip List + Hash Table, (작은 경우) listpack으로 구현되어 정렬 유지와 조회를 모두 O(log N) 또는 그 이하로 처리한다.
- score를 무엇으로 두느냐(점수, 우선순위, 실행 시각, 요청 시각, Geohash, 고정값 0)에 따라 리더보드, 우선순위 큐, 지연 큐, Rate Limiter, GEO 인덱스, 자동완성 등 전혀 다른 용도로 재활용할 수 있는 범용 자료구조다.
- Rate Limiting처럼 여러 명령을 조합하는 패턴은 반드시 트랜잭션(`MULTI/EXEC`)이나 Lua 스크립트로 원자성을 보장해야 한다.

## 기술적 한계와 보완 전략
- **대용량 컬렉션의 O(log N + M) 비용**: `ZRANGE`로 한 번에 수만 건 이상을 조회하면 M이 커져 응답 지연과 메모리 스파이크가 발생할 수 있다. → `LIMIT`을 활용한 페이지네이션, 또는 필요한 범위만 조회하도록 애플리케이션에서 청크 단위 처리.
- **score 정밀도 한계**: score는 64비트 부동소수점(double)이라 정수형 timestamp를 다룰 때는 문제없지만, 매우 큰 정수나 소수점 이하 정밀도가 중요한 값에는 부동소수점 오차가 발생할 수 있다. → 필요시 정수 스케일링(예: 마이크로초 단위)이나 member 문자열에 tie-breaker를 포함.
- **동일 score 대량 발생 시 정렬 편향**: score가 같은 멤버가 많으면 사전식 정렬 규칙에 의존하게 되어 의도한 순서가 아닐 수 있다. → score에 미세한 offset(예: 소수점 뒤에 타임스탬프 일부)을 추가해 tie-break.
- **단일 키에 데이터가 몰리는 핫키(Hot Key) 문제**: 전체 랭킹을 하나의 키에 저장하면 트래픽이 그 키에 집중된다. → 샤딩(구간별/지역별 키 분리) 후 애플리케이션에서 `ZUNIONSTORE` 등으로 병합하거나, Redis Cluster의 해시 태그를 고려.
- **Rate Limiting의 메모리 증가**: 슬라이딩 윈도우 로그 방식은 요청마다 멤버를 추가하므로 트래픽이 많으면 메모리 사용량이 커진다. → 매 요청마다 `ZREMRANGEBYSCORE`로 윈도우 밖 데이터를 정리하고, 필요시 카운터 기반 슬라이딩 윈도우(Sliding Window Counter) 방식과 트레이드오프를 검토.
- **listpack → skiplist 전환은 비가역**: 한 번 skiplist로 전환되면 멤버가 줄어도 listpack으로 되돌아가지 않아 메모리 절감 효과가 사라진다. → 필요시 키를 재생성(`DUMP`/`RESTORE`, 또는 신규 키에 복사 후 교체)해 재압축할 수 있다.

## 키워드
- **Sorted Set (ZSet)**: score(부동소수점)로 항상 정렬된 상태를 유지하는 고유 멤버 집합. Set과 Hash의 특성을 결합한 Redis 자료구조.
- **Skip List**: 여러 레벨의 연결 리스트를 이용해 평균 O(log N)의 탐색/삽입/삭제를 제공하는 확률적 자료구조. Sorted Set이 큰 경우 내부적으로 정렬 순서를 유지하는 데 사용된다.
- **Leaderboard (리더보드)**: `ZADD`/`ZINCRBY`로 점수를 갱신하고 `ZREVRANGE`로 상위 랭킹을 실시간 조회하는 Sorted Set의 대표적 활용 사례.
- **ZADD / ZRANGEBYSCORE**: 멤버-score를 추가/갱신하는 명령과, score 범위로 멤버를 조회하는 명령. Sorted Set 활용의 핵심 명령어 쌍.
- **우선순위 큐 (Priority Queue)**: score를 우선순위로 사용해 `ZPOPMIN`/`ZPOPMAX`로 가장 시급한 작업을 원자적으로 꺼내는 큐 패턴.
- **Sliding Window Rate Limiting**: 요청 시각을 score로 저장하고, 윈도우 밖 기록을 `ZREMRANGEBYSCORE`로 제거하며 윈도우 내 요청 수를 세는 슬라이딩 윈도우 방식의 API 요청 제한 기법.
- **Delayed Queue (지연 큐)**: score를 실행 예정 시각으로 저장해두고, 워커가 현재 시각 이전의 항목만 골라 처리하는 지연 작업 스케줄링 패턴.
- **ZRANGEBYLEX**: 모든 멤버의 score가 동일할 때 사전식(lexicographical) 순서로 범위를 조회하는 명령. 자동완성, 문자열 인덱스 구현에 활용된다.
- **listpack 인코딩**: 멤버 수/값 크기가 임계치(`zset-max-listpack-entries`/`value`) 이하인 작은 Sorted Set을 연속 메모리 블록으로 압축 저장하는 Redis 7.0+의 기본 인코딩. Redis 7.0 이전의 `ziplist`를 대체했다.
- **GEO 명령**: `GEOADD`, `GEOSEARCH` 등 위경도 기반 명령어 집합으로, 위치를 Geohash 값으로 인코딩해 score로 사용하는 방식으로 Sorted Set 위에 구현되어 있다.

## 참고 자료
- [Redis 공식 문서 - Sorted sets](https://redis.io/docs/latest/develop/data-types/sorted-sets/)
- [Redis 공식 문서 - ZADD](https://redis.io/commands/zadd/)
- [Redis 공식 문서 - ZRANGEBYSCORE](https://redis.io/commands/zrangebyscore/)
- [Redis 공식 문서 - ZRANGEBYLEX](https://redis.io/commands/zrangebylex/)
- [Redis 공식 문서 - Geospatial indexes](https://redis.io/docs/latest/develop/data-types/geospatial/)
- [Redis 공식 튜토리얼 - Rate limiting](https://redis.io/learn/howtos/ratelimiting)
- [Redis GitHub - t_zset.c 소스코드](https://github.com/redis/redis/blob/unstable/src/t_zset.c)
