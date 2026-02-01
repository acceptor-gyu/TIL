# Web Push Notification in Server

## 개요
서버에서 클라이언트로 웹 푸시 알림을 전송하는 메커니즘에 대한 학습. Web Push Protocol과 VAPID를 사용하여 브라우저가 닫혀있어도 사용자에게 알림을 전달할 수 있는 기술.

## 상세 내용

### Web Push 아키텍처

Web Push는 세 가지 주체가 관여합니다:

1. **Application Server (백엔드)**: 푸시 알림을 발송하는 서버
2. **Push Service**: 브라우저 벤더가 운영하는 중개 서버 (Chrome: FCM, Firefox: Mozilla Push Service)
3. **User Agent (브라우저)**: Service Worker를 통해 푸시를 수신하고 알림을 표시

```
[Application Server] ---> [Push Service] ---> [Browser/Service Worker] ---> [User]
                          (FCM, Mozilla등)
```

### 동작 흐름

1. **구독 단계** (Subscription)
   - 사용자가 웹사이트를 방문하여 알림 권한 허용
   - Service Worker가 Push Manager API를 통해 Push Service에 구독 요청
   - Push Service가 고유한 엔드포인트 URL 생성 및 암호화 키 발급
   - 프론트엔드가 구독 정보(PushSubscription)를 백엔드에 전송
   - 백엔드가 구독 정보를 데이터베이스에 저장

2. **발송 단계** (Send)
   - 백엔드가 특정 이벤트 발생 시 푸시 발송 결정
   - VAPID 키로 인증 정보 생성
   - Push Service 엔드포인트로 HTTP POST 요청 전송
   - Push Service가 해당 브라우저로 푸시 전달

3. **수신 단계** (Receive)
   - 브라우저의 Service Worker가 `push` 이벤트 수신
   - Service Worker가 Notification API로 알림 표시
   - 사용자가 알림 클릭 시 `notificationclick` 이벤트 처리

### VAPID (Voluntary Application Server Identification)

VAPID는 Application Server가 자신을 식별하는 표준 방법입니다.

**왜 필요한가?**
- Push Service가 누가 푸시를 보내는지 확인
- 악의적인 푸시 발송 방지
- 문제 발생 시 책임 추적 가능

**VAPID 키 생성**
```bash
# web-push 라이브러리 사용
npx web-push generate-vapid-keys
```

결과:
```
Public Key: BMxY...
Private Key: pXmv...
```

### 프론트엔드 구현

#### 1. Service Worker 등록

```javascript
// main.js
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js')
    .then(registration => {
      console.log('Service Worker 등록 성공:', registration);
    })
    .catch(error => {
      console.error('Service Worker 등록 실패:', error);
    });
}
```

#### 2. 푸시 구독 요청

```javascript
// main.js
async function subscribeToPush() {
  // Service Worker 준비 대기
  const registration = await navigator.serviceWorker.ready;

  // 알림 권한 요청
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    console.log('알림 권한이 거부되었습니다.');
    return;
  }

  // VAPID Public Key (백엔드에서 제공)
  const vapidPublicKey = 'BMxY...';

  // URL-safe base64를 Uint8Array로 변환
  const convertedVapidKey = urlBase64ToUint8Array(vapidPublicKey);

  // Push 구독
  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true, // 모든 푸시는 알림으로 표시
    applicationServerKey: convertedVapidKey
  });

  // 구독 정보를 서버로 전송
  await fetch('/api/push/subscribe', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(subscription)
  });

  console.log('푸시 구독 완료:', subscription);
}

// VAPID Key 변환 헬퍼 함수
function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - base64String.length % 4) % 4);
  const base64 = (base64String + padding)
    .replace(/\-/g, '+')
    .replace(/_/g, '/');

  const rawData = window.atob(base64);
  const outputArray = new Uint8Array(rawData.length);

  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}
```

#### 3. Service Worker에서 푸시 수신

```javascript
// sw.js
self.addEventListener('push', event => {
  let data = {};

  if (event.data) {
    data = event.data.json();
  }

  const title = data.title || '새로운 알림';
  const options = {
    body: data.body || '내용이 없습니다.',
    icon: data.icon || '/icon.png',
    badge: '/badge.png',
    data: {
      url: data.url || '/'
    },
    actions: [
      { action: 'open', title: '열기' },
      { action: 'close', title: '닫기' }
    ]
  };

  event.waitUntil(
    self.registration.showNotification(title, options)
  );
});

// 알림 클릭 이벤트
self.addEventListener('notificationclick', event => {
  event.notification.close();

  if (event.action === 'open' || !event.action) {
    const urlToOpen = event.notification.data.url;

    event.waitUntil(
      clients.openWindow(urlToOpen)
    );
  }
});
```

### 백엔드 구현 (Spring Boot)

#### 1. 의존성 추가

```gradle
// build.gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'nl.martijndwars:web-push:5.1.1'
    implementation 'com.google.code.gson:gson:2.10.1'

    // WebSocket 사용 시
    implementation 'org.springframework.boot:spring-boot-starter-websocket'
}
```

```xml
<!-- pom.xml -->
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>nl.martijndwars</groupId>
        <artifactId>web-push</artifactId>
        <version>5.1.1</version>
    </dependency>
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
    </dependency>

    <!-- WebSocket 사용 시 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
</dependencies>
```

#### 2. VAPID 설정

```yaml
# application.yml
push:
  vapid:
    public-key: BMxY...
    private-key: pXmv...
    subject: mailto:your-email@example.com
```

```java
// VapidConfig.java
@Configuration
@ConfigurationProperties(prefix = "push.vapid")
@Data
public class VapidConfig {
    private String publicKey;
    private String privateKey;
    private String subject;
}
```

#### 3. Entity 및 Repository

```java
// PushSubscription.java
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 1000)
    private String endpoint;

    @Column(nullable = false, length = 500)
    private String p256dh;

    @Column(nullable = false, length = 500)
    private String auth;

    @Column(nullable = false, updatable = false)
    @CreatedDate
    private LocalDateTime createdAt;

    public nl.martijndwars.webpush.Subscription toWebPushSubscription() {
        return new nl.martijndwars.webpush.Subscription(
            endpoint,
            new nl.martijndwars.webpush.Subscription.Keys(p256dh, auth)
        );
    }
}
```

```java
// PushSubscriptionRepository.java
@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
    List<PushSubscription> findByUserId(Long userId);
    Optional<PushSubscription> findByEndpoint(String endpoint);
    void deleteByEndpoint(String endpoint);
}
```

#### 4. DTO

```java
// PushSubscriptionDto.java
@Data
public class PushSubscriptionDto {
    private String endpoint;
    private Long expirationTime;
    private Keys keys;

    @Data
    public static class Keys {
        private String p256dh;
        private String auth;
    }
}

// NotificationPayload.java
@Data
@Builder
public class NotificationPayload {
    private String title;
    private String body;
    private String icon;
    private String url;
}
```

#### 5. Service 클래스

