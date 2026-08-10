import type { Book, BookStatus } from "@/features/books/types";

const statusPriority: Record<BookStatus, number> = {
  WRITING: 0,
  REVISING: 1,
  PLANNING: 2,
  FINISHED: 3,
  ARCHIVED: 4,
};

type SortableBook = Pick<Book, "status"> & Partial<Pick<Book, "createdAt" | "updatedAt">>;

export function sortBooksForLibrary<TBook extends SortableBook>(books: TBook[]) {
  return books
    .map((book, index) => ({ book, index }))
    .sort((left, right) => {
      const statusDifference = getStatusPriority(left.book.status) - getStatusPriority(right.book.status);
      if (statusDifference !== 0) {
        return statusDifference;
      }

      const leftDate = getBookSortTimestamp(left.book);
      const rightDate = getBookSortTimestamp(right.book);

      if (leftDate !== null && rightDate !== null && leftDate !== rightDate) {
        return rightDate - leftDate;
      }

      if (leftDate !== null && rightDate === null) {
        return -1;
      }

      if (leftDate === null && rightDate !== null) {
        return 1;
      }

      return left.index - right.index;
    })
    .map(({ book }) => book);
}

function getStatusPriority(status: BookStatus) {
  return statusPriority[status] ?? Number.MAX_SAFE_INTEGER;
}

function getBookSortTimestamp(book: SortableBook) {
  const rawDate = book.updatedAt ?? book.createdAt;
  if (!rawDate) {
    return null;
  }

  const timestamp = Date.parse(rawDate);
  return Number.isNaN(timestamp) ? null : timestamp;
}
