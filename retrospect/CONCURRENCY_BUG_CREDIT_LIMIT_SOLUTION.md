# 동시성 버그 해결 방안: 동일 학생 동시 신청 시 학점 초과 문제

## 📌 문제 정의

### 현재 상황
```java
// EnrollmentService.java:63-79
List<Enrollment> enrollments = enrollmentRepository.findAllByStudentId(student.getId());
// ...
int totalCredits = enrolledCourses.values().stream()
    .mapToInt(Course::getCredits)
    .sum();
if (totalCredits + course.getCredits() > MAX_CREDITS) {
    throw new CustomException(ErrorCode.CREDIT_LIMIT_EXCEEDED);
}
```

### 버그 시나리오

```
초기 상태:
- 학생 A의 현재 수강 학점: 15학점
- MAX_CREDITS: 18학점
- 두 개의 3학점 강좌(C1, C2)에 동시 신청

┌─────────────────────────────────────────────────────────────┐
│ Timeline                                                    │
├─────────────────────────────────────────────────────────────┤
│ T1 (Thread 1: C1 신청)            T2 (Thread 2: C2 신청)      │
├─────────────────────────────────────────────────────────────┤
│ SELECT enrollments              │                           │
│ → 현재 학점: 15                   │                           │
│                                 │ SELECT enrollments        │
│                                 │ → 현재 학점: 15             │
│ 학점 체크: 15 + 3 = 18 (OK)       │                           │
│                                 │ 학점 체크: 15 + 3 = 18 (OK) │
│ INSERT enrollment (C1)          │                           │
│ ✅ 성공                          │                           │
│                                 │ INSERT enrollment (C2)    │
│                                 │ ✅ 성공                    │
└─────────────────────────────────────────────────────────────┘

최종 상태: 학생 A의 학점 = 15 + 3 + 3 = 21학점 ❌
문제: MAX_CREDITS(18) 초과!
```

### 핵심 문제

**Check-Then-Act 패턴의 Race Condition**
1. 학점 조회 (SELECT)
2. 학점 검증 (15 + 3 <= 18)
3. 신청 처리 (INSERT)

→ 1번과 3번 사이에 다른 트랜잭션이 끼어들어 학점 초과 발생!

---

## 🛠️ 해결 방안

### 방안 1: 비관적 락 (Pessimistic Lock) ⭐⭐⭐⭐⭐ **권장**

#### 개념
Student 엔티티에 대해 배타적 락을 획득하여, 해당 학생의 수강신청이 완료될 때까지 다른 트랜잭션이 접근하지 못하도록 차단

#### 구현 방법

**1단계: StudentRepository에 락 메서드 추가**
```java
// StudentRepository.java
public interface StudentRepository extends JpaRepository<Student, Long> {

    /**
     * 학생 조회 (비관적 쓰기 락)
     *
     * 해당 학생의 수강신청 처리가 완료될 때까지 다른 트랜잭션이 대기하도록 강제.
     * 동일 학생의 동시 신청 시 학점 초과를 방지한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Student s WHERE s.id = :id")
    Optional<Student> findByIdWithLock(@Param("id") Long id);
}
```

**2단계: EnrollmentService 수정**
```java
// EnrollmentService.java
public EnrollmentResponse enroll(EnrollmentRequest request) {
    // 변경 전: Student student = studentRepository.findById(request.getStudentId())
    // 변경 후:
    Student student = studentRepository.findByIdWithLock(request.getStudentId())
            .orElseThrow(() -> new CustomException(ErrorCode.STUDENT_NOT_FOUND));

    // 이후 로직은 동일...
    // 학점 체크, 시간표 충돌 체크, 정원 감소, INSERT
    // → 모두 student 락이 유지된 상태에서 실행
}
```

**3단계: 실행 흐름 (락 적용 후)**
```
T1 (Thread 1: C1 신청)          T2 (Thread 2: C2 신청)
───────────────────────────────────────────────────────────
SELECT s ... FOR UPDATE         │ (대기)
→ Student 락 획득                 │
                                │
학점 체크: 15 + 3 = 18 (OK)       │
INSERT enrollment (C1)          │
COMMIT → 락 해제                  │
                                │ SELECT s ... FOR UPDATE
                                │ → Student 락 획득
                                │ 학점 체크: 18 + 3 = 21 (초과!)
                                │ ❌ CREDIT_LIMIT_EXCEEDED
```

