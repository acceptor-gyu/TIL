# Outbox table에 event가 많이 쌓이면 polling과 발행 지연을 어떻게 줄일 수 있나요?

> 면접 예상 질문 대비용 정리

## 개요
- 질문 의도: Transactional Outbox 패턴 도입 이후, 이벤트 적재량이 커졌을 때 릴레이(Relay)의 조회/발행 처리량이 왜 무너지는지와 이를 어떻게 되돌릴지 설명할 수 있는가
- 전제 정리: Outbox 패턴을 쓰는 이유는 DB 트랜잭션(비즈니스 상태 변경)과 메시지 브로커 발행이라는 서로 다른 두 시스템 간의 "이중 쓰기(dual write)" 문제를 없애기 위함이다. 비즈니스 데이터 변경과 이벤트 저장을 같은 DB 트랜잭션 안에서 원자적으로 커밋하고, 별도의 Relay(Message Relay/Polling Publisher)가 Outbox 테이블을 읽어 브로커로 발행한다.
- 지연(Latency)과 처리량(Throughput)을 분리해서 답변해야 하는 이유: "느려졌다"는 현상은 (1) 이벤트 하나가 생성되어 발행되기까지 걸리는 시간(latency)의 문제일 수도 있고, (2) 단위 시간당 처리 가능한 이벤트 수(throughput)가 유입량을 못 따라가서 쌓이는 문제일 수도 있다. 둘의 원인과 해법이 다르므로 구분해서 진단해야 한다.

## 상세 내용

### 1. Outbox 패턴은 어떻게 동작하는가 (기본 전제)
최적화 이야기를 하기 전에, Outbox 테이블의 행이 어떤 생애주기를 갖는지 먼저 정리한다.

```
[애플리케이션]
  BEGIN
    UPDATE orders SET status = 'PAID' ...   -- (1) 비즈니스 상태 변경
    INSERT INTO outbox (...) VALUES (...)   -- (2) 발행할 이벤트를 같은 트랜잭션에 기록
  COMMIT                                    -- (1)(2)가 원자적으로 함께 커밋

[Relay = 별도 프로세스/스레드]
  (3) SELECT ... WHERE status = 'PENDING'   -- 미발행 이벤트 조회
  (4) 브로커(Kafka 등)로 발행 → ack 수신
  (5) UPDATE outbox SET status = 'PUBLISHED' -- 발행 완료 표시
```

핵심은 (2)가 브로커 호출이 아니라 **같은 DB에 대한 INSERT**라는 점이다. 브로커 발행은 트랜잭션에 참여할 수 없기 때문에, "DB 커밋은 성공했는데 발행은 실패" 또는 그 반대인 이중 쓰기(dual write) 문제가 생긴다. 이벤트를 일단 DB에 함께 적어두고 나중에 발행하는 방식으로 이 틈을 없애는 것이 Outbox 패턴이다.

**행 하나의 생애주기**

| 단계 | status | 의미 |
| --- | --- | --- |
| INSERT 직후 | `PENDING` | 발행 대기. Relay가 찾아가야 할 대상 |
| Relay가 선점 | `IN_PROGRESS` | 특정 Relay가 처리 중(클레임 방식일 때만 사용) |
| 브로커 ack 수신 후 | `PUBLISHED` | 발행 완료. **기능적으로는 더 이상 필요 없는 행** |
| 발행 반복 실패 | `FAILED` | 재시도 대상 또는 DLQ 이관 대상 |

**Q. 데이터를 계속 테이블에 쌓아두어야 하나?**
아니다. Outbox 테이블은 이벤트 **이력 저장소가 아니라 발행 대기 큐**다. `PUBLISHED`가 된 행은 발행이라는 목적을 이미 달성했으므로 시스템 동작에는 필요하지 않다. 그럼에도 즉시 지우지 않고 잠깐 남겨두는 이유는 다음과 같은 운영상의 필요 때문이다.
- 장애 조사: "그 이벤트가 실제로 발행됐는가"를 확인할 근거
- 재발행: 컨슈머 측 버그로 특정 기간 이벤트를 다시 흘려보내야 할 때의 원본
- 감사(audit) 요건이 있는 도메인에서의 증빙

