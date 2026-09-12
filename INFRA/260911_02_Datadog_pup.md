# Datadog pup

## 개요
Datadog이 내놓은 CLI 도구 **Pup**에 대한 학습 기록이다. "200+ commands across 33+ Datadog products"를 하나의 바이너리로 제공하며, 사람이 터미널에서 쓰는 것뿐 아니라 **AI 에이전트가 라이브 프로덕션 텔레메트리에 직접 접근하는 통로**로 설계된 것이 핵심이다. 관측 데이터가 SaaS 웹 UI 안에만 갇혀 있으면 코딩 에이전트는 "지금 프로덕션에서 무슨 일이 벌어지는지"를 알 수 없는데, Pup은 그 간극을 CLI라는 가장 조합하기 쉬운 인터페이스로 메운다.

> 참고: 과거 Datadog Agent 5.x에는 `localhost:17125`에서 실시간 메트릭을 보여주던 동명의 로컬 웹 대시보드 `pup`이 번들되어 있었으나, Agent 6에서 코어가 Go로 재작성되면서 제거된 뒤 복귀하지 않았다. 지금의 Pup CLI와는 이름만 같을 뿐 목적·구현·유지보수 주체가 완전히 다른 별개의 도구다.

## 상세 내용

### 1. 왜 만들어졌는가 — 에이전트에게 없는 것은 지능이 아니라 도구다
- AI 코딩 에이전트는 코드베이스는 잘 읽지만, **런타임에 실제로 무슨 일이 벌어졌는지**는 모른다. "이 PR 배포 후 에러율이 올랐나?"에 답하려면 관측 백엔드에 접근해야 한다.
- 기존 해법은 에이전트에게 장기 유효한 API 키를 쥐여주는 것이었는데, 이는 권한 범위가 넓고 회수가 어렵다. Pup은 **OAuth2 + PKCE**로 한 번 로그인하면 사용자의 RBAC 권한을 그대로 물려받는 스코프된 토큰을 쓰게 해 이 문제를 피한다.
- 출력이 처음부터 구조화(JSON/YAML)되어 있어 `jq`나 파이프로 엮기 쉽고, 에이전트가 파싱 실패로 헛도는 일이 줄어든다.

### 2. 설치와 인증
```bash
# 설치 (Homebrew)
brew tap datadog-labs/pack && brew install datadog-labs/pack/pup

# 또는 소스 빌드 (Rust로 작성되어 있다)
git clone https://github.com/DataDog/pup && cd pup && cargo build --release
```

```bash
# OAuth2 로그인 (권장) — 브라우저가 열리고, 토큰은 OS 키체인에 저장된다
pup auth login
pup auth status
```

인증 방식은 세 가지이며 **`DD_ACCESS_TOKEN` → OAuth 토큰 → API 키** 순으로 우선순위가 적용된다.

| 방식 | 설정 | 용도 |
| --- | --- | --- |
| OAuth2 + PKCE | `pup auth login` | 로컬 개발·에이전트 사용 (권장). 자동 갱신, 키체인 저장 |
| API 키 | `DD_API_KEY`, `DD_APP_KEY` | CI 등 브라우저가 없는 기존 파이프라인 |
| Bearer 토큰 | `DD_ACCESS_TOKEN` | WASM·헤드리스 등 상태를 못 들고 있는 환경 |

### 3. 명령 구조와 출력 형식
명령은 Datadog 제품 도메인별로 계층화되어 있다 — `pup <도메인> <동작> [옵션]`.

| 영역 | 도메인 | 대표 하위 명령 |
| --- | --- | --- |
| 관측 | `metrics`, `logs`, `traces`, `rum`, `events` | `query`, `search`, `aggregate`, `patterns` |
| 모니터링 | `monitors`, `dashboards`, `slos`, `synthetics` | `list`, `get`, `create`, `update`, `delete` |
| 보안 | `security`, `audit-logs`, `governance` | rules, signals, findings |
| 운영 | `incidents`, `workflows`, `runbooks`, `error-tracking` | `list`, `run`, 온콜 관리 |
| CI/CD | `cicd` | 파이프라인, 테스트, DORA 메트릭 |
| 조직 | `users`, `api-keys`, `app-keys`, `organizations` | 키·권한 관리 |

