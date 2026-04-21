# 다중 서버 환경에서 사용자 인증 - Session vs Token

## 개요
단일 서버에서는 세션 기반 인증이 간단하지만, 다중 서버(Scale-out) 환경에서는 세션 공유 문제가 발생한다. 이를 해결하기 위한 Session 기반 전략과 Token 기반 인증(JWT)의 차이점, 트레이드오프를 정리한다.

## 상세 내용

### 1. 세션 기반 인증의 동작 원리

사용자가 로그인하면 서버는 세션 데이터를 생성하고, 그 식별자(Session ID)를 클라이언트 쿠키에 내려보낸다.
이후 모든 요청에서 클라이언트는 쿠키를 전송하고, 서버는 저장소를 조회하여 사용자를 식별한다.

```
[클라이언트]                    [서버]                    [세션 저장소]
    |                          |                           |
    |-- POST /login ---------->|                           |
    |                          |-- 세션 생성 --------------->|
    |                          |  { userId: 1, role: ADMIN }
    |<-- Set-Cookie: SID=... --|                           |
    |                          |                           |
    |-- GET /profile --------->|                           |
    |    Cookie: SID=abc123    |--- 세션 조회 SID=abc123 --->|
    |                          |<-- { userId: 1, ... } ----|
    |<-- 200 OK ---------------|                           |
```

**핵심 특징**
- 상태(State)를 서버가 보유: Stateful 방식
- 서버 메모리 또는 외부 저장소(DB, Redis)에 세션 데이터 보관
- 쿠키에는 Session ID만 저장되므로 민감 정보가 클라이언트에 노출되지 않음
- 서버에서 세션을 즉시 삭제하면 해당 세션은 바로 무효화됨

---

### 2. 다중 서버에서 세션의 문제점

단일 서버에서는 문제가 없지만, 서버를 수평 확장(Scale-out)하는 순간 세션은 특정 서버의 메모리에 종속된다.

```
               [로드밸런서]
             /     |      \
        [서버A]   [서버B]  [서버C]
        세션=1   세션=없음  세션=없음
```

**시나리오: 세션 불일치 문제**
1. 사용자가 서버A에 로그인 → 서버A 메모리에 세션 저장
2. 다음 요청이 로드밸런서에 의해 서버B로 라우팅
3. 서버B에는 해당 세션 정보가 없음 → 401 Unauthorized 반환
4. 사용자 입장에서는 로그인이 풀려버린 것처럼 보임

이로 인해 다중 서버 환경에서는 아래 세 가지 전략 중 하나를 선택해야 한다.

---

### 3. 세션 공유 전략

#### 3-1. Sticky Session (세션 고정)

로드밸런서가 특정 클라이언트를 항상 동일한 서버로 라우팅한다. L7 로드밸런서에서 쿠키 또는 IP 기반으로 구현한다.

```
[클라이언트A] ---> [로드밸런서] ---> [서버A] (항상)
[클라이언트B] ---> [로드밸런서] ---> [서버B] (항상)
```

| 구분 | 내용                                         |
|------|--------------------------------------------|
| 장점 | 구현이 단순, 기존 세션 코드 변경 불필요                    |
| 단점 | 서버 장애 시 해당 서버의 모든 세션 유실                    |
| 단점 | 트래픽이 특정 서버에 집중될 수 있어 로드밸런싱 효과 반감           |
| 단점 | Auto Scaling 환경에서 서버가 자주 교체되면 세션이 자주 끊김    |
| 단점 | Kubernetes처럼 **Pod가 동적으로 생성/삭제되는 환경**에 부적합 |

#### 3-2. Session Clustering (세션 복제)

서버 간 세션 데이터를 실시간으로 복제한다. Tomcat의 경우 `cluster` 설정으로 활성화할 수 있다.

```
[서버A] <---복제---> [서버B] <---복제---> [서버C]
세션=1               세션=1               세션=1
```

| 구분 | 내용 |
|------|------|
| 장점 | 서버 장애 시 다른 서버에 세션이 보존되어 있어 복원 가능 |
| 단점 | 서버 수 증가에 비례하여 복제 트래픽과 메모리 사용량 증가 |
| 단점 | 대규모 클러스터에서는 복제 지연(Latency) 문제 발생 |
| 단점 | 네트워크 파티션 발생 시 세션 일관성 보장 어려움 |

#### 3-3. External Session Store (외부 세션 저장소)

모든 서버가 공유하는 중앙 세션 저장소(Redis, Memcached)를 사용한다. 가장 일반적으로 채택되는 방식이다.

```
               [로드밸런서]
             /     |      \
        [서버A]   [서버B]   [서버C]
             \     |      /
           [Redis Session Store]
             { SID: 세션데이터 }
```

