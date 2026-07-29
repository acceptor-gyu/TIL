# AWS Bedrock FM (Foundation Model)

## 개요
AWS Bedrock에서 제공하는 파운데이션 모델(Foundation Model, FM)은 대규모 데이터로 사전 학습되어 텍스트 생성·요약·분류·임베딩·이미지 생성 등 다양한 다운스트림 태스크에 범용적으로 활용할 수 있는 대형 모델이다. 전통적인 태스크 특화 모델은 하나의 작업(예: 감성 분석)을 위해 처음부터 학습해야 했지만, FM은 한 번의 대규모 사전학습으로 여러 태스크에 재사용(zero-shot/few-shot)되거나, 소량의 데이터로 추가 튜닝(fine-tuning)해 특정 도메인에 적응시킬 수 있다는 점이 핵심 차이다. Bedrock은 Anthropic, Amazon, Meta, Cohere, AI21 Labs, Mistral, Stability AI 등 여러 벤더의 FM을 단일 API로 호출할 수 있게 해줌으로써, 서버 인프라나 GPU 클러스터를 직접 구축·관리하지 않고도 검증된 최신 생성형 AI 모델을 곧바로 애플리케이션에 통합할 수 있게 해준다.

## 상세 내용

### 1. Foundation Model(FM)이란
- **정의**: FM은 인터넷 규모의 텍스트·이미지·코드 등 방대한 비지도 데이터로 사전학습(Pre-training)되어, 언어와 세계 지식에 대한 범용 표현을 내재화한 대형 신경망(주로 Transformer 기반)이다. "Foundation"이라는 이름은 이 모델이 이후 다양한 응용(챗봇, 요약, 코드 생성, RAG 등)의 "기반"이 된다는 의미에서 유래했다(스탠퍼드 HAI가 2021년 제안한 용어).
- **등장 배경**: GPT-3 이후 모델 파라미터와 학습 데이터 규모가 커질수록 일반화 성능이 비약적으로 향상되는 스케일링 법칙(Scaling Law)이 확인되면서, 개별 기업이 태스크마다 모델을 처음부터 학습하기보다 하나의 거대 모델을 사전학습해두고 이를 다양한 곳에 재사용하는 방식이 경제적으로 합리적인 선택이 되었다.
- **전통적 태스크 특화 모델과의 차이**:
  - 태스크 특화 모델은 라벨링된 데이터로 처음부터(from scratch) 학습해야 하며, 태스크가 바뀌면 모델도 새로 학습해야 한다.
  - FM은 사전학습 단계에서 범용 표현을 학습해두고, 프롬프트(zero-shot/few-shot)만으로 새로운 태스크에 적응하거나, 필요 시 소량 데이터로 미세 조정(fine-tuning)해 성능을 끌어올릴 수 있어 개발·운영 비용이 크게 절감된다.

### 2. Bedrock에서 제공하는 FM 종류
Bedrock은 2023년 4월 출시 이후 지원 모델을 지속적으로 확대해 왔으며, 2026년 기준 7개 이상의 모델 제공사에서 40여 개의 FM을 제공한다.
- **Amazon Titan / Nova**
  - Titan Text(Express/Lite): 범용 텍스트 생성·요약 모델.
  - Titan Text Embeddings V2(`amazon.titan-embed-text-v2:0`): 최대 8,192 토큰(약 50,000자) 입력을 1,024차원 벡터로 변환하는 임베딩 모델로, 검색·RAG·시맨틱 유사도 계산에 최적화되어 있다.
  - Titan Multimodal Embeddings / Titan Image Generator: 이미지·텍스트를 함께 임베딩하거나 텍스트 프롬프트로 이미지를 생성하는 멀티모달 모델.
  - Amazon Nova(Micro/Lite/Pro/Canvas/Reel): Titan을 잇는 차세대 Amazon 자체 모델군으로, 텍스트·이미지·비디오 생성을 저비용·저지연으로 제공한다.
