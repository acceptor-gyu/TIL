# Datadog pup CLI

> 선행 학습: [Datadog pup](./260911_02_Datadog_pup.md) — 도구의 등장 배경과 전체 개요를 다룬다.
> 이 문서는 **CLI 자체의 사용법과 운영 적용**에 초점을 맞춘다.

## 개요
Pup CLI는 Datadog이 배포한 단일 Rust 바이너리로, `pup <도메인> <동작> [옵션]` 형태의 계층적 명령을 통해 33개 이상 제품·50여 개 도메인을 터미널에서 조작한다. 이 문서는 개요 문서([260911_02](./260911_02_Datadog_pup.md))에서 다룬 배경과 별개로 **실제로 손에 쥐고 쓸 때 필요한 명령 스펙, 인증 설정, 쿼리 문법, 파이프라인 조합, CI/CD·에이전트 통합 방법**을 정리한다. 즉 "왜 만들어졌는가"가 아니라 "오늘 당장 어떻게 쓰는가"에 초점을 맞춘다.

## 상세 내용

### 1. 설치와 업데이트
```bash
# Homebrew (macOS/Linux)
brew tap datadog-labs/pack
brew install datadog-labs/pack/pup

# 소스 빌드 (Rust 툴체인 필요)
git clone https://github.com/DataDog/pup && cd pup
cargo build --release

# 또는 GitHub Releases에서 플랫폼별 바이너리 직접 다운로드
```
- 세 경로 모두 결과물은 동일한 단일 바이너리다. 팀 공용 CI 이미지에는 버전을 고정한 릴리스 바이너리를, 로컬 개발에는 Homebrew를 쓰는 조합이 관리하기 쉽다.
- `pup --version`으로 설치된 버전을, `pup --help` 또는 `pup agent schema`로 **현재 빌드에 실제로 존재하는 명령 스펙**을 확인한다. 아직 활발히 개발 중인 도구라 문서보다 `--help` 출력을 1차 근거로 삼는 편이 안전하다.

### 2. 인증 체계
| 방식 | 환경변수/명령 | 용도 |
| --- | --- | --- |
| OAuth2 + PKCE | `pup auth login` | 로컬 개발·에이전트(권장). 자동 갱신, 키체인 저장 |
| API 키 | `DD_API_KEY`, `DD_APP_KEY` | CI 등 브라우저 없는 환경 |
| Bearer 토큰 | `DD_ACCESS_TOKEN` | WASM·헤드리스 등 세션을 유지 못 하는 환경 |

- 우선순위는 **`DD_ACCESS_TOKEN` → OAuth 세션 → API 키** 순으로 평가된다. 여러 방식이 동시에 설정돼 있어도 상위 우선순위가 이긴다.
- `pup auth login`은 Dynamic Client Registration(DCR)으로 클라이언트 자격 증명을 발급받고, PKCE 코드 챌린지를 생성한 뒤 로컬 콜백 서버를 열어 브라우저 인가를 받는다. 승인 후 코드 검증자(code verifier)로 토큰을 교환하므로 인가 코드가 가로채여도 재사용할 수 없다.
- 발급된 토큰은 macOS Keychain / Linux Secret Service / Windows Credential Manager에 저장되고, 시크릿 스토어가 없는 환경에서는 `~/.config/pup/`에 `0600` 권한의 JSON 파일로 폴백한다. Access Token은 1시간, Refresh Token은 30일 유효하며 만료 전 자동 갱신된다.
- `pup auth status`로 현재 세션을, `pup auth logout`으로 토큰을 폐기한다. `pup auth token`은 access token만 개행 문자와 함께 stdout으로 내보내는데, 다른 Bearer 기반 도구에 넘겨 쓰기 위한 것이며 보안상 Agent Mode 스키마와 WASM 빌드에서는 제외된다.
- **환경별 선택 기준**: 로컬 개발·에이전트 사용 = OAuth2, CI/CD = API 키, 상태를 못 들고 있는 서버리스/WASM = Bearer 토큰.
- 멀티 리전은 `DD_SITE`(또는 설정 파일)로 지정하며, 지정이 없으면 세션의 조직 메타데이터를 참고하고 최종적으로 `datadoghq.com`으로 기본값이 잡힌다. US1/EU1/US3/US5/AP1/AP2·정부 리전까지 지원한다.

