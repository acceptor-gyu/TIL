# [ARCHITECTURE] Hexagonal Architecture — port와 adapter는 각각 어떤 책임을 가지나요?

> 면접 예상 질문 대비용 정리 (port/adapter 책임 분리 관련 꼬리질문 포함)

## 개요
- 질문 의도: "포트는 인터페이스, 어댑터는 구현체", **어떤 코드를 포트에 두고 어떤 코드를 어댑터에 둘지 판단할 기준을 갖고 있는가**. 이 기준이 없으면 포트에 JPA가 새어 나오거나, 구현체가 하나뿐인 인터페이스만 만들고 끝난다.
- 전제 정리: 헥사고날 아키텍처의 목적은 "육각형을 그리는 것"이 아니라 **애플리케이션이 외부 세계에 대해 갖는 의존성의 방향을 뒤집는 것**이다. 그래서 포트/어댑터 책임 분리는 결국 하나의 질문으로 환원된다 — **"이 지식(knowledge)의 소유권은 애플리케이션에 있는가, 외부 기술에 있는가?"**

| 구분 | 소유권 | 사용하는 어휘 | 바뀌는 이유 |
| --- | --- | --- | --- |
| Port | 애플리케이션 | 도메인/유스케이스 언어 (`Order`, `Money`, `NotificationSender`) | 비즈니스 요구가 바뀔 때 |
| Adapter | 외부 기술 | 기술 언어 (`ResultSet`, `HttpEntity`, `ConsumerRecord`) | 기술/벤더/스펙이 바뀔 때 |

- 답변의 축: **소유권(누가 계약을 정의하는가)**, **어휘(계약이 무슨 언어로 쓰였는가)**, **변경 사유(무엇이 바뀌면 이 파일이 수정되는가)**. 이 세 축으로 모든 꼬리질문이 정리된다.
- 관련 정리: [헥사고날 아키텍처](../ARCHITECTURE/260615_01_헥사고날_아키텍처.md) — 구성 요소와 패키지 구조는 여기서 다루고, 이 문서는 면접 답변 논리와 판단 기준에 집중한다.

## 상세 내용

### 0. 먼저 비유로 전체 그림 잡기

포트/어댑터가 끝까지 헷갈리는 이유는 대부분 **포트를 '등장인물'로 상상하기 때문**이다. 결론부터 말하면 이렇다.

> **포트는 계약서다.** 일하는 주체(공장, 운송업체, 거래처, 판매 채널)는 전부 어댑터이거나 애플리케이션 코어이고, 포트는 그 사이에 놓인 **발주서·주문서 양식**이다.

#### 0-1. 가구 회사 비유
우리가 소파를 만드는 가구 회사라고 하자.

| 비유 속 대상 | 헥사고날 요소 | 이유 |
| --- | --- | --- |
| 작업장의 제작 공정과 설계도·제작 규칙 | Domain + Application (코어) | 실제 가치를 만드는 곳. 여기가 사라지면 회사가 사라진다 |
| **우리가 쓰는 발주서 양식** ("미송 원목 30mm 10장") | **Outbound Port** | 우리 회사 언어로만 쓰여 있다. 어느 업체·어떤 트럭·어떤 결제 조건인지는 적혀 있지 않다 |
| A목재상 / 수입상 / 운송업체 | **Outbound Adapter** | 실제로 일하는 주체. 각자 접수 방식(전화·팩스·API)이 다르고, **교체 가능하다** |
| **우리가 받는 주문서 양식**(= 제품 카탈로그) | **Inbound Port** | "우리에게 주문할 수 있는 것"의 목록 = 유스케이스 목록 |
| 오프라인 매장 / 자사몰 / 쿠팡 / 전화 주문 | **Inbound Adapter** | 각 채널의 주문 형식을 우리 주문서 양식으로 번역한다 |
| 창고업체 | Persistence Adapter | 완성품을 맡기고 찾아오는 곳. "어느 랙에 뒀는지"는 창고 사정이다 |

#### 0-2. "포트 = 운송업체, 어댑터 = 생산 공장"은 절반만 맞다
이 감각에서 **맞는 절반**은 분명하다.
- 운송업체가 **교체 가능하고**, **우리 내부 규칙을 모르고**, **실제로 일을 한다**는 감각 → 정확히 어댑터의 성질이다.
- 공장이 **우리 것이고 함부로 바뀌지 않는 중심**이라는 감각 → 정확히 애플리케이션 코어의 성질이다.

**밀린 절반**은 이것이다.

| 처음 떠올린 비유 | 실제 대응 | 이유 |
| --- | --- | --- |
| 포트 = 운송업체 | 운송업체 = **어댑터** | 운송업체는 '주체'다. 실제로 운반하고, 다른 업체로 교체된다 |
| 어댑터 = 생산 공장 | 생산 공장 = **애플리케이션 코어** | 공장은 번역자가 아니라 가치를 만드는 본체다 |
| (비어 있던 자리) | 포트 = **발주서 양식** | 포트는 사람도 회사도 아니고, 주체들 사이의 계약 문서다 |

즉 **주체 하나를 포트에 매칭하려는 순간 반드시 어댑터와 겹친다.** 포트에 대응하는 것은 항상 '양식·규격·계약'이어야 한다. 이 교정 하나가 1-3의 책임 목록 전체를 설명한다 — 계약서는 일을 하지 않으므로 포트에는 재시도도, 직렬화도, 커넥션 풀도 들어갈 수 없다.

#### 0-3. "adapter"라는 이름의 출처 — 콘센트 비유
가장 짧은 비유는 전기 플러그다.
- 벽면 콘센트의 **구멍 규격(220V, 2핀)** = 포트
- 해외 기기를 그 구멍에 꽂게 해 주는 **여행용 플러그 어댑터** = 어댑터
- 콘센트 규격은 **집(애플리케이션)이 정한다.** 기기가 정하지 않는다 → 계약의 소유권이 애플리케이션에 있다는 뜻
- 어댑터는 전기를 만들지도, 쓰지도 않는다. **모양만 번역한다** → 어댑터에 비즈니스 규칙이 들어가면 안 되는 이유

"어댑터"라는 용어 자체가 여기서 왔다는 점을 말하면, 어댑터의 책임이 '번역'이라는 것도 함께 설명된다.

#### 0-4. 비유가 깨지는 지점 (면접에서 조심할 곳)
비유는 그림을 잡는 용도이고, 다음 세 지점에서는 반드시 코드로 내려와야 한다.
- **의존성 방향**: 현실에서는 발주서 양식을 목재상이 요구하는 경우가 많다. 헥사고날은 반대로 **양식을 항상 우리가 정한다.** 비유가 이 방향성까지 알려주지는 않는다.
- **인바운드/아웃바운드의 비대칭**: 비유에서는 "주문 받는 쪽"과 "발주하는 쪽"이 자연스럽게 나뉘지만, 코드에서는 **아웃바운드만 DIP가 필요하다**는 점이 드러나지 않는다.
- **트랜잭션**: "발주와 창고 입고를 한 번에 되돌린다"에 대응하는 현실 물류 개념이 없다. 일관성 경계는 비유로 설명하기 어렵다.

면접에서는 **비유 한두 문장 → 곧바로 코드와 사례**로 내려가는 것이 좋다. 비유만 길게 말하면 "개념만 알고 써 본 적은 없다"는 인상을 준다.

---

### 1. 메인 질문: port와 adapter는 각각 어떤 책임을 가지는가

#### 1-1. 두괄식! (결론을 먼저)
**"포트는 애플리케이션이 외부에 요구하거나 제공하는 '능력'을 애플리케이션의 언어로 선언하는 계약이고, 어댑터는 그 계약을 특정 기술의 언어로 번역하는 책임을 가집니다. 즉 포트는 '무엇(what)'을, 어댑터는 '어떻게(how)'를 담당합니다."**

