# 요구사항 분석 및 설계 결정

## 개요
- 대상: 대학교 수강신청 시스템 서버
- 목표: 동시 신청 상황에서도 정원 초과 없이 정확한 신청 결과를 보장
- 범위: REST API 서버만 구현 (프론트 제외)

## 핵심 요구사항 정리
- 학생 목록 조회
- 강좌 목록 조회 (전체/학과별, 정원/현재 인원/시간 포함)
- 교수 목록 조회
- 수강신청
- 수강취소
- 내 시간표(이번 학기) 조회
- 학생당 최대 18학점
- 동일 시간대 중복 수강 불가
- 동시성 제어로 정원 초과 방지
- `GET /health`는 200 반환

## 불명확한 부분에 대한 판단
- 학기 구분: 단일 학기 기준으로 처리한다.
- 시간표 충돌 기준: 강의 시간이 겹치면 중복 수강 불가로 판단한다.
- 수강신청 기준: 정원 초과 시 실패, 시간표 충돌/학점 초과 시 실패.
- 수강취소: 신청 성공 후에만 가능하며, 취소 시 정원에서 제외한다.
- 교수 정보: 강좌와 1:N 관계로 가정하고 강좌에 담당 교수 정보를 포함한다.

## 데이터 모델(개념)
- Department(학과): id, name
- Professor(교수): id, name, departmentId
- Student(학생): id, name, departmentId, maxCredits
- Course(강좌): id, name, credits, capacity, schedule, departmentId, professorId
- Enrollment(수강내역): id, studentId, courseId, semester

## 동시성 제어 전략

### 수강 신청 시스템 동시성 제어 설계 (MySQL / H2)


본 내용은 **수강 신청 시스템에서 정원 초과를 방지하기 위한 동시성 제어 메커니즘**을 MySQL(InnoDB) 기준으로 설명하고, **H2 데이터베이스에서도 동일하게 동작하도록** 구현하는 방법을 정리합니다.

### 1. 설계 목표
- 정원(capacity)을 초과한 수강 신청이 **절대 발생하지 않도록 보장**
- 동시 요청(더블 클릭, 재시도, 다중 사용자)에서도 **정합성 유지**
- MySQL 운영 환경과 H2 테스트 환경에서 **동일한 로직으로 동작**

### 2. 핵심 원칙

1. **DB를 최종 진실(Source of Truth)로 사용**
2. 좌석 감소는 반드시 **원자적(atomic) 연산**으로 수행
3. 중복 신청은 **DB 제약(UNIQUE)** 으로 차단
4. 모든 변경은 **트랜잭션** 안에서 처리

### 3. 테이블 설계

3.1. course 테이블
```sql
CREATE TABLE course (
  id BIGINT PRIMARY KEY,
  name VARCHAR(127) NOT NULL,
  ...
  capacity INT NOT NULL,
  seats_left INT NOT NULL
) ENGINE=InnoDB;
```
- `seats_left`: 남은 좌석 수
- 정원을 매번 `COUNT(*)`로 계산하지 않고, 카운터 컬럼을 사용

3.2. enrollment 테이블
```sql
CREATE TABLE registration (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_course_user UNIQUE (course_id, user_id)
) ENGINE=InnoDB;
```
- (course_id, user_id) 유니크 제약으로 중복 신청 방지

### 4. 정원 초과 방지 메커니즘

4.1. 조건부 UPDATE (핵심)

```sql
UPDATE course
SET seats_left = seats_left - 1
WHERE id = ? AND seats_left > 0;
```
- 한 SQL 문으로 좌석 감소 + 조건 검사 수행
- 영향받은 row 수가 1 → 성공
- 0 → 정원 마감
- InnoDB가 해당 row에 **행 락(Row Lock)** 을 걸어 동시성 안전
---

### 5. 트랜잭션 처리 흐름

5.1. 수강 신청 트랜잭션

```sql
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;

-- 1. 좌석 확보
UPDATE course
SET seats_left = seats_left - 1
WHERE id = ? AND seats_left > 0;

-- 2. 수강 등록
INSERT INTO registration(course_id, user_id)
VALUES (?, ?);

COMMIT;
```

5.2. 실패 처리

- 좌석 UPDATE 영향 row = 0
  - 정원 마감 → ROLLBACK
- INSERT 시 UNIQUE 제약 위반 (에러 1062)
  - 이미 신청됨 → ROLLBACK

> 좌석 감소와 INSERT는 같은 트랜잭션에 있으므로, ROLLBACK 시 자동으로 원복됨
>

---

### 6. 트랜잭션 격리 레벨

권장: READ COMMITTED

- 조건부 UPDATE 패턴에서는 충분
- MySQL 기본(REPEATABLE READ)보다 경합이 적음

```sql
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
```

---

### 7. H2에서도 동일하게 동작시키는 방법

7.1. H2를 MySQL 모드로 실행

JDBC URL 예시:

```
jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE
```

- `MODE=MySQL`: MySQL 문법/동작 최대한 호환
- `DB_CLOSE_DELAY=-1`: 메모리 DB 유지

---

7.2. 격리 레벨 명시

- 테스트(H2)와 운영(MySQL) 간 차이를 줄이기 위해 명시적으로 설정

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
```

---

### 8. 주의사항

- H2는 InnoDB와 **락 구현이 완전히 동일하지 않음**
- 단위/통합 테스트는 H2로 충분
- 실제 동시성/경합 테스트는 **Testcontainers + MySQL** 권장

---

### 9. 취소 처리(좌석 복구)

```sql
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;

DELETE FROM registration
WHERE course_id = ? AND user_id = ?;

UPDATE course
SET seats_left = seats_left + 1
WHERE id = ?;

COMMIT;
```

- DELETE 영향 row = 1일 때만 좌석 복구

---

### 10. 요약

- 정원 초과 방지는 **조건부 UPDATE + 트랜잭션**으로 해결
- 중복 신청은 **UNIQUE 인덱스**로 차단
- MySQL / H2 모두 동일한 SQL 흐름 사용 가능
- H2는 반드시 MySQL 모드로 실행
- 운영 안정성 검증은 MySQL 실환경 테스트 병행


## 데이터 생성 전략
- 서버 시작 시 동적으로 생성하며 1분 이내 완료
- 최소 수량:
  - 학과 10개 이상
  - 강좌 500개 이상
  - 학생 10,000명 이상
  - 교수 100명 이상
- 현실적인 이름/패턴 사용
- 완성된 레코드를 정적 파일로 제공하지 않고 런타임 생성

## 에러 처리 원칙
- 공통 에러 응답 형식 제공
- 주요 에러:
  - 정원 초과
  - 시간표 충돌
  - 학점 초과
  - 존재하지 않는 리소스
  - 중복 신청
- 상태 코드 매핑 원칙:
  - 200 OK: 정상 조회
  - 201 Created: 생성 성공
  - 400 Bad Request: 요청 값/형식 오류, 쿼리 파라미터 오류
  - 404 Not Found: 리소스 없음
  - 409 Conflict: 정책 충돌(정원 초과, 중복 신청 등)
  - 500 Internal Server Error: 서버 내부 오류

## 테스트 우선순위
- 동시성 테스트: 동일 강좌 동시 신청 시 정원 초과 방지
- 정책 테스트: 학점 상한, 시간표 충돌
- 기본 기능 테스트: 조회/신청/취소/시간표
