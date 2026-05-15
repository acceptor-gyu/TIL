# Claude Code Dreaming

## 개요

2026년 5월 6일 Anthropic은 샌프란시스코에서 열린 개발자 이벤트 "Code with Claude"에서 **Dreaming**을 공식 발표했다. Dreaming은 에이전트 세션이 끝난 뒤 축적된 메모리 스토어를 자율적으로 정리·강화하는 **비동기 백그라운드 프로세스**다.

인간의 REM 수면에서 뇌가 낮 동안 수집한 정보를 정리·통합하듯, Claude 에이전트도 유휴 시간에 과거 세션 기록을 회고하여 메모리를 재구성한다. 단순한 자동화가 아니라 **컨텍스트 기반의 능동적 메모리 큐레이션**이 핵심이다.

- 기존 대화형 에이전트는 세션마다 메모를 누적만 할 뿐 정리하지 않아, 시간이 지날수록 중복·모순·구식 정보가 쌓인다.
- Dreaming은 이 문제를 세션 밖에서 비동기적으로 해결한다.
- Harvey(Legal AI)는 Dreaming 도입 후 태스크 완료율이 약 6배 증가했다.

## 상세 내용

### 1. Dreaming이란 무엇인가

Dreaming은 Anthropic의 **Claude Managed Agents** 플랫폼에서 제공되는 기능으로, 세션과 세션 사이에 실행되는 예약형 비동기 잡(Job)이다.

| 구분 | 일반 에이전트 세션 | Dreaming |
| --- | --- | --- |
| 실행 시점 | 사용자 요청 시 | 세션 종료 후 / 스케줄 |
| 목적 | 태스크 수행 | 메모리 정리·강화 |
| 메모리 쓰기 방식 | 증분(Incremental) 추가 | 재구성(Reorganization) |
| 원본 데이터 수정 | 직접 수정 | 입력 스토어를 절대 수정하지 않음, 새 출력 스토어 생성 |

"활성 세션"에서 쌓인 메모리는 중복, 모순, 상대 날짜("어제"), 구식 디버깅 노트 등으로 오염된다. Dreaming은 이를 **새 메모리 스토어**로 깨끗하게 재작성한다.

### 2. 동작 원리

Dream은 다음 입력을 받아 비동기 파이프라인을 실행한다.

- **입력 메모리 스토어(필수)**: 검증·중복제거·재구성 대상
- **과거 세션 트랜스크립트(선택, 최대 100개)**: 패턴·인사이트 추출 원천

파이프라인은 크게 4단계로 구성된다.

**1단계 — 방향 설정(Orientation)**
- 메모리 디렉토리 스캔 및 인덱스 파일(MEMORY.md) 읽기
- 현재 메모리 상태와 목표 상태 파악

**2단계 — 신호 수집(Gather Signal)**
- 사용자 수정 사항, 명시적 저장 명령, 반복 패턴 grep 검색
- 전체 세션 로그를 순차 읽기하지 않고 효율적으로 필요한 정보만 추출

**3단계 — 통합(Consolidation)**
- 상대 날짜를 절대 날짜로 변환 ("어제" → "2026-05-09")
- 모순 정보 제거, 구식 노트 삭제, 중복 항목 병합
- 새로운 인사이트·패턴을 기억으로 승격

**4단계 — 정리 및 인덱싱(Prune and Index)**
- MEMORY.md를 지정 상한선(예: 200줄) 이하로 유지
- 만료된 파일 포인터 제거, 신규 항목 추가

완료 후 출력 스토어 ID가 `outputs[]`에 기록되며, 입력 스토어는 변경되지 않는다. 따라서 결과가 만족스럽지 않으면 출력 스토어를 폐기하면 된다.

### 3. Claude Code에서의 Auto-Dream

Claude Code(CLI)에는 Managed Agents API의 Dreaming과 별개로 **Auto-Dream**이라는 로컬 메모리 통합 메커니즘이 존재한다.

