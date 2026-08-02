import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { LoginForm } from "@/features/auth/components/login-form";
import { renderWithClient } from "@/test/test-utils";
import { ApiError } from "@/lib/api/client";

const authApi = vi.hoisted(() => ({ login: vi.fn(), fetchSession: vi.fn(), logout: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: navigation.replace }) }));

const session = {
  user: { displayName: "Autor A", email: "autor-a@iwrite.local" },
  activeWorkspace: { name: "Espaço do Autor A", role: "OWNER" },
};

function fillCredentials(email = "autor-a@iwrite.local", password = "senha-correta") {
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: email } });
  fireEvent.change(screen.getByLabelText("Senha"), { target: { value: password } });
}

function submit() {
  fireEvent.click(screen.getByRole("button", { name: "Entrar" }));
}

describe("LoginForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authApi.login.mockResolvedValue(session);
  });

  test("expõe labels visíveis e autocomplete esperados por gerenciadores de senha", () => {
    renderWithClient(<LoginForm />);

    const email = screen.getByLabelText("Email");
    const password = screen.getByLabelText("Senha");

    expect(email).toHaveAttribute("type", "email");
    expect(email).toHaveAttribute("autocomplete", "email");
    expect(password).toHaveAttribute("type", "password");
    expect(password).toHaveAttribute("autocomplete", "current-password");
  });

  test("alterna a visibilidade da senha com nome acessível", () => {
    renderWithClient(<LoginForm />);

    const password = screen.getByLabelText("Senha");
    const toggle = screen.getByRole("button", { name: /senha/i });

    expect(toggle).toHaveAccessibleName("Mostrar senha");
    fireEvent.click(toggle);
    expect(password).toHaveAttribute("type", "text");
    expect(screen.getByRole("button", { name: /senha/i })).toHaveAccessibleName("Ocultar senha");

    fireEvent.click(screen.getByRole("button", { name: /senha/i }));
    expect(password).toHaveAttribute("type", "password");
  });

  test("valida campos no cliente antes de chamar a API", async () => {
    renderWithClient(<LoginForm />);

    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("Informe seu email.");

    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "sem-arroba" } });
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("Informe um email válido.");

    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "autor-a@iwrite.local" } });
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("Informe sua senha.");

    expect(authApi.login).not.toHaveBeenCalled();
  });

  test("entra e redireciona para a biblioteca", async () => {
    renderWithClient(<LoginForm />);

    fillCredentials();
    submit();

    await waitFor(() => expect(authApi.login).toHaveBeenCalledWith("autor-a@iwrite.local", "senha-correta"));
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/library"));
  });

  test("não envia duas vezes enquanto a primeira tentativa está em voo", async () => {
    let releaseLogin: ((value: typeof session) => void) | undefined;
    authApi.login.mockReturnValueOnce(new Promise((resolve) => {
      releaseLogin = resolve;
    }));

    renderWithClient(<LoginForm />);
    fillCredentials();
    submit();

    const button = await screen.findByRole("button", { name: "Entrando…" });
    expect(button).toBeDisabled();

    fireEvent.click(button);
    fireEvent.submit(button.closest("form")!);

    releaseLogin?.(session);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalled());
    expect(authApi.login).toHaveBeenCalledTimes(1);
  });

  test("credenciais inválidas mostram um erro que não distingue email de senha", async () => {
    authApi.login.mockRejectedValue(new ApiError("qualquer coisa vinda do backend", 401));

    renderWithClient(<LoginForm />);
    fillCredentials("autor-a@iwrite.local", "senha-errada");
    submit();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Não foi possível entrar. Confira seus dados e tente novamente.");
    expect(alert.textContent).not.toContain("autor-a@iwrite.local");
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  test("backend indisponível é reportado como indisponibilidade, não como credencial", async () => {
    authApi.login.mockRejectedValue(new TypeError("Failed to fetch"));

    renderWithClient(<LoginForm />);
    fillCredentials();
    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não conseguimos acessar o IWrite agora. Tente novamente em instantes.",
    );
  });

  test("anuncia sessão expirada quando chega redirecionado por expiração", () => {
    renderWithClient(<LoginForm expired />);

    expect(screen.getByRole("status")).toHaveTextContent("Sua sessão expirou. Entre novamente para continuar.");
  });
});
