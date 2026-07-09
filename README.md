# TIL
학습 기록

## TIL 작성 자동화

### 스킬 호출 방법

```
/add-til-template [카테고리] [제목]
```

**예시:**
```
/add-til-template Docker BuildKit 캐시 마운트와 빌드 시간 단축
/add-til-template Database Redis 캐싱 전략
/add-til-template Network TCP 혼잡 제어
```

- 카테고리가 없으면 자동으로 폴더와 README 섹션을 생성합니다.
- 카테고리를 생략하면 제목 전체를 기반으로 카테고리를 추론합니다.

### 동작 과정

```
/add-til-template 호출
        │
        ▼
┌─────────────────────────────────────────────┐
│  1단계: add-til-template 서브에이전트 (Opus)    │
│                                             │
│  • 오늘 날짜와 파일 번호 결정                     │
│    (같은 날짜 기존 파일 확인 후 번호 부여)          │
│  • 카테고리 폴더 확인 / 신규 생성                 │
│  • YYMMDD_번호_제목.md 파일 생성                │
│  • 제목 및 목차 작성                           │
│  • 키워드 5~10개 선정                          │
│  • Stop Hook → 생성된 파일명을                  │
│    /tmp/til-created-file.txt 에 저장          │
└─────────────────────────────────────────────┘
        │  생성된 파일명 전달
        ▼
┌─────────────────────────────────────────────┐
│  2단계: task-til 서브에이전트 (Sonnet)          │
│                                             │
│  • 생성된 템플릿 파일 읽기                       │
│  • 공식 문서 웹 검색으로 최신 정보 수집             │
│  • 목차에 맞는 본문 내용 작성                     │
│  • 선정된 키워드 설명 추가                       │
│  • README.md 해당 카테고리에 항목 추가            │
│    (날짜 최신순, 같은 날은 번호 내림차순)           │
└─────────────────────────────────────────────┘
        │
        ▼
     완료 보고
```

### 파일명 규칙

```
카테고리/YYMMDD_번호_제목.md

예시: Docker/260401_01_BuildKit_캐시_마운트와_빌드_시간_단축.md
```

---

## AI
- [Claude Code Dreaming](./AI/260510_01_Claude_Code_Dreaming.md) - 2026.05.10
- [hermes agent](./AI/260508_01_hermes_agent.md) - 2026.05.08
- [Andrej Karpathy Skills의 동작 원리와 코딩 성능이 향상되는 이유 분석](./AI/260427_02_Andrej_Karpathy_Skills의_동작_원리와_코딩_성능이_향상되는_이유_분석.md) - 2026.04.27
- [Claude Code - gstack, superpowers, GSD](./AI/260427_01_Claude_Code_-_gstack_superpowers_GSD.md) - 2026.04.27
- [Multi-Agent와 Subagent의 차이](./AI/260421_01_Multi-Agent와_Subagent의_차이.md) - 2026.04.21
- [AI Harness Design Sample](./AI/260419_02_AI_Harness_Design_Sample.md) - 2026.04.19
- [Anthropic Multi-Agent 3-Step Harness Design](./AI/260419_01_Anthropic_Multi-Agent_3-Step_Harness_Design.md) - 2026.04.19
- [NLP(자연어 처리) 기초](./AI/260418_01_NLP_자연어_처리_기초.md) - 2026.04.18
- [하네스 엔지니어링 (Harness Engineering)](./AI/260414_01_Harness_Engineering.md) - 2026.04.14
- [pi extension으로 개인 CLI Tool 만들기](./AI/260407_01_pi_extension으로_개인_CLI_Tool_만들기.md) - 2026.04.07
- [Pi Coding Agents](./AI/260406_03_Pi_Coding_Agents.md) - 2026.04.06
- [하네스 아키텍처 (Harness Architecture)](./AI/260406_02_하네스_아키텍처.md) - 2026.04.06
- [Gmail MCP 서버와 Claude Code 스케줄 태스크 연동 자동화](./AI/260406_01_Gmail_MCP_서버와_Claude_Code_스케줄_태스크_연동_자동화.md) - 2026.04.06
- [AI 기술을 활용한 새로운 시도와 문제 해결 사례](./AI/260404_01_AI_기술을_활용한_새로운_시도와_문제_해결_사례.md) - 2026.04.04

