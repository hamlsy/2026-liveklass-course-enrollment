package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.github.benmanes.caffeine.cache.Cache;
import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.course.presentation.response.CourseEnrollmentListResponse;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import com.hamlsy.liveklass_assignment.member.domain.entity.Member;
import com.hamlsy.liveklass_assignment.member.domain.entity.MemberRole;
import com.hamlsy.liveklass_assignment.member.domain.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class GetCourseEnrollmentsUseCase {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final MemberRepository memberRepository;
    private final Cache<Long, MemberRole> memberRoleCache;

    /**
     * 강의별 수강생 목록 조회 (크리에이터 전용, 페이지네이션)
     *
     * 처리 순서:
     *   1. 강의 조회 → 없으면 CourseNotFoundException
     *   2. CREATOR 역할 검증 (memberRoleCache hit 시 DB 미접근)
     *   3. 강의 소유권 검증 (course.validateCreator)
     *   4. PENDING / CONFIRMED 수강생 목록 페이지 조회 (CANCELLED 제외)
     *   5. CourseEnrollmentListResponse 반환
     *
     * @param courseId    조회할 강의 ID
     * @param requesterId 요청자 Member ID (실 서비스에서는 SecurityContext에서 추출)
     * @param pageable    페이지 정보 (page, size, sort)
     */
    @Transactional(readOnly = true)
    public CourseEnrollmentListResponse execute(Long courseId, Long requesterId, Pageable pageable) {

        // 1. 강의 조회
        Course course = courseRepository.findById(courseId)
            .orElseThrow(CourseNotFoundException::new);

        // 2. CREATOR 역할 검증 (캐시 우선, miss 시 DB 조회 후 캐시 저장)
        MemberRole role = memberRoleCache.getIfPresent(requesterId);
        if (role == null) {
            Member requester = memberRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
            role = requester.getRole();
            memberRoleCache.put(requesterId, role);
        }
        if (role != MemberRole.CREATOR) {
            throw new UnauthorizedCreatorException();
        }

        // 3. 강의 소유권 검증 (course.creatorId == requesterId)
        course.validateCreator(requesterId);

        // 4. 수강생 목록 페이지 조회 (CANCELLED 제외, JOIN FETCH 없음)
        Page<Enrollment> page = enrollmentRepository.findPageByCourseIdAndStatusIn(
            courseId,
            List.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED),
            pageable
        );

        return CourseEnrollmentListResponse.of(courseId, course.getTitle(), page);
    }
}