```java
// PushNotificationService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private final PushSubscriptionRepository subscriptionRepository;
    private final VapidConfig vapidConfig;
    private final Gson gson;

    private PushService pushService;

    @PostConstruct
    public void init() throws GeneralSecurityException {
        // VAPID 키 설정
        Security.addProvider(new BouncyCastleProvider());

        pushService = new PushService()
            .setPublicKey(vapidConfig.getPublicKey())
            .setPrivateKey(vapidConfig.getPrivateKey())
            .setSubject(vapidConfig.getSubject());
    }

    /**
     * 구독 정보 저장
     */
    public PushSubscription saveSubscription(Long userId, PushSubscriptionDto dto) {
        // 기존 구독이 있으면 업데이트, 없으면 생성
        PushSubscription subscription = subscriptionRepository
            .findByEndpoint(dto.getEndpoint())
            .orElse(new PushSubscription());

        subscription.setUserId(userId);
        subscription.setEndpoint(dto.getEndpoint());
        subscription.setP256dh(dto.getKeys().getP256dh());
        subscription.setAuth(dto.getKeys().getAuth());

        return subscriptionRepository.save(subscription);
    }

    /**
     * 특정 사용자에게 푸시 발송
     */
    public void sendPushToUser(Long userId, NotificationPayload payload) {
        List<PushSubscription> subscriptions = subscriptionRepository.findByUserId(userId);

        if (subscriptions.isEmpty()) {
            log.warn("사용자 {}의 구독 정보가 없습니다.", userId);
            return;
        }

        String payloadJson = gson.toJson(payload);

        subscriptions.forEach(subscription -> {
            try {
                nl.martijndwars.webpush.Notification notification =
                    new nl.martijndwars.webpush.Notification(
                        subscription.toWebPushSubscription(),
                        payloadJson
                    );

                HttpResponse response = pushService.send(notification);

                // 410 Gone: 구독 만료
                if (response.getStatusLine().getStatusCode() == 410) {
                    subscriptionRepository.deleteByEndpoint(subscription.getEndpoint());
                    log.info("만료된 구독 삭제: {}", subscription.getEndpoint());
                }

            } catch (Exception e) {
                log.error("푸시 발송 실패: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 모든 구독자에게 푸시 발송
     */
    @Async
    public void sendPushToAll(NotificationPayload payload) {
        List<PushSubscription> allSubscriptions = subscriptionRepository.findAll();
        String payloadJson = gson.toJson(payload);

        allSubscriptions.parallelStream().forEach(subscription -> {
            try {
                nl.martijndwars.webpush.Notification notification =
                    new nl.martijndwars.webpush.Notification(
                        subscription.toWebPushSubscription(),
                        payloadJson
                    );

                HttpResponse response = pushService.send(notification);

                if (response.getStatusLine().getStatusCode() == 410) {
                    subscriptionRepository.deleteByEndpoint(subscription.getEndpoint());
                }

            } catch (Exception e) {
                log.error("푸시 발송 실패: {}", e.getMessage());
            }
        });
    }

    /**
     * 우선순위와 TTL 설정하여 발송
     */
    public void sendPushWithOptions(Long userId, NotificationPayload payload,
                                     String urgency, int ttl) {
        List<PushSubscription> subscriptions = subscriptionRepository.findByUserId(userId);
        String payloadJson = gson.toJson(payload);

        subscriptions.forEach(subscription -> {
            try {
                nl.martijndwars.webpush.Notification notification =
                    new nl.martijndwars.webpush.Notification(
                        subscription.toWebPushSubscription(),
                        payloadJson
                    );

                // Urgency와 TTL 설정
                notification.setUrgency(Urgency.valueOf(urgency.toUpperCase()));
                notification.setTtl(ttl);

                pushService.send(notification);

            } catch (Exception e) {
                log.error("푸시 발송 실패: {}", e.getMessage(), e);
            }
        });
    }
}
```

#### 6. Controller

```java
// PushNotificationController.java
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushNotificationController {

    private final PushNotificationService pushService;
    private final VapidConfig vapidConfig;

    /**
     * VAPID Public Key 제공
     */
    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> getPublicKey() {
        return ResponseEntity.ok(Map.of("publicKey", vapidConfig.getPublicKey()));
    }

    /**
     * 푸시 구독
     */
    @PostMapping("/subscribe")
    public ResponseEntity<Map<String, String>> subscribe(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestBody PushSubscriptionDto subscription) {

        pushService.saveSubscription(user.getId(), subscription);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("message", "구독 완료"));
    }

    /**
     * 특정 사용자에게 푸시 발송
     */
    @PostMapping("/send/{userId}")
    public ResponseEntity<Map<String, String>> sendToUser(
            @PathVariable Long userId,
            @RequestBody NotificationPayload payload) {

        pushService.sendPushToUser(userId, payload);
        return ResponseEntity.ok(Map.of("message", "푸시 발송 완료"));
    }

    /**
     * 전체 푸시 발송
     */
    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, String>> broadcast(
            @RequestBody NotificationPayload payload) {

        pushService.sendPushToAll(payload);
        return ResponseEntity.accepted()
            .body(Map.of("message", "푸시 발송 중"));
    }
}
```

#### 7. 비동기 설정

```java
// AsyncConfig.java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "pushTaskExecutor")
    public Executor pushTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("push-");
        executor.initialize();
        return executor;
    }
}
```

### PushSubscription 객체 구조

프론트엔드에서 백엔드로 전송되는 구독 정보:

```json
{
  "endpoint": "https://fcm.googleapis.com/fcm/send/...",
  "expirationTime": null,
  "keys": {
    "p256dh": "BKxj...",  // 공개키 (암호화용)
    "auth": "kMTx..."     // 인증 시크릿
  }
}
```

- **endpoint**: Push Service의 고유 URL (푸시를 보낼 주소)
- **keys.p256dh**: 메시지 암호화에 사용되는 공개키
- **keys.auth**: 메시지 인증에 사용되는 시크릿

### 데이터베이스 스키마 예시

```sql
CREATE TABLE push_subscriptions (
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL,
  endpoint TEXT NOT NULL UNIQUE,
  p256dh TEXT NOT NULL,
  auth TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT NOW(),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_push_subscriptions_user_id ON push_subscriptions(user_id);
```

### 보안 고려사항

1. **HTTPS 필수**: Service Worker는 HTTPS 환경에서만 동작 (localhost 제외)
2. **VAPID 키 보안**: Private Key는 절대 클라이언트에 노출하지 않음
3. **구독 정보 보안**: endpoint는 URL이지만 보안 토큰이 포함되어 있으므로 안전하게 저장
4. **Rate Limiting**: 푸시 발송 빈도 제한 필요
5. **사용자 동의**: 알림 권한은 사용자 액션에 의해서만 요청

### 제약사항 및 팁

1. **브라우저 제약**
   - Safari는 macOS 13+, iOS 16.4+부터 지원
   - 각 브라우저마다 Push Service가 다름 (Chrome: FCM, Firefox: Mozilla)

2. **메시지 크기 제한**
   - 대부분의 Push Service는 4KB 제한
   - 큰 데이터는 푸시에 포함하지 말고 URL만 전달

3. **만료 처리**
   - HTTP 410 Gone 응답 시 구독 정보 삭제
   - 주기적으로 만료된 구독 정리

4. **개발 팁**
   - Chrome DevTools > Application > Service Workers에서 푸시 테스트 가능
   - `chrome://gcm-internals`에서 푸시 이벤트 모니터링

### WebSocket 활용 방법

Web Push와 WebSocket은 서로 다른 용도로 사용되며, 함께 사용하면 효과적인 알림 시스템을 구축할 수 있습니다.

#### WebSocket vs Web Push 비교

| 특징 | WebSocket | Web Push |
|------|-----------|----------|
| **연결 상태** | 브라우저가 열려있어야 함 | 브라우저가 닫혀있어도 작동 |
| **실시간성** | 즉각 전달 (밀리초) | 약간의 지연 (초 단위) |
| **양방향 통신** | 가능 | 불가능 (서버 → 클라이언트만) |
| **서버 부하** | 연결 유지 비용 | 연결 유지 불필요 |
| **사용 사례** | 채팅, 실시간 업데이트 | 백그라운드 알림, 중요 이벤트 |
| **브라우저 지원** | 대부분 지원 | Service Worker 필요 |

