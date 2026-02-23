# 비관적 락 적용 시 데드락 분석

> 학점 제한 동시성 제어 + 정원 초과 방지를 **모두 비관적 락(Pessimistic Lock)**으로 구현할 때 발생 가능한 데드락 시나리오를 분석한다.

## 관련 문서

- [학점 제한 동시성 버그 해결 방안](./CONCURRENCY_BUG_CREDIT_LIMIT_SOLUTION.md)
- [정원 초과 방지 동시성 제어 구현 분석](./CAPACITY_CONTROL_IMPLEMENTATION.md)

---

## 비관적 락 적용 구현 (가정)

### 변경 사항 요약

기존의 원자적 업데이트(정원) + Check-Then-Act(학점) 방식 대신, **두 리소스 모두 비관적 락**으로 보호하는 시나리오를 가정한다.

```java
// StudentRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Student s WHERE s.id = :id")
Optional<Student> findByIdWithLock(@Param("id") Long id);

// CourseRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM Course c WHERE c.id = :id")
Optional<Course> findByIdWithLock(@Param("id") Long id);
```

```java
// EnrollmentService.java (비관적 락 적용 버전)
@Transactional
public EnrollmentResponse enroll(EnrollmentRequest request) {
    // Step 1: Student 락 획득 (SELECT ... FOR UPDATE)
    Student student = studentRepository.findByIdWithLock(request.getStudentId())
            .orElseThrow(() -> new CustomException(ErrorCode.STUDENT_NOT_FOUND));

    // Step 2: Course 락 획득 (SELECT ... FOR UPDATE)
    Course course = courseRepository.findByIdWithLock(request.getCourseId())
            .orElseThrow(() -> new CustomException(ErrorCode.COURSE_NOT_FOUND));

    // Step 3: 학점 체크
    // Step 4: 정원 체크 & 감소
    // Step 5: INSERT enrollment
    // Step 6: COMMIT → 모든 락 해제
}
```

---

## 데드락 시나리오

### 시나리오 1: 두 학생이 서로 다른 순서로 두 강좌를 신청

> **발생 조건**: 학점 체크를 위해 Student 락을 먼저 잡고, 정원 체크를 위해 Course 락을 잡는 구조에서,
> 두 학생이 **서로 다른 두 강좌를 교차 신청**할 때 발생한다.

#### 전제 조건

```
- 학생 A (id=1), 학생 B (id=2)
- 강좌 C1 (id=10), 강좌 C2 (id=20)
- 두 학생 모두 학점 여유 있음, 두 강좌 모두 정원 여유 있음
```

#### 데드락 발생 흐름

```
T1 (학생 A → 강좌 C1 신청)         T2 (학생 B → 강좌 C2 신청)
──────────────────────────────────────────────────────────────
Lock Student(id=1)                 Lock Student(id=2)
→ Student A 락 획득 ✅              → Student B 락 획득 ✅

Lock Course(id=10)                 Lock Course(id=20)
→ Course C1 락 획득 ✅              → Course C2 락 획득 ✅

학점 체크 OK                        학점 체크 OK
정원 감소 OK                        정원 감소 OK
INSERT enrollment(A, C1)           INSERT enrollment(B, C2)
COMMIT → 모든 락 해제               COMMIT → 모든 락 해제
```

**이 경우는 데드락이 발생하지 않는다.** 서로 다른 Student, 서로 다른 Course에 락을 걸기 때문이다.

---

### 시나리오 2: 두 학생이 동일 강좌를 신청 (정상 직렬화)

```
T1 (학생 A → 강좌 C1 신청)         T2 (학생 B → 강좌 C1 신청)
──────────────────────────────────────────────────────────────
Lock Student(id=1) ✅               Lock Student(id=2) ✅
Lock Course(id=10) ✅               Lock Course(id=10) ⏳ (T1이 점유 중, 대기)

학점 체크 OK
정원 감소 OK
INSERT enrollment(A, C1)
COMMIT → Course(10) 락 해제
                                   Lock Course(id=10) ✅ (획득)
                                   학점 체크 OK
                                   정원 감소 OK
                                   INSERT enrollment(B, C1)
                                   COMMIT
```

**데드락 없음.** Course 락에서 직렬화되지만 순서대로 처리된다.

---

### 시나리오 3: 동일 학생이 두 강좌를 동시 신청 (정상 직렬화)

