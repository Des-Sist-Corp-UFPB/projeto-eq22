package com.iwrite.notebook.repository;

import com.iwrite.notebook.entity.BookNotebookSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface BookNotebookSettingsRepository extends JpaRepository<BookNotebookSettings, UUID> {

    @Modifying
    @Query(value = """
            insert into book_notebook_settings (book_id, defaults_initialized_at)
            values (:bookId, current_timestamp)
            on conflict (book_id) do nothing
            """, nativeQuery = true)
    int insertInitializedIfMissing(@Param("bookId") UUID bookId);
}
