# AI Harness Design Sample

## 개요
Planner-Generator-Evaluator 3단계 하네스 구조를 백엔드 서버 또는 NLP 프로젝트에 실제로 적용하는 방법을 구체적인 코드/설정 예시 중심으로 정리한다. Anthropic의 Multi-Agent 3-Step Harness Design 원칙을 기반으로, 파일 핸드오프, 모델 티어링, Stop Condition 등을 실무 프로젝트에서 어떻게 구현하는지 살펴본다.

## 상세 내용

### 1. 하네스 전체 구조 요약

```
사용자 요구사항 (1~4문장)
        │
        ▼
┌─────────────────────────────────┐
│  Planner (Opus)                 │
│  • 요구사항 → 전체 스펙 확장        │
│  • 스프린트 단위 분해               │
│  • sprint-contract.json 생성     │
└────────────────┬────────────────┘
                 │ sprint-contract.json
                 ▼
┌─────────────────────────────────┐
│  Generator (Sonnet)             │
│  • 스프린트 단위 코드 생성           │
│  • Git 커밋으로 진행 상황 기록       │
│  • claude-progress.txt 갱신      │
│  • evaluation-request.md 작성    │
└────────────────┬────────────────┘
                 │ evaluation-request.md
                 ▼
┌─────────────────────────────────┐
│  Evaluator (Sonnet / Haiku)     │
│  • 독립 검증 (실제 실행 기반)        │
│  • evaluation-report.md 작성     │
│  • PASS → 다음 스프린트            │
│  • FAIL → Generator 피드백       │
└─────────────────────────────────┘
        │ 최대 N회 반복 후
        ▼
  Stop Condition → 완료 or 에스컬레이션
```

**에이전트별 역할과 입출력 아티팩트**

| 에이전트 | 입력 | 출력 | 모델 권장 |
|---------|------|------|---------|
| Planner | 사용자 프롬프트 | sprint-contract.json | Opus |
| Generator | sprint-contract.json, claude-progress.txt | 코드, evaluation-request.md | Sonnet |
| Evaluator | evaluation-request.md, 실행 중인 앱 | evaluation-report.md | Sonnet or Haiku |

---

### 2. Planner 단계: 스프린트 계약 생성

#### Planner 시스템 프롬프트 설계

```
당신은 소프트웨어 아키텍트입니다.
사용자의 짧은 요구사항을 받아 전체 스프린트 계획으로 확장합니다.

규칙:
- 세부 구현 코드를 직접 작성하지 않는다
- 각 스프린트는 독립적으로 검증 가능해야 한다
- 각 스프린트는 3~5개의 검증 기준(acceptance criteria)을 포함한다
- 결과물은 반드시 sprint-contract.json 형식으로 출력한다
- 스프린트 수는 3~6개 사이로 유지한다
```

#### sprint-contract.json 포맷 (백엔드 REST API 예시)