#### 장점
- ✅ **확실한 동시성 보장**: DB 레벨에서 락 획득
- ✅ **구현 간단**: 메서드 하나만 추가 (`findByIdWithLock`)
- ✅ **데이터 정합성 완벽**: 트랜잭션 격리 수준과 무관
- ✅ **기존 로직 유지**: 학점 체크 로직 변경 불필요

#### 단점
- ⚠️ **성능 저하**: 동일 학생이 동시 신청 시 직렬화 (대기 발생)
- ⚠️ **데드락 위험**: 여러 엔티티에 락을 거는 경우 순서 중요
- ⚠️ **타임아웃 설정 필요**: 락 대기 시간 제한 필요

#### 데드락 방지 전략
```java
// 락 획득 순서 정의 (항상 일관된 순서로 락 획득)
// 1. Student 락 획득
// 2. Course 정원 감소 (이미 원자적 업데이트 사용 중)
// → 순서만 지키면 데드락 발생 안 함

// 타임아웃 설정 예시
@QueryHints(@QueryHint(
    name = "javax.persistence.lock.timeout",
    value = "5000"  // 5초
))
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Student> findByIdWithLock(@Param("id") Long id);
```

---

### 방안 2: 낙관적 락 (Optimistic Lock) ⭐⭐⭐

#### 개념
Student 엔티티에 version 필드를 추가하여, 수정 시 version이 변경되었으면 예외 발생 후 재시도

#### 구현 방법

**1단계: Student 엔티티 수정**
```java
@Entity
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version  // ← 추가
    private Long version;

    private String studentNumber;
    private String name;
    // ...
}
```

**2단계: EnrollmentService 수정**
```java
public EnrollmentResponse enroll(EnrollmentRequest request) {
    try {
        // Student 조회 (version 함께 조회)
        Student student = studentRepository.findById(request.getStudentId())
                .orElseThrow(() -> new CustomException(ErrorCode.STUDENT_NOT_FOUND));

        // 학점 체크 로직...
        // INSERT enrollment
        // → COMMIT 시 Student의 version이 변경되었는지 체크

    } catch (OptimisticLockException ex) {
        // 재시도 또는 에러 반환
        throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
    }
}
```

**3단계: 재시도 로직 추가 (선택)**
```java
@Transactional
public EnrollmentResponse enroll(EnrollmentRequest request) {
    int maxRetries = 3;
    int attempt = 0;

    while (attempt < maxRetries) {
        try {
            return doEnroll(request);
        } catch (OptimisticLockException ex) {
            attempt++;
            if (attempt >= maxRetries) {
                throw new CustomException(ErrorCode.TOO_MANY_RETRIES);
            }
            // 잠시 대기 후 재시도
            Thread.sleep(100);
        }
    }
}
```

#### 장점
- ✅ **성능 우수**: 락을 걸지 않아 대기 없음
- ✅ **데드락 없음**: 락을 사용하지 않음
- ✅ **확장성 좋음**: 동시 접속 많아도 성능 유지

#### 단점
- ⚠️ **재시도 로직 필요**: 충돌 시 사용자 경험 저하
- ⚠️ **스키마 변경 필요**: Student 테이블에 version 컬럼 추가
- ⚠️ **충돌 빈번 시 비효율**: 같은 학생이 계속 신청하면 재시도 반복

#### 적용 시나리오
- 동일 학생의 동시 신청이 **드문 경우** (대부분 다른 학생)
- 재시도가 허용되는 환경
- 성능이 최우선인 경우

---

### 방안 3: 원자적 업데이트 (Atomic Update) ⭐⭐⭐⭐

#### 개념
Student 테이블에 `currentCredits` 컬럼을 추가하고, 정원 감소처럼 원자적 UPDATE 쿼리로 학점 체크 + 증가를 한 번에 처리

#### 구현 방법

**1단계: Student 테이블 스키마 변경**
```sql
ALTER TABLE student ADD COLUMN current_credits INT DEFAULT 0;
```

**2단계: Student 엔티티 수정**
```java
@Entity
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String studentNumber;
    private String name;

    @Column(name = "current_credits", nullable = false)
    private Integer currentCredits = 0;  // ← 추가

    // getter만 제공 (setter 금지)
    public Integer getCurrentCredits() {
        return currentCredits;
    }
}
```

