# AWS Bedrock

## 개요
Amazon Bedrock은 Anthropic, Amazon, Meta, Mistral, Cohere, AI21, DeepSeek, OpenAI 등 여러 회사의 파운데이션 모델(FM)을 단일 API로 호출할 수 있게 해주는 완전관리형(Serverless) 생성형 AI 서비스다. 직접 GPU 인프라를 구축하거나 모델을 학습시키지 않고도, 이미 학습이 끝난 최고 수준의 FM을 바로 호출해서 텍스트 생성, 요약, RAG, 에이전트, 이미지/멀티모달 생성 같은 애플리케이션을 만들 수 있게 해주는 것이 등장 배경이다. 특히 데이터 프라이버시(입력 데이터가 모델 재학습에 쓰이지 않음)와 AWS의 IAM/VPC/CloudTrail 같은 기존 보안·거버넌스 체계를 그대로 활용할 수 있다는 점이 엔터프라이즈 채택의 핵심 이유다.

## 상세 내용

### AWS Bedrock이란 무엇인가
- 완전관리형(Serverless) 생성형 AI 서비스: 서버 프로비저닝, 스케일링, 패치 없이 API 호출만으로 FM을 사용할 수 있다.
- 등장 배경: 자체 모델 학습이나 GPU 클러스터 운영 없이, 검증된 파운데이션 모델을 API 형태로 "소비"하려는 수요에서 출발했다. 2023년 4월 출시 이후 지원 모델과 기능(Agents, Knowledge Bases, Guardrails 등)이 지속적으로 확장되었다.
- SageMaker와의 차이점: SageMaker는 데이터 수집부터 모델 학습·튜닝·배포·운영까지 ML 라이프사이클 전반을 다루는 플랫폼인 반면, Bedrock은 이미 학습된 FM을 API로 호출하는 데 초점이 맞춰져 있다. 다만 Bedrock도 자체 데이터로 Fine-tuning/Continued Pre-training을 지원해 두 서비스의 경계가 일부 겹친다.

### 지원하는 파운데이션 모델(Foundation Models)
- Anthropic Claude(Opus/Sonnet/Haiku), Amazon Nova(텍스트/이미지/비디오), Meta Llama, Mistral, Cohere Command/Embed, AI21 Jamba 등 100개 이상의 모델을 제공한다.
- 2026년 기준으로 DeepSeek, Moonshot AI의 Kimi, MiniMax, OpenAI(GPT-OSS, GPT-5.6 계열)까지 지원 모델군이 확대되었다.
- 텍스트 생성, 임베딩(Titan Embeddings, Cohere Embed), 이미지 생성(Amazon Nova Canvas, Stability AI), 비디오 생성(Nova Reel) 등 모델 유형이 다양하다.
- 모델 선택 기준: 벤치마크 성능, 토큰당 비용, 컨텍스트 윈도우 길이, 멀티모달(이미지/문서 입력) 지원 여부, 리전별 가용성을 종합적으로 고려한다.

### 핵심 기능과 API
- **다양한 호출 API**: 모델 제공사에 맞춘 네이티브 Messages API(Anthropic), OpenAI 호환 Responses API/Chat Completions API, AWS SDK 기반 Invoke API(`InvokeModel`), 그리고 모델 간 통일된 인터페이스를 제공하는 **Converse API**(`Converse`/`ConverseStream`)까지 여러 방식으로 모델을 호출할 수 있다. Converse API를 쓰면 모델별 요청/응답 스키마 차이를 신경 쓰지 않고 코드를 한 번만 작성해 여러 모델에 재사용할 수 있다.
- **스트리밍 응답**: `ConverseStream`, `InvokeModelWithResponseStream`으로 토큰 단위 스트리밍 응답을 처리할 수 있다.
- **Tool Use(함수 호출)**: Converse API는 Claude, Mistral Large, Cohere Command R/R+ 등 지원 모델에서 툴 정의와 호출 결과 반영을 표준화된 방식으로 지원한다.
- **Knowledge Bases (RAG)**: 벡터 스토어와 연동해 검색 증강 생성을 구현한다.
- **Bedrock Agents**: 툴 호출과 멀티스텝 태스크 오케스트레이션을 담당한다. (아래 별도 섹션 참고)
- **Guardrails**: 유해 콘텐츠 필터링과 정책 기반 응답 제어를 담당한다.
- **Fine-tuning / Continued Pre-training**: 자체 데이터로 모델을 커스터마이징한다.
- **Provisioned Throughput vs On-Demand**: 처리량 보장 여부에 따른 두 가지 요금/성능 모드를 제공한다.
- **Batch Inference**: 실시간성이 필요 없는 대량 요청을 온디맨드 대비 약 50% 저렴하게 처리한다.

