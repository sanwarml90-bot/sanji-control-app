import com.sanji.control.WebSocketClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM test: connect the hand-rolled WebSocketClient to a LOCAL plain
 * ws:// server (test/TestWsServer.py) and verify the full round trip:
 * handshake -> hello sent -> command received -> result sent -> close.
 * Run the Python server first, then this test.
 */
public class TestWsLocal {

    public static void main(String[] args) throws Exception {
        String url = "ws://127.0.0.1:8765";
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch gotCommand = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> command = new AtomicReference<>();

        WebSocketClient client = new WebSocketClient(url, new WebSocketClient.Listener() {
            @Override
            public void onOpen() {
                System.out.println("onOpen: handshake OK");
                opened.countDown();
            }

            @Override
            public void onMessage(String text) {
                System.out.println("onMessage: " + text);
                command.set(text);
                gotCommand.countDown();
            }

            @Override
            public void onClose(int code, String reason) {
                System.out.println("onClose: " + code + " " + reason);
                closed.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                System.out.println("onFailure: " + e);
                failure.set(String.valueOf(e));
                opened.countDown();
                gotCommand.countDown();
                closed.countDown();
            }
        });

        try {
            client.connect();
        } catch (Exception e) {
            System.out.println("connect() threw: " + e);
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }

        if (!opened.await(10, TimeUnit.SECONDS) || failure.get() != null) {
            System.out.println("RESULT: FAIL (no onOpen: " + failure.get() + ")");
            System.exit(1);
        }

        client.send("{\"type\":\"hello\",\"role\":\"device\",\"device\":\"sanji-phone\"}");
        System.out.println("hello sent");

        if (!gotCommand.await(10, TimeUnit.SECONDS)) {
            System.out.println("RESULT: FAIL (no command received)");
            System.exit(1);
        }
        if (command.get() == null || !command.get().contains("\"tap\"")) {
            System.out.println("RESULT: FAIL (command was not the tap: " + command.get() + ")");
            System.exit(1);
        }

        // Reply to the command (as ControlService would).
        client.send("{\"type\":\"result\",\"id\":\"t1\",\"ok\":true,\"info\":\"tapped 10,20\"}");
        System.out.println("result sent");

        closed.await(10, TimeUnit.SECONDS);
        client.close();
        System.out.println("RESULT: PASS (local ws round trip OK)");
    }
}
