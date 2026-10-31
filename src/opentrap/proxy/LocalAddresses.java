package opentrap.proxy;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The addresses on this machine a listener can be bound to.
 *
 * <p>Every address of every interface that is up, so the "Specific address"
 * choice offers what the host actually has rather than a guess: loopback,
 * LAN addresses, docker and VPN interfaces, and IPv6 including its scope.
 */
public final class LocalAddresses {
    private LocalAddresses() {}

    public static List<String> all() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    String s = a.getHostAddress();
                    if (!out.contains(s)) out.add(s);
                }
            }
        } catch (Exception ignored) {
            // No interfaces readable: loopback is still bindable.
        }
        if (!out.contains("127.0.0.1")) out.add(0, "127.0.0.1");
        // Routable addresses first, then loopback, then link-local IPv6, which
        // is the order they are useful in.
        out.sort((x, y) -> rank(x) - rank(y));
        return out;
    }

    private static int rank(String a) {
        if (a.startsWith("fe80:")) return 3;
        if (a.contains(":")) return 2;
        if (a.startsWith("127.")) return 1;
        return 0;
    }
}
