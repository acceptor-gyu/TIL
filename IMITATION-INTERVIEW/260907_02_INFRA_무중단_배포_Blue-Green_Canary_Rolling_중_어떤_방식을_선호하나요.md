# [INFRA] 무중단 배포 — Blue-Green, Canary, Rolling 중 어떤 방식을 선호하고 이유는 무엇인가요?

> 면접 예상 질문 대비용 정리 (무중단 배포 전략 관련 꼬리질문 포함)

## 개요
- 질문 의도: "내 서비스의 제약 조건(트래픽 규모, 인프라 예산, 롤백 요구 시간, 관측 성숙도, DB 변경 여부)을 기준으로 선택할 수 있는가" "상황에 따라 다릅니다"로 끝내는 것이 아니라, 기준을 세우고 하나를 고른 뒤 그 선택의 비용까지 
- 전제 정리: 세 전략은 모두 "신버전과 구버전이 어느 시점에, 얼마나 오래 공존하는가"를 다르게 설정한 것이고, 한 문장으로 세 전략의 장단점이 거의 다 파생된다.

| 전략 | 공존 시점 | 공존 비율 | 공존 시간 |
| --- | --- | --- | --- |
| Blue-Green | 전환 순간에만 | 사실상 0% (전환 전엔 Green이 트래픽 0) | 초 단위 |
| Canary | 롤아웃 전 구간 | 1% → 100%로 의도적 조절 | 분~시간 단위(길게 유지) |
| Rolling | 롤아웃 전 구간 | 통제 불가(교체 진행률에 종속) | 수 분 |

- 답변의 축: **롤백 속도**, **폭발 반경(blast radius)**, **인프라 비용**, **두 버전 공존 부담**. 이 네 축의 트레이드오프가 전부다.
- 관련 정리: [Blue-Green, Canary, Rolling 배포](../INFRA/260907_01_Blue-Green_Canary_Rolling_배포.md) — 각 전략의 동작 방식은 여기서 다루고, 이 문서는 면접 답변 논리와 꼬리질문에 집중한다.

## 상세 내용

### 1. 메인 질문: 어떤 방식을 선호하는가

#### 1-1. 두괄식! (결론을 먼저)
**"Rolling을 기본값으로 두고, 사용자 영향이 큰 변경에는 Canary를 얹는다. Blue-Green은 인프라/런타임 자체가 바뀌는 배포에만 쓴다."**

이유는 세 가지다.

1. **대부분의 배포는 리스크가 낮다** — 문구 수정, 버그 픽스, 로그 추가 같은 배포에까지 인프라 2배 비용(Blue-Green)이나 30분짜리 단계별 관찰(Canary)을 쓰는 것은 과설계다. Rolling은 추가 리소스가 `maxSurge`만큼만 필요하고, Kubernetes `Deployment`의 기본 동작이라 별도 도구 없이 굴러간다. **가장 자주 하는 일이 가장 싸야 한다.**
2. **리스크는 배포가 아니라 변경 내용에 있다** — 결제 로직 변경, 쿼리 튜닝, 라이브러리 메이저 업그레이드처럼 "스테이징에서 재현 안 되는 문제"가 예상되는 변경에서는 폭발 반경을 줄이는 것이 유일하게 효과적인 방어다. 이때만 Canary로 1%에 먼저 노출한다.
3. **롤백 속도가 진짜 필요한 순간은 따로 있다** — JDK 버전, 컨테이너 베이스 이미지, 인스턴스 타입, 웹서버 교체처럼 "잘못되면 파드가 아예 안 뜨거나 전면 장애"인 배포는 Rolling으로 하면 롤아웃이 중간에 멈춘 채 반쯤 죽은 상태가 된다. 이때는 Blue-Green으로 **완성된 환경을 검증한 뒤 스위치**하는 것이 좋다.

#### 1-2. 선택 기준을 판단 트리로
```
DB 스키마 변경이 하위 호환인가?
├─ No → 배포 전략 논의 이전에 Expand-Contract로 쪼갠다
└─ Yes
   └─ 런타임/인프라 레벨 변경(JDK, base image, 인스턴스 타입)인가?
      ├─ Yes → Blue-Green (환경 전체를 검증 후 스위치, 롤백=라우팅 되돌리기)
      └─ No
         └─ 사용자 영향이 큰/되돌리기 어려운 비즈니스 로직 변경인가?
            ├─ Yes → Canary (1% → 10% → 50% → 100%, 지표 게이트)
            └─ No  → Rolling (기본값)
```

#### 1-3. "선호"를 물었을 때 하지 말아야 할 답변
- **"Blue-Green이 가장 안전해서 선호합니다"** — 안전성은 전략이 아니라 **검증 품질**에서 온다. Green을 띄워놓고 헬스체크만 통과시킨 뒤 스위치하면 100% 트래픽이 한 번에 새 버전으로 가므로, 폭발 반경은 오히려 Rolling보다 크다. Blue-Green의 강점은 "안전"이 아니라 **롤백이 라우팅 되돌리기 한 번**이라는 점이다.
- **"Canary가 가장 좋습니다"** — Canary는 지표를 신뢰성 있게 비교할 관측 체계가 없으면 그냥 **느린 Rolling**이다. 1%에 노출해도 그 1%의 에러율을 볼 수 없다면 아무 방어 효과가 없다. "관측 체계가 있다면"이라는 전제를 반드시 붙여야 한다.
- **비용 이야기를 빼놓는 답변** — Blue-Green은 전환 시점에 운영 규모의 2배 자원이 필요하고, Canary는 배포 리드타임을 늘려 하루 배포 횟수를 떨어뜨린다.

#### 1-4. 조합해서 쓰는 것이 실무
현실에서는 셋 중 하나를 고르는 것이 아니라 섞는다.
- **Rolling + Canary**: Kubernetes에서 Argo Rollouts의 `canary` 전략은 내부적으로 파드를 점진 교체하면서 트래픽 가중치를 조절한다. Rolling의 자원 효율 + Canary의 지표 게이트를 함께 얻는다.
- **Blue-Green + Canary**: Green 환경을 띄운 뒤 트래픽을 한 번에 100% 넘기지 않고 10% → 50% → 100%로 넘긴다(AWS CodeDeploy의 `Canary10Percent5Minutes` 같은 배포 설정이 정확히 이 형태다). 롤백 즉시성과 폭발 반경 제어를 동시에 취한다.
- **+ Feature Flag**: 배포(deploy)와 릴리즈(release)를 분리하면 코드는 Rolling으로 조용히 내보내고, 기능 노출은 플래그로 1% → 100% 제어한다. **롤백이 재배포가 아니라 플래그 off**가 되므로 되돌리는 시간이 초 단위로 떨어진다.

