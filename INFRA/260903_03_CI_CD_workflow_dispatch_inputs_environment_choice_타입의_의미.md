# CI/CD - `on.workflow_dispatch.inputs.environment: {type: choice, options: [dev, prod]}` 의 의미

## 개요

한 줄로 요약하면 이렇다.

> "이 워크플로우는 **사람이 버튼으로 직접 실행**할 수 있고, 실행할 때 **dev / prod 중 하나를 드롭다운으로 골라서** 넘길 수 있다."

각 키를 분해하면 다음과 같다.

| 키 | 의미 |
|---|---|
| `on:` | 워크플로우가 **언제** 실행되는지(트리거) 정의 |
| `workflow_dispatch` | **수동 실행** 트리거 (Actions 탭에 "Run workflow" 버튼 생성) |
| `inputs` | 수동 실행 시 사용자에게 받을 **입력 파라미터** 정의 |
| `environment` | 예약어가 아니라 개발자가 **직접 지은 파라미터 이름** |
| `type: choice` + `options` | 자유 입력이 아닌 **드롭다운 선택지**로 값을 제한 |

## 상세 내용

### 1. 인라인 표기 풀어 쓰기

```yaml
on:
  workflow_dispatch:
    inputs:
      environment: {type: choice, options: [dev, prod]}
```

`{...}`, `[...]`는 YAML의 **플로우 스타일**이고, 들여쓰기 **블록 스타일**과 완전히 동치다. 풀어 쓰면 이렇다.

```yaml
on:
  workflow_dispatch:            # 수동 실행 트리거
    inputs:                     # 실행 시 받을 파라미터 모음
      environment:              # 파라미터 이름 (내 마음대로 지음)
        description: "배포할 환경을 선택하세요"
        required: true
        default: dev
        type: choice            # 값의 형식 = 드롭다운
        options:                # 선택 가능한 값 목록
          - dev
          - prod
```

### 2. `workflow_dispatch` — 수동 실행 트리거

`on:`에 올 수 있는 대표 트리거는 다음과 같다.

| 트리거 | 실행 시점 | 방식 |
|---|---|---|
| `push` | 커밋 푸시 | 자동 |
| `pull_request` | PR 생성/갱신 | 자동 |
| `schedule` | cron 시각 도달 | 자동 |
| `workflow_dispatch` | 사람이 UI/API/CLI로 실행 | **수동** |
| `workflow_call` | 다른 워크플로우가 호출 | 호출 |

`workflow_dispatch`를 선언하면 Actions 탭에 **"Run workflow" 버튼**이 생긴다. 단, **워크플로우 파일이 기본 브랜치(main)에 있어야** 버튼이 보인다. 기능 브랜치에만 있으면 UI에 나타나지 않는다.

UI 외의 실행 방법:

```bash
# GitHub CLI
gh workflow run deploy.yml -f environment=prod

# REST API
curl -X POST -H "Authorization: Bearer <TOKEN>" \
  https://api.github.com/repos/{owner}/{repo}/actions/workflows/deploy.yml/dispatches \
  -d '{"ref":"main","inputs":{"environment":"prod"}}'
```

### 3. `inputs` — 입력 파라미터

`inputs` 하위 키 이름이 곧 파라미터 이름이다. 지정 가능한 속성:

- `description` — UI 폼 설명 문구
- `required` — 필수 여부
- `default` — 기본값
- `type` — `string`(기본) / `number` / `boolean` / `choice` / `environment`

```yaml
inputs:
  environment:
    type: choice
    options: [dev, prod]
    default: dev
    required: true
  version:
    type: string          # 자유 입력
  dryRun:
    type: boolean         # 체크박스, if: 조건에 바로 사용 가능
    default: false
```

> 제약: 파라미터는 워크플로우당 **최대 10개**, 전체 페이로드는 **65,535자** 이내.

### 4. `type: choice` 의 핵심 — 서버 사이드 검증

`choice`는 단순히 UI를 예쁘게 만드는 게 아니라 **GitHub 플랫폼이 강제하는 검증**이다.

- UI에서는 드롭다운으로 렌더링 → `Prod`, `prd`, `prod ` 같은 오타가 애초에 불가능
- **API로 직접 호출해도** `options`에 없는 값은 서버가 거부 → 우회 불가
- `default`는 반드시 `options` 안의 값이어야 함 (아니면 워크플로우 유효성 검사 실패)
- 값은 항상 **문자열**로 평가됨 (별도의 enum 타입이 있는 건 아님)

**`type: choice` vs `type: environment`**

```yaml
environment:
  type: environment   # 저장소에 등록된 Environments 목록을 GitHub가 자동으로 채워줌
```

