package org.wuyang.ytdlp.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyAddressTest {

    @Test
    void identifiesHttpProxy() {
        ProxyAddress proxy = ProxyAddress.parse("http://127.0.0.1:7890");

        assertEquals(ProxyAddress.Protocol.HTTP, proxy.protocol());
        assertEquals("http://127.0.0.1:7890", proxy.value());
        assertEquals(7890, proxy.port());
    }

    @Test
    void identifiesSocks5hProxy() {
        ProxyAddress proxy = ProxyAddress.parse("socks5h://127.0.0.1:3080");

        assertTrue(proxy.isSocks5());
        assertEquals(ProxyAddress.Protocol.SOCKS5, proxy.protocol());
        assertEquals(3080, proxy.port());
    }

    @Test
    void treatsUnschemedAddressAsHttpProxy() {
        ProxyAddress proxy = ProxyAddress.parse("127.0.0.1:7890");

        assertEquals("http://127.0.0.1:7890", proxy.value());
    }

    @Test
    void rejectsUnknownSchemesAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> ProxyAddress.parse("ftp://127.0.0.1:21"));
        assertThrows(IllegalArgumentException.class, () -> ProxyAddress.parse("https://127.0.0.1:7890"));
        assertThrows(IllegalArgumentException.class, () -> ProxyAddress.parse("http://127.0.0.1:70000"));
    }
}
