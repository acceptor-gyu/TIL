# Blue-Green, Canary, Rolling 배포

## 개요
서비스가 커질수록 "배포 = 순간적인 다운타임"이라는 공식은 더 이상 허용되지 않는다. 사용자는 새 버전이 배포되는 순간에도 끊김 없이 요청을 처리받길 원하고, 운영자는 새 버전에 문제가 생겼을 때 즉시 되돌릴 수 있길 원한다. 이 두 요구를 동시에 만족시키기 위한 배포 전략이 **Blue-Green**, **Canary**, **Rolling** 세 가지이며, 이들은 모두 "무중단 배포(Zero-downtime Deployment)"라는 큰 우산 아래에 있는 서로 다른 트레이드오프의 조합이다. 셋 다 정답이 아니라 상황(트래픽 규모, 인프라 예산, 롤백 속도 요구사항, DB 스키마 변경 여부)에 따라 선택하는 도구다.

## 배포 전략이 필요한 배경

### 재배포(Recreate) 방식의 한계
가장 단순한 배포 방식은 기존 버전을 완전히 내리고 새 버전을 올리는 **Recreate** 전략이다. Kubernetes의 `Deployment` 리소스도 `strategy.type: Recreate`를 지원하는데, 이 경우 기존 파드를 모두 종료한 뒤에야 새 파드를 생성한다. 구현이 가장 단순하고 두 버전이 동시에 떠 있지 않아 하위 호환성 문제를 걱정할 필요가 없지만, 파드가 모두 내려간 시점과 새 파드가 Ready 상태가 되는 시점 사이에는 필연적으로 다운타임이 발생한다. 트래픽이 있는 서비스에서는 사실상 사용할 수 없는 전략이다.

### 무중단 배포의 전제 조건 (헬스체크, LB, 세션 외부화)
무중단 배포가 성립하려면 다음 조건이 먼저 충족되어야 한다.

- **헬스체크(Health Check)**: 로드밸런서나 오케스트레이터가 "이 인스턴스는 트래픽을 받을 준비가 되었는가"를 판단할 수 있는 엔드포인트가 필요하다. Kubernetes의 Readiness/Liveness Probe가 대표적이다.
- **로드밸런서(LB)를 통한 트래픽 제어**: 특정 인스턴스/버전으로의 트래픽 비중을 코드 배포와 독립적으로 조절할 수 있어야 한다. ALB Target Group, Nginx upstream, Kubernetes Service, Istio VirtualService 등이 이 역할을 한다.
- **세션 외부화(Stateless 또는 세션 스토어 분리)**: 애플리케이션 서버가 세션을 자체 메모리에 들고 있으면, 특정 인스턴스가 내려가는 순간 그 인스턴스에 붙어 있던 사용자의 세션이 유실된다. Redis 등 외부 세션 스토어를 사용하거나 JWT처럼 상태를 클라이언트에 위임해야 인스턴스 교체가 자유로워진다.

이 세 가지가 갖춰진 상태에서만 Blue-Green, Canary, Rolling이 의미를 가진다.

## Blue-Green 배포

### 동작 방식 (Blue/Green 두 환경 + 트래픽 스위칭)
현재 운영 중인 환경을 **Blue**, 새로 배포할 환경을 **Green**이라고 부른다. Green 환경은 Blue와 동일한 규모로 별도로 구축되며, Blue가 여전히 실사용자 트래픽을 처리하는 동안 Green에 배포하고 검증한다. 검증이 끝나면 트래픽을 Blue에서 Green으로 한 번에(또는 매우 짧은 시간에) 전환한다. 문제가 발생하면 다시 Blue로 트래픽을 되돌리기만 하면 되므로 롤백이 즉각적이다.

