package com.sanji.control;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal WebSocket client (RFC 6455) over a plain java.net.Socket.
 * Used instead of OkHttp because the sandbox blocks Java's network access
 * for dependency downloads, so OkHttp cannot be fetched; this client needs
 * no external library and works on any Android API level.
 *
 * Supports wss:// (TLS) and ws:// (plain). Handles text frames only
 * (the relay only sends JSON text). Sends masked frames (client requirement)
 * and reassembles fragmented messages. Pings are answered with pongs.
 */
public class WebSocketClient {

    /** Callback for WebSocket events. All calls happen on the reader thread. */
    public interface Listener {
        void onOpen();
        void onMessage(String text);
        void onClose(int code, String reason);
        void onFailure(Exception e);
    }

    private final String url;
    private final Listener listener;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile InputStream in;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Thread readerThread;

    public WebSocketClient(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    /** Connect (blocking). Performs the HTTP Upgrade handshake, then starts the reader thread. */
    public void connect() throws Exception {
        URI uri = new URI(url);
        String scheme = uri.getScheme();
        boolean tls = "wss".equalsIgnoreCase(scheme);
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) port = tls ? 443 : 80;
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        Socket raw = new Socket(host, port);
        raw.setSoTimeout(0); // blocking reads; close() interrupts them
        Socket s = tls
                ? ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true)
                : raw;
        this.socket = s;
        this.out = s.getOutputStream();
        this.in = new BufferedInputStream(s.getInputStream());
        handshakeAndStart(path, host, uri.getPort());
    }

    /**
     * Test-only entry point: run the handshake + reader over already-open
     * streams (no Socket, no TLS). Lets the JVM test verify the handshake
     * and framing without a network connection.
     */
    void connectStreams(InputStream in, OutputStream out, String path, String host) throws Exception {
        this.in = new BufferedInputStream(in);
        this.out = out;
        handshakeAndStart(path, host, -1);
    }

    /** HTTP Upgrade handshake, then start the reader thread. */
    private void handshakeAndStart(String path, String host, int port) throws Exception {
        byte[] keyBytes = new byte[16];
        new Random().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + (port != -1 ? ":" + port : "") + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read status line + headers until the blank line.
        String statusLine = readHttpLine();
        if (statusLine == null || !statusLine.contains("101")) {
            throw new IOException("WebSocket handshake failed: " + statusLine);
        }
        String line;
        String accept = null;
        while ((line = readHttpLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("sec-websocket-accept:")) {
                accept = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        String expected = computeAccept(key);
        if (accept == null || !accept.equals(expected)) {
            throw new IOException("WebSocket handshake: bad Sec-WebSocket-Accept");
        }

        closed.set(false);
        readerThread = new Thread(this::readLoop, "sanji-ws-reader");
        readerThread.start();
        listener.onOpen();
    }

    /** Send a text message (masked, as required for client frames). */
    public synchronized void send(String text) throws IOException {
        if (closed.get() || out == null) throw new IOException("socket closed");
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        sendFrame(0x1, payload); // opcode 0x1 = text
    }

    /** Close the connection (sends a close frame, then closes the socket). */
    public synchronized void close() {
        if (closed.getAndSet(true)) return;
        try {
            if (out != null) sendFrame(0x8, new byte[0]); // opcode 0x8 = close
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }

    // ---- Frame I/O ----

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x80 | opcode); // FIN + opcode
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        int len = payload.length;
        if (len < 126) {
            buf.write(0x80 | len);
        } else if (len < 65536) {
            buf.write(0x80 | 126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) buf.write((len >> (8 * i)) & 0xFF);
        }
        buf.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            buf.write(payload[i] ^ mask[i % 4]);
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    private void readLoop() {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = -1;
        try {
            while (!closed.get()) {
                int b0 = in.read();
                if (b0 == -1) break;
                int b1 = in.read();
                if (b1 == -1) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = (readByte() << 8) | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (masked && mask != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                }
                switch (opcode) {
                    case 0x0: // continuation
                        message.write(payload);
                        break;
                    case 0x1: // text
                    case 0x2: // binary (treat as text)
                        messageOpcode = opcode;
                        message.write(payload);
                        break;
                    case 0x8: // close
                        close();
                        listener.onClose(1000, "remote closed");
                        return;
                    case 0x9: // ping -> pong
                        sendFrame(0xA, payload);
                        break;
                    case 0xA: // pong
                        break;
                    default:
                        break;
                }
                if (fin && messageOpcode == 0x1) {
                    String text = new String(message.toByteArray(), StandardCharsets.UTF_8);
                    message.reset();
                    messageOpcode = -1;
                    listener.onMessage(text);
                } else if (fin) {
                    message.reset();
                    messageOpcode = -1;
                }
            }
        } catch (Exception e) {
            if (!closed.get()) listener.onFailure(e);
        } finally {
            if (!closed.get()) {
                closed.set(true);
                listener.onClose(1006, "connection lost");
            }
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) throw new IOException("unexpected end of stream");
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new IOException("unexpected end of stream");
            off += n;
        }
    }

    private String readHttpLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
        }
        if (c == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String computeAccept(String key) throws Exception {
        String concat = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(concat.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}
