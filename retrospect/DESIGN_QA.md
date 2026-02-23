# 설계 리뷰 Q&A — AI Native Senior Engineer 관점

> 이 문서는 수강신청 시스템의 핵심 설계 결정에 대해 Senior Engineer 관점에서 제기할 수 있는 질문과 그에 대한 답변을 정리합니다.
> 각 항목은 트레이드오프를 의식적으로 선택했음을 기록합니다.

---

## 목차

1. [동시성 정합성의 알려진 허점](#1-동시성-정합성의-알려진-허점)
2. [H2 인메모리 DB의 운영 적합성](#2-h2-인메모리-db의-운영-적합성)
3. [스케일아웃 시 동시성 모델의 한계](#3-스케일아웃-시-동시성-모델의-한계)
4. [보상 트랜잭션의 실패 시나리오](#4-보상-트랜잭션의-실패-시나리오)
5. [시간표 충돌 검증의 정밀도](#5-시간표-충돌-검증의-정밀도)
6. [API 설계의 관례적 질문](#6-api-설계의-관례적-질문)
7. [초기 데이터 전략의 운영 리스크](#7-초기-데이터-전략의-운영-리스크)
8. [모니터링과 관측 가능성](#8-모니터링과-관측-가능성)
9. [테스트 커버리지 공백](#9-테스트-커버리지-공백)
10. [도메인 모델의 빈약함](#10-도메인-모델의-빈약함)

---

## 1. 동시성 정합성의 알려진 허점

<details>
<summary><strong>Q. 학점 상한(18학점)과 시간표 충돌 검증이 Snapshot Read 기반인데, 이 Race Condition을 "알려진 허점"으로 남겨두는 것이 의도인가?</strong></summary>

### 문제 상황

같은 학생이 밀리초 단위로 두 강좌를 동시 신청하면, 두 트랜잭션이 모두 학점 검증을 통과하여 18학점을 초과하거나 시간표가 겹치는 수강이 성립할 수 있다.

```
트랜잭션 A (강좌X, 3학점 신청)          트랜잭션 B (강좌Y, 3학점 신청)
────────────────────────────────        ────────────────────────────────
현재 이수 학점 조회 → 16학점             현재 이수 학점 조회 → 16학점  (같은 Snapshot)
16 + 3 = 19? NO → 검증 통과             16 + 3 = 19? NO → 검증 통과
강좌X 좌석 감소 → 성공                  강좌Y 좌석 감소 → 성공
Enrollment INSERT → 성공                Enrollment INSERT → 성공
결과: 학생이 총 22학점 보유 (정합성 깨짐)
```

### 의도적 트레이드오프

**이 허점은 현재 설계에서 의도적으로 수용된 트레이드오프다.**

- 수강신청 시스템에서 학생 한 명이 동시에 두 개의 탭/클라이언트에서 수강신청하는 시나리오 자체가 매우 드물다.
- 좌석 정원 초과(강좌 단위 불변식)가 핵심 요구사항이며, 학점 한도 초과는 상대적으로 발생 빈도와 비즈니스 임팩트가 낮다.
- 정합성을 완전히 보장하려면 `SELECT ... FOR UPDATE`로 해당 학생의 Enrollment 행 전체를 잠궈야 하는데, 이는 동일 학생의 모든 수강신청을 직렬화하여 처리량을 크게 줄인다.

### 개선 방안 (필요 시)

```sql
-- 학생 단위 직렬화: 해당 학생의 기존 Enrollment에 X-Lock 획득
SELECT e.* FROM enrollments e WHERE e.student_id = :studentId FOR UPDATE;
```

- 또는 Redis 분산 락으로 `student:{studentId}` 키에 Lock을 걸어 애플리케이션 레벨에서 직렬화
- 운영 중 실제로 학점 초과가 탐지되면 그 시점에 도입을 검토한다

### 현재 최종 방어선

DB UNIQUE 제약 `(student_id, course_id)`이 동일 강좌 중복 신청은 100% 방지한다. 서로 다른 강좌에 대한 학점 초과만 이론적으로 가능하다.

</details>

---

## 2. H2 인메모리 DB의 운영 적합성

<details>
<summary><strong>Q. H2 인메모리 + MODE=MySQL로 동시성 테스트를 검증하는데, 실제 MySQL/PostgreSQL로 전환했을 때 동일한 동작이 보장되는가?</strong></summary>

### H2와 InnoDB의 차이

| 항목 | H2 (MODE=MySQL) | MySQL InnoDB |
|------|-----------------|-------------|
| Gap Lock | 미지원 | REPEATABLE_READ에서 지원 |
| Next-Key Lock | 미지원 | 팬텀 리드 방지에 사용 |
| MVCC 구현 | JVM 메모리 기반 | Undo Log 기반 |
| 데드락 감지 | 단순 감지 | 정교한 대기 그래프 분석 |

### 현재 설계에서 H2를 사용하는 이유

- **목적**: 이 프로젝트는 운영 배포가 아닌 **동시성 제어 메커니즘 설계 검증**이 목적이다.
- **격리 수준**: `READ_COMMITTED` + 원자적 UPDATE 패턴은 H2와 InnoDB 모두 동일하게 동작한다.
- **핵심 메커니즘**: `UPDATE ... WHERE seatsLeft > 0`의 원자성은 모든 RDBMS에서 Row-Level Lock으로 보장된다.

### 운영 전환 시 추가 검토 항목

1. **Flyway/Liquibase 도입**: 현재 `DDL: create` 방식을 마이그레이션 기반으로 전환
2. **실 DB 동시성 테스트**: Testcontainers(MySQL/PostgreSQL)로 동일 시나리오 재검증
3. **데드락 모니터링**: InnoDB의 `SHOW ENGINE INNODB STATUS`로 Lock 경합 패턴 확인

```yaml
# 운영 전환 예시
spring:
  datasource:
    url: jdbc:mysql://prod-host:3306/enrollment
  jpa:
    database-platform: org.hibernate.dialect.MySQL8Dialect
    hibernate:
      ddl-auto: validate  # create → validate 로 변경 필수
```

### 결론

H2는 개발·테스트 편의를 위한 선택이며, 핵심 동시성 로직(원자적 UPDATE + UNIQUE 제약)은 표준 SQL이라 실 DB에서도 동일하게 동작한다. 단, 운영 전환 전 Testcontainers 기반 통합 테스트 추가가 권장된다.

</details>

---

## 3. 스케일아웃 시 동시성 모델의 한계

<details>
<summary><strong>Q. 현재 동시성 제어가 단일 DB Row Lock에 의존하는데, 수평 확장 시나리오를 고려했는가?</strong></summary>

### 병목 지점 분석

인기 강좌(예: 정원 100명)에 1,000명이 동시 신청하면 다음과 같은 직렬화가 발생한다.

```
[요청 1]  UPDATE courses SET seatsLeft = seatsLeft - 1 WHERE id = 42 AND seatsLeft > 0
  ↑ X-Lock 획득
[요청 2~1000] Lock 대기 큐에서 순서대로 대기
  ↑ 각 요청이 평균 N ms × 대기 순번 만큼 지연
```

### 현재 설계에서의 트레이드오프

- **장점**: 구현이 단순하고 DB가 직렬화를 보장하므로 별도의 분산 락 인프라가 불필요하다.
- **단점**: 극단적 피크(수만 명 동시 접속)에서 Lock 대기 시간이 타임아웃(예: 30초)을 초과할 수 있다.

### 스케일아웃 시 대안 전략

#### Option 1: Redis 분산 락 + 카운터

```
Redis: course:{courseId}:seats = 잔여 좌석 수 (원자적 DECR)
→ Redis DECR 성공 → DB INSERT
→ Redis DECR 실패(0 이하) → CAPACITY_FULL 반환
```
- 장점: DB Lock 없이 고처리량 달성
- 단점: Redis와 DB 간 정합성 유지 복잡도 증가

#### Option 2: 큐 기반 처리 (Kafka/Redis Queue)

```
수강신청 요청 → Queue 적재 → Consumer가 순서대로 처리
→ 초당 처리량을 Consumer 수로 조절 (백프레셔)
```
- 장점: 피크 트래픽 흡수, 처리량 제어 가능
- 단점: 응답이 비동기가 되어 UX 복잡도 증가

#### Option 3: 현재 설계 유지 + DB 커넥션 풀 튜닝

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      connection-timeout: 30000
      lock-timeout: 5000  # MySQL innodb_lock_wait_timeout
```

### 현재 프로젝트 범위에서의 결론

이 프로젝트는 **단일 서버 + H2 기반 설계 검증**이 목적이므로, 현재 DB Lock 방식으로 충분하다. 운영 수준의 스케일아웃이 요구되면 Redis DECR 방식을 1차로 검토한다.

</details>

---

## 4. 보상 트랜잭션의 실패 시나리오

<details>
<summary><strong>Q. recoverSeat()가 REQUIRES_NEW로 실행되는데, 이 보상 트랜잭션 자체가 실패하면 좌석이 영구적으로 감소한 채 남지 않는가?</strong></summary>

### 보상 트랜잭션 흐름

```
[6] decreaseSeatIfAvailable → 성공 (seatsLeft - 1)
[7] Enrollment INSERT → DataIntegrityViolationException (UNIQUE 위반)
    └─ 현재 트랜잭션 세션 오염 (EntityManager 무효화)
[8] recoverSeat() [REQUIRES_NEW] → 별도 트랜잭션에서 seatsLeft + 1
```

### 실패 가능 시나리오

```java
// EnrollmentService.java - recoverSeat
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recoverSeat(Long courseId) {
    courseRepository.increaseSeat(courseId);
    // 만약 여기서 커넥션 고갈, 타임아웃 등으로 실패하면?
    // → seatsLeft가 감소된 채로 남음 (좌석 누락)
}
```

### 현재 설계의 리스크

| 실패 원인 | 발생 가능성 | 영향 |
|----------|------------|------|
| DB 커넥션 풀 고갈 | 극단적 피크 시 낮음 | 좌석 누락 |
| 네트워크 순단 | 매우 낮음 | 좌석 누락 |
| DB 인스턴스 재시작 | 거의 없음 | 좌석 누락 |

### 실제로 이 경로가 발생하는 경우

`recoverSeat()`이 호출되는 경우는 **좌석 감소 성공 후 UNIQUE 위반** 이다. 즉:
1. 동시 요청 중 한 명이 이미 Enrollment INSERT에 성공한 상태
2. 나머지 요청이 좌석만 감소하고 INSERT 실패

이 경우 `recoverSeat()` 실패 시 해당 강좌의 실제 신청자 수와 `seatsLeft` 값이 불일치하게 된다.

### 개선 방안

#### 단기: 정합성 감사 스케줄러

```java
@Scheduled(cron = "0 0 * * * *")  // 매 시간
public void auditSeats() {
    // 실제 Enrollment COUNT와 seatsLeft 비교
    // 불일치 발견 시 경고 로그 + 자동 보정
    List<Course> courses = courseRepository.findAll();
    for (Course course : courses) {
        long actualEnrolled = enrollmentRepository.countByCourseId(course.getId());
        int expectedSeatsLeft = course.getCapacity() - (int) actualEnrolled;
        if (expectedSeatsLeft != course.getSeatsLeft()) {
            log.warn("좌석 불일치 감지: courseId={}, seatsLeft={}, expected={}",
                course.getId(), course.getSeatsLeft(), expectedSeatsLeft);
            courseRepository.correctSeatsLeft(course.getId(), expectedSeatsLeft);
        }
    }
}
```

#### 장기: 아웃박스 패턴 또는 Saga

보상 트랜잭션을 DB 트랜잭션 내 아웃박스 테이블에 기록하고, 별도 프로세스가 안정적으로 재시도하도록 설계.

### 현재 프로젝트 범위에서의 결론

이 시나리오의 발생 확률은 매우 낮고(커넥션 고갈과 UNIQUE 위반이 동시에 발생해야 함), 현재 프로젝트 범위에서는 감사 스케줄러를 추가하는 것이 적절한 수준의 방어책이다.

</details>

---

## 5. 시간표 충돌 검증의 정밀도

<details>
<summary><strong>Q. schedule 필드가 단순 문자열 비교("월 09:00-10:30")인데, 부분 겹침을 감지할 수 있는가?</strong></summary>

### 현재 구현의 한계

```java
// EnrollmentService.java - 시간표 충돌 검증
boolean hasConflict = enrolledCourses.stream()
    .anyMatch(enrolledCourse ->
        enrolledCourse.getSchedule().equals(course.getSchedule())
    );
```

**문자열 동등 비교**만 하므로 다음 케이스를 감지하지 못한다.

| 기신청 강좌 | 신청 강좌 | 실제 충돌? | 감지 여부 |
|------------|---------|----------|---------|
| `"월 09:00-10:30"` | `"월 09:00-10:30"` | 완전 일치 | ✅ 감지 |
| `"월 09:00-10:30"` | `"월 10:00-11:30"` | 1시간 겹침 | ❌ 미감지 |
| `"월 09:00-10:30"` | `"화 09:00-10:30"` | 다른 요일 | ✅ 올바르게 통과 |
| `"월,수 09:00-10:30"` | `"수 09:00-10:30"` | 수요일 겹침 | ❌ 미감지 |

### 올바른 구현 방향

#### schedule 형식 파싱 및 시간 범위 겹침 검사

```java
// 예시: "월,수 09:00-10:30" 파싱
public record ScheduleSlot(Set<DayOfWeek> days, LocalTime start, LocalTime end) {

    public static ScheduleSlot parse(String schedule) {
        // "월,수 09:00-10:30" → days={MON, WED}, start=09:00, end=10:30
        String[] parts = schedule.split(" ");
        Set<DayOfWeek> days = parseDays(parts[0]);      // "월,수"
        String[] times = parts[1].split("-");
        LocalTime start = LocalTime.parse(times[0]);
        LocalTime end = LocalTime.parse(times[1]);
        return new ScheduleSlot(days, start, end);
    }

    public boolean overlapsWith(ScheduleSlot other) {
        // 요일 교집합이 있고, 시간 범위가 겹치면 충돌
        boolean dayOverlap = !Collections.disjoint(this.days, other.days);
        boolean timeOverlap = this.start.isBefore(other.end)
                           && other.start.isBefore(this.end);
        return dayOverlap && timeOverlap;
    }
}
```

### 현재 설계에서 단순 문자열 비교를 사용하는 이유

- 현재 `DataInitializer`가 생성하는 강좌의 schedule 값은 **완전히 동일한 문자열인 경우에만 충돌**하도록 데이터가 구성되어 있다.
- 즉, 데이터 생성 규칙과 검증 로직이 암묵적으로 맞춰져 있어 현재 범위 내에서는 정상 동작한다.
- 실제 운영 환경에서는 부분 겹침이 발생하므로 **반드시 개선이 필요하다.**

### 개선 우선순위

이 항목은 데이터 정합성에 직접 영향을 미치므로 **높은 우선순위**로 개선이 필요하다.

</details>

---

## 6. API 설계의 관례적 질문

<details>
<summary><strong>Q. 수강취소가 DELETE /api/enrollments + Request Body인데, HTTP DELETE 메서드에 Body를 포함하는 것이 RESTful 관례에 맞는가?</strong></summary>

### 현재 설계

```java
// EnrollmentController.java
@DeleteMapping
public ResponseEntity<EnrollmentCancelResponse> cancel(
    @Valid @RequestBody EnrollmentCancelRequest request
) {
    // request: { studentId, courseId }
}
```

### 문제점

- HTTP 명세(RFC 7231)는 DELETE에 Body를 **허용하지만 의미 없음(no defined semantics)** 으로 명시
- 일부 프록시, 로드밸런서, 방화벽이 DELETE Body를 **무시하거나 제거**할 수 있음
- Swagger/OpenAPI 문서화 시 일부 도구가 DELETE Body를 제대로 표현하지 못함
- 클라이언트 라이브러리(Axios, Fetch 등)가 DELETE + Body 조합을 기본으로 지원하지 않는 경우 있음

### 대안 비교

| 방식 | 예시 | 장점 | 단점 |
|------|------|------|------|
| **현재: DELETE + Body** | `DELETE /api/enrollments` + `{studentId, courseId}` | 단일 엔드포인트 | 프록시 호환성 위험 |
| **Path Variable** | `DELETE /api/enrollments/{studentId}/{courseId}` | RESTful 표준, 캐시 가능 | URL이 길어짐 |
| **Query Parameter** | `DELETE /api/enrollments?studentId=1&courseId=42` | 단순 | 민감 정보 노출 가능 (로그) |
| **복합 리소스 ID** | `DELETE /api/enrollments/{enrollmentId}` | 가장 RESTful | Enrollment ID를 클라이언트가 알아야 함 |

### 권장 방향

```
DELETE /api/enrollments/{studentId}/{courseId}
```

또는 수강신청 시 Enrollment ID를 응답에 포함하고:

```
DELETE /api/enrollments/{enrollmentId}
```

### 현재 설계 유지 이유

- 현재 `Enrollment` 엔티티 ID를 클라이언트에 노출하지 않는 설계 방향을 유지하기 위해 Body 방식 선택
- 프록시 없이 직접 연결하는 환경에서는 실질적 문제가 없음
- 인터뷰/과제 범위에서는 동작 정확성이 우선이며, API 관례는 운영 전환 시 개선 예정

</details>

---

## 7. 초기 데이터 전략의 운영 리스크

<details>
<summary><strong>Q. DataInitializer가 매 시작 시 10,000명+ 학생과 500개+ 강좌를 INSERT하는데, 운영 환경에서도 이 패턴을 사용할 것인가?</strong></summary>

### 현재 구현

```java
// DataInitializer.java
@Component
public class DataInitializer {

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        // 15개 학과, 100명+ 교수, 500개+ 강좌, 10,000명+ 학생 생성
        // SplittableRandom(20240215L) 고정 시드 → 결정론적 데이터
    }
}
```

### 운영 환경 리스크

1. **프로파일 분리 없음**: `@Profile("local")` 또는 `@Profile("!prod")` 없이 항상 실행됨
2. **멱등성 미보장**: `DDL: create`로 매번 스키마를 재생성하므로 현재는 문제없지만, `DDL: validate`로 전환 시 데이터 중복 INSERT 발생
3. **시작 시간**: 10,000건+ INSERT로 애플리케이션 시작이 수 초 지연됨

### 현재 설계의 의도

- `DDL: create` + `DataInitializer` 조합은 **개발·검증 환경 전용** 설계
- 고정 시드(`SplittableRandom(20240215L)`)로 재현 가능한 데이터를 생성하여 **테스트 결정론성** 확보
- `DataInitializationStatus`로 초기화 완료 여부를 Health 엔드포인트에 노출하여 준비 상태 확인 가능

### 운영 전환 시 개선 방향

```java
// 운영 환경 분리
@Component
@Profile({"local", "test"})  // 운영 환경(prod)에서는 실행 안 함
public class DataInitializer { ... }
```

```yaml
# 운영 application-prod.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate  # create → validate
  flyway:
    enabled: true         # Flyway로 마이그레이션 관리
```

### 결론

현재 DataInitializer는 **개발/검증 목적으로만 사용**하며, 운영 배포 전 프로파일 분리와 Flyway 도입이 필수다.

</details>

---

## 8. 모니터링과 관측 가능성

<details>
<summary><strong>Q. 수강신청 시스템의 핵심 메트릭(좌석 소진 속도, Lock 대기 시간, 실패율)을 어떻게 관측할 계획인가?</strong></summary>

### 현재 관측 가능성 수준

- `/health` 엔드포인트: 초기화 상태(`initialized: true/false`)만 노출
- 로깅: 예외 발생 시 `GlobalExceptionHandler`에서 로그 출력
- 메트릭: **별도 수집 없음**

### 운영에서 필요한 핵심 메트릭

| 메트릭 | 설명 | 알림 기준 |
|--------|------|----------|
| `enrollment.request.count` | 초당 수강신청 요청 수 | 임계치 초과 시 |
| `enrollment.success.rate` | 성공률 | 80% 미만 시 |
| `enrollment.capacity_full.count` | 정원 초과 거절 수 | 급증 시 |
| `enrollment.lock_wait.duration` | DB Lock 대기 시간 | 1초 초과 시 |
| `course.seats_left` | 강좌별 잔여 좌석 | 0 도달 시 |

### 개선 방향

#### Spring Actuator + Micrometer

```java
// EnrollmentService.java에 메트릭 추가 예시
@Autowired
private MeterRegistry meterRegistry;

public EnrollmentResponse enroll(EnrollmentRequest request) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        // ... 기존 로직 ...
        meterRegistry.counter("enrollment.success").increment();
        return response;
    } catch (CustomException e) {
        meterRegistry.counter("enrollment.failure",
            "reason", e.getErrorCode().name()).increment();
        throw e;
    } finally {
        sample.stop(meterRegistry.timer("enrollment.duration"));
    }
}
```

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

### 현재 프로젝트 범위에서의 결론

이 프로젝트는 동시성 제어 메커니즘 검증이 목적이므로 Actuator 기본 엔드포인트 수준으로 충분하다. 운영 배포 시 Prometheus + Grafana 연동이 권장된다.

</details>

---

## 9. 테스트 커버리지 공백

<details>
<summary><strong>Q. 수강취소의 동시성 테스트가 없는데, 같은 수강건을 동시에 취소하면 좌석이 이중 복구되지 않는가?</strong></summary>

### 문제 시나리오

```
학생 A가 강좌X를 수강신청한 상태

요청 1: DELETE /api/enrollments (studentId=A, courseId=X)
요청 2: DELETE /api/enrollments (studentId=A, courseId=X)  ← 동시 요청

──────────────────────────────────────────
[요청 1] deleteByStudentIdAndCourseId → 1건 삭제 성공
[요청 2] deleteByStudentIdAndCourseId → 0건 삭제 (이미 없음) → ENROLLMENT_NOT_FOUND
[요청 1] increaseSeat → seatsLeft + 1 ← 정상
[요청 2] 예외로 반환 → increaseSeat 미실행

결과: 좌석 1회만 증가 → 정합성 유지 ✅
```

### 왜 현재 설계가 안전한가

`deleteByStudentIdAndCourseId`의 반환값(삭제 건수)을 활용한 조기 실패가 핵심이다.

```java
// EnrollmentService.java - cancel
int deletedCount = enrollmentRepository.deleteByStudentIdAndCourseId(
    request.getStudentId(), request.getCourseId()
);

if (deletedCount == 0) {
    throw new CustomException(ErrorCode.ENROLLMENT_NOT_FOUND);
}
// deletedCount == 1인 트랜잭션만 increaseSeat 실행
courseRepository.increaseSeat(request.getCourseId());
```

DB의 `DELETE` 연산은 Row 삭제 시 X-Lock을 획득하므로, 동시 요청 중 **정확히 하나**만 1을 반환한다. 나머지는 0을 반환하여 `ENROLLMENT_NOT_FOUND`로 처리된다.

### 그럼에도 테스트가 필요한 이유

이 동작이 설계적으로 안전하더라도, **동시 취소 시나리오의 명시적 테스트가 없으면 이후 코드 변경 시 회귀를 감지할 수 없다.**

### 추가해야 할 테스트

```java
@Test
@DisplayName("같은 수강신청을 동시에 취소하면 한 번만 성공하고 좌석이 정확히 1회 복구된다")
void concurrentCancelTest() throws InterruptedException {
    // Given: 수강신청 1건 생성, 초기 seatsLeft 기록
    int initialSeatsLeft = course.getSeatsLeft();

    // When: 동시 취소 2건 실행
    CountDownLatch latch = new CountDownLatch(1);
    List<CompletableFuture<Integer>> futures = IntStream.range(0, 2)
        .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            latch.await();
            return cancelRequest(studentId, courseId);  // HTTP status 반환
        })).toList();
    latch.countDown();

    // Then: 성공 1건, 실패(404) 1건
    long successCount = futures.stream()
        .map(CompletableFuture::join)
        .filter(status -> status == 200).count();
    assertThat(successCount).isEqualTo(1);
    assertThat(courseRepository.findById(courseId).get().getSeatsLeft())
        .isEqualTo(initialSeatsLeft + 1);  // 정확히 1회 복구
}
```

### 결론

현재 구현은 설계상 안전하지만, 명시적 동시 취소 테스트 추가가 필요하다.

</details>

---

## 10. 도메인 모델의 빈약함

<details>
<summary><strong>Q. Entity가 JPA 연관관계 없이 순수 FK 컬럼만 사용하는데, 이것이 의도적인 경량화인가, 아니면 N+1 문제를 야기하는 트레이드오프인가?</strong></summary>

### 현재 설계

```java
// Student.java
@Column(name = "department_id")
private Long departmentId;  // JPA 연관관계(@ManyToOne) 없이 FK만 보관

// Enrollment.java
private Long studentId;  // @ManyToOne Student 없음
private Long courseId;   // @ManyToOne Course 없음
```

### 의도적 경량화의 장점

1. **즉시 로딩(EAGER) 폭발 방지**: 연관관계가 없으면 예상치 못한 EAGER 로딩으로 인한 대량 JOIN 쿼리가 발생하지 않는다.
2. **순환 참조 방지**: `Student → Department → Student` 같은 순환 직렬화 문제가 없다.
3. **트랜잭션 경계 명확화**: 연관 엔티티의 Lazy 초기화 실패(`LazyInitializationException`)를 원천 차단한다.
4. **마이크로서비스 분리 용이**: 도메인 간 의존성이 ID 참조만으로 제한되어 추후 분리 시 유리하다.

### 트레이드오프: 추가 쿼리 발생

```java
// StudentTimetableService.java - 현재 2단계 쿼리
List<Enrollment> enrollments = enrollmentRepository.findByStudentId(studentId);
List<Long> courseIds = enrollments.stream().map(Enrollment::getCourseId).toList();
List<Course> courses = courseRepository.findAllById(courseIds);  // IN 쿼리 1회
```

`findAllById`는 `WHERE id IN (...)` 단일 쿼리를 실행하므로 **N+1이 아닌 2-query 패턴**이다. 수강 강좌 수가 수백 개를 넘지 않는 한 성능상 문제없다.

### 연관관계 적용 시와 비교

| 항목 | 현재 (FK 컬럼만) | JPA 연관관계 |
|------|----------------|------------|
| 코드 복잡도 | 낮음 | 높음 (fetch 전략 관리) |
| N+1 위험 | 없음 | LAZY 설정 실수 시 발생 |
| JOIN 쿼리 | 수동 관리 | JPQL/QueryDSL로 명시 |
| 도메인 표현력 | 낮음 (ID만 보임) | 높음 (객체 그래프 탐색) |

### Department 존재 검증 중복 쿼리 문제

```java
// StudentService.java
departmentRepository.findById(departmentId)
    .orElseThrow(() -> new CustomException(ErrorCode.DEPARTMENT_NOT_FOUND));
// → 이후 실제 Student 조회 쿼리까지 총 2회 쿼리
```

이는 연관관계 부재로 인한 필연적 비용이며, 쿼리 횟수보다 **명확한 오류 메시지 제공**의 가치가 더 크다.

### 결론

FK 컬럼 방식은 현재 시스템 규모와 요구사항에 적합한 의도적 선택이다. 도메인 복잡도가 증가하면 QueryDSL + DTO Projection 방식으로 전환을 검토한다.

</details>
