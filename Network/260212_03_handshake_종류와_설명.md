# handshake 종류와 설명

## 개요
네트워크 통신에서 handshake는 두 시스템 간 연결을 수립하거나 종료할 때 수행하는 협상 과정입니다. 각 프로토콜마다 고유한 handshake 방식을 사용하여 안정적이고 보안된 통신을 보장합니다.

## 상세 내용

### 1. TCP 3-Way Handshake (연결 수립)

TCP 연결을 수립하는 과정으로, 클라이언트와 서버가 통신을 시작하기 전에 연결을 확립합니다.

**과정:**
1. **SYN (Synchronize)**: 클라이언트가 서버에 연결 요청을 보냄
   - **무엇을**: 클라이언트의 초기 시퀀스 번호(ISN)를 전송
   - **왜**: 데이터 패킷의 순서를 추적하기 위한 시작점을 서버에 알림. 각 바이트마다 번호를 매겨 패킷 순서 보장 및 중복/손실 감지 가능
   - **목적**: "연결하고 싶습니다. 제 시작 번호는 X입니다"
   - 상태: CLOSED → SYN_SENT

2. **SYN-ACK (Synchronize-Acknowledge)**: 서버가 요청을 수락하고 응답
   - **무엇을**:
     - 서버의 초기 시퀀스 번호 전송
     - 클라이언트의 시퀀스 번호 + 1을 ACK로 전송
   - **왜**:
     - 서버도 데이터를 보낼 준비가 되었음을 알리고 자신의 시작 번호 설정
     - ACK를 통해 클라이언트의 SYN을 받았음을 확인 (신뢰성 보장)
   - **목적**: "연결 수락합니다. 당신의 번호 X를 확인했고, 제 시작 번호는 Y입니다"
   - 상태: LISTEN → SYN_RECEIVED

3. **ACK (Acknowledge)**: 클라이언트가 서버의 응답을 확인
   - **무엇을**: 서버의 시퀀스 번호 + 1을 ACK로 전송
   - **왜**: 서버의 SYN을 받았음을 확인하여 양방향 연결 완성. 이 시점부터 데이터 전송 가능
   - **목적**: "당신의 번호 Y도 확인했습니다. 이제 통신 시작합시다"
   - 상태: SYN_SENT → ESTABLISHED (서버도 ESTABLISHED)

**왜 3-Way인가?**
- 2-Way로는 불충분: 서버의 응답을 클라이언트가 받았는지 서버가 알 수 없음
- 4-Way는 불필요: SYN과 ACK를 동시에 보내는 것으로 효율성 확보
- 양방향 시퀀스 번호 동기화와 상호 확인 모두 달성

**특징:**
- 양방향 통신 준비 완료
- 시퀀스 번호 동기화로 데이터 순서 보장
- 연결 지향적 통신의 시작점

### 2. TCP 4-Way Handshake (연결 종료)

TCP 연결을 안전하게 종료하는 과정입니다.

**과정:**
1. **FIN (Finish)**: 클라이언트가 연결 종료 요청
   - **무엇을**: FIN 플래그 전송
   - **왜**: 클라이언트가 더 이상 보낼 데이터가 없음을 알림 (하지만 받는 것은 가능)
   - **목적**: "제가 보낼 데이터는 끝났습니다"
   - 상태: ESTABLISHED → FIN_WAIT_1

2. **ACK**: 서버가 종료 요청을 확인
   - **무엇을**: FIN에 대한 확인 응답
   - **왜**: 서버가 클라이언트의 종료 의사를 인지했음을 알림. 하지만 서버는 아직 보낼 데이터가 있을 수 있음
   - **목적**: "알겠습니다. 제가 보낼 데이터를 마저 보내겠습니다"
   - 상태: ESTABLISHED → CLOSE_WAIT
   - 클라이언트 상태: FIN_WAIT_1 → FIN_WAIT_2

3. **FIN**: 서버가 남은 데이터 전송 후 종료 준비 완료
   - **무엇을**: 서버도 FIN 플래그 전송
   - **왜**: 서버도 모든 데이터를 보냈고 종료할 준비가 됨
   - **목적**: "저도 보낼 데이터를 모두 보냈습니다. 연결을 종료합시다"
   - 상태: CLOSE_WAIT → LAST_ACK

