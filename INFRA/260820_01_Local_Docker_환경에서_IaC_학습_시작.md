# Local Docker 환경에서 IaC 학습 시작

## 개요

AWS 같은 클라우드 계정 없이도, 로컬 Docker 환경만으로 IaC(Infrastructure as Code)를 학습할 수 있는 이유와 그 방법을 정리한다. Terraform의 Docker Provider, Docker Compose, LocalStack을 활용하면 비용 부담 없이 선언형 인프라 관리, 상태(State) 관리, 멱등성이라는 IaC의 핵심 개념을 실습으로 체득할 수 있다.

- 왜 로컬 Docker 환경에서 IaC를 시작하는가
- 클라우드 실습 대비 로컬 실습의 장단점
- 학습 로드맵 개요

## 상세 내용

### 1. IaC를 로컬에서 시작해야 하는 이유

- 클라우드 계정/과금 없이 반복 실습 가능 — `terraform apply` / `destroy`를 초 단위로 반복해도 비용이 발생하지 않는다.
- 생성-변경-파괴(Create/Update/Destroy) 사이클을 빠르게 반복하며 Terraform의 리소스 라이프사이클(`create`, `read`, `update`, `delete`)을 체감할 수 있다.
- 실수해도 복구 비용이 0에 가까움 — 컨테이너를 잘못 지워도 `docker rm`, `terraform apply` 재실행으로 즉시 복구 가능.
- IaC의 본질은 "어떤 클라우드를 쓰는가"가 아니라 "인프라를 코드로 선언하고 재현 가능하게 관리하는가"에 있으므로, Provider만 Docker로 바꿔도 핵심 개념 학습에는 지장이 없다.
- 선언형 사고방식(원하는 최종 상태를 기술)을 먼저 익히고, 이후 AWS/GCP 등 실제 클라우드 Provider로 자연스럽게 확장할 수 있다.

### 2. 실습 환경 구성

- **Docker Desktop / Colima 설치**: Colima는 macOS/Linux에서 Docker Desktop 없이도 경량 VM으로 Docker Daemon을 띄울 수 있는 오픈소스 대안이다.
- **Docker Daemon 소켓 확인**: `docker context ls`, `echo $DOCKER_HOST`로 Terraform Docker Provider가 접근할 소켓 경로(`unix:///var/run/docker.sock` 등)를 확인한다.
- **Terraform CLI 설치**: `kreuzwerker/docker` Provider는 Terraform 1.1.5 이상을 요구한다. `tfenv`로 여러 버전을 프로젝트별로 전환하며 관리하면 편리하다.
- **작업 디렉터리 구조 설계**
  ```
  .
  ├── main.tf         # Provider, 리소스 정의
  ├── variables.tf    # 입력 변수 선언
  ├── outputs.tf      # 출력값 선언
  └── terraform.tfvars
  ```
- **`.gitignore` 설정**: `.terraform/`(Provider 플러그인 캐시), `*.tfstate`, `*.tfstate.backup`, `.terraform.lock.hcl`(팀 정책에 따라 커밋 여부 결정)을 반드시 제외한다. State 파일에는 민감 정보가 평문으로 남을 수 있어 Git에 커밋하면 안 된다.

### 3. Terraform Docker Provider로 첫 리소스 만들기

`kreuzwerker/docker`는 Terraform Registry에 등록된 커뮤니티 Provider로, `docker pull`/`docker run`에 대응하는 리소스를 제공한다.

```hcl
terraform {
  required_providers {
    docker = {
      source  = "kreuzwerker/docker"
      version = "~> 3.0"
    }
  }
}

provider "docker" {}

resource "docker_image" "nginx" {
  name         = "nginx:latest"
  keep_locally = false
}

resource "docker_container" "web" {
  name  = "tf-nginx"
  image = docker_image.nginx.image_id

  ports {
    internal = 80
    external = 8080
  }
}
```

- `terraform init` — `required_providers`에 선언된 Provider 플러그인을 다운로드하고 `.terraform.lock.hcl`로 버전을 고정한다.
- `docker_image` — `docker pull`에 대응하며 이미지가 로컬에 이미 있으면 재사용한다.
- `docker_container` — `docker run`에 대응하며 포트 매핑, 볼륨, 환경변수 등을 선언적으로 기술한다.
- `terraform plan` — 실제로 아무것도 바꾸지 않고, 현재 State와 코드의 차이를 계산해 "무엇이 생성/변경/삭제될지" 미리 보여준다.
- `terraform apply` — `plan` 결과를 실행해 실제 컨테이너를 생성하고 State 파일을 갱신한다.
- `terraform destroy` — State에 기록된 리소스를 역순으로 정리한다.

### 4. IaC 핵심 개념을 로컬에서 체감하기

