# API key creation research

This document records research for a possible future feature. NReader does not
automatically create API keys. The website's authenticated key-management API
is described as first-party/internal; recheck its current documentation and
access conditions before implementing any integration.

## Sources and evidence

- Website settings: `https://nhentai.net/user/settings#apikeys`.
- API documentation: `https://nhentai.net/api/v2/docs#/` and
  `https://nhentai.net/api/v2/openapi.json`.
- The API schema summarized below was inspected on November 9, 2026, version
  `2.0.0+4a31779`. These declarations are not proof of current server behavior.
- On November 13, 2026, a test account could sign into the website and reach
  **Settings → API Keys**. The form offered a required key name, an optional
  purpose, a Cloudflare verification widget, and **Create Key**. An expired
  verification produced a visible error. A fresh verification reached the server,
  which rejected creation because the test account was not old enough. The key
  list remained empty after a page reload.
- The user reports that accounts may need to be approximately 14–15 days old.
  This duration has not been independently confirmed and must not be hardcoded.
- A successful creation, its exact request sequence, proof-of-work action, and
  the website's cleanup interaction have not yet been observed. Do not infer them
  from this record; capture a sanitized trace with an eligible account first.

## Declared API contract

The OpenAPI document describes these endpoints relative to `https://nhentai.net`:

| Operation | Authentication and declared behavior |
| --- | --- |
| `GET /api/v2/captcha` | Public; returns CAPTCHA configuration. |
| `GET /api/v2/pow` | Public; accepts an optional `action` query and returns a proof-of-work challenge. The action for key creation is unverified. |
| `GET /api/v2/user/keys` | User token required; returns key metadata. |
| `POST /api/v2/user/keys` | User token required; subject to the `allow_api_keys` feature gate and rate limits. |
| `DELETE /api/v2/user/keys/{key_id}` | User token required; removes the specified key and is subject to the same feature gate. |
| `GET /api/v2/user` | Accepts a user token or API key and returns the authenticated user profile. |

The declared create body has a required `name` (1–255 characters), optional
`purpose`, and required `pow_challenge`, `pow_nonce`, and `captcha_response`.
The declared successful response has `id`, `key`, and `name`. Key-list entries
include an ID, prefix, name, creation time, and optional last-used time.

## Reproduction and remaining checks

1. In a browser, sign into an eligible test account and open the API Keys section.
   Record the existing key count without recording any key, token, cookie, account
   identifier, or complete network payload.
2. Enter a distinctive temporary name and purpose, complete the site's verification,
   and create one key. Record the sanitized method, endpoint, action parameter,
   request-field names, response status and field names, and any prerequisite
   requests. Do not record verification tokens or proof-of-work values.
3. Verify the new key through NReader and `GET /api/v2/user`; confirm it belongs
   to the intended account. Test rotation separately if two temporary keys are
   available. Record results without storing key values.
4. Revoke only the keys created for this test and confirm their removal. Keep
   the original key count and all unrelated account data intact.

Also determine how the website handles expired verification, proof-of-work
failure, account eligibility, rate limits, cancellation, and duplicate submits.
Use a fresh verification when needed; an observed expired challenge was rejected.
The caller must not claim a successful automatic creation flow until these checks
are complete and the current service contract has been reviewed.
