# 현재 구현 GAP 분석

> 작성일: 2026-02-20
> 분석 대상: PROBLEM.md 요구사항 vs 현재 구현 코드

---

## 요약

| 우선순위 | 분류 | 항목 | 영향도 |
|----------|------|------|--------|
| P0 | 요구사항 위반 | `/health` 초기화 중 503 반환 | 평가 기준 직접 위반 |
| P0 | 동시성 버그 | 동일 학생 동시 신청 시 학점 초과 가능 | 핵심 비즈니스 규칙 위반 |
| P1 | 테스트 부족 | 동시성 테스트 규모(10명 vs 요구 100명) | 평가 시나리오 미반영 |
| P1 | 데이터 정합성 | `enrolled` 카운터와 실제 enrollment 불일치 | 응답 데이터 신뢰성 |
| P2 | 문서 오류 | README 프로젝트 구조 설명 불일치 | 온보딩 혼란 |
| P2 | 문서 오류 | REQUIREMENTS.md 데이터 모델 실제 코드와 불일치 | 문서 신뢰성 |
| P2 | REST 관행 | `DELETE /api/enrollments`에 body 사용 | 호환성 위험 |
| P3 | 문서 누락 | 인증/인가 결정사항 미문서화 | 설계 결정 미기록 |
| P3 | 제출물 | `prompts/` 파일명 이상 | 평가자 혼란 가능성 |

---

## P0: 즉시 수정 필요

### 1. `/health` 엔드포인트가 초기화 중 503 반환

**PROBLEM.md 원문:**
```
GET /health
응답: HTTP 200 OK
```

**현재 구현** (`HealthController.java:22-26`):
```java
if (dataInitializationStatus.isReady()) {
    return ResponseEntity.ok(Map.of("status", "ok"));
}
return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Map.of("status", "initializing"));  // 503 반환
```

**문제점:**
요구사항은 `/health`가 **항상 200 OK**를 반환해야 한다고 명시한다. 데이터 초기화(최대 1분 소요) 중에는 503이 반환되므로, 평가 시 서버 시작 직후 헬스체크가 실패할 수 있다.

또한 PROBLEM.md는 "GET /health → 200 응답 시점에 API 호출이 가능해야 함"이라고 했으므로, 초기화 완료 여부를 알리는 별도 필드를 200 응답 내에 포함하는 방식이 더 적합하다.

**권장 수정 방향:**
```java
// 200 OK + 상태 정보를 body에 포함
return ResponseEntity.ok(Map.of(
    "status", dataInitializationStatus.isReady() ? "ok" : "initializing"
));
```

---

### 2. 동일 학생 동시 신청 시 학점 초과 가능 (TOCTOU)

**문제점:**
`EnrollmentService.enroll()` (`EnrollmentService.java:63-89`)에서 학점 체크와 시간표 충돌 체크는 순수 애플리케이션 레벨에서 수행된다. DB 레벨 제약이 없기 때문에, 동일한 학생이 서로 다른 두 강좌를 **동시에** 신청하면 다음 레이스 컨디션이 발생한다:

```
[스레드 A: 강좌X 신청]          [스레드 B: 강좌Y 신청]
현재 학점 조회 → 15학점          현재 학점 조회 → 15학점
15 + 3 = 18 ≤ 18 ✓ 통과        15 + 3 = 18 ≤ 18 ✓ 통과
decreaseSeat → 성공             decreaseSeat → 성공
INSERT → 성공                   INSERT → 성공
  ↓                               ↓
 최종 학점: 21학점 (18학점 초과!)
```

**현재 동시성 보호:**
- 중복 신청 (동일 학생, 동일 강좌): UniqueConstraint + 보상 트랜잭션으로 보호 ✓
- 정원 초과: `decreaseSeatIfAvailable` 조건부 UPDATE로 보호 ✓
- **학점/시간표 제약: 보호 없음** ✗

**권장 수정 방향:**
- 학생 레벨의 낙관적/비관적 락 적용
- 또는 `enrollments` 테이블에서 총 학점을 SELECT FOR UPDATE로 조회

---

## P1: 중요 개선 필요

### 3. 동시성 테스트 규모 부족 (10명 vs 100명)

**PROBLEM.md 원문:**
> "정원이 1명 남은 강좌에 100명이 동시에 신청해도, 정확히 1명만 성공해야 합니다."

**현재 테스트** (`EnrollmentConcurrencyIntegrationTest.java:75`):
```java
int participants = 10;  // 요구사항은 100명
```

**문제점:**
10명으로도 동시성 제어가 검증되지만, 평가자가 "100명 동시 신청"을 직접 시나리오로 검증할 때 테스트 코드가 이를 커버하지 않는다. 또한 `EnrollmentConcurrencyTest.java`와 `EnrollmentConcurrencyIntegrationTest.java`의 역할이 중복된다.

**권장 수정 방향:**
```java
int participants = 100;  // PROBLEM.md 명시 수치로 맞춤
```

---

### 4. `enrolled` 카운터와 실제 enrollment 레코드 불일치

**문제점:**
`DataInitializer`는 강좌 생성 시 `seatsLeft`를 랜덤으로 설정하지만 (`DataInitializer.java:157-160`), 실제 `enrollments` 테이블에는 대응하는 레코드를 생성하지 않는다.

```java
// DataInitializer.java:157-159
int enrolled = random.nextInt(0, capacity + 1);
int seatsLeft = capacity - enrolled;
// → 실제 enrollment 레코드는 없음!
```

이로 인해:
- 강좌 목록 API의 `enrolled` 응답값 = `capacity - seatsLeft` (카운터 기반)
- 실제 `enrollmentRepository.countByCourseId(courseId)` = 0