### 2. 꼬리질문 ①: Blue-Green 배포에서 rollback은 어떻게 구현하나요?

#### 2-1. 원칙: 롤백은 "재배포"가 아니라 "라우팅 되돌리기"
Blue-Green 롤백의 핵심은 **구버전(Blue) 환경을 지우지 않고 살려두는 것**이다. 롤백이 필요하면 라우팅 대상을 Green → Blue로 되돌린다. 재빌드/재배포가 개입하는 순간 롤백은 수 분~수십 분이 되고, 그건 Blue-Green을 쓰는 이유를 스스로 버리는 것이다.

```
[정상 배포]
 LB → Blue(v1) 100%                     # 평시
 LB → Blue(v1) 100%, Green(v2) 대기      # Green 배포 + 검증
 LB → Green(v2) 100%                    # 스위치
 (Blue를 bake time 동안 유지)             # ★ 롤백 창구
 Blue 종료                               # bake time 경과 후

[롤백]
 LB → Blue(v1) 100%                     # 라우팅만 되돌림 (수 초)
```

#### 2-2. 레이어별 구현 방법
| 전환 레이어 | 롤백 방법 | 롤백 소요 | 주의점 |
| --- | --- | --- | --- |
| ALB 리스너 룰 / Target Group | 리스너의 forward 대상 TG를 Blue TG로 변경 | 수 초 | 기존 커넥션은 즉시 끊기지 않음(아래 2-4) |
| Kubernetes Service selector | Service의 `selector.version`을 `v2` → `v1`로 patch | 수 초 | kube-proxy/Endpoint 전파 지연 존재 |
| Ingress / Istio VirtualService | `weight`를 Blue 100 / Green 0으로 되돌림 | 수 초 | 가장 세밀하게 제어 가능 |
| DNS (Route 53) | 레코드를 Blue로 되돌림 | **TTL에 종속** | 클라이언트/JVM DNS 캐시 때문에 즉시성이 없음 → 롤백 수단으로 부적합 |

DNS 전환은 "구현이 쉬워서" 자주 언급되지만, **롤백 관점에서는 최악**이다. TTL 60초로 설정해도 이를 무시하는 리졸버와 자체 DNS 캐시를 가진 클라이언트(구버전 JVM의 무한 캐시 등)가 있어 되돌리는 데 수 분~수십 분이 걸릴 수 있다. 롤백 속도를 위해 Blue-Green을 쓴다면 전환은 LB/Ingress 레이어에서 해야 한다.

#### 2-3. Kubernetes에서의 최소 구현
```yaml
# Service: selector의 version 라벨 하나로 트래픽 방향을 결정
apiVersion: v1
kind: Service
metadata:
  name: api
spec:
  selector:
    app: api
    version: v1        # ← 이 값만 v2로 바꾸면 전환, v1로 되돌리면 롤백
  ports:
    - port: 80
      targetPort: 8080
```

```bash
# 전환
kubectl patch svc api -p '{"spec":{"selector":{"app":"api","version":"v2"}}}'
# 롤백
kubectl patch svc api -p '{"spec":{"selector":{"app":"api","version":"v1"}}}'
```

Deployment는 `api-v1`, `api-v2` 두 개를 각각 유지하고, 롤백 창구가 닫히는 시점(bake time 종료)에 `api-v1`을 삭제한다.

#### 2-4. 라우팅만 되돌려서는 안 되는 것들 (실무에서 물어보는 지점)
"라우팅 되돌리면 끝"이라고 답하면 여기서 반문이 들어온다. **롤백 시 잔여 상태(residual state)** 처리가 핵심이다.

**(1) 이미 수립된 커넥션은 되돌아가지 않는다**
LB의 라우팅을 바꿔도 HTTP keep-alive로 열려 있는 커넥션과 진행 중인 요청은 여전히 Green으로 간다. ALB는 대상 그룹에서 빠진 대상에 대해 `deregistration_delay`(기본 300초) 동안 진행 중인 요청을 마무리시키는 연결 드레이닝을 수행한다. 즉 롤백 직후에도 짧게는 수 초, 설정에 따라 수 분간 Green이 트래픽을 받는다. **완전한 차단이 필요하면 Green 파드를 실제로 종료**해야 한다.

**(2) 이미 적용된 DB 마이그레이션은 롤백되지 않는다**
Green 배포 전에 실행한 DDL은 라우팅을 되돌려도 그대로다. 그래서 Blue-Green이 성립하려면 **스키마 변경이 항상 구버전과 호환**이어야 한다(7절). "롤백 스크립트로 되돌린다"는 답은 위험한데, 그 사이 Green이 신규 스키마 기준으로 쓴 데이터가 유실되거나 깨지기 때문이다. 원칙은 **"스키마는 롤포워드(roll forward)만, 애플리케이션은 롤백 가능"**.

**(3) 캐시 오염**
Green(v2)이 직렬화 포맷이 바뀐 객체를 공용 Redis에 써두면, 롤백된 Blue(v1)가 그것을 역직렬화하다 터진다. 대응은 두 가지 중 하나다 — 캐시 키에 스키마 버전을 접미어로 붙여 네임스페이스를 분리하거나(`user:v2:{id}`), 캐시 값 포맷을 하위 호환이 보장되는 형태(JSON + 필드 추가만 허용)로 고정한다.

**(4) 비동기 소비자와 스케줄러**
Green이 새 포맷 메시지를 이미 큐에 넣었다면, 롤백된 Blue의 컨슈머가 그 메시지를 해석하지 못한다. 컨슈머/스케줄러/배치는 웹 레이어와 함께 롤백되도록 배포 단위를 묶고, 메시지 스키마는 **추가만 허용(additive-only)** 규칙을 둔다.

**(5) 세션**
Green에서만 발급하는 새 형식 세션/토큰이 있다면 롤백 후 그 사용자들은 로그아웃된다. 세션 스토어를 외부화(Redis)하고 포맷을 호환 유지해야 한다.