출력은 `-o/--output` 플래그로 `json`(기본), `table`, `yaml`, `csv`, `tsv` 중 선택한다. 사람이 볼 때는 `-o table`, 에이전트나 스크립트가 받을 때는 기본 JSON을 쓰는 식이다.

### 4. Agent Mode — 사람이 부를 때와 에이전트가 부를 때를 구분한다
- Pup은 `CLAUDECODE`, `CURSOR_AGENT`, `CODEX` 같은 **환경변수를 보고 자신이 AI 에이전트에게 호출되었는지 자동 감지**한다. 필요하면 `FORCE_AGENT_MODE=1` 또는 `--agent` 플래그로 강제할 수 있다.
- Agent Mode에서는 (1) 메타데이터·에러 상세·힌트가 포함된 기계 친화적 JSON을 반환하고, (2) 확인 프롬프트를 자동 승인한다. 대화형 `y/N` 프롬프트 앞에서 에이전트가 멈춰버리는 문제를 없애기 위한 설계다.
- `pup agent schema`는 사용 가능한 명령 스펙을 구조화된 JSON으로 내려준다. 전체 문서를 컨텍스트에 넣는 대신 **필요한 명령만 골라 로드**해 토큰 소비를 줄이는 용도다.

> 확인 프롬프트 자동 승인은 편리한 만큼 위험하다. `pup dashboards delete <ID> --yes`처럼 파괴적인 명령까지 승인 없이 통과하므로, 에이전트에게는 읽기 위주 권한의 계정을 물리는 편이 안전하다.

### 5. 활용 예시

**(1) 장애 조사 — 로그에서 시작해 원인을 좁힌다**
```bash
# 최근 1시간, checkout 서비스의 에러 로그 메시지만 뽑기
pup logs search --query="status:error service:checkout" --from="1h" | jq '.data[].attributes.message'

# 현재 열려 있는 인시던트 제목 확인
pup incidents list --status=active | jq '.data[].attributes.title'

# 우리 팀이 소유한 모니터 중 어떤 게 울고 있는지
pup monitors list --tags="team:api-platform" -o table
```

**(2) 배포 전후 지표 비교**
```bash
# CPU 사용률 시계열을 최근 1시간치로 조회
pup metrics query --query="avg:system.cpu.user{*}" --from="1h"

# 공유용 대시보드 URL을 기간 지정해서 생성 (슬랙에 던질 때 유용)
pup dashboards url abc-123-def --from=now-1w --to=now --live=true
```

**(3) 코딩 에이전트에 붙여 쓰기**
```bash
# Claude Code 등에 Datadog 운영 스킬 설치
pup skills install claude-code
pup skills list
```
설치되는 번들 스킬에는 인시던트 트리아지, 로그·메트릭·트레이스 상관 분석, 변경 추적, DB 모니터링 분석 같은 운영 워크플로가 포함된다. 예를 들어 Claude Code에서 `/sre-investigate`를 실행하면 에이전트가 뒤에서 Pup 명령을 연쇄 호출하며 로그 → 트레이스 → 배포 이력 → DB 텔레메트리 순으로 조사를 진행한다.

**(4) CI 실패 분류 자동화**
`cicd` 도메인으로 파이프라인 이벤트와 테스트 결과를 가져와, 실패를 flaky / 인프라 문제 / 진짜 회귀로 분류하고 후속 조치를 제안하는 흐름을 에이전트에게 맡길 수 있다. CI에서는 OAuth 대신 `DD_API_KEY`/`DD_APP_KEY`를 쓰면 되고, 배포 이벤트 생성이나 소스맵 업로드도 별도 시크릿 없이 같은 바이너리로 처리한다.