여기서 중요한 것은 계약의 **소유권**이다. 포트는 구현체 쪽이 아니라 **애플리케이션 쪽이 정의한다.** 이 한 가지가 지켜지지 않으면 인터페이스가 있어도 헥사고날이 아니다.

#### 1-2. 방향에 따른 두 종류
포트/어댑터는 "호출 방향"에 따라 두 갈래로 나뉜다. Cockburn의 원문 용어(primary/secondary)와 실무 용어(inbound/outbound)를 함께 알고 있으면 좋다.

| 방향 | 포트 이름 | 어댑터 이름 | 누가 누구를 호출하나 | 예시 |
| --- | --- | --- | --- | --- |
| 들어오는 쪽 | Inbound Port (Driving / Primary) | Driving Adapter | 어댑터 → 포트 구현체(유스케이스) | REST Controller, Kafka Consumer, Scheduler, CLI |
| 나가는 쪽 | Outbound Port (Driven / Secondary) | Driven Adapter | 유스케이스 → 포트 → 어댑터 | JPA Adapter, HTTP Client, S3 Adapter, Mail Adapter |

의존성 방향은 두 경우 모두 **어댑터 → 애플리케이션**이다. 인바운드는 자연스럽게 그렇게 되고, 아웃바운드는 DIP로 뒤집어서 그렇게 만든다. 아웃바운드가 헥사고날의 핵심인 이유가 이것이다.

#### 1-3. 책임 목록 (구체적으로)

**Inbound Port의 책임**
- 유스케이스 단위의 진입점을 하나의 메서드로 선언한다. (`CreateOrderUseCase.create(CreateOrderCommand)`)
- 입력 모델(Command/Query)을 애플리케이션 타입으로 정의한다. `HttpServletRequest`나 `@RequestBody` DTO를 여기에 노출하면 안 된다.
- **입력 유효성 검증의 경계**를 정한다: 형식 검증(필수값, 포맷)은 인바운드 어댑터, 비즈니스 불변식(재고 부족, 상태 전이 가능 여부)은 도메인.
- 트랜잭션 경계와 인가(authorization) 경계가 붙는 자리다.

**Inbound Adapter의 책임**
- 프로토콜 해석: HTTP 메서드/경로/헤더 파싱, 메시지 역직렬화, 스케줄 트리거.
- 외부 표현 ↔ 애플리케이션 모델 변환: `CreateOrderRequest` → `CreateOrderCommand`.
- 프로토콜 수준 응답 결정: 상태 코드, 헤더, 에러 바디 포맷. 도메인 예외를 HTTP 4xx/5xx로 사상하는 것도 어댑터 책임이다.
- 인증(토큰 파싱/검증) 같은 트랜스포트 관심사.

**Outbound Port의 책임**
- 애플리케이션이 **필요한 능력**을 선언한다. 능력 중심이라는 점이 중요하다: `SendNotificationPort`는 능력이고, `FcmClient`는 기술이다.
- 파라미터/반환형이 도메인 타입이어야 한다. `Order`를 받고 `Optional<Order>`를 반환한다. `OrderEntity`나 `OrderJpaDto`가 시그니처에 나오면 포트가 아니다.
- 실패 semantics를 계약에 포함한다: 어떤 예외를 던지는지, 멱등한지, 부분 실패가 가능한지. **이걸 명시하지 않은 포트는 어댑터를 갈아끼우는 순간 깨진다.**

**Outbound Adapter의 책임**
- 기술 호출: JPA/JDBC 실행, HTTP 요청, SDK 호출.
- 모델 변환: 도메인 애그리거트 ↔ 영속성 엔티티 / 외부 API 스키마.
- **기술 예외를 애플리케이션 예외로 번역**: `DataIntegrityViolationException` → `DuplicateOrderException`, `SocketTimeoutException` → `NotificationTemporarilyUnavailableException`. 어댑터가 이 번역을 안 하면 기술 예외가 유스케이스로 새어 나가고, 그 순간 유스케이스는 특정 기술에 묶인다.
- 기술적 신뢰성 처리: 재시도, 서킷 브레이커, 커넥션 풀, 타임아웃, 배치 크기, 캐시.

#### 1-4. 경계가 헷갈릴 때 쓰는 판정 질문 3개
실무에서 "이 코드 어디에 두지?"를 만나면 이 순서로 묻는다.

1. **"이 코드를 지우면 비즈니스 규칙이 사라지는가?"** → Yes면 도메인/유스케이스, No면 어댑터.
2. **"이 코드가 수정되는 이유가 기술/벤더/스펙 변경인가?"** → Yes면 어댑터. (SRP의 "변경 이유" 관점)
3. **"이 타입 이름을 도메인 전문가에게 말해도 알아들을까?"** → No면 포트 시그니처에 있을 수 없다.

전형적인 오배치 예시:

| 코드 | 잘못 두는 자리 | 올바른 자리 | 이유 |
| --- | --- | --- | --- |
| 결제 실패 시 3회 재시도 | 유스케이스 서비스 | 아웃바운드 어댑터 | 네트워크 신뢰성 = 기술 관심사 |
| 결제 실패 시 주문을 CANCELED로 | 어댑터 | 도메인 | 상태 전이 = 비즈니스 규칙 |
| 금액을 소수점 2자리로 반올림해 전송 | 도메인 | 어댑터 | 외부 API 스펙 요구사항 |
| 금액을 원 단위로 절사해 청구 | 어댑터 | 도메인 | 청구 정책 = 비즈니스 규칙 |
| `@Valid`, `@NotBlank` | 유스케이스 Command | 인바운드 어댑터 Request DTO | 형식 검증은 프로토콜 관심사 |

마지막 두 줄이 특히 좋은 답변 소재다. **"반올림"이 기술인지 비즈니스인지는 코드만 봐서는 알 수 없고, 왜 그렇게 하는지를 알아야 결정된다** — 아키텍처 경계는 문법이 아니라 의도로 갈린다는 점을 보여줄 수 있다.

---

### 2. 꼬리질문 ①: Port는 application layer에 두는 게 좋나요, domain layer에 두는 게 좋나요?

#### 2-1. 결론
**"인바운드 포트는 application에 둡니다. 아웃바운드 포트도 기본값은 application이고, 도메인 규칙이 직접 호출해야 하는 포트만 domain에 둡니다."**

판단 기준은 취향이 아니라 하나의 객관적 질문으로 정해진다 — **"그 포트를 호출하는 코드가 어디에 있는가?"**

> **비유로**: 발주서 양식을 **생산관리팀 서식함**(application)에 둘 것인가, **설계도 묶음 안**(domain)에 둘 것인가의 문제다. 기준은 하나다 — **그 발주서를 실제로 작성하는 사람이 누구인가.**
> 대부분은 생산관리팀이 쓴다(= 유스케이스 서비스가 포트를 호출). 그런데 "이 목재가 함수율 12% 이하인지 검사 성적서를 받아야 한다"처럼 **제작 규칙 자체가 외부 정보를 요구**하면, 그 양식은 설계도 안에 있어야 한다(= 환율·공휴일 포트가 domain으로 가는 경우).
> 제품 카탈로그(인바운드 포트)를 설계도 묶음에 끼워 넣지 않는 이유도 같다. 카탈로그는 "우리가 무엇을 만들 수 있는가"가 아니라 **고객이 무엇을 주문할 수 있는가**를 적은 문서이므로 설계도의 관심사가 아니다.

#### 2-2. 왜 인바운드 포트는 application인가
인바운드 포트는 **유스케이스**다. 유스케이스는 도메인 개념이 아니라 애플리케이션 개념이다.

- `CreateOrderUseCase`는 "주문 생성"이라는 **시스템 사용 시나리오**를 표현한다. 반면 `Order`는 주문 그 자체다.
- 인바운드 포트의 파라미터는 보통 `CreateOrderCommand` 같은 애플리케이션 전용 입력 모델이다. 이 타입은 도메인 모델이 아니라 "이 유스케이스가 받는 입력"이므로 domain에 둘 근거가 없다.
- 도메인 모델은 자기를 호출하는 유스케이스의 존재를 알아야 할 이유가 없다.

