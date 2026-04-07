package com.hamlsy.liveklass_assignment.common.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 페이지네이션 응답 공통 래퍼.
 *
 * content      : 현재 페이지 데이터 목록
 * page         : 현재 페이지 번호 (0-based)
 * size         : 페이지 크기 (요청한 size)
 * totalElements: 전체 항목 수
 * totalPages   : 전체 페이지 수
 * hasNext      : 다음 페이지 존재 여부
 */
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.hasNext()
        );
    }
}
