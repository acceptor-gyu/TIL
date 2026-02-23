# 컨텍스트 엔지니어링 피드백 리포트

> **목적**: AI Native Engineer 관점에서 현재 프로젝트의 컨텍스트 엔지니어링 설정을 분석하고, Agent 활용 효율을 높이기 위한 개선 방향을 제시한다.
> **작성일**: 2026-02-21
> **분석 대상**: `CLAUDE.md`, `.claude/agents/*.md`, `.claude/agent-memory/*/MEMORY.md`, `.claude/plans/`

---

## 1. 현황 요약

### 1.1 Agent Pipeline 구조

현재 다음 5개의 전문화 Agent가 순차 파이프라인 형태로 설계되어 있다.

```
analyze-planner → task-runner → test-planner → test-runner → README-manager
     (Opus)         (Opus)        (Sonnet)       (Opus)        (Opus)
```

| Agent | 역할 | 모델 | Memory |
|---|---|---|---|
| analyze-planner | 요구사항 분석 + PLAN.md 생성 | Opus | ✅ (활성) |
| task-runner | PLAN.md 기반 코드 구현 | Opus | ✅ (활성) |
| test-planner | 테스트 시나리오 설계 | Sonnet | ✅ (활성, 참조 파일 누락) |
| test-runner | 테스트 코드 작성 + 실행 | Opus | ✅ (활성) |
| README-manager | 문서 갱신 | Opus | ⚠️ (디렉터리만 존재, MEMORY.md 없음) |

### 1.2 잘 된 점

- **역할 분리가 명확하다**: 각 Agent가 단일 책임을 갖고, Constraints 섹션으로 월권을 명시적으로 금지한다.
- **프로젝트 범위 영속 메모리**: `agent-memory/`를 버전 관리에 포함하여 팀 전체가 Agent의 학습 이력을 공유한다.
- **test-runner Memory가 가장 풍부하다**: 동시성 테스트 패턴, 실패 원인, 구체적인 CLI 명령어까지 기록되어 있어 재사용성이 높다.
- **test-planner Memory의 주의사항 섹션**: `EnrollmentRequest` setter 없음, H2 락 동작 차이 등 실제 삽질에서 나온 인사이트가 담겨 있다.

---

## 2. 발견된 문제점

### 2.1 [HIGH] Agent 간 핸드오프 프로토콜이 암묵적이다

**현상**: 각 Agent가 어떤 파일을 읽고 어떤 파일을 생성하는지 어디에도 명시되어 있지 않다.
**영향**: Agent를 호출하는 사람이 "이 Agent를 언제 어떤 컨텍스트로 실행해야 하는가"를 직접 알아야 한다. 파이프라인이 문서 밖에만 존재한다.

```
현재 (암묵적):
  analyze-planner 실행 → ??? → task-runner 실행

이상적 (명시적):
  analyze-planner 실행 → docs/PLAN.md 생성
  task-runner가 docs/PLAN.md를 읽고 → 코드 구현 → commit
  test-planner가 구현 코드를 읽고 → .claude/plans/{feature}-test-plan.md 생성
  test-runner가 test-plan.md를 읽고 → 테스트 코드 작성 + 실행
```

**권장 개선**: 각 Agent 정의 파일 상단에 `Input` / `Output` 섹션을 추가한다.

```markdown
## Handoff
- Input: `docs/PLAN.md` (analyze-planner가 생성한 계획서)
- Output: 구현 코드 커밋, `.claude/agent-memory/task-runner/MEMORY.md` 갱신
```

---

### 2.2 [HIGH] CLAUDE.md 아키텍처와 실제 구현 패키지가 불일치한다

**현상**: `CLAUDE.md`의 시스템 구조는 `service` 계층을 별도로 표기하지만, task-runner Memory와 test-planner Memory에 기록된 실제 패키지 구조는 다르다.

```
CLAUDE.md 기술:              실제 구현 (agent-memory 기준):
  dbcore ← service ← api      dbcore (entity/repository)
                               api (controller + service 통합)
                               common (dto/exception)
```

**영향**: analyze-planner나 task-runner가 CLAUDE.md를 참조할 때 잘못된 구조를 기준으로 계획을 세울 수 있다. 특히 신규 기능 추가 시 패키지를 잘못 배치할 위험이 있다.

**권장 개선**: CLAUDE.md의 패키지 의존성 규칙을 실제 구현에 맞게 동기화한다.

---

