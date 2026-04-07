# LiveKlass Backend Assignment: Course Enrollment System

## 프로젝트 개요

LiveKlass 수강 신청 마이크로서비스입니다.

- 크리에이터의 강의 개설 및 상태 관리
- 클래스메이트의 선착순 수강 신청 / 결제 확정 / 취소
- 정원 초과 시 대기열 자동 승격
- 대량 동시 요청 상황에서 데이터 정합성 유지

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 4.0.5 |
| Persistence | Spring Data JPA / Hibernate |
| Database | H2 (in-memory) |
| Cache | Caffeine |
| Build | Gradle |
| Test | JUnit 5, CountDownLatch, ExecutorService |

---

## 실행 방법

### 로컬 실행

```bash
# 빌드
./gradlew build

# 실행
./gradlew bootRun
```

서버 기동 후 기본 포트: `http://localhost:8080`

H2 콘솔: `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:mem:liveklass`
- Username: `sa` / Password: (없음)

> **주의:** H2 in-memory DB를 사용하므로 서버 재시작 시 데이터가 초기화됩니다.

---

## API 목록 및 예시

> `userId`, `creatorId`는 실제 서비스에서는 SecurityContext에서 추출합니다.
> 과제에서는 요청 파라미터로 전달합니다.

### Course

#### 강의 개설
```
POST /courses
Content-Type: application/json

{
  "creatorId": 1,
  "title": "스프링 부트 실전",
  "description": "Spring Boot 심화 강의",
  "price": 50000,
  "capacity": 30,
  "startDate": "2026-04-01T00:00:00",
  "endDate": "2026-06-30T23:59:59"
}
```

```json
{
  "id": 1,
  "title": "스프링 부트 실전",
  "status": "DRAFT",
  "price": 50000,
  "capacity": 30,
  "currentCount": 0
}
```

#### 강의 상태 변경
```
PATCH /courses/{courseId}/status
Content-Type: application/json

{ "status": "OPEN" }
```

상태 전이 규칙: `DRAFT → OPEN → CLOSED` (역방향 불가)

#### 강의 목록 조회
```
GET /courses
GET /courses?status=OPEN
```

#### 강의 상세 조회
```
GET /courses/{courseId}
```

---

### Enrollment

#### 수강 신청
```
POST /enrollments?userId=10
Content-Type: application/json
Idempotency-Key: {uuid}  (선택)

{ "courseId": 1 }
```

```json
{
  "enrollmentId": 5,
  "userId": 10,
  "courseId": 1,
  "courseTitle": "스프링 부트 실전",
  "price": 50000,
  "status": "PENDING",
  "enrolledAt": "2026-04-07T10:00:00",
  "confirmedAt": null
}
```

- `Idempotency-Key` 헤더 포함 시: 동일 키 재시도 → 캐시된 응답 반환 (DB 미처리)
- 헤더 없이 중복 신청 시: `ALREADY_ENROLLED` 오류 반환

#### 결제 확정 (PENDING → CONFIRMED)
```
PATCH /enrollments/{enrollmentId}/confirm
```

#### 수강 취소
```
PATCH /enrollments/{enrollmentId}/cancel?userId=10
```

- 신청일로부터 7일 이내만 취소 가능
- 취소 시 대기열 1순위 자동 승격 처리

#### 내 수강 내역 조회 (페이지네이션)
```
GET /enrollments/me?userId=10
GET /enrollments/me?userId=10&page=0&size=5&sort=enrolledAt,DESC
```