### 3. 명령 체계 이해하기
- 기본 패턴은 `pup <도메인> <동작> [옵션]`(예: `pup monitors list`)이고, 하위 그룹이 있는 도메인은 `pup <도메인> <하위그룹> <동작>`(예: `pup rum apps list`) 형태로 한 단계 더 깊어진다.
- 도메인은 관측(metrics·logs·traces·rum·events·database monitoring), 모니터링(monitors·dashboards·slos·synthetics·notebooks), 인프라(hosts·network devices·tags·profiling), 보안(rules·signals·audit-logs·sensitive data scanning), 개발(cicd·code coverage·error-tracking·service catalog), 운영(incidents·on-call·workflows·runbooks·change requests), 조직(users·api-keys·app-keys) 영역으로 나뉜다.
- 공통 전역 플래그: `--config`(설정 파일 경로), `--site`(리전), `--output`/`-o`(포맷), `--jq`(응답 가공), `--verbose`, `--yes`(확인 프롬프트 스킵), `--read-only`(쓰기 계열 명령 차단).
- 출력 포맷은 `json`(기본, 스크립트·에이전트용), `table`(사람이 눈으로 훑을 때), `yaml`, `csv`, `tsv` 중 선택한다. 사람이 대화형으로 쓸 때는 `-o table`, 파이프라인에 태울 때는 기본 JSON에 `--jq`를 얹는 조합이 일반적이다.

### 4. 쿼리 작성법
- **동작 패턴**: 목록 조회는 `pup <도메인> list`, 단건 조회는 `pup <도메인> get <id>`, 검색은 도메인별 `--query` 문법을 쓰고, 생성·수정은 `--file`로 페이로드를 전달하는 CRUD 패턴이 공통적으로 반복된다.
- 로그 검색 문법: `pup logs search --query="status:error service:checkout" --from="1h"` — Datadog Log Search 문법을 그대로 사용하므로 `status:`, `service:`, `env:` 같은 파셋 필터를 조합할 수 있다.
- 메트릭 쿼리 문법: `pup metrics query --query="avg:system.cpu.user{env:prod} by {host}" --from="1h"` — 메트릭 질의어(MQL)를 그대로 받는다.
- 시간 범위는 절대값뿐 아니라 `1h`, `now-1w` 같은 상대 표현을 지원한다.
- 팀 소유권 필터(`--tags="team:api-platform"`)로 우리 팀 리소스만 좁혀볼 수 있고, Database Monitoring처럼 `--query="dbm_type:activity service:orders env:prod"` 식의 도메인 전용 필터 문법을 쓰는 영역도 있다.
- 대량 결과는 `--limit`으로 개수를 제한하거나, Service Catalog·IDP 같은 도메인에서는 `--cursor` 기반 페이지네이션을 쓴다.

### 5. 파이프라인 조합
- `--jq` 플래그는 포맷팅 이전에 raw JSON을 가공한다(`pup logs search --query=... | pup 자체 --jq '.data[].attributes.message'` 대신 플래그 한 번으로 필드 추출 가능). 물론 기존처럼 외부 `jq` 바이너리에 파이프로 넘겨도 동일하게 동작한다.
- 조사 흐름은 보통 "로그에서 에러 확인 → 관련 트레이스 조회 → 최근 배포 이벤트 대조" 순으로 여러 `pup` 호출을 이어 붙인다.
- 자주 쓰는 조회는 셸 함수나 alias로 고정해두면 매번 옵션을 다시 조합할 필요가 없다.
- 비정상 종료 시 0이 아닌 exit code와 함께 JSON 에러 응답(에러 코드·힌트 포함)을 돌려주므로, 셸 스크립트에서 `$?` 체크와 에러 본문 파싱을 함께 하는 편이 안전하다.

### 6. 실무 시나리오
- 장애 대응: 알림 수신 직후 `pup incidents list --status=active`, `pup monitors list --tags="team:..."`, `pup logs search`를 연속 호출해 터미널에서 1차 트리아지를 마친다.
- 배포 전후 지표 비교: `pup metrics query`로 배포 전후 구간을 비교하고, 이상 있으면 `pup dashboards url ... --from=now-1w --to=now`로 팀 채널에 공유할 스냅샷 URL을 만든다.
- 모니터·대시보드 정의를 `list`/`get` + `--file` export로 뽑아 코드로 형상 관리(Observability as Code)하는 흐름에 붙일 수 있다.
- SLO 도메인으로 error budget 소진율을 주기적으로 조회해 리포팅 스크립트에 태운다.