즉 "영구 보관"이 아니라 **짧은 보관 기간(retention)을 둔 뒤 정리**하는 것이 정석이다. 감사 목적이 크다면 Outbox 테이블에 계속 두지 말고 별도의 `outbox_archive` 테이블이나 로그 저장소로 옮기는 편이 낫다. 조회 경로(=Relay가 매번 스캔하는 테이블)와 보관 경로를 분리하는 것이 목적이다.

**Q. PUBLISHED 행은 주기적으로 삭제하면 안 되나?**
삭제해도 되고, 오히려 **권장된다**. 이 TIL에서 다루는 성능 문제의 상당수는 "지우지 않아서" 생긴다. 다만 몇 가지 주의점이 있다.
- **삭제 시점**: 브로커의 ack를 확실히 받은 행만 지운다. 발행 확인 전에 지우면 이벤트가 영구 유실된다.
- **보관 기간**: 컨슈머 장애 복구에 필요한 시간(예: 1~7일)을 고려해 정한다. 무작정 짧게 잡으면 재발행 수단이 사라진다.
- **삭제 방식**: 한 번에 수백만 건을 `DELETE` 하면 락 보유 시간, undo/WAL 폭증, replication lag을 유발한다. 청크 단위 반복 삭제나 파티션 DROP을 쓴다.
- **MVCC 비용**: 삭제 자체가 공짜가 아니다. PostgreSQL은 `VACUUM`이 dead tuple을 회수해야 공간이 실제로 반환되고, MySQL InnoDB는 purge thread가 undo log를 정리한다.

이 정리 작업이 아예 없거나, 있더라도 **유입 속도 > (발행 속도 또는 정리 속도)** 인 상태가 지속되면 테이블이 무한히 커진다. 그때부터 아래에서 다룰 조회 지연과 발행 지연 문제가 본격적으로 드러난다.

### 2. 문제 상황 정의
Outbox 테이블 구조 예시:

```sql
CREATE TABLE outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, IN_PROGRESS, PUBLISHED, FAILED
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL
);
CREATE INDEX idx_outbox_status_id ON outbox (status, id);
```

이벤트 적재량이 증가하면 나타나는 증상:
- polling 쿼리 응답 시간 증가 (풀스캔에 가까운 조회, 인덱스 비효율)
- 발행 지연(end-to-end lag) 증가: "이벤트 생성 시각"과 "실제 브로커 발행 시각"의 차이가 벌어짐
- 릴레이 배치 주기와 유입량 불일치로 인한 백로그(backlog) 누적 — 처리 속도 < 유입 속도이면 시간이 지날수록 격차가 무한히 커진다

지연을 구성 요소로 분해하면 다음과 같다.

```
end-to-end lag
  = polling 주기 대기 시간 (다음 polling까지 기다리는 시간)
  + 조회(query) 시간
  + 브로커 발행(publish) 시간
  + 상태 업데이트(commit) 시간
```

각 구간이 어디서 병목인지 파악해야 올바른 처방이 나온다.

### 3. 왜 느려지는가 (원인 분석)
전제로 삼을 상황: 테이블에 누적 3,000만 건, 그중 `PENDING`은 항상 수백 건 남짓. Relay는 아래 쿼리를 1초마다 실행한다.

```sql
SELECT id, payload FROM outbox
WHERE status = 'PENDING' ORDER BY id LIMIT 100;
```

**논리적으로는 수백 건짜리 쿼리인데 왜 느려지는가**가 이 절의 질문이다.

#### 3-1. 낮은 카디널리티가 왜 문제인가
카디널리티(cardinality)는 컬럼이 가질 수 있는 값의 종류 수다. `status`는 `PENDING`/`IN_PROGRESS`/`PUBLISHED`/`FAILED` 4가지뿐이므로 카디널리티가 극단적으로 낮다. 여기서 파생되는 문제는 세 갈래다.

