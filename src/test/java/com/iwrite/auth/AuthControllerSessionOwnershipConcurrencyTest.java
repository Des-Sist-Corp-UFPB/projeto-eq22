package com.iwrite.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, no-sleep coverage of {@link AuthController#sessionLock} and the three private
 * ownership-token operations it guards — {@code markSessionOwnerIfSessionExists}, {@code
 * releaseSessionOwnerIfStillOwned} and {@code discardPartialSession} (#149 review, round 8: making
 * the ownership comparison and the invalidation it decides one atomic unit, so a concurrent login can
 * never write its own token into the gap between a registration rollback's read and its {@code
 * invalidate()} call).
 *
 * <p>Exercised directly via reflection against a minimally-constructed {@link AuthController} — none
 * of these three methods touch any of its injected collaborators, only the {@link
 * jakarta.servlet.http.HttpSession} and the lock passed in — rather than through the full HTTP stack.
 * The end-to-end behavior (a concurrent login's session surviving a registration rollback, and vice
 * versa) is already covered by {@link RegistrationSessionOwnershipRaceIntegrationTest} and {@link
 * RegistrationWhileAuthenticatedIntegrationTest}; this class isolates the locking mechanism itself.
 */
class AuthControllerSessionOwnershipConcurrencyTest {

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

        markSessionOwnerIfSessionExists(request, tokenFlow1, lock);
        markSessionOwnerIfSessionExists(request, tokenFlow2, lock); // flow 2 takes ownership

        releaseSessionOwnerIfStillOwned(request, tokenFlow1, lock); // flow 1's own (stale) release

        assertThat(session.getAttribute("iwrite.auth.session-owner-token")).isEqualTo(tokenFlow2);

        releaseSessionOwnerIfStillOwned(request, tokenFlow2, lock); // flow 2's own release
        assertThat(session.getAttribute("iwrite.auth.session-owner-token")).isNull();
    }

    @Test
    void discardNaoInvalidaSessaoCujaPropriedadeJaPassouParaOutroFluxo() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String preexistingSessionId = session.getId();
        MockHttpServletRequest request = requestWithSession(session);
        Object lock = AuthController.sessionLock(session);
        UUID registrationToken = UUID.randomUUID();
        UUID loginToken = UUID.randomUUID();

        markSessionOwnerIfSessionExists(request, registrationToken, lock);
        session.changeSessionId(); // a real rotation, same shape establishSession's own strategy call produces
        markSessionOwnerIfSessionExists(request, loginToken, lock); // login takes ownership after registration

        discardPartialSession(request, preexistingSessionId, registrationToken, lock);

        // registration's rollback found a different owner and must not have invalidated anything.
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute("iwrite.auth.session-owner-token")).isEqualTo(loginToken);
    }

    @Test
    void discardInvalidaSessaoAindaPertencenteAoProprioCadastroQuandoNaoHaConcorrencia() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String preexistingSessionId = session.getId();
        MockHttpServletRequest request = requestWithSession(session);
        Object lock = AuthController.sessionLock(session);
        UUID registrationToken = UUID.randomUUID();

        markSessionOwnerIfSessionExists(request, registrationToken, lock);
        session.changeSessionId();

        discardPartialSession(request, preexistingSessionId, registrationToken, lock);

        assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpServletRequest requestWithSession(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    private void markSessionOwnerIfSessionExists(MockHttpServletRequest request, UUID token, Object lock) throws Exception {
        invoke("markSessionOwnerIfSessionExists", request, token, lock);
    }

    private void releaseSessionOwnerIfStillOwned(MockHttpServletRequest request, UUID token, Object lock) throws Exception {
        invoke("releaseSessionOwnerIfStillOwned", request, token, lock);
    }

    private void discardPartialSession(MockHttpServletRequest request, String preexistingSessionId, UUID token, Object lock) throws Exception {
        Method method = AuthController.class.getDeclaredMethod(
                "discardPartialSession", HttpServletRequest.class, String.class, UUID.class, Object.class);
        method.setAccessible(true);
        try {
            method.invoke(controller, request, preexistingSessionId, token, lock);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
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