#### Hybrid 전략: WebSocket + Web Push

효과적인 알림 시스템은 두 기술을 조합하여 사용합니다:

```
사용자 온라인 → WebSocket으로 즉각 전달
사용자 오프라인 → Web Push로 알림 전송
```

#### Spring Boot WebSocket 설정

```java
// WebSocketConfig.java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 클라이언트로 메시지를 보낼 때 사용할 prefix
        config.enableSimpleBroker("/topic", "/queue");
        // 클라이언트에서 메시지를 보낼 때 사용할 prefix
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS();
    }
}
```

#### WebSocket 이벤트 리스너

```java
// WebSocketEventListener.java
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    // 사용자 연결 상태 추적
    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        String username = headerAccessor.getUser().getName();

        activeSessions.put(username, new SessionInfo(sessionId, true));
        log.info("사용자 연결: {} (세션: {})", username, sessionId);
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String username = headerAccessor.getUser().getName();

        activeSessions.remove(username);
        log.info("사용자 연결 해제: {}", username);
    }

    public boolean isUserOnline(String username) {
        return activeSessions.containsKey(username);
    }
}

@Data
@AllArgsConstructor
class SessionInfo {
    private String sessionId;
    private boolean online;
}
```

#### Hybrid 알림 서비스

```java
// HybridNotificationService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridNotificationService {

    private final PushNotificationService pushService;
    private final WebSocketEventListener webSocketListener;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 사용자 온라인 상태에 따라 WebSocket 또는 Web Push 선택
     */
    public void sendNotification(Long userId, String username, NotificationPayload payload) {
        if (webSocketListener.isUserOnline(username)) {
            // 온라인 → WebSocket으로 즉시 전달
            sendViaWebSocket(username, payload);
            log.info("WebSocket으로 알림 전송: {}", username);
        } else {
            // 오프라인 → Web Push로 알림
            pushService.sendPushToUser(userId, payload);
            log.info("Web Push로 알림 전송: {}", username);
        }
    }

    /**
     * 중요한 알림은 두 채널 모두 사용
     */
    public void sendCriticalNotification(Long userId, String username, NotificationPayload payload) {
        // WebSocket으로 즉시 전달 시도
        if (webSocketListener.isUserOnline(username)) {
            sendViaWebSocket(username, payload);
        }

        // Web Push도 함께 발송 (브라우저가 백그라운드일 수 있음)
        pushService.sendPushToUser(userId, payload);
        log.info("Critical 알림 전송 (Hybrid): {}", username);
    }

    /**
     * WebSocket으로 메시지 전송
     */
    private void sendViaWebSocket(String username, NotificationPayload payload) {
        messagingTemplate.convertAndSendToUser(
            username,
            "/queue/notifications",
            payload
        );
    }

    /**
     * 특정 토픽 구독자 전체에게 전송
     */
    public void broadcastToTopic(String topic, NotificationPayload payload) {
        // WebSocket 구독자에게 실시간 전송
        messagingTemplate.convertAndSend("/topic/" + topic, payload);

        // 오프라인 사용자를 위해 Web Push도 발송
        pushService.sendPushToAll(payload);
    }
}
```

#### WebSocket Controller

```java
// NotificationController.java
@Controller
@RequiredArgsConstructor
public class NotificationController {

    private final HybridNotificationService notificationService;

    /**
     * 실시간 알림 전송 (WebSocket)
     */
    @MessageMapping("/notification.send")
    @SendToUser("/queue/reply")
    public NotificationPayload sendNotification(
            @Payload NotificationPayload payload,
            Principal principal) {

        log.info("알림 수신: {} -> {}", principal.getName(), payload.getTitle());
        return payload;
    }

    /**
     * 특정 사용자에게 알림 전송 (REST API)
     */
    @PostMapping("/api/notifications/send")
    public ResponseEntity<?> sendToUser(
            @RequestParam Long userId,
            @RequestParam String username,
            @RequestBody NotificationPayload payload) {

        notificationService.sendNotification(userId, username, payload);
        return ResponseEntity.ok().build();
    }
}
```

#### 프론트엔드: WebSocket 연결

```javascript
// websocket-client.js
class NotificationClient {
  constructor() {
    this.stompClient = null;
    this.connected = false;
  }

  connect(username) {
    const socket = new SockJS('/ws');
    this.stompClient = Stomp.over(socket);

    this.stompClient.connect({}, (frame) => {
      console.log('WebSocket 연결됨:', frame);
      this.connected = true;

      // 개인 알림 구독
      this.stompClient.subscribe('/user/queue/notifications', (message) => {
        const notification = JSON.parse(message.body);
        this.showNotification(notification);
      });

      // 토픽 구독 (전체 공지 등)
      this.stompClient.subscribe('/topic/announcements', (message) => {
        const notification = JSON.parse(message.body);
        this.showNotification(notification);
      });
    }, (error) => {
      console.error('WebSocket 연결 실패:', error);
      this.connected = false;
    });
  }

  disconnect() {
    if (this.stompClient !== null) {
      this.stompClient.disconnect();
      this.connected = false;
    }
  }

  showNotification(notification) {
    // Notification API 사용
    if ('Notification' in window && Notification.permission === 'granted') {
      new Notification(notification.title, {
        body: notification.body,
        icon: notification.icon
      });
    }

    // UI에도 표시
    this.displayInUI(notification);
  }

  displayInUI(notification) {
    const toast = document.createElement('div');
    toast.className = 'notification-toast';
    toast.innerHTML = `
      <strong>${notification.title}</strong>
      <p>${notification.body}</p>
    `;
    document.body.appendChild(toast);

    setTimeout(() => toast.remove(), 5000);
  }
}

// 사용 예시
const notificationClient = new NotificationClient();
notificationClient.connect('username');
```

#### 프론트엔드: 과제 기한 알림 시스템

과제 기한 알림을 받고 처리하는 프론트엔드 로직입니다.

##### 1. 과제 관리 클래스