즉 domain 패키지에 `CreateOrderUseCase`가 있으면, 도메인이 애플리케이션 시나리오를 알고 있는 상태가 된다. 방향이 반대다.

#### 2-3. 아웃바운드 포트: 호출자를 따라간다
아웃바운드 포트는 두 부류로 갈린다.

**(1) 유스케이스 서비스만 호출하는 포트 → application/port/out (대부분)**
```java
// application/port/out/LoadOrderPort.java
public interface LoadOrderPort {
    Optional<Order> load(OrderId id);
}

// application/service/CancelOrderService.java
class CancelOrderService implements CancelOrderUseCase {
    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;

    public void cancel(CancelOrderCommand command) {
        Order order = loadOrderPort.load(command.orderId())
            .orElseThrow(OrderNotFoundException::new);
        order.cancel();                 // 도메인 규칙은 도메인 안에서
        saveOrderPort.save(order);
    }
}
```
조회 → 도메인 메서드 호출 → 저장 패턴에서는 포트를 호출하는 주체가 항상 application의 서비스다. 그러면 포트도 application에 있는 것이 자연스럽다. Tom Hombergs의 *buckpal* 예제 구조가 이 방식이고, 실무 Spring 프로젝트의 사실상 표준이다.

**(2) 도메인 규칙 자체가 외부 정보를 필요로 하는 포트 → domain**
```java
// domain/policy/ExchangeRateProvider.java  ← domain에 선언
public interface ExchangeRateProvider {
    ExchangeRate rateOf(Currency from, Currency to);
}

// domain/model/Order.java  ← 도메인이 직접 사용
public class Order {
    public Money totalIn(Currency target, ExchangeRateProvider rates) {
        return items.stream()
            .map(item -> item.amount().convert(target, rates))
            .reduce(Money.zero(target), Money::plus);
    }
}
```
이런 유형은 실제로 존재한다.
- `ExchangeRateProvider` — 환율 없이는 금액 계산 규칙 자체가 성립하지 않는다.
- `HolidayCalendar` — "영업일 3일 후 배송" 같은 규칙이 외부 달력을 필요로 한다.
- `EmailUniquenessChecker` — "이메일 중복 불가"가 도메인 불변식일 때.

DDD 관점에서는 도메인 서비스가 의존하는 추상이고, 이 경우 포트는 domain에 있어야 한다.

#### 2-4. 결정적 기준: 모듈 의존성으로 검증하기
말싸움을 끝내는 방법은 **Gradle 멀티모듈로 쪼개보는 것**이다.

```
:domain        ← 어떤 모듈도 의존하지 않는다 (순수 Java)
:application   ← implementation project(':domain')
:adapter-web   ← implementation project(':application')
:adapter-persistence ← implementation project(':application')
```

이 구조에서는 규칙이 자동으로 강제된다.
- `:domain`이 `:application`을 의존할 수 없으므로, **도메인 코드가 호출하는 포트는 반드시 `:domain`에 있어야 컴파일된다.**
- 반대로 application 서비스만 호출하는 포트를 domain에 두는 것은 컴파일은 되지만 의미가 없다 — domain 모듈에 아무도 안 쓰는 인터페이스가 쌓인다.

패키지만으로 나눌 때는 ArchUnit으로 같은 규칙을 검사한다.
```java
@ArchTest
static final ArchRule 도메인은_애플리케이션을_모른다 =
    noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAPackage("..application..");
```

#### 2-5. 이 답변에 붙는 함정 하나
포트를 domain에 두기로 했다면, **그 포트의 파라미터와 반환형도 전부 domain 타입이어야 한다.** `CreateOrderCommand`(application 개념)나 `Page<T>`(Spring 타입)를 쓰는 포트는 domain에 둘 수 없다. "포트를 domain에 두겠다"는 선택은 시그니처 순수성까지 함께 감당한다는 뜻이다. 이걸 감당할 자신이 없으면 application에 두는 것이 정직한 선택이다.

#### 2-6. 하지 말아야 할 답변
- "도메인이 정의해야 하니까 무조건 domain에 둡니다" — Cockburn이 말한 "애플리케이션 코어"는 domain + application을 합친 개념이다. "코어가 소유한다"와 "domain 패키지에 둔다"는 다른 이야기다.
- "adapter 패키지 안에 포트를 두고 어댑터가 구현합니다" — 구현체 쪽에 계약이 있으면 DIP가 성립하지 않는다. 이건 그냥 인터페이스가 하나 있는 레이어드다.

---

### 3. 꼬리질문 ②: Adapter 구현체를 갈아끼울 수 있다는 말은 실제로 어떤 변경을 의미하나요?

#### 3-1. 이 질문의 의도
"MySQL을 MongoDB로 바꿀 수 있습니다" 같은 교과서 문장을 **실제로 겪은 변경으로 환원할 수 있는지**를 본다. 그리고 그 교체가 실무에서 얼마나 드문 일인지 아는지도 함께 본다.

> **비유로**: 운송업체를 한진에서 CJ로 바꾸는 일이다. 발주서 양식과 설계도는 그대로이고, **바뀌는 것은 그 업체의 접수 양식으로 번역하는 부분과 계약 설정뿐**이다.
> 그런데 "익일 배송"을 전제로 생산 일정을 짜 두었는데 새 업체가 **주 2회 배송**이라면? 운송업체만 바꿔서는 안 되고 생산 계획 자체를 다시 짜야 한다. 이것이 3-4에서 말할 **포트 계약의 비기능 요건(지연·멱등성·순서 보장)이 바뀌면 유스케이스를 재작성해야 한다**와 정확히 같은 이야기다.
> 나머지 세 유형도 같은 비유 안에 있다. 테스트 대역 교체는 **실물 자재 없이 목업 자재로 조립 순서를 리허설**하는 것이고, 다중 어댑터 라우팅은 **지역별로 다른 배송사를 쓰는 것**이며, Strangler는 **신규 거래처에 병행 발주해 품질을 비교한 뒤 전환**하는 것이다.

#### 3-2. 실제로 자주 일어나는 "갈아끼우기" 4가지

**(1) 테스트 대역 교체 — 빈도 1위이자, 헥사고날 가치의 대부분**
```java
// 어댑터 없이 유스케이스만 테스트한다
class CancelOrderServiceTest {
    @Test
    void 배송이_시작된_주문은_취소할_수_없다() {
        var loadPort = new InMemoryOrderRepository();   // 포트의 Fake 구현
        loadPort.save(anOrder().shipped().build());
        var service = new CancelOrderService(loadPort, loadPort);

        assertThatThrownBy(() -> service.cancel(new CancelOrderCommand(ORDER_ID)))
            .isInstanceOf(OrderAlreadyShippedException.class);
    }
}
```
DB도 Spring 컨텍스트도 안 띄운다. 실행 시간이 초 단위에서 밀리초 단위로 내려가고, 테스트가 스키마 변경에 깨지지 않는다. **"어댑터 교체"의 실무 사례를 하나만 들라면 이것이다.**

**(2) 동일 목적 벤더 교체 — 현실적으로 일어나는 교체**
- 결제 PG: 토스페이먼츠 ↔ 포트원 ↔ 나이스페이
- 알림: FCM ↔ APNs ↔ Slack ↔ 카카오 알림톡
- 스토리지: S3 ↔ GCS ↔ MinIO
- 메일: SES ↔ SendGrid
- 인증: 자체 JWT ↔ Cognito ↔ Auth0

"DB 교체"는 거의 안 일어나지만 **벤더 교체는 실제로 자주 일어난다.** 단가 협상, 장애 이력, 리전 요구, 계약 종료 같은 비기술적 이유로 발생한다.

