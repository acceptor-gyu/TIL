# `permitAll`은 잘못된 토큰을 익명으로 보지 않는다 — 공개 API가 만료 토큰에 401을 내던 이유

홈 화면은 로그인 없이 보여야 해서 `SecurityConfig`에 진작 열어 두었다. 그런데 **낡은 액세스 토큰을 들고 있는 앱이 그 홈에서 401을 받았다.**

증상만 보면 "비회원 공개가 안 된다"로 읽힌다. 실제로는 반대였다 — **비회원은 멀쩡했고, 토큰이 만료된 기존 회원만 깨졌다.** 로그아웃한 적 없는 사용자가 앱을 오랜만에 켜면 첫 화면이 비는 상황이다. 재현 조건이 "토큰이 있되 만료된 상태"라 평소 테스트로는 잘 드러나지 않는다.

원인은 Spring Security 필터 체인의 순서였고, 고치는 과정에서 **범위를 좁게 잡은 대가로 구멍 다섯 개**가 더 나왔다. 그 과정을 적는다.

> 사내 저장소 기준이라 프로젝트명·경로 같은 식별자는 일반화해서 적는다.

---

## 1. `permitAll`이 보장하는 것의 범위

`permitAll`은 **인가 규칙**이다. 즉 "이 요청에 어떤 권한이 필요한가"에 대한 답이고, 그 판정은 `AuthorizationFilter`에서 일어난다.

문제는 그 앞에 인증 필터가 있다는 것이다.

```text
SecurityContextHolderFilter
CorsFilter / CsrfFilter
  ⋮
BearerTokenAuthenticationFilter      ← ① 토큰이 있으면 여기서 해독한다
  ⋮
AnonymousAuthenticationFilter        ← ② 인증이 비어 있으면 익명을 채운다
ExceptionTranslationFilter           ← ③ 인가 실패를 401/403으로 번역
AuthorizationFilter                  ← ④ permitAll / hasRole 판정
```

`permitAll`은 ④의 규칙인데, 토큰이 붙어 있으면 ①에서 이미 끝나 버린다. 정확히 말하면 이렇다.

| 요청 | ①에서 벌어지는 일 | 결과 |
| --- | --- | --- |
| 헤더 없음 | 토큰이 없으니 체인을 그냥 잇는다 | ②가 익명을 채우고 ④가 통과 → **200** |
| 유효한 토큰 | 인증 성공, 컨텍스트에 채움 | ④가 통과 → **200** |
| **만료된 토큰** | 해독 실패 → 컨텍스트 비우고 **진입점 호출 후 return** | ④에 **도달하지 못함** → **401** |

즉 `permitAll`은 **"토큰이 없어도 된다"**는 뜻이지 **"잘못된 토큰을 없는 것으로 본다"**는 뜻이 아니다. 두 문장이 같다고 읽은 것이 이 버그의 전부다.

`BearerTokenAuthenticationFilter.doFilterInternal`의 구조를 보면 명확하다.

```java
Authentication authenticationRequest = this.authenticationConverter.convert(request);
if (authenticationRequest == null) {
    filterChain.doFilter(request, response);   // ← 토큰 없음: 체인이 이어진다
    return;
}
try {
    ... authenticate ...
    filterChain.doFilter(request, response);
} catch (AuthenticationException failed) {
    this.securityContextHolderStrategy.clearContext();
    this.authenticationFailureHandler.onAuthenticationFailure(request, response, failed);
    //  ↑ 체인을 잇지 않는다. 여기서 응답이 끝난다
}
```

**체인이 이어지는 유일한 실패 경로는 "토큰이 없음"이다.** 고칠 자리가 여기서 정해진다.

---

## 2. 고칠 수 있는 자리 세 곳