```javascript
// assignment-manager.js
class AssignmentManager {
  constructor(notificationClient) {
    this.notificationClient = notificationClient;
    this.assignments = [];
    this.timers = new Map(); // 로컬 타이머 관리
  }

  /**
   * 초기화: 서버에서 과제 목록 불러오기
   */
  async initialize() {
    await this.loadAssignments();
    this.setupLocalTimers();
    this.subscribeToNotifications();
  }

  /**
   * 서버에서 과제 목록 가져오기
   */
  async loadAssignments() {
    try {
      const response = await fetch('/api/assignments');
      this.assignments = await response.json();
      this.renderAssignments();
    } catch (error) {
      console.error('과제 목록 로드 실패:', error);
    }
  }

  /**
   * 과제 생성
   */
  async createAssignment(title, description, deadline) {
    try {
      const response = await fetch('/api/assignments', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, description, deadline })
      });

      const newAssignment = await response.json();
      this.assignments.push(newAssignment);
      this.setupTimerForAssignment(newAssignment);
      this.renderAssignments();

      return newAssignment;
    } catch (error) {
      console.error('과제 생성 실패:', error);
    }
  }

  /**
   * 과제 완료
   */
  async completeAssignment(assignmentId) {
    try {
      await fetch(`/api/assignments/${assignmentId}/complete`, {
        method: 'POST'
      });

      // 로컬 상태 업데이트
      const assignment = this.assignments.find(a => a.id === assignmentId);
      if (assignment) {
        assignment.status = 'COMPLETED';
        assignment.completedAt = new Date().toISOString();
        this.clearTimer(assignmentId);
        this.renderAssignments();
      }
    } catch (error) {
      console.error('과제 완료 실패:', error);
    }
  }

  /**
   * 로컬 타이머 설정 (클라이언트 측 알림)
   */
  setupLocalTimers() {
    this.assignments.forEach(assignment => {
      if (assignment.status !== 'COMPLETED') {
        this.setupTimerForAssignment(assignment);
      }
    });
  }

  /**
   * 개별 과제 타이머 설정
   */
  setupTimerForAssignment(assignment) {
    const deadline = new Date(assignment.deadline);
    const now = new Date();

    // 이미 지난 기한
    if (deadline < now) {
      return;
    }

    // 1시간 전 로컬 알림
    const oneHourBefore = deadline.getTime() - (60 * 60 * 1000);
    if (oneHourBefore > now.getTime()) {
      const timeout = oneHourBefore - now.getTime();
      const timer = setTimeout(() => {
        this.showLocalNotification(assignment, '1시간 후 마감됩니다');
      }, timeout);
      this.timers.set(`${assignment.id}_1h`, timer);
    }

    // 기한 정각 알림
    const timeout = deadline.getTime() - now.getTime();
    if (timeout > 0) {
      const timer = setTimeout(() => {
        this.showLocalNotification(assignment, '지금 마감입니다!');
      }, timeout);
      this.timers.set(`${assignment.id}_deadline`, timer);
    }
  }

  /**
   * 로컬 알림 표시
   */
  showLocalNotification(assignment, message) {
    if ('Notification' in window && Notification.permission === 'granted') {
      const notification = new Notification(`📚 ${assignment.title}`, {
        body: message,
        icon: '/icons/assignment.png',
        badge: '/icons/badge.png',
        tag: `assignment-${assignment.id}`,
        requireInteraction: true // 사용자가 닫을 때까지 유지
      });

      notification.onclick = () => {
        window.focus();
        window.location.href = `/assignments/${assignment.id}`;
        notification.close();
      };
    }

    // UI 토스트도 표시
    this.showToast(assignment.title, message, 'warning');
  }

  /**
   * 타이머 제거
   */
  clearTimer(assignmentId) {
    const timer1h = this.timers.get(`${assignmentId}_1h`);
    const timerDeadline = this.timers.get(`${assignmentId}_deadline`);

    if (timer1h) {
      clearTimeout(timer1h);
      this.timers.delete(`${assignmentId}_1h`);
    }
    if (timerDeadline) {
      clearTimeout(timerDeadline);
      this.timers.delete(`${assignmentId}_deadline`);
    }
  }

  /**
   * WebSocket을 통한 서버 알림 구독
   */
  subscribeToNotifications() {
    if (!this.notificationClient.stompClient) {
      console.error('WebSocket이 연결되지 않았습니다.');
      return;
    }

    // 과제 관련 알림 구독
    this.notificationClient.stompClient.subscribe('/user/queue/assignments', (message) => {
      const notification = JSON.parse(message.body);
      this.handleServerNotification(notification);
    });
  }

  /**
   * 서버 알림 처리
   */
  handleServerNotification(notification) {
    console.log('서버 알림 수신:', notification);

    // 브라우저 알림 표시
    if ('Notification' in window && Notification.permission === 'granted') {
      const browserNotification = new Notification(notification.title, {
        body: notification.body,
        icon: notification.icon,
        tag: 'assignment-server-notification'
      });

      browserNotification.onclick = () => {
        if (notification.url) {
          window.location.href = notification.url;
        }
        browserNotification.close();
      };
    }

    // UI 업데이트
    this.showToast(notification.title, notification.body, 'info');

    // 과제 목록 새로고침
    this.loadAssignments();
  }

  /**
   * UI 토스트 표시
   */
  showToast(title, message, type = 'info') {
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.innerHTML = `
      <div class="toast-header">
        <strong>${title}</strong>
        <button class="close-btn" onclick="this.parentElement.parentElement.remove()">×</button>
      </div>
      <div class="toast-body">${message}</div>
    `;

    const container = document.getElementById('toast-container') || this.createToastContainer();
    container.appendChild(toast);

    // 5초 후 자동 제거
    setTimeout(() => toast.remove(), 5000);
  }

  /**
   * 토스트 컨테이너 생성
   */
  createToastContainer() {
    const container = document.createElement('div');
    container.id = 'toast-container';
    container.style.cssText = `
      position: fixed;
      top: 20px;
      right: 20px;
      z-index: 9999;
    `;
    document.body.appendChild(container);
    return container;
  }

  /**
   * 과제 목록 렌더링
   */
  renderAssignments() {
    const container = document.getElementById('assignments-list');
    if (!container) return;

    container.innerHTML = '';

    this.assignments.forEach(assignment => {
      const item = this.createAssignmentItem(assignment);
      container.appendChild(item);
    });
  }

  /**
   * 과제 아이템 생성
   */
  createAssignmentItem(assignment) {
    const div = document.createElement('div');
    div.className = `assignment-item status-${assignment.status.toLowerCase()}`;
    div.innerHTML = `
      <div class="assignment-header">
        <h3>${assignment.title}</h3>
        <span class="status-badge ${assignment.status.toLowerCase()}">
          ${this.getStatusText(assignment.status)}
        </span>
      </div>
      <p class="assignment-description">${assignment.description || ''}</p>
      <div class="assignment-footer">
        <span class="deadline">
          ${this.formatDeadline(assignment.deadline)}
        </span>
        <span class="time-remaining ${this.getTimeRemainingClass(assignment)}">
          ${this.getTimeRemaining(assignment.deadline)}
        </span>
        ${assignment.status !== 'COMPLETED' ? `
          <button class="btn-complete" onclick="assignmentManager.completeAssignment(${assignment.id})">
            완료하기
          </button>
        ` : ''}
      </div>
    `;
    return div;
  }

  /**
   * 상태 텍스트
   */
  getStatusText(status) {
    const statusMap = {
      'PENDING': '대기 중',
      'IN_PROGRESS': '진행 중',
      'COMPLETED': '완료',
      'OVERDUE': '기한 초과'
    };
    return statusMap[status] || status;
  }

  /**
   * 기한 포맷
   */
  formatDeadline(deadline) {
    const date = new Date(deadline);
    return date.toLocaleString('ko-KR', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  /**
   * 남은 시간 계산
   */
  getTimeRemaining(deadline) {
    const now = new Date();
    const deadlineDate = new Date(deadline);
    const diff = deadlineDate - now;

    if (diff < 0) {
      return '기한 초과';
    }

    const days = Math.floor(diff / (1000 * 60 * 60 * 24));
    const hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
    const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));

    if (days > 0) {
      return `${days}일 ${hours}시간 남음`;
    } else if (hours > 0) {
      return `${hours}시간 ${minutes}분 남음`;
    } else {
      return `${minutes}분 남음`;
    }
  }

  /**
   * 남은 시간에 따른 CSS 클래스
   */
  getTimeRemainingClass(assignment) {
    if (assignment.status === 'COMPLETED') return 'completed';
    if (assignment.status === 'OVERDUE') return 'overdue';

    const now = new Date();
    const deadline = new Date(assignment.deadline);
    const diff = deadline - now;
    const hours = diff / (1000 * 60 * 60);

    if (hours < 1) return 'urgent';
    if (hours < 24) return 'warning';
    return 'normal';
  }
}
```

##### 2. Service Worker에서 백그라운드 푸시 수신

