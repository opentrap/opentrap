# Icon credits

The OpenTRAP application icon (`icon/opentrap.svg` and the rasterised copies
under `src/opentrap/ui/icons/`) is based on the **knemo-monitor** icon from the
**Flat Remix** icon theme.

- Author: Daniel Ruiz de Alegria
- Source: https://github.com/daniruiz/flat-remix
- Licence: GNU General Public License v3

The only modification is pinning the fill colour to the theme's orange so the icon
stays visible on any desktop colour scheme instead of being recoloured by it.

Flat Remix is GPL-3.0, which is compatible with OpenTRAP's AGPL-3.0 licence. In
line with the GPL, this credit and the licence are retained. The full GPL-3.0
text is at https://www.gnu.org/licenses/gpl-3.0.html and is also shipped with
most systems under `/usr/share/common-licenses/GPL-3`.

# Bundled tools

OpenTRAP drives these external tools, fetched on demand into the product rather
than packed into the jar:

- **OWASP ZAP** (Zed Attack Proxy), by the ZAP dev team / Checkmarx, licensed
  Apache 2.0. Source: https://github.com/zaproxy/zaproxy. OpenTRAP runs ZAP with
  no user interface, as a headless scan engine on the loopback: its crawler
  (spider) and active-scan rules power OpenTRAP's crawl and audit, and its
  findings are mapped into OpenTRAP's own issue model and shown in OpenTRAP's UI.
  Nothing of ZAP's interface is shown to the user. The engine is fetched by
  `fetch-zap.sh`. Apache 2.0 is compatible with OpenTRAP's AGPL-3.0 licence.
- **Chromium**, the embedded testing browser, BSD-licensed open-source build,
  fetched by `fetch-browser.sh`.
