# Kotlin Coroutine — 비동기 처리의 핵심

## 개요
Kotlin Coroutine은 비동기 프로그래밍을 동기 코드처럼 직관적으로 작성할 수 있게 해주는 경량 동시성 프레임워크이다. 스레드 기반 비동기 처리의 한계를 극복하고, 구조화된 동시성(Structured Concurrency)을 통해 안전하고 예측 가능한 비동기 흐름을 제공한다.

## 상세 내용

### 1. Coroutine이란 무엇인가

Coroutine은 "일시 중단 가능한 계산 단위(suspendable computation)"이다. 특정 지점에서 실행을 잠시 멈추고(suspend), 나중에 다시 재개(resume)할 수 있다. 이 덕분에 비동기 코드를 마치 순차적인 동기 코드처럼 작성할 수 있다.

**경량 스레드와의 차이**

코루틴은 종종 "경량 스레드"라고 불리지만, 실제로 OS 스레드와는 근본적으로 다르다.
- 스레드는 OS가 관리하며 컨텍스트 스위칭 비용이 크다
- 코루틴은 JVM 런타임이 관리하며, 한 스레드 위에서 여러 코루틴이 번갈아 실행된다
- 코루틴은 스레드를 점유하지 않고 suspend 지점에서 스레드를 반납한다

```kotlin
// 코루틴 100,000개 동시 실행 — 메모리 약 수백 MB
fun main() = runBlocking {
    repeat(100_000) {
        launch {
            delay(1000L)
            print(".")
        }
    }
}

// 스레드 100,000개 — 메모리 약 100GB, 현실적으로 불가
fun main() {
    repeat(100_000) {
        thread {
            Thread.sleep(1000L)
            print(".")
        }
    }
}
```

**협력적 멀티태스킹(Cooperative Multitasking)**

스레드의 선점형(preemptive) 방식과 달리, 코루틴은 협력적(cooperative) 방식으로 동작한다. 코루틴이 스스로 `suspend` 지점에서 양보(yield)할 때만 다른 코루틴이 실행 기회를 얻는다. 이는 명시적인 suspend 포인트가 없으면 코루틴이 스레드를 독점한다는 의미이기도 하다.

**suspend 함수의 동작 원리**

`suspend` 키워드가 붙은 함수는 컴파일 시 Continuation Passing Style(CPS)로 변환된다. 내부적으로 상태 머신(state machine)이 생성되어 현재 실행 위치와 지역 변수를 저장하고, 재개 시점에 복원한다.

```kotlin
// 작성하는 코드
suspend fun fetchUserData(userId: Long): User {
    val user = userRepository.findById(userId)  // 여기서 suspend
    val profile = profileService.get(userId)    // 여기서 suspend
    return user.copy(profile = profile)
}

// 컴파일 후 개념적 동작 (단순화)
fun fetchUserData(userId: Long, continuation: Continuation<User>): Any {
    // 상태 머신으로 변환되어 각 suspend 지점 사이의 상태를 관리
}
```

---

### 2. Coroutine vs Thread

| 항목 | Thread | Coroutine |
|------|--------|-----------|
| 메모리 | 약 1~2MB/개 | 약 수KB/개 |
| 동시 실행 가능 수 | 수천 개 한계 | 수만~수십만 개 |
| 컨텍스트 스위칭 | OS 레벨, 비용 큼 | JVM 레벨, 비용 작음 |
| 블로킹 | 대기 중에도 스레드 점유 | 대기 중 스레드 반납 |
| 취소(Cancellation) | interrupt() — 불안정 | 구조화된 취소 지원 |
| 예외 처리 | 독립적, 전파 어려움 | 계층 구조로 전파 |

**블로킹 vs 논블로킹 비교**

```kotlin
// Thread 기반 — 블로킹 I/O
fun fetchBlocking(): String {
    Thread.sleep(1000)  // 스레드 1개 1초 동안 점유
    return "result"
}

// Coroutine 기반 — 논블로킹
suspend fun fetchNonBlocking(): String {
    delay(1000)  // 스레드 반납 후 1초 뒤 재개
    return "result"
}
```

