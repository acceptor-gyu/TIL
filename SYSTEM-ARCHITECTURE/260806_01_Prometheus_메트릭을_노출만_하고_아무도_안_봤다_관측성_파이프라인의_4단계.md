# Prometheus 메트릭을 노출만 하고 아무도 안 봤다 — 관측성 파이프라인의 4단계

## 개요
`/actuator/prometheus` 엔드포인트를 열고 메트릭을 노출하는 것만으로 "관측성(Observability)"을 갖췄다고 착각하기 쉽다. 하지만 노출된 메트릭은 누군가 수집(scrape)하고, 저장하고, 시각화하고, 임계치를 넘으면 알림(alert)으로 이어져야 비로소 가치를 갖는다. 이 글에서는 메트릭이 실제 대응 행동으로 이어지기까지의 관측성 파이프라인을 4단계(Instrument → Collect → Visualize → Alert)로 나누어 정리하고, 각 단계에서 흔히 빠지는 함정을 짚는다.

## 상세 내용

### 1. 문제 상황: "메트릭은 있는데 장애는 놓쳤다"
- **메트릭 노출(expose) ≠ 관측(observe)**: `management.endpoints.web.exposure.include=prometheus`를 설정해 `/actuator/prometheus`가 열려 있다는 것은 "누군가 긁어갈 수 있는 상태"일 뿐, 실제로 아무도 수집하지 않으면 메트릭은 요청이 들어온 그 순간에만 메모리에 계산되고 사라진다. 즉 엔드포인트가 살아있는 것과 데이터가 축적되어 시계열로 남는 것은 완전히 다른 이야기다.
- **엔드포인트만 열고 Prometheus에 등록하지 않은 사례**: 애플리케이션 쪽 계측(Instrumentation)은 끝났지만, `prometheus.yml`의 `scrape_configs`에 해당 타깃을 추가하지 않거나 Kubernetes 환경에서 `ServiceMonitor`/어노테이션(`prometheus.io/scrape: "true"`) 설정을 빠뜨리면 Prometheus 서버는 그 존재조차 모른다. 신규 서비스 배포 시 가장 흔히 누락되는 지점이다.
- **대시보드는 있지만 아무도 열어보지 않는 상태**: Grafana에 대시보드를 만들어놓아도, 이것이 온콜(on-call) 대응 절차나 배포 후 체크리스트에 포함되지 않으면 "존재하지만 아무도 참조하지 않는 문서"와 다를 바 없다.
- **알림이 없어서 사후에야 장애를 인지하는 흐름**: 결국 위 세 가지가 겹치면 장애는 고객 문의, 매출 그래프, 또는 다음날 아침 로그 확인으로만 발견된다. 이는 "관측 가능(observable)"했지만 "관측되지 않은(unobserved)" 전형적인 사례다.

### 2. 관측성 파이프라인 4단계

#### 2-1. Instrument (계측) — 메트릭을 만든다
- **Micrometer와 Prometheus registry의 관계**: Micrometer는 애플리케이션 코드에서 "SLF4J의 메트릭 버전"과 같은 파사드(facade) 역할을 한다. `MeterRegistry`라는 벤더 중립적인 API로 메트릭을 등록하면, `PrometheusMeterRegistry` 구현체가 이를 Prometheus의 텍스트 노출 포맷(exposition format)으로 변환해 `/actuator/prometheus` 경로에 노출한다. 코드는 특정 모니터링 벤더에 종속되지 않고, registry만 교체하면 Datadog·CloudWatch 등으로도 전환 가능하다.
- **4가지 메트릭 타입**: Micrometer는 Counter, Gauge, Timer, DistributionSummary를 기본 Meter 타입으로 제공한다.
  - `Counter`: 단조 증가만 하는 값(예: 총 요청 수, 에러 수). 시간으로도 요약할 수 없고 감소하지 않는 값에 사용한다.
  - `Gauge`: 현재 순간의 값(예: 커넥션 풀 사용 중 개수, 큐 길이)을 관찰한다. 증가/감소 모두 가능하며 표본 시점의 스냅샷이다.
  - `Timer`: 짧은 지연시간과 이벤트 빈도를 함께 측정한다. 총 소요 시간과 이벤트 카운트를 최소한으로 보고하며, 백엔드 지원 여부에 따라 histogram이나 percentile도 함께 리포트된다.
  - `DistributionSummary(Summary)`: Timer와 구조는 비슷하지만 시간이 아닌 값(예: 요청 페이로드 크기, 배치 처리 건수)의 분포를 기록한다.
