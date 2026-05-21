# Vercel과 Vercel로 할 수 있는 모든 것

## 목차

1. [Vercel이란](#vercel이란)
2. [핵심 인프라: Vercel Delivery Network (CDN)](#핵심-인프라-vercel-delivery-network-cdn)
3. [Vercel Functions](#vercel-functions)
4. [배포 파이프라인과 환경 관리](#배포-파이프라인과-환경-관리)
5. [스토리지](#스토리지)
6. [AI 인프라](#ai-인프라)
7. [보안](#보안)
8. [요금 체계](#요금-체계)
9. [키워드 정리](#키워드-정리)

---

## Vercel이란

Vercel은 "AI Cloud"를 표방하는 프론트엔드 중심의 클라우드 플랫폼이다. 웹 애플리케이션을 빌드하고, 배포하고, 전 세계에 서빙하는 데 필요한 인프라를 추상화해서 제공한다.

### Vercel이 해결하는 문제

전통적인 배포 방식에서는 CDN 설정, 서버 관리, 스케일링, SSL 인증서 발급 등을 개발자가 직접 챙겨야 했다. Vercel은 이것들을 모두 자동화한다.

- Git 저장소에 push하면 자동으로 빌드 및 배포
- CDN 설정 없이도 전 세계 엣지에 자동 배포
- SSL/TLS 인증서 자동 발급 및 갱신
- 트래픽에 따라 자동 스케일링

### 지원하는 프레임워크

Next.js를 포함해 Nuxt, SvelteKit, Remix, Astro, Vite, React Router, TanStack Start 같은 프론트엔드 프레임워크를 지원한다. Express, Hono, FastAPI, NestJS 같은 백엔드 프레임워크 배포도 가능하다.

---

## 핵심 인프라: Vercel Delivery Network (CDN)

Vercel CDN은 단순한 정적 파일 캐싱 CDN이 아니다. **프레임워크를 이해하는 CDN**이다.

### 글로벌 네트워크

- 51개국에 걸친 **126개 이상의 PoP(Points of Presence)**
- 20개 이상의 Vercel 리전에서 컴퓨트 실행
- PoP와 리전은 사설 저지연 네트워크로 연결

### 프레임워크-어웨어(Framework-Aware)

일반 CDN은 응답을 받은 뒤에야 해당 경로가 캐시 가능한지 안다. Vercel CDN은 빌드 시점에 프레임워크 설정을 분석해서 **어떤 경로가 캐시 가능한지를 미리 알고** 모든 CDN 리전에 메타데이터를 배포한다.

이로 인해 다음 기능들이 가능해진다:
- **Request Collapsing**: 동일 경로에 동시 요청이 몰릴 때 함수 호출을 1회로 합침
- **300ms 전 세계 동기화**: 캐시 무효화 시 전 리전에 300ms 안에 전파
- **즉시 롤백**: 이전 배포의 캐시를 유지하므로 롤백 시 캐시 손실 없음

### CDN 캐싱 계층

```
요청
  │
  ▼
CDN Cache (각 PoP 로컬)
  │ miss
  ▼
ISR Cache (Function 리전 근처 내구성 스토리지)
  │ miss
  ▼
Vercel Function 실행
  │
  ▼
응답 → ISR Cache 저장 → CDN Cache 저장
```

### ISR (Incremental Static Regeneration)

ISR은 **정적 콘텐츠의 속도와 서버 사이드 렌더링의 유연성을 결합**한 캐싱 전략이다.

**stale-while-revalidate 패턴**을 따른다:
1. 방문자에게는 캐시된 응답을 즉시 반환
2. 백그라운드에서 새 버전 생성
3. 새 버전 준비 완료 후 모든 리전 캐시를 원자적으로 교체

**재검증 방식 두 가지:**
- **시간 기반 재검증**: 설정한 인터벌(예: 60초)마다 자동 재검증
- **온디맨드 재검증**: API 호출로 특정 경로나 태그를 즉시 무효화

**ISR을 쓰면 좋은 상황:**
- 상품 카탈로그 (수시로 재빌드 불가능한 규모)
- CMS 기반 콘텐츠 (편집자가 발행할 때마다 갱신)
- 분 단위로 변하는 데이터 (실시간은 아니지만 오래된 것도 곤란한 경우)

### 이미지 최적화

Vercel은 이미지를 동적으로 변환해 파일 크기를 줄인다:
- WebP, AVIF 포맷으로 자동 변환
- 요청 시 리사이즈 및 크롭
- 변환된 이미지는 CDN에 캐시
- Next.js `<Image>`, Astro, Nuxt 등 프레임워크 내장 컴포넌트와 연동

---

## Vercel Functions

Vercel Functions는 서버를 관리하지 않고 서버 사이드 코드를 실행하는 방법이다.

### 동작 방식

요청이 들어오면 새 함수 인스턴스를 생성하고, 요청 처리 후 자동으로 0으로 스케일 다운한다. 트래픽이 없으면 비용도 없다.

### Fluid Compute

2025년 도입된 Vercel의 컴퓨트 최적화 모델이다. 기존 서버리스는 요청마다 격리된 인스턴스를 새로 생성했다. Fluid Compute는 같은 인스턴스 내에서 **동시 요청 처리**를 허용한다.

**장점:**
- 콜드 스타트 감소 (기존 대비 약 25% 절감)
- 유휴 CPU 시간을 활용해 비용 절감
- I/O 바운드 작업(DB 조회, AI API 호출 등)에서 효율 극대화
- AI 워크로드처럼 긴 응답 스트리밍에 특히 유리

```
기존 서버리스:
요청A → 인스턴스1
요청B → 인스턴스2  (별도 인스턴스, 콜드 스타트 발생)

Fluid Compute:
요청A → 인스턴스1
요청B → 인스턴스1  (같은 인스턴스 재사용, idle 시간 활용)
```

### 런타임 종류

| 런타임 | 특징 | 기본 리전 |
|--------|------|-----------|
| Node.js | 일반 서버 사이드 코드, DB 연결 | `iad1` (Washington D.C.) |
| Edge | 전 세계 PoP에 배포, 초저지연 | 요청 가장 가까운 리전 |

> 데이터 소스(DB)와 함수는 같은 리전에 위치시켜야 레이턴시를 최소화할 수 있다.

### 함수 생성 예시

```ts
// api/hello.ts
export default {
  fetch(request: Request) {
    return new Response('Hello from Vercel!');
  },
};
```

---

## 배포 파이프라인과 환경 관리

### 배포 트리거

```
Git push / PR 생성
        │
        ▼
Vercel 빌드 서버
        │
        ▼
프레임워크 빌드 실행
        │
        ▼
정적 파일 → CDN 배포
서버 코드 → Function 배포
        │
        ▼
고유 Preview URL 생성
PR에 자동 댓글로 URL 첨부
```

### 세 가지 환경

**1. Local Development**
- Vercel CLI로 환경 변수를 로컬에 동기화
- `vercel env pull` 명령으로 `.env.local` 자동 생성

**2. Preview Environment**
- production 브랜치(보통 `main`)가 아닌 브랜치에 push하면 자동 생성
- 각 커밋과 브랜치마다 고유 URL 부여
- 두 종류의 URL:
  - **브랜치 URL**: 해당 브랜치의 최신 배포를 항상 가리킴
  - **커밋 URL**: 특정 커밋의 배포를 고정으로 가리킴

**3. Production Environment**
- `main` 브랜치에 push 또는 `vercel --prod` 명령으로 배포
- 프로덕션 도메인이 새 배포를 즉시 가리키도록 업데이트

### Custom Environment (Pro/Enterprise)

Pro는 프로젝트당 1개, Enterprise는 12개의 커스텀 환경을 만들 수 있다.

```bash
# staging 환경에 배포
vercel deploy --target=staging

# staging 환경 변수 pull
vercel pull --environment=staging

# staging 환경 변수 추가
vercel env add MY_KEY staging
```

### Environment Variables

환경마다 독립적인 환경 변수를 설정할 수 있다. 예를 들어 Preview 환경에서는 스테이징 DB를, Production에서는 운영 DB를 가리키도록 분리할 수 있다.

```
Production    → PROD_DB_URL
Preview       → STAGING_DB_URL
Development   → LOCAL_DB_URL
```

---

## 스토리지

Vercel은 서버리스 환경에서 바로 쓸 수 있는 관리형 스토리지를 제공한다.

### Vercel Blob

대용량 파일 저장에 특화된 오브젝트 스토리지다.

- 이미지, 비디오, 사용자 업로드 파일 저장
- Fast(빠른 읽기) + 밀리초 단위 쓰기
- Hobby, Pro 플랜 지원

```ts
import { put } from '@vercel/blob';

const blob = await put('avatar.png', file, { access: 'public' });
console.log(blob.url); // CDN URL 반환
```

### Vercel Edge Config

전 세계 엣지에서 **초저지연으로 읽을 수 있는 글로벌 데이터 스토어**다.

- 대부분의 읽기가 **1ms 미만**, 99%는 10ms 이하
- 모든 CDN 리전에 능동적으로 복제
- 쓰기는 초 단위 (자주 바뀌지 않는 데이터용)
- **사용 사례**: 피처 플래그, 중요한 리다이렉트 URL, 유지보수 모드 스위치

```ts
import { get } from '@vercel/edge-config';

const featureFlag = await get('new_checkout_enabled');
```

### Marketplace Storage

Vercel 대시보드에서 직접 프로비저닝할 수 있는 외부 스토리지 통합이다. 자격 증명이 자동으로 환경 변수에 주입된다.

| 유형 | 제공사 | 사용 사례 |
|------|--------|-----------|
| Postgres | Neon, Supabase | ACID 트랜잭션, 복잡한 쿼리 |
| KV (Redis) | Upstash | 캐싱, 세션, 레이트 리미팅 |
| NoSQL | MongoDB, DynamoDB | 유연한 스키마 |
| 벡터 DB | Pinecone, pgvector | AI 임베딩, 의미 검색 |

```bash
# CLI 한 줄로 DB 프로비저닝 + 환경 변수 주입
vercel install neon
vercel install upstash
```

---

## AI 인프라

Vercel은 AI 애플리케이션을 위한 전용 인프라를 갖추고 있다.

### v0

자연어로 UI를 설명하면 shadcn/ui + Tailwind CSS 기반의 React 코드를 생성해주는 AI 개발 어시스턴트다. 2026년 2월 업데이트로 Git 워크플로 연동, 보안 강화, 에이전트 기능이 추가됐다.

### AI SDK

언어 모델 통합을 위한 공식 SDK다. 스트리밍과 tool calling을 지원한다.

### AI Gateway

여러 AI 프로바이더(OpenAI, Anthropic, Google 등)로의 라우팅을 관리하는 게이트웨이다. 자동 failover 기능을 제공한다.

### Vercel Sandbox

AI 에이전트가 생성한 코드처럼 **신뢰할 수 없는 코드를 안전하게 실행**하기 위한 격리 환경이다.

---

## 보안

모든 배포에 기본적으로 적용되는 보안 기능들이다.

### 기본 보안

- **HTTPS 자동 적용**: SSL/TLS 인증서 자동 발급 및 갱신, TLS 1.2/1.3 지원
- **플랫폼 DDoS 방어**: 추가 비용 없이 모든 배포에 상시 적용
- **Deployment Protection**: 프리뷰 배포에 대한 무단 접근 차단

### 고급 보안 (Pro/Enterprise)

| 기능 | 설명 |
|------|------|
| Vercel WAF | 커스텀 규칙으로 공격, 스크래핑, 악성 트래픽 차단 |
| Bot Management | 자동화 트래픽 탐지 및 차단 |
| BotID | 사용자에게 CAPTCHA 노출 없이 정교한 봇을 방어하는 인비저블 CAPTCHA |
| AI Bot Filtering | AI 크롤러/봇 트래픽 제어 |
| RBAC | 역할 기반 접근 제어 |

---

## 요금 체계

### Hobby (무료)

- 개인 프로젝트용
- 상업적 사용 공식 금지
- 대역폭 100GB
- Serverless 실행 100GB-시간

### Pro — $20/월 per Developer seat

- 상업적 사용 가능
- Developer 시트당 $20/월, Viewer 시트는 무료
- 매월 $20 사용 크레딧 포함 (Function 실행, 대역폭 등에 사용)
- 팀 협업 기능
- 커스텀 환경 1개
- 더 높은 실행 한도

### Enterprise — 커스텀 견적 (영업팀 문의)

- 커스텀 환경 12개
- 전용 지원 및 SLA
- 고급 보안 기능 전체 사용 가능 (WAF, Bot Management, BotID 등)
- 역할 기반 접근 제어(RBAC)

---

## 키워드 정리

### CDN (Content Delivery Network)
사용자와 가까운 서버에서 콘텐츠를 제공해 레이턴시를 줄이는 분산 네트워크. Vercel CDN은 단순 정적 파일 캐싱을 넘어 프레임워크의 라우팅 및 캐싱 설정을 빌드 시점에 분석해 최적의 전략을 자동 적용한다.

### ISR (Incremental Static Regeneration)
정적 생성과 서버 사이드 렌더링의 장점을 결합한 캐싱 전략. stale-while-revalidate 패턴으로 동작해 방문자에게는 캐시된 응답을 즉시 주면서 백그라운드에서 새 버전을 생성한다. Next.js, SvelteKit, Nuxt, Astro에서 지원한다.

### Fluid Compute
Vercel이 2025년 도입한 서버리스 컴퓨트 최적화 모델. 같은 함수 인스턴스 내에서 동시 요청 처리를 허용해 콜드 스타트를 줄이고 유휴 CPU 시간을 재활용한다. AI 워크로드처럼 I/O 대기가 많은 작업에서 특히 효과적이다.

### Edge Config
전 세계 CDN 리전에 능동적으로 복제되는 글로벌 저지연 데이터 스토어. 읽기 지연이 1ms 미만이며, 자주 바뀌지 않으면서 빠르게 읽어야 하는 데이터(피처 플래그, 리다이렉트 규칙 등)에 적합하다.

### Preview Deployment
Production 브랜치 외의 브랜치에 push하거나 PR을 생성할 때 자동으로 만들어지는 독립 배포 환경. 각 배포에 고유 URL이 부여돼 프로덕션 영향 없이 변경사항을 검토할 수 있다.

### Serverless Function
요청이 있을 때만 실행되고 트래픽이 없으면 0으로 스케일 다운되는 함수형 컴퓨트. 서버를 프로비저닝하거나 관리할 필요 없이 서버 사이드 로직을 실행할 수 있다.

### PoP (Point of Presence)
CDN의 물리적 노드. 사용자의 요청이 가장 가까운 PoP에서 처리되어 네트워크 왕복 시간을 최소화한다. Vercel은 51개국에 126개 이상의 PoP를 운영한다.

### stale-while-revalidate
캐시가 만료됐을 때 만료된 콘텐츠를 즉시 반환하면서 동시에 백그라운드에서 새 콘텐츠를 가져오는 캐시 전략. 사용자는 항상 빠른 응답을 받고, 콘텐츠는 비동기로 최신화된다.

### Vercel Blob
이미지, 비디오, 파일 등 대용량 바이너리 데이터를 저장하는 Vercel의 오브젝트 스토리지 서비스. 업로드된 파일은 CDN URL로 서빙된다.

### Rolling Release
새 배포를 전체 트래픽에 한번에 전환하지 않고 점진적으로 트래픽을 이전하는 배포 전략. 문제 발생 시 영향 범위를 최소화할 수 있다.