### RAG 아키텍처와 Knowledge Bases
- S3 등의 데이터 소스를 연결하면 Bedrock이 문서를 청킹하고 임베딩 모델로 벡터화하는 파이프라인을 관리형으로 제공한다.
- 벡터 데이터베이스로 OpenSearch Serverless, Amazon Aurora(pgvector), S3 Vectors, Pinecone, Redis 등을 백엔드로 선택할 수 있다.
- 사용자 질의 → 벡터 검색으로 관련 문서 조각 검색 → 검색 결과를 프롬프트에 삽입해 FM에 전달 → 근거 기반 응답 생성이라는 흐름으로 동작한다.
- Guardrails의 Contextual Grounding Check와 결합하면 RAG 응답이 검색된 소스에서 벗어나는(할루시네이션) 경우를 탐지·차단할 수 있다.

### Bedrock Agents와 AgentCore로의 전환
- Agents 빌드타임 구성 요소: 파운데이션 모델, 자연어 Instructions, Action Groups(OpenAPI 스키마 + Lambda 함수로 정의된 실행 가능한 액션), Knowledge Bases, 그리고 전처리/오케스트레이션/후처리 단계별 Prompt Template로 구성된다.
- 런타임 동작(`InvokeAgent`)은 전처리 → 오케스트레이션(모델이 rationale 생성 → 액션/지식베이스 선택 → 실행 → observation 생성 → 반복) → 후처리의 루프 구조로 이루어지며, trace 기능으로 각 단계의 추론 과정을 추적할 수 있다.
- **중요한 변화**: 기존 Bedrock Agents는 2026년 7월 30일부로 "Bedrock Agents Classic"으로 전환되어 신규 고객 가입이 중단되고 유지보수 모드로 들어간다. AWS는 후속으로 **Amazon Bedrock AgentCore**를 제시하고 있으며, AgentCore는 Runtime, Memory, Gateway(MCP 변환), Identity, Code Interpreter, Browser, Observability, Evaluations 등 모듈화된 서비스 집합으로, LangGraph·CrewAI·Strands Agents 같은 오픈소스 프레임워크와 임의의 FM(Bedrock 내외부 모두)을 조합해 프로덕션급 에이전트를 운영할 수 있게 해준다.

### 보안과 거버넌스
- IAM 기반 세밀한 접근 제어(모델 호출, Agent, Guardrail 등 리소스 단위 정책 설정)
- VPC Endpoint(PrivateLink)를 통한 프라이빗 통신으로 인터넷 구간 노출 없이 Bedrock 호출 가능
- 데이터 프라이버시: 입력으로 제공한 텍스트/이미지/문서는 모델 재학습에 사용되지 않으며, 저장하지 않는다(Guardrails 로그 등 사용자가 명시적으로 설정한 항목 제외)
- CloudWatch(메트릭/로그)와 CloudTrail(API 호출 감사)을 통한 모니터링과 컴플라이언스 대응

### 비용 구조
- **On-Demand**: 입력/출력 토큰 단위 종량 과금. 모델·리전별 단가가 다르며, 출력 토큰이 입력 토큰보다 3~5배 비싼 경우가 많아 응답 길이 관리가 비용에 큰 영향을 준다.
- **Provisioned Throughput**: 분당 처리 토큰량을 보장받는 대신 모델 유닛 단위로 시간당 요금을 지불하며, 1개월/6개월 약정으로 단가를 낮출 수 있다. 트래픽이 크고 예측 가능한 워크로드에 적합하다.
- **Batch Inference**: 온디맨드 대비 약 50% 할인된 가격으로 지연시간에 민감하지 않은 대량 작업을 처리한다.
- **Priority/Flex 서비스 티어**: Priority 티어는 응답 속도를 우선해 추가 요금(약 75% 프리미엄)을, Flex 티어는 지연 허용 대신 할인(약 50%)을 제공한다.
- **Knowledge Bases / Guardrails 부가 비용**: Knowledge Bases 자체는 별도 요금이 없지만 연결된 벡터 스토어(OpenSearch Serverless 등) 비용이 별도로 발생하며, Guardrails는 텍스트/이미지 평가 단위로 과금된다.
- 비용 최적화 전략: 모델 라우팅(작업 난이도에 따라 저비용 모델과 고성능 모델을 분리 호출), 프롬프트/응답 캐싱, Batch 처리 활용, 리전/모델별 단가 비교가 핵심이다.

## 핵심 정리
- 핵심 포인트 1: Bedrock은 인프라 관리 없이 여러 파운데이션 모델(Claude, Nova, Llama, DeepSeek, OpenAI 등)을 Converse API 등 단일 인터페이스로 소비하는 서버리스 생성형 AI 플랫폼이다.
- 핵심 포인트 2: Knowledge Bases(RAG), Agents/AgentCore, Guardrails로 단순 모델 호출을 넘어 검색 증강, 자율 에이전트, 안전 정책까지 갖춘 엔터프라이즈 애플리케이션을 구성할 수 있다.
- 핵심 포인트 3: On-Demand, Provisioned Throughput, Batch, Priority/Flex 티어 등 다양한 처리량·비용 모델 중 워크로드 특성에 맞는 선택이 운영 설계의 핵심이며, 2026년 7월 30일부로 기존 Bedrock Agents는 Classic으로 전환되고 AgentCore가 후속 표준으로 자리 잡고 있다.