### 2.3 [HIGH] PROBLEM.md 위치 규약이 없다

**현상**: `analyze-planner`가 "PROBLEM.md를 읽어 요구사항을 분석한다"고 명시하지만, PROBLEM.md가 어느 경로에 있어야 하는지 규약이 없다. 실제로 analyze-planner Memory에는 `/Users/luke-gyu/dev/musinsa/docs/PLAN.md`가 출력 경로로 기록되어 있다.

**영향**: 다음 세션에서 새로운 기능을 추가할 때 PROBLEM.md를 어디에 만들어야 하는지 혼란이 생긴다.

**권장 개선**: CLAUDE.md 또는 analyze-planner.md에 입력/출력 파일 경로 규약을 명시한다.

```markdown
## 파일 규약
- 요구사항 입력: `docs/PROBLEM.md`
- 계획 출력: `docs/PLAN.md`
- 테스트 플랜 출력: `.claude/plans/{feature}-test-plan.md`
```

---

### 2.4 [MEDIUM] test-planner Memory가 존재하지 않는 파일을 참조한다

**현상**: `.claude/agent-memory/test-planner/MEMORY.md`의 마지막 줄이 다음을 참조한다.

```markdown
- [수강신청 테스트 플랜 패턴](./enrollment-test-patterns.md)
```

그러나 `.claude/agent-memory/test-planner/enrollment-test-patterns.md`는 존재하지 않는다.

**영향**: test-planner Agent가 다음 세션에서 이 파일을 읽으려 하면 실패한다. 깨진 참조는 Memory 신뢰성을 낮춘다.

**권장 개선**: 해당 파일을 생성하거나 참조를 제거한다.

---

### 2.5 [MEDIUM] README-manager의 MEMORY.md가 없고, Agent 정의에 미완성 템플릿이 포함되어 있다

**현상 1**: `.claude/agent-memory/README-manager/` 디렉터리가 존재하지만 내부가 비어 있다 (MEMORY.md 없음). 다른 Agent와 달리 학습이 전혀 축적되지 않는다.

**현상 2**: `README-manager.md` 하단에 아래와 같은 미완성 템플릿이 그대로 남아 있다.

```markdown
### 설치 및 세팅
- git clone
- cd ...

### 자바 설치 확인
install java
java -v
```

**영향**: Agent 정의 파일이 지시사항인지 템플릿인지 불분명하여, Agent가 이 내용을 "자신이 따라야 할 출력 형식"으로 혼동할 수 있다.

**권장 개선**:
- 템플릿 내용을 `## Output Template` 섹션으로 명확히 분리하거나 별도 파일로 이동한다.
- `MEMORY.md`를 생성하고 현재 README 상태 및 주의사항을 기록한다.

---

### 2.6 [MEDIUM] 모델 배분이 비용/효율 최적화가 되어 있지 않다

**현상**: README-manager가 Opus 모델을 사용한다. README 갱신은 코드 이해보다 문서 편집에 가까운 작업이다.

| Agent | 현재 모델 | 권장 모델 | 이유 |
|---|---|---|---|
| analyze-planner | Opus | Opus | 요구사항 추론, 암묵적 요건 도출 → 고차원 추론 필요 |
| task-runner | Opus | Opus | 복잡한 코드 구현, ADR 결정 → 유지 |
| test-planner | Sonnet | Sonnet | 적절함 |
| test-runner | Opus | Sonnet | 테스트 코드 작성 + 실행은 패턴 반복적, Memory에 패턴 축적됨 |
| README-manager | Opus | Haiku | 문서 편집은 지시 따르기 수준, 고비용 불필요 |

---

### 2.7 [LOW] 오케스트레이터가 없어 파이프라인 실행이 수동이다

**현상**: 5개 Agent를 순서에 맞게 직접 호출해야 한다. 각 Agent의 완료 여부를 수동으로 확인하고 다음 Agent를 트리거해야 한다.

**영향**: 자동화 수준이 낮다. 특히 "이번엔 test-planner까지만" 같은 부분 실행도 규약이 없다.

**권장 개선**: CLAUDE.md에 파이프라인 실행 가이드를 추가하고, 각 단계의 완료 조건(Definition of Done)을 명시한다.