**(1) 옵티마이저의 선택도(selectivity) 오추정 → 인덱스를 안 쓴다**

옵티마이저는 통계 정보를 보고 "이 조건에 몇 행이 걸릴까"를 추정한 뒤 인덱스 스캔과 풀스캔 중 싼 쪽을 고른다. 값 분포에 대한 상세 통계가 없거나 낡았다면 균등 분포를 가정하므로, 4가지 값이라면 `status = 'PENDING'`에 **전체의 1/4인 750만 건**이 걸린다고 추정한다.

- 실제: 500건 → 인덱스 스캔이 압도적으로 유리
- 추정: 750만 건 → "그 정도면 랜덤 I/O로 훑느니 순차로 풀스캔이 싸다"고 판단

인덱스가 멀쩡히 있는데도 실행 계획이 `Seq Scan` / `type: ALL`로 떨어지는 전형적인 이유다. PostgreSQL은 `ANALYZE`가 수집하는 MCV(Most Common Values) 목록으로, MySQL은 히스토그램으로 이 편향을 어느 정도 잡아준다. 다만 Outbox처럼 **분포가 시시각각 변하는 테이블**은 통계가 금방 낡아 오추정이 재발한다. 진단할 때 `EXPLAIN ANALYZE`의 `rows=`(추정)와 `actual rows=`(실제) 괴리를 먼저 봐야 하는 이유다.

**(2) 단일 컬럼 인덱스 `(status)`는 걸려도 별 이득이 없다**

인덱스를 타더라도, 세컨더리 인덱스는 조건에 맞는 항목의 **위치(PostgreSQL: ctid, MySQL InnoDB: PK 값)**만 갖고 있다. `payload`를 읽으려면 그 위치로 다시 찾아가야 한다(테이블 랜덤 액세스 / PK 클러스터 인덱스 재탐색). 게다가 인덱스가 `id` 순서 정보를 갖고 있지 않으므로 `ORDER BY id`를 위해 **매칭된 행을 전부 모아 정렬**해야 하고, 그제서야 `LIMIT 100`이 적용된다. 낮은 카디널리티 컬럼 단독 인덱스가 "있으나 마나"라고 불리는 이유다.

**(3) `(status, id)` 복합 인덱스는 스캔 자체는 효율적이다 — 비용은 다른 데 있다**

여기서 흔한 오해를 짚고 갈 필요가 있다. B-tree는 선두 컬럼부터 정렬되므로 `(status, id)` 인덱스에서 `PENDING` 항목들은 **연속된 구간에 모여 있고 그 안에서 이미 `id` 순으로 정렬**되어 있다. 따라서 위 쿼리는 "PENDING 구간의 시작점으로 내려가서 앞에서 100개만 읽고 멈추는" 형태가 되고, PUBLISHED 항목을 하나씩 건너뛰며 스캔하지는 **않는다**.

그럼에도 낮은 카디널리티가 비용을 만드는 지점은 스캔 경로가 아니라 인덱스의 **크기와 유지 비용**이다.

| 구분 | 전체 인덱스 `(status, id)` | 부분 인덱스 `(id) WHERE status='PENDING'` |
| --- | --- | --- |
| 엔트리 수 | 3,000만 | 500 |
| 대략적 크기 | 1GB 내외 | 수십 KB |
| B-tree 깊이 | 4단계 내외 | 1~2단계 |
| 캐시 상주 | 버퍼 풀 상당 부분 점유 | 항상 메모리에 상주 |

트리 깊이가 2단계 늘어난다는 것 자체는 조회당 랜덤 I/O 두 번 정도의 차이라 극적이지 않다. 진짜 차이는 **99.99%가 이미 발행되어 쓸모없어진 엔트리를 위해 1GB를 계속 관리하고 캐시에 이고 있다**는 점이다.

**(4) 상태 UPDATE가 인덱스 쓰기를 증폭시킨다**

