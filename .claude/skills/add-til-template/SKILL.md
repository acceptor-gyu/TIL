---
name: add-til-template
description: 새로운 TIL 항목을 생성하고 내용까지 자동으로 작성합니다
argument-hint: [카테고리] [제목]
---

다음 두 단계를 순서대로 실행하세요.

## 1단계: add-til-template 서브에이전트 호출

Agent tool을 사용하여 `add-til-template` 서브에이전트를 호출하세요.
- subagent_type: `add-til-template`
- prompt: `$ARGUMENTS`

서브에이전트가 완료되면 생성된 파일명을 결과에서 확인하세요.

## 2단계: task-til 서브에이전트 호출

1단계에서 생성된 파일명으로 `task-til` 서브에이전트를 호출하세요.
- subagent_type: `task-til`
- prompt: 1단계에서 확인한 파일명 (예: `260330_01_Redis_캐싱_전략.md`)

두 단계가 모두 완료되면 사용자에게 결과를 알려주세요.
