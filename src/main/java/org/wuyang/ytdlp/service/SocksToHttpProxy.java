package org.wuyang.ytdlp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SocksToHttpProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SocksToHttpProxy.class);
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final InetSocketAddress socksAddress;
    private final ServerSocket server;
    private final ExecutorService workers;
    private final Thread acceptThread;

    SocksToHttpProxy(String host, int port) throws IOException {
        this.socksAddress = new InetSocketAddress(host, port);
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0));
        this.workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "socks-http-proxy-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.acceptThread = new Thread(this::acceptConnections, "socks-http-proxy-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    private void acceptConnections() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                workers.execute(() -> handle(client));
            } catch (SocketException e) {
                if (!server.isClosed()) {
                    log.error("event=pot.socks_bridge_accept_failed message={}", e.getMessage(), e);
                    close();
                }
            } catch (IOException e) {
                log.error("event=pot.socks_bridge_accept_failed message={}", e.getMessage(), e);
                close();
            }
        }
    }

    private void handle(Socket client) {
        boolean tunnelEstablished = false;
        try {
            client.setSoTimeout(CONNECT_TIMEOUT_MS);
            String request = readHeader(client.getInputStream());
            String[] requestLine = request.split("\r\n", 2)[0].split(" ", 3);
            if (requestLine.length < 2 || !requestLine[0].equalsIgnoreCase("CONNECT")) {
                writeResponse(client.getOutputStream(), "HTTP/1.1 405 Method Not Allowed\r\n\r\n");
                return;
            }

            InetSocketAddress target = parseTarget(requestLine[1]);
            try (Socket upstream = new Socket(new Proxy(Proxy.Type.SOCKS, socksAddress))) {
                upstream.connect(InetSocketAddress.createUnresolved(target.getHostString(), target.getPort()),
                        CONNECT_TIMEOUT_MS);
                writeResponse(client.getOutputStream(), "HTTP/1.1 200 Connection Established\r\n\r\n");
                tunnelEstablished = true;
                client.setSoTimeout(0);
                upstream.setSoTimeout(0);
                relay(client, upstream);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.debug("event=pot.socks_bridge_request_failed message={}", e.getMessage());
            if (!tunnelEstablished) {
                try {
                    writeResponse(client.getOutputStream(), "HTTP/1.1 502 Bad Gateway\r\n\r\n");
                } catch (IOException responseError) {
                    log.debug("event=pot.socks_bridge_error_response_failed message={}", responseError.getMessage());
                }
            }
        } finally {
            closeQuietly(client);
        }
    }

    private static String readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (header.size() < MAX_HEADER_BYTES) {
            int next = input.read();
            if (next == -1) {
                throw new IOException("Proxy client closed before sending headers");
            }
            header.write(next);
            matched = switch (matched) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : 0;
                default -> matched;
            };
            if (matched == 4) {
                return header.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException("Proxy request headers exceed the limit");
    }

    private static InetSocketAddress parseTarget(String authority) throws IOException {
        String host;
        String portText;
        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            if (closingBracket < 0 || closingBracket + 1 >= authority.length()
                    || authority.charAt(closingBracket + 1) != ':') {
                throw new IOException("Invalid CONNECT authority");
            }
            host = authority.substring(1, closingBracket);
            portText = authority.substring(closingBracket + 2);
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon <= 0) {
                throw new IOException("Invalid CONNECT authority");
            }
            host = authority.substring(0, colon);
            portText = authority.substring(colon + 1);
        }
        try {
            int port = Integer.parseInt(portText);
            if (host.isBlank() || port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return InetSocketAddress.createUnresolved(host, port);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid CONNECT port", e);
        }
    }

    private static void relay(Socket client, Socket upstream) throws IOException {
        CountDownLatch completed = new CountDownLatch(2);
        copyAsync(client, upstream, completed);
        copyAsync(upstream, client, completed);
        try {
            completed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Proxy relay interrupted", e);
        }
    }

    private static void copyAsync(Socket source, Socket destination, CountDownLatch completed) {
        Thread thread = new Thread(() -> {
            try {
                source.getInputStream().transferTo(destination.getOutputStream());
            } catch (IOException e) {
                log.debug("event=pot.socks_bridge_relay_ended message={}", e.getMessage());
            } finally {
                try {
                    destination.shutdownOutput();
                } catch (IOException e) {
                    log.debug("event=pot.socks_bridge_shutdown_failed message={}", e.getMessage());
                }
                completed.countDown();
            }
        }, "socks-http-proxy-relay");
        thread.setDaemon(true);
        thread.start();
    }

    private static void writeResponse(OutputStream output, String response) throws IOException {
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("event=pot.socks_bridge_socket_close_failed message={}", e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException e) {
            log.debug("event=pot.socks_bridge_server_close_failed message={}", e.getMessage());
        }
        workers.shutdownNow();
    }
}
