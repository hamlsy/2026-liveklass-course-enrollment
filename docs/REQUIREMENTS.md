# LiveKlass Backend Assignment: 상세 요구사항 및 설계 정의서

## 1. 프로젝트 개요
- 서비스명: LiveKlass 수강 신청 시스템 (과제 A)
- 핵심 목표: DDD 아키텍처를 준수하며, DB Lock을 활용해 선착순 수강 신청의 정합성 보장

## 2. 기술 스택
- Framework: Spring Boot 3.4.x (Java 17)
- Database: H2 (In-memory)
- Persistence: Spring Data JPA
- Concurrency: JPA Pessimistic Lock (Redis 미사용)

## 3. 아키텍처 설계 (DDD Based UseCase Pattern)

### 3.1 Layer 구조
1. **presentation**: Controller, Request/Response DTO
2. **application**:
    - **UseCase**: 서비스 간 오케스트레이션 및 단일 작업 실행 (Naming: `XxxUseCase`, Method: `execute()`)
    - **Service**: 도메인 로직 실행 및 트랜잭션 관리
3. **domain**: Entity, VO (Value Object), DomainService, Repository Interface
4. **infrastructure**: Persistence (JpaRepository 구현체), External API
5. **common**: Exception, Utils, Constants

### 3.2 핵심 도메인 상태
- **Course Status**: DRAFT, OPEN, CLOSED
- **Enrollment Status**: PENDING, CONFIRMED, CANCELLED

## 4. 상세 기능 명세

### 4.1 강의 관리 (Course)
- **CreateCourseUseCase**: 강사가 강의 정보를 입력하여 `DRAFT` 상태로 생성.
- **GetCourseListUseCase**: 상태 필터를 적용하여 강의 목록 조회.
- **GetCourseDetailUseCase**: 현재 신청 인원(`current_count`)을 포함한 상세 정보 조회.

### 4.2 수강 신청 및 결제 (Enrollment)
- **CreateEnrollmentUseCase**:
    - `OPEN` 상태 확인 및 정원 여유 확인.
    - 중복 신청 여부 검증.
    - **동시성 제어**: `Pessimistic Lock`을 사용하여 `current_count`를 안전하게 증가시킨 후 `PENDING` 생성.
- **ConfirmPaymentUseCase**: 결제 완료 처리 (`PENDING` -> `CONFIRMED`).
- **CancelEnrollmentUseCase**:
    - 결제 후 7일 이내인지 검증 (선택 구현).
    - `CONFIRMED` -> `CANCELLED` 변경 및 `current_count` 1 감소.
- **GetMyEnrollmentsUseCase**: 특정 사용자의 신청 내역 및 상태 조회.

## 5. 동시성 및 데이터 정합성 전략
- **전략**: DB 비관적 락 (`LockModeType.PESSIMISTIC_WRITE`) 활용.
- **이유**: Redis 등 외부 인프라 사용이 제한된 환경에서, 가장 확실하게 원자적 수정을 보장할 수 있는 방식 선택.
- **검증**: `ExecutorService`와 `CountDownLatch`를 사용하여 100개 이상의 동시 요청 테스트 수행.

## 6. AI 구현 지시 가이드 (Implementation Plan)
1. **패키지 분리**: 상기 명시된 4개 레이어(presentation, application, domain, infrastructure)를 패키지로 엄격히 분리할 것.
2. **UseCase 구현**: 서비스 레이어를 오케스트레이션하는 `execute()` 메서드 중심의 UseCase 클래스를 작성할 것.
3. **Entity 설계**: 비즈니스 로직(상태 변경, 검증 등)은 도메인 엔티티 내부에 위치시킬 것.
4. **테스트**: 멀티스레드 테스트 코드를 작성하여 Race Condition 상황에서 정원 초과가 발생하지 않음을 증명할 것.