### 7. CI/CD 파이프라인에 넣기
- CI에서는 브라우저 로그인이 불가능하므로 `DD_API_KEY`/`DD_APP_KEY` 환경변수 기반 인증을 쓴다. 시크릿 매니저에 키를 저장하고 파이프라인 스텝에만 주입한다.
- `cicd` 도메인으로 배포 이벤트 기록, 소스맵 업로드, 테스트 실행 결과 조회가 가능하고, 실패를 flaky / 인프라 문제 / 실제 회귀로 분류하는 후속 분석에 활용할 수 있다.
- 같은 도메인에서 DORA 메트릭(배포 빈도, 리드 타임, 변경 실패율, MTTR)을 조회해 릴리스 품질 대시보드에 반영한다.
- 게이트 조건(예: "에러율이 임계치를 넘으면 배포 중단")으로 쓸 때는 `--read-only`로 조회 전용임을 보장하고, 네트워크 지연으로 인한 오탐을 막기 위해 타임아웃과 재시도 정책을 명시적으로 둔다.

### 8. Agent Mode와 자동화
- Pup은 `CLAUDECODE`, `CURSOR_AGENT`, `CODEX` 같은 환경변수로 자신이 AI 에이전트에 의해 호출됐는지 자동 감지하며, `--agent` 플래그나 `FORCE_AGENT_MODE=1`로 강제할 수도 있다.
- Agent Mode에서는 메타데이터·에러 상세·힌트가 포함된 JSON을 반환하고 확인 프롬프트를 자동 승인한다.
- `pup agent schema`는 사용 가능한 명령 스펙을 구조화된 JSON으로 반환해, 200개가 넘는 명령 전체를 프롬프트에 넣는 대신 필요한 것만 동적으로 로드하게 해준다.
- `pup skills install <claude-code|cursor|codex|...>`로 인시던트 트리아지, 로그·트레이스·배포 이력 상관 분석 같은 운영 워크플로를 코딩 에이전트에 스킬 형태로 주입한다. 개별 스킬 이름으로 선택 설치도 가능하다.
- 런북은 `~/.config/pup/runbooks/`에 저장된 YAML로 pup 명령, 셸 스크립트, HTTP 요청, Datadog Workflows 호출, 확인 스텝을 조합하며 변수 보간·조건부 실행·폴링·출력 캡처를 지원한다.
- `pup acp serve`는 로컬에 Agent Communication Protocol(ACP) 서버와 OpenAI 호환 엔드포인트를 동시에 띄워, opencode·Cursor 등 호환 클라이언트가 Datadog Bits AI 에이전트에 직접 접근하게 한다.

### 9. 안전하게 쓰기
- Agent Mode의 자동 승인은 `delete`·`update` 같은 파괴적 명령에도 그대로 적용된다. 이를 막는 안전장치가 바로 `--read-only` 플래그이며, 에이전트용 자격 증명 자체를 읽기 전용 역할로 발급하는 편이 근본적인 대응이다.
- OAuth 토큰은 로그인한 사용자의 RBAC 권한을 그대로 상속하므로, 에이전트가 광범위한 쓰기 권한을 갖지 않게 하려면 애초에 좁은 역할의 계정으로 로그인시킨다.
- 넓은 시간 범위(`--from` 값이 매우 큰 경우)로 로그·메트릭을 조회하면 쿼리 비용과 API 레이트 리밋에 걸릴 수 있으므로, 래퍼 스크립트나 런북에서 조회 범위를 표준화해두는 것이 좋다.
- 아직 `datadog-labs` 계열의 비교적 신생 도구이므로 명령 스펙과 플래그가 버전 간 바뀔 수 있다. 운영에 태우기 전 버전을 고정하고 `--help`/`agent schema` 출력을 신뢰의 기준으로 삼는다.

