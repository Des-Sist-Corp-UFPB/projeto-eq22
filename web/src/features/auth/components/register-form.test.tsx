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

  test("recusa senha sem nenhum dígito, mesmo longa", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ password: "somente-letras-aqui", passwordConfirmation: "somente-letras-aqui" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A senha deve ter ao menos 10 caracteres, incluindo letras e números.",
    );
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("recusa senha sem nenhuma letra, mesmo longa", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ password: "1234567890123", passwordConfirmation: "1234567890123" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A senha deve ter ao menos 10 caracteres, incluindo letras e números.",
    );
    expect(authApi.register).not.toHaveBeenCalled();
  });

  // The backend policy (PasswordPolicy.isValid) tests Unicode letters/digits via
  // Character.isLetter/Character.isDigit, not [a-zA-Z]/[0-9] — the client must accept the same
  // passwords the server would, or a legitimate password gets rejected before it ever reaches the API.
  test("aceita senha com letra acentuada, sem recusar no cliente", async () => {
    // No ASCII a-z/A-Z at all — only accented letters — so the old [a-zA-Z] regex would have
    // rejected this as "no letter" even though it plainly has letters.
    const password = "áéíóúâêîôû12";
    renderWithClient(<RegisterForm />);
    fillForm({ password, passwordConfirmation: password });

    submit();

    await waitFor(() => expect(authApi.register).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("aceita senha com dígito Unicode não ASCII, sem recusar no cliente", async () => {
    // U+0661 ARABIC-INDIC DIGIT ONE: Character.isDigit and \p{Nd} both recognize it, [0-9] does not.
    const password = "senha-valida-١aaa";
    renderWithClient(<RegisterForm />);
    fillForm({ password, passwordConfirmation: password });

    submit();

    await waitFor(() => expect(authApi.register).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  // Same code points PasswordPolicyTest uses on the backend — both sides must agree on exactly the
  // same examples. Outside the BMP, so each is a surrogate pair in JS too; only a Unicode-aware
  // (`u` flag) regex reads the pair back as one code point instead of two lone surrogates.
  test("aceita letra fora do BMP como único caractere classificado como letra", async () => {
    // U+10400 DESERET CAPITAL LETTER LONG A — no BMP letters anywhere else in the password.
    const password = "123456789𐐀";
    renderWithClient(<RegisterForm />);
    fillForm({ password, passwordConfirmation: password });

    submit();

    await waitFor(() => expect(authApi.register).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("aceita dígito fora do BMP como único caractere classificado como dígito", async () => {
    // U+1D7CE MATHEMATICAL BOLD DIGIT ZERO — no BMP digits anywhere else in the password.
    const password = "abcdefghij𝟎";
    renderWithClient(<RegisterForm />);
    fillForm({ password, passwordConfirmation: password });

    submit();

    await waitFor(() => expect(authApi.register).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  // Codex P3 (round 5): password.length counts UTF-16 code units, so a supplementary-plane
  // character inflates the apparent length by one. Same examples as PasswordPolicyTest on the
  // backend — both sides must agree on exactly the same accept/reject boundary.
  test("recusa senha com 9 code points mesmo tendo 10 unidades UTF-16 via dígito suplementar", async () => {
    // 8 BMP letters + 1 supplementary digit = 9 code points, but .length === 10.
    const password = "abcdefgh𝟎";
    expect(password.length).toBe(10);
    expect([...password].length).toBe(9);
    renderWithClient(<RegisterForm />);
    fillForm({ password, passwordConfirmation: password });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A senha deve ter ao menos 10 caracteres, incluindo letras e números.",
    );
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("aceita senha com 10 code points via dígito suplementar", async () => {
    // 9 BMP letters + 1 supplementary digit = 10 code points (.length === 11).
    const password = "abcdefghi𝟎";
    expect([...password].length).toBe(10);
    renderWithClient(<RegisterForm />);
    fillForm({ password, passwordConfirmation: password });

    submit();

    await waitFor(() => expect(authApi.register).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("recusa senha com 9 code points incluindo letra suplementar e dígito BMP", async () => {
    // 8 BMP digits + 1 supplementary letter = 9 code points, but .length === 10.
    const password = "12345678𐐀";
    expect(password.length).toBe(10);
    expect([...password].length).toBe(9);
    renderWithClient(<RegisterForm />);
    fillForm({ password, passwordConfirmation: password });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A senha deve ter ao menos 10 caracteres, incluindo letras e números.",
    );
    expect(authApi.register).not.toHaveBeenCalled();
  });

  // Codex P3 (round 5): mirrors RegistrationService's containsControlCharacter — an embedded
  // control character (NUL, TAB, newline, DEL) is not stripped by trim() and must be rejected on
  // the client with the same message the backend returns, before the request is ever sent. Control
  // characters are built via String.fromCharCode rather than string literals, so the raw byte never
  // has to round-trip through source-level escaping.
  test("recusa nome de exibição com NUL embutido no cliente", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ displayName: "Ana" + String.fromCharCode(0x0000) + "Silva" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Informe um nome de exibição válido.");
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("recusa nome de exibição com tab ou newline internos no cliente", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ displayName: "Ana" + String.fromCharCode(0x0009) + "Silva" + String.fromCharCode(0x000a) + "Costa" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Informe um nome de exibição válido.");
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("recusa nome de exibição com DEL embutido no cliente", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ displayName: "Ana" + String.fromCharCode(0x007f) + "Silva" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Informe um nome de exibição válido.");
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("aceita nome de exibição com acentos e emoji, sem recusar no cliente", async () => {
    const displayName = "José da Conceição 😀";
    renderWithClient(<RegisterForm />);
    fillForm({ displayName });

    submit();

    await waitFor(() => expect(authApi.register).toHaveBeenCalledWith(expect.objectContaining({ displayName })));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  // Same mirroring as displayName, applied to email: containsControlCharacter must catch it before
  // the "@" check, on the client, with the same message the backend returns for an invalid email.
  test("recusa email com NUL embutido no cliente", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ email: "a" + String.fromCharCode(0x0000) + "@iwrite.local" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Informe um email válido.");
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("recusa email com controle C1 embutido no cliente", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ email: "a" + String.fromCharCode(0x0080) + "@iwrite.local" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Informe um email válido.");
    expect(authApi.register).not.toHaveBeenCalled();
  });

  // ASCII-only policy (#149 review): mirrors the backend's EmailNormalizer.isAscii — Java's
  // toLowerCase(Locale.ROOT) and PostgreSQL's lower() are not guaranteed to canonicalize every
  // Unicode code point the same way, so this slice restricts account emails to ASCII. Unlike
  // displayName/password (still full Unicode), a non-ASCII email must now be recusado no cliente.
  test("recusa email com letra acentuada (política ASCII-somente)", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ email: "usuária@iwrite.local" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Informe um email válido.");
    expect(authApi.register).not.toHaveBeenCalled();
  });

  // Codex P2 (round 6, #149): javaTrim, not email.trim() — the value validated (and sent) must be
  // exactly what RegistrationService computes with Java's String.trim(), which strips every
  // leading/trailing code point <= U+0020, not just JavaScript's own whitespace definition.
  test("remove TAB/CR/LF/espaços nas bordas do email antes de validar e enviar", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ email: " \t\r\nnova@iwrite.local \t\r\n" });

    submit();

    await waitFor(() =>
      expect(authApi.register).toHaveBeenCalledWith(expect.objectContaining({ email: "nova@iwrite.local" })),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("recusa email cujo controle embutido não é removido pelo trim", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ email: "a" + String.fromCharCode(0x0009) + "b@iwrite.local" });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Informe um email válido.");
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("recusa email vazio após remover apenas espaços/controles nas bordas", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ email: " \t\r\n " });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Informe seu email.");
    expect(authApi.register).not.toHaveBeenCalled();
  });

  test("recusa nome de exibição com mais de 255 caracteres no cliente", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ displayName: "A".repeat(256) });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "O nome de exibição deve ter no máximo 255 caracteres.",
    );
    expect(authApi.register).not.toHaveBeenCalled();
  });

  // The DOM `maxlength` attribute truncates by UTF-16 code unit; an emoji like 😀 (U+1F600) is two
  // of those units for one code point, so `maxLength={255}` on the input would have cut off a paste
  // well before the real, code-point-based 255 limit `validate` enforces. The field must carry no
  // such attribute at all — the code-point count is the only limit — and the count itself must land
  // on the right boundary regardless of how many UTF-16 units the same string takes up.
  test("o campo de nome de exibição não trunca por unidade UTF-16", () => {
    renderWithClient(<RegisterForm />);
    expect(screen.getByLabelText("Nome de exibição")).not.toHaveAttribute("maxlength");
  });

  test("128 emojis podem ser inseridos integralmente, sem truncar por unidade UTF-16", async () => {
    const displayName = "😀".repeat(128); // 128 code points, 256 UTF-16 code units
    renderWithClient(<RegisterForm />);
    fillForm({ displayName });

    submit();

    await waitFor(() =>
      expect(authApi.register).toHaveBeenCalledWith(expect.objectContaining({ displayName })),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("aceita nome de exibição com exatamente 255 code points em emoji", async () => {
    const displayName = "😀".repeat(255);
    renderWithClient(<RegisterForm />);
    fillForm({ displayName });

    submit();

    await waitFor(() =>
      expect(authApi.register).toHaveBeenCalledWith(expect.objectContaining({ displayName })),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("recusa nome de exibição com 256 code points em emoji", async () => {
    renderWithClient(<RegisterForm />);
    fillForm({ displayName: "😀".repeat(256) });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "O nome de exibição deve ter no máximo 255 caracteres.",
    );
    expect(authApi.register).not.toHaveBeenCalled();
  });

  // Codex P2 (round 6, #149): mirrors the backend's PasswordPolicy.MAX_UTF8_BYTES — bcrypt silently
  // ignores any UTF-8 byte past the 72nd, so the client must reject the same boundary with a clear
  // message before ever sending the request.
  test("aceita senha com exatamente 72 bytes UTF-8", async () => {
    const password = "a1" + "b".repeat(70);
    expect(new TextEncoder().encode(password)).toHaveLength(72);
    renderWithClient(<RegisterForm />);
    fillForm({ password, passwordConfirmation: password });

    submit();

    await waitFor(() => expect(authApi.register).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("recusa senha com 73 bytes UTF-8 com mensagem clara de tamanho máximo", async () => {
    const password = "a1" + "b".repeat(71);
    expect(new TextEncoder().encode(password)).toHaveLength(73);
    renderWithClient(<RegisterForm />);
    fillForm({ password, passwordConfirmation: password });

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("A senha deve ter no máximo 72 bytes");
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