`delay()`는 스레드를 블로킹하지 않고 코루틴만 일시 중단한다. 그 사이 스레드는 다른 코루틴의 작업을 처리할 수 있다.

---

### 3. Coroutine Builder

**`launch` — Fire-and-forget**

결과값 없이 백그라운드 작업을 시작할 때 사용한다. `Job` 객체를 반환하며 취소(cancel)나 완료 대기(join)에 활용한다.

```kotlin
val job: Job = scope.launch {
    delay(1000L)
    println("작업 완료")
}

job.join()    // 완료 대기
job.cancel()  // 취소
```

**`async` / `await` — 결과를 반환하는 비동기 처리**

결과값이 필요한 비동기 작업에 사용한다. `Deferred<T>` 를 반환하며, `await()`로 결과를 받는다. 여러 작업을 병렬로 실행하고 결과를 합산할 때 유용하다.

```kotlin
// 순차 실행 — 총 2초
suspend fun sequential() {
    val user = fetchUser()      // 1초
    val orders = fetchOrders()  // 1초
    println("${user}, ${orders}")
}

// 병렬 실행 — 총 1초
suspend fun parallel() = coroutineScope {
    val user = async { fetchUser() }      // 동시 시작
    val orders = async { fetchOrders() }  // 동시 시작
    println("${user.await()}, ${orders.await()}")
}
```

**`runBlocking` — 코루틴과 일반 코드의 브릿지**

현재 스레드를 블로킹하면서 코루틴 블록이 완료될 때까지 기다린다. 테스트 코드나 `main()` 함수에서만 사용하며, 프로덕션 코드에서는 지양한다.

```kotlin
fun main() = runBlocking {  // main 스레드 블로킹
    launch {
        delay(1000L)
        println("World!")
    }
    println("Hello,")
}
```

---

### 4. CoroutineScope와 CoroutineContext

**CoroutineScope의 역할과 생명주기 관리**

`CoroutineScope`는 코루틴의 생명주기를 관리하는 컨테이너이다. 스코프 내에서 시작된 모든 코루틴은 해당 스코프의 자식이 되며, 스코프가 취소되면 모든 자식 코루틴도 함께 취소된다.

```kotlin
class UserService(
    private val userRepository: UserRepository
) : CoroutineScope by CoroutineScope(Dispatchers.IO) {

    fun loadUsers() {
        launch {
            val users = userRepository.findAll()
            println("Loaded: ${users.size} users")
        }
    }

    fun clear() {
        cancel()  // 이 스코프에서 실행 중인 모든 코루틴 취소
    }
}
```

**CoroutineContext 구성 요소**

`CoroutineContext`는 코루틴의 실행 환경을 정의하는 불변 맵(immutable map)이다. `+` 연산자로 여러 컨텍스트를 조합할 수 있다.

| 요소 | 설명 |
|------|------|
| `Job` | 코루틴의 생명주기 제어 (Active → Completing → Completed/Cancelled) |
| `CoroutineDispatcher` | 실행할 스레드 결정 |
| `CoroutineName` | 디버깅용 이름 |
| `CoroutineExceptionHandler` | 미처리 예외 핸들러 |

```kotlin
val context = Dispatchers.IO + CoroutineName("data-loader") + SupervisorJob()

launch(context) {
    println(coroutineContext[CoroutineName])  // CoroutineName(data-loader)
    println(coroutineContext[Job])            // 현재 Job 상태
}
```

**GlobalScope의 위험성과 대안**

`GlobalScope`는 애플리케이션 전체 생명주기와 연결되어 있어 취소 불가능하고, 메모리 누수와 예측 불가능한 동작을 유발한다.

```kotlin
// 나쁜 예 — GlobalScope 사용
GlobalScope.launch {
    delay(10000L)
    println("이 코루틴은 취소되지 않음")
}

// 좋은 예 — 적절한 스코프 사용
class MyService(private val scope: CoroutineScope) {
    fun doWork() {
        scope.launch {
            delay(10000L)
            println("스코프가 취소되면 함께 취소됨")
        }
    }
}
```

