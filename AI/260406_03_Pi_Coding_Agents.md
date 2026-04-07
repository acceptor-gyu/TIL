# Pi Coding Agents

## 개요

Pi는 Mario Zechner(libGDX 창시자)가 만든 최소한의 터미널 코딩 에이전트(Minimal Terminal Coding Harness)이다. 핵심 철학은 "도구가 워크플로우에 맞추는 것이 아니라, 워크플로우에 맞게 Pi를 확장한다"는 것이다.

Claude Code, Cursor, Copilot과 같은 기존 AI 코딩 도구들이 다양한 기능을 내장(bake-in)하는 방향과 반대로, Pi는 4개의 기본 도구만 유지하면서 사용자가 필요한 기능을 직접 확장하도록 설계되었다. 이 미니멀한 설계가 오히려 OpenClaw라는 프로덕션 멀티채널 AI 어시스턴트의 기반이 되고 있다.

## 상세 내용

### Pi의 4가지 기본 도구

Pi가 기본으로 제공하는 도구는 단 4가지다.

| 도구 | 설명 |
| --- | --- |
| **read** | 파일 내용 읽기 |
| **write** | 파일 생성 및 작성 |
| **edit** | 기존 파일의 일부 수정 |
| **bash** | 쉘 명령 실행 |

이 4가지만으로도 LLM이 코드를 작성하고 실행하며 결과를 확인하는 완전한 개발 루프를 돌릴 수 있다는 것이 Pi의 주장이다. 다른 에이전트가 내장하는 기능(서브 에이전트, 계획 모드, 권한 팝업, MCP 지원 등)은 의도적으로 제외되어 있다.

### 패키지 레이어 구조

Pi는 모노레포(pi-mono)로 구성된 레이어드 아키텍처를 갖는다.

```
┌──────────────────────────────────────────┐
│  @mariozechner/pi-coding-agent           │  ← 풀 코딩 에이전트 CLI, 세션, 확장 시스템
├──────────────────────────────────────────┤
│  @mariozechner/pi-agent-core             │  ← 에이전트 루프, 도구 호출 처리
├──────────────────────────────────────────┤
│  @mariozechner/pi-ai                     │  ← 통합 LLM API (멀티 프로바이더)
├──────────────────────────────────────────┤
│  @mariozechner/pi-tui                    │  ← 터미널 UI 라이브러리
└──────────────────────────────────────────┘
```

각 레이어는 독립적으로 사용할 수 있으며, `pi-ai`만 사용하면 여러 LLM 프로바이더를 통합 API로 호출할 수 있다.

### AgentSession: 핵심 오케스트레이터

Pi의 핵심 클래스는 `AgentSession`이다. 에이전트의 전체 라이프사이클을 관리하며 세 가지 운영 모드를 지원한다.

```
AgentSession
    │
    ├── Interactive Mode   ← 풀 TUI 인터랙티브 세션
    ├── Print Mode         ← 단일 요청 CLI (스크립트 자동화)
    └── RPC Mode           ← JSON 이벤트 기반 (비Node.js 통합)
```

세 모드 모두 동일한 `pi-agent-core`의 에이전트 루프와 `pi-ai`의 LLM 추상화를 공유한다.

### 트리 기반 세션 관리

세션은 JSONL 형식으로 저장되며, 각 메시지는 `id`와 `parentId`를 포함해 트리 구조로 관리된다.

```
Session A (main)
    │
    ├── Message 1
    ├── Message 2
    │       │
    │       ├── Branch 1  ← /tree 명령으로 이 지점으로 돌아오기
    │       └── Branch 2
    └── Message 3
```

- `/tree`: 전체 대화 이력을 시각화하고 분기 간 이동
- `/compact`: 긴 대화를 수동 압축 (토큰 절약)
- 컨텍스트 한계 초과 시 `SessionManager`가 자동 요약 생성

이 구조는 "이 방향이 잘못됐으니 이전으로 돌아가서 다른 방법을 시도"하는 개발 패턴을 자연스럽게 지원한다.

### 확장 시스템 (Extension System)

Pi의 확장 시스템은 다음 4가지 방식으로 구성된다.