#### 2-5. 자동 롤백
수동 판단은 늦다. 배포 파이프라인에 지표 기반 자동 롤백을 넣는다.
- **AWS CodeDeploy**: CloudWatch 알람을 배포 그룹에 연결해두면, 배포 중(및 지정한 bake time 중) 알람이 울리면 자동으로 트래픽을 원본(Blue)으로 되돌린다.
- **Argo Rollouts / Flagger**: `AnalysisTemplate`에 Prometheus 쿼리(에러율, p99)를 정의하고 실패 시 자동 abort → 이전 ReplicaSet으로 되돌린다.
- **Bake time 설정이 곧 롤백 창구의 길이**다. Blue를 너무 빨리 지우면 자동 롤백이 불가능해지고, 너무 오래 두면 2배 비용이 계속 든다. 보통 "장애가 드러나는 데 걸리는 시간"(예: 배치 주기, 캐시 TTL, 트래픽 피크 주기)을 기준으로 10~60분 정도를 잡는다.

### 3. 꼬리질문 ②: Canary에서 트래픽을 늘릴 기준은 어떤 지표로 판단하나요?

#### 3-1. 먼저 "비교 대상"을 정의해야 한다
가장 흔한 실수는 **카나리아의 절대값을 임계치와 비교**하는 것이다(예: "에러율 1% 미만이면 통과"). 평시 에러율이 이미 0.8%인 서비스라면 이 게이트는 아무것도 걸러내지 못한다. 올바른 방법은 **베이스라인과의 비교**다.

```
❌ canary_error_rate < 1%                      # 절대 임계치: 서비스 평시 수준을 무시
✅ canary_error_rate <= baseline_error_rate * 1.2  # 상대 비교: 회귀를 잡아낸다
```

여기서 베이스라인을 **현재 운영 중인 구버전 전체**로 잡으면 또 다른 편향이 생긴다. 카나리아 파드는 방금 떠서 JIT 워밍업이 안 됐고, 커넥션 풀과 로컬 캐시가 비어 있고, 리소스 상태가 다르다. Spinnaker의 Kayenta 같은 자동 카나리아 분석 도구가 **구버전으로 새 파드를 함께 띄워 베이스라인으로 쓰는(baseline vs canary)** 방식을 권하는 이유가 이것이다. 같은 시각, 같은 수명, 같은 크기의 파드끼리 비교해야 "버전 차이"만 남는다.

#### 3-2. 봐야 하는 지표 계층
**계층 1 — Golden Signals (필수)**

| 지표 | 판정 방법 | 놓치기 쉬운 점 |
| --- | --- | --- |
| Errors | HTTP 5xx 비율, 예외 발생률을 베이스라인 대비 비교 | 4xx도 봐야 한다. 400/422 급증은 API 계약 파괴 신호 |
| Latency | p50이 아니라 **p95/p99** | 평균은 소수 사용자의 심각한 지연을 가린다 |
| Traffic | 카나리아가 실제로 기대한 비율의 요청을 받았는지 | 라우팅이 잘못돼 트래픽 0인데 "에러 0%"로 통과하는 사고 |
| Saturation | CPU/메모리/GC 시간, DB 커넥션 풀 사용률, 스레드 풀 큐 | 1% 트래픽에선 안 터지고 50%에서 터지는 유형 |

**계층 2 — 의존성/자원 지표**
- DB: 쿼리 응답 시간, slow query 수, 커넥션 풀 대기 시간 (N+1이 새로 생겼는지 잡는 자리)
- 외부 API: 호출 수 증가율(불필요한 중복 호출), 타임아웃/서킷브레이커 오픈 횟수
- 큐: 컨슈머 lag 증가 여부

**계층 3 — 비즈니스 지표 (이게 진짜 안전망)**
5xx도 안 나고 p99도 정상인데 "결제 버튼이 눌리지 않는" 배포는 기술 지표로 잡히지 않는다.
- 결제 성공률, 주문 완료율, 로그인 성공률, 검색 결과 클릭률
- 단, 비즈니스 지표는 노이즈가 크고 표본이 모이는 데 시간이 걸린다 → **1% 단계에서는 통계적으로 의미 없다**. 50% 이상 단계의 게이트로 쓰는 것이 현실적이다.

**계층 4 — 클라이언트 관측(RUM)**
서버는 200을 응답했지만 프론트엔드에서 JS 에러가 터지는 경우가 있다. 실사용자 모니터링의 JS 에러율, LCP를 함께 본다.

#### 3-3. 승격 조건에는 "시간"과 "표본 수"가 반드시 들어간다
지표가 좋아 보여도 표본이 적으면 판단할 수 없다. 1% 트래픽에서 5분간 요청이 200건이라면 에러율 0%는 "문제 없음"의 증거가 아니다.

```
승격 조건 = (지표 조건) AND (최소 관찰 시간) AND (최소 요청 수)

예) 1%  단계: 5분  이상 AND 요청 1,000건 이상 AND 에러율 <= baseline*1.2 AND p99 <= baseline*1.3
    10% 단계: 10분 이상 AND 요청 10,000건 이상 AND 위 조건 유지
    50% 단계: 30분 이상 AND 결제 성공률 >= baseline - 0.5%p
    100%: 승격
```

관찰 시간을 정할 때 고려할 것:
- **워밍업 구간 제외**: 파드가 뜬 직후 30초~2분은 JIT/캐시 워밍업 때문에 지연이 높다. 이 구간을 판정에서 빼지 않으면 정상 배포가 계속 롤백된다.
- **지연 발현형 문제**: 메모리 릭, 커넥션 릭, 캐시 만료 후 폭주, 스케줄러 오작동은 5분 안에 안 나타난다. 이런 리스크가 있는 변경은 관찰 시간을 시간 단위로 잡거나, 100% 승격 후에도 bake time을 둔다.
- **트래픽 주기**: 새벽에 1% 카나리아를 30분 돌린 것은 피크 시간대 검증이 아니다.

#### 3-4. 롤백(abort) 기준은 승격 기준보다 민감해야 한다
승격은 "충분히 좋다"를 요구하지만, 롤백은 "조금이라도 나쁘면" 즉시 발동해야 한다. 보통 두 단계로 둔다.
- **즉시 abort**: 5xx 급증, 파드 CrashLoopBackOff, 헬스체크 연속 실패 → 관찰 시간 무시하고 즉시 롤백
- **단계 실패**: 관찰 시간 종료 시점에 지표 조건 미달 → 확대 중단 후 롤백