4. **ACK**: 클라이언트가 서버의 종료 요청 확인
   - **무엇을**: 서버의 FIN에 대한 최종 확인 응답
   - **왜**: 서버가 안전하게 연결을 닫을 수 있도록 확인. TIME_WAIT으로 네트워크에 남아있을 수 있는 지연 패킷 처리
   - **목적**: "확인했습니다. 안녕히 가세요"
   - 클라이언트 상태: FIN_WAIT_2 → TIME_WAIT → CLOSED
   - 서버 상태: LAST_ACK → CLOSED

**왜 4-Way인가?**
- **Half-Close 지원**: TCP는 전이중(Full-Duplex) 통신이므로 각 방향을 독립적으로 종료
- **데이터 보호**: 서버가 아직 보낼 데이터가 남아있을 수 있어, FIN을 받았다고 즉시 종료하면 데이터 손실 발생
- **ACK와 FIN 분리**: 2단계와 3단계 사이에 서버가 남은 작업을 완료할 시간 확보

**TIME_WAIT의 이유:**
- **지연 패킷 처리**: 네트워크에 아직 떠도는 패킷이 있을 수 있어 충분한 시간(2*MSL) 대기
- **재연결 문제 방지**: 같은 포트로 즉시 재연결 시 이전 연결의 패킷과 혼동 방지
- **안전한 종료 보장**: 마지막 ACK가 손실되면 서버가 FIN을 재전송하므로, 재전송을 받을 수 있도록 대기

**특징:**
- 양방향 독립적 종료 (Half-Close 지원)
- TIME_WAIT 상태로 지연된 패킷 처리
- 안전한 리소스 해제

### 3. TLS/SSL Handshake (보안 연결 수립)

HTTPS 통신에서 암호화된 연결을 수립하는 과정입니다.

**과정 (TLS 1.2 기준):**
1. **Client Hello**: 클라이언트가 지원하는 암호화 방식 목록 전송
   - **무엇을**:
     - 지원하는 TLS 버전
     - 암호화 스위트(Cipher Suite) 목록 (AES-GCM, ChaCha20 등)
     - 클라이언트 랜덤 데이터 (28바이트)
     - 지원하는 압축 방식
   - **왜**:
     - 클라이언트와 서버가 모두 지원하는 가장 강력한 암호화 방식을 협상하기 위함
     - 랜덤 데이터는 나중에 세션 키 생성에 사용 (재사용 공격 방지)
   - **목적**: "안녕하세요, 저는 이런 암호화 방식들을 지원합니다"

2. **Server Hello + Certificate**: 서버가 사용할 암호화 방식 선택
   - **무엇을**:
     - 선택된 TLS 버전 및 암호화 스위트
     - 서버 랜덤 데이터 (28바이트)
     - 서버의 공개키 인증서 (X.509 형식)
     - 인증서 체인 (중간 CA ~ Root CA)
   - **왜**:
     - 실제 사용할 암호화 방식 결정 (일반적으로 가장 강력한 것 선택)
     - 서버 인증서로 서버의 신원 증명 (중간자 공격 방지)
     - 양쪽 랜덤 데이터로 예측 불가능한 세션 키 생성
   - **목적**: "이 방식을 사용하겠습니다. 제 신원을 증명하는 인증서입니다"

3. **인증서 검증**: 클라이언트가 서버 인증서 확인
   - **무엇을**:
     - CA(Certificate Authority) 서명 검증
     - 인증서 유효기간 확인
     - 인증서 폐기 목록(CRL/OCSP) 확인
     - 도메인 이름 일치 확인
   - **왜**:
     - 서버가 정말 신뢰할 수 있는지 확인 (가짜 서버 방지)
     - 인증서가 변조되지 않았는지 검증
     - 피싱 공격 방지
   - **목적**: "당신이 진짜 example.com 서버가 맞나요?"

