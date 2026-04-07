package com.hamlsy.liveklass_assignment.course.application.service;

import com.hamlsy.liveklass_assignment.common.exception.CourseNotFoundException;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;

    @Transactional
    public Course create(Course course) {
        return courseRepository.save(course);
    }

    @Transactional
    public Course updateStatus(Long courseId, CourseStatus nextStatus) {
        Course course = courseRepository.findById(courseId)
            .orElseThrow(CourseNotFoundException::new);
        course.changeStatus(nextStatus);
        return course; // dirty checking
    }

    @Transactional(readOnly = true)
    public Course findById(Long courseId) {
        return courseRepository.findById(courseId)
            .orElseThrow(CourseNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<Course> findAll(CourseStatus status) {
        return (status != null)
            ? courseRepository.findAllByStatus(status)
            : courseRepository.findAll();
    }
}
