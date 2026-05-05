# SSE(Server Sent Events)와 WebSocket의 차이

## 개요
실시간 메시징 환경에서 자주 사용되는 두 가지 대표 기술인 SSE(Server-Sent Events)와 WebSocket의 동작 방식과 특성을 비교하여, 어떤 상황에서 어떤 기술을 선택해야 하는지에 대한 기준을 정립한다.

## 상세 내용

### 1. SSE(Server-Sent Events)란

SSE는 서버에서 클라이언트로 단방향 데이터를 스트리밍하는 기술이다. 별도의 프로토콜을 사용하지 않고 표준 HTTP 연결을 그대로 활용하며, WHATWG HTML Living Standard의 일부로 명세가 관리된다.

**HTTP/1.1 기반 단방향 스트리밍 프로토콜**

SSE는 HTTP 연결을 닫지 않고 유지하면서 서버가 지속적으로 데이터를 전송하는 방식이다. 서버는 응답 헤더에 `Content-Type: text/event-stream`을 설정하고, 연결이 끊기기 전까지 이벤트 데이터를 계속 전송한다. 클라이언트는 `Cache-Control: no-cache`와 함께 동작하여 중간 캐시가 응답을 가로채지 못하도록 한다.

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

**text/event-stream MIME 타입**

`text/event-stream`은 SSE에서 사용하는 MIME 타입으로, UTF-8 인코딩된 텍스트 스트림을 의미한다. 브라우저는 이 MIME 타입을 받으면 EventSource API를 통해 스트리밍 데이터를 처리할 준비를 한다.

**메시지 포맷 (data, event, id, retry 필드)**

SSE 이벤트는 줄 단위로 구성되며, 이중 개행(`\n\n`)으로 이벤트 경계를 구분한다.

```
: 주석(comment) - 서버가 연결 유지를 위해 주기적으로 전송

id: 1
event: userconnect
data: {"username": "alice", "time": "10:22:00"}

id: 2
data: This is a simple message
data: This line is appended to the same event

retry: 3000
```

| 필드 | 설명 |
|------|------|
| `data` | 이벤트의 실제 데이터. 여러 줄이면 줄바꿈으로 연결 |
| `event` | 커스텀 이벤트 타입. 지정하지 않으면 `message` 이벤트로 처리 |
| `id` | 이벤트 ID. 재연결 시 `Last-Event-ID` 헤더로 서버에 전달 |
| `retry` | 재연결 대기 시간(밀리초). 정수값만 유효 |

**자동 재연결(Reconnect) 메커니즘**

SSE의 중요한 특징 중 하나는 브라우저가 연결이 끊겼을 때 자동으로 재연결을 시도한다는 것이다. 브라우저는 기본적으로 몇 초 후 자동 재연결을 시도하며, `retry` 필드로 대기 시간을 제어할 수 있다. 재연결 시 마지막으로 수신한 이벤트의 `id` 값을 `Last-Event-ID` 요청 헤더로 서버에 전달하여, 서버가 누락된 이벤트를 재전송할 수 있도록 한다.

```
GET /events HTTP/1.1
Host: example.com
Last-Event-ID: 42
```

**EventSource API 클라이언트 구현**

```javascript
const evtSource = new EventSource("/events");

// 기본 메시지 이벤트
evtSource.onmessage = (event) => {
  console.log("data:", event.data);
  console.log("lastEventId:", event.lastEventId);
};

// 커스텀 이벤트 타입 리스닝
evtSource.addEventListener("notification", (event) => {
  const data = JSON.parse(event.data);
  showNotification(data.message);
});

// 에러 처리
evtSource.onerror = (err) => {
  if (evtSource.readyState === EventSource.CLOSED) {
    console.log("Connection closed");
  }
};

// 명시적 연결 종료
evtSource.close();
```

---

### 2. WebSocket이란

WebSocket은 RFC 6455로 표준화된 독립적인 TCP 기반 프로토콜로, 단일 TCP 연결 위에서 서버와 클라이언트가 동시에 메시지를 주고받을 수 있는 양방향(Full-Duplex) 통신을 제공한다. [이전 TIL - WebSocket 동작 원리](./260505_01_WebSocket_동작_원리.md) 참고.