- **RED 메트릭(Rate, Errors, Duration) / USE 메트릭(Utilization, Saturation, Errors)**: RED는 요청 기반 서비스(API 등)를 관찰할 때 "초당 요청 수(Rate), 실패율(Errors), 응답 지연(Duration)" 세 축으로 요약하는 방법론이고, USE는 인프라 자원(CPU, 디스크, 메모리 등)을 "사용률(Utilization), 포화도(Saturation), 에러(Errors)"로 관찰하는 방법론이다. 두 방법론을 조합하면 애플리케이션 레벨과 인프라 레벨을 빠짐없이 커버할 수 있다.
- **라벨(label) 설계와 카디널리티 폭발 주의**: Prometheus는 메트릭 이름 + 라벨 조합마다 별도의 시계열을 생성한다. `user_id`, `request_id`, 자유 입력 텍스트 등 값의 종류가 무한히 늘어나는 필드를 라벨로 쓰면 시계열 수가 기하급수적으로 늘어나 메모리와 저장 비용이 폭증하는 "카디널리티 폭발(cardinality explosion)"이 발생한다. 라벨은 값의 종류가 유한하고 예측 가능한 것(HTTP method, status code, endpoint 템플릿 등)으로 제한해야 한다.

#### 2-2. Collect (수집·저장) — 메트릭을 긁어와 저장한다
- **Prometheus의 Pull 모델과 scrape_interval**: Prometheus는 애플리케이션이 메트릭을 밀어넣는(push) 방식이 아니라, 서버가 주기적으로 타깃의 `/metrics` 엔드포인트를 호출해 긁어가는(pull/scrape) 방식을 기본으로 한다. Prometheus가 수집 시점과 헬스 상태를 스스로 통제하기 때문에, 타깃이 응답하지 않으면 즉시 `up=0`으로 감지할 수 있는 것이 장점이다. `scrape_interval`은 기본값이 15초(전역 기본값 기준, 예시 설정 파일은 15s를 많이 사용)이며 잡(job) 단위로 오버라이드할 수 있다.
- **Service Discovery vs 정적 타깃 설정**: 소규모 환경에서는 `static_configs`로 IP:Port를 직접 나열할 수 있지만, Kubernetes·Consul·EC2처럼 인스턴스가 동적으로 뜨고 사라지는 환경에서는 `kubernetes_sd_configs` 같은 서비스 디스커버리를 사용해 타깃을 자동으로 찾아낸다. `relabel_configs`로 어떤 타깃을 수집 대상에 포함할지 필터링하는 것이 일반적이다.
- **시계열 데이터 저장(TSDB)과 retention 정책**: Prometheus는 자체 TSDB(시계열 데이터베이스)에 로컬 디스크 기반으로 데이터를 저장하며, 기본 retention은 15일이다. 장기 보관이나 다중 인스턴스 통합 조회가 필요하면 Thanos, Cortex, Mimir 같은 원격 저장소/장기 보관 솔루션과 연계한다.
- **Push가 필요한 배치·단기 작업과 Pushgateway**: 배치 잡처럼 scrape 주기보다 짧게 실행되고 종료되는 작업은 Pull 방식으로는 수집 타이밍을 맞추기 어렵다. 이런 경우 작업이 끝나기 전에 Pushgateway로 메트릭을 밀어넣고, Prometheus가 Pushgateway를 대상으로 scrape 하도록 구성한다. 다만 Pushgateway는 예외적인 상황(short-lived job)에 한정해서 사용하도록 공식 문서에서도 권장하며, 일반 서비스에 남용하면 오히려 스테일(stale)한 데이터가 계속 노출되는 문제가 생긴다.

