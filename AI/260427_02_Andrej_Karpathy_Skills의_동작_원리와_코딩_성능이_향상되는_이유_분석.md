# Andrej Karpathy Skills의 동작 원리와 코딩 성능이 향상되는 이유 분석

## 개요

Andrej Karpathy는 2026년 1월 X(구 Twitter)에서 LLM 코딩 에이전트의 구체적인 실패 패턴을 공개적으로 비판했다. 에이전트가 "빠른 주니어 개발자처럼 질문 없이 달려나간다"고 표현한 그의 관찰을 바탕으로, 개발자 Forrest Chang이 `CLAUDE.md` 단일 파일로 이 문제를 해결하는 `andrej-karpathy-skills` 레포지토리를 공개했다. 이 TIL에서는 해당 Skills가 어떤 원리로 동작하며, 왜 실질적인 코딩 성능 향상으로 이어지는지 분석한다.

## 상세 내용

### 1. Skills가 탄생한 배경: Karpathy의 LLM 비판

Karpathy는 2026년 1월 26일 X 포스트에서 LLM 코딩 에이전트의 세 가지 구조적 문제를 지적했다.

> "The models make wrong assumptions on your behalf and just run along with them without checking. They don't manage their confusion, don't seek clarifications, don't surface inconsistencies, don't present tradeoffs, don't push back when they should."

**문제 1: 무언의 가정 (Silent Assumption)**
에이전트는 불명확한 요구사항을 마주쳤을 때 질문하지 않고 자의적으로 해석한다. 이 과정에서 발생한 가정은 개발자에게 공개되지 않아 디버깅 비용이 급증한다.

**문제 2: 과잉 엔지니어링 (Over-Engineering)**
100줄로 해결할 수 있는 문제에 1,000줄의 코드를 작성하고, 불필요한 추상화 레이어와 "미래를 위한" 유연성을 추가한다. 이는 유지보수 부채로 직결된다.

**문제 3: 무관한 코드 수정 (Collateral Edits)**
현재 태스크와 무관한 인접 코드, 주석, 포맷을 "개선"이라는 명목으로 변경한다. 이는 의도치 않은 사이드 이펙트와 코드 리뷰 노이즈를 만든다.

Karpathy 본인도 수 주 만에 "수동 코딩 80% → 에이전트 코딩 80%"로 전환되는 변화를 경험했으나, 이 문제들이 생산성 이득을 갉아먹고 있다고 지적했다.

---

### 2. CLAUDE.md Skills의 동작 원리

#### CLAUDE.md가 Claude Code에서 처리되는 방식

Claude Code는 세션 시작 시 해당 디렉토리의 `CLAUDE.md` 파일을 읽어 **시스템 레벨 컨텍스트**로 주입한다. 이는 매 대화마다 별도로 지시할 필요 없이, 프로젝트 전체에 걸쳐 에이전트의 행동 규칙이 일관되게 적용됨을 의미한다.

```
프로젝트 디렉토리
├── CLAUDE.md          ← 세션 시작 시 자동으로 읽힘
├── src/
│   └── main.java
└── tests/
```

#### Claude Code Skills vs CLAUDE.md 직접 작성

Claude Code의 Skills 기능을 사용하면 `CLAUDE.md`를 프로젝트마다 복사하지 않아도 된다. Skills는 필요한 시점에만 로드되므로 컨텍스트 윈도우 압박이 없다. 반면 `CLAUDE.md`에 직접 넣으면 항상 컨텍스트를 점유한다.

| 구분 | CLAUDE.md 직접 작성 | Skills 방식 |
|------|-------------------|-------------|
| 적용 범위 | 해당 프로젝트만 | 모든 프로젝트에서 호출 가능 |
| 컨텍스트 점유 | 항상 | 필요 시에만 |
| 설치 | 파일 복사 | Claude Code 마켓플레이스 |

---

### 3. 네 가지 핵심 원칙과 성능 향상 메커니즘

#### 원칙 1: Think Before Coding (코딩 전에 생각하기)

**핵심 명령**: "가정하지 마라. 혼란을 숨기지 마라. 트레이드오프를 드러내라."

에이전트가 모호한 요구사항을 마주했을 때 진행하기 전에 명시적으로 가정을 서술하고, 여러 해석 가능성이 있을 경우 복수의 해석을 제시하며, 더 단순한 대안이 있다면 제안한다.

