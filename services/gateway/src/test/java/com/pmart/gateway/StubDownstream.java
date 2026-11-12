package com.pmart.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * Gateway 뒤의 하위 서비스(Keycloak, Employee Auth, Employee Service, 샘플 API) 대역.
 * 받은 요청을 기록해 두고 200을 돌려줍니다. Gateway가 무엇을 전달했는지(Authorization 헤더 등) 확인하는 용도입니다.
 */
final class StubDownstream implements AutoCloseable {

    record Received(String method, String path, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    final List<Received> received = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    StubDownstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders())));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"stub\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Received last() {
        return received.get(received.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
