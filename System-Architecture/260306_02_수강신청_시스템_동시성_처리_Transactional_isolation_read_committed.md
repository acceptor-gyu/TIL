# 수강신청 시스템에서 동시성 처리를 하는 Service Layer의 @Transactional isolation 레벨을 read committed로 설정해도 될까

## 개요
수강신청 시스템처럼 동시성이 중요한 서비스에서 `@Transactional(isolation = Isolation.READ_COMMITTED)`를 사용하는 것이 적절한지 분석한다.

## 상세 내용

### 1. 수강신청 시스템의 동시성 특성

수강신청 시스템은 대표적인 고동시성(High Concurrency) 시나리오다. 특정 시간에 수백~수천 명의 사용자가 동시에 같은 강좌에 신청을 시도하며, 가장 핵심적인 요구사항은 **정원 초과를 절대로 허용하지 않는 것**이다.

이 도메인에서 발생할 수 있는 주요 동시성 문제는 다음과 같다.

**Check-Then-Act 패턴의 위험성**

가장 흔한 구현 방식은 잔여석 확인 후 등록하는 패턴이다.

```java
@Transactional
public void registerCourse(Long courseId, Long studentId) {
    Course course = courseRepository.findById(courseId).orElseThrow();

    // 1. 잔여석 확인 (Check)
    if (course.getCurrentEnrollment() >= course.getMaxCapacity()) {
        throw new CourseFullException("정원이 초과되었습니다.");
    }

    // 2. 수강 등록 (Act)
    // ← 이 사이에 다른 트랜잭션이 개입할 수 있다
    enrollmentRepository.save(new Enrollment(courseId, studentId));
    course.incrementEnrollment();
    courseRepository.save(course);
}
```

이 패턴에서 "Check"와 "Act" 사이에 다른 트랜잭션이 끼어들면 두 트랜잭션 모두 잔여석이 있다고 판단하고 동시에 등록을 완료할 수 있다. 이것이 **Race Condition**이다.

**Lost Update**

두 트랜잭션이 동시에 같은 강좌 레코드를 읽어 현재 수강 인원을 `currentEnrollment = 29`로 확인하고, 각각 `currentEnrollment = 30`으로 업데이트하면 실제로는 31명이 등록되었지만 DB에는 30명으로 기록된다. 두 번의 업데이트 중 하나가 덮어씌워진 것이다.

---

### 2. READ COMMITTED의 동작 방식

`READ COMMITTED`는 SQL 표준에서 정의한 4가지 격리 수준 중 두 번째 단계다. 핵심 원칙은 "커밋된 데이터만 읽는다"이다.

**MVCC 기반 스냅샷 읽기**

InnoDB에서 `READ COMMITTED`는 **매 SELECT마다 새로운 스냅샷을 생성**한다. 이는 `REPEATABLE READ`가 트랜잭션의 첫 번째 읽기 시점에 스냅샷을 고정하는 것과 대조적이다.

```
READ COMMITTED:
T1: START → SELECT (스냅샷 생성) → ... → SELECT (새 스냅샷 생성) → COMMIT
                   ↑                            ↑
               커밋된 최신 데이터          커밋된 최신 데이터 (달라질 수 있음)

REPEATABLE READ:
T1: START → SELECT (스냅샷 고정) → ... → SELECT (같은 스냅샷) → COMMIT
                   ↑                            ↑
               시점 A의 데이터              시점 A의 데이터 (동일 보장)
```

**허용/방지하는 이상 현상**

| 이상 현상 | READ COMMITTED | REPEATABLE READ |
|-----------|:--------------:|:---------------:|
| Dirty Read | 방지 | 방지 |
| Non-Repeatable Read | **발생** | 방지 |
| Phantom Read | **발생** | 부분 방지(InnoDB) |

**Lock 전략**

`READ COMMITTED`는 **Record Lock만 사용**하고 Gap Lock은 비활성화한다. 덕분에 동시성이 높고 데드락 발생 확률이 낮다.

---

### 3. READ COMMITTED만으로 충분한가?