| | `type: choice` | `type: environment` |
|---|---|---|
| 선택지 출처 | YAML에 직접 나열 | 저장소 Settings의 Environments 자동 조회 |
| 환경 추가 시 | YAML 수정 필요 | 수정 불필요 |
| 용도 | 환경 외 일반 선택지에도 사용 | 배포 환경 전용 |

### 5. 입력값 사용하기

```yaml
jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}   # ★ GitHub Environments와 연결되는 지점
    env:
      TARGET_ENV: ${{ inputs.environment }}
    steps:
      - uses: actions/checkout@v4

      - name: Prod 전용 스텝
        if: inputs.environment == 'prod'
        run: echo "prod 배포 로직"

      - name: 환경별 값 사용
        run: echo "${{ secrets.DB_URL }} / ${{ vars.API_BASE }}"
```

참조 방법은 두 가지지만 **`inputs.*`를 쓰는 것이 권장**된다.

- `${{ inputs.environment }}` — `workflow_dispatch` / `workflow_call` 모두 동작 (권장)
- `${{ github.event.inputs.environment }}` — 예전 방식, `workflow_call`에선 사용 불가

**중요**: `inputs.environment`는 그 자체로는 그냥 문자열이다. 잡 레벨의 `environment: ${{ inputs.environment }}` 한 줄을 써야 비로소 GitHub의 **Environments 기능**(승인자 · 브랜치 제한 · 환경별 Secrets)과 실제로 연결된다. 이 줄이 없으면 이름만 우연히 같은 문자열일 뿐이다.

### 6. 왜 이렇게 사용하는가 — 이 패턴의 장점

**① 워크플로우 파일을 환경별로 복사하지 않아도 된다**

`deploy-dev.yml`, `deploy-prod.yml`을 따로 두면 배포 로직을 고칠 때마다 두 파일을 동기화해야 하고, 한쪽만 고쳐서 dev/prod 동작이 갈라지는 사고가 난다. 파라미터 하나로 **단일 파일 = 단일 진실 공급원(Single Source of Truth)** 을 유지할 수 있다.

**② 휴먼 에러를 구조적으로 차단한다**

자유 텍스트 입력이었다면 `prd`, `Prod`, `production` 같은 오타로 배포가 실패하거나, 더 나쁘게는 **의도치 않은 환경에 배포**될 수 있다. `choice`는 애초에 잘못된 값을 입력할 방법 자체를 없앤다. 게다가 API 호출까지 서버가 검증하므로, 스크립트로 자동화해도 안전망이 유지된다.

**③ prod 배포에 승인 게이트를 걸 수 있다**

`environment: ${{ inputs.environment }}`로 연결해두면, `prod`를 고른 실행만 Settings의 `prod` Environment 보호 규칙(**Required reviewers**)에 걸려 승인 전까지 대기한다. dev는 즉시 배포, prod는 승인 후 배포라는 정책을 **워크플로우 코드 변경 없이 GitHub 설정만으로** 구현할 수 있다.

**④ 시크릿을 환경별로 격리한다**

같은 `${{ secrets.DB_URL }}` 코드가 dev 잡에서는 dev DB를, prod 잡에서는 prod DB를 가리킨다. **prod 시크릿이 dev 실행에 아예 노출되지 않으므로** 최소 권한 원칙이 자연스럽게 지켜진다.

**⑤ 배포 시점을 사람이 통제한다**

push 트리거 자동 배포와 달리, 릴리즈 일정·점검 시간·롤백 등 **"지금 배포할지"를 사람이 결정**해야 하는 상황에 맞는다. 머지와 배포를 분리할 수 있다.

**⑥ 실행 이력이 감사 추적으로 남는다**

누가 · 언제 · 어떤 환경으로 실행했는지가 Actions 실행 목록에 그대로 기록된다. `run-name`을 커스터마이징하면 목록만 봐도 바로 식별된다.

```yaml
run-name: ${{ github.actor }}님이 ${{ inputs.environment }} 환경으로 배포
```

### 7. 실전 예시

```yaml
name: Deploy
run-name: ${{ github.actor }}님이 ${{ inputs.environment }} 환경으로 배포

on:
  workflow_dispatch:
    inputs:
      environment:
        description: "배포 대상 환경"
        required: true
        default: dev
        type: choice
        options: [dev, prod]

jobs:
  build:
    runs-on: ubuntu-latest
    outputs:
      image-tag: ${{ steps.tag.outputs.value }}
    steps:
      - uses: actions/checkout@v4
      - id: tag
        run: echo "value=${{ inputs.environment }}-$(git rev-parse --short HEAD)" >> "$GITHUB_OUTPUT"
      - run: docker build -t myapp:${{ steps.tag.outputs.value }} .

  deploy:
    needs: build
    runs-on: ubuntu-latest
    environment: ${{ inputs.environment }}   # prod면 여기서 승인 대기
    steps:
      - run: ./deploy.sh --env=${{ inputs.environment }} --tag=${{ needs.build.outputs.image-tag }}
```