```json
{
  "project": "도서 관리 REST API",
  "tech_stack": {
    "language": "Java 21",
    "framework": "Spring Boot 3.x",
    "database": "PostgreSQL",
    "test": "JUnit 5 + RestAssured"
  },
  "sprints": [
    {
      "id": "sprint-01",
      "title": "도메인 모델 및 데이터베이스 레이어",
      "scope": [
        "Book 엔티티 정의 (id, title, author, isbn, publishedAt)",
        "BookRepository JPA 인터페이스 구현",
        "Flyway 마이그레이션 스크립트 작성"
      ],
      "out_of_scope": [
        "비즈니스 로직 구현 제외",
        "API 엔드포인트 구현 제외"
      ],
      "acceptance_criteria": [
        "AC-01: Book 테이블이 PostgreSQL에 정상 생성된다",
        "AC-02: BookRepository.save()로 데이터 저장이 가능하다",
        "AC-03: isbn 필드에 UNIQUE 제약이 적용된다",
        "AC-04: Flyway 마이그레이션이 애플리케이션 시작 시 자동 실행된다"
      ],
      "handoff_to": "sprint-02"
    },
    {
      "id": "sprint-02",
      "title": "CRUD REST API 구현",
      "scope": [
        "GET /api/books - 전체 목록 조회 (페이지네이션)",
        "GET /api/books/{id} - 단건 조회",
        "POST /api/books - 신규 등록",
        "PUT /api/books/{id} - 수정",
        "DELETE /api/books/{id} - 삭제"
      ],
      "out_of_scope": [
        "인증/인가 제외",
        "검색 기능 제외"
      ],
      "acceptance_criteria": [
        "AC-01: POST /api/books 요청 시 201 Created와 생성된 도서 JSON 반환",
        "AC-02: GET /api/books?page=0&size=10 요청 시 페이지네이션 적용된 목록 반환",
        "AC-03: 존재하지 않는 id 조회 시 404 Not Found 반환",
        "AC-04: isbn 중복 등록 시 409 Conflict 반환",
        "AC-05: 모든 엔드포인트에 대한 통합 테스트 통과"
      ],
      "handoff_to": "sprint-03"
    }
  ]
}
```

#### sprint-contract.yaml 포맷 (NLP 프로젝트 예시)

```yaml
project: "한국어 감성 분석 파이프라인"
tech_stack:
  language: Python 3.11
  framework: PyTorch + HuggingFace Transformers
  model_base: klue/roberta-base
  evaluation: sklearn.metrics

sprints:
  - id: sprint-01
    title: "데이터 전처리 파이프라인"
    scope:
      - "원시 CSV 데이터 로드 및 정제"
      - "KoBERT 토크나이저 적용"
      - "Train/Val/Test 8:1:1 분리"
      - "DataLoader 구성"
    acceptance_criteria:
      - "AC-01: 전처리 후 결측값 0건"
      - "AC-02: 토큰 최대 길이 128 초과 샘플 비율 < 5%"
      - "AC-03: 레이블 분포 로그 출력 확인"
      - "AC-04: DataLoader에서 배치 단위 정상 반환"

  - id: sprint-02
    title: "Fine-tuning 학습 스크립트"
    scope:
      - "klue/roberta-base 기반 분류 헤드 구성"
      - "AdamW 옵티마이저 + 선형 스케줄러"
      - "학습 루프 (5 epoch)"
      - "Val loss 기반 최적 체크포인트 저장"
    acceptance_criteria:
      - "AC-01: Val Accuracy >= 85%"
      - "AC-02: 체크포인트 파일 저장 확인"
      - "AC-03: 학습 곡선 (loss/accuracy) 로그 출력"
```

---

### 3. Generator 단계: 코드 생성과 진행 상황 관리

#### claude-progress.txt 포맷

컨텍스트 윈도우 초기화 또는 새 세션 시작 시 Generator가 참조하는 상태 파일이다.

```
=== HARNESS PROGRESS FILE ===
프로젝트: 도서 관리 REST API
마지막 갱신: 2026-04-19 14:30

=== 완료된 스프린트 ===
[DONE] sprint-01: 도메인 모델 및 데이터베이스 레이어
  - 커밋: a1b2c3d "feat: Book 엔티티 및 JPA 레포지토리 구현"
  - Evaluator 결과: PASS (2026-04-19 13:45)
  - 통과 기준: AC-01, AC-02, AC-03, AC-04 모두 통과

=== 현재 스프린트 ===
[IN_PROGRESS] sprint-02: CRUD REST API 구현
  - 진행 상황: BookController, BookService 구현 완료
  - 미완료: 통합 테스트 작성 필요 (AC-05)
  - 다음 작업: BookControllerTest 작성 후 Evaluator 요청

=== 대기 중인 스프린트 ===
[PENDING] sprint-03: 검색 기능 구현
[PENDING] sprint-04: 인증/인가 추가

=== 환경 정보 ===
실행 포트: 8080
테스트 DB: localhost:5432/books_test
마이그레이션 경로: src/main/resources/db/migration/
```

