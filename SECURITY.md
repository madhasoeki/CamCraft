# Security Policy

## Supported Versions

| Version | Supported |
| :--- | :--- |
| `0.0.x` (Latest Release) | Yes |

## Threat Model & Local-First Design

- **Local-only media transport:** CamCraft streams video and audio exclusively over USB (`adb forward` on `127.0.0.1:9876`) or your local Wi-Fi LAN. No video or audio leaves your network.
- **No authentication on LAN socket:** Anyone on the same local Wi-Fi subnet can connect to port `9876` while the Android server is running. Stop the server on your phone when not streaming on untrusted networks, or prefer USB ADB mode.
- **Verified in-app updates:** Desktop (`camcraft-gui` / `camcraft-cli`) and Android release updates query GitHub Releases over HTTPS and verify downloaded payloads against `SHA256SUMS.txt` before installing.

## Reporting a Vulnerability

Please **do not** open public GitHub issues for security vulnerabilities.

Instead, report vulnerabilities privately via:
1. **GitHub Private Vulnerability Reporting** under the **Security** tab of `madhasoeki/CamCraft`, or
2. Emailing `ramadhan.kpj@gmail.com` with steps to reproduce and impact details.

We aim to acknowledge reports within 72 hours and coordinate a fix before public disclosure.
