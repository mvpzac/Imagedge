# Security Policy

## Supported Versions

Only the latest release is actively supported with security fixes. Users are
encouraged to stay on the most recent version.

## Reporting a Vulnerability

If you discover a security vulnerability, please **do not** open a public issue.
Instead, report it privately so it can be addressed before disclosure:

- Open a [private vulnerability report](https://github.com/mvpzac/Imagedge/security/advisories/new)
  on GitHub (preferred), or
- Contact the maintainers via GitHub Issues with the label `security` for
  non-sensitive questions.

Please include:

- The affected version(s)
- A description of the vulnerability and its impact
- Steps to reproduce (if possible)
- Any suggested fix (optional)

You can expect an acknowledgment within 5 business days, and we will work with
you on a timeline for a fix and coordinated disclosure.

## Scope

This project talks to cameras over the local network using vendor protocols
(PTP/IP, UPnP, LiveView, BLE). Security reports are most useful when they cover:

- Unintended network exposure or credential handling
- Data loss or corruption paths (download, Motion Photo packaging, RAW decode)
- Permission or sandboxing issues on Android

## Disclosure

We follow responsible disclosure: fixes are published in a release, and the
vulnerability is disclosed publicly only after a fix is available.