| 후보 | 왜 안 되나 / 되나 |
| --- | --- |
| `AuthenticationFailureHandler` | 시그니처에 `FilterChain`이 없다. 실패를 다르게 처리할 수는 있어도 **체인을 이어받을 수 없다** |
| `AuthenticationEntryPoint` | 응답을 커밋하는 자리다. 여기서 "그냥 통과"를 표현할 방법이 없다 |
| **`BearerTokenResolver`** | **`null`을 돌려주면 필터가 "토큰 없음"으로 보고 체인을 잇는다.** 위 코드의 첫 분기가 그것이다 |

세 번째가 유일한 자리다. 게다가 `oauth2ResourceServer { bearerTokenResolver = ... }`로 공식 확장 지점이 열려 있다.

```kotlin
class OptionalPublicBearerTokenResolver(
    private val decoder: JwtDecoder,
) : BearerTokenResolver {
    private val delegate = DefaultBearerTokenResolver()

    override fun resolve(request: HttpServletRequest): String? {
        if (!PUBLIC_REQUESTS.matches(request)) {
            return delegate.resolve(request)      // 보호된 경로: 그대로 넘긴다
        }
        val token = try {
            delegate.resolve(request)
        } catch (malformed: OAuth2AuthenticationException) {
            null                                   // 헤더 규격 위반도 "없음"으로
        } ?: return null

        return try {
            decoder.decode(token)
            token
        } catch (invalid: JwtException) {
            null                                   // 만료·서명·구조·token_use 불일치
        }
    }
}
```

여기서 토큰을 한 번 더 해독하므로 유효한 토큰은 **두 번 해독된다**(리졸버에서 한 번, 인증 제공자가 한 번). 로컬 비밀키 HMAC 검증이라 DB 조회 한 번보다 훨씬 싸서 그대로 뒀다. 해독 결과를 요청 속성에 실어 나르면 아낄 수 있지만, 그러려면 리졸버와 디코더가 서로를 알아야 한다.

---

## 3. 어디까지 적용할 것인가 — 이게 진짜 결정이었다

구현은 20줄인데, **범위를 정하는 것이 유일한 설계 판단**이었다.

### 안 A. 모든 요청에서 잘못된 토큰을 버린다

공개 경로 목록이 아예 필요 없다. 토큰을 버리면 익명이 되고, 보호된 경로는 인가 규칙이 그대로 막는다. 목록을 두 곳에 유지할 일이 없으니 어긋날 자리도 없다.

**대가가 하나 있다.** 보호된 경로에서 만료 토큰을 보냈을 때 응답이 미묘하게 바뀐다.

```
기존:  401  WWW-Authenticate: Bearer error="invalid_token", error_description="Jwt expired at ..."
변경:  401  WWW-Authenticate: Bearer
```

401을 만드는 주체가 리소스 서버 필터에서 `ExceptionTranslationFilter`로 바뀌면서, 예외가 "토큰이 잘못됨"이 아니라 "인증이 없음"이 되기 때문이다. 이 값은 **앱이 "토큰을 새로 받으면 된다"와 "다시 로그인해야 한다"를 가르는 근거**라 잃으면 안 된다.

되살리는 방법은 있다. 버린 이유를 요청 속성에 적어 두고 `AuthenticationEntryPoint`에서 꺼내 원래 오류로 되돌리는 것이다. 실제로 처음엔 그렇게 만들었다 — 리졸버와 진입점을 한 클래스에 두어 짝이라는 것을 드러냈다.

### 안 B. 인증이 필요 없는 경로에서만 버린다 — 채택

보호된 경로는 토큰을 그대로 넘기므로 **응답이 한 글자도 바뀌지 않는다.** 진입점을 건드릴 필요가 없다.

대가는 공개 경로 목록이 `SecurityConfig`와 리졸버 두 곳에 생긴다는 것이다.

### 왜 B인가

둘 다 대가가 있고, 크기가 아니라 **누가 계속 지불하는가**가 달랐다.

A의 대가는 "인증 관련 코드를 고칠 때마다 두 클래스를 짝으로 봐야 한다"는 지속 비용이다. 리졸버만 고치고 진입점을 빠뜨리면 진단 정보가 **조용히** 사라진다 — 테스트가 없으면 아무도 모른다.