**HTTP Upgrade를 통한 프로토콜 전환 (101 Switching Protocols)**

WebSocket 연결은 HTTP Upgrade 요청으로 시작한다. 클라이언트가 `Upgrade: websocket` 헤더를 포함한 HTTP 요청을 보내면, 서버가 `101 Switching Protocols`로 응답하면서 이후의 통신이 WebSocket 프레임 방식으로 전환된다.

```
// 클라이언트 요청
GET /chat HTTP/1.1
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13

// 서버 응답
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

**ws:// 와 wss:// 스킴**

| 스킴 | 설명 | 기본 포트 |
|------|------|-----------|
| `ws://` | 평문 WebSocket | 80 |
| `wss://` | TLS 암호화 WebSocket | 443 |

HTTP와 동일한 포트를 공유하므로 방화벽 우회가 용이하다. 프로덕션 환경에서는 반드시 `wss://`를 사용해야 한다.

**양방향 풀 듀플렉스 통신**

Handshake 이후에는 서버와 클라이언트 양쪽 모두 언제든 메시지를 먼저 보낼 수 있다. HTTP처럼 요청-응답 사이클을 따르지 않아 실시간 채팅, 게임, 협업 도구와 같이 양방향 상호작용이 빈번한 경우에 적합하다.

**프레임 기반 메시지 전송**

Handshake 이후 모든 데이터는 WebSocket 프레임 단위로 주고받는다. 최소 2바이트의 오버헤드만 필요하여 HTTP 헤더를 반복 전송하는 방식보다 효율적이다. 텍스트(UTF-8)와 바이너리 데이터를 모두 전송할 수 있다.

---

### 3. SSE와 WebSocket의 핵심 차이

**통신 방향성 (단방향 vs 양방향)**

SSE는 서버에서 클라이언트로만 데이터를 전송하는 단방향 프로토콜이다. 클라이언트가 서버로 데이터를 보내야 하는 경우, 별도의 HTTP 요청(fetch, XMLHttpRequest)을 사용해야 한다. WebSocket은 Handshake 이후 서버와 클라이언트 양쪽이 자유롭게 메시지를 주고받는 완전한 양방향 통신이 가능하다.

**기반 프로토콜 (HTTP vs TCP 위 별도 프로토콜)**

SSE는 표준 HTTP 위에서 동작한다. HTTP/1.1에서는 단순한 long-lived HTTP 응답이며, HTTP/2에서는 멀티플렉싱 스트림 위에서 동작한다. 기존 HTTP 인프라(프록시, 로드밸런서, CDN)와 완벽하게 호환된다.

WebSocket은 HTTP Upgrade를 통해 시작하지만, 이후에는 HTTP와는 완전히 다른 독립적인 TCP 기반 프로토콜을 사용한다. 일부 오래된 프록시나 방화벽이 WebSocket 트래픽을 차단하거나 제대로 처리하지 못할 수 있다.

**데이터 포맷 (텍스트 전용 vs 텍스트/바이너리)**

SSE는 UTF-8 텍스트 데이터만 전송할 수 있다. 바이너리 데이터가 필요하면 Base64로 인코딩하여 텍스트로 전송해야 한다. WebSocket은 텍스트(0x1)와 바이너리(0x2) 프레임을 모두 지원하여 이미지, 파일, 미디어 스트림도 효율적으로 전송할 수 있다.

**자동 재연결 지원 여부**

SSE의 EventSource API는 연결이 끊기면 브라우저가 자동으로 재연결을 시도하는 기능이 기본 내장되어 있다. `Last-Event-ID`를 통한 이벤트 재전송 요청까지 자동으로 처리된다. WebSocket은 연결 끊김 감지(Ping/Pong)와 재연결 로직을 모두 직접 구현해야 한다.

**방화벽 및 프록시 친화성**

SSE는 일반 HTTP 트래픽이므로 방화벽 통과가 쉽고, CORS 설정만으로 크로스 도메인 접근이 가능하다. WebSocket은 초기 Upgrade 요청에서 프록시가 헤더를 제거하거나, CONNECT 터널을 사용하는 방식에서 문제가 발생할 수 있다. 이를 해결하려면 `wss://`(443 포트)를 사용하거나 Socket.IO와 같은 폴백 라이브러리를 활용한다.