#### evaluation-request.md 포맷

Generator가 스프린트 구현을 완료한 후 Evaluator에게 전달하는 핸드오프 아티팩트다.

```markdown
# Evaluation Request: sprint-02

## 구현 완료 항목
- BookController: GET/POST/PUT/DELETE 5개 엔드포인트 구현
- BookService: 비즈니스 로직 및 예외 처리 구현
- BookControllerTest: RestAssured 기반 통합 테스트 5개 작성
- 실행 방법: `./gradlew test` 또는 `./gradlew bootRun`

## 검증 요청 항목 (sprint-contract.json 기준)
- [ ] AC-01: POST /api/books → 201 Created + JSON 응답
- [ ] AC-02: GET /api/books?page=0&size=10 → 페이지네이션 응답
- [ ] AC-03: 없는 id 조회 → 404 Not Found
- [ ] AC-04: isbn 중복 등록 → 409 Conflict
- [ ] AC-05: 통합 테스트 전체 통과

## 주요 변경 파일
- src/main/java/.../BookController.java
- src/main/java/.../BookService.java
- src/test/java/.../BookControllerTest.java

## 알려진 제한사항
- DELETE 시 연관 데이터 삭제 처리는 sprint-03에서 구현 예정
```

#### Generator가 Git을 활용하는 패턴

```bash
# 스프린트 시작 시 브랜치 생성
git checkout -b sprint-02/crud-rest-api

# 기능 단위 커밋 (설명적인 메시지)
git add src/main/java/.../BookController.java
git commit -m "feat(sprint-02): BookController CRUD 엔드포인트 구현"

git add src/main/java/.../BookService.java
git commit -m "feat(sprint-02): BookService 비즈니스 로직 및 예외 처리"

git add src/test/java/.../BookControllerTest.java
git commit -m "test(sprint-02): BookController 통합 테스트 추가"

# Evaluator PASS 후 메인 브랜치 머지
git checkout main
git merge sprint-02/crud-rest-api
git tag sprint-02-passed
```

---

### 4. Evaluator 단계: 독립 검증 구현

#### Evaluator 시스템 프롬프트 설계 원칙

```
당신은 독립적인 품질 검증 에이전트입니다.
Generator가 구현한 결과물을 sprint-contract.json의 기준으로 검증합니다.

규칙:
- 코드를 읽는 것만으로 판단하지 않는다. 반드시 직접 실행하여 검증한다
- 각 AC(Acceptance Criteria)에 대해 명확한 PASS/FAIL을 판정한다
- FAIL 판정 시 재현 가능한 구체적인 실패 정보를 제공한다
- Generator의 구현 의도를 추측하여 호의적으로 해석하지 않는다
- 전체 AC 중 하나라도 FAIL이면 전체 스프린트는 FAIL이다
```

#### evaluation-report.md 포맷

