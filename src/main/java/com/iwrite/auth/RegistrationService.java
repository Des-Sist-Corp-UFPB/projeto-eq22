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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creates a user's personal workspace end to end: {@code User}, {@code UserCredential}, a personal
 * {@code Tenant}, an {@code OWNER} {@code TenantMembership}, and the primary {@code UserPersona} —
 * one transaction, so a failure at any step leaves nothing behind. Session establishment is not
 * part of this service: {@link AuthController#register} re-authenticates through the same
 * {@code AuthenticationManager} path {@link AuthController#login} uses, once this method returns.
 */
@Service
public class RegistrationService {

    // Same shape as the existing recipient-email check in BookCollaborationInvitationService: kept
    // as its own small copy rather than shared, since the two validate different bounded concepts
    // (an account's login email vs. an invitation's recipient email) that only look alike today.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository membershipRepository;
    private final UserPersonaRepository personaRepository;
    private final PasswordEncoder passwordEncoder;
    private final IanaZoneIdValidator timeZoneValidator;

    public RegistrationService(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            TenantRepository tenantRepository,
            TenantMembershipRepository membershipRepository,
            UserPersonaRepository personaRepository,
            PasswordEncoder passwordEncoder,
            IanaZoneIdValidator timeZoneValidator
    ) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.personaRepository = personaRepository;
        this.passwordEncoder = passwordEncoder;
        this.timeZoneValidator = timeZoneValidator;
    }

    @Transactional
    public void register(RegisterRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        validateEmailFormat(email);
        validatePassword(request.password(), request.passwordConfirmation());
        String displayName = request.displayName().trim();
        UserPersonaType persona = parsePersona(request.primaryPersona());
        String timeZoneId = validateTimeZone(request.timeZone());

        // Fast, cheap precheck for the ordinary sequential-duplicate case: refuses before bcrypt
        // ever runs. The saveAndFlush below is what actually closes the concurrent race.
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException(RegistrationMessages.EMAIL_ALREADY_IN_USE);
        }

        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setTimeZoneId(timeZoneId);

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // Two requests passed the precheck together; the unique constraint on users.email is
            // the actual arbiter. Nothing else in this method has run yet, so there is nothing to
            // roll back beyond this one failed insert.
            throw new ConflictException(RegistrationMessages.EMAIL_ALREADY_IN_USE);
        }

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(request.password()));
        credentialRepository.save(credential);

        Tenant tenant = new Tenant();
        tenant.setName("Espaço de " + displayName);
        tenant.setDefaultTimeZoneId(timeZoneId);
        tenantRepository.save(tenant);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(tenant);
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        membershipRepository.save(membership);

        UserPersona userPersona = new UserPersona();
        userPersona.setUserId(user.getId());
        userPersona.setPersona(persona);
        userPersona.setPrimary(true);
        personaRepository.save(userPersona);
    }

    private void validateEmailFormat(String email) {
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new BadRequestException(RegistrationMessages.INVALID_EMAIL);
        }
    }

    private void validatePassword(String password, String confirmation) {
        if (!password.equals(confirmation)) {
            throw new BadRequestException(RegistrationMessages.PASSWORD_CONFIRMATION_MISMATCH);
        }
        if (!PasswordPolicy.isValid(password)) {
            throw new BadRequestException(RegistrationMessages.WEAK_PASSWORD);
        }
    }

    private UserPersonaType parsePersona(String rawPersona) {
        try {
            return UserPersonaType.valueOf(rawPersona.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(RegistrationMessages.INVALID_PERSONA);
        }
    }

    private String validateTimeZone(String rawTimeZone) {
        try {
            ZoneId zoneId = timeZoneValidator.validate(rawTimeZone);
            return zoneId.getId();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(RegistrationMessages.INVALID_TIME_ZONE);
        }
    }
}
