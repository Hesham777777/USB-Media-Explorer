# Signing key — transitional state (rotation in progress)

`usbmedia.p12` (PKCS12, alias/password: `usbmedia`) is the **legacy fallback** key. It was
committed to keep CI signatures stable, which made it public — and a public signing key is a
compromised signing key (audit CRITICAL #1). The repo is now wired for a rotated key that
never touches git:

1. CI materializes the `USBMEDIA_KEYSTORE_B64` secret into `keystore/ci.p12` (git-ignored)
   and exports `USBMEDIA_STORE_PASSWORD` / `USBMEDIA_KEY_ALIAS` / `USBMEDIA_KEY_PASSWORD`.
2. `app/build.gradle.kts` prefers `ci.p12` + those env vars; it falls back to this committed
   key **only while the secrets are not configured**.
3. Once the owner completes `SETUP_SIGNING.md` (steps 1–4), this file is deleted with
   `git rm keystore/usbmedia.p12` and every build signs with the rotated key.

Until rotation, this key signs direct-install APKs only — never use it for store publication.
Validity: 2026-09-05 → 2056-08-28 (RSA 2048, SHA-256).

**Owner action required: complete `SETUP_SIGNING.md` at the repo root.**
