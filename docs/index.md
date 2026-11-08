---
layout: default
title: OpenTRAP
---

<section class="hero"><div class="wrap">
  <div class="htext">
    <h1>OpenTRAP</h1>
    <div class="sub">A free, open-source alternative to Burp Suite</div>
    <p class="lead">Capture, inspect, replay and tamper with the traffic between a browser and a web application, then crawl and audit it for real vulnerabilities. One self-contained download, no licence gate, powered by the OWASP ZAP engine.</p>
    <div class="cta">
      <a class="btn btn-primary" href="https://github.com/opentrap/opentrap/releases/latest/download/opentrap-x86_64.appimage">&#8681;&nbsp; Download for Linux</a>
      <a class="btn btn-ghost" href="https://github.com/opentrap/opentrap/releases/latest">Go straight to releases</a>
    </div>
  </div>
  <div class="hshot">
    <a class="shot" href="images/dashboard.png"><img src="images/dashboard.png" alt="OpenTRAP dashboard with a crawl and an audit finished and the live table of the most serious vulnerabilities found"></a>
  </div>
</div></section>

<section class="osband" id="software-freedom"><div class="wrap">
  <div class="os-head">
    <h2>Proudly free and open source</h2>
    <p>OpenTRAP is free and open source software, released under the GNU AGPL v3.0 with no licence gate on anything. We stand with the organisations that make free and open source software possible - if OpenTRAP is useful to you, consider supporting them.</p>
  </div>
  <div class="os-logos">
    <a href="https://www.fsf.org/" title="Free Software Foundation"><img src="images/orgs/fsf.png" alt="Free Software Foundation"></a>
    <a href="https://opensource.org/" title="Open Source Initiative"><img src="images/orgs/osi.svg" alt="Open Source Initiative"></a>
    <a href="https://sfconservancy.org/" title="Software Freedom Conservancy"><img src="images/orgs/sfc.svg" alt="Software Freedom Conservancy"></a>
    <a href="https://fsfe.org/" title="Free Software Foundation Europe"><img src="images/orgs/fsfe.svg" alt="Free Software Foundation Europe"></a>
    <a href="https://www.eff.org/" title="Electronic Frontier Foundation"><img src="images/orgs/eff.svg" alt="Electronic Frontier Foundation"></a>
  </div>
</div></section>

<section class="band" id="features"><div class="wrap">
  <h2>Everything in one window</h2>
  <p class="lead">A proxy-first workspace with the whole toolset one click away, built around a detection engine that holds up.</p>
  <div class="grid">
    <a class="shot" href="images/target.png"><img src="images/target.png" alt="OpenTRAP Target tab: site map, request list and the issues found with a full advisory beside them"></a>
    <ul class="checks">
      <li><span class="ic">{% include check.html %}</span><span><b>A complete proxy-first suite</b>Intercept, HTTP history, repeater, intruder, sequencer, decoder, comparer, an out-of-band collaborator and a full traffic logger.</span></li>
      <li><span class="ic">{% include check.html %}</span><span><b>Scanning that holds up</b>Crawl and audit for real vulnerabilities on the proven OWASP ZAP engine, with findings in a clear issue view and the evidence highlighted.</span></li>
      <li><span class="ic">{% include check.html %}</span><span><b>Guided from the start</b>Open or create a project at launch, then drive crawl-and-audit scans through a step-by-step wizard.</span></li>
      <li><span class="ic">{% include check.html %}</span><span><b>Scan configurations that fit the job</b>Pick a tier from Lightweight to Deep, or build a Custom scan with full control over crawl depth, attack strength and which inputs are tested.</span></li>
      <li><span class="ic">{% include check.html %}</span><span><b>Scope that keeps the noise out</b>Add a host or path to scope from the site map and the proxy offers to stop recording everything else. Filters on the site map and the history hide out-of-scope, not-found and static content, and a scan crawls and attacks only what the scope covers.</span></li>
      <li><span class="ic">{% include check.html %}</span><span><b>Newest first, everywhere</b>Every history in the suite, from the proxy and the Logger to Intruder results and Collaborator interactions, lists the latest entry at the top.</span></li>
      <li><span class="ic">{% include check.html %}</span><span><b>One self-contained file</b>Every release is a single Linux AppImage with its own runtime and the scan engine built in. Download it, run it, scan.</span></li>
      <li><span class="ic">{% include check.html %}</span><span><b>Genuinely open</b>Licensed under the GNU AGPL v3.0, so it stays open and hackable, with no licence gate on its core capabilities.</span></li>
      <li><span class="ic">{% include check.html %}</span><span><b>Built in the open</b>A <a href="https://github.com/opentrap/opentrap/blob/main/ROADMAP.md">public roadmap</a> with a reference code for every unbuilt feature, mirrored inside the app, and every contribution merged with full credit. No AI features, by design.</span></li>
    </ul>
  </div>