## AI-AGENT
- [AI Agent Team](./AI-AGENT/260218_02_AI_Agent_Team.md) - 2026.02.18
- [컨텍스트 엔지니어링](./AI-AGENT/260218_01_컨텍스트_엔지니어링.md) - 2026.02.18

## Alaram to Client
- [Web Push Notification in Server](./Alaram%20to%20Client/260201_01_web_push_notification_in_server.md) - 2026.02.01

## ALGORITHM
- [Overlap 알고리즘](./ALGORITHM/260302_01_overlap_알고리즘.md) - 2026.03.02

## API-DESIGN
- [Spring Boot OSIV와 Projection/DTO 패턴](./API-DESIGN/260217_03_Spring_Boot_OSIV와_Projection_DTO_패턴.md) - 2026.02.17
- [Controller에서 Entity를 직접 반환하는 것과 DTO를 사용하는 것](./API-DESIGN/260217_02_Controller에서_Entity를_직접_반환하는_것과_DTO를_사용하는_것.md) - 2026.02.17

## ARCHITECTURE
- [헥사고날 아키텍처](./ARCHITECTURE/260615_01_헥사고날_아키텍처.md) - 2026.06.15

## Claude
- [Claude Code 메인 세션과 서브 에이전트 세션의 차이](CLAUDE/260331_02_Claude_Code_메인_세션과_서브_에이전트_세션의_차이.md) - 2026.03.31
- [Claude Code를 잘 활용하기 위해 알아야 하는 상식](CLAUDE/260331_01_Claude_Code를_잘_활용하기_위해_알아야_하는_상식.md) - 2026.03.31
- [CLAUDE 서브 에이전트를 활용한 자동화 파이프라인](CLAUDE/260330_01_CLAUDE_서브_에이전트를_활용한_자동화_파이프라인.md) - 2026.03.30
- [Claude 토큰 최적화 활용 방법](CLAUDE/260216_03_토큰_최적화_활용_방법.md) - 2026.02.16
- [Claude Skills, SubAgent, AGENT.md의 차이](CLAUDE/260204_03_Claude_Skills_SubAgent_AGENT_md_차이.md) - 2026.02.04
- [Claude Skills 활용 전략](CLAUDE/260129_01_Claude_Skills_활용_전략.md) - 2026.01.29

## Concurrency
- [Worker Pool 패턴](./CONCURRENCY/260410_01_Worker_Pool_패턴.md) - 2026.04.10
- [Java CountDownLatch](CONCURRENCY/260223_02_Java_CountDownLatch.md) - 2026.02.23

## DATA-STRUCTURE
- [Set 자료구조에서 get() 메서드의 시간 복잡도가 O(1)이 되는 원리](./DATA-STRUCTURE/260309_03_Set_자료구조에서_get_메서드의_시간_복잡도가_O1이_되는_원리.md) - 2026.03.09
- [Stack, Queue, Deque, PriorityQueue](./DATA-STRUCTURE/260213_03_Stack_Queue_Deque_PriorityQueue.md) - 2026.02.13

## DEVLOG
- [파티셔닝을 실제 운영중인 서비스에 적용할 때 필요한 전략](./DEVLOG/260328_01_파티셔닝을_실제_운영중인_서비스에_적용할_때_필요한_전략.md) - 2026.03.28
- [제한 시간 게임 중도 종료 유저 데이터 관리](./DEVLOG/260327_01_제한_시간_게임_중도_종료_유저_데이터_관리.md) - 2026.03.27
- [대규모 트래픽 환경을 운영하는 e-commerce 시스템에서 Elasticsearch 활용 전략](./DEVLOG/260324_02_E-commerce_시스템에서_Elasticsearch_활용_전략.md) - 2026.03.24

