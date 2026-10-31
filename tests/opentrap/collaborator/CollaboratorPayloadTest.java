package opentrap.collaborator;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Pins the Collaborator payload controls.
 *
 * <p>"Payloads to generate" was read into a field the copy action could not
 * see, so asking for several produced one. "Include Collaborator server
 * location" was likewise ignored and the full hostname was always copied.
 */
public final class CollaboratorPayloadTest {

    public void run() {
        Harness.suite("collaborator payloads");

        Harness.test("the unique label is taken without the server location", () -> {
            Expect.equal(CollaboratorTool.uniquePrefix("abc123def.oast.example.com"),
                    "abc123def", "only the nonce label is kept");
            Expect.equal(CollaboratorTool.uniquePrefix("nodots"), "nodots",
                    "a hostname with no server part is left alone");
            Expect.equal(CollaboratorTool.uniquePrefix(null), "", "a missing hostname is empty");
        });
    }
}
