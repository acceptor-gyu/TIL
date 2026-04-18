# Terraform은 무엇이고 어떻게 활용하는가

## 개요

인프라를 수동으로 클릭해서 구성하는 방식은 규모가 커질수록 실수가 잦아지고, 환경 간 일관성을 유지하기가 어렵다. Terraform은 이런 문제를 해결하기 위해 HashiCorp가 개발한 IaC(Infrastructure as Code) 도구다. 인프라를 코드로 선언하고, 해당 코드를 실행하면 클라우드 리소스가 자동으로 생성/변경/삭제된다.

- Terraform 이란 무엇인가
- IaC(Infrastructure as Code)의 필요성
- Terraform이 해결하는 문제

## 상세 내용

### 1. Terraform의 핵심 개념

Terraform은 HCL(HashiCorp Configuration Language)로 작성된 `.tf` 파일을 읽어 인프라를 관리한다.

**Provider**

Provider는 Terraform이 특정 플랫폼(AWS, GCP, Azure 등)과 통신하게 해주는 플러그인이다. 현재 3,000개 이상의 Provider가 공개 레지스트리에 등록되어 있다.

```hcl
provider "aws" {
  region = "ap-northeast-2"
}
```

**Resource**

Resource는 실제로 생성/관리할 인프라 구성 요소다. 블록 타입과 이름으로 정의하며, 모듈 내에서 참조할 때 이 이름을 사용한다.

```hcl
resource "aws_instance" "web_server" {
  ami           = "ami-0c55b159cbfafe1f0"
  instance_type = "t3.micro"

  tags = {
    Name = "web-server"
  }
}
```

**Data Source**

이미 존재하는 리소스의 정보를 읽어오기 위해 사용한다. Terraform이 직접 만든 리소스가 아니어도 참조할 수 있다.

```hcl
data "aws_vpc" "default" {
  default = true
}
```

**Module**

여러 리소스를 묶어 재사용 가능한 단위로 만든 것이다. 공식 레지스트리의 모듈을 사용하거나 직접 작성할 수 있다.

```hcl
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "5.0.0"

  name = "my-vpc"
  cidr = "10.0.0.0/16"
}
```

**State**

Terraform이 실제 인프라와 코드 간의 매핑 정보를 저장하는 파일(`.tfstate`)이다. 이 파일이 인프라의 "현재 상태"를 나타내는 단일 진실 공급원(Single Source of Truth) 역할을 한다.

**Backend**

State 파일을 어디에 저장할지 지정한다. 로컬(기본값)로 두면 팀 협업이 어려워지므로, 운영 환경에서는 반드시 Remote Backend를 사용해야 한다.

---

### 2. Terraform 동작 원리

**선언형(Declarative) 방식**

Terraform은 "어떻게 만들지"가 아닌 "최종 상태가 어떠해야 하는지"를 선언한다. 리소스 간 의존성은 Terraform이 자동으로 분석하여 Dependency Graph를 생성하고 올바른 순서로 처리한다.

예를 들어, EC2 인스턴스가 특정 Security Group을 참조하면 Terraform은 Security Group을 먼저 만들고 EC2를 생성한다.

**Plan → Apply → Destroy 라이프사이클**

```
terraform init     # Provider 플러그인 다운로드, 백엔드 초기화
     ↓
terraform plan     # 현재 State와 코드를 비교해 변경 사항 미리보기 (dry-run)
     ↓
terraform apply    # 계획된 변경 사항을 실제 인프라에 적용
     ↓
terraform destroy  # State에 있는 모든 리소스 제거
```

`terraform plan`은 실제로 아무것도 변경하지 않는다. 무엇이 생성/수정/삭제될지를 보여주는 안전망 역할을 한다.

**State 파일의 역할**

- apply 후 생성된 리소스 정보를 `.tfstate`에 기록한다.
- 다음 plan 실행 시 현재 코드와 State를 비교하여 차이점(diff)을 계산한다.
- State 없이는 Terraform이 어떤 리소스를 자신이 관리하는지 알 수 없다.

---

### 3. Terraform 주요 명령어

| 명령어 | 설명 |
|--------|------|
| `terraform init` | 작업 디렉토리 초기화, Provider 플러그인 다운로드 |
| `terraform plan` | 변경 사항 미리보기 (실제 인프라에 영향 없음) |
| `terraform apply` | 인프라에 변경 사항 적용 |
| `terraform destroy` | 관리 중인 모든 리소스 삭제 |
| `terraform import` | 기존 리소스를 State로 가져오기 |
| `terraform fmt` | HCL 코드 자동 포맷팅 |
| `terraform validate` | 구성 파일 문법 유효성 검사 |
| `terraform output` | Output 값 출력 |
| `terraform state list` | State에 등록된 리소스 목록 확인 |
| `terraform refresh` | 실제 인프라 상태를 State에 동기화 |

