import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import LoginPage from "@/app/login/page";
import { renderWithClient } from "@/test/test-utils";

const authApi = vi.hoisted(() => ({ login: vi.fn(), fetchSession: vi.fn(), logout: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: navigation.replace }) }));

/** The page is an async server component; render whatever it resolves to. */
async function renderLoginPage(searchParams: { reason?: string } = {}) {
  return renderWithClient(await LoginPage({ searchParams: Promise.resolve(searchParams) }));
}

describe("página de login", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  test("apresenta a identidade do produto e uma única ação principal", async () => {
    await renderLoginPage();

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Dê forma à sua história.");
    expect(
      screen.getByText("Organize manuscritos, cenas, personagens e ideias em um espaço criado para escrever."),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("Bem-vindo de volta");
    expect(screen.getByText("Entre para continuar de onde parou.")).toBeInTheDocument();

    // One primary action, and no fake social or sign-up buttons alongside it.
    const buttons = screen.getAllByRole("button").map((button) => button.textContent);
    expect(buttons).toContain("Entrar");
    expect(buttons.join(" ")).not.toMatch(/Google|Apple|SSO|Criar conta|Cadastr|Esqueci/i);
  });

  test("prioriza o formulário no mobile e esconde a arte decorativa", async () => {
    const { container } = await renderLoginPage();

    const artwork = container.querySelector("svg");
    expect(artwork).not.toBeNull();
    // Decorative only: it carries no meaning a reader would miss.
    expect(artwork).toHaveAttribute("aria-hidden", "true");
    // Absent until the viewport is wide; on a phone the form is what is left.
    expect(artwork).toHaveClass("hidden", "lg:block");

    // The form itself is never behind a breakpoint.
    expect(screen.getByLabelText("Email")).toBeVisible();
    expect(screen.getByLabelText("Senha")).toBeVisible();
    expect(screen.getByRole("button", { name: "Entrar" })).toBeVisible();
  });

  test("o formulário faz parte da página, não de um subtree só do cliente", async () => {
    // Guards the regression: reading ?reason with useSearchParams pushed the form out of the
    // server-rendered HTML, so /login painted once without any form.
    await renderLoginPage();
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
  });

  test("mostra o aviso de sessão expirada quando o parâmetro chega na URL", async () => {
    await renderLoginPage({ reason: "expired" });
    expect(screen.getByRole("status")).toHaveTextContent("Sua sessão expirou. Entre novamente para continuar.");
  });
});