**1. Extensions (TypeScript 모듈)**
- `.pi/` (프로젝트 로컬) 또는 `~/.pi/agent/` (글로벌) 디렉토리에 자동 탐색
- `ExtensionAPI`를 통해 커스텀 도구, 명령어, 라이프사이클 훅, TUI 접근 가능
- `beforeToolCall` / `afterToolCall` 훅으로 도구 실행 인터셉트 가능
- 프로젝트 로컬 리소스가 글로벌 설정보다 우선

```typescript
// 확장 예시: 커스텀 명령어 추가
export function activate(api: ExtensionAPI) {
    api.registerCommand("/review", async (args) => {
        // 코드 리뷰 로직
    });
}
```

**2. Skills**
- 명령어 및 도구를 포함한 기능 패키지
- `Agent Skills` 표준을 따르며 Claude Code, Codex CLI와 호환
- 온디맨드 방식으로 로드

**3. Prompt Templates**
- 재사용 가능한 지시사항 모음
- 특정 작업 유형(리팩토링, 코드 리뷰 등)에 특화된 프롬프트 재사용

**4. Pi Packages**
- 확장, 스킬, 프롬프트, 테마를 npm 또는 git으로 배포하는 번들 단위
- 커뮤니티 패키지 설치 또는 직접 패키지 생성 가능

### MCP를 의도적으로 제외한 이유

Pi는 MCP(Model Context Protocol) 지원을 의도적으로 제외했다. 그 이유는 다음과 같다.

- MCP는 외부 의존성과 복잡성을 증가시킴
- Pi의 Extension 시스템이 MCP가 제공하는 기능을 TypeScript로 더 유연하게 구현 가능
- README 기반 CLI 도구나 Bash 도구로 대부분의 MCP 유스케이스를 커버

다른 에이전트가 "기능을 내장"하는 방향으로 성장할 때, Pi는 "사용자가 필요한 것을 스스로 구축"하는 방향을 선택했다.

### 멀티 프로바이더 지원

pi-ai는 15개 이상의 LLM 프로바이더를 단일 API로 추상화한다.

- Anthropic (Claude)
- OpenAI (GPT, o-series)
- Google (Gemini)
- Azure OpenAI
- AWS Bedrock
- Mistral, Groq, Cerebras
- xAI (Grok)
- Ollama (로컬 모델)
- OpenRouter
- GitHub Copilot 등

세션 중 `/model` 명령으로 즉시 모델 전환이 가능하며, 동일한 세션 내에서 여러 모델의 응답을 비교할 수 있다.

### Claude Code와의 차이점

| 항목 | Pi | Claude Code |
| --- | --- | --- |
| **기본 도구 수** | 4개 (read, write, edit, bash) | 20개 이상 |
| **MCP 지원** | 미지원 (Extension으로 대체) | 지원 |
| **서브 에이전트** | 미지원 (확장으로 구현) | 지원 |
| **권한 팝업** | 미지원 | 지원 |
| **확장 방식** | TypeScript Extensions + npm | Skills (.md 기반) |
| **멀티 프로바이더** | 15개 이상 | Anthropic 전용 |
| **오픈소스** | 완전 오픈소스 | 비공개 |
| **설계 방향** | 최소화 + 확장성 | 기능 내장형 |

### 실제 프로덕션 활용: OpenClaw

Pi의 모든 패키지를 기반으로 구축된 OpenClaw는 멀티채널 AI 어시스턴트이다.

- WhatsApp, Telegram, Discord, Slack, Signal, iMessage, Google Chat, Microsoft Teams 등 지원
- 채널 간 공유 메모리와 지속 세션
- pi-ai(LLM 추상화) + pi-agent-core(에이전트 루프) + pi-coding-agent(세션/확장) + pi-tui(UI) 전체 스택 활용

## 핵심 정리

