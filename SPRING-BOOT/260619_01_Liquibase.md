# Liquibase

## 개요

Liquibase는 데이터베이스 스키마 변경(마이그레이션)을 코드로 관리하는 오픈소스 도구다. Git으로 애플리케이션 코드의 변경 이력을 추적하듯이, Liquibase는 DB 스키마의 변경 이력을 changelog 파일에 기록하고 추적한다. Spring Boot와의 통합이 공식 지원되며, 애플리케이션 기동 시 자동으로 마이그레이션을 실행한다.

## 상세 내용

### 1. Liquibase란

데이터베이스 스키마를 팀 단위로 협업하거나 여러 환경(dev, staging, prod)에서 운영할 때 스키마 변경 이력을 추적하지 않으면 다음 문제가 발생한다.

- 어떤 스키마 변경이 어떤 환경에 적용됐는지 알 수 없음
- 배포 시 수동으로 DDL을 실행해야 하며 실수 발생 가능
- 롤백 방법이 문서화되지 않음

Liquibase는 모든 스키마 변경을 **changelog 파일**에 기록하고, 각 변경 단위인 **changeset**을 기준으로 어떤 변경이 적용됐는지를 DB 내 테이블로 추적한다. 이를 통해 Git처럼 스키마 변경 이력을 버전 관리할 수 있다.

---

### 2. 핵심 개념

#### ChangeLog

모든 데이터베이스 변경 이력을 담는 마스터 파일이다. Changelog 내에 여러 개의 changeset을 순서대로 정의하며, Liquibase는 이 순서대로 변경을 적용한다.

지원 포맷: **XML / YAML / JSON / SQL**

```yaml
# db/changelog/db.changelog-master.yaml
databaseChangeLog:
  - include:
      file: db/changelog/changes/001-create-users-table.yaml
  - include:
      file: db/changelog/changes/002-add-email-column.yaml
```

`include`로 개별 파일을 지정하거나 `includeAll`로 디렉토리 내 파일을 전부 포함할 수 있다.

```yaml
databaseChangeLog:
  - includeAll:
      path: db/changelog/changes/
```

#### ChangeSet

개별 변경 단위다. `author`, `id`, `파일명` 세 가지 속성의 조합으로 고유하게 식별된다.

```yaml
- changeSet:
    id: 001
    author: luke
    changes:
      - createTable:
          tableName: users
          columns:
            - column:
                name: id
                type: BIGINT
                autoIncrement: true
                constraints:
                  primaryKey: true
            - column:
                name: username
                type: VARCHAR(100)
                constraints:
                  nullable: false
            - column:
                name: created_at
                type: TIMESTAMP
```

**하나의 changeset은 한 가지 논리적 변경만 담는 것이 원칙**이다. 이미 적용된 changeset은 수정하지 않고, 새로운 changeset을 추가하는 방식으로 변경한다.

---

### 3. 동작 원리

#### DATABASECHANGELOG 테이블

Liquibase가 처음 실행될 때 자동으로 생성하는 테이블이다. 적용된 changeset의 id, author, filename, 실행 날짜, checksum 등을 기록한다.

```
ID    | AUTHOR | FILENAME                      | DATEEXECUTED        | MD5SUM
------|--------|-------------------------------|---------------------|------------------
001   | luke   | changes/001-create-users.yaml | 2026-06-19 10:00:00 | 8:abc123...
002   | luke   | changes/002-add-email.yaml    | 2026-06-19 10:00:01 | 8:def456...
```

`liquibase update` 실행 시 이 테이블을 조회해 아직 적용되지 않은 changeset만 실행한다.

#### DATABASECHANGELOGLOCK 테이블

동시에 여러 인스턴스가 마이그레이션을 실행하는 것을 방지하는 락 테이블이다. 클러스터 환경에서 다중 인스턴스가 동시에 기동되더라도 마이그레이션은 단 한 번만 실행된다.

```
ID | LOCKED | LOCKGRANTED         | LOCKEDBY
---|--------|---------------------|------------------
1  | TRUE   | 2026-06-19 10:00:00 | server-instance-1
```

마이그레이션 완료 후 락을 해제한다. 애플리케이션이 비정상 종료되어 락이 해제되지 않은 경우, `liquibase releaseLocks` 명령으로 수동 해제할 수 있다.

#### checksum 기반 변경 감지

Liquibase는 이미 적용된 changeset의 내용이 변경됐는지 checksum을 통해 감지한다. 이미 적용된 changeset의 내용을 수정하면 다음 실행 시 `Validation Failed` 오류가 발생한다.