**헤더 오버헤드와 효율성**

SSE는 초기 HTTP 연결 이후 각 이벤트마다 `data:`, `event:` 등의 텍스트 필드가 붙어 약 5~10바이트의 오버헤드가 존재한다. WebSocket 프레임은 최소 2바이트 오버헤드로 매우 경량이어서, 초당 수백~수천 건의 메시지가 오가는 고주파 시스템에서 WebSocket이 더 유리하다.

---

### 4. 비교 표

| 항목 | SSE | WebSocket |
|------|-----|-----------|
| 통신 방향 | 서버 → 클라이언트 (단방향) | 양방향 (Full-Duplex) |
| 프로토콜 | HTTP | WebSocket(ws/wss) - TCP 기반 독립 프로토콜 |
| 데이터 타입 | 텍스트(UTF-8)만 | 텍스트 + 바이너리 |
| 자동 재연결 | 기본 내장 (EventSource) | 직접 구현 필요 |
| 브라우저 API | `EventSource` | `WebSocket` |
| 방화벽/프록시 | HTTP 트래픽이라 친화적 | Upgrade 처리 여부에 의존 |
| 프레임 오버헤드 | 약 5~10바이트/이벤트 | 최소 2바이트/프레임 |
| HTTP/2 지원 | 멀티플렉싱으로 연결 수 제한 해소 | 별도 연결 유지 |
| 크로스 도메인 | CORS 헤더로 처리 | CORS 미적용 (Origin 헤더로 별도 인증) |
| 구현 복잡도 | 낮음 | 높음 |

---

### 5. 사용 사례 비교

**SSE가 적합한 경우**

서버에서 클라이언트로 데이터를 일방적으로 밀어넣으면 되는 상황에서 SSE가 적합하다. 클라이언트의 실시간 응답이 필요하지 않고, 단순히 최신 데이터를 수신하면 되는 경우다.

- **실시간 알림**: 배달 상태, 주문 처리 진행, 소셜 미디어 알림
- **주식/암호화폐 시세 피드**: 가격 변동 데이터를 클라이언트에 지속 스트리밍
- **진행률 표시**: 파일 업로드, 배치 작업, 빌드 진행 상황
- **AI 토큰 스트리밍**: ChatGPT, Claude와 같은 LLM 서비스가 토큰을 생성하는 즉시 클라이언트로 전송

특히 AI 스트리밍은 SSE의 대표적인 현대적 활용 사례다. LLM은 토큰을 하나씩 생성하는 특성상, 전체 응답이 완성될 때까지 기다리지 않고 생성되는 즉시 클라이언트로 보내야 응답성이 좋다. OpenAI, Anthropic, Google 등 주요 AI API가 모두 SSE를 사용하여 스트리밍 응답을 제공한다.

```javascript
// OpenAI Streaming API - SSE 활용 예시
const evtSource = new EventSource("/api/chat");

evtSource.onmessage = (event) => {
  if (event.data === "[DONE]") {
    evtSource.close();
    return;
  }
  const { choices } = JSON.parse(event.data);
  appendToken(choices[0].delta.content);
};
```

**WebSocket이 적합한 경우**

클라이언트도 서버로 빈번하게 데이터를 보내야 하거나, 양방향 실시간 상호작용이 핵심인 경우에 WebSocket이 적합하다.

- **실시간 채팅**: 사용자 간 메시지 교환 - 클라이언트도 메시지를 서버로 전송
- **멀티플레이어 게임**: 플레이어 입력, 위치, 게임 상태를 밀리초 단위로 양방향 동기화
- **협업 도구 (Google Docs, Figma)**: 여러 사용자의 편집 이벤트 실시간 동기화
- **화상회의 시그널링**: WebRTC 연결 수립을 위한 SDP/ICE 교환

---

### 6. 성능 및 확장성 고려사항

**HTTP/2 환경에서 SSE의 멀티플렉싱 이점**

HTTP/1.1에서는 브라우저가 도메인당 최대 6개의 연결만 허용한다. SSE 연결이 하나라면 남은 5개의 연결로 다른 HTTP 요청을 처리해야 하므로, 여러 탭에서 SSE를 동시에 사용하면 연결 부족 문제가 생긴다.