- Pi는 4개의 기본 도구(read, write, edit, bash)만 가진 최소 터미널 코딩 에이전트이다
- "도구가 워크플로우에 맞추는 것이 아니라, 워크플로우에 맞게 Pi를 확장한다"는 철학이 핵심이다
- TypeScript Extension 시스템, Skills, Prompt Templates, Pi Packages로 무한 확장 가능하다
- pi-ai, pi-agent-core, pi-coding-agent, pi-tui로 구성된 레이어드 모노레포 구조를 갖는다
- 트리 기반 세션 관리로 대화 분기 및 이전 지점으로의 복귀가 가능하다
- 15개 이상의 LLM 프로바이더를 단일 API로 지원하며 세션 중 모델 전환이 가능하다
- 완전 오픈소스이며 npm으로 설치해 즉시 사용 가능하다

## 키워드

### Pi Coding Agent
Mario Zechner가 만든 최소한의 터미널 코딩 에이전트. 4개의 기본 도구(read, write, edit, bash)만 제공하며, 사용자가 TypeScript Extension으로 기능을 확장하는 설계 철학을 갖는다. `@mariozechner/pi-coding-agent` npm 패키지로 배포된다.

### Minimal Terminal Coding Harness
Pi의 자기 설명 문구. "최소한의 터미널 코딩 하네스"로, 에이전트 도구가 워크플로우를 강제하는 대신 사용자의 워크플로우에 맞게 도구를 적응시키는 설계 방향을 의미한다.

### Extension System (확장 시스템)
Pi에서 기능을 추가하는 방식. TypeScript 모듈로 작성된 Extensions, 기능 패키지인 Skills, 재사용 프롬프트인 Prompt Templates, npm/git 배포 단위인 Pi Packages로 구성된다.

### AgentSession
Pi-coding-agent의 핵심 오케스트레이터 클래스. 에이전트 라이프사이클 관리, 세션 분기, 컨텍스트 압축, Interactive/Print/RPC 세 가지 운영 모드를 담당한다.

### Tree-based Session (트리 기반 세션)
JSONL 형식으로 저장되며 각 메시지가 `id`와 `parentId`를 갖는 세션 구조. 대화를 트리로 관리해 이전 분기 지점으로 돌아가 다른 방향으로 탐색할 수 있다.

### pi-ai
Pi 모노레포의 LLM 추상화 레이어 패키지. Anthropic, OpenAI, Google, Azure, Bedrock 등 15개 이상 프로바이더를 단일 API로 통합하여 프로바이더 교체를 투명하게 처리한다.

### SessionManager
세션의 활성 분기 관리, 메시지 추가, 컨텍스트 구축을 담당하는 컴포넌트. 토큰 한계 초과 시 자동 요약을 생성하는 컨텍스트 압축 기능을 내장한다.

### Pi Skills
명령어 및 도구를 포함한 기능 패키지. `Agent Skills` 표준을 따르며 Claude Code, Codex CLI와 호환된다. npm 또는 git으로 배포하며, 커뮤니티가 공유하는 `badlogic/pi-skills` 레포지토리가 있다.

### OpenClaw
Pi의 모든 패키지(pi-ai, pi-agent-core, pi-coding-agent, pi-tui)를 기반으로 구축된 프로덕션 멀티채널 AI 어시스턴트. WhatsApp, Telegram, Discord, Slack 등 주요 채널을 지원하며 채널 간 공유 메모리와 지속 세션을 제공한다.

### Context Compaction (컨텍스트 압축)
긴 대화로 인한 토큰 초과를 처리하는 메커니즘. Pi에서는 `/compact` 명령으로 수동 압축하거나 `SessionManager`가 자동으로 오래된 내용을 요약한다.

## 참고 자료
- [Pi: The Minimal Agent Within OpenClaw - Armin Ronacher](https://lucumr.pocoo.org/2026/1/31/pi/)
- [Pi Coding Agent 공식 사이트](https://shittycodingagent.ai/)
- [badlogic/pi-mono GitHub](https://github.com/badlogic/pi-mono)
- [pi-mono coding-agent 패키지](https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent)
- [badlogic/pi-skills GitHub](https://github.com/badlogic/pi-skills)
- [@mariozechner/pi-coding-agent npm](https://www.npmjs.com/package/@mariozechner/pi-coding-agent)
- [How to Build a Custom Agent Framework with PI](https://nader.substack.com/p/how-to-build-a-custom-agent-framework)