Spring Session + Redis 설정 예시:
```yaml
# application.yml
spring:
  session:
    store-type: redis
  data:
    redis:
      host: localhost
      port: 6379
```

```java
@EnableRedisHttpSession
@Configuration
public class SessionConfig {
    // Spring Session이 자동으로 HttpSession을 Redis로 위임
}
```

| 구분 | 내용 |
|------|------|
| 장점 | 어떤 서버로 요청이 가도 동일한 세션 조회 가능 |
| 장점 | 서버 장애 시 세션 유실 없음, Auto Scaling에 친화적 |
| 단점 | Redis 자체가 SPOF(Single Point of Failure)가 될 수 있음 (Redis Sentinel/Cluster로 해결) |
| 단점 | 모든 요청마다 Redis 네트워크 I/O 발생 → 지연 시간 추가 (통상 1~2ms) |

**세 가지 전략 비교 요약**

| 전략 | 확장성 | 가용성 | 구현 복잡도 | 클라우드 친화성 |
|------|--------|--------|-------------|----------------|
| Sticky Session | 낮음 | 낮음 | 쉬움 | 나쁨 |
| Session Clustering | 중간 | 중간 | 중간 | 보통 |
| External Store (Redis) | 높음 | 높음 | 중간 | 좋음 |

---

### 4. 토큰 기반 인증 (JWT)

JWT(JSON Web Token)는 서버가 상태를 보관하지 않는 Stateless 방식이다. 인증 정보를 토큰 자체에 포함시켜 클라이언트가 보관한다.

#### JWT 구조

JWT는 `.`으로 구분된 세 부분으로 구성된다: `Header.Payload.Signature`

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9    <- Header (Base64Url)
.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6Ikx1a2UiLCJyb2xlIjoiVVNFUiIsImV4cCI6MTc3MDAwMDAwMH0  <- Payload (Base64Url)
.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c  <- Signature (HMAC/RSA)
```

**Header**
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

**Payload (Claims)**
```json
{
  "sub": "1234567890",       // 사용자 식별자 (registered claim)
  "name": "Luke",            // 사용자 이름 (public claim)
  "role": "USER",            // 권한 (private claim)
  "iat": 1700000000,         // 발급 시간 (issued at)
  "exp": 1700003600          // 만료 시간 (expiration)
}
```

**Signature**
```
HMACSHA256(
  base64UrlEncode(header) + "." + base64UrlEncode(payload),
  secret
)
```

서버는 수신한 토큰의 Header + Payload를 secret key로 다시 서명하여 Signature와 일치하는지 확인한다. 일치하지 않으면 위변조된 토큰으로 판단한다.

#### JWT 인증 흐름

```
[클라이언트]                        [서버 A / B / C]
    |                                   |
    |-- POST /login ------------------->|
    |                                   | 서명하여 JWT 생성
    |<-- { accessToken: "eyJ..." } -----|
    |                                   |
    |  (토큰을 localStorage/쿠키에 저장)    |
    |                                   |
    |-- GET /api/profile -------------->|
    |   Authorization: Bearer eyJ...    | 서명 검증만 수행
    |<-- 200 OK (DB 조회 없이 응답) -------|
```

**핵심 장점: Stateless**
모든 서버가 secret key만 공유하면 어떤 서버도 독립적으로 토큰을 검증할 수 있다. 세션 저장소 조회가 필요 없으므로 수평 확장이 자유롭다.

---

### 5. JWT의 한계와 보완

#### 한계 1: 토큰 즉시 무효화 불가

JWT는 서버에 상태가 없으므로 발급된 토큰은 만료 시간까지 유효하다. 사용자가 로그아웃하거나 계정이 탈취되어도 서버 입장에서 해당 토큰을 막을 방법이 없다.

```
탈취된 토큰이 있어도 만료 전까지 유효
[공격자] -- Authorization: Bearer 탈취토큰 --> [서버] --> 200 OK (!)
```

#### 한계 2: 토큰 크기로 인한 오버헤드

세션 ID는 수십 바이트에 불과하지만, JWT는 Payload가 클수록 수백 바이트에서 킬로바이트 단위로 커진다. 모든 요청의 HTTP 헤더에 포함되므로 네트워크 대역폭이 소비된다.

#### 보완 전략 1: Refresh Token

Access Token의 유효 기간을 짧게(15분~1시간) 설정하고, 장기 유효한 Refresh Token으로 Access Token을 재발급한다.

```
[클라이언트]                             [서버]                     [DB]
    |                                   |                         |
    |-- POST /login ------------------->|                         |
    |<-- { accessToken(15분),            |-- Refresh Token 저장 --->|
    |      refreshToken(7일) } ----------|                         |
    |                                    |                         |
    | (15분 후 Access Token 만료)          |                         |
    |                                    |                         |
    |-- POST /auth/refresh ------------->|                         |
    |   { refreshToken: "..." }          |-- Refresh Token 검증 --->|
    |<-- { newAccessToken(15분),          |<-- 유효 확인 -------------|
    |      newRefreshToken(7일) } --------|-- 기존 토큰 교체 --------->|
