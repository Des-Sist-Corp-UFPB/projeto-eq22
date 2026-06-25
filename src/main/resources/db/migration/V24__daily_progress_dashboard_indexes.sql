create index idx_daily_progress_user_date_book
    on book_daily_writing_progress (user_id, progress_date, book_id);

create index idx_daily_progress_book_date_user
    on book_daily_writing_progress (book_id, progress_date, user_id);