**(3) 하나의 포트에 여러 어댑터를 동시에 두고 라우팅**
```java
public interface SocialProfileLoader {
    SocialProfile load(AuthorizationCode code);
    boolean supports(SocialProvider provider);
}
// KakaoProfileLoader, GoogleProfileLoader, AppleProfileLoader ...
```
"갈아끼움"이 아니라 **동시 공존 + 선택**이다. 소셜 로그인, 지역별 배송사, A/B 실험이 여기 속한다. → [FACADE 패턴 - N개의 소셜 로그인이 필요한 경우를 예시로](../DESIGN/260903_02_FACADE_패턴_N개의_소셜_로그인이_필요한_경우를_예시로.md)

**(4) 점진적 마이그레이션(Strangler Fig)**
포트가 없으면 아예 시도할 수 없는 것이 이 유형이다.
```java
// 신규 저장소로 이관 중: 두 어댑터를 감싸는 데코레이터
class DualWriteOrderRepository implements SaveOrderPort {
    private final SaveOrderPort legacy;   // MySQL
    private final SaveOrderPort next;     // 새 저장소
    private final boolean compareEnabled;

    public void save(Order order) {
        legacy.save(order);
        try {
            next.save(order);             // shadow write
        } catch (Exception e) {
            log.warn("shadow write failed", e);   // 아직 신규는 실패해도 무시
        }
    }
}
```
읽기도 같은 방식으로 shadow read → 결과 비교 → 트래픽 전환 순으로 진행한다. **포트는 "이런 데코레이터를 끼울 자리"를 만들어 준다는 점에서 값을 한다.**

#### 3-3. 실제로 무엇이 바뀌고, 무엇이 안 바뀌는가
이 표를 말할 수 있으면 답변이 구체적으로 들린다. FCM에서 Slack으로 알림 채널을 바꾼다고 가정한다.

| 구분 | 파일 | 변경 |
| --- | --- | --- |
| 새로 추가 | `SlackNotificationAdapter` | 포트 구현 + Slack SDK 호출 |
| 새로 추가 | `SlackMessageMapper` | `Notification` → Slack Block Kit 페이로드 |
| 새로 추가 | `SlackAdapterIntegrationTest` | WireMock/Testcontainers 기반 |
| 수정 | `NotificationConfig`, `application.yml` | 빈 선택, 토큰/채널 설정 |
| 삭제(또는 보존) | `FcmNotificationAdapter` | 롤백 대비로 남겨두는 편이 많다 |
| **변경 없음** | `Notification`, `SendNotificationPort` | 도메인 모델, 포트 계약 |
| **변경 없음** | `OrderConfirmService` 등 유스케이스 | 알림 채널을 모른다 |
| **변경 없음** | 유스케이스 단위 테스트 | Fake 포트를 쓰므로 무관 |

전환 스위치는 설정으로 뺀다.
```java
@Configuration
class NotificationConfig {
    @Bean
    @ConditionalOnProperty(name = "notification.channel", havingValue = "slack")
    SendNotificationPort slackAdapter(SlackClient client) {
        return new SlackNotificationAdapter(client);
    }
    @Bean
    @ConditionalOnProperty(name = "notification.channel", havingValue = "fcm", matchIfMissing = true)
    SendNotificationPort fcmAdapter(FcmClient client) {
        return new FcmNotificationAdapter(client);
    }
}
```

#### 3-4. 솔직하게 말해야 하는 한계 — 교체 가능성은 포트 계약이 성립하는 범위까지다
여기까지만 말하면 교과서다. **면접에서 차이를 만드는 것은 다음 이야기다.**

**RDB → NoSQL 전면 교체는 어댑터만 갈아서는 안 된다.** 포트 계약이 이미 관계형 DB의 가정을 흡수하고 있기 때문이다.
- `saveOrder(order)` + `saveInventory(inventory)`를 한 트랜잭션으로 묶는 유스케이스는 **두 애그리거트를 원자적으로 저장할 수 있다**는 가정을 갖고 있다. 이 가정이 없는 저장소로 바꾸면 유스케이스를 사가(saga)로 재작성해야 한다.
- 조인 기반 조회 포트, 낙관적 락(`@Version`) 전제, `ORDER BY + OFFSET` 페이징도 마찬가지다.

**포트 계약에 명시해야 하는 비기능 요건**도 있다. 이게 빠진 포트는 벤더 교체 시 조용히 깨진다.

| 계약 항목 | 명시하지 않으면 생기는 일 |
| --- | --- |
| 동기/비동기 | sync 반환을 가정한 유스케이스에 async 벤더를 끼우면 "성공"의 의미가 달라진다 |
| 멱등성 | 재시도 안전을 가정했는데 벤더가 비멱등이면 중복 결제/중복 알림 |
| 지연 시간 | 10ms 가정 자리에 500ms 벤더가 들어오면 상위 타임아웃이 터진다 |
| 부분 실패 | 벌크 발송에서 "3건 중 1건 실패"를 계약이 표현하지 못하면 어댑터가 삼켜버린다 |
| 순서 보장 | 이벤트 순서를 가정한 로직이 순서 보장 없는 브로커로 옮겨가면 상태가 깨진다 |

결론 문장으로 정리하면: **"어댑터 교체가 무료인 범위는 '포트 계약이 동일하게 성립하는 범위'까지다. 포트 계약을 바꿔야 하는 교체는 유스케이스 재작성을 수반한다. 헥사고날은 교체를 공짜로 만들어 주는 게 아니라, 교체 비용이 드는 지점을 한곳에 모아 준다."**

---

### 4. 꼬리질문 ③: Repository interface도 port로 볼 수 있나요?

#### 4-1. 결론
**"개념적으로는 그렇습니다. DDD의 Repository는 '도메인이 정의하고 인프라가 구현하는' 계약이므로 아웃바운드 포트의 원형입니다. 다만 Spring Data JPA의 `JpaRepository` 상속 인터페이스는 포트가 아니라 어댑터의 내부 도구입니다."**

#### 4-2. Repository = Outbound Port인 이유
Fowler의 Repository 패턴과 DDD Repository는 원래부터 "도메인 계층이 소유하는 컬렉션 추상"이다. Cockburn의 secondary port와 목적/방향/소유권이 정확히 겹친다.
- 애그리거트 단위로 도메인 객체를 주고받는다 → 도메인 언어
- 저장 기술을 감춘다 → 기술 격리
- 구현은 인프라가, 정의는 도메인/애플리케이션이 → DIP

> **비유로**: 창고 포트는 "완성된 소파를 맡기고 찾아온다"는 계약이다. 그런데 `JpaRepository`를 상속해 그대로 노출하는 것은 **창고업체 전산 시스템 계정을 우리 직원 전원에게 열어주는 것**에 가깝다. 아래 세 가지 문제가 그대로 대응된다.
> - 직원들이 '랙 번호', '파렛트 코드' 같은 창고 내부 어휘를 쓰기 시작한다 (= 계약에 기술 타입 노출)
> - 그 계정에는 '전체 폐기' 버튼도 딸려 온다 (= `deleteAll()`이 유스케이스에서 호출 가능해진다)
> - 권한 체계를 우리가 아니라 창고업체가 정한다 (= 계약 소유권이 프레임워크에 있다)

#### 4-3. 그런데 왜 `JpaRepository` 상속은 포트가 아닌가
```java
// 이건 포트가 아니다
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    Page<OrderEntity> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);
}
```
세 가지 이유가 있다.