```markdown
# Evaluation Report: sprint-02
평가 일시: 2026-04-19 15:20
평가자: Evaluator-Agent (claude-sonnet-4-6)

## 종합 판정: FAIL

## AC별 판정 결과

| AC | 판정 | 세부 내용 |
|----|------|---------|
| AC-01 | PASS | POST /api/books → HTTP 201, Location 헤더 포함 |
| AC-02 | PASS | page/size 파라미터 정상 동작, totalElements 포함 |
| AC-03 | PASS | 존재하지 않는 id → HTTP 404, 에러 메시지 포함 |
| AC-04 | FAIL | isbn 중복 등록 시 500 Internal Server Error 반환 (기대: 409 Conflict) |
| AC-05 | FAIL | `./gradlew test` 실행 결과: 5개 중 1개 실패 |

## FAIL 항목 상세

### AC-04: isbn 중복 등록 예외 처리 누락
- 재현 방법:
  ```bash
  curl -X POST http://localhost:8080/api/books \
    -H "Content-Type: application/json" \
    -d '{"title":"테스트","author":"작가","isbn":"978-89-01-12345-6"}'
  # 동일 요청 재전송 시
  curl -X POST http://localhost:8080/api/books \
    -H "Content-Type: application/json" \
    -d '{"title":"테스트","author":"작가","isbn":"978-89-01-12345-6"}'
  ```
- 실제 응답: HTTP 500, `DataIntegrityViolationException` 스택 트레이스
- 기대 응답: HTTP 409, `{"error": "이미 등록된 ISBN입니다"}`
- 수정 방향: BookService에서 DataIntegrityViolationException 캐치 후 409로 변환

### AC-05: 통합 테스트 실패
- 실패 테스트: `BookControllerTest.isbn_중복_등록_시_409_반환`
- 원인: AC-04와 동일한 예외 처리 누락

## 다음 단계
Generator는 위 2개 항목을 수정 후 재평가를 요청하세요.
재시도 횟수: 1/3
```

#### 백엔드 API 검증 스크립트 (Evaluator가 실행하는 패턴)

```bash
#!/bin/bash
# evaluate-sprint-02.sh

BASE_URL="http://localhost:8080"
PASS_COUNT=0
FAIL_COUNT=0

echo "=== Sprint-02 Evaluation Start ==="

# AC-01: POST /api/books → 201 Created
echo "[AC-01] POST /api/books..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X POST $BASE_URL/api/books \
  -H "Content-Type: application/json" \
  -d '{"title":"테스트 도서","author":"홍길동","isbn":"978-89-01-99999-1"}')

if [ "$RESPONSE" -eq 201 ]; then
  echo "  PASS: HTTP 201 반환"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  FAIL: HTTP $RESPONSE 반환 (기대: 201)"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# AC-03: 없는 id 조회 → 404
echo "[AC-03] GET /api/books/99999..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" $BASE_URL/api/books/99999)

if [ "$RESPONSE" -eq 404 ]; then
  echo "  PASS: HTTP 404 반환"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  FAIL: HTTP $RESPONSE 반환 (기대: 404)"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# AC-05: 통합 테스트 실행
echo "[AC-05] ./gradlew test..."
./gradlew test --quiet
if [ $? -eq 0 ]; then
  echo "  PASS: 전체 테스트 통과"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  FAIL: 테스트 실패"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""
echo "=== 결과: PASS $PASS_COUNT / FAIL $FAIL_COUNT ==="
[ $FAIL_COUNT -eq 0 ] && echo "종합 판정: PASS" || echo "종합 판정: FAIL"
```

#### NLP 프로젝트 Evaluator 판정 기준 예시

```python
# nlp_evaluator.py
import json
from sklearn.metrics import classification_report

THRESHOLDS = {
    "accuracy": 0.85,
    "macro_f1": 0.80,
    "positive_recall": 0.78,
    "negative_recall": 0.78,
}

def evaluate_sprint(pred_path: str, label_path: str) -> dict:
    preds = load_predictions(pred_path)
    labels = load_labels(label_path)

    report = classification_report(labels, preds, output_dict=True)
    results = {}

    results["accuracy"] = {
        "value": report["accuracy"],
        "threshold": THRESHOLDS["accuracy"],
        "pass": report["accuracy"] >= THRESHOLDS["accuracy"],
    }
    results["macro_f1"] = {
        "value": report["macro avg"]["f1-score"],
        "threshold": THRESHOLDS["macro_f1"],
        "pass": report["macro avg"]["f1-score"] >= THRESHOLDS["macro_f1"],
    }

    overall_pass = all(v["pass"] for v in results.values())

    return {
        "sprint_id": "sprint-02",
        "overall": "PASS" if overall_pass else "FAIL",
        "metrics": results,
    }
```

---

### 5. 모델 티어링 전략

작업 특성에 맞게 모델을 배정하여 비용과 품질을 동시에 최적화한다.

