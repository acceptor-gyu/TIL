# Stack, Queue, Deque, PriorityQueue

## 개요
선형 자료구조의 핵심인 Stack, Queue, Deque, PriorityQueue는 데이터의 삽입과 삭제 순서에 따라 구분됩니다. 각 자료구조는 특정 문제 해결에 최적화되어 있으며, 실무에서 광범위하게 사용됩니다.

## 상세 내용

### Stack (스택)

**개념**: LIFO (Last In First Out) - 후입선출
- 가장 나중에 들어간 데이터가 가장 먼저 나옴
- 접시를 쌓는 것과 유사

**주요 연산**:
```java
- push(item): 스택의 맨 위에 요소 추가 - O(1)
- pop(): 스택의 맨 위 요소 제거 및 반환 - O(1)
- peek()/top(): 스택의 맨 위 요소 확인 (제거 X) - O(1)
- isEmpty(): 스택이 비어있는지 확인 - O(1)
- size(): 스택의 크기 반환 - O(1)
```

**구현 방법**:

1. **배열 기반 구현**:
```java
public class ArrayStack<T> {
    private T[] array;
    private int top;
    private static final int DEFAULT_CAPACITY = 10;

    @SuppressWarnings("unchecked")
    public ArrayStack() {
        array = (T[]) new Object[DEFAULT_CAPACITY];
        top = -1;
    }

    public void push(T item) {
        if (top == array.length - 1) {
            resize();  // 배열 크기 확장
        }
        array[++top] = item;
    }

    public T pop() {
        if (isEmpty()) {
            throw new EmptyStackException();
        }
        T item = array[top];
        array[top--] = null;  // 참조 해제
        return item;
    }

    public T peek() {
        if (isEmpty()) {
            throw new EmptyStackException();
        }
        return array[top];
    }

    public boolean isEmpty() {
        return top == -1;
    }

    private void resize() {
        array = Arrays.copyOf(array, array.length * 2);
    }
}
```

**장점**: 캐시 지역성 우수, 메모리 연속성
**단점**: 크기 조절 시 O(n) 비용

2. **연결 리스트 기반 구현**:
```java
public class LinkedStack<T> {
    private class Node {
        T data;
        Node next;

        Node(T data) {
            this.data = data;
        }
    }

    private Node top;
    private int size;

    public void push(T item) {
        Node newNode = new Node(item);
        newNode.next = top;
        top = newNode;
        size++;
    }

    public T pop() {
        if (isEmpty()) {
            throw new EmptyStackException();
        }
        T item = top.data;
        top = top.next;
        size--;
        return item;
    }

    public T peek() {
        if (isEmpty()) {
            throw new EmptyStackException();
        }
        return top.data;
    }

    public boolean isEmpty() {
        return top == null;
    }

    public int size() {
        return size;
    }
}
```

**장점**: 동적 크기, resize 불필요
**단점**: 포인터 오버헤드, 캐시 지역성 낮음

**실사용 예시**:

1. **함수 호출 스택 (Call Stack)**:
```java
public int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}
// factorial(5) 호출 시:
// Stack: [factorial(5), factorial(4), factorial(3), factorial(2), factorial(1)]
// 반환: 1 → 2 → 6 → 24 → 120
```

2. **괄호 매칭**:
```java
public boolean isValid(String s) {
    Stack<Character> stack = new Stack<>();
    Map<Character, Character> map = Map.of(')', '(', '}', '{', ']', '[');

    for (char c : s.toCharArray()) {
        if (map.containsValue(c)) {
            stack.push(c);  // 여는 괄호
        } else {
            if (stack.isEmpty() || stack.pop() != map.get(c)) {
                return false;
            }
        }
    }
    return stack.isEmpty();
}
```

3. **후위 표기법 계산**:
```java
// 중위: 3 + 4 * 2
// 후위: 3 4 2 * +
public int evalRPN(String[] tokens) {
    Stack<Integer> stack = new Stack<>();

    for (String token : tokens) {
        if (isOperator(token)) {
            int b = stack.pop();
            int a = stack.pop();
            stack.push(calculate(a, b, token));
        } else {
            stack.push(Integer.parseInt(token));
        }
    }
    return stack.pop();
}
```