### 트래픽 전환 지점 (LB Target Group / DNS / Ingress)
전환은 보통 다음 레이어 중 하나에서 일어난다.
- **ALB/NLB Target Group 스위칭**: 리스너 규칙이 가리키는 Target Group을 Blue → Green으로 변경한다.
- **DNS 전환**: Route 53의 레코드를 Blue 환경 IP/ALB에서 Green 환경 것으로 변경한다. TTL 때문에 즉시성이 떨어질 수 있다.
- **Ingress/Service 셀렉터 변경**: Kubernetes에서는 Service의 `selector` 라벨을 Blue 파드에서 Green 파드로 바꾸는 방식으로 구현할 수 있다.

### 장점 - 즉시 롤백, 검증 후 전환
Green 환경에서 스모크 테스트, 실제 트래픽 미러링 등 충분한 검증을 마친 뒤 전환하기 때문에 안정성이 높고, 문제가 생기면 라우팅만 되돌리면 되므로 롤백 시간이 매우 짧다(초 단위).

### 단점 - 인프라 2배 비용, DB 스키마 동기화 문제
운영 환경과 동일한 규모의 인프라를 두 벌 유지해야 하므로 비용이 두 배로 든다(전환 시점에만 일시적으로 두 배가 필요하도록 오토스케일링으로 최적화하기도 한다). 또한 Blue와 Green이 같은 데이터베이스를 공유하는 경우, 새 버전이 요구하는 스키마 변경이 이전 버전과 호환되지 않으면 전환 전후 어느 한쪽에서 장애가 난다. 이 문제는 Expand-Contract 마이그레이션으로 완화할 수 있다.

### 실무 사례 (AWS CodeDeploy, ALB Target Group 교체)
AWS CodeDeploy는 Blue/Green 배포를 1급 기능으로 지원한다. ECS 기준으로 CodeDeploy가 새 태스크 세트(Green)를 띄우고, ALB 리스너의 트래픽을 원본(Blue)에서 교체본(Green)으로 전환한 뒤, 지정된 대기 시간 동안 문제가 없으면 Blue 태스크를 종료한다. CloudWatch 알람을 연동해두면 배포 중 에러율이 임계치를 넘을 때 자동으로 트래픽을 Blue로 롤백시킬 수 있다.

## Canary 배포

### 동작 방식 (일부 트래픽 비율만 신버전으로)
Canary(카나리아)라는 이름은 광부들이 유독가스를 감지하기 위해 카나리아 새를 탄광에 데려간 것에서 유래했다. 신버전을 전체가 아닌 일부 트래픽에만 노출시켜, 실사용자 환경에서 문제가 있는지 미리 감지한다. Blue-Green과 달리 신버전과 구버전이 동시에, 그리고 오랫동안 트래픽을 나눠 받는다는 점이 다르다.

### 트래픽 분배 방법 (가중치 기반, 헤더/쿠키 기반, 사용자 세그먼트)
- **가중치 기반**: 전체 트래픽의 N%를 신버전으로 보낸다. Istio `VirtualService`의 `weight` 필드, AWS CodeDeploy의 `Canary10Percent5Minutes` 같은 사전 정의 옵션이 대표적이다.
- **헤더/쿠키 기반**: 특정 헤더(`X-Canary: true`)나 쿠키를 가진 요청만 신버전으로 라우팅한다. 내부 테스터나 특정 사용자군에게만 먼저 노출할 때 사용한다.
- **사용자 세그먼트 기반**: 특정 지역, 특정 요금제, 특정 사용자 ID 해시 값 등을 기준으로 분배한다. Feature Flag 서비스(LaunchDarkly 등)와 결합해 배포와 무관하게 노출을 제어하기도 한다.

### 점진적 확대 전략 (1% -> 10% -> 50% -> 100%)
한 번에 100%로 확대하지 않고 단계적으로 늘려가며 각 단계에서 지표를 관찰한다. 예를 들어 1% → 5분 관찰 → 10% → 10분 관찰 → 50% → 관찰 → 100% 식으로 진행하며, 각 단계 사이에 자동/수동 게이트를 둔다. Argo Rollouts 같은 도구는 이 단계별 확대와 지표 기반 자동 승격/롤백을 코드(CRD)로 선언할 수 있게 해준다.