1. **기술 타입이 계약에 노출된다.** 타입 파라미터가 `OrderEntity`(JPA 엔티티)이고 반환형이 `Page`(Spring 타입)다. 이 인터페이스에 의존하는 유스케이스는 자동으로 JPA와 Spring Data에 의존한다.
2. **계약이 "애플리케이션이 필요한 것"을 표현하지 못한다.** 상속만으로 `saveAll`, `deleteAllInBatch`, `flush`, `getReferenceById` 등 수십 개 메서드가 열린다. 포트는 필요한 능력만 좁게 선언해야 하는데(ISP), 여기서는 필요 없는 능력까지 전부 노출된다. `deleteAll()`이 유스케이스에서 호출 가능한 상태 자체가 위험이다.
3. **소유권이 프레임워크에 있다.** 구현체를 Spring이 런타임에 프록시로 만들어 준다. 애플리케이션이 정의한 계약이 아니라 프레임워크와의 규약이다.

#### 4-4. 올바른 배치
```java
// application/port/out/OrderRepository.java  ← 포트: 도메인 언어만
public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(OrderId id);
}

// adapter/out/persistence/OrderPersistenceAdapter.java  ← 어댑터
@Repository
@RequiredArgsConstructor
class OrderPersistenceAdapter implements OrderRepository {
    private final OrderJpaRepository jpaRepository;   // Spring Data는 어댑터 '안'에서만
    private final OrderMapper mapper;

    @Override
    public void save(Order order) {
        try {
            jpaRepository.save(mapper.toEntity(order));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateOrderException(order.id(), e);   // 기술 예외 번역
        }
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpaRepository.findById(id.value()).map(mapper::toDomain);
    }
}

// adapter/out/persistence/OrderJpaRepository.java  ← 어댑터의 내부 도구 (package-private)
interface OrderJpaRepository extends JpaRepository<OrderEntity, Long> { }
```
포인트는 `OrderJpaRepository`를 **package-private으로 닫는 것**이다. 그러면 애플리케이션 계층에서 물리적으로 접근할 수 없다.

#### 4-5. 포트를 하나로 둘까, 잘게 쪼갤까
자주 나오는 꼬리질문이다. 두 방식 모두 근거가 있다.

| 방식 | 예 | 장점 | 단점 |
| --- | --- | --- | --- |
| 애그리거트 단위 하나 | `OrderRepository` | 응집도 높음, 파일 수 적음, 탐색 쉬움 | 유스케이스가 무엇을 쓰는지 시그니처로 안 드러남, 테스트 Fake가 커짐 |
| 능력 단위 분리 | `LoadOrderPort`, `SaveOrderPort` | ISP 준수, 유스케이스 의존성이 명확, mock 범위 최소 | 인터페이스 폭발, 구현체 하나가 여러 포트 구현 |

판단 기준: **저장 기술이 갈라질 가능성이 있는지**, 그리고 **유스케이스들이 쓰는 메서드 집합의 편차가 큰지**. 편차가 작고 구현이 하나뿐이면 애그리거트 단위 하나로 두는 것이 실용적이다. 조회는 무겁고 저장은 가벼운 식으로 성격이 다르면 분리가 값을 한다.

#### 4-6. 조회 전용 포트는 따로 두는 게 낫다
애그리거트 리포지토리 포트에 복잡한 목록/통계 조회를 밀어넣으면, 포트가 점점 SQL을 닮아간다.
```java
// 이렇게 되면 포트가 SQL 쿼리 조건을 그대로 옮긴 형태가 된다 (안 좋은 신호)
List<Order> findByStatusAndCreatedAtBetweenAndAmountGreaterThan(...);
```
읽기 경로는 애그리거트를 복원할 필요가 없으므로 **별도 쿼리 포트로 분리하고 DTO(projection)를 바로 반환**하는 편이 좋다.
```java
// application/port/out/OrderQueryPort.java
public interface OrderQueryPort {
    List<OrderSummary> searchSummaries(OrderSearchCondition condition);
}
```
어댑터는 QueryDSL/native query로 자유롭게 최적화하고, 포트 계약은 "어떤 정보를 원하는가"만 남는다. CQRS의 읽기/쓰기 모델 분리를 헥사고날 안에서 실현하는 방식이다.

#### 4-7. 트랜잭션은 포트에 넣지 않는다
`beginTransaction()`, `commit()` 같은 메서드를 포트에 두는 것은 기술 누수다. 현실적인 선택은 다음 순서다.
1. **유스케이스 서비스에 `@Transactional`** — 순수성을 조금 양보하고 실무 생산성을 얻는 절충. 가장 흔하다.
2. **인바운드 어댑터/데코레이터에서 트랜잭션 경계 선언** — application을 프레임워크 무의존으로 유지하고 싶을 때.
3. **`TransactionRunner` 포트** — `<T> T runInTx(Supplier<T>)` 형태의 추상. 순수성은 지키지만 람다 중첩으로 가독성이 떨어진다.

---

### 5. 꼬리질문 ④: Layered Architecture와 Hexagonal Architecture는 어떤 차이가 있나요?

#### 5-1. 한 줄 차이
**"Layered는 의존성이 위에서 아래로 흐르는 수직 구조이고, Hexagonal은 의존성이 바깥에서 안으로 향하는 동심 구조입니다. 본질적인 차이는 '인프라가 최하위 계층인가, 바깥의 교체 가능한 부속인가'입니다."**

```
[Layered]                         [Hexagonal]
Presentation                       Adapter(in) ─┐
     ↓                                          ↓
Application                                  Port(in)
     ↓                                          ↓
Domain                                       Application / Domain
     ↓                                          ↓
Infrastructure (DB)                          Port(out)
                                                ↑
※ 도메인이 DB에 의존              Adapter(out) ─┘   ※ DB가 도메인에 의존
```

> **비유로**: Layered는 **일렬 컨베이어 벨트**다. 맨 위에 주문 접수, 맨 아래에 창고가 놓이고 물건이 위에서 아래로만 흐른다. 그래서 **창고 배치가 바뀌면 상류 공정이 영향을 받는다.**
> Hexagonal은 **작업장을 가운데 두고 입고장과 출하장을 사방에 배치한 공장**이다. 창고도 판매 채널도 똑같이 '건물 바깥'이고, 밖으로 나가는 문은 전부 우리가 정한 규격의 출하장이다. 5-2 표의 "UI와 DB의 대칭성"이 이 배치 차이를 말한다.
> 강제력 차이도 비유로 드러난다. 컨베이어에는 공정을 건너뛰지 못하게 막는 물리적 장치가 없어 작업자의 규율에 의존한다(= 컨벤션). 벽과 출입 카드로 나뉜 공장에서는 애초에 넘어갈 수 없다(= 모듈 경계와 ArchUnit).

#### 5-2. 비교표

| 관점 | Layered | Hexagonal |
| --- | --- | --- |
| 의존 방향 | 상위 → 하위 (단방향 수직) | 외부 → 내부 (동심원) |
| DB의 위치 | 최하위 계층 (도메인이 의존) | 바깥 어댑터 (도메인에 의존) |
| 인터페이스 소유권 | 관례상 인프라/모호 | 애플리케이션 코어가 소유 |
| DIP | 선택적 | 구조의 전제 |
| UI와 DB의 관계 | 비대칭 (UI는 위, DB는 아래) | **대칭 (둘 다 그냥 외부)** |
| 도메인 모델 | JPA 엔티티와 합쳐지기 쉬움 | 영속성 엔티티와 분리 |
| 테스트 | 컨텍스트/DB 자주 필요 | 코어는 순수 단위 테스트 |
| 규칙 강제 수단 | 컨벤션, 코드 리뷰 | 패키지/모듈 컴파일 의존성, ArchUnit |
| 코드량 | 적음 | 1.5~3배 |
| 적합한 상황 | CRUD 중심, 짧은 수명, 소규모 팀 | 외부 연동 다수, 두꺼운 도메인 규칙, 긴 수명 |

여기서 가장 자주 놓치는 칸은 **대칭성**이다. Layered는 UI를 "위", DB를 "아래"로 놓아 둘을 다르게 취급한다. Hexagonal은 둘 다 "애플리케이션을 구동하거나 애플리케이션이 구동하는 외부"로 동급 취급한다. 그래서 "사용자가 애플리케이션을 쓰는 방식과 테스트 케이스가 애플리케이션을 쓰는 방식이 대칭이어야 한다"는 Cockburn의 원래 목표가 성립한다.