</div></section>

<section class="pad" id="screenshots"><div class="wrap">
  <div class="section-head">
    <h2>See it in action</h2>
    <p>Every tool lives in one window. Click any screenshot for the full-size image.</p>
  </div>
  <div class="gallery">
    <a class="card" href="images/target.png"><img src="images/target.png" alt="Target tab: site map with a padlock per host, request list and issues with a full advisory"><div class="cap"><b>Target</b><span>Site map with a padlock per host and out-of-scope hosts greyed, every request in the middle, every issue on the right with its advisory, request and response beside it.</span></div></a>
    <a class="card" href="images/logger.png"><img src="images/logger.png" alt="Logger tab with a filterable table of every request"><div class="cap"><b>Logger</b><span>A complete, filterable record of every request OpenTRAP and the scan engine make, newest first and tagged by the tool that sent it. Nothing happens behind your back.</span></div></a>
  </div>
</div></section>

<section class="pad soft" id="get-started"><div class="wrap">
  <div class="section-head">
    <h2>Get started</h2>
    <p>One file. No Java to install, no parts missing: the runtime and the scan engine are inside it and set themselves up on first use.</p>
  </div>
  <div class="steps">
    <p style="text-align:center;margin-bottom:24px"><a class="btn btn-primary" href="https://github.com/opentrap/opentrap/releases/latest/download/opentrap-x86_64.appimage">&#8681;&nbsp; Download the latest AppImage</a></p>
    <p>Or fetch it from a terminal:</p>
    <pre><code>wget https://github.com/opentrap/opentrap/releases/latest/download/opentrap-x86_64.appimage</code></pre>
    <p>make it executable:</p>
    <pre><code>chmod +x opentrap-x86_64.appimage</code></pre>
    <p>and run it:</p>
    <pre><code>./opentrap-x86_64.appimage</code></pre>
    <p>You can also double-click the downloaded file in your file manager. The optional testing browser is an in-app download, offered only when you want it, so the release stays small.</p>
  </div>
</div></section>

<section class="pad"><div class="wrap">
  <div class="section-head"><h2>Frequently asked</h2></div>
  <div class="faq">
    <div class="q"><b>Is there a free, open-source alternative to Burp Suite?</b><span>Yes. OpenTRAP offers the intercepting-proxy workflow Burp users know, with Proxy, Repeater, Intruder, Collaborator, Sequencer, Decoder and Comparer, under the AGPL-3.0 with no paid tier.</span></div>
    <div class="q"><b>How does OpenTRAP relate to OWASP ZAP?</b><span>It uses the OWASP ZAP engine for crawling and scanning and puts a familiar, recognisable interface on top: ZAP's detection in a proxy-first workspace.</span></div>
    <div class="q"><b>Which platforms does it run on?</b><span>A single self-contained Linux AppImage today, with the Java runtime and the scan engine inside the file.</span></div>
    <div class="q"><b>How big do project files get?</b><span>Small for what they hold. The crawl, your own traffic and every finding are kept in full; the audit's thousands of probe requests are kept as headers only, so a scan-heavy project is roughly a tenth of the size. Project &gt; Project size shows where the bytes go, Save copy writes a smaller copy with in-scope items only, and Settings &gt; Project can keep every probe body if you want it.</span></div>
    <div class="q"><b>Is it really free?</b><span>Yes, released under the GNU AGPL v3.0, with every capability open and nothing reserved for a paid edition.</span></div>
    <div class="q"><b>How do I contribute, and what is still to build?</b><span>The <a href="https://github.com/opentrap/opentrap/blob/main/ROADMAP.md">roadmap</a> lists every feature laid out in the interface but not implemented yet, each with a reference code. Pick one, open a pull request, and it is merged with full credit. See <a href="https://github.com/opentrap/opentrap/blob/main/CONTRIBUTING.md">CONTRIBUTING.md</a>.</span></div>
  </div>
</div></section>