### 성공/실패 판정 지표 (에러율, p99 레이턴시, 비즈니스 메트릭)
카나리아 단계에서 무엇을 봐야 확대를 계속할지 판단할 수 있다.
- **에러율**: HTTP 5xx 비율, 예외 발생률
- **레이턴시**: p50뿐 아니라 p95/p99 같은 꼬리 지연시간 (평균만 보면 일부 사용자의 체감 저하를 놓친다)
- **비즈니스 메트릭**: 결제 성공률, 장바구니 이탈률처럼 기술 지표로는 안 보이는 회귀를 잡아낼 수 있는 지표

### 장점 - 리스크 최소화, 실사용자 검증
문제가 있는 배포라도 영향 범위가 트래픽의 일부로 제한되므로 장애의 폭발 반경(blast radius)이 작다. 스테이징에서는 재현되지 않는 실사용자 트래픽 패턴에서의 문제를 조기에 발견할 수 있다.

### 단점 - 관측 체계 필수, 배포 시간 장기화
단계별 지표를 신뢰성 있게 측정할 관측 인프라(메트릭, 로깅, 트레이싱, 알림)가 갖춰져 있지 않으면 카나리아는 그저 "느린 롤아웃"에 불과하다. 또한 단계마다 관찰 시간을 두기 때문에 Blue-Green이나 Rolling에 비해 전체 배포 완료까지 걸리는 시간이 훨씬 길다.

## Rolling 배포

### 동작 방식 (인스턴스를 순차적으로 교체)
전체 인스턴스(파드) 중 일부를 새 버전으로 교체하고, 새 인스턴스가 정상 동작함을 확인한 뒤 다음 배치를 교체하는 과정을 전체 인스턴스가 교체될 때까지 반복한다. Kubernetes `Deployment`의 기본 전략(`strategy.type: RollingUpdate`)이 바로 이것이다. 별도의 이중 인프라가 필요 없고, 특정 시점에는 신버전과 구버전이 소규모로만 공존한다.

### maxSurge / maxUnavailable 파라미터의 의미
Kubernetes 공식 문서에 따르면 두 값으로 롤링 업데이트의 속도와 여유 자원을 제어한다.
- **maxUnavailable**: 업데이트 중 동시에 사용 불가 상태가 될 수 있는 파드의 최대 개수(절대값 또는 비율). 기본값은 25%이며, `maxSurge`가 0이면 0으로 둘 수 없다.
- **maxSurge**: 원하는 replica 수를 초과해서 임시로 추가 생성할 수 있는 파드의 최대 개수(절대값 또는 비율).