## 기술적 한계와 보완 전략
- 모델별 리전 가용성 제약: 최신/인기 모델일수록 특정 리전(주로 us-east-1)에만 먼저 출시되는 경우가 많아, 글로벌 서비스에서는 크로스 리전 추론(Cross-Region Inference) 프로파일 활용이 필요하다.
- On-Demand 처리량 한도(쿼터)와 급격한 트래픽 증가 시 스로틀링 이슈: 예측 가능한 고트래픽 워크로드는 Provisioned Throughput 전환 시점을 미리 계획해야 한다.
- 최신 오픈소스/외부 모델 대비 Bedrock에 통합되기까지의 시차: 자체 호스팅 대비 최신 모델 반영 속도가 느릴 수 있다.
- 벤더 종속성(AWS Lock-in): Converse API처럼 모델 불가지론적(model-agnostic) 인터페이스를 우선 채택하고, 프롬프트/오케스트레이션 로직을 별도 추상화 계층으로 분리해 다른 클라우드/자체 호스팅 모델로의 전환 비용을 낮추는 설계가 필요하다.
- Bedrock Agents Classic의 신규 가입 중단(2026.07.30): 신규 프로젝트는 AgentCore 기반 설계를 우선 검토해야 하며, 기존 Agents Classic 사용자는 마이그레이션 로드맵을 준비해야 한다.

## 키워드
- **AWS Bedrock**: 여러 회사의 파운데이션 모델을 단일 API로 호출할 수 있게 해주는 AWS의 완전관리형 서버리스 생성형 AI 서비스.
- **Foundation Model(FM)**: 대규모 데이터로 사전 학습되어 다양한 다운스트림 작업(텍스트 생성, 요약, 임베딩 등)에 범용적으로 활용 가능한 대형 모델. Bedrock은 Claude, Nova, Llama, DeepSeek 등 100개 이상의 FM을 제공한다.
- **Converse API**: 서로 다른 모델 제공사의 API 스키마 차이를 흡수해, 코드를 한 번만 작성하면 여러 FM에 재사용할 수 있게 해주는 Bedrock의 통일된 모델 호출 인터페이스(`Converse`/`ConverseStream`).
- **RAG(Knowledge Bases)**: 벡터 검색으로 관련 문서를 찾아 프롬프트에 삽입한 뒤 FM이 근거 기반 응답을 생성하게 하는 검색 증강 생성 기법. Bedrock Knowledge Bases가 데이터 수집·임베딩·검색 파이프라인을 관리형으로 제공한다.
- **Bedrock Agents(Classic) / AgentCore**: Action Groups(Lambda+OpenAPI)와 Knowledge Base를 조합해 모델이 스스로 추론·행동·관찰을 반복하며 멀티스텝 태스크를 수행하게 하는 오케스트레이션 기능. 2026.07.30부로 Classic은 신규 가입이 중단되고, 후속으로 Runtime/Memory/Gateway/Identity 등을 모듈화한 AgentCore가 표준으로 자리 잡고 있다.
- **Guardrails**: 콘텐츠 필터(유해/차별/폭력 등), Denied Topics, PII 마스킹, Contextual Grounding Check, Automated Reasoning Check 등으로 입력/출력을 정책 기반으로 검열·차단하는 안전장치.
- **Provisioned Throughput**: 분당 처리 가능한 토큰량(모델 유닛)을 사전에 구매해 예측 가능한 대량 트래픽을 안정적으로 처리하는 요금/성능 모드. On-Demand(종량 과금)와 대비되는 개념이다.
- **Fine-tuning / Continued Pre-training**: 자체 도메인 데이터로 FM의 가중치를 추가 학습시켜 특정 태스크나 말투에 맞게 커스터마이징하는 기능.
- **Batch Inference**: 실시간 응답이 필요 없는 대량 요청을 온디맨드 대비 약 50% 저렴하게 비동기로 처리하는 방식.
- **서버리스 AI(Serverless AI)**: 인프라 프로비저닝·스케일링·패치를 신경 쓰지 않고 API 호출만으로 AI 모델을 소비할 수 있는 운영 모델. Bedrock의 핵심 가치 제안이다.

## 참고 자료
- [What is Amazon Bedrock? (AWS 공식 문서)](https://docs.aws.amazon.com/bedrock/latest/userguide/what-is-bedrock.md)
- [Amazon Bedrock Converse API 발표](https://aws.amazon.com/about-aws/whats-new/2024/05/amazon-bedrock-new-converse-api)
- [Amazon Bedrock Guardrails 공식 문서](https://docs.aws.amazon.com/bedrock/latest/userguide/guardrails.html)
- [How Amazon Bedrock Agents works (공식 문서)](https://docs.aws.amazon.com/bedrock/latest/userguide/agents-how.html)
- [What is Amazon Bedrock AgentCore? (공식 문서)](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/what-is-bedrock-agentcore.html)
- [Amazon Bedrock Pricing (공식 페이지)](https://aws.amazon.com/bedrock/pricing/)
