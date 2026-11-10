import com.sanji.control.Config;
import com.sanji.control.WebSocketClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM live test: connect the hand-rolled WebSocketClient to the real relay
 * (wss://sanji-control-relay-production.up.railway.app), send the hello
 * message, wait briefly for any reply, then close.
 * Exits 0 when the handshake + hello send succeed.
 */
public class TestWebSocket {

    public static void main(String[] args) throws Exception {
        System.out.println("Relay URL: " + Config.RELAY_URL);
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> firstMessage = new AtomicReference<>();

        WebSocketClient client = new WebSocketClient(Config.RELAY_URL, new WebSocketClient.Listener() {
            @Override
            public void onOpen() {
                System.out.println("onOpen: handshake OK");
                opened.countDown();
            }

            @Override
            public void onMessage(String text) {
                System.out.println("onMessage: " + text);
                firstMessage.compareAndSet(null, text);
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

        boolean openedOk = opened.await(15, TimeUnit.SECONDS);
        if (!openedOk) {
            System.out.println("RESULT: FAIL (no onOpen within 15s)");
            System.exit(1);
        }
        if (failure.get() != null) {
            System.out.println("RESULT: FAIL (" + failure.get() + ")");
            System.exit(1);
        }

        // Send the hello message exactly as ControlService does.
        String hello = "{\"type\":\"hello\",\"role\":\"device\",\"device\":\"sanji-phone\"}";
        client.send(hello);
        System.out.println("hello sent (" + hello.length() + " bytes)");

        // Wait a few seconds for any relay reply (it may send nothing).
        Thread.sleep(5000);
        client.close();
        closed.await(5, TimeUnit.SECONDS);

        System.out.println("first message from relay: "
                + (firstMessage.get() == null ? "(none)" : firstMessage.get()));
        System.out.println("RESULT: PASS (handshake + hello send OK)");
    }
}