#### 3-5. Argo Rollouts 예시
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
spec:
  strategy:
    canary:
      steps:
        - setWeight: 1
        - pause: { duration: 5m }
        - analysis:
            templates: [{ templateName: success-rate }]
        - setWeight: 10
        - pause: { duration: 10m }
        - analysis:
            templates: [{ templateName: success-rate }]
        - setWeight: 50
        - pause: { duration: 30m }
---
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: success-rate
spec:
  metrics:
    - name: success-rate
      interval: 1m
      successCondition: result[0] >= 0.99
      failureLimit: 2            # 2회 실패하면 롤백
      provider:
        prometheus:
          address: http://prometheus:9090
          query: |
            sum(rate(http_requests_total{job="api-canary",code!~"5.."}[2m]))
            /
            sum(rate(http_requests_total{job="api-canary"}[2m]))
```

`failureLimit`처럼 "연속 N회 실패"를 요구하는 설정이 중요하다. 단발성 스파이크로 롤백이 발동하면 팀이 자동 롤백을 신뢰하지 않게 되고, 결국 껐다.

### 4. 꼬리질문 ③: Rolling 배포 중 구버전과 신버전이 공존하면 어떤 문제가 생기나요?

핵심 전제: Rolling에서 공존은 **선택이 아니라 필연**이고, 공존 비율/시간을 제어할 수도 없다. 따라서 신버전은 항상 **N/N+1 호환**(구버전과 신버전이 동시에 떠 있어도 정상 동작)이어야 한다. 아래는 그 호환이 깨질 때 나타나는 문제들이다.

#### 4-1. API 계약 파괴 (요청/응답 스키마)
같은 엔드포인트가 두 버전에 걸쳐 있으므로, 클라이언트는 요청마다 다른 버전에 도달한다.
- **필드 제거/이름 변경**: v2가 `userName` → `name`으로 바꿨다면, 프론트엔드는 요청이 v1에 걸리면 `userName`을, v2에 걸리면 `name`을 받는다. 새로고침마다 화면이 깨진다.
- **필수 파라미터 추가**: v2가 요구하는 새 필드를 구버전 프론트가 안 보내면 v2 파드에서만 400이 난다. 에러율이 "배포 진행률에 비례해" 증가하는 특징적 패턴이 나온다.
- **대응**: 제거·이름 변경은 한 배포에서 하지 않는다. 추가 → 양쪽 지원 → (구버전 클라이언트 소멸 확인) → 제거로 최소 2회 배포에 나눈다.

#### 4-2. 프론트엔드 정적 자산 404 (실무에서 가장 자주 터지는 유형)
SPA에서 흔하다. 사용자가 이미 받은 구버전 `index.html`은 `main.a1b2c3.js` 같은 해시된 청크를 참조한다. 배포로 자산이 교체되고 구버전 청크가 서버에서 사라지면, 그 사용자가 라우팅 이동 시 lazy-load 청크를 요청하는 순간 **404 → 화면 백지**가 된다. Rolling으로 서버 파드를 교체하는 동안 자산이 파드 로컬에 있으면 더 심해진다(요청이 어느 파드로 가느냐에 따라 성공/실패).
- **대응**: 정적 자산은 파드가 아니라 S3/CDN에 올리고, **구버전 자산을 최소 1~2 릴리즈 기간 남긴다**(불변 파일명이므로 공존 가능). 청크 로드 실패를 감지해 새로고침을 유도하는 에러 바운더리도 함께 둔다.

#### 4-3. DB 스키마 불일치
- v2가 추가한 `NOT NULL` 컬럼에 v1은 값을 안 넣는다 → v1의 INSERT 전부 실패
- v2가 컬럼을 지웠는데 v1이 `SELECT *`로 매핑 → v1에서 예외
- v2가 컬럼 이름을 바꿨는데 v1은 옛 이름으로 조회 → v1 전면 장애
- **대응**: 7절의 Expand-Contract. 결론적으로 **스키마는 항상 "구버전도 동작하는" 상태를 유지**해야 한다.

#### 4-4. 캐시/세션 직렬화 비호환
v1과 v2가 같은 Redis를 공유하는데 v2가 캐시 객체에 필드를 추가/제거했다면, 서로가 쓴 값을 읽다 역직렬화 예외가 난다. Java 기본 직렬화(`serialVersionUID`)를 쓰면 클래스가 조금만 바뀌어도 즉시 깨진다.
- **대응**: 캐시 값은 JSON 등 유연한 포맷 + "알 수 없는 필드 무시" 설정, 또는 캐시 키에 버전 접미어(`user:v2:{id}`)를 붙여 네임스페이스를 분리한다. 후자는 배포 직후 캐시 미스가 몰려 DB로 트래픽이 튀는 부작용(thundering herd)을 고려해야 한다.

#### 4-5. 스케줄러/배치 중복 또는 누락
v1과 v2가 같은 cron 잡을 들고 있으면 **같은 시각에 두 버전이 동시에 실행**될 수 있다. 정산·집계 잡이라면 데이터가 두 번 반영된다. 반대로 v2에서 잡의 스케줄이나 이름을 바꿨다면 공존 구간에 **아무도 실행하지 않는** 창이 생긴다.
- **대응**: 스케줄러를 웹 인스턴스에서 분리(별도 Deployment, replicas 1)하거나, DB/Redis 분산 락(ShedLock 등)으로 단일 실행을 보장한다. 잡 자체는 멱등하게 만든다.

#### 4-6. 메시지 큐 컨슈머 문제
- **스키마 불일치**: v2 프로듀서가 새 필드를 담아 발행한 메시지를 v1 컨슈머가 파싱 실패 → 재시도 폭주 또는 DLQ 적재
- **Kafka 리밸런싱**: 컨슈머 파드가 순차 교체되면 그 횟수만큼 컨슈머 그룹 리밸런스가 발생하고, 그동안 파티션 소비가 멈춘다. 파드 수가 많으면 배포 시간 내내 lag이 쌓인다(정적 멤버십/협력적 리밸런싱 설정으로 완화)
- **대응**: 메시지 스키마는 추가만 허용, 컨슈머는 모르는 필드 무시. Avro/Protobuf + Schema Registry로 호환성 규칙(BACKWARD)을 강제하는 것이 정석이다.

#### 4-7. 종료 처리(Graceful Shutdown) 미흡으로 인한 요청 유실
공존 자체와 별개로, **구버전 파드가 죽는 순간**에도 요청이 깨질 수 있다. Kubernetes에서 파드 삭제 시 Endpoint 제거와 컨테이너 SIGTERM 전달은 **병렬로** 일어나므로, LB/kube-proxy가 아직 그 파드를 목록에 갖고 있는 동안 애플리케이션이 먼저 종료되면 그 요청은 커넥션 리셋을 맞는다.
```yaml
spec:
  terminationGracePeriodSeconds: 45
  containers:
    - name: api
      lifecycle:
        preStop:
          exec:
            command: ["sh", "-c", "sleep 10"]   # 엔드포인트 전파를 기다린다