#### 2-3. Visualize (시각화) — 사람이 이해할 수 있게 만든다
- **Grafana 대시보드와 PromQL 기본**: Prometheus를 데이터 소스로 등록한 Grafana에서 PromQL 쿼리 결과를 패널로 시각화한다. 단순히 최신 값을 보여주는 Gauge 패널부터, 시계열 추이를 보여주는 Time series 패널까지 목적에 맞게 구성한다.
- **rate(), histogram_quantile()로 p95/p99 지연 계산**: Counter 타입은 누적값이므로 그대로 그리면 계속 우상향하는 그래프만 나온다. `rate(http_requests_total[5m])`처럼 range vector에 `rate()`를 적용해 초당 증가율(RPS)로 변환해야 의미 있는 그래프가 된다. 지연시간 분포는 Histogram으로 수집한 뒤 `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))` 형태로 p95/p99 같은 백분위수를 계산한다.
- **대시보드가 "장애 대응 도구"가 되기 위한 조건**: 단순히 예쁜 그래프를 나열하는 것이 아니라, RED/USE 메트릭을 한 화면에서 볼 수 있어야 하고, 온콜 담당자가 알림을 받았을 때 "무엇을, 왜 봐야 하는지"를 바로 알 수 있도록 알림과 대시보드가 링크되어 있어야 한다(Alertmanager의 `generatorURL`을 대시보드로 연결하는 방식 등).

#### 2-4. Alert (알림) — 행동으로 이어지게 한다
- **Alertmanager와 알림 라우팅·그룹핑·억제(inhibition)**: Alertmanager는 Prometheus 서버가 보낸 알림을 중복 제거(deduplicate), 그룹핑(grouping), 라우팅(routing)한 뒤 이메일·Slack·PagerDuty 같은 리시버로 전달하는 별도 컴포넌트다. 그룹핑은 같은 라벨 값을 가진 알림들을 하나의 알림으로 묶어 대규모 장애 시 알림 폭탄을 방지하고, 라우팅 트리는 라벨 매처(matcher)를 기준으로 알림을 팀·심각도별로 분기시킨다. 억제(inhibition)는 상위 심각도 알림이 이미 발생했다면 그로부터 파생되는 하위 알림들의 통지를 억제하는 기능으로, 예를 들어 "클러스터 전체 다운" 알림이 뜨면 그 하위의 "개별 파드 다운" 알림들은 노이즈로 취급해 억제한다.
- **임계치 기반 vs SLO 기반(에러 버짓, Burn Rate) 알림**: 단순 임계치 알림(예: "에러율 5% 초과 시 알림")은 트래픽이 적은 서비스에서는 과민 반응, 트래픽이 많은 서비스에서는 둔감 반응을 일으키기 쉽다. Google SRE Workbook은 SLO의 에러 버짓 소진 속도(Burn Rate)를 기준으로 알림을 설계할 것을 권장한다. 예를 들어 30일 에러 버짓 기준으로 1시간 윈도우에서 14.4배 속도로 소진되면(=2%를 1시간에 소진) 긴급 알림을, 6시간 윈도우에서 6배 속도(5% 소진)면 준긴급 알림을 발생시키는 식이다. 단기 윈도우와 장기 윈도우를 함께 검사하는 멀티윈도우 멀티번레이트(Multiwindow, Multi-Burn-Rate) 방식은 빠른 감지와 낮은 오탐률을 동시에 달성하기 위한 절충안이다.
- **Alert Fatigue(알림 피로)와 실행 가능한(actionable) 알림 설계**: "일단 다 알림으로 걸어두자"는 접근은 결국 아무도 알림을 진지하게 보지 않는 알림 피로를 초래한다. 좋은 알림의 기준은 (1) 사람이 지금 당장 취해야 할 행동이 있는가(actionable), (2) 자동으로 복구되지 않는 문제인가, (3) 사용자에게 실제 영향을 주는가(symptom-based, cause-based 알림과의 구분) 세 가지로 점검하는 것이 일반적이다.