```

**Refresh Token Rotation**: Refresh Token도 사용할 때마다 새로 교체한다. 이전 Refresh Token으로 재발급을 시도하면 탈취 가능성으로 판단하여 모든 세션을 무효화한다.

#### 보완 전략 2: Token Blacklist (Redis 활용)

로그아웃 또는 계정 정지 시 해당 토큰의 JTI(JWT ID)를 Redis에 저장하고, 요청마다 블랙리스트를 확인한다.

```java
// 로그아웃 처리
public void logout(String accessToken) {
    Claims claims = jwtParser.parseToken(accessToken);
    long remainingTtl = claims.getExpiration().getTime() - System.currentTimeMillis();

    // 남은 유효시간만큼 Redis에 블랙리스트 등록
    redisTemplate.opsForValue()
        .set("blacklist:" + claims.getId(), "revoked",
             remainingTtl, TimeUnit.MILLISECONDS);
}

// 요청 검증 시
public boolean isBlacklisted(String jti) {
    return redisTemplate.hasKey("blacklist:" + jti);
}
```

> **주의**: 블랙리스트를 도입하는 순간 Stateless의 이점이 희석된다. 모든 요청마다 Redis를 조회해야 하므로 사실상 외부 저장소에 의존하는 구조가 된다.

---

### 6. Session vs Token 비교 분석

| 비교 항목 | Session 기반 | JWT 기반 |
|-----------|-------------|---------|
| 상태 보관 위치 | 서버 (메모리/Redis) | 클라이언트 (localStorage/쿠키) |
| Stateless 여부 | Stateful | Stateless (순수 JWT의 경우) |
| 즉시 무효화 | 가능 (세션 삭제) | 어려움 (Blacklist 필요) |
| 수평 확장 | 외부 저장소 필요 | 자유로움 |
| 네트워크 비용 | 세션 ID만 전송 (소) | 토큰 전체 전송 (중) |
| 서버 부하 | DB/Redis I/O 발생 | 서명 검증 연산 |
| 보안 제어 | 서버가 완전 통제 | 만료 전 제어 어려움 |
| 구현 복잡도 | 단순 (프레임워크 지원) | Refresh Token 관리 복잡 |
| 주 사용 환경 | 전통적 웹 서비스 | REST API, 마이크로서비스 |

**보안 관점**
세션 방식은 서버가 완전히 제어권을 가지므로 보안 사고 발생 시 즉각 대응이 가능하다. 반면 JWT는 만료 전까지 토큰이 유효하므로 탈취 시 피해 시간이 생긴다. 단, JWT는 쿠키의 CSRF 공격에 덜 취약하다(Authorization 헤더 방식 사용 시).

**확장성 관점**
마이크로서비스 아키텍처에서 JWT가 유리하다. 각 서비스가 공개 키(Public Key)만 가지고 있으면 독립적으로 토큰을 검증할 수 있어 Auth 서버에 대한 의존성이 없다.

**성능 관점**
Redis 기반 세션은 요청마다 네트워크 I/O가 발생한다(~1~2ms 추가 지연). JWT는 서버 내 연산만으로 검증되므로 I/O가 없다. 단, 블랙리스트를 사용하면 동일하게 Redis 조회가 필요해진다.

---

### 7. 하이브리드 접근

실무에서는 순수 세션이나 순수 JWT보다 두 방식을 결합한 하이브리드 구조가 많이 사용된다.

#### 패턴: Short-lived JWT + Redis Session

```
[클라이언트]              [API 서버]                       [Redis]
    |                      |                              |
    |-- POST /login ------>|                              |
    |                      |---- Refresh Token 저장 ------>|
    |<---- { accessToken(5분), refreshToken } ------------|
    |                      |                              |
    |-- GET /api/data ---->|                              |
    |   Bearer accessToken |     서명만 검증 (I/O 없음)       |
    |<-- 200 OK -----------|                              |
    |                      |                              |
    | (로그아웃 요청)          |                             |
    |-- POST /logout ------>|                             |
    |                       |-- refreshToken 삭제 -------->|
    |                       |-- accessToken 블랙리스트 등록 ->|
