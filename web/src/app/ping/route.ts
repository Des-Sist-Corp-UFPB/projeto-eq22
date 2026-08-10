export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({
    status: "ok",
    service: "eq22",
    timestamp: new Date().toISOString(),
  });
}