#### 5-3. Layered가 실무에서 무너지는 지점 (경험 기반 답변 소재)
"차이"를 추상적으로 말하는 대신, Layered가 실제로 깨지는 방식 4가지를 들면 설득력이 생긴다.

1. **Service가 JPA 엔티티를 그대로 반환한다.** Controller가 영속 모델에 직접 의존하고, 응답 직렬화 시점에 지연 로딩이 터진다(`LazyInitializationException`). 컬럼 하나 바꾸면 API 응답 스키마가 바뀐다.
2. **Repository 인터페이스를 인프라가 소유한다.** 인터페이스가 있어도 시그니처에 `Pageable`, `Specification`, `Entity`가 나오면 DIP가 성립하지 않는다. "인터페이스가 있다 ≠ 의존성이 역전됐다."
3. **계층 건너뛰기.** 바쁠 때 Controller가 Repository를 직접 호출한다. Layered에는 이걸 막을 물리적 수단이 없다(같은 모듈, 모두 public).
4. **DB 스키마가 도메인 모델을 결정한다.** 테이블을 먼저 그리고 엔티티를 생성하면, 도메인 모델이 정규화 결과물의 그림자가 된다. 비즈니스 규칙이 서비스에 절차적으로 흩어지고 도메인 객체는 getter/setter 덩어리(anemic model)가 된다.

#### 5-4. 공정하게 짚어야 할 점: 둘은 대립 관계가 아니다
- **Layered에 DIP를 제대로 적용하면 사실상 Hexagonal로 수렴한다.** 인터페이스 소유권을 상위로 올리고 도메인 모델을 영속 모델과 분리하면 남는 차이는 용어와 패키지 이름뿐이다.
- 실제 차이는 **"규칙을 어디까지 물리적으로 강제하는가"**에 있다. Hexagonal은 패키지 구조/모듈 경계/가시성으로 규칙을 컴파일 타임에 강제한다. Layered는 사람의 규율에 의존한다. 규모가 커지고 인원이 바뀌면 이 차이가 결과를 갈라놓는다.
- Hexagonal 내부에도 계층은 존재한다. adapter/application/domain은 계층이다. Hexagonal은 **계층을 없앤 것이 아니라 계층 간 의존 방향을 재정의한 것**이다.
- 관련 정리: [Layered Architecture의 장점과 도입 이유](../SYSTEM-ARCHITECTURE/260420_01_Layered_Architecture의_장점과_도입_이유.md)

#### 5-5. 선택 기준
```
도메인 규칙이 두꺼운가? (상태 전이/정책/불변식이 실제로 존재하는가)
├─ No → Layered로 충분. 헥사고날은 과설계
└─ Yes
   └─ 외부 연동이 여러 개인가? (DB + 외부 API + 브로커 + 캐시)
      ├─ No → Layered + DIP(포트 개념만 부분 적용)
      └─ Yes
         └─ 시스템 수명이 긴가? / 팀 인원 교체가 예상되는가?
            ├─ No → 부분 적용(코어 도메인만 엄격)
            └─ Yes → Hexagonal + ArchUnit으로 규칙 강제
```

---

### 6. 꼬리질문 ⑤: Port와 adapter를 너무 많이 만들면 어떤 단점이 생기나요?

#### 6-1. 결론
**"단점은 코드량 증가가 아니라, 간접 계층이 늘어나면서 '이 시스템이 무엇을 하는지'가 코드에서 안 읽히게 되는 것입니다. 그리고 잘못 그은 포트는 오히려 결합을 고착시킵니다."**

#### 6-2. 구체적인 단점 7가지

**(1) 탐색 비용(indirection tax)**
버그 하나를 추적하려면 이 경로를 다 지나야 한다.
```
Controller → Request DTO → Command → UseCase(interface) → Service
  → OutPort(interface) → Adapter → Mapper → JpaRepository → Entity → 테이블
```
11 hop. IDE에서 "구현으로 가기"를 반복해야 하고, 인터페이스가 하나뿐인데도 매번 인터페이스로 점프한다. 신규 입사자 온보딩과 장애 대응 속도에 직접 영향을 준다.

**(2) 매핑 4중 구조**
`Request → Command → Domain → Entity`, 조회는 역방향. 모델이 4개면 매퍼도 4개다.
- 코드량이 늘고, 필드 추가 시 4곳을 수정해야 한다(누락 버그의 온상).
- 대량 조회에서 불필요한 객체 생성이 실측 가능한 오버헤드가 된다.
- MapStruct로 생성해도 **생성된 코드의 매핑 규칙을 검증할 테스트가 또 필요하다.**

**(3) 구현체가 하나뿐인 인터페이스의 홍수**
YAGNI 위반이다. 인터페이스는 "여러 구현이 있을 수 있다"는 정보를 전달하는데, 항상 하나뿐이면 그 정보가 거짓말이 된다. 결과적으로 인터페이스가 정보 가치를 잃는다.

**(4) 잘못 그은 포트는 결합을 고착시킨다 — 가장 중요한 단점**
포트를 CRUD 단위나 기술 단위로 잘게 쪼개면 이런 일이 생긴다.
```java
// 포트가 잘게 쪼개진 결과: 유스케이스가 일관성 경계를 직접 관리하게 된다
void confirm(ConfirmOrderCommand cmd) {
    Order order = loadOrderPort.load(cmd.orderId());
    Inventory inv = loadInventoryPort.load(order.productIds());
    order.confirm(inv);
    saveOrderPort.save(order);
    saveInventoryPort.save(inv);       // 이 둘이 같은 트랜잭션인지 시그니처로 알 수 없다
    publishEventPort.publish(new OrderConfirmed(order.id()));  // 커밋 전인지 후인지도 모른다
}
```
포트 개수가 많다는 것은 **애플리케이션이 외부에 요구하는 능력이 그만큼 많다**는 뜻이고, 이는 종종 유스케이스 응집도가 낮다는 신호다. 추상화를 늘렸는데 결합은 오히려 늘어난 상태다.

**(5) 성능/기능 최적화를 표현할 자리가 없다**
포트를 도메인 언어로만 유지하면 다음을 표현할 방법이 없다.
- 배치 insert, `IN` 절 묶어 조회하기 (N+1 회피)
- 필요한 컬럼만 읽는 projection
- 커서 기반 페이징
- `SELECT ... FOR UPDATE` 락 힌트, 격리 수준 지정
- 스트리밍(대량 데이터를 메모리에 올리지 않고 처리)

결국 두 갈래로 귀결된다: 포트에 기술 개념을 넣어 순수성을 포기하거나(`findAllByIdIn`, `Slice<T>`), 어댑터가 비효율 구현을 강요받거나. 두 번째가 훨씬 자주 문제가 된다.

**(6) 테스트 유지비 역전**
- 포트마다 Fake를 만들면, 포트 시그니처 하나를 바꿀 때 모든 Fake를 수정해야 한다.
- Mock을 남발하면 `verify(port).save(any())` 같은 **구현 검증 테스트**가 되어, 리팩터링할 때마다 테스트가 깨진다. 헥사고날을 도입한 이유(리팩터링 자유도)와 정반대 결과다.
- 포트가 많으면 유스케이스 테스트 하나의 셋업 코드가 길어져서, "빠른 단위 테스트"라는 이점이 상쇄된다.

**(7) 일관성 경계가 흐려진다**
여러 아웃바운드 포트를 한 유스케이스에서 호출할 때, 어느 호출이 같은 트랜잭션에 참여하고 어느 것이 외부 네트워크 호출인지 포트 시그니처만으로는 알 수 없다. **DB 저장 포트와 Slack 전송 포트가 코드상 완전히 똑같이 생겼다**는 것이 문제다. 이 구분을 잃으면 트랜잭션 안에서 외부 API를 호출하는 사고가 난다. → [비관적 락으로 막은 동시성이 @Transactional 안의 Slack 호출로 되살아났다](../SYSTEM-ARCHITECTURE/260730_01_비관적_락으로_막은_동시성이_Transactional_안의_Slack_호출로_되살아났다.md)

