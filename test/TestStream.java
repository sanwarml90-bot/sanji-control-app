import com.sanji.control.StreamController;

/**
 * JVM tests for the live-screen stream timing (StreamController).
 * No device, no Android runtime.
 *
 * Rules under test:
 *  1. No controller seen -> no streaming, no frames.
 *  2. A controller message starts the stream (frame allowed immediately).
 *  3. Frames are paced to ~4/sec: a second frame is refused within 250 ms
 *     of the last one, allowed once 250 ms have passed.
 *  4. The stream stops when no controller message arrives for 30 s
 *     (presence window) — this is what keeps mobile data at zero when
 *     nobody is watching.
 *  5. reset() stops the stream.
 */
public class TestStream {

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

    public static void main(String[] args) {
        // 1. Fresh controller: nobody connected -> no stream, no frames.
        StreamController s = new StreamController();
        check("no controller -> not present", !s.isControllerPresent(1000));
        check("no controller -> no frame", !s.shouldSendFrame(1000));

        // 2. A controller message starts the stream; a frame is allowed at once.
        s.onControllerMessage(1000);
        check("controller message -> present", s.isControllerPresent(1000));
        check("controller present, no frame yet -> frame allowed",
                s.shouldSendFrame(1000));

        // 3. Frame pacing: refused within 250 ms, allowed at 250 ms.
        s.noteFrameSent(1000);
        check("frame sent at 1000 -> refused at 1100 (within 250 ms)",
                !s.shouldSendFrame(1100));
        check("frame sent at 1000 -> refused at 1249 (just under 250 ms)",
                !s.shouldSendFrame(1249));
        check("frame sent at 1000 -> allowed at 1250 (250 ms elapsed)",
                s.shouldSendFrame(1250));

        // 4. Presence window: still present at 29 s, gone at 30 s.
        StreamController s2 = new StreamController();
        s2.onControllerMessage(0);
        check("controller seen at 0 -> present at 29,999 ms",
                s2.isControllerPresent(29_999));
        check("controller seen at 0 -> gone at 30,000 ms (timeout)",
                !s2.isControllerPresent(30_000));
        check("controller gone -> no frame even if interval elapsed",
                !s2.shouldSendFrame(60_000));

        // 5. A later controller message restarts the stream.
        s2.onControllerMessage(60_000);
        check("new controller message -> present again",
                s2.isControllerPresent(60_000));
        check("new controller message -> frame allowed",
                s2.shouldSendFrame(60_000));

        // 6. reset() stops the stream.
        s2.reset();
        check("reset -> not present", !s2.isControllerPresent(60_001));
        check("reset -> no frame", !s2.shouldSendFrame(60_001));

        // 7. Constants: ~4 fps pacing, 30 s presence window.
        check("FRAME_INTERVAL_MS is 250 (~4 fps)",
                StreamController.FRAME_INTERVAL_MS == 250);
        check("CONTROLLER_TIMEOUT_MS is 30,000",
                StreamController.CONTROLLER_TIMEOUT_MS == 30_000);

        System.out.println("\n=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }
}