4. **브라우저 방문 기록** (뒤로가기/앞으로가기)
5. **실행 취소 (Undo) 기능**
6. **DFS (깊이 우선 탐색)**

### Queue (큐)

**개념**: FIFO (First In First Out) - 선입선출
- 가장 먼저 들어간 데이터가 가장 먼저 나옴
- 줄 서기와 유사

**주요 연산**:
```java
- enqueue(item)/offer(item): 큐의 맨 뒤에 요소 추가 - O(1)
- dequeue()/poll(): 큐의 맨 앞 요소 제거 및 반환 - O(1)
- peek()/front(): 큐의 맨 앞 요소 확인 (제거 X) - O(1)
- isEmpty(): 큐가 비어있는지 확인 - O(1)
- size(): 큐의 크기 반환 - O(1)
```

**구현 방법**:

1. **원형 배열 (Circular Array)**:
```java
public class CircularQueue<T> {
    private T[] array;
    private int front;
    private int rear;
    private int size;
    private int capacity;

    @SuppressWarnings("unchecked")
    public CircularQueue(int capacity) {
        this.capacity = capacity;
        array = (T[]) new Object[capacity];
        front = 0;
        rear = -1;
        size = 0;
    }

    public void enqueue(T item) {
        if (isFull()) {
            throw new IllegalStateException("Queue is full");
        }
        rear = (rear + 1) % capacity;  // 원형으로 이동
        array[rear] = item;
        size++;
    }

    public T dequeue() {
        if (isEmpty()) {
            throw new NoSuchElementException("Queue is empty");
        }
        T item = array[front];
        array[front] = null;
        front = (front + 1) % capacity;  // 원형으로 이동
        size--;
        return item;
    }

    public T peek() {
        if (isEmpty()) {
            throw new NoSuchElementException("Queue is empty");
        }
        return array[front];
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean isFull() {
        return size == capacity;
    }
}
```

**원형 배열의 필요성**:
- 일반 배열: dequeue 시 앞쪽 공간 낭비
- 원형 배열: 공간 재사용으로 효율적

2. **연결 리스트 기반**:
```java
public class LinkedQueue<T> {
    private class Node {
        T data;
        Node next;

        Node(T data) {
            this.data = data;
        }
    }

    private Node front;
    private Node rear;
    private int size;

    public void enqueue(T item) {
        Node newNode = new Node(item);
        if (isEmpty()) {
            front = rear = newNode;
        } else {
            rear.next = newNode;
            rear = newNode;
        }
        size++;
    }

    public T dequeue() {
        if (isEmpty()) {
            throw new NoSuchElementException("Queue is empty");
        }
        T item = front.data;
        front = front.next;
        if (front == null) {
            rear = null;  // 마지막 요소 제거 시
        }
        size--;
        return item;
    }

    public T peek() {
        if (isEmpty()) {
            throw new NoSuchElementException("Queue is empty");
        }
        return front.data;
    }

    public boolean isEmpty() {
        return front == null;
    }
}
```

**실사용 예시**:

1. **BFS (너비 우선 탐색)**:
```java
public void bfs(Graph graph, int start) {
    Queue<Integer> queue = new LinkedList<>();
    boolean[] visited = new boolean[graph.size()];

    queue.offer(start);
    visited[start] = true;

    while (!queue.isEmpty()) {
        int node = queue.poll();
        System.out.print(node + " ");

        for (int neighbor : graph.neighbors(node)) {
            if (!visited[neighbor]) {
                queue.offer(neighbor);
                visited[neighbor] = true;
            }
        }
    }
}
```

2. **프로세스 스케줄링** (CPU 작업 큐)
3. **프린터 대기열**
4. **메시지 큐** (RabbitMQ, Kafka)
5. **캐시 구현** (LRU Cache)

### Deque (덱, Double-Ended Queue)