---

### 4. Terraform 활용 사례

**클라우드 인프라 코드 기반 관리**

VPC, 서브넷, EC2, RDS, S3, IAM 등 AWS 리소스 전체를 코드로 관리한다. GUI 콘솔에서 수동으로 설정하던 작업이 코드 한 줄로 재현된다.

**멀티 클라우드 / 멀티 리전 배포 자동화**

동일한 Terraform 구성에서 Provider만 바꾸면 AWS, GCP, Azure 각각에 동일한 구조의 인프라를 배포할 수 있다.

**개발/스테이징/프로덕션 환경 일관성 유지**

Workspace 또는 디렉토리 분리를 통해 환경별 인프라를 동일한 코드로 관리한다. 환경마다 수동으로 설정해서 발생하던 "스테이징에서는 됐는데 프로덕션에서 안 됨" 문제를 줄일 수 있다.

**CI/CD 파이프라인과의 통합**

GitHub Actions, GitLab CI 등과 연동하여 PR이 열리면 자동으로 `terraform plan` 결과를 코멘트로 달고, main 브랜치에 머지되면 `terraform apply`가 자동 실행되도록 구성할 수 있다.

```yaml
# GitHub Actions 예시
- name: Terraform Plan
  run: terraform plan -detailed-exitcode
  # exit code 0: 변경 없음, 1: 오류, 2: 변경 있음
```

**GitOps 기반 인프라 변경 관리**

main 브랜치를 단일 진실 공급원으로 두고, 모든 인프라 변경은 Pull Request를 통해 코드 리뷰를 거쳐 반영한다. Atlantis, Spacelift 같은 도구가 이 흐름을 자동화해준다.

---

### 5. Terraform 모범 사례 (Best Practices)

**모듈화로 재사용성 확보**

