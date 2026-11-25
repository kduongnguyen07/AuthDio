# HiFiDeck Android — URL Validation & SSRF Defense Test Matrix (`SEC-04`)

**Date:** 2026-11-24  
**Implementation:** `app/src/main/java/com/example/mdownload/core/YouTubeUrlParser.kt`, `app/src/main/java/com/example/mdownload/engine/YtDlpEngine.kt`  
**Automated Verification:** `app/src/test/java/com/example/mdownload/SecurityAndSubprocessP1Test.kt` (all test cases executed and passing under `:app:testDebugUnitTest --rerun-tasks`)

---

## 1. Offline Syntactic & IP-Literal Matrix (`YouTubeUrlParser.normalizeUrl`)

All cases below run strictly offline without DNS or network I/O (safe on main/UI thread).

| Category | Test Input | Expected Result | Actual Result |
| :--- | :--- | :--- | :--- |
| Valid YouTube Watch | `https://www.youtube.com/watch?v=dQw4w9WgXcQ` | Accepted | **PASS** |
| Valid YouTube Short | `https://youtu.be/dQw4w9WgXcQ?t=42` | Accepted | **PASS** |
| Valid YouTube Music | `https://music.youtube.com/watch?v=dQw4w9WgXcQ` | Accepted | **PASS** |
| Valid Public Domain + Port | `https://example.com:8443/audio/track.mp3` | Accepted | **PASS** |
| Valid Public IPv4 | `https://93.184.216.34/track.mp3` | Accepted | **PASS** |
| Valid Public IPv6 | `https://[2606:2800:220:1:248:1893:25c8:1946]/audio.flac` | Accepted | **PASS** |
| Valid IDN Domain | `https://münchen.example/audio.mp3` | Accepted (Punycode normalized) | **PASS** |
| Disallowed Scheme (`file`) | `file:///etc/passwd` | Rejected (`null`) | **PASS** |
| Disallowed Scheme (`javascript`) | `javascript:alert(1)` | Rejected (`null`) | **PASS** |
| Disallowed Scheme (`ftp`) | `ftp://example.com/file.mp3` | Rejected (`null`) | **PASS** |
| Disallowed Scheme (`data`) | `data:text/plain;base64,SGVsbG8=` | Rejected (`null`) | **PASS** |
| Embedded Credentials | `https://user:pass@youtube.com/watch?v=dQw4w9WgXcQ` | Rejected (`null`) | **PASS** |
| Authority Confusion (`@`) | `https://youtube.com@127.0.0.1/admin` | Rejected (`null`) | **PASS** |
| Backslash Authority Confusion | `https://youtube.com\@127.0.0.1/` | Rejected (`null`) | **PASS** |
| Malformed Percent Encoding | `https://example.com/%ZZ` | Rejected (`null`) | **PASS** |
| Control Character Injection | `https://example.com/a\r\nb` | Rejected (`null`) | **PASS** |
| Invalid Port (`0`) | `https://example.com:0/audio` | Rejected (`null`) | **PASS** |
| Invalid Port (`99999`) | `https://example.com:99999/audio` | Rejected (`null`) | **PASS** |
| Single-Label Host (`localhost`) | `http://localhost/admin` | Rejected (`null`) | **PASS** |
| Trailing Dot Localhost | `http://localhost./admin` | Rejected (`null`) | **PASS** |
| Single-Label Internal Host | `http://router/status` | Rejected (`null`) | **PASS** |
| `.local` mDNS Suffix | `http://printer.local/audio` | Rejected (`null`) | **PASS** |
| `.internal` Suffix | `http://metadata.google.internal/computeMetadata/v1/` | Rejected (`null`) | **PASS** |
| `.home.arpa` Suffix | `http://nas.home.arpa/music` | Rejected (`null`) | **PASS** |
| IPv4 Loopback | `http://127.0.0.1/secret` | Rejected (`null`) | **PASS** |
| IPv4 Loopback Alternate | `http://127.1/secret` | Rejected (`null`) | **PASS** |
| IPv4 Unspecified | `http://0.0.0.0/` | Rejected (`null`) | **PASS** |
| IPv4 RFC 1918 (`10/8`) | `http://10.0.0.1/` | Rejected (`null`) | **PASS** |
| IPv4 RFC 1918 (`172.16/12`) | `http://172.16.0.1/` | Rejected (`null`) | **PASS** |
| IPv4 RFC 1918 (`192.168/16`) | `http://192.168.1.1/` | Rejected (`null`) | **PASS** |
| IPv4 Link-Local / Cloud Metadata | `http://169.254.169.254/latest/meta-data/` | Rejected (`null`) | **PASS** |
| IPv4 CGNAT (`100.64/10`) | `http://100.64.0.1/` | Rejected (`null`) | **PASS** |
| IPv4 Multicast (`224/4`) | `http://224.0.0.1/` | Rejected (`null`) | **PASS** |
| IPv4 Broadcast | `http://255.255.255.255/` | Rejected (`null`) | **PASS** |
| Decimal Integer IPv4 Obfuscation | `http://2130706433/` (`127.0.0.1`) | Rejected (`null`) | **PASS** |
| Hex IPv4 Obfuscation | `http://0x7f000001/` (`127.0.0.1`) | Rejected (`null`) | **PASS** |
| Octal IPv4 Obfuscation | `http://0177.0.0.1/` (`127.0.0.1`) | Rejected (`null`) | **PASS** |
| IPv6 Loopback | `http://[::1]/` | Rejected (`null`) | **PASS** |
| IPv6 Unspecified | `http://[::]/` | Rejected (`null`) | **PASS** |
| IPv6 Link-Local | `http://[fe80::1]/` | Rejected (`null`) | **PASS** |
| IPv6 Zone ID | `http://[fe80::1%25eth0]/` | Rejected (`null`) | **PASS** |
| IPv6 ULA (`fc00::/7`) | `http://[fd12:3456:789a::1]/` | Rejected (`null`) | **PASS** |
| IPv6 Multicast | `http://[ff02::1]/` | Rejected (`null`) | **PASS** |
| IPv4-Mapped IPv6 (Loopback) | `http://[::ffff:127.0.0.1]/` | Rejected (`null`) | **PASS** |
| IPv4-Mapped IPv6 (Private) | `http://[::ffff:192.168.0.1]/` | Rejected (`null`) | **PASS** |
| IPv4-Mapped IPv6 (Hex form) | `http://[::ffff:7f00:1]/` | Rejected (`null`) | **PASS** |
| 6to4 Embedded Private IPv4 | `http://[2002:7f00:0001::1]/` | Rejected (`null`) | **PASS** |
| NAT64 Embedded Private IPv4 | `http://[64:ff9b::10.0.0.1]/` | Rejected (`null`) | **PASS** |