| 에이전트 | 권장 모델 | 이유 |
|---------|---------|------|
| Planner | claude-opus-4 | 전략적 사고, 복잡한 스펙 분해, 1회성 실행 |
| Generator | claude-sonnet-4-6 | 반복적 코드 생성, 품질과 속도의 균형 |
| Evaluator (API) | claude-haiku-4 | 규칙 기반 판정, 빠른 피드백 루프 |
| Evaluator (NLP 메트릭) | 코드 직접 실행 | LLM 불필요, 수치 기반 자동 판정 |

**비용 시나리오 비교**

```
[전략 A] 모든 단계에 Opus 사용
  - Planner: $2
  - Generator: $15 × 5스프린트 × 평균 2회 = $150
  - Evaluator: $5 × 5스프린트 × 평균 2회 = $50
  총 비용: ~$202

[전략 B] 모델 티어링 적용
  - Planner: $2 (Opus 유지)
  - Generator: $4 × 5스프린트 × 평균 2회 = $40 (Sonnet)
  - Evaluator: $0.5 × 5스프린트 × 평균 2회 = $5 (Haiku)
  총 비용: ~$47 (약 77% 절감)
```

---

### 6. Stop Condition 설계

#### 재시도 횟수 기반 Stop Condition

```python
# harness_runner.py
MAX_RETRIES = 3

def run_sprint(sprint_id: str) -> str:
    for attempt in range(1, MAX_RETRIES + 1):
        print(f"[{sprint_id}] 시도 {attempt}/{MAX_RETRIES}")

        # Generator 실행
        generator_result = run_generator(sprint_id)

        # Evaluator 실행
        eval_result = run_evaluator(sprint_id, generator_result)

        if eval_result["overall"] == "PASS":
            print(f"[{sprint_id}] PASS (시도 {attempt}회)")
            update_progress(sprint_id, "DONE")
            return "PASS"

        print(f"[{sprint_id}] FAIL - 피드백 전달 후 재시도")
        write_feedback_to_generator(eval_result)

    # Stop Condition 도달
    print(f"[{sprint_id}] 최대 재시도 횟수 초과 → 에스컬레이션")
    trigger_human_escalation(sprint_id, eval_result)
    return "ESCALATED"
```

#### 비용 상한 기반 Stop Condition

```python
COST_CEILING_USD = 50.0
current_cost = 0.0

def check_cost_limit(response) -> bool:
    global current_cost
    # Anthropic API 응답에서 토큰 사용량 기반 비용 계산
    input_cost = response.usage.input_tokens * 0.000003
    output_cost = response.usage.output_tokens * 0.000015
    current_cost += input_cost + output_cost

    if current_cost >= COST_CEILING_USD:
        print(f"비용 상한 도달: ${current_cost:.2f} >= ${COST_CEILING_USD}")
        trigger_human_escalation("COST_CEILING_REACHED")
        return False
    return True
```

#### 품질 임계값 기반 조기 종료

```python
# 3회 연속 동일한 AC가 FAIL이면 구조적 문제로 판단하여 에스컬레이션
def detect_stuck_loop(failure_history: list[dict]) -> bool:
    if len(failure_history) < 3:
        return False

    recent = failure_history[-3:]
    failed_acs = [set(r["failed_acs"]) for r in recent]

    # 3회 연속 동일한 AC 실패 = 구조적 문제
    if failed_acs[0] == failed_acs[1] == failed_acs[2]:
        print(f"동일한 AC 3회 연속 실패: {failed_acs[0]}")
        return True
    return False
```

---

### 7. 파일 기반 핸드오프 아티팩트 전체 구조

에이전트 간 직접 통신 없이 파일만으로 상태를 전달하는 전체 디렉토리 구조다.

