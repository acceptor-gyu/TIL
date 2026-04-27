# Claude Code - gstack, superpowers, GSD

## 개요
Claude Code의 핵심 철학과 설계 원칙인 gstack, superpowers, GSD에 대해 학습한다. 2026년 현재 Claude Code 생태계에서 가장 빠르게 성장 중인 세 가지 프레임워크로, 각각 AI 코딩 에이전트의 서로 다른 문제를 해결한다. Anthropic이 Claude Code를 어떤 방향으로 설계했는지, 각 개념이 실제 코딩 에이전트로서 어떻게 구현되어 있는지 이해한다.

## 상세 내용

### 1. gstack (Generative Stack)

gstack은 Y Combinator CEO인 Garry Tan이 만든 오픈소스 기술 스택이다. Claude Code를 단일 AI 어시스턴트가 아닌 **"가상 엔지니어링 팀"** 으로 변환하는 역할 기반 거버넌스 프레임워크다.

#### 기존 tech stack과의 차이
| 구분 | 기존 Tech Stack | gstack |
|------|----------------|--------|
| 구성 요소 | 언어, 프레임워크, DB | AI 역할, 의사결정 권한 |
| 제약 대상 | 기술 선택 | 결정을 내리는 주체 |
| 실행 주체 | 개발자 | 역할별 AI 에이전트 |

#### 핵심 구성 요소: 23개 전문 역할(Skills)

gstack은 스프린트를 다음 단계로 구성한다: **생각 → 계획 → 구축 → 검토 → 테스트 → 배포 → 성찰**

각 단계는 이전 단계의 결과물을 읽고 다음 단계에 제공하므로 누락이 없다.

- **전략 레이어**: `/office-hours` (제품 재정의), `/plan-ceo-review` (범위 최적화)
- **설계 레이어**: `/design-consultation`, `/design-shotgun` (시각 탐색), `/design-html` (프로덕션 HTML)
- **개발 레이어**: `/plan-eng-review` (아키텍처 리뷰), `/review` (코드 검토)
- **검증 레이어**: `/qa` (브라우저 자동화), `/cso` (보안 감사)
- **배포 레이어**: `/ship` (PR 자동화), `/land-and-deploy` (프로덕션 검증)
- **성찰 레이어**: `/retro` (팀 회고), `/investigate` (디버깅)

#### "Boil the Lake" 원칙
gstack의 핵심 설계 철학이다. "호수도 끓일 수 있다"는 개념으로, 특정 모듈의 테스트 커버리지를 100%로 올리는 것처럼 현실적인 범위를 정의하고 그것을 완전히 달성하는 패턴이다. Ship 단계 이후의 빈틈을 채우는 데 활용한다.

#### 병렬 실행
Conductor를 통해 10-15개의 독립 스프린트를 동시에 운영할 수 있다. 구조화된 프로세스가 혼돈 대신 조율을 가능하게 한다.

---

### 2. Superpowers

Superpowers는 Jesse Vincent가 개발한 **강제 TDD(Test-Driven Development) 파이프라인** 프레임워크다. AI가 빠르게 생성하지만 품질이 불안정한 코드를 안정화하는 데 초점을 맞춘다.

#### Claude Code가 제공하는 핵심 능력들

Claude Code는 터미널, IDE, 데스크톱 앱, 브라우저에서 동작하며 다음 능력을 갖춘다:

1. **파일 시스템 직접 접근 및 편집**: 코드베이스 전체를 읽고, 여러 파일에 걸쳐 수정을 적용
2. **터미널 명령 실행**: 빌드, 테스트, 배포 명령을 직접 실행하고 결과를 평가
3. **웹 검색 및 외부 정보 수집**: 최신 문서와 정보를 실시간으로 참조
4. **Multi-tool 병렬 호출**: 여러 도구를 동시에 호출하여 병렬로 작업 수행
5. **Git 연동**: 변경사항 스테이징, 커밋 메시지 작성, PR 생성
6. **MCP(Model Context Protocol) 연동**: Google Drive, Jira, Slack 등 외부 서비스 연결

#### Superpowers 프레임워크의 7단계 파이프라인

```
브레인스토밍 → 스펙 작성 → 계획 수립 → TDD → 개발 → 리뷰 → 마무리
```

핵심 원칙은 **테스트 먼저 작성**이다. 테스트 전에 작성된 코드는 삭제하고 테스트부터 시작하도록 강제한다. chardet 라이브러리 v7.0.0이 이 방식으로 성능을 41배 향상시킨 사례가 있다.