HTTP/2에서는 하나의 TCP 연결에서 여러 스트림을 동시에 처리하는 멀티플렉싱을 지원한다. 기본적으로 100~200개의 동시 스트림을 단일 연결에서 처리할 수 있어, 연결 수 제한 문제가 사실상 해소된다.

```
HTTP/1.1:
  브라우저 ─── Connection 1 (SSE) ──→ Server
  브라우저 ─── Connection 2 (GET) ──→ Server
  ...최대 6개

HTTP/2:
  브라우저 ─── 단일 Connection ─── Stream 1 (SSE) ──→ Server
                              │── Stream 2 (GET) ──→ Server
                              │── Stream 3 (SSE) ──→ Server
                              └── ... (기본 100개 스트림)
```

**WebSocket의 커넥션 유지 비용**

WebSocket은 연결이 수립된 이후 계속 TCP 소켓을 점유한다. 동시 접속자가 10만 명이라면 서버에 10만 개의 커넥션이 유지된다. 이를 처리하기 위해 Netty, Vert.x와 같은 Non-blocking 이벤트 루프 기반 서버를 사용하거나 수평 확장이 필요하다.

**로드밸런서 및 스티키 세션 이슈**

SSE와 WebSocket 모두 장기 연결(long-lived connection)이기 때문에 로드밸런서에서 일반 HTTP 요청과 다르게 처리해야 한다. 라운드 로빈 방식의 로드밸런서는 같은 클라이언트 요청을 매번 다른 서버로 라우팅할 수 있어, 메시지 브로드캐스트 시 모든 서버에 전달되지 않을 수 있다.

해결 방법:
- **Sticky Session(세션 고정)**: 클라이언트 IP 또는 쿠키를 기반으로 항상 같은 서버로 라우팅
- **Redis Pub/Sub**: 모든 서버 인스턴스가 Redis 채널을 구독하여 메시지 브로드캐스트
- **Kafka**: 고처리량이 필요한 경우 메시지 브로커를 활용한 이벤트 스트리밍

**동시 연결 수 제한 (브라우저당 HTTP 연결 제한)**

HTTP/1.1 기준으로 브라우저는 동일 도메인에 대해 최대 6개의 동시 연결을 허용한다. SSE는 이 연결 중 하나를 지속적으로 점유하므로, HTTP/2를 사용하지 않는 환경에서는 SSE를 도메인 분리 또는 HTTP/2 적용을 통해 해결해야 한다.

---

## 핵심 정리
- SSE는 서버에서 클라이언트로의 단방향 스트리밍이 필요할 때 가장 적합한 선택지이다.
- WebSocket은 양방향 실시간 통신이 필요한 경우에 사용한다.
- SSE는 HTTP 기반이라 인프라 호환성이 좋고 자동 재연결을 기본으로 제공한다.
- WebSocket은 더 낮은 오버헤드와 바이너리 전송이 가능하지만 직접 관리해야 할 부분이 많다.
- AI 토큰 스트리밍(ChatGPT, Claude 등)은 SSE의 대표적인 현대적 활용 사례다.
- HTTP/2 환경에서는 SSE의 연결 수 제한 문제가 멀티플렉싱으로 해소된다.
- 기술 선택 기준: "양방향 통신이 명확히 필요하면 WebSocket, 그 외에는 SSE로 시작하라"

## 기술적 한계와 보완 전략
- SSE의 단방향 한계 → 클라이언트→서버 통신은 별도 HTTP 요청(fetch/POST)으로 보완
- 브라우저당 HTTP 연결 수 제한(6개) → HTTP/2 멀티플렉싱 활용 또는 도메인 분리
- WebSocket 프록시/방화벽 차단 이슈 → `wss://`(443 포트) 사용 또는 Socket.IO 폴백 활용
- WebSocket 자동 재연결 부재 → Heartbeat(Ping/Pong) + Exponential Backoff 재연결 로직 구현
- 로드밸런서 환경에서의 세션 분산 → Redis Pub/Sub, Kafka 등 메시지 브로커로 해결
- SSE 텍스트 전용 제한 → 바이너리 데이터는 Base64 인코딩 또는 WebSocket으로 대체