4. **Key Exchange**: 세션 키 교환
   - **무엇을**:
     - 클라이언트가 Pre-Master Secret (48바이트) 생성
     - 서버의 공개키로 암호화하여 전송
     - 양쪽이 동일한 Master Secret 생성
   - **왜**:
     - 대칭키 암호화를 위한 공유 비밀키 안전하게 교환
     - 공개키 암호화는 느리므로 실제 데이터는 빠른 대칭키로 암호화
     - 양쪽의 랜덤 데이터 + Pre-Master Secret → 세션별로 고유한 키 생성
   - **목적**: "이 키로 실제 데이터를 암호화합시다"
   - **과정**: `Master Secret = PRF(Pre-Master Secret + Client Random + Server Random)`

5. **Finished**: 양쪽 모두 암호화 준비 완료 메시지 전송
   - **무엇을**:
     - 지금까지의 모든 handshake 메시지를 해시하여 MAC 생성
     - 생성된 세션 키로 암호화하여 전송
   - **왜**:
     - Handshake 과정이 변조되지 않았음을 상호 검증
     - 양쪽이 동일한 키를 갖고 있는지 확인 (키 불일치 시 복호화 실패)
     - 중간자가 handshake를 변조하면 해시가 달라져 감지 가능
   - **목적**: "준비 완료! handshake가 안전했는지 확인합시다"

**왜 이렇게 복잡한가?**
- **인증 + 암호화 동시 달성**: 서버 신원 확인과 안전한 키 교환을 모두 수행
- **완전 순방향 비밀성(PFS)**: 개인키 유출되어도 과거 세션 안전 (세션별로 고유한 키 사용)
- **중간자 공격 방지**: 인증서 검증으로 가짜 서버 차단

**TLS 1.3의 개선:**
- **1-RTT handshake**: Client Hello에 키 교환 정보 포함 → 왕복 1회로 단축
- **0-RTT 재연결**: 이전 세션 정보로 즉시 데이터 전송 (보안 trade-off 있음)
- **약한 암호화 제거**: RSA 키 교환, SHA-1, RC4 등 제거로 보안 강화
- **간소화**: 단계를 줄여 성능 향상

### 4. HTTP/2 Connection Preface

HTTP/2 연결을 시작할 때의 초기화 과정입니다.

**과정:**
1. **Connection Preface**: 클라이언트가 매직 문자열 전송
   ```
   PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n
   ```
   - **무엇을**: 고정된 24바이트 매직 문자열 (Connection Preface)
   - **왜**:
     - HTTP/1.1 프록시가 HTTP/2 트래픽을 잘못 해석하지 않도록 방지
     - 유효하지 않은 HTTP/1.1 요청 형태로 만들어 프록시가 거부하도록 설계
     - HTTP/2를 지원하는 서버만 연결을 수락
   - **목적**: "저는 HTTP/2를 사용하겠습니다. HTTP/1.1 프록시는 이 연결을 거부하세요"

2. **SETTINGS Frame**: 양쪽이 설정 정보 교환
   - **무엇을**:
     - `SETTINGS_MAX_CONCURRENT_STREAMS`: 최대 동시 스트림 수
     - `SETTINGS_INITIAL_WINDOW_SIZE`: 초기 흐름 제어 윈도우 크기
     - `SETTINGS_MAX_FRAME_SIZE`: 최대 프레임 크기
     - `SETTINGS_MAX_HEADER_LIST_SIZE`: 최대 헤더 크기
     - `SETTINGS_ENABLE_PUSH`: 서버 푸시 활성화 여부
   - **왜**:
     - **멀티플렉싱 제어**: 동시에 처리할 수 있는 요청 수 협상
     - **흐름 제어**: 수신자가 처리할 수 있는 데이터량 조절 (버퍼 오버플로우 방지)
     - **성능 최적화**: 각자의 리소스 상황에 맞는 설정 적용
     - **호환성**: 양쪽의 기능 지원 여부 확인
   - **목적**: "저는 이런 설정으로 동작하겠습니다"

3. **SETTINGS ACK**: 설정 확인 응답
   - **무엇을**: 빈 SETTINGS 프레임에 ACK 플래그 설정
   - **왜**: 상대방의 설정을 받았고 적용했음을 확인 (신뢰성 보장)
   - **목적**: "당신의 설정을 받았고 준수하겠습니다"

