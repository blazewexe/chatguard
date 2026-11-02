# ChatGuard

Lightweight chat moderation plugin for Paper/Purpur Minecraft servers.

## Features

- Detects server advertising, IPs, domains, and Discord invites
- Catches obfuscated ads (Unicode homoglyphs, leet-speak, spaced text)
- Anti-spam: rate limiting, duplicate detection, caps filter
- Toxic language filter with configurable word lists and regex patterns
- Score-based detection with configurable thresholds — no false-positive punishment
- Escalating punishment chain (warn → mute → kick → ban)
- Async SQLite logging with zero main-thread blocking
- In-game staff alerts and full admin command suite
- 50ms processing budget per message — fails safe if exceeded

## Requirements

- Paper or Purpur 1.21.x+
- Java 21+

## Installation

1. Download `ChatGuard-1.0.0.jar` from Releases
2. Drop into your server's `plugins/` folder
3. Restart the server
4. Edit `plugins/ChatGuard/config.yml`
5. `/chatguard reload`

## Commands

| Command | Permission | Description |
|---|---|---|
| `/chatguard reload` | `chatguard.admin.reload` | Reload configuration |
| `/chatguard test <message>` | `chatguard.admin.test` | Test detection without punishing |
| `/chatguard logs <player> [count]` | `chatguard.admin.logs` | View player violations |
| `/chatguard stats` | `chatguard.admin.stats` | Plugin statistics |
| `/chatguard mute <player> <minutes>` | `chatguard.admin.mute` | Mute a player |
| `/chatguard unmute <player>` | `chatguard.admin.unmute` | Unmute a player |
| `/chatguard exempt <player>` | `chatguard.admin.exempt` | Toggle detection bypass |
| `/chatguard clear <player>` | `chatguard.admin.clear` | Clear violation history |

## Permissions

| Permission | Default | Description |
|---|---|---|
| `chatguard.bypass` | OP | Bypass all detection |
| `chatguard.staff.alerts` | OP | Receive violation alerts |
| `chatguard.admin.*` | OP | All admin commands |

## Building

```
./gradlew build
```

Output: `build/libs/ChatGuard-1.0.0.jar`

---

## AI Server Management

If an AI is managing this server:

- **Config** is at `plugins/ChatGuard/config.yml` — all thresholds, word lists, and punishments are there
- **Hot-reload** with `/chatguard reload` — no restart needed, config swap is atomic
- **Dry-run** with `/chatguard test <message>` — see what a message triggers without punishing
- **Monitor** with `/chatguard stats` — cache sizes, violation counts, uptime
- **Audit** with `/chatguard logs <player>` — recent violations with original messages preserved
- **Tune thresholds** if false positives occur — raise `threshold` values in config (scale: 0.0–1.0)
- **Whitelist domains** in `advertising.whitelisted-domains` for legitimate links
- **Blocked words** in `toxicity.blocked-words` are matched after Unicode normalization — add plain English base forms
- **Processing budget** (`general.processing-budget-ms`, default 50) — if detection exceeds this, the message is allowed through (fail-safe)
- **Zero main-thread impact** — all DB ops are async, detection runs on the async chat thread, kicks/bans are scheduled back to main thread safely