`status`를 인덱스 선두 컬럼으로 두면, `PENDING → PUBLISHED` 갱신은 값 하나 바꾸는 일이 아니다. 인덱스 상에서 행의 **위치가 바뀌는** 일이므로 기존 엔트리를 지우고 트리의 다른 위치에 새 엔트리를 삽입해야 한다. 발행 1건마다 인덱스 삽입/삭제가 따라붙는다.

PostgreSQL에서는 여기에 더해, 인덱스가 걸린 컬럼이 바뀌면 **HOT(Heap-Only Tuple) 업데이트가 불가능**해진다. 즉 새 힙 튜플을 만들고 그 테이블의 **모든 인덱스에 새 엔트리를 추가**해야 한다. 발행량이 늘수록 인덱스 팽창 속도가 빨라진다.

#### 3-2. dead tuple이 polling 쿼리 경로를 직접 오염시킨다
앞의 (3)에서 "PENDING 구간만 읽으니 스캔은 효율적"이라고 했는데, 여기에 MVCC 변수가 끼어든다.

PostgreSQL은 UPDATE를 "기존 튜플을 죽은 것으로 표시 + 새 튜플 삽입"으로 처리하고, 죽은 튜플과 그 인덱스 엔트리는 `VACUUM`이 돌아야 회수된다. 그래서 이미 PUBLISHED가 된 행의 **낡은 인덱스 엔트리가 여전히 `PENDING` 구간에 남아 있는** 상태가 만들어진다. Relay의 polling 쿼리는 그 구간을 읽으므로, 죽은 엔트리를 하나씩 방문해서 "이건 이미 죽었네" 하고 버리는 비용을 매번 지불한다.

- 발행 속도가 `autovacuum`의 회수 속도보다 빠르면 이 죽은 엔트리가 계속 누적된다
- 결과적으로 **PENDING 500건을 찾기 위해 수만~수십만 개의 죽은 엔트리를 훑는** 상황이 된다
- 큐 용도로 쓰는 테이블에서 시간이 갈수록 조회가 느려지는 대표적인 원인이며, 해당 테이블만 `autovacuum_vacuum_scale_factor`를 낮춰 더 자주 청소하도록 조정하는 것이 일반적인 대응이다

MySQL InnoDB도 구조는 다르지만 성격은 같다. 삭제된 세컨더리 인덱스 레코드는 즉시 사라지지 않고 delete-mark만 된 채 purge thread가 나중에 정리하며, 긴 트랜잭션이 살아 있으면 purge가 밀려 같은 현상이 나타난다.

#### 3-3. 테이블 팽창이 캐시를 밀어낸다
Relay가 실제로 자주 건드리는 데이터는 "최근에 들어온 PENDING 몇백 건"으로 아주 작다. 그런데 테이블과 인덱스가 수 GB로 커지면, 대량 조회·정리 작업·통계 수집 등이 오래된 PUBLISHED 페이지를 버퍼 풀로 끌어올리면서 정작 뜨거운 페이지를 밀어낸다. 그 결과 polling 쿼리가 메모리에서 끝나던 것이 디스크 I/O를 타기 시작하고, 응답 시간이 마이크로초 단위에서 밀리초 단위로 뛴다. 1초에 한 번 도는 쿼리에서는 티가 안 나지만, 지연을 줄이려고 polling 주기를 짧게 가져가는 순간 이 비용이 그대로 증폭된다.

#### 3-4. 단일 Relay의 처리량 상한
조회 → 발행 → 상태 갱신을 한 스레드가 순차로 처리한다면 처리량은 단순한 식으로 결정된다.

```
처리량(TPS) = 배치 크기 / 1사이클 소요 시간

예) 배치 100건, 사이클 = 조회 20ms + 발행 50ms + 상태갱신 30ms = 100ms
   → 100건 / 0.1s = 1,000 TPS
```