**왜 이 과정이 필요한가?**
- **HTTP/1.1과의 구분**: 매직 문자열로 프로토콜 명확히 구분
- **유연한 설정**: 클라이언트와 서버의 리소스 상황이 다르므로 협상 필요
- **성능 최적화**: 동시 스트림 수 등을 조절하여 최적의 성능 달성
- **역호환성 방지**: HTTP/1.1 프록시가 HTTP/2를 잘못 처리하지 않도록 보호

**특징:**
- TCP 연결 위에서 동작 (TCP handshake 이후)
- 멀티플렉싱 준비
- 서버 푸시 가능 여부 협상
- 바이너리 프로토콜로 효율적 파싱

### 5. WebSocket Handshake

HTTP에서 WebSocket 프로토콜로 업그레이드하는 과정입니다.

**과정:**
1. **HTTP Upgrade 요청** (클라이언트):
   ```http
   GET /chat HTTP/1.1
   Host: example.com
   Upgrade: websocket
   Connection: Upgrade
   Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
   Sec-WebSocket-Version: 13
   ```
   - **무엇을**:
     - `Upgrade: websocket`: WebSocket으로 프로토콜 변경 요청
     - `Connection: Upgrade`: 연결 유지하며 업그레이드
     - `Sec-WebSocket-Key`: 랜덤하게 생성된 16바이트 base64 인코딩 값
     - `Sec-WebSocket-Version: 13`: WebSocket 프로토콜 버전
   - **왜**:
     - **기존 인프라 활용**: HTTP(80) 또는 HTTPS(443) 포트 재사용으로 방화벽 통과 용이
     - **보안**: Sec-WebSocket-Key로 일반 HTTP 클라이언트의 실수로 인한 연결 방지
     - **호환성**: HTTP 요청 형태로 시작하여 프록시 통과 가능
   - **목적**: "이 HTTP 연결을 WebSocket으로 업그레이드하고 싶습니다"

2. **Upgrade 응답** (서버):
   ```http
   HTTP/1.1 101 Switching Protocols
   Upgrade: websocket
   Connection: Upgrade
   Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
   ```
   - **무엇을**:
     - `101 Switching Protocols`: 프로토콜 전환 승인
     - `Sec-WebSocket-Accept`: 클라이언트의 Key를 검증한 응답 값
   - **왜**:
     - **검증**: Accept 값 계산 과정으로 WebSocket을 이해하는 서버인지 확인
       ```
       Sec-WebSocket-Accept = base64(SHA1(Sec-WebSocket-Key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
       ```
     - **캐시 방지**: HTTP 캐시 서버가 이 응답을 저장하지 못하도록 함
     - **프로토콜 전환 확정**: 이 시점 이후로는 HTTP가 아닌 WebSocket 프레임 사용
   - **목적**: "업그레이드를 승인합니다. 이제부터 WebSocket 프로토콜을 사용합니다"