**(5) 런북과 로컬 AI 서버**
- `runbooks`: YAML로 정의한 운영 절차를 로컬에서 실행하는 엔진. pup 명령, 셸 스크립트, HTTP 요청, Datadog Workflows 호출, 확인 프롬프트 등을 단계로 조합하고 변수 보간을 지원한다.
- `pup acp serve`: OAuth 스코프가 걸린 로컬 서버를 띄워 ACP 및 OpenAI 호환 프로토콜로 노출한다. 호환되는 아무 클라이언트나 Datadog 텔레메트리에 접근시킬 수 있다.

### 6. MCP Server와의 관계 — 대체재가 아니라 용도 분담
Datadog은 MCP Server도 함께 제공한다. 둘은 경쟁 관계가 아니라 **호출 주체가 다르다**.

| | Pup CLI | Datadog MCP Server |
| --- | --- | --- |
| 대상 | 터미널 네이티브 에이전트, CI/CD, 자동화 하네스 | IDE 안의 채팅형 어시스턴트 |
| 인터페이스 | 조합 가능한 셸 명령 (파이프·jq) | 큐레이션된 대화형 툴 |
| 안전장치 | Agent Mode에서 확인 자동 승인 | 확인 게이트(confirmation gate) 중심 |
| 커버리지 | 33개 이상 제품 도메인의 넓은 API 표면 | 상대적으로 선별된 기능 집합 |

실무에서는 MCP 툴이 있으면 그쪽을 먼저 쓰고, 커버되지 않는 영역을 Pup CLI로 보완하는 폴백 구성이 흔하다.

## 핵심 정리
- 핵심 포인트 1: Pup은 Datadog API 표면 전체(33+ 제품, 200+ 명령)를 단일 바이너리 CLI로 노출해, **AI 에이전트가 라이브 텔레메트리를 직접 조회**할 수 있게 만든 도구다.
- 핵심 포인트 2: OAuth2 + PKCE로 장기 API 키 배포 없이 사용자 RBAC를 그대로 상속받는 것이 보안상 가장 큰 차별점이다.
- 핵심 포인트 3: Agent Mode는 호출 주체를 환경변수로 감지해 JSON 응답 + 프롬프트 자동 승인으로 전환하고, `pup agent schema`로 필요한 명령만 로드해 컨텍스트 비용을 줄인다.
- 핵심 포인트 4: MCP Server와는 역할이 다르다 — 터미널/CI 자동화는 Pup, IDE 채팅 어시스턴트는 MCP.
- 핵심 포인트 5: 이름이 같은 Agent 5.x 시절의 로컬 대시보드 `pup`(포트 17125)은 Agent 6에서 제거된 별개의 레거시 도구다. 검색 시 시대 구분이 필요하다.

## 기술적 한계와 보완 전략
- 한계: Agent Mode의 확인 자동 승인이 `delete` 계열 명령까지 통과시킨다 → 보완: 에이전트용 계정에는 읽기 전용에 가까운 역할을 부여하고, 쓰기가 필요한 작업은 별도 승인 절차가 있는 워크플로로 분리한다.
- 한계: 200+ 명령 전체를 프롬프트에 넣으면 컨텍스트가 폭발한다 → 보완: `pup agent schema`로 필요한 도메인 스펙만 동적으로 로드하고, 자주 쓰는 절차는 스킬/런북으로 고정한다.
- 한계: 에이전트가 임의의 쿼리를 던지면 넓은 시간 범위 조회로 비용·레이트 리밋 문제가 생길 수 있다 → 보완: `--from` 범위를 강제하는 래퍼나 런북을 통해 호출 패턴을 표준화한다.
- 한계: `datadog-labs` 네임스페이스에서 배포되는 비교적 새 도구라 명령 스펙과 플래그가 변할 수 있다 → 보완: 버전을 고정하고 `pup --help` / `agent schema` 출력을 신뢰의 근거로 삼되, 문서에 적힌 예시를 그대로 신뢰하지 않는다.
- 한계: 이름 충돌로 인한 자료 혼선 → 보완: 검색 시 "Datadog CLI" 또는 `DataDog/pup` 저장소를 기준으로 삼고, 포트 17125가 언급되면 레거시 문서로 판단한다.

