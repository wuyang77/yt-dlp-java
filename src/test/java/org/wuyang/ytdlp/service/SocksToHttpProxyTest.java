package org.wuyang.ytdlp.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocksToHttpProxyTest {

    @Test
    void tunnelsHttpConnectThroughSocks5() throws Exception {
        AtomicReference<String> requestedHost = new AtomicReference<>();
        try (ServerSocket socksServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             SocksToHttpProxy bridge = new SocksToHttpProxy("127.0.0.1", socksServer.getLocalPort())) {
            Thread socksThread = new Thread(() -> serveSocksConnection(socksServer, requestedHost));
            socksThread.setDaemon(true);
            socksThread.start();

            int bridgePort = Integer.parseInt(bridge.url().substring(bridge.url().lastIndexOf(':') + 1));
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bridgePort)) {
                client.setSoTimeout(3_000);
                client.getOutputStream().write(
                        "CONNECT example.test:443 HTTP/1.1\r\nHost: example.test:443\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));

                assertTrue(readHeader(client).startsWith("HTTP/1.1 200"));
                client.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
                assertEquals("hello", new String(client.getInputStream().readNBytes(5), StandardCharsets.US_ASCII));
            }

            socksThread.join(3_000);
            assertEquals("example.test", requestedHost.get());
            assertTrue(!socksThread.isAlive(), "Mock SOCKS server should finish after the tunnel closes");
        }
    }

    private static void serveSocksConnection(ServerSocket server, AtomicReference<String> requestedHost) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(3_000);
            var input = socket.getInputStream();
            var output = socket.getOutputStream();
            byte[] greetingHeader = input.readNBytes(2);
            if (greetingHeader.length != 2 || greetingHeader[0] != 5) {
                throw new IOException("Invalid SOCKS5 greeting");
            }
            byte[] methods = input.readNBytes(greetingHeader[1] & 0xff);
            if (methods.length != (greetingHeader[1] & 0xff)) {
                throw new IOException("Incomplete SOCKS5 authentication methods");
            }
            output.write(new byte[] {5, 0});

            byte[] request = input.readNBytes(4);
            if (request.length != 4 || request[0] != 5 || request[1] != 1) {
                throw new IOException("Invalid SOCKS5 CONNECT request");
            }
            int addressType = request[3] & 0xff;
            if (addressType != 3) {
                throw new IOException("Expected a domain-name SOCKS5 destination");
            }
            int hostLength = input.read();
            requestedHost.set(new String(input.readNBytes(hostLength), StandardCharsets.US_ASCII));
            input.readNBytes(2);
            output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
            output.flush();

            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String readHeader(Socket socket) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (header.size() < 4096) {
            int value = socket.getInputStream().read();
            if (value == -1) {
                break;
            }
            header.write(value);
            if (header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                return header.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("HTTP CONNECT response header was not completed");
    }
}
