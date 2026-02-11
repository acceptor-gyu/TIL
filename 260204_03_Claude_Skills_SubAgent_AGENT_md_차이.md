# Claude Skills, SubAgent, AGENT.md의 차이

## 개요
Claude Code에서 제공하는 확장 기능인 Skills, SubAgents, 그리고 Memory 파일(CLAUDE.md)의 개념과 차이점, 각각의 사용 시점을 정리합니다.

## 상세 내용

### 1. Claude Skills

#### 정의
**Skills**는 Claude Code에서 재사용 가능한 명령어이자 지식 모음입니다. `SKILL.md` 파일로 정의되며, Claude가 자동으로 또는 수동으로(슬래시 커맨드) 호출할 수 있습니다.

#### 용도
- 반복되는 작업의 자동화 (예: 배포, 리뷰, 코드 생성)
- 도메인 지식 또는 컨벤션 공유
- 슬래시 커맨드 생성 (예: `/add-til`, `/review`, `/commit`)
- 복잡한 워크플로우 재사용

#### 파일 구조

```
.claude/skills/
├── add-til/
│   └── SKILL.md
├── deploy/
│   └── SKILL.md
└── commit/
    └── SKILL.md
```

#### SKILL.md 형식

```yaml
---
name: add-til
description: 새로운 TIL 항목을 생성하고 README에 자동으로 추가합니다
disable-model-invocation: true
argument-hint: [카테고리] [제목]
---

# 새로운 TIL 항목 추가

## 수행 작업
1. 오늘 날짜 확인 (YYMMDD 형식)
2. 파일 번호 결정
3. 새 파일 생성
4. README 업데이트

## 사용법
/add-til [카테고리] [제목]
```

#### 주요 설정 옵션

| 옵션 | 설명 | 예시 |
|------|------|------|
| `name` | 스킬 이름 | `add-til` |
| `description` | Claude가 언제 사용할지 결정하는 설명 | "새 TIL 항목 생성" |
| `disable-model-invocation` | `true`면 사용자만 호출 가능 | 배포, 민감한 작업 |
| `user-invocable` | `false`면 Claude만 호출 가능 | 배경 지식 스킬 |
| `context` | `fork`면 별도 subagent에서 실행 | 독립적 작업 |
| `allowed-tools` | 접근 가능한 도구 제한 | `["Read", "Write"]` |
| `argument-hint` | 인자 힌트 표시 | `[카테고리] [제목]` |

#### 호출 방식

```bash
# 1. 사용자 수동 호출 (슬래시 커맨드)
/add-til Database Redis 캐싱 전략

# 2. Claude 자동 호출
# description을 보고 Claude가 필요성을 인식하면 자동 호출
# (disable-model-invocation: false 일 때)

# 3. 다른 스킬에서 호출
# context: fork 설정 시 subagent로 실행
```

#### Skills의 스코프 우선순위

```
Enterprise (.claude/skills/)  # 최우선
    ↓
Project (.claude/skills/)
    ↓
Personal (~/.claude/skills/)  # 최저 우선
```

---

### 2. Claude SubAgents

#### 정의
**SubAgents**는 특정 작업을 위해 Claude가 위임할 수 있는 전문화된 AI 에이전트입니다. 독립적인 컨텍스트, 커스텀 시스템 프롬프트, 제한된 도구 접근을 가집니다.

#### 용도
- 탐색/분석 작업을 메인 컨텍스트에서 격리
- 특정 작업에 특화된 지시사항 적용
- 병렬 작업 실행 (여러 subagent 동시 실행)
- 컨텍스트 비용 절감 (verbose 결과가 main에 쌓이지 않음)

#### 내장 SubAgents

| Agent | 역할 | 특징 |
|-------|------|------|
| **Explore** | 빠른 코드 검색/분석 | Haiku 모델, 읽기 전용 도구 |
| **Plan** | Plan Mode에서 컨텍스트 수집 | 읽기 전용 도구 |
| **General-purpose** | 복잡한 다단계 작업 | 모든 도구 접근 |
| **Bash** | 커맨드 실행 전문 | Bash 도구 특화 |

#### SubAgent 정의 (Agent SDK)

```python
from claude_code_sdk import Agent, AgentDefinition

agents = {
    "code-reviewer": AgentDefinition(
        description="Expert code reviewer for Python projects",
        prompt="You are a senior code reviewer. Focus on...",
        tools=["Read", "Grep", "Glob"],  # 읽기 전용
        model="sonnet"
    ),
    "db-analyzer": AgentDefinition(
        description="Database schema and query analyzer",
        prompt="You analyze database schemas...",
        tools=["Read", "Bash"],
        model="opus"
    )
}
```

#### SubAgent 정의 (.claude/agents/*.md)

```yaml
---
name: code-reviewer
description: 코드 품질 검토 및 개선 제안
tools:
  - Read
  - Grep
  - Glob
model: sonnet
---

# Code Reviewer Agent

## Role
당신은 시니어 코드 리뷰어입니다.

## Guidelines
- 코드 품질, 가독성, 유지보수성 검토
- 보안 취약점 체크
- 성능 개선점 제안
```