```
Caused by: liquibase.exception.ValidationFailedException:
  Validation Failed:
    1 changesets check sum
      db/changelog/changes/001-create-users.yaml::001::luke was:
        8:abc123... but is now: 8:xyz789...
```

이 오류를 무시하려면 changeset에 `validCheckSum` 속성을 추가하거나, 올바른 해결책인 **새 changeset을 추가하는 방식**을 따라야 한다.

---

### 4. Spring Boot 연동

#### 의존성 추가

```gradle
// Gradle
implementation 'org.liquibase:liquibase-core'
```

```xml
<!-- Maven -->
<dependency>
    <groupId>org.liquibase</groupId>
    <artifactId>liquibase-core</artifactId>
</dependency>
```

Spring Boot의 `spring-boot-starter-data-jpa` 또는 `spring-boot-starter-jdbc`와 함께 사용하면 DataSource가 자동으로 연결된다.

#### 설정

```yaml
spring:
  liquibase:
    enabled: true
    change-log: classpath:/db/changelog/db.changelog-master.yaml
    contexts: dev          # 특정 context만 실행
    default-schema: public # 기본 스키마 지정
    drop-first: false      # true 시 기동마다 DB 초기화 (개발 전용)
```

기본 changelog 경로는 `classpath:/db/changelog/db.changelog-master.yaml`이며, 별도 지정 없이 이 경로에 파일을 두면 자동으로 인식한다.

#### 애플리케이션 기동 시 마이그레이션 실행 흐름

```
Spring Boot 기동
    │
    ▼
DataSource 초기화
    │
    ▼
Liquibase AutoConfiguration 활성화
    │
    ▼
DATABASECHANGELOGLOCK 획득 (분산 락)
    │
    ▼
changelog 파일 파싱
    │
    ▼
DATABASECHANGELOG 조회 → 미적용 changeset 식별
    │
    ▼
미적용 changeset 순서대로 실행
    │
    ▼
DATABASECHANGELOGLOCK 해제
    │
    ▼
애플리케이션 서버 시작 (HTTP 트래픽 수신)
```

HTTP 요청을 받기 전에 마이그레이션이 완료되므로, 스키마 변경이 코드 배포와 항상 동기화된다.

---

### 5. 주요 명령과 기능

#### CLI 명령

| 명령 | 설명 |
|------|------|
| `liquibase update` | 미적용 changeset을 모두 실행 |
| `liquibase rollback --tag=v1.0` | 특정 태그까지 롤백 |
| `liquibase rollbackCount 3` | 최근 3개 changeset 롤백 |
| `liquibase status` | 미적용 changeset 목록 확인 |
| `liquibase validate` | changelog 파일 유효성 검증 |
| `liquibase generateChangeLog` | 기존 DB에서 changelog 자동 생성 |
| `liquibase releaseLocks` | 잠긴 락 수동 해제 |
| `liquibase tag v1.0` | 현재 상태에 태그 지정 |

#### Context와 Label을 통한 환경별 적용

**Context**: 특정 환경에서만 changeset을 실행하도록 제어한다.

```yaml
- changeSet:
    id: insert-test-data
    author: luke
    context: dev, test   # dev, test 환경에서만 실행
    changes:
      - insert:
          tableName: users
          columns:
            - column:
                name: username
                value: testuser
```

실행 시 `--contexts=dev`로 지정하거나 `spring.liquibase.contexts=dev`로 설정한다.

**Label**: 런타임에 복잡한 표현식으로 실행 여부를 제어한다. Context가 환경 구분에 초점을 맞춘다면, Label은 기능/릴리스 단위 구분에 적합하다.

```yaml
- changeSet:
    id: feature-payment
    author: luke
    labels: payment-v2
    changes:
      - createTable:
          tableName: payments
```

#### Precondition (사전 조건 검증)

changeset 실행 전 DB 상태를 먼저 검증한다. 조건 불충족 시 실행을 건너뛰거나 오류를 발생시킬 수 있다.

```yaml
- changeSet:
    id: add-index-on-email
    author: luke
    preConditions:
      - onFail: MARK_RAN      # 조건 실패 시: 실행 건너뜀 (MARK_RAN / HALT / WARN / CONTINUE)
        not:
          indexExists:
            tableName: users
            indexName: idx_users_email
    changes:
      - createIndex:
          tableName: users
          indexName: idx_users_email
          columns:
            - column:
                name: email
```

---

### 6. Flyway와의 비교