B의 대가는 "공개 경로를 더할 때 두 곳을 고쳐야 한다"는 일회성 비용이고, **테스트로 강제할 수 있다.** 빠뜨리면 빨간불이 뜨게 만들 수 있다.

지속적이고 조용한 실패보다 일회적이고 시끄러운 실패를 골랐다.

---

## 4. 범위를 좁혔더니 구멍이 다섯 개 나왔다

B를 고르고 나서 "공개 조회"만 열었다. `GET` 메서드에 목록·상세 경로들. 그리고 목록 대조 테스트를 쓰자마자 다섯 개가 나왔다.

| 막히던 경로 | 영향 |
| --- | --- |
| **`POST /auth/token/refresh`** | **토큰을 고치러 온 요청이 낡은 토큰 때문에 막힌다** |
| `/assets/home-default-banner.svg` | 배너 0건일 때 홈이 가리키는 자산. 이미지 한 장이 401이면 첫 화면이 깨져 보인다 |
| `/terms`, `/terms/**` | 로그인 화면의 이용약관 링크 |
| `/auth/check/nickname` | 가입 중 닉네임 중복 확인 |
| `/actuator/health` | 헬스체크가 헤더를 달고 오면 막힌다 |

첫 번째가 압도적으로 나쁘다. 앱이 만료 토큰을 감지하고 재발급을 시도하는데, 그 요청에 여전히 옛 토큰이 헤더에 달려 있으면 **고치러 온 요청이 고쳐야 할 것 때문에 막힌다.** 앱을 지우고 다시 깔기 전에는 빠져나올 수 없는 루프다.

원인은 명확하다. 규칙은 **"인증이 필요 없는 경로"**였는데 구현이 **"공개 GET 조회"**로 번역됐다. 그 차이만큼이 구멍이었다.

`permitAll`은 조회만 있는 집합이 아니다. 가입·약관·닉네임 확인·토큰 재발급이 전부 거기 있다. 그래서 리졸버가 여는 집합을 `permitAll` **전체**와 같게 맞췄다. 안전성은 자명하다 — **인증이 필요 없다고 이미 선언한 경로에서 잘못된 토큰을 무시해도 새로 열리는 것이 없다.** 토큰이 없는 요청이 이미 통과하는 경로다.

---

## 5. 어긋남을 드러나게 만들기

목록이 둘이라는 사실은 없앨 수 없으니, 어긋났을 때 보이게 만드는 데 집중했다.

**첫째, 같은 문법으로 적는다.** 원래 리졸버는 경로를 정규식으로 들고 있었다.

```kotlin
"/api/v1/joins", "/api/v1/joins/([0-9]+|application-notice|map)"
```

인가 규칙 쪽은 이렇다.

```kotlin
authorize(HttpMethod.GET, "${JoinPostController.BASE_PATH}/*", permitAll)
```

**두 줄을 나란히 놓아도 다른지 알기 어렵다.** `/*`는 한 칸 와일드카드고 `([0-9]+|...)`는 열거인데, 새 경로가 생기면 앞은 자동으로 따라오고 뒤는 안 따라온다. 그래서 리졸버도 인가 규칙과 같은 `PathPatternRequestMatcher`로 바꿨다.

```kotlin
private val PUBLIC_REQUESTS = OrRequestMatcher(
    pathPattern("/api/v1/auth/token/refresh"),
    pathPattern(HttpMethod.GET, "${JoinPostController.BASE_PATH}/*"),
    ...
)
```

이제 두 목록이 같은 문법이라 diff로 비교된다.

**둘째, 목록을 훑는 테스트를 둔다.**

```kotlin
val blocked = publicPaths.filter { path ->
    getWithToken(path, expiredToken).andReturn().response.status == 401
}
assertThat(blocked).isEmpty()
```