유입이 1,000 TPS를 넘는 순간 백로그는 **선형이 아니라 시간에 비례해 무한히** 쌓인다(유입 1,200 TPS면 초당 200건씩 영구 누적). 이때는 인덱스를 아무리 다듬어도 조회 시간 20ms를 줄이는 정도의 개선밖에 못 얻는다. 사이클의 절반을 차지하는 것은 브로커 왕복이므로, 배치를 키우거나 Relay를 늘려 **병렬도를 올리는 것**이 유일한 해법이다.

#### 3-5. 다중 Relay의 락 경합
그렇다고 Relay를 그냥 여러 개 띄우면, 모두 같은 쿼리(`status='PENDING' ORDER BY id LIMIT 100`)를 던지므로 **정확히 같은 100건**을 노린다.

- `FOR UPDATE`만 쓰면: 1번이 잡은 행을 2번은 **락이 풀릴 때까지 대기**한다. 인스턴스를 N개로 늘려도 실질 처리량은 1개일 때와 같고, 대기·타임아웃만 늘어난다
- 락 없이 조회 후 UPDATE로 선점하면: 둘 다 조회에 성공해 **같은 이벤트를 중복 발행**한다
- 서로 다른 순서로 여러 행을 잠그면 데드락 위험까지 생긴다

즉 "Relay를 늘렸는데 처리량이 안 늘거나 오히려 나빠졌다"는 상황의 원인은 락 경합이며, 5절의 `SKIP LOCKED`가 정확히 이 지점을 해결한다.

### 4. 조회(Polling) 최적화
- **부분 인덱스(Partial Index)**: PostgreSQL은 `CREATE INDEX ON outbox (id) WHERE status = 'PENDING'`처럼 조건부 인덱스를 만들 수 있어, 인덱스 크기 자체를 미발행 행 수준으로 유지할 수 있다. MySQL 8.0은 부분 인덱스를 직접 지원하지 않으므로 커버링 인덱스 `(status, id)`로 근접 효과를 낸다.
- **발행 완료 행 즉시 제거**: 발행에 성공하면 UPDATE로 상태만 바꾸지 말고 별도의 `outbox_archive` 테이블로 이동시키거나 DELETE 한다. Outbox 테이블은 "이력 저장소"가 아니라 "임시 큐"로 취급해 작게 유지하는 것이 가장 효과적인 최적화다.
- **커서 기반 페이징**: `WHERE id > :last_id ORDER BY id LIMIT N`은 OFFSET 방식과 달리 이전 페이지를 건너뛰기 위한 스캔 비용이 없어 페이지가 뒤로 갈수록 느려지지 않는다.
- **배치 크기 튜닝**: 한 번의 왕복(round trip)으로 가져오는 행 수를 늘리면 네트워크 왕복 비용을 줄일 수 있지만, 너무 크면 락 보유 시간과 트랜잭션 길이가 늘어난다. 처리량과 지연의 트레이드오프를 측정하며 조정한다.
- **파티셔닝**: 생성일 기준 range partition으로 나누면 오래된 파티션을 `DROP PARTITION`으로 즉시 비울 수 있어 대량 DELETE보다 훨씬 저렴하다.

### 5. 경쟁 소비(Competing Consumer)와 병렬화
- **`SELECT ... FOR UPDATE SKIP LOCKED`**: 여러 릴레이 인스턴스가 동시에 실행해도 이미 다른 트랜잭션이 잠근 행은 건너뛰고 잠기지 않은 행만 가져오므로, 대기 없이 안전하게 병렬 처리할 수 있다. PostgreSQL은 9.5부터, MySQL은 8.0부터 지원한다.