- **Anthropic Claude**: Opus(최고 성능), Sonnet(성능·비용 균형), Haiku(저지연·저비용) 등급으로 구성되며, 긴 컨텍스트와 툴 사용(Tool Use), 문서 추론에 강점이 있어 에이전트 구축에 자주 채택된다.
- **Meta Llama**: 오픈 웨이트 기반 모델(Llama 3.x/4 Maverick·Scout 등)로, 비용에 민감하거나 자체 파인튜닝 기준선(baseline)이 필요한 워크로드에 활용된다.
- **Cohere**: Command R/R+(RAG·툴 사용 특화), Embed(다국어 임베딩) 모델을 제공한다.
- **AI21 Labs**: Jamba 계열은 Mamba-Transformer 하이브리드 아키텍처로 긴 컨텍스트를 효율적으로 처리한다.
- **Mistral / Stability AI**: Mistral Large 2·Mixtral은 다국어 처리에 강점이 있고, Stability AI는 Stable Diffusion 기반 이미지 생성 모델을 제공한다.
- 위 모델들은 크게 **텍스트 생성**, **임베딩**, **멀티모달(이미지/문서 입력)**, **이미지·비디오 생성** 모델로 구분되며, 하나의 애플리케이션 안에서 목적에 맞게 여러 모델을 조합해 사용하는 것이 일반적이다.

### 3. FM 선택 기준
- **태스크 유형**: 단순 요약/분류는 저비용·저지연 모델(Haiku, Nova Micro/Lite)로 충분한 경우가 많고, 복잡한 추론이나 에이전트형 RAG는 고성능 모델(Opus, Sonnet, Nova Pro)이 필요하다. 임베딩·검색에는 전용 임베딩 모델(Titan Embeddings, Cohere Embed)을 사용해야 한다.
- **컨텍스트 길이(Context Window)**: 긴 문서 요약이나 대화 이력을 다룰 때는 모델별 최대 컨텍스트 토큰 수를 확인해야 한다(모델마다 수만~수십만 토큰까지 편차가 크다).
- **비용(토큰 단가) 및 지연 시간(Latency)**: On-Demand는 입력/출력 토큰 단위로 과금되며, 출력 토큰이 입력 토큰보다 3~5배 비싼 경우가 많아 응답 길이 제어가 비용에 직결된다. 실시간 대화형 서비스는 지연 시간(Time To First Token 등)이 중요한 반면, 배치성 작업은 Batch Inference로 비용을 더 낮출 수 있다.
- **라이선스 및 데이터 처리 정책**: 상업적 이용 가능 여부, 파인튜닝 데이터의 재학습 활용 여부, 리전별 모델 가용성(특정 최신 모델은 특정 리전에만 우선 출시되는 경우가 많음)을 반드시 확인해야 한다.

### 4. FM 호출 방식
- **InvokeModel / InvokeModelWithResponseStream API**: 모델 제공사가 정의한 고유 요청/응답 스키마(예: Anthropic Messages 형식, Titan 형식 등)를 그대로 사용하는 저수준 호출 방식이다. `InvokeModel`은 동기 단건 응답, `InvokeModelWithResponseStream`은 토큰 단위 스트리밍 응답을 반환한다.
- **Converse API를 통한 통합 인터페이스**: `Converse` / `ConverseStream`은 Bedrock이 지원하는 대화형 모델 전반에 대해 하나의 정규화된 요청 형태와 응답 포맷(`output.message.content`, `usage.inputTokens/outputTokens`, `metrics.latencyMs` 등)을 제공한다. 코드를 한 번만 작성해두면 `modelId`만 바꿔서 여러 모델로 손쉽게 교체·비교할 수 있고, Tool Use(함수 호출)와 멀티턴 대화 상태 관리도 표준화된 방식으로 다룰 수 있다.
- **모델별 요청/응답 스키마 차이**: InvokeModel을 직접 사용할 경우 모델마다 파라미터명(`max_tokens_to_sample` vs `maxTokenCount` 등)과 응답 구조가 달라, 모델 교체 시 파싱 로직을 수정해야 하는 부담이 있다. 이 문제를 줄이기 위해 Converse API 또는 자체 추상화 레이어 도입이 권장된다.
- **스트리밍 응답 처리**: 챗봇 UI처럼 실시간성이 중요한 경우 스트리밍 API로 토큰이 생성되는 즉시 클라이언트에 전달해 체감 응답 속도(TTFT)를 낮춘다.

