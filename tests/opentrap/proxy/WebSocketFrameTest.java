package opentrap.proxy;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Tests the WebSocket frame codec: encode then read must round-trip the payload
 * in both masked (client-to-server) and unmasked (server-to-client) directions,
 * across the three length encodings, and masked bytes must differ on the wire.
 */
public final class WebSocketFrameTest {

    private static byte[] roundTrip(byte[] payload, boolean mask) {
        try {
            byte[] wire = WebSocketFrame.encode(true, 1, payload, mask);
            WebSocketFrame f = WebSocketFrame.read(new ByteArrayInputStream(wire));
            Expect.notNull(f, "frame read back");
            return f.payload;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void run() {
        Harness.suite("websocket frames");

        Harness.test("a masked text frame round-trips to the same payload", () -> {
            byte[] p = "hello websocket".getBytes(StandardCharsets.UTF_8);
            Expect.isTrue(Arrays.equals(p, roundTrip(p, true)), "masked round-trip");
        });

        Harness.test("an unmasked frame round-trips to the same payload", () -> {
            byte[] p = "server says hi".getBytes(StandardCharsets.UTF_8);
            Expect.isTrue(Arrays.equals(p, roundTrip(p, false)), "unmasked round-trip");
        });

        Harness.test("the 16-bit and 64-bit length encodings round-trip", () -> {
            byte[] medium = new byte[1000];   // uses the 126 / 16-bit length
            byte[] large = new byte[70000];   // uses the 127 / 64-bit length
            for (int i = 0; i < medium.length; i++) medium[i] = (byte) (i & 0xFF);
            for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 7 & 0xFF);
            Expect.isTrue(Arrays.equals(medium, roundTrip(medium, true)), "1000-byte masked");
            Expect.isTrue(Arrays.equals(large, roundTrip(large, false)), "70000-byte unmasked");
        });

        Harness.test("masking actually obscures the payload on the wire", () -> {
            byte[] p = "secret".getBytes(StandardCharsets.UTF_8);
            byte[] wire = WebSocketFrame.encode(true, 1, p, true);
            // The literal payload must not appear verbatim in a masked frame.
            boolean verbatim = indexOf(wire, p) >= 0;
            Expect.isFalse(verbatim, "masked payload present verbatim");
        });
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