결론부터 말하면 **충분하지 않다.** 이유는 다음과 같다.

**Non-Repeatable Read로 인한 정원 체크 무력화**

```
시나리오: 강좌 정원 30명, 현재 29명 등록된 상태

T1 (학생 A): SELECT currentEnrollment → 29명 확인 (1자리 남음)

T2 (학생 B): SELECT currentEnrollment → 29명 확인 (1자리 남음)
             INSERT enrollment → 성공
             UPDATE course SET currentEnrollment = 30 → 성공
             COMMIT

T1 (학생 A): (B가 이미 커밋했지만 T1은 이미 정원 확인을 마쳤음)
             INSERT enrollment → 성공
             UPDATE course SET currentEnrollment = 30 → 성공 (30→30, Lost Update!)
             COMMIT

결과: 31번째 학생이 등록됨. 정원 초과 발생.
```

`READ COMMITTED`의 Non-Repeatable Read 특성 때문에 T1이 SELECT한 29라는 값이 T2 커밋 이후에는 이미 유효하지 않은 데이터가 되었지만, T1은 이를 인지하지 못하고 등록을 진행한다.

**격리 수준만으로는 동시성 문제를 해결할 수 없는 이유**

격리 수준은 읽기 일관성에 대한 보장이지, **쓰기 직렬화에 대한 보장이 아니다.** `REPEATABLE READ`를 써도 동일한 문제가 발생한다. T1이 첫 번째 읽기 스냅샷(29명)을 유지하더라도, T2가 먼저 INSERT와 UPDATE를 커밋하면 T1의 UPDATE가 T2의 데이터를 덮어쓸 수 있다.

이 문제를 해결하려면 격리 수준 설정만으로는 부족하고, **명시적인 락 전략**이 반드시 필요하다.

---

### 4. 동시성 문제 해결을 위한 보완 전략

#### 4-1. 비관적 락 (SELECT ... FOR UPDATE)

가장 직관적이고 확실한 방법이다. 강좌 레코드를 읽을 때 배타적 락을 획득하여, 다른 트랜잭션이 같은 레코드를 수정하지 못하도록 막는다.

```java
// Repository
public interface CourseRepository extends JpaRepository<Course, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Course c WHERE c.id = :id")
    Optional<Course> findByIdWithPessimisticLock(@Param("id") Long id);
}

// Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public void registerCourse(Long courseId, Long studentId) {
    // SELECT ... FOR UPDATE: 이 시점부터 다른 트랜잭션은 이 레코드에 대기
    Course course = courseRepository.findByIdWithPessimisticLock(courseId)
        .orElseThrow(() -> new CourseNotFoundException(courseId));

    if (course.isFull()) {
        throw new CourseFullException("정원이 초과되었습니다.");
    }

    enrollmentRepository.save(new Enrollment(courseId, studentId));
    course.incrementEnrollment();
    // 트랜잭션 커밋 시 락 해제 → 다음 트랜잭션이 최신 데이터로 진행
}
```

**동작 원리**

```
T1: SELECT ... FOR UPDATE (course_id=1) → X-Lock 획득
T2: SELECT ... FOR UPDATE (course_id=1) → X-Lock 대기 (T1 커밋 전까지 블로킹)

T1: 정원 확인(29 < 30) → INSERT → UPDATE currentEnrollment=30 → COMMIT → X-Lock 해제
T2: X-Lock 획득 → 정원 확인(30 >= 30) → CourseFullException 발생 → ROLLBACK
```

이제 정원 초과가 완벽히 방지된다.

#### 4-2. 낙관적 락 (@Version)

충돌이 발생했을 때 재시도하는 방식이다. 수강신청처럼 충돌이 빈번한 상황에서는 재시도 비용이 커서 비관적 락보다 불리한 경우가 많다.

