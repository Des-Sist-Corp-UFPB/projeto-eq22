package com.iwrite.auth;

import com.iwrite.common.exception.ConflictException;
import com.iwrite.tenant.entity.TenantMembershipRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic, no-sleep coverage of {@link AuthController#sessionLock} and the private
 * session-establishment operations it guards — {@link AuthController#establishSession}, {@code
 * releaseSessionOwnerIfStillOwned} and {@code discardPartialSession} (#149 review, round 8: making
 * the ownership comparison and the invalidation it decides one atomic unit; round 9: widening {@code
 * establishSession}'s own critical section to the whole method, not just its two token writes, so a
 * concurrent flow sharing the same session can never write its own token, run {@code
 * onAuthentication}, or save its {@link org.springframework.security.core.context.SecurityContext}
 * partway through another flow's establishment or rollback decision).
 *
 * <p>Exercised directly via reflection against minimally-constructed {@link AuthController}
 * instances — {@code releaseSessionOwnerIfStillOwned} and {@code discardPartialSession} touch none of
 * its injected collaborators, only the {@link jakarta.servlet.http.HttpSession} and the lock passed
 * in; {@code establishSession} additionally needs a {@link SessionAuthenticationStrategy} and a
 * {@link SecurityContextRepository} (both interfaces, faked here to pause on a latch) but never
 * reaches its final database round trip, since that runs after the lock is released and is
 * irrelevant to these tests. The end-to-end behavior (a concurrent login's session surviving a
 * registration rollback, and vice versa) is already covered by {@link
 * RegistrationSessionOwnershipRaceIntegrationTest} and {@link
 * RegistrationWhileAuthenticatedIntegrationTest}; this class isolates the locking mechanism itself.
 */
class AuthControllerSessionOwnershipConcurrencyTest {

    private static final String TOKEN_ATTRIBUTE = "iwrite.auth.session-owner-token";

    private final AuthController controller = new AuthController(null, null, null, null, null, null, null, null);

    // sessionLock's own contract: same session -> same monitor (even across an id rotation, since a
    // servlet container mutates a session's id in place rather than replacing the instance), distinct
    // sessions -> distinct monitors (never a lock shared across unrelated sessions), and a null
    // pre-existing session -> a fresh, uncontended monitor every call (nothing else could ever be
    // racing on a session that did not exist yet).

    @Test
    void sessionLockRetornaOMesmoMonitorParaAMesmaSessaoMesmoAposMudarDeId() {
        MockHttpSession session = new MockHttpSession();
        Object lockBeforeRotation = AuthController.sessionLock(session);

        session.changeSessionId();

        assertThat(AuthController.sessionLock(session)).isSameAs(lockBeforeRotation);
    }

    @Test
    void sessionLockRetornaMonitoresDiferentesParaSessoesDiferentes() {
        Object lockA = AuthController.sessionLock(new MockHttpSession());
        Object lockB = AuthController.sessionLock(new MockHttpSession());
        assertThat(lockA).isNotSameAs(lockB);
    }

    @Test
    void sessionLockRetornaUmMonitorDescartavelParaSessaoNulaACadaChamada() {
        assertThat(AuthController.sessionLock(null)).isNotSameAs(AuthController.sessionLock(null));
    }

    // Scenario A (round 8): proves the monitor two racing flows share actually excludes concurrent
    // execution of their critical sections — the mechanism discardPartialSession's read-then-
    // invalidate and a concurrent establishSession's token write both rely on. Deterministic without
    // sleep: the assertion inside the synchronized block is not a timing guess, it is a guarantee of
    // the Java Memory Model — a second thread cannot possibly have entered its own synchronized block
    // on the same monitor while this thread is still inside its own.
    @Test
    void doisFluxosNoMesmoMonitorNuncaExecutamSuasSecoesCriticasAoMesmoTempo() throws InterruptedException {
        Object lock = new Object();
        CountDownLatch secondThreadEntered = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            synchronized (lock) {
                secondThreadEntered.countDown();
            }
        });

        long countWhileStillHeld;
        synchronized (lock) {
            second.start();
            countWhileStillHeld = secondThreadEntered.getCount();
        }

        second.join(10_000);
        assertThat(second.isAlive()).isFalse();
        assertThat(countWhileStillHeld).isEqualTo(1);
        assertThat(secondThreadEntered.await(10, TimeUnit.SECONDS)).isTrue();
    }

    // Scenario D (round 8): releaseSessionOwnerIfStillOwned and discardPartialSession are both
    // compare-and-clear against the *current* token, never the caller's own stale copy — a flow whose
    // token has since been overwritten by a concurrent flow's own establishSession must never remove
    // (release) or invalidate (discard) on that other flow's behalf.

    @Test
    void releaseNaoRemoveTokenQueJaFoiSobrescritoPorOutroFluxo() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithSession(session);
        Object lock = AuthController.sessionLock(session);
        UUID tokenFlow1 = UUID.randomUUID();
        UUID tokenFlow2 = UUID.randomUUID();

        session.setAttribute(TOKEN_ATTRIBUTE, tokenFlow1);
        session.setAttribute(TOKEN_ATTRIBUTE, tokenFlow2); // flow 2 takes ownership

        releaseSessionOwnerIfStillOwned(request, tokenFlow1, lock); // flow 1's own (stale) release

        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(tokenFlow2);

        releaseSessionOwnerIfStillOwned(request, tokenFlow2, lock); // flow 2's own release
        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isNull();
    }

    @Test
    void discardNaoInvalidaSessaoCujaPropriedadeJaPassouParaOutroFluxo() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String preexistingSessionId = session.getId();
        MockHttpServletRequest request = requestWithSession(session);
        Object lock = AuthController.sessionLock(session);
        UUID registrationToken = UUID.randomUUID();
        UUID loginToken = UUID.randomUUID();

        session.setAttribute(TOKEN_ATTRIBUTE, registrationToken);
        session.changeSessionId(); // a real rotation, same shape establishSession's own strategy call produces
        session.setAttribute(TOKEN_ATTRIBUTE, loginToken); // login takes ownership after registration

        discardPartialSession(request, preexistingSessionId, registrationToken, lock);

        // registration's rollback found a different owner and must not have invalidated anything.
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(loginToken);
    }

    @Test
    void discardInvalidaSessaoAindaPertencenteAoProprioCadastroQuandoNaoHaConcorrencia() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String preexistingSessionId = session.getId();
        MockHttpServletRequest request = requestWithSession(session);
        Object lock = AuthController.sessionLock(session);
        UUID registrationToken = UUID.randomUUID();

        session.setAttribute(TOKEN_ATTRIBUTE, registrationToken);
        session.changeSessionId();

        discardPartialSession(request, preexistingSessionId, registrationToken, lock);

        assertThat(session.isInvalid()).isTrue();
    }

    // Round 9 (#149 review): establishSession's critical section now spans the whole method — the
    // initial marker, onAuthentication, SecurityContext creation/saveContext and the final
    // reassertion — not just the two attribute writes. These four scenarios exercise that widened
    // lock directly against the real establishSession/discardPartialSession methods, using fake
    // SessionAuthenticationStrategy/SecurityContextRepository collaborators (both interfaces) that
    // pause on a latch mid-critical-section, exactly where the round-8 lock used to release.

    /** Scenario A: login marks its token and pauses (inside its own critical section, before {@code
     *  onAuthentication} completes); a concurrent registration on the same session must not be able to
     *  enter its own establishSession critical section until login's finishes. Proven by polling the
     *  registration thread's actual {@link Thread.State#BLOCKED} state — not a fixed sleep-and-hope —
     *  before releasing login. */
    @Test
    void loginPausadoDentroDeEstablishSessionBloqueiaCadastroConcorrenteAteTerminar() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithSession(session);
        Object lock = AuthController.sessionLock(session);

        CountDownLatch loginEnteredCriticalSection = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        AuthController loginController = controllerWithPausingStrategy(loginEnteredCriticalSection, releaseLogin);
        AuthController registrationController = controllerWithImmediateStrategy();

        UUID loginToken = UUID.randomUUID();
        UUID registrationToken = UUID.randomUUID();

        Thread loginThread = new Thread(() ->
                callEstablishSession(loginController, request, loginToken, lock));
        loginThread.start();

        assertThat(loginEnteredCriticalSection.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(loginToken);

        Thread registrationThread = new Thread(() ->
                callEstablishSession(registrationController, request, registrationToken, lock));
        registrationThread.start();

        // Blocked on the same monitor login still holds, not merely slow to start: registration's
        // own critical section (its token write) cannot have run yet.
        awaitState(registrationThread, Thread.State.BLOCKED);
        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(loginToken);

        releaseLogin.countDown();
        loginThread.join(10_000);
        registrationThread.join(10_000);

        assertThat(loginThread.isAlive()).isFalse();
        assertThat(registrationThread.isAlive()).isFalse();
        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(registrationToken);
    }

    /** Polls (no fixed sleep) until {@code thread} reaches {@code expected}, or fails after 10s. */
    private void awaitState(Thread thread, Thread.State expected) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != expected) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Thread never reached " + expected + ", was " + thread.getState());
            }
            Thread.sleep(5);
        }
    }

    /** Scenario B: registration establishes first and is rolled back before login ever takes
     *  ownership — the partial session must be invalidated. */
    @Test
    void cadastroEstabelecidoPrimeiroSofreRollbackAntesDeLoginAssumirInvalidaSessaoParcial() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String preexistingSessionId = session.getId();
        MockHttpServletRequest request = requestWithSession(session);
        Object lock = AuthController.sessionLock(session);
        AuthController registrationController = controllerWithImmediateStrategy();
        UUID registrationToken = UUID.randomUUID();

        callEstablishSession(registrationController, request, registrationToken, lock);
        assertThat(session.getId()).isNotEqualTo(preexistingSessionId); // the real strategy rotated it

        invokeDiscardPartialSession(registrationController, request, preexistingSessionId, registrationToken, lock);

        assertThat(session.isInvalid()).isTrue();
    }

    /** Scenario C: registration's own establishSession completes and writes its token (lock released);
     *  in the real gap between that and its rollback decision (a separate critical section — the
     *  commit attempt itself runs outside any lock), a concurrent login on the same session runs its
     *  own establishSession to completion and takes ownership; only then does registration's rollback
     *  run. It must find the current owner is login's, not its own, and leave the session alone. */
    @Test
    void loginEstabelecidoDepoisDeCadastroAssumeOwnershipERollbackAnteriorNaoInvalidaOLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String preexistingSessionId = session.getId();
        MockHttpServletRequest request = requestWithSession(session);
        Object lock = AuthController.sessionLock(session);
        AuthController registrationController = controllerWithImmediateStrategy();
        AuthController loginController = controllerWithImmediateStrategy();
        UUID registrationToken = UUID.randomUUID();
        UUID loginToken = UUID.randomUUID();

        callEstablishSession(registrationController, request, registrationToken, lock);
        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(registrationToken);

        // The real gap: registration's transaction commit attempt (and its eventual failure) happens
        // strictly after establishSession returns and strictly before discardPartialSession runs —
        // neither is inside the lock, so a concurrent login's full establishSession can run in between.
        callEstablishSession(loginController, request, loginToken, lock);
        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(loginToken);

        invokeDiscardPartialSession(registrationController, request, preexistingSessionId, registrationToken, lock);

        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(loginToken);
    }

    /** Scenario D: two unrelated sessions establish concurrently and never block each other. */
    @Test
    void sessoesDistintasEstabelecemConcorrentementeSemSeBloquear() throws Exception {
        MockHttpSession sessionA = new MockHttpSession();
        MockHttpSession sessionB = new MockHttpSession();
        MockHttpServletRequest requestA = requestWithSession(sessionA);
        MockHttpServletRequest requestB = requestWithSession(sessionB);
        Object lockA = AuthController.sessionLock(sessionA);
        Object lockB = AuthController.sessionLock(sessionB);

        CountDownLatch aEnteredCriticalSection = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AuthController controllerA = controllerWithPausingStrategy(aEnteredCriticalSection, releaseA);
        AuthController controllerB = controllerWithImmediateStrategy();
        UUID tokenA = UUID.randomUUID();
        UUID tokenB = UUID.randomUUID();

        Thread threadA = new Thread(() -> callEstablishSession(controllerA, requestA, tokenA, lockA));
        threadA.start();
        assertThat(aEnteredCriticalSection.await(10, TimeUnit.SECONDS)).isTrue();

        // B's session is unrelated: it must complete even while A is still paused mid-critical-section.
        callEstablishSession(controllerB, requestB, tokenB, lockB);
        assertThat(sessionB.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(tokenB);

        releaseA.countDown();
        threadA.join(10_000);
        assertThat(threadA.isAlive()).isFalse();
        assertThat(sessionA.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(tokenA);
    }

    // #149 review, fresh finding: establishRegistrationSession must revalidate, under the same lock
    // as its own mutation, that no concurrent flow has authenticated the shared session since
    // register()'s one-time thread-local check — never on SecurityContextHolder (this thread's own
    // copy) or a snapshot captured earlier, only the SecurityContext actually persisted on the
    // session itself.

    /** {@link AuthController#sessionAlreadyAuthenticated} reads straight off the session attribute
     *  {@link HttpSessionSecurityContextRepository} itself writes — proven here by using the real
     *  repository to save a context, exactly like {@link AuthController#establishSessionStateLocked}
     *  does, then reading it back through the method under test via reflection. */
    @Test
    void sessionAlreadyAuthenticatedDetectaContextoPersistidoPelaMesmaRepository() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithSession(session);
        Authentication authentication = new TestingAuthenticationToken(
                new IWriteUserDetails(UUID.randomUUID(), UUID.randomUUID(), "someone@iwrite.local",
                        TenantMembershipRole.OWNER, null),
                null);
        authentication.setAuthenticated(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SECURITY_CONTEXT_REPOSITORY.saveContext(context, request, new MockHttpServletResponse());

        assertThat(invokeSessionAlreadyAuthenticated(session)).isTrue();
    }

    @Test
    void sessionAlreadyAuthenticatedEhFalsoParaSessaoNulaOuSemContexto() throws Exception {
        assertThat(invokeSessionAlreadyAuthenticated(null)).isFalse();
        assertThat(invokeSessionAlreadyAuthenticated(new MockHttpSession())).isFalse();
    }

    /** The exact race the finding describes: a login has already saved its {@link SecurityContext}
     *  onto the shared session (simulating a concurrent login that finished first) before registration
     *  ever reaches {@code establishRegistrationSession}. Must throw {@link ConflictException} and —
     *  critically — must never have written registration's own ownership token, since that token is
     *  what {@code discardPartialSession}'s rollback compares against to decide whether to invalidate
     *  the (login's) session. */
    @Test
    void establishRegistrationSessionLancaConflitoQuandoSessaoJaAutenticadaSemEscreverSeuProprioToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithSession(session);
        Authentication loginAuthentication = new TestingAuthenticationToken(
                new IWriteUserDetails(UUID.randomUUID(), UUID.randomUUID(), "login-concorrente@iwrite.local",
                        TenantMembershipRole.OWNER, null),
                null);
        loginAuthentication.setAuthenticated(true);
        SecurityContext loginContext = SecurityContextHolder.createEmptyContext();
        loginContext.setAuthentication(loginAuthentication);
        SECURITY_CONTEXT_REPOSITORY.saveContext(loginContext, request, new MockHttpServletResponse());

        Object lock = AuthController.sessionLock(session);
        AuthController registrationController = controllerWithImmediateStrategy();
        UUID registrationToken = UUID.randomUUID();

        assertThatThrownBy(() -> invokeEstablishRegistrationSession(registrationController, request, registrationToken, lock))
                .isInstanceOf(ConflictException.class)
                .hasMessage(RegistrationMessages.ALREADY_AUTHENTICATED);

        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isNotEqualTo(registrationToken);
    }

    /** No concurrent authentication: registration establishes normally, exactly like {@link
     *  AuthController#establishSession} would for login on an anonymous session. */
    @Test
    void establishRegistrationSessionEstabelecidaNormalmenteSemSessaoAutenticadaConcorrente() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = requestWithSession(session);
        Object lock = AuthController.sessionLock(session);
        AuthController registrationController = controllerWithImmediateStrategy();
        UUID registrationToken = UUID.randomUUID();

        callEstablishRegistrationSession(registrationController, request, registrationToken, lock);

        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(registrationToken);
    }

    private boolean invokeSessionAlreadyAuthenticated(HttpSession session) throws Exception {
        Method method = AuthController.class.getDeclaredMethod("sessionAlreadyAuthenticated", HttpSession.class);
        method.setAccessible(true);
        return (boolean) method.invoke(controller, (Object) session);
    }

    private void invokeEstablishRegistrationSession(AuthController controller, MockHttpServletRequest request, UUID token, Object lock) throws Exception {
        Authentication authentication = new TestingAuthenticationToken(
                new IWriteUserDetails(UUID.randomUUID(), UUID.randomUUID(), "nova-conta@iwrite.local",
                        TenantMembershipRole.OWNER, null),
                null);
        Method method = AuthController.class.getDeclaredMethod(
                "establishRegistrationSession", Authentication.class, UUID.class,
                HttpServletRequest.class, HttpServletResponse.class, Object.class);
        method.setAccessible(true);
        try {
            method.invoke(controller, authentication, token, request, new MockHttpServletResponse(), lock);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e.getCause());
        }
    }

    /** Same NullPointerException-swallowing shape as {@link #callEstablishSession}: {@code
     *  currentSession} runs strictly after the lock is released and is irrelevant here. */
    private void callEstablishRegistrationSession(AuthController controller, MockHttpServletRequest request, UUID token, Object lock) {
        try {
            invokeEstablishRegistrationSession(controller, request, token, lock);
        } catch (NullPointerException expectedFromNullAuthSessionService) {
            // currentSession() ran after the lock was released, exactly as intended; ignored.
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Real default impl, same as production: only {@code saveContext} matters to these tests (it
     *  writes to the session), and reimplementing all three interface methods as no-ops would be more
     *  code than just using the real one. */
    private static final SecurityContextRepository SECURITY_CONTEXT_REPOSITORY = new HttpSessionSecurityContextRepository();

    /** Real {@link ChangeSessionIdAuthenticationStrategy}, not a no-op: scenarios B and C need an
     *  actual id rotation, exactly what production's own strategy bean does, for {@code
     *  discardPartialSession}'s id check to be meaningfully exercised rather than trivially true. */
    private AuthController controllerWithImmediateStrategy() {
        return newController(new ChangeSessionIdAuthenticationStrategy(), SECURITY_CONTEXT_REPOSITORY);
    }

    private AuthController controllerWithPausingStrategy(CountDownLatch entered, CountDownLatch release) {
        SessionAuthenticationStrategy delegate = new ChangeSessionIdAuthenticationStrategy();
        SessionAuthenticationStrategy strategy = (authentication, req, res) -> {
            delegate.onAuthentication(authentication, req, res);
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to be released mid-critical-section");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        return newController(strategy, SECURITY_CONTEXT_REPOSITORY);
    }

    private AuthController newController(SessionAuthenticationStrategy strategy, SecurityContextRepository repository) {
        return new AuthController(null, null, null, repository, strategy, null, null, null);
    }

    /** Invokes the real, private {@code establishSession} and swallows the {@link NullPointerException}
     *  from its final {@code currentSession} call (this test's {@code authSessionService} is {@code
     *  null}) — that call runs strictly after the lock is released, so it is irrelevant to every
     *  assertion here, which is only about ordering inside the critical section above it. */
    private void callEstablishSession(AuthController controller, MockHttpServletRequest request, UUID token, Object lock) {
        try {
            invokeEstablishSession(controller, request, token, lock);
        } catch (NullPointerException expectedFromNullAuthSessionService) {
            // currentSession() ran after the lock was released, exactly as intended; ignored.
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeEstablishSession(AuthController controller, MockHttpServletRequest request, UUID token, Object lock) throws Exception {
        Authentication authentication = new TestingAuthenticationToken(
                new IWriteUserDetails(UUID.randomUUID(), UUID.randomUUID(), "someone@iwrite.local",
                        TenantMembershipRole.OWNER, null),
                null);
        Method method = AuthController.class.getDeclaredMethod(
                "establishSession", Authentication.class, UUID.class,
                HttpServletRequest.class, HttpServletResponse.class, Object.class);
        method.setAccessible(true);
        try {
            method.invoke(controller, authentication, token, request, new MockHttpServletResponse(), lock);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e.getCause());
        }
    }

    private void invokeDiscardPartialSession(AuthController controller, MockHttpServletRequest request, String preexistingSessionId, UUID token, Object lock) throws Exception {
        Method method = AuthController.class.getDeclaredMethod(
                "discardPartialSession", HttpServletRequest.class, String.class, UUID.class, Object.class);
        method.setAccessible(true);
        try {
            method.invoke(controller, request, preexistingSessionId, token, lock);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private MockHttpServletRequest requestWithSession(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    private void releaseSessionOwnerIfStillOwned(MockHttpServletRequest request, UUID token, Object lock) throws Exception {
        invoke("releaseSessionOwnerIfStillOwned", request, token, lock);
    }

    private void discardPartialSession(MockHttpServletRequest request, String preexistingSessionId, UUID token, Object lock) throws Exception {
        invokeDiscardPartialSession(controller, request, preexistingSessionId, token, lock);
    }

    private void invoke(String methodName, MockHttpServletRequest request, UUID token, Object lock) throws Exception {
        Method method = AuthController.class.getDeclaredMethod(
                methodName, HttpServletRequest.class, UUID.class, Object.class);
        method.setAccessible(true);
        try {
            method.invoke(controller, request, token, lock);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
