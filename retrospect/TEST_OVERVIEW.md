# 테스트 현황 문서

> `src/test/` 디렉토리의 전체 테스트를 체계적으로 정리한 문서입니다.
> 각 테스트의 목적, 검증 항목, 기술적 설계 의도를 포함합니다.

---

## 목차

1. [테스트 구성 개요](#1-테스트-구성-개요)
2. [단위 테스트 (unit/)](#2-단위-테스트-unit)
3. [통합 테스트 (integration/) — 수강신청 비즈니스 규칙](#3-통합-테스트-integration--수강신청-비즈니스-규칙)
4. [통합 테스트 (integration/) — 동시성 제어](#4-통합-테스트-integration--동시성-제어)
5. [통합 테스트 (integration/) — API 엔드포인트](#5-통합-테스트-integration--api-엔드포인트)
6. [통합 테스트 (integration/) — 인프라 및 데이터 초기화](#6-통합-테스트-integration--인프라-및-데이터-초기화)
7. [전체 테스트 케이스 요약표](#7-전체-테스트-케이스-요약표)

---

## 1. 테스트 구성 개요

### 1.1 디렉토리 구조

```
src/test/java/com/musinsa/
├── unit/
│   └── service/                        ← 단위 테스트 (Mockito)
│       ├── EnrollmentServiceTest.java   (5개 테스트)
│       ├── CourseServiceTest.java       (2개 테스트)
│       ├── StudentServiceTest.java      (3개 테스트)
│       └── ProfessorServiceTest.java    (3개 테스트)
└── integration/                        ← 통합 테스트 (SpringBootTest + H2)
    ├── EnrollmentConcurrencyTest.java           (4개 — P0 동시성 제어)
    ├── EnrollmentConcurrencyIntegrationTest.java (1개 — 기본 동시성 검증)
    ├── EnrollmentBusinessRuleIntegrationTest.java(4개 — 비즈니스 규칙 + 복구)
    ├── DataInitializerTest.java                  (7개 — 초기 데이터 검증)
    ├── StudentTimetableIntegrationTest.java       (3개 — 시간표 API)
    ├── CourseControllerIntegrationTest.java       (6개 — 강좌 API)
    ├── StudentControllerIntegrationTest.java      (5개 — 학생 API)
    └── ProfessorControllerIntegrationTest.java    (5개 — 교수 API)
```

### 1.2 테스트 수 요약

| 분류 | 파일 수 | 테스트 수 |
|------|--------|----------|
| 단위 테스트 | 4 | **13개** |
| 통합 테스트 | 8 | **35개** |
| **합계** | **12** | **48개** |

### 1.3 테스트 기술 스택

| 도구 | 용도 |
|------|------|
| JUnit 5 | 테스트 실행 프레임워크 |
| AssertJ | 가독성 높은 단언문 |
| Mockito | 단위 테스트용 Mock 객체 |
| Spring Boot Test | 통합 테스트 컨텍스트 로드 |
| MockMvc | HTTP 요청/응답 시뮬레이션 |
| H2 Database | 인메모리 테스트 데이터베이스 |
| `CountDownLatch` | 동시성 테스트에서 동시 시작 제어 |
| `CompletableFuture` | 비동기 요청 결과 수집 |

---

## 2. 단위 테스트 (unit/)

단위 테스트는 Mockito로 의존성을 대체하여 **단일 서비스 클래스의 로직만** 검증한다.
DB, 네트워크, 스프링 컨텍스트 없이 순수 자바 수준에서 실행된다.

---

### 2.1 EnrollmentServiceTest

**파일**: `unit/service/EnrollmentServiceTest.java`
**목적**: 수강신청 비즈니스 규칙 위반 시 올바른 에러 코드가 반환되는지 검증
**Mock 대상**: `StudentRepository`, `CourseRepository`, `EnrollmentRepository`

> 핵심 설계 의도: 각 예외 케이스에서 `courseRepository.decreaseSeatIfAvailable()`이
> **절대 호출되지 않아야 함**을 `verify(never())`로 명시 검증한다.
> 사전 검증에서 걸러진 요청이 좌석 감소까지 도달하지 않음을 보장한다.

#### TC-005: 중복 신청 차단

```
Given: 학생 A가 강좌 X를 이미 신청한 상태
       existsByStudentIdAndCourseId() → true
When:  학생 A가 강좌 X를 다시 신청
Then:  DUPLICATE_ENROLLMENT (409)
       decreaseSeatIfAvailable() 미호출 확인
```

#### TC-007: 학점 상한 초과 차단

```
Given: 학생이 이미 15학점 수강 중 (3학점 강좌 × 5개)
       신청하려는 강좌 = 4학점 → 15 + 4 = 19 > MAX_CREDITS(18)
When:  수강 신청
Then:  CREDIT_LIMIT_EXCEEDED (409)
       decreaseSeatIfAvailable() 미호출 확인
```

검증 데이터: 기존 강좌 5개(각 3학점), 신규 강좌 4학점으로 초과 경계를 명확히 구성

#### TC-008: 시간표 충돌 차단

```
Given: 학생이 "월 09:00-10:30" 강좌를 이미 신청한 상태
       신청하려는 강좌도 "월 09:00-10:30"
When:  수강 신청
Then:  SCHEDULE_CONFLICT (409)
       decreaseSeatIfAvailable() 미호출 확인
```

`schedule` 문자열 동일 비교로 충돌을 판단하는 현재 로직을 검증

#### TC-009: 존재하지 않는 학생

```
Given: studentId = 999999 (DB에 없음)
       studentRepository.findById() → Optional.empty()
When:  수강 신청
Then:  STUDENT_NOT_FOUND (404)
       courseRepository.findById() 미호출 확인
       decreaseSeatIfAvailable() 미호출 확인
```

학생 조회 실패 시 이후 모든 단계가 실행되지 않는 조기 실패를 검증

#### TC-010: 존재하지 않는 강좌

```
Given: courseId = 999999 (DB에 없음)
       courseRepository.findById() → Optional.empty()
When:  수강 신청
Then:  COURSE_NOT_FOUND (404)
       decreaseSeatIfAvailable() 미호출 확인
```

---

### 2.2 CourseServiceTest

**파일**: `unit/service/CourseServiceTest.java`
**목적**: 강좌 조회 서비스의 페이지 크기 제한과 학과 필터 분기 로직 검증

#### 최대 페이지 크기 제한 적용

```
Given: size = 200 (최대값 100 초과)
When:  courseService.findAll(0, 200, null)
Then:  실제 Pageable.pageSize = 100 (cap 적용)
       ArgumentCaptor로 Repository에 전달된 Pageable 직접 검증
```

`ArgumentCaptor`를 활용하여 서비스가 Repository에 전달하는 `Pageable` 객체의
실제 pageSize를 직접 꺼내 검증하는 방식이 특징적이다.

#### 학과 필터 시 학과 기준 조회 분기

```
Given: departmentId = 5 (유효한 학과)
When:  courseService.findAll(0, 20, 5L)
Then:  courseRepository.findAllByDepartmentId() 호출 확인
       courseRepository.findAll() 미호출
```

---

### 2.3 StudentServiceTest

**파일**: `unit/service/StudentServiceTest.java`
**목적**: 학생 조회 서비스의 기본 페이지 크기, 학과 필터, 예외 처리 검증

#### 기본 페이지 크기 적용

```
Given: size = 0 (미지정)
When:  studentService.findAll(0, 0, null)
Then:  기본 size = 20 적용
       응답의 page, size, totalElements, totalPages 모두 검증
       Pageable의 sort = ASC(id) 검증
```

#### 학과 필터 분기

```
Given: departmentId = 10 (존재하는 학과)
When:  studentService.findAll(0, 20, 10L)
Then:  studentRepository.findAllByDepartmentId() 호출
```

#### 존재하지 않는 학과

```
Given: departmentId = 999 (DB에 없음)
When:  studentService.findAll(0, 20, 999L)
Then:  DEPARTMENT_NOT_FOUND (404)
```

---

### 2.4 ProfessorServiceTest

**파일**: `unit/service/ProfessorServiceTest.java`
**목적**: 교수 조회 서비스의 전체 조회, 학과 필터, 예외 처리 검증
**패턴**: StudentServiceTest와 동일 구조

| 테스트 | 검증 내용 |
|--------|----------|
| 학과 필터 없음 | `professorRepository.findAll()` 호출, `Sort.ASC(id)` 적용 |
| 학과 필터 있음 | `professorRepository.findAllByDepartmentId()` 호출 |
| 존재하지 않는 학과 | `DEPARTMENT_NOT_FOUND` (404) |

---

## 3. 통합 테스트 (integration/) — 수강신청 비즈니스 규칙

### 3.1 EnrollmentBusinessRuleIntegrationTest

**파일**: `integration/EnrollmentBusinessRuleIntegrationTest.java`
**목적**: 실제 H2 DB를 사용하여 비즈니스 규칙과 예외 상황을 End-to-End로 검증
**특징**:
- `@Transactional` 미사용 → 실제 트랜잭션 커밋이 발생하여 동시성 동작 검증 가능
- `@AfterEach`에서 생성된 엔티티 ID를 추적하여 수동 삭제
- `AtomicLong SEQ`로 테스트 간 데이터 격리 보장

#### TC-006: 동일 학생 동시 10번 신청 → 1건만 성공 + 좌석 복구

```
Given: 정원 30, seatsLeft = 30인 강좌
       같은 학생 1명 (수강 이력 없음)
When:  동일 학생이 동시에 10번 신청 (CountDownLatch 동시 시작)
Then:  성공 = 정확히 1건
       DUPLICATE_ENROLLMENT ≥ 1건
       예상치 못한 에러 = 0건
       Enrollment 레코드 = 1건 (핵심 검증)
       ★ seatsLeft = 29 (보상 트랜잭션으로 복구된 상태 검증)
```

**이 테스트가 특별히 중요한 이유**: 보상 트랜잭션(`recoverSeat()`)이 실제로 동작했는지를
`seatsLeft = 29` 단언문으로 직접 검증한다. 10번 시도 중 9번은 INSERT 실패 후
`REQUIRES_NEW` 트랜잭션으로 좌석을 복구해야 한다. 복구가 실패하면 `seatsLeft < 29`가 된다.

#### TC-011: 정원 마감 강좌 신청

```
Given: seatsLeft = 0인 강좌
When:  수강 신청
Then:  CAPACITY_FULL (409)
       seatsLeft = 0 유지 검증
```

#### TC-012: 수강 취소 → Enrollment 삭제 + 좌석 복구

```
Given: 학생 A가 강좌 X를 신청한 상태 (seatsLeft = 9 → 8)
When:  수강 취소
Then:  응답 status = "CANCELLED"
       enrollmentRepository.existsByStudentIdAndCourseId() → false
       seatsLeft = 9 (원래 값으로 복구)
```

수강신청(`enroll`)과 취소(`cancel`) 각각의 DB 상태를 검증하는 완전한 라이프사이클 테스트

#### TC-013: 신청 내역 없는 강좌 취소

```
Given: 학생이 강좌를 신청하지 않은 상태
When:  수강 취소 시도
Then:  ENROLLMENT_NOT_FOUND (404)
```

---

## 4. 통합 테스트 (integration/) — 동시성 제어

동시성 테스트는 **`@Transactional`을 테스트 메서드에 붙이지 않는다.**
테스트 트랜잭션으로 감싸면 각 스레드의 별도 트랜잭션이 테스트 내 데이터를 볼 수 없어
실제 Race Condition이 재현되지 않기 때문이다.

`CountDownLatch`를 사용하여 모든 스레드가 준비된 후 동시에 시작 신호를 받는 방식으로
실제 경쟁 상황을 최대한 재현한다.

---

### 4.1 EnrollmentConcurrencyTest (P0 동시성 — 정원 초과 방지)

**파일**: `integration/EnrollmentConcurrencyTest.java`
**목적**: 정원 초과가 절대 발생하지 않음을 증명하는 핵심 동시성 테스트
**특징**:
- `@Timeout(value = 30, unit = TimeUnit.SECONDS)` — 데드락/무한 대기 방지
- 각 테스트마다 독립적인 Department/Professor/Course/Student 데이터 생성
- `AtomicLong DEPARTMENT_SEQ`로 병렬 테스트 실행 시에도 고유성 보장
- 결과를 `Result` 값 타입(SUCCESS / FAILURE / ERROR)으로 캡처하여 예상치 못한 에러도 명시 검증

#### TC-001: 정원 1명 남은 강좌에 100명 동시 신청

```
Given: capacity = 30, seatsLeft = 1 (기존 신청 29건)
       100명의 신규 학생
When:  100명이 CountDownLatch로 동시에 수강 신청
Then:  성공 = 정확히 1명
       CAPACITY_FULL = 정확히 99명
       예상치 못한 에러 = 0명
       seatsLeft = 0 (음수 불가 단언 포함)
       Enrollment 레코드 = 30건 (기존 29 + 신규 1)
```

**이 테스트가 시스템의 가장 중요한 요구사항 검증**:
기획서의 "정원이 1명 남은 강좌에 100명이 동시에 신청해도 정확히 1명만 성공해야 한다"를
코드로 직접 검증한다.

#### TC-002: 정원 10명 남은 강좌에 100명 동시 신청

```
Given: capacity = 50, seatsLeft = 10 (기존 신청 40건)
       100명의 신규 학생
When:  100명이 동시에 수강 신청
Then:  성공 = 정확히 10명
       CAPACITY_FULL = 정확히 90명
       seatsLeft = 0
       Enrollment 레코드 = 50건 (기존 40 + 신규 10)
```

#### TC-003: 정원 가득 찬 강좌에 50명 동시 신청

```
Given: capacity = 30, seatsLeft = 0 (만석)
       50명의 신규 학생
When:  50명이 동시에 수강 신청
Then:  성공 = 0명 (전원 실패)
       CAPACITY_FULL = 50명
       seatsLeft = 0 유지
       Enrollment 레코드 증가 없음
```

#### TC-004: 행복 경로 (Happy Path)

```
Given: capacity = 30, seatsLeft = 10인 강좌
       조건을 모두 만족하는 학생 1명
When:  수강 신청
Then:  EnrollmentResponse 반환 (id, studentId, courseId, status="ENROLLED")
       seatsLeft = 9 (1 감소)
       Enrollment 레코드 1건 증가
```

---

### 4.2 EnrollmentConcurrencyIntegrationTest (기본 동시성 검증)

**파일**: `integration/EnrollmentConcurrencyIntegrationTest.java`
**목적**: 정원 1인 강좌에 대한 단순화된 동시성 검증
**특징**: `@AfterEach` 정리 없이 단순 구조로 작성된 초기 검증용 테스트

```
Given: capacity = 1, seatsLeft = 1인 강좌
       10명의 신규 학생
When:  10명이 동시에 수강 신청
Then:  successCount = 1 (정확히 1명만 성공)
       course.getEnrolled() = 1
       Enrollment 레코드 = 1건
```

> EnrollmentConcurrencyTest의 TC-001과 동일한 시나리오를 더 단순한 구조로 검증한다.
> TC-001보다 데이터 격리와 결과 분류가 덜 엄밀하지만 보완적 역할을 한다.

---

## 5. 통합 테스트 (integration/) — API 엔드포인트

`@SpringBootTest + @AutoConfigureMockMvc` 조합으로 실제 HTTP 요청/응답을 검증한다.
`MockMvc`가 실제 서블릿 컨테이너를 거치지 않고 Spring MVC 레이어를 완전히 테스트한다.

모든 조회 API 테스트는 공통적으로 **세 가지 에러 패턴**을 검증한다:
- `page = -1` → `INVALID_REQUEST` (400)
- 허용되지 않은 쿼리 파라미터 → `INVALID_QUERY_PARAM` (400)
- 존재하지 않는 학과 ID → `DEPARTMENT_NOT_FOUND` (404)

---

### 5.1 CourseControllerIntegrationTest

**파일**: `integration/CourseControllerIntegrationTest.java`
**엔드포인트**: `GET /api/courses`

#### 강좌 전체 목록 조회

```
Request:  GET /api/courses?page=0&size=1
Response: 200 OK
          {
            "success": true,
            "data": {
              "page": 0, "size": 1,
              "content": [{
                "id": ...,
                "capacity": ...,      ← 정원
                "enrolled": ...,      ← 현재 신청 인원 (capacity - seatsLeft)
                "schedule": "..."     ← 강의 시간
              }]
            }
          }
```

`enrolled = capacity - seatsLeft`를 계산하여 반환하는 `Course.getEnrolled()` 메서드의
동작을 HTTP 응답 레벨에서 검증한다.

#### 학과별 강좌 조회

```
Request:  GET /api/courses?departmentId={validId}&page=0&size=5
Response: 200 OK — content[0].departmentId = {validId}
```

#### 에러 케이스

| 요청 파라미터 | 기대 응답 | 에러 코드 |
|-------------|---------|---------|
| `page=-1` | 400 | `INVALID_REQUEST` |
| `unknown=1` (허용 안된 파라미터) | 400 | `INVALID_QUERY_PARAM` |
| `departmentId=999999` (없는 학과) | 404 | `DEPARTMENT_NOT_FOUND` |
| `size=xyz` (타입 불일치) | 400 | `INVALID_REQUEST` |

---

### 5.2 StudentControllerIntegrationTest

**파일**: `integration/StudentControllerIntegrationTest.java`
**엔드포인트**: `GET /api/students`

#### 학생 목록 조회

```
Request:  GET /api/students?page=0&size=1
Response: 200 OK
          {
            "success": true,
            "data": {
              "content": [{
                "id": ...,
                "studentNumber": "...",
                "name": "...",
                "grade": ...,
                "departmentId": ...
              }]
            }
          }
```

초기 데이터(`DataInitializer`)가 생성한 학생 중 첫 번째 학생을 DB에서 직접 조회하여
응답값과 일치하는지 검증한다.

#### 에러 케이스

| 요청 파라미터 | 기대 응답 | 에러 코드 |
|-------------|---------|---------|
| `page=-1` | 400 | `INVALID_REQUEST` |
| `unknown=1` | 400 | `INVALID_QUERY_PARAM` |
| `departmentId=999999` | 404 | `DEPARTMENT_NOT_FOUND` |
| `page=abc` (타입 불일치) | 400 | `INVALID_REQUEST` |

---

### 5.3 ProfessorControllerIntegrationTest

**파일**: `integration/ProfessorControllerIntegrationTest.java`
**엔드포인트**: `GET /api/professors`

#### 교수 목록 조회

```
Request:  GET /api/professors?page=0&size=1
Response: 200 OK
          {
            "success": true,
            "data": {
              "content": [{
                "id": ...,
                "name": "...",
                "departmentId": ...
              }]
            }
          }
```

#### 에러 케이스

| 요청 파라미터 | 기대 응답 | 에러 코드 |
|-------------|---------|---------|
| `page=-1` | 400 | `INVALID_REQUEST` |
| `unknown=1` | 400 | `INVALID_QUERY_PARAM` |
| `departmentId=999999` | 404 | `DEPARTMENT_NOT_FOUND` |
| `departmentId=bad` (타입 불일치) | 400 | `INVALID_REQUEST` |

---

### 5.4 StudentTimetableIntegrationTest

**파일**: `integration/StudentTimetableIntegrationTest.java`
**엔드포인트**: `GET /api/students/{studentId}/timetable`

#### 수강 이력 없는 학생 — 빈 시간표

```
Given: 수강 이력이 없는 학생 (기존 이력 있으면 삭제 후 테스트)
When:  GET /api/students/{studentId}/timetable
Then:  200 OK
       data.studentId = {studentId}
       data.items = [] (빈 배열)
       data.totalCredits = 0
```

#### 수강 이력 있는 학생 — 시간표 반환

```
Given: 학생이 강좌 1개를 수강 신청한 상태
When:  GET /api/students/{studentId}/timetable
Then:  200 OK
       data.items[0].courseId = {courseId}
       data.items[0].name = {courseName}
       data.items[0].credits = {credits}
       data.items[0].schedule = {schedule}
       data.totalCredits = {credits}
```

#### 존재하지 않는 학생

```
Given: DB에 존재하지 않는 studentId
When:  GET /api/students/{studentId}/timetable
Then:  404 NOT FOUND
       error.code = "STUDENT_NOT_FOUND"
```

존재하지 않는 ID 탐색 방법: DB에서 최대 ID + 1000을 시작점으로, 실제 존재하지 않을 때까지 반복 확인

---

## 6. 통합 테스트 (integration/) — 인프라 및 데이터 초기화

### 6.1 DataInitializerTest

**파일**: `integration/DataInitializerTest.java`
**목적**: 서버 시작 시 `DataInitializer`가 최소 요구 수량의 데이터를 1분 이내에 생성하고
헬스체크가 정상 응답하는지 검증
**특징**: `@SpringBootTest`로 전체 컨텍스트를 로드하면 `ApplicationRunner`가 자동 실행되어
테스트 메서드 도달 전에 이미 초기화 완료 상태

#### 최소 데이터 수량 검증

| 테스트 | 검증 조건 | 최솟값 |
|--------|---------|------|
| 학과 수량 | `departmentRepository.count() >= 10` | 10개 |
| 교수 수량 | `professorRepository.count() >= 100` | 100명 |
| 강좌 수량 | `courseRepository.count() >= 500` | 500개 |
| 학생 수량 | `studentRepository.count() >= 10,000` | 10,000명 |

#### DataInitializationStatus ready 전환 검증

```
Given: DataInitializer.run()이 완료된 상태
When:  dataInitializationStatus.isReady() 호출
Then:  true 반환
```

`markReady()` 호출이 누락되면 헬스체크가 계속 503을 반환하므로 중요한 검증이다.

#### 헬스체크 응답 검증

```
Request:  GET /health
Response: 200 OK
          {
            "success": true,
            "data": { "status": "ok" }
          }
```

`ApiResponseAdvice`가 응답을 `CustomResponse`로 감싸므로 `$.data.status`로 접근한다.

#### 초기화 60초 이내 완료 검증

```
- 테스트 메서드에 도달했다는 것 자체가 초기화 완료를 의미
- 4개 테이블의 count 쿼리 실행 시간이 60초 미만임을 검증
- dataInitializationStatus.isReady() = true 추가 확인
```

---

## 7. 전체 테스트 케이스 요약표

### 단위 테스트

| TC | 파일 | 테스트명 | 검증 항목 |
|----|------|---------|---------|
| TC-005 | EnrollmentServiceTest | 중복 신청 차단 | `DUPLICATE_ENROLLMENT` + `decreaseSeatIfAvailable` 미호출 |
| TC-007 | EnrollmentServiceTest | 학점 상한 초과 | `CREDIT_LIMIT_EXCEEDED` + `decreaseSeatIfAvailable` 미호출 |
| TC-008 | EnrollmentServiceTest | 시간표 충돌 | `SCHEDULE_CONFLICT` + `decreaseSeatIfAvailable` 미호출 |
| TC-009 | EnrollmentServiceTest | 학생 없음 | `STUDENT_NOT_FOUND` + 강좌 조회 미호출 |
| TC-010 | EnrollmentServiceTest | 강좌 없음 | `COURSE_NOT_FOUND` + `decreaseSeatIfAvailable` 미호출 |
| - | CourseServiceTest | 페이지 크기 제한 | `size=200` 요청 시 실제 `pageSize=100` |
| - | CourseServiceTest | 학과 필터 분기 | `findAllByDepartmentId()` 호출 확인 |
| - | StudentServiceTest | 기본 페이지 크기 | `size=0` → `pageSize=20`, 응답 매핑 전체 검증 |
| - | StudentServiceTest | 학과 필터 분기 | `findAllByDepartmentId()` 호출 확인 |
| - | StudentServiceTest | 존재하지 않는 학과 | `DEPARTMENT_NOT_FOUND` |
| - | ProfessorServiceTest | 전체 조회 | `findAll()` 호출 + `Sort.ASC(id)` 적용 확인 |
| - | ProfessorServiceTest | 학과 필터 분기 | `findAllByDepartmentId()` 호출 확인 |
| - | ProfessorServiceTest | 존재하지 않는 학과 | `DEPARTMENT_NOT_FOUND` |

### 통합 테스트 — 동시성

| TC | 파일 | 테스트명 | 동시 스레드 | 핵심 단언 |
|----|------|---------|-----------|---------|
| TC-001 | EnrollmentConcurrencyTest | 정원 1명에 100명 동시 신청 | 100 | 성공=1, CAPACITY_FULL=99, seatsLeft=0 |
| TC-002 | EnrollmentConcurrencyTest | 정원 10명에 100명 동시 신청 | 100 | 성공=10, CAPACITY_FULL=90, seatsLeft=0 |
| TC-003 | EnrollmentConcurrencyTest | 만석 강좌에 50명 동시 신청 | 50 | 성공=0, CAPACITY_FULL=50 |
| TC-004 | EnrollmentConcurrencyTest | 행복 경로 (단건 신청) | 1 | EnrollmentResponse, seatsLeft-=1 |
| - | EnrollmentConcurrencyIntegrationTest | 정원 1에 10명 동시 신청 | 10 | 성공=1, enrolled=1 |

### 통합 테스트 — 비즈니스 규칙 + 복구

| TC | 파일 | 테스트명 | 핵심 단언 |
|----|------|---------|---------|
| TC-006 | EnrollmentBusinessRuleIntegrationTest | 동일 학생 동시 10번 신청 | 성공=1, Enrollment=1건, **seatsLeft=29(복구 검증)** |
| TC-011 | EnrollmentBusinessRuleIntegrationTest | 만석 강좌 신청 | `CAPACITY_FULL`, seatsLeft=0 유지 |
| TC-012 | EnrollmentBusinessRuleIntegrationTest | 수강 취소 성공 | Enrollment 삭제, seatsLeft 원복 |
| TC-013 | EnrollmentBusinessRuleIntegrationTest | 미신청 강좌 취소 | `ENROLLMENT_NOT_FOUND` |

### 통합 테스트 — API 엔드포인트

| 파일 | 테스트명 | 검증 항목 |
|------|---------|---------|
| CourseControllerIntegrationTest | 강좌 목록 조회 | capacity, enrolled, schedule 필드 포함 |
| CourseControllerIntegrationTest | 학과별 강좌 조회 | departmentId 필터 응답 확인 |
| CourseControllerIntegrationTest | 음수 페이지 | 400 INVALID_REQUEST |
| CourseControllerIntegrationTest | 허용 안된 쿼리 파라미터 | 400 INVALID_QUERY_PARAM |
| CourseControllerIntegrationTest | 없는 학과 ID | 404 DEPARTMENT_NOT_FOUND |
| CourseControllerIntegrationTest | 타입 불일치 파라미터 | 400 INVALID_REQUEST |
| StudentControllerIntegrationTest | 학생 목록 조회 | studentNumber, name, grade, departmentId |
| StudentControllerIntegrationTest | 음수 페이지 | 400 INVALID_REQUEST |
| StudentControllerIntegrationTest | 허용 안된 쿼리 파라미터 | 400 INVALID_QUERY_PARAM |
| StudentControllerIntegrationTest | 없는 학과 ID | 404 DEPARTMENT_NOT_FOUND |
| StudentControllerIntegrationTest | 타입 불일치 파라미터 | 400 INVALID_REQUEST |
| ProfessorControllerIntegrationTest | 교수 목록 조회 | name, departmentId |
| ProfessorControllerIntegrationTest | 음수 페이지 | 400 INVALID_REQUEST |
| ProfessorControllerIntegrationTest | 허용 안된 쿼리 파라미터 | 400 INVALID_QUERY_PARAM |
| ProfessorControllerIntegrationTest | 없는 학과 ID | 404 DEPARTMENT_NOT_FOUND |
| ProfessorControllerIntegrationTest | 타입 불일치 파라미터 | 400 INVALID_REQUEST |
| StudentTimetableIntegrationTest | 수강 이력 없음 — 빈 시간표 | items=[], totalCredits=0 |
| StudentTimetableIntegrationTest | 수강 이력 있음 — 시간표 반환 | items[0] 필드 전체 + totalCredits |
| StudentTimetableIntegrationTest | 없는 학생 ID | 404 STUDENT_NOT_FOUND |

### 통합 테스트 — 인프라

| 파일 | 테스트명 | 검증 항목 |
|------|---------|---------|
| DataInitializerTest | 학과 수량 | count ≥ 10 |
| DataInitializerTest | 교수 수량 | count ≥ 100 |
| DataInitializerTest | 강좌 수량 | count ≥ 500 |
| DataInitializerTest | 학생 수량 | count ≥ 10,000 |
| DataInitializerTest | ready 상태 전환 | `isReady() = true` |
| DataInitializerTest | 헬스체크 200 OK | `$.data.status = "ok"` |
| DataInitializerTest | 60초 이내 완료 | 초기화 후 데이터 조회 시간 < 60초 |

---

**문서 버전**: 1.0
**최종 수정일**: 2026-02-23
**테스트 파일 수**: 12개
**테스트 케이스 수**: 48개