```sql
-- PostgreSQL / MySQL 8.0+ 공통 문법
SELECT id, aggregate_id, payload
FROM outbox
WHERE status = 'PENDING'
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

- **샤드 키 기반 분배**: `aggregate_id`를 해시하여 릴레이 인스턴스별 담당 파티션(또는 modulo 범위)을 고정하면, 동일 aggregate의 이벤트가 항상 같은 인스턴스에서 처리되어 순서를 보장하기 쉬워진다.
- **순서 보장과 병렬화의 트레이드오프**: 전역 순서(global ordering)를 병렬화와 함께 가져갈 수는 없다. 대부분의 도메인은 "같은 aggregate 내에서만 순서가 지켜지면 충분"하므로, 병렬화 단위를 aggregate로 좁히는 것이 현실적인 절충이다.
- **클레임(Claim) 방식**: 조회 시 `status`를 `IN_PROGRESS`로 즉시 갱신해 선점(claim)하고, `claimed_at` + lease timeout을 두어 일정 시간 내 발행이 완료되지 않으면 다시 `PENDING`으로 되돌리는 회수(reclaim) 로직을 둔다. 이는 릴레이 프로세스가 죽었을 때 이벤트가 영구히 방치되는 것을 막는다.

### 6. Polling 주기 자체를 줄이거나 없애기
- **고정 주기 polling의 한계**: 1초마다 polling한다면, 이벤트가 생성된 직후 이론적으로 최대 1초의 대기 시간이 무조건 발생한다. 즉 고정 주기는 지연의 하한선을 만든다.
- **적응형(Adaptive) 백오프**: 직전 배치에서 가져온 행이 있으면 즉시 다음 배치를 조회하고, 없으면 대기 시간을 점진적으로 늘린다(예: 10ms → 50ms → 200ms → 최대 1s). 유휴 상태의 불필요한 DB 부하를 줄이면서도 바쁠 때는 지연을 최소화한다.
- **커밋 후 즉시 깨우기(in-process signal)**: 애플리케이션 트랜잭션이 커밋된 직후, 같은 프로세스 내에서 Relay 스레드에 신호를 보내(`CountDownLatch`, 이벤트 버스 등) 다음 polling 주기를 기다리지 않고 즉시 조회를 트리거한다. 단일 인스턴스 환경에서는 효과적이지만 다중 인스턴스에서는 로컬 신호만으로는 부족하다.
- **DB 알림 기능 활용**: PostgreSQL은 `LISTEN`/`NOTIFY`로 트랜잭션 커밋 시 특정 채널에 알림을 보낼 수 있다. 트리거에서 `pg_notify('outbox_channel', NEW.id::text)`를 호출하면, 이를 구독 중인 릴레이가 polling 없이 즉시 깨어나 조회를 시작할 수 있다. 다만 NOTIFY는 세션이 끊기면 유실될 수 있어 polling을 완전히 대체하기보다 "빠른 트리거 + 안전망으로서의 저빈도 polling"을 함께 쓰는 것이 안전하다.
- **CDC(Change Data Capture)로 전환**: Debezium + Kafka Connect의 Outbox Event Router를 사용하면 DB의 트랜잭션 로그(WAL/binlog)를 직접 tailing해서 폴링 자체를 제거할 수 있다. Debezium은 outbox 테이블에 INSERT가 발생하는 즉시 로그에서 이를 감지해 Kafka로 라우팅하며, `aggregate_id`를 메시지 키로 사용해 파티션 내 순서를 보장한다.
  - Transaction Log Tailing 방식의 장점: DB에 대한 반복 SELECT 부하가 없고(로그를 읽는 것이므로), 지연이 밀리초 단위로 최소화된다.
  - 운영 비용: Kafka Connect 클러스터, Debezium 커넥터 운영/모니터링, 스키마 변경 시 커넥터 재설정 등 인프라 복잡도가 늘어난다.

### 7. 발행 단계 최적화
- **브로커 배치 발행**: Kafka Producer의 `linger.ms`, `batch.size` 설정으로 여러 메시지를 모아 한 번에 전송하면 네트워크 왕복 수를 줄여 처리량을 높일 수 있다. 비동기 전송(`producer.send()` + 콜백)으로 발행 확인을 기다리는 동안 다음 배치를 준비할 수 있다.
- **상태 업데이트 배치화**: 발행 성공한 행을 건별 UPDATE 하지 말고 `UPDATE outbox SET status = 'PUBLISHED' WHERE id IN (:ids)`처럼 IN 절로 묶어 한 번에 갱신한다.
- **발행 성공 후 상태 갱신 순서**: 브로커 발행 확인(ack) 후에 상태를 갱신해야 at-least-once가 보장된다(발행에 실패했는데 먼저 상태를 바꾸면 유실 위험). 반대로 상태 갱신에 실패하면 같은 이벤트가 중복 발행될 수 있으므로, 컨슈머 측에서 이벤트 ID 기반 멱등성(idempotency key) 처리가 필수다.
- **실패 이벤트 재시도와 DLQ 분리**: 재시도 정책(exponential backoff 등)을 두고, 특정 이벤트가 반복 실패(poison message)하면 별도의 Dead Letter Queue로 옮겨 정상 큐의 처리를 막지 않도록 한다.

### 8. 운영/정리 전략
- **보관 주기(retention) 정책**: PUBLISHED 행을 얼마나 보관할지 정하고, 삭제 시 한 번에 대량 DELETE 하지 않고 청크 단위(`LIMIT 1000` 반복)로 나눠 락 보유 시간과 replication lag을 최소화한다.
- **파티션 스위칭 / TRUNCATE**: 파티셔닝을 도입했다면 오래된 파티션을 DROP하는 것이 DELETE보다 훨씬 빠르고 락 영향이 적다.
- **모니터링 지표**: outbox backlog 크기(PENDING 행 수), oldest unpublished age(가장 오래된 미발행 이벤트의 나이), 배치당 처리 건수(throughput), 발행 실패율.
- **알람 기준**: backlog 크기 자체보다 oldest unpublished age가 SLA를 넘을 때 알람을 울리는 것이 실제 서비스 영향과 더 직결된다.

### 9. 면접 답변 시나리오 (요약 흐름)
1. **즉시 적용 가능한 조치**: 커버링/부분 인덱스 추가, 배치 크기 튜닝, 발행 완료 데이터를 아카이브 테이블로 분리해 Outbox 자체를 작게 유지
2. **구조적 조치**: `SKIP LOCKED` 기반 다중 인스턴스 병렬화, 파티셔닝, 적응형 polling(백오프)
3. **아키텍처 전환**: Debezium 기반 CDC/Log Tailing으로 polling 자체를 제거
4. 각 단계는 비용/복잡도가 커지므로, 문제의 심각도에 따라 순차적으로 도입한다고 답하는 것이 설득력 있다.

## 핵심 정리
- 핵심 포인트 1: Outbox 테이블은 "저장소"가 아니라 "큐"로 다뤄야 하며, 발행 완료 데이터를 빠르게 분리하는 것이 가장 효과적인 최적화다.
- 핵심 포인트 2: `SKIP LOCKED` 기반 경쟁 소비로 릴레이를 수평 확장하되, 순서 보장 단위를 aggregate로 좁혀야 한다.
- 핵심 포인트 3: 고정 주기 polling은 지연의 하한을 만들기 때문에, 적응형 polling이나 CDC(Debezium)로 하한 자체를 낮춰야 한다.
- 핵심 포인트 4: 지연 지표는 backlog 건수보다 "가장 오래된 미발행 이벤트의 나이(oldest unpublished age)"가 더 정확한 신호다.

## 기술적 한계와 보완 전략
- 병렬화 시 전역 순서 보장 불가 → aggregate 단위 파티셔닝 + 컨슈머 측 버전/시퀀스 검증
- at-least-once 발행으로 인한 중복 → 컨슈머 멱등성 처리, 이벤트 ID 기반 중복 제거
- CDC 도입 시 운영 복잡도와 스키마 변경 취약성 → 이벤트 payload를 JSON으로 고정하고 스키마 진화 규칙(추가만 허용 등)을 정의
- 대량 삭제로 인한 락/replication lag → 청크 삭제, 파티션 DROP으로 대체
- DB 자체가 병목일 때는 Outbox 패턴 자체가 한계 → 이벤트 발행 대상을 별도 저장소(예: 전용 큐)로 분리하거나 CDC 기반으로 완전히 전환하는 것을 검토

## 키워드
- **Transactional Outbox**: 비즈니스 데이터 변경과 이벤트 저장을 하나의 로컬 DB 트랜잭션으로 묶어 원자성을 보장하는 패턴. 별도의 Relay가 저장된 이벤트를 읽어 메시지 브로커로 발행한다.
- **Polling Publisher**: Outbox 테이블을 주기적으로 조회(polling)해서 미발행 이벤트를 찾아 발행하는 Relay 구현 방식. 구현이 단순하지만 polling 주기가 지연의 하한을 만들고 DB에 반복 부하를 준다.
- **SKIP LOCKED**: `SELECT ... FOR UPDATE SKIP LOCKED` 구문으로, 이미 다른 트랜잭션이 잠근 행은 건너뛰고 잠기지 않은 행만 즉시 반환한다. 여러 워커가 대기 없이 안전하게 큐를 나눠 처리할 수 있게 해준다(PostgreSQL 9.5+, MySQL 8.0+).
- **Change Data Capture (CDC)**: 데이터베이스의 변경 이력(트랜잭션 로그)을 실시간으로 캡처해 다른 시스템에 전파하는 기법. DB에 폴링 부하를 주지 않고 변경을 거의 즉시 감지할 수 있다.
- **Debezium**: 오픈소스 CDC 플랫폼으로, DB의 WAL(PostgreSQL)이나 binlog(MySQL)를 tailing해 변경 이벤트를 Kafka 등으로 스트리밍한다. Outbox Event Router SMT를 제공해 outbox 테이블의 INSERT를 감지하고 지정된 컬럼(aggregate_id, event_type, payload 등)을 기반으로 적절한 토픽/키로 라우팅한다.
- **Competing Consumer**: 여러 소비자(컨슈머/워커)가 동일한 큐를 두고 경쟁적으로 작업을 가져가 병렬 처리하는 패턴. 처리량을 수평으로 확장할 수 있지만 순서 보장이 어려워진다.
- **Partial Index (부분 인덱스)**: 특정 조건을 만족하는 행만 포함하는 인덱스. PostgreSQL에서 `CREATE INDEX ... WHERE status = 'PENDING'`처럼 정의하면 인덱스 크기를 실제 미발행 행 수준으로 줄일 수 있다.
- **Idempotency (멱등성)**: 동일한 연산을 여러 번 수행해도 결과가 한 번 수행한 것과 같은 성질. at-least-once 메시지 전달로 인한 중복 발행 시, 컨슈머가 이벤트 ID를 기준으로 중복을 걸러내 부작용이 반복되지 않도록 한다.
- **Table Partitioning**: 큰 테이블을 특정 기준(날짜, 해시 등)으로 여러 물리적 파티션으로 나누는 기법. 오래된 파티션을 DROP으로 즉시 제거할 수 있어 대량 DELETE보다 훨씬 저렴하게 정리할 수 있다.
- **Backlog Lag**: 처리되지 않고 쌓여 있는 이벤트의 규모나, 가장 오래된 미발행 이벤트가 생성된 후 얼마나 시간이 지났는지를 나타내는 지표. 시스템의 발행 지연 상태를 모니터링하는 핵심 신호다.

## 참고 자료
- [Microservices.io - Pattern: Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
- [Microservices.io - Pattern: Polling publisher](https://microservices.io/patterns/data/polling-publisher.html)
- [Microservices.io - Pattern: Transaction log tailing](https://microservices.io/patterns/data/transaction-log-tailing.html)
- [Debezium Documentation - Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
- [PostgreSQL Documentation - SELECT (FOR UPDATE/SKIP LOCKED)](https://www.postgresql.org/docs/current/sql-select.html)
- [PostgreSQL Documentation - LISTEN](https://www.postgresql.org/docs/current/sql-listen.html)
- [PostgreSQL Documentation - NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html)
- [MySQL 8.0 Reference Manual - Locking Reads (NOWAIT / SKIP LOCKED)](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)
