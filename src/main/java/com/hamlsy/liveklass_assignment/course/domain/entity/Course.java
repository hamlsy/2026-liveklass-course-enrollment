package com.hamlsy.liveklass_assignment.course.domain.entity;

import com.hamlsy.liveklass_assignment.common.exception.EnrollmentLimitExceededException;
import com.hamlsy.liveklass_assignment.common.exception.ErrorCode;
import com.hamlsy.liveklass_assignment.common.exception.InvalidCourseStatusException;
import com.hamlsy.liveklass_assignment.common.exception.UnauthorizedCreatorException;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "course",
    indexes = {
        @Index(name = "idx_course_status",     columnList = "status"),
        @Index(name = "idx_course_creator_id", columnList = "creator_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Course {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int currentCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourseStatus status;

    @Column(nullable = false)
    private LocalDateTime startDate;

    @Column(nullable = false)
    private LocalDateTime endDate;

    @Column(nullable = false)
    private Long creatorId;

    @Builder
    public Course(String title, String description, int price,
                  int capacity, LocalDateTime startDate, LocalDateTime endDate,
                  Long creatorId) {
        validateDates(startDate, endDate);
        this.title        = title;
        this.description  = description;
        this.price        = price;
        this.capacity     = capacity;
        this.currentCount = 0;
        this.status       = CourseStatus.DRAFT;
        this.startDate    = startDate;
        this.endDate      = endDate;
        // creatorId가 null이면 0L로 기본 처리 (기존 테스트 픽스처 보호)
        this.creatorId    = (creatorId != null) ? creatorId : 0L;
    }

    // ── 도메인 메서드 ──────────────────────────────────────────────

    /** 상태가 OPEN인지 검증 */
    public void validateOpen() {
        if (this.status != CourseStatus.OPEN) {
            throw new InvalidCourseStatusException();
        }
    }

    /** 현재 시각이 수강 신청 기간 내인지 검증 */
    public void validateEnrollmentPeriod() {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(startDate) || now.isAfter(endDate)) {
            throw new InvalidCourseStatusException(ErrorCode.INVALID_ENROLLMENT_PERIOD);
        }
    }

    /** 현재 정원이 꽉 찼는지 여부 */
    public boolean isFull() {
        return this.currentCount >= this.capacity;
    }

    /** 요청자가 이 강의의 크리에이터인지 검증 */
    public void validateCreator(Long requesterId) {
        if (!this.creatorId.equals(requesterId)) {
            throw new UnauthorizedCreatorException();
        }
    }

    /**
     * 정원을 1 증가.
     * 반드시 Pessimistic Lock 획득 후 호출해야 한다.
     * currentCount >= capacity 이면 EnrollmentLimitExceededException
     */
    public void increaseCurrentCount() {
        if (this.currentCount >= this.capacity) {
            throw new EnrollmentLimitExceededException();
        }
        this.currentCount++;
    }

    /**
     * 정원을 1 감소 (취소 시 호출).
     * 반드시 Pessimistic Lock 획득 후 호출해야 한다.
     */
    public void decreaseCurrentCount() {
        if (this.currentCount <= 0) return;
        this.currentCount--;
    }

    /**
     * 상태 전이.
     * canTransitionTo() 로 규칙 검증 — CLOSED → 어떤 상태로도 불가.
     */
    public void changeStatus(CourseStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new InvalidCourseStatusException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = next;
    }

    // ── private ───────────────────────────────────────────────────

    private static void validateDates(LocalDateTime startDate, LocalDateTime endDate) {
        if (endDate == null || startDate == null || !endDate.isAfter(startDate)) {
            throw new InvalidCourseStatusException(ErrorCode.INVALID_COURSE_DATE);
        }
    }
}