### 5. FM 커스터마이징
- **파인튜닝(Fine-tuning)**: 라벨링된 자체 데이터로 FM의 가중치를 추가 학습시켜 특정 태스크나 말투·형식에 맞게 성능을 높인다. Bedrock은 모델별로 지원되는 파인튜닝 유형과 하이퍼파라미터가 다르며, 콘솔 또는 API(`CreateModelCustomizationJob`)로 작업을 생성한다.
- **지속적 사전학습(Continued Pre-training)**: 라벨이 없는 대량의 도메인 텍스트로 모델을 추가 사전학습시켜 특정 도메인 지식이나 어휘에 익숙해지게 만드는 방식이다. 예를 들어 Amazon Titan Text Express/Lite는 Continued Pre-training을 지원한다.
- **프로비저닝된 처리량(Provisioned Throughput)**: 커스터마이징된 모델(또는 특정 기본 모델)을 안정적인 처리량으로 서빙하려면 On-Demand 대신 Provisioned Throughput을 구매해 분당 처리 가능한 토큰량을 보장받을 수 있다. 커스텀 모델은 On-Demand로 호출할 수 없고 Provisioned Throughput 구매가 필요한 경우가 많다.
- **Knowledge Bases를 통한 RAG 연동**: 모델 자체를 재학습시키지 않고도, 벡터 검색으로 찾은 관련 문서를 프롬프트에 삽입해 최신·사내 지식을 반영한 응답을 생성할 수 있다. 파인튜닝(모델의 스타일·행동 변경)과 RAG(모델에 최신 사실 정보 제공)는 상호 보완적인 커스터마이징 전략이다.
- 파인튜닝·지속적 사전학습에 사용한 데이터는 서비스 개선이나 제3자 모델 제공사와 공유되지 않으며, 호출이 처리된 AWS 리전 내에 암호화되어 저장된다는 점이 데이터 프라이버시 측면에서 강조된다.

### 6. 운영 및 보안 관점
- **IAM 기반 접근 제어**: 모델 호출(`bedrock:InvokeModel`), 파인튜닝 작업, Guardrail 리소스 등 세부 액션 단위로 IAM 정책을 부여해 최소 권한 원칙을 적용할 수 있다.
- **Guardrails를 통한 유해 콘텐츠 필터링**: 욕설·차별·폭력 등 콘텐츠 필터, 금지 주제(Denied Topics), PII 마스킹, 근거 기반 검증(Contextual Grounding Check) 등을 정책으로 정의해 입력·출력을 검열할 수 있다.
- **모델 호출 로깅 및 CloudWatch 모니터링**: 모델 호출 요청/응답을 S3나 CloudWatch Logs로 남겨 감사·디버깅에 활용하고, 지연 시간·오류율·토큰 사용량 등을 CloudWatch 메트릭으로 모니터링한다.
- **VPC 엔드포인트를 통한 프라이빗 연결**: AWS PrivateLink 기반 VPC 엔드포인트를 구성하면 인터넷 구간을 거치지 않고 VPC 내부에서 Bedrock API를 안전하게 호출할 수 있다.

## 핵심 정리
- 핵심 포인트 1: Bedrock FM은 인프라 관리 없이 단일 API로 여러 벤더(Anthropic, Amazon, Meta, Cohere, AI21, Mistral, Stability AI 등)의 파운데이션 모델을 사용할 수 있게 해준다.
- 핵심 포인트 2: 태스크 유형, 컨텍스트 길이, 비용, 지연 시간을 종합해 적절한 FM(텍스트 생성/임베딩/멀티모달/이미지 생성)을 선택해야 한다.
- 핵심 포인트 3: Converse API로 모델 간 호출 인터페이스를 통일하면 모델별 스키마 차이로 인한 코드 수정 부담과 벤더 종속을 줄이고, 모델 교체·비교 비용을 낮출 수 있다.

## 기술적 한계와 보완 전략
- 모델별 응답 스키마·성능 편차 → Converse API 및 자체 추상화 레이어로 통일해 모델 교체 비용 최소화
- 토큰 단가에 따른 비용 부담(특히 출력 토큰) → 모델 라우팅(난이도별 모델 분리 호출), 응답 길이 제한, 프롬프트/응답 캐싱, Batch Inference 활용
- 환각(Hallucination) 및 유해 응답 → Knowledge Bases(RAG)로 근거 기반 응답을 유도하고, Guardrails의 Contextual Grounding Check로 근거 이탈 응답을 탐지·차단
- 리전별 모델 가용성 제약(최신 모델은 특정 리전에 우선 출시) → 사용 가능 리전 사전 확인 및 크로스 리전 추론(Cross-Region Inference) 프로파일 또는 멀티 리전 전략 수립
- 커스텀 모델의 On-Demand 미지원 → Provisioned Throughput 구매 시점과 약정 기간(1개월/6개월)을 트래픽 예측과 함께 사전 계획