```json
{
  "content": [
    {
      "enrollmentId": 5,
      "userId": 10,
      "courseId": 1,
      "courseTitle": "스프링 부트 실전",
      "price": 50000,
      "status": "CONFIRMED",
      "enrolledAt": "2026-04-07T10:00:00",
      "confirmedAt": "2026-04-07T10:05:00"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

기본값: `page=0, size=10, sort=enrolledAt DESC` / 최대 페이지 크기: 100

#### 강의별 수강생 목록 조회 (크리에이터 전용, 페이지네이션)
```
GET /enrollments/courses/{courseId}/enrollments?creatorId=1
GET /enrollments/courses/{courseId}/enrollments?creatorId=1&page=0&size=20
```

```json
{
  "courseId": 1,
  "courseTitle": "스프링 부트 실전",
  "totalElements": 28,
  "totalPages": 2,
  "page": 0,
  "size": 20,
  "hasNext": true,
  "enrollments": [
    {
      "enrollmentId": 3,
      "userId": 10,
      "status": "CONFIRMED",
      "enrolledAt": "2026-04-07T09:00:00",
      "confirmedAt": "2026-04-07T09:05:00"
    }
  ]
}
```

- CANCELLED 제외, PENDING / CONFIRMED만 반환
- 크리에이터 본인의 강의만 조회 가능

---

### Waitlist (대기열)

#### 대기열 등록
```
POST /waitlist?userId=10&courseId=1
```

```json
{
  "waitlistId": 2,
  "userId": 10,
  "courseId": 1,
  "status": "WAITING",
  "waitlistedAt": "2026-04-07T10:30:00"
}
```

- 강의가 정원 미달인 경우 등록 불가 (`COURSE_NOT_FULL`)
- 이미 수강 중이거나 대기 중인 경우 등록 불가

#### 대기 취소
```
PATCH /waitlist/{waitlistId}/cancel?userId=10
```

- 이미 승격(PROMOTED)된 대기열은 취소 불가 → 수강 취소 API 이용

#### 대기 순번 조회
```
GET /waitlist/position?userId=10&courseId=1
```

```json
{
  "userId": 10,
  "courseId": 1,
  "position": 3
}
```

순번은 2초 TTL 캐시로 제공됩니다.

---

### 에러 응답 형식

```json
{
  "code": "ALREADY_ENROLLED",
  "message": "이미 신청한 강의입니다."
}
```

| 코드 | HTTP | 설명 |
|------|------|------|
| `COURSE_NOT_FOUND` | 404 | 강의 없음 |
| `ENROLLMENT_LIMIT_EXCEEDED` | 400 | 정원 초과 |
| `ALREADY_ENROLLED` | 400 | 중복 신청 |
| `CANCELLATION_PERIOD_EXPIRED` | 400 | 7일 취소 기한 초과 |
| `ENROLLMENT_QUEUE_FULL` | 503 | 동시 요청 과부하 (Semaphore 만료) |
| `UNAUTHORIZED_CREATOR` | 403 | 크리에이터 권한 없음 |
| `WAITLIST_ALREADY_PROMOTED` | 409 | 승격된 대기열 취소 시도 |
| `COURSE_NOT_FULL` | 400 | 정원 여유 있을 때 대기열 등록 시도 |

---

## 데이터 모델 설명

### 테이블 구조

#### `course`
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | 강의 ID |
| title | VARCHAR | 강의명 |
| description | TEXT | 강의 설명 |
| price | INT | 수강료 |
| capacity | INT | 정원 |
| current_count | INT | 현재 수강 인원 |
| status | VARCHAR | DRAFT / OPEN / CLOSED |
| start_date | DATETIME | 수강 신청 시작일 |
| end_date | DATETIME | 수강 신청 종료일 |
| creator_id | BIGINT | 크리에이터 Member ID |

인덱스: `idx_course_status (status)`, `idx_course_creator_id (creator_id)`

#### `member`
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | 회원 ID |
| name | VARCHAR | 회원명 |
| role | VARCHAR | STUDENT / CREATOR |

#### `enrollment`
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | 수강 신청 ID |
| user_id | BIGINT | 수강생 ID (FK 없음, 의도적) |
| course_id | BIGINT FK | 강의 ID |
| status | VARCHAR | PENDING / CONFIRMED / CANCELLED |
| enrolled_at | DATETIME | 신청 일시 |
| confirmed_at | DATETIME | 결제 확정 일시 |

인덱스:
- `idx_enrollment_user_id (user_id)` — 단순 조회
- `idx_enrollment_course_id (course_id)` — 강의별 조회
- `idx_enrollment_user_course_status (user_id, course_id, status)` — 중복 신청 Covering Index
- `idx_enrollment_user_enrolled_at (user_id, enrolled_at)` — 페이지네이션 정렬 (filesort 제거)

#### `waitlist`
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | 대기열 ID |
| user_id | BIGINT | 대기 사용자 ID |
| course_id | BIGINT FK | 강의 ID |
| status | VARCHAR | WAITING / PROMOTED / CANCELLED |
| waitlisted_at | DATETIME | 대기 등록 일시 |

인덱스:
- `idx_waitlist_course_status (course_id, status)` — FIFO 승격 쿼리
- `idx_waitlist_user_course (user_id, course_id)` — 중복 대기 검증

### 관계

```
Member (1) ─── (N) Enrollment (N) ─── (1) Course
                                             │
                         Waitlist (N) ───────┘
