# WebSocket 동작 원리

## 개요
WebSocket은 클라이언트와 서버 간 양방향(Full-Duplex) 통신을 제공하는 프로토콜로, HTTP의 요청-응답 모델의 한계를 극복하기 위해 등장했다. 단일 TCP 연결을 통해 지속적인 메시지 교환이 가능하여 실시간 통신이 필요한 애플리케이션에서 핵심적으로 활용된다.

## 상세 내용

### 1. WebSocket이 등장한 배경

HTTP는 기본적으로 클라이언트가 요청을 보내야만 서버가 응답하는 구조다. 이 모델은 정적인 웹 페이지 제공에는 충분하지만, 실시간성이 필요한 애플리케이션에서는 여러 한계를 드러낸다.

**Polling**
- 클라이언트가 일정 간격으로 서버에 반복 요청을 보내는 방식
- 서버에 새 데이터가 없어도 요청이 발생하므로 불필요한 트래픽이 발생
- 실시간성이 떨어지고 서버 리소스 낭비가 심하다

**Long Polling**
- 서버가 새 데이터가 생길 때까지 응답을 보류하는 방식
- Polling보다 효율적이지만, 응답 후 즉시 다시 연결해야 하므로 여전히 연결 오버헤드 존재
- 서버에서 클라이언트로 데이터를 먼저 보낼 수 없는 구조

**SSE (Server-Sent Events)**
- 서버가 클라이언트로 단방향으로 이벤트를 스트리밍하는 방식
- HTTP/1.1에서는 브라우저가 도메인당 6개 연결로 제한됨 (HTTP/2에서는 멀티플렉싱으로 해소)
- 클라이언트가 서버로 메시지를 보낼 수 없어 완전한 양방향 통신 불가

**WebSocket이 해결한 것**
- 단일 TCP 연결로 서버-클라이언트 간 완전한 양방향 통신
- 연결이 수립된 후에는 요청 없이도 서버가 능동적으로 데이터 푸시 가능
- 프레임 기반의 경량 통신으로 HTTP 헤더 오버헤드 제거 (프레임당 최소 2바이트)

### 2. WebSocket 프로토콜 스펙

WebSocket은 2011년 RFC 6455로 표준화된 독립적인 TCP 기반 프로토콜이다.

