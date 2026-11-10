package com.sanji.control;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM test for the WebSocketClient's handshake + framing, using in-memory
 * piped streams (no network — the sandbox blocks Java TCP entirely).
 * A fake server runs on a thread: it validates the client's Upgrade
 * request, sends a 101, then exercises text frames, ping/pong,
 * fragmentation, and close. The test also decodes the client's masked
 * frames to prove send() is correct.
 */
public class TestWsFrames {

    private static int passed = 0;
    private static int failed = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    // ---- Fake-server frame helpers (server frames are UNMASKED) ----

    static void serverSendFrame(OutputStream out, int opcode, boolean fin, byte[] payload)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write((fin ? 0x80 : 0x00) | opcode);
        int len = payload.length;
        if (len < 126) {
            buf.write(len);
        } else if (len < 65536) {
            buf.write(126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(127);
            for (int i = 7; i >= 0; i--) buf.write((len >> (8 * i)) & 0xFF);
        }
        buf.write(payload);
        out.write(buf.toByteArray());
        out.flush();
    }

    /** Read one frame the CLIENT sent (masked) and return its payload as a string. */
    static String readClientFrame(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 == -1 || b1 == -1) return null;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        byte[] mask = new byte[4];
        if (masked) {
            int off = 0;
            while (off < 4) {
                int n = in.read(mask, off, 4 - off);
                if (n == -1) return null;
                off += n;
            }
        }
        byte[] payload = new byte[(int) len];
        int off = 0;
        while (off < payload.length) {
            int n = in.read(payload, off, payload.length - off);
            if (n == -1) return null;
            off += n;
        }
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        return "opcode=" + opcode + " payload=" + new String(payload, StandardCharsets.UTF_8);
    }

    static String readHttpRequest(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            buf.write(c);
            byte[] b = buf.toByteArray();
            int n = b.length;
            if (n >= 4 && b[n - 4] == '\r' && b[n - 3] == '\n' && b[n - 2] == '\r' && b[n - 1] == '\n') {
                break;
            }
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        // Pipes: client -> server, server -> client.
        PipedOutputStream clientOut = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientOut);
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverOut);

        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch gotTap = new CountDownLatch(1);
        CountDownLatch gotFrag = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch pongRead = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> tapMsg = new AtomicReference<>();
        AtomicReference<String> fragMsg = new AtomicReference<>();

        WebSocketClient client = new WebSocketClient("ws://127.0.0.1:8765/", new WebSocketClient.Listener() {
            @Override
            public void onOpen() {
                opened.countDown();
            }

            @Override
            public void onMessage(String text) {
                if (text.contains("\"tap\"")) {
                    tapMsg.set(text);
                    gotTap.countDown();
                } else if (text.contains("frag")) {
                    fragMsg.set(text);
                    gotFrag.countDown();
                }
            }

            @Override
            public void onClose(int code, String reason) {
                closed.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                failure.set(String.valueOf(e));
                opened.countDown();
                gotTap.countDown();
                gotFrag.countDown();
                closed.countDown();
                pongRead.countDown();
            }
        });

        // Fake server thread.
        AtomicReference<String> serverHandshake = new AtomicReference<>();
        AtomicReference<String> clientHelloFrame = new AtomicReference<>();
        AtomicReference<String> clientPongFrame = new AtomicReference<>();
        Thread server = new Thread(() -> {
            try {
                // 1. Read + validate the Upgrade request.
                String req = readHttpRequest(serverIn);
                serverHandshake.set(req);
                String key = null;
                for (String line : req.split("\r\n")) {
                    if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                        key = line.split(":", 2)[1].trim();
                    }
                }
                String accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest(
                                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                        .getBytes(StandardCharsets.UTF_8)));
                String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                serverOut.write(resp.getBytes(StandardCharsets.UTF_8));
                serverOut.flush();

                // 2. Read the client's hello frame (masked text).
                clientHelloFrame.set(readClientFrame(serverIn));

                // 3. Send a tap command (single text frame).
                serverSendFrame(serverOut, 0x1, true,
                        "{\"id\":\"t1\",\"action\":\"tap\",\"x\":10,\"y\":20}"
                                .getBytes(StandardCharsets.UTF_8));

                // 4. Send a ping; expect a pong back.
                serverSendFrame(serverOut, 0x9, true, "ping!".getBytes(StandardCharsets.UTF_8));
                clientPongFrame.set(readClientFrame(serverIn));
                pongRead.countDown();

                // 5. Send a fragmented text message ("frag" + "mented").
                serverSendFrame(serverOut, 0x1, false, "frag".getBytes(StandardCharsets.UTF_8));
                serverSendFrame(serverOut, 0x0, true, "mented".getBytes(StandardCharsets.UTF_8));

                // 6. Send close.
                serverSendFrame(serverOut, 0x8, true, new byte[0]);
            } catch (Exception e) {
                failure.set("server: " + e);
                opened.countDown();
                gotTap.countDown();
                gotFrag.countDown();
                closed.countDown();
                pongRead.countDown();
            }
        }, "fake-ws-server");
        server.start();

        // Connect the client over the pipes.
        client.connectStreams(clientIn, clientOut, "/", "127.0.0.1");

        check("handshake: client sent Upgrade request",
                serverHandshake.get() != null
                        && serverHandshake.get().startsWith("GET / HTTP/1.1")
                        && serverHandshake.get().contains("Upgrade: websocket")
                        && serverHandshake.get().contains("Sec-WebSocket-Key:")
                        && serverHandshake.get().contains("Sec-WebSocket-Version: 13"));
        check("handshake: onOpen called", opened.await(5, TimeUnit.SECONDS)
                && failure.get() == null);

        // Send hello (the server reads it as the first client frame).
        client.send("{\"type\":\"hello\",\"role\":\"device\",\"device\":\"sanji-phone\"}");
        check("tap command delivered via onMessage",
                gotTap.await(5, TimeUnit.SECONDS)
                        && tapMsg.get() != null && tapMsg.get().contains("\"tap\""));
        check("ping answered with pong (opcode=10)",
                pongRead.await(5, TimeUnit.SECONDS)
                        && clientPongFrame.get() != null
                        && clientPongFrame.get().startsWith("opcode=10")
                        && clientPongFrame.get().contains("ping!"));
        check("fragmented message reassembled into one onMessage",
                gotFrag.await(5, TimeUnit.SECONDS)
                        && "fragmented".equals(fragMsg.get()));
        check("close frame triggers onClose",
                closed.await(5, TimeUnit.SECONDS));
        check("no failure", failure.get() == null);

        // The hello frame the server read should be a masked text frame.
        // (Read after the fact; the server stored it.)
        server.join(5000);
        check("client hello frame decoded correctly",
                clientHelloFrame.get() != null
                        && clientHelloFrame.get().startsWith("opcode=1")
                        && clientHelloFrame.get().contains("\"hello\""));

        client.close();
        System.out.println("\n=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }
}