```
project-root/
├── .harness/
│   ├── sprint-contract.json       # Planner 출력 → Generator/Evaluator 입력
│   ├── claude-progress.txt        # Generator 갱신 → 새 세션 시작 시 참조
│   ├── sprints/
│   │   ├── sprint-01/
│   │   │   ├── evaluation-request.md   # Generator 출력 → Evaluator 입력
│   │   │   └── evaluation-report.md    # Evaluator 출력 → Generator 입력
│   │   └── sprint-02/
│   │       ├── evaluation-request.md
│   │       ├── evaluation-report-r1.md  # 1차 평가
│   │       └── evaluation-report-r2.md  # 재시도 후 2차 평가
│   └── escalation-log.md          # 에스컬레이션 기록
└── src/ (실제 소스 코드)
```

**핸드오프 원칙: 최소 정보 전달**

```
나쁜 예: 이전 에이전트의 전체 대화 컨텍스트를 그대로 넘김
         → 토큰 폭발, 불필요한 정보로 인한 판단 오염

좋은 예: 다음 에이전트가 작업을 시작하는 데 필요한 정보만 구조화
         → sprint-contract.json + evaluation-request.md 조합으로
            Evaluator는 Generator의 전체 구현 과정을 알 필요 없음
```

---

### 8. 안티패턴과 해결책

**안티패턴 1: Generator가 자체 평가 후 PASS 선언**

```
문제: "구현을 완료했습니다. 모든 기준을 충족합니다."
     → 자기 편향(Self-Evaluation Bias), 실제로는 AC-04 미충족

해결: Evaluator 에이전트를 별도 컨텍스트에서 실행하고
     독립 판정 결과만을 PASS/FAIL 기준으로 삼는다
```

**안티패턴 2: 스프린트 없이 전체 구현을 한 번에 시도**

```
문제: "전체 시스템을 한 번에 구현하겠습니다."
     → 컨텍스트 고갈, 오류 추적 불가, 부분 완료 상태 복구 불가

해결: sprint-contract.json으로 스프린트 단위 분해
     각 스프린트 완료 후 Git 태그로 안전한 복구 지점 확보
```

**안티패턴 3: 단일 에이전트에 멀티 에이전트 구조 강제 적용**

```
문제: 간단한 CRUD API를 3단계 하네스로 구현
     → 불필요한 비용 증가, 복잡도만 높아짐

해결: 단순 작업 (1~2시간 이내 구현)은 단일 에이전트로 충분
     복잡한 장기 작업 (8시간 이상, 스프린트 3개 초과)에만 하네스 적용
```

**안티패턴 4: 핸드오프 파일 없이 전체 컨텍스트 전달**

```
문제: Evaluator에게 Generator의 전체 구현 과정을 컨텍스트로 전달
     → 토큰 낭비, Evaluator가 Generator의 의도를 과도하게 반영

해결: evaluation-request.md에 검증에 필요한 최소 정보만 정리하여 전달
     Evaluator는 sprint-contract.json 기준만으로 독립 판정
```

---

## 핵심 정리
- sprint-contract.json은 Planner가 정의하고 Generator와 Evaluator가 모두 참조하는 단일 진실 공급원(Single Source of Truth)이다
- evaluation-request.md와 evaluation-report.md는 Generator-Evaluator 간 파일 기반 핸드오프의 핵심 아티팩트이며, 전체 컨텍스트 대신 최소 정보만 포함한다
- Stop Condition은 재시도 횟수(3회), 비용 상한, 동일 실패 반복 감지 3가지를 조합하여 무한 루프와 비용 폭발을 방지한다
- 모델 티어링은 Planner(Opus) → Generator(Sonnet) → Evaluator(Haiku) 순으로 적용하여 비용을 최대 77% 절감할 수 있다
- 단순 작업에는 하네스를 적용하지 않는 것이 최선이며, 복잡한 장기 작업에서만 3단계 하네스가 비용 대비 품질 이점을 발휘한다

## 키워드