---

### 5. Dispatcher 종류와 선택 기준

Dispatcher는 코루틴이 어떤 스레드에서 실행될지를 결정한다. 작업 유형에 맞는 Dispatcher 선택이 성능에 직접적인 영향을 미친다.

**`Dispatchers.Default` — CPU 집약 작업**

공유 스레드 풀(CPU 코어 수에 비례)을 사용한다. 정렬, 파싱, 복잡한 계산 등 CPU를 많이 사용하는 작업에 적합하다.

```kotlin
launch(Dispatchers.Default) {
    val sorted = largeList.sortedBy { it.name }  // CPU 집약 작업
    val parsed = heavyJsonParsing(rawData)
}
```

**`Dispatchers.IO` — I/O 작업**

블로킹 I/O에 최적화된 스레드 풀로, 최대 64개(또는 CPU 코어 수 중 큰 값)의 스레드를 사용한다. 네트워크 요청, 파일 읽기/쓰기, 데이터베이스(JDBC) 접근에 사용한다.

```kotlin
suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
    File(path).readText()  // 블로킹 I/O를 IO 스레드에서 실행
}

suspend fun fetchFromDB(id: Long): User = withContext(Dispatchers.IO) {
    jdbcRepository.findById(id)  // JDBC는 블로킹이므로 IO Dispatcher 필요
}
```

**`Dispatchers.Main` — UI 스레드**

Android나 JavaFX 등 UI 프레임워크의 메인 스레드에서만 실행한다. UI 업데이트 시 사용하며, 서버 사이드에서는 거의 사용하지 않는다.

**`Dispatchers.Unconfined` — 제한 없는 디스패처**

호출한 스레드에서 즉시 시작하고, 재개 시에는 suspend 함수가 사용한 스레드에서 실행된다. 예측 불가능하므로 특수 목적(테스트, 이벤트 루프) 외에는 사용을 지양한다.

**`withContext()`를 통한 Dispatcher 전환**

```kotlin
suspend fun processUserRequest(userId: Long): UserResponse {
    // IO Dispatcher: DB 조회
    val user = withContext(Dispatchers.IO) {
        userRepository.findById(userId)
    }
    // Default Dispatcher: 복잡한 계산
    val result = withContext(Dispatchers.Default) {
        computeRecommendations(user)
    }
    return UserResponse(user, result)
}
```

---

### 6. 구조화된 동시성 (Structured Concurrency)

구조화된 동시성은 코루틴의 생명주기가 명확한 범위(scope) 내에서 관리됨을 보장하는 원칙이다. 코루틴은 절대 스코프 밖으로 누수되지 않으며, 부모-자식 계층 구조를 형성한다.

**핵심 규칙**
1. 자식 코루틴은 부모보다 오래 살 수 없다
2. 부모가 취소되면 모든 자식이 취소된다
3. 자식 중 하나가 실패하면 부모와 다른 자식도 취소된다 (기본 Job)

```kotlin
coroutineScope {          // 부모 스코프
    launch { task1() }    // 자식 1
    launch { task2() }    // 자식 2
    launch {
        launch { task3() } // 손자
    }
} // 모든 자식/손자 완료 후 여기에 도달
```

**`coroutineScope` vs `supervisorScope`**

```kotlin
// coroutineScope — 하나라도 실패하면 전체 취소
suspend fun fetchAll() = coroutineScope {
    val a = async { fetchA() }  // fetchA()가 실패하면
    val b = async { fetchB() }  // fetchB()도 취소됨
    listOf(a.await(), b.await())
}

// supervisorScope — 각 자식이 독립적으로 실패 처리
suspend fun fetchAllIndependent() = supervisorScope {
    val a = async { fetchA() }  // fetchA()가 실패해도
    val b = async { fetchB() }  // fetchB()는 계속 실행
    listOf(
        runCatching { a.await() }.getOrNull(),
        runCatching { b.await() }.getOrNull()
    )
}
```