## Database
- [MySQL MyISAM vs InnoDB Engine](./DATABASE/260622_01_MySQL_MyISAM_vs_InnoDB_Engine.md) - 2026.06.22
- [Elasticsearch를 활용해 검색 엔진 품질 높이기](RDBMS/260402_01_Elasticsearch를_활용해_검색_엔진_품질_높이기.md) - 2026.04.02
- [gh-ost (GitHub Online Schema Transmogrifier)](RDBMS/260328_01_gh-ost.md) - 2026.03.28
- [MySQL ReentrantLock](./DATABASE/260319_01_MySQL_ReentrantLock.md) - 2026.03.19
- [MySQL 핵심 로그와 활용도](./DATABASE/260314_02_MySQL_핵심_로그와_활용도.md) - 2026.03.14
- [트랜잭션 ACID](./DATABASE/260314_01_트랜잭션_ACID.md) - 2026.03.14
- [MySQL I/O 종류 (Random Access vs Sequential Access)](./DATABASE/260312_01_MySQL_IO_종류_Random_Access_vs_Sequential_Access.md) - 2026.03.12
- [MySQL scan 종류와 특징](./DATABASE/260311_03_MySQL_scan_종류와_특징.md) - 2026.03.11
- [MySQL Optimizer Condition Pushdown](./DATABASE/260311_02_MySQL_Optimizer_Condition_Pushdown.md) - 2026.03.11
- [ALTER TABLE column type 변경 시 RDBMS의 전략](RDBMS/260311_01_ALTER_TABLE_column_type_변경_시_RDBMS의_전략.md) - 2026.03.11
- [Transaction Isolation Level - READ COMMITTED와 REQUIRES_NEW의 관계](RDBMS/260226_01_Transaction_Isolation_Level_READ_COMMITTED와_REQUIRES_NEW의_관계.md) - 2026.02.26
- [RDBMS, binlog, Elasticsearch 함께 활용해서 성능 좋은 DB 역할하기](RDBMS/260225_02_RDBMS_binlog_Elasticsearch_활용.md) - 2026.02.25
- [MySQL 2대 구성 시 Read Replica 데이터 정합성](RDBMS/260225_01_MySQL_Replication_데이터_정합성.md) - 2026.02.25
- [2-Phase Locking (2PL)](RDBMS/260223_01_2_Phase_Locking.md) - 2026.02.23
- [RDBMS(MySQL) index와 B+Tree](RDBMS/260220_04_RDBMS_MySQL_index와_B+Tree.md) - 2026.02.20
- [RDBMS(MySQL) MVCC](RDBMS/260220_03_RDBMS_MySQL_MVCC.md) - 2026.02.20
- [RDBMS(MySQL) 트랜잭션 격리 수준](RDBMS/260220_02_RDBMS_MySQL_트랜잭션_격리_수준.md) - 2026.02.20
- [RDBMS(MySQL) Lock 종류](RDBMS/260220_01_RDBMS_MySQL_Lock_종류.md) - 2026.02.20
- [비관적 락과 낙관적 락](RDBMS/260216_01_비관적_락과_낙관적_락.md) - 2026.02.16
- [Connection Pool](RDBMS/260212_01_Connection_Pool.md) - 2026.02.12
- [데이터베이스 삭제 메커니즘 DELETE와 VACUUM (Tombstone)](RDBMS/260127_01_RDBMS_DELETE.md) - 2026.01.27
- [MySQL Purge DEEP DIVE](RDBMS/260127_02_MYSQL_PURGE.md) - 2026.01.27

## Docker
- [Dockerfile의 JVM 메모리 옵션](./DOCKER/260529_02_Dockerfile의_JVM_메모리_옵션.md) - 2026.05.29
- [BuildKit 캐시 마운트와 빌드 시간 단축](DOCKER/260401_01_BuildKit_캐시_마운트와_빌드_시간_단축.md) - 2026.04.01

## INFRA
- [Terraform은 무엇이고 어떻게 활용하는가](./INFRA/260417_01_Terraform은_무엇이고_어떻게_활용하는가.md) - 2026.04.17

## INTERVIEW
- [사용자가 웹사이트에 처음 접근했을 때 발생하는 일련의 과정](./INTERVIEW/260213_01_사용자가_웹사이트에_처음_접근했을_때_발생하는_일련의_과정.md) - 2026.02.13

## Kotlin
- [Coroutine — 비동기 처리의 핵심](./Kotlin/260326_01_Coroutine_비동기_처리의_핵심.md) - 2026.03.26

