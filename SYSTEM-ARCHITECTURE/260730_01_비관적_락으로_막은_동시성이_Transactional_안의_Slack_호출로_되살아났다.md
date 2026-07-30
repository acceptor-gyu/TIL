# 비관적 락으로 막은 동시성이, @Transactional 안의 Slack 호출로 되살아났다

## 개요
- upbrella 우산 대여 서비스의 `RentService.addRental()` 사례 분석
- `PESSIMISTIC_WRITE` 락으로 우산 중복 대여(동시성)를 막았지만, 같은 `@Transactional` 안에서 Slack Webhook(RestTemplate 외부 HTTP 호출)을 실행
- "락을 짧게 잡겠다"는 의도가, 트랜잭션 커밋까지 락이 유지되는 특성 + 외부 I/O 대기로 인해 다시 무너지는 문제
- 관련 확장: 여행 숙소 예약 시스템의 overbooking 방지에 같은 안티패턴이 어떻게 재현되는지

## 상세 내용

### 1. 문제의 코드 흐름 (upbrella)
- `UmbrellaRepository.findByIdAndDeletedIsFalseForUpdate()` — `@Lock(PESSIMISTIC_WRITE)` + `javax.persistence.lock.timeout = 3000`
- `RentService.addRental()`의 실행 순서
  - (1) 락 획득: `umbrellaService.findUmbrellaByIdForRent()` → `SELECT ... FOR UPDATE`
  - (2) 상태 변경: `umbrella.rentUmbrella()` (rentable = false)
  - (3) 이력 저장: `rentRepository.save(History...)`
  - (4) 외부 I/O: `slackAlarmService.notifyRent()` → `RestTemplate.exchange(webHookUrl)`
  - (5) 조건부 신고 저장 + `notifyConditionReport()` (또 다른 Slack 호출)
- 핵심: (4)~(5)가 트랜잭션 커밋 이전에 실행 → **락 보유 구간 안에 외부 HTTP 대기 포함**

### 2. 왜 "되살아나는가" — 락 보유 시간과 트랜잭션 경계
- 비관적 락은 트랜잭션 커밋/롤백 시점까지 DB 커넥션이 해제되지 않음
- Slack Webhook 응답 지연(수백 ms ~ 타임아웃)만큼 락 보유 시간이 그대로 늘어남
- 짧게 끝났어야 할 임계 구역(critical section)이 외부 서비스 SLA에 종속됨
- 결과: 동시 대여 요청이 락 대기 큐에 쌓임 → `lock.timeout(3000ms)` 초과 시 예외 → 처리량 저하, 사용자 체감 지연
- Slack 장애 시 대여 트랜잭션 전체가 롤백(대여 자체가 실패) — 부가 알림 때문에 핵심 비즈니스가 막히는 결합 문제

### 3. 커넥션 풀 관점의 2차 피해
- HikariCP 커넥션이 외부 I/O 동안 점유됨 → 커넥션 풀 고갈(pool exhaustion)
- 락 대기 + 커넥션 대기가 겹치며 장애가 전파(cascading)

### 4. 해결 전략
- 트랜잭션 내부에서는 DB 작업만, 외부 I/O(Slack)는 트랜잭션 밖으로 분리
- `@TransactionalEventListener(phase = AFTER_COMMIT)` 로 커밋 이후 알림 발행
- 알림 비동기화: `@Async` / 메시지 큐(SQS 등)로 발행-구독 분리
- 락 구간 최소화: 상태 변경만 트랜잭션에 두고, 부가 로직은 별도 서비스 호출
- 외부 호출 타임아웃/서킷 브레이커(Resilience4j) 설정으로 결합도 완화
- 대안 동시성 제어 비교: 낙관적 락(@Version), 유니크 제약, 원자적 UPDATE (`UPDATE ... SET rentable=false WHERE id=? AND rentable=true`)

### 5. [예시] 여행 숙소 예약 시스템 overbooking 방지
- 상황: 한정된 객실(재고)에 대해 다수 사용자가 동시에 같은 날짜를 예약 → overbooking 방지 필요
- 비관적 락 적용 예
  - `SELECT ... FROM room_inventory WHERE room_id=? AND date=? FOR UPDATE` 로 해당 날짜 재고 row 잠금
  - 잔여 재고 확인 후 감소 → 이력/결제 정보 저장
- 여기서 재현되는 동일한 안티패턴
  - 같은 트랜잭션 안에서 PG(결제 게이트웨이) 승인 API, 확정 메일/SMS, Slack 운영 알림 등 외부 호출 수행
  - 결제 승인 대기(수 초) 동안 재고 row 락이 유지 → 같은 객실을 노리는 다른 요청 전부 대기/타임아웃
  - 성수기 인기 숙소일수록 락 경합이 심해져 예약 처리량 급감 (락으로 막은 overbooking이, 외부 호출 지연으로 사실상 처리 불능 상태를 유발)
- 권장 설계
  - 재고 차감은 짧은 트랜잭션으로 원자적 처리(락 or 조건부 UPDATE)하고 즉시 커밋
  - 결제 승인은 별도 단계로 분리 → 실패 시 재고 복원(보상 트랜잭션/Saga)
  - "임시 예약(hold) → 결제 → 확정" 상태 머신으로 재고 점유 시간 최소화
  - 알림/후처리는 커밋 이후 이벤트 or 큐로 비동기 처리
  - 분산 환경이면 DB 비관적 락 대신 Redis 분산 락 + TTL 고려