#### 기존 챗봇 형태 AI 대비 에이전트형 AI의 차별점

기존 챗봇은 대화 → 사용자가 실행이라는 구조였다. 에이전트형 AI인 Claude Code는 다음 루프를 자율적으로 실행한다:

```
관찰(컨텍스트 수집) → 추론(계획 수립) → 실행(도구 호출) → 검증(결과 평가) → 반복
```

개발자는 목표를 설정하고 커밋 여부를 결정하지만, 실행 루프는 독립적으로 동작한다.

---

### 3. GSD (Get Stuff Done)

GSD는 Lex Christopherson이 만든 **컨텍스트 격리(Context Isolation) 솔루션**이다. AI 출력 품질이 대화가 길어질수록 저하되는 문제를 깨끗한 컨텍스트 윈도우로 해결한다.

#### GSD 철학의 핵심: 실행 중심 설계

이름 그대로 "일을 완수하는 것"에 집중한다. 우아한 아키텍처보다 **실제 완성**을 우선시한다.

#### 3단계 순차 프로세스

**1단계: Plan (계획)**
- 코드 작성 전 상세한 명세서를 생성한다
- 명세는 다른 개발자가 추가 질문 없이 구현할 수 있을 정도로 구체적이어야 한다
- 포함 내용: 프로젝트 개요, 기능 목록, 데이터 모델, API 표면, 인증 요구사항, 엣지 케이스, 구현 순서

**2단계: Execute (실행)**
- 계획 세션과 **별도의 새로운 세션**에서 시작한다
- 명세 문서를 단일 정보원으로 활용한다
- `/compact` 명령으로 주기적으로 컨텍스트를 압축한다
- 반복되는 실수, 이전 결정 위반이 발생하면 컨텍스트를 재설정하는 신호로 인식한다

**3단계: Verify (검증)**
- 또 다른 새 세션에서 명세와 구현을 비교한다
- 누락 기능, 명세 위반, 미처리 엣지 케이스를 점검한다

#### 원자적 작업 분해와 컨텍스트 관리

각 단계를 격리된 컨텍스트에서 실행하는 이유:
- 계획 단계의 탐색적 추론이 구현 품질을 저하시키지 않는다
- 구현 중 누적된 노이즈가 검증을 방해하지 않는다
- 각 세션은 "깨끗한 기초"에서 시작한다

#### Headless 모드와 자동화 파이프라인에서의 GSD 적용

Headless 모드는 `--print` 플래그로 활성화한다. 인터랙티브 TUI 없이 stdout으로 출력을 스트리밍한다.

```bash
# CI/CD 파이프라인에서 활용
claude -p "translate new strings into French and raise a PR for review"

# 로그 분석 자동화
tail -200 app.log | claude -p "Slack me if you see any anomalies"

# 보안 검토 자동화
git diff main --name-only | claude -p "review these changed files for security issues"
```

`--allowedTools` 플래그로 허용 도구를 화이트리스트에 추가하면, 승인을 요청할 사람이 없는 환경에서도 안전하게 실행된다.

---

### 4. 실제 활용 방법 및 적합한 프로젝트

#### gstack 활용 방법

**설치 및 시작**
```bash
# gstack 레포지토리 클론 후 CLAUDE.md를 프로젝트에 추가
cp gstack/CLAUDE.md ./CLAUDE.md

# 역할별 슬래시 커맨드 실행
/plan-ceo-review   # 제품 범위 최적화
/design-html       # 프로덕션 HTML 생성
/qa                # 브라우저 자동화 테스트
/ship              # PR 자동 생성
```

**적합한 프로젝트 및 상황**
- **스타트업 초기 단계**: 1-2명이 프론트엔드, 백엔드, 디자인, QA를 모두 담당해야 할 때
- **MVP 빠른 구축**: 여러 역할의 검토가 필요하지만 인력이 부족할 때
- **병렬 스프린트**: 독립적인 기능 10-15개를 동시에 진행해야 할 때
- **부적합한 경우**: 이미 팀 구조와 역할이 명확히 분리된 조직

#### Superpowers 활용 방법

**7단계 파이프라인 실행**
```
1. /brainstorm   - 아이디어 탐색, 기술 선택 논의
2. /spec         - 기능 명세 문서화
3. /plan         - 구현 순서와 아키텍처 계획
4. /tdd          - 테스트 먼저 작성 (코드 없이)
5. /dev          - 테스트를 통과하는 코드 작성
6. /review       - 코드 품질 검토
7. /close        - 문서화, 정리, 커밋
```