> **비유로**: 자재 하나마다 발주서 양식을 따로 만들어 양식이 200종이 된 회사다. 위 7가지가 그대로 나타난다.
> - 신입이 어떤 양식을 써야 할지 몰라 매번 선임에게 묻는다 (= 탐색 비용)
> - 양식마다 업체 양식으로 옮겨 적는 절차가 따로 생긴다 (= 매핑 비용)
> - 거래처가 한 곳뿐인 자재에도 양식이 있다 (= 구현체 하나뿐인 인터페이스)
> - **"나사 발주서"와 "경첩 발주서"로 쪼개니, 같이 와야 하는 부품을 한 번에 주문할 수 없다** (= (4) 일관성/트랜잭션 경계 상실). 잘못 그은 포트가 결합을 고착시킨다는 말의 정체가 이것이다
> - **"트럭 한 대를 채워 보내면 운임이 싸다"를 양식이 표현하지 못한다** (= (5) 배치 insert, `IN` 절 묶어 조회하기 같은 최적화를 포트가 담을 자리가 없다)
>
> 조절 원칙도 같은 비유로 나온다. 발주서는 **거래처가 바뀔 수 있어서**가 아니라 **회사 담장을 넘는 거래이기 때문에** 만든다. 담장 안에서 옆 작업대에 부품을 건네는 일에는 발주서를 쓰지 않는다.

#### 6-3. 그래서 포트 개수를 어떻게 조절하는가

**원칙: 포트는 "교체 가능성"이 아니라 "경계의 필요성"으로 만든다.**

포트를 만들 자격이 있는 것:
- **프로세스/네트워크 경계를 넘는 호출** — DB, 외부 API, 브로커, 캐시, 파일 스토리지
- **테스트에서 대역이 필요한 것** — 시간(`Clock`), 랜덤, ID 생성기, 현재 사용자
- **벤더 교체 가능성이 실재하는 것** — 결제, 알림, 인증

포트를 만들지 않아야 하는 것:
- 같은 프로세스 안의 순수 계산 로직 → 도메인 서비스로 직접 호출
- 구현체가 하나이고 테스트에서 stub도 필요 없는 것 → **삭제 후보**
- 단순 유틸리티, 값 객체 변환

**전략 1: 비대칭 적용(선택적 헥사고날)**

전 코드에 같은 엄격도를 적용하는 것이 가장 흔한 실패다. 모듈별로 규칙을 차등한다.

| 영역 | 예시 | 적용 수준 |
| --- | --- | --- |
| 코어 도메인 | 주문, 결제, 정산 | 포트/어댑터 엄격 적용, 도메인 모델 분리 |
| 지원 서브도메인 | 알림 설정, 공지 | Layered + 아웃바운드 포트만 |
| 일반 CRUD/어드민 | 코드 테이블, 배너 관리 | Spring Data 직접 사용, 포트 없음 |

Eric Evans의 코어/지원/일반 서브도메인 구분을 아키텍처 엄격도에 그대로 대응시키는 방식이다.

**전략 2: 조회 경로는 얇게 (CQRS)**

쓰기 경로는 애그리거트 복원이 필요하니 포트를 제대로 두고, 읽기 경로는 어댑터에서 DTO를 바로 만들어 반환한다. 매핑 단계를 하나로 줄이고, 성능 최적화 자유도를 확보한다.

**전략 3: 매핑 전략을 유스케이스별로 선택**

전 구간 풀 매핑을 강제하지 않는다.

| 전략 | 방식 | 적합한 경우 |
| --- | --- | --- |
| No mapping | 도메인 모델을 어댑터까지 그대로 | 단순 CRUD, 초기 개발 단계 |
| One-way | 어댑터가 도메인 인터페이스를 구현 | 읽기 전용 경로 |
| Two-way | Command/Response만 매핑, Entity는 공유 | 대다수 유스케이스 |
| Full | 모든 경계에서 매핑 | 코어 도메인, 스키마 변동이 큰 영역 |

**전략 4: 규칙을 사람이 아니라 도구가 지키게 한다**
```java
@AnalyzeClasses(packages = "com.example.order")
class ArchitectureTest {
    @ArchTest
    static final ArchRule 애플리케이션은_어댑터를_모른다 =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule 도메인에는_스프링_의존성이_없다 =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.persistence..");
}
```
규칙이 자동 검증되면 "포트를 왜 두는지"를 매번 리뷰에서 설명하지 않아도 된다. 인지 비용이 내려가고, 그만큼 포트를 무의미하게 늘릴 유인도 줄어든다.

**전략 5: 삭제 기준을 명시적으로 갖는다**

다음 세 조건을 모두 만족하는 포트는 지운다.
1. 구현체가 하나뿐이다
2. 테스트에서 Fake/Mock으로 대체되지 않는다
3. 프로세스 경계를 넘지 않는다

포트를 만드는 기준만 있고 지우는 기준이 없으면 개수는 단조 증가한다.

---

### 7. 면접 답변 시나리오 (요약 흐름)
> 도입부에서 비유를 쓴다면 딱 한 문장이다 — "코어는 공장, 어댑터는 거래처와 운송업체, 포트는 그 사이의 발주서 양식입니다." 그리고 바로 1번으로 내려간다.

1. **정의를 소유권으로 말한다** — "포트는 애플리케이션이 소유한 계약, 어댑터는 그 계약을 기술로 번역하는 구현체. 포트가 무엇(what), 어댑터가 어떻게(how)."
2. **판정 기준을 제시한다** — "이 코드를 지우면 비즈니스 규칙이 사라지는가", "이 코드가 바뀌는 이유가 기술 변경인가", "이 타입 이름을 도메인 전문가가 알아듣는가."
3. **포트 위치는 호출자로 정한다** — "인바운드는 application. 아웃바운드도 기본 application이고, 도메인 규칙이 직접 호출하는 것만 domain. 멀티모듈로 쪼개면 컴파일이 답을 알려준다."
4. **교체 가능성을 경험으로 환원한다** — "실무에서 가장 자주 갈아끼우는 건 테스트 대역이고, 그다음이 벤더 교체다. DB 전면 교체는 포트 계약 자체가 깨져서 어댑터만으로는 안 된다."
5. **Repository는 조건부 Yes** — "DDD Repository는 아웃바운드 포트의 원형이지만, `JpaRepository` 상속 인터페이스는 계약에 기술 타입이 노출되고 소유권이 프레임워크에 있으므로 어댑터의 내부 도구로 봐야 한다."
6. **Layered와의 차이는 강제력에 있다** — "Layered에 DIP를 제대로 적용하면 헥사고날에 수렴한다. 차이는 규칙을 컨벤션으로 지키는지, 컴파일 의존성과 ArchUnit으로 강제하는지다."
7. **비용을 인정하고 조절 전략을 말한다** — "간접 계층과 매핑 비용은 실재한다. 그래서 전 영역에 같은 엄격도를 쓰지 않고, 코어 도메인만 엄격하게 적용하고 CRUD는 레이어드로 둔다."

---