```
T1 (학생 A → 강좌 C1 신청)         T2 (학생 A → 강좌 C2 신청)
──────────────────────────────────────────────────────────────
Lock Student(id=1) ✅               Lock Student(id=1) ⏳ (T1이 점유 중, 대기)

Lock Course(id=10) ✅
학점 체크 OK
정원 감소 OK
INSERT enrollment(A, C1)
COMMIT → Student(1) 락 해제
                                   Lock Student(id=1) ✅ (획득)
                                   Lock Course(id=20) ✅
                                   학점 체크: 갱신된 학점 반영 ✅
                                   정원 감소 OK
                                   INSERT enrollment(A, C2)
                                   COMMIT
```

**데드락 없음.** 이것이 바로 비관적 락의 핵심 목적이다. Student 락에서 직렬화되어 학점 초과를 방지한다.

---

### 시나리오 4: 수강신청 + 수강취소 교차 (데드락 발생!)

> **핵심 데드락 시나리오**: 수강신청과 수강취소가 **락 획득 순서가 다를 때** 발생한다.

#### 전제 조건

```
- 학생 A (id=1)
- 강좌 C1 (id=10): 학생 A가 이미 수강 중
- 강좌 C2 (id=20): 학생 A가 새로 신청하려는 강좌
```

#### 수강취소에도 비관적 락을 적용한 경우

```java
// cancel()에서도 정원 복구를 위해 Course 락, 학점 복구를 위해 Student 락을 사용한다고 가정
@Transactional
public EnrollmentCancelResponse cancel(EnrollmentCancelRequest request) {
    // Step 1: Course 락 획득 (정원 복구를 위해)
    Course course = courseRepository.findByIdWithLock(request.getCourseId())
            .orElseThrow(() -> new CustomException(ErrorCode.COURSE_NOT_FOUND));

    // Step 2: Student 락 획득 (학점 복구를 위해)
    Student student = studentRepository.findByIdWithLock(request.getStudentId())
            .orElseThrow(() -> new CustomException(ErrorCode.STUDENT_NOT_FOUND));

    // Step 3: DELETE enrollment
    // Step 4: 정원 증가
    // Step 5: COMMIT → 모든 락 해제
}
```

#### 데드락 발생 흐름

```
T1 (학생 A → 강좌 C2 수강신청)      T2 (학생 A → 강좌 C1 수강취소)
──────────────────────────────────────────────────────────────────
[enroll: Student 먼저]              [cancel: Course 먼저]

Lock Student(id=1) ✅               Lock Course(id=10) ✅

Lock Course(id=20) ✅               Lock Student(id=1) ⏳
                                   → T1이 Student(1) 점유 중, 대기

학점 체크 중...
→ 기존 수강 강좌 조회를 위해
  Course(id=10) 정보 필요

courseRepository.findAllById(courseIds)
→ Course(id=10)에 접근 시도

⚠️ Course(id=10)은 T2가 점유 중!
→ T1도 대기 시작

┌────────────────────────────────────────────────┐
│            💀 데드락 발생!                        │
│                                                │
│  T1: Student(1) 보유 → Course(10) 대기          │
│  T2: Course(10) 보유 → Student(1) 대기          │
│                                                │
│  서로 상대방의 락을 기다리며 무한 대기             │
└────────────────────────────────────────────────┘
```

#### 왜 발생하는가?

| | enroll() | cancel() |
|---|---|---|
| **1번째 락** | Student (id=1) | Course (id=10) |
| **2번째 락** | Course (id=20), 그러나 학점 체크 중 Course(id=10) 접근 | Student (id=1) |

**락 획득 순서가 다르다:**
- `enroll()`: Student → Course
- `cancel()`: Course → Student

이것이 데드락의 근본 원인이다. **순환 대기(Circular Wait)**가 형성된다.

---

### 시나리오 5: 두 학생이 서로의 수강 강좌를 신청 (데드락 발생!)

> **발생 조건**: 학점 체크 과정에서 이미 수강 중인 강좌들의 정보를 조회할 때, 해당 강좌에 다른 트랜잭션이 비관적 락을 걸고 있으면 데드락이 발생할 수 있다.

#### 전제 조건

```
- 학생 A (id=1): 강좌 C1 (id=10) 수강 중
- 학생 B (id=2): 강좌 C2 (id=20) 수강 중
- 학생 A가 강좌 C2를 신청, 학생 B가 강좌 C1을 신청 (교차 신청)
```

#### 데드락 발생 흐름