**적합한 프로젝트 및 상황**
- **라이브러리/SDK 개발**: 인터페이스 안정성이 중요하고 테스트 커버리지가 높아야 할 때
- **AI 생성 코드 품질 불안정 문제**: 빠른 생성은 되지만 버그가 잦을 때
- **신뢰도 높은 코드베이스 구축**: chardet v7처럼 성능과 신뢰성을 동시에 높여야 할 때
- **부적합한 경우**: 요구사항이 유동적이어서 테스트를 먼저 확정하기 어려운 탐색적 프로토타이핑

#### GSD 활용 방법

**3단계 세션 분리 실행**
```bash
# 1단계: 새 세션에서 명세 작성
claude -p "프로젝트 X의 상세 명세를 작성해줘. 기능 목록, 데이터 모델, API, 엣지 케이스 포함"
# → spec.md 저장

# 2단계: 새 세션에서 구현
claude -p "spec.md를 기반으로 구현해줘" --allowedTools "Edit,Write,Bash"
# 컨텍스트 오염 징후 시 /compact 실행

# 3단계: 새 세션에서 검증
claude -p "spec.md와 현재 구현을 비교해서 누락된 기능과 명세 위반을 찾아줘"
```

**적합한 프로젝트 및 상황**
- **대규모 기능 구현**: 대화가 길어지면서 AI 품질이 점점 저하될 때
- **명세 기반 개발**: 요구사항이 명확하고 사전에 문서화할 수 있을 때
- **CI/CD 자동화**: Headless 모드로 파이프라인에 Claude Code를 통합할 때
- **부적합한 경우**: 소규모 수정이나 빠른 탐색이 필요한 작업 (세션 분리 오버헤드가 크다)

#### 프레임워크 선택 가이드

```
내 팀에 역할이 분리되지 않았다 → gstack
AI가 만든 코드에 버그가 잦다  → Superpowers
대화가 길수록 품질이 떨어진다 → GSD
복잡한 대형 프로젝트          → GSD + gstack 조합
품질과 속도 모두 필요하다     → Superpowers + GSD 조합
```

---

### 5. 세 가지 개념의 연결

#### 프레임워크 비교

| 측면 | Superpowers | GSD | gstack |
|------|------------|-----|--------|
| **제약 대상** | 개발 프로세스 | 실행 환경(컨텍스트) | 의사결정 권한 |
| **핵심 메커니즘** | 의무적 TDD 파이프라인 | 컨텍스트 격리 | 역할 기반 프롬프트 |
| **해결하는 문제** | AI 코드 품질 불안정 | 컨텍스트 오염 | 단일 에이전트의 시야 한계 |
| **적합한 상황** | 규율이 필요한 개발자 | 복잡한 대규모 프로젝트 | 다중 역할 창업자/엔지니어 |

**한 줄 요약**: gstack은 생각하고, GSD는 안정화하며, Superpowers는 실행한다.

#### 세 프레임워크는 경쟁하지 않는다

세 프레임워크는 보완적 관계다. 조직에서 가장 심각한 문제점에 맞는 도구를 선택하면 된다:
- 코드 품질이 문제라면 → Superpowers
- 컨텍스트가 오염되어 품질이 저하된다면 → GSD
- 에이전트 관점의 편향이 문제라면 → gstack

#### Claude Code의 설계 철학

Claude Code 공식 문서에 따르면, 에이전트 루프는 다음 구조를 따른다:
1. 목표를 설정받고 코드베이스를 관찰한다
2. 작업 계획을 수립한다
3. 도구(파일 편집, 터미널 명령, 웹 검색)를 호출하여 실행한다
4. 결과를 검증하고, 실패 시 재시도한다

이 루프를 통해 개발자는 "무엇"을 결정하고, Claude Code는 "어떻게"를 결정한다.

#### 다른 AI 코딩 도구와의 철학적 차이

- **GitHub Copilot**: 코드 완성 도우미. 개발자가 직접 실행을 담당한다
- **Cursor**: IDE 통합 AI. 컨텍스트 인식이 강하지만 에이전트 루프가 제한적이다
- **Claude Code**: 완전한 에이전트. 관찰-추론-실행-검증 루프를 자율적으로 반복하며 목표를 달성한다