흐름: **dev/prod 선택 → 환경명을 포함해 이미지 태깅 → (prod면) 승인 게이트 통과 → 배포**

### 8. 자주 겪는 문제

| 증상 | 원인 |
|---|---|
| "Run workflow" 버튼이 안 보임 | 워크플로우 파일이 기본 브랜치에 없음 / 워크플로우 비활성화 / 쓰기 권한 부족 |
| `inputs`가 `null` | `push` 등 다른 이벤트로 트리거된 실행. `github.event_name` 확인 또는 기본값 처리 필요 |
| 유효성 검사 실패 | `default` 값이 `options` 목록에 없음 |
| YAML 린터가 `on:`을 `True:`로 표시 | YAML 1.1에서 `on`/`yes`/`no`가 불리언으로 해석됨. Actions 파서는 정상 동작하므로 무시 가능 |

## 핵심 정리

- 핵심 포인트 1: `workflow_dispatch`는 "사람이 버튼으로 돌리는 워크플로우" 트리거이며, 파일이 **기본 브랜치에 있어야** UI에 노출된다.
- 핵심 포인트 2: `inputs.environment`의 `environment`는 **예약어가 아니라** 개발자가 지은 파라미터 이름이다.
- 핵심 포인트 3: `type: choice` + `options`는 UI 편의가 아니라 **서버 사이드 검증**이다. API 호출도 막힌다.
- 핵심 포인트 4: 잡의 `environment:` 키에 연결해야 **승인 게이트 + 환경별 시크릿**이 실제로 작동한다.
- 핵심 포인트 5: 이 패턴을 쓰는 이유는 **워크플로우 단일화 · 오타 차단 · prod 승인 통제 · 시크릿 격리 · 배포 시점 통제 · 감사 추적** 6가지다.

## 기술적 한계와 보완 전략

| 한계 | 보완 |
|---|---|
| 입력 제한은 검증일 뿐 **권한 제어가 아님** | prod는 Environments의 Required reviewers + Deployment branch 제한으로 통제 |
| 파라미터 최대 10개 | 옵션이 많아지면 JSON 문자열 입력이나 설정 파일 기반으로 전환 |
| 선택지가 하드코딩되어 환경 추가 시 YAML 수정 필요 | `type: environment` 또는 재사용 워크플로우(`workflow_call`)로 중앙화 |
| 수동 실행은 이력 추적이 흐려질 수 있음 | `run-name`에 실행자/환경 노출 + 배포 로그 별도 수집 |

## 키워드

- **GitHub Actions**: GitHub에 내장된 CI/CD 플랫폼. YAML 워크플로우 파일로 빌드·테스트·배포를 자동화한다.
- **workflow_dispatch**: 워크플로우를 UI / REST API / `gh` CLI로 수동 실행할 수 있게 하는 이벤트 트리거.
- **inputs**: 수동 실행 시 받을 파라미터의 이름·타입·기본값을 정의하는 블록. 최대 10개.
- **type: choice / options**: 입력값을 `options`에 나열된 값 중 하나로 강제하는 타입. 드롭다운 UI + 서버 사이드 검증.
- **GitHub Environments**: Settings에 정의하는 배포 대상(dev, prod 등). 승인자, 배포 가능 브랜치, 환경 전용 Secrets/Variables를 가진다. 잡의 `environment:` 키로 연결해야 작동한다.
- **Required Reviewers**: Environment 보호 규칙. 해당 환경으로 배포하는 잡을 승인 전까지 대기시킨다.
- **환경별 Secrets / Variables**: Environment마다 독립 관리되는 값. 같은 코드가 환경에 따라 다른 값을 참조하게 한다.
- **컨텍스트 표현식 (`${{ }}`)**: 워크플로우 YAML에서 입력값·이벤트 정보·시크릿을 참조하는 문법.
- **Single Source of Truth**: 하나의 정의만 유지해 환경 간 설정 불일치를 방지하는 원칙.

## 참고 자료

- [Workflow syntax for GitHub Actions - GitHub Docs](https://docs.github.com/actions/using-workflows/workflow-syntax-for-github-actions)
- [Events that trigger workflows - GitHub Docs](https://docs.github.com/actions/using-workflows/events-that-trigger-workflows)
- [Manually running a workflow - GitHub Docs](https://docs.github.com/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)
- [Using environments for deployment - GitHub Docs](https://docs.github.com/actions/deployment/targeting-different-environments/using-environments-for-deployment)
- [GitHub Actions: Input types for manual workflows - GitHub Changelog](https://github.blog/changelog/2021-11-10-github-actions-input-types-for-manual-workflows/)