**개념**: 양쪽 끝에서 삽입과 삭제가 모두 가능한 자료구조
- Stack과 Queue의 기능을 모두 수행 가능
- 더 유연한 자료구조

**주요 연산**:
```java
- addFirst(item)/offerFirst(item): 앞쪽에 추가 - O(1)
- addLast(item)/offerLast(item): 뒤쪽에 추가 - O(1)
- removeFirst()/pollFirst(): 앞쪽에서 제거 - O(1)
- removeLast()/pollLast(): 뒤쪽에서 제거 - O(1)
- peekFirst()/getFirst(): 앞쪽 요소 확인 - O(1)
- peekLast()/getLast(): 뒤쪽 요소 확인 - O(1)
```

**구현 방법**:

**이중 연결 리스트 (Doubly Linked List)**:
```java
public class Deque<T> {
    private class Node {
        T data;
        Node prev;
        Node next;

        Node(T data) {
            this.data = data;
        }
    }

    private Node front;
    private Node rear;
    private int size;

    public void addFirst(T item) {
        Node newNode = new Node(item);
        if (isEmpty()) {
            front = rear = newNode;
        } else {
            newNode.next = front;
            front.prev = newNode;
            front = newNode;
        }
        size++;
    }

    public void addLast(T item) {
        Node newNode = new Node(item);
        if (isEmpty()) {
            front = rear = newNode;
        } else {
            rear.next = newNode;
            newNode.prev = rear;
            rear = newNode;
        }
        size++;
    }

    public T removeFirst() {
        if (isEmpty()) {
            throw new NoSuchElementException("Deque is empty");
        }
        T item = front.data;
        front = front.next;
        if (front == null) {
            rear = null;
        } else {
            front.prev = null;
        }
        size--;
        return item;
    }

    public T removeLast() {
        if (isEmpty()) {
            throw new NoSuchElementException("Deque is empty");
        }
        T item = rear.data;
        rear = rear.prev;
        if (rear == null) {
            front = null;
        } else {
            rear.next = null;
        }
        size--;
        return item;
    }

    public boolean isEmpty() {
        return front == null;
    }
}
```

**Java의 ArrayDeque**:
- 내부적으로 원형 배열 사용
- Stack/Queue보다 빠름 (동기화 오버헤드 없음)
- null 요소 불가

**실사용 예시**:

1. **슬라이딩 윈도우 최대값**:
```java
public int[] maxSlidingWindow(int[] nums, int k) {
    Deque<Integer> deque = new ArrayDeque<>();  // 인덱스 저장
    int[] result = new int[nums.length - k + 1];

    for (int i = 0; i < nums.length; i++) {
        // 범위 벗어난 인덱스 제거
        if (!deque.isEmpty() && deque.peekFirst() < i - k + 1) {
            deque.pollFirst();
        }

        // 현재 값보다 작은 값들 제거 (의미 없음)
        while (!deque.isEmpty() && nums[deque.peekLast()] < nums[i]) {
            deque.pollLast();
        }

        deque.offerLast(i);

        if (i >= k - 1) {
            result[i - k + 1] = nums[deque.peekFirst()];
        }
    }
    return result;
}
```

2. **회문 검사**:
```java
public boolean isPalindrome(String s) {
    Deque<Character> deque = new ArrayDeque<>();
    for (char c : s.toLowerCase().toCharArray()) {
        if (Character.isLetterOrDigit(c)) {
            deque.offerLast(c);
        }
    }

    while (deque.size() > 1) {
        if (!deque.pollFirst().equals(deque.pollLast())) {
            return false;
        }
    }
    return true;
}
```

3. **작업 스케줄러** (앞/뒤 우선순위 조정)
4. **브라우저 캐시** (LRU 구현)

### PriorityQueue (우선순위 큐)

**개념**: 우선순위가 높은 요소가 먼저 나오는 큐
- 일반 큐와 달리 삽입 순서와 무관
- 내부적으로 힙(Heap) 자료구조 사용