```

- Access Token 유효기간을 매우 짧게(5분) 유지하여 탈취 피해 최소화
- Refresh Token은 Redis에 저장하여 서버 측에서 즉시 폐기 가능
- 일반 API 요청은 I/O 없이 JWT 서명 검증만으로 처리

#### OAuth 2.0에서의 활용 사례

OAuth 2.0의 Authorization Code Flow는 이 하이브리드 접근의 대표적인 예다.

```
사용자 --> [클라이언트 앱] --> [Authorization Server] --> Access Token(JWT) + Refresh Token
                                                      --> Refresh Token은 서버 DB에 저장
```

- Access Token: 짧은 수명의 JWT → 리소스 서버에서 Stateless 검증
- Refresh Token: 긴 수명, 서버 DB 저장 → 폐기 및 갱신 제어 가능

**결론**: 보안 제어가 중요하면 세션 중심, 확장성·마이크로서비스가 우선이면 JWT 중심, 둘 다 중요하면 Short-lived JWT + Redis Refresh Token 하이브리드가 현실적인 선택이다.

---

## 핵심 정리
- 세션 기반 인증은 Stateful 방식으로, 서버가 상태를 보관하므로 즉시 무효화가 가능하지만 다중 서버 환경에서는 세션 공유 전략(Sticky/Clustering/Redis Store)이 필요하다
- 다중 서버 세션 공유의 현실적 정답은 **External Session Store(Redis)**이며, Sticky Session은 클라우드/Auto Scaling 환경에 부적합하다
- JWT는 Stateless로 수평 확장이 자유롭지만, 토큰 즉시 무효화가 불가능하다는 근본적인 한계가 있다
- JWT의 무효화 문제는 **짧은 Access Token 유효기간 + Refresh Token Rotation** 으로 피해 범위를 줄이는 방향으로 보완한다
- Token Blacklist(Redis)는 즉시 무효화를 가능하게 하지만, Stateless 이점을 포기하는 것이므로 트레이드오프를 인식하고 사용해야 한다
- 마이크로서비스에서는 JWT가 유리한데, 각 서비스가 Auth 서버를 호출하지 않고 공개 키로 독립 검증할 수 있기 때문이다
- 실무에서는 **Short-lived JWT(Access) + Redis 저장 Refresh Token** 하이브리드가 보안과 확장성을 균형 있게 달성하는 패턴으로 많이 채택된다

## 키워드
- `Session`: 서버가 사용자 상태를 저장하는 Stateful 인증 방식으로, Session ID를 쿠키로 클라이언트에 전달
- `JWT`: Header·Payload·Signature 세 부분으로 구성되어 사용자 정보를 자체 포함하는 자가 서명 토큰
- `Token`: 클라이언트가 보유하는 인증 자격증명으로, JWT처럼 자체 검증 가능한 형태가 대표적
- `Stateless`: 서버가 클라이언트 상태를 보관하지 않아 모든 요청이 독립적으로 처리되는 아키텍처 특성
- `Sticky Session`: 로드밸런서가 특정 클라이언트를 항상 동일한 서버로 라우팅하는 세션 고정 전략
- `Redis Session Store`: Redis를 공유 세션 저장소로 사용하여 모든 서버가 동일한 세션에 접근하는 방식
- `Refresh Token`: Access Token 만료 후 새 토큰을 발급받기 위한 장기 유효 토큰으로, 서버에 저장하여 폐기 제어 가능
- `Scale-out`: 서버 대수를 늘려 트래픽을 분산하는 수평 확장 전략
- `로드밸런서`: 클라이언트 요청을 여러 서버에 분배하는 장치로, L4(TCP)와 L7(HTTP) 방식이 있음
- `인증(Authentication)`: 사용자가 주장하는 신원이 실제로 맞는지 확인하는 과정 (인가 Authorization과 구별)

## 참고 자료
- [JWT.io - JSON Web Token Introduction](https://www.jwt.io/introduction)
- [Auth0 Docs - JSON Web Token Structure](https://auth0.com/docs/secure/tokens/json-web-tokens/json-web-token-structure)
- [Auth0 Blog - What Are Refresh Tokens and How to Use Them Securely](https://auth0.com/blog/refresh-tokens-what-are-they-and-when-to-use-them/)
- [Logto Blog - Token-based vs Session-based Authentication](https://blog.logto.io/token-based-authentication-vs-session-based-authentication)
- [Stytch Blog - JWTs vs Sessions: Which Authentication Approach is Right for You?](https://stytch.com/blog/jwts-vs-sessions-which-is-right-for-you/)
- [ByteByteGo - Session-based Authentication vs JWT](https://bytebytego.com/guides/whats-the-difference-between-session-based-authentication-and-jwts/)
- [Redis - Session Management](https://redis.io/solutions/session-management/)
- [AWS - Session Management Caching](https://aws.amazon.com/caching/session-management/)
- [Baeldung - Significance of a JWT Refresh Token](https://www.baeldung.com/cs/json-web-token-refresh-token)