```

`Enrollment.userId`는 Long 타입으로 Member FK를 갖지 않습니다.
Member JOIN 없이 수강 내역을 조회하기 위한 의도적 설계입니다.

---

## 요구사항 해석 및 가정

### 해석

| 요구사항 | 해석 |
|---------|------|
| 선착순 수강 신청 | Pessimistic Lock + currentCount 원자적 증가로 구현 |
| 중복 신청 방지 | (user_id, course_id, status IN PENDING/CONFIRMED) 체크 |
| 취소 7일 제한 | enrolledAt 기준 7일 이내만 허용 |
| 크리에이터 수강생 조회 | Member.role == CREATOR && course.creatorId == requesterId 이중 검증 |

### 가정

- 인증/인가 미구현: userId, creatorId를 요청 파라미터로 전달하는 방식으로 대체
- 결제 시스템 연동 없음: `PATCH /confirm`으로 수동 결제 확정 처리
- 대기열 알림 없음: 승격은 즉시 처리되나 사용자 통보 로직 미포함
- Member 초기 데이터: 테스트 코드에서 직접 생성, 별도 시드 데이터 없음

---

## 설계 결정과 이유

### 1. Pessimistic Lock (비관적 락)

수강 신청과 취소 시 `SELECT ... FOR UPDATE`로 Course 행에 락을 겁니다.

```
낙관적 락(Optimistic Lock) 대비 선택 이유:
- 충돌이 빈번한 선착순 시나리오 → 낙관적 락은 재시도 폭풍(retry storm) 유발
- 비관적 락으로 충돌 자체를 직렬화 → 재시도 없이 한 번에 처리
```

### 2. Semaphore (per-course) — DB 연결 풀 보호

HikariCP `maximum-pool-size = 10`. 동시 100개 요청이 모두 락 대기하면 DB 연결 고갈.

```
per-course Semaphore(permits=5, fair=true)로 동시 진입 스레드를 5개로 제한.
fair=true: FIFO 순서 보장, 선착순 의도 유지.
Semaphore는 트랜잭션 바깥에서 acquire/release — 락 보유 중 Semaphore 점유 방지.
```

### 3. Caffeine Cache — DB 부하 사전 차단

실제 정합성은 Lock이 보장하지만, Lock 진입 전에 명백히 불가능한 요청을 캐시로 조기 차단합니다.

| 캐시 | 키 | TTL | 용도 |
|------|---|-----|------|
| `courseCapacityCache` | courseId | 5초 | 정원 초과 사전 필터 |
| `idempotencyCache` | Idempotency-Key | 24시간 | 멱등 응답 재사용 |
| `memberRoleCache` | memberId | 60초 | CREATOR 검증 DB 쿼리 제거 |
| `waitlistPositionCache` | "courseId:userId" | 2초 | 순번 COUNT 쿼리 중복 제거 |

### 4. UseCase 패턴

비즈니스 오케스트레이션을 UseCase 단위로 분리합니다.

```
Controller → UseCase → Service → Repository
```

UseCase는 트랜잭션 경계 + Semaphore 제어 책임.
Service는 순수 트랜잭션 내 도메인 로직만 담당.

### 5. Waitlist 승격 — `Propagation.MANDATORY`

취소 트랜잭션 안에서 `tryPromoteNextWaiting()`을 호출합니다.
`Propagation.MANDATORY`로 선언하여 반드시 기존 트랜잭션에 참여하도록 강제합니다.

```
이유: 별도 트랜잭션으로 분리하면 취소 커밋 후 승격 실패 시
     정원은 줄었으나 대기자도 승격 안 된 상태가 될 수 있음.
     동일 트랜잭션 내 처리로 원자성 보장.
```

### 6. N+1 방지 전략

| 쿼리 상황 | 해결 방법 |
|---------|---------|
| 내 수강 내역 조회 | `JOIN FETCH e.course` — 단일 쿼리로 course 로딩 |
| 취소 시 course 접근 | `JOIN FETCH e.course` 전용 쿼리 |
| 크리에이터 수강생 목록 | JOIN FETCH 없음 — EnrollmentItem DTO가 course 데이터를 사용하지 않음 |

### 7. `Enrollment.userId` — Member FK 미사용

```java
@Column(name = "user_id", nullable = false)
private Long userId;  // @ManyToOne Member 아님
```

수강 내역 조회 시 Member 테이블 JOIN 불필요. 필요한 것은 userId (식별자)뿐이므로 참조 단순화.

### 8. 페이지네이션 인덱스 설계

`GET /enrollments/me?sort=enrolledAt,DESC` 처리 시:

```sql
-- 기존 단순 인덱스(user_id)만 있으면
SELECT ... FROM enrollment WHERE user_id = ? ORDER BY enrolled_at DESC
→ user_id 필터 후 전체 결과 filesort

