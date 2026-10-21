package opentrap.discovery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The built-in content-discovery wordlist and the logic that turns words plus
 * optional extensions into concrete request paths. Content discovery (forced
 * browse) finds unlinked paths and files; it is distinct from crawling, which
 * remains ZAP's job.
 */
public final class Wordlist {

    private Wordlist() {}

    /** A compact default list of commonly interesting directories and files. */
    public static final String[] DEFAULT = {
            "admin", "administrator", "login", "logout", "register", "dashboard", "config",
            "config.php", "configuration", "settings", "setup", "install", "installer",
            "backup", "backups", "bak", "old", "test", "tests", "dev", "staging", "tmp", "temp",
            "api", "api/v1", "api/v2", "graphql", "rest", "soap", "rpc",
            "upload", "uploads", "files", "file", "download", "downloads", "media", "static",
            "assets", "images", "img", "css", "js", "scripts", "includes", "inc", "lib", "vendor",
            "db", "database", "sql", "dump", "dump.sql", "data", "logs", "log", "debug", "trace",
            "private", "secret", "secrets", "internal", "hidden", "secure",
            "user", "users", "account", "accounts", "profile", "profiles", "member", "members",
            "robots.txt", "sitemap.xml", "crossdomain.xml", "humans.txt", ".well-known",
            ".git", ".git/config", ".svn", ".env", ".htaccess", ".htpasswd", ".DS_Store",
            "web.config", "phpinfo.php", "info.php", "server-status", "server-info", "status", "health",
            "wp-admin", "wp-login.php", "wp-content", "wp-config.php", "wp-json",
            "console", "actuator", "actuator/health", "metrics", "swagger", "swagger-ui",
            "swagger.json", "openapi.json", "docs", "documentation", "readme", "README.md",
            "CHANGELOG", "LICENSE", "manage", "management", "portal", "cpanel", "webmail",
            "phpmyadmin", "pma", "adminer", "shell", "cmd", "exec", "proxy", "redirect",
            "search", "query", "feed", "rss", "atom", "export", "import", "report", "reports",
    };

    /**
     * Build the candidate paths: each word (with a leading slash), plus the word
     * with each extension appended when the word has no extension of its own.
     * Duplicates and blanks are removed, order preserved.
     */
    public static List<String> candidates(List<String> words, List<String> extensions) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : words) {
            if (raw == null) continue;
            String w = raw.trim();
            if (w.isEmpty() || w.startsWith("#")) continue;
            while (w.startsWith("/")) w = w.substring(1);
            if (w.isEmpty()) continue;
            out.add("/" + w);
            boolean hasDot = w.substring(Math.max(0, w.lastIndexOf('/'))).contains(".");
            if (!hasDot) {
                for (String ext : extensions) {
                    String e = ext.trim();
                    if (e.isEmpty()) continue;
                    if (e.startsWith(".")) e = e.substring(1);
                    out.add("/" + w + "." + e);
                }
            }
        }
        return new ArrayList<>(out);
    }
}