## 키워드
- **Pup CLI**: Datadog이 공개한 Rust 기반 CLI로, 33개 이상 제품 도메인에 걸쳐 200개 이상 명령을 제공하며 AI 에이전트 사용을 1급 시나리오로 상정해 설계되었다.
- **Agent Mode**: `CLAUDECODE`·`CURSOR_AGENT`·`CODEX` 등 환경변수로 호출 주체가 AI 에이전트임을 감지해, 구조화 JSON 응답과 확인 프롬프트 자동 승인으로 전환되는 실행 모드.
- **OAuth2 + PKCE**: 공개 클라이언트(CLI 등)가 클라이언트 시크릿 없이 안전하게 인가 코드를 교환하도록 보강한 OAuth 확장. 장기 API 키 배포 없이 스코프된 접근을 가능하게 한다.
- **RBAC(역할 기반 접근 제어)**: 사용자에게 부여된 역할에 따라 권한을 제한하는 모델. Pup의 OAuth 토큰은 로그인한 사용자의 RBAC를 그대로 상속한다.
- **MCP(Model Context Protocol)**: LLM 애플리케이션이 외부 도구·데이터에 연결하기 위한 개방형 프로토콜. Datadog MCP Server는 IDE 채팅형 어시스턴트를 주 대상으로 한다.
- **Agent Skills**: 코딩 에이전트에 설치해 특정 도메인 작업 절차를 주입하는 패키지. `pup skills install <agent>`로 인시던트 트리아지 등 운영 워크플로를 배포한다.
- **Runbook(런북)**: 반복되는 운영 절차를 문서·코드로 고정한 실행 가능한 순서. Pup은 YAML로 정의된 런북을 로컬에서 실행하는 엔진을 내장한다.
- **DORA 메트릭**: 배포 빈도, 변경 리드 타임, 변경 실패율, 평균 복구 시간(MTTR)으로 소프트웨어 전달 성과를 측정하는 지표 집합. Pup의 `cicd` 도메인에서 조회할 수 있다.
- **Error Tracking**: 개별 에러 이벤트를 지문(fingerprint) 기준으로 묶어 이슈 단위로 관리하는 기능. 로그 검색과 달리 "같은 원인의 반복"을 하나로 집계해 보여준다.
- **Datadog Agent**: 호스트에 상주하며 메트릭·로그·트레이스를 수집해 백엔드로 전송하는 에이전트 프로세스. Agent 5.x에는 동명의 로컬 대시보드 `pup`이 번들되어 있었으나 Agent 6에서 제거되었다.

## 참고 자료
- [DataDog/pup - GitHub](https://github.com/DataDog/pup)
- [Pup CLI 공식 문서](https://docs.datadoghq.com/cli/)
- [Give your AI agents live Datadog access from the command line (Datadog Blog)](https://www.datadoghq.com/blog/give-your-ai-agents-live-datadog-access-from-the-command-line/)
- [Datadog MCP Server 제품 페이지](https://www.datadoghq.com/product/ai/mcp-server/)
- [datadog-labs/agent-skills - GitHub](https://github.com/datadog-labs/agent-skills)
- [Agent Version Differences (Agent 5 → 6 변경점)](https://docs.datadoghq.com/agent/guide/version_differences/)
- [dd-agent Wiki - Agent Architecture (레거시 pup 관련)](https://github.com/DataDog/dd-agent/wiki/Agent-Architecture)
