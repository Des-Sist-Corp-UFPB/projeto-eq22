import type { Metadata } from "next";
import "./globals.css";
import { QueryProvider } from "@/components/providers/query-provider";
import { UmamiAnalytics } from "@/lib/analytics/umami-analytics";

export const metadata: Metadata = {
  title: "IWrite",
  description: "Book writing workspace",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="pt-BR">
      <body>
        <UmamiAnalytics />
        <QueryProvider>{children}</QueryProvider>
      </body>
    </html>
  );
}
