<p align="center">
  <img src="docs/images/banner.jpg" alt="DSH Mobile — the DeepSeek Harness in your pocket" width="100%">
</p>

<h1 align="center">DSH Mobile — DeepSeek Harness Remote</h1>

<p align="center">
  An open-source Android companion that puts your <b>DeepSeek Harness</b> in your pocket.<br>
  Drive sessions, review plans and goals, answer approvals and questions, and get notified
  when the harness finishes — from your phone, through a <b>Cloudflare Tunnel</b> or on your local network.
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

DSH Mobile is an **unofficial companion app** for the
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (MIT). It mirrors the web GUI
feature for feature and uses the harness's own visual language. Android only, Kotlin + Jetpack
Compose.

This fork talks to the harness **directly**. There is no relay plugin to install and nothing to pair:
you give the app the address of your harness — a Cloudflare Tunnel hostname, or a local
`host:port` — plus the auth token `dsh web` prints in the terminal when it starts, and you are in.
The harness's own authentication does the rest, and the tunnel provides the encryption.

> This is a fork of [sorsama/deepseek-harness-mobile](https://github.com/sorsama/deepseek-harness-mobile).
> Upstream reaches the harness through its `dsh-relay` plugin; that path has been removed here.

---

## Screenshots

| Connect | Chat | Trajectory |
|:--:|:--:|:--:|
| <img src="docs/images/home.png" width="240" alt="Connect screen"> | <img src="docs/images/chat.png" width="240" alt="Chat: streamed turns with per-tool icons, tool cards, goal dock and composer"> | <img src="docs/images/trajectory.png" width="240" alt="Trajectory: a per-turn ledger with usage totals"> |
| Harness URL + auth token, recent harnesses with live reachability, auto-connect. | Streamed turns, a glyph per tool, expandable tool cards, permission picker. | The same session as a per-turn ledger with usage totals. |

| Session details | Subagents |
|:--:|:--:|
| <img src="docs/images/session-info.png" width="240" alt="Details panel: context breakdown, goal, plan mode, jobs, queue, subagents, host information"> | <img src="docs/images/subagent.png" width="240" alt="Subagent catalog with continuable children"> |
| Context breakdown, goal, plan mode, background jobs, queued turns, host info, session-log export. | The subagent catalog — open a child's transcript, follow up, or interrupt it. |

## How it works

```
 phone ──HTTPS──▶ Cloudflare edge ──tunnel──▶ cloudflared ──HTTP──▶ dsh web (127.0.0.1:3080)
```

1. `dsh web` listens on loopback, as it always has, and prints a URL containing a one-time
   **auth token**.
2. `cloudflared` on the same computer publishes `127.0.0.1:3080` as `https://dsh.example.com`.
3. The app sends that token to your tunnel URL once. The harness answers with a signed session
   cookie bound to the hostname; the app stores it encrypted (Android Keystore) and sends it on
   every request from then on. The cookie survives harness restarts, so you only paste the token
   again if you sign out or clear the app's data.

The one thing the harness needs to know is that your tunnel hostname is allowed to talk to it —
that is the `trustedHosts` setting in step 2 below.

## Requirements

- Android 8.0+ (minSdk 26).
- A running [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) at
  `0.2.0-rc.1` or newer (`0.2.1-alpha.1` is the baseline this app is validated against; see
  [compatibility](docs/COMPATIBILITY.md)).
- For remote access: a Cloudflare account and [`cloudflared`](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)
  on the computer running the harness. Not needed for same-Wi-Fi or USB use.

## Setup

### 1. Expose the harness with a Cloudflare Tunnel

Create a named tunnel and route a hostname to the harness's loopback port. Once:

```sh
cloudflared tunnel login
cloudflared tunnel create dsh
cloudflared tunnel route dns dsh dsh.example.com
```

`~/.cloudflared/config.yml`:

```yaml
tunnel: dsh
credentials-file: /home/you/.cloudflared/<tunnel-id>.json
ingress:
  - hostname: dsh.example.com
    service: http://127.0.0.1:3080
  - service: http_status:404
```

Then keep it running (`cloudflared tunnel run dsh`, or `cloudflared service install`).

WebSockets, which the app uses for its event stream, pass through Cloudflare Tunnel by default.
Leave the hostname **without** a Cloudflare Access policy: the app authenticates with the harness's
own token and cannot complete an Access login page. If you do want Access in front, add a
bypass rule for the paths the app uses (`/`, `/api/*`), or don't — the harness already refuses
every request that lacks its session cookie.

### 2. Tell the harness to trust the tunnel hostname

The harness only answers `/api` calls whose `Host` header it trusts. By default that is loopback and
its own LAN addresses, so a request arriving via `dsh.example.com` gets **HTTP 403** until the
hostname is added. Two ways, pick one:

**a) Per launch** — pass the flag every time you start the web profile:

```sh
dsh web --trusted-host dsh.example.com
```

**b) Permanently** — add this entry to the web profile's patch layer. The file is
`$DSH_HOME/profiles/web/cordis.patch.yml` (default `~/.dsh/profiles/web/cordis.patch.yml`); create
it as a YAML list if it does not exist, or append the entry to the list that is there:

```yaml
- id: web-runtime
  name: '@deepseek-ai/dsh-web-app'
  config:
    # A patch replaces the whole config block, so keep the shipped keys too.
    openBrowser: !!js ctx.webStartup.openBrowser
    printUrl: true
    surfaceContext: true
    trustedHosts: !!js "[...(ctx.webStartup.trustedHosts ?? []), 'dsh.example.com']"
```

The `!!js` expression keeps any `--trusted-host` flags you still pass on the command line and adds
your tunnel hostname on top. Restart `dsh web` afterwards.

### 3. Start the harness and copy the token

```sh
dsh web
```

The startup line looks like:

```
dsh web: http://127.0.0.1:3080/?token=k7Qx9...
```

Everything after `token=` is the **auth token**. It changes on every restart of `dsh web`.

### 4. Connect from the phone

Install the APK (see [Building](#building), or grab one from this repository's Releases page), open
the app and fill in the two fields:

| Field | What to paste |
|---|---|
| **Harness URL** | `https://dsh.example.com` — or, on the same Wi-Fi, `192.168.1.20:3080` (see [Local network](#local-network-instead-of-a-tunnel)). You can also paste the whole `http://…/?token=…` startup line; the token is read out of it. |
| **Auth token** | The token from step 3. Leave it empty when reconnecting to a harness this phone has already signed in to. |

Tap **Connect**. The app exchanges the token, verifies it is talking to a harness, and opens the
session list. The harness lands in **Recent**, where a tap reconnects with the stored session.
**Auto-connect** can do that for you on launch.

If a connect attempt fails, the app names the cause and the fix:

| The app says | Cause | Fix |
|---|---|---|
| *The harness rejected this address* (403) | Hostname not in `trustedHosts` | Step 2, then restart `dsh web` |
| *…has not signed this phone in* (401) | No session for this harness | Paste the current auth token |
| *The harness did not accept that token* | Token is from an earlier `dsh web` run | Copy the newest startup line |
| *…refused the connection* | Nothing listening on that port | Start `dsh web`; check `cloudflared` points at `127.0.0.1:3080` |
| *Nothing answers to "…"* | Hostname does not resolve | Check the tunnel's DNS route |
| *Something answered…but it is not a DeepSeek Harness* | Tunnel is up, origin is down (Cloudflare 502/530), or wrong port | Start `dsh web`; check the ingress rule |
| *…event stream would not open* | WebSocket blocked on the phone | Disable VPN / private DNS and retry |

### Local network instead of a tunnel

Same Wi-Fi, no Cloudflare: the harness has to listen on the LAN rather than loopback, which it does
not do by default. Apply the one-file patch in [`harness/README.md`](harness/README.md), restart
`dsh web`, and enter the address it prints as `LAN:` — e.g. `192.168.1.20:3080` — plus the token.
This path is plain HTTP: it authenticates but does not encrypt, so use it only on a network you
trust. A tunnel is the recommended way to reach the harness from anywhere else.

**USB / emulator:** `dsh web`, then `adb reverse tcp:3080 tcp:3080`, and connect to
`127.0.0.1:3080` with the token. No patch needed.

**Your own HTTPS reverse proxy** (Caddy, nginx, Traefik) works the same way as a tunnel: paste the
`https://` address, add the hostname to `trustedHosts`, and install the proxy's CA on the phone if it
is not publicly trusted.

## Features

- Connecting — one URL and one token. Recent harnesses with live reachability, auto-connect to the
  last used harness or to one on the same device, encrypted session storage.
- Navigation — the drawers work like Discord's: swipe right from the left edge for the
  workspace-grouped chat list, swipe left to close it, swipe left from the right edge for the
  session details panel.
- Chat — streamed turns with reasoning disclosure, markdown, terminal/diff/read/search/web tool
  cards, a queue dock where you can edit, remove or steer a queued turn, history paging, and
  multi-photo and file attachments. Drafts are kept per host and session.
- Workspace panels — tabbed text, Markdown, image, PDF and isolated HTML previews; native
  terminal controls with a bundled xterm renderer; archived-session restore in Settings.
- Slash commands and skills — the composer checks a `/` line against the session's own command
  catalog and runs it through the harness's command gateway.
- Everything the GUI does — goals (phases, rounds, pause/resume/edit), plan mode and plan review,
  permission approvals, user questions, todo dock, subagents (catalog, follow-ups, interrupt),
  background jobs, workflow runs, skills, model selection, agent presets, automations, plugins,
  session search, trajectory ledger, session export, message feedback.
- Notifications — turn complete, goal complete or blocked, review or question waiting for you.
  A foreground service keeps the connection alive in the background.
- Harness look — the exact DeepSeek Harness design tokens, with light, dark and system themes.
- 11 languages — English, 中文, हिन्दी, Español, Français, العربية, বাংলা, Português, Русский,
  اردو, ไทย (RTL aware).

## Security

**Read [docs/SECURITY.md](docs/SECURITY.md) first.** A signed-in device reaches the whole harness
API, and the agent runs commands on that computer — signing in grants the same power as a shell
there. Keep the token private, use HTTPS (a tunnel or proxy) for anything that leaves your Wi-Fi,
and forget a harness from the app (or clear its data in Settings) on a phone you stop using.

- The session cookie is stored encrypted with a key in the Android Keystore.
- Plain HTTP is only used when you enter a plain `http://` or bare `host:port` address; the app
  shows a warning when it is about to do that to a non-loopback host.
- The only request the app makes to anything other than your harness is an optional GitHub
  release check, which can be switched off in Settings.

## Building

```sh
./gradlew :app:assembleDebug      # debug APK → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # release APK (signed when keystore env is set)
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
```

Needs JDK 17 and the Android SDK (platform 35). The shipped version comes from the git tag: the
release workflow exports `DSH_VERSION_NAME` from the tag name and derives `versionCode` from it.
See [CONTRIBUTING.md](CONTRIBUTING.md) for the development loop against a real harness and the
module layout.

## Repository

| Path | What |
|---|---|
| `core/` | Pure-JVM protocol core: wire DTOs, RPC client, WebSocket mux, reconnect loop, session folding, notification classifier |
| `app/` | Android UI: screens, connection, foreground service, notifications, i18n |
| `mock-harness/` | Ktor mock of the harness `/api` server for tests |
| `tools/capture/` | Records real harness traffic into conformance fixtures |
| `harness/` | LAN patch + guide for same-Wi-Fi use without a tunnel |
| `docs/` | [Architecture](docs/ARCHITECTURE.md), [protocol notes](docs/PROTOCOL.md), [compatibility](docs/COMPATIBILITY.md), [security](docs/SECURITY.md) |

## License

[MIT](LICENSE). Bundled third-party material is listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The DeepSeek Harness and its brand are property
of their respective owners; this project is an independent, community-built remote, forked from
[sorsama/deepseek-harness-mobile](https://github.com/sorsama/deepseek-harness-mobile).