```javascript
// sw.js (Service Worker)
self.addEventListener('push', event => {
  let data = {};

  if (event.data) {
    data = event.data.json();
  }

  // 과제 알림 처리
  if (data.type === 'assignment') {
    const title = data.title || '과제 알림';
    const options = {
      body: data.body,
      icon: '/icons/assignment.png',
      badge: '/icons/badge.png',
      tag: `assignment-${data.assignmentId}`,
      data: {
        url: data.url || `/assignments/${data.assignmentId}`,
        assignmentId: data.assignmentId
      },
      actions: [
        { action: 'view', title: '확인하기' },
        { action: 'complete', title: '완료하기' },
        { action: 'dismiss', title: '닫기' }
      ],
      requireInteraction: true, // 중요 알림은 사용자가 닫을 때까지 유지
      vibrate: [200, 100, 200] // 진동 패턴
    };

    event.waitUntil(
      self.registration.showNotification(title, options)
    );
  } else {
    // 일반 알림 처리
    const title = data.title || '새로운 알림';
    const options = {
      body: data.body || '내용이 없습니다.',
      icon: data.icon || '/icon.png',
      badge: '/badge.png',
      data: { url: data.url || '/' }
    };

    event.waitUntil(
      self.registration.showNotification(title, options)
    );
  }
});

// 알림 클릭 이벤트
self.addEventListener('notificationclick', event => {
  event.notification.close();

  if (event.action === 'view') {
    // 확인하기 - 과제 페이지 열기
    const urlToOpen = event.notification.data.url;
    event.waitUntil(
      clients.openWindow(urlToOpen)
    );
  } else if (event.action === 'complete') {
    // 완료하기 - API 호출
    const assignmentId = event.notification.data.assignmentId;
    event.waitUntil(
      fetch(`/api/assignments/${assignmentId}/complete`, {
        method: 'POST',
        credentials: 'include'
      }).then(() => {
        // 완료 후 페이지 열기
        return clients.openWindow('/assignments');
      })
    );
  } else if (!event.action || event.action === 'dismiss') {
    // 기본 동작 또는 닫기
    if (event.notification.data.url) {
      event.waitUntil(
        clients.openWindow(event.notification.data.url)
      );
    }
  }
});
```

##### 3. 애플리케이션 초기화

```javascript
// app.js
document.addEventListener('DOMContentLoaded', async () => {
  // 알림 권한 요청
  if ('Notification' in window && Notification.permission === 'default') {
    const permission = await Notification.requestPermission();
    console.log('알림 권한:', permission);
  }

  // Service Worker 등록
  if ('serviceWorker' in navigator) {
    try {
      const registration = await navigator.serviceWorker.register('/sw.js');
      console.log('Service Worker 등록 성공:', registration);
    } catch (error) {
      console.error('Service Worker 등록 실패:', error);
    }
  }

  // WebSocket 연결
  const notificationClient = new NotificationClient();
  notificationClient.connect(currentUsername);

  // 과제 관리자 초기화
  window.assignmentManager = new AssignmentManager(notificationClient);
  await assignmentManager.initialize();

  // Web Push 구독
  await subscribeToPush();

  // 1분마다 UI 업데이트 (남은 시간 표시)
  setInterval(() => {
    assignmentManager.renderAssignments();
  }, 60000);
});

// Web Push 구독 함수
async function subscribeToPush() {
  try {
    const registration = await navigator.serviceWorker.ready;

    // Public Key 가져오기
    const response = await fetch('/api/push/public-key');
    const { publicKey } = await response.json();

    const convertedKey = urlBase64ToUint8Array(publicKey);

    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: convertedKey
    });

    // 서버에 구독 정보 전송
    await fetch('/api/push/subscribe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(subscription)
    });

    console.log('Web Push 구독 완료');
  } catch (error) {
    console.error('Web Push 구독 실패:', error);
  }
}
```

##### 4. HTML & CSS

```html
<!-- assignments.html -->
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>과제 관리</title>
  <link rel="stylesheet" href="/css/assignments.css">
</head>
<body>
  <div class="container">
    <header>
      <h1>📚 과제 관리</h1>
      <button id="btn-create-assignment">새 과제 추가</button>
    </header>

    <div id="assignments-list"></div>
    <div id="toast-container"></div>
  </div>

  <script src="/js/stomp.min.js"></script>
  <script src="/js/sockjs.min.js"></script>
  <script src="/js/notification-client.js"></script>
  <script src="/js/assignment-manager.js"></script>
  <script src="/js/app.js"></script>
</body>
</html>
```

```css
/* assignments.css */
.assignment-item {
  background: white;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 16px;
  box-shadow: 0 2px 4px rgba(0,0,0,0.1);
  transition: box-shadow 0.2s;
}

.assignment-item:hover {
  box-shadow: 0 4px 8px rgba(0,0,0,0.15);
}

.status-overdue {
  border-left: 4px solid #dc3545;
}

.status-completed {
  opacity: 0.7;
  background: #f8f9fa;
}

.time-remaining.urgent {
  color: #dc3545;
  font-weight: bold;
}

.time-remaining.warning {
  color: #fd7e14;
  font-weight: bold;
}

.time-remaining.normal {
  color: #28a745;
}

.toast {
  background: white;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  margin-bottom: 12px;
  min-width: 300px;
  animation: slideIn 0.3s ease-out;
}

.toast-warning {
  border-left: 4px solid #fd7e14;
}

.toast-info {
  border-left: 4px solid #0dcaf0;
}

@keyframes slideIn {
  from {
    transform: translateX(100%);
    opacity: 0;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}
```

##### 5. 프론트엔드 동작 원리 요약

```
1️⃣ 초기화 단계
   - Service Worker 등록
   - Web Push 구독
   - WebSocket 연결
   - 과제 목록 로드
   - 로컬 타이머 설정

2️⃣ 알림 수신 경로

   [서버 알림 발송]
         ↓
   ┌─────────────┬─────────────┐
   │             │             │
   [WebSocket]  [Push Service]
   │             │             │
   실시간 전달  Service Worker
   │             │
   └──────┬──────┘
          ↓
   [브라우저 알림 표시]
          ↓
   [UI 업데이트]

3️⃣ 로컬 타이머
   - 과제 생성 시 setTimeout으로 1시간 전, 정각 알림 예약
   - 브라우저가 열려있을 때 로컬에서 알림 발송
   - 서버 알림의 백업 역할

4️⃣ 알림 클릭 처리
   - 확인하기: 과제 페이지로 이동
   - 완료하기: API 호출 후 목록 페이지로 이동
   - 닫기: 알림만 닫기

5️⃣ 실시간 업데이트
   - WebSocket으로 서버 이벤트 수신
   - 과제 상태 변경 시 UI 즉시 반영
   - 1분마다 남은 시간 자동 업데이트
```

#### 통합 예시: 채팅 애플리케이션

```java
// ChatService.java
@Service
@RequiredArgsConstructor
public class ChatService {

    private final HybridNotificationService notificationService;

    public void sendMessage(Long senderId, Long receiverId, String message) {
        // 메시지 저장
        saveMessage(senderId, receiverId, message);

        // 알림 생성
        NotificationPayload payload = NotificationPayload.builder()
            .title("새로운 메시지")
            .body(message)
            .icon("/icons/message.png")
            .url("/chat/" + senderId)
            .build();

        // Hybrid 방식으로 알림 전송
        User receiver = userRepository.findById(receiverId).orElseThrow();
        notificationService.sendNotification(receiverId, receiver.getUsername(), payload);
    }
}
```

#### WebSocket 재연결 로직

