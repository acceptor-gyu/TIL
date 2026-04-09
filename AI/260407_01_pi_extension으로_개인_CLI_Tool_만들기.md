# pi extension으로 개인 CLI Tool 만들기

## 개요
`@mariozechner/pi-coding-agent`의 Extension 시스템을 활용하여 개인 맞춤형 CLI 도구를 만드는 방법을 학습한다. roach-pi(https://github.com/tmdgusya/roach-pi)는 실제 Pi Extension으로 구현된 좋은 레퍼런스로, 레트로 ASCII 배너·실시간 컨텍스트 푸터·멀티에이전트 오케스트레이션을 TypeScript로 구현했다.

## 상세 내용

### 1. Pi Extension vs Claude Code Skill

개인 CLI 도구를 만들 때 먼저 목표에 맞는 플랫폼을 선택해야 한다.

| 항목 | Pi Extension | Claude Code Skill |
|---|---|---|
| 작성 언어 | TypeScript | Markdown (SKILL.md) |
| TUI 라이브러리 | `@mariozechner/pi-tui` (커스텀 배너, 푸터 가능) | 없음 (텍스트 출력만) |
| 커맨드 등록 | `api.registerCommand("/name", handler)` | frontmatter `name` 필드 |
| 훅 시스템 | `api.on("session_start" \| "beforeToolCall" \| ...)` | 없음 |
| 적합한 상황 | 레트로 UI, 복잡한 로직, 서브에이전트 | 프롬프트 재사용, 간단한 워크플로우 |

저 "ROACH PI" 레트로 터미널 화면은 `@mariozechner/pi-tui`의 `ctx.ui.setHeader()` + Unicode 블록 문자로 만든 것이다. Claude Code로는 구현할 수 없다.

---

### 2. Pi Extension 동작 원리

Pi는 `~/.pi/agent/extensions/` (글로벌) 또는 `.pi/` (프로젝트 로컬) 디렉토리를 자동 탐색하여 Extension을 로드한다.

```
Pi 시작
  │
  ├── ~/.pi/agent/extensions/<my-ext>/index.ts 로드
  │       └── activate(api) 호출
  │               ├── api.on("session_start", ...) → 배너 출력
  │               ├── api.registerCommand("/hello", ...) → 커맨드 등록
  │               └── api.on("beforeToolCall", ...) → 도구 인터셉트
  │
  └── pi 세션 시작
```

프로젝트 로컬(`.pi/`) 리소스가 글로벌 설정보다 우선 적용된다.

---

### 3. Extension 프로젝트 구조

```
~/.pi/agent/extensions/my-pi/
├── index.ts          ← 진입점: 훅·커맨드 등록
├── footer.ts         ← 하단 상태바 컴포넌트 (선택)
├── render.ts         ← 출력 렌더링 헬퍼 (선택)
├── skills/
│   └── my-command/
│       └── SKILL.md  ← /my-command 프롬프트
├── package.json
└── tsconfig.json
```

roach-pi의 실제 구조:

```
extensions/agentic-harness/
├── agents/           ← 12개 에이전트 프롬프트 .md (explorer, planner, worker 등)
├── skills/           ← 주입 스킬 프롬프트
├── index.ts          ← 배너 + 훅 등록
├── footer.ts         ← 실시간 컨텍스트/캐시/브랜치 상태바
├── render.ts         ← Unicode 아이콘 + 시맨틱 컬러 렌더링
├── subagent.ts       ← 서브에이전트 병렬 실행
└── discipline.ts     ← Karpathy Rules 주입
```

---

### 4. 개발 환경 세팅

```bash
# Pi 설치
npm install -g @mariozechner/pi-coding-agent

# Extension 디렉토리 생성
mkdir -p ~/.pi/agent/extensions/my-pi
cd ~/.pi/agent/extensions/my-pi

# 패키지 초기화
npm init -y
npm install @mariozechner/pi-tui @mariozechner/pi-coding-agent
```

`tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "outDir": "./dist",
    "strict": true
  }
}
```

---

### 5. index.ts — 배너와 커맨드 등록

`activate` 함수가 Pi 로드 시 자동으로 호출되는 진입점이다.

```typescript
import { ExtensionAPI } from "@mariozechner/pi-coding-agent";

export function activate(api: ExtensionAPI) {
    // 세션 시작 시 ASCII 배너 출력
    api.on("session_start", (ctx) => {
        const banner = `
███╗   ███╗██╗   ██╗    ██████╗ ██╗
████╗ ████║╚██╗ ██╔╝    ██╔══██╗██║
██╔████╔██║ ╚████╔╝     ██████╔╝██║
██║╚██╔╝██║  ╚██╔╝      ██╔═══╝ ██║
██║ ╚═╝ ██║   ██║       ██║     ██║
╚═╝     ╚═╝   ╚═╝       ╚═╝     ╚═╝`;
        ctx.ui.setHeader(banner);
    });

    // 커스텀 커맨드 등록
    api.registerCommand("/hello", async (args, ctx) => {
        ctx.ui.print(`Hello, ${args || "world"}!`);
    });

    // 도구 호출 전 인터셉트 (로깅, 권한 확인 등)
    api.on("beforeToolCall", (tool, args, ctx) => {
        ctx.ui.print(`⏳ ${tool}(${JSON.stringify(args)})`);
    });
}
```

---

### 6. footer.ts — 실시간 상태바

roach-pi의 푸터는 컨텍스트 윈도우 사용량, 캐시 히트율, git 브랜치를 실시간으로 보여준다. `@mariozechner/pi-tui`의 컴포넌트 트리로 구현된다.

```typescript
import { Container, Text } from "@mariozechner/pi-tui";

export function buildFooter(ctx: SessionContext) {
    const usageRatio = ctx.session.tokenCount / ctx.session.maxTokens;
    const barWidth = 20;
    const filled = Math.round(barWidth * usageRatio);
    const bar = "█".repeat(filled) + "░".repeat(barWidth - filled);

    // 색상 임계치: 60% 미만 green, 60~85% yellow, 85% 이상 red
    const color = usageRatio < 0.6 ? "success"
                : usageRatio < 0.85 ? "warning"
                : "error";

    return Container([
        Text(`[${bar}] ${Math.round(usageRatio * 100)}%`, { color }),
        Text(` ⎇ ${ctx.session.gitBranch}`, { color: "muted" }),
    ]);
}
```

---

### 7. TUI 핵심 — Unicode 블록 문자

저 레트로 화면의 정체는 순수 Unicode 문자다. 그래픽 라이브러리가 아니다.

| 용도 | 문자 |
|---|---|
| ASCII 배너 굵은 선 | `█ ║ ╔ ╗ ╚ ╝ ═` |
| 진행바 | `█` (채움) `░` (빔) |
| 상태 아이콘 | `⏳` 실행중 `✓` 성공 `✗` 실패 `◐` 부분 |
| 구분선 | `─── Task ───` |
| 테마 컬러 | `"accent"` `"muted"` `"error"` `"success"` |

`ctx.ui.setHeader()`에 위 문자로 만든 문자열을 넘기면 roach-pi와 동일한 화면이 나온다.

---

### 8. Skills — 프롬프트 기반 커맨드

TypeScript 로직 없이 프롬프트만으로 커맨드를 만들 때 사용한다. `Agent Skills` 표준을 따르므로 Claude Code와도 호환된다.

```
skills/my-command/
└── SKILL.md
```

```yaml
---
name: my-command
description: 코드 리뷰를 수행합니다
disable-model-invocation: true
---

$ARGUMENTS 에 대한 코드 리뷰를 수행하세요.

1. 보안 취약점 확인
2. 성능 이슈 확인
3. 가독성 개선 제안
```

호출: `/my-command src/auth.ts`

---

### 9. Pi Package로 배포

완성된 Extension을 다른 사람과 공유하려면 Pi Package로 패키징한다.

```bash
# git 레포로 설치
pi install git:github.com/yourname/my-pi

# npm 패키지로 설치
pi install @yourname/my-pi
```

로컬 개발 시에는 심링크로 즉시 반영:

```bash
ln -s ~/dev/my-pi ~/.pi/agent/extensions/my-pi
```

---

### 10. 단계별 시작 전략

roach-pi를 레퍼런스로 삼아 점진적으로 기능을 추가하는 것이 효율적이다.

| 단계 | 작업 | 난이도 |
|---|---|---|
| 1 | `index.ts`에서 ASCII 배너만 바꿔서 띄우기 | 쉬움 |
| 2 | `skills/*.md`에 자주 쓰는 프롬프트 커맨드 추가 | 쉬움 |
| 3 | `footer.ts`로 하단 상태바 커스터마이징 | 중간 |
| 4 | `api.registerCommand()`로 TypeScript 커맨드 추가 | 중간 |
| 5 | `subagent.ts` 참고해 병렬 서브에이전트 구현 | 어려움 |

## 핵심 정리

- roach-pi는 `@mariozechner/pi-coding-agent`의 TypeScript Extension으로, Claude Code Skill과는 다른 방식이다
- 레트로 터미널 UI는 `@mariozechner/pi-tui`의 `ctx.ui.setHeader()` + Unicode 블록 문자로 구현한다
- `~/.pi/agent/extensions/<name>/`에 `index.ts`를 두고 `activate(api)` 함수를 export하면 Pi가 자동 로드한다
- `api.on("session_start", ...)` 훅으로 배너를, `api.registerCommand("/name", ...)` 로 커스텀 커맨드를 등록한다
- Skills(`.md` 파일)는 프롬프트 기반 커맨드로, TypeScript 없이도 `/커맨드`를 만들 수 있다
- `pi install git:github.com/yourname/my-pi`로 npm 또는 git을 통해 배포·설치할 수 있다

## 키워드

### Pi Extension
`@mariozechner/pi-coding-agent`의 확장 단위. `~/.pi/agent/extensions/` 또는 `.pi/` 디렉토리에 TypeScript 모듈로 작성하며, `activate(api: ExtensionAPI)` 함수를 통해 커맨드·훅·TUI를 등록한다.

### ExtensionAPI
Pi Extension의 진입점 인터페이스. `api.registerCommand()`, `api.on()`, `api.registerTool()` 등의 메서드로 Pi의 동작을 확장한다.

### pi-tui
Pi 모노레포의 터미널 UI 라이브러리. `ctx.ui.setHeader()`로 세션 배너를, Container/Text 컴포넌트로 푸터를 구성한다. 레트로 화면은 이 라이브러리 + Unicode 블록 문자의 조합이다.

### roach-pi
`tmdgusya`가 만든 Pi Extension 레퍼런스 구현체. ASCII 배너, 실시간 컨텍스트 푸터, `/plan`·`/clarify`·`/ultraplan` 커맨드, 병렬 서브에이전트 오케스트레이션을 포함한다.

### session_start 훅
Pi Extension에서 세션이 시작될 때 실행되는 라이프사이클 훅. `api.on("session_start", ctx => { ... })`으로 등록하며, 배너 출력이나 초기화 로직에 사용된다.

### Pi Package
Extension, Skills, Prompt Templates, 테마를 npm 또는 git으로 배포하는 번들 단위. `pi install git:github.com/name/repo` 명령으로 설치한다.

### Agent Skills Standard
Pi Skills가 따르는 오픈 스탠다드(agentskills.io). 이 표준 덕분에 Pi용으로 만든 SKILL.md가 Claude Code, Codex CLI 등 다른 AI 코딩 도구와도 호환된다.

### CLI Tool (커맨드라인 도구)
터미널에서 명령어로 실행하는 프로그램. Pi Extension에서는 `api.registerCommand("/name", handler)` 또는 `skills/<name>/SKILL.md`로 개인 맞춤형 슬래시 커맨드를 만들어 CLI 도구처럼 활용한다.

## 참고 자료
- [tmdgusya/roach-pi GitHub](https://github.com/tmdgusya/roach-pi)
- [badlogic/pi-mono GitHub](https://github.com/badlogic/pi-mono)
- [Pi Coding Agent 공식 사이트](https://shittycodingagent.ai/)
- [@mariozechner/pi-coding-agent npm](https://www.npmjs.com/package/@mariozechner/pi-coding-agent)
- [How to Build a Custom Agent Framework with PI](https://nader.substack.com/p/how-to-build-a-custom-agent-framework)