**3단계: StudentRepository에 원자적 업데이트 메서드 추가**
```java
public interface StudentRepository extends JpaRepository<Student, Long> {

    /**
     * 학점 원자적 증가
     *
     * 현재 학점 + 추가 학점 <= MAX_CREDITS인 경우에만 증가.
     *
     * @param studentId 학생 ID
     * @param credits 추가할 학점
     * @param maxCredits 최대 학점 (18)
     * @return 업데이트된 행 수 (1: 성공, 0: 실패)
     */
    @Modifying
    @Query("UPDATE Student s SET s.currentCredits = s.currentCredits + :credits " +
           "WHERE s.id = :studentId AND s.currentCredits + :credits <= :maxCredits")
    int increaseCreditsIfWithinLimit(
        @Param("studentId") Long studentId,
        @Param("credits") Integer credits,
        @Param("maxCredits") Integer maxCredits
    );

    /**
     * 학점 원자적 감소 (수강 취소 시)
     */
    @Modifying
    @Query("UPDATE Student s SET s.currentCredits = s.currentCredits - :credits " +
           "WHERE s.id = :studentId AND s.currentCredits >= :credits")
    int decreaseCredits(
        @Param("studentId") Long studentId,
        @Param("credits") Integer credits
    );
}
```

**4단계: EnrollmentService 수정**
```java
public EnrollmentResponse enroll(EnrollmentRequest request) {
    Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new CustomException(ErrorCode.STUDENT_NOT_FOUND));

    Course course = courseRepository.findById(request.getCourseId())
            .orElseThrow(() -> new CustomException(ErrorCode.COURSE_NOT_FOUND));

    // 중복 신청 체크...
    // 시간표 충돌 체크...

    // 학점 원자적 증가 (Check-Then-Act를 원자적으로!)
    int updated = studentRepository.increaseCreditsIfWithinLimit(
        student.getId(),
        course.getCredits(),
        MAX_CREDITS
    );

    if (updated == 0) {
        log.warn("수강신청 실패: 학점 초과 studentId={}, courseId={}, requestCredits={}",
                student.getId(), course.getId(), course.getCredits());
        throw new CustomException(ErrorCode.CREDIT_LIMIT_EXCEEDED);
    }

    // 정원 감소...
    // INSERT enrollment...

    // INSERT 실패 시 학점 복구 (보상 트랜잭션)
    try {
        Enrollment enrollment = enrollmentRepository.save(new Enrollment(...));
        return EnrollmentResponse.from(enrollment);
    } catch (DataIntegrityViolationException ex) {
        // 학점 복구
        studentRepository.decreaseCredits(student.getId(), course.getCredits());
        // 정원 복구 (기존 로직)
        proxy.recoverSeat(course.getId());
        throw new CustomException(ErrorCode.DUPLICATE_ENROLLMENT);
    }
}
```

**5단계: 수강 취소 시 학점 감소**
```java
public EnrollmentCancelResponse cancel(EnrollmentCancelRequest request) {
    // 기존 로직...
    long deleted = enrollmentRepository.deleteByStudentIdAndCourseId(...);

    // 학점 감소 추가
    Course course = courseRepository.findById(request.getCourseId())
            .orElseThrow(() -> new CustomException(ErrorCode.COURSE_NOT_FOUND));

    studentRepository.decreaseCredits(request.getStudentId(), course.getCredits());

    // 정원 증가 (기존 로직)
    courseRepository.increaseSeat(request.getCourseId());
}
```

#### 장점
- ✅ **강력한 동시성 보장**: 정원 감소와 동일한 패턴
- ✅ **성능 우수**: 락 없이 원자적 업데이트
- ✅ **데드락 없음**: UPDATE 쿼리만 사용
- ✅ **일관된 패턴**: 정원 관리와 동일한 방식

#### 단점
- ⚠️ **스키마 변경 필요**: `current_credits` 컬럼 추가
- ⚠️ **데이터 동기화**: currentCredits와 실제 Enrollment 합계 일치 보장 필요
- ⚠️ **초기 마이그레이션**: 기존 데이터에 currentCredits 계산 필요

#### 데이터 정합성 유지

**방법 1: 트리거 활용 (권장)**
```sql
-- Enrollment INSERT 시 자동으로 currentCredits 증가
CREATE TRIGGER after_enrollment_insert
AFTER INSERT ON enrollment
FOR EACH ROW
BEGIN
    UPDATE student s
    JOIN course c ON c.id = NEW.course_id
    SET s.current_credits = s.current_credits + c.credits
    WHERE s.id = NEW.student_id;
END;

-- Enrollment DELETE 시 자동으로 currentCredits 감소
CREATE TRIGGER after_enrollment_delete
AFTER DELETE ON enrollment
FOR EACH ROW
BEGIN
    UPDATE student s
    JOIN course c ON c.id = OLD.course_id
    SET s.current_credits = s.current_credits - c.credits
    WHERE s.id = OLD.student_id;
END;
```