반복되는 인프라 패턴(VPC 구성, ECS 클러스터 등)을 Module로 추출한다. 환경별로 Module을 호출하는 방식으로 DRY(Don't Repeat Yourself) 원칙을 지킨다.

```
modules/
  vpc/
    main.tf
    variables.tf
    outputs.tf
envs/
  prod/
    main.tf  ← module "vpc" { source = "../../modules/vpc" }
  staging/
    main.tf
```

**Remote Backend로 State 안전하게 관리**

AWS를 사용하는 경우 S3 + State Locking 조합이 표준이다. Terraform 1.10.0부터는 DynamoDB 기반 잠금 대신 S3 Native Locking이 권장된다.

```hcl
terraform {
  backend "s3" {
    bucket         = "my-terraform-state"
    key            = "prod/network/terraform.tfstate"
    region         = "ap-northeast-2"
    use_lockfile   = true  # S3 Native Locking (Terraform 1.10+)
    encrypt        = true
  }
}
```

State 파일에는 민감 정보가 포함될 수 있으므로 암호화와 버저닝을 반드시 활성화한다. `{environment}/{component}/terraform.tfstate` 형식으로 키를 구성하면 관리가 용이하다.

**Workspace로 환경 분리**

```bash
terraform workspace new staging
terraform workspace select prod
```

단, 복잡한 환경 분리에는 Workspace보다 디렉토리 구조 분리가 더 명확한 경우가 많다.

**변수와 출력 관리**

- `variables.tf`: 입력 변수 정의
- `terraform.tfvars`: 환경별 실제 값 (git에 커밋하지 않도록 주의)
- `outputs.tf`: 다른 모듈이나 파이프라인에서 참조할 값 노출

**민감 정보 처리**

- `sensitive = true` 속성으로 출력에서 마스킹
- AWS Secrets Manager, HashiCorp Vault 등과 연동하여 시크릿을 코드에 하드코딩하지 않는다
- `terraform.tfvars`를 `.gitignore`에 추가하거나 CI/CD 환경변수로 주입한다

---

### 6. Terraform의 한계와 주의점

**State 파일 문제**

State 파일이 유실되면 Terraform은 이미 존재하는 리소스를 "처음 보는 것"으로 인식해 중복 생성을 시도할 수 있다. 또한 여러 사람이 동시에 `apply`를 실행하면 State가 충돌한다. Remote Backend의 Locking 기능이 이를 방지하지만, 완전한 해결책은 아니다.

**Drift (상태 불일치)**

콘솔에서 수동으로 리소스를 변경하면 실제 인프라와 State 파일 간 불일치(Drift)가 발생한다. `terraform plan`을 주기적으로 실행하거나 CI/CD에서 `-detailed-exitcode` 플래그로 Drift를 감지할 수 있다.

```bash
terraform plan -detailed-exitcode
# exit code 2가 반환되면 drift 발생
```

**규모 확장 시 속도 저하**

리소스가 수백~수천 개로 늘어나면 plan/apply 시간이 길어진다. 상태를 여러 파일로 분리하는 `terraform_remote_state` 패턴이나 `terragrunt` 같은 도구로 완화할 수 있다.

**다른 IaC 도구와의 비교**

| 도구 | 언어 | 특징 | 적합한 상황 |
|------|------|------|------------|
| **Terraform** | HCL | 멀티 클라우드, 3,000+ Provider, BSL 라이선스 | 멀티 클라우드 또는 AWS 기반 표준 IaC |
| **OpenTofu** | HCL | Terraform fork, MPL 2.0 오픈소스 | Terraform 호환 + 오픈소스 필요 시 |
| **Pulumi** | Python/Go/TS 등 | 범용 언어 사용, 4,800+ Provider | 개발자 친화적, 복잡한 로직 필요 시 |
| **CloudFormation** | JSON/YAML | AWS 네이티브, 무료 | AWS 단일 클라우드 환경 |

2025년 IBM이 HashiCorp를 인수한 후에도 라이선스는 오픈소스로 복귀하지 않았으며, 이에 대한 대안으로 OpenTofu의 채택률이 빠르게 성장하고 있다(2025년 기준 약 12%).

---

## 핵심 정리

- Terraform은 HashiCorp가 개발한 IaC 도구로, 선언형 언어(HCL)로 인프라를 코드처럼 관리한다
- State 파일을 통해 실제 인프라와 코드 간의 일관성을 유지하며, 이 파일의 안전한 관리가 운영의 핵심이다
- `terraform plan`으로 변경 사항을 미리 확인하고 `terraform apply`로 적용하는 흐름이 기본이다
- 모듈화, Remote Backend(S3 + Native Locking), Workspace 등 구조적 전략이 대규모 운영 핵심이다
- Drift 감지를 CI/CD에 통합하고 콘솔 수동 변경을 금지하는 것이 상태 불일치 예방의 핵심이다
- 멀티 클라우드 환경에서 반복 가능하고 재현 가능한 인프라 구성을 가능케 한다

## 키워드

- **Terraform**: HashiCorp가 개발한 IaC 도구. HCL로 인프라 리소스를 선언적으로 정의하고 관리한다.
- **IaC (Infrastructure as Code)**: 인프라를 코드로 정의하고 버전 관리하는 방법론. 수동 설정 대비 일관성과 재현성을 보장한다.
- **HCL (HashiCorp Configuration Language)**: Terraform에서 사용하는 선언형 설정 언어. JSON보다 가독성이 높고, 변수/반복문/조건문 등을 지원한다.
- **Provider**: 특정 클라우드 플랫폼이나 서비스와 Terraform이 통신하게 해주는 플러그인. AWS, GCP, Azure 등 3,000개 이상 존재한다.
- **State**: Terraform이 관리하는 리소스의 현재 상태를 기록한 `.tfstate` 파일. 실제 인프라와 코드 사이의 매핑 정보를 담는다.
- **Module**: 여러 리소스를 묶어 재사용 가능하게 만든 단위. VPC, ECS 클러스터 같은 공통 패턴을 추상화하는 데 활용한다.
- **Remote Backend**: State 파일을 팀이 공유할 수 있도록 원격 저장소(S3 등)에 저장하는 방식. 동시 수정을 방지하는 Locking 기능을 포함한다.
- **Workspace**: 동일한 Terraform 코드로 여러 환경(dev/staging/prod)을 분리해서 관리하는 기능.
- **Plan & Apply**: `terraform plan`으로 변경 사항을 미리 확인(dry-run)하고, `terraform apply`로 실제 적용하는 두 단계 워크플로우.
- **Drift**: 실제 인프라 상태와 Terraform State 파일 간의 불일치. 콘솔에서 수동 변경이 발생하면 나타나며, 주기적인 plan 실행으로 감지할 수 있다.

## 참고 자료

- [Terraform 공식 문서 - HashiCorp Developer](https://developer.hashicorp.com/terraform)
- [What is Infrastructure as Code with Terraform?](https://developer.hashicorp.com/terraform/tutorials/aws-get-started/infrastructure-as-code)
- [Backend Type: s3 | Terraform | HashiCorp Developer](https://developer.hashicorp.com/terraform/language/backend/s3)
- [Managing Terraform State on AWS: S3 Backend and DynamoDB Locking Guide](https://terrateam.io/blog/terraform-state-aws-s3-backend)
- [Terraform Drift Detection and Remediation](https://spacelift.io/blog/terraform-drift-detection)
- [Terraform vs Pulumi vs OpenTofu 2026 비교](https://eitt.academy/knowledge-base/terraform-vs-pulumi-vs-opentofu-iac-comparison-2026/)