```java
@Entity
public class Course {
    @Id
    private Long id;

    private int currentEnrollment;
    private int maxCapacity;

    @Version
    private Long version;  // JPA가 자동으로 버전 관리

    public boolean isFull() {
        return currentEnrollment >= maxCapacity;
    }
}

// Service (재시도 로직 필요)
@Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3)
@Transactional(isolation = Isolation.READ_COMMITTED)
public void registerCourse(Long courseId, Long studentId) {
    Course course = courseRepository.findById(courseId).orElseThrow();

    if (course.isFull()) {
        throw new CourseFullException("정원이 초과되었습니다.");
    }

    enrollmentRepository.save(new Enrollment(courseId, studentId));
    course.incrementEnrollment();
    // 커밋 시 UPDATE course SET version = version+1 WHERE id=? AND version=?
    // 버전 불일치 시 OptimisticLockingFailureException 발생 → 재시도
}
```

수강신청처럼 **충돌 빈도가 높은** 상황에서는 비관적 락이 더 효율적이다. 낙관적 락은 충돌 시 롤백 후 재시도를 반복하여 오히려 DB 부하가 증가할 수 있다.

#### 4-3. UNIQUE 제약 조건 + 예외 처리

수강 신청 테이블에 `(course_id, student_id)` UNIQUE 제약 조건을 추가하면 중복 신청을 DB 레벨에서 방지할 수 있다. 그러나 이것은 **중복 신청 방지**에만 유효하고, **정원 초과 방지**는 해결하지 못한다.

```sql
CREATE TABLE enrollment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    course_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    UNIQUE KEY uk_course_student (course_id, student_id)
);
```

정원 초과 방지를 위해서는 별도 락 전략이 추가로 필요하다.

#### 4-4. Redis 분산 락

DB 락 대신 Redis를 이용한 분산 락을 사용하면 DB 부하를 줄이고 더 빠른 락 획득/해제가 가능하다. 특히 여러 서버 인스턴스가 있는 분산 환경에서 DB의 `SELECT FOR UPDATE`는 동일한 DB를 바라보므로 유효하지만, Redis 분산 락은 더 유연하고 타임아웃 제어가 용이하다.

