package com.coldchain.api.common;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/** Checks the {@code sort} request parameter against the fields a list may be sorted by. */
public final class Sorting {

    private Sorting() {
    }

    /**
     * Without this check, {@code ?sort=passwordHash} or a typo would reach the database and
     * end as an HTTP 500. With it, the client gets a clear 400.
     */
    public static void requireAllowed(Pageable pageable, Set<String> allowedFields) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowedFields.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty()
                        + "'. Allowed sort fields: " + allowedFields);
            }
        }
    }
}