**주요 연산**:
```java
- offer(item)/add(item): 요소 추가 - O(log n)
- poll()/remove(): 최우선 순위 요소 제거 및 반환 - O(log n)
- peek()/element(): 최우선 순위 요소 확인 - O(1)
- isEmpty(): 비어있는지 확인 - O(1)
- size(): 크기 반환 - O(1)
```

**구현: 이진 힙 (Binary Heap)**:

**최소 힙 (Min Heap)**:
```java
public class MinHeap<T extends Comparable<T>> {
    private List<T> heap;

    public MinHeap() {
        heap = new ArrayList<>();
    }

    public void offer(T item) {
        heap.add(item);
        siftUp(heap.size() - 1);
    }

    public T poll() {
        if (isEmpty()) {
            throw new NoSuchElementException("Heap is empty");
        }
        T min = heap.get(0);
        T last = heap.remove(heap.size() - 1);

        if (!isEmpty()) {
            heap.set(0, last);
            siftDown(0);
        }
        return min;
    }

    public T peek() {
        if (isEmpty()) {
            throw new NoSuchElementException("Heap is empty");
        }
        return heap.get(0);
    }

    private void siftUp(int index) {
        T item = heap.get(index);

        while (index > 0) {
            int parentIndex = (index - 1) / 2;
            T parent = heap.get(parentIndex);

            if (item.compareTo(parent) >= 0) {
                break;
            }

            heap.set(index, parent);
            index = parentIndex;
        }
        heap.set(index, item);
    }

    private void siftDown(int index) {
        T item = heap.get(index);
        int size = heap.size();
        int half = size / 2;

        while (index < half) {
            int childIndex = 2 * index + 1;
            T child = heap.get(childIndex);
            int rightIndex = childIndex + 1;

            // 오른쪽 자식이 더 작으면 선택
            if (rightIndex < size && heap.get(rightIndex).compareTo(child) < 0) {
                childIndex = rightIndex;
                child = heap.get(childIndex);
            }

            if (item.compareTo(child) <= 0) {
                break;
            }

            heap.set(index, child);
            index = childIndex;
        }
        heap.set(index, item);
    }

    public boolean isEmpty() {
        return heap.isEmpty();
    }

    public int size() {
        return heap.size();
    }
}
```

**힙의 특성**:
- **완전 이진 트리 (Complete Binary Tree)**
- **힙 속성 (Heap Property)**:
  - 최소 힙: 부모 ≤ 자식
  - 최대 힙: 부모 ≥ 자식
- **배열로 구현**:
  - 부모 인덱스: `(i - 1) / 2`
  - 왼쪽 자식: `2 * i + 1`
  - 오른쪽 자식: `2 * i + 2`

**Java PriorityQueue 사용**:
```java
// 최소 힙 (기본)
PriorityQueue<Integer> minHeap = new PriorityQueue<>();

// 최대 힙
PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());

// 커스텀 비교자
PriorityQueue<Task> taskQueue = new PriorityQueue<>(
    (a, b) -> a.priority - b.priority
);
```

**실사용 예시**:

1. **다익스트라 알고리즘** (최단 경로):
```java
public int[] dijkstra(int[][] graph, int start) {
    int n = graph.length;
    int[] dist = new int[n];
    Arrays.fill(dist, Integer.MAX_VALUE);
    dist[start] = 0;

    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] - b[1]);
    pq.offer(new int[]{start, 0});  // [노드, 거리]

    while (!pq.isEmpty()) {
        int[] current = pq.poll();
        int node = current[0];
        int distance = current[1];

        if (distance > dist[node]) continue;

        for (int neighbor = 0; neighbor < n; neighbor++) {
            if (graph[node][neighbor] > 0) {
                int newDist = distance + graph[node][neighbor];
                if (newDist < dist[neighbor]) {
                    dist[neighbor] = newDist;
                    pq.offer(new int[]{neighbor, newDist});
                }
            }
        }
    }
    return dist;
}
```