## 키워드

**SSE (Server-Sent Events)**
서버에서 클라이언트로 단방향 데이터 스트리밍을 제공하는 기술. HTTP 연결을 유지하면서 `text/event-stream` MIME 타입으로 이벤트를 지속 전송한다. WHATWG HTML Living Standard의 일부로 표준화되어 있다.

**WebSocket**
HTTP Upgrade를 거쳐 단일 TCP 연결 위에서 양방향 통신을 제공하는 프로토콜. RFC 6455로 표준화되었으며, 서버와 클라이언트 모두 언제든 메시지를 먼저 전송할 수 있다.

**EventSource API**
브라우저에 내장된 SSE 클라이언트 인터페이스. `new EventSource(url)`로 연결을 수립하고, `onmessage`, `addEventListener`로 이벤트를 수신한다. 연결 끊김 시 자동 재연결 기능이 기본으로 내장되어 있다.

**HTTP Streaming**
HTTP 응답을 한 번에 반환하지 않고 연결을 열어두면서 데이터를 지속적으로 전송하는 방식. SSE가 이 방식을 활용하며, `Transfer-Encoding: chunked`와 함께 사용되기도 한다.

**Full Duplex**
통신 채널의 양쪽이 동시에 독립적으로 데이터를 송수신할 수 있는 방식. WebSocket이 단일 TCP 연결에서 Full-Duplex 통신을 제공한다.

**HTTP Upgrade**
HTTP 연결을 다른 프로토콜로 전환하는 메커니즘. WebSocket 연결 수립 시 `Upgrade: websocket` 헤더와 함께 사용하며, 서버가 `101 Switching Protocols`로 응답하면 전환이 완료된다.

**text/event-stream**
SSE에서 사용하는 MIME 타입. 서버는 이 Content-Type으로 응답하여 브라우저에게 SSE 스트리밍임을 알린다. UTF-8 텍스트 스트림으로, `data:`, `event:`, `id:`, `retry:` 필드로 이벤트를 구성한다.

**양방향 통신**
서버와 클라이언트가 서로 능동적으로 메시지를 주고받는 통신 방식. WebSocket이 대표적이며, 채팅, 게임, 협업 도구 등에서 활용된다. SSE는 단방향이므로 클라이언트→서버 통신은 별도 HTTP 요청이 필요하다.

**HTTP/2 Multiplexing**
HTTP/2에서 하나의 TCP 연결 위에 여러 스트림을 동시에 처리하는 기능. SSE를 HTTP/2로 사용하면 단일 연결에서 기본 100~200개의 SSE 스트림을 동시에 처리할 수 있어, HTTP/1.1의 도메인당 6개 연결 제한 문제가 해소된다.

**자동 재연결(Reconnect)**
SSE EventSource API가 제공하는 기본 기능. 서버와의 연결이 끊기면 브라우저가 자동으로 재연결을 시도한다. `retry` 필드로 재연결 대기 시간을 설정하고, `Last-Event-ID` 헤더로 마지막 수신 이벤트 이후의 데이터를 요청한다.

## 참고 자료
- [Using server-sent events - MDN Web Docs](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)
- [EventSource - MDN Web Docs](https://developer.mozilla.org/en-US/docs/Web/API/EventSource)
- [Server-sent events - WHATWG HTML Living Standard](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [WebSocket vs SSE: Which One Should You Use? - WebSocket.org](https://websocket.org/comparisons/sse/)
- [WebSockets vs Server-Sent Events (SSE) - Ably](https://ably.com/blog/websockets-vs-sse)
- [Understanding Server-Sent Events and Why HTTP/2 Matters - DEV Community](https://dev.to/abhivyaktii/understanding-server-sent-events-sse-and-why-http2-matters-1cj7)
- [How ChatGPT Uses Server-Sent Events to Stream Real-Time Conversation - DEV Community](https://dev.to/rohitdhas/how-chatgpt-uses-server-sent-events-to-stream-real-time-conversation-3976)
- [WebSockets vs SSE vs Long-Polling vs WebRTC vs WebTransport - RxDB](https://rxdb.info/articles/websockets-sse-polling-webrtc-webtransport.html)
