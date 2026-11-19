package com.emmanuel.wallpapersync;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight pure-Java HTTP/1.1 server for local Wi-Fi wallpaper uploads.
 * No external HTTP library required.
 */
final class WallpaperHttpServer {
    private static final byte[] HTML_BYTES = WebUi.HTML.getBytes(StandardCharsets.UTF_8);
    interface Handler {
        String statusJson();
        Result applyWallpaper(String target, byte[] imageBytes);
        Result clearWallpaper(String target);
    }

    static final class Result {
        final boolean ok;
        final int httpCode;
        final String error;

        Result(boolean ok, int httpCode, String error) {
            this.ok = ok;
            this.httpCode = httpCode;
            this.error = error;
        }

        static Result success() {
            return new Result(true, 200, null);
        }

        static Result fail(int code, String error) {
            return new Result(false, code, error);
        }
    }

    private final Handler handler;
    private volatile String pin;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;
    private int port;

    WallpaperHttpServer(Handler handler, String pin) {
        this.handler = handler;
        this.pin = pin;
    }

    void setPin(String pin) {
        this.pin = pin;
    }

    String getPin() {
        return pin;
    }

    int getPort() {
        return port;
    }

    boolean isRunning() {
        return running;
    }

    synchronized int start(int preferredPort) throws IOException {
        if (running) return port;
        IOException last = null;
        for (int p = preferredPort; p < preferredPort + 10; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                serverSocket = s;
                port = p;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (serverSocket == null) {
            throw last != null ? last : new IOException("No free port");
        }
        running = true;
        ThreadPoolExecutor exec = new ThreadPoolExecutor(0, 4, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16), r -> {
                    Thread t = new Thread(r, "wallpaper-http-worker");
                    t.setDaemon(true);
                    return t;
                });
        exec.allowCoreThreadTimeOut(true);
        pool = exec;
        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(30_000);
                    pool.execute(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) break;
                }
            }
        }, "wallpaper-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return port;
    }

    synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             OutputStream out = s.getOutputStream()) {
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(128);
            String requestLine = readLine(in, lineBuf);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Bad request\"}");
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String rawPath = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while (headers.size() < 64 && (line = readLine(in, lineBuf)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }

            dispatch(method, rawPath, headers, in, out);
        } catch (IOException ignored) {
        }
    }

    void dispatch(String method, String rawPath, Map<String, String> headers, InputStream body, OutputStream out)
            throws IOException {
        String path = rawPath;
        String query = "";
        int qIdx = rawPath.indexOf('?');
        if (qIdx >= 0) {
            path = rawPath.substring(0, qIdx);
            query = rawPath.substring(qIdx + 1);
        }

        if ("GET".equals(method) && "/".equals(path)) {
            writeResponse(out, 200, "text/html; charset=utf-8", HTML_BYTES);
            return;
        }
        if ("GET".equals(method) && "/api/status".equals(path)) {
            writeJson(out, 200, handler.statusJson());
            return;
        }

        if ("POST".equals(method) && ("/api/wallpaper".equals(path) || "/api/clear".equals(path))) {
            String expectedPin = pin;
            String givenPin = headers.get("x-pin");
            if (expectedPin != null && !expectedPin.isEmpty()
                    && !MessageDigest.isEqual(expectedPin.getBytes(StandardCharsets.UTF_8),
                            (givenPin == null ? "" : givenPin.trim()).getBytes(StandardCharsets.UTF_8))) {
                writeJson(out, 403, "{\"ok\":false,\"error\":\"Invalid pairing PIN\"}");
                return;
            }
            String target = queryParam(query, "target");
            if (!"home".equals(target) && !"screensaver".equals(target) && !"both".equals(target)) {
                writeJson(out, 400, "{\"ok\":false,\"error\":\"Invalid target\"}");
                return;
            }
            if ("/api/clear".equals(path)) {
                Result r = handler.clearWallpaper(target);
                writeResult(out, r);
                return;
            }
            int contentLength = parseInt(headers.get("content-length"), -1);
            if (contentLength <= 0 || contentLength > LauncherBridge.MAX_BYTES) {
                writeJson(out, 413, "{\"ok\":false,\"error\":\"Image must be between 1 B and 15 MB\"}");
                return;
            }
            byte[] imageBytes = LauncherBridge.readBounded(body, contentLength);
            Result r = handler.applyWallpaper(target, imageBytes);
            writeResult(out, r);
            return;
        }

        writeJson(out, 404, "{\"ok\":false,\"error\":\"Not found\"}");
    }

    private static void writeResult(OutputStream out, Result r) throws IOException {
        if (r.ok) {
            writeJson(out, 200, "{\"ok\":true}");
        } else {
            String msg = r.error == null ? "Failed" : r.error.replace("\"", "'");
            writeJson(out, r.httpCode, "{\"ok\":false,\"error\":\"" + msg + "\"}");
        }
    }

    private static void writeJson(OutputStream out, int code, String json) throws IOException {
        writeResponse(out, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static String reasonPhrase(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 502: return "Bad Gateway";
            default: return "Error";
        }
    }

    private static void writeResponse(OutputStream out, int code, String contentType, byte[] body) throws IOException {
        String status = reasonPhrase(code);
        String header = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    static String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String readLine(InputStream in, ByteArrayOutputStream buf) throws IOException {
        buf.reset();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (prev == '\r') buf.write('\r');
            if (c != '\r') buf.write(c);
            prev = c;
            if (buf.size() > 8192) throw new IOException("Header line too long");
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.US_ASCII.name());
    }
}