- **표준**: [RFC 6455](https://datatracker.ietf.org/doc/html/rfc6455)
- **스킴**: `ws://` (평문), `wss://` (TLS 암호화)
- **포트**: ws는 기본 80, wss는 기본 443 (HTTP/HTTPS와 동일 포트 공유)
- **계층**: TCP 위에서 동작하는 응용 계층 프로토콜이며, HTTP와는 독립적

HTTP와 동일한 포트를 사용하기 때문에 방화벽을 우회하기 쉽고, 기존 HTTP 인프라와 호환성이 높다.

### 3. WebSocket Handshake 과정

WebSocket 연결은 반드시 HTTP Upgrade 과정을 통해 시작된다.

**Client → Server: HTTP Upgrade 요청**

```
GET /chat HTTP/1.1
Host: example.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
```

- `Upgrade: websocket`: WebSocket으로 프로토콜 전환 요청
- `Connection: Upgrade`: 연결 업그레이드 명시
- `Sec-WebSocket-Key`: 16바이트 난수를 Base64로 인코딩한 값. 클라이언트가 매 연결마다 새로 생성
- `Sec-WebSocket-Version: 13`: RFC 6455 기준 버전 번호

**Server → Client: HTTP 101 Switching Protocols 응답**

```
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

**Sec-WebSocket-Accept 검증 과정**

서버는 `Sec-WebSocket-Accept` 값을 다음 방식으로 생성한다:
1. 클라이언트의 `Sec-WebSocket-Key` 값을 받는다
2. 고정 매직 문자열 `258EAFA5-E914-47DA-95CA-C5AB0DC85B11`을 뒤에 붙인다
3. 연결된 문자열을 SHA-1 해시 처리한다 (20바이트 결과)
4. SHA-1 결과를 Base64로 인코딩하여 응답 헤더에 포함

```
Sec-WebSocket-Accept = Base64(SHA1(Sec-WebSocket-Key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
```

이 검증은 서버가 WebSocket 프로토콜을 의도적으로 지원한다는 사실을 클라이언트에게 증명하는 것이 목적이다. 보안 암호화나 인증을 위한 메커니즘이 아니다. 클라이언트는 이 값을 검증하여 서버가 실제 WebSocket 서버인지 확인한다.

**Handshake 이후 프로토콜 전환**

101 응답을 받으면 HTTP 통신이 종료되고 이후의 모든 통신은 WebSocket 프레임 형식으로 이루어진다. HTTP 헤더가 더 이상 필요하지 않아 오버헤드가 대폭 감소한다.

### 4. WebSocket 프레임 구조

Handshake 이후 모든 데이터는 WebSocket 프레임 단위로 주고받는다.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-------+-+-------------+-------------------------------+
|F|R|R|R| opcode|M| Payload len |    Extended payload length    |
|I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
|N|V|V|V|       |S|             |   (if payload len==126/127)   |
| |1|2|3|       |K|             |                               |
+-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
|     Extended payload length continued, if payload len == 127  |
+ - - - - - - - - - - - - - - -+-------------------------------+
|                               |Masking-key, if MASK set to 1  |
+-------------------------------+-------------------------------+
| Masking-key (continued)       |          Payload Data         |
+-------------------------------- - - - - - - - - - - - - - - - +
:                     Payload Data continued ...                :
+ - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
|                     Payload Data continued ...                |
+---------------------------------------------------------------+
```

**FIN (1bit)**
- 1: 현재 프레임이 메시지의 마지막 프레임
- 0: 이후에 이어지는 프레임이 존재 (단편화)

**RSV1, RSV2, RSV3 (각 1bit)**
- 예약 비트. 현재는 0이어야 하며, 협상된 확장 기능이 있을 때만 1로 설정 가능

**Opcode (4bit)**
- `0x0`: 연속 프레임 (Continuation)
- `0x1`: 텍스트 프레임 (UTF-8 인코딩)
- `0x2`: 바이너리 프레임
- `0x8`: 연결 종료 (Close)
- `0x9`: Ping
- `0xA`: Pong

**MASK (1bit)**
- 클라이언트 → 서버 방향의 프레임은 반드시 1 (마스킹 필수)
- 서버 → 클라이언트 방향의 프레임은 0 (마스킹 없음)

**Payload Length (7bit + 확장)**
- `0~125`: 해당 값이 페이로드 길이
- `126`: 다음 16bit를 실제 길이로 사용
- `127`: 다음 64bit를 실제 길이로 사용

**Masking-Key (32bit, MASK=1일 때만 존재)**
- 랜덤으로 생성된 32bit 마스킹 키
- 페이로드 각 바이트를 순환하며 XOR 연산 적용

**Payload Data**
- 실제 전송 데이터 (Extension Data + Application Data)

**프레임 단편화 (Fragmentation)**

대용량 메시지는 여러 프레임으로 분할하여 전송할 수 있다:
- 첫 번째 프레임: FIN=0, Opcode=실제 타입 (텍스트/바이너리)
- 중간 프레임: FIN=0, Opcode=0x0 (Continuation)
- 마지막 프레임: FIN=1, Opcode=0x0 (Continuation)

### 5. 메시지 송수신 동작 방식

**텍스트 프레임 vs 바이너리 프레임**

| 구분 | Opcode | 인코딩 | 활용 예 |
|------|--------|--------|---------|
| 텍스트 프레임 | 0x1 | UTF-8 | JSON 메시지, 채팅 텍스트 |
| 바이너리 프레임 | 0x2 | 원시 바이트 | 이미지, 파일, 미디어 스트림 |

**Ping/Pong을 통한 연결 유지**

WebSocket은 연결이 살아있는지 확인하기 위한 제어 프레임을 지원한다:
- `Ping` 프레임 (Opcode=0x9): 한쪽이 연결 상태 확인을 위해 전송
- `Pong` 프레임 (Opcode=0xA): Ping을 받은 즉시 동일한 페이로드로 응답 필수

NAT 장비나 방화벽이 idle 연결을 자동으로 끊는 문제를 방지하기 위해 서버 또는 클라이언트가 주기적으로 Ping을 전송하는 것이 일반적이다.

**Close 프레임과 정상 종료 절차**

WebSocket 연결을 정상 종료할 때는 다음 절차를 따른다:
1. 종료를 원하는 측이 Close 프레임 (Opcode=0x8)을 전송
2. Close 프레임에는 상태 코드와 이유 문자열 포함 가능
   - `1000`: 정상 종료
   - `1001`: 엔드포인트가 떠남 (브라우저 탭 닫기 등)
   - `1011`: 서버 내부 오류
3. 수신 측도 Close 프레임으로 응답
4. TCP 연결이 종료됨

이 양방향 Close 핸드셰이크를 "Closing Handshake"라 하며, 데이터 유실 없이 안전하게 연결을 끊는다.

### 6. WebSocket의 양방향 통신 메커니즘

**단일 TCP 연결의 지속성**

HTTP/1.1의 Keep-Alive도 TCP 연결을 재사용하지만, 여전히 각 요청마다 헤더를 주고받는다. WebSocket은 Handshake 이후 헤더 오버헤드 없이 프레임만 주고받아 효율적이다.

| 항목 | HTTP/1.1 (Polling) | SSE | WebSocket |
|------|-------------------|-----|-----------|
| 방향 | 클라이언트 → 서버 요청 필요 | 서버 → 클라이언트 단방향 | 완전한 양방향 |
| 오버헤드 | 높음 (매 요청마다 헤더) | 낮음 (연결 유지, 약 5바이트/메시지) | 매우 낮음 (최소 2바이트/프레임) |
| 서버 푸시 | 불가 | 가능 | 가능 |
| 재연결 | N/A | 자동 | 직접 구현 필요 |
| 지연시간 | 높음 | 낮음 | 매우 낮음 |

**서버 푸시 가능 구조**

HTTP에서는 클라이언트의 요청 없이 서버가 먼저 데이터를 보낼 수 없다. WebSocket은 연결이 수립된 이후 서버가 언제든지 메시지를 클라이언트로 전송할 수 있어, 이벤트 기반 실시간 시스템 구현에 적합하다.

### 7. 실제 활용 사례

**실시간 채팅**
사용자 간 메시지를 지연 없이 교환해야 하므로 WebSocket이 가장 적합한 선택이다.

**주식/암호화폐 시세 스트리밍**
수십 ms 단위로 변하는 가격 데이터를 서버가 클라이언트로 지속 푸시할 때 활용한다. 단방향이므로 SSE로도 가능하지만, 클라이언트가 필터 조건을 실시간으로 변경하는 경우 WebSocket이 필요하다.

**협업 도구 (Google Docs, Figma)**
여러 사용자의 편집 이벤트를 실시간으로 동기화한다. OT(Operational Transformation) 또는 CRDT 알고리즘과 함께 WebSocket을 사용한다.

**온라인 게임**
플레이어 입력, 위치 정보, 게임 상태를 밀리초 단위로 동기화한다. 지연이 매우 민감하여 WebSocket의 낮은 오버헤드가 중요하다.

**실시간 알림 시스템**
배달 상태 추적, 경매 입찰 알림, 소셜 미디어 알림 등에 활용된다.

## 핵심 정리
- WebSocket은 HTTP Upgrade 메커니즘을 통해 핸드셰이크 후 단일 TCP 커넥션 위에서 양방향 통신을 제공한다.
- Handshake 시 `Sec-WebSocket-Key`와 매직 GUID를 SHA-1 해싱 후 Base64 인코딩한 `Sec-WebSocket-Accept`로 서버의 WebSocket 지원을 검증한다.
- 메시지는 프레임 단위로 송수신되며, 클라이언트에서 서버로 보내는 프레임은 반드시 마스킹을 적용해야 한다 (캐시 포이즈닝 공격 방지).
- Ping/Pong으로 연결 상태를 점검하고, Close 프레임의 양방향 교환으로 정상 종료를 보장한다.
- HTTP와 다르게 서버가 능동적으로 메시지를 푸시할 수 있어 실시간 통신에 적합하다.

## 기술적 한계와 보완 전략
- **로드밸런서/프록시 호환성**: 일부 미들웨어가 Upgrade 헤더를 제대로 처리하지 못함 → Sticky Session 또는 WebSocket 지원 LB(AWS ALB, Nginx) 필요
- **연결 수 부담**: 다수의 지속 연결이 서버 리소스를 점유 → Reactive 서버(Netty, Vert.x), 수평 확장 전략 필요
- **방화벽/NAT 타임아웃**: Idle 연결이 끊어질 수 있음 → Heartbeat(Ping/Pong) 주기적 전송 (일반적으로 25~30초 간격)
- **확장성 문제**: 멀티 서버 환경에서 메시지 브로드캐스트 어려움 → Redis Pub/Sub, Kafka 등 메시지 브로커 도입
- **재연결 처리**: 네트워크 단절 시 클라이언트 측 재연결 로직 필요 → Exponential Backoff, 메시지 재전송 큐
- **표준 메시징 미흡**: 프로토콜 자체는 메시지 라우팅/구독 기능이 없음 → STOMP, Socket.IO 같은 상위 프로토콜 활용
- **모바일 환경**: 모바일 OS가 백그라운드 시 소켓 연결을 강제로 끊는 경우가 있음 → FCM/APNs와 병행하거나 재연결 로직 필수

## 키워드

**WebSocket**
HTTP 기반의 요청-응답 한계를 극복하기 위해 RFC 6455로 표준화된 양방향 통신 프로토콜. 단일 TCP 연결 위에서 동작하며 서버가 능동적으로 클라이언트에 데이터를 푸시할 수 있다.

**RFC 6455**
WebSocket 프로토콜의 공식 표준 문서. 2011년 IETF가 발행했으며, Handshake, 프레임 구조, 마스킹, 제어 프레임 등 WebSocket의 모든 세부 사항을 정의한다.

**HTTP Upgrade**
기존 HTTP 연결을 다른 프로토콜(WebSocket 등)로 전환하는 메커니즘. `Upgrade: websocket`, `Connection: Upgrade` 헤더와 함께 사용되며, 서버가 101 Switching Protocols로 응답하면 프로토콜 전환이 완료된다.

**Handshake**
WebSocket 연결 수립을 위한 초기 HTTP 교환 과정. 클라이언트가 HTTP Upgrade 요청을 보내고, 서버가 101로 응답하면서 이후 모든 통신이 WebSocket 프레임 방식으로 전환된다.

**Full-Duplex**
통신 채널의 양쪽이 동시에 데이터를 송수신할 수 있는 방식. 전화 통화가 대표적인 예로, WebSocket도 단일 TCP 연결에서 서버와 클라이언트가 동시에 메시지를 주고받을 수 있다.

**WebSocket Frame**
WebSocket에서 데이터를 전달하는 기본 단위. FIN 비트, Opcode, Mask 비트, Payload Length, Masking Key, Payload Data로 구성된다. 최소 2바이트의 오버헤드로 경량 통신이 가능하다.

**Masking**
클라이언트에서 서버로 보내는 WebSocket 프레임에 반드시 적용해야 하는 XOR 기반 변환. 32bit 랜덤 마스킹 키와 페이로드를 XOR 연산하여 중간 프록시 서버의 캐시 포이즈닝 공격을 방지한다.

**Ping/Pong**
WebSocket 연결 유지 확인을 위한 제어 프레임. Ping(0x9)을 받으면 수신 측은 동일한 페이로드로 Pong(0xA)을 즉시 응답해야 한다. NAT/방화벽에 의한 idle 연결 차단을 방지하기 위한 Heartbeat 메커니즘으로 활용된다.

**STOMP (Simple Text Oriented Messaging Protocol)**
WebSocket 위에서 동작하는 메시징 하위 프로토콜. WebSocket 자체에는 없는 메시지 목적지(`/topic/`, `/queue/`), 구독(SUBSCRIBE), 메시지 타입 등의 개념을 추가하여 Spring WebSocket 등과 함께 채팅, 알림 시스템 구현에 널리 사용된다.

**Server Push**
클라이언트의 요청 없이 서버가 먼저 데이터를 전송하는 방식. WebSocket에서는 연결 수립 후 서버가 언제든 클라이언트로 프레임을 전송할 수 있어, 실시간 이벤트 알림, 스트리밍 데이터 제공이 가능하다.

## 참고 자료
- [RFC 6455 - The WebSocket Protocol](https://datatracker.ietf.org/doc/html/rfc6455)
- [WebSocket Protocol Guide - websocket.org](https://websocket.org/guides/websocket-protocol/)
- [WebSocket HTTP Headers Reference - websocket.org](https://websocket.org/reference/headers/)
- [Sec-WebSocket-Accept - MDN Web Docs](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Sec-WebSocket-Accept)
- [Writing WebSocket servers - MDN Web Docs](https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_WebSocket_servers)
- [WebSockets vs SSE vs Long-Polling vs WebRTC vs WebTransport - RxDB](https://rxdb.info/articles/websockets-sse-polling-webrtc-webtransport.html)
- [Spring Boot WebSocket with STOMP - websocket.org](https://websocket.org/guides/frameworks/spring-boot/)