## 키워드
- **AWS Bedrock**: 여러 회사의 파운데이션 모델을 단일 API로 호출할 수 있게 해주는 AWS의 완전관리형 서버리스 생성형 AI 서비스.
- **Foundation Model**: 대규모 비지도 데이터로 사전학습되어 다양한 다운스트림 태스크(텍스트 생성, 요약, 임베딩, 이미지 생성 등)에 범용적으로 재사용 가능한 대형 모델. 태스크마다 처음부터 학습해야 했던 전통적 특화 모델과 달리 zero-shot/few-shot 또는 소량 데이터의 파인튜닝만으로 적응할 수 있다.
- **Converse API**: 서로 다른 모델 제공사의 API 스키마 차이를 흡수해, 코드를 한 번만 작성하면 여러 FM에 재사용할 수 있게 해주는 Bedrock의 통일된 모델 호출 인터페이스(`Converse`/`ConverseStream`).
- **Fine-tuning**: 라벨링된 자체 데이터로 FM의 가중치를 추가 학습시켜 특정 태스크나 도메인, 응답 스타일에 맞게 성능을 개선하는 모델 커스터마이징 기법.
- **Knowledge Bases (RAG)**: 벡터 검색으로 관련 문서를 찾아 프롬프트에 삽입한 뒤 FM이 근거 기반 응답을 생성하게 하는 검색 증강 생성(Retrieval-Augmented Generation) 기법으로, Bedrock이 데이터 수집·임베딩·검색 파이프라인을 관리형으로 제공한다.
- **Guardrails**: 콘텐츠 필터, 금지 주제(Denied Topics), PII 마스킹, Contextual Grounding Check 등으로 모델 입력/출력을 정책 기반으로 검열·차단하는 Bedrock의 안전장치.
- **Provisioned Throughput**: 분당 처리 가능한 토큰량(모델 유닛)을 사전에 구매해 예측 가능한 대량 트래픽이나 커스텀 모델을 안정적으로 서빙하는 요금/성능 모드로, 종량 과금인 On-Demand와 대비되는 개념이다.
- **Anthropic Claude**: Opus/Sonnet/Haiku 등급으로 제공되는 Anthropic의 FM으로, 긴 컨텍스트·툴 사용·문서 추론에 강점이 있어 Bedrock 에이전트 구축에 자주 채택된다.
- **Amazon Titan**: Amazon이 자체 개발한 FM 계열(Titan Text, Titan Text Embeddings, Titan Image Generator, Titan Multimodal Embeddings)로, 텍스트 생성부터 임베딩·이미지 생성까지 폭넓게 제공되며 Continued Pre-training을 지원하는 대표 모델이다.
- **Serverless AI**: 인프라 프로비저닝·스케일링·패치를 신경 쓰지 않고 API 호출만으로 AI 모델을 소비할 수 있는 운영 모델로, Bedrock FM 활용의 핵심 가치 제안이다.

## 참고 자료
- [What is Amazon Bedrock? (AWS 공식 문서)](https://docs.aws.amazon.com/bedrock/latest/userguide/what-is-bedrock.html)
- [Supported foundation models in Amazon Bedrock (공식 문서)](https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html)
- [Amazon Bedrock Converse API 발표](https://aws.amazon.com/about-aws/whats-new/2024/05/amazon-bedrock-new-converse-api)
- [Converse - Boto3 공식 API 레퍼런스](https://boto3.amazonaws.com/v1/documentation/api/latest/reference/services/bedrock-runtime/client/converse.html)
- [Amazon Titan models (공식 문서)](https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-titan.html)
- [Customize a model with fine-tuning or continued pre-training (공식 문서)](https://docs.aws.amazon.com/bedrock/latest/userguide/custom-model-fine-tuning.html)
- [Amazon Bedrock Guardrails 공식 문서](https://docs.aws.amazon.com/bedrock/latest/userguide/guardrails.html)
- [Amazon Bedrock Pricing (공식 페이지)](https://aws.amazon.com/bedrock/pricing/)