### 3. 파이프라인의 각 단계가 끊어지면 벌어지는 일
- **Instrument 누락**: 애초에 측정하지 않은 값은 존재하지 않는 것과 같다. 장애가 발생해도 "왜"를 설명할 데이터 자체가 없어 블랙박스 디버깅을 해야 한다.
- **Collect 누락**: 애플리케이션은 메트릭을 계속 계산하고 있지만 아무도 긁어가지 않으면, 해당 값은 요청 시점에만 존재했다가 다음 GC/다음 계산 시점에 덮어써진다. 사고 발생 시점의 데이터를 소급해서 볼 방법이 없다.
- **Visualize 누락**: 데이터는 Prometheus TSDB에 안전하게 쌓여 있지만, PromQL을 매번 직접 짜서 조회해야 한다면 실전 장애 대응 속도에서 크게 뒤처진다. "데이터는 있으나 해석 불가"한 상태다.
- **Alert 누락**: 사람이 능동적으로 대시보드를 계속 들여다보지 않는 한 이상 징후를 놓친다. 결국 장애는 SLA 위반, 고객 클레임 등 외부 신호로만 감지된다.

### 4. 메트릭만으로는 부족하다 — 관측성의 세 기둥
- **Metrics, Logs, Traces의 역할 구분**: 이 세 가지는 흔히 "관측성의 세 기둥(Three Pillars of Observability)"이라고 불린다. Metrics는 시간에 따른 숫자 집계(요청 수, 지연시간 등)로 시스템의 전반적인 건강 상태를 낮은 비용으로 지속 관찰하는 데 유리하다. Logs는 특정 이벤트의 상세한 컨텍스트를 남기고, Traces는 하나의 요청이 여러 서비스를 거치는 경로와 각 구간의 소요 시간을 추적한다.
- **메트릭으로 "무엇이" 이상한지, 트레이스로 "어디서", 로그로 "왜"**: 예를 들어 p99 지연시간 메트릭이 급증했다면(무엇이), 분산 트레이싱으로 어느 서비스/어느 구간에서 지연이 발생했는지 좁히고(어디서), 해당 구간의 로그를 통해 구체적인 원인(예외, 쿼리, 외부 API 응답)을 확인한다(왜). 세 신호는 상호 보완적이며, 어느 하나만으로는 근본 원인 분석(RCA)이 완결되지 않는다.
- **OpenTelemetry로의 표준 수렴 흐름**: 과거에는 메트릭·로그·트레이스마다 서로 다른 계측 라이브러리와 프로토콜을 사용했지만, OpenTelemetry(OTel)는 이 세 신호를 하나의 SDK와 프로토콜(OTLP)로 표준화하는 CNCF 프로젝트다. Spring Boot 진영에서도 Micrometer(Metrics)와 Micrometer Tracing(구 Spring Cloud Sleuth)이 OpenTelemetry Exporter와 연동되는 방향으로 수렴하고 있다.

## 핵심 정리
- 메트릭 노출은 관측성의 시작일 뿐, 수집·시각화·알림까지 이어져야 의미가 있다
- 관측성 파이프라인은 Instrument → Collect → Visualize → Alert의 4단계로 구성된다
- 파이프라인의 어느 한 단계라도 끊기면 장애를 사후에야 인지하게 된다
- 알림은 "울리는 것"이 아니라 "행동으로 이어지는 것"이 목표다(actionable alert)

## 기술적 한계와 보완 전략
- 카디널리티 폭발: 고유 라벨 남발이 Prometheus 메모리·저장 비용을 폭증시킴 → 라벨 설계 가이드(값의 종류를 유한하게 제한)와 recording rule을 활용해 자주 쓰는 집계 쿼리를 미리 계산해 저장
- Pull 모델의 사각지대: 단기·배치 작업은 scrape 주기 안에 사라짐 → Pushgateway 또는 OpenTelemetry Collector 같은 별도 수집 경로 활용
- 임계치 알림의 한계: 고정 임계치는 트래픽 변동에 취약해 오탐/미탐이 잦음 → SLO 기반 멀티윈도우 Burn Rate 알림으로 보완
- 메트릭 단독의 한계: "왜"를 설명하지 못함 → Logs, Traces와 결합한 3 pillars, OpenTelemetry로의 표준 수렴
- 단일 Prometheus 인스턴스의 확장 한계: 장기 보관·다중 클러스터 통합 조회가 필요하면 Thanos/Mimir/Cortex 같은 원격 저장소 연계 검토

