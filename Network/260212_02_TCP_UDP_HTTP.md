# TCP, UDP, HTTP

## 개요
네트워크 통신의 핵심 프로토콜인 TCP, UDP, HTTP의 개념, 차이점, 동작 원리, 그리고 실무 활용에 대해 학습합니다.

## 상세 내용

### OSI 7계층과 TCP/IP 모델

```
OSI 7계층              TCP/IP 모델
─────────────────────────────────
7. Application    ┐
6. Presentation   │→  Application (HTTP, FTP, SMTP)
5. Session        ┘
─────────────────────────────────
4. Transport      →   Transport   (TCP, UDP)
─────────────────────────────────
3. Network        →   Internet    (IP)
─────────────────────────────────
2. Data Link      ┐
1. Physical       ┘→  Network Access
```

### TCP (Transmission Control Protocol)

#### 개념
**신뢰성 있는 연결 지향(Connection-Oriented) 프로토콜**
- 데이터 전송 전 연결 수립 (3-way handshake)
- 순서 보장, 오류 검출, 재전송 보장
- 흐름 제어, 혼잡 제어

#### 3-way Handshake (연결 수립)

```
Client                    Server
  │                         │
  │─────── SYN ────────────>│  1. 클라이언트 → 서버: 연결 요청
  │   (seq=100)             │     SYN(Synchronize) 패킷 전송
  │                         │
  │<──── SYN + ACK ─────────│  2. 서버 → 클라이언트: 연결 수락
  │   (seq=200, ack=101)    │     SYN + ACK 패킷 전송
  │                         │
  │─────── ACK ────────────>│  3. 클라이언트 → 서버: 연결 확인
  │   (ack=201)             │     ACK(Acknowledgment) 패킷 전송
  │                         │
  │◄═══════ 연결 수립 ═══════►│  연결 완료, 데이터 전송 시작
```

**각 단계 설명**:
1. **SYN**: "연결하고 싶어요. 내 순서 번호는 100이에요"
2. **SYN-ACK**: "좋아요! 100번 잘 받았어요(ack=101). 내 순서 번호는 200이에요"
3. **ACK**: "200번 잘 받았어요(ack=201). 이제 데이터 보낼게요"

#### 4-way Handshake (연결 종료)

```
Client                    Server
  │                         │
  │─────── FIN ────────────>│  1. 클라이언트: 연결 종료 요청
  │                         │
  │<────── ACK ─────────────│  2. 서버: 종료 요청 확인
  │                         │     (아직 데이터 전송 가능)
  │                         │
  │<────── FIN ─────────────│  3. 서버: 종료 준비 완료
  │                         │
  │─────── ACK ────────────>│  4. 클라이언트: 종료 확인
  │                         │
  │    [TIME_WAIT]          │  일정 시간 대기 (2MSL)
  │                         │
  │◄═══════ 연결 종료 ═══════►│
```

**TIME_WAIT 이유**:
- 마지막 ACK가 유실될 경우 재전송 대비
- 일반적으로 2MSL (Maximum Segment Lifetime, 약 60초) 대기

#### TCP 헤더 구조

```
 0                   15                              31
┌───────────────────┬───────────────────────────────┐
│   Source Port     │     Destination Port          │ 16비트 각
├───────────────────┴───────────────────────────────┤
│             Sequence Number                       │ 32비트
├───────────────────────────────────────────────────┤
│          Acknowledgment Number                    │ 32비트
├─────┬─────┬───────┬───────────────────────────────┤
│Offset│Rsv │Flags  │         Window Size           │
├─────┴─────┴───────┴───────────────────────────────┤
│   Checksum        │    Urgent Pointer             │
├───────────────────┴───────────────────────────────┤
│             Options (optional)                    │
└───────────────────────────────────────────────────┘
```

**주요 플래그**:
- **SYN** (Synchronize): 연결 수립
- **ACK** (Acknowledgment): 수신 확인
- **FIN** (Finish): 연결 종료
- **RST** (Reset): 연결 재설정 (비정상 종료)
- **PSH** (Push): 즉시 전달
- **URG** (Urgent): 긴급 데이터

