import { Card } from "@/components/ui/card";

type DashboardMetricCardProps = {
  label: string;
  value: string;
  helper?: string;
};

export function DashboardMetricCard({ label, value, helper }: DashboardMetricCardProps) {
  return (
    <Card className="p-4 transition-[transform,background-color,box-shadow] duration-150 ease-out hover:scale-[1.01] hover:bg-white hover:shadow-sm hover:shadow-zinc-200/70">
      <p className="text-xs font-medium uppercase text-zinc-500">{label}</p>
      <p className="mt-2 text-2xl font-semibold text-zinc-950">{value}</p>
      {helper ? <p className="mt-1 text-sm text-zinc-500">{helper}</p> : null}
    </Card>
  );
}