```
T1 (학생 A → 강좌 C2 신청)              T2 (학생 B → 강좌 C1 신청)
──────────────────────────────────────────────────────────────────────
Lock Student(id=1) ✅                    Lock Student(id=2) ✅
Lock Course(id=20) ✅                    Lock Course(id=10) ✅

학점 체크: 기존 수강 강좌 조회             학점 체크: 기존 수강 강좌 조회
→ findAllByStudentId(1)                  → findAllByStudentId(2)
→ courseIds = [10]                       → courseIds = [20]
→ courseRepository.findAllById([10])      → courseRepository.findAllById([20])

⚠️ Course(id=10)은 T2가 FOR UPDATE     ⚠️ Course(id=20)은 T1이 FOR UPDATE
   락으로 점유 중!                          락으로 점유 중!
→ T1 대기                               → T2 대기

┌────────────────────────────────────────────────┐
│            💀 데드락 발생!                        │
│                                                │
│  T1: Course(20) 보유 → Course(10) 대기          │
│  T2: Course(10) 보유 → Course(20) 대기          │
│                                                │
│  서로 상대방이 잡은 강좌 락을 기다리며 무한 대기    │
└────────────────────────────────────────────────┘
```

#### 주의

이 시나리오는 `courseRepository.findAllById()`가 **일반 SELECT**이면 발생하지 않는다. 하지만 다음 상황에서 문제가 될 수 있다:

1. **REPEATABLE_READ 이상의 격리 수준**: InnoDB의 Next-Key Lock으로 인해 SELECT도 락 충돌 가능
2. **Course에 대한 findByIdWithLock() 사용**: 학점 계산 중에도 Course 락을 사용하는 경우
3. **JPA 1차 캐시와 FOR UPDATE 쿼리 혼용**: 이미 캐시된 엔티티와 락 쿼리 간의 충돌

---

### 시나리오 6: 여러 엔티티에 대한 비관적 락 순서 불일치 (데드락 발생!)

> **가장 전형적인 데드락**: 복수의 Course에 비관적 락을 걸 때, 락 획득 순서가 트랜잭션마다 다르면 발생한다.

#### 전제 조건

```
- 학생 A (id=1): 강좌 C1, C2, C3 수강 중 → 강좌 C4 신청
- 학생 B (id=2): 강좌 C4, C5, C6 수강 중 → 강좌 C1 신청
- 학점 검증을 위해 기존 수강 강좌를 모두 비관적 락으로 조회한다고 가정
```

#### 만약 학점 검증에서 기존 수강 강좌도 락을 건다면

```java
// 위험한 구현: 학점 검증 시 기존 수강 강좌에도 FOR UPDATE
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM Course c WHERE c.id IN :ids")
List<Course> findAllByIdWithLock(@Param("ids") List<Long> ids);
```

```
T1 (학생 A → C4 신청)                   T2 (학생 B → C1 신청)
──────────────────────────────────────────────────────────────
Lock Student(1) ✅                       Lock Student(2) ✅
Lock Course(4) ✅ (신청 대상)             Lock Course(1) ✅ (신청 대상)

학점 검증: 기존 수강 강좌 락 획득          학점 검증: 기존 수강 강좌 락 획득
findAllByIdWithLock([1, 2, 3])           findAllByIdWithLock([4, 5, 6])

Lock Course(1) ⏳ T2가 점유 중!           Lock Course(4) ⏳ T1이 점유 중!

┌────────────────────────────────────────────────┐
│            💀 데드락 발생!                        │
│                                                │
│  T1: Course(4) 보유 → Course(1) 대기            │
│  T2: Course(1) 보유 → Course(4) 대기            │
└────────────────────────────────────────────────┘
```

---

## 데드락 발생 조건 정리

데드락은 다음 **4가지 조건이 모두 충족**될 때 발생한다 (Coffman 조건):

| 조건 | 설명 | 비관적 락에서의 해당 여부 |
|------|------|--------------------------|
| **상호 배제 (Mutual Exclusion)** | 자원을 한 번에 하나의 트랜잭션만 사용 | `FOR UPDATE`는 배타적 락 → **해당** |
| **점유 대기 (Hold and Wait)** | 자원을 점유한 채 다른 자원을 대기 | Student 락 보유 + Course 락 대기 → **해당** |
| **비선점 (No Preemption)** | 다른 트랜잭션의 락을 강제 해제 불가 | DB 락은 COMMIT/ROLLBACK까지 유지 → **해당** |
| **순환 대기 (Circular Wait)** | 트랜잭션들이 순환 형태로 서로 대기 | 시나리오 4, 5, 6에서 발생 → **해당** |

---

## 데드락 방지 전략

### 전략 1: 락 획득 순서 통일 (Lock Ordering)

> **가장 근본적인 해결책**: 모든 트랜잭션에서 락 획득 순서를 동일하게 강제한다.

#### 규칙 정의

