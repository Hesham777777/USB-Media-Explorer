# Stable signing key — deliberately committed

`usbmedia.p12` (PKCS12, alias/password: `usbmedia`) signs **every** APK this repo publishes,
debug and release alike.

Why it is committed even though `.gitignore` says keystores never go in:

- GitHub Actions runners are ephemeral. With no explicit `signingConfig`, AGP auto-generates a
  fresh random debug keystore on *every* CI run, so each published APK carried a *different*
  signature and Android rejected updates over the previous install with “App not installed”.
- A single stable key committed here makes every build carry the same signature, so new versions
  install **over** old ones and keep all user data (favorites, recent folders, playback
  positions, thumbnail cache).

Scope and limits:

- This key signs direct-install APKs distributed through GitHub Releases only. It is **not** a
  Play Store upload key — before any store publication, replace it with a proper upload key kept
  in CI secrets.
- Validity: 2026-09-05 → 2056-08-28 (RSA 2048, SHA-256).
- Certificate SHA-256 fingerprint:
  `78:21:E2:17:12:7B:1F:12:99:6D:16:20:3F:FE:8A:D6:4E:A8:CC:9C:9C:AA:50:18:FD:18:24:A9:D3:D3:EE:1C`

One-time note: builds published before 2026-09-05 were signed with those random runner keys, so
they must be uninstalled once before installing any build signed with this key. From then on,
updates install over each other without data loss.