### 10. 대안과 비교
- **Datadog MCP Server**: IDE 채팅형 어시스턴트를 위한 큐레이션된 대화형 도구 집합이다. Pup은 터미널 네이티브 에이전트·CI/CD·자동화 하네스를 대상으로 넓은 API 표면(50+ 도메인)을 셸 명령으로 노출한다는 점이 다르다. 겹치지 않는 영역은 서로 보완 관계다(예: 공식 문서는 프로파일링처럼 Pup이 아직 지원하지 않는 기능은 MCP 서버를 쓰라고 안내한다).
- `datadog-ci`: 배포 이벤트·소스맵 업로드 등 CI 특화 기능에 집중된 기존 도구로, Pup의 `cicd` 도메인과 기능이 겹치지만 Pup은 CI 전용이 아니라 전 도메인을 아우르는 범용 CLI라는 차이가 있다.
- Terraform Datadog Provider: 모니터·대시보드 등의 **선언적 형상 관리**가 목적이라 Pup의 명령형 조회·운영 성격과는 역할이 다르다. 리소스를 코드로 정의·적용하는 것은 Terraform, 즉석에서 조사하고 조작하는 것은 Pup으로 구분하는 것이 자연스럽다.
- 복잡한 시각화, 대규모 대시보드 편집처럼 시각적 상호작용이 필요한 작업은 여전히 웹 UI가 우세하다.

## 핵심 정리
- 핵심 포인트 1: Pup은 `pup <도메인> <동작>` 계층 구조와 `--output`/`--jq`로 조합 가능한 출력을 제공해, 사람의 대화형 조사와 스크립트·에이전트의 파이프라인 소비를 같은 바이너리로 커버한다.
- 핵심 포인트 2: 인증은 OAuth2+PKCE(로컬·에이전트) → API 키(CI) → Bearer 토큰(WASM)으로 환경별 역할이 명확히 나뉘고, 우선순위는 `DD_ACCESS_TOKEN > OAuth 세션 > API 키` 순이다.
- 핵심 포인트 3: Agent Mode는 환경변수 자동 감지로 JSON 응답과 확인 프롬프트 자동 승인으로 전환되지만, 이는 곧 파괴적 명령이 그대로 통과할 위험이기도 해 `--read-only`와 좁은 RBAC 역할로 상쇄해야 한다.
- 핵심 포인트 4: `pup agent schema`·`pup skills install`·런북·`pup acp serve`는 각각 컨텍스트 최적화, 워크플로 주입, 절차 고정, 로컬 에이전트 서버 노출이라는 서로 다른 층위의 자동화 도구다.
- 핵심 포인트 5: MCP Server, `datadog-ci`, Terraform Provider와는 경쟁이 아니라 역할 분담 관계이며, 필요한 만큼만 겹치는 영역을 확인하고 나머지는 각 도구의 강점대로 나눠 쓰는 것이 실무적이다.

## 기술적 한계와 보완 전략
- 한계: Agent Mode의 확인 자동 승인이 `delete`/`update` 계열 명령까지 통과시킨다 → 보완: 에이전트용 계정에는 읽기 전용에 가까운 RBAC 역할을 부여하고, `--read-only` 플래그를 기본값으로 래핑한 셸 함수를 배포한다.
- 한계: 200개 이상 명령 전체를 프롬프트에 넣으면 컨텍스트가 폭발한다 → 보완: `pup agent schema`로 필요한 도메인 스펙만 동적으로 로드하고, 반복되는 조사 절차는 스킬·런북으로 고정해 재질의를 줄인다.
- 한계: 넓은 시간 범위 조회가 비용·레이트 리밋 문제를 일으킬 수 있다 → 보완: `--from` 범위를 강제하는 래퍼나 런북으로 호출 패턴을 표준화하고, CI 게이트에는 타임아웃·재시도 정책을 명시한다.
- 한계: `datadog-labs` 네임스페이스에서 배포되는 비교적 신생 도구라 명령 스펙과 플래그가 바뀔 수 있다 → 보완: 팀 공용 환경에서는 버전을 고정하고, 문서보다 `pup --help`/`agent schema` 실측 출력을 신뢰의 근거로 삼는다.
- 한계: 명시적인 감사 로그 기능이 공식 문서에 별도로 정리돼 있지 않다 → 보완: OAuth 토큰이 사용자 RBAC를 상속한다는 점을 활용해 Datadog 조직 측 감사 로그(Audit Trail)에서 호출 주체를 추적하고, CI 환경에서는 API 키를 서비스 계정 단위로 분리해 발급한다.

