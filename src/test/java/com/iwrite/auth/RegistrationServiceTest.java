package com.iwrite.auth;

import com.iwrite.auth.dto.RegisterRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.timezone.IanaZoneIdValidator;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserCredential;
import com.iwrite.user.entity.UserPersona;
import com.iwrite.user.entity.UserPersonaType;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserPersonaRepository;
import com.iwrite.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final String RAW_PASSWORD = "senha-valida-1";
    private static final String HASH = "{bcrypt}$2a$hashed";

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserCredentialRepository credentialRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private TenantMembershipRepository membershipRepository;
    @Mock
    private UserPersonaRepository personaRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private IanaZoneIdValidator timeZoneValidator;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(
                userRepository, credentialRepository, tenantRepository, membershipRepository,
                personaRepository, passwordEncoder, timeZoneValidator);
    }

    private RegisterRequest validRequest() {
        return new RegisterRequest("Nova Autora", " Nova@IWrite.local ", RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");
    }

    private void stubHappyPathCollaborators() {
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));
        lenient().when(userRepository.findByEmail("nova@iwrite.local")).thenReturn(Optional.empty());
        lenient().when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(HASH);
        lenient().when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            // Simulates the id a real saveAndFlush would assign via @GeneratedValue.
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            return user;
        });
    }

    @Test
    void cadastroCompletoCriaAsCincoEntidadesNaOrdemEsperada() {
        stubHappyPathCollaborators();

        service.register(validRequest());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("nova@iwrite.local");
        assertThat(savedUser.getDisplayName()).isEqualTo("Nova Autora");
        assertThat(savedUser.getTimeZoneId()).isEqualTo("America/Sao_Paulo");

        ArgumentCaptor<UserCredential> credentialCaptor = ArgumentCaptor.forClass(UserCredential.class);
        verify(credentialRepository).save(credentialCaptor.capture());
        assertThat(credentialCaptor.getValue().getPasswordHash()).isEqualTo(HASH);
        assertThat(credentialCaptor.getValue().getUserId()).isEqualTo(savedUser.getId());

        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getDefaultTimeZoneId()).isEqualTo("America/Sao_Paulo");

        ArgumentCaptor<TenantMembership> membershipCaptor = ArgumentCaptor.forClass(TenantMembership.class);
        verify(membershipRepository).save(membershipCaptor.capture());
        assertThat(membershipCaptor.getValue().getRole()).isEqualTo(TenantMembershipRole.OWNER);
        assertThat(membershipCaptor.getValue().getUser()).isSameAs(savedUser);
        assertThat(membershipCaptor.getValue().getTenant()).isSameAs(tenantCaptor.getValue());

        ArgumentCaptor<UserPersona> personaCaptor = ArgumentCaptor.forClass(UserPersona.class);
        verify(personaRepository).save(personaCaptor.capture());
        assertThat(personaCaptor.getValue().getPersona()).isEqualTo(UserPersonaType.WRITER);
        assertThat(personaCaptor.getValue().isPrimary()).isTrue();
        assertThat(personaCaptor.getValue().getUserId()).isEqualTo(savedUser.getId());
    }

    @Test
    void cadastroCompletoEncodaApenasASenhaNuncaAConfirmacao() {
        stubHappyPathCollaborators();

        service.register(validRequest());

        verify(passwordEncoder).encode(RAW_PASSWORD);
    }

    /** Also proves passwordConfirmation never reaches persistence: the only thing this method ever
     *  hands to {@code passwordEncoder.encode} is {@code request.password()}, and a mismatch never
     *  falls through to that call or to any repository write. */
    @Test
    void confirmacaoDivergenteFalhaAntesDeQualquerEscritaNoBanco() {
        RegisterRequest request = new RegisterRequest("Nova", "nova@iwrite.local", RAW_PASSWORD, "outra-senha-1", "WRITER", "America/Sao_Paulo");
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.PASSWORD_CONFIRMATION_MISMATCH);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, passwordEncoder);
    }

    @Test
    void senhaForaDaPoliticaFalhaAntesDeQualquerEscritaNoBanco() {
        RegisterRequest request = new RegisterRequest("Nova", "nova@iwrite.local", "curta1", "curta1", "WRITER", "America/Sao_Paulo");
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.WEAK_PASSWORD);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository);
    }

    // Codex P2 (round 6, #149): a password over PasswordPolicy.MAX_UTF8_BYTES must fail the same
    // stable WEAK_PASSWORD path, before bcrypt or any write — see PasswordPolicyTest for the exact
    // byte-boundary coverage.
    @Test
    void senhaAcimaDoLimiteDeBytesUtf8FalhaAntesDeQualquerEscritaOuBcrypt() {
        String tooLong = "a1" + "b".repeat(71); // 73 UTF-8 bytes
        RegisterRequest request = new RegisterRequest("Nova", "nova@iwrite.local", tooLong, tooLong, "WRITER", "America/Sao_Paulo");
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.WEAK_PASSWORD);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, passwordEncoder);
    }

    // Codex P2 (fresh finding, round 7, #149): String.getBytes(UTF_8) silently substitutes an
    // unpaired surrogate with the same replacement byte instead of rejecting it — PasswordPolicy now
    // rejects it outright via BcryptInputPolicy (see PasswordPolicyTest/BcryptInputPolicyTest for the
    // exhaustive encoding coverage), so this must fail the same stable WEAK_PASSWORD path, before
    // bcrypt or any write.
    @Test
    void senhaComSurrogateIsoladoFalhaAntesDeQualquerEscritaOuBcrypt() {
        String malformed = "abcdefgh1" + '\uD800';
        RegisterRequest request = new RegisterRequest("Nova", "nova@iwrite.local", malformed, malformed, "WRITER", "America/Sao_Paulo");
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.WEAK_PASSWORD);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, passwordEncoder);
    }

    @Test
    void personaInvalidaFalhaAntesDeQualquerEscritaNoBanco() {
        RegisterRequest request = new RegisterRequest("Nova", "nova@iwrite.local", RAW_PASSWORD, RAW_PASSWORD, "PROTAGONISTA", "America/Sao_Paulo");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_PERSONA);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, timeZoneValidator);
    }

    @Test
    void timezoneInvalidoFalhaAntesDeQualquerEscritaNoBanco() {
        RegisterRequest request = new RegisterRequest("Nova", "nova@iwrite.local", RAW_PASSWORD, RAW_PASSWORD, "WRITER", "nao-e-um-fuso");
        when(timeZoneValidator.validate("nao-e-um-fuso")).thenThrow(new IllegalArgumentException("bad zone"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_TIME_ZONE);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository);
    }

    @Test
    void emailJaCadastradoRecusaAntesDeGravarQualquerCoisa() {
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(userRepository.findByEmail("nova@iwrite.local")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> service.register(validRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage(RegistrationMessages.EMAIL_ALREADY_IN_USE);

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(credentialRepository, tenantRepository, membershipRepository, personaRepository);
    }

    @Test
    void violacaoConcorrenteDeUnicidadeViraConflitoSemContinuarAsProximasEtapas() {
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(userRepository.findByEmail("nova@iwrite.local")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> service.register(validRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage(RegistrationMessages.EMAIL_ALREADY_IN_USE);

        // The race was caught at the very first write: nothing downstream may ever be attempted.
        verifyNoInteractions(credentialRepository, tenantRepository, membershipRepository, personaRepository);
    }

    @Test
    void falhaEmUmaEtapaPosteriorPropagaSemSerEngolida() {
        stubHappyPathCollaborators();
        when(credentialRepository.save(any(UserCredential.class))).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.register(validRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        // Steps after the one that failed are never reached — @Transactional's rollback is driven
        // by this exception actually propagating out of the method, not being swallowed.
        verifyNoInteractions(tenantRepository, membershipRepository, personaRepository);
    }

    @Test
    void displayNameCom255CaracteresEAceito() {
        stubHappyPathCollaborators();
        String displayName = "A".repeat(255);
        RegisterRequest request = new RegisterRequest(displayName, " Nova@IWrite.local ", RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        service.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getDisplayName()).hasSize(255);
    }

    @Test
    void displayNameCom256CaracteresRetorna400SemGravarNada() {
        String displayName = "A".repeat(256);
        RegisterRequest request = new RegisterRequest(displayName, "nova@iwrite.local", RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.DISPLAY_NAME_TOO_LONG);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, timeZoneValidator);
    }

    @Test
    void nomeDeTenantDerivadoNuncaExcede255CaracteresMesmoComDisplayNameNoLimite() {
        stubHappyPathCollaborators();
        // 254 chars: "Espaço de " (10 chars) + this displayName is 264 chars, forcing truncation
        // of the derived tenant name while the user's own displayName stays whole.
        String displayName = "A".repeat(254);
        RegisterRequest request = new RegisterRequest(displayName, " Nova@IWrite.local ", RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        service.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getDisplayName()).hasSize(254);

        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(tenantCaptor.capture());
        String tenantName = tenantCaptor.getValue().getName();
        assertThat(tenantName.codePointCount(0, tenantName.length())).isLessThanOrEqualTo(255);
        assertThat(tenantName).startsWith("Espaço de ");
    }

    @Test
    void deriveTenantNameTruncaPorCodePointsSemCortarSurrogatePair() {
        // U+1F600 ("😀") is a surrogate pair in UTF-16: 2 chars, 1 code point. 250 of them is 250
        // code points (well past the 245 available after the 10-char prefix), so truncation must
        // land on a code point boundary or the result would contain half a surrogate pair.
        String emojiDisplayName = "😀".repeat(250);

        String tenantName = RegistrationService.deriveTenantName(emojiDisplayName);

        assertThat(tenantName.codePointCount(0, tenantName.length())).isEqualTo(255);
        assertThat(tenantName).isEqualTo("Espaço de " + "😀".repeat(245));
    }

    @Test
    void email255CaracteresEAceito() {
        stubHappyPathCollaborators();
        String longLocalPart = "n" + "a".repeat(241);
        String email = longLocalPart + "@iwrite.local"; // 255 chars total
        assertThat(email).hasSize(255);
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        RegisterRequest request = new RegisterRequest("Nova Autora", email, RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        service.register(request);

        verify(userRepository).saveAndFlush(any(User.class));
    }

    @Test
    void email256CaracteresRetorna400SemGravarNada() {
        String longLocalPart = "n" + "a".repeat(242);
        String email = longLocalPart + "@iwrite.local"; // 256 chars total
        assertThat(email).hasSize(256);
        RegisterRequest request = new RegisterRequest("Nova Autora", email, RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.EMAIL_TOO_LONG);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, timeZoneValidator);
    }

    // Codex P3 (round 5): NUL and other control code points are not \s, so EMAIL_PATTERN alone lets
    // them through; the later users.email insert (Postgres varchar) rejects NUL outright, which
    // without this check would surface as a generic 500 rather than a validation 400. Control
    // characters are built via (char) casts rather than string literals, so the raw byte never has
    // to round-trip through source-level escaping.

    @Test
    void emailComNulEmbutidoRetorna400SemGravarNada() {
        String email = "a" + (char) 0x0000 + "@iwrite.local";
        RegisterRequest request = new RegisterRequest("Nova Autora", email, RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_EMAIL);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, timeZoneValidator);
    }

    @Test
    void emailComSohEmbutidoRetorna400SemGravarNada() {
        String email = "a" + (char) 0x0001 + "@iwrite.local";
        RegisterRequest request = new RegisterRequest("Nova Autora", email, RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_EMAIL);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, timeZoneValidator);
    }

    @Test
    void emailComControleC1EmbutidoRetorna400SemGravarNada() {
        // U+0080 <control> — a C1 control character, not matched by \s or by ASCII-range checks.
        String email = "a" + (char) 0x0080 + "@iwrite.local";
        RegisterRequest request = new RegisterRequest("Nova Autora", email, RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_EMAIL);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, timeZoneValidator);
    }

    // NUL sitting at either edge of the raw email is stripped by EmailNormalizer's trim() before
    // this check ever runs — real coverage of the finding needs the control character embedded in
    // the middle, as above; this pins that the pre-existing whitespace exclusion (\s) still works.
    @Test
    void emailComTabInternoRetorna400SemGravarNada() {
        String email = "a" + (char) 0x0009 + "b@iwrite.local";
        RegisterRequest request = new RegisterRequest("Nova Autora", email, RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_EMAIL);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, timeZoneValidator);
    }

    // Round 5 follow-up: the finding covers "any code point Character.isISOControl agrees on",
    // not just the local part — containsControlCharacter scans the whole normalized email, so a
    // control character sitting after the '@' must be caught the same way.
    @Test
    void emailComControleNaParteDeDominioRetorna400SemGravarNada() {
        String email = "a@iwrite" + (char) 0x0000 + ".local";
        RegisterRequest request = new RegisterRequest("Nova Autora", email, RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_EMAIL);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, timeZoneValidator);
    }

    // ASCII-only policy (#149 review, see EmailNormalizer): Java's toLowerCase(Locale.ROOT) and
    // PostgreSQL's lower() are not guaranteed to canonicalize every Unicode code point identically,
    // so this slice restricts account emails to ASCII outright rather than risk that divergence.
    // A non-ASCII email must be rejected the same way as any other malformed email — before any
    // lookup, bcrypt call or write — never accepted as it would have been pre-review.
    @Test
    void emailUnicodeSemCaracteresDeControleEhRecusadoPelaPoliticaAsciiSomente() {
        String email = "usuária@iwrite.local";
        RegisterRequest request = new RegisterRequest("Nova Autora", email, RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_EMAIL);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository, timeZoneValidator);
    }

    // Codex P3 (round 5): same reasoning, applied to displayName ahead of the users.display_name
    // insert. The finding's own example (trailing NUL) is actually stripped by
    // RegistrationService#register's displayName.trim() before validateDisplayName ever sees it —
    // these use an embedded control character instead, which trim() cannot remove, to exercise the
    // real defect.

    @Test
    void displayNameComNulEmbutidoRetorna400SemGravarNada() {
        String displayName = "An" + (char) 0x0000 + "a";
        RegisterRequest request = new RegisterRequest(displayName, "nova@iwrite.local", RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_DISPLAY_NAME);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository);
    }

    @Test
    void displayNameComControleSohEmbutidoRetorna400SemGravarNada() {
        String displayName = "An" + (char) 0x0001 + "a";
        RegisterRequest request = new RegisterRequest(displayName, "nova@iwrite.local", RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_DISPLAY_NAME);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository);
    }

    @Test
    void displayNameComNewlineInternoRetorna400SemGravarNada() {
        String displayName = "An" + (char) 0x000A + "a";
        RegisterRequest request = new RegisterRequest(displayName, "nova@iwrite.local", RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_DISPLAY_NAME);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository);
    }

    @Test
    void displayNameComTabInternoRetorna400SemGravarNada() {
        String displayName = "An" + (char) 0x0009 + "a";
        RegisterRequest request = new RegisterRequest(displayName, "nova@iwrite.local", RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(RegistrationMessages.INVALID_DISPLAY_NAME);

        verifyNoInteractions(userRepository, credentialRepository, tenantRepository, membershipRepository, personaRepository);
    }

    @Test
    void displayNameComEmojiEZwjContinuaAceito() {
        // Category Cf (ZERO WIDTH JOINER, U+200D) is not Cc/isISOControl — legitimate emoji
        // sequences like family emoji must keep working.
        stubHappyPathCollaborators();
        String displayName = "👩‍👩‍👧‍👦";
        RegisterRequest request = new RegisterRequest(displayName, " Nova@IWrite.local ", RAW_PASSWORD, RAW_PASSWORD, "writer", "America/Sao_Paulo");

        service.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getDisplayName()).isEqualTo(displayName);
    }

    @Test
    void violacaoDeIntegridadeNaoRelacionadaAUkUsersEmailNaoViraConflito() {
        lenient().when(timeZoneValidator.validate("America/Sao_Paulo")).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(userRepository.findByEmail("nova@iwrite.local")).thenReturn(Optional.empty());
        DataIntegrityViolationException unrelated = new DataIntegrityViolationException("chk_some_other_constraint");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(unrelated);

        assertThatThrownBy(() -> service.register(validRequest()))
                .isSameAs(unrelated);

        // Never reported as a duplicate email, and nothing downstream is attempted either way.
        verifyNoInteractions(credentialRepository, tenantRepository, membershipRepository, personaRepository);
    }
}
