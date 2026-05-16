import { BookWorkspace } from "@/features/workspace/components/book-workspace";

type BookWorkspacePageProps = {
  params: Promise<{ bookId: string }>;
  searchParams: Promise<{ sceneId?: string | string[] }>;
};

export default async function BookWorkspacePage({ params, searchParams }: BookWorkspacePageProps) {
  const { bookId } = await params;
  const { sceneId } = await searchParams;
  const initialSceneId = typeof sceneId === "string" && sceneId.trim() ? sceneId : undefined;

  return <BookWorkspace bookId={bookId} initialSceneId={initialSceneId} />;
}