**왜 HTTP Upgrade를 사용하는가?**
- **방화벽/프록시 통과**: 80/443 포트를 사용하여 기업 방화벽 통과 용이
- **기존 인프라 재사용**: 별도의 포트나 서버 설정 불필요
- **점진적 업그레이드**: HTTP로 시작하여 필요시에만 WebSocket으로 전환
- **보안 계층 재사용**: HTTPS 위에서 동작하면 TLS 암호화 자동으로 적용 (wss://)

**Sec-WebSocket-Key의 역할:**
- **의도 확인**: 일반 HTTP 클라이언트가 실수로 WebSocket 연결을 시도하지 않도록 방지
- **캐시 방지**: 프록시나 캐시 서버가 이 응답을 재사용하지 못하도록 함
- **보안이 아님**: 암호화나 인증 목적이 아니며, 프로토콜 오용 방지가 주 목적

**특징:**
- HTTP에서 양방향 통신으로 업그레이드
- 단일 TCP 연결 재사용 (새 연결 생성 불필요)
- 오버헤드 최소화 (HTTP 헤더 없이 작은 프레임 사용)
- 실시간 양방향 통신 지원

### 6. QUIC Handshake

HTTP/3의 기반이 되는 QUIC 프로토콜의 handshake입니다.

**과정:**
1. **Initial Packet**: 클라이언트가 연결 요청
   - **무엇을**:
     - TLS 1.3 Client Hello
     - QUIC Transport Parameters (설정 정보)
     - Connection ID (연결 식별자)
   - **왜**:
     - **TLS와 Transport 통합**: 보안 handshake와 전송 설정을 한 번에 수행
     - **Connection ID**: IP 주소가 아닌 Connection ID로 연결 식별 (모바일 환경 대응)
     - **빠른 연결**: TLS 1.3의 1-RTT handshake를 UDP 위에서 구현
   - **목적**: "QUIC 연결을 시작합니다. 암호화와 전송 설정을 협상합시다"

2. **Handshake Packet**: 서버가 암호화 자료 전송
   - **무엇을**:
     - TLS 1.3 Server Hello, Certificate, Finished
     - 서버의 QUIC Transport Parameters
     - 초기 암호화 키 자료
   - **왜**:
     - **1-RTT 달성**: 첫 왕복에서 TLS handshake 완료
     - **즉시 암호화**: Handshake 패킷부터 이미 암호화됨 (Initial 패킷도 부분 암호화)
     - **설정 교환**: 흐름 제어, 최대 데이터 크기 등 협상
   - **목적**: "인증서와 설정을 전송합니다. 이제 암호화된 통신이 가능합니다"

3. **1-RTT Data**: 단 한 번의 왕복으로 데이터 전송 가능
   - **무엇을**: 실제 애플리케이션 데이터 (HTTP/3 요청 등)
   - **왜**: TLS 1.3 handshake가 1-RTT에 완료되므로 두 번째 메시지부터 즉시 데이터 전송
   - **목적**: "handshake 완료, 실제 데이터를 주고받습니다"

**0-RTT 재연결 (선택적):**
- **무엇을**: 이전 연결의 세션 정보(PSK)를 사용하여 handshake 없이 즉시 데이터 전송
- **왜**:
  - **최고 속도**: handshake 없이 첫 패킷부터 데이터 전송 (레이턴시 제로)
  - **모바일 최적화**: 네트워크 전환 시에도 빠른 재연결
- **제약사항**:
  - Replay 공격 가능성 (idempotent한 요청만 안전)
  - 서버가 PSK를 거부할 수 있음

**왜 QUIC이 TCP보다 빠른가?**

1. **통합 handshake**:
   - TCP: TCP 3-way (1 RTT) + TLS 1.3 (1 RTT) = **2 RTT**
   - QUIC: TLS + Transport 동시 = **1 RTT**

2. **Head-of-Line Blocking 해결**:
   - TCP: 하나의 패킷 손실이 모든 스트림 지연
   - QUIC: 스트림별 독립적 재전송 (다른 스트림에 영향 없음)

3. **연결 마이그레이션**:
   - **무엇을**: IP 주소나 포트가 변경되어도 연결 유지
   - **왜**: Connection ID로 연결을 식별하므로 4-tuple(IP/Port) 변경에 영향 없음
   - **사용 사례**: WiFi ↔ LTE 전환 시에도 연결 유지

4. **개선된 손실 복구**:
   - 각 패킷에 고유 번호 부여 (TCP는 시퀀스 번호 재사용)
   - 더 정확한 RTT 측정 가능
   - 빠른 재전송 감지

**QUIC Transport Parameters 예시:**
```
- initial_max_streams_bidi: 양방향 스트림 최대 개수
- initial_max_data: 전체 연결의 최대 데이터량
- initial_max_stream_data: 스트림별 최대 데이터량
- max_idle_timeout: 유휴 시간 초과
- max_udp_payload_size: UDP 패킷 최대 크기
```

**특징:**
- UDP 기반으로 더 빠른 연결 (커널 수정 불필요)
- 0-RTT 재연결 지원으로 모바일 환경 최적화
- 패킷 손실에 강한 구조 (스트림별 독립적 처리)
- 연결 마이그레이션 지원 (IP 변경 시에도 연결 유지)
- 사용자 공간에서 구현되어 빠른 프로토콜 진화 가능

### 7. 데이터베이스 연결과 TLS/SSL Handshake

데이터베이스 서버와의 연결에서 TLS/SSL 사용은 선택적이지만, 운영 환경에서는 강력히 권장됩니다.

#### 기본 DB 연결 과정 (TLS 없이)

```
애플리케이션 → TCP 3-Way Handshake → DB 서버
            → DB 프로토콜 handshake (평문) →
            → 인증 (ID/PW, 평문 또는 해시) →
            → 쿼리 실행 (평문) →
```

- **무엇을**: TCP 연결 후 바로 DB 프로토콜로 통신
- **왜**: 빠르고 간단하지만 보안 취약
- **문제점**:
  - 네트워크 스니핑으로 쿼리, 데이터, 비밀번호 노출
  - 중간자 공격(MITM) 취약
  - 서버 신원 검증 불가

#### TLS/SSL 적용 시 연결 과정

```
애플리케이션 → TCP 3-Way Handshake → DB 서버
            → TLS Handshake (암호화 협상) →
            → DB 프로토콜 handshake (암호화됨) →
            → 인증 (암호화됨) →
            → 쿼리 실행 (암호화됨) →
```

- **무엇을**: TCP 연결 후 TLS handshake를 먼저 수행
- **왜**:
  - 모든 DB 통신 내용 암호화 (쿼리, 결과, 비밀번호)
  - 서버 인증서로 DB 서버 신원 확인
  - 중간자 공격 방지
- **목적**: "안전한 채널을 먼저 구축한 후 DB 통신 시작"

#### 주요 데이터베이스별 TLS 설정

**1. MySQL**
```java
// TLS 없이 (개발 환경)
jdbc:mysql://localhost:3306/mydb

// TLS 활성화 (인증서 검증 안 함 - 권장 안 함)
jdbc:mysql://db.example.com:3306/mydb?useSSL=true

// TLS + 인증서 검증 (운영 환경 권장)
jdbc:mysql://db.example.com:3306/mydb?useSSL=true&requireSSL=true&verifyServerCertificate=true
```

**설정 파라미터:**
- `useSSL=true`: TLS 활성화
- `requireSSL=true`: TLS 필수 (평문 연결 시도 시 실패)
- `verifyServerCertificate=true`: 서버 인증서 검증

**2. PostgreSQL**
```java
// sslmode별 동작
jdbc:postgresql://db.example.com:5432/mydb?sslmode=disable    // TLS 비활성화
jdbc:postgresql://db.example.com:5432/mydb?sslmode=allow      // 서버가 요구하면 사용
jdbc:postgresql://db.example.com:5432/mydb?sslmode=prefer     // 가능하면 TLS (기본값)
jdbc:postgresql://db.example.com:5432/mydb?sslmode=require    // TLS 필수
jdbc:postgresql://db.example.com:5432/mydb?sslmode=verify-ca  // TLS + CA 검증
jdbc:postgresql://db.example.com:5432/mydb?sslmode=verify-full // TLS + CA + 호스트명 검증
```

- **무엇을**: sslmode로 TLS 수준 세밀하게 제어
- **왜**: 환경별로 다른 보안 수준 적용 가능
- **권장**: 운영 환경은 `verify-full`, 개발은 `prefer` 또는 `require`

**3. MongoDB**
```javascript
// TLS 사용
mongodb://db.example.com:27017?tls=true

// TLS + 인증서 검증
mongodb://db.example.com:27017?tls=true&tlsCAFile=/path/to/ca.pem&tlsCertificateKeyFile=/path/to/client.pem
```

**4. Redis (6.0+)**
```bash
# redis.conf
tls-port 6380
tls-cert-file /path/to/redis.crt
tls-key-file /path/to/redis.key
tls-ca-cert-file /path/to/ca.crt
```

#### TLS 사용이 필수인 경우

1. **공개 네트워크 통신**
   - **무엇을**: 인터넷을 통한 DB 접근
   - **왜**: 패킷이 여러 네트워크를 거치므로 스니핑 위험 높음
   - 예: 클라우드 DB (AWS RDS, Google Cloud SQL)

2. **규제 준수**
   - **무엇을**: PCI-DSS, HIPAA, GDPR 등
   - **왜**: 개인정보/금융 데이터 전송 시 암호화 의무
   - 예: 신용카드 정보, 의료 데이터

3. **멀티 테넌트 환경**
   - **무엇을**: 여러 고객의 데이터를 처리하는 SaaS
   - **왜**: 데이터 유출 시 다수의 고객에게 영향
   - 예: B2B SaaS 플랫폼

4. **VPC 간 통신**
   - **무엇을**: 서로 다른 VPC/Region 간 통신
   - **왜**: VPC 피어링이라도 네트워크 경계를 넘어가므로
   - 예: Multi-region 아키텍처

#### Connection Pool과 TLS의 관계

Connection Pool은 연결을 재사용하여 TLS handshake 비용을 최소화합니다.

**TLS Overhead 분석:**
```
TLS 없이:
  연결당: TCP 3-way (1 RTT) = ~10ms
  10개 연결: 10 RTT = ~100ms

TLS 사용 (Pool 없이):
  연결당: TCP 3-way (1 RTT) + TLS (1-2 RTT) = ~30ms
  10개 연결: 20-30 RTT = ~300ms

TLS 사용 + Connection Pool:
  초기 연결: (TCP + TLS) × Pool 크기 = 1회만 발생
  이후 요청: Handshake 없음, 연결 재사용
  성능 영향: 거의 없음 ✅
```

**HikariCP 설정 예시:**
```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:mysql://db.example.com:3306/mydb?useSSL=true&requireSSL=true");
config.setUsername("user");
config.setPassword("password");

// Pool 설정
config.setMaximumPoolSize(20);        // 최대 연결 수
config.setMinimumIdle(5);             // 최소 유휴 연결
config.setConnectionTimeout(30000);   // 연결 타임아웃 (30초)
config.setMaxLifetime(1800000);       // 연결 최대 수명 (30분)

HikariDataSource ds = new HikariDataSource(config);
```

- **무엇을**: Pool에서 TLS 연결을 미리 생성하고 재사용
- **왜**:
  - 요청마다 handshake 반복 불필요
  - 초기 연결 시에만 TLS overhead 발생
  - `maxLifetime`으로 주기적 갱신하여 보안 유지
- **결과**: TLS 사용해도 성능 저하 거의 없음

#### 실전 권장사항

**환경별 설정:**

```java
// ❌ 나쁜 예: 운영 환경에서 TLS 비활성화
String prodUrl = "jdbc:mysql://prod-db:3306/mydb?useSSL=false";

// ⚠️ 위험: TLS 사용하지만 검증 안 함 (중간자 공격 가능)
String riskyUrl = "jdbc:mysql://prod-db:3306/mydb?useSSL=true&verifyServerCertificate=false";

// ✅ 좋은 예: TLS + 인증서 검증
String secureUrl = "jdbc:mysql://prod-db:3306/mydb?useSSL=true&requireSSL=true&verifyServerCertificate=true";

// ✅ 최선: Connection Pool + TLS
HikariConfig config = new HikariConfig();
config.setJdbcUrl(secureUrl);
config.setMaximumPoolSize(20);
config.setMinimumIdle(5);
config.setMaxLifetime(1800000);  // 30분마다 연결 재생성
```

**개발 vs 운영:**
```java
// 개발 환경 (localhost)
jdbc:mysql://localhost:3306/mydb  // TLS 선택적

// 스테이징 환경
jdbc:mysql://staging-db:3306/mydb?useSSL=true&requireSSL=true

// 운영 환경 (필수)
jdbc:mysql://prod-db:3306/mydb?useSSL=true&requireSSL=true&verifyServerCertificate=true
```

#### 왜 Connection Pool + TLS가 최선인가?

1. **보안**: 모든 DB 통신 암호화
2. **성능**: Pool로 handshake 비용 상쇄
3. **확장성**: 연결 재사용으로 DB 부하 감소
4. **안정성**: 연결 수 제한으로 리소스 고갈 방지
5. **규제 준수**: 암호화 요구사항 충족

**성능 비교:**
```
환경: AWS RDS (서울), EC2 (서울), 50ms RTT

Pool 없이 + TLS 없이:
  - 요청당 handshake: 1 RTT (50ms)
  - 100 요청: 5초

Pool 없이 + TLS 사용:
  - 요청당 handshake: 2 RTT (100ms)
  - 100 요청: 10초

Pool (10개) + TLS 사용:
  - 초기 handshake: 2 RTT × 10 = 1초
  - 100 요청: 1초 (handshake 비용 없음) ✅
```

## 핵심 정리

### TCP Handshakes
- **3-Way**: 연결 수립 (SYN → SYN-ACK → ACK)
  - 양방향 시퀀스 번호 동기화
  - 2-Way는 불충분, 4-Way는 불필요
- **4-Way**: 연결 종료 (FIN → ACK → FIN → ACK)
  - Half-Close 지원으로 안전한 종료
  - TIME_WAIT으로 지연 패킷 처리
- 신뢰성 있는 연결 보장

### 보안 Handshakes
- **TLS/SSL**: 암호화된 통신 채널 구축
  - 서버 인증 (인증서 검증)
  - 안전한 키 교환 (Pre-Master Secret)
  - Handshake 무결성 검증 (Finished 메시지)
- **TLS 1.3**: 1-RTT로 성능 개선
  - 0-RTT 재연결 지원
  - 약한 암호화 제거
- 인증서 검증으로 신뢰성 확보

### 프로토콜 Handshakes
- **HTTP/2**: SETTINGS 프레임으로 멀티플렉싱 준비
  - 매직 문자열로 HTTP/1.1 프록시 방지
  - 동시 스트림 수, 윈도우 크기 협상
- **WebSocket**: HTTP에서 양방향 통신으로 업그레이드
  - 80/443 포트 재사용
  - Sec-WebSocket-Key로 프로토콜 오용 방지
- **QUIC**: UDP 기반의 빠른 연결 수립
  - TLS + Transport 통합 (1-RTT)
  - Connection ID로 연결 마이그레이션
  - Head-of-Line Blocking 해결

### 데이터베이스 보안
- **DB 연결 + TLS**: 선택적이지만 운영 환경 필수
  - MySQL: `useSSL=true&requireSSL=true&verifyServerCertificate=true`
  - PostgreSQL: `sslmode=verify-full`
  - 모든 쿼리와 데이터 암호화
- **Connection Pool + TLS**: 최고의 조합
  - 초기 연결 시에만 handshake 발생
  - 이후 연결 재사용으로 성능 영향 최소화
  - 보안과 성능 모두 달성

### 성능 관점
- Handshake는 레이턴시의 주요 원인
  - TCP: 1 RTT, TLS 1.2: 2 RTT, TLS 1.3: 1 RTT, QUIC: 1 RTT
- 연결 재사용(Keep-Alive, Connection Pooling)으로 최적화
  - Pool로 handshake 비용을 초기 1회로 제한
  - DB 연결에서 특히 효과적
- 0-RTT, 1-RTT 기술로 handshake 오버헤드 감소
- **핵심**: Handshake가 느리다면 → Connection Pool 사용

## 참고 자료

### 프로토콜 명세
- [RFC 793 - Transmission Control Protocol](https://www.rfc-editor.org/rfc/rfc793)
- [RFC 8446 - The Transport Layer Security (TLS) Protocol Version 1.3](https://www.rfc-editor.org/rfc/rfc8446)
- [RFC 7540 - Hypertext Transfer Protocol Version 2 (HTTP/2)](https://www.rfc-editor.org/rfc/rfc7540)
- [RFC 6455 - The WebSocket Protocol](https://www.rfc-editor.org/rfc/rfc6455)
- [RFC 9000 - QUIC: A UDP-Based Multiplexed and Secure Transport](https://www.rfc-editor.org/rfc/rfc9000)

### 데이터베이스 TLS/SSL 설정
- [MySQL - Using Encrypted Connections](https://dev.mysql.com/doc/refman/8.0/en/encrypted-connections.html)
- [PostgreSQL - SSL Support](https://www.postgresql.org/docs/current/ssl-tcp.html)
- [MongoDB - TLS/SSL Configuration](https://www.mongodb.com/docs/manual/core/security-transport-encryption/)
- [Redis - TLS Support](https://redis.io/docs/management/security/encryption/)

### Connection Pool
- [HikariCP - Documentation](https://github.com/brettwooldridge/HikariCP)
- [Apache Commons DBCP](https://commons.apache.org/proper/commons-dbcp/)