예를 들어 `replicas: 10`, `maxSurge: 25%`, `maxUnavailable: 25%`라면, 최대 12~13개 파드까지 늘어날 수 있고 동시에 최소 7~8개는 항상 가용 상태를 유지한다. `maxSurge: 0`으로 설정하면 추가 자원 없이 기존 파드를 하나씩 내리고 새 파드로 교체하는 방식이 되어 리소스는 절약되지만 그만큼 가용 파드 수가 일시적으로 줄어든다.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sample-app
spec:
  replicas: 10
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 25%
      maxUnavailable: 25%
  template:
    spec:
      containers:
        - name: sample-app
          image: my-registry/sample-app:2.0.0
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 5
```

### 장점 - 추가 리소스 부담 최소
Blue-Green처럼 전체 규모를 두 배로 유지할 필요가 없어 인프라 비용 효율이 좋다. 별도의 트래픽 스위칭 인프라 없이 오케스트레이터(K8s)가 기본으로 지원하기 때문에 구현 난이도도 낮다.

### 단점 - 롤백 지연, 배포 중 두 버전 공존
문제가 발견되어 롤백을 결정해도, 다시 이전 버전으로 파드를 하나씩 교체해야 하므로 Blue-Green처럼 즉시 되돌아가지지 않는다. 또한 배포가 진행되는 동안에는 신버전 파드와 구버전 파드가 함께 트래픽을 받으므로, API/DB 스펙에 하위 호환성이 없으면 요청이 어느 파드로 가느냐에 따라 결과가 달라지는 문제가 생긴다.

## 세 전략 비교

### 비교표 (리소스 비용 / 롤백 속도 / 리스크 / 구현 난이도)

| 항목 | Blue-Green | Canary | Rolling |
|---|---|---|---|
| 추가 인프라 비용 | 높음 (환경 2배) | 낮음~중간 (신버전 소규모 인스턴스) | 낮음 (surge만큼만) |
| 롤백 속도 | 매우 빠름 (라우팅 전환) | 빠름 (트래픽 비중 0으로) | 느림 (재교체 필요) |
| 장애 폭발 반경 | 전환 시 100% (검증 실패 시) | 트래픽 비율만큼 제한적 | 배포 진행률만큼 점진적 |
| 관측 요구 수준 | 중간 (전환 전 검증) | 매우 높음 (단계별 지표 필수) | 낮음~중간 (헬스체크 정도) |
| 구현 난이도 | 중간 (환경 이원화 필요) | 높음 (트래픽 분배 + 지표 자동화) | 낮음 (오케스트레이터 기본 지원) |
| 배포 소요 시간 | 짧음 | 김 (단계별 관찰) | 중간 |

### 어떤 상황에 무엇을 선택할 것인가
- 트래픽이 많지 않고 롤백 속도가 최우선이며 인프라 비용을 감당할 수 있다면 **Blue-Green**.
- 신버전의 실사용자 반응을 점진적으로 확인하고 싶고, 이미 견고한 관측/알림 체계가 있다면 **Canary**.
- 별도 인프라 없이 기본적인 무중단 배포만 필요하고 비용 효율이 중요하다면 **Rolling** (Kubernetes 기본값이 이미 이 전략이라는 점도 실용적인 이유가 된다).
- 실제로는 셋을 배타적으로 쓰기보다, Rolling을 기본 골격으로 하되 중요한 배포에는 Canary 단계를 앞에 두거나, DB 마이그레이션이 큰 배포는 Blue-Green으로 격리하는 식으로 조합하는 경우가 많다.

## 공통 고려사항

### 두 버전 공존 시 하위 호환성 (API 버전, DB 마이그레이션 Expand-Contract)
Rolling과 Canary는 물론, Blue-Green도 전환 순간과 롤백 대비 기간 동안에는 신/구 버전이 같은 DB를 공유한 채 동시에 존재한다. 이때 신버전이 만든 스키마 변경을 구버전이 이해하지 못하면(컬럼 삭제, 타입 변경 등) 장애가 난다. 이를 해결하는 표준 패턴이 **Expand-Contract**다.
1. **Expand**: 기존 컬럼/스키마는 그대로 두고 새 컬럼/구조를 추가만 한다. 구버전과 신버전 모두 이 시점의 스키마와 호환된다.
2. **Migrate**: 신버전을 배포해 새 컬럼을 사용하도록 전환한다.
3. **Contract**: 모든 인스턴스가 신버전으로 전환되고 나서야, 더 이상 쓰이지 않는 옛 컬럼/구조를 제거한다.

API 레벨에서도 마찬가지로, 필드를 삭제하기보다 새 필드를 추가하고 일정 기간 두 필드를 함께 지원한 뒤 제거하는 방식(선택적 필드, API 버전 헤더 등)으로 하위 호환성을 유지한다.

### 헬스체크 설계 (Liveness vs Readiness)
Kubernetes 공식 문서 기준으로 두 프로브의 책임이 다르다.
- **Liveness Probe**: 컨테이너가 살아있지만 응답 불가(교착 상태 등)인 경우를 감지해 kubelet이 컨테이너를 재시작하게 만든다.
- **Readiness Probe**: 컨테이너가 요청을 받을 준비가 되었는지 판단한다. 실패하면 Service의 엔드포인트 목록에서 제외되어 트래픽을 받지 않지만, 컨테이너 자체가 재시작되지는 않는다.

배포 전략 관점에서 중요한 것은 Readiness다. Rolling/Canary/Blue-Green 모두 "새 인스턴스가 Readiness를 통과했을 때만 트래픽을 흘려보낸다"는 원칙이 지켜져야 무중단이 성립한다. 초기 구동이 느린 애플리케이션이라면 Liveness가 섣불리 컨테이너를 죽이지 않도록 **Startup Probe**를 별도로 두는 것도 고려한다.

### 세션/캐시 상태 처리
인스턴스 교체가 반복되는 배포 전략에서는 특정 인스턴스에만 존재하는 상태(로컬 세션, 로컬 캐시)가 문제를 일으킨다. 세션은 Redis 같은 외부 스토어로 빼거나 JWT처럼 무상태로 설계하고, 로컬 캐시는 배포 직후 캐시 미스가 몰릴 수 있음을 감안해 워밍업 전략이나 분산 캐시(Redis)를 함께 고려해야 한다.

### 롤백 시나리오와 자동화
사람이 대시보드를 보고 수동으로 롤백을 결정하는 것보다, CloudWatch 알람, Prometheus Alertmanager 등과 연동해 에러율/레이턴시 임계치를 넘으면 자동으로 롤백되도록 파이프라인을 구성하는 것이 사고 대응 시간을 크게 줄인다. Argo Rollouts, AWS CodeDeploy 모두 이런 자동 롤백 훅을 1급 기능으로 제공한다.

## 핵심 정리
- Blue-Green, Canary, Rolling은 모두 "무중단"을 달성하는 방법이지만 비용, 롤백 속도, 리스크 제어 방식이 서로 다른 트레이드오프이며 목적에 따라 선택하거나 조합해야 한다.
- Kubernetes의 `maxSurge`/`maxUnavailable`처럼 파라미터 하나로도 Rolling 배포의 속도와 리소스 여유, 가용성 사이의 균형이 크게 달라진다.
- 어떤 전략을 쓰든 Readiness 기반 헬스체크, 세션 외부화, API/DB 하위 호환성이라는 전제 조건이 없으면 "무중단"은 이름뿐인 배포가 된다.

## 기술적 한계와 보완 전략

### DB 스키마 변경은 어떤 전략으로도 무중단이 공짜가 아니다
Blue-Green이 인프라 전환은 순식간에 해결해주지만, 신/구 버전이 공유하는 DB 스키마까지 순식간에 전환해주지는 않는다. 결국 스키마 변경은 애플리케이션 배포와 별개의 타임라인으로, Expand-Contract 같은 점진적 마이그레이션 패턴으로 다뤄야 한다. 배포 전략을 아무리 잘 골라도 이 부분을 생략하면 배포 전략 선택 자체가 무의미해진다.

### 관측 없는 Canary는 의미가 없다
Canary의 핵심 가치는 "단계마다 판단해서 확대 여부를 결정한다"는 것인데, 판단할 지표(에러율, p99, 비즈니스 메트릭)가 신뢰할 수 있는 수준으로 수집되지 않으면 그냥 배포 시간만 길어진 Rolling 배포와 다를 바가 없다. 카나리아를 도입하기 전에 관측 인프라(메트릭 수집, 알림, 대시보드)가 먼저 갖춰져야 하는 이유다.

### Feature Flag와의 조합으로 배포와 릴리즈 분리
배포(Deploy)는 코드를 프로덕션 인프라에 올리는 행위이고, 릴리즈(Release)는 그 기능을 사용자에게 노출하는 행위다. Feature Flag를 쓰면 이 둘을 분리할 수 있다. 즉, 신버전 코드는 Rolling이나 Blue-Green으로 전체 인스턴스에 이미 배포되어 있지만, 특정 기능은 Flag로 꺼둔 채 특정 세그먼트에만 점진적으로 켜는 방식으로 릴리즈 리스크를 관리할 수 있다. 이는 인프라 레벨의 Canary(트래픽 라우팅 기반)와는 다른, 애플리케이션 레벨의 점진적 노출 방법이며 두 방식을 함께 쓰면 인프라 문제와 기능 문제를 분리해서 진단할 수 있다는 장점이 있다.

## 키워드
- **무중단 배포 (Zero-downtime Deployment)**: 배포 중에도 서비스 가용성을 유지하며 사용자 요청을 끊김 없이 처리하는 배포 방식 전반을 가리키는 개념. Blue-Green, Canary, Rolling 모두 이를 달성하기 위한 구체적 전략이다.
- **Blue-Green Deployment**: 기존 환경(Blue)과 신규 환경(Green)을 동일 규모로 병행 운영하다가, 검증 후 트래픽을 한 번에 전환하는 배포 전략. 롤백이 빠른 대신 인프라 비용이 이중으로 든다.
- **Canary Release**: 신버전을 일부 트래픽/사용자에게만 우선 노출해 실사용자 환경에서 문제를 조기에 감지한 뒤 점진적으로 확대하는 배포 전략. 카나리아 새가 유독가스를 감지하던 것에서 이름이 유래했다.
- **Rolling Update**: 기존 인스턴스를 일정 단위씩 순차적으로 신버전으로 교체하는 배포 전략. Kubernetes Deployment의 기본 전략이며 추가 인프라 부담이 가장 적다.
- **maxSurge / maxUnavailable**: Kubernetes RollingUpdate 전략의 파라미터. maxSurge는 원하는 replica 수를 초과해 임시로 더 만들 수 있는 파드 수, maxUnavailable은 업데이트 중 동시에 사용 불가할 수 있는 파드 수를 제어한다.
- **트래픽 스위칭 (Traffic Shifting)**: 로드밸런서, DNS, 서비스 메시 등을 이용해 특정 버전/환경으로 향하는 트래픽 비율이나 대상을 제어하는 기법. Blue-Green의 전환, Canary의 점진적 확대 모두 이 기법에 의존한다.
- **헬스체크 (Readiness / Liveness Probe)**: Kubernetes에서 컨테이너의 상태를 판단하는 두 프로브. Liveness는 재시작 필요 여부를, Readiness는 트래픽 수신 준비 여부를 판단하며, 무중단 배포는 Readiness가 정확해야 성립한다.
- **하위 호환성 (Backward Compatibility)**: 신버전이 구버전의 클라이언트/데이터 포맷과도 정상 동작할 수 있는 성질. 배포 중 신/구 버전이 공존하는 구간에서 반드시 필요하다.
- **Expand-Contract 마이그레이션**: DB 스키마 변경을 확장(Expand, 추가만) → 전환(Migrate) → 축소(Contract, 옛 구조 제거) 세 단계로 나눠 신/구 버전이 항상 호환되는 스키마 상태를 유지하며 진행하는 마이그레이션 패턴.
- **Feature Flag**: 코드 배포와 기능 노출(릴리즈)을 분리해, 배포된 코드 중 특정 기능만 런타임에 켜고 끌 수 있게 하는 스위치. 애플리케이션 레벨의 점진적 릴리즈 수단으로 인프라 레벨의 Canary와 조합해 사용된다.

## 참고 자료
- [Kubernetes - Deployments (RollingUpdate, maxSurge, maxUnavailable)](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)
- [Kubernetes - Liveness, Readiness, and Startup Probes](https://kubernetes.io/docs/concepts/workloads/pods/probes/)
- [Kubernetes - Configure Liveness, Readiness and Startup Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [AWS CodeDeploy - Working with deployment configurations](https://docs.aws.amazon.com/codedeploy/latest/userguide/deployment-configurations.html)
- [AWS - CodeDeploy blue/green deployments for Amazon ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/deployment-type-bluegreen.html)
- [AWS Blog - CodeDeploy now supports linear and canary deployments for Amazon ECS](https://aws.amazon.com/blogs/containers/aws-codedeploy-now-supports-linear-and-canary-deployments-for-amazon-ecs)
