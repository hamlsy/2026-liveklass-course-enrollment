# LiveKlass 수강 신청 시스템: 세부 기능 및 로직 명세 (Functional Specification)

## 1. 강의 관리 (Course Domain)

### 1.1 강의 등록 (CreateCourse)
- **Input**: 제목, 설명, 가격, 최대 정원(capacity), 수강 시작/종료일
- **Logic**:
    - 입력받은 수강 종료일은 시작일보다 이전일 수 없음.
    - 초기 상태는 항상 `DRAFT`로 설정.
    - 현재 신청 인원(`currentCount`)은 0으로 초기화.

### 1.2 강의 상태 변경 (UpdateCourseStatus)
- **Logic**:
    - `DRAFT` -> `OPEN`: 관리자가 모집을 시작할 때 전환.
    - `OPEN` -> `CLOSED`: 정원이 찼거나 관리자가 수동으로 마감할 때 전환.
    - 이미 `CLOSED`된 강의는 다시 `OPEN`할 수 없음 (비즈니스 정책에 따른 가정).

---

## 2. 수강 신청 관리 (Enrollment Domain)

### 2.1 수강 신청 실행 (CreateEnrollmentUseCase) - 핵심 로직
이 프로세스는 **Atomic**하게 실행되어야 하며, 동시성 제어가 적용되어야 함.

1. **강의 존재 및 상태 검증**:
    - 해당 `courseId`가 존재하는가?
    - 강의 상태가 `OPEN`인가? (DRAFT나 CLOSED면 `400 Bad Request` 또는 커스텀 예외)
2. **신청 기간 검증**:
    - 현재 시간이 `startDate`와 `endDate` 사이인가?
3. **중복 신청 검증**:
    - 해당 `userId`가 이미 이 `courseId`에 대해 `PENDING` 또는 `CONFIRMED` 상태의 신청 내역이 있는가?
4. **정원 검증 및 점유 (Pessimistic Lock 적용 포인트)**:
    - `SELECT ... FOR UPDATE`를 통해 Course 엔티티를 조회.
    - `course.currentCount < course.capacity` 인지 확인.
    - 조건 충족 시 `course.currentCount`를 1 증가시킴.
5. **신청 데이터 생성**:
    - `Enrollment` 엔티티를 `PENDING` 상태로 저장.

### 2.2 결제 확정 (ConfirmPaymentUseCase)
- **Input**: `enrollmentId`
- **Logic**:
    - 해당 신청 건이 `PENDING` 상태인지 확인.
    - 상태를 `CONFIRMED`로 변경하고 `confirmedAt` 시간을 기록.

### 2.3 수강 취소 (CancelEnrollmentUseCase)
- **Input**: `enrollmentId`, `userId`
- **Logic**:
    - 본인의 신청 내역인지 확인.
    - 상태가 `CONFIRMED`인 경우:
        - (선택 구현 시) 결제일로부터 7일 이내인지 확인.
        - 상태를 `CANCELLED`로 변경.
        - **중요**: 해당 강의(`Course`)의 `currentCount`를 1 감소시킴.

---

## 3. 조회 기능 (Query)

### 3.1 강의 목록 및 상세 조회
- 목록 조회 시 `currentCount`와 `capacity`를 함께 보여주어 잔여석 확인 가능하게 함.
- 상태별(OPEN, CLOSED 등) 필터링 제공.

### 3.2 내 수강 내역 조회
- 사용자의 ID를 기반으로 신청한 강의명, 결제 금액, 현재 상태(`PENDING`, `CONFIRMED` 등)를 반환.

---

## 4. 예외 처리 정의 (Exception Handling)
AI가 아래 상황에서 적절한 예외를 던지도록 설정:
- `CourseNotFoundException`: 강의가 존재하지 않음 (404)
- `EnrollmentLimitExceededException`: 정원 초과 (400)
- `InvalidCourseStatusException`: 신청 가능한 상태가 아님 (400)
- `AlreadyEnrolledException`: 이미 신청한 강의임 (400)
- `CancellationPeriodExpiredException`: 취소 가능 기간 만료 (400)