```
Spring Boot라면 `server.shutdown=graceful`과 `spring.lifecycle.timeout-per-shutdown-phase`를 함께 설정해 진행 중 요청을 마무리하게 한다. `terminationGracePeriodSeconds`는 preStop + 앱의 드레이닝 시간보다 커야 한다(기본값 30초).

#### 4-8. 롤아웃이 중간에 멈춘 상태
Rolling의 고유 리스크. 신버전 파드가 Readiness를 통과하지 못하면 롤아웃이 진행되지 않고 **v1 일부 + v2 일부(비정상)** 상태로 정지한다. `maxUnavailable`만큼은 이미 v1이 내려가 있으므로 가용 용량도 줄어든 상태다. 여기서 롤백은 라우팅 스위치가 아니라 **역방향 Rolling**이므로 되돌리는 데도 롤아웃 시간이 그대로 걸린다. Rolling의 진짜 약점은 "공존"보다 이 **롤백 지연**이다.

### 5. 꼬리질문 ④: DB schema 변경이 포함된 배포는 어떤 순서로 진행해야 하나요?

#### 5-1. 대원칙 두 개
1. **애플리케이션 배포와 스키마 변경을 같은 원자적 단위로 묶지 않는다.** 마이그레이션은 별도 단계로 먼저(또는 나중에) 독립 실행한다. 앱 시작 시 자동 마이그레이션(Flyway `migrate-on-startup`)은 Rolling에서 특히 위험하다 — 어느 파드가 먼저 뜨느냐에 따라 순서가 뒤엉키고, 롤백 시 스키마만 앞서간 상태가 된다.
2. **스키마는 항상 "직전 버전 앱도 동작 가능한" 상태를 유지한다(N/N+1 호환).** 이 원칙이 지켜지면 앱 롤백이 언제든 안전해지고, 지켜지지 않으면 어떤 배포 전략을 써도 무중단이 성립하지 않는다.

#### 5-2. Expand-Contract (= Parallel Change)
컬럼 이름을 `user_name` → `full_name`으로 바꾸는 예로 전체 순서를 보면 이렇다. **최소 3회의 배포**가 필요하다.

```
[0] 시작 상태: 앱 v1 ── 읽기/쓰기: user_name

[1] EXPAND — 스키마만 변경 (앱 배포 없음)
    ALTER TABLE users ADD COLUMN full_name VARCHAR(100) NULL;
    → v1은 이 컬럼을 모르지만 아무 영향 없음 (NULL 허용이므로 v1의 INSERT도 성공)
    ※ 이 시점에 롤백해도 안전

[2] 배포 A — 이중 쓰기(dual write), 읽기는 아직 구컬럼
    write: user_name = X, full_name = X
    read : user_name
    → v1과 배포A가 공존해도 양쪽 다 정상 (v1은 user_name만 보면 되므로)

[3] BACKFILL — 기존 데이터 이관 (앱 배포 없음)
    UPDATE users SET full_name = user_name
     WHERE full_name IS NULL AND id BETWEEN ? AND ?;   -- 청크 단위 반복
    → 락 보유 시간과 replication lag을 피하기 위해 반드시 나눠서 실행

[4] 배포 B — 읽기를 신컬럼으로 전환 (쓰기는 여전히 이중)
    write: user_name = X, full_name = X
    read : full_name
    → 이 배포는 롤백해도 안전(배포A로 돌아가면 구컬럼을 읽는데 값이 동일하므로)
    → ★ 여기서 데이터 정합성 검증: full_name IS NULL 또는 불일치 건수 = 0 확인

[5] 배포 C — 구컬럼 쓰기 중단
    write: full_name = X
    read : full_name

[6] CONTRACT — 구컬럼 제거 (모든 인스턴스가 배포 C 이상임을 확인한 뒤)
    ALTER TABLE users DROP COLUMN user_name;
    → 되돌릴 수 없는 유일한 단계. 충분한 관찰 기간을 둔 뒤 실행