```
항상 Student → Course 순서로 락을 획득한다.
복수의 Course에 락을 걸 때는 Course ID 오름차순으로 획득한다.
```

#### 적용 예시

```java
// enroll(): Student 먼저, Course 나중 ✅
public EnrollmentResponse enroll(EnrollmentRequest request) {
    Student student = studentRepository.findByIdWithLock(request.getStudentId());
    Course course = courseRepository.findByIdWithLock(request.getCourseId());
    // ...
}

// cancel(): Student 먼저, Course 나중 ✅ (순서 통일!)
public EnrollmentCancelResponse cancel(EnrollmentCancelRequest request) {
    Student student = studentRepository.findByIdWithLock(request.getStudentId());
    Course course = courseRepository.findByIdWithLock(request.getCourseId());
    // DELETE enrollment, 정원 복구, ...
}
```

#### 시나리오 4 재검증 (락 순서 통일 후)

```
T1 (학생 A → 강좌 C2 수강신청)      T2 (학생 A → 강좌 C1 수강취소)
──────────────────────────────────────────────────────────────────
Lock Student(id=1) ✅               Lock Student(id=1) ⏳ (T1이 점유 중)

Lock Course(id=20) ✅
학점 체크 OK
정원 감소 OK
INSERT enrollment(A, C2)
COMMIT → Student(1) 락 해제
                                   Lock Student(id=1) ✅ (획득)
                                   Lock Course(id=10) ✅
                                   DELETE enrollment(A, C1)
                                   정원 복구
                                   COMMIT

✅ 데드락 없음! Student 락에서 직렬화됨.
```

#### 한계

- 학점 체크 시 기존 수강 강좌를 조회하는 `findAllById()`가 **일반 SELECT**여야 한다.
- 기존 수강 강좌에 FOR UPDATE를 걸면 시나리오 5, 6의 데드락은 여전히 발생 가능하다.

---

### 전략 2: 락 범위 최소화

> Student에만 비관적 락을 걸고, Course는 기존 원자적 업데이트를 유지한다.

#### 구현

```java
@Transactional
public EnrollmentResponse enroll(EnrollmentRequest request) {
    // Student만 비관적 락 (학점 제한 동시성 제어)
    Student student = studentRepository.findByIdWithLock(request.getStudentId())
            .orElseThrow(() -> new CustomException(ErrorCode.STUDENT_NOT_FOUND));

    // Course는 일반 SELECT (락 없음)
    Course course = courseRepository.findById(request.getCourseId())
            .orElseThrow(() -> new CustomException(ErrorCode.COURSE_NOT_FOUND));

    // 학점 체크 (Student 락이 보호)
    // 시간표 충돌 체크

    // 정원 감소 (원자적 업데이트 - 기존 방식 유지)
    int updated = courseRepository.decreaseSeatIfAvailable(course.getId());
    if (updated == 0) {
        throw new CustomException(ErrorCode.CAPACITY_FULL);
    }

    // INSERT enrollment
    // ...
}
```

#### 장점

```
┌────────────────────────────────────────────────────────┐
│ 비관적 락: Student만                                     │
│ → 동일 학생의 동시 신청을 직렬화 (학점 초과 방지)         │
│                                                        │
│ 원자적 업데이트: Course의 seatsLeft                      │
│ → 정원 초과 방지 (기존 검증된 방식)                       │
│                                                        │
│ 결과: 락이 하나의 엔티티(Student)에만 걸리므로            │
│       순환 대기가 원천적으로 불가능 → 데드락 없음!         │
└────────────────────────────────────────────────────────┘
```

#### 이 조합이 데드락-프리인 이유

| 항목 | 설명 |
|------|------|
| 상호 배제 | Student FOR UPDATE는 배타적 → **해당** |
| 점유 대기 | Student 락 보유 + Course 원자적 UPDATE → **해당하지 않음** (Course 락을 "점유"하지 않음) |
| 비선점 | Student 락은 COMMIT까지 유지 → **해당** |
| 순환 대기 | Student 락만 존재, Course는 Row Lock이 UPDATE 실행 순간에만 유지 → **순환 불가** |

Coffman 조건 4가지 중 **점유 대기**와 **순환 대기**가 깨지므로 데드락이 발생하지 않는다.

---

### 전략 3: 락 타임아웃 설정

> 데드락을 완전히 방지하지는 못하지만, 데드락 발생 시 무한 대기를 방지한다.

```java
@QueryHints(@QueryHint(
    name = "javax.persistence.lock.timeout",
    value = "3000"  // 3초 타임아웃
))
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Student s WHERE s.id = :id")
Optional<Student> findByIdWithLock(@Param("id") Long id);
```

