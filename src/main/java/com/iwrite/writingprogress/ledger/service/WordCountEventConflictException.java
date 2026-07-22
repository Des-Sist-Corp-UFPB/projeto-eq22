package com.iwrite.writingprogress.ledger.service;

import com.iwrite.common.exception.ConflictException;

public class WordCountEventConflictException extends ConflictException {

    public WordCountEventConflictException(String message) {
        super(message);
    }
}