#### 흐름 제어 (Flow Control)

**슬라이딩 윈도우 (Sliding Window)**:
```
송신 버퍼:  [1][2][3][4][5][6][7][8][9][10]
            └─ 전송 완료 ─┘└─ 윈도우 ─┘└ 대기 ┘
                           (5,6,7,8 전송 가능)

수신 버퍼:  [1][2][3][4][ ][ ][ ][ ]
            └─ 수신 완료 ─┘└ 윈도우 ┘
```

- 수신자의 버퍼 크기에 맞춰 송신량 조절
- Window Size로 수신 가능한 데이터 양 알림

#### 혼잡 제어 (Congestion Control)

**알고리즘**:
1. **Slow Start**: 윈도우 크기를 지수적으로 증가 (1 → 2 → 4 → 8...)
2. **Congestion Avoidance**: 임계값 도달 후 선형 증가
3. **Fast Retransmit**: 중복 ACK 3개 수신 시 즉시 재전송
4. **Fast Recovery**: 혼잡 발생 시 윈도우 크기 절반으로 감소

```
윈도우 크기
    │
 16 │         /\
    │        /  \      재전송
 12 │       /    \    ↓
    │      /      \  /
  8 │     /        \/
    │    /         /\
  4 │   /         /  \
    │  /         /    \
  0 │─┴─────────┴──────┴────> 시간
     Slow    Congestion  Fast
     Start   Avoidance   Recovery
```

#### TCP 장단점

**장점**:
- 신뢰성: 순서 보장, 오류 검출, 재전송
- 흐름/혼잡 제어로 네트워크 안정성
- 연결 지향으로 상태 관리

**단점**:
- 오버헤드: 3-way handshake, ACK 등
- 느림: 재전송, 흐름 제어로 지연 발생
- 연결 유지 비용

### UDP (User Datagram Protocol)

#### 개념
**비연결성(Connectionless), 비신뢰성 프로토콜**
- 연결 수립 없이 바로 데이터 전송
- 순서 보장 X, 재전송 X, 흐름 제어 X
- 빠르고 단순

#### UDP 헤더 구조

```
 0                   15                              31
┌───────────────────┬───────────────────────────────┐
│   Source Port     │     Destination Port          │
├───────────────────┼───────────────────────────────┤
│     Length        │         Checksum              │
└───────────────────┴───────────────────────────────┘
│                  Data                              │
```

**헤더 크기**: 8바이트 (TCP는 최소 20바이트)

#### UDP 동작 방식

```
Client                    Server
  │                         │
  │═══════ Data ═══════════>│  연결 수립 없이
  │═══════ Data ═══════════>│  바로 데이터 전송
  │═══════ Data ═══════════>│  (ACK 없음)
  │                         │
  │      (패킷 유실 가능)      │  재전송 없음
  │                         │
```

#### UDP 장단점

**장점**:
- 빠름: 연결 수립 없음, ACK 없음
- 오버헤드 적음: 헤더 8바이트만
- 실시간 전송 가능

**단점**:
- 신뢰성 없음: 패킷 유실 가능
- 순서 보장 안 됨
- 흐름/혼잡 제어 없음

#### UDP 사용 사례

**실시간 통신**:
- **VoIP**: 음성 통화 (Skype, Zoom 음성)
- **스트리밍**: 실시간 방송, IPTV
- **온라인 게임**: FPS, 실시간 멀티플레이어

**DNS 조회**:
```bash
dig google.com
# UDP 53번 포트 사용
# 빠른 응답 필요, 재전송은 애플리케이션에서 처리
```

**기타**:
- NTP (Network Time Protocol): 시간 동기화
- DHCP: IP 주소 할당
- SNMP: 네트워크 모니터링

### TCP vs UDP 비교