#### Task Tool로 SubAgent 호출

Claude Code 내부에서 Task 도구를 통해 subagent를 호출합니다:

```
Task tool 호출:
- subagent_type: "Explore"
- prompt: "Where are API endpoints defined?"
- model: "haiku" (선택사항)
```

#### SubAgent의 특징

1. **독립적 컨텍스트**: main conversation과 분리
2. **결과 요약**: verbose 결과를 요약해서 반환
3. **도구 제한**: 필요한 도구만 접근 허용
4. **병렬 실행**: 여러 subagent 동시 실행 가능
5. **비용 최적화**: 작업별 적합한 모델 선택

---

### 3. CLAUDE.md (Memory 파일)

#### 정의
**CLAUDE.md**는 프로젝트 메모리 파일입니다. 프로젝트 정보, 컨벤션, 자주 사용하는 명령어 등을 저장하여 Claude가 항상 참고할 수 있게 합니다.

#### 용도
- 프로젝트 아키텍처 및 구조 설명
- 코딩 컨벤션 및 스타일 가이드
- 자주 사용하는 명령어 기록
- 팀 규칙 및 정책 공유

#### 파일 위치

```
./CLAUDE.md              # 프로젝트 루트 (가장 일반적)
./.claude/CLAUDE.md      # .claude 디렉토리 내
~/CLAUDE.md              # 홈 디렉토리 (전역 설정)
```

#### CLAUDE.md 예시

```markdown
# Project Memory

## Architecture
- Spring Boot 3.x + Kotlin
- Layered Architecture (Controller → Service → Repository)
- PostgreSQL + Redis

## Conventions
- Package: com.example.{domain}.{layer}
- Naming: camelCase for methods, PascalCase for classes
- Tests: *Test.kt for unit, *IntegrationTest.kt for integration

## Common Commands
- Build: ./gradlew build
- Test: ./gradlew test
- Run: ./gradlew bootRun

## Important Notes
- API 응답은 항상 ApiResponse<T> 형태로 래핑
- 예외 처리는 GlobalExceptionHandler에서 중앙 관리
```

#### AGENT.md에 대하여

**참고**: 현재 Claude Code 공식 문서에서 "AGENT.md"라는 전용 파일은 명시적으로 정의되지 않습니다.

- 일부 프로젝트에서 Agent 관련 설정을 중앙화하기 위해 별도로 사용하는 관례
- SubAgent 공통 지시사항 정의
- 팀 차원의 Agent 정책 관리

실제로는 `.claude/agents/*.md` 형태로 SubAgent를 정의하거나, `CLAUDE.md`에 Agent 관련 지침을 포함시키는 것이 일반적입니다.

---

### 4. 세 가지의 비교

#### 비교 표

| 구분 | Skills | SubAgents | CLAUDE.md |
|------|--------|-----------|-----------|
| **형식** | SKILL.md (YAML + Markdown) | .md (YAML + Markdown) | 순수 Markdown |
| **실행 위치** | Main 또는 forked subagent | 독립 에이전트 인스턴스 | 메모리 로드 (컨텍스트 주입) |
| **호출 방식** | `/skill-name` 또는 자동 | Claude 자동 위임 또는 명시 | 자동 로드 (항상) |
| **컨텍스트 격리** | Optional (`context: fork`) | 필수 격리 | 격리 안 함 |
| **목적** | 작업 자동화 / 지식 재사용 | 특화된 작업 위임 | 프로젝트 기본 정보/규칙 |
| **도구 제한** | `allowed-tools` | `tools` 필드 | N/A (전체 도구 접근) |
| **반환값** | 결과 통합 | 요약만 반환 | N/A (메모리) |
| **호출 주체** | 사용자 또는 Claude | Claude | 시스템 자동 |

#### 개념적 차이

```
┌─────────────────────────────────────────────────────────────┐
│                    Claude Code Session                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  CLAUDE.md (항상 로드됨 - 배경 지식)                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Project info, conventions, common commands          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  Skills (필요 시 호출 - 작업 자동화)                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ /add-til → TIL 생성 workflow 실행                     │    │
│  │ /deploy → 배포 프로세스 실행                             │    │
│  │ /commit → 커밋 메시지 생성 및 커밋                        │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  SubAgents (위임 - 독립 실행)                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │   Explore    │  │ code-reviewer│  │ db-analyzer  │       │
│  │   (Haiku)    │  │   (Sonnet)   │  │   (Opus)     │       │
│  │  읽기 전용     │  │    코드 리뷰   │   │   DB 분석     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│        ↑                 ↑                 ↑                │
│        └─────────────────┴─────────────────┘                │
│                Task tool로 위임                               │
└─────────────────────────────────────────────────────────────┘
```

---

### 5. 사용 시점 가이드