## Java
- [Record를 DTO로 사용하3는 이유는 무엇인가](./Java/260307_02_Record를_DTO로_사용하는_이유는_무엇인가.md) - 2026.03.07

## JVM
- [GC 상황을 확인하는 jstat](./JVM/260614_01_GC_상황을_확인하는_jstat.md) - 2026.06.14
- [자바 인스턴스 확인을 위한 jps](./JVM/260612_01_자바_인스턴스_확인을_위한_jps.md) - 2026.06.12
- [강제로 GC 시키기](./JVM/260611_04_강제로_GC_시키기.md) - 2026.06.11
- [5가지 GC 방식](./JVM/260611_03_5가지_GC_방식.md) - 2026.06.11
- [GC 알고리즘 vs GC 선택: 장단점 비교](./JVM/260611_02_GC_알고리즘_vs_GC_선택_장단점_비교.md) - 2026.06.11
- [GC의 원리](./JVM/260611_01_GC의_원리.md) - 2026.06.11
- [자바의 Runtime Data Area 구성](./JVM/260609_02_자바의_Runtime_Data_Area_구성.md) - 2026.06.09
- [GC란](./JVM/260609_01_GC란.md) - 2026.06.09
- [GC는 언제 발생할까](./JVM/260608_01_GC는_언제_발생할까.md) - 2026.06.08
- [JVM 예외는 JVM에서 어떻게 처리될까](./JVM/260607_02_JVM_예외는_JVM에서_어떻게_처리될까.md) - 2026.06.07
- [클래스 로딩 절차](./JVM/260607_01_클래스_로딩_절차.md) - 2026.06.07
- [JVM이 종료될 때의 절차](./JVM/260605_02_JVM이_종료될_때의_절차.md) - 2026.06.05
- [JVM이 시작할 때의 절차](./JVM/260605_01_JVM이_시작할_때의_절차.md) - 2026.06.05
- [IBM JVM의 JIT 컴파일 및 최적화 절차](./JVM/260604_03_IBM_JVM의_JIT_컴파일_및_최적화_절차.md) - 2026.06.04
- [JRockit의 JIT 컴파일 및 최적화 절차](./JVM/260604_02_JRockit의_JIT_컴파일_및_최적화_절차.md) - 2026.06.04
- [JIT Optimizer라는 게 도대체 뭘까](./JVM/260604_01_JIT_Optimizer라는_게_도대체_뭘까.md) - 2026.06.04
- [HotSpot VM은 어떻게 구성되어 있을까?](./JVM/260603_02_HotSpot_VM은_어떻게_구성되어_있을까.md) - 2026.06.03
- [JVM은 도대체 어떻게 구동될까?](./JVM/260603_01_JVM은_도대체_어떻게_구동될까.md) - 2026.06.03
- [JVM 기반 애플리케이션의 기동 성능 최적화 전략](./JVM/260601_02_JVM_기반_애플리케이션의_기동_성능_최적화_전략.md) - 2026.06.01
- [CDS, CRaC, GC](./JVM/260529_03_CDS_CRaC_GC.md) - 2026.05.29

## MONITORING
- [Datadog 활용하기](MONITORING/260313_01_Datadog_활용하기.md) - 2026.03.13

## NLP
- [BERT (Bidirectional Encoder Representations from Transformers)](./NLP/260427_03_BERT.md) - 2026.04.27
- [TF-IDF + LogisticRegression 파이프라인](./NLP/260425_01_TF-IDF_+_LogisticRegression_파이프라인.md) - 2026.04.25

## Network
- [TCP 길이 프리픽스 프로토콜](./Network/260320_01_TCP_길이_프리픽스_프로토콜.md) - 2026.03.20
- [OSI 7 Layer](./Network/260302_02_OSI_7_Layer.md) - 2026.03.02
- [handshake 종류와 설명](./Network/260212_03_handshake_종류와_설명.md) - 2026.02.12
- [TCP, UDP, HTTP](./Network/260212_02_TCP_UDP_HTTP.md) - 2026.02.12
- [네트워크에서 활용되는 Exponential Backoff](./Network/260127_03_EXPONENTIAL_BACKOFF_ON_NETWORK.md) - 2026.01.27