```

**확장 → 이중 쓰기 → 백필 → 읽기 전환 → 구경로 제**의 골격과 **각 단계가 독립적으로 롤백 가능해야 한다**는 이유를 설명한다.

#### 5-3. 변경 유형별 안전 순서 요약
| 변경 | 위험 | 안전한 순서 |
| --- | --- | --- |
| 컬럼 추가(NULL 허용) | 낮음 | 스키마 먼저 → 앱 배포 |
| 컬럼 추가(NOT NULL) | 높음 | ① NULL 허용으로 추가 → ② 앱이 값을 채우도록 배포 → ③ 백필 → ④ NOT NULL 부여 |
| 컬럼 삭제 | 높음 | ① 앱에서 사용 중단 배포 → ② 관찰 → ③ 스키마에서 삭제 (**앱 먼저**) |
| 컬럼 이름 변경 | 높음 | 이름 변경 금지. 추가 + 이중 쓰기 + 삭제로 분해 (5-2) |
| 타입 변경/축소 | 높음 | 새 컬럼 추가 후 이관 (in-place ALTER는 락과 데이터 절삭 위험) |
| 인덱스 추가 | 중간 | PostgreSQL `CREATE INDEX CONCURRENTLY`, MySQL은 온라인 DDL 또는 gh-ost |
| 테이블 삭제/이름 변경 | 높음 | 앱에서 참조 제거 배포 → 충분한 관찰 → 삭제 |
| NOT NULL 제거, 기본값 추가 | 낮음 | 스키마 먼저 (구버전 앱에 영향 없음) |

핵심 규칙 하나로 압축하면 — **추가(add)는 스키마 먼저, 제거(remove)는 앱 먼저.**

#### 5-4. 마이그레이션 자체를 무중단으로 실행하기
스키마 변경 순서를 맞춰도, DDL 실행 그 자체가 락으로 장애를 만들 수 있다.
- **PostgreSQL**: `ALTER TABLE`은 `ACCESS EXCLUSIVE` 락을 잡는다. 문제는 락 대기가 큐를 이루면서 **그 뒤의 모든 읽기까지 막힌다**는 점이다. `SET lock_timeout = '3s'`를 걸고 실패하면 재시도하는 패턴이 안전하다. 인덱스는 `CREATE INDEX CONCURRENTLY`로 쓰기를 막지 않고 생성한다. 컬럼 추가는 PG 11부터 상수 DEFAULT라도 전체 rewrite 없이 즉시 끝난다.
- **MySQL**: 5.6+의 온라인 DDL(`ALGORITHM=INPLACE, LOCK=NONE`)로 다수 변경을 무중단 처리할 수 있으나 지원 여부가 변경 종류마다 다르다. 지원되지 않으면 `gh-ost` 또는 `pt-online-schema-change`로 섀도 테이블 + 트리거/binlog 기반 복제 후 원자적 rename을 쓴다.
- **대용량 백필**: 단일 UPDATE 금지. PK 범위 청크(예: 1,000~10,000행) 단위로 나누고 사이에 sleep을 넣어 replication lag과 undo/WAL 증가를 억제한다.

#### 5-5. 배포 전략별 스키마 변경 궁합
- **Rolling**: 공존이 필수라 Expand-Contract가 **강제**된다. 대신 원칙만 지키면 가장 안전하다.
- **Blue-Green**: "환경이 분리되니 스키마도 분리된다"고 오해하기 쉽지만 **DB는 보통 공유**한다. 게다가 롤백이 라우팅 되돌리기이므로 **구버전이 신 스키마에서 동작해야 한다** — Rolling과 동일한 요구사항이다. DB까지 복제하면 전환 시점 이후 Blue에 쓰인 데이터를 어떻게 동기화할지가 새 문제가 되므로 실무에서는 거의 하지 않는다.
- **Canary**: 공존 시간이 가장 길다(분~시간). Expand-Contract 요구가 가장 강하고, 이중 쓰기 구간의 정합성 검증도 가장 중요하다.

결론: **어떤 전략을 고르든 스키마 변경의 하위 호환성은 면제되지 않는다.** 배포 전략은 애플리케이션의 리스크를 줄이지만 데이터의 리스크는 줄여주지 않는다.

### 6. 꼬리질문 ⑤: 배포 성공 여부는 health check 외에 어떤 지표로 판단해야 하나요?

#### 6-1. 헬스체크가 증명하는 것과 못 하는 것
헬스체크(특히 `/health`가 200을 반환하는 수준)가 증명하는 것은 **프로세스가 떠서 요청을 받을 수 있다**뿐이다. 이것만 보고 성공을 선언하면 놓치는 유형이 이렇게 많다.

| 헬스체크가 통과하는데 실패한 배포 | 왜 안 잡히나 |
| --- | --- |
| 특정 API만 500 | `/health`는 그 코드 경로를 타지 않는다 |
| 응답은 200인데 내용이 빈 배열/잘못된 값 | 상태 코드만 보므로 |
| p99가 3배 느려짐 (N+1 쿼리 유입) | 헬스체크는 DB를 안 타거나 가벼운 쿼리만 탄다 |
| 결제 로직 오류로 주문 성공률 급락 | 기술 지표에 안 나타난다 |
| 메모리 릭 → 30분 후 OOM | 배포 직후엔 정상 |
| 프론트엔드 JS 에러로 화면 백지 | 서버는 정상 응답 |
| 컨슈머가 메시지를 못 읽어 lag 폭증 | HTTP 헬스체크와 무관 |

또한 **liveness와 readiness를 혼동하면 헬스체크가 오히려 장애를 만든다.** liveness에 DB 연결 검사를 넣으면 DB가 잠깐 흔들릴 때 모든 파드가 재시작 루프에 빠지며 전면 장애가 된다. 원칙은 — **liveness는 "재시작하면 나아지는가"만 검사(자기 자신), readiness는 "지금 트래픽을 받아도 되는가"를 검사(의존성 포함)**.

#### 6-2. 배포 판정에 써야 하는 지표
**(1) 서비스 레벨 지표 (Golden Signals / RED)**
- 에러율: 5xx뿐 아니라 4xx 변화율(계약 파괴 감지), 애플리케이션 예외 로그 발생률
- 레이턴시: p50/p95/p99를 **엔드포인트별로**. 전체 평균은 트래픽 많은 엔드포인트에 가려진다
- 처리량: 요청 수가 배포 전 대비 유지되는지 (급감은 라우팅/헬스체크 문제 신호)
- 포화도: CPU, 메모리, GC pause 시간·빈도, 스레드 풀 큐 길이, 파일 디스크립터

**(2) SLO 기반 판정 (가장 권장되는 형태)**
개별 임계치를 나열하는 대신 SLO와 **에러 예산 소모 속도**(burn rate)로 판정한다. "가용성 SLO 99.9%인데 배포 후 10분간 예산을 시간당 14배 속도로 태우고 있다" 같은 판단은 임의의 임계치보다 근거가 명확하고, 알람 피로도 낮다.

**(3) 의존성 지표**
- DB: 커넥션 풀 사용률/대기 시간, slow query 수, 트랜잭션 롤백률, deadlock 수
- 외부 API: 호출량 증가율(중복 호출 유입 감지), 타임아웃률, 서킷브레이커 상태
- 캐시: 히트율 급락(키 포맷 변경/캐시 무효화 사고 감지)
- 큐: 컨슈머 lag, DLQ 유입 건수

**(4) 비즈니스/전환 지표**
결제 성공률, 주문·가입 완료율, 로그인 성공률, 검색 결과 클릭률. **"기술 지표는 다 정상인데 매출이 떨어지는" 배포를 잡는 유일한 층**이다. 노이즈가 크므로 요일/시간대 대비(WoW) 비교로 본다.

**(5) 클라이언트 관측(RUM)**
JS 에러율, 정적 자산 404율(4-2의 청크 404를 잡는 지표), Core Web Vitals, 앱 크래시율.

**(6) 릴리즈 프로세스 지표 (DORA)**
개별 배포가 아니라 배포 체계의 건강도를 보는 지표. 면접에서 한 줄 언급하면 시야가 넓어 보인다.
- 변경 실패율(change failure rate), 배포 빈도(deployment frequency), 리드 타임(lead time for changes), 서비스 복원 시간(MTTR)

#### 6-3. 판정에는 "관찰 기간"이 포함되어야 한다
배포 완료 직후의 스냅샷은 판정 근거가 되지 못한다.
- **워밍업 배제**: 파드 기동 후 1~2분은 JIT 컴파일 전, 커넥션 풀/캐시 미충전 상태다. 이 구간의 높은 지연으로 롤백을 발동시키면 안 된다. Kubernetes `startupProbe`로 기동 구간을 분리하고, LB 레벨에서는 slow start를 쓴다.
- **지연 발현형 문제 대비**: 메모리/커넥션 릭, 캐시 TTL 만료 후 폭주, 스케줄 잡 오작동, 배치 시간대 부하는 배포 후 수십 분~수 시간 뒤에 드러난다. 그래서 배포 후 bake time을 두고, 그 기간에는 롤백 경로(구버전 이미지/환경)를 유지한다.

#### 6-4. 정리된 판정 체계
```
1) 기동 게이트 : startupProbe / readinessProbe 통과 + 스모크 테스트(핵심 시나리오 E2E)
2) 즉시 게이트 : 5xx율, 예외 로그 급증, 크래시 여부  → 위반 시 관찰 시간 무시하고 즉시 롤백
3) 단계 게이트 : p99, 포화도, DB/외부 의존성 지표를 베이스라인 대비 비교 (관찰 5~30분)
4) 사업 게이트 : 전환율/결제 성공률을 WoW 대비 (관찰 30분~수 시간, 충분한 표본 필요)
5) Bake time  : 롤백 경로를 유지한 채 관찰 지속 → 여기까지 통과하면 배포 성공 선언, 구버전 정리
```

### 7. 면접 답변 시나리오 (요약 흐름)
1. **선택 기준을 먼저 제시** — "롤백 속도, 폭발 반경, 인프라 비용, 두 버전 공존 부담 네 가지로 판단합니다."
2. **결론을 말한다** — "기본은 Rolling, 리스크 큰 변경은 Canary, 런타임/인프라가 바뀌는 배포는 Blue-Green입니다."
3. **선택의 비용을 인정한다** — "Rolling은 롤백이 역방향 롤아웃이라 느립니다. 그래서 되돌리기가 급한 배포에는 안 씁니다."
4. **공통 전제를 강조한다** — "어떤 전략이든 두 버전 공존과 스키마 하위 호환은 면제되지 않습니다. Expand-Contract가 배포 전략보다 먼저입니다."
5. **판정 체계로 마무리한다** — "성공 판정은 헬스체크가 아니라 베이스라인 대비 골든 시그널 + 비즈니스 지표 + bake time으로 봅니다."

## 핵심 정리
- 핵심 포인트 1: 세 전략의 차이는 **"두 버전이 언제·얼마나·얼마나 오래 공존하는가"** 하나로 환원된다. 이 축에서 롤백 속도·폭발 반경·비용이 모두 파생된다.
- 핵심 포인트 2: Blue-Green의 롤백은 재배포가 아니라 **라우팅 되돌리기**이며, 그것이 성립하려면 구버전 환경을 bake time 동안 살려두어야 한다. 다만 커넥션 드레이닝·적용된 DDL·공용 캐시·발행된 메시지는 라우팅을 되돌려도 되돌아오지 않는다.
- 핵심 포인트 3: Canary의 승격 기준은 절대 임계치가 아니라 **베이스라인 대비 상대 비교 + 최소 관찰 시간 + 최소 표본 수**여야 한다. 관측 체계 없는 Canary는 느린 Rolling일 뿐이다.
- 핵심 포인트 4: Rolling의 공존 문제는 API 계약, 정적 자산 404, 스키마, 캐시 직렬화, 스케줄러 중복, 메시지 스키마, graceful shutdown의 7가지 축으로 정리된다. 신버전은 항상 **N/N+1 호환**이어야 한다.
- 핵심 포인트 5: 스키마 변경 규칙은 한 문장 — **추가는 스키마 먼저, 제거는 앱 먼저.** 그리고 스키마는 롤포워드만, 애플리케이션은 롤백 가능하게 유지한다.
- 핵심 포인트 6: 헬스체크는 "프로세스가 살아 있다"만 증명한다. 배포 성공은 골든 시그널 + 의존성 지표 + 비즈니스 지표 + bake time으로 판정한다.

## 기술적 한계와 보완 전략
- **Blue-Green의 2배 비용** → 전환 시점에만 스케일아웃하도록 오토스케일링과 결합하거나, Blue를 축소 규모로만 유지해 "롤백 시 스케일업"을 감수한다(롤백 속도와 비용의 재교환).
- **Canary의 긴 리드 타임** → 리스크 등급을 나눠 저위험 배포는 Canary를 건너뛴다. 지표 게이트와 승격을 자동화(Argo Rollouts/Flagger)해 사람이 대기하는 시간을 없앤다.
- **Canary의 표본 부족** → 트래픽이 적은 서비스에서는 1% 단계가 통계적으로 무의미하다. 시작 비중을 10~20%로 올리거나, 특정 사용자 세그먼트(내부 직원, 특정 지역)로 라우팅해 표본을 의도적으로 확보한다.
- **Rolling의 느린 롤백** → 이전 ReplicaSet을 남겨 `kubectl rollout undo`로 즉시 되돌릴 수 있게 하고, 애초에 되돌리기 급한 변경은 Feature Flag로 감싼다(롤백 = 플래그 off).
- **DB 롤백 불가** → 스키마는 롤포워드 전용으로 취급하고, 파괴적 변경(DROP)은 앱 배포와 최소 1개 릴리즈 이상 간격을 둔다. 백필 결과는 반드시 검증 쿼리로 확인한 뒤 다음 단계로 넘어간다.
- **관측 체계 부재** → 전략 도입 전에 지표 파이프라인(메트릭·로그·트레이싱)과 SLO 정의가 선행되어야 한다. 관측이 없으면 Canary와 자동 롤백은 형태만 남고 기능하지 않는다.
- **상태를 가진 워크로드** → 컨슈머, 스케줄러, WebSocket 서버는 웹 레이어와 배포 특성이 다르다. 배포 단위를 분리하고 각각에 맞는 전략(컨슈머는 리밸런싱 최소화, 스케줄러는 단일 실행 보장)을 적용한다.

## 키워드
- **Blast Radius (폭발 반경)**: 하나의 결함이 영향을 미치는 사용자/요청의 범위. Canary가 트래픽 비율을 낮게 시작하는 목적은 이 범위를 의도적으로 좁히는 것이다.
- **Bake Time**: 배포 후 구버전 환경(또는 롤백 경로)을 유지하면서 지표를 관찰하는 기간. 이 기간의 길이가 곧 자동 롤백이 가능한 창구의 길이다.
- **N/N+1 호환성**: 인접한 두 버전이 동시에 떠 있어도 정상 동작하는 성질. Rolling과 Canary에서 공존은 필연이므로 이 호환성이 무중단 배포의 실질적 전제 조건이다.
- **Expand-Contract (Parallel Change)**: 스키마/인터페이스 변경을 "확장 → 이중 사용 → 축소" 3단계로 나눠, 각 단계가 하위 호환을 유지하도록 하는 리팩터링 패턴. 컬럼 이름 변경처럼 파괴적인 작업을 여러 배포로 분해한다.
- **Dual Write (이중 쓰기)**: 마이그레이션 과도기에 구경로와 신경로 양쪽에 모두 쓰는 것. 어느 버전의 앱이 읽어도 같은 값을 보게 만들어 롤백 가능성을 유지한다.
- **Backfill**: 새로 추가한 컬럼/테이블에 기존 데이터를 채워 넣는 작업. 단일 대량 UPDATE는 락과 replication lag을 유발하므로 청크 단위로 나눠 실행한다.
- **Golden Signals**: Google SRE가 제시한 모니터링 4대 지표 — Latency, Traffic, Errors, Saturation. 배포 판정의 최소 계층이다.
- **Error Budget Burn Rate**: SLO가 허용하는 실패량(에러 예산)을 얼마나 빠른 속도로 소모하고 있는지. 임의의 절대 임계치보다 근거가 명확한 알람/롤백 기준이 된다.
- **Automated Canary Analysis (ACA)**: 카나리아와 베이스라인의 지표를 통계적으로 비교해 승격/롤백을 자동 판정하는 기법. Spinnaker의 Kayenta가 대표적이며, 워밍업 편향을 없애기 위해 구버전으로 별도 베이스라인 파드를 띄워 비교한다.
- **Connection Draining / Deregistration Delay**: 대상을 로드밸런서에서 제거할 때 진행 중인 요청이 끝날 때까지 기다리는 동작. ALB의 기본 대기 시간은 300초이며, 이 때문에 라우팅을 되돌린 직후에도 잔여 트래픽이 신버전에 남는다.
- **Graceful Shutdown / preStop**: 종료 신호를 받은 뒤 새 요청을 거부하고 진행 중 요청만 마무리하는 종료 절차. Kubernetes에서는 Endpoint 제거 전파 지연 때문에 `preStop`으로 짧게 대기하는 패턴이 필요하다.
- **Readiness vs Liveness Probe**: readiness는 "지금 트래픽을 받아도 되는가"(실패 시 트래픽 차단), liveness는 "재시작해야 하는가"(실패 시 컨테이너 재시작). liveness에 외부 의존성 검사를 넣으면 의존성 장애가 전면 재시작 폭풍으로 증폭된다.
- **Feature Flag (Toggle)**: 코드 배포와 기능 노출을 분리하는 스위치. 배포 전략의 롤백 단위를 "재배포"에서 "설정 변경"으로 낮춰 복구 시간을 크게 줄인다.
- **gh-ost / pt-online-schema-change**: MySQL에서 락 없는 스키마 변경을 수행하는 도구. 섀도 테이블에 데이터를 복제한 뒤 원자적 rename으로 교체한다.
- **CREATE INDEX CONCURRENTLY**: PostgreSQL에서 쓰기를 차단하지 않고 인덱스를 생성하는 옵션. 대신 전체 테이블을 두 번 스캔하므로 생성 시간이 길고, 실패 시 무효(invalid) 인덱스가 남아 정리가 필요하다.

## 참고 자료
- [Martin Fowler - BlueGreenDeployment](https://martinfowler.com/bliki/BlueGreenDeployment.html)
- [Martin Fowler - CanaryRelease](https://martinfowler.com/bliki/CanaryRelease.html)
- [Martin Fowler - ParallelChange (Expand-Contract)](https://martinfowler.com/bliki/ParallelChange.html)
- [Martin Fowler - FeatureToggle](https://martinfowler.com/articles/feature-toggles.html)
- [Google SRE Book - Monitoring Distributed Systems (Golden Signals)](https://sre.google/sre-book/monitoring-distributed-systems/)
- [Google SRE Workbook - Alerting on SLOs (Burn Rate)](https://sre.google/workbook/alerting-on-slos/)
- [Kubernetes Docs - Deployments (RollingUpdate, maxSurge/maxUnavailable)](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)
- [Kubernetes Docs - Pod Lifecycle (Termination, preStop)](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)
- [Kubernetes Docs - Configure Liveness, Readiness and Startup Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Argo Rollouts - Canary Strategy](https://argo-rollouts.readthedocs.io/en/stable/features/canary/)
- [Argo Rollouts - Analysis & Progressive Delivery](https://argo-rollouts.readthedocs.io/en/stable/features/analysis/)
- [Flagger - Deployment Strategies](https://docs.flagger.app/usage/deployment-strategies)
- [Spinnaker - Automated Canary Analysis (Kayenta)](https://spinnaker.io/docs/guides/user/canary/)
- [AWS Docs - CodeDeploy Deployment Configurations](https://docs.aws.amazon.com/codedeploy/latest/userguide/deployment-configurations.html)
- [AWS Docs - ALB Target Group Deregistration Delay](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-target-groups.html)
- [PostgreSQL Docs - ALTER TABLE](https://www.postgresql.org/docs/current/sql-altertable.html)
- [PostgreSQL Docs - CREATE INDEX (CONCURRENTLY)](https://www.postgresql.org/docs/current/sql-createindex.html)
- [MySQL 8.0 Reference Manual - Online DDL Operations](https://dev.mysql.com/doc/refman/8.0/en/innodb-online-ddl-operations.html)
- [gh-ost - GitHub's online schema migration for MySQL](https://github.com/github/gh-ost)
- [DORA - Four Keys Metrics](https://dora.dev/guides/dora-metrics-four-keys/)