```java
@Service
public class CourseRegistrationService {

    private final RedissonClient redissonClient;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void registerCourse(Long courseId, Long studentId) {
        RLock lock = redissonClient.getLock("course:lock:" + courseId);

        try {
            // 최대 3초 대기, 락 획득 시 5초 유지
            boolean acquired = lock.tryLock(3, 5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new LockAcquisitionException("잠시 후 다시 시도해주세요.");
            }

            Course course = courseRepository.findById(courseId).orElseThrow();

            if (course.isFull()) {
                throw new CourseFullException("정원이 초과되었습니다.");
            }

            enrollmentRepository.save(new Enrollment(courseId, studentId));
            course.incrementEnrollment();
            courseRepository.save(course);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 획득 중 인터럽트 발생", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

---

### 5. Isolation Level 올리기 vs 락 전략 비교

#### SERIALIZABLE vs READ COMMITTED + 비관적 락

격리 수준을 `SERIALIZABLE`로 올리는 것과 `READ COMMITTED` + `SELECT FOR UPDATE`를 조합하는 것은 얼핏 비슷해 보이지만 동작 방식과 성능에 큰 차이가 있다.

| 항목 | SERIALIZABLE | READ COMMITTED + 비관적 락 |
|------|:-----------:|:--------------------------:|
| 락 범위 | 모든 SELECT에 자동으로 FOR SHARE 추가 | 명시적으로 지정한 곳만 FOR UPDATE |
| 동시성 영향 | 시스템 전체에 영향 | 특정 자원에만 국한 |
| 읽기 작업 블로킹 | 발생 (FOR SHARE끼리는 공존하지만 쓰기와 충돌) | 읽기 전용 쿼리는 블로킹 없음 |
| 데드락 위험 | 높음 | 락 순서 관리 시 낮음 |
| 구현 복잡도 | 단순 (어노테이션 하나) | 중간 (Lock 어노테이션 + 쿼리 작성) |
| 적용 범위 | 트랜잭션 내 모든 쿼리 | 명시적으로 지정한 쿼리만 |

`SERIALIZABLE`은 **모든 트랜잭션**을 직렬화하기 때문에 수강신청과 무관한 단순 조회 트랜잭션까지 성능에 영향을 받는다. 반면 `READ COMMITTED + SELECT FOR UPDATE` 조합은 **락이 필요한 자원에만 선택적으로 적용**할 수 있어 성능과 정합성을 동시에 잡을 수 있다.

#### MySQL InnoDB 기본 격리 수준(REPEATABLE READ)과의 비교

Spring의 `@Transactional`에서 `isolation`을 명시하지 않으면 기본값인 `Isolation.DEFAULT`가 적용되고, 이는 **DB의 기본 격리 수준을 그대로 사용**한다. MySQL InnoDB의 기본은 `REPEATABLE READ`다.

```java
// 아래 두 설정은 MySQL + InnoDB 조합에서 사실상 동일하게 동작한다
@Transactional                                          // Isolation.DEFAULT
@Transactional(isolation = Isolation.REPEATABLE_READ)  // 명시적으로 동일
```

`REPEATABLE READ`에서도 락 전략 없이는 수강신청 동시성 문제가 해결되지 않는다. 격리 수준과 락 전략은 별개의 개념임을 항상 인식해야 한다.

```
격리 수준: 트랜잭션이 다른 트랜잭션의 변경을 얼마나 볼 수 있는가 (가시성)
락 전략: 특정 자원에 대한 동시 접근을 어떻게 제어하는가 (직렬화)
```

---

### 6. 실무에서의 권장 패턴

#### 6-1. READ COMMITTED + SELECT FOR UPDATE 조합

수강신청처럼 **충돌이 빈번하고 정합성이 중요한** 시스템에서 가장 권장되는 패턴이다.

```java
@Service
@RequiredArgsConstructor
public class CourseEnrollmentService {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EnrollmentResult enroll(Long courseId, Long studentId) {
        // 중복 신청 체크 (락 전에 빠르게 확인)
        if (enrollmentRepository.existsByCourseIdAndStudentId(courseId, studentId)) {
            throw new AlreadyEnrolledException("이미 신청한 강좌입니다.");
        }

        // 핵심 자원에만 비관적 락 적용
        Course course = courseRepository.findByIdWithPessimisticLock(courseId)
            .orElseThrow(() -> new CourseNotFoundException(courseId));

        // 락 획득 후 최신 상태로 정원 재확인
        if (course.isFull()) {
            throw new CourseFullException("정원이 초과되었습니다.");
        }

        Enrollment enrollment = enrollmentRepository.save(
            Enrollment.of(courseId, studentId)
        );
        course.incrementEnrollment();

        return EnrollmentResult.success(enrollment.getId());
    }
}
```

**핵심 포인트**

1. `isolation = Isolation.READ_COMMITTED`는 MySQL InnoDB의 기본값(`REPEATABLE_READ`)보다 한 단계 낮지만, `SELECT FOR UPDATE`와 함께 사용하면 정합성을 완벽히 보장한다.
2. 락은 `courseRepository.findByIdWithPessimisticLock()`에서 획득하며, 트랜잭션이 커밋되거나 롤백될 때 자동으로 해제된다.
3. 락 획득 후 정원을 재확인(double check)한다. 락을 기다리는 동안 다른 트랜잭션이 정원을 채웠을 수 있기 때문이다.

#### 6-2. 트랜잭션 범위 최소화

락을 보유하는 시간이 길수록 다른 트랜잭션의 대기 시간이 늘어난다. 트랜잭션 범위를 최소화해야 한다.

```java
// 나쁜 예: 트랜잭션 범위가 불필요하게 크다
@Transactional(isolation = Isolation.READ_COMMITTED)
public void enrollWithNotification(Long courseId, Long studentId) {
    Course course = courseRepository.findByIdWithPessimisticLock(courseId).orElseThrow();

    if (course.isFull()) throw new CourseFullException();

    enrollmentRepository.save(Enrollment.of(courseId, studentId));
    course.incrementEnrollment();

    // 이메일 발송은 락을 보유한 채로 실행되어 처리 시간이 늘어남
    emailService.sendEnrollmentConfirmation(studentId, courseId); // 외부 API 호출!
}

