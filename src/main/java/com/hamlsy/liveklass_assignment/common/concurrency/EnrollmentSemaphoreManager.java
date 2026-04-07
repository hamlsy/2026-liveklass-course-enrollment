package com.hamlsy.liveklass_assignment.common.concurrency;

import com.hamlsy.liveklass_assignment.common.exception.EnrollmentQueueFullException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class EnrollmentSemaphoreManager {

    /**
     * MAX_CONCURRENT_PER_COURSE = hikari.maximum-pool-size / 2
     * Pool의 절반만 수강 신청에 할당, 나머지는 조회 등 다른 요청용으로 예약
     *
     * fair = true: 먼저 대기한 스레드가 먼저 진입 (선착순 의미 부여)
     */
    private static final int MAX_CONCURRENT_PER_COURSE = 5;
    private static final long ACQUIRE_TIMEOUT_SECONDS = 2L;

    private final ConcurrentHashMap<Long, Semaphore> semaphores = new ConcurrentHashMap<>();

    public void acquire(Long courseId) {
        Semaphore semaphore = semaphores.computeIfAbsent(
            courseId,
            id -> new Semaphore(MAX_CONCURRENT_PER_COURSE, true)
        );
        try {
            boolean acquired = semaphore.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new EnrollmentQueueFullException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EnrollmentQueueFullException();
        }
    }

    public void release(Long courseId) {
        Semaphore semaphore = semaphores.get(courseId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /**
     * 강의가 CLOSED될 때 호출하여 Semaphore 제거 (메모리 누수 방지)
     * UpdateCourseStatusUseCase에서 status == CLOSED 전환 시 호출
     */
    public void removeSemaphore(Long courseId) {
        semaphores.remove(courseId);
    }
}
