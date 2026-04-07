package com.hamlsy.liveklass_assignment.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Course
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "강의를 찾을 수 없습니다."),
    INVALID_COURSE_STATUS(HttpStatus.BAD_REQUEST, "수강 신청이 불가한 강의 상태입니다."),
    INVALID_ENROLLMENT_PERIOD(HttpStatus.BAD_REQUEST, "수강 신청 기간이 아닙니다."),
    INVALID_COURSE_DATE(HttpStatus.BAD_REQUEST, "종료일은 시작일 이후여야 합니다."),
    INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "허용되지 않는 상태 전이입니다."),

    // Enrollment
    ENROLLMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "수강 신청 내역을 찾을 수 없습니다."),
    ENROLLMENT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "정원이 초과되었습니다."),
    ALREADY_ENROLLED(HttpStatus.BAD_REQUEST, "이미 신청한 강의입니다."),
    CANCELLATION_PERIOD_EXPIRED(HttpStatus.BAD_REQUEST, "취소 가능 기간(7일)이 지났습니다."),
    INVALID_ENROLLMENT_STATUS(HttpStatus.BAD_REQUEST, "현재 상태에서는 해당 작업을 수행할 수 없습니다."),
    UNAUTHORIZED_ENROLLMENT(HttpStatus.FORBIDDEN, "본인의 수강 신청만 처리할 수 있습니다."),

    // Concurrency
    ENROLLMENT_QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "현재 요청이 많습니다. 잠시 후 다시 시도해주세요."),

    // Creator
    UNAUTHORIZED_CREATOR(HttpStatus.FORBIDDEN, "해당 강의의 크리에이터만 조회할 수 있습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),

    // Waitlist
    ALREADY_IN_WAITLIST(HttpStatus.BAD_REQUEST, "이미 대기열에 등록되어 있습니다."),
    WAITLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "대기열 항목을 찾을 수 없습니다."),
    WAITLIST_UNAUTHORIZED(HttpStatus.FORBIDDEN, "본인의 대기열만 취소할 수 있습니다."),
    WAITLIST_ALREADY_PROMOTED(HttpStatus.CONFLICT, "이미 수강 신청으로 승격된 대기열입니다. 수강 취소 API를 이용하세요."),
    COURSE_NOT_FULL(HttpStatus.BAD_REQUEST, "강의 정원이 남아있습니다. 수강 신청을 이용하세요.");

    private final HttpStatus httpStatus;
    private final String message;
}
