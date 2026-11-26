package com.joseantonio.reconciliation.application.port.in;

import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;

/** Zero-based page request. */
public record PageQuery(int page, int size) {

    public static final int MAX_SIZE = 500;

    public PageQuery {
        if (page < 0) {
            throw new InvalidRequestException("INVALID_PAGE", "page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidRequestException("INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_SIZE);
        }
    }
}
