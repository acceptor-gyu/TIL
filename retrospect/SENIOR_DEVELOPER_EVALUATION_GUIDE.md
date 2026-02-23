# 시니어 개발자 관점의 수강신청 시스템 평가 가이드

> 수강신청 시스템을 평가할 때 시니어 개발자가 중점적으로 확인하는 항목과 평가 방법을 정리한 문서입니다.

## 목차
1. [평가 우선순위](#평가-우선순위)
2. [평가 프로세스](#평가-프로세스)
3. [핵심 질문 리스트](#핵심-질문-리스트)
4. [레드 플래그](#레드-플래그)
5. [평가 기준표](#평가-기준표)

---

## 평가 우선순위

### Q1. 수강신청 시스템에서 가장 먼저 확인해야 할 부분은 무엇인가요?

<details>
<summary>답변 보기</summary>

**동시성 제어가 최우선입니다.**

수강신청 시스템의 핵심 난제는 동시 접속 상황에서 정원을 정확하게 관리하는 것입니다. 다음 항목을 중점적으로 확인합니다:

#### 확인 포인트
- ✅ **정원 초과 신청이 절대 발생하지 않는가?**
  - 100명이 동시에 마지막 1자리에 신청할 때의 동작
  - 트랜잭션 격리 수준과 락 전략의 적절성

- ✅ **Race Condition 처리 전략**
  - 비관적 락(Pessimistic Lock) vs 낙관적 락(Optimistic Lock) 선택 근거
  - `@Lock(LockModeType.PESSIMISTIC_WRITE)` 등의 사용 여부

- ✅ **트랜잭션 범위와 격리 수준**
  - `@Transactional`의 전파 속성과 격리 수준
  - 정원 체크와 신청 처리 사이의 원자성 보장

- ✅ **데드락 발생 가능성**
  - 락 획득 순서 일관성
  - 타임아웃 설정 및 재시도 전략

#### 실제 확인 코드 예시
```java
// EnrollmentService.enroll() 메서드에서 확인할 것
@Transactional
public EnrollmentResponse enroll(Long studentId, Long courseId) {
    // 1. 락 전략 확인
    Course course = courseRepository.findByIdWithLock(courseId);

    // 2. 정원 체크와 신청 처리 사이의 원자성
    if (course.isFull()) {
        throw new CourseFullException();
    }
    course.incrementEnrollmentCount(); // 이 사이에 다른 트랜잭션이 끼어들 수 없어야 함

    // 3. 저장
    Enrollment enrollment = new Enrollment(studentId, courseId);
    return enrollmentRepository.save(enrollment);
}
```

</details>

---

### Q2. 데이터 정합성은 어떻게 검증하나요?

<details>
<summary>답변 보기</summary>

**데이터 정합성은 다층 방어 전략으로 확인합니다.**

#### 확인 포인트

1. **중복 신청 방지**
   ```sql
   -- DB 제약조건 확인
   ALTER TABLE enrollment ADD CONSTRAINT uk_student_course
   UNIQUE (student_id, course_id);
   ```
   - DB 레벨의 unique 제약조건 존재 여부
   - 애플리케이션 레벨의 중복 체크 로직

2. **정원 증감의 일관성**
   ```java
   // 신청 시
   course.incrementEnrollmentCount();

   // 취소 시
   course.decrementEnrollmentCount();

   // 실제 신청자 수와 일치하는가?
   assert course.getCurrentEnrollment() == enrollmentRepository.countByCourseId(courseId);
   ```

3. **예외 발생 시 롤백 전략**
   ```java
   @Test
   void 신청_중_예외_발생시_정원이_증가하지_않는다() {
       // Given: 정원 30인 강의
       Course course = courseFixture.create(30);

       // When: 신청 중 예외 발생
       assertThatThrownBy(() -> {
           enrollmentService.enroll(studentId, courseId);
           throw new RuntimeException("예상치 못한 에러");
       });

       // Then: 정원은 여전히 0
       assertThat(course.getCurrentEnrollment()).isEqualTo(0);
   }
   ```

4. **외래키 제약조건**
   - Student, Course가 존재하지 않는 경우 처리
   - 삭제된 강의에 대한 신청 방지

</details>

---

### Q3. 아키텍처 품질은 어떤 기준으로 평가하나요?

<details>
<summary>답변 보기</summary>

**계층별 책임 분리와 도메인 모델의 풍부함을 중점적으로 봅니다.**

#### 올바른 계층 분리

```
✅ 바람직한 구조:

Controller
├─ 역할: HTTP 요청/응답 처리, DTO 변환
├─ 금지: 비즈니스 로직, 직접 Repository 호출
└─ 예시: @RequestBody 검증, ResponseEntity 생성

Service
├─ 역할: 비즈니스 로직, 트랜잭션 경계
├─ 금지: HTTP 관련 코드, SQL 직접 작성
└─ 예시: 정원 초과 검증, 중복 신청 체크

Repository
├─ 역할: 데이터 접근, 쿼리 최적화
├─ 금지: 비즈니스 로직
└─ 예시: findByIdWithLock, existsByStudentIdAndCourseId

Entity (Domain)
├─ 역할: 도메인 규칙, 상태 관리, 불변성 보장
├─ 금지: 빈약한 모델(getter/setter만 존재)
└─ 예시: isFull(), canEnroll(Student)
```

#### 안티 패턴 예시

```java
// ❌ Controller에서 비즈니스 로직 수행
@PostMapping("/enroll")
public ResponseEntity<?> enroll(@RequestBody EnrollRequest request) {
    Course course = courseRepository.findById(request.getCourseId());
    if (course.getCurrentEnrollment() >= course.getMaxCapacity()) { // 비즈니스 로직!
        return ResponseEntity.badRequest().build();
    }
    // ...
}

// ✅ Service로 위임
@PostMapping("/enroll")
public ResponseEntity<?> enroll(@RequestBody EnrollRequest request) {
    EnrollmentResponse response = enrollmentService.enroll(
        request.getStudentId(),
        request.getCourseId()
    );
    return ResponseEntity.ok(response);
}
```

#### 풍부한 도메인 모델

```java
// ❌ 빈약한 도메인 모델
public class Course {
    private Long id;
    private String name;
    private Integer currentEnrollment;
    private Integer maxCapacity;

    // getter/setter만 존재
}

// ✅ 풍부한 도메인 모델
public class Course {
    private Long id;
    private String name;
    private Capacity capacity; // Value Object

    public boolean isFull() {
        return capacity.isFull();
    }

    public void enroll() {
        if (isFull()) {
            throw new CourseFullException();
        }
        capacity.increment();
    }

    public boolean canEnroll(Student student) {
        return !isFull() && student.canEnrollMoreCourses();
    }
}
```

</details>

---

### Q4. 어떤 테스트가 반드시 있어야 하나요?

<details>
<summary>답변 보기</summary>

**동시성 테스트, 통합 테스트, 엣지 케이스 테스트가 필수입니다.**

#### 1. 동시성 테스트 (최우선)

```java
@Test
@DisplayName("동시에 100명이 정원 30인 강의에 신청할 때 정확히 30명만 성공한다")
void 동시_신청_시_정원_초과_불가() throws InterruptedException {
    // Given: 정원 30인 강의
    Course course = courseRepository.save(new Course("알고리즘", 30));
    int threadCount = 100;
    ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    // When: 100개 스레드 동시 신청
    for (int i = 0; i < threadCount; i++) {
        final Long studentId = (long) i;
        executorService.submit(() -> {
            try {
                enrollmentService.enroll(studentId, course.getId());
                successCount.incrementAndGet();
            } catch (CourseFullException e) {
                failCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await();
    executorService.shutdown();

    // Then: 정확히 30명 성공, 70명 실패
    assertThat(successCount.get()).isEqualTo(30);
    assertThat(failCount.get()).isEqualTo(70);

    // 실제 DB의 신청 건수도 30건
    assertThat(enrollmentRepository.countByCourseId(course.getId())).isEqualTo(30);
}
```

#### 2. 통합 테스트

```java
@SpringBootTest
@Transactional
class EnrollmentIntegrationTest {

    @Test
    @DisplayName("학생 신청 -> 취소 -> 재신청 전체 플로우가 정상 동작한다")
    void 신청_취소_재신청_플로우() {
        // Given
        Student student = studentRepository.save(new Student("홍길동"));
        Course course = courseRepository.save(new Course("자료구조", 30));

        // When: 신청
        EnrollmentResponse response1 = enrollmentService.enroll(student.getId(), course.getId());
        assertThat(response1.isSuccess()).isTrue();

        // When: 취소
        enrollmentService.cancel(student.getId(), course.getId());
        assertThat(enrollmentRepository.existsByStudentIdAndCourseId(student.getId(), course.getId()))
            .isFalse();

        // When: 재신청
        EnrollmentResponse response2 = enrollmentService.enroll(student.getId(), course.getId());
        assertThat(response2.isSuccess()).isTrue();
    }
}
```

#### 3. 엣지 케이스 테스트

```java
@Test
void 정원이_0인_강의는_신청할_수_없다() {
    Course course = new Course("특강", 0);
    assertThatThrownBy(() -> enrollmentService.enroll(studentId, course.getId()))
        .isInstanceOf(CourseFullException.class);
}

@Test
void 존재하지_않는_강의에_신청하면_예외가_발생한다() {
    assertThatThrownBy(() -> enrollmentService.enroll(studentId, 999L))
        .isInstanceOf(CourseNotFoundException.class);
}

@Test
void 이미_신청한_강의에_중복_신청하면_예외가_발생한다() {
    enrollmentService.enroll(studentId, courseId);

    assertThatThrownBy(() -> enrollmentService.enroll(studentId, courseId))
        .isInstanceOf(DuplicateEnrollmentException.class);
}
```

</details>

---

### Q5. 성능과 확장성은 어떻게 검증하나요?

<details>
<summary>답변 보기</summary>

**쿼리 최적화, 인덱스 전략, 캐싱 가능성을 확인합니다.**

#### 1. N+1 쿼리 문제

```java
// ❌ N+1 문제 발생
@GetMapping("/students/{id}/enrollments")
public List<EnrollmentResponse> getEnrollments(@PathVariable Long id) {
    List<Enrollment> enrollments = enrollmentRepository.findByStudentId(id);

    return enrollments.stream()
        .map(e -> new EnrollmentResponse(
            e.getId(),
            e.getCourse().getName(), // N번의 추가 쿼리 발생!
            e.getCourse().getInstructor()
        ))
        .collect(toList());
}

// ✅ Fetch Join으로 해결
@Query("SELECT e FROM Enrollment e JOIN FETCH e.course WHERE e.studentId = :studentId")
List<Enrollment> findByStudentIdWithCourse(@Param("studentId") Long studentId);
```

#### 2. 불필요한 쿼리 최적화

```java
// ❌ count 쿼리는 비용이 높음
long count = enrollmentRepository.countByStudentIdAndCourseId(studentId, courseId);
if (count > 0) {
    throw new DuplicateEnrollmentException();
}

// ✅ exists가 더 효율적
boolean exists = enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId);
if (exists) {
    throw new DuplicateEnrollmentException();
}
```

#### 3. 인덱스 전략

```sql
-- 필수 인덱스
CREATE INDEX idx_enrollment_student ON enrollment(student_id);
CREATE INDEX idx_enrollment_course ON enrollment(course_id);
CREATE UNIQUE INDEX uk_enrollment_student_course ON enrollment(student_id, course_id);

-- 복합 인덱스 고려
CREATE INDEX idx_course_status_capacity ON course(status, max_capacity);
```

#### 4. 페이징 처리

```java
// ❌ 전체 조회
@GetMapping("/courses")
public List<CourseResponse> getAllCourses() {
    return courseRepository.findAll(); // 수천 건 조회 시 메모리 부족
}

// ✅ 페이징
@GetMapping("/courses")
public Page<CourseResponse> getCourses(Pageable pageable) {
    return courseRepository.findAll(pageable);
}
```

#### 5. 캐싱 전략

```java
// 강의 정보는 자주 변경되지 않으므로 캐싱 가능
@Cacheable(value = "courses", key = "#id")
public Course getCourse(Long id) {
    return courseRepository.findById(id)
        .orElseThrow(() -> new CourseNotFoundException(id));
}

// 신청 시 캐시 무효화
@CacheEvict(value = "courses", key = "#courseId")
public EnrollmentResponse enroll(Long studentId, Long courseId) {
    // ...
}
```

</details>

---

## 평가 프로세스

### Q6. 실제로 코드를 평가할 때 어떤 순서로 진행하나요?

<details>
<summary>답변 보기</summary>

**4단계 프로세스로 체계적으로 진행합니다.**

#### Step 1: 코드 리뷰 (30분)

```
1. EnrollmentService.enroll() 메서드 정밀 분석
   └─ 트랜잭션 범위 확인
   └─ 락 전략 확인
   └─ 예외 처리 흐름 추적

2. Entity 관계와 제약조건 확인
   └─ ERD 파악
   └─ Foreign Key, Unique 제약조건
   └─ 연관관계 매핑 (OneToMany, ManyToOne)

3. Repository 인터페이스 확인
   └─ 커스텀 쿼리 검토
   └─ 락 모드 설정 여부
   └─ 인덱스 힌트 사용

4. 테스트 코드 커버리지 확인
   └─ 동시성 테스트 존재 여부
   └─ 통합 테스트 시나리오
   └─ 엣지 케이스 커버리지
```

#### Step 2: 동시성 시나리오 실행 (20분)

```bash
# 1. 기존 동시성 테스트 실행
./gradlew test --tests "*ConcurrencyTest"

# 2. 결과 확인
- 정원 초과 신청이 발생하는가?
- 데드락이 발생하는가?
- 응답 시간은 적절한가?

# 3. 부하 테스트 (선택)
# JMeter, Gatling 등으로 피크 타임 시뮬레이션
```

#### Step 3: 아키텍처 점검 (20분)

```
1. 패키지 구조 확인
   src/main/java/com/musinsa/
   ├── api (controller)
   ├── service
   ├── dbcore (entity, repository)
   └── common (dto, exception)

2. 의존성 방향 검증
   api → service → dbcore
   (역방향 의존 없는지 확인)

3. 순환 참조 검사
   ./gradlew dependencies | grep CIRCULAR

4. DTO 변환 위치
   - Controller에서 변환하는가?
   - Service에서 Entity를 직접 반환하지 않는가?

5. 도메인 규칙의 위치
   - Entity에 비즈니스 로직이 있는가?
   - Service가 단순 위임만 하지 않는가?
```

#### Step 4: 질문과 토론 (30분)

```
핵심 질문 리스트:

[동시성]
Q1. 비관적 락을 선택한 이유는? 낙관적 락 대비 장단점은?
Q2. 트랜잭션 격리 수준을 READ_COMMITTED로 설정한 근거는?
Q3. 데드락 발생 가능 시나리오와 대응 방안은?

[성능]
Q4. 피크 타임에 1000 TPS를 처리할 수 있는가?
Q5. 쿼리 실행 계획을 확인했는가? EXPLAIN 결과는?
Q6. 캐싱 도입을 고려했는가? 어떤 데이터를 캐싱할 것인가?

[확장성]
Q7. 데이터베이스를 스케일 아웃해야 한다면 어떻게 할 것인가?
Q8. 읽기/쓰기 분리(CQRS)를 고려했는가?
Q9. 이벤트 기반 아키텍처로 전환 가능한가?

[운영]
Q10. 모니터링과 알림은 어떻게 구성할 것인가?
Q11. 장애 발생 시 롤백 전략은?
Q12. 로그는 적절하게 남기고 있는가?
```

</details>

---

## 핵심 질문 리스트

### Q7. 평가 시 개발자에게 반드시 물어봐야 할 질문은 무엇인가요?

<details>
<summary>답변 보기</summary>

#### 동시성 관련 질문

**Q1. 정원 체크와 신청 처리 사이의 원자성을 어떻게 보장하나요?**
```
기대하는 답변:
- 비관적 락으로 Course 엔티티를 잠금
- 트랜잭션 내에서 정원 체크 → 증가 → 저장을 원자적으로 처리
- @Transactional의 격리 수준과 전파 속성 설정
```

**Q2. 100명이 동시에 마지막 1자리에 신청하면 어떻게 되나요?**
```
기대하는 답변:
- 1명만 성공하고 99명은 CourseFullException 발생
- 락 타임아웃이 발생할 수 있으며, 재시도 로직이 필요할 수 있음
- 실제 동시성 테스트로 검증했음
```

**Q3. 비관적 락 vs 낙관적 락 중 어떤 것을 선택했고, 그 이유는?**
```
기대하는 답변 (비관적 락):
- 정원이 거의 찬 상태에서 충돌이 빈번하게 발생
- 낙관적 락은 재시도가 많아 오히려 비효율적
- 데이터 일관성이 최우선이므로 비관적 락 선택

기대하는 답변 (낙관적 락):
- 대부분의 강의는 정원에 여유가 있어 충돌이 적음
- 읽기 성능이 중요한 경우
- version 필드로 충돌 감지 후 재시도
```

#### 데이터 정합성 관련 질문

**Q4. 중복 신청을 어떻게 방지하나요?**
```
기대하는 답변:
- DB: UNIQUE 제약조건 (student_id, course_id)
- 애플리케이션: existsByStudentIdAndCourseId() 체크
- 두 가지 방어선으로 이중 보호
```

**Q5. 신청 도중 예외가 발생하면 정원은 어떻게 되나요?**
```
기대하는 답변:
- @Transactional로 자동 롤백
- 정원 증가는 취소되고 원래 상태로 복구
- 롤백 테스트 코드로 검증
```

#### 성능 관련 질문

**Q6. 학생의 신청 내역을 조회할 때 N+1 쿼리 문제가 없나요?**
```
기대하는 답변:
- JOIN FETCH로 Course 정보를 한 번에 조회
- @EntityGraph 또는 @Query 사용
- 쿼리 로그로 확인 (hibernate.show_sql=true)
```

**Q7. 1000 TPS를 처리할 수 있나요? 부하 테스트를 했나요?**
```
기대하는 답변:
- JMeter/Gatling으로 부하 테스트 수행
- Connection Pool 크기 조정
- 쿼리 최적화 및 인덱스 추가
- 결과: 평균 응답 시간 200ms, 에러율 0%
```

#### 아키텍처 관련 질문

**Q8. Controller에서 직접 Repository를 호출하지 않는 이유는?**
```
기대하는 답변:
- 계층 분리 원칙 준수
- 트랜잭션 경계를 Service에서 관리
- 비즈니스 로직의 재사용성 향상
- 테스트 용이성 (Service 단위 테스트)
```

**Q9. Entity를 직접 반환하지 않고 DTO로 변환하는 이유는?**
```
기대하는 답변:
- Entity 변경이 API 응답에 영향을 주지 않도록
- 필요한 정보만 노출 (보안)
- 순환 참조 방지
- API 버전 관리 용이
```

</details>

---

## 레드 플래그

### Q8. 어떤 코드를 발견하면 즉시 지적해야 하나요?

<details>
<summary>답변 보기</summary>

**다음 코드 패턴은 즉시 수정이 필요합니다.**

#### 🚨 치명적 오류 (Critical)

**1. 정원 체크 후 커밋 발생**
```java
// ❌ 절대 안 됨!
public void enroll(Long studentId, Long courseId) {
    Course course = courseRepository.findById(courseId).get();

    if (course.getCurrentEnrollment() < course.getMaxCapacity()) {
        // 여기서 다른 트랜잭션이 끼어들 수 있음!
        courseRepository.save(course); // 커밋!

        // 이 사이에 정원이 초과될 수 있음
        Enrollment enrollment = new Enrollment(studentId, courseId);
        enrollmentRepository.save(enrollment);
    }
}

// ✅ 올바른 방법
@Transactional
public void enroll(Long studentId, Long courseId) {
    Course course = courseRepository.findByIdWithLock(courseId); // 락 획득

    if (!course.canEnroll()) {
        throw new CourseFullException();
    }

    course.enroll(); // 정원 증가
    enrollmentRepository.save(new Enrollment(studentId, courseId));
    // 메서드 종료 시 함께 커밋
}
```

**2. synchronized로 동시성 제어**
```java
// ❌ 단일 인스턴스에서만 동작 (스케일 아웃 시 무용지물)
private synchronized void enroll(Long studentId, Long courseId) {
    // 여러 서버에서 동시 실행 시 동시성 보장 안 됨!
}

// ✅ DB 락 사용
@Transactional
public void enroll(Long studentId, Long courseId) {
    Course course = courseRepository.findByIdWithPessimisticLock(courseId);
    // ...
}
```

**3. 트랜잭션 없이 정원 증감**
```java
// ❌ 위험!
public void enroll(Long studentId, Long courseId) { // @Transactional 없음!
    Course course = courseRepository.findById(courseId).get();
    course.incrementEnrollment();
    courseRepository.save(course);

    // 예외 발생 시 롤백 안 됨!
    enrollmentRepository.save(new Enrollment(studentId, courseId));
}
```

#### ⚠️ 높은 우선순위 (High)

**4. 동시성 테스트 부재**
```java
// ❌ 단순 테스트만 존재
@Test
void 신청_성공() {
    enrollmentService.enroll(1L, 1L);
    // 동시 접속 시나리오 없음!
}

// ✅ 필수: 동시성 테스트
@Test
void 동시에_100명이_신청해도_정원만큼만_성공() {
    // CountDownLatch, ExecutorService 사용
}
```

**5. unique 제약조건 없이 중복 방지**
```java
// ❌ 애플리케이션 로직만으로 중복 방지
if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
    throw new DuplicateEnrollmentException();
}
// 두 요청이 동시에 체크하면 둘 다 통과!

// ✅ DB 제약조건 추가 필수
@Table(uniqueConstraints = {
    @UniqueConstraint(columnNames = {"student_id", "course_id"})
})
```

**6. Service에서 Entity 직접 반환**
```java
// ❌ Entity 노출
public Course getCourse(Long id) {
    return courseRepository.findById(id).get();
}

// ✅ DTO 변환
public CourseResponse getCourse(Long id) {
    Course course = courseRepository.findById(id)
        .orElseThrow(() -> new CourseNotFoundException(id));
    return CourseResponse.from(course);
}
```

#### 📌 개선 권장 (Medium)

**7. N+1 쿼리 문제**
```java
// ❌ 비효율
List<Enrollment> enrollments = enrollmentRepository.findByStudentId(studentId);
enrollments.forEach(e -> {
    String courseName = e.getCourse().getName(); // N번 쿼리!
});

// ✅ Fetch Join
@Query("SELECT e FROM Enrollment e JOIN FETCH e.course WHERE e.studentId = :studentId")
```

**8. 페이징 없는 전체 조회**
```java
// ❌ 메모리 부족 위험
@GetMapping("/enrollments")
public List<Enrollment> getAll() {
    return enrollmentRepository.findAll(); // 수만 건 조회
}

// ✅ 페이징
@GetMapping("/enrollments")
public Page<Enrollment> getAll(Pageable pageable) {
    return enrollmentRepository.findAll(pageable);
}
```

**9. 매직 넘버 사용**
```java
// ❌ 의미 불명확
if (course.getMaxCapacity() > 100) {
    // ...
}

// ✅ 상수로 정의
private static final int LARGE_COURSE_THRESHOLD = 100;

if (course.getMaxCapacity() > LARGE_COURSE_THRESHOLD) {
    // ...
}
```

</details>

---

## 평가 기준표

### Q9. 정량적인 평가 기준은 무엇인가요?

<details>
<summary>답변 보기</summary>

#### 종합 평가표

| 영역 | 가중치 | 평가 항목 | 배점 | 평가 기준 |
|------|--------|-----------|------|-----------|
| **동시성** | 40% | 락 전략 | 15점 | 비관적/낙관적 락 적절성, 데드락 고려 |
| | | 트랜잭션 설계 | 15점 | 격리 수준, 전파 속성, 원자성 보장 |
| | | 동시성 테스트 | 10점 | 멀티스레드 테스트, 정원 초과 방지 검증 |
| **정합성** | 25% | 제약조건 | 10점 | Unique, FK 설정, DB 레벨 방어 |
| | | 예외 처리 | 8점 | 롤백 전략, 예외 계층, 의미 있는 메시지 |
| | | 데이터 일관성 | 7점 | 정원 증감 일관성, 실제 데이터 검증 |
| **아키텍처** | 20% | 계층 분리 | 8점 | Controller/Service/Repository 책임 |
| | | 도메인 모델 | 7점 | 풍부한 도메인, 비즈니스 로직 위치 |
| | | 확장성 | 5점 | 패키지 구조, 의존성 방향, 재사용성 |
| **테스트** | 10% | 커버리지 | 4점 | 라인 커버리지 80% 이상 |
| | | 시나리오 | 3점 | 통합 테스트, 엣지 케이스 |
| | | 품질 | 3점 | Given-When-Then, 명확한 검증 |
| **코드 품질** | 5% | 가독성 | 2점 | 네이밍, 주석, 복잡도 |
| | | 표준 준수 | 2점 | 코딩 컨벤션, 일관성 |
| | | 성능 | 1점 | 쿼리 최적화, 인덱스 |

#### 등급 기준

```
S등급 (90점 이상): 프로덕션 배포 가능
├─ 동시성 제어 완벽
├─ 동시성 테스트 포함
├─ 데이터 정합성 보장
└─ 확장 가능한 아키텍처

A등급 (80~89점): 일부 개선 후 배포 가능
├─ 핵심 기능 구현 완료
├─ 경미한 성능 이슈
└─ 테스트 커버리지 보완 필요

B등급 (70~79점): 상당한 개선 필요
├─ 동시성 처리 미흡
├─ 테스트 부족
└─ 아키텍처 재설계 검토

C등급 (60~69점): 재작성 권장
├─ 동시성 제어 누락
├─ 데이터 정합성 위험
└─ 기본 설계 오류

F등급 (60점 미만): 프로젝트 요구사항 미달성
```

#### 필수 통과 조건 (하나라도 미달 시 재평가)

```
✅ 동시성 테스트 존재 (정원 초과 방지 검증)
✅ 트랜잭션으로 원자성 보장
✅ DB 제약조건 설정 (unique, FK)
✅ 예외 발생 시 롤백 동작
✅ 계층 간 책임 분리
```

</details>

---

### Q10. 우수 사례와 미흡 사례의 차이는 무엇인가요?

<details>
<summary>답변 보기</summary>

#### 우수 사례 (S등급)

```java
/**
 * 강의 수강 신청 서비스
 * - 비관적 락으로 동시성 제어
 * - 트랜잭션으로 원자성 보장
 * - 풍부한 도메인 모델 활용
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;

    /**
     * 수강 신청
     * @throws CourseFullException 정원 초과
     * @throws DuplicateEnrollmentException 중복 신청
     * @throws CourseNotFoundException 강의 없음
     */
    @Transactional
    public EnrollmentResponse enroll(Long studentId, Long courseId) {
        // 1. 엔티티 조회 (비관적 락)
        Student student = studentRepository.findById(studentId)
            .orElseThrow(() -> new StudentNotFoundException(studentId));
        Course course = courseRepository.findByIdWithLock(courseId)
            .orElseThrow(() -> new CourseNotFoundException(courseId));

        // 2. 비즈니스 규칙 검증 (도메인 모델에 위임)
        if (!course.canEnroll(student)) {
            throw new CourseFullException(courseId);
        }

        // 3. 중복 신청 체크
        if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
            throw new DuplicateEnrollmentException(studentId, courseId);
        }

        // 4. 신청 처리 (도메인 모델이 정원 관리)
        course.enroll();
        Enrollment enrollment = Enrollment.of(student, course);
        enrollmentRepository.save(enrollment);

        // 5. DTO 변환 후 반환
        return EnrollmentResponse.from(enrollment);
    }
}

/**
 * 풍부한 도메인 모델
 */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Course {

    @Id @GeneratedValue
    private Long id;

    private String name;

    @Embedded
    private Capacity capacity; // Value Object로 캡슐화

    /**
     * 수강 신청 가능 여부
     */
    public boolean canEnroll(Student student) {
        return capacity.hasAvailableSlot()
            && student.canEnrollMoreCourses();
    }

    /**
     * 수강 신청 처리
     * @throws CourseFullException 정원 초과 시
     */
    public void enroll() {
        capacity.increment();
    }

    /**
     * 수강 취소 처리
     */
    public void cancel() {
        capacity.decrement();
    }
}

/**
 * 정원 Value Object
 */
@Embeddable
public class Capacity {

    private int current;
    private int maximum;

    public boolean hasAvailableSlot() {
        return current < maximum;
    }

    public void increment() {
        if (!hasAvailableSlot()) {
            throw new CourseFullException();
        }
        current++;
    }

    public void decrement() {
        if (current <= 0) {
            throw new IllegalStateException("정원이 이미 0입니다");
        }
        current--;
    }
}
```

#### 미흡 사례 (C등급)

```java
/**
 * 문제점:
 * 1. 동시성 제어 없음
 * 2. 트랜잭션 없음
 * 3. 빈약한 도메인 모델
 * 4. 계층 분리 위반
 */
@RestController
@RequiredArgsConstructor
public class EnrollmentController {

    private final EnrollmentRepository enrollmentRepository;
    private final CourseRepository courseRepository; // Controller가 직접 Repository 호출!

    @PostMapping("/enroll")
    public String enroll(@RequestParam Long studentId, @RequestParam Long courseId) {
        // 1. 동시성 제어 없음!
        Course course = courseRepository.findById(courseId).get();

        // 2. Controller에 비즈니스 로직!
        if (course.getCurrentEnrollment() >= course.getMaxCapacity()) {
            return "정원 초과"; // 예외 대신 문자열 반환
        }

        // 3. 트랜잭션 없음 - 정원 증가와 신청 처리가 원자적이지 않음!
        course.setCurrentEnrollment(course.getCurrentEnrollment() + 1);
        courseRepository.save(course);

        // 4. 예외 발생 시 정원은 이미 증가된 상태!
        Enrollment enrollment = new Enrollment();
        enrollment.setStudentId(studentId);
        enrollment.setCourseId(courseId);
        enrollmentRepository.save(enrollment);

        return "성공";
    }
}

/**
 * 빈약한 도메인 모델 (Anemic Domain Model)
 */
@Entity
public class Course {
    private Long id;
    private String name;
    private Integer currentEnrollment; // primitive 타입으로 노출
    private Integer maxCapacity;

    // getter/setter만 존재 - 비즈니스 로직 없음!
    public Integer getCurrentEnrollment() { return currentEnrollment; }
    public void setCurrentEnrollment(Integer value) { this.currentEnrollment = value; }
}
```

#### 차이점 요약

| 항목 | 우수 사례 | 미흡 사례 |
|------|-----------|-----------|
| **동시성** | 비관적 락, 트랜잭션 | 제어 없음 |
| **계층 분리** | Controller → Service → Repository | Controller → Repository 직접 |
| **도메인 모델** | canEnroll(), enroll() 메서드 | getter/setter만 |
| **예외 처리** | 명확한 도메인 예외 | 문자열 반환 |
| **트랜잭션** | @Transactional 명시 | 없음 (정합성 위험) |
| **DTO 변환** | Service에서 변환 | Entity 직접 노출 |
| **테스트** | 동시성/통합 테스트 | 단순 테스트만 |

</details>

---

## 마무리

### Q11. 평가를 마친 후에는 무엇을 하나요?

<details>
<summary>답변 보기</summary>

#### 1. 평가 리포트 작성

```markdown
# 수강신청 시스템 코드 리뷰 리포트

## 종합 평가
- 등급: B (76점)
- 프로덕션 배포: 개선 후 가능
- 핵심 이슈: 동시성 테스트 부재, N+1 쿼리

## 상세 평가

### 동시성 (28/40점)
✅ 비관적 락 적절히 사용
✅ 트랜잭션 격리 수준 적절
❌ 동시성 테스트 없음 (-10점)
⚠️  데드락 처리 전략 미흡 (-2점)

### 데이터 정합성 (22/25점)
✅ Unique 제약조건 설정
✅ 예외 처리 체계적
⚠️  롤백 테스트 부족 (-3점)

### 아키텍처 (18/20점)
✅ 계층 분리 명확
✅ DTO 변환 적절
⚠️  일부 Service 로직이 과도하게 비대 (-2점)

### 테스트 (5/10점)
❌ 동시성 테스트 없음 (-3점)
⚠️  통합 테스트 시나리오 부족 (-2점)

### 코드 품질 (3/5점)
✅ 네이밍 일관성
⚠️  주석 부족 (-2점)

## 필수 개선 사항 (P0)
1. 동시성 테스트 추가
2. N+1 쿼리 해결 (JOIN FETCH)
3. 데드락 처리 전략 수립

## 권장 개선 사항 (P1)
4. 통합 테스트 시나리오 확대
5. 캐싱 도입 검토
6. 모니터링 및 로깅 강화
```

#### 2. 개선 액션 플랜 제시

```markdown
## 개선 로드맵

### Phase 1: 필수 개선 (1주)
- [ ] 동시성 테스트 작성
  - CountDownLatch 활용
  - 100명 동시 신청 시나리오
  - 정원 초과 방지 검증

- [ ] N+1 쿼리 해결
  - JOIN FETCH 적용
  - 쿼리 로그 확인
  - 성능 측정 (before/after)

- [ ] 데드락 처리
  - 락 획득 순서 정의
  - 타임아웃 설정
  - 재시도 로직 추가

### Phase 2: 성능 개선 (1주)
- [ ] 인덱스 최적화
- [ ] 쿼리 튜닝
- [ ] Connection Pool 조정

### Phase 3: 운영 준비 (1주)
- [ ] 모니터링 대시보드
- [ ] 알림 설정
- [ ] 로그 전략 수립
```

#### 3. 후속 조치

```
1. 개발자와 1:1 미팅
   - 평가 결과 공유
   - 기술적 질문 답변
   - 개선 방향 논의

2. 코드 리뷰 세션
   - 우수 사례 공유
   - 안티 패턴 설명
   - 모범 코드 시연

3. 멘토링 제공
   - 동시성 개념 설명
   - 테스트 작성 실습
   - 아키텍처 설계 가이드
```

</details>

---

## 부록

### 참고 자료

- [Java Concurrency in Practice](https://jcip.net/)
- [Spring Data JPA Lock 전략](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#locking)
- [DDD Aggregate Pattern](https://martinfowler.com/bliki/DDD_Aggregate.html)
- [Test Double 패턴](https://martinfowler.com/bliki/TestDouble.html)

### 체크리스트

```markdown
## 평가 전 준비 체크리스트

- [ ] 프로젝트 README 확인
- [ ] 빌드 및 테스트 실행
- [ ] ERD 및 아키텍처 다이어그램 검토
- [ ] 요구사항 문서 숙지

## 평가 중 체크리스트

- [ ] EnrollmentService 코드 리뷰
- [ ] 동시성 테스트 실행
- [ ] 통합 테스트 커버리지 확인
- [ ] 쿼리 실행 계획 확인
- [ ] 개발자 질의응답

## 평가 후 체크리스트

- [ ] 평가 리포트 작성
- [ ] 개선 액션 플랜 제시
- [ ] 1:1 피드백 세션 진행
```

---

**문서 버전**: 1.0
**최종 수정일**: 2026-02-21
**작성자**: 시니어 개발자 평가 가이드