## 키워드
- **Pup CLI**: Datadog이 공개한 Rust 기반 단일 바이너리 CLI. 50여 개 도메인, 200개 이상 명령을 계층 구조(`pup <도메인> <동작>`)로 제공하며 AI 에이전트 사용을 1급 시나리오로 설계했다.
- **DD_SITE(리전 엔드포인트)**: 어느 Datadog 사이트(US1/EU1/US3/US5/AP1/AP2/정부 리전 등)에 연결할지 지정하는 설정. 환경변수·설정 파일 값이 없으면 세션 메타데이터를, 그마저 없으면 `datadoghq.com`을 기본값으로 쓴다.
- **OAuth2 + PKCE**: 공개 클라이언트(CLI 등)가 클라이언트 시크릿 없이도 안전하게 인가 코드를 교환하도록 코드 챌린지/검증자를 추가한 OAuth 확장. Pup은 여기에 Dynamic Client Registration까지 더해 로그인 시마다 고유 클라이언트 자격 증명을 발급받는다.
- **RBAC(역할 기반 접근 제어)**: 사용자에게 부여된 역할에 따라 권한을 제한하는 모델. Pup의 OAuth 토큰은 로그인한 사용자의 RBAC 권한을 그대로 상속하므로, 에이전트용 계정 설계가 곧 CLI 권한 설계가 된다.
- **Agent Mode**: `CLAUDECODE`·`CURSOR_AGENT`·`CODEX` 등 환경변수로 호출 주체가 AI 에이전트임을 감지해, 구조화 JSON 응답과 확인 프롬프트 자동 승인으로 동작이 전환되는 실행 모드.
- **Runbook(런북)**: `~/.config/pup/runbooks/`에 YAML로 정의해두는 반복 운영 절차. pup 명령·셸 스크립트·HTTP 요청·Workflows 호출·확인 스텝을 조합하고 변수 보간과 조건부 실행을 지원한다.
- **Rate Limit(API 레이트 리밋)**: 일정 시간 동안 허용되는 API 호출 수를 제한하는 정책. 넓은 시간 범위 조회나 에이전트의 반복 질의가 한도를 초과하면 요청이 거절될 수 있다.
- **DORA 메트릭**: 배포 빈도, 변경 리드 타임, 변경 실패율, 평균 복구 시간(MTTR)으로 소프트웨어 전달 성과를 측정하는 지표 집합. Pup의 `cicd` 도메인에서 조회할 수 있다.
- **SLO / Error Budget**: 서비스 수준 목표(SLO)와 그 목표 안에서 허용되는 실패 여유분(Error Budget). Pup의 `slos` 도메인으로 소진율을 조회해 배포 강행 여부 판단 근거로 쓴다.
- **Observability as Code**: 모니터·대시보드·SLO 같은 관측 설정을 코드(파일)로 정의해 버전 관리하는 접근. Pup의 export(`get`/`list` + `--file`)와 Terraform Datadog Provider가 이 흐름의 서로 다른 축을 담당한다.

## 참고 자료
- [DataDog/pup - GitHub](https://github.com/DataDog/pup)
- [Pup CLI 공식 문서 (docs.datadoghq.com/cli)](https://docs.datadoghq.com/cli/)
- [pup/docs/COMMANDS.md - 명령 구조와 전역 플래그](https://github.com/DataDog/pup/blob/main/docs/COMMANDS.md)
- [pup/docs/OAUTH2.md - OAuth2 + PKCE 인증 플로우](https://github.com/DataDog/pup/blob/main/docs/OAUTH2.md)
- [Give your AI agents live Datadog access from the command line (Datadog Blog)](https://www.datadoghq.com/blog/give-your-ai-agents-live-datadog-access-from-the-command-line/)
- [Datadog MCP Server 제품 페이지](https://www.datadoghq.com/product/ai/mcp-server/)
- [Log Search Syntax - Datadog 공식 문서](https://docs.datadoghq.com/logs/explorer/search_syntax/)
- [Metrics Query Language - Datadog 공식 문서](https://docs.datadoghq.com/metrics/advanced-filtering/)
