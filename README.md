<div align="center">
  <img src="assets/img/Discord_MCP_full_logo.svg" width="60%" alt="DeepSeek-V3" />
</div>
<hr>
<div align="center" style="line-height: 1;">
    <a href="https://github.com/modelcontextprotocol/servers" target="_blank" style="margin: 2px;">
        <img alt="MCP Server" src="https://badge.mcpx.dev?type=server" style="display: inline-block; vertical-align: middle;"/>
    </a>
    <a href="https://discord.gg/5Uvxe5jteM" target="_blank" style="margin: 2px;">
        <img alt="Discord" src="https://img.shields.io/discord/936242526120194108?color=7389D8&label&logo=discord&logoColor=ffffff" style="display: inline-block; vertical-align: middle;"/>
    </a>
    <a href="https://github.com/SaseQ/discord-mcp/blob/main/LICENSE" target="_blank" style="margin: 2px;">
        <img alt="MIT License" src="https://img.shields.io/github/license/SaseQ/discord-mcp" style="display: inline-block; vertical-align: middle;"/>
    </a>
</div>


## 📖 Description

A [Model Context Protocol (MCP)](https://modelcontextprotocol.io/introduction) server for the Discord API using [(JDA)](https://jda.wiki/),
designed to integrate Discord bots with MCP-compatible applications such as Claude, ChatGPT etc. It allows AI assistants to interact with 
Discord by managing channels, sending messages, and retrieving server information. Ideal for building powerful Discord automation and AI-driven workflows.


## 🔬 Installation

### ► 🐳 Docker Installation (Recommended)

> [!NOTE]
> Docker installation is required. Full instructions can be found on [docker.com](https://www.docker.com/products/docker-desktop/).

#### 1) Set local env variables
```bash
export DISCORD_TOKEN="YOUR_DISCORD_BOT_TOKEN"
export DISCORD_GUILD_ID=""
export SPRING_PROFILES_ACTIVE=http
# Only if you want download_attachment. Must match the container path mounted below,
# not a host path — see Security notes.
export DISCORD_MCP_DOWNLOAD_ROOT=/var/lib/discord-mcp/downloads
```

> [!TIP]
> There is deliberately no `DISCORD_MCP_FILE_ROOT` here. It is needed only for local-path
> uploads — `send_file`'s `filePath`, and `set_guild_scheduled_event_image` when you are not
> using its `imageUrl` — and a deployment that only ever covers events from a CDN link needs
> no filesystem grant at all. To enable it, add
> `export DISCORD_MCP_FILE_ROOT=/var/lib/discord-mcp/uploads` and the uploads mount below,
> pointing it at a directory that holds nothing but uploads. See Security notes first.

> [!IMPORTANT]
> Instructions for creating a Discord bot and retrieving its token can be found [here](https://discordjs.guide/legacy/preparations/app-setup).
> On the bot's Developer Portal page, enable **Server Members Intent** under Privileged Gateway
> Intents. The Developer Portal's **Message Content Intent** grant controls whether Discord returns
> ordinary message bodies across its APIs. Enable that Portal grant when message-reading tools are
> required. This server reads messages through Discord's REST API and deliberately does not request
> the Message Content Gateway intent.

> [!TIP]
> The `DISCORD_GUILD_ID` env variable is optional.
> 
> When provided, it sets a default Discord server ID so any tool that accepts a `guildId` parameter can omit it.

#### 2) Run the Docker container
```bash
docker run -d -i \
  --name discord-mcp \
  --restart unless-stopped \
  -p 8085:8085 \
  -e SPRING_PROFILES_ACTIVE \
  -e DISCORD_TOKEN \
  -e DISCORD_GUILD_ID \
  -e DISCORD_MCP_DOWNLOAD_ROOT \
  -e DISCORD_MCP_ALLOWED_GUILDS \
  -e DISCORD_MCP_ALLOWED_TOOLS \
  -e DISCORD_MCP_WRITE_MODE \
  -e DISCORD_EXPECTED_BOT_ID \
  -v discord-mcp-downloads:/var/lib/discord-mcp/downloads \
  saseq/discord-mcp:latest
```

> [!TIP]
> `-e DISCORD_MCP_DOWNLOAD_ROOT` and the downloads `-v` are only needed for
> `download_attachment`; leave them off and that tool refuses, and nothing else changes.
>
> **For local-path uploads** — `send_file`'s `filePath`, and `set_guild_scheduled_event_image`
> when you are not using its `imageUrl` — run `mkdir -p uploads` first, then add these two
> lines to the command above:
>
> ```
>   -e DISCORD_MCP_FILE_ROOT \
>   -v "$PWD/uploads":/var/lib/discord-mcp/uploads:ro \
> ```
>
> Kept out of the block rather than shipped inert, so pasting it does not leave a root-owned
> `./uploads` behind for a feature you did not enable — Docker creates a missing bind-mount
> source as `root:root`. `docker-compose.yml` ships the same pair commented, for the same
> reason.
>
> The two mounts are different shapes on purpose. Downloads are a **named volume**, because
> the app writes them and they should survive `docker rm`. Uploads are a **read-only bind
> mount**, because the operator puts files there and the app only reads them — a named volume
> cannot be written from the host without `docker cp`, which makes "put the file there"
> impossible to act on, and `:ro` enforces at the mount what the docs describe.

Default MCP endpoint URL (HTTP profile): `http://localhost:8085/mcp`

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🐋 Docker Compose Installation
    </summary>

#### 1) Clone the repository
```bash
git clone https://github.com/SaseQ/discord-mcp
```

#### 2) Go to the project directory
```bash
cd discord-mcp
```

#### 3) Create local runtime env
```bash
cat > .env <<EOF
SPRING_PROFILES_ACTIVE=http
DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN>
DISCORD_GUILD_ID=
# Optional, enables download_attachment. Container path, matching the named volume.
DISCORD_MCP_DOWNLOAD_ROOT=/var/lib/discord-mcp/downloads
# Optional, enables local-path uploads. Uncomment to grant it — send_file's filePath and
# set_guild_scheduled_event_image's filePath. The latter works via imageUrl without this, so
# a deployment covering events from CDN links should leave it off. Container path, matching
# the ./uploads bind mount. See Security notes before pointing it at the download path.
#DISCORD_MCP_FILE_ROOT=/var/lib/discord-mcp/uploads
EOF
```

#### 4) Start one shared MCP server container
```bash
docker compose up -d --build
```

> [!TIP]
> For local-path uploads there are **three** commented lines, not two: `DISCORD_MCP_FILE_ROOT`
> in `.env`, the matching `DISCORD_MCP_FILE_ROOT:` line under `environment:` in
> `docker-compose.yml`, and the `./uploads` volume line below it. Miss the middle one and the
> variable never reaches the container, so the tool refuses with "Set DISCORD_MCP_FILE_ROOT"
> pointing at a variable you did set.
>
> Run `mkdir -p uploads` first: Docker creates a missing bind-mount source as `root:root`,
> which would leave you a directory needing `sudo` to put posters into — the opposite of the
> point. All three stay commented by default so a deployment that covers events from CDN links
> gets neither the filesystem grant nor a stray root-owned directory.

#### 5) Verify
```bash
docker ps --filter name=discord-mcp
curl -fsS http://localhost:8085/actuator/health
```

> [!TIP]
> You do not need to set `LOGGING_PATTERN_CONSOLE` manually.
> Logging is configured automatically for both `http` and legacy `stdio` modes.

Default MCP endpoint URL (HTTP profile): `http://localhost:8085/mcp`

Health endpoint (Actuator): `http://localhost:8085/actuator/health`

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🔧 Manual Installation
    </summary>

#### 1) Clone the repository
```bash
git clone https://github.com/SaseQ/discord-mcp
```

#### 2) Build the project

> NOTE: Maven installation is required to use the mvn command. Full instructions can be found [here](https://www.baeldung.com/install-maven-on-windows-linux-mac).

```bash
cd discord-mcp
mvn clean package # The jar file will be available in the /target directory
```

#### 3) Configure AI client
Run the JAR as a long-running server:

```bash
DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN> \
DISCORD_GUILD_ID= \
SPRING_PROFILES_ACTIVE=http \
java -jar /absolute/path/to/discord-mcp-1.0.0.jar
```

> NOTE: The `DISCORD_GUILD_ID` environment variable is optional. When provided, it sets a default Discord server ID so any tool that accepts a `guildId` parameter can omit it.

Default MCP endpoint URL (HTTP profile): `http://localhost:8085/mcp`

</details>

## 🔒 Security notes

### Multi-server deployment policy

All policy variables below are optional so existing deployments retain their current behavior.
For an agent-facing bot installed in more than one server, configure them explicitly:

When `DISCORD_MCP_ALLOWED_GUILDS`, `DISCORD_MCP_ALLOWED_TOOLS`, or preview
`DISCORD_MCP_WRITE_MODE` activates deployment policy, argument keys absent from a tool's generated
schema are rejected rather than ignored. Audit-only deployments retain upstream argument handling.

- `DISCORD_MCP_ALLOWED_GUILDS`: comma-separated guild snowflakes. Every call must name an
  allowed guild or contain a channel ID that resolves to one. When the wrapper cannot resolve a
  guild, it refuses the call rather than guessing. If a tool declares `guildId`, an allowed
  `DISCORD_GUILD_ID` remains its default when the argument is omitted. Tools without a `guildId`
  parameter can never borrow that default. Every supplied channel-like target must resolve from
  the JDA cache; uncached channels and archived threads, including auto-archived forum posts, are
  refused even when a guild is explicit. This matches the existing service implementations, which
  already depend on the same cache for those channel and thread lookups. Activating a guild
  allowlist also removes webhook URL/ID operations, invite-code operations, and private-message
  operations from tool discovery because those global targets cannot prove guild scope, even when
  `DISCORD_MCP_ALLOWED_TOOLS` is unset.
  Upgrade note: replace any copied `OPTIONAL_DEFAULT_SERVER_ID` value before enabling policy;
  policy-active startup rejects a non-snowflake `DISCORD_GUILD_ID` instead of ignoring it.
- `DISCORD_MCP_ALLOWED_TOOLS`: comma-separated exact tool names. Unknown names fail startup and
  tools not named here are not exported to MCP clients. Setting this variable also enables strict
  generated-schema checking, so caller-supplied argument keys that the selected tool does not
  declare are rejected. When a guild allowlist is active, global target tools that cannot prove a
  guild are rejected at startup and must be omitted: webhook URL/ID operations, invite-code
  lookup/deletion, and private-message operations. Whenever deployment policy is active and this
  variable is unset, credential-returning reads (`list_webhooks`, `list_invites`, and
  `get_invite_details`) are omitted by default. Name a guild-scoped one explicitly only when its
  caller is allowed to receive those credentials or invite material.
- `DISCORD_MCP_WRITE_MODE`: `preview` returns `WRITE_PREVIEW` plus the proposed argument object
  for every mutation without calling Discord. Large payloads are bounded and hashed rather than
  echoed in full. `allow` executes writes. Unknown tool names are
  classified as writes, so new tools do not silently become read-only. Preview is a write gate,
  not a data-isolation mode: allowed read tools still execute and can return sensitive data.
  In particular, omit `list_webhooks`, `list_invites`, and `get_invite_details` unless the caller
  is explicitly allowed to receive their credentials or invite material. `create_webhook` and
  `create_invite` are the write-side equivalents; allowing them lets their results return new
  credentials after Preview is changed to `allow`.
  Policy-active parsing retains the raw request while validating a JSON tree before the delegate
  binds it. Policy authorization releases the parsed payload tree before the delegate performs its
  network call, retaining only bounded audit identifiers. A deployment that exports `send_file` at
  its 50 MiB maximum must still budget heap for the raw request, delegate binding, and base64 decode,
  or omit that tool when running with a smaller heap.
- `DISCORD_EXPECTED_BOT_ID`: refuses startup when a valid token authenticates the wrong bot.
- `DISCORD_MCP_ACCESS_TOKEN_FILE`: HTTP-only bearer credential, read from a mounted file. When
  set under the STREAMABLE protocol, the configured MCP endpoint returns `401` unless the request
  has the exact `Authorization: Bearer ...` header. Startup fails unless the protocol is explicitly
  configured as STREAMABLE while the bearer is enabled. Health checks remain available without the
  bearer; every other current or future servlet path defaults to protected. Use a separate random
  token of at least 32 characters, never the Discord bot token. Token files over 4096 bytes are
  rejected. The file is read once at startup, so restart the process after rotating it. Only the
  exact default `/actuator/health` path is public; custom management paths and health subpaths remain
  bearer-protected. Do not set `spring.mvc.servlet.path` with the bundled health check. If a custom
  servlet path is required, keep health protected and update the deployment health check to match.
  Bearer-enabled startup rejects any configured `management.server.port`, because a separate
  management servlet context would be outside this filter.
  Keep the bearer file outside `DISCORD_MCP_FILE_ROOT` and `DISCORD_MCP_DOWNLOAD_ROOT`;
  startup rejects lexical and resolved containment so tools cannot read or overwrite it. The token
  must use printable ASCII without whitespace so it can be represented in an HTTP header.
- `DISCORD_MCP_AUDIT_FILE`: append-only JSONL tool audit. It records tool, outcome, write mode,
  a per-process salted arguments hash, and a per-call invocation ID that pairs `started` with its
  terminal record under concurrency. When deployment policy is active, it also records resolved
  guild IDs and nests selected declared Discord object IDs under `argumentIds`. Audit-only
  deployments record an empty `guildIds` array and omit argument-provided IDs because no active
  policy schema has established those fields; ignored undeclared keys therefore cannot shape
  reserved audit fields or inflate records. It deliberately does
  not record message bodies, invite credentials, or other complete arguments. The random salt
  prevents practical dictionary recovery of low-entropy inputs while preserving correlation within
  one process lifetime; hashes intentionally change after restart. A `tool-returned`
  outcome means the MCP tool returned to its caller; it does not claim that an asynchronously
  queued Discord mutation later succeeded. Use readback for consequential writes. The active file rotates to one `.1`
  backup before the next append would exceed `DISCORD_MCP_AUDIT_MAX_BYTES` (10 MiB by default),
  so the two files remain bounded. Set a value of at least 4096 bytes if a different cap is needed.
  This cap is a disk bound, not a retention guarantee. Lowering it discards any active or rotated
  file already above the new cap and writes a warning to the operational log. High call volume can
  rotate older evidence out of both files; forward audit records to a durable log sink when
  retention matters. Keep the
  audit file outside `DISCORD_MCP_FILE_ROOT` and `DISCORD_MCP_DOWNLOAD_ROOT` so an MCP tool cannot
  read or write the audit trail. Startup rejects either configured path when its lexical or resolved
  location contains the audit file.
  The sink must be a regular file, not a symbolic link, device, or FIFO, and neither it nor its `.1`
  rotation may be the configured `logging.file.name`; startup rejects lexical and resolved aliases. On POSIX
  filesystems, startup creates or tightens the active audit file to owner read/write (`0600`) and
  preserves those permissions when rotation creates a new active file.
  `DISCORD_MCP_AUDIT_MAX_BYTES` is parsed only when `DISCORD_MCP_AUDIT_FILE` is configured, so an
  otherwise unused legacy value does not block startup.

Example hardened HTTP profile:

```bash
export DISCORD_MCP_ALLOWED_GUILDS=123456789012345678,234567890123456789
export DISCORD_MCP_ALLOWED_TOOLS=get_server_info,list_channels,read_messages,send_message,edit_message
export DISCORD_MCP_WRITE_MODE=preview # Change to allow only after reviewing previews.
export DISCORD_EXPECTED_BOT_ID=345678901234567890
export DISCORD_MCP_ACCESS_TOKEN_FILE=/run/secrets/discord-mcp-access-token
export DISCORD_MCP_AUDIT_FILE=/var/lib/discord-mcp/audit.jsonl
export DISCORD_MCP_AUDIT_MAX_BYTES=10485760
```

For plain `docker run`, create the gitignored repo-local file `./discord-mcp-access-token`, then add
these arguments to the command above. The image runs as its unprivileged `app` user, so verify that
user can read the mounted file. An operator-owned mode `0600` file normally cannot be read through
this bind mount. Prefer a secret-store mount, or use narrowly scoped ownership or ACLs for the
container UID/GID. Do not solve this with a world-readable host file. The bind mount is required;
forwarding only the container pathname makes startup fail because the credential is not present in
the container.

```bash
  --mount type=bind,src="$PWD/discord-mcp-access-token",dst=/run/secrets/discord-mcp-access-token,readonly \
  --mount type=volume,src=discord-mcp-audit,dst=/var/lib/discord-mcp \
  -e DISCORD_MCP_ACCESS_TOKEN_FILE \
  -e DISCORD_MCP_AUDIT_FILE \
  -e DISCORD_MCP_AUDIT_MAX_BYTES \
```

The named audit parent at `/var/lib/discord-mcp` and the more-specific downloads child mount at
`/var/lib/discord-mcp/downloads` are intentional. Docker applies the child mount over its parent,
so downloads still use their dedicated volume while the audit remains in its separate volume.

The allowlist is application-enforced and complements,
rather than replaces, Discord role hierarchy, channel overrides, and least-privilege bot grants.
Use separate runtime profiles for different guild and write scopes instead of widening one
always-on process.

**Upgrade note:** the generic `docker-compose.yml` now binds its unauthenticated default endpoint
to host loopback. Existing clients on other hosts lose access until an authenticated,
deployment-specific override deliberately republishes it. The generic Compose file
does not forward the bearer-token or audit-file paths because it cannot conditionally create the
required secret bind mount and persistent audit volume. Add both in a deployment-specific Compose
override, or use a launcher that creates those mounts explicitly. Deliberately publishing beyond
loopback requires an authenticated deployment-specific override.
The audit `started` record is fail-closed: if it cannot be appended, the tool is not called. A
completion-record failure after Discord has already responded is returned as a warning without
turning the completed action into an MCP failure that invites a duplicate retry.

### `DISCORD_MCP_FILE_ROOT`

Optional. The single directory that `send_file` and `set_guild_scheduled_event_image` may
read local `filePath` uploads from.

> **Two rules, and the rest of this section is why.** Point it at a directory only you write
> to. Never point it at `DISCORD_MCP_DOWNLOAD_ROOT`, or at a directory containing it.

**Unset (default), local paths are refused.** `send_file` still works via `fileUrl` or
base64 `fileData`, and `set_guild_scheduled_event_image` still works via `imageUrl` —
unset refuses only local `filePath`. Set this only if you need local-path uploads, and
point it at a directory that holds nothing but uploads:

```bash
export DISCORD_MCP_FILE_ROOT=/var/lib/discord-mcp/uploads
```

Or inline on the launch command, alongside `DISCORD_TOKEN`. A bare assignment
without `export` stays a shell variable and never reaches the Java process, so
Spring sees it as unset and `send_file` keeps refusing local paths.

Why it defaults to off: every tool here is callable by an LLM, and an agent's context can
be poisoned by any message it reads. An unconstrained `filePath` means one tool call can
post `/proc/self/environ` — bot token included — into a Discord channel. Paths are resolved
with `toRealPath()` and prefix-checked, so symlinks out of the root are rejected too. A
filesystem root (`/`) is rejected, since it would confine nothing.

Run the server as a dedicated unprivileged user regardless. The env var is a guard, not a
substitute for one.

**On upgrading:** a deployment that already set this for `send_file` gains
`set_guild_scheduled_event_image` when the jar is updated, with no config change. That is a
narrower case than the fallback `DISCORD_MCP_DOWNLOAD_ROOT` refuses below: the filesystem
grant is identical — same root, same read, no new directory.

**What makes it acceptable is the root holding only what you put there**, which is the rule
at the top of this section and the one the rest of it defends. The format check is a second
bound and a much weaker one: a cover is rejected unless its bytes begin with a PNG or JPEG
signature, so no `.env` or `/proc/self/environ` leaves this way — but it is a check on the
first 3–8 bytes, not image validation. A file that does not *start* like an image cannot
leave; a real image with data appended to it still can, and three bytes are trivial to
prepend to anything you control. Treat it as what stops an accident, not as what would stop
someone who can write into the root.

The destination is *wider* rather than narrower, which is worth knowing: an event
cover is served from `guild-events/{event_id}/{hash}.png`, an unsigned, non-expiring,
unauthenticated URL, where a message attachment sits behind a signed expiring link inside a
permission-gated channel. Anything that does reach a cover is more durable and more public
than the same bytes posted to a channel. Deployments that filter tools by name do not
acquire this one at all until they list it.

If you want the upgrade to be inert on your deployment, refuse the tool by name until you
have decided — that control already exists and needs no new variable. A separate
`DISCORD_MCP_COVER_ROOT` was considered and not added, because the grant a second name would
describe is the same grant.

**Compose deployments, specifically:** `docker-compose.yml` did not declare
`DISCORD_MCP_FILE_ROOT` before, so a value in `.env` never reached the container and local
paths stayed refused whatever it said. It is declared now, but commented out, so that an
upgrade cannot activate a setting an operator left behind after it appeared to do nothing.
Uncomment it and the uploads volume together.

**The `imageUrl` path needs no configuration**, so it is worth naming what it permits by
default: a model that has been talked into it can fetch any public HTTPS image under 5 MB and
pin it to a permanent, unauthenticated `discordapp.com` URL. That is bounded — two formats,
one cover per event, `MANAGE_EVENTS` required, and the tool is name-filterable — and it is
the same shape as what `send_file`'s `fileUrl` already allows. Worth knowing rather than
worth blocking, but the section above reasons about local files leaving, and this is the path
that is on out of the box.

**You probably do not need this for `set_guild_scheduled_event_image`.** That tool takes an
`imageUrl` as well as a `filePath`, and a poster already posted to Discord has a CDN URL, so
the common case — put an image that is already in Discord onto an event — needs no
filesystem grant at all. The URL goes through the same `RemoteFetchGuard` as `send_file`'s
`fileUrl`.

**Pointing this at `DISCORD_MCP_DOWNLOAD_ROOT`** chains `download_attachment` into the tools
that read local paths. It is a real widening: `send_file` can then read anything
`download_attachment` saved, and what it saved was chosen by whoever got the agent to call
it. Since `imageUrl` covers the case that used to motivate it, treat this as something to do
only when you have a reason beyond convenience.

It also cancels the format argument made above. That reasoning holds only because an upload
root contains what the operator put there; once the two roots are one directory, the bytes
were chosen by whoever caused the download, and making them begin with a PNG signature is
free.

So `set_guild_scheduled_event_image` refuses a local `filePath` when the two roots overlap —
equal, or either nested inside the other — and says which two variables collide. `send_file`
and `download_attachment` are unchanged: the chained root was always a widening for them, it
is documented above, and turning it into a startup failure would break deployments that chose
it deliberately. `imageUrl` is unaffected — it reads no files.

The server also warns once at startup when the two roots overlap, or when either is set to a
directory that does not resolve. The per-call refusal above is a tool result: it reaches the
model that made the call, not the person who set the variables.

Where that warning lands depends on the profile. Under `http` — what Compose runs — it goes
to the console, so `docker logs` has it. Under the stdio profile it goes to the log file only,
because stdout belongs to the MCP protocol there; check `./target/logs/discord-mcp-server.log`
(or wherever `logging.file.name` points) rather than expecting it on screen.

**That refusal raises the cost of the direct path; it does not close it.** With the roots
chained, the same end state is two calls away: `download_attachment` writes the chosen file,
`send_file` posts it to a channel — no format check there, and it reads the same root — and
`imageUrl` pins the resulting CDN link as a permanent cover. Nothing short of separating the
two directories removes that, so read the refusal as a guard against reaching the state by
accident, not as the reason the shared variable is acceptable. That reason is the upload root
containing only what you put in it.

### `DISCORD_MCP_ALLOW_SHARED_ROOT`

Optional. Set it to exactly `true` to keep local `filePath` covers working when the two roots
overlap.

```bash
export DISCORD_MCP_ALLOW_SHARED_ROOT=true
```

**Only the string `true` enables it**, case-insensitively and trimmed. `1`, `yes` and `on` do
not. A variable whose only job is to drop a check should not be satisfiable by accident.

**On Docker, it has to reach the container.** `docker-compose.yml` declares it under
`environment:`, so a value in `.env` is enough there — unlike `DISCORD_MCP_FILE_ROOT`, which
also needs its line uncommented. With plain `docker run`, add `-e DISCORD_MCP_ALLOW_SHARED_ROOT`
alongside the `-e DISCORD_MCP_FILE_ROOT` from the uploads tip above. Miss it and the refusal
below tells you to set a variable you already set.

This is for the deployment that keeps **one media directory on purpose** — an agent host where
what it downloads, what people send it, and what it generates all land in one place, and covers
get set from there. On that layout the refusal above costs the `filePath` branch and buys
nothing, because the paragraph above is true: `download_attachment` → `send_file` → `imageUrl`
already reaches the same end state with no local path at all, and a host running one media
directory has granted all three.

It changes nothing else. The magic-byte check, the size ceiling, the confinement to
`DISCORD_MCP_FILE_ROOT` and the symlink refusal all still apply — this drops one comparison
between two configured directories, not a check on the file. The startup warning still fires
every boot, with different wording, because "point them at separate directories" is the wrong
advice once the sharing is deliberate and the exposure is still worth stating to whoever
inherits the host.

Leave it unset unless the shared directory is the point. Two directories remain the better
configuration for anything that has a choice.

### `DISCORD_MCP_DOWNLOAD_ROOT`

Optional. The single directory that `download_attachment` may write saved attachments into.

**Unset (default), downloads are refused.** `download_attachment` is the only *tool* that
writes to the filesystem — the process still writes its own logs — and with this unset it
fails immediately and nothing else changes.

```bash
export DISCORD_MCP_DOWNLOAD_ROOT=/var/lib/discord-mcp/downloads
```

**It does not fall back to `DISCORD_MCP_FILE_ROOT`, deliberately.** Reading a directory
and writing to it are different grants. An existing deployment set `DISCORD_MCP_FILE_ROOT`
to allow local-path *uploads*; if downloads inherited it, upgrading the jar would hand an
LLM-driven tool write access to that directory with no configuration change and nothing to
notice. Point both at the same directory if you want that — but as a decision, not a
default, and note that `set_guild_scheduled_event_image` refuses a local `filePath` when the
two overlap, for the reason given under `DISCORD_MCP_FILE_ROOT` above. `send_file` and
`download_attachment` still work on a shared root; covers must come from `imageUrl` there,
unless you set [`DISCORD_MCP_ALLOW_SHARED_ROOT`](#discord_mcp_allow_shared_root).

Files are named `<attachmentId>-<sanitized original name>`. The attachment ID makes names
unique across attachments; re-downloading the same one replaces its own file. Uploader
filenames are untrusted, so they are reduced to a single path component — no traversal, no
hidden files. Writes go to a temporary file in the same directory and are moved into place,
so a failed write cannot destroy an already-saved copy and a symlink at the target is
replaced rather than followed.

Saved files are `rw-r-----`. If the process reading them runs as a different user from the
server, that user needs to be in the file's **group** — directory ownership will not do it,
and neither will a POSIX default ACL, since the ACL is masked by the mode the file is
created with. The two arrangements that work:

- run the MCP server and its consumer as the same user (the usual case, where the client
  spawns the server), or
- make the download directory `setgid` and owned by a group both accounts are in, so saved
  files inherit that group:

```bash
# 2750, not 2770: the group needs read and traverse to open the 0640 files, not write.
# A consumer in this group should not be able to delete or replace the archive.
sudo install -d -m 2750 -o discord-mcp -g attachments /var/lib/discord-mcp/downloads
sudo usermod -aG attachments the-consuming-user
```

Per call: 50 MB per attachment, 100 MB total. **Nothing caps the number of calls.** The
hazard is smaller than that sounds — names are keyed by attachment ID, so re-downloading
the same attachment overwrites in place, and filling a volume needs a stream of *distinct*
attachments that someone has actually uploaded to Discord under its own rate limits. Still
worth bounding, since the Compose default is a plain named volume on the Docker host's root
filesystem with no size limit of its own. Point it at a dedicated device:

```yaml
volumes:
  discord-mcp-downloads:
    driver_opts:
      type: ext4
      device: /dev/disk/by-label/discord-mcp
```

A `tmpfs` volume would also bound the size, but it charges it to RAM and empties on every
restart — which defeats the reason this is a named volume at all.

## 🔗 Connections

### ► 🗞️ Default config.json Connection

Recommended (HTTP singleton mode):
```json
{
  "mcpServers": {
    "discord-mcp": {
      "url": "http://localhost:8085/mcp"
    }
  }
}
```

Legacy mode (stdio, starts a new process/container per client session):
```json
{
  "mcpServers": {
    "discord-mcp": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "-e",
        "DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN>",
        "-e",
        "DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID>",
        "saseq/discord-mcp:latest"
      ]
    }
  }
}
```

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        ⌨️ Claude Code Connection
    </summary>

Recommended (HTTP singleton mode):
```bash
claude mcp add discord-mcp --transport http http://localhost:8085/mcp
```

Legacy mode (stdio, starts a new process/container per client session):
```bash
claude mcp add discord-mcp -- docker run --rm -i -e DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN> -e DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID> saseq/discord-mcp:latest
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🤖 Codex CLI Connection
    </summary>

```bash
codex mcp add discord-mcp --url http://localhost:8085/mcp
codex mcp list
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🦞 OpenClaw Connection
    </summary>

Run this command:
```bash
openclaw mcp set discord-mcp '{"url":"http://localhost:8085/mcp","transport":"streamable-http"}'
openclaw mcp list
```

OR

Pasting the following configuration into your OpenClaw `~/.openclaw/config.json` file:
```json
{
  "mcp": {
    "servers": {
      "discord-mcp": {
        "url": "http://localhost:8085/mcp",
        "transport": "streamable-http"
      }
    }
  }
}
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🖲 Cursor Connection
    </summary>

Go to: `Settings` -> `Cursor Settings` -> `MCP` -> `Add new global MCP server`

Pasting the following configuration into your Cursor `~/.cursor/mcp.json` file is the recommended approach. You may also install in a specific project by creating `.cursor/mcp.json` in your project folder. See [Cursor MCP docs](https://docs.cursor.com/context/model-context-protocol) for more info.
```json
{
  "mcpServers": {
    "discord-mcp": {
      "url": "http://localhost:8085/mcp"
    }
  }
}
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🚀 n8n Connection
    </summary>

#### Connect in n8n
1. Open n8n and add an **MCP Client** node.
2. Choose **HTTP** or **Streamable HTTP** transport (depending on your n8n version/node options).
3. Set the server URL to: `http://localhost:8085/mcp`
4. Save the node and test the connection.
5. After connecting, you can use the available Discord tools exposed by `discord-mcp` inside your workflow.

#### Notes
- If n8n is running in Docker, `localhost` may point to the n8n container itself, not your host machine.
- In that case, use the Docker service name or another reachable host, for example: `http://discord-mcp:8085/mcp`

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🖥 Claude Desktop Connection
    </summary>



STDIO local config (Default, legacy):
> Past the following configuration into your Claude Desktop `claude_desktop_config.json` file.
```json
{
  "mcpServers": {
    "discord-mcp": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "-e",
        "DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN>",
        "-e",
        "DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID>",
        "saseq/discord-mcp:latest"
      ]
    }
  }
}
```

Remote MCP Connector:
1. Open Claude Desktop and go to `Settings` -> `Connectors`.
2. Add a custom connector and set MCP URL to your server endpoint (for example `https://<PUBLIC_HOST>/mcp`).
3. Save and reconnect.

> Claude Desktop remote connectors are managed via Connectors UI (not `claude_desktop_config.json`).
> `http://localhost:8085/mcp` is reachable only from your machine. For Claude Desktop remote connectors, expose the endpoint with public HTTPS (for example tunnel/reverse proxy).

</details>


## Testing

Run unit tests:

```bash
mvn test
```

Optional live Discord integration tests are included and are skipped unless `DISCORD_TOKEN` and `DISCORD_GUILD_ID` are set. They are read-only and validate member lookup/search against a real guild using the bot's own guild member record.

```bash
export DISCORD_TOKEN="YOUR_DISCORD_BOT_TOKEN"
export DISCORD_GUILD_ID="YOUR_SERVER_ID"
mvn -Dtest=DiscordLiveIntegrationTest test
```

Additional optional live checks:

```bash
export DISCORD_TEST_MEMBER_QUERY="member-name-or-prefix" # optional override; defaults to the bot username
export DISCORD_TEST_CHANNEL_ID="TEXT_CHANNEL_ID_READABLE_BY_THE_BOT"
mvn -Dtest=DiscordLiveIntegrationTest test
```


## 🛠️ Available Tools

#### Server Information
- [`get_server_info`](): Get detailed discord server information

#### User Management
- [`get_user_id_by_name`](): Get a Discord user's ID by username in a guild for ping usage `<@id>`
- [`get_member_by_id`](): Get a Discord guild member by stable user ID, including username, global name, nickname, effective name, bot flag, join time, and roles
- [`search_members`](): Search Discord guild members by ID, username, global name, nickname, or effective name and return identity candidates with stable user IDs
- [`send_private_message`](): Send a private message to a specific user
- [`edit_private_message`](): Edit a private message from a specific user
- [`delete_private_message`](): Delete a private message from a specific user
- [`read_private_messages`](): Read private message history from a specific user (includes author IDs, attachment metadata, supports `count` 1-100 and optional cursor: `before` or `after` or `around`)

#### Message Management
- [`send_message`](): Send a message to a specific channel
- [`send_file`](): Send a file (attachment) to a specific channel via local path, URL, or base64, with an optional message (max 50MB, Discord-boost dependent). Local `filePath` uploads require [`DISCORD_MCP_FILE_ROOT`](#-security-notes)
- [`get_attachment`](): Get attachment metadata (filename, size, content type, URLs) from a specific message, without downloading
- [`download_attachment`](): Download a message's attachments to disk and return the saved paths (max 50MB each, 100MB per call). Requires [`DISCORD_MCP_DOWNLOAD_ROOT`](#-security-notes)
- [`edit_message`](): Edit a message from a specific channel
- [`delete_message`](): Delete a message from a specific channel
- [`read_messages`](): Read message history from a specific channel (includes author IDs, attachment metadata, supports `count` 1-100 and optional cursor: `before` or `after` or `around`)
- [`add_reaction`](): Add a reaction (emoji) to a specific message
- [`remove_reaction`](): Remove a specified reaction (emoji) from a message

#### Channel Management
- [`create_text_channel`](): Create a new text channel
- [`edit_text_channel`](): Edit settings of a text channel (name, topic, nsfw, slowmode, category, position)
- [`delete_channel`](): Delete a channel
- [`find_channel`](): Find a channel type and ID using name and server ID
- [`list_channels`](): List of all channels
- [`get_channel_info`](): Get detailed information about a channel
- [`move_channel`](): Move a channel to another category and/or change its position

#### Category Management
- [`create_category`](): Create a new category for channels
- [`edit_category`](): Edit a category (rename or move position)
- [`delete_category`](): Delete a category
- [`find_category`](): Find a category ID using name and server ID
- [`list_channels_in_category`](): List of channels in a specific category

#### Webhook Management
- [`create_webhook`](): Create a new webhook on a specific channel
- [`delete_webhook`](): Delete a webhook
- [`list_webhooks`](): List of webhooks on a specific channel
- [`send_webhook_message`](): Send a message via webhook

#### Role Management
- [`list_roles`](): Get a list of all roles on the server with their details
- [`create_role`](): Create a new role on the server
- [`edit_role`](): Modify an existing role's settings
- [`delete_role`](): Permanently delete a role from the server
- [`assign_role`](): Assign a role to a user
- [`remove_role`](): Remove a role from a user

#### Moderation and User Management
- [`kick_member`](): Kicks a member from the server
- [`ban_member`](): Bans a user from the server
- [`unban_member`](): Removes a ban from a user
- [`timeout_member`](): Disables communication for a member for a specified duration
- [`remove_timeout`](): Removes a timeout (unmute) from a member before it expires
- [`set_nickname`](): Changes a member's nickname on the server
- [`get_bans`](): Returns a list of banned users on the server with ban reasons

#### Voice & Stage Channel Management
- [`create_voice_channel`](): Create a new voice channel in a guild
- [`create_stage_channel`](): Create a new stage channel for audio events
- [`edit_voice_channel`](): Edit settings of a voice or stage channel (name, bitrate, user limit, region)
- [`move_member`](): Move a member to another voice channel
- [`disconnect_member`](): Disconnect a member from their current voice channel
- [`modify_voice_state`](): Server mute or deafen a member in voice channels

#### Scheduled Events Management
- [`create_guild_scheduled_event`](): Schedule a new event on the server (voice, stage, or external), optionally recurring
- [`edit_guild_scheduled_event`](): Modify event details or change its status (start, complete, cancel), including the recurrence rule
- [`set_guild_scheduled_event_image`](): Replace an event's cover image from a direct `imageUrl` or a local `filePath` (max 5MB, no animation; covers display at 5:2, so crop first). `imageUrl` needs no filesystem access; `filePath` requires [`DISCORD_MCP_FILE_ROOT`](#-security-notes). Separate from `edit_guild_scheduled_event` so a deployment can allow event edits without granting a local-file read
- [`delete_guild_scheduled_event`](): Permanently delete a scheduled event
- [`list_guild_scheduled_events`](): List all active and scheduled events on the server, showing which ones recur and their cover image URL. Spends one API call per invocation, including on a server with no events — an empty cache is not evidence there are none

> **Recurring events.** JDA has no representation for Discord's `recurrence_rule`, so these tools
> send that one field through a custom JDA route — same bot token, same rate limiter. Pass
> `recurrenceRule` as JSON. Usually `{"frequency": 2}` is enough: the start time is the series'
> first occurrence, so for weekly, monthly and yearly the selector is derived from it when omitted
> and must agree with it when supplied. Discord accepts a narrow set of rules and rejects the rest
> with an opaque 400, so rules are validated locally first, before anything is created. Editing a recurring event's start time moves its recurrence
> anchor too; without that the series snaps back to its old time.
- [`get_guild_scheduled_event_users`](): Get list of users interested in a scheduled event

#### Channel Permission Overwrites
- [`list_channel_permission_overwrites`](): List all permission overwrites for a channel with role/member breakdown
- [`upsert_role_channel_permissions`](): Create or update permission overwrite for a role on a channel
- [`upsert_member_channel_permissions`](): Create or update permission overwrite for a member on a channel
- [`delete_channel_permission_overwrite`](): Delete a permission overwrite for a role or member from a channel

#### Invite Management
- [`create_invite`](): Create a new invite link for a specific channel
- [`list_invites`](): List all active invites on the server with their statistics
- [`delete_invite`](): Delete (revoke) an invite so the link stops working
- [`get_invite_details`](): Get details about a specific invite (works for any public invite)

#### Forum Management
- [`create_forum_channel`](): Create a new forum channel
- [`edit_forum_channel`](): Edit settings of a forum channel (name, topic, nsfw, slowmode, category, position, default sort, default layout)
- [`list_forum_channels`](): List all forum channels in the server
- [`get_forum_channel_info`](): Get detailed information about a forum channel including tags and settings
- [`list_forum_tags`](): List all available tags in a forum channel
- [`create_forum_post`](): Create a new forum post (thread) with an initial message in a forum channel
- [`list_forum_posts`](): List active posts (threads) in a forum channel
- [`modify_forum_post`](): Modify a forum post: lock/unlock, archive/unarchive, pin/unpin, or change applied tags

#### Emoji Management
- [`list_emojis`](): List all custom emojis on the server
- [`get_emoji_details`](): Get detailed information about a specific custom emoji
- [`create_emoji`](): Upload a new custom emoji to the server (base64 or image URL, max 256KB)
- [`edit_emoji`](): Edit an existing emoji's name or role restrictions
- [`delete_emoji`](): Permanently delete a custom emoji from the server

>If `DISCORD_GUILD_ID` is set, the `guildId` parameter becomes optional for all tools above.

<hr>

A more detailed examples can be found in the [Wiki](https://github.com/SaseQ/discord-mcp/wiki).
