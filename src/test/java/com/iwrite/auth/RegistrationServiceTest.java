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
}