## 핵심 정리
- 핵심 포인트 1: **포트와 어댑터를 가르는 기준은 "소유권"이다.** 계약을 애플리케이션이 정의하고 외부 기술이 구현하면 포트/어댑터이고, 계약이 구현체 쪽에 있으면 인터페이스가 있어도 헥사고날이 아니다. 판정은 "이 타입 이름을 도메인 전문가가 알아듣는가"로 빠르게 할 수 있다.
- 핵심 포인트 2: **포트의 위치는 취향이 아니라 호출자가 결정한다.** 인바운드 포트는 유스케이스라는 애플리케이션 개념이므로 application, 아웃바운드 포트는 유스케이스 서비스가 호출하면 application, 도메인 규칙이 직접 호출하면 domain. 멀티모듈로 쪼개면 컴파일 의존성이 답을 강제한다.
- 핵심 포인트 3: **어댑터 교체가 무료인 범위는 포트 계약이 동일하게 성립하는 범위까지다.** 트랜잭션 원자성, 멱등성, 동기/비동기, 순서 보장 같은 가정이 바뀌면 유스케이스를 재작성해야 한다. 헥사고날은 교체를 공짜로 만들지 않고, 교체 비용이 드는 지점을 한곳에 모아 준다.
- 핵심 포인트 4: **가장 큰 비용은 코드량이 아니라 간접 계층에 의한 가독성 저하이고, 가장 큰 위험은 잘못 그은 포트가 결합을 고착시키는 것이다.** 그래서 포트는 "교체 가능성"이 아니라 "프로세스 경계 + 테스트 대역 필요성"으로 만들고, 코어 도메인에만 엄격하게 적용한다.
- 핵심 포인트 5: **한 문장 비유 — 코어는 공장, 어댑터는 거래처·운송업체·판매 채널, 포트는 그 사이의 발주서·주문서 양식이다.** 포트를 '주체'로 상상하면 반드시 어댑터와 겹치므로, 포트에 대응하는 것은 언제나 '양식·규격·계약'이어야 한다. 다만 비유는 의존성 방향과 트랜잭션 경계를 설명하지 못하므로, 면접에서는 한 문장만 쓰고 바로 코드로 내려간다.

---

## 기술적 한계와 보완 전략

**한계 1: 순수성과 성능이 충돌한다**
포트를 도메인 언어로만 유지하면 배치 처리, projection, 커서 페이징, 락 힌트, 스트리밍을 표현할 자리가 없다.
→ 보완: 쓰기 경로만 애그리거트 포트로 엄격하게 유지하고, 읽기 경로는 별도 쿼리 포트에서 DTO를 직접 반환하게 분리한다(CQRS 읽기 모델). 대량 처리는 "배치 유스케이스"라는 별도 인바운드 포트로 명시하고 그 포트에는 기술적 제약(청크 크기 등)을 계약에 포함시킨다.

**한계 2: 트랜잭션 경계가 포트 계약에 드러나지 않는다**
DB 저장 포트와 외부 API 호출 포트가 코드상 구분되지 않아, 트랜잭션 안에서 외부 호출을 하는 사고가 난다.
→ 보완: 네이밍 컨벤션으로 구분한다(`SaveXxxPort` vs `NotifyXxxPort`/`CallXxxPort`). 트랜잭션 이후에 실행되어야 하는 것은 도메인 이벤트로 바꾸고 `@TransactionalEventListener(AFTER_COMMIT)` 또는 Outbox 패턴으로 옮긴다. ArchUnit으로 "`@Transactional` 메서드는 외부 호출 포트를 직접 호출할 수 없다" 규칙을 넣을 수도 있다.

**한계 3: 매핑 비용이 실질적으로 크다**
4중 모델과 4개 매퍼는 코드량과 필드 누락 버그를 늘린다.
→ 보완: 유스케이스 성격별로 매핑 전략을 다르게 선택한다(No mapping / One-way / Two-way / Full). MapStruct로 생성하되 매핑 누락을 잡는 테스트(모든 필드 왕복 검증)를 함께 둔다.

**한계 4: 팀 이해도가 갈리면 구조가 제각각이 된다**
포트를 어디 두는지, 매핑을 어디까지 하는지가 사람마다 다르면 헥사고날이 오히려 복잡도를 늘린다.
→ 보완: 판단 규칙을 문서가 아니라 **실행 가능한 테스트(ArchUnit)와 템플릿 모듈**로 고정한다. 신규 기능은 기존 슬라이스를 복사해서 시작하게 만든다.

**한계 5: 도입 시점을 놓치면 이득이 사라진다**
도메인 규칙이 얇은 CRUD 서비스에 전면 적용하면 순수 비용만 남는다.
→ 보완: 처음부터 전면 적용하지 말고, 아웃바운드 포트(외부 연동 격리)만 먼저 도입한다. 여기서 테스트 속도와 벤더 교체 이득이 즉시 나온다. 인바운드 포트와 도메인 모델 분리는 도메인 규칙이 실제로 두꺼워진 뒤에 확장한다.

---

## 키워드
- **Port**: 애플리케이션이 소유하는 계약(인터페이스). 도메인/유스케이스 언어로 "무엇이 필요한가/무엇을 제공하는가"를 선언하고, 기술 어휘를 포함하지 않는다
- **Adapter**: 포트 계약을 특정 기술의 언어로 번역하는 구현체. 프로토콜 해석, 모델 변환, 기술 예외 번역, 재시도/타임아웃 같은 신뢰성 처리를 담당한다
- **Inbound / Driving / Primary Port**: 외부가 애플리케이션을 구동하기 위한 유스케이스 인터페이스. 트랜잭션·인가 경계가 붙는 자리
- **Outbound / Driven / Secondary Port**: 애플리케이션이 외부 능력을 사용하기 위한 인터페이스. DIP로 의존 방향을 뒤집는 핵심 지점
- **의존성 역전 원칙(DIP)**: 계약의 소유권을 상위 정책(애플리케이션)으로 올려, 저수준 모듈(인프라)이 추상에 의존하도록 방향을 뒤집는 원칙. "인터페이스가 있다"와 "의존성이 역전됐다"는 다르다
- **Repository as Port**: DDD Repository는 아웃바운드 포트의 원형. 다만 `JpaRepository` 상속 인터페이스는 기술 타입이 계약에 노출되고 소유권이 프레임워크에 있으므로 어댑터의 내부 도구로 취급한다
- **포트 계약의 비기능 요건**: 멱등성, 동기/비동기, 지연, 부분 실패, 순서 보장. 명시되지 않으면 어댑터 교체 시 조용히 깨지는 항목들
- **Indirection tax**: 간접 계층이 늘어나 코드 탐색·디버깅·온보딩 비용이 증가하는 현상. 헥사고날의 대표적 비용
- **선택적 헥사고날(비대칭 적용)**: 코어 도메인은 엄격 적용, 지원 서브도메인은 부분 적용, 일반 CRUD는 레이어드로 두어 엄격도를 영역별로 차등하는 실무 전략
- **매핑 전략(No / One-way / Two-way / Full)**: 계층 간 모델 변환 범위를 유스케이스 성격에 따라 다르게 선택하는 기법. 전 구간 풀 매핑 강제는 흔한 과설계
- **ArchUnit**: 패키지/의존성 규칙을 테스트 코드로 검증하는 라이브러리. 헥사고날 규칙을 컨벤션이 아니라 컴파일/CI 수준에서 강제하는 수단
- **Strangler Fig**: 기존 어댑터와 신규 어댑터를 동시에 두고 shadow write/read로 비교 후 전환하는 점진적 마이그레이션 기법. 포트가 없으면 데코레이터를 끼울 자리가 없다

---

## 참고 자료
- [Hexagonal architecture - Alistair Cockburn (원문)](https://alistair.cockburn.us/hexagonal-architecture/)
- [Get Your Hands Dirty on Clean Architecture - Tom Hombergs (buckpal 예제)](https://reflectoring.io/book/)
- [Hexagonal Architecture with Java and Spring - reflectoring.io](https://reflectoring.io/spring-hexagonal-architecture/)
- [Ready for changes with Hexagonal Architecture - Netflix Technology Blog](https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749)
- [Repository - Martin Fowler (P of EAA Catalog)](https://martinfowler.com/eaaCatalog/repository.html)
- [ArchUnit - Unit test your Java architecture](https://www.archunit.org/)
- [Hexagonal architecture pattern - AWS Prescriptive Guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/hexagonal-architecture.html)
