package opentrap.decoder;

import opentrap.core.Bytes;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.util.Arrays;

/**
 * Tests the decoder transforms added for wider coverage: gzip/deflate round
 * trips, gzip recognition by smart-decode, and the expanded set of named HTML
 * entities. Assertions use byte values so the source stays ASCII-only.
 */
public final class DecoderTransformsTest {

    private static byte[] dec(String name, String in) {
        try { return Transforms.decode(name, Bytes.bytes(in)); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] dec(String name, byte[] in) {
        try { return Transforms.decode(name, in); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] enc(String name, byte[] in) {
        try { return Transforms.encode(name, in); } catch (Exception e) { throw new RuntimeException(e); }
    }

    public void run() {
        Harness.suite("decoder transforms");

        Harness.test("gzip and deflate round-trip", () -> {
            byte[] data = Bytes.bytes("hello hello hello world world");
            Expect.isTrue(Arrays.equals(data, dec("gzip", enc("gzip", data))), "gzip");
            Expect.isTrue(Arrays.equals(data, dec("deflate", enc("deflate", data))), "deflate");
        });

        Harness.test("smart-decode recognises gzip by its magic number", () -> {
            byte[] gz = enc("gzip", Bytes.bytes("some compressible text text text"));
            Expect.equal(Transforms.guess(gz), " gzip", "guess gzip");
        });

        Harness.test("expanded named HTML entities decode to their code points", () -> {
            byte[] copy = dec("html", "&copy;");
            Expect.equal(copy.length, 1, "copyright is one Latin-1 byte");
            Expect.equal(copy[0] & 0xff, 169, "copyright code point");
            byte[] guil = dec("html", "&laquo;x&raquo;");
            Expect.equal(guil.length, 3, "two guillemets around x");
            Expect.equal(guil[0] & 0xff, 171, "laquo");
            Expect.equal(guil[2] & 0xff, 187, "raquo");
            Expect.isTrue(dec("html", "&euro;").length > 0, "euro decodes to something");
        });

        Harness.test("brotli encode is reported as unsupported", () -> {
            boolean threw = false;
            try { Transforms.encode("brotli", Bytes.bytes("x")); } catch (Exception e) { threw = true; }
            Expect.isTrue(threw, "brotli encode refused");
        });
    }
}
