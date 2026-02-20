# Subagent Frontmatter 훅을 활용한 순차 실행 구현

## 구현 개요

이 프로젝트는 `add-til-template`과 `task-til` 두 서브에이전트를 **자동으로 순차 실행**하도록 구성되었습니다.

### 실행 흐름

```
사용자: Task tool로 add-til-template 호출
    ↓
[add-til-template 서브에이전트 시작]
    ↓
템플릿 파일 생성 (예: 260220_01_Redis_캐싱_전략.md)
    ↓
[Stop 훅 - agent 타입] (add-til-template.md 내부)
    - 생성된 파일명 추출
    - /tmp/til-created-file.txt에 저장
    - {"ok": true} 반환
    ↓
[add-til-template 서브에이전트 종료]
    ↓
[SubagentStop 훅 - prompt 타입] (settings.json)
    - /tmp/til-created-file.txt 읽기
    - task-til 서브에이전트 자동 호출
    ↓
[task-til 서브에이전트 시작]
    ↓
파일 내용 작성 + README 업데이트
    ↓
[task-til 서브에이전트 종료]
    ↓
완료!
```

---

## 파일 구조

```
.claude/
├── agents/
│   ├── add-til-template.md   # Stop 훅 포함
│   └── task-til.md
├── settings.json              # SubagentStop 훅 포함
└── scripts/
    └── trigger-task-til.sh    # (사용하지 않음, 학습용)
```

---

## 핵심 구현 요소

### 1. add-til-template.md의 Stop 훅

```yaml
hooks:
  Stop:
    - hooks:
        - type: agent
          prompt: |
            생성된 파일명을 찾아서 /tmp/til-created-file.txt에 저장
          timeout: 60
```

**타입 선택 이유:**
- `agent` 타입: 파일을 읽고 쓸 수 있어야 하므로 (Read, Write 도구 필요)
- `prompt` 타입은 파일 접근 불가
- `command` 타입은 쉘 스크립트로 가능하지만 컨텍스트 추출이 어려움

**동작:**
- 서브에이전트 종료 직전에 발동
- 대화 내용(`$ARGUMENTS`)을 분석해 생성된 파일명 추출
- `/tmp/til-created-file.txt`에 파일명 저장
- `{"ok": false}` 반환 시 서브에이전트 종료 차단

---

### 2. settings.json의 SubagentStop 훅

```json
{
  "hooks": {
    "SubagentStop": [
      {
        "matcher": "add-til-template",
        "hooks": [
          {
            "type": "prompt",
            "prompt": "파일명을 읽고 task-til 호출..."
          }
        ]
      }
    ]
  }
}
```

**타입 선택 이유:**
- `prompt` 타입: LLM이 직접 Task tool을 호출할 수 있음
- `command` 타입은 Claude에게 지시만 가능 (직접 도구 호출 불가)
- `agent` 타입도 가능하지만 과도함

**matcher:**
- `"add-til-template"`: 이 이름의 서브에이전트가 종료될 때만 발동
- 정규표현식 사용 가능

**동작:**
- `add-til-template` 종료 시 메인 세션에서 발동
- `/tmp/til-created-file.txt` 읽기
- `task-til` 서브에이전트 자동 호출

---

## 사용 방법

### 방법 1: Task tool 직접 호출 (추천)

```
Task tool을 사용하여 add-til-template 서브에이전트를 호출해주세요.
카테고리: Database
제목: Redis 캐싱 전략
```

### 방법 2: Skill이 등록되어 있다면

```
/add-til-template Database Redis 캐싱 전략
```

그러면:
1. `add-til-template`이 템플릿 생성
2. 자동으로 `task-til`이 내용 작성
3. README 자동 업데이트

---

## 주요 학습 포인트

### 1. 훅 체인의 설계

| 위치 | 이벤트 | 타입 | 역할 |
|---|---|---|---|
| `add-til-template.md` | `Stop` | `agent` | 파일명 추출 및 저장 |
| `settings.json` | `SubagentStop` | `prompt` | 다음 서브에이전트 호출 |

**왜 Stop과 SubagentStop을 분리했나?**
- `Stop` 훅 (프론트매터): 서브에이전트 **내부**에서 실행 → 컨텍스트 접근 용이
- `SubagentStop` 훅 (settings.json): **메인 세션**에서 실행 → 다른 서브에이전트 호출 가능

### 2. 파일을 통한 데이터 전달

서브에이전트 간 직접 통신은 불가능하므로 `/tmp/til-created-file.txt` 파일을 중간 저장소로 사용:

```
add-til-template → /tmp/til-created-file.txt → task-til
```

### 3. 타입별 적합한 사용처

| 훅 타입 | 파일 접근 | 도구 호출 | 적합한 용도 |
|---|---|---|---|
| `command` | ✅ (쉘) | ❌ | 빠른 검증, 외부 명령 실행 |
| `prompt` | ❌ | ✅ | 간단한 판단, 도구 호출 지시 |
| `agent` | ✅ | ✅ | 복잡한 검증, 파일 분석 |

### 4. exit code와 return value

**Stop 훅에서:**
- `{"ok": true}`: 서브에이전트 정상 종료
- `{"ok": false, "reason": "..."}`: 서브에이전트 계속 실행 (종료 차단)

**PreToolUse 훅에서:**
- `exit 0`: 도구 실행 허용
- `exit 2`: 도구 실행 차단

---

## 대안적 구현 방법

### A. command 타입으로 구현하기

`.claude/agents/add-til-template.md`:
```yaml
hooks:
  Stop:
    - hooks:
        - type: command
          command: "./scripts/extract-filename.sh"
```

`./scripts/extract-filename.sh`:
```bash
#!/bin/bash
INPUT=$(cat)
# JSON 파싱하여 파일명 추출하는 복잡한 로직 필요
# 정규표현식으로 "생성된 파일: XXX.md" 찾기
```

**단점:** 대화 내용이 JSON으로 전달되므로 파싱 복잡

---

### B. 프롬프트 체이닝 (훅 없이)

메인 세션에서:
```
1. add-til-template 호출
2. 결과를 받아 파일명 확인
3. task-til 호출하면서 파일명 전달
```

**장점:** 간단, 명확
**단점:** 자동화 안됨

---

## 트러블슈팅

### 1. task-til이 자동 호출되지 않음

**확인 사항:**
- `/tmp/til-created-file.txt` 파일이 생성되었는가?
- `settings.json`의 matcher가 정확한가? (`add-til-template`)
- SubagentStop 훅이 정상 실행되었는가? (verbose 모드로 확인)

### 2. 파일명을 찾을 수 없음

**확인 사항:**
- `add-til-template`의 Stop 훅이 `{"ok": true}`를 반환했는가?
- 생성된 파일명이 출력에 명확하게 나타나는가?

### 3. 무한 루프 발생

**원인:** Stop 훅이 계속 `{"ok": false}` 반환
**해결:** 훅 로직에서 종료 조건 명확히 설정

---

## 참고 문서

- [Claude Code Hooks](https://code.claude.com/docs/en/hooks.md)
- [Sub-agents](https://code.claude.com/docs/en/sub-agents.md)
- [Hooks Guide](https://code.claude.com/docs/en/hooks-guide.md)