---

### 7. 예외 처리

**`launch` vs `async`에서의 예외 전파 차이**

`launch`와 `async`는 예외를 처리하는 방식이 근본적으로 다르다.

```kotlin
// launch — 예외가 즉시 전파됨 (try-catch 필요 없음, 핸들러로 잡아야 함)
val job = scope.launch {
    throw RuntimeException("launch 예외")  // 즉시 부모로 전파
}

// async — 예외가 Deferred에 저장됨 (await() 시점에 발생)
val deferred = scope.async {
    throw RuntimeException("async 예외")  // await() 호출 시까지 저장
}
try {
    deferred.await()  // 여기서 예외 발생
} catch (e: RuntimeException) {
    println("async 예외 처리: ${e.message}")
}
```

**`CoroutineExceptionHandler`**

`launch`로 시작한 루트 코루틴의 미처리 예외를 전역에서 처리한다. `async`에는 효과가 없다.

```kotlin
val handler = CoroutineExceptionHandler { context, exception ->
    println("Coroutine ${context[CoroutineName]} 에서 예외 발생: $exception")
}

val scope = CoroutineScope(Dispatchers.Default + handler)

scope.launch(CoroutineName("data-fetcher")) {
    throw IOException("네트워크 오류")  // handler에서 처리됨
}
```

**`SupervisorJob`을 활용한 독립적 실패 처리**

일반 `Job`은 자식 실패를 부모로 전파하지만, `SupervisorJob`은 자식이 독립적으로 실패할 수 있게 한다.

```kotlin
// 일반 Job — 하나가 실패하면 모두 취소
val normalScope = CoroutineScope(Dispatchers.Default + Job())
normalScope.launch { task1() }  // task2() 실패 시 이것도 취소됨
normalScope.launch { task2() }  // 예외 발생

// SupervisorJob — 각 자식 독립 실행
val supervisorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
supervisorScope.launch { task1() }  // task2() 실패해도 계속 실행
supervisorScope.launch { task2() }  // 예외 발생해도 task1에 영향 없음
```

> 주의: `withContext(SupervisorJob())`는 기대대로 동작하지 않는다. SupervisorJob은 CoroutineScope 생성 시 또는 supervisorScope 블록에서 사용해야 한다.

---

### 8. Flow — 코루틴 기반 리액티브 스트림

**Cold Stream vs Hot Stream**

| 항목 | Cold Stream (Flow) | Hot Stream (SharedFlow, StateFlow) |
|------|-------------------|-------------------------------------|
| 실행 시점 | 구독(collect) 시 시작 | 구독 여부와 관계없이 실행 |
| 구독자마다 | 각자 독립 실행 | 동일한 스트림 공유 |
| 활용 | DB 조회, API 호출 | 이벤트 브로드캐스트, UI 상태 |

**Flow 기본 사용법**

```kotlin
// Flow 생성
fun numberFlow(): Flow<Int> = flow {
    for (i in 1..10) {
        delay(100)
        emit(i)  // 값 방출
    }
}

// Flow 수집
suspend fun collect() {
    numberFlow()
        .filter { it % 2 == 0 }
        .map { it * it }
        .collect { println(it) }  // 4, 16, 36, 64, 100
}
```

**`StateFlow` — 상태 관리용 Hot Stream**

현재 상태를 항상 보유하며, 새 구독자에게 최신값을 즉시 제공한다. UI 상태 관리에 적합하다.

```kotlin
class UserViewModel {
    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun loadUser(userId: Long) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val user = userService.getUser(userId)
                _uiState.value = UiState.Success(user)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message)
            }
        }
    }
}
```

**`SharedFlow` — 이벤트 브로드캐스트용 Hot Stream**

여러 구독자에게 동일한 이벤트를 전달한다. 일회성 이벤트(네비게이션, 알림)에 적합하다.

```kotlin
class EventBus {
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,            // 새 구독자에게 이전 이벤트 재전송 없음
        extraBufferCapacity = 64  // 버퍼 크기
    )
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    suspend fun emit(event: AppEvent) {
        _events.emit(event)
    }
}
```