| 항목 | TCP | UDP |
|------|-----|-----|
| **연결** | 연결 지향 (3-way handshake) | 비연결 |
| **신뢰성** | 높음 (재전송, 순서 보장) | 낮음 (보장 없음) |
| **속도** | 느림 (오버헤드 많음) | 빠름 (오버헤드 적음) |
| **헤더 크기** | 20~60 바이트 | 8 바이트 |
| **순서 보장** | 보장 | 보장 안 됨 |
| **오류 검출** | 체크섬 + 재전송 | 체크섬만 |
| **흐름 제어** | 있음 (슬라이딩 윈도우) | 없음 |
| **혼잡 제어** | 있음 | 없음 |
| **사용 사례** | HTTP, HTTPS, FTP, SMTP, SSH | DNS, VoIP, 스트리밍, 게임 |
| **전이중 통신** | 지원 | 지원 |

### HTTP (HyperText Transfer Protocol)

#### 개념
**웹에서 데이터를 주고받기 위한 애플리케이션 계층 프로토콜**
- TCP 기반 (신뢰성 보장)
- 클라이언트-서버 모델
- 무상태(Stateless) 프로토콜

#### HTTP 버전별 특징

##### HTTP/1.0 (1996년)
- **특징**: 요청마다 TCP 연결 새로 수립
- **문제**: 매번 3-way handshake 발생 (느림)

```
Client                    Server
  │                         │
  │─ 1. TCP 연결 ───────────>│
  │─ 2. GET /index.html ───>│
  │<─ 3. Response ──────────│
  │─ 4. TCP 종료 ───────────>│
  │                         │
  │─ 5. TCP 연결 ───────────>│  새 요청마다
  │─ 6. GET /style.css ────>│  연결 재수립
  │<─ 7. Response ──────────│
  │─ 8. TCP 종료 ───────────>│
```

##### HTTP/1.1 (1997년)
- **Keep-Alive**: 연결 재사용
- **Pipelining**: 여러 요청 동시 전송
- **Host 헤더**: 가상 호스팅 지원

```
Client                    Server
  │                         │
  │─ 1. TCP 연결 ───────────>│
  │─ 2. GET /index.html ───>│
  │<─ 3. Response ──────────│
  │─ 4. GET /style.css ────>│  연결 유지
  │<─ 5. Response ──────────│  (Keep-Alive)
  │─ 6. GET /script.js ────>│
  │<─ 7. Response ──────────│
  │─ 8. TCP 종료 ───────────>│  모든 요청 완료 후
```

**문제: HOL (Head-of-Line) Blocking**
- 앞 요청이 지연되면 뒤 요청도 대기

##### HTTP/2 (2015년)
- **멀티플렉싱**: 하나의 연결에서 여러 요청/응답 병렬 처리
- **헤더 압축**: HPACK 알고리즘
- **서버 푸시**: 클라이언트 요청 없이 리소스 전송
- **바이너리 프로토콜**: 텍스트 → 바이너리

```
Client                    Server
  │                         │
  │─ TCP 연결 ──────────────>│
  │                         │
  │══ Stream 1: GET /html ═>│
  │══ Stream 2: GET /css ══>│  병렬 처리
  │══ Stream 3: GET /js ═══>│  (멀티플렉싱)
  │                         │
  │<═ Stream 1: html ═══════│  순서 무관
  │<═ Stream 3: js ═════════│  응답 가능
  │<═ Stream 2: css ════════│
```

##### HTTP/3 (2022년)
- **QUIC 기반**: UDP 사용 (TCP 대신)
- **0-RTT**: 연결 재수립 시 지연 없음
- **패킷 손실 복구 개선**: 스트림별 독립적 재전송

```
TCP (HTTP/1.1, HTTP/2):
  3-way handshake (1 RTT) + TLS 핸드셰이크 (1~2 RTT) = 2~3 RTT

QUIC (HTTP/3):
  0-RTT (재연결 시) 또는 1-RTT (최초 연결)
```

#### HTTP 요청/응답 구조

**요청 (Request)**:
```http
GET /api/users/123 HTTP/1.1
Host: api.example.com
User-Agent: Mozilla/5.0
Accept: application/json
Authorization: Bearer token123
Connection: keep-alive

(본문 - POST, PUT 등에서 사용)
```

**응답 (Response)**:
```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 85
Date: Wed, 11 Feb 2026 10:00:00 GMT
Connection: keep-alive

{
  "id": 123,
  "name": "홍길동",
  "email": "hong@example.com"
}
```