- **선언형(Declarative) vs 명령형(Imperative)**: `docker run` 같은 명령형은 "어떻게" 할지를 순서대로 지시하지만, Terraform은 "무엇을" 원하는지(최종 상태)만 기술하고 실행 순서·차이 계산은 도구가 담당한다.
- **멱등성(Idempotency)**: 동일한 `.tf` 코드로 `terraform apply`를 여러 번 실행해도 이미 원하는 상태라면 아무 변화가 없다(`No changes. Your infrastructure matches the configuration.`). 이는 State와 실제 인프라를 비교(refresh)하는 과정에서 보장된다.
- **State 파일의 역할과 Drift**: `terraform.tfstate`는 Terraform이 마지막으로 알고 있는 리소스의 실제 속성을 JSON으로 기록한 파일이다. 사용자가 `docker rm`으로 컨테이너를 직접 지우면 State와 실제 인프라가 어긋나는 Drift가 발생하고, 다음 `plan`에서 이를 감지해 재생성을 제안한다.
- **리소스 의존성 그래프**: Terraform은 리소스 참조(`docker_image.nginx.image_id`처럼 다른 리소스의 속성을 참조)를 통해 암묵적 의존성을 추론하고, 이를 기반으로 DAG(방향성 비순환 그래프)를 만들어 병렬/순차 실행 순서를 결정한다. 참조로 표현할 수 없는 의존은 `depends_on`으로 명시한다.
- **변수(variable) / 출력(output) / 지역값(locals)**: `variable`은 외부에서 주입 가능한 입력값, `output`은 apply 후 노출할 결과값(예: 컨테이너 IP), `locals`는 코드 내부에서 재사용할 계산된 값을 정의할 때 사용한다.

### 5. Docker Compose와 IaC의 차이

| 구분 | Docker Compose | Terraform |
|---|---|---|
| 목적 | 여러 컨테이너의 오케스트레이션(로컬 개발/테스트) | 다양한 Provider(클라우드, SaaS, Docker 등)를 아우르는 범용 인프라 관리 |
| 상태 추적 | 없음 (매번 현재 컨테이너 상태를 기준으로 up/down) | `.tfstate`로 리소스 상태를 명시적으로 추적 |
| 대상 범위 | Docker 컨테이너/네트워크/볼륨에 한정 | AWS/GCP/Azure/Kubernetes/Docker 등 리소스 전반 |
| 실행 계획 미리보기 | 없음 | `terraform plan`으로 변경 사항 사전 검토 가능 |
| 적합한 상황 | 로컬 개발 환경, 간단한 다중 컨테이너 앱 구성 | 프로덕션 인프라, 클라우드 리소스까지 포함한 전체 인프라 관리 |

- 로컬 개발 편의성만 필요하면 Compose로 충분하지만, "인프라의 변경 이력·상태를 추적"하거나 "클라우드 리소스까지 함께 관리"해야 한다면 Terraform이 적합하다.
- 실무에서는 두 도구를 함께 쓰기도 한다(Compose로 로컬 개발, Terraform으로 클라우드 배포).

### 6. LocalStack으로 AWS 리소스 흉내내기

- LocalStack은 AWS 서비스를 컨테이너 하나로 로컬에서 에뮬레이션하는 도구로, S3/DynamoDB/SQS/SNS/Lambda/IAM 등 핵심 서비스를 무료(Community/Hobby) 티어에서 제공한다.
- Terraform에서는 AWS Provider의 `endpoints` 블록으로 각 서비스 엔드포인트를 LocalStack 컨테이너 주소(`http://localhost:4566`)로 재지정해 사용한다.

```hcl
provider "aws" {
  region                      = "us-east-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_requesting_account_id  = true

  endpoints {
    s3       = "http://localhost:4566"
    dynamodb = "http://localhost:4566"
  }
}

resource "aws_s3_bucket" "example" {
  bucket = "local-test-bucket"
}

resource "aws_dynamodb_table" "example" {
  name         = "local-test-table"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }
}
```

- 2026년 3월 23일부터 LocalStack은 최신 릴리스 사용 시 무료 Hobby 플랜이라도 계정 등록과 Auth Token 발급이 필요해졌다. 완전 오프라인 실습을 원한다면 이 정책 변화를 반드시 확인해야 한다.
- 무료 티어는 서비스 종류뿐 아니라 동시 세션 수, 일부 고급 기능(예: IAM 세밀한 정책 검증)이 제한되므로, 실제 프로덕션 동작을 100% 재현하지는 못한다는 점을 유의한다.

### 7. 다음 단계로 넘어가기

- **원격 백엔드 전환**: 로컬 `terraform.tfstate`는 팀 협업 시 State Locking이 없어 동시 `apply` 충돌 위험이 있다. `backend "s3"` + DynamoDB(State Lock 테이블)로 전환하면 State를 원격에서 안전하게 공유·잠금할 수 있다.
- **Module로 재사용 가능한 구성 추출**: 반복되는 리소스 묶음을 `module` 블록으로 분리해 여러 환경(dev/stage/prod)에서 재사용한다.
- **CI 파이프라인에서 자동 검증**: `terraform fmt -check`(포맷 검사), `terraform validate`(문법/설정 검증), `terraform plan`(변경 사항 리뷰)을 PR 단계에서 자동 실행해 코드 품질과 예기치 않은 변경을 사전에 차단한다.

## 핵심 정리

