import { EmptyState } from "@/components/ui/empty-state";

type SceneEmptyStateProps = {
  title: string;
  description: string;
};

export function SceneEmptyState({ title, description }: SceneEmptyStateProps) {
  return (
    <section className="flex h-full overflow-y-auto p-6">
      <EmptyState className="m-auto max-w-md" title={title} description={description} />
    </section>
  );
}