## 키워드
- **Observability(관측성)**: 시스템 외부에서 관찰 가능한 출력(메트릭, 로그, 트레이스)만으로 내부 상태를 추론할 수 있는 능력. 모니터링이 "알려진 문제를 감지"하는 것이라면 관측성은 "미지의 문제까지 질의(query)로 파고들 수 있는" 더 넓은 개념이다.
- **Prometheus**: SoundCloud에서 시작되어 CNCF의 졸업 프로젝트가 된 오픈소스 모니터링·알림 시스템. Pull 기반 수집, 다차원 라벨 데이터 모델, 자체 쿼리 언어(PromQL)와 TSDB를 특징으로 한다.
- **Micrometer**: JVM 애플리케이션을 위한 메트릭 계측 파사드 라이브러리. "메트릭계의 SLF4J"로 불리며, 하나의 API로 계측하면 Prometheus, Datadog, CloudWatch 등 다양한 백엔드로 내보낼 수 있다.
- **PromQL**: Prometheus의 함수형 쿼리 언어. `rate()`, `sum()`, `histogram_quantile()` 등으로 시계열 데이터를 집계·변환·필터링한다.
- **Alertmanager**: Prometheus 서버가 발생시킨 알림을 수신해 중복 제거, 그룹핑, 라우팅, 억제(inhibition), 무음(silence) 처리 후 실제 알림 채널로 전달하는 독립 컴포넌트.
- **Cardinality(카디널리티)**: 메트릭 이름과 라벨 조합으로 생성되는 고유 시계열의 개수. 카디널리티가 너무 높아지면(예: user_id를 라벨로 사용) 메모리·저장 비용이 폭증하는 카디널리티 폭발이 발생한다.
- **RED/USE Method**: RED(Rate, Errors, Duration)는 요청 기반 서비스를, USE(Utilization, Saturation, Errors)는 인프라 자원을 관찰하기 위한 메트릭 설계 방법론.
- **SLO / Error Budget**: SLO(Service Level Objective)는 서비스가 달성해야 할 목표 신뢰도(예: 가용성 99.9%)이며, Error Budget은 SLO가 허용하는 실패 여유분(예: 99.9% SLO면 0.1%의 실패 허용치)이다. Burn Rate는 이 에러 버짓이 소진되는 속도를 의미한다.
- **OpenTelemetry**: 메트릭, 로그, 트레이스를 하나의 표준 API/SDK/프로토콜(OTLP)로 통합하는 CNCF 관측성 프레임워크. 벤더 중립적인 계측 표준으로 자리잡고 있다.
- **Alert Fatigue(알림 피로)**: 실행 가능성이 낮은 알림이 과도하게 발생해 담당자가 알림 자체를 무시하거나 둔감해지는 현상. 실행 가능한(actionable) 알림 설계로 완화한다.

## 참고 자료
- [Prometheus 공식 문서 - Configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)
- [Prometheus 공식 문서 - Alertmanager](https://prometheus.io/docs/alerting/latest/alertmanager/)
- [Prometheus 공식 문서 - Pushgateway](https://prometheus.io/docs/practices/pushing/)
- [Micrometer 공식 문서 - Meters](https://docs.micrometer.io/micrometer/reference/concepts/meters.html)
- [Micrometer 공식 문서 - Timers](https://docs.micrometer.io/micrometer/reference/concepts/timers.html)
- [Micrometer 공식 문서 - Distribution Summaries](https://docs.micrometer.io/micrometer/reference/concepts/distribution-summaries.html)
- [Google SRE Workbook - Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/)
- [OpenTelemetry 공식 문서](https://opentelemetry.io/docs/)
