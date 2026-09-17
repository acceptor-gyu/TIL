# 트랜잭션이 FCM 왕복을 감싸지 않게 하기 — outbox로 알림 보내기

알림은 "업무가 성공했으면 반드시 나가야 하고, 알림이 실패했다고 업무를 되돌리면 안 되는" 두 요구를 동시에 가진다. 이를 동시에 만족하려면, **기록과 발송을 나눠야한다.**

조인 신청·채팅 메시지·부킹 문의의 알림을 만들면서 outbox로 갔다. 그 과정에서 배운 것 중 문서에 없던 것은 **트랜잭션 전파가 이 설계의 진짜 함정**이라는 점과, **실패를 한 숫자로 묶으면 진단이 정반대로 간다**는 점이었다.

> 사내 저장소 기준이라 프로젝트명·계정 ID·도메인 같은 식별자는 일반화해서 적는다.

---

## 1. 순진하게 만들면 어디가 깨지나

```kotlin
@Transactional
fun apply(userId: Long, postId: Long) {
    val application = applications.save(...)
    post.addApplicant(...)
    pushMessenger.send(host.deviceTokens, message)   // ← 여기
}
```

세 가지가 한꺼번에 깨진다.

| 문제 | 내용 |
| --- | --- |
| **커넥션 점유** | 구글 왕복이 끝날 때까지 DB 커넥션을 쥔다. 라운딩 임박 알림처럼 정해진 시각에 한꺼번에 나가면 **커넥션 풀이 먼저 마른다** |
| **응답 지연** | 요청 스레드가 FCM을 기다린다 |
| **유실** | 커밋 직후 프로세스가 죽으면 알림은 영영 안 나간다. 아무 기록도 남지 않는다 |

여기에 덜 명백한 것이 하나 더 있다. 발송을 트랜잭션 안에 두면 **롤백됐는데 알림은 이미 나간** 경우가 생긴다. "신청이 취소됐는데 접수 알림만 온" 상태다.

### 이 장에 나온 어노테이션

#### `@Transactional` — 옵션 없이 붙이면

**메서드를 DB 트랜잭션 경계로 만든다.** 메서드에 들어갈 때 트랜잭션을 열고, 정상적으로 끝나면 커밋, 예외가 밖으로 나가면 롤백한다. 그 사이의 DB 작업은 전부 한 덩어리로 성공하거나 전부 없던 일이 된다.

위처럼 옵션 없이 맨몸으로 붙인 것은 이것과 같다.

```kotlin
@Transactional(
    propagation = Propagation.REQUIRED,  // 있으면 참여, 없으면 새로 시작
    isolation = Isolation.DEFAULT,       // DB 기본값 (MySQL InnoDB = REPEATABLE READ)
    timeout = -1,                        // 제한 없음
    readOnly = false,
)                                        // rollbackFor 미지정 → RuntimeException·Error 에만 롤백
```

| 옵션 | 동작 | 장점 | 단점 |
| --- | --- | --- | --- |
| `readOnly = true` | 하이버네이트 플러시 모드를 `MANUAL`로 낮추고 더티 체킹용 스냅샷을 만들지 않는다 | 조회 경로의 메모리·CPU를 아끼고, 읽기 복제본 라우팅의 힌트가 된다 | **쓰기가 조용히 무시된다.** 플러시가 일어나지 않아 예외 없이 변경이 사라진다 |
| `rollbackFor` | 지정한 예외에도 롤백한다 | 체크 예외에 롤백이 필요할 때 명시 | `Exception::class`로 뭉뚱그리면 잡아서 처리하려던 흐름까지 되돌아간다. 코틀린은 체크 예외가 없어 기본값으로 충분한 경우가 대부분이다 |
| `isolation` | 격리 수준을 올린다 | 비반복 읽기·팬텀을 막는다 | 잠금 경합이 늘어 처리량이 떨어진다 |
| `timeout` | 초 단위 제한, 넘기면 롤백 | 폭주 쿼리가 커넥션을 오래 쥐는 것을 끊는다 | 원래 오래 걸리는 배치 경로에서 오탐이 난다 |

`propagation`은 이 글의 주제라 5장에서 따로 본다. 나머지 중 제일 자주 걸리는 것은 **기본 롤백 대상이 `RuntimeException`과 `Error`뿐**이라는 점이다 — 체크 예외는 그냥 커밋된다.

---

## 2. 기록과 발송을 나눈다

```text
[업무 트랜잭션]                        [커밋 후]
  신청 저장                        outbox 행을 claim
  인원 증가            ──커밋──►        FCM 발송    ──►  markSent
  outbox 행 INSERT                  (트랜잭션 밖)
```

**outbox 기록은 업무와 같은 트랜잭션**이다. 기록이 실패하면 업무도 롤백한다. 이건 "알림 실패로 업무를 되돌리지 않는다"와 모순되지 않는다 — 되돌리는 것은 **발송 실패**가 아니라 **기록 실패**다. 기록조차 못 했다는 건 DB가 나갔다는 뜻이라 업무도 살아남으면 안 된다.

**발송은 커밋 후, 트랜잭션 밖**이다. 실패해도 행이 남아 있으므로 다음 회차가 재시도한다.

창구는 두 줄이다.

```kotlin
@ApplicationService
class NotificationScheduleService(
    private val outboxService: NotificationOutboxService,
    private val dispatchService: NotificationDispatchService,
    private val afterCommitExecutor: AfterCommitNotificationExecutor,
    private val clock: Clock,
) : NotificationScheduleManager {
    @Transactional
    override fun sendNow(type, subjectType, subjectId, retryExpired) {
        // ① 업무 트랜잭션에 참여해 outbox 행을 넣는다
        val outboxId = outboxService.enqueue(type, subjectType, subjectId, clock.instant(), retryExpired) ?: return
        // ② 커밋된 뒤에 발송을 맡긴다 — 롤백되면 실행되지 않는다
        afterCommitExecutor.execute { dispatchService.dispatch(outboxId) }
    }
}
```

①은 업무와 운명을 같이하고, ②는 커밋 이후에만 일어난다. **`AfterCommitNotificationExecutor`를 포트로 둔 것**은 application 계층이 스프링의 트랜잭션 동기화 API를 모르게 하기 위해서다. 이 계층이 쓰는 것은 "롤백되면 실행도 취소된다"는 계약뿐이다.

