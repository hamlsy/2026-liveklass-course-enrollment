package com.hamlsy.liveklass_assignment.concurrency;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.enrollment.application.usecase.CreateEnrollmentUseCase;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * 동시성 테스트는 멀티스레드 특성상 @Transactional 롤백이 불가.
 * 각 테스트 후 @DirtiesContext로 컨텍스트 재시작하여 H2 초기화.
 *
 * 주의: 이 테스트는 컨텍스트 재시작 비용이 크므로 CI에서는 별도로 실행 권장.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CreateEnrollmentConcurrencyTest {

    @Autowired private CreateEnrollmentUseCase createEnrollmentUseCase;
    @Autowired private CourseRepository courseRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;

    private Long courseId;
    private static final int CAPACITY     = 30;
    private static final int THREAD_COUNT = 100;

    @BeforeEach
    void setUp() {
        Course course = Course.builder()
            .title("동시성 테스트 강의").description("test").price(10000).capacity(CAPACITY)
            .startDate(LocalDateTime.now().minusDays(1))
            .endDate(LocalDateTime.now().plusDays(30))
            .build();
        course.changeStatus(CourseStatus.OPEN);
        courseId = courseRepository.save(course).getId();
    }

    @Test
    @DisplayName("100명 동시 신청 → 정원(30)만 성공, currentCount 정합성 보장")
    void 동시_100명_신청시_정원_초과하지_않는다() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(THREAD_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final long userId = i + 1L;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();  // 모든 스레드 준비 후 동시 출발
                    createEnrollmentUseCase.execute(userId, new CreateEnrollmentRequest(courseId));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();           // 모든 스레드 준비 완료 대기
        start.countDown();       // 동시 출발 신호
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // 성공 건수 = 정원
        assertThat(successCount.get())
            .as("성공 건수는 정원(30)과 일치해야 한다")
            .isEqualTo(CAPACITY);

        // 실패 건수 = 전체 - 정원
        assertThat(failCount.get())
            .as("실패 건수는 70이어야 한다")
            .isEqualTo(THREAD_COUNT - CAPACITY);

        // DB currentCount = 정원 (정합성 검증)
        Course result = courseRepository.findById(courseId).orElseThrow();
        assertThat(result.getCurrentCount())
            .as("DB currentCount는 정원(30)과 일치해야 한다")
            .isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("동일 사용자 10회 동시 중복 신청 → 1건만 성공")
    void 동일_사용자_중복_신청시_1건만_성공한다() throws InterruptedException {
        final long SAME_USER_ID  = 999L;
        final int  ATTEMPT_COUNT = 10;

        ExecutorService executor = Executors.newFixedThreadPool(ATTEMPT_COUNT);
        CountDownLatch ready = new CountDownLatch(ATTEMPT_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(ATTEMPT_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < ATTEMPT_COUNT; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    createEnrollmentUseCase.execute(SAME_USER_ID,
                        new CreateEnrollmentRequest(courseId));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get())
            .as("동일 사용자의 동시 중복 신청은 1건만 성공해야 한다")
            .isEqualTo(1);

        Course result = courseRepository.findById(courseId).orElseThrow();
        assertThat(result.getCurrentCount())
            .as("currentCount는 1이어야 한다")
            .isEqualTo(1);
    }
}