```
Before: "로그인 기능 추가해줘" → (질문 없이) 세션 + 토큰 + OAuth 모두 구현
After:  "로그인 방식이 세션, JWT 토큰, OAuth 중 어느 것인지 확인이 필요합니다.
         가장 단순한 구현은 세션 방식입니다. 어떻게 진행할까요?"
```

**성능 향상 이유**: 잘못된 가정으로 인한 롤백과 재작업 비용을 사전에 제거한다. 한 번 잘못된 방향으로 200줄을 작성하는 것보다 미리 2줄을 확인하는 것이 총 비용이 낮다.

---

#### 원칙 2: Simplicity First (단순함 우선)

**핵심 명령**: "요청받은 것만 해결하는 최소한의 코드. 추측성 코드 금지."

- 요청받지 않은 기능을 추가하지 않는다
- 단 한 곳에서만 사용되는 추상화는 만들지 않는다
- "언젠가 필요할" 에러 핸들링은 제거한다
- 200줄로 작성된 것을 50줄로 재작성할 수 있는지 항상 확인한다

```java
// 과잉 엔지니어링 (에이전트의 기본 성향)
public abstract class AbstractUserFactory<T extends BaseUser> {
    protected abstract T createUser(UserCreationContext context);
    // ... 수십 줄의 불필요한 추상화
}

// Simplicity First 적용 후
public User createUser(String name, String email) {
    return new User(name, email);
}
```

**성능 향상 이유**: 코드베이스가 작을수록 이해, 리뷰, 디버깅 속도가 빠르다. 불필요한 코드는 미래의 컨텍스트 윈도우를 점유하여 에이전트의 추론 품질을 저하시킨다.

---

#### 원칙 3: Surgical Changes (외과적 변경)

**핵심 명령**: "반드시 필요한 곳만 수정하라. 내가 만든 쓰레기만 치워라."

구체적인 규칙:
- 인접한 코드, 주석, 포맷을 "개선"하지 않는다
- 작동 중인 코드를 리팩토링하지 않는다
- 기존 코드 스타일을 그대로 유지한다
- 이미 있던 사용하지 않는 코드를 삭제하지 않는다 (플래그는 허용)
- 자신의 변경으로 인해 고아가 된 import/변수/함수만 제거한다

**성능 향상 이유**: 코드 리뷰 시 "이 변경이 왜 여기 들어갔지?" 하는 노이즈를 제거한다. PR 크기가 줄어들고, 실제 변경 의도가 명확히 드러난다. Git diff가 작을수록 리뷰 품질이 높아진다.

---

#### 원칙 4: Goal-Driven Execution (목표 중심 실행)

**핵심 명령**: "성공 기준을 정의하라. 충족될 때까지 반복하라."

명령형 지시("버그를 고쳐라")를 선언형 목표("이 버그를 재현하는 테스트를 먼저 작성하고, 그 테스트를 통과시켜라")로 전환한다.

```
약한 기준 (Weak Criteria):
"이 기능이 작동하도록 만들어줘"
→ 에이전트가 "작동"의 기준을 스스로 정의 → 불완전한 구현

강한 기준 (Strong Criteria):
"다음 세 가지 케이스를 모두 통과하는 테스트를 작성하고, 테스트가 통과되면 완료"
→ 에이전트가 독립적으로 반복 실행 → 검증 가능한 완료
```

**성능 향상 이유**: LLM은 반복 루프에 매우 강하다. 성공 기준이 명확하면 에이전트가 사람의 개입 없이 스스로 검증하며 개선할 수 있다. 반면 기준이 모호하면 사람이 계속 개입해야 한다.

---

### 4. 실제 동작: Skills 설치와 사용 방법

#### 방법 1: Claude Code Skills 마켓플레이스 (추천)

```bash
# Claude Code에서 Skills 설치
/install-github-app forrestchang/andrej-karpathy-skills
```

설치 후 필요할 때 호출:
```
/karpathy-guidelines
```

#### 방법 2: 프로젝트에 CLAUDE.md 직접 추가

```bash
# 레포지토리 클론
git clone https://github.com/forrestchang/andrej-karpathy-skills

# CLAUDE.md를 프로젝트 루트에 복사
cp andrej-karpathy-skills/CLAUDE.md ./CLAUDE.md
```

#### Cursor 사용자라면

동일한 원칙을 `.cursorrules` 파일로도 활용할 수 있다. 레포지토리에는 Cursor 호환 규칙도 포함되어 있다.