## 핵심 정리
- gstack, Superpowers, GSD는 동일한 근본 문제(AI 생성 코드의 신뢰성)를 서로 다른 각도에서 해결한다
- gstack은 의사결정 권한을 역할별로 분리하고, GSD는 컨텍스트를 격리하며, Superpowers는 개발 프로세스에 TDD를 강제한다
- Claude Code의 에이전트 루프(관찰 → 추론 → 실행 → 검증)는 세 프레임워크 모두의 기반이 된다
- Headless 모드와 `--allowedTools` 플래그를 활용하면 CI/CD 파이프라인에 Claude Code를 완전 자동화할 수 있다
- 세 프레임워크는 상호 보완적이며, 조직의 병목 지점에 따라 선택하거나 조합해서 사용한다

## 키워드

### `Claude Code`
Anthropic이 개발한 에이전트형 AI 코딩 도구. 터미널, IDE, 브라우저에서 동작하며 파일 편집, 터미널 명령 실행, 웹 검색을 자율적으로 수행한다. 관찰-추론-실행-검증의 에이전트 루프를 반복하며 목표를 달성한다.

### `gstack`
Y Combinator CEO Garry Tan이 만든 역할 기반 거버넌스 프레임워크. CEO, 엔지니어, 디자이너 등 23개 역할로 Claude Code를 가상 엔지니어링 팀으로 변환한다. "Boil the Lake" 원칙으로 현실적인 범위를 완전히 달성하는 패턴을 구현한다.

### `Generative Stack`
AI를 핵심으로 하는 개발 스택의 개념. 기존 기술 스택(언어, 프레임워크, DB)에 더해 프롬프트, 모델, 도구(tool), 오케스트레이션 레이어가 추가된 구조다.

### `superpowers`
Jesse Vincent가 개발한 강제 TDD 기반 7단계 파이프라인 프레임워크. 브레인스토밍부터 마무리까지 각 단계를 순서대로 강제하여 AI 코드의 품질을 안정화한다.

### `GSD`
"Get Stuff Done"의 약자. Lex Christopherson이 만든 컨텍스트 격리 솔루션. Plan-Execute-Verify의 3단계 각각을 별도의 새로운 Claude 세션에서 실행하여 컨텍스트 오염을 방지한다.

### `AI Coding Agent`
단순 코드 완성을 넘어 목표 달성을 위한 자율적 실행 루프를 갖춘 AI 시스템. Claude Code가 대표적이며, 계획-실행-검증을 반복하며 복잡한 개발 작업을 처리한다.

### `tool use`
AI 에이전트가 파일 시스템, 터미널, 웹 등 외부 도구를 직접 호출하는 능력. Claude Code는 `--allowedTools` 플래그로 허용 도구를 제한할 수 있어 안전한 자동화가 가능하다.

### `autonomy`
에이전트가 사람의 개입 없이 판단하고 실행하는 정도. Claude Code는 허용 도구 범위, 승인 방식 등을 통해 자율성 수준을 조절할 수 있다. 자율성이 높을수록 생산성이 올라가지만 안전 관리가 더 중요해진다.

### `headless mode`
인터랙티브 UI 없이 Claude Code를 실행하는 모드. `--print` 플래그로 활성화하며, stdout으로 출력을 스트리밍한다. CI/CD 파이프라인, 스케줄 태스크, 자동화 스크립트에서 활용한다.

### `AI-native development`
AI 에이전트를 개발 프로세스의 핵심 참여자로 설계하는 개발 방식. 사람이 "무엇"을 결정하고 AI가 "어떻게"를 결정하는 분업 구조가 특징이다. gstack, Superpowers, GSD는 모두 AI-native development를 실현하는 프레임워크다.

## 참고 자료
- [Claude Code 공식 문서](https://code.claude.com/docs/en/overview)
- [gstack GitHub Repository](https://github.com/garrytan/gstack)
- [Superpowers, GSD, and gstack: What Each Claude Code Framework Actually Constrains](https://medium.com/@tentenco/superpowers-gsd-and-gstack-what-each-claude-code-framework-actually-constrains-12a1560960ad)
- [GSD Framework for Claude Code: How to Plan and Build Full Applications](https://www.mindstudio.ai/blog/gsd-framework-claude-code-plan-build-applications)
- [Claude Code as an Autonomous Agent: Advanced Workflows (2026)](https://www.sitepoint.com/claude-code-as-an-autonomous-agent-advanced-workflows-2026/)
