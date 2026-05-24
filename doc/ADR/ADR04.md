# Architecture Decision Record: Web Scraping Strategy for Regulatory Document Discovery

**ADR ID:** ADR-004  
**Status:** Accepted  
**Date:** 2026-05-24  
**Author:** Maria Ind  
**Supersedes:** None

---

## Context

The compliance checker needs to discover and ingest regulatory PDF documents from external sources, primarily the FINMA document center (https://www.finma.ch/de/dokumente/). This page is built on Sitecore CMS and presents two challenges:

1. **JavaScript-rendered content** — The document list is not present in the initial HTML. It is loaded via an AJAX API call after the page's JavaScript bundle (`app.min.js`) executes.

2. **Pagination via "Weitere laden" button** — The full document set is loaded progressively in batches of 10. Each click triggers another API call with a `Skip` parameter.

3. **Filter by document type** — The Dokumentenart dropdown uses Sitecore item GUIDs to filter by category (e.g., Wegleitung, FINMA-Aufsichtsmitteilung). Only one type can be selected at a time.

---

## Options Evaluated

### Option A: Playwright for Java — Rejected

Full Chromium browser automation. Would handle the JavaScript and button clicks perfectly, but requires external browser binaries (~500 MB), a manual install step (`playwright install chromium`), and fails in headless cloud environments (Azure App Service, Container Apps) where browser packages aren't available.

### Option B: Selenium WebDriver — Rejected

Same external binary problem as Playwright, plus version-matching fragility between Chrome and ChromeDriver.

### Option C: HtmlUnit — Attempted, Failed

Pure Java headless browser (~15 MB Maven dependency). No external binaries, works everywhere Java runs. However, HtmlUnit's Rhino-based JavaScript engine could not parse FINMA's minified `app.min.js` bundle (modern ES syntax with optional chaining, nullish coalescing). The error `missing ; before statement (app.min.js#4047)` confirmed the JS engine limitation. Without executing the JavaScript, the AJAX call that loads the document list never fires, and only static PDF links in the page layout are found (1 document instead of 100+).

### Option D: Direct API Call via HttpClient — Chosen

By intercepting the browser's network requests (DevTools → Network → XHR), the underlying Sitecore search API was discovered:

- **Endpoint**: `POST https://www.finma.ch/api/search/getresult`
- **Content-Type**: `application/x-www-form-urlencoded`
- **Body**: `ds={datasource-guid}&Dokumentenart={category-guid}&Order=4`
- **Pagination**: `?Skip=10`, `?Skip=20`, etc. as a query parameter
- **Response**: JSON with `{"Items": [{"OtherLanguageLinks": [{"Url": "...", "Name": "DE"}], ...}]}`

This eliminates browser emulation entirely. Plain `java.net.http.HttpClient` makes POST requests, Jackson parses the JSON response, and PDF URLs are extracted from the `OtherLanguageLinks` array.

---

## Decision

Use **direct HttpClient API calls** to the FINMA search endpoint. The scraper is isolated behind `@Profile("scrape")` and activated via `--spring.profiles.active=dev,scrape`.

Sitecore item GUIDs for the document type filters are stored in `application.yml` under `compliance.scraper.finma.guid.*`, not hardcoded in Java. These are stable CMS content IDs, but externalizing them means a config change (no redeployment) if FINMA restructures their content tree.

The system also provides a manual ingestion path via a GUI textbox and REST API (`POST /api/ingest/url`), which works independently of the scraper for any arbitrary PDF URL.

---

## Implementation Details

### API Parameters

| Parameter | Value | Notes |
|---|---|---|
| `ds` | `{E02680B6-2600-4C66-BD5B-57BF955A97A8}` | Sitecore datasource for the document center component |
| `Dokumentenart` | GUID from config | `{56463C7B-...}` for Wegleitung, `{1C2D29B9-...}` for Aufsichtsmitteilung |
| `Order` | `4` | Sort order (by date descending) |
| `Skip` | `0, 10, 20, ...` | Pagination offset, increments by 10 |

### How to Find New GUIDs

If FINMA adds new document categories or changes their CMS structure:

1. Open `https://www.finma.ch/de/dokumente/` in a browser
2. Press F12 → Network tab → filter by Fetch/XHR
3. Select the desired document type from the Dokumentenart dropdown
4. Right-click the XHR request → Copy as cURL
5. The `Dokumentenart` value in `--data-raw` is the new GUID

### Two-Pass Architecture

The scraper runs two sequential API queries (one per document type) because the Dokumentenart filter accepts only one GUID at a time. Results are deduplicated by URL across passes using a `LinkedHashMap`.

---

## Deployment Impact

| Environment | Playwright | HtmlUnit | Direct API |
|---|---|---|---|
| Developer laptop | Requires browser install | Works, but JS fails | Works |
| Docker container | +500 MB image | Works, but JS fails | Works |
| Azure App Service | Not supported | Works, but JS fails | Works |
| CI/CD pipelines | Requires browser action | Works, but JS fails | Works |

---

## Risks and Mitigations

**Risk:** FINMA changes the API endpoint or response format.

**Mitigation:** The scraper logs the HTTP status code and response preview at DEBUG level. If the response format changes, the JSON parser will return 0 documents (graceful degradation, not a crash). The manual URL textbox remains functional regardless of API changes.

**Risk:** FINMA adds rate limiting or blocks non-browser requests.

**Mitigation:** The scraper uses a standard User-Agent header and makes requests sequentially (not in parallel). At ~12 requests per scrape run (122 documents / 10 per page), the load is negligible. If blocked, the scraper can be extended with configurable delays between requests.

**Risk:** Sitecore GUIDs change.

**Mitigation:** GUIDs are in `application.yml`, not in code. The ADR documents the DevTools procedure for discovering new GUIDs. No redeployment needed for a GUID update.

---

## Lessons Learned

1. **Start with the API, not the page.** The entire HtmlUnit detour could have been avoided by checking DevTools first. For Sitecore/CMS-based sites, the underlying API is almost always cleaner and more stable than scraping the rendered HTML.

2. **Browser emulation is a last resort.** Both Playwright and HtmlUnit add significant complexity (binary dependencies or JS engine limitations) for what is fundamentally an HTTP call. Direct API access is faster, lighter, and more reliable.

3. **ADRs should document the journey.** This ADR originally recommended HtmlUnit. The implementation proved that wrong. Documenting the failure and the pivot to direct API calls is more valuable than a clean "we chose the right thing on the first try" narrative.

---

## References

- [FINMA Document Center](https://www.finma.ch/de/dokumente/)
- [Sitecore Content Search API](https://doc.sitecore.com/xp/en/developers/latest/sitecore-experience-manager/using-the-search-rest-api.html)
- [HtmlUnit GitHub](https://github.com/HtmlUnit/htmlunit) — Evaluated, failed on modern JS
- [Playwright for Java](https://playwright.dev/java/) — Evaluated, rejected due to binary dependency