// 좋은 예: 이메일 발송을 트랜잭션 밖으로 분리
@Transactional(isolation = Isolation.READ_COMMITTED)
public Long enroll(Long courseId, Long studentId) {
    Course course = courseRepository.findByIdWithPessimisticLock(courseId).orElseThrow();

    if (course.isFull()) throw new CourseFullException();

    Enrollment enrollment = enrollmentRepository.save(Enrollment.of(courseId, studentId));
    course.incrementEnrollment();

    return enrollment.getId(); // 트랜잭션 종료 (락 해제)
}

// 이메일 발송은 트랜잭션 외부에서 처리
public void enrollWithNotification(Long courseId, Long studentId) {
    Long enrollmentId = enroll(courseId, studentId);
    emailService.sendEnrollmentConfirmation(studentId, courseId); // 락 해제 후 실행
}
```

#### 6-3. 재시도 로직 설계

`SELECT FOR UPDATE`로 락을 대기하다 타임아웃이 발생하거나 데드락이 감지되면 예외가 발생한다. 이를 대비한 재시도 로직을 갖추는 것이 좋다.

```java
@Service
public class CourseEnrollmentFacade {

    private final CourseEnrollmentService enrollmentService;

    // Spring Retry 활용
    @Retryable(
        value = {CannotAcquireLockException.class, DeadlockLoserDataAccessException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)  // 100ms, 200ms, 400ms 간격
    )
    public EnrollmentResult enrollWithRetry(Long courseId, Long studentId) {
        return enrollmentService.enroll(courseId, studentId);
    }

    @Recover
    public EnrollmentResult recoverFromLockTimeout(
            CannotAcquireLockException e, Long courseId, Long studentId) {
        throw new ServiceUnavailableException("현재 수강신청이 집중되어 있습니다. 잠시 후 다시 시도해주세요.");
    }
}
```

---

## 핵심 정리
- 핵심 포인트 1: READ COMMITTED 단독으로는 수강신청 동시성 문제를 해결할 수 없다. 격리 수준은 읽기 일관성에 대한 보장이지 쓰기 직렬화에 대한 보장이 아니다.
- 핵심 포인트 2: 격리 수준보다 적절한 락 전략 선택이 더 중요하다. SERIALIZABLE로 올려도 내부적으로는 모든 SELECT에 FOR SHARE를 추가하는 방식이라 시스템 전체 성능이 저하된다.
- 핵심 포인트 3: READ COMMITTED + SELECT FOR UPDATE(비관적 락) 조합이 성능과 정합성을 모두 잡는 실무 패턴이다. 락은 필요한 자원에만 선택적으로 적용하고, 트랜잭션 범위는 최소화하며, 재시도 로직을 함께 구비한다.

## 키워드

### `@Transactional`
Spring에서 선언적으로 트랜잭션을 관리하는 어노테이션이다. AOP 프록시 기반으로 동작하며, `isolation`, `propagation`, `readOnly`, `timeout`, `rollbackFor` 등의 속성을 통해 트랜잭션 동작을 세밀하게 제어할 수 있다. 기본값은 `isolation = Isolation.DEFAULT`(DB 기본값 사용), `propagation = Propagation.REQUIRED`이다.

### `Isolation Level`
여러 트랜잭션이 동시에 실행될 때 서로의 변경 사항이 얼마나 보이는지를 정의하는 수준이다. SQL 표준은 `READ UNCOMMITTED`, `READ COMMITTED`, `REPEATABLE READ`, `SERIALIZABLE` 4단계를 정의하며, 격리 수준이 높을수록 정합성은 높아지지만 동시성(성능)은 낮아진다.

### `READ COMMITTED`
커밋된 데이터만 읽는 격리 수준이다. 매 SELECT마다 새로운 MVCC 스냅샷을 생성하므로 항상 최신 커밋 데이터를 읽는다. Dirty Read는 방지하지만 Non-Repeatable Read와 Phantom Read는 발생할 수 있다. Record Lock만 사용하고 Gap Lock을 비활성화해 높은 동시성을 제공하며 데드락 발생 확률이 낮다.

### `동시성 제어`
여러 트랜잭션이 동시에 같은 데이터에 접근할 때 발생하는 문제(Race Condition, Lost Update, Phantom Read 등)를 방지하기 위한 메커니즘이다. 격리 수준, 비관적 락, 낙관적 락, 분산 락 등의 방법이 있으며, 시스템 특성에 맞는 전략을 선택해야 한다.

### `비관적 락`
데이터 충돌이 자주 발생할 것으로 예상하고 미리 락을 획득하는 방식이다. `SELECT ... FOR UPDATE` 구문으로 배타적 락(X-Lock)을 걸어 다른 트랜잭션의 접근을 차단한다. 수강신청처럼 충돌 빈도가 높은 시스템에서 낙관적 락보다 효율적이다.

### `SELECT FOR UPDATE`
비관적 락을 구현하기 위한 SQL 구문이다. 선택된 행에 배타적 락을 걸어 트랜잭션이 완료될 때까지 다른 트랜잭션이 해당 행을 읽거나 수정하지 못하게 막는다. JPA에서는 `@Lock(LockModeType.PESSIMISTIC_WRITE)` 어노테이션으로 구현한다.

### `수강신청 시스템`
특정 시간에 다수의 사용자가 동시에 제한된 자원(강좌 정원)에 접근하는 대표적인 고동시성 시나리오다. 정원 초과 방지가 핵심 요구사항이며, Check-Then-Act 패턴의 Race Condition이 주요 동시성 문제로 발생한다.

### `Race Condition`
여러 스레드 또는 트랜잭션이 공유 자원에 동시에 접근하여 실행 순서에 따라 결과가 달라지는 현상이다. 수강신청에서는 잔여석 확인과 등록 사이의 갭에서 발생하며, 두 트랜잭션이 동시에 잔여석이 있다고 판단하고 모두 등록을 완료하는 문제로 나타난다.

### `Lost Update`
두 트랜잭션이 동시에 같은 데이터를 읽고 수정할 때, 하나의 트랜잭션의 변경 사항이 다른 트랜잭션의 변경 사항으로 덮어씌워지는 현상이다. 수강신청에서는 `currentEnrollment`를 두 트랜잭션이 동시에 읽어 각각 +1 업데이트하면 실제로는 +2가 되어야 하지만 +1만 반영되는 문제로 나타난다.

### `트랜잭션 격리 수준`
SQL 표준에서 정의한 4단계 격리 수준(READ UNCOMMITTED, READ COMMITTED, REPEATABLE READ, SERIALIZABLE)으로, 동시에 실행되는 트랜잭션 간 데이터 가시성을 제어한다. MySQL InnoDB의 기본값은 `REPEATABLE READ`이며, Spring `@Transactional`의 기본값인 `Isolation.DEFAULT`는 DB의 기본 격리 수준을 그대로 따른다.

## 참고 자료
- [MySQL 8.4 Reference Manual - Transaction Isolation Levels](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)
- [Spring Framework - @Transactional Annotation (공식 문서)](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Baeldung - Transaction Propagation and Isolation in Spring @Transactional](https://www.baeldung.com/spring-transactional-propagation-isolation)
- [Baeldung - Pessimistic Locking in JPA](https://www.baeldung.com/jpa-pessimistic-locking)
- [Baeldung - Enabling Transaction Locks in Spring Data JPA](https://www.baeldung.com/java-jpa-transaction-locks)
- [Percona - Differences Between READ-COMMITTED and REPEATABLE-READ Transaction Isolation Levels](https://www.percona.com/blog/differences-between-read-committed-and-repeatable-read-transaction-isolation-levels/)
- [PlanetScale - MySQL isolation levels and how they work](https://planetscale.com/blog/mysql-isolation-levels-and-how-they-work)