`PushSendService.send`에 `@Transactional`이 아예 없다. 토큰 조회와 무효 토큰 삭제가 각자 짧은 트랜잭션이고, 그 사이의 FCM 호출은 트랜잭션 밖이다.

같은 이유로 `PushMessenger`는 **예외를 던지지 않는 계약**으로 뒀다. 조인 신청은 접수됐는데 발송이 실패했다고 그 신청을 되돌릴 수는 없다. 결과를 값으로 돌려주고 원인은 어댑터가 로그에 남긴다.

### 이 장에 나온 어노테이션

#### `@ApplicationService` / `@Adapter` — 직접 만든 스테레오타입

**이 클래스를 스프링 빈으로 등록하면서, 동시에 "어느 계층의 무슨 역할인지"를 표시한다.** 스프링이 주는 `@Service`·`@Component` 자리에 대신 붙이는 것으로, 이 저장소가 직접 정의한 어노테이션이다.

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Service                       // ← 메타 어노테이션
annotation class ApplicationService

@Component
annotation class Adapter
```

**어떻게 동작하나.** 컴포넌트 스캔은 클래스에 `@Component`가 직접 붙었는지만 보지 않고, 붙어 있는 어노테이션을 **타고 올라가며** 찾는다.

```text
@ApplicationService → @Service → @Component   ← 스캔은 여기까지 따라 올라간다
@Adapter            → @Component
```

스프링이 주는 `@Service`·`@Repository`·`@Controller`도 전부 이 방식으로 만들어진 것이라, 새 스테레오타입을 정의하는 것은 프레임워크가 쓰는 방법을 그대로 쓰는 일이다. ArchUnit도 이 사슬을 알고 있어 `beMetaAnnotatedWith(Component::class.java)`로 검사한다.

**왜 이름 규칙(`...Adapter` 접미사)으로 하지 않았나.** 사람이 읽는 용도라면 이름으로 충분하고, 실제로 이 저장소도 이름 규칙을 함께 쓴다 — ArchUnit이 `simpleName.endsWith("Service")`로 역할 패키지 위치를 검사한다. 어노테이션이 하는 일은 그 위에 있다. **역할에 딸린 프레임워크 설정까지 한 이름에 묶는 것**이다.

```kotlin
@ApplicationService
@Validated                     // 메서드 파라미터 검증 프록시가 붙는다
annotation class ValidatedApplicationService

