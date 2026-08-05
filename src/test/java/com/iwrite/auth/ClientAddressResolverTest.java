package com.iwrite.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAddressResolverTest {

    @Test
    void semProxiesConfiguradosSempreUsaOEnderecoDoPeer() {
        ClientAddressResolver resolver = new ClientAddressResolver("");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.22.0.4");
        request.addHeader("X-Forwarded-For", "9.9.9.9");

        assertThat(resolver.resolve(request)).isEqualTo("172.22.0.4");
    }

    @Test
    void proxyConfiguradoPorIpTemOEnderecoEncaminhadoAceito() {
        ClientAddressResolver resolver = new ClientAddressResolver("172.22.0.4");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.22.0.4");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 172.22.0.4");

        // The left-most entry is the original client; anything after it names an intermediate proxy.
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void proxyConfiguradoPorCidrTemOEnderecoEncaminhadoAceito() {
        ClientAddressResolver resolver = new ClientAddressResolver("172.22.0.0/24");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.22.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void headerForwardedRfc7239TemPrioridadeSobreXForwardedFor() {
        ClientAddressResolver resolver = new ClientAddressResolver("172.22.0.4");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.22.0.4");
        request.addHeader("Forwarded", "for=203.0.113.9;proto=https");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void headerForwardedComIpv6EPortaTemColchetesEPortaRemovidos() {
        ClientAddressResolver resolver = new ClientAddressResolver("172.22.0.4");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.22.0.4");
        request.addHeader("Forwarded", "for=\"[2001:db8::1]:8080\"");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
    }

    @Test
    void peerNaoConfiavelNaoConseguePassarUmEnderecoForjado() {
        ClientAddressResolver resolver = new ClientAddressResolver("172.22.0.4");

        MockHttpServletRequest request = new MockHttpServletRequest();
        // Direct access to the published port arrives from the network's gateway, not the
        // configured proxy — the forged header must be ignored, not silently trusted.
        request.setRemoteAddr("172.22.0.1");
        request.addHeader("X-Forwarded-For", "9.9.9.9");

        assertThat(resolver.resolve(request)).isEqualTo("172.22.0.1");
    }

    @Test
    void proxyConfiguradoPorHostnameResolveEmCadaChamada() {
        // "localhost" always resolves, unlike a Compose service name in this unit test's environment
        // — proves hostname entries are matched by resolving them, not just compared as strings.
        ClientAddressResolver resolver = new ClientAddressResolver("localhost");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void hostnameConfiguradoQueNaoResolveDegradaParaNaoConfiavel() {
        ClientAddressResolver resolver = new ClientAddressResolver("frontend-que-nao-existe.invalid");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.22.0.4");
        request.addHeader("X-Forwarded-For", "9.9.9.9");

        assertThat(resolver.resolve(request)).isEqualTo("172.22.0.4");
    }

    @Test
    void semCabecalhoEncaminhadoUsaOPeerMesmoQuandoConfiavel() {
        ClientAddressResolver resolver = new ClientAddressResolver("172.22.0.4");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.22.0.4");

        assertThat(resolver.resolve(request)).isEqualTo("172.22.0.4");
    }

    @Test
    void cidrInvalidoFalhaNaConstrucao() {
        assertThatThrownBy(() -> new ClientAddressResolver("999.999.999.999"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listaComVariosEntradasAceitaEspacosEEntradasVazias() {
        ClientAddressResolver resolver = new ClientAddressResolver(" 172.22.0.4 , , 172.22.0.5 ");

        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("172.22.0.5");
        first.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.resolve(first)).isEqualTo("203.0.113.7");
    }
}