자동 실행 조건은 아래 두 가지를 동시 만족할 때다.

- 마지막 드리밍 이후 **24시간 이상** 경과
- 해당 기간 동안 **5개 이상의 세션** 발생

조건이 충족되면 백그라운드에서 조용히 실행되며, 사용자 작업을 방해하지 않는다. 수동으로 트리거하려면 `/dream` 명령 또는 채팅창에 "dream", "consolidate my memory files" 등을 입력한다.

안전 가드레일:

- **메모리 디렉토리 외부 접근 불가**: 프로젝트 코드를 수정하지 않는다.
- **락 파일**: 동시 실행을 방지한다.
- **비파괴적**: 원본 메모리를 그대로 두고 출력본을 별도 생성한다.

### 4. API 사용법 (Managed Agents)

Dreaming은 `dreaming-2026-04-21` 베타 헤더가 추가로 필요하다.

```bash
# Dream 생성
curl -s https://api.anthropic.com/v1/dreams \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "anthropic-beta: managed-agents-2026-04-01,dreaming-2026-04-21" \
  -H "content-type: application/json" \
  -d '{
    "inputs": [
      { "type": "memory_store", "memory_store_id": "<store_id>" },
      { "type": "sessions", "session_ids": ["<session_a>", "<session_b>"] }
    ],
    "model": "claude-opus-4-7",
    "instructions": "Focus on coding-style preferences; ignore one-off debugging notes."
  }'
```

Dream 상태 생명주기:

| status | 의미 |
| --- | --- |
| `pending` | 생성 완료, 큐에 대기 중 |
| `running` | 파이프라인 처리 중, `usage` 실시간 업데이트 |
| `completed` | 성공, `outputs[]`에 새 메모리 스토어 ID |
| `failed` | 오류로 종료, 부분 출력 스토어 존재 |
| `canceled` | 취소됨 |

지원 모델은 `claude-opus-4-7`과 `claude-sonnet-4-6`이며, 세션은 최대 100개까지 입력할 수 있다.

### 5. 활용 시나리오

- **대규모 코드베이스 리팩토링**: 여러 날에 걸친 리팩토링 세션 후 축적된 맥락을 Dreaming으로 정리해 다음 세션에서 깔끔한 메모리로 시작
- **TIL/문서화 자동 정리**: 매일 쌓이는 TIL 작성 세션의 패턴을 야간 Dreaming으로 통합·인덱싱
- **PR 리뷰 패턴 학습**: 반복적으로 지적되는 코드 스타일을 패턴으로 추출하여 다음 PR 작성 시 사전 반영
- **팀 공유 메모리 관리**: 팀 단위로 공유하는 메모리 스토어를 주기적으로 정리해 팀 전체의 선호·규칙 최신화

### 6. 실전 적용 시 고려 사항

**비용 제어**
- Dreaming은 선택한 모델의 표준 API 토큰 요금으로 과금된다.
- `usage` 필드가 실시간 업데이트되므로 비용을 추적할 수 있다.
- 소규모 세션 배치로 먼저 테스트 후 규모를 늘리는 것이 권장된다.

**안전 가드레일**
- 입력 스토어와 세션은 Dream이 `running` 상태일 때 삭제·아카이브하면 `input_memory_store_unavailable` 오류로 실패한다.
- `pending`/`running` 상태에서 출력 스토어를 삭제하면 400 오류가 반환된다.
- 취소(cancel)는 즉시 처리되며, 완료된 Dream은 취소할 수 없다.

**Human-in-the-loop**
- 자동 업데이트 모드와 수동 검토 모드 중 선택 가능하다.
- 출력 스토어를 검토 후 실제 세션에 연결할지 결정하는 것이 권장 패턴이다.

## 핵심 정리