| 항목 | Liquibase | Flyway |
|------|-----------|--------|
| 변경 방식 | 선언적 (XML/YAML/JSON/SQL) | SQL 스크립트 중심 |
| 롤백 지원 | 내장 rollback 지원 | 오픈소스는 롤백 미지원 (Pro만 지원) |
| DB 벤더 독립성 | 추상화된 Change Type 제공 | SQL 직접 작성 → 벤더 종속 가능 |
| 학습 곡선 | 상대적으로 높음 | 단순하고 직관적 |
| 환경별 제어 | Context / Label로 세밀하게 제어 | Placeholder로 제한적 제어 |
| 파일명 규칙 | 자유롭게 지정 가능 | `V1__description.sql` 규칙 강제 |
| 커뮤니티 | 크고 활발 | 소규모 |

**선택 기준**: 다양한 DB를 지원하거나 롤백이 중요한 환경이면 Liquibase, 단순한 SQL 마이그레이션으로 빠르게 시작하고 싶다면 Flyway가 적합하다.

---

## 핵심 정리

- Liquibase는 DB 스키마 변경을 changelog/changeset 단위로 관리해 **버전 관리와 재현성**을 보장한다.
- `DATABASECHANGELOG` 테이블이 적용 이력을 추적하고, `DATABASECHANGELOGLOCK` 테이블이 분산 환경에서의 중복 실행을 방지한다.
- **이미 적용된 changeset은 절대 수정하지 않는다.** 변경이 필요하면 새 changeset을 추가하는 원칙을 따라야 checksum 오류를 피할 수 있다.
- Spring Boot와 통합하면 기동 시 자동으로 마이그레이션이 실행되며, HTTP 트래픽 수신 전에 완료된다.

## 기술적 한계와 보완 전략

- **이미 적용된 ChangeSet 수정 시 checksum 오류**: 수정이 아닌 새 ChangeSet 추가 원칙을 팀 컨벤션으로 확립한다.
- **롤백 스크립트 작성 부담**: 모든 changeset에 `rollback` 블록을 함께 작성하는 습관을 들이거나, 자동 롤백이 지원되는 Change Type(createTable, addColumn 등)을 우선 활용한다.
- **대용량 테이블 변경 시 운영 환경 락/다운타임**: `gh-ost`나 `pt-online-schema-change` 같은 온라인 스키마 변경 도구와 병행하거나, 변경을 여러 단계로 나눠 무중단 배포 전략을 적용한다.
- **분산 락 미해제 상태**: 애플리케이션 비정상 종료 시 `DATABASECHANGELOGLOCK`이 잠긴 채 남을 수 있다. 모니터링 알람과 함께 `liquibase releaseLocks` 절차를 운영 매뉴얼에 포함한다.

## 키워드

- **Liquibase**: DB 스키마 변경 이력을 코드로 관리하는 오픈소스 마이그레이션 도구
- **Database Migration**: 데이터베이스 스키마를 코드와 함께 버전 관리하고 자동화된 방식으로 배포하는 프로세스
- **ChangeLog / ChangeSet**: ChangeLog는 모든 변경 이력을 담는 마스터 파일, ChangeSet은 개별 변경 단위. `author + id + filename`으로 고유 식별
- **DATABASECHANGELOG**: Liquibase가 자동 생성하는 적용 이력 추적 테이블. 어떤 changeset이 언제 적용됐는지 기록
- **Schema Versioning**: 스키마 변경을 버전으로 관리해 환경 간 일관성을 유지하고 변경 이력을 추적하는 방식
- **Rollback**: 특정 시점의 태그나 개수를 기준으로 changeset을 되돌리는 기능. `liquibase rollback` 명령 또는 changeset 내 `rollback` 블록으로 정의
- **Spring Boot**: `spring-boot-autoconfigure`에 Liquibase AutoConfiguration이 포함되어 있어 의존성 추가만으로 기동 시 자동 마이그레이션이 실행됨
- **Flyway**: SQL 스크립트 중심의 또 다른 DB 마이그레이션 도구. Liquibase보다 단순하지만 롤백과 다중 포맷 지원이 약함
- **Precondition**: changeset 실행 전 DB 상태를 검증하는 조건. 테이블/인덱스 존재 여부 등을 사전 확인해 멱등성을 보장

## 참고 자료

- [Liquibase 공식 문서 - Core Concepts](https://www.liquibase.org/get-started/core-usage/liquibase-core-concepts-author-database-changes)
- [Liquibase Community 5.0 릴리스 노트](https://docs.liquibase.com/community/release-notes/5-0)
- [Liquibase Spring Boot 연동 가이드](https://contribute.liquibase.com/extensions-integrations/directory/integration-docs/springboot/)
- [Flyway vs Liquibase 비교 (Bytebase, 2026)](https://www.bytebase.com/blog/flyway-vs-liquibase/)