**방법 2: 정합성 검증 배치 작업**
```java
// 매일 새벽 실행하여 currentCredits와 실제 합계 비교
@Scheduled(cron = "0 0 3 * * *")  // 매일 새벽 3시
public void validateStudentCredits() {
    List<Student> students = studentRepository.findAll();

    for (Student student : students) {
        List<Enrollment> enrollments = enrollmentRepository.findAllByStudentId(student.getId());
        List<Long> courseIds = enrollments.stream()
            .map(Enrollment::getCourseId)
            .toList();

        int actualCredits = courseRepository.findAllById(courseIds).stream()
            .mapToInt(Course::getCredits)
            .sum();

        if (!actualCredits.equals(student.getCurrentCredits())) {
            log.error("학점 불일치 감지: studentId={}, currentCredits={}, actualCredits={}",
                student.getId(), student.getCurrentCredits(), actualCredits);
            // 알림 전송 or 자동 수정
        }
    }
}
```

**방법 3: 보상 트랜잭션 (Compensating Transaction)**

> **핵심 개념**: 정원 감소·수강신청 저장 등 각 단계가 실패할 때, 이미 완료된 학점 증가를 즉시 원상 복구하는 명시적 보상 로직을 애플리케이션 코드로 관리한다.

---

##### 언제 보상 트랜잭션이 필요한가?

| 상황 | Spring `@Transactional` 롤백으로 충분한가? |
|------|-------------------------------------------|
| `increaseCreditsIfWithinLimit()` 와 `enrollmentRepository.save()` 가 **같은 트랜잭션** | ✅ 예외 발생 시 전체 롤백 → 자동 복구 |
| 정원 감소(`decreaseSeat`)를 **별도 트랜잭션** (`REQUIRES_NEW`)으로 실행하는 경우 | ❌ 정원 트랜잭션은 이미 커밋됨 → 명시적 보상 필요 |
| `increaseCreditsIfWithinLimit()` 성공 후 **다른 예외**로 바깥 트랜잭션만 롤백되는 경우 | ❌ `@Modifying` 쿼리가 flush되기 전 롤백되면 자동 복구되지만, flush 이후 커밋 전 실패 시 불일치 가능 |

> **결론**: 현재 구현처럼 정원 복구(`recoverSeat`)를 `REQUIRES_NEW`로 처리하는 경우, 학점 복구도 동일한 패턴의 명시적 보상 트랜잭션이 필요하다.

---

##### 구현 방법

**1단계: EnrollmentService에 보상 트랜잭션 적용**

```java
@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private static final int MAX_CREDITS = 18;

    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;

    // self-proxy: 보상 트랜잭션(REQUIRES_NEW)을 위해 자기 자신을 주입
    @Autowired
    private EnrollmentService self;

    @Transactional
    public EnrollmentResponse enroll(EnrollmentRequest request) {
        Student student = studentRepository.findById(request.getStudentId())
                .orElseThrow(() -> new CustomException(ErrorCode.STUDENT_NOT_FOUND));
        Course course = courseRepository.findById(request.getCourseId())
                .orElseThrow(() -> new CustomException(ErrorCode.COURSE_NOT_FOUND));

        // 중복 신청 체크, 시간표 충돌 체크...

        // Step 1: 학점 원자적 증가
        int creditUpdated = studentRepository.increaseCreditsIfWithinLimit(
                student.getId(), course.getCredits(), MAX_CREDITS);
        if (creditUpdated == 0) {
            throw new CustomException(ErrorCode.CREDIT_LIMIT_EXCEEDED);
        }

        // Step 2: 정원 감소 (REQUIRES_NEW 별도 트랜잭션)
        int seatUpdated = courseRepository.decreaseSeat(course.getId());
        if (seatUpdated == 0) {
            // Step 2 실패 → Step 1 보상: 학점 즉시 복구
            self.compensateCredits(student.getId(), course.getCredits());
            throw new CustomException(ErrorCode.COURSE_FULL);
        }

        // Step 3: 수강신청 저장
        try {
            Enrollment enrollment = enrollmentRepository.save(
                    Enrollment.of(student.getId(), course.getId()));
            return EnrollmentResponse.from(enrollment);

        } catch (DataIntegrityViolationException ex) {
            // Step 3 실패 → Step 1, 2 모두 보상
            self.compensateCredits(student.getId(), course.getCredits());
            self.recoverSeat(course.getId());
            throw new CustomException(ErrorCode.DUPLICATE_ENROLLMENT);
        }
    }

    /**
     * 학점 보상 트랜잭션
     *
     * 수강신청이 실패한 경우 이미 증가된 currentCredits를 원상 복구한다.
     * REQUIRES_NEW를 사용하여 외부 트랜잭션 롤백과 무관하게 반드시 커밋한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateCredits(Long studentId, Integer credits) {
        int restored = studentRepository.decreaseCredits(studentId, credits);
        if (restored == 0) {
            // 복구 실패는 데이터 불일치이므로 반드시 알림
            log.error("학점 보상 트랜잭션 실패: studentId={}, credits={}", studentId, credits);
        }
    }

    /**
     * 정원 보상 트랜잭션
     *
     * 수강신청이 실패한 경우 이미 감소된 정원을 원상 복구한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverSeat(Long courseId) {
        courseRepository.increaseSeat(courseId);
    }
}
```