```javascript
// reconnection-handler.js
class ReconnectingWebSocket {
  constructor(url) {
    this.url = url;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 5;
    this.reconnectDelay = 1000;
  }

  connect() {
    const socket = new SockJS(this.url);
    this.stompClient = Stomp.over(socket);

    this.stompClient.connect({},
      () => {
        console.log('연결 성공');
        this.reconnectAttempts = 0;
        this.subscribeToChannels();
      },
      (error) => {
        console.error('연결 실패:', error);
        this.handleReconnect();
      }
    );
  }

  handleReconnect() {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++;
      const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts - 1);

      console.log(`${delay}ms 후 재연결 시도 (${this.reconnectAttempts}/${this.maxReconnectAttempts})`);

      setTimeout(() => this.connect(), delay);
    } else {
      console.error('최대 재연결 시도 횟수 초과');
    }
  }

  subscribeToChannels() {
    // 구독 로직
  }
}
```

#### 알림 우선순위 전략

```java
// NotificationPriority.java
public enum NotificationPriority {
    LOW,      // WebSocket만 (온라인 시)
    NORMAL,   // Hybrid (온라인: WebSocket, 오프라인: Web Push)
    HIGH,     // 항상 양쪽 (WebSocket + Web Push)
    CRITICAL  // 항상 양쪽 + 재시도 로직
}

// NotificationStrategy.java
@Service
@RequiredArgsConstructor
public class NotificationStrategy {

    private final HybridNotificationService hybridService;
    private final WebSocketEventListener webSocketListener;
    private final PushNotificationService pushService;

    public void send(Long userId, String username, NotificationPayload payload,
                     NotificationPriority priority) {

        switch (priority) {
            case LOW:
                if (webSocketListener.isUserOnline(username)) {
                    hybridService.sendViaWebSocket(username, payload);
                }
                break;

            case NORMAL:
                hybridService.sendNotification(userId, username, payload);
                break;

            case HIGH:
            case CRITICAL:
                hybridService.sendCriticalNotification(userId, username, payload);
                break;
        }
    }
}
```

### 고급 기능

#### 타임존 고려한 예약 발송

```java
// ScheduledNotificationService.java
@Service
@RequiredArgsConstructor
public class ScheduledNotificationService {

    private final PushNotificationService pushService;
    private final TaskScheduler taskScheduler;

    /**
     * 사용자 타임존에 맞춰 예약 발송
     */
    public void scheduleNotification(Long userId, ZoneId timezone,
                                      LocalTime sendTime, NotificationPayload payload) {

        ZonedDateTime now = ZonedDateTime.now(timezone);
        ZonedDateTime scheduledTime = now.with(sendTime);

        // 이미 지난 시간이면 다음날로
        if (scheduledTime.isBefore(now)) {
            scheduledTime = scheduledTime.plusDays(1);
        }

        Instant instant = scheduledTime.toInstant();

        taskScheduler.schedule(() -> {
            pushService.sendPushToUser(userId, payload);
        }, instant);
    }

    /**
     * 매일 특정 시간에 반복 발송
     */
    @Scheduled(cron = "0 0 9 * * *") // 매일 오전 9시
    public void sendDailyNotification() {
        NotificationPayload payload = NotificationPayload.builder()
            .title("오늘의 할 일")
            .body("새로운 하루를 시작하세요!")
            .build();

        pushService.sendPushToAll(payload);
    }
}
```

#### 배치 작업과 알림 큐

```java
// NotificationQueue.java
@Service
@RequiredArgsConstructor
public class NotificationQueueService {

    private final Queue<NotificationTask> notificationQueue = new ConcurrentLinkedQueue<>();
    private final PushNotificationService pushService;

    /**
     * 알림을 큐에 추가
     */
    public void enqueue(Long userId, NotificationPayload payload) {
        notificationQueue.offer(new NotificationTask(userId, payload));
    }

    /**
     * 큐에서 알림을 꺼내 배치로 발송
     */
    @Scheduled(fixedDelay = 5000) // 5초마다 실행
    public void processQueue() {
        List<NotificationTask> batch = new ArrayList<>();

        // 최대 100개씩 배치 처리
        for (int i = 0; i < 100 && !notificationQueue.isEmpty(); i++) {
            NotificationTask task = notificationQueue.poll();
            if (task != null) {
                batch.add(task);
            }
        }

        if (!batch.isEmpty()) {
            batch.parallelStream().forEach(task -> {
                pushService.sendPushToUser(task.getUserId(), task.getPayload());
            });
        }
    }
}

@Data
@AllArgsConstructor
class NotificationTask {
    private Long userId;
    private NotificationPayload payload;
}
```

### 실전 예시: 과제 기한 알림 시스템

사용자에게 할당된 과제가 기한 내에 완료되지 않았을 때 자동으로 알림을 발송하는 시스템 구현 예시입니다.

#### 방법 1: 주기적 체크 방식 (간단, 추천)

매 시간마다 기한이 지난 미완료 과제를 확인하여 알림을 발송합니다.

```java
// Assignment.java (과제 Entity)
@Entity
@Table(name = "assignments")
@Getter
@Setter
@NoArgsConstructor
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentStatus status = AssignmentStatus.PENDING;

    @Column(nullable = false)
    private boolean notificationSent = false; // 알림 발송 여부

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}

public enum AssignmentStatus {
    PENDING,    // 대기 중
    IN_PROGRESS, // 진행 중
    COMPLETED,   // 완료
    OVERDUE      // 기한 초과
}
```

```java
// AssignmentRepository.java
@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    // 기한이 지났고 완료되지 않은 과제 조회
    @Query("SELECT a FROM Assignment a WHERE a.deadline < :now " +
           "AND a.status != 'COMPLETED' " +
           "AND a.notificationSent = false")
    List<Assignment> findOverdueAssignments(@Param("now") LocalDateTime now);

    // 특정 시간 이후 기한이 도래하는 미완료 과제 (사전 알림용)
    @Query("SELECT a FROM Assignment a WHERE a.deadline BETWEEN :start AND :end " +
           "AND a.status != 'COMPLETED'")
    List<Assignment> findUpcomingDeadlines(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );

    // 사용자별 미완료 과제
    List<Assignment> findByUserIdAndStatusNot(Long userId, AssignmentStatus status);
}
```

```java
// AssignmentNotificationService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentNotificationService {

    private final AssignmentRepository assignmentRepository;
    private final HybridNotificationService notificationService;
    private final UserRepository userRepository;

    /**
     * 매 시간마다 기한이 지난 과제 확인 및 알림 발송
     */
    @Scheduled(cron = "0 0 * * * *") // 매 시간 정각
    public void checkOverdueAssignments() {
        LocalDateTime now = LocalDateTime.now();
        List<Assignment> overdueAssignments = assignmentRepository.findOverdueAssignments(now);

        log.info("기한 초과 과제 {} 건 발견", overdueAssignments.size());

        for (Assignment assignment : overdueAssignments) {
            sendOverdueNotification(assignment);

            // 상태 업데이트
            assignment.setStatus(AssignmentStatus.OVERDUE);
            assignment.setNotificationSent(true);
            assignmentRepository.save(assignment);
        }
    }

    /**
     * 기한 초과 알림 발송
     */
    private void sendOverdueNotification(Assignment assignment) {
        User user = userRepository.findById(assignment.getUserId()).orElseThrow();

        NotificationPayload payload = NotificationPayload.builder()
            .title("⚠️ 과제 기한 초과")
            .body(String.format("'%s' 과제의 기한이 지났습니다.", assignment.getTitle()))
            .icon("/icons/warning.png")
            .url("/assignments/" + assignment.getId())
            .build();

        // Hybrid 방식으로 알림 전송 (중요 알림)
        notificationService.sendCriticalNotification(
            assignment.getUserId(),
            user.getUsername(),
            payload
        );

        log.info("기한 초과 알림 발송: {} -> {}", user.getUsername(), assignment.getTitle());
    }

    /**
     * 기한 24시간 전 사전 알림
     */
    @Scheduled(cron = "0 0 9 * * *") // 매일 오전 9시
    public void sendUpcomingDeadlineReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime tomorrow = now.plusHours(24);

        List<Assignment> upcomingAssignments =
            assignmentRepository.findUpcomingDeadlines(now, tomorrow);

        log.info("24시간 내 기한 도래 과제 {} 건", upcomingAssignments.size());

        for (Assignment assignment : upcomingAssignments) {
            sendReminderNotification(assignment);
        }
    }

    /**
     * 사전 알림 발송
     */
    private void sendReminderNotification(Assignment assignment) {
        User user = userRepository.findById(assignment.getUserId()).orElseThrow();

        long hoursUntilDeadline = ChronoUnit.HOURS.between(
            LocalDateTime.now(),
            assignment.getDeadline()
        );

        NotificationPayload payload = NotificationPayload.builder()
            .title("📌 과제 마감 임박")
            .body(String.format("'%s' 과제가 %d시간 후 마감됩니다.",
                  assignment.getTitle(), hoursUntilDeadline))
            .icon("/icons/reminder.png")
            .url("/assignments/" + assignment.getId())
            .build();

        notificationService.sendNotification(
            assignment.getUserId(),
            user.getUsername(),
            payload
        );
    }
}
```