```markdown
## Agent 파이프라인 실행 순서
1. `analyze-planner`: docs/PROBLEM.md 준비 후 실행 → docs/PLAN.md 생성 확인
2. `task-runner`: docs/PLAN.md 완성 후 실행 → 각 Task 커밋 확인
3. `test-planner`: 구현 완료 후 실행 → .claude/plans/{feature}-test-plan.md 생성 확인
4. `test-runner`: test-plan.md 준비 후 실행 → `./gradlew test` 100% 통과 확인
5. `README-manager`: 전체 통과 후 실행 → README.md 최신화 확인
```

---

### 2.8 [LOW] 주 오케스트레이터(Claude Code 세션)의 MEMORY.md가 비어 있다

**현상**: `/Users/luke-gyu/.claude/projects/-Users-luke-gyu-dev-musinsa/memory/MEMORY.md`가 비어 있다. 이 메모리는 Claude Code 메인 세션(Agent를 호출하는 주체)의 장기 기억이다.

**영향**: 메인 세션이 매번 새로 시작하면서 프로젝트 맥락을 재파악해야 한다. "지난 세션에서 무슨 Agent까지 실행했다"는 상태를 기억하지 못한다.

**권장 개선**: 프로젝트 핵심 맥락(기술 스택, 현재 구현 상태, 다음 할 일)을 주 MEMORY.md에 기록한다.

---

## 3. 컨텍스트 엔지니어링 원칙 기반 종합 평가

| 원칙 | 현재 수준 | 평가 |
|---|---|---|
| **역할 명확성** (Role Clarity) | 각 Agent가 명확한 페르소나와 Constraints를 가짐 | ✅ 우수 |
| **컨텍스트 접근성** (Context Accessibility) | CLAUDE.md가 모든 Agent의 시스템 프롬프트에 포함됨 | ✅ 우수 |
| **핸드오프 명시성** (Handoff Explicitness) | 파일 입출력 규약이 암묵적 | ⚠️ 개선 필요 |
| **메모리 활성화** (Memory Activation) | 4/5 Agent에 실용적인 Memory 존재 | ✅ 양호 |
| **메모리 정합성** (Memory Integrity) | 깨진 참조, 빈 디렉터리 존재 | ⚠️ 개선 필요 |
| **비용 최적화** (Cost Optimization) | 단순 작업에 Opus 과다 사용 | ⚠️ 개선 필요 |
| **파이프라인 가시성** (Pipeline Visibility) | 실행 순서와 완료 조건이 문서화되지 않음 | ❌ 미흡 |
| **소스 진실성** (Source of Truth 동기화) | CLAUDE.md ↔ 실제 구현 불일치 | ❌ 미흡 |

---

## 4. 우선순위별 개선 로드맵

### 즉시 수정 (단기)

1. **CLAUDE.md 패키지 구조 동기화**: 실제 구현 패키지 (`dbcore`, `api`, `common`)로 업데이트
2. **test-planner Memory 깨진 참조 제거**: `enrollment-test-patterns.md` 생성 또는 링크 삭제
3. **README-manager MEMORY.md 생성**: 현재 README 상태와 주의사항 기록
4. **README-manager.md 미완성 템플릿 정리**: 지시사항과 출력 형식을 명확히 분리

### 중기 개선

5. **파일 입출력 규약 문서화**: 각 Agent의 Input/Output 파일 경로를 CLAUDE.md 또는 각 agent 파일에 명시
6. **파이프라인 실행 가이드 추가**: CLAUDE.md에 실행 순서, 완료 조건, 부분 실행 방법 추가
7. **모델 배분 최적화**: test-runner → Sonnet, README-manager → Haiku로 변경

### 장기 개선

8. **주 세션 MEMORY.md 활성화**: 프로젝트 전체 상태를 주 MEMORY.md에 꾸준히 기록
9. **Agent 간 상태 공유 파일 도입**: `.claude/state.md` 등으로 현재 파이프라인 진행 상태를 추적

---

## 5. 핵심 메시지

현재 설정의 가장 큰 강점은 **Agent별 메모리가 실제 삽질과 인사이트로 채워져 있다**는 점이다. 특히 test-runner와 test-planner의 Memory는 재사용 가능한 수준이다.

가장 시급한 약점은 **"파이프라인이 머릿속에만 존재한다"**는 것이다. 어느 파일이 어느 Agent의 입력이 되는지, 어느 파일이 출력되는지, 언제 다음 Agent로 넘어가는지가 코드와 문서 어디에도 명시되어 있지 않다. 이를 명문화하는 것이 Agent 활용 효율을 가장 크게 높이는 첫걸음이다.
