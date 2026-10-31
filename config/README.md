# OpenTV provider configuration & health checks

This repository serves the mobile app's fixed HTTPS provider manifest:
`https://raw.githubusercontent.com/etumen/opentv-mobile/main/config/providers.json`.

## Controlled publishing

- Initial manifest v1 contains `"providers": {}`: **no provider override**.
- Any change must increase `version`, preserve `"schema": 1`, pass device tests, and be merged by review.
- Unknown provider IDs/fields, invalid CSS or non-HTTPS domains are rejected by the mobile client.
- The client stores the last accepted version and can roll back from **Ayarlar → Geliştirici · Provider Durumu**. A rolled-back version cannot be reloaded until a higher version is published.
- **Domain overrides affect only film/series provider traffic**, not live IPTV, NAS, or player/CDN domains. A new domain must be verified as belonging to the intended provider.
- CSS-based catalogue overrides are opt-in, for existing section IDs only. They may not work with a site's detail/episode/playback parser; verify on a device before publishing one.

## Example manifest (illustrative, NOT deployed)

```json
{
  "schema": 1,
  "version": 2,
  "providers": {
    "ddizibox": {
      "domain": "https://verified-domain.example",
      "listings": {
        "latest": {
          "type": "SERIES",
          "path": "/diziler?page={page}",
          "item": "a[href*='/dizi/']",
          "link": ":self",
          "title": "h3",
          "image": "img",
          "next": "a.next"
        }
      }
    }
  }
}
```

The example domain is intentionally not a real site; do not publish it.

## Monitoring

`.github/workflows/provider-health.yml` runs `scripts/provider_health.py` every six hours (00:17, 06:17, 12:17, 18:17 UTC), on configuration changes, and when manually dispatched. The script checks public website reachability, HTTP status, and HTML link presence. It stores a 14-day JSON report as an Actions artifact and prints the results into the Actions job summary.

This is **monitoring, not auto-repair**: it cannot prove actual stream/playback health; DiziPal is discovered through the existing upstream `DiziPalOrijinal` domain list (failed discovery shows `not_checked`); WAF/challenge responses are marked `blocked_or_unavailable` and require human review. No configuration, domain, code or customer data is automatically changed. GitHub's scheduled jobs may be delayed; in inactive public repositories GitHub may disable schedules after 60 days.

The workflow has `contents: read` permission and no stored credentials. The remotely fetched manifest is data-only but does **not** yet have independent signature verification; HTTPS and repository access control remain the trust boundary.