이 테스트는 **200을 요구하지 않는다.** 확인하려는 것은 "토큰 때문에 막히지 않는다"이고, 데이터가 없어 404가 나는 것은 이 규칙과 무관하다. 200을 요구하면 그 경로의 다른 사정이 바뀔 때마다 같이 깨져서, 정작 토큰 규칙이 깨졌을 때의 신호가 묻힌다.

---

## 6. 테스트를 믿기 전에 실패시켰다

새 테스트가 통과했을 때 바로 믿지 않았다. **리졸버만 고치기 전 버전으로 되돌려 같은 테스트를 돌렸다.**

```bash
git show <이전-커밋>:.../OptionalPublicBearerTokenResolver.kt > .../OptionalPublicBearerTokenResolver.kt
./gradlew :api:test --tests "*.PublicEndpointTokenIntegrationTest"
```

기대한 2개가 정확히 실패했고, 실패 메시지가 막히는 경로를 그대로 뱉었다.

```
Expecting empty but was: ["/api/v1/auth/check/nickname?...",
    "/api/v1/terms", "/api/v1/terms/SERVICE",
    "/actuator/health", "/assets/home-default-banner.svg"]
```

**통과하는 테스트는 아무것도 증명하지 않는다.** 깨질 때 깨지는 것을 봐야 그 테스트가 무엇을 지키는지 알 수 있다.

### 왜 단위 테스트가 아니라 통합 테스트인가

이 변경은 **필터 체인의 순서가 전부**다. 리졸버 단위 테스트는 "잘못된 토큰에 null을 돌려준다"까지만 말한다. 정작 지켜야 하는 것은 그 다음의 사슬이다.

```
리졸버가 null →  필터가 체인을 잇는다
              →  AnonymousAuthenticationFilter가 익명을 채운다
              →  AuthorizationFilter가 permitAll을 통과시킨다
              →  컨트롤러의 `Jwt?`에 null이 들어온다
```

이 중 **어디가 끊어져도 화면은 똑같이 401을 받는데, 리졸버 단위 테스트는 넷 다 통과한 것처럼 보인다.** 그래서 MockMvc로 실제 HTTP를 부르고 응답 본문을 증거로 삼았다. 200을 받았다는 것만으로는 **비회원으로** 읽혔다는 증거가 안 된다 — 서버가 만료 토큰의 `sub`를 신원으로 재사용해도 200이 나온다. `showLoginPrompt`가 참인지, 찜·즐겨찾기 같은 개인화 값이 꺼졌는지가 그것을 가른다.

반대편도 함께 잡았다. 토큰을 버린 요청이 익명이 되므로, 인가가 익명을 막지 못하면 보호 경로가 열린다. 확인 경로로는 공개 목록과 **한 칸 차이로 붙어 있는** 개인 내역 경로를 골랐다. 잘못 열리면 남의 신청 목록이 나가는 자리다.

`WWW-Authenticate`도 양방향으로 본다. 만료 토큰의 401에는 `invalid_token`이 남아야 하고, 토큰을 아예 안 보낸 401에는 없어야 한다. 앞쪽만 검사하면 **모든 401에 `invalid_token`을 붙이는 구현도 통과한다.**

---

## 느낀 점

**"열려 있다"는 상태가 아니라 조건부 서술이다.** `permitAll` 한 줄을 보고 "이 경로는 열렸다"고 읽었는데, 정확히는 "권한 검사를 통과한다"였다. 그 앞의 인증 단계는 별개의 관문이고, 두 관문 사이에 "토큰이 있지만 틀렸다"는 상태가 끼어 있었다. 설정 한 줄이 보장하는 범위를 문장으로 적어 보지 않으면 이 틈이 안 보인다.

**규칙을 구현으로 번역할 때 좁아진 만큼이 버그가 된다.** 규칙은 "인증이 필요 없는 경로"였고 구현은 "공개 GET 조회"였다. 둘의 차집합이 정확히 구멍 다섯 개였다. 번역이 일어난 자리를 의식하고 **원래 문장을 코드 옆에 남겨 두는 것**이 대가가 작다.