```java
// 타임아웃 예외 처리
try {
    Student student = studentRepository.findByIdWithLock(request.getStudentId())
            .orElseThrow(() -> new CustomException(ErrorCode.STUDENT_NOT_FOUND));
} catch (PessimisticLockingFailureException ex) {
    log.warn("락 획득 타임아웃: studentId={}", request.getStudentId());
    throw new CustomException(ErrorCode.LOCK_TIMEOUT);
}
```

#### 주의: H2 Database의 타임아웃 지원

```
H2 Database는 javax.persistence.lock.timeout 힌트를 지원하지 않는다.
테스트 환경(H2)과 운영 환경(MySQL/PostgreSQL)의 동작이 다를 수 있다.

- MySQL: innodb_lock_wait_timeout (기본 50초)
- PostgreSQL: lock_timeout (기본 0, 무한 대기)
- H2: 타임아웃 힌트 무시, DB 레벨 LOCK_TIMEOUT 설정 필요
```

---

## 권장 조합

### Student 비관적 락 + Course 원자적 업데이트 (데드락 없음)

```
┌────────────────────────────────────────────────────────────────┐
│                     권장 구현 전략                                │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  학점 제한 동시성 제어:                                          │
│  ┌─────────────────────────────────────────────┐               │
│  │  Student에 비관적 락 (FOR UPDATE)              │               │
│  │  → 동일 학생의 동시 신청을 직렬화               │               │
│  │  → 학점 체크가 항상 최신 상태 보장              │               │
│  └─────────────────────────────────────────────┘               │
│                                                                │
│  정원 초과 방지:                                                │
│  ┌─────────────────────────────────────────────┐               │
│  │  Course의 seatsLeft 원자적 업데이트 (기존 유지)  │               │
│  │  → UPDATE ... SET seatsLeft - 1               │               │
│  │    WHERE seatsLeft > 0                        │               │
│  │  → DB Row Lock이 UPDATE 순간에만 유지          │               │
│  └─────────────────────────────────────────────┘               │
│                                                                │
│  데드락 위험: ❌ 없음                                            │
│  이유: 장기 보유 락이 Student 하나뿐                              │
│        Course는 원자적 UPDATE로 즉시 완료                        │
│        순환 대기 구조가 형성될 수 없음                             │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### enroll() 흐름

```
1. Student FOR UPDATE 획득           ← 비관적 락 (학점 보호)
2. Course 일반 SELECT                ← 락 없음
3. 중복 신청 체크                     ← 일반 SELECT
4. 학점 체크 (기존 수강 강좌 조회)     ← 일반 SELECT (Student 락이 보호)
5. 시간표 충돌 체크                   ← 일반 SELECT
6. 정원 감소 (원자적 UPDATE)          ← DB Row Lock (즉시 완료)
7. INSERT enrollment
8. COMMIT → Student 락 해제
```

### cancel() 흐름

```
1. Student FOR UPDATE 획득           ← 비관적 락 (순서 통일!)
2. DELETE enrollment
3. 정원 복구 (UPDATE seatsLeft + 1)   ← 원자적 UPDATE
4. COMMIT → Student 락 해제
```

---

## 데드락 시나리오 요약

| 시나리오 | 설명 | 데드락 발생 | 원인 |
|----------|------|-------------|------|
| #1 | 두 학생이 서로 다른 강좌 신청 | ❌ 없음 | 서로 다른 리소스에 락 |
| #2 | 두 학생이 동일 강좌 신청 | ❌ 없음 | Course 락에서 직렬화 |
| #3 | 동일 학생이 두 강좌 동시 신청 | ❌ 없음 | Student 락에서 직렬화 |
| **#4** | **수강신청 + 수강취소 교차** | **✅ 발생** | **락 순서 불일치 (Student→Course vs Course→Student)** |
| **#5** | **두 학생이 교차 강좌 신청 (Course 락 사용 시)** | **✅ 발생** | **Course 간 순환 대기** |
| **#6** | **복수 Course에 비관적 락 (학점 검증)** | **✅ 발생** | **Course ID 순서 불일치** |

### 핵심 교훈

1. **비관적 락을 2개 이상의 엔티티에 적용하면 데드락 위험이 급격히 증가한다.**
2. **락 획득 순서가 트랜잭션마다 다르면 순환 대기가 형성된다.**
3. **Student 비관적 락 + Course 원자적 업데이트 조합이 데드락을 원천 차단하는 최적의 전략이다.**

---

**문서 버전**: 1.0
**최종 수정일**: 2026-02-23
**작성자**: 비관적 락 데드락 분석 가이드