-- 복합 인덱스 (user_id, enrolled_at) 추가 후
→ Index Range Scan, filesort 없음
```

---

## 테스트 실행 방법

```bash
# 전체 테스트 실행
./gradlew test

# 테스트 결과 리포트 확인
open build/reports/tests/test/index.html
```

### 테스트 구성

| 파일 | 종류 | 설명 |
|------|------|------|
| `domain/CourseTest` | 단위 | Course 상태 전이, 정원 증감, 날짜 검증 |
| `domain/EnrollmentTest` | 단위 | 취소 7일 제한, 소유자 검증, 멱등 처리 |
| `usecase/CreateEnrollmentUseCaseTest` | 통합 | 정상 신청, 중복 신청, 정원 초과 |
| `usecase/ConfirmPaymentUseCaseTest` | 통합 | PENDING→CONFIRMED 전이, 멱등 confirm |
| `usecase/CancelEnrollmentUseCaseTest` | 통합 | 정상 취소, 7일 초과 취소 거부, 소유자 검증 |
| `concurrency/CreateEnrollmentConcurrencyTest` | 동시성 | 100스레드 동시 신청 → 정원(30)만 성공, 정합성 보장 |

동시성 테스트는 `@DirtiesContext`로 각 테스트 후 컨텍스트를 재시작합니다 (H2 초기화).

---

## 미구현 / 제약사항

| 항목 | 내용 |
|------|------|
| 인증/인가 | Spring Security 설정은 존재하나 모든 엔드포인트 permitAll 상태. userId를 파라미터로 전달 |
| Docker | Docker 환경 미구성. 로컬 실행만 지원 |
| 결제 연동 | 외부 결제 시스템 없음. `/confirm` API로 수동 확정 |
| 대기열 알림 | 승격 시 사용자 Push/Email 알림 없음 |
| Waitlist 단위 테스트 | 기본 플로우 통합 검증 없음 (동시성 테스트는 enrollment 위주) |
| sort 필드 화이트리스트 | `?sort=confirmedAt` 등 인덱스 없는 필드 정렬 허용 (과제 범위) |
| 로그인한 사용자 정원 캐시 stale | 5초 TTL 내에서 이미 찬 강의에 false negative 발생 가능 (실제 정합성은 Lock이 보장) |

---

## AI 활용 범위

본 프로젝트는 Claude Code (claude-sonnet-4-6)를 활용하여 구현되었습니다.

### 활용 방식

- **Phase별 설계 문서 작성**: `plans/` 디렉토리의 각 Phase 플랜 초안을 AI와 함께 작성
- **시니어 코드 리뷰**: 각 Phase 구현 전 플랜을 AI에게 시니어 관점으로 검토 요청
  - 동시성 문제, DB 부하, N+1 패턴, dead code, 인덱스 설계 등을 사전 발견 및 수정
- **코드 구현**: 플랜 확정 후 AI가 코드 생성, 개발자가 검토 후 승인
- **테스트 코드**: 동시성 테스트 포함 전체 테스트 코드 작성

### 주요 AI 기여 항목

| 항목 | AI 기여 |
|------|---------|
| Semaphore 기반 DB 연결 풀 보호 설계 | 설계 제안 및 구현 |
| `Propagation.MANDATORY`로 승격 원자성 보장 | 설계 및 구현 |
| 복합 인덱스 `(user_id, enrolled_at)` 추가 | 리뷰 중 filesort 문제 발견 및 수정 |
| `memberRoleCache`로 크리에이터 검증 DB 쿼리 제거 | 설계 및 구현 |
| JOIN FETCH 대상 최적화 (불필요한 JOIN 제거) | 리뷰 중 발견 및 수정 |

### Human-in-the-loop

모든 AI 생성 플랜은 개발자가 직접 검토·승인 후 구현을 진행했습니다.
코드 생성 후 테스트 통과 여부를 확인하고 커밋했습니다.