**대가를 크기가 아니라 지불 주체로 비교하면 결정이 쉬워진다.** 두 안 모두 대가가 있었는데, 한쪽은 "앞으로 계속, 조용히" 지불하고 다른 쪽은 "한 번, 시끄럽게" 지불한다. 크기만 재면 비슷해 보여서 결정이 안 난다.

**같은 사실이 두 곳에 있는 걸 없앨 수 없으면, 어긋났을 때 보이게 만든다.** 문법을 통일해 diff로 보이게 하고, 훑는 테스트로 빨간불이 뜨게 했다. 근본 해결은 아니지만 "조용한 실패"를 "시끄러운 실패"로 바꾼 것만으로 성격이 달라진다.

**회복 경로는 따로 검사해야 한다.** 재발급 엔드포인트가 막힌 건 일반적인 "401이 난다"와 심각도가 다르다. **고장을 고치는 경로가 그 고장에 의존하면 사용자가 빠져나올 방법이 없다.** 인증·복구·재시도처럼 "문제 상황에서 호출되는" 경로는 정상 경로와 같은 기준으로 검사하면 안 된다.

---

## 정리

- `permitAll`은 **인가** 규칙이고 `AuthorizationFilter`에서 판정된다. 인증 필터가 그보다 **먼저** 돌기 때문에 잘못된 토큰은 인가에 닿지 못한다
- `BearerTokenAuthenticationFilter`에서 **체인이 이어지는 유일한 실패 경로는 "토큰 없음"**이다. 그래서 고칠 자리가 `BearerTokenResolver`로 정해진다 — 실패 핸들러는 체인을 못 잇고 진입점은 응답을 커밋한다
- 전역 적용은 목록이 필요 없는 대신 `WWW-Authenticate`의 `invalid_token`을 잃는다. 되살리려면 `AuthenticationEntryPoint`까지 **짝으로** 유지해야 한다
- 공개 경로 한정은 보호 경로 응답을 그대로 두는 대신 **목록이 두 곳**에 생긴다. 지속적·조용한 비용보다 일회적·시끄러운 비용을 골랐다
- 여는 집합을 "공개 GET 조회"로 좁히면 **약관·닉네임 확인·기본 배너 자산·헬스체크·토큰 재발급**이 빠진다. `permitAll` 전체와 같게 맞춰야 한다
- **재발급 경로가 막히면 회복 불가 루프가 된다.** 고장을 고치는 경로는 그 고장에 의존하면 안 된다
- 목록이 둘이면 **같은 패턴 문법으로 적어** diff로 비교되게 하고, 훑는 테스트로 어긋남에 빨간불을 붙인다
- 그 테스트는 **401이 아닌지만** 본다. 200을 요구하면 무관한 변경에 같이 깨져서 신호가 묻힌다
- **테스트를 믿기 전에 실패시킨다.** 고치기 전 버전으로 되돌려 기대한 만큼 빨간불이 뜨는지 확인한다
- 필터 체인처럼 **사슬 전체가 규칙인 변경**은 단위 테스트로 덮이지 않는다. 사슬의 어느 고리가 끊겨도 증상이 같기 때문이다

## 참고

- [OAuth 2.0 Resource Server JWT — Spring Security Reference](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Bearer Token Resolution — Spring Security Reference](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/bearer-tokens.html)
- [Architecture: Filter chain / ExceptionTranslationFilter — Spring Security Reference](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- [Authorize HTTP Requests — Spring Security Reference](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
- [RFC 6750 — The OAuth 2.0 Authorization Framework: Bearer Token Usage (§3 `WWW-Authenticate`)](https://datatracker.ietf.org/doc/html/rfc6750#section-3)
- [RFC 9110 — HTTP Semantics (§11 Authentication)](https://datatracker.ietf.org/doc/html/rfc9110#section-11)