@Adapter
@RestController                // 컨트롤러로 등록되고 응답 본문이 직렬화된다
annotation class WebApiAdapter
```

클래스 이름을 `XxxValidatedApplicationService`로 고쳐 봐야 검증은 켜지지 않는다. **이름은 사람만 읽고, 어노테이션은 프레임워크가 읽는다.** 이렇게 묶어 두면 그 역할 전체에 무언가를 더할 때(공통 AOP, 새 검증 설정) 클래스를 전부 고치지 않고 스테레오타입 한 곳만 고치면 된다.

`@Adapter`처럼 `@Component`에 아무것도 더하지 않는 것은 이름 규칙으로 대체해도 크게 다르지 않다. 실익이 분명한 쪽은 `@ValidatedApplicationService`·`@WebApiAdapter`처럼 **동작을 함께 묶은 것**이고, 나머지는 그 둘과 계층 어휘를 하나로 맞추는 값이다.

> `@ApplicationService`는 일부러 `@Transactional`을 포함하지 않는다 — 어디까지가 한 단위인지는 메서드가 스스로 말해야 하므로 `@Transactional`을 직접 붙인다.

---

## 3. 인스턴스가 둘이면 `@Scheduled`를 그냥 달 수 없다

```kotlin
@Adapter
@ConditionalOnProperty(name = ["scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class NotificationOutboxScheduler(
    private val outboxDispatcher: NotificationOutboxDispatcher,
) {
    @Scheduled(fixedDelayString = "PT1M")
    fun dispatchDue() = outboxDispatcher.dispatchDue()
}
```

이렇게 달면 인스턴스 둘이 동시에 깨어나 같은 알림을 두 번 보낸다. **중복 발송이 인스턴스를 늘리는 시점에 조용히 시작된다.**

잠금도 리더 선출도 필요 없다. 상태 전이 하나면 된다.

```kotlin
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query(
    """
    UPDATE NotificationOutbox outbox
    SET outbox.status = ...SENDING,
        outbox.updatedAt = :at,
        outbox.version = outbox.version + 1
    WHERE outbox.id = :id
      AND outbox.status = ...PENDING
      AND outbox.scheduledAt <= :at
    """,
)
fun claim(@Param("id") id: Long, @Param("at") at: Instant): Int
```

**1행을 고친 쪽만 보낸다.** `WHERE`에 `PENDING`이 들어 있는 것이 핵심이다.

### "1행을 고쳤는가"를 무엇으로 아나

**UPDATE가 돌려주는 행 수다.** 애플리케이션이 판정하는 게 아니라 DB가 직렬화해 준 결과를 반환값으로 읽을 뿐이고, 그래서 `claim`의 반환 타입이 `Int`다. JDBC `executeUpdate()`의 값을 JPA가 그대로 전달한다.

두 인스턴스가 같은 행에 동시에 이 UPDATE를 날리면 이렇게 갈린다.

| 시점 | 인스턴스 A | 인스턴스 B |
| --- | --- | --- |
| t1 | 행에 배타 락 획득. `PENDING` 일치 → `SENDING`으로 변경 | 같은 행 락을 기다리며 **블록** |
| t2 | 커밋 — `REQUIRES_NEW`라 여기서 즉시. 락 해제 | 여전히 대기 |
| t3 | 반환 **1** → 발송 진행 | 락 획득, 조건을 **다시 평가**. 이미 `SENDING`이라 불일치 |
| t4 | | 반환 **0** → 그냥 빠진다 |

t3의 "다시 평가"가 전부다. `UPDATE ... WHERE`는 **잠금 읽기**(locking read)라 트랜잭션 시작 시점의 일관된 스냅샷이 아니라 **최신 커밋본**을 본다. 격리 수준이 REPEATABLE READ여도 그렇다. 그래서 B는 A가 방금 커밋한 `SENDING`을 보고 탈락한다.

일반 `SELECT`였다면 B는 스냅샷의 `PENDING`을 그대로 봤을 것이다. 엔티티를 읽어 고치지 않고 **JPQL로 직접 쓴 이유**가 이것이다 — 읽고-고치는 사이가 비면 그 틈에 다른 인스턴스가 같은 행을 집어 가고, 그러면 둘 다 보낸다. 조건과 갱신이 한 문장이어야 원자적이다.

> **`version = version + 1`은 여기서 승자를 가르지 않는다.** 가르는 것은 `WHERE`의 `status = PENDING` 하나다. 다만 이 증가가 뒤에서 쓰인다 — `markSent`·`markExpired`·`reclaim`은 잠근 현재 행의 `version`이 **집어 갈 때 받은 스냅샷과 같을 때만** 전이한다. 6장의 `SENDING` 회수가 만드는 "죽은 줄 알았던 옛 발송자가 뒤늦게 표를 고치는" 경우를 이것이 막는다.

**0행의 뜻은 하나가 아니다.** "남이 가져갔다" 말고도 `scheduledAt`이 아직 안 됐거나 행이 사라진 경우가 같은 0으로 온다. `dispatch`는 어느 쪽이든 조용히 빠지면 되니 동작상 문제는 없지만, 8장에서 지적하는 **여러 원인이 한 값에 뭉개지는** 모양과 같다는 건 알고 있어야 한다.

같은 저장소의 고아 파일 정리 배치도 인스턴스가 둘이면 같이 깨어나는데, 거기는 조건부 UPDATE가 아니라 **행을 잠그고 재검사하는** 방식이다. 차이는 멱등성이 아니라 **경쟁 상대**다 — outbox가 경쟁하는 것은 같은 배치의 다른 인스턴스이고 상태가 `PENDING`에서 한 방향으로만 가므로 조건부 UPDATE 한 문장이면 된다. 정리가 경쟁하는 것은 **사용자의 확정 요청**이라 "지워도 되는가"가 왔다 갔다 하고, 그래서 확정 경로와 같은 행 잠금을 잡아야 한다.

**멱등성이 정하는 것은 조율 여부가 아니라 실패 복구 방식이다.** 두 번 지워도 결과가 같으니 중간에 실패한 행을 다음 회차가 그냥 다시 집으면 된다. 발송은 같은 복구가 중복 발송이 되므로 그럴 수 없고, 그래서 위의 `claim`이 필요하다.

### 이 장에 나온 어노테이션

#### `@Scheduled` — 주기는 기본값이 없다

**이 메서드를 주기적으로 자동 실행하라**는 표시다. 누가 부르지 않아도 스프링이 별도 스레드에서 정해진 간격마다 호출한다. 애플리케이션 어딘가에 `@EnableScheduling`이 켜져 있어야 동작한다.

주기는 `cron`·`fixedDelay`·`fixedRate` **중 하나를 반드시 줘야 한다.** 아무것도 안 주면 기동할 때 예외가 난다. 나머지는 `initialDelay = -1`(첫 실행을 늦추지 않음), `timeUnit = MILLISECONDS`, `zone = ""`(서버 기본 시간대)가 기본이다.

| 주기 옵션 | 기준 | 장점 | 단점 |
| --- | --- | --- | --- |
| `fixedDelay` | **이전 실행이 끝난 시점**부터 센다 | 회차가 절대 겹치지 않는다. 한 회차가 밀려도 뒤가 쌓이지 않는다 | 실제 간격이 "주기 + 실행 시간"이라 정확한 주기를 보장하지 않는다 |
| `fixedRate` | **이전 실행이 시작한 시점**부터 센다 | 간격이 일정하다 | 실행이 주기보다 길면 다음 회차가 곧바로 이어 붙어 밀린다 |
| `cron` | 시각을 지정한다(`0 0 3 * * *`) | "새벽 3시" 같은 요구를 그대로 적는다 | 실행 시간을 고려하지 않아 겹칠 수 있고, 서머타임·시간대를 신경 써야 한다 |

`...String` 계열(`fixedDelayString`)은 `PT1M` 같은 ISO-8601 Duration 표기와 `${...}` 프로퍼티 치환을 쓸 수 있다. 밀린 예약이 많아도 회차를 연달아 시작하지 않으려고 `fixedDelay`를 골랐다.

> 함정 하나 — **스케줄러 스레드풀 기본 크기는 1이다.** 한 작업이 오래 끌면 무관한 다른 `@Scheduled`까지 밀린다. 이 저장소도 `spring.task.scheduling.pool.size`를 올려 두지 않아서, 새벽에 도는 파일 정리가 길어지면 **1분 주기 outbox 디스패치가 그동안 밀린다.** 알림 지연이 알림과 무관한 배치에서 오는 경로다.

#### `@ConditionalOnProperty` — 기본은 "설정이 없으면 등록 안 함"

**설정 파일의 값에 따라 이 클래스를 빈으로 등록할지 말지를 정한다.**

용어를 풀면 이렇다.

- **설정** — `application.yml`(또는 `application-test.yml`)에 적는 값. 여기서는 `scheduling.enabled: true` 같은 줄이다.
- **등록** — 스프링 컨테이너가 이 클래스의 객체를 만들어 관리하는 것. 등록되지 않으면 그 객체는 아예 존재하지 않는다.
- **켜고 끈다** — 스케줄러 클래스가 등록되지 않으면 **그 안의 `@Scheduled` 메서드도 돌지 않는다.** 즉 이 한 줄이 배치 전체를 켜고 끄는 스위치다. 테스트에서 1분마다 알림이 나가면 곤란하므로 거기서만 끄는 식으로 쓴다.

```yaml
# application-test.yml — 테스트에서만 스케줄러를 끈다
scheduling:
  enabled: false
```

옵션을 안 주면 `matchIfMissing = false`, `havingValue = ""`다. 즉 **그 프로퍼티가 설정되어 있고 `false`가 아니어야** 등록된다.

| `matchIfMissing` | 동작 | 장점 | 단점 |
| --- | --- | --- | --- |
| `false` (기본) | 설정이 없으면 등록하지 않는다 | 명시적으로 켜야 하므로 실수로 켜지지 않는다 | 설정을 빠뜨리면 기능이 **조용히** 빠진다 |
| `true` | 설정이 없으면 등록한다 | 운영에서 따로 켤 필요가 없고, 테스트에서만 끄면 된다 | 어디에도 안 적혀 있는데 켜져 있어서, 켜진 이유를 추적하기 어렵다 |

여기서는 평소엔 켜 두고 테스트에서만 `scheduling.enabled=false`로 끄려고 `true`를 썼다.

#### `@Modifying` — 기본값은 둘 다 `false`다

**이 `@Query`가 조회가 아니라 변경(`UPDATE`·`DELETE`)임을 알린다.** Spring Data JPA는 기본적으로 쿼리를 조회로 실행하므로, 이게 없으면 변경 쿼리를 만났을 때 예외가 난다. 옵션 둘은 **이 변경과 영속성 컨텍스트(1차 캐시)의 아귀를 맞추는** 설정이다.

`@Modifying`을 그냥 붙이면 `flushAutomatically = false`, `clearAutomatically = false`다. **이 글에서 둘 다 켠 것은 기본값이 아니라 의도적인 선택이다.**

| 옵션 | `false` (기본) | `true` | 장단점 |
| --- | --- | --- | --- |
| `flushAutomatically` | 영속성 컨텍스트에 남은 변경을 DB에 쓰지 않고 쿼리를 실행한다 | 실행 **전**에 먼저 플러시한다 | 켜면 아직 안 써진 변경을 무시한 채 `WHERE`가 평가되는 일이 없다. 대신 필요 없는 상황에서도 플러시 비용을 낸다 |
| `clearAutomatically` | 1차 캐시를 그대로 둔다 | 실행 **후**에 영속성 컨텍스트를 비운다 | **벌크 연산은 영속성 컨텍스트를 우회하므로** 안 비우면 바로 뒤의 `findById`가 아직 `PENDING`인 옛 엔티티를 돌려준다. 대신 **컨텍스트 전체가 비워져** 아직 플러시되지 않은 다른 변경이 사라지므로, 보통 `flushAutomatically`와 짝으로 켠다 |

#### `@Query` / `@Param`

**리포지토리 메서드가 실행할 쿼리를 직접 적는다.** Spring Data는 보통 `findByStatusAndScheduledAtBefore(...)`처럼 메서드 이름에서 쿼리를 만들어 주는데, 이름으로 표현할 수 없는 쿼리(여기서는 조건부 `UPDATE`)는 이렇게 직접 쓴다.

기본은 `nativeQuery = false`, 즉 JPQL이다.

| | 장점 | 단점 |
| --- | --- | --- |
| JPQL (기본) | 엔티티·필드 이름으로 쓰므로 타입이 맞고 DB를 바꿔도 따라온다 | DB 고유 문법(윈도 함수, 힌트 등)을 쓸 수 없다 |
| `nativeQuery = true` | SQL을 그대로 쓴다 | 이식성이 사라지고, 페이징할 때 `countQuery`를 직접 적어야 한다 |

`@Param`은 `:id` 자리에 인자를 묶는다. 안 붙이면 컴파일 시 파라미터 이름이 남아 있어야 동작하므로, 명시하는 편이 안전하다.

---

## 4. 폴링을 기다리지 않는 즉시 발송

행만 넣고 1분 주기 스케줄러가 집어가게 하면 채팅 알림이 최대 1분 늦는다. "대화가 이어지는 동안 도착해야 한다"가 채팅 알림의 전제라 그건 기능을 망친다.

그래서 **넣은 쪽이 커밋 뒤 바로 디스패치를 맡기고, 스케줄러는 놓친 것만 줍는 안전망**으로 둔다.

```text
요청 스레드 ─ 커밋 ─► 실행기에 위임 ─► (다른 스레드) claim → FCM → markSent
                │
                └─ 실행기가 거절해도 행은 남아 있다 → 스케줄러가 줍는다
```

이 배치로 셋을 동시에 얻는다.

- 요청 스레드가 FCM을 기다리지 않는다
- 프로세스가 죽어도 행이 남는다
- 실패하면 다음 회차가 재시도한다

**실행기가 거절해도 업무를 실패로 응답하지 않는다.** 이미 커밋된 업무이고, 저장된 outbox를 배치가 복구하기 때문이다.

### 실행기가 둘인 이유 — 하나는 시점, 하나는 스레드

**① 커밋 후 실행기**는 *언제* 넘길지를 정한다. 스프링의 트랜잭션 동기화에 콜백을 걸어 둔다.

```kotlin
@Adapter
class SpringAfterCommitNotificationExecutor(
    private val dispatchExecutor: NotificationDispatchExecutor,
) : AfterCommitNotificationExecutor {
    override fun execute(task: Runnable) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Notification dispatch requires an active outbox transaction"
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                // 커밋은 끝났다. 실행기 거절이나 CallerRuns 발송 실패로 성공 응답을 실패로 바꾸면
                // 클라이언트가 이미 완료된 업무를 재시도한다. 행은 남아 배치가 다시 집을 수 있다.
                try {
                    dispatchExecutor.execute(task)
                } catch (exception: RuntimeException) {
                    logger.error(exception) { "Immediate notification dispatch failed; outbox polling will recover it" }
                }
            }
        })
    }
}
```

- `afterCommit`은 **롤백되면 아예 호출되지 않는다.** "업무가 롤백되면 발송도 취소된다"가 별도 코드 없이 성립한다.
- 앞의 `check`는 트랜잭션 없이 부르는 것을 막는다. 그 경우 콜백이 영영 불리지 않아 **알림이 조용히 사라지기** 때문에, 조용한 유실 대신 시끄러운 실패로 바꿔 둔다.
- 여기서 예외를 삼키는 것이 핵심이다. 커밋이 이미 끝났으므로 이 시점의 예외를 위로 올리면 **완료된 업무가 실패로 응답된다.**

**② 디스패치 스레드풀**은 *어느 스레드*에서 돌지를 정한다.

```kotlin
@Bean(destroyMethod = "shutdown")
fun dispatchThreadPool(): ThreadPoolTaskExecutor =
    ThreadPoolTaskExecutor().apply {
        corePoolSize = 4      // 발송은 대부분 FCM 을 기다리는 시간이라 CPU 를 쓰지 않는다
        maxPoolSize = 8
        queueCapacity = 500   // 라운딩 임박이 한꺼번에 나갈 때를 받는다
        setThreadNamePrefix("notify-")
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(20)
    }
