import { BookWorkspace } from "@/features/workspace/components/book-workspace";

export default async function BookWorkspacePage({ params }: { params: Promise<{ bookId: string }> }) {
  const { bookId } = await params;

  return <BookWorkspace bookId={bookId} />;
}