2. **K번째 큰/작은 요소 찾기**:
```java
public int findKthLargest(int[] nums, int k) {
    PriorityQueue<Integer> minHeap = new PriorityQueue<>();

    for (int num : nums) {
        minHeap.offer(num);
        if (minHeap.size() > k) {
            minHeap.poll();  // 가장 작은 요소 제거
        }
    }
    return minHeap.peek();  // k번째로 큰 요소
}
```

3. **작업 스케줄링** (우선순위 기반):
```java
class Task {
    String name;
    int priority;
    long deadline;

    Task(String name, int priority, long deadline) {
        this.name = name;
        this.priority = priority;
        this.deadline = deadline;
    }
}

PriorityQueue<Task> scheduler = new PriorityQueue<>(
    Comparator.comparingInt((Task t) -> t.priority)
              .thenComparingLong(t -> t.deadline)
);
```

4. **실시간 중앙값 찾기**:
```java
class MedianFinder {
    private PriorityQueue<Integer> maxHeap;  // 작은 절반
    private PriorityQueue<Integer> minHeap;  // 큰 절반

    public MedianFinder() {
        maxHeap = new PriorityQueue<>(Collections.reverseOrder());
        minHeap = new PriorityQueue<>();
    }

    public void addNum(int num) {
        maxHeap.offer(num);
        minHeap.offer(maxHeap.poll());

        if (maxHeap.size() < minHeap.size()) {
            maxHeap.offer(minHeap.poll());
        }
    }

    public double findMedian() {
        if (maxHeap.size() > minHeap.size()) {
            return maxHeap.peek();
        }
        return (maxHeap.peek() + minHeap.peek()) / 2.0;
    }
}
```

5. **이벤트 시뮬레이션**
6. **허프만 코딩**
7. **프림 알고리즘** (최소 신장 트리)

## 비교 및 선택 가이드

| 자료구조 | 삽입 | 삭제 | 조회 | 사용 사례 |
|---------|------|------|------|----------|
| **Stack** | O(1) | O(1) | O(1) | 함수 호출, 괄호 검사, DFS, 실행 취소 |
| **Queue** | O(1) | O(1) | O(1) | BFS, 작업 대기열, 프로세스 스케줄링 |
| **Deque** | O(1) | O(1) | O(1) | 슬라이딩 윈도우, 회문, LRU 캐시 |
| **PriorityQueue** | O(log n) | O(log n) | O(1) | 다익스트라, K번째 요소, 작업 우선순위 |

**선택 기준**:
- **LIFO 필요**: Stack
- **FIFO 필요**: Queue
- **양방향 접근**: Deque
- **우선순위 기반**: PriorityQueue

**Java 구현체 선택**:
```java
// Stack: ArrayDeque 사용 (Stack 클래스는 deprecated)
Deque<Integer> stack = new ArrayDeque<>();

// Queue: LinkedList 또는 ArrayDeque
Queue<Integer> queue = new LinkedList<>();
Queue<Integer> queue2 = new ArrayDeque<>();  // 더 빠름

// Deque: ArrayDeque
Deque<Integer> deque = new ArrayDeque<>();

// PriorityQueue: PriorityQueue
PriorityQueue<Integer> pq = new PriorityQueue<>();
```

## 핵심 정리

### Stack (스택)
- **LIFO**: 가장 나중에 들어간 것이 먼저 나옴
- **주요 연산**: push, pop, peek - 모두 O(1)
- **사용**: 함수 호출 스택, 괄호 매칭, DFS, 후위 표기법, Undo 기능
- **구현**: 배열 또는 연결 리스트
- **Java**: ArrayDeque 사용 권장

### Queue (큐)
- **FIFO**: 가장 먼저 들어간 것이 먼저 나옴
- **주요 연산**: enqueue, dequeue, peek - 모두 O(1)
- **사용**: BFS, 프로세스 스케줄링, 프린터 대기열, 메시지 큐
- **구현**: 원형 배열 또는 연결 리스트
- **Java**: LinkedList 또는 ArrayDeque