#### 방법 2: 동적 스케줄링 방식 (정확한 시간)

과제 생성 시 정확한 기한 시간에 알림을 예약합니다.

```java
// DynamicAssignmentScheduler.java
@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicAssignmentScheduler {

    private final TaskScheduler taskScheduler;
    private final HybridNotificationService notificationService;
    private final AssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    // 스케줄된 작업 추적
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * 과제 생성 시 기한 알림 예약
     */
    public void scheduleDeadlineNotification(Assignment assignment) {
        LocalDateTime deadline = assignment.getDeadline();
        Instant deadlineInstant = deadline.atZone(ZoneId.systemDefault()).toInstant();

        // 이미 지난 시간이면 예약하지 않음
        if (deadlineInstant.isBefore(Instant.now())) {
            log.warn("과제 {} 기한이 이미 지났습니다.", assignment.getId());
            return;
        }

        // 기한 정각에 알림 발송
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            checkAndNotifyIfNotCompleted(assignment.getId());
        }, deadlineInstant);

        scheduledTasks.put(assignment.getId(), future);
        log.info("과제 {} 알림 예약: {}", assignment.getId(), deadline);

        // 추가: 24시간 전 사전 알림도 예약
        scheduleReminderNotification(assignment, 24);
        // 1시간 전 알림
        scheduleReminderNotification(assignment, 1);
    }

    /**
     * 사전 알림 예약
     */
    private void scheduleReminderNotification(Assignment assignment, int hoursBefore) {
        LocalDateTime reminderTime = assignment.getDeadline().minusHours(hoursBefore);
        Instant reminderInstant = reminderTime.atZone(ZoneId.systemDefault()).toInstant();

        if (reminderInstant.isBefore(Instant.now())) {
            return; // 이미 지난 시간
        }

        taskScheduler.schedule(() -> {
            sendReminderIfNotCompleted(assignment.getId(), hoursBefore);
        }, reminderInstant);

        log.info("과제 {} 사전 알림 예약: {}시간 전", assignment.getId(), hoursBefore);
    }

    /**
     * 기한 도래 시 완료 여부 확인 및 알림
     */
    private void checkAndNotifyIfNotCompleted(Long assignmentId) {
        Optional<Assignment> optionalAssignment = assignmentRepository.findById(assignmentId);

        if (optionalAssignment.isEmpty()) {
            return;
        }

        Assignment assignment = optionalAssignment.get();

        // 이미 완료된 과제는 알림 발송 안 함
        if (assignment.getStatus() == AssignmentStatus.COMPLETED) {
            log.info("과제 {} 완료됨, 알림 발송 안 함", assignmentId);
            return;
        }

        // 기한 초과 알림 발송
        User user = userRepository.findById(assignment.getUserId()).orElseThrow();

        NotificationPayload payload = NotificationPayload.builder()
            .title("⚠️ 과제 기한 초과")
            .body(String.format("'%s' 과제의 기한이 지났습니다.", assignment.getTitle()))
            .icon("/icons/warning.png")
            .url("/assignments/" + assignment.getId())
            .build();

        notificationService.sendCriticalNotification(
            assignment.getUserId(),
            user.getUsername(),
            payload
        );

        // 상태 업데이트
        assignment.setStatus(AssignmentStatus.OVERDUE);
        assignment.setNotificationSent(true);
        assignmentRepository.save(assignment);

        // 스케줄 맵에서 제거
        scheduledTasks.remove(assignmentId);
    }

    /**
     * 사전 알림 발송
     */
    private void sendReminderIfNotCompleted(Long assignmentId, int hoursBefore) {
        Optional<Assignment> optionalAssignment = assignmentRepository.findById(assignmentId);

        if (optionalAssignment.isEmpty()) {
            return;
        }

        Assignment assignment = optionalAssignment.get();

        if (assignment.getStatus() == AssignmentStatus.COMPLETED) {
            return;
        }

        User user = userRepository.findById(assignment.getUserId()).orElseThrow();

        NotificationPayload payload = NotificationPayload.builder()
            .title("📌 과제 마감 임박")
            .body(String.format("'%s' 과제가 %d시간 후 마감됩니다.",
                  assignment.getTitle(), hoursBefore))
            .icon("/icons/reminder.png")
            .url("/assignments/" + assignment.getId())
            .build();

        notificationService.sendNotification(
            assignment.getUserId(),
            user.getUsername(),
            payload
        );
    }

    /**
     * 과제 완료 시 스케줄 취소
     */
    public void cancelScheduledNotification(Long assignmentId) {
        ScheduledFuture<?> future = scheduledTasks.remove(assignmentId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            log.info("과제 {} 알림 스케줄 취소", assignmentId);
        }
    }

    /**
     * 애플리케이션 시작 시 기존 과제들 재스케줄링
     */
    @PostConstruct
    public void rescheduleExistingAssignments() {
        List<Assignment> pendingAssignments = assignmentRepository
            .findByUserIdAndStatusNot(null, AssignmentStatus.COMPLETED);

        for (Assignment assignment : pendingAssignments) {
            if (assignment.getDeadline().isAfter(LocalDateTime.now())) {
                scheduleDeadlineNotification(assignment);
            }
        }

        log.info("기존 과제 {} 건 재스케줄링 완료", pendingAssignments.size());
    }
}
```

#### AssignmentService (과제 관리)

```java
// AssignmentService.java
@Service
@RequiredArgsConstructor
@Transactional
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final DynamicAssignmentScheduler scheduler;

    /**
     * 과제 생성
     */
    public Assignment createAssignment(Long userId, String title,
                                        String description, LocalDateTime deadline) {
        Assignment assignment = new Assignment();
        assignment.setUserId(userId);
        assignment.setTitle(title);
        assignment.setDescription(description);
        assignment.setDeadline(deadline);
        assignment.setStatus(AssignmentStatus.PENDING);

        Assignment saved = assignmentRepository.save(assignment);

        // 알림 스케줄링
        scheduler.scheduleDeadlineNotification(saved);

        return saved;
    }

    /**
     * 과제 완료 처리
     */
    public void completeAssignment(Long assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new IllegalArgumentException("과제를 찾을 수 없습니다."));

        assignment.setStatus(AssignmentStatus.COMPLETED);
        assignment.setCompletedAt(LocalDateTime.now());
        assignmentRepository.save(assignment);

        // 알림 스케줄 취소
        scheduler.cancelScheduledNotification(assignmentId);
    }

    /**
     * 과제 기한 수정
     */
    public void updateDeadline(Long assignmentId, LocalDateTime newDeadline) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new IllegalArgumentException("과제를 찾을 수 없습니다."));

        assignment.setDeadline(newDeadline);
        assignmentRepository.save(assignment);

        // 기존 스케줄 취소 후 재스케줄링
        scheduler.cancelScheduledNotification(assignmentId);
        scheduler.scheduleDeadlineNotification(assignment);
    }
}
```