**주요 Flow 연산자**

```kotlin
flow
    .map { transform(it) }           // 변환
    .filter { condition(it) }        // 필터링
    .take(5)                         // 앞 5개만
    .debounce(300)                   // 300ms 내 마지막 값만 (검색어 입력)
    .distinctUntilChanged()          // 동일값 연속 방출 무시
    .flatMapLatest { fetchData(it) } // 새 값 오면 이전 작업 취소
    .catch { e -> emit(defaultValue) } // 예외 처리
    .collect { println(it) }         // 수집 (터미널 연산)
```

**`combine`으로 여러 Flow 병합**

```kotlin
val userFlow: Flow<User> = getUserFlow()
val settingsFlow: Flow<Settings> = getSettingsFlow()

combine(userFlow, settingsFlow) { user, settings ->
    UserWithSettings(user, settings)
}.collect { println(it) }
```

---

### 9. Spring Boot에서의 Coroutine 활용

Spring Framework 5.2(Boot 2.2)부터 Kotlin Coroutine을 공식 지원한다. WebFlux 위에서 코루틴을 사용하면 리액티브 코드의 복잡성 없이 논블로킹 처리를 구현할 수 있다.

**의존성 설정**

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")  // Reactor 연동 필수

    // R2DBC
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("io.r2dbc:r2dbc-postgresql")
}
```

**suspend 함수 기반 Controller**

Spring WebFlux는 suspend 함수를 지원하므로, Mono/Flux 대신 suspend 함수와 Flow를 직접 반환할 수 있다.

```kotlin
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService
) {

    // suspend 함수로 단일 객체 반환
    @GetMapping("/{id}")
    suspend fun getUser(@PathVariable id: Long): UserResponse {
        return userService.findById(id)
    }

    // Flow로 스트리밍 응답
    @GetMapping
    fun getUsers(): Flow<UserResponse> {
        return userService.findAll()
    }

    // 병렬 처리
    @GetMapping("/{id}/dashboard")
    suspend fun getDashboard(@PathVariable id: Long): DashboardResponse = coroutineScope {
        val user = async { userService.findById(id) }
        val orders = async { orderService.findByUserId(id) }
        DashboardResponse(user.await(), orders.await())
    }
}
```

**suspend 함수 기반 Service**

```kotlin
@Service
class UserService(
    private val userRepository: UserRepository
) {
    suspend fun findById(id: Long): UserResponse {
        return userRepository.findById(id)
            ?.toResponse()
            ?: throw UserNotFoundException(id)
    }

    fun findAll(): Flow<UserResponse> {
        return userRepository.findAll().map { it.toResponse() }
    }
}
```

**R2DBC와 코루틴 연동**

`CoroutineCrudRepository`를 사용하면 suspend 함수와 Flow를 그대로 사용할 수 있다.

```kotlin
// Entity
@Table("users")
data class UserEntity(
    @Id val id: Long? = null,
    val name: String,
    val email: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

// Repository — CoroutineCrudRepository 사용
interface UserRepository : CoroutineCrudRepository<UserEntity, Long> {
    suspend fun findByEmail(email: String): UserEntity?
    fun findAllByCreatedAtAfter(date: LocalDateTime): Flow<UserEntity>
}

// Service에서 활용
@Service
class UserService(private val userRepository: UserRepository) {

    suspend fun createUser(request: CreateUserRequest): UserResponse {
        val existing = userRepository.findByEmail(request.email)
        if (existing != null) throw DuplicateEmailException(request.email)

        val saved = userRepository.save(
            UserEntity(name = request.name, email = request.email)
        )
        return saved.toResponse()
    }

    fun getRecentUsers(since: LocalDateTime): Flow<UserResponse> {
        return userRepository.findAllByCreatedAtAfter(since)
            .map { it.toResponse() }
    }
}
```

**트랜잭션 처리**

R2DBC 환경에서의 트랜잭션은 `@Transactional`을 그대로 사용하되, suspend 함수에 적용한다.

```kotlin
@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val inventoryRepository: InventoryRepository
) {
    @Transactional
    suspend fun placeOrder(request: OrderRequest): OrderResponse {
        val inventory = inventoryRepository.findById(request.itemId)
            ?: throw ItemNotFoundException(request.itemId)

        if (inventory.quantity < request.quantity) {
            throw InsufficientStockException()
        }

        val order = orderRepository.save(request.toEntity())
        inventoryRepository.save(inventory.copy(quantity = inventory.quantity - request.quantity))

        return order.toResponse()
    }
}
```

---

## 핵심 정리
- Coroutine은 스레드보다 훨씬 가벼운 비동기 처리 단위로, 수만 개를 동시에 실행할 수 있다
- suspend 함수는 실행을 일시 중단하고 재개할 수 있어 비동기 코드를 순차적으로 작성 가능하다
- Structured Concurrency를 통해 코루틴의 생명주기를 안전하게 관리하고 리소스 누수를 방지한다
- Dispatcher 선택이 성능에 직접적 영향을 미치므로 작업 유형에 맞는 Dispatcher를 사용해야 한다
- Flow는 코루틴 생태계에서 리액티브 스트림을 처리하는 표준 방식이다

## 키워드

| 키워드 | 설명 |
|--------|------|
| `Kotlin Coroutine` | JVM에서 경량 비동기 처리를 가능하게 하는 동시성 프레임워크. 수십만 개의 코루틴을 적은 스레드로 운영할 수 있다. |
| `suspend` | 함수 앞에 붙이는 키워드. 해당 함수는 실행을 일시 중단할 수 있으며, 내부적으로 Continuation Passing Style(CPS)로 컴파일된다. |
| `CoroutineScope` | 코루틴의 생명주기를 관리하는 컨테이너. 스코프가 취소되면 내부 모든 코루틴이 취소된다. |
| `Dispatcher` | 코루틴이 실행될 스레드를 결정하는 구성 요소. `Default`(CPU), `IO`(I/O 작업), `Main`(UI), `Unconfined` 4종류가 있다. |
| `Structured Concurrency` | 코루틴의 생명주기가 명확한 스코프 안에 묶여 있음을 보장하는 원칙. 부모-자식 계층 구조로 안전한 취소와 예외 전파를 가능하게 한다. |
| `Flow` | 비동기적으로 여러 값을 순차적으로 방출하는 Cold Stream. collect 시점에 실행이 시작된다. |
| `async/await` | 결과값을 반환하는 비동기 작업에 사용하는 코루틴 빌더 쌍. `async`는 `Deferred<T>`를 반환하고, `await()`로 결과를 받는다. |
| `launch` | 결과값 없이 Fire-and-forget 방식으로 코루틴을 시작하는 빌더. `Job`을 반환하며 예외 발생 시 즉시 부모로 전파된다. |
| `CoroutineContext` | Job, Dispatcher, CoroutineName 등 코루틴 실행 환경을 정의하는 불변 맵. `+` 연산자로 요소를 조합한다. |
| `SupervisorJob` | 자식 코루틴의 실패가 다른 자식이나 부모로 전파되지 않도록 격리하는 Job. 독립적인 실패 처리가 필요한 서비스 레이어에 적합하다. |

## 참고 자료
- [Kotlin Coroutines 공식 문서](https://kotlinlang.org/docs/coroutines-overview.html)
- [Coroutine context and dispatchers](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html)
- [Coroutine exceptions handling](https://kotlinlang.org/docs/exception-handling.html)
- [StateFlow and SharedFlow | Android Developers](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- [Going Reactive with Spring, Coroutines and Kotlin Flow](https://spring.io/blog/2019/04/12/going-reactive-with-spring-coroutines-and-kotlin-flow/)
- [Non-Blocking Spring Boot with Kotlin Coroutines | Baeldung](https://www.baeldung.com/kotlin/spring-boot-kotlin-coroutines)
