import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { RegisterForm } from "@/features/auth/components/register-form";
import { renderWithClient } from "@/test/test-utils";
import { ApiError } from "@/lib/api/client";

const authApi = vi.hoisted(() => ({ register: vi.fn(), fetchSession: vi.fn(), logout: vi.fn(), login: vi.fn() }));
const navigation = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock("@/features/auth/api/auth-api", () => authApi);
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: navigation.replace }) }));

const session = {
  user: { displayName: "Nova Autora", email: "nova@iwrite.local" },
  activeWorkspace: { name: "Espaço de Nova Autora", role: "OWNER" },
};

const VALID_PASSWORD = "senha-valida-1";

function fillForm(overrides: Partial<Record<"displayName" | "email" | "password" | "passwordConfirmation", string>> = {}) {
  const values = {
    displayName: "Nova Autora",
    email: "nova@iwrite.local",
    password: VALID_PASSWORD,
    passwordConfirmation: VALID_PASSWORD,
    ...overrides,
  };
  fireEvent.change(screen.getByLabelText("Nome de exibição"), { target: { value: values.displayName } });
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: values.email } });
  fireEvent.change(screen.getByLabelText("Senha"), { target: { value: values.password } });
  fireEvent.change(screen.getByLabelText("Confirme a senha"), { target: { value: values.passwordConfirmation } });
}

function submit() {
  fireEvent.click(screen.getByRole("button", { name: "Criar conta" }));
}

describe("RegisterForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authApi.register.mockResolvedValue(session);
  });

  test("expõe labels visíveis e autocomplete esperados por gerenciadores de senha", () => {
    renderWithClient(<RegisterForm />);

    expect(screen.getByLabelText("Nome de exibição")).toHaveAttribute("autocomplete", "name");
    const email = screen.getByLabelText("Email");
    expect(email).toHaveAttribute("type", "email");
    expect(email).toHaveAttribute("autocomplete", "email");
    expect(screen.getByLabelText("Senha")).toHaveAttribute("autocomplete", "new-password");
    expect(screen.getByLabelText("Confirme a senha")).toHaveAttribute("autocomplete", "new-password");
    expect(screen.getByLabelText("Perfil principal")).toBeInTheDocument();
  });

  test("valida campos obrigatórios no cliente antes de chamar a API", async () => {
    renderWithClient(<RegisterForm />);

    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("Informe seu nome de exibição.");

    fireEvent.change(screen.getByLabelText("Nome de exibição"), { target: { value: "Nova Autora" } });
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("Informe seu email.");

    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "sem-arroba" } });
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent("Informe um email válido.");

    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("recusa senha fora da política no cliente", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ password: "curta1", passwordConfirmation: "curta1" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A senha deve ter ao menos 10 caracteres, incluindo letras e números.",
    );
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("recusa senha e confirmação divergentes no cliente", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ passwordConfirmation: "outra-senha-1" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("A confirmação de senha não confere.");
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("cadastra e redireciona para a biblioteca", async () => {
    renderWithClient(<RegisterForm />);
    fillForm();

    submit();

    await waitFor(() =>
      expect(authApi.register).toHaveBeenCalledWith(
        expect.objectContaining({
          displayName: "Nova Autora",
          email: "nova@iwrite.local",
          password: VALID_PASSWORD,
          passwordConfirmation: VALID_PASSWORD,
          primaryPersona: "WRITER",
        }),
      ),
    );
    await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith("/library"));
  });

  test("envia o fuso horário detectado do navegador", async () => {
    renderWithClient(<RegisterForm />);
    fillForm();

    submit();

    await waitFor(() => expect(authApi.register).toHaveBeenCalled());
    const call = authApi.register.mock.calls[0][0];
    expect(typeof call.timeZone).toBe("string");
    expect(call.timeZone.length).toBeGreaterThan(0);
  });

  test("não envia duas vezes enquanto a primeira tentativa está em voo", async () => {
    let releaseRegister: ((value: typeof session) => void) | undefined;
    authApi.register.mockReturnValueOnce(
      new Promise((resolve) => {
        releaseRegister = resolve;
      }),
    );

    renderWithClient(<RegisterForm />);
    fillForm();
    submit();

    const button = await screen.findByRole("button", { name: "Criando conta…" });
    expect(button).toBeDisabled();

    fireEvent.click(button);
    fireEvent.submit(button.closest("form")!);

    releaseRegister?.(session);
    await waitFor(() => expect(navigation.replace).toHaveBeenCalled());
    expect(authApi.register).toHaveBeenCalledTimes(1);
  });

  test("email já utilizado mostra a mensagem específica do backend", async () => {
    authApi.register.mockRejectedValue(new ApiError("Este email já está cadastrado.", 409));

    renderWithClient(<RegisterForm />);
    fillForm();
    submit();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Este email já está cadastrado.");
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  test("senha recusada pelo backend mostra a mensagem específica retornada", async () => {
    authApi.register.mockRejectedValue(
      new ApiError("A senha deve ter ao menos 10 caracteres, incluindo letras e números.", 400),
    );

    renderWithClient(<RegisterForm />);
    fillForm();
    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A senha deve ter ao menos 10 caracteres, incluindo letras e números.",
    );
  });

  test("limite de tentativas (429) orienta a aguardar, não mensagem arbitrária do backend", async () => {
    authApi.register.mockRejectedValue(new ApiError("origin bucket exhausted for 10.0.0.7", 429));

    renderWithClient(<RegisterForm />);
    fillForm();
    submit();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Muitas tentativas de cadastro. Aguarde um momento e tente novamente.");
    expect(alert.textContent).not.toContain("origin bucket");
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  test("erro 500 é reportado como indisponibilidade, não expõe detalhe do backend", async () => {
    authApi.register.mockRejectedValue(new ApiError("stack trace interno", 500));

    renderWithClient(<RegisterForm />);
    fillForm();
    submit();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Não conseguimos acessar o IWrite agora. Tente novamente em instantes.");
    expect(alert.textContent).not.toContain("stack trace");
  });

  test("falha de rede é reportada como indisponibilidade", async () => {
    authApi.register.mockRejectedValue(new TypeError("Failed to fetch"));

    renderWithClient(<RegisterForm />);
    fillForm();
    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não conseguimos acessar o IWrite agora. Tente novamente em instantes.",
    );
  });
});