### Harness Design (하네스 설계)
멀티 에이전트 시스템에서 에이전트의 실행, 컨텍스트 관리, 핸드오프, 검증을 통제하는 외부 스캐폴딩 구조. 하네스의 품질이 멀티 에이전트 시스템 전체의 신뢰성을 결정한다.

### Planner-Generator-Evaluator
Anthropic이 장기 실행 코딩 작업을 위해 설계한 3단계 하네스의 에이전트 구성. Planner는 스펙을 정의하고, Generator는 구현하며, Evaluator는 독립적으로 검증한다. GAN(Generative Adversarial Network)에서 영감을 받은 구조다.

### 3-Step Harness
Planner-Generator-Evaluator 3단계로 구성된 멀티 에이전트 하네스 패턴. 각 에이전트가 독립적인 역할을 가지며, 파일 기반 핸드오프 아티팩트로 상태를 전달한다.

### Multi-Agent (멀티 에이전트)
단일 LLM 호출이 아닌, 여러 에이전트가 역할을 분담하여 협업하는 아키텍처. 단순 작업에는 오버엔지니어링이 될 수 있으므로, 복잡한 장기 실행 작업에서만 적용한다.

### Sprint Contract (스프린트 계약)
Generator와 Evaluator가 구현 전에 합의하는 스프린트 단위 검증 기준 문서. JSON 또는 YAML 형식으로 구현 범위(scope), 제외 범위(out_of_scope), 검증 기준(acceptance_criteria)을 정의한다.

### File-based Handoff (파일 기반 핸드오프)
에이전트 간 직접 통신 없이 파일을 통해 상태를 전달하는 패턴. sprint-contract.json, claude-progress.txt, evaluation-request.md, evaluation-report.md 등의 아티팩트로 구성된다. 에이전트 독립성 보장과 중간 상태 복구를 가능하게 한다.

### Model Tiering (모델 티어링)
작업 복잡도에 따라 다른 성능과 비용의 모델을 배정하는 전략. Planner에는 Opus(전략적 사고), Generator에는 Sonnet(반복 구현), Evaluator에는 Haiku(규칙 기반 판정)를 배정하여 비용 효율성과 품질을 동시에 확보한다.

### Stop Condition (종료 조건)
에이전트 루프가 무한히 반복되는 것을 방지하기 위한 종료 조건. 최대 재시도 횟수, 비용 상한, 동일 실패 반복 감지 3가지를 조합하여 구현한다. 한계 도달 시 사람에게 에스컬레이션하거나 작업을 종료한다.

### Context Isolation (컨텍스트 격리)
각 에이전트가 독립적인 컨텍스트 창을 가지며, 에이전트 간 전달 정보를 최소화하는 원칙. Evaluator는 Generator의 구현 과정을 모르고 sprint-contract.json 기준만으로 독립 판정한다.

### Independent Evaluation (독립 평가)
Generator와 분리된 Evaluator 에이전트가 결과물을 검증하는 원칙. 자기 편향(Self-Evaluation Bias)을 제거하고, Evaluator 프롬프트 튜닝이 Generator의 자기비판 프롬프트보다 실질적인 품질 향상 효과를 낸다.

## 참고 자료
- [Anthropic Multi-Agent 3-Step Harness Design](./260419_01_Anthropic_Multi-Agent_3-Step_Harness_Design.md)
- [하네스 엔지니어링 (Harness Engineering)](./260414_01_Harness_Engineering.md)
- [하네스 아키텍처 (Harness Architecture)](./260406_02_하네스_아키텍처.md)
- [Harness design for long-running application development - Anthropic Engineering](https://www.anthropic.com/engineering/harness-design-long-running-apps)
- [The GAN-Style Agent Loop: Deconstructing Anthropic's Harness Architecture - Epsilla Blog](https://www.epsilla.com/blogs/anthropic-harness-engineering-multi-agent-gan-architecture)
- [agentic-sprint: Autonomous multi-agent development system for Claude Code](https://github.com/damienlaine/agentic-sprint)