두 값이 일치하지 않아 데이터 정합성 문제가 발생한다. 평가자가 목록 조회로 `enrolled=25`를 확인하고 해당 학생들의 수강신청 내역을 확인하면 내역이 없는 상황이 된다.

**권장 수정 방향:**
- 초기 데이터 생성 시 `seatsLeft = capacity`(빈 강좌)로 통일, 또는
- enrollment 레코드도 같이 생성 (학생과 연결), 또는
- `enrolled`를 항상 `enrollmentRepository.countByCourseId()`로 계산

---

## P2: 개선 권장

### 5. README 프로젝트 구조 설명 오류

**현재 README.md** (`README.md:137-138`):
```
│   └── service/                  # 비즈니스 서비스 계층
│       └── student/              # 학생 서비스
```

**실제 구조:**
```
│   └── api/
│       ├── service/              # 서비스는 api/ 하위에 위치
│       │   ├── EnrollmentService.java
│       │   ├── CourseService.java
│       │   └── ...
```

`service/student/` 경로는 존재하지 않는다. README를 보고 구조를 파악하는 평가자에게 혼란을 줄 수 있다.

---

### 6. REQUIREMENTS.md 데이터 모델과 실제 코드 불일치

**REQUIREMENTS.md** (`docs/REQUIREMENTS.md:31-32`):
```
- Student(학생): id, name, departmentId, maxCredits  ← maxCredits 없음
- Enrollment(수강내역): id, studentId, courseId, semester  ← semester 없음
```

**실제 엔티티:**
- `Student.java`: `id, studentNumber, name, grade, departmentId` (maxCredits 없음)
- `Enrollment.java`: `id, studentId, courseId, createdAt` (semester 없음)

문서가 최신 코드를 반영하지 않는다.

---

### 7. `DELETE /api/enrollments`에 Request Body 사용

**현재 구현:**
```bash
curl -X DELETE "http://localhost:8080/api/enrollments" \
  -H "Content-Type: application/json" \
  -d '{"studentId":1,"courseId":1001}'
```

**문제점:**
HTTP 표준(RFC 7231)에서 DELETE 메서드에 body를 포함하는 것은 "의미적으로 정의되지 않음"이다. 일부 프록시, 로드밸런서, HTTP 클라이언트가 DELETE body를 무시하거나 거부할 수 있다.

**권장 수정 방향 (선택지):**
- `DELETE /api/enrollments?studentId=1&courseId=1001` (쿼리 파라미터)
- `DELETE /api/students/{studentId}/enrollments/{courseId}` (REST 자원 경로)
- `DELETE /api/enrollments/{enrollmentId}` (enrollmentId 기반, enrollment 조회 API 필요)

---

## P3: 보완 고려

### 8. 인증/인가 결정사항 미문서화

PROBLEM.md는 인증/인가를 자유 결정사항으로 두었지만, **결정 사항과 근거를 REQUIREMENTS.md에 반드시 기록**해야 한다.

**현재 상황:**
- 인증/인가 없음 → 누구든 임의의 `studentId`로 다른 학생 수강신청/취소 가능
- REQUIREMENTS.md에 이 결정 사항이 명시되어 있지 않음

**REQUIREMENTS.md에 추가 권장:**
```
## 인증/인가
- 구현하지 않기로 결정
- 근거: 이번 과제의 핵심은 동시성 제어이며, 인증 레이어 추가 시
  구현 복잡도가 크게 증가함. 평가 환경에서 ID를 알면 누구나
  테스트할 수 있어야 하므로 인증 없이 진행.
```

---

### 9. `prompts/` 파일명 이상

**현재 상태:**
```
prompts/
└── *.md   ← 리터럴 별표(*)가 파일명에 포함됨
```

`ls -la prompts/`로 확인하면 파일명이 `*.md`(3481 bytes)이다. 이는 아마도 `prompts/*.md`라는 glob 패턴을 그대로 파일명으로 사용한 것으로 보인다. 내용은 프롬프트 이력이 있으므로 문제는 없으나, 파일명을 의미 있게 변경하는 것을 권장한다.

**권장 파일명:** `prompts/history.md` 또는 `prompts/prompt-log.md`

---

## 동시성 제어 전략 평가

현재 구현된 동시성 제어는 **정원 초과 방지** 측면에서는 견고하다:

| 시나리오 | 처리 방식 | 평가 |
|----------|-----------|------|
| 정원 1인 강좌에 100명 동시 신청 | 조건부 UPDATE (`seatsLeft > 0`) | ✅ 안전 |
| 동일 학생 동일 강좌 동시 중복 신청 | UniqueConstraint + 보상 트랜잭션 | ✅ 안전 |
| 동일 학생 다른 강좌 동시 신청 (학점 초과) | 애플리케이션 레벨만 체크 | ❌ 취약 |
| 동일 학생 다른 강좌 동시 신청 (시간 충돌) | 애플리케이션 레벨만 체크 | ❌ 취약 |

---

## 미구현 및 결정 미기록 항목

PROBLEM.md가 자유 결정사항으로 열어둔 항목 중 코드나 문서에 기록되지 않은 것:

| 항목 | 결정 필요 내용 | 현재 상태 |
|------|---------------|-----------|
| 수강신청 기간 | 기간 제한 여부 | 미구현, 미문서화 |
| 재수강 허용 여부 | 이미 수강한 강좌 재신청 가능 여부 | 미문서화 (현재 허용) |
| 선수과목 | 선수과목 제약 | 미구현, 미문서화 |
| 학년별 수강 제한 | 1학년이 대학원 강좌 수강 가능 여부 | 미구현, 미문서화 |
| 취소 기한 | 수강취소 가능 기간 제한 | 미구현, 미문서화 |