- IaC의 핵심은 클라우드 계정 유무가 아니라 "선언형으로 인프라를 기술하고 State로 추적한다"는 사고방식이며, Docker Provider만으로도 이를 충분히 연습할 수 있다.
- `terraform plan → apply → destroy` 사이클과 State/Drift 개념을 로컬에서 무료로, 빠르게 반복 학습할 수 있다.
- Docker Compose는 컨테이너 오케스트레이션에 특화된 도구이고, Terraform은 State 추적과 멀티 Provider 지원이 강점인 범용 IaC 도구다.
- LocalStack으로 AWS 리소스를 흉내내며 실제 클라우드 Provider 문법과 유사한 코드를 미리 연습할 수 있지만, 계정 등록 요건 등 정책 변화와 기능 제한을 인지해야 한다.

## 기술적 한계와 보완 전략

- 로컬 Docker Provider는 실제 클라우드의 IAM/네트워크(VPC, 보안 그룹)/과금 모델을 재현하지 못한다 → 로컬에서 개념을 익힌 뒤 반드시 실제 클라우드 프리티어 계정에서 최종 검증한다.
- LocalStack 무료 버전은 지원 서비스와 기능이 제한적이고, 2026년부터 계정 등록이 필요해졌다 → 완전 오프라인이 필요하면 정책 변경 여부를 사전에 확인하고, 유료 Pro 플랜 대비 학습 목적에 맞는 서비스만 선택적으로 활용한다.
- 로컬 `tfstate`는 협업/동시 실행에 취약하다(State Locking 부재) → 학습 초기에는 로컬 State로 시작하되, 팀 프로젝트로 확장 시 즉시 원격 백엔드(S3 + DynamoDB Lock)로 전환한다.
- 로컬에서 통과한 코드가 클라우드에서 그대로 동작한다는 보장이 없다(Provider별 인자·제약 차이) → `terraform plan`을 실제 클라우드 계정 대상으로도 반드시 재실행해 검증한다.

## 키워드

- **IaC (Infrastructure as Code)**: 인프라 구성을 코드로 작성해 버전 관리·재현·자동화가 가능하게 하는 방법론. 수동 클릭옵스 대비 일관성과 감사 추적성을 확보한다.
- **Terraform**: HashiCorp에서 개발한 선언형 IaC 도구로, HCL(HashiCorp Configuration Language)로 리소스를 기술하고 다양한 Provider(AWS, GCP, Docker 등)를 통해 실제 인프라를 프로비저닝한다.
- **Docker Provider**: Terraform에서 Docker 리소스(이미지, 컨테이너, 네트워크, 볼륨)를 관리할 수 있게 해주는 커뮤니티 Provider(`kreuzwerker/docker`). 클라우드 계정 없이 Terraform 문법을 익히기에 적합하다.
- **선언형(Declarative)**: "어떻게 할지"가 아니라 "무엇을 원하는지(최종 상태)"만 기술하는 방식. 실행 순서와 변경 계산은 도구가 담당한다.
- **멱등성(Idempotency)**: 동일한 연산을 여러 번 수행해도 결과가 달라지지 않는 성질. IaC 도구가 State를 비교해 필요한 변경만 적용함으로써 보장한다.
- **State / Drift**: State는 Terraform이 관리하는 리소스의 마지막 known 상태를 기록한 파일이며, Drift는 실제 인프라가 State와 어긋난 상태를 의미한다.
- **terraform plan & apply**: `plan`은 코드와 State를 비교해 변경 계획만 보여주는 dry-run, `apply`는 그 계획을 실제로 실행해 인프라를 변경하고 State를 갱신하는 명령.
- **LocalStack**: AWS 클라우드 서비스를 로컬 컨테이너 환경에서 에뮬레이션해주는 도구로, 실제 AWS 계정 없이 S3/DynamoDB 등을 테스트할 수 있게 해준다.
- **Docker Compose**: `docker-compose.yml`로 여러 컨테이너의 구성과 관계를 선언적으로 정의하고 `docker compose up`으로 한 번에 기동하는 오케스트레이션 도구.
- **Remote Backend**: Terraform State를 로컬 파일이 아닌 S3, Terraform Cloud 등 원격 저장소에 보관해 팀 협업과 State Locking을 지원하는 구성.

## 참고 자료

- [kreuzwerker/docker Provider - Terraform Registry](https://registry.terraform.io/providers/kreuzwerker/docker/latest/docs)
- [docker_image Resource - Terraform Registry](https://registry.terraform.io/providers/kreuzwerker/docker/latest/docs/resources/image)
- [docker_container Resource - Terraform Registry](https://registry.terraform.io/providers/kreuzwerker/docker/latest/docs/resources/container)
- [Build Infrastructure with Docker - HashiCorp Developer Tutorial](https://developer.hashicorp.com/terraform/tutorials/docker-get-started/docker-build)
- [Terraform State - HashiCorp Developer Docs](https://developer.hashicorp.com/terraform/language/state)
- [Terraform Backend Configuration - HashiCorp Developer Docs](https://developer.hashicorp.com/terraform/language/backend)
- [LocalStack Documentation](https://docs.localstack.cloud/)
- [Colima - GitHub](https://github.com/abiosoft/colima)
