# Google SRE - SLO 설계법

## 개요
Google SRE(Site Reliability Engineering)에서 제시하는 SLO(Service Level Objective) 설계 방법론에 대한 학습 기록. 서비스 신뢰성을 정량적으로 측정하고 관리하기 위한 SLI/SLO/SLA의 개념과 실무 적용 방법을 다룬다.

## 상세 내용

### 1. SLI, SLO, SLA의 개념과 차이
- SLI (Service Level Indicator): 서비스 수준을 측정하는 지표
- SLO (Service Level Objective): 내부적으로 추구하는 목표치
- SLA (Service Level Agreement): 고객과의 계약상 약속
- 세 가지 지표의 관계와 우선순위

### 2. 좋은 SLI 선정 기준
- 사용자 경험과 직결되는 지표 선정
- 가용성(Availability), 지연시간(Latency), 처리량(Throughput), 에러율(Error Rate)
- The Four Golden Signals (Latency, Traffic, Errors, Saturation)
- 시스템 종류별 권장 SLI (사용자 대면 / 스토리지 / Big Data 시스템)

### 3. SLO 목표치 설정 전략
- 100% 가용성을 추구하지 않는 이유
- 현재 성능 기준 vs 사용자 기대 기준
- 점진적 SLO 강화 방법
- 너무 엄격하거나 너무 느슨한 SLO의 위험성

### 4. Error Budget (에러 예산)
- Error Budget의 개념: (1 - SLO) × 기간
- 99.9% SLO 기준 월간 허용 다운타임 (약 43분)
- 에러 예산 소진 시 정책 (배포 중단, 신뢰성 작업 우선순위 상향)
- 개발 속도와 안정성의 균형 도구로서의 활용

### 5. SLO 설계 프로세스
- 사용자 여정(Critical User Journey) 식별
- SLI 명세서 작성 (측정 대상, 방법, 집계 기간)
- SLO 목표치 결정 및 합의
- 측정 시스템 구축 (모니터링, 대시보드, 알림)
- 정기적 리뷰 및 조정

### 6. 측정 윈도우(Rolling Window) 설계
- 28일 Rolling Window 권장 이유
- Calendar-based vs Rolling-based 비교
- Burn Rate 기반 알림 설정 (Multi-window Multi-burn-rate alerts)

### 7. 성능 비교: SLO 적용 전후
- 비용 대비 신뢰성 효과
- 100% 가용성 추구 vs 99.9% SLO 운영 비용 비교
- 인적 자원과 인프라 자원 효율성
- 사용자 체감 품질과 SLO 수치의 상관관계

### 8. 실무 적용 사례
- 마이크로서비스 환경의 SLO 계층화
- 의존성 있는 서비스 간 SLO 전파
- SLO와 카나리 배포의 연계
- Post-mortem과 SLO 위반 분석

## 핵심 정리
- SLI는 측정 지표, SLO는 내부 목표, SLA는 외부 계약
- 100% 신뢰성은 비현실적이며 비효율적, 적정 SLO 설정이 핵심
- Error Budget으로 개발 속도와 안정성의 균형 관리
- 사용자 경험 중심으로 SLI를 선정해야 의미 있는 SLO가 된다
- The Four Golden Signals(Latency, Traffic, Errors, Saturation)이 기본
- Rolling Window(28일)와 Burn Rate 기반 알림이 효과적
- SLO는 한 번 정하고 끝이 아니라 지속적으로 리뷰 및 조정해야 한다

## 기술적 한계와 보완 전략
- 모든 사용자 경험을 SLI로 환산하기 어려움 → 합성 모니터링(Synthetic Monitoring)과 RUM(Real User Monitoring) 병행
- Long-tail Latency 측정의 어려움 → P50/P95/P99 다층 측정 및 분포 기반 분석
- 의존성 있는 서비스 SLO 계산 복잡성 → SLO Dependency Graph 도입
- 짧은 다운타임에도 Error Budget이 빠르게 소진되는 문제 → Multi-window Burn Rate 알림으로 조기 감지
- 비즈니스 가치와 직결되지 않는 SLO → 비즈니스 메트릭과 연계한 Critical User Journey 우선순위화

## 키워드
- SLI (Service Level Indicator)
- SLO (Service Level Objective)
- SLA (Service Level Agreement)
- Error Budget
- The Four Golden Signals
- Burn Rate
- Rolling Window
- Critical User Journey
- Site Reliability Engineering (SRE)
- Availability Tier (Nine's)

## 참고 자료
-