#### HTTP 메서드

| 메서드 | 설명 | 멱등성 | 안전성 | Body |
|--------|------|--------|--------|------|
| **GET** | 리소스 조회 | O | O | X |
| **POST** | 리소스 생성, 처리 | X | X | O |
| **PUT** | 리소스 전체 수정 | O | X | O |
| **PATCH** | 리소스 부분 수정 | X | X | O |
| **DELETE** | 리소스 삭제 | O | X | X |
| **HEAD** | 헤더만 조회 | O | O | X |
| **OPTIONS** | 지원 메서드 확인 | O | O | X |

- **멱등성**: 여러 번 호출해도 결과 동일
- **안전성**: 리소스 변경 없음

#### HTTP 상태 코드

**1xx (정보)**:
- 100 Continue: 계속 진행

**2xx (성공)**:
- 200 OK: 성공
- 201 Created: 생성 성공
- 204 No Content: 성공, 응답 본문 없음

**3xx (리다이렉션)**:
- 301 Moved Permanently: 영구 이동
- 302 Found: 임시 이동
- 304 Not Modified: 캐시 사용

**4xx (클라이언트 오류)**:
- 400 Bad Request: 잘못된 요청
- 401 Unauthorized: 인증 필요
- 403 Forbidden: 권한 없음
- 404 Not Found: 리소스 없음
- 429 Too Many Requests: 요청 과다

**5xx (서버 오류)**:
- 500 Internal Server Error: 서버 내부 오류
- 502 Bad Gateway: 게이트웨이 오류
- 503 Service Unavailable: 서비스 이용 불가
- 504 Gateway Timeout: 게이트웨이 타임아웃

#### HTTPS (HTTP Secure)

**개념**: HTTP + TLS/SSL 암호화

```
HTTP:
Client ─────── 평문 전송 ─────────> Server
       (누구나 볼 수 있음)

HTTPS:
Client ─────── 암호화 전송 ─────────> Server
       (중간에서 볼 수 없음)
```

**TLS 핸드셰이크**:
```
Client                    Server
  │                         │
  │─ 1. ClientHello ───────>│  지원하는 암호화 방식 전달
  │<─ 2. ServerHello ───────│  암호화 방식 선택, 인증서 전송
  │                         │
  │─ 3. 인증서 검증 ────────│  CA(인증기관) 확인
  │─ 4. PreMaster Secret ──>│  대칭키 교환 (비대칭키로 암호화)
  │                         │
  │◄═ 5. 대칭키 생성 ══════►│  양측에서 세션키 생성
  │                         │
  │══ 6. 암호화 통신 시작 ══►│  대칭키로 데이터 암호화
```

**포트**:
- HTTP: 80
- HTTPS: 443

### 실무 활용

#### Spring Boot에서 TCP 소켓 통신
```java
@Component
public class TcpServer {

    @Bean
    public ServerSocketFactory serverSocketFactory() {
        return new DefaultServerSocketFactory();
    }

    @Bean
    public TcpNetServerConnectionFactory connectionFactory() {
        TcpNetServerConnectionFactory factory =
            new TcpNetServerConnectionFactory(9090);  // 포트 9090
        factory.setApplicationEventPublisher(applicationEventPublisher);
        return factory;
    }

    @EventListener
    public void handleMessage(TcpConnectionEvent event) {
        byte[] data = (byte[]) event.getSource();
        // 데이터 처리
    }
}
```

#### UDP 소켓 통신
```java
@Component
public class UdpReceiver {

    @Bean
    public UnicastReceivingChannelAdapter udpAdapter() {
        UnicastReceivingChannelAdapter adapter =
            new UnicastReceivingChannelAdapter(8080);  // UDP 8080 포트
        adapter.setOutputChannel(udpChannel());
        return adapter;
    }

    @ServiceActivator(inputChannel = "udpChannel")
    public void handleUdpMessage(byte[] data) {
        // UDP 패킷 처리
        String message = new String(data);
        log.info("Received: {}", message);
    }
}
```