#### Controller

```java
// AssignmentController.java
@RestController
@RequestMapping("/api/assignments")
@RequiredArgsConstructor
public class AssignmentController {

    private final AssignmentService assignmentService;

    /**
     * 과제 생성
     */
    @PostMapping
    public ResponseEntity<Assignment> createAssignment(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestBody CreateAssignmentRequest request) {

        Assignment assignment = assignmentService.createAssignment(
            user.getId(),
            request.getTitle(),
            request.getDescription(),
            request.getDeadline()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(assignment);
    }

    /**
     * 과제 완료
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<Void> completeAssignment(@PathVariable Long id) {
        assignmentService.completeAssignment(id);
        return ResponseEntity.ok().build();
    }

    /**
     * 과제 기한 수정
     */
    @PutMapping("/{id}/deadline")
    public ResponseEntity<Void> updateDeadline(
            @PathVariable Long id,
            @RequestBody UpdateDeadlineRequest request) {

        assignmentService.updateDeadline(id, request.getDeadline());
        return ResponseEntity.ok().build();
    }
}

@Data
class CreateAssignmentRequest {
    private String title;
    private String description;
    private LocalDateTime deadline;
}

@Data
class UpdateDeadlineRequest {
    private LocalDateTime deadline;
}
```

#### 두 방식의 장단점 비교

| 방식 | 장점 | 단점 | 사용 사례 |
|------|------|------|-----------|
| **주기적 체크** | - 구현 간단<br>- 서버 재시작 시 자동 복구<br>- 메모리 사용량 적음 | - 정확한 시간 보장 안 됨<br>- 불필요한 DB 조회 | 대부분의 경우 추천 |
| **동적 스케줄링** | - 정확한 시간에 알림<br>- DB 조회 최소화 | - 구현 복잡<br>- 메모리 사용<br>- 재시작 시 재스케줄링 필요 | 정확한 시간이 중요한 경우 |

#### 알림 종류별 전략

```java
// NotificationTiming.java (알림 타이밍 전략)
public enum NotificationTiming {
    // 기한 3일 전
    THREE_DAYS_BEFORE(Duration.ofDays(3), "3일 후 마감됩니다"),
    // 기한 1일 전
    ONE_DAY_BEFORE(Duration.ofDays(1), "내일 마감됩니다"),
    // 기한 1시간 전
    ONE_HOUR_BEFORE(Duration.ofHours(1), "1시간 후 마감됩니다"),
    // 기한 초과
    OVERDUE(Duration.ZERO, "기한이 지났습니다");

    private final Duration beforeDeadline;
    private final String message;

    NotificationTiming(Duration beforeDeadline, String message) {
        this.beforeDeadline = beforeDeadline;
        this.message = message;
    }

    public LocalDateTime calculateNotificationTime(LocalDateTime deadline) {
        return deadline.minus(beforeDeadline);
    }

    public String getMessage() {
        return message;
    }
}
```

## 핵심 정리

### Web Push 기본
- Web Push는 Application Server → Push Service → Browser 경로로 전달됨
- VAPID 키를 통해 서버 인증을 수행하며, Public Key는 클라이언트에서, Private Key는 서버에서만 사용
- 프론트엔드는 Service Worker와 Push Manager API를 통해 구독 생성 및 푸시 수신
- PushSubscription 객체는 endpoint, p256dh, auth 키를 포함하며 데이터베이스에 저장 필요
- HTTPS 환경 필수, 사용자 권한 동의 필요, 메시지 크기 4KB 제한
- HTTP 410 응답 시 만료된 구독 정보를 정리해야 함

### Spring Boot 구현
- nl.martijndwars:web-push 라이브러리 사용
- JPA Entity로 구독 정보 관리, Repository 패턴 적용
- @Async를 활용한 비동기 푸시 발송으로 성능 최적화
- VAPID 키는 application.yml에 설정하고 @ConfigurationProperties로 주입
- BouncyCastle Provider를 Security에 등록하여 암호화 처리

### WebSocket vs Web Push
- WebSocket: 브라우저 열려있을 때만 작동, 실시간성 우수, 양방향 통신
- Web Push: 브라우저 닫혀있어도 작동, 약간의 지연, 단방향 통신
- Hybrid 전략: 온라인 시 WebSocket, 오프라인 시 Web Push 사용
- 중요 알림은 두 채널 모두 사용하여 확실한 전달 보장

### WebSocket 통합
- Spring WebSocket + STOMP 프로토콜 사용
- SimpMessagingTemplate으로 특정 사용자나 토픽에 메시지 전송
- WebSocketEventListener로 사용자 연결/해제 상태 추적
- 재연결 로직과 Exponential Backoff 적용으로 안정성 확보
- 알림 우선순위에 따라 전송 전략 차별화 (LOW/NORMAL/HIGH/CRITICAL)

### 과제 기한 알림 시스템 (백엔드)
- **주기적 체크 방식**: @Scheduled로 매 시간 기한 초과 과제 확인 (구현 간단, 추천)
- **동적 스케줄링 방식**: TaskScheduler로 과제 생성 시 정확한 기한에 알림 예약
- 사전 알림 기능: 24시간 전, 1시간 전 등 여러 시점에 리마인더 발송
- 과제 완료 시 스케줄 취소, 기한 수정 시 재스케줄링
- 애플리케이션 재시작 시 기존 과제 자동 재스케줄링 (@PostConstruct)
- notificationSent 플래그로 중복 알림 방지

### 과제 기한 알림 시스템 (프론트엔드)
- **3가지 알림 경로**: 로컬 타이머, WebSocket 실시간 알림, Web Push 백그라운드 알림
- **AssignmentManager 클래스**: 과제 CRUD, 타이머 관리, 알림 구독, UI 렌더링 통합 관리
- **로컬 타이머**: setTimeout으로 1시간 전/정각 알림 예약 (브라우저 열려있을 때)
- **Service Worker**: Web Push 수신 시 actions 버튼 제공 (확인하기/완료하기/닫기)
- **실시간 UI 업데이트**: WebSocket으로 과제 상태 변경 즉시 반영, 1분마다 남은 시간 갱신
- **알림 클릭 핸들링**: notificationclick 이벤트로 과제 페이지 이동 또는 완료 API 호출
- **시각적 피드백**: 남은 시간에 따라 색상 변경 (urgent/warning/normal), 토스트 알림 표시

## 참고 자료
- [MDN Web Push API](https://developer.mozilla.org/en-US/docs/Web/API/Push_API)
- [MDN Notifications API](https://developer.mozilla.org/en-US/docs/Web/API/Notifications_API)
- [Web Push Protocol RFC 8030](https://datatracker.ietf.org/doc/html/rfc8030)
- [VAPID RFC 8292](https://datatracker.ietf.org/doc/html/rfc8292)
- [web-push Java 라이브러리](https://github.com/web-push-libs/webpush-java)
- [Service Worker 명세](https://w3c.github.io/ServiceWorker/)
- [Spring WebSocket 공식 문서](https://docs.spring.io/spring-framework/reference/web/websocket.html)
- [STOMP Protocol](https://stomp.github.io/)