```

- **스프링 부트 기본 `applicationTaskExecutor`를 쓰지 않는다.** 그쪽은 MVC 비동기 응답도 함께 쓰므로, 알림이 몰릴 때 그 큐가 차면 무관한 요청까지 막힌다.
- **큐가 차면 버리지 않고 부른 스레드가 직접 실행한다(`CallerRuns`).** 버려도 행은 `PENDING`으로 남아 유실은 아니지만, 그러면 "1분 뒤에 나가는 알림"이 된다. 채팅에서는 그 지연이 기능 저하라 요청 스레드가 느려지는 쪽을 택했다 — 그 느려짐은 지표에 바로 보인다.
- 내려갈 때는 보내던 것을 마저 보낸다. 남은 것은 행이 있으니 다음 인스턴스가 줍는다.

그리고 이 `CallerRuns`가 다음 장의 함정을 만든다.

### 이 장에 나온 어노테이션

#### `@Bean(destroyMethod = ...)` — 기본값은 "추론"이다

**이 메서드가 돌려주는 객체를 스프링 빈으로 등록한다.** 직접 `new`로 만들어야 하거나 세밀하게 설정해야 하는 객체(여기서는 스레드풀)를 컨테이너에 맡길 때 쓴다. `destroyMethod`는 **애플리케이션이 종료될 때 그 객체에서 불러 줄 정리 메서드**를 가리킨다.

`@Bean`만 붙이면 `destroyMethod`가 `"(inferred)"`라, 스프링이 **`close()`나 `shutdown()`을 찾아 알아서 부른다.** 즉 위 코드의 `destroyMethod = "shutdown"`은 동작을 바꾸는 설정이 아니라 **의도를 드러내는 설정**이다.

| 값 | 동작 | 장단점 |
| --- | --- | --- |
| 생략 (추론) | `close`/`shutdown`을 찾아 호출 | 편하지만, 종료 훅이 있다는 사실이 코드에 안 보인다 |
| `"shutdown"` 명시 | 그 메서드를 호출 | 무엇이 언제 내려가는지 읽힌다. 이름이 바뀌면 깨진다 |
| `""` | 아무것도 호출하지 않는다 | 외부에서 생명주기를 관리하는 자원에 쓴다. 끄는 것을 잊으면 누수 |

#### `@Configuration(proxyBeanMethods = ...)` — 기본값은 `true`

**이 클래스가 `@Bean` 메서드를 모아 둔 설정 클래스임을 표시한다.** `proxyBeanMethods`는 그 `@Bean` 메서드끼리 서로 호출할 때의 처리를 정한다.

| 값 | 동작 | 장점 | 단점 |
| --- | --- | --- | --- |
| `true` (기본) | CGLIB 프록시를 씌워 `@Bean` 메서드끼리 호출해도 싱글턴을 돌려준다 | 설정 클래스 안에서 `otherBean()`을 직접 불러도 안전하다 | 프록시 생성 비용이 들고, 클래스와 `@Bean` 메서드가 `final`이면 안 된다 |
| `false` | 프록시를 만들지 않는다 | 기동이 가벼워지고 네이티브 이미지에 유리하다 | `@Bean` 메서드를 직접 부르면 **매번 새 인스턴스가 만들어진다.** 의존은 메서드 파라미터로 주입받아야 한다 |

여기서는 `@Bean` 메서드끼리 부르지 않으므로 `false`로 뒀다. 실제로 `dispatchThreadPool`을 `notificationDispatchExecutor`의 **파라미터로 받고 있다.**

`@EnableConfigurationProperties`는 `@ConfigurationProperties` 클래스를 빈으로 등록한다. 컴포넌트 스캔 대상이 아닌 프로퍼티 클래스를 쓰는 쪽에서 명시적으로 켜는 용도다.

---

## 5. 이게 진짜 함정이다 — 트랜잭션 전파

여기까지는 익숙한데, 실제로 붙이면서 가장 많이 부딪힌 건 전파 설정이었다.

### 옵션이 실제로 하는 일

| 전파 | 진행 중인 트랜잭션이 있으면 | 없으면 | 언제 커밋되나 |
| --- | --- | --- | --- |
| `REQUIRED` (기본) | **참여한다** — 경계를 새로 만들지 않는다 | 새로 시작 | 가장 바깥이 끝날 때 함께 |
| `REQUIRES_NEW` | **보류시키고** 별도 커넥션으로 새로 시작 | 새로 시작 | 이 메서드가 끝날 때 즉시 |
| `NOT_SUPPORTED` | **보류시키고** 트랜잭션 없이 실행 | 그냥 실행 | 커밋할 것이 없다 |

"참여한다"와 "보류시킨다"의 차이가 전부다.

- **`REQUIRED`로 참여하면 운명을 공유한다.** 안쪽에서 롤백 표시가 나면 바깥까지 죽고, 반대로 안쪽이 정상 종료해도 그 자리에서 커밋되지 않는다 — 바깥이 끝나야 한다.
- **`REQUIRES_NEW`는 바깥 커넥션을 잠시 떼어 두고 자기 커넥션을 새로 얻는다.** 그래서 안쪽이 커밋한 것은 바깥이 나중에 롤백해도 남는다. 대신 한 스레드가 커넥션을 둘 쥐므로, 이 중첩이 풀 크기만큼 쌓이면 교착이 난다.
- **`NOT_SUPPORTED`는 트랜잭션을 아예 열지 않는다.** 보류시키면서 그 스레드에 묶여 있던 **영속성 컨텍스트(`EntityManager`)까지 함께 풀어 두고**, 메서드가 끝나면 되돌려 놓는다. 이 점이 아래 함정의 해법이 된다. 대신 그 안에서 여러 번 쓰면 **각각이 따로 커밋되어 원자성이 없다** — 이 글에서는 그게 오히려 원하는 성질이다.

나머지 넷은 이 설계에서 쓰지 않았지만 같이 적어 둔다.

| 전파 | 동작 | 장점 | 단점 |
| --- | --- | --- | --- |
| `SUPPORTS` | 있으면 참여, 없으면 트랜잭션 없이 실행 | 호출 맥락을 가리지 않는 조회 유틸에 편하다 | **맥락에 따라 동작이 갈린다.** 지연 로딩이 될 때도 안 될 때도 있어 예측이 어렵다 |
| `MANDATORY` | 없으면 `IllegalTransactionStateException` | 경계 없이 부르는 실수를 호출 즉시 드러낸다 | 단독 재사용이 불가능해진다 |
| `NEVER` | 있으면 예외 | 트랜잭션이 걸리면 안 되는 구간을 강제한다 | 쓸 일이 드물다 |
| `NESTED` | 세이브포인트를 잡아 안쪽만 되돌린다 | 커넥션 하나로 부분 롤백이 된다 | JDBC 세이브포인트가 필요해 **`JpaTransactionManager`에서는 기본적으로 쓸 수 없다** |

### 어디에 무엇을 걸었나

| 자리 | 전파 | 왜                                    |
| --- | --- |--------------------------------------|
| 즉시 발송·예약 enqueue | `REQUIRED` | 업무와 outbox를 함께 커밋한다                  |
| 예약 취소 | `REQUIRED` | 업무 변경이 롤백되면 유효한 예약이 취소된 채로 남지 않아야 한다 |
| **커밋 후 dispatch** | **`NOT_SUPPORTED`** | 아래 설명 참고                             |
| 상태 전이(claim·markSent·reclaim) | `REQUIRES_NEW` | 각각 짧게 커밋한다                           |

`NOT_SUPPORTED`가 왜 필요한지가 이 설계에서 가장 안 보이는 부분이었다.

발송은 보통 별도 스레드에서 돈다. 그런데 **스레드풀 큐가 차면 `CallerRuns` 정책이 같은 스레드에서 실행한다.** 그 시점에는 원래 영속성 컨텍스트가 아직 살아 있고, outbox 행을 조회하면 **1차 캐시에 있는 삽입 당시의 `PENDING` 스냅샷**을 돌려준다. 방금 다른 트랜잭션이 `SENDING`으로 바꿔 놨어도 그게 안 보인다.

`NOT_SUPPORTED`는 현재 트랜잭션을 **중단**시켜 이 재사용을 끊는다. 평소에는 아무 일도 안 하는 설정인데, **큐가 찬 순간에만 동작이 갈린다.** 부하 테스트 전에는 드러나지 않는 종류의 버그다.

```kotlin
@Transactional(propagation = Propagation.NOT_SUPPORTED)
fun dispatch(outboxId: Long) {
    val at = clock.instant()
    val outbox = outboxWriter.claim(outboxId, at)   // ← 여기서부터 각자 REQUIRES_NEW
    if (outbox == null) {
        logger.debug { "Outbox already claimed by someone else: id=$outboxId" }
        return
    }

    runCatching { send(outbox, at) }
        .onFailure { failure ->
            // SENDING 으로 남는다. reclaimStale 이 되돌리고 다음 회차가 다시 시도한다.
            logger.error(failure) { "Outbox dispatch failed: id=$outboxId, ..." }
        }
}
```

이 메서드 자체는 트랜잭션이 없고, 안에서 부르는 상태 전이만 각각 새 트랜잭션을 연다.

```kotlin
@Component
class NotificationOutboxWriter(
    private val repository: NotificationOutboxRepository,
) {
    /** 넣는 쪽만 REQUIRED — 업무가 롤백되면 예약도 함께 사라져야 한다 */
    @Transactional
    fun save(outbox: NotificationOutbox): NotificationOutbox = repository.save(outbox)

    /** 커밋되어야 다른 인스턴스에 보이므로 반드시 독립된 트랜잭션이다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(id: Long, at: Instant): NotificationOutbox? =
        if (repository.claim(id, at) == 1) repository.findById(id).orElseThrow() else null

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSent(outbox: NotificationOutbox, at: Instant) = updateClaim(outbox) { it.markSent(at) }
    // markExpired, reclaim 도 같다
}
```

`claim`이 `REQUIRES_NEW`여야 하는 이유가 여기서 분명해진다. **집어 간 사실이 즉시 커밋되지 않으면 다른 인스턴스가 같은 행을 또 집는다** — 3장의 단일 실행 보장이 통째로 무너진다. 반대로 `save`는 `REQUIRED`여야 한다. 여기서 새 트랜잭션을 열면 업무가 롤백돼도 예약만 남는다.

`dispatch`에 `NOT_SUPPORTED` 대신 아무것도 안 붙이면 어떻게 되나 — 평소에는 똑같이 동작한다. `CallerRuns`로 요청 스레드에 실려 왔을 때만 바깥 트랜잭션이 살아 있어 갈린다. **"평소에는 차이가 없는 설정"이 정확히 그 이유로 위험하다.**

---

## 6. 이슈에 없던 상태를 둘 더했다

처음 설계에는 `PENDING → SENDING → SENT`만 있었다. 여기서 두 가지 수정 사항이 있었다.

**`EXPIRED` — 유예 시간(기본 1시간)을 넘긴 예약.** 티오프가 지난 뒤 도착하는 "24시간 전 임박" 알림은 보내면 오히려 이상하다. 장애로 며칠 밀린 예약이 복구와 동시에 한꺼번에 튀어나오는 것도 이것이 막는다.

**`SENDING` 회수 — 집어 가던 중 죽은 행을 `PENDING`으로 되돌린다.** 원래 설계에는 전이만 있고 거기서 죽으면 그 알림이 **영영 안 나가는** 구멍이 있었다.

여기서 방향을 하나 정해야 했다. 중복과 유실 중 무엇이 더 나쁜가.

> **유실을 더 나쁘게 본다. 중복은 사용자가 알아채고 넘기지만, 오지 않은 알림은 아무도 모른다.**

회수는 중복 발송 가능성을 만든다(죽은 줄 알았는데 살아 있던 경우). 그걸 감수한 결정이다. 다만 피해 범위는 좁혀 둔다 — 상태 전이는 잠근 행의 `version`이 집어 갈 때 받은 스냅샷과 같을 때만 일어나므로, **되돌아온 옛 발송자가 새 발송자의 상태를 덮어쓰지는 못한다.** 중복으로 나가는 것은 FCM 메시지뿐이고 표는 일관되게 남는다.

**조립기가 없는 유형은 끝난 것으로 표시하지 않는다.** 알림 유형을 더했는데 문구 조립기를 안 만든 경우다. `EXPIRED`로 두면 "너무 늦어서"와 "코드 버그로"가 같은 상태로 뭉개져서, 나중에 표를 봐도 무엇이 문제였는지 알 수 없다. `SENDING`으로 남겨 두면 조립기를 붙여 배포했을 때 밀려 있던 것이 그대로 나가고, 안 붙이면 유예 시간이 지나 자연히 `EXPIRED`로 간다. **그동안 ERROR 로그가 반복되는 것은 의도다 — 코드 버그는 시끄러워야 한다.**

---

## 7. 무엇을 담고 무엇을 안 담나

outbox 행에 **문구를 담지 않는다.** 유형과 주제만 담고 발송 시점에 조립한다. 담으면 채팅 알림 본문이 메시지 본문의 사본이 되어, 별도 발송 내역 표를 두지 않기로 한 결정과 어긋난다.

대가는 **"사건 시점의 값"을 보장하지 않는다**는 것이다. 즉시 발송에서는 커밋과 발송 사이가 밀리초라 사실상 같고, 예약 발송에서는 오히려 최신 값이 맞다 — 티오프가 바뀌었으면 바뀐 시각을 알려야 한다.

**주제와 목적지를 가른 것**도 같은 맥락이다. 조인 신청 접수 알림의 주제는 **그 신청**이고(누구의 신청인지 알아야 문구를 만든다) 딥링크 목적지는 **모집글**이다. 주제를 모집글로 두면 지원자가 셋일 때 발송 시점에 누구 건지 알 수 없다.

### 정리 배치를 두지 않는다

행이 메시지 수만큼 쌓이지만 지우지 않는다. 이 표가 결과적으로 발송 이력 역할을 하는데, **본문이 없어서** 내역 표를 두지 않기로 한 결정에 걸리지 않는다.

| 질문 | outbox로 답하나 |
| --- | --- |
| 이 메시지·신청에 알림을 보냈나, 언제 | ✅ 행과 `sent_at` |
| 왜 안 나갔나 | ✅ `CANCELED`·`EXPIRED`·`PENDING`에 머묾 |
| 누가 받았나, 실제로 배달됐나 | ❌ 로그에만 |

성능도 문제가 아니다. 조회 경로가 전부 인덱스를 타고 `PENDING`이 인덱스에서 한곳에 모여 있어, 종료 행이 아무리 쌓여도 스케줄러 쿼리는 그대로다. **풀스캔하는 쿼리가 없다.**

---

## 8. 실패를 한 숫자로 묶으면 진단이 정반대로 간다

처음에는 `failed = requested - delivered` 하나만 뒀다. dev 검증에서 바로 걸렸다.

가짜 토큰으로 발송했더니 이런 응답이 나왔다.

```json
{"requested":1,"delivered":0,"invalidTokens":[],"failed":1}
```

문제는 **이 응답이 두 경우에 똑같이 나온다**는 것이다 — FCM이 그 토큰을 거절한 경우와, FCM에 닿지도 못한 경우. 결국 클라우드 감사 로그를 뒤져서 갈랐는데, 그건 우연히 남은 흔적이지 설계된 관측 지점이 아니다.

"알림이 안 와요" 문의의 첫 질문은 **우리가 보낸 사실이 있는지**인데 그 답이 없었다.

| 값 | 뜻 | 볼 곳 |
| --- | --- | --- |
| `delivered` | FCM이 접수했다 | 앱 — 권한·포그라운드 처리 |
| `rejected` | FCM이 응답했지만 거절했다 | 죽은 토큰이거나 APNs 설정 |
| `unreachable` | **FCM에 닿지도 못했다** | **우리 쪽** — 자격 증명·네트워크 |

`unreachable`은 나머지로 계산한다(`requested - delivered - rejected`). 구현이 따로 세게 하면 합이 어긋날 수 있고, **그 어긋남은 진단을 정반대로 이끈다.** 그리고 `unreachable > 0`일 때만 ERROR로 남긴다 — 죽은 토큰과 같은 수준으로 남기면 로그가 어디를 볼지 말해 주지 못한다.

---

## 9. 그래서 "정확히 한 번"인가

아니다. 그렇게 쓰지 않는 게 낫다.

```text
claim(SENDING) ──► FCM 접수 ──► ✗ 프로세스 죽음
                                  └─ 회수가 PENDING으로 되돌림 → 다시 보냄 (중복)
```

FCM이 접수한 뒤 상태를 기록하기 전에 죽으면 중복된다. 여기에 유예 시간 만료와 수신자별 실패 정책까지 얹히므로, 외부 전달은 **정확히 한 번을 보장하지 않는다.**

이걸 문서와 코드 주석에 명시해 뒀다. 보장하지 않는 것을 보장한다고 적으면, 나중에 중복이 보고됐을 때 **버그를 찾느라 시간을 쓴다.** 주석을 활용해 설계된 동작임을 명시하자.

---

## 느낀 점

**"알림 실패로 업무를 되돌리지 않는다"와 "업무가 성공했으면 알림이 반드시 나간다"는 모순이 아니다.** 기록과 발송을 나누면 앞은 발송에, 뒤는 기록에 걸린다. 요구사항이 모순처럼 보일 때 **하나의 동작으로 읽고 있는 것을 둘로 쪼갤 수 있는지** 먼저 본다.

**분산 조율이 필요해 보일 때, 상태 전이 한 줄로 끝나는지 먼저 본다.** 인스턴스가 둘이 되는 순간 중복 발송이 시작되니 분산 락이나 리더 선출로 손이 가기 쉽다. 실제로 필요했던 것은 `WHERE status = 'PENDING'`이 붙은 UPDATE 하나였다. **조건과 갱신이 한 문장이면 "1행을 고친 쪽만 진행한다"는 성질은 DB가 이미 준다** — 새로 들일 것 없이, 인스턴스를 몇 대로 늘려도 그대로 성립한다.

**조율 방식을 정하는 것은 멱등성이 아니라 경쟁 상대다.** 처음에는 "삭제는 멱등이라 정리 배치는 조율이 필요 없다"고 적었는데, 정리도 행 잠금과 재검사로 조율하고 있었다. 갈린 것은 **누구와 경쟁하느냐**였다 — 한 방향으로만 가는 상태를 다른 인스턴스와 다투면 조건부 UPDATE로 끝나고, 되돌아올 수 있는 조건을 사용자 요청과 다투면 잠금이 필요하다. 멱등성이 답하는 질문은 따로 있었다. **조율이 필요한가가 아니라, 실패했을 때 그냥 다시 해도 되는가**다.

**중복과 유실 중 무엇이 더 나쁜지를 먼저 정해야 설계가 결정된다.** 이걸 안 정하면 회수를 넣을지 말지가 계속 왔다 갔다 한다. 우리는 유실을 더 나쁘게 봤고, 그 한 문장이 `SENDING` 회수·안전망 스케줄러·"정확히 한 번 아님" 선언을 전부 따라오게 했다.

**평소에 아무 일도 안 하는 설정이 제일 위험하다.** `NOT_SUPPORTED`는 스레드풀 큐가 차야 동작이 갈린다. 부하 테스트 전에는 안 드러나고, 드러났을 때는 "알림이 가끔 안 나간다"처럼 재현 안 되는 형태로 온다. 이런 설정은 **왜 있는지를 코드 옆에 적어 두지 않으면 다음 사람이 지운다.**

**관측값을 뺄셈으로 만들면 두 원인이 한 숫자에 뭉개진다.** `failed = requested - delivered`는 우리 쪽 장애와 상대 쪽 거절을 구분 못 한다. **지표를 설계할 때 "이 값이 올랐을 때 어디를 보는가"를 먼저 적어 보면** 뭉갠 것이 보인다.

**보장하지 않는 것은 보장하지 않는다고 써야 한다.** "정확히 한 번"이라고 적어 두면 중복이 보고됐을 때 없는 버그를 찾는다. 문서의 가치는 무엇을 보장하느냐만큼 **무엇을 보장하지 않느냐**에 있다.

---

## 정리

- 알림 발송을 업무 트랜잭션 안에 두면 **DB 커넥션이 외부 지연에 묶이고**, 정해진 시각에 몰리는 발송에서 풀이 먼저 마른다
- outbox **기록**은 업무와 같은 트랜잭션(`REQUIRED`), **발송**은 커밋 후 트랜잭션 밖. "알림 실패로 업무를 되돌리지 않는다"는 발송에 걸리는 말이고, 기록 실패는 되돌려야 한다
- 인스턴스가 둘이면 `@Scheduled`가 중복 발송을 만든다. **`WHERE status='PENDING'` 조건부 UPDATE가 1행을 고친 쪽만** 보내게 하면 잠금도 리더 선출도 필요 없다
- "1행을 고쳤는가"는 **UPDATE의 반환 행 수**다. `UPDATE ... WHERE`는 잠금 읽기라 스냅샷이 아닌 **최신 커밋본**을 보므로, 뒤에 온 쪽은 이미 `SENDING`인 것을 읽고 0행이 된다. 다만 0행은 "남이 가져감"·"아직 시각 전"·"행 없음"이 뭉쳐 있다
- 조율 방식을 정하는 것은 멱등성이 아니라 **경쟁 상대**다. 파일 정리 배치도 조율은 한다 — 사용자의 확정 요청과 다투므로 조건부 UPDATE가 아니라 잠금·재검사를 쓸 뿐이다. 멱등성이 답하는 것은 **실패했을 때 그냥 다시 해도 되는가**다
- `spring.task.scheduling.pool.size`가 기본 1이라 **무관한 배치가 길어지면 outbox 디스패치가 밀린다.** 알림 지연이 알림 바깥에서 오는 경로다
- 폴링만 쓰면 채팅 알림이 최대 1주기 늦는다. **넣은 쪽이 커밋 후 바로 위임하고 스케줄러는 안전망**으로 두면 지연·유실·재시도를 동시에 얻는다
- `CallerRuns`로 같은 스레드에서 실행되면 **원래 영속성 컨텍스트가 옛 스냅샷을 돌려준다.** 커밋 후 dispatch를 `NOT_SUPPORTED`로 둬 자원을 중단시킨다 — 큐가 찰 때만 갈리는 동작이라 늦게 발견된다
- **유실을 중복보다 나쁘게 보기로 정하면** `SENDING` 회수·안전망·"정확히 한 번 아님"이 따라 나온다. 이 한 문장을 먼저 정해야 한다
- 조립기가 없는 유형을 `EXPIRED`로 두면 "늦어서"와 "버그로"가 뭉개진다. `SENDING`으로 남겨 **ERROR 로그가 계속 울게** 둔다
- outbox에 **문구를 담지 않는다.** 대가는 "사건 시점의 값"을 보장하지 않는 것이고, 예약 발송에서는 오히려 최신 값이 맞다
- **주제와 목적지는 다르다.** 문구를 만들 수 있는 단위가 주제이고, 딥링크가 목적지다
- 실패를 한 숫자로 묶으면 **우리 쪽 장애와 상대 쪽 거절이 구분되지 않는다.** `delivered`/`rejected`/`unreachable`로 갈라야 로그가 어디를 볼지 말해 준다
- 외부 전달은 **정확히 한 번이 아니다.** 접수 후 기록 전 장애면 중복된다. 명시해 두지 않으면 없는 버그를 찾게 된다

## 참고

- [Transactional outbox pattern — microservices.io](https://microservices.io/patterns/data/transactional-outbox.html)
- [Transaction Propagation — Spring Framework Reference](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [`@TransactionalEventListener` — Spring Framework Reference](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [`ThreadPoolExecutor.CallerRunsPolicy` — Java SE API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.CallerRunsPolicy.html)
- [Send messages to multiple devices — Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging/send-message)
- [FCM error codes (`UNREGISTERED`, `INVALID_ARGUMENT`) — Firebase](https://firebase.google.com/docs/reference/fcm/rest/v1/ErrorCode)