## Network-Security
- [세션과 쿠키 방식의 차이 이해하기](./Network-Security/260421_01_세션과_쿠키_방식의_차이_이해하기.md) - 2026.04.21
- [SSL, TLS](./Network-Security/260213_02_SSL_TLS.md) - 2026.02.13

## OOP
- [빌더 패턴 (with Lombok @Builder)](./OOP/260306_01_빌더_패턴_with_Lombok_Builder.md) - 2026.03.06
- [SOLID 원칙이란](./OOP/260305_01_SOLID_원칙이란.md) - 2026.03.05

## OperationSystem
- [스레드, 프로세스, 코어](OPERATING-SYSTEM/260211_03_스레드_프로세스_코어.md) - 2026.02.11
- [운영체제란](./OPERATING-SYSTEM/260309_01_운영체제란.md) - 2026.03.09

## Performance-Comparison
- [JMH와 async-profiler](PERFORMANCE-COMPARISON/260529_01_JMH와_async-profiler.md) - 2026.05.29
- [Google SRE SLO 설계법](PERFORMANCE-COMPARISON/260526_01_Google_SRE_SLO_설계법.md) - 2026.05.26
- [N번 JOIN 단일 API vs 단순 SELECT N번 API 효율성 비교](PERFORMANCE-COMPARISON/260219_01_N번_JOIN_단일_API_vs_단순_SELECT_N번_API_효율성_비교.md) - 2026.02.19

## PYTHON
- [FastAPI](./PYTHON/260629_01_FAST_API.md) - 2026.06.29

## Spring-Boot
- [@ComponentScan을 사용하는 이유](SPRING-BOOT/260707_01_@ComponentScan을_사용하는_이유.md) - 2026.07.07
- [@Transactional 동작 원리 (feat. 프록시)](SPRING-BOOT/260622_01_@Transactional_동작_원리_feat_프록시.md) - 2026.06.22
- [@ComponentScan의 한계와 @AutoConfiguration](SPRING-BOOT/260621_04_@ComponentScan의_한계와_@AutoConfiguration.md) - 2026.06.21
- [@Transactional propagation 옵션과 보상 트랜잭션 구체적인 상황 예시](SPRING-BOOT/260621_03_@Transactional_propagation_옵션과_보상_트랜잭션_구체적인_상황_예시.md) - 2026.06.21
- [@Transactional propagation 옵션과 CGLIB 프록시](SPRING-BOOT/260621_02_@Transactional_propagation_옵션과_CGLIB_프록시.md) - 2026.06.21
- [AOT Cache와 JVM CDS](SPRING-BOOT/260621_01_AOT_Cache와_JVM_CDS.md) - 2026.06.21
- [CGLIB 프록시와 @Configuration](SPRING-BOOT/260619_03_CGLIB_프록시와_@Configuration.md) - 2026.06.19
- [CGLIB 프록시](SPRING-BOOT/260619_02_CGLIB_프록시.md) - 2026.06.19
- [Liquibase](SPRING-BOOT/260619_01_Liquibase.md) - 2026.06.19
- [REQUIRES_NEW를 사용해야 하는 상황과 사용할 때 주의할 점](SPRING-BOOT/260227_01_REQUIRES_NEW를_사용해야_하는_상황과_주의할_점.md) - 2026.02.27
- [REQUIRES_NEW를 사용할 때 getBean()을 사용해야 하는 이유](SPRING-BOOT/260226_02_REQUIRES_NEW를_사용할_때_getBean을_사용해야_하는_이유.md) - 2026.02.26

## SpringDataJPA
- [N+1 문제와 해결방법](SPRING-DATA-JPA/260216_04_N+1_문제와_해결방법.md) - 2026.02.16
- [Spring Data JPA 저장 방식 - save(), saveAll(), 배치 등](SPRING-DATA-JPA/260204_02_Spring_Data_JPA_저장_방식.md) - 2026.02.04
- [Spring Data JPA 활용](SPRING-DATA-JPA/260204_01_Spring_Data_JPA_활용.md) - 2026.02.04