## 핵심 정리
- 비관적 락은 트랜잭션 커밋까지 유지되므로, 트랜잭션 안의 외부 I/O 시간만큼 락도 길어진다
- 임계 구역 안에 외부 서비스(Slack, PG) 호출을 넣으면, 동시성 방어가 외부 SLA에 종속되어 무력화된다
- 외부 I/O는 커밋 이후(AFTER_COMMIT) 또는 비동기로 분리하고, 트랜잭션은 DB 작업만 짧게 유지한다
- overbooking 방지도 동일 원칙: 재고 차감은 짧고 원자적으로, 결제/알림은 트랜잭션 밖으로

## 기술적 한계와 보완 전략
- AFTER_COMMIT 분리 시 알림 유실 가능성 → 아웃박스(Outbox) 패턴 + 재발행으로 보완
- 비동기화 시 실패 추적이 어려움 → 재시도/DLQ, 멱등성 키 설계 필요
- 락 구간 축소 vs 정합성: 결제 실패 시 재고 복원(보상 트랜잭션) 로직의 복잡도 증가
- 분산 락 도입 시 락 획득 실패/TTL 만료로 인한 이중 처리 방어(펜싱 토큰) 고려

## 키워드

**비관적 락(PESSIMISTIC_WRITE)**
- JPA `LockModeType.PESSIMISTIC_WRITE`는 `SELECT ... FOR UPDATE`를 발행해 다른 트랜잭션의 읽기/쓰기를 차단하는 배타 락(exclusive lock)
- `javax.persistence.lock.timeout` 힌트로 락 대기 시간을 설정할 수 있으며, 초과 시 `PessimisticLockException` 발생

**락 보유 시간(Lock Duration)**
- 비관적 락은 획득 시점부터 트랜잭션이 커밋/롤백될 때까지 계속 유지됨
- 트랜잭션 안에서 무엇을 하든(DB 작업이든 외부 호출이든) 그 시간만큼 락 보유 시간이 늘어남

**@Transactional 경계**
- Spring의 선언적 트랜잭션은 프록시가 메서드 진입 시 커넥션을 열고, 메서드가 정상 종료되면 커밋, 예외 발생 시 롤백
- 트랜잭션 경계 안에 있는 모든 코드(외부 API 호출 포함)가 하나의 물리 트랜잭션/커넥션에 묶임

**AFTER_COMMIT 이벤트**
- `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`는 기본값이 `AFTER_COMMIT`이며, 트랜잭션이 성공적으로 커밋된 이후에만 리스너가 실행됨
- 트랜잭션이 없는 상태에서 이벤트가 발행되면 `fallbackExecution()`을 명시하지 않는 한 이벤트는 무시됨

**외부 I/O 분리**
- DB 트랜잭션의 임계 구역에는 순수 DB 연산만 남기고, Slack/PG/메일 등 외부 네트워크 호출은 트랜잭션 밖(커밋 이후 또는 별도 스레드/큐)으로 빼내는 설계 원칙

**커넥션 풀 고갈(Connection Pool Exhaustion)**
- 트랜잭션이 길어질수록 HikariCP 등 커넥션 풀에서 커넥션이 반환되지 않는 시간이 길어짐
- 동시 요청이 몰리면 풀의 모든 커넥션이 대기 중인 트랜잭션에 점유되어, 이후 요청은 커넥션 획득 자체에서 타임아웃 발생

**overbooking 방지**
- 한정된 재고(객실, 좌석, 티켓 등)에 대해 동시 요청이 재고 수량을 초과해 예약/판매되는 현상을 막는 것
- 비관적 락, 낙관적 락, 조건부 원자적 UPDATE, DB 유니크 제약 등으로 구현

**재고 차감 원자성**
- `UPDATE inventory SET stock = stock - 1 WHERE id = ? AND stock > 0` 처럼 조건과 갱신을 하나의 원자적 연산으로 묶어 레이스 컨디션을 방지하는 패턴
- 명시적 락 없이도 DB의 원자적 실행 보장을 이용해 동시성을 제어할 수 있음

**보상 트랜잭션(Saga)**
- 여러 서비스/단계에 걸친 작업을 하나의 ACID 트랜잭션으로 묶을 수 없을 때, 각 단계 실패 시 이전 단계를 취소하는 보상(compensating) 작업을 실행해 최종적 일관성을 맞추는 패턴
- 예: 결제 실패 시 차감했던 재고를 다시 복원하는 보상 로직

**서킷 브레이커(Circuit Breaker)**
- Resilience4j 등에서 제공하는 패턴으로, 외부 서비스 호출 실패율이 임계치를 넘으면 일정 시간 동안 호출을 즉시 차단(open)해 장애 전파를 막고, 이후 반개방(half-open) 상태로 점진적으로 복구를 시도

## 참고 자료
- [Spring Framework - Transaction-bound Events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [Spring Framework - TransactionalEventListener Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html)
- [Jakarta Persistence - Optimistic and Pessimistic Locking](https://www.objectdb.com/java/jpa/persistence/lock)
- [Baeldung - Pessimistic Locking in JPA](https://www.baeldung.com/jpa-pessimistic-locking)
- [HikariCP - About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [Resilience4j - Circuit Breaker](https://resilience4j.readme.io/docs/circuitbreaker)
- [Microsoft Azure Architecture Center - Saga Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/saga)
