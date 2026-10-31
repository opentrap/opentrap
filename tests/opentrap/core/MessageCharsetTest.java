package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.nio.charset.StandardCharsets;

/**
 * Pins how a message's declared charset is read.
 *
 * <p>Bodies were always decoded as ISO-8859-1, so a UTF-8 response showed
 * "sönmez" as "sÃ¶nmez". The charset is now taken from the Content-Type when
 * the bytes really are valid in it, and ignored when they are not, because
 * decoding bytes that do not fit would replace them and an edit would write the
 * replacements back.
 */
public final class MessageCharsetTest {

    private static HttpMessage response(String contentType, byte[] body) {
        byte[] head = ("HTTP/1.1 200 OK\r\nContent-Type: " + contentType
                + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = new byte[head.length + body.length];
        System.arraycopy(head, 0, raw, 0, head.length);
        System.arraycopy(body, 0, raw, head.length, body.length);
        return new HttpMessage(raw);
    }

    public void run() {
        Harness.suite("message charset");

        Harness.test("the charset is read off the Content-Type", () -> {
            Expect.equal(response("text/javascript; charset=UTF-8", new byte[0]).charsetName(),
                    "UTF-8", "charset from a parameter");
            Expect.equal(response("text/html;charset=\"iso-8859-15\"", new byte[0]).charsetName(),
                    "iso-8859-15", "a quoted charset has its quotes taken off");
            Expect.equal(response("application/json", new byte[0]).charsetName(),
                    "", "no charset named");
        });

        Harness.test("a UTF-8 body reads as the text that was sent", () -> {
            String turkish = "berfin sönmez bursa / kasım ayı artış";
            HttpMessage m = response("text/javascript; charset=UTF-8",
                    turkish.getBytes(StandardCharsets.UTF_8));
            String shown = new String(m.raw(), StandardCharsets.UTF_8);
            Expect.isTrue(shown.contains(turkish), "the body reads back as it was sent");
            // The old behaviour, kept here so the failure mode stays recognisable.
            String asLatin1 = new String(m.raw(), StandardCharsets.ISO_8859_1);
            Expect.isTrue(asLatin1.contains("sÃ¶nmez"),
                    "reading UTF-8 as ISO-8859-1 is what produced the mojibake");
        });

        Harness.test("bytes that are not valid in the declared charset round-trip anyway", () -> {
            // A body claiming UTF-8 but carrying a stray 0xFF must not be
            // decoded as UTF-8, or an edit would write back a replacement char.
            byte[] bad = {(byte) 0xFF, (byte) 0xFE, 'o', 'k'};
            HttpMessage m = response("text/plain; charset=UTF-8", bad);
            String latin1 = new String(m.raw(), StandardCharsets.ISO_8859_1);
            byte[] back = latin1.getBytes(StandardCharsets.ISO_8859_1);
            Expect.isTrue(java.util.Arrays.equals(m.raw(), back),
                    "ISO-8859-1 round-trips every byte untouched");
        });
    }
}