**2단계: 수강 취소 시 학점 복구 (보상 로직 동일 적용)**

```java
@Transactional
public EnrollmentCancelResponse cancel(EnrollmentCancelRequest request) {
    long deleted = enrollmentRepository.deleteByStudentIdAndCourseId(
            request.getStudentId(), request.getCourseId());

    if (deleted == 0) {
        throw new CustomException(ErrorCode.ENROLLMENT_NOT_FOUND);
    }

    Course course = courseRepository.findById(request.getCourseId())
            .orElseThrow(() -> new CustomException(ErrorCode.COURSE_NOT_FOUND));

    // 취소 = 수강신청의 역방향 보상: 학점 감소 + 정원 증가
    studentRepository.decreaseCredits(request.getStudentId(), course.getCredits());
    courseRepository.increaseSeat(request.getCourseId());

    return EnrollmentCancelResponse.success();
}
```

---

##### 보상 트랜잭션 흐름도

```
enroll() [REQUIRED]
   │
   ├─ Step 1: increaseCreditsIfWithinLimit() ─ 성공
   │
   ├─ Step 2: decreaseSeat() ─ 실패
   │         └─→ compensateCredits() [REQUIRES_NEW] ─ 학점 복구 커밋
   │               └─→ throw COURSE_FULL
   │
   └─ Step 3: enrollmentRepository.save() ─ 실패 (중복 등)
             ├─→ compensateCredits() [REQUIRES_NEW] ─ 학점 복구 커밋
             ├─→ recoverSeat()       [REQUIRES_NEW] ─ 정원 복구 커밋
             └─→ throw DUPLICATE_ENROLLMENT
```

---

##### 장점과 단점

| 항목 | 설명 |
|------|------|
| ✅ 즉시 복구 | 배치 작업과 달리 실패 직후 바로 정합성 복구 |
| ✅ 트리거 불필요 | DB 종속 없이 애플리케이션 코드로 관리 |
| ✅ 디버깅 용이 | 보상 로직이 코드에 명시되어 추적 가능 |
| ⚠️ self-proxy 주의 | Spring AOP 특성상 자기 자신 호출 시 `@Autowired` self-proxy 필요 |
| ⚠️ 보상 실패 처리 | compensateCredits()가 실패할 경우 불일치 발생 → 알림/모니터링 필수 |
| ⚠️ 복잡도 증가 | 각 실패 경로마다 보상 흐름을 명시해야 함 |

> **권장 조합**: 보상 트랜잭션을 **주 전략**으로 사용하고, 배치 작업을 **최후 안전망**으로 병행 운영한다.
> 트리거는 DB 종속성이 생기므로 테스트 환경(H2)과의 호환성을 고려할 때 지양한다.

---

### 방안 4: 분산 락 (Distributed Lock with Redis) ⭐⭐

#### 개념
Redis를 활용하여 애플리케이션 레벨에서 학생별 락 획득

#### 구현 방법

**1단계: Redis 의존성 추가**
```gradle
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
implementation 'org.redisson:redisson-spring-boot-starter:3.23.5'
```

**2단계: Redisson 설정**
```java
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://localhost:6379");
        return Redisson.create(config);
    }
}
```