---

### 5. 성능이 향상되는 근본 이유

#### LLM의 구조적 편향과 가이드라인의 대응

LLM은 학습 데이터 특성상 "더 많은 것을 제공하는" 방향으로 편향되어 있다. 코드 작성 시:
- 더 많은 기능 → 더 유용해 보임
- 더 많은 추상화 → 더 "엔지니어링스러워" 보임
- 더 많은 에러 핸들링 → 더 안전해 보임

이 편향은 스택오버플로우, GitHub, 기술 문서 등에서 "좋은 코드"로 평가받은 예시들로부터 학습된 것이다. Karpathy Skills는 이 편향에 직접적으로 역방향 제약을 가한다.

#### 컨텍스트 주입의 효과

CLAUDE.md는 매 세션마다 시스템 프롬프트 수준으로 주입된다. 일반 대화에서 한 번 지시하는 것과 달리, 모든 응답에 걸쳐 규칙이 활성화된 상태를 유지한다. 이는 개발자가 매번 "간단하게 작성해줘"라고 덧붙이지 않아도 되는 효과다.

#### "과속하는 주니어 → 규율 있는 엔지니어"로의 전환

| 특성 | 가이드라인 없는 에이전트 | Karpathy Skills 적용 |
|------|----------------------|---------------------|
| 모호한 요구사항 처리 | 자의적으로 해석 후 진행 | 명시적으로 확인 요청 |
| 코드 복잡도 | 불필요하게 높음 | 최소 필요 수준 |
| 변경 범위 | 관련 없는 코드까지 수정 | 요청된 범위만 수정 |
| 완료 기준 | 모호함 | 테스트로 검증 |

---

### 6. Karpathy의 4-Layer LLM 코딩 워크플로우

Skills 외에도 Karpathy는 AI 코딩 도구를 용도에 따라 4계층으로 활용하는 워크플로우를 제안한다.

```
┌─────────────────────────────────────────────────────┐
│  Layer 4: GPT-5 Pro 등 최강 모델 (10% 미만)          │
│  10분 이상 막힌 버그, 전체 컨텍스트 필요한 심층 분석   │
├─────────────────────────────────────────────────────┤
│  Layer 3: Claude Code (사이드 어시스턴트)             │
│  프롬프트로 명세 가능한 큰 코드 블록 생성              │
├─────────────────────────────────────────────────────┤
│  Layer 2: 하이라이트 & 수정                           │
│  특정 코드 블록 선택 후 리팩토링, 에러 핸들링 추가     │
├─────────────────────────────────────────────────────┤
│  Layer 1: Tab Completion (75% 사용)                  │
│  실시간 자동완성, 인라인 AI 제안                      │
└─────────────────────────────────────────────────────┘
```

핵심 인사이트: "도구를 하나만 잘 쓰는 것이 아니라, 각 도구를 언제 써야 하는지 아는 것이 성공의 열쇠다."

---

### 7. 주의사항과 한계

#### 성능이 항상 향상되지는 않는 상황

- **탐색적 프로토타이핑**: "일단 뭔가 만들어봐"처럼 방향이 미정인 경우, 과도한 질문이 오히려 진행을 늦춘다.
- **사소한 수정**: 한 줄 변경처럼 자명한 태스크에서는 가이드라인 준수 오버헤드가 더 크다.
- **Skills 자체의 트레이드오프**: 가이드라인이 컨텍스트를 점유하므로, 컨텍스트 윈도우가 짧은 환경에서는 역효과가 날 수 있다.

#### Karpathy가 지적한 장기적 리스크

AI 코딩에 지나치게 의존하면 개발자의 수동 코딩 능력과 문법 기억력이 약화될 수 있다. 이는 AI 툴에 장애가 생겼을 때 대응력이 떨어지는 결과로 이어진다.

## 핵심 정리

- Karpathy Skills는 Karpathy가 직접 작성한 것이 아니라, 그의 2026년 1월 X 포스트에서 영감받은 개발자 Forrest Chang의 작업이다
- CLAUDE.md는 세션 시작 시 시스템 컨텍스트로 주입되어, 매 응답에 걸쳐 행동 규칙을 일관되게 적용한다
- 네 가지 원칙(생각 먼저, 단순함 우선, 외과적 변경, 목표 중심 실행)은 LLM의 구조적 편향인 "과잉 제공 성향"에 직접 역방향 제약을 가한다
- 성공 기준이 명확할수록(Goal-Driven) LLM의 자율 반복 루프 능력이 발휘되어 사람 개입 없이 완성도가 높아진다
- Skills 방식은 프로젝트별 CLAUDE.md 복사 없이 모든 프로젝트에 적용 가능하고 컨텍스트 점유도 최소화된다