- Dreaming은 에이전트의 "유휴 시간 메모리 강화 전략"이다.
- 세션 간 메모리 스토어를 비파괴적으로 재구성하여 중복·모순·구식 정보를 제거한다.
- 입력 스토어를 절대 수정하지 않으므로, 결과가 마음에 들지 않으면 출력 스토어를 폐기하면 된다.
- Claude Code의 Auto-Dream은 24시간 + 5세션 조건에서 자동 실행되며, `/dream`으로 수동 트리거도 가능하다.
- 안전성(가드레일), 비용(토큰 모니터링), 검증(Human-in-the-loop)을 함께 설계해야 실효성을 가진다.

## 기술적 한계와 보완 전략

- **잘못된 통합으로 인한 정보 손실**: 출력 스토어를 반드시 검토하고 반영 여부를 결정한다. 입력 스토어는 보존되므로 롤백이 가능하다.
- **컨텍스트 드리프트**: `instructions` 파라미터로 "특정 주제에 집중하고 일회성 디버깅 노트는 무시"와 같이 드리밍 방향을 명시적으로 지정한다.
- **비용 폭증**: 한 번에 100개 세션을 투입하지 말고, 소규모 배치(10~20개)로 단가 대비 품질을 검증한다.
- **추적 불가능한 변경**: Dream이 `running` 중일 때 `session_id`로 실행 세션 이벤트를 스트리밍해 실시간 관찰이 가능하다. Dream 완료 후에도 해당 세션 트랜스크립트가 아카이브되어 감사(audit)할 수 있다.
- **현재 접근 제한**: Research Preview 단계이므로 `claude.com/form/claude-managed-agents`에서 액세스 신청이 필요하다.

## 키워드

- **Claude Code**: Anthropic의 CLI 기반 AI 코딩 에이전트
- **Dreaming Mode**: 세션 간 비동기 메모리 정리·강화 프로세스. 인간의 REM 수면에 비유되며, 과거 세션 트랜스크립트를 분석하여 메모리 스토어를 재구성한다.
- **Autonomous Agent**: 사용자의 즉각적 지시 없이 스스로 판단하고 행동하는 에이전트
- **Idle-time Computation**: 에이전트가 활성 태스크를 수행하지 않는 유휴 시간을 활용한 백그라운드 연산
- **Long-term Memory**: 세션을 넘어 지속되는 에이전트 메모리. Memory Store(메모리 스토어)가 구현체로, Dreaming이 이를 유지·관리한다.
- **Harness Engineering**: 에이전트 파이프라인의 오케스트레이션 구조. Dreaming은 하네스 내 별도 비동기 잡으로 위치한다.
- **Human-in-the-loop**: 자동화 파이프라인에 사람의 검토·승인 체크포인트를 삽입하는 설계 패턴. Dreaming에서는 출력 스토어를 세션에 연결하기 전에 검토하는 단계가 이에 해당한다.
- **Guardrail**: 에이전트의 행동 범위를 제한하는 안전 장치. Dreaming에서는 메모리 디렉토리 외부 쓰기 금지, 락 파일, 비파괴적 출력 방식이 가드레일에 해당한다.
- **Context Compression**: 누적된 컨텍스트를 압축·요약하여 토큰 효율을 높이는 기법. Dreaming의 통합 단계가 이 역할을 수행한다.
- **Background Agent**: 사용자 인터랙션 없이 백그라운드에서 자율 실행되는 에이전트 프로세스

## 참고 자료

- [Dreams - Claude API Docs](https://platform.claude.com/docs/en/managed-agents/dreams)
- [New in Claude Managed Agents: dreaming, outcomes, and multiagent orchestration](https://claude.com/blog/new-in-claude-managed-agents)
- [Claude Code Dreams: Auto-Dream Mechanics](https://claudefa.st/blog/guide/mechanics/auto-dream)
- [Anthropic introduces "dreaming" - VentureBeat](https://venturebeat.com/technology/anthropic-introduces-dreaming-a-system-that-lets-ai-agents-learn-from-their-own-mistakes)
- [Claude Managed Agents overview](https://platform.claude.com/docs/en/managed-agents/overview)