### Deque (덱)
- **양방향**: 앞/뒤 모두에서 삽입/삭제 가능
- **주요 연산**: addFirst/Last, removeFirst/Last - 모두 O(1)
- **사용**: 슬라이딩 윈도우, 회문 검사, Stack/Queue 역할 모두 가능
- **구현**: 이중 연결 리스트
- **Java**: ArrayDeque (가장 빠름)

### PriorityQueue (우선순위 큐)
- **우선순위 기반**: 높은 우선순위가 먼저 나옴
- **주요 연산**: offer O(log n), poll O(log n), peek O(1)
- **사용**: 다익스트라, K번째 요소, 작업 스케줄링, 허프만 코딩
- **구현**: 이진 힙 (완전 이진 트리)
- **Java**: PriorityQueue (최소 힙 기본)

## 키워드

### 1. LIFO (Last In First Out)
Stack의 핵심 원리로, 가장 나중에 삽입된 요소가 가장 먼저 제거됩니다. 접시를 쌓고 꺼내는 것과 유사하며, 함수 호출 스택이 대표적인 예시입니다.

### 2. FIFO (First In First Out)
Queue의 핵심 원리로, 가장 먼저 삽입된 요소가 가장 먼저 제거됩니다. 줄 서기와 동일한 개념으로, 공정한 처리 순서를 보장합니다.

### 3. 원형 배열 (Circular Array)
Queue 구현에서 배열의 끝에 도달하면 다시 처음으로 돌아가는 방식입니다. 모듈로 연산 `(index + 1) % capacity`를 사용하여 공간을 효율적으로 재사용합니다.

### 4. 이중 연결 리스트 (Doubly Linked List)
각 노드가 이전 노드(prev)와 다음 노드(next) 포인터를 모두 가지는 구조입니다. Deque 구현의 핵심으로, 양방향 탐색과 삽입/삭제를 O(1)에 수행합니다.

### 5. 힙 (Heap)
완전 이진 트리 기반의 자료구조로, 부모-자식 간 대소 관계를 유지합니다. 최소 힙은 부모 ≤ 자식, 최대 힙은 부모 ≥ 자식 조건을 만족하며, 배열로 효율적으로 구현됩니다.

### 6. Sift-Up / Sift-Down
힙에서 요소를 추가(sift-up) 또는 제거(sift-down) 시 힙 속성을 유지하기 위해 요소를 이동시키는 연산입니다. 시간 복잡도는 O(log n)입니다.

### 7. Amortized O(1)
동적 배열의 resize처럼 최악의 경우 O(n)이지만, 평균적으로는 O(1)의 성능을 보이는 경우를 의미합니다. Stack의 push 연산이 대표적입니다.

### 8. 완전 이진 트리 (Complete Binary Tree)
마지막 레벨을 제외한 모든 레벨이 완전히 채워져 있고, 마지막 레벨은 왼쪽부터 채워진 이진 트리입니다. 힙의 구조적 기반이며, 배열로 효율적 표현이 가능합니다.

### 9. Comparator
Java에서 객체의 정렬 기준을 정의하는 인터페이스입니다. PriorityQueue에서 커스텀 우선순위를 지정할 때 사용하며, 람다식으로 간결하게 작성 가능합니다.

### 10. 슬라이딩 윈도우 (Sliding Window)
고정 크기의 윈도우를 배열 위에서 이동시키며 최대/최소값을 찾는 기법입니다. Deque를 사용하면 O(n) 시간에 해결 가능하며, 단순 구현 대비 O(nk) → O(n) 개선 효과가 있습니다.

## 참고 자료
- [Oracle Java Documentation - Collections Framework](https://docs.oracle.com/javase/8/docs/technotes/guides/collections/overview.html)
- [Java API - Deque Interface](https://docs.oracle.com/javase/8/docs/api/java/util/Deque.html)
- [Java API - PriorityQueue Class](https://docs.oracle.com/javase/8/docs/api/java/util/PriorityQueue.html)
- [Introduction to Algorithms (CLRS)](https://mitpress.mit.edu/9780262046305/introduction-to-algorithms/)
- [GeeksforGeeks - Data Structures](https://www.geeksforgeeks.org/data-structures/)
