import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import RegisterPage from "@/app/register/page";
import { renderWithClient } from "@/test/test-utils";

const authApi = vi.hoisted(() => ({ register: vi.fn(), fetchSession: vi.fn(), logout: vi.fn(), login: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: navigation.replace }) }));

describe("página de cadastro", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  test("apresenta o formulário de cadastro e um link de volta ao login", () => {
    renderWithClient(<RegisterPage />);

    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("Crie sua conta");
    expect(screen.getByLabelText("Nome de exibição")).toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
    expect(screen.getByLabelText("Senha")).toBeInTheDocument();
    expect(screen.getByLabelText("Confirme a senha")).toBeInTheDocument();
    expect(screen.getByLabelText("Perfil principal")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Criar conta" })).toBeInTheDocument();

    const backLink = screen.getByRole("link", { name: "Entrar" });
    expect(backLink).toHaveAttribute("href", "/login");
  });

  test("esconde a arte decorativa no mobile, como a tela de login", () => {
    const { container } = renderWithClient(<RegisterPage />);

    const artwork = container.querySelector("svg");
    expect(artwork).not.toBeNull();
    expect(artwork).toHaveClass("hidden", "lg:block");
  });
});
