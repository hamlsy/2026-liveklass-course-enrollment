package com.hamlsy.liveklass_assignment.course.domain.repository;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;

import java.util.List;
import java.util.Optional;

public interface CourseRepository {
    Course save(Course course);
    Optional<Course> findById(Long id);
    List<Course> findAll();
    List<Course> findAllByStatus(CourseStatus status);

    /**
     * Pessimistic Write Lock + 3초 타임아웃.
     * 수강 신청 / 취소 시 currentCount 수정에 사용.
     */
    Optional<Course> findByIdWithLock(Long id);
}
