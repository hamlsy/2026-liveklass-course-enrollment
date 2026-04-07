# LiveKlass Enrollment Service - CLAUDE.md

## Project Context
This project is a backend system for LiveKlass course enrollment.

Primary goals:
- Handle high-concurrency enrollment requests
- Maintain strong data integrity
- Follow strict Domain-Driven Design principles
- Prevent over-capacity issues during simultaneous enrollment

---

## Package Structure

You MUST organize code by domain first.

Preferred structure:

```text
[domain-name]/
 ├── presentation
 ├── application
 ├── domain
 └── infrastructure
```

Examples:

```text
member/
course/
enrollment/
```

NEVER use global layer-first structure such as:

```text
application/
domain/
presentation/
infrastructure/
```

ALWAYS use feature-first package structure.

---

## Architecture Rules

Each domain package MUST follow strict 4-layer DDD architecture.

Example:

```text
enrollment/
 ├── presentation
 ├── application
 ├── domain
 └── infrastructure
```

### Layer Responsibilities

#### presentation
- Controllers
- Request DTOs
- Response DTOs
- API validation only

#### application
- UseCase orchestration
- transaction boundaries
- application services

#### domain
- rich entities
- business rules
- repository interfaces
- value objects
- enums

#### infrastructure
- JPA implementations
- database adapters
- external integrations

---

## UseCase Rules

You MUST use UseCase classes for business orchestration.

Naming:

```text
[Action][Subject]UseCase
```

Examples:
- CreateEnrollmentUseCase
- CancelEnrollmentUseCase

Each UseCase MUST expose:

```java
execute()
```

---

## Domain Rules

Entities MUST remain rich.

ALWAYS keep business logic inside domain entities.

NEVER place business logic in:
- controller
- DTO
- repository implementation

---

## Concurrency Rules

You MUST use pessimistic locking.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

Lock target:
- Course entity during enrollment

NEVER use Redis.

---
## Tech Specifications

You MUST use the following stack.

### Language
- Java 17

### Framework
- Spring Boot 4.0.5

### Persistence
- Spring Data JPA
- Hibernate ORM
- H2 Database

### Build Tool
- Gradle

### Testing
- JUnit 5
- CountDownLatch
- ExecutorService

All persistence code MUST use Spring Data JPA repositories.

Always use `jakarta.persistence.*`.

NEVER use `javax.persistence.*`.

## Business Constraints

Validate duplicate enrollment:
- User + Course

Cancellation allowed only within 7 days.

Always include CountDownLatch multi-thread tests.

---

## Commands

```bash
./gradlew bootRun
./gradlew build
./gradlew test
./gradlew clean
```

---

## References

@REQUIREMENTS.md
@FUNCTIONAL_SPEC.md