**3단계: EnrollmentService에 분산 락 적용**
```java
@Service
public class EnrollmentService {

    private final RedissonClient redissonClient;

    public EnrollmentResponse enroll(EnrollmentRequest request) {
        String lockKey = "enrollment:student:" + request.getStudentId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 락 획득 시도 (최대 5초 대기, 획득 후 10초 자동 해제)
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);

            if (!acquired) {
                throw new CustomException(ErrorCode.LOCK_ACQUISITION_FAILED);
            }

            // 수강신청 로직 실행 (락이 보장된 상태)
            return doEnroll(request);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.INTERRUPTED);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

#### 장점
- ✅ **스케일 아웃 가능**: 여러 서버에서도 동작
- ✅ **스키마 변경 불필요**: DB 변경 없음
- ✅ **타임아웃 제어 용이**: 락 획득 대기 시간 세밀 조정

#### 단점
- ⚠️ **인프라 추가 필요**: Redis 서버 운영 필요
- ⚠️ **복잡도 증가**: 락 관리 로직 추가
- ⚠️ **Redis 장애 시 영향**: SPOF (Single Point of Failure)
- ⚠️ **네트워크 레이턴시**: DB 락보다 느릴 수 있음

---

### 방안 5: DB 제약조건 (Database Constraint) ⭐⭐

#### 개념
DB 트리거로 학점 체크를 강제

#### 구현 방법

```sql
-- Enrollment INSERT 전에 학점 체크
DELIMITER $$

CREATE TRIGGER before_enrollment_insert
BEFORE INSERT ON enrollment
FOR EACH ROW
BEGIN
    DECLARE current_credits INT;
    DECLARE new_course_credits INT;
    DECLARE max_credits INT DEFAULT 18;

    -- 현재 학점 계산
    SELECT COALESCE(SUM(c.credits), 0)
    INTO current_credits
    FROM enrollment e
    JOIN course c ON e.course_id = c.id
    WHERE e.student_id = NEW.student_id;

    -- 신청하려는 강좌 학점
    SELECT credits
    INTO new_course_credits
    FROM course
    WHERE id = NEW.course_id;

    -- 학점 초과 체크
    IF current_credits + new_course_credits > max_credits THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'CREDIT_LIMIT_EXCEEDED';
    END IF;
END$$

DELIMITER ;
```

#### 장점
- ✅ **최종 방어선**: 애플리케이션 버그가 있어도 DB에서 차단
- ✅ **별도 코드 불필요**: 트리거가 자동 실행

#### 단점
- ⚠️ **DB 종속적**: H2, MySQL, PostgreSQL 등 문법 다름
- ⚠️ **디버깅 어려움**: 트리거 로직 파악 힘듦
- ⚠️ **성능 저하**: 매 INSERT마다 학점 재계산
- ⚠️ **에러 처리 복잡**: 트리거 예외를 애플리케이션에서 해석 필요

---

## 📊 방안 비교표

| 항목 | 비관적 락 | 낙관적 락 | 원자적 업데이트 | 분산 락 | DB 제약조건 |
|------|-----------|-----------|-----------------|---------|-------------|
| **구현 난이도** | ⭐⭐⭐⭐⭐ 매우 쉬움 | ⭐⭐⭐ 보통 | ⭐⭐⭐ 보통 | ⭐⭐ 어려움 | ⭐⭐ 어려움 |
| **성능** | ⭐⭐⭐ 보통 | ⭐⭐⭐⭐⭐ 우수 | ⭐⭐⭐⭐⭐ 우수 | ⭐⭐⭐ 보통 | ⭐⭐ 낮음 |
| **확장성** | ⭐⭐⭐ 보통 | ⭐⭐⭐⭐⭐ 우수 | ⭐⭐⭐⭐⭐ 우수 | ⭐⭐⭐⭐ 좋음 | ⭐⭐⭐ 보통 |
| **데이터 정합성** | ⭐⭐⭐⭐⭐ 완벽 | ⭐⭐⭐⭐ 좋음 | ⭐⭐⭐⭐⭐ 완벽 | ⭐⭐⭐⭐ 좋음 | ⭐⭐⭐⭐⭐ 완벽 |
| **스키마 변경** | ❌ 불필요 | ✅ 필요 (version) | ✅ 필요 (currentCredits) | ❌ 불필요 | ❌ 불필요 |
| **인프라 추가** | ❌ 불필요 | ❌ 불필요 | ❌ 불필요 | ✅ 필요 (Redis) | ❌ 불필요 |
| **데드락 위험** | ⚠️ 있음 | ❌ 없음 | ❌ 없음 | ⚠️ 있음 | ❌ 없음 |
| **동시 신청 충돌** | 직렬화 (대기) | 재시도 | 즉시 실패 | 직렬화 (대기) | 즉시 실패 |

---

## 🎯 권장 사항

### 🥇 **1순위: 비관적 락 (Pessimistic Lock)**

**선정 이유:**
1. ✅ **구현 가장 간단** - 메서드 하나만 추가
2. ✅ **스키마 변경 불필요** - 기존 테이블 그대로 사용
3. ✅ **데이터 정합성 완벽** - DB 레벨 보장
4. ✅ **기존 로직 유지** - 학점 체크 코드 변경 불필요
5. ✅ **정원 관리와 일관성** - 정원도 원자적 업데이트, 학생도 락으로 보호

**적용 조건:**
- 동일 학생의 동시 신청이 상대적으로 드문 경우 (피크 타임에도 학생당 평균 1~2건)
- 수강신청 기간이 제한적 (1주일 등)
- 현재 인프라로 충분한 경우

**성능 최적화:**
```java
// 락 타임아웃 설정으로 무한 대기 방지
@QueryHints(@QueryHint(name = "javax.persistence.lock.timeout", value = "3000"))
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Student> findByIdWithLock(@Param("id") Long id);

// 락 범위 최소화 - 학점 체크와 정원 감소만 락 내에서 수행
// 중복 체크, 시간표 충돌 체크는 락 획득 전에 수행 가능
```

---

### 🥈 **2순위: 원자적 업데이트 (Atomic Update)**

**선정 이유:**
1. ✅ **정원 관리와 동일한 패턴** - 학습 곡선 없음
2. ✅ **성능 우수** - 락 없이 원자적 처리
3. ✅ **일관된 아키텍처** - 모든 동시성 제어를 원자적 업데이트로 통일

**적용 조건:**
- 스키마 변경이 허용되는 경우
- 데이터 정합성 검증 배치 작업 운영 가능
- 장기적으로 확장성을 고려하는 경우

**주의 사항:**
- `current_credits`와 실제 학점 합계 불일치 발생 가능
- 트리거 또는 배치 작업으로 정합성 검증 필요
- 초기 마이그레이션 스크립트 작성 필요

---

### 🥉 **3순위: 낙관적 락 (Optimistic Lock)**

**선정 이유:**
- 동일 학생 동시 신청이 매우 드문 경우
- 재시도 로직 구현 가능
- 성능이 최우선인 경우

**적용 조건:**
- 충돌 빈도가 낮은 환경 (< 5%)
- 사용자에게 재시도 요청 가능
- 스키마 변경 허용

---

## 🧪 테스트 시나리오 추가 필요

### TC-005: 동일 학생 동시 신청 시 학점 초과 방지

```java
@Test
@DisplayName("TC-005: 동일 학생이 두 강좌에 동시 신청 시 학점 초과가 발생하지 않아야 한다")
void shouldPreventCreditExceededWhenSameStudentEnrollsConcurrently() throws Exception {
    // Given: 현재 15학점 수강 중인 학생
    Student student = createStudentWithEnrollments(15);  // 15학점 수강 중

    // 두 개의 3학점 강좌 (동시 신청 시 15 + 3 + 3 = 21학점 초과!)
    Course course1 = createCourse("알고리즘", 3, 30);
    Course course2 = createCourse("데이터베이스", 3, 30);

    // When: 동일 학생이 두 강좌에 동시 신청
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(2);

    AtomicReference<Result> result1 = new AtomicReference<>();
    AtomicReference<Result> result2 = new AtomicReference<>();

    ExecutorService executor = Executors.newFixedThreadPool(2);

    executor.submit(() -> {
        try {
            startLatch.await();
            EnrollmentResponse response = enrollmentService.enroll(
                new EnrollmentRequest(student.getId(), course1.getId())
            );
            result1.set(Result.success(response));
        } catch (CustomException ex) {
            result1.set(Result.failure(ex.getErrorCode()));
        } finally {
            doneLatch.countDown();
        }
    });

    executor.submit(() -> {
        try {
            startLatch.await();
            EnrollmentResponse response = enrollmentService.enroll(
                new EnrollmentRequest(student.getId(), course2.getId())
            );
            result2.set(Result.success(response));
        } catch (CustomException ex) {
            result2.set(Result.failure(ex.getErrorCode()));
        } finally {
            doneLatch.countDown();
        }
    });

    startLatch.countDown();  // 동시 시작
    doneLatch.await(10, TimeUnit.SECONDS);
    executor.shutdown();

    // Then: 둘 중 하나만 성공해야 한다
    long successCount = Stream.of(result1.get(), result2.get())
        .filter(Result::isSuccess)
        .count();

    long creditExceededCount = Stream.of(result1.get(), result2.get())
        .filter(r -> r.isFailure() && r.getErrorCode() == ErrorCode.CREDIT_LIMIT_EXCEEDED)
        .count();

    assertThat(successCount)
        .as("하나의 강좌만 신청 성공해야 한다")
        .isEqualTo(1);

    assertThat(creditExceededCount)
        .as("하나는 학점 초과로 실패해야 한다")
        .isEqualTo(1);

    // 최종 학점 검증: 15 + 3 = 18학점
    List<Enrollment> finalEnrollments = enrollmentRepository.findAllByStudentId(student.getId());
    int totalCredits = calculateTotalCredits(finalEnrollments);

    assertThat(totalCredits)
        .as("최종 학점이 18학점이어야 한다")
        .isEqualTo(18);

    assertThat(totalCredits)
        .as("최대 학점(18)을 초과하지 않아야 한다")
        .isLessThanOrEqualTo(MAX_CREDITS);
}
```

---

## 📝 구현 체크리스트

### 비관적 락 적용 시

- [ ] `StudentRepository`에 `findByIdWithLock()` 메서드 추가
- [ ] `EnrollmentService.enroll()`에서 `findById()` → `findByIdWithLock()` 변경
- [ ] 락 타임아웃 설정 (`@QueryHints`)
- [ ] TC-005 동시성 테스트 작성 및 실행
- [ ] 성능 테스트 (동일 학생 동시 신청 시나리오)
- [ ] 모니터링 설정 (락 대기 시간, 타임아웃 발생 빈도)

### 원자적 업데이트 적용 시

- [ ] Student 테이블에 `current_credits INT DEFAULT 0` 컬럼 추가
- [ ] Student 엔티티에 `currentCredits` 필드 추가
- [ ] `StudentRepository`에 `increaseCreditsIfWithinLimit()` 메서드 추가
- [ ] `StudentRepository`에 `decreaseCredits()` 메서드 추가
- [ ] `EnrollmentService.enroll()`에서 원자적 업데이트 호출
- [ ] `EnrollmentService.cancel()`에서 학점 감소 호출
- [ ] 보상 트랜잭션에 학점 복구 로직 추가
- [ ] 기존 데이터 마이그레이션 (currentCredits 계산)
- [ ] DB 트리거 생성 (선택)
- [ ] 정합성 검증 배치 작업 구현 (선택)
- [ ] TC-005 동시성 테스트 작성 및 실행

---

## 🔍 모니터링 지표

### 락 관련 지표 (비관적 락 사용 시)

```java
// AOP로 락 획득 시간 측정
@Aspect
@Component
public class LockMonitoringAspect {

    @Around("execution(* StudentRepository.findByIdWithLock(..))")
    public Object monitorLockAcquisition(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > 1000) {  // 1초 이상 대기
                log.warn("Student 락 획득 지연: {}ms, args={}", elapsed, joinPoint.getArgs());
            }
            // 메트릭 수집 (Prometheus, Micrometer 등)
            meterRegistry.timer("student.lock.acquisition.time").record(elapsed, TimeUnit.MILLISECONDS);
        }
    }
}
```

### 학점 초과 시도 지표

```java
// 학점 초과 실패 빈도 모니터링
@Around("execution(* EnrollmentService.enroll(..))")
public Object monitorCreditExceeded(ProceedingJoinPoint joinPoint) throws Throwable {
    try {
        return joinPoint.proceed();
    } catch (CustomException ex) {
        if (ex.getErrorCode() == ErrorCode.CREDIT_LIMIT_EXCEEDED) {
            meterRegistry.counter("enrollment.credit.exceeded").increment();
        }
        throw ex;
    }
}
```

---

## 📚 참고 자료

- [JPA Lock 전략](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#locking)
- [Pessimistic vs Optimistic Locking](https://vladmihalcea.com/optimistic-vs-pessimistic-locking/)
- [Database Concurrency Control](https://en.wikipedia.org/wiki/Concurrency_control)
- [Redisson Distributed Lock](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers)

---

**문서 버전**: 1.0
**최종 수정일**: 2026-02-21
**작성자**: 동시성 버그 해결 가이드