## SpringTest
- [Spring Layered Architecture Test](SPRING-TEST/260203_01_Spring_Layered_Architecture_Test.md) - 2025.02.03

## ALL-IN-TAB
- [Vercel과 Vercel로 할 수 있는 모든 것](ALL-IN-TAB/260521_02_Vercel과_Vercel로_할_수_있는_모든_것.md) - 2026.05.21
- [PG 연동을 위한 시스템 설계 및 개발](ALL-IN-TAB/260521_01_PG_연동을_위한_시스템_설계_및_개발.md) - 2026.05.21

## System-Architecture
- [따닥 방지 전략과 적절한 케이스 사례](SYSTEM-ARCHITECTURE/260502_01_따닥_방지_전략과_적절한_케이스_사례.md) - 2026.05.02
- [Layered Architecture의 장점과 도입 이유](SYSTEM-ARCHITECTURE/260420_01_Layered_Architecture의_장점과_도입_이유.md) - 2026.04.20
- [SQS와 Kafka의 차이](SYSTEM-ARCHITECTURE/260324_01_SQS와_Kafka의_차이.md) - 2026.03.24
- [Datadog 활용하기](MONITORING/260313_01_Datadog_활용하기.md) - 2026.03.13
- [로드밸런싱이란](SYSTEM-ARCHITECTURE/260309_02_로드밸런싱이란.md) - 2026.03.09
- [DB Replication](SYSTEM-ARCHITECTURE/260307_01_DB_Replication.md) - 2026.03.07
- [다중 서버 환경에서 사용자 인증 - Session vs Token](SYSTEM-ARCHITECTURE/260306_03_다중_서버_환경에서_사용자_인증_Session_vs_Token.md) - 2026.03.06
- [수강신청 시스템 동시성 처리 @Transactional isolation read committed](SYSTEM-ARCHITECTURE/260306_02_수강신청_시스템_동시성_처리_Transactional_isolation_read_committed.md) - 2026.03.06
- [트래픽 급증 대응 아키텍처 설계 지침](SYSTEM-ARCHITECTURE/260218_03_트래픽_급증_대응_아키텍처_설계_지침.md) - 2026.02.18
- [수강신청 시스템에서 초당 10,000건의 요청 처리 아키텍처](SYSTEM-ARCHITECTURE/260216_02_수강신청_시스템_초당_10000건_요청_처리_아키텍처.md) - 2026.02.16

## TestCode
- [동시성 테스트](TESTCODE/260211_02_동시성_테스트.md) - 2026.02.11
- [단위테스트와 통합 테스트의 차이점은 무엇인가요?](TESTCODE/260211_01_단위테스트와_통합_테스트의_차이점.md) - 2026.02.11

## trade-off
- [모든 Entity는 불변 객체여야 할까? JPA에서는 어떨까?](./trade-off/260217_01_모든_Entity는_불변_객체여야_할까_JPA에서는_어떨까.md) - 2026.02.17

## UX
- [기존 서비스에 다국어 제공](./UX/260317_01_기존_서비스에_다국어_제공.md) - 2026.03.17

## VECTOR-DATABASE
- [벡터 데이터베이스 종류와 활용](./VECTOR-DATABASE/260417_01_벡터_데이터베이스_종류와_활용.md) - 2026.04.17

## REAL-TIME-MESSAGING
- [멱등성 개념과 메시지 순서 역전 문제와 해결법](./REAL-TIME-MESSAGING/260505_03_멱등성_개념과_메시지_순서_역전_문제와_해결법.md) - 2026.05.05
- [SSE(Server-Sent Events)와 WebSocket의 차이](./REAL-TIME-MESSAGING/260505_02_SSE_Server_Sent_Events와_WebSocket의_차이.md) - 2026.05.05
- [WebSocket 동작 원리](./REAL-TIME-MESSAGING/260505_01_WebSocket_동작_원리.md) - 2026.05.05

## TroubleShooting
- [실행 시간이 1분 이상인 쿼리와 DB Connection과의 관계](./TroubleShooting/260216_05_실행_시간이_1분_이상인_쿼리와_DB_connection과의_관계.md) - 2026.02.16