#### Skills를 사용하는 경우

1. **반복되는 수동 작업**
   ```yaml
   # /add-til - TIL 항목 생성
   # /deploy - 배포 프로세스
   # /review - PR 리뷰
   # /commit - 커밋 생성
   ```

2. **배경 지식 제공** (Claude만 사용)
   ```yaml
   ---
   name: api-conventions
   user-invocable: false
   description: API 설계 패턴 가이드
   ---
   ```

3. **워크플로우 템플릿**
   - 스텝 바이 스텝 가이드
   - 예시, 템플릿 포함

#### SubAgents를 사용하는 경우

1. **Verbose 결과의 격리**
   - 테스트 실행 (많은 로그)
   - 문서 탐색 (많은 파일 읽기)
   - 데이터 분석 (큰 결과)

2. **특화된 도구 제한** (보안)
   ```yaml
   name: code-reviewer
   tools: [Read, Grep, Glob]  # 수정 불가
   ```

3. **병렬 처리**
   - Authentication / Database / API 동시 분석
   - 각 subagent가 독립 실행

4. **비용 최적화**
   - 간단한 검색: Haiku (Explore agent)
   - 복잡한 분석: Opus (전용 subagent)

#### CLAUDE.md를 사용하는 경우

1. **프로젝트 기본 정보**
   - 아키텍처 개요
   - 디렉토리 구조
   - 빌드/테스트 커맨드

2. **팀 컨벤션**
   - 코드 스타일
   - 네이밍 규칙
   - Git workflow

3. **항상 참고할 규칙**
   - API 설계 원칙
   - 에러 처리 방침
   - 보안 가이드라인

---

### 6. 실전 프로젝트 구조 예시

```
my-project/
├── .claude/
│   ├── CLAUDE.md              # 프로젝트 메모리
│   ├── agents/
│   │   ├── code-reviewer.md   # 읽기 전용 코드 리뷰
│   │   ├── db-analyzer.md     # DB 쿼리 분석
│   │   └── test-runner.md     # 테스트 실행 전문
│   └── skills/
│       ├── add-til/
│       │   └── SKILL.md       # TIL 생성 자동화
│       ├── deploy/
│       │   └── SKILL.md       # 배포 프로세스
│       ├── commit/
│       │   └── SKILL.md       # 커밋 생성
│       └── api-conventions/
│           └── SKILL.md       # API 컨벤션 (배경 지식)
├── src/
└── README.md
```

---

### 7. 선택 가이드 플로우차트

```
작업이 필요함
    │
    ├─ 항상 Claude가 알아야 하는 정보인가?
    │       │
    │       └─ Yes → CLAUDE.md에 추가
    │
    ├─ 사용자가 직접 트리거하는 작업인가?
    │       │
    │       └─ Yes → Skill 생성 (disable-model-invocation: true)
    │
    ├─ Claude가 자동으로 인식해서 실행해야 하는가?
    │       │
    │       └─ Yes → Skill 생성 (description으로 트리거 조건 명시)
    │
    ├─ 독립된 컨텍스트에서 실행해야 하는가?
    │       │
    │       └─ Yes → SubAgent 정의
    │
    ├─ 도구 접근을 제한해야 하는가?
    │       │
    │       └─ Yes → SubAgent 정의 (tools 필드로 제한)
    │
    └─ 병렬로 실행해야 하는가?
            │
            └─ Yes → SubAgent 여러 개 정의
```

## 핵심 정리

### Skills
- **정의**: 재사용 가능한 명령어/지식 (SKILL.md)
- **호출**: `/skill-name` 또는 Claude 자동 인식
- **용도**: 작업 자동화, 워크플로우 템플릿, 배경 지식
- **특징**: 사용자/Claude 선택적 호출, 도구 제한 가능

### SubAgents
- **정의**: 전문화된 위임 에이전트
- **호출**: Claude가 Task tool로 자동 위임
- **용도**: 독립 실행, 병렬 처리, 도구 제한
- **특징**: 독립 컨텍스트, 비용 최적화, 결과 요약 반환

### CLAUDE.md
- **정의**: 프로젝트 메모리 파일
- **호출**: 세션 시작 시 자동 로드
- **용도**: 프로젝트 정보, 컨벤션, 명령어 기록
- **특징**: 항상 컨텍스트에 포함, 격리 없음

### 선택 기준
- **항상 필요한 정보** → CLAUDE.md
- **수동 트리거 작업** → Skill (disable-model-invocation)
- **자동 인식 작업** → Skill (description 활용)
- **독립/병렬 실행** → SubAgent
- **도구 제한 필요** → SubAgent

## 참고 자료
- [Claude Code Skills 공식 문서](https://docs.anthropic.com/en/docs/claude-code/skills)
- [Claude Code Memory 공식 문서](https://docs.anthropic.com/en/docs/claude-code/memory)
- [Claude Agent SDK SubAgents](https://docs.anthropic.com/en/docs/agents/subagents)