## 키워드

### `Andrej Karpathy Skills`
Karpathy의 LLM 코딩 비판에서 영감받은 CLAUDE.md 기반 행동 가이드라인 모음. Think Before Coding, Simplicity First, Surgical Changes, Goal-Driven Execution의 네 원칙으로 구성된다. Forrest Chang이 작성하여 Claude Code Skills로 배포했다.

### `CLAUDE.md`
Claude Code가 세션 시작 시 자동으로 읽는 설정 파일. 프로젝트 디렉토리 루트에 위치하며, 시스템 프롬프트 수준으로 에이전트 행동에 영향을 미친다. 팀 컨벤션, 코드 스타일, 행동 규칙 등을 명시한다.

### `Claude Code Skills`
Claude Code의 확장 기능. SKILL.md 파일로 정의되며, 필요한 시점에만 로드되어 컨텍스트를 절약한다. 마켓플레이스를 통해 크로스 프로젝트로 설치 및 공유할 수 있다.

### `Silent Assumption`
LLM이 모호한 요구사항을 마주했을 때 개발자에게 묻지 않고 자의적으로 해석하여 진행하는 패턴. Karpathy가 지적한 핵심 문제 중 하나. Think Before Coding 원칙이 이를 방지하는 것을 목표로 한다.

### `Over-Engineering`
LLM이 요청받은 것 이상의 기능, 추상화, 유연성을 코드에 추가하는 패턴. 100줄로 해결 가능한 문제에 1,000줄을 작성하는 것이 대표적이다. Simplicity First 원칙이 이를 제약한다.

### `Surgical Changes`
현재 태스크와 직접 관련된 코드만 수정하고, 인접한 코드와 스타일을 그대로 유지하는 원칙. 코드 리뷰 노이즈를 제거하고 PR 크기를 최소화하여 협업 효율을 높인다.

### `Goal-Driven Execution`
LLM에게 단계별 지시 대신 검증 가능한 성공 기준을 제공하는 접근 방식. "테스트를 먼저 작성하고 통과시켜라"처럼 선언형 목표를 부여하면, LLM이 사람 개입 없이 자율적으로 반복하며 목표를 달성한다.

### `Behavioral Context Injection`
CLAUDE.md나 Skills를 통해 에이전트의 기본 행동 양식을 수정하는 기법. 매 대화에서 개별적으로 지시하지 않고, 세션 수준에서 일관된 규칙을 주입하는 방식이다.

### `LLM Coding Bias`
LLM이 학습 데이터의 특성상 "더 많이 제공하는" 방향으로 편향되는 현상. 코드 작성 시 불필요한 기능, 추상화, 에러 핸들링을 추가하는 경향으로 나타난다. 스택오버플로우와 GitHub의 고평가 코드에서 학습된 패턴이 원인이다.

### `vibe coding`
Karpathy가 2025년 2월에 처음 제안한 개념. "코드가 존재한다는 것도 잊고 LLM에게 모든 것을 맡기는" 접근 방식. 2026년 현재는 더 엄격한 감독과 검증이 필요한 "agentic engineering"으로 진화 중이다.

## 참고 자료
- [andrej-karpathy-skills GitHub Repository (Forrest Chang)](https://github.com/forrestchang/andrej-karpathy-skills)
- [SKILL.md - 원칙 상세 정의](https://github.com/forrestchang/andrej-karpathy-skills/blob/main/skills/karpathy-guidelines/SKILL.md)
- [How Andrej Karpathy's LLM Workflow is Redefining Developer Productivity - Analytics Vidhya](https://www.analyticsvidhya.com/blog/2025/08/llm-workflow-for-developers/)
- [Andrej Karpathy's Claude Code Skills - Medium](https://medium.com/data-science-in-your-pocket/andrej-karpathys-claude-code-skills-3db42cc634c8)
- [2025 LLM Year in Review - karpathy.bearblog.dev](https://karpathy.bearblog.dev/year-in-review-2025/)
- [Vibe coding - Wikipedia](https://en.wikipedia.org/wiki/Vibe_coding)
