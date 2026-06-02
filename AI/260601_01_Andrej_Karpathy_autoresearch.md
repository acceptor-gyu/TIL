# Andrej Karpathy autoresearch

## 개요
[Andrej Karpathy가 제시한 autoresearch(자율 연구) 개념과, LLM/에이전트를 활용해 연구 과정 자체를 자동화하려는 시도에 대한 학습 정리]

## 상세 내용

### 1. autoresearch란 무엇인가
- 개념 정의: 가설 수립 → 실험 설계 → 실행 → 분석 → 다음 가설로 이어지는 연구 루프의 자동화
- Karpathy가 이 개념을 언급한 맥락과 문제의식

### 2. 연구 루프(Research Loop)의 구조
- 가설 생성 (Hypothesis generation)
- 실험/코드 작성 및 실행 (Experimentation)
- 결과 평가 및 피드백 (Evaluation)
- 반복과 수렴 (Iteration)

### 3. autoresearch를 구성하는 핵심 요소
- LLM 기반 추론 엔진
- 에이전트 하네스(harness)와 도구 사용(tool use)
- 검증 가능한 보상 신호 / 평가 환경 (verifiable reward)
- 메모리와 컨텍스트 관리

### 4. 기존 AutoML / AI Scientist와의 관계
- AutoML, AI Scientist(Sakana) 등 선행 사례와의 차이
- "연구를 코딩하는 에이전트"라는 관점

### 5. Karpathy의 관련 관점
- Software 2.0 / 3.0 흐름과 autoresearch의 연결
- LLM이 "자기 자신을 개선하는 연구자" 역할을 할 수 있는가에 대한 시각
- 인간-AI 협업 루프에서 인간의 역할 변화

### 6. 한계와 현실적 제약
- 보상 해킹(reward hacking)과 평가 신뢰성 문제
- 새로운 발견(novelty) vs 기존 지식 재조합의 경계
- 컴퓨팅 비용과 재현성

## 핵심 정리
- 핵심 포인트 1: autoresearch는 연구의 반복 루프 자체를 에이전트로 자동화하려는 시도다
- 핵심 포인트 2: 검증 가능한 평가 환경이 자율 연구 품질을 좌우하는 핵심 병목이다
- 핵심 포인트 3: 인간은 방향 설정과 검증자(verifier) 역할로 이동한다

## 기술적 한계와 보완 전략
- 평가 신뢰성 부족 → 검증 가능한 보상(verifiable reward) 및 인간 검토 루프 결합
- 보상 해킹 → 다중 평가 지표와 홀드아웃 환경 활용
- 비용/재현성 → 실험 로깅, 시드 고정, 캐싱 전략

## 키워드
- autoresearch
- Andrej Karpathy
- Research Loop
- Agentic Harness
- Verifiable Reward
- AI Scientist
- AutoML
- Reward Hacking
- Self-improving AI
- Software 3.0

## 참고 자료
-
