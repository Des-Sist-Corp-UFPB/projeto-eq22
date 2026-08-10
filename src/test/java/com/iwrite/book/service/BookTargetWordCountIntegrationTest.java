package com.iwrite.book.service;

import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookTargetWordCountIntegrationTest extends PostgresIntegrationTest {

    @Test
    void createsBookWithoutTargetWordCount() {
        BookResponse book = createBook("No target");

        assertThat(book.targetWordCount()).isNull();
        assertThat(bookService.findById(book.id()).targetWordCount()).isNull();
    }

    @Test
    void createsBookWithPositiveTargetWordCount() {
        BookResponse book = createBook("With target", 80000);

        assertThat(book.targetWordCount()).isEqualTo(80000);
        assertThat(bookService.findById(book.id()).targetWordCount()).isEqualTo(80000);
    }

    @Test
    void updateCanSetAndRemoveTargetWordCount() {
        BookResponse book = createBook("Mutable target");

        BookUpdateRequest setTarget = new BookUpdateRequest();
        setTarget.setTargetWordCount(50000);
        BookResponse updated = bookService.update(book.id(), setTarget);

        assertThat(updated.targetWordCount()).isEqualTo(50000);

        BookUpdateRequest removeTarget = new BookUpdateRequest();
        removeTarget.setTargetWordCount(null);
        BookResponse removed = bookService.update(book.id(), removeTarget);

        assertThat(removed.targetWordCount()).isNull();
        assertThat(bookService.findById(book.id()).targetWordCount()).isNull();
    }
}