---

## 2. Pre-Flight DNS Resolution Matrix (`YouTubeUrlParser.validateResolvedHost`)

Executed off the main thread inside `YtDlpEngine.inspectSource()` and `YtDlpEngine.executeDownload()`. Tested deterministically using an injected mock resolver (zero live external network probes).

| Scenario | Mocked DNS Answer | Expected Result | Actual Result |
| :--- | :--- | :--- | :--- |
| Public IPv4 only | `93.184.216.34` | Allowed (`Right` / valid) | **PASS** |
| Public IPv6 only | `2606:2800:220:1:248:1893:25c8:1946` | Allowed | **PASS** |
| Loopback IPv4 | `127.0.0.1` | Blocked (error) | **PASS** |
| Private RFC1918 IPv4 | `10.1.2.3` | Blocked (error) | **PASS** |
| Mixed Public + Private A/AAAA | `93.184.216.34`, `192.168.1.5` | Blocked (fail-closed if ANY answer is non-public) | **PASS** |
| Link-Local Cloud Metadata | `169.254.169.254` | Blocked (error) | **PASS** |
| IPv6 ULA | `fd00::1` | Blocked (error) | **PASS** |
| Empty / Unresolvable DNS | `UnknownHostException` | Blocked (error) | **PASS** |

---

## 3. Residual Limitations

- `validateResolvedHost` runs pre-flight before invoking `yt-dlp`. It cannot stop **TOCTOU DNS rebinding** between pre-flight resolution and `yt-dlp`'s own socket connect, nor **HTTP 3xx redirects** returned by an initially public origin pointing to an internal IP inside `yt-dlp`.
