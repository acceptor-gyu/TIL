# Vercel과 Vercel로 할 수 있는 모든 것

## 개요
- Vercel이란 무엇인가
- 왜 Vercel이 주목받는가 (Next.js와의 관계)
- 다른 호스팅/배포 플랫폼과의 차별점

## 상세 내용

### 1. Vercel 핵심 개념
- Frontend Cloud / Edge Network 기반 플랫폼
- Serverless Functions와 Edge Functions
- 글로벌 CDN과 Edge Network 구조
- Git 연동 기반 자동 배포 (CI/CD)

### 2. Vercel로 할 수 있는 것들

#### 2.1 정적/동적 웹사이트 배포
- Next.js, Nuxt, SvelteKit, Remix 등 주요 프레임워크 지원
- 정적 사이트(SSG), 서버사이드 렌더링(SSR), ISR(Incremental Static Regeneration)
- Preview Deployments (PR/브랜치별 배포 환경)

#### 2.2 서버리스 백엔드
- Serverless Functions (Node.js, Python, Go, Ruby)
- Edge Functions (낮은 레이턴시, 글로벌 분산 실행)
- Cron Jobs (스케줄링 작업)
- API Routes 손쉬운 구성

#### 2.3 데이터 인프라 (Vercel Storage)
- Vercel Postgres (Serverless Postgres)
- Vercel KV (Redis 기반 Key-Value)
- Vercel Blob (대용량 파일 저장)
- Edge Config (실시간 구성 데이터)

#### 2.4 AI/ML 통합
- Vercel AI SDK (LLM 스트리밍 응답 처리)
- AI Playground
- AI Gateway (다양한 LLM 통합)

#### 2.5 도메인 & 보안
- 자동 HTTPS / SSL 인증서 발급
- 커스텀 도메인 연결
- DDoS 방어, Web Application Firewall (WAF)
- Bot Protection

#### 2.6 분석 및 모니터링
- Vercel Analytics (Web Vitals 측정)
- Speed Insights (성능 측정)
- Log Drains, Observability

#### 2.7 협업/팀 기능
- Preview Comments (디자이너/PM 협업)
- Team & Role 관리
- Build Cache 공유

### 3. Vercel 배포 흐름
- Git push → Build → Deploy → Edge Network 배포
- 환경변수 관리 (Production / Preview / Development)
- 롤백 및 Instant Rollback

### 4. 가격 정책 및 한계
- Hobby / Pro / Enterprise 플랜 차이
- Bandwidth, Function 실행 시간 등 제한
- Cold Start 이슈 (Serverless 특성)

## 핵심 정리
- Vercel은 단순 호스팅이 아닌 Frontend Cloud 플랫폼
- Git 기반 자동 배포 + Edge Network + Serverless의 통합 경험 제공
- Next.js와의 강력한 시너지, 그러나 다양한 프레임워크 지원
- AI, DB, Storage까지 통합하여 풀스택 개발 가능

## 기술적 한계와 보완 전략
- Serverless Cold Start → Edge Functions 활용 / 워밍업 전략
- Bandwidth 비용 → 이미지 최적화, CDN 캐시 정책 강화
- Vendor Lock-in 우려 → 표준 프레임워크 사용, IaC로 이식성 확보
- 장시간 실행 작업 한계 → 외부 워커(예: AWS Lambda, Cloud Run) 분리

## 키워드
- Vercel
- Next.js
- Serverless Functions
- Edge Network
- Preview Deployments
- ISR (Incremental Static Regeneration)
- Vercel AI SDK
- Vercel Storage
- CI/CD
- Frontend Cloud

## 참고 자료
-
