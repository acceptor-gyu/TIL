# 정원 초과 방지 동시성 제어 구현 분석

> 현재 프로젝트에서 수강신청 시 정원보다 많은 인원이 신청되는 것을 방지하기 위한 동시성 제어 로직 분석 문서

## 📌 목차

1. [핵심 전략 요약](#핵심-전략-요약)
2. [구현 상세 분석](#구현-상세-분석)
3. [동작 흐름](#동작-흐름)
4. [동시성 보장 메커니즘](#동시성-보장-메커니즘)
5. [보상 트랜잭션](#보상-트랜잭션)
6. [실제 시나리오 분석](#실제-시나리오-분석)
7. [장단점 평가](#장단점-평가)

---

## 핵심 전략 요약

### 전략 이름
**원자적 조건부 업데이트 (Atomic Conditional Update)**

### 핵심 원리
```
정원 체크(WHERE seatsLeft > 0) + 정원 감소(SET seatsLeft = seatsLeft - 1)
→ 하나의 원자적 UPDATE 쿼리로 처리
```

### 구현 위치
- **Repository**: `CourseRepository.decreaseSeatIfAvailable()`
- **Service**: `EnrollmentService.enroll()`
- **보상 로직**: `EnrollmentService.recoverSeat()`

---

## 구현 상세 분석

### 1. 핵심 메서드: `decreaseSeatIfAvailable()`

#### 파일 위치
`src/main/java/com/musinsa/dbcore/repository/CourseRepository.java`

#### 코드
```java
@Modifying
@Query("update Course c set c.seatsLeft = c.seatsLeft - 1
        where c.id = :id and c.seatsLeft > 0")
int decreaseSeatIfAvailable(@Param("id") Long id);
```

#### 동작 원리

**SQL로 변환 시:**
```sql
UPDATE course
SET seats_left = seats_left - 1
WHERE id = ? AND seats_left > 0
```

**핵심 포인트:**

1. **원자성 (Atomicity)**
   - `WHERE seatsLeft > 0` 조건 체크와 `SET seatsLeft - 1` 감소가 **하나의 SQL 문**
   - DB 엔진이 원자적으로 실행 (중간에 끼어들 수 없음)

2. **조건부 업데이트 (Conditional Update)**
   - 정원이 남아있을 때만 (`seatsLeft > 0`) 업데이트 실행
   - 정원이 없으면 `0건 업데이트` (아무것도 변경 안 됨)

3. **반환값 활용**
   - `int` 반환: 업데이트된 행 수
   - `1`: 정원 감소 성공 (좌석 있음)
   - `0`: 정원 감소 실패 (좌석 없음)

---

### 2. 서비스 레이어: `EnrollmentService.enroll()`

#### 파일 위치
`src/main/java/com/musinsa/api/service/EnrollmentService.java`

#### 전체 흐름

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public EnrollmentResponse enroll(EnrollmentRequest request) {
    // [1] 학생 조회
    Student student = studentRepository.findById(request.getStudentId())
            .orElseThrow(() -> new CustomException(ErrorCode.STUDENT_NOT_FOUND));

    // [2] 강좌 조회
    Course course = courseRepository.findById(request.getCourseId())
            .orElseThrow(() -> new CustomException(ErrorCode.COURSE_NOT_FOUND));

    // [3] 중복 신청 체크
    if (enrollmentRepository.existsByStudentIdAndCourseId(student.getId(), course.getId())) {
        throw new CustomException(ErrorCode.DUPLICATE_ENROLLMENT);
    }

    // [4] 학점 제한 체크 (MAX_CREDITS: 18)
    // ... (학점 계산 로직)

    // [5] 시간표 충돌 체크
    // ... (시간표 비교 로직)

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // [6] 정원 감소 (동시성 제어 핵심!)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    int updated = courseRepository.decreaseSeatIfAvailable(course.getId());
    if (updated == 0) {
        log.warn("수강신청 실패: 정원 초과 studentId={}, courseId={}",
                 student.getId(), course.getId());
        throw new CustomException(ErrorCode.CAPACITY_FULL);
    }

    // [7] Enrollment 레코드 생성
    try {
        Enrollment enrollment = enrollmentRepository.save(
            new Enrollment(student.getId(), course.getId())
        );
        log.info("수강신청 성공: enrollmentId={}, studentId={}, courseId={}",
                enrollment.getId(), student.getId(), course.getId());
        return EnrollmentResponse.from(enrollment);

    } catch (DataIntegrityViolationException ex) {
        // [8] INSERT 실패 시 좌석 복구 (보상 트랜잭션)
        try {
            EnrollmentService proxy = applicationContext.getBean(EnrollmentService.class);
            proxy.recoverSeat(course.getId());
        } catch (Exception recoverEx) {
            log.error("좌석 복구 중 예외 발생: courseId={}, error={}",
                    course.getId(), recoverEx.getMessage(), recoverEx);
        }
        log.warn("수강신청 실패: 유니크 제약 충돌 (좌석 복구 시도 완료) studentId={}, courseId={}",
                student.getId(), course.getId());
        throw new CustomException(ErrorCode.DUPLICATE_ENROLLMENT);
    }
}
```

#### 단계별 상세 설명

**[1-5] 사전 검증 단계**
- 학생/강좌 존재 여부
- 중복 신청 체크
- 학점 제한 (18학점)
- 시간표 충돌

→ 이 단계는 동시성 문제 없음 (단순 조회/검증)

**[6] 정원 감소 (핵심 동시성 제어)**
```java
int updated = courseRepository.decreaseSeatIfAvailable(course.getId());
if (updated == 0) {
    throw new CustomException(ErrorCode.CAPACITY_FULL);
}
```

**동작 방식:**
1. `decreaseSeatIfAvailable()` 호출
2. DB에서 `UPDATE ... WHERE seatsLeft > 0` 실행
3. 반환값 확인:
   - `1`: 정원 감소 성공 → 다음 단계 진행
   - `0`: 정원 없음 → `CAPACITY_FULL` 예외 발생

**[7] Enrollment 레코드 생성**
- 정원 감소에 성공한 경우만 실행
- `INSERT INTO enrollment (student_id, course_id)` 실행

**[8] 보상 트랜잭션**
- INSERT 실패 시 (unique 제약조건 위반 등)
- 이미 감소시킨 좌석을 복구
- 별도 트랜잭션(`REQUIRES_NEW`)으로 실행

---

### 3. 보상 로직: `recoverSeat()`

#### 코드
```java
@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
public void recoverSeat(Long courseId) {
    int updated = courseRepository.increaseSeat(courseId);
    if (updated > 0) {
        log.info("좌석 복구 완료: courseId={}", courseId);
    } else {
        log.warn("좌석 복구 실패: 강좌를 찾을 수 없음 courseId={}", courseId);
    }
}
```

#### 필요성

**문제 상황:**
```
1. decreaseSeatIfAvailable() 성공 → seatsLeft 감소
2. enrollment INSERT 실패 (예: unique 제약조건 위반)
3. 결과: 신청은 안 됐는데 좌석만 감소 ❌
```

**해결 방법:**
- INSERT 실패 시 좌석을 다시 증가 (`seatsLeft + 1`)
- 별도 트랜잭션으로 실행 (현재 트랜잭션이 오염되어 롤백 불가)

#### `REQUIRES_NEW` 전파 속성 사용 이유

```
현재 트랜잭션 (enroll)
├─ decreaseSeatIfAvailable() ✅
├─ enrollment INSERT ❌ (DataIntegrityViolationException)
└─ EntityManager 세션 오염 → ROLLBACK만 가능

별도 트랜잭션 (recoverSeat - REQUIRES_NEW)
└─ increaseSeat() ✅ (새 트랜잭션이므로 정상 실행)
```

---

## 동작 흐름

### 정상 케이스: 신청 성공

```
┌─────────────────────────────────────────────────────────────┐
│ 정상 케이스: 정원 30, 현재 29명 신청 (seatsLeft = 1)           │
└─────────────────────────────────────────────────────────────┘

Step 1: 사전 검증
  ├─ 학생 존재? ✅
  ├─ 강좌 존재? ✅
  ├─ 중복 신청? ❌
  ├─ 학점 초과? ❌
  └─ 시간표 충돌? ❌

Step 2: 정원 감소 (동시성 제어)
  ├─ SQL: UPDATE course SET seats_left = 0
  │        WHERE id = 1 AND seats_left > 0
  ├─ 실행 결과: 1행 업데이트
  └─ seatsLeft: 1 → 0 ✅

Step 3: Enrollment 생성
  ├─ SQL: INSERT INTO enrollment (student_id, course_id)
  │        VALUES (123, 1)
  ├─ 실행 결과: 1행 삽입
  └─ 신청 완료 ✅

Step 4: 응답 반환
  └─ EnrollmentResponse (id: 456, status: ENROLLED)
```

---

### 실패 케이스 1: 정원 초과

```
┌─────────────────────────────────────────────────────────────┐
│ 실패 케이스 1: 정원 30, 현재 30명 신청 (seatsLeft = 0)         │
└─────────────────────────────────────────────────────────────┘

Step 1: 사전 검증
  └─ 모두 통과 ✅

Step 2: 정원 감소 시도
  ├─ SQL: UPDATE course SET seats_left = seats_left - 1
  │        WHERE id = 1 AND seats_left > 0
  │                         ↑
  │                    seatsLeft = 0 이므로 조건 불만족!
  ├─ 실행 결과: 0행 업데이트 (아무것도 변경 안 됨)
  └─ updated == 0 ❌

Step 3: 예외 발생
  └─ throw CustomException(ErrorCode.CAPACITY_FULL)

최종 상태:
  ├─ seatsLeft: 0 (변경 없음)
  └─ Enrollment: 생성 안 됨
```

---

### 실패 케이스 2: INSERT 실패 + 좌석 복구

```
┌─────────────────────────────────────────────────────────────┐
│ 실패 케이스 2: 정원 감소 성공했지만 INSERT 실패               │
│ (예: 동일 학생이 중복 체크를 통과했지만 unique 제약조건 위반)   │
└─────────────────────────────────────────────────────────────┘

Step 1: 사전 검증
  └─ existsByStudentIdAndCourseId() = false ✅

Step 2: 정원 감소
  ├─ SQL: UPDATE course SET seats_left = 29
  │        WHERE id = 1 AND seats_left > 0
  ├─ 실행 결과: 1행 업데이트
  └─ seatsLeft: 30 → 29 ✅

Step 3: Enrollment 생성 시도
  ├─ SQL: INSERT INTO enrollment (student_id, course_id)
  │        VALUES (123, 1)
  ├─ 실행 결과: DataIntegrityViolationException ❌
  │   (unique 제약조건 위반: 이미 동일 레코드 존재)
  └─ catch (DataIntegrityViolationException ex)

Step 4: 좌석 복구 (보상 트랜잭션)
  ├─ proxy.recoverSeat(courseId)
  ├─ SQL: UPDATE course SET seats_left = 30
  │        WHERE id = 1
  ├─ 실행 결과: 1행 업데이트
  └─ seatsLeft: 29 → 30 ✅ (원상 복구)

Step 5: 예외 발생
  └─ throw CustomException(ErrorCode.DUPLICATE_ENROLLMENT)

최종 상태:
  ├─ seatsLeft: 30 (복구됨)
  └─ Enrollment: 생성 안 됨
```

---

## 동시성 보장 메커니즘

### 1. 원자적 연산의 의미

```sql
-- ❌ 안전하지 않은 방법 (Check-Then-Act)
SELECT seats_left FROM course WHERE id = 1;  -- 30 조회
-- 이 사이에 다른 트랜잭션이 끼어들 수 있음!
UPDATE course SET seats_left = 29 WHERE id = 1;

-- ✅ 안전한 방법 (Atomic Conditional Update)
UPDATE course
SET seats_left = seats_left - 1
WHERE id = 1 AND seats_left > 0;
-- 체크와 업데이트가 원자적으로 실행
```

### 2. DB 엔진의 동시성 제어

#### MySQL InnoDB의 경우

```
UPDATE ... WHERE 실행 시:

1. Row-level Lock 획득
   └─ WHERE 조건에 해당하는 행에 대해 배타적 락 획득

2. 조건 검증 + 업데이트
   └─ seatsLeft > 0 체크
   └─ 만족하면 seatsLeft - 1
   └─ 불만족하면 아무것도 안 함

3. Lock 해제
   └─ COMMIT 또는 ROLLBACK 시 락 해제
```

#### 동시 요청 처리 흐름

```
시간 →

T1 (Thread 1)                    T2 (Thread 2)
─────────────────────────────────────────────────────────
UPDATE ... WHERE id=1            │
AND seats_left > 0               │
→ Row Lock 획득                  │
→ seats_left = 30                │
→ 조건 만족 (30 > 0)              │
→ UPDATE 실행 (29로 변경)         │
                                 │ UPDATE ... WHERE id=1
                                 │ AND seats_left > 0
                                 │ → Row Lock 대기... ⏳
COMMIT                           │
→ Lock 해제                      │
                                 │ → Row Lock 획득
                                 │ → seats_left = 29
                                 │ → 조건 만족 (29 > 0)
                                 │ → UPDATE 실행 (28로 변경)
                                 │ COMMIT
                                 │ → Lock 해제
```

### 3. 트랜잭션 격리 수준

#### 현재 설정
```java
@Transactional(isolation = Isolation.READ_COMMITTED)
```

#### READ_COMMITTED의 의미

```
READ_COMMITTED:
- 커밋된 데이터만 읽음
- Non-Repeatable Read 발생 가능
- Phantom Read 발생 가능
- 대부분의 DB 기본값

왜 READ_COMMITTED로 충분한가?
→ UPDATE ... WHERE 자체가 Row Lock을 사용하므로
  격리 수준과 무관하게 동시성 보장됨
```

#### 격리 수준별 비교

| 격리 수준 | 정원 초과 방지 | 성능 |
|-----------|---------------|------|
| **READ_UNCOMMITTED** | ✅ (UPDATE 락으로 보장) | ⭐⭐⭐⭐⭐ |
| **READ_COMMITTED** (현재) | ✅ (UPDATE 락으로 보장) | ⭐⭐⭐⭐⭐ |
| **REPEATABLE_READ** | ✅ (UPDATE 락으로 보장) | ⭐⭐⭐⭐ |
| **SERIALIZABLE** | ✅ (UPDATE 락으로 보장) | ⭐⭐ |

→ **결론**: READ_COMMITTED로 충분 (성능과 정합성 균형)

---

## 실제 시나리오 분석

### 시나리오 1: 마지막 1자리에 100명 동시 신청

```
초기 상태:
- Course: id=1, capacity=30, seatsLeft=1
- 100명의 학생이 동시에 신청 요청

┌──────────────────────────────────────────────────────────────┐
│ Timeline (정원 1 → 0으로 변경되는 순간)                        │
└──────────────────────────────────────────────────────────────┘

Thread 1 (학생 A)        DB (Course 테이블)       Thread 2 (학생 B)
─────────────────────────────────────────────────────────────────
UPDATE ... WHERE         │                       │
seats_left > 0           │                       │
→ Row Lock 획득          │                       │
→ seats_left 확인: 1     │                       │
→ 조건 만족 (1 > 0) ✅    │                       │
→ UPDATE 실행            │                       │
   seatsLeft: 1 → 0      │                       UPDATE ... WHERE
                         │                       seats_left > 0
                         │                       → Row Lock 대기 ⏳
COMMIT                   │                       │
→ updated = 1            │                       │
→ Lock 해제              │                       │
                         │                       → Row Lock 획득
                         │                       → seats_left 확인: 0
                         │                       → 조건 불만족 (0 > 0) ❌
                         │                       → UPDATE 안 함
                         │                       COMMIT
                         │                       → updated = 0
                         │                       → CAPACITY_FULL 예외

INSERT enrollment        │                       (INSERT 실행 안 됨)
(학생 A, 강좌 1)          │
→ 성공 ✅                 │

최종 결과:
├─ 학생 A: 신청 성공 ✅
├─ 학생 B: CAPACITY_FULL 예외 ❌
├─ 나머지 98명: CAPACITY_FULL 예외 ❌
└─ seatsLeft: 0
```

### 시나리오 2: 동일 학생이 2개 요청 (네트워크 지연 등)

```
초기 상태:
- 학생 A가 실수로 "신청" 버튼을 2번 클릭
- 두 개의 HTTP 요청이 거의 동시에 서버 도착

┌──────────────────────────────────────────────────────────────┐
│ Timeline (동일 학생의 중복 요청)                               │
└──────────────────────────────────────────────────────────────┘

Thread 1 (요청 1)                     Thread 2 (요청 2)
────────────────────────────────────────────────────────────────
existsByStudentIdAndCourseId()        │
→ false (아직 enrollment 없음)         │
                                      │ existsByStudentIdAndCourseId()
                                      │ → false (아직 enrollment 없음)
UPDATE seats_left - 1                 │
→ 성공 (seatsLeft: 30 → 29)           │
                                      │ UPDATE seats_left - 1
                                      │ → 성공 (seatsLeft: 29 → 28)
INSERT enrollment (학생 A, 강좌 1)     │
→ 성공 ✅                              │
COMMIT                                │
                                      │ INSERT enrollment (학생 A, 강좌 1)
                                      │ → DataIntegrityViolationException ❌
                                      │   (unique 제약조건 위반)
                                      │
                                      │ catch (DataIntegrityViolationException)
                                      │ └─ recoverSeat(강좌 1)
                                      │    UPDATE seats_left + 1
                                      │    → 성공 (seatsLeft: 28 → 29) ✅
                                      │
                                      │ throw DUPLICATE_ENROLLMENT

최종 결과:
├─ 요청 1: 신청 성공 ✅
├─ 요청 2: DUPLICATE_ENROLLMENT 예외 ❌
├─ seatsLeft: 29 (복구됨)
└─ Enrollment: 1건만 생성
```

**핵심:**
- DB의 unique 제약조건이 최종 방어선 역할
- 애플리케이션 레벨 체크를 통과해도 DB에서 차단
- 보상 트랜잭션으로 좌석 복구하여 정합성 유지

### 시나리오 3: 100명이 정원 10인 강좌에 동시 신청

```
초기 상태:
- Course: capacity=50, seatsLeft=10
- 100명의 서로 다른 학생이 동시 신청

실행 결과:
┌─────────────────────────────────────────────┐
│ 순서대로 처리 (Row Lock으로 직렬화)           │
├─────────────────────────────────────────────┤
│ Thread 1:  UPDATE ... → updated=1 ✅        │
│ Thread 2:  UPDATE ... → updated=1 ✅        │
│ Thread 3:  UPDATE ... → updated=1 ✅        │
│ ...                                         │
│ Thread 10: UPDATE ... → updated=1 ✅        │
│ ────────────────────────────────────────    │
│ Thread 11: UPDATE ... → updated=0 ❌        │
│ Thread 12: UPDATE ... → updated=0 ❌        │
│ ...                                         │
│ Thread 100: UPDATE ... → updated=0 ❌       │
└─────────────────────────────────────────────┘

최종 상태:
├─ 성공: 정확히 10명 ✅
├─ 실패: 90명 (CAPACITY_FULL)
└─ seatsLeft: 0
```

---

## 장단점 평가

### ✅ 장점

#### 1. **구현 단순성**
```
필요한 것:
- Repository 메서드 1개 (@Query 애노테이션)
- Service 로직 10줄 내외
- 복잡한 락 관리 로직 불필요
```

#### 2. **높은 성능**
```
- 비관적 락 없이도 동시성 보장
- UPDATE 쿼리 1개로 정원 체크 + 감소
- Row Lock 대기 시간 최소화 (UPDATE 실행 시점만)
```

#### 3. **데드락 없음**
```
- 단일 테이블(Course)에만 UPDATE
- 락 획득 순서 문제 없음
- 타임아웃 설정 불필요
```

#### 4. **확장성 우수**
```
- 여러 서버에서 동시 실행 가능 (스케일 아웃)
- DB 레벨 동시성 제어이므로 애플리케이션 독립적
- Redis 같은 추가 인프라 불필요
```

#### 5. **트랜잭션 격리 수준 무관**
```
- READ_COMMITTED로 충분
- UPDATE ... WHERE 자체가 Row Lock 사용
- 성능과 정합성 균형 최적
```

#### 6. **명확한 실패 처리**
```
- updated == 0 → 정원 없음 (명확한 판단 기준)
- 재시도 로직 불필요 (즉시 실패 반환)
- 사용자에게 빠른 피드백
```

---

### ⚠️ 단점 및 제약사항

#### 1. **보상 트랜잭션 필요**
```java
// INSERT 실패 시 좌석 복구 로직 필수
try {
    enrollmentRepository.save(...);
} catch (DataIntegrityViolationException ex) {
    recoverSeat(courseId);  // 별도 트랜잭션 필요
}
```

**문제:**
- 코드 복잡도 증가
- REQUIRES_NEW 전파 속성 필요
- 보상 실패 시 데이터 불일치 가능성 (로그로만 추적)

**완화 방법:**
- 보상 실패를 모니터링
- 배치 작업으로 정합성 검증

#### 2. **스키마에 seatsLeft 컬럼 필수**
```sql
CREATE TABLE course (
    ...
    seats_left INT NOT NULL,  -- 이 컬럼이 반드시 있어야 함
    ...
);
```

**문제:**
- Enrollment 테이블 카운트와 별도 관리
- 데이터 중복 (capacity - count(enrollment) = seatsLeft)

**완화 방법:**
- 배치 작업으로 주기적 검증
- DB 트리거로 자동 동기화

#### 3. **복구 실패 시 데이터 불일치**
```
시나리오:
1. decreaseSeatIfAvailable() 성공 → seatsLeft = 29
2. enrollment INSERT 실패 (unique 위반)
3. recoverSeat() 실패 (DB 연결 끊김 등)
4. 결과: seatsLeft = 29 (복구 안 됨) ❌

실제 신청자: 30명
seatsLeft: 29 (1명분 누락)
```

**완화 방법:**
```java
// 배치 작업으로 정합성 검증
@Scheduled(cron = "0 0 * * * *")  // 매시간
public void validateSeatsLeft() {
    List<Course> courses = courseRepository.findAll();
    for (Course course : courses) {
        long actualEnrollments = enrollmentRepository.countByCourseId(course.getId());
        int expectedSeatsLeft = course.getCapacity() - (int) actualEnrollments;

        if (course.getSeatsLeft() != expectedSeatsLeft) {
            log.error("seatsLeft 불일치: courseId={}, expected={}, actual={}",
                course.getId(), expectedSeatsLeft, course.getSeatsLeft());
            // 자동 수정 또는 알림
        }
    }
}
```

#### 4. **Row Lock 대기 발생**
```
인기 강좌 (동시 신청 100명):

Thread 1: UPDATE ... → Lock 획득, 실행 (10ms)
Thread 2: UPDATE ... → Lock 대기 (10ms), 실행 (10ms)
Thread 3: UPDATE ... → Lock 대기 (20ms), 실행 (10ms)
...
Thread 100: UPDATE ... → Lock 대기 (990ms), 실행 (10ms)
```

**문제:**
- 마지막 요청은 최대 수 초 대기 가능
- 피크 타임에 응답 시간 증가

**완화 방법:**
- 정원이 충분히 크면 대기 시간 짧음 (보통 수십 ms)
- 타임아웃 설정으로 무한 대기 방지
- 큐잉 시스템 도입 (선착순 대기열)

#### 5. **UPDATE 쿼리 비용**
```sql
-- 매 신청마다 UPDATE 쿼리 실행
UPDATE course SET seats_left = seats_left - 1
WHERE id = ? AND seats_left > 0
```

**문제:**
- INSERT만 하는 것보다 비용 높음
- 인덱스 업데이트 필요

**완화 방법:**
- seatsLeft에 인덱스 설정 (조건절에 사용)
- 대부분의 DB가 효율적으로 처리 (단일 행 업데이트)

---

### 📊 다른 방식과 비교

| 항목 | 현재 방식 (Atomic Update) | 비관적 락 | 낙관적 락 |
|------|---------------------------|-----------|-----------|
| **구현 난이도** | ⭐⭐⭐⭐⭐ 매우 쉬움 | ⭐⭐⭐⭐ 쉬움 | ⭐⭐⭐ 보통 |
| **성능** | ⭐⭐⭐⭐⭐ 우수 | ⭐⭐⭐ 보통 | ⭐⭐⭐⭐⭐ 우수 |
| **확장성** | ⭐⭐⭐⭐⭐ 우수 | ⭐⭐⭐ 보통 | ⭐⭐⭐⭐⭐ 우수 |
| **데드락 위험** | ❌ 없음 | ⚠️ 있음 | ❌ 없음 |
| **보상 로직** | ✅ 필요 | ❌ 불필요 | ❌ 불필요 |
| **재시도** | ❌ 불필요 | ❌ 불필요 | ✅ 필요 |
| **스키마 변경** | ✅ 필요 (seatsLeft) | ❌ 불필요 | ✅ 필요 (version) |

---

## 검증 포인트

### 동시성 테스트로 검증할 사항

#### ✅ 이미 구현된 테스트 (EnrollmentConcurrencyTest)

**TC-001: 1자리 남은 강좌에 100명 동시 신청**
```java
@Test
@DisplayName("TC-001: 정원이 1명 남은 강좌에 100명이 동시 신청 시 정확히 1명만 성공해야 한다")
void shouldEnrollOnlyOneStudentWhenOneSeatsLeftAndHundredConcurrentRequests()
```
- ✅ 정확히 1명만 성공
- ✅ 99명은 CAPACITY_FULL 에러
- ✅ seatsLeft = 0
- ✅ Enrollment 레코드 = 30건 (기존 29 + 신규 1)

**TC-002: 10자리 남은 강좌에 100명 동시 신청**
```java
@Test
@DisplayName("TC-002: 정원 10명 남은 강좌에 100명이 동시 신청 시 정확히 10명만 성공해야 한다")
void shouldEnrollExactlyTenStudentsWhenTenSeatsLeftAndHundredConcurrentRequests()
```
- ✅ 정확히 10명만 성공
- ✅ 90명은 CAPACITY_FULL 에러
- ✅ seatsLeft = 0
- ✅ Enrollment 레코드 = 50건 (기존 40 + 신규 10)

**TC-003: 정원 가득 찬 강좌에 50명 동시 신청**
```java
@Test
@DisplayName("TC-003: 정원이 가득 찬 강좌에 동시 신청 시 모두 실패해야 한다")
void shouldFailAllRequestsWhenCourseIsFullAndConcurrentRequests()
```
- ✅ 성공 0명
- ✅ 50명 모두 CAPACITY_FULL 에러
- ✅ seatsLeft = 0 유지
- ✅ Enrollment 레코드 증가 없음

#### 추가 검증 포인트

**1. 보상 트랜잭션 검증**
```java
// 좌석 복구가 정상 동작하는지 확인
@Test
void shouldRecoverSeatWhenInsertFails() {
    // INSERT 실패 시나리오 시뮬레이션
    // seatsLeft가 원래대로 복구되는지 검증
}
```

**2. seatsLeft 정합성 검증**
```java
// seatsLeft와 실제 신청 수가 일치하는지
@Test
void seatsLeftShouldMatchActualEnrollments() {
    Course course = courseRepository.findById(courseId).orElseThrow();
    long actualEnrollments = enrollmentRepository.countByCourseId(courseId);
    int expectedSeatsLeft = course.getCapacity() - (int) actualEnrollments;

    assertThat(course.getSeatsLeft()).isEqualTo(expectedSeatsLeft);
}
```

---

## 모니터링 및 운영

### 주요 모니터링 지표

#### 1. 정원 초과 실패 빈도
```java
// 메트릭 수집
@Around("execution(* EnrollmentService.enroll(..))")
public Object monitorCapacityFull(ProceedingJoinPoint joinPoint) throws Throwable {
    try {
        return joinPoint.proceed();
    } catch (CustomException ex) {
        if (ex.getErrorCode() == ErrorCode.CAPACITY_FULL) {
            meterRegistry.counter("enrollment.capacity.full",
                "course_id", getCourseId(joinPoint.getArgs())
            ).increment();
        }
        throw ex;
    }
}
```

#### 2. 좌석 복구 발생 빈도
```java
// recoverSeat() 호출 빈도 모니터링
@Around("execution(* EnrollmentService.recoverSeat(..))")
public Object monitorSeatRecovery(ProceedingJoinPoint joinPoint) throws Throwable {
    meterRegistry.counter("enrollment.seat.recovery").increment();
    return joinPoint.proceed();
}
```

#### 3. seatsLeft 불일치 감지
```java
// 배치 작업으로 주기적 검증
@Scheduled(cron = "0 0 * * * *")
public void detectSeatsLeftMismatch() {
    List<Course> courses = courseRepository.findAll();
    int mismatchCount = 0;

    for (Course course : courses) {
        long actualEnrollments = enrollmentRepository.countByCourseId(course.getId());
        int expectedSeatsLeft = course.getCapacity() - (int) actualEnrollments;

        if (course.getSeatsLeft() != expectedSeatsLeft) {
            mismatchCount++;
            log.error("seatsLeft 불일치 감지: courseId={}, expected={}, actual={}",
                course.getId(), expectedSeatsLeft, course.getSeatsLeft());
        }
    }

    meterRegistry.gauge("enrollment.seats_left.mismatch", mismatchCount);
}
```

---

## 결론

### 핵심 요약

현재 프로젝트의 정원 초과 방지 동시성 제어는 **원자적 조건부 업데이트(Atomic Conditional Update)** 방식으로 구현되어 있습니다.

**핵심 메커니즘:**
```sql
UPDATE course
SET seats_left = seats_left - 1
WHERE id = ? AND seats_left > 0
```

**동작 원리:**
1. 정원 체크(`WHERE seatsLeft > 0`)와 감소(`SET seatsLeft - 1`)를 한 쿼리로 처리
2. DB의 Row Lock으로 동시성 보장
3. 반환값(`updated`)으로 성공/실패 판단
4. INSERT 실패 시 보상 트랜잭션으로 좌석 복구

**장점:**
- ✅ 구현 간단 (메서드 1개 + 로직 10줄)
- ✅ 성능 우수 (락 불필요)
- ✅ 데드락 없음
- ✅ 확장성 우수 (스케일 아웃 가능)

**주의 사항:**
- ⚠️ 보상 트랜잭션 필수 (INSERT 실패 시 좌석 복구)
- ⚠️ seatsLeft 정합성 주기적 검증 필요
- ⚠️ 보상 실패 시 데이터 불일치 가능성 (모니터링 필요)

**검증 상태:**
- ✅ 동시성 테스트 3건 (TC-001, TC-002, TC-003) 통과
- ✅ 정원 초과 절대 발생하지 않음 확인
- ✅ 100명 동시 신청 시나리오 검증 완료

---

**문서 버전**: 1.0
**최종 수정일**: 2026-02-21
**작성자**: 정원 초과 방지 동시성 제어 구현 분석
