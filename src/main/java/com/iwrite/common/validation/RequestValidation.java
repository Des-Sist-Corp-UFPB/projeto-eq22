package com.iwrite.common.validation;

import com.iwrite.common.exception.BadRequestException;

public final class RequestValidation {

    private RequestValidation() {
    }

    public static void rejectBlankWhenPresent(String fieldName, String value) {
        if (value != null && value.isBlank()) {
            throw new BadRequestException(fieldName + " must not be blank");
        }
    }
}