#### HTTP 클라이언트 (RestTemplate)
```java
@Service
public class ApiClient {

    private final RestTemplate restTemplate;

    public User getUser(Long id) {
        String url = "https://api.example.com/users/" + id;

        // GET 요청
        ResponseEntity<User> response = restTemplate.getForEntity(
            url,
            User.class
        );

        return response.getBody();
    }

    public User createUser(UserCreateRequest request) {
        String url = "https://api.example.com/users";

        // POST 요청
        return restTemplate.postForObject(
            url,
            request,
            User.class
        );
    }
}
```

#### WebClient (비동기, HTTP/2 지원)
```java
@Service
public class AsyncApiClient {

    private final WebClient webClient;

    public AsyncApiClient(WebClient.Builder builder) {
        this.webClient = builder
            .baseUrl("https://api.example.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    public Mono<User> getUser(Long id) {
        return webClient.get()
            .uri("/users/{id}", id)
            .retrieve()
            .bodyToMono(User.class);
    }

    public Flux<User> getAllUsers() {
        return webClient.get()
            .uri("/users")
            .retrieve()
            .bodyToFlux(User.class);
    }
}
```

### 프로토콜 선택 가이드

#### TCP를 사용해야 할 때
- 데이터 무결성이 중요한 경우
  - 파일 전송 (FTP)
  - 이메일 (SMTP, IMAP)
  - 웹 (HTTP/HTTPS)
  - 원격 접속 (SSH)
- 순서가 중요한 경우
- 데이터 손실이 허용되지 않는 경우

#### UDP를 사용해야 할 때
- 실시간성이 중요한 경우
  - 화상 회의, 음성 통화
  - 온라인 게임
  - 실시간 스트리밍
- 빠른 응답이 필요한 경우
  - DNS 조회
  - NTP (시간 동기화)
- 일부 패킷 손실이 허용되는 경우

#### HTTP 버전 선택
```
HTTP/1.1: 레거시 시스템, 간단한 API
HTTP/2:   모던 웹 애플리케이션, 성능 중요
HTTP/3:   최신 브라우저, 모바일 환경, 패킷 손실 많은 환경
```

## 핵심 정리

### TCP (전송 제어 프로토콜)
- **연결 지향**: 3-way handshake로 연결 수립
- **신뢰성**: 순서 보장, 재전송, 오류 검출
- **흐름 제어**: 슬라이딩 윈도우로 수신 속도 조절
- **혼잡 제어**: Slow Start, Congestion Avoidance
- **사용 사례**: HTTP, FTP, SSH, SMTP

### UDP (사용자 데이터그램 프로토콜)
- **비연결**: 연결 수립 없이 바로 전송
- **빠름**: 오버헤드 적음 (헤더 8바이트)
- **비신뢰성**: 순서 보장 X, 재전송 X
- **사용 사례**: DNS, VoIP, 스트리밍, 게임

### HTTP (하이퍼텍스트 전송 프로토콜)
- **무상태**: 각 요청은 독립적
- **TCP 기반**: 신뢰성 있는 전송
- **메서드**: GET, POST, PUT, DELETE 등
- **버전**:
  - HTTP/1.1: Keep-Alive, 연결 재사용
  - HTTP/2: 멀티플렉싱, 헤더 압축
  - HTTP/3: QUIC(UDP) 기반, 0-RTT

### 선택 기준
- **데이터 무결성 중요** → TCP
- **실시간성 중요** → UDP
- **웹 애플리케이션** → HTTP/HTTPS (TCP 기반)
- **성능 최적화** → HTTP/2 또는 HTTP/3

## 참고 자료
- [RFC 793 - TCP](https://datatracker.ietf.org/doc/html/rfc793)
- [RFC 768 - UDP](https://datatracker.ietf.org/doc/html/rfc768)
- [RFC 2616 - HTTP/1.1](https://datatracker.ietf.org/doc/html/rfc2616)
- [RFC 7540 - HTTP/2](https://datatracker.ietf.org/doc/html/rfc7540)
- [RFC 9114 - HTTP/3](https://datatracker.ietf.org/doc/html/rfc9114)
- [MDN Web Docs - HTTP](https://developer.mozilla.org/en-US/docs/Web/HTTP)
