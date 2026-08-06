# Ukulele
...and his music was electric.

Ukulele is a lightweight, simple-to-host Discord music bot inspired by FredBoat. While FredBoat is engineered for millions of servers, Ukulele is designed for personal use and small communities, keeping the stack focused and efficient.

The bot is self-contained and requires **Java 25** to run.

> [!NOTE]
> The modernized stack (Java 25, Spring Boot 4, Kotlin 2.3, REST/WebSocket API) is the primary line of development on the **master** branch.

> [!IMPORTANT]
> This project utilizes a **custom Lavaplayer fork** ([`JustinFreitas/lavaplayer`](https://github.com/JustinFreitas/lavaplayer), `v2.2.6_13`) which unlocks advanced features like **ReplayGain (Volume Normalization)**. This is **opt-in** — see [Volume Normalization](#-volume-normalization-replaygain) to enable it.

---

## 🚀 Key Features

### 🎵 Core Music Playback
* **Multi-Source Support:** Play music from YouTube, SoundCloud, Bandcamp, Vimeo, Twitch, and more.
* **Local Files:** Support for playing local audio files directly from the host system.
* **Batch Queueing:** Add multiple tracks in one command by separating URLs with a pipe (`url1 | url2 | url3`).
* **Dynamic Queue:** Manage your playback queue with ease.
* **Shuffle & Loop:** Randomize your queue or loop individual tracks and the entire queue.
* **Precise Seeking:** Jump to any part of a track with the `seek` command.

### 🔊 Advanced Audio Control
* **Volume Normalization (ReplayGain):** Optionally balances audio levels across tracks so you don't have to constantly adjust your volume. Disabled by default — see [Volume Normalization](#-volume-normalization-replaygain) to turn it on.
* **Virtual Volume Scaling:** High-fidelity volume control mapped to the player's internal engine.
* **Per-Track Volume:** Set a volume for an individual track via its queue label (e.g. `[Label, v:42] URL`) — see [`::play` syntax](#-play-syntax).

### 📱 Remote Control & Integration
* **REST API:** Fully featured API to control the player, manage the queue, and update configuration programmatically.
* **WebSockets (STOMP):** Real-time player status updates and event streaming for building modern dashboards or mobile app integrations.
* **Secure Access:** Built-in security with API token authentication.

### 🛠️ Robust Infrastructure
* **Java 25, Spring Boot 4 & Kotlin 2.3:** Built on the latest, high-performance Java ecosystem, with JDA 6 for Discord.
* **Zero-Maintenance Database:** Uses an embedded H2 database with R2DBC for efficient, reactive data handling.
* **Flyway Migrations:** Automated database schema management.
* **Docker Ready:** Includes a `Dockerfile` and `docker-compose.yml` for instant deployment.

---

## ⌨️ Commands

Ukulele uses **Discord Slash Commands** (`/`). Each command's aliases are shown in parentheses.

| Command | Description |
| :--- | :--- |
| `/play` (`p`) `<url>[\|<url>...]` | Add one or more tracks to the queue. |
| `/skip` (`s`) `[range]` | Skip the current track or a range of tracks. |
| `/nowplaying` (`np`) | Show detailed information about the current track. |
| `/queue` (`q`, `list`) | Display the current playback queue. |
| `/volume` (`v`) `<0-150>%` | Set the volume, or use `+` / `-` to step by the optimal amount. |
| `/seek <time>` | Seek to a specific timestamp in the track. |
| `/pause` / `/resume` | Pause or resume playback. |
| `/shuffle` | Randomize the current queue. |
| `/repeat` (`r`) | Toggle looping for the current track. |
| `/loop` (`l`) | Toggle looping for the entire queue. |
| `/stop` | Clear the queue and disconnect the player. |
| `/say <text>` | Repeat the given text back as a message. |
| `/exit` | Shut down the bot gracefully (owner only). |
| `/help` (`h`, `?`) `[command]` | List all commands, or show help for a specific one. |

### 🎶 `/play` syntax

A play request is one or more **identifiers** (a URL, a search like `ytsearch:...`, or a local file path). Each identifier may be prefixed with an optional `[...]` label.

```
/play args: [optional label] <url-or-path>
```

**Multiple tracks at once.** Separate identifiers with a pipe (`|`) to queue several in a single command:

```
/play args: https://youtu.be/aaa | https://youtu.be/bbb | ytsearch:lofi beats
```

**Labels.** Anything inside the leading `[...]` is a label for that track. If `prependQueueLabelToTitle` is enabled in your config, the label is shown in front of the track title in the queue:

```
/play args: [Morning Mix] https://youtu.be/aaa
```

**Per-track volume (`v:`).** Add a `v:<n>` attribute inside the label to set that track's volume (1–150) when it starts playing — handy for taming a track that's much louder or quieter than the rest. It's case-insensitive and can sit anywhere in the label:

```
/play args: [Quiet Intro, v:42] https://youtu.be/aaa | [v:120] https://youtu.be/bbb
```

Here the first track plays at 42% and the second at 120%, independent of the player's current volume. The number is a percentage on the same scale as the `volume` command, so it is mapped through `minVolume`/`maxVolume` exactly like `volume` is — `v:42` and `::volume 42` produce the same level.

> [!NOTE]
> When [Volume Normalization](#-volume-normalization-replaygain) is enabled, a track's `v:` volume is skipped for any track that has ReplayGain data — normalization takes precedence, since the two would otherwise fight over the same track. The `v:` value still applies to tracks without ReplayGain data, which is what it is for.

### 🔉 Volume Normalization (ReplayGain)

ReplayGain levels each track to a consistent loudness so you aren't constantly riding the volume control. It relies on the custom Lavaplayer fork and is **off by default**.

To enable it, set `normalization` to `true` under the `config:` block in your `ukulele.yml`:

```yaml
config:
  normalization: true
```

When enabled, the player applies ReplayGain on track start; tracks without ReplayGain data fall back to the player's current volume (and any per-track `v:` label).

Two details worth knowing if you tag your own library. Gain is limited against the track's peak, so a large positive ReplayGain on a file with little headroom is reduced rather than allowed to clip. And `R128_TRACK_GAIN` (what Opus files normally carry) is referenced to −23 LUFS while `REPLAYGAIN_TRACK_GAIN` is referenced to −18 LUFS; the fork corrects for that, so a tagged `.opus` and a tagged `.mp3` sit at the same level.

---

## 🏠 Host it yourself

### Manual Installation
1. **Install Java 25:** Downloads available from [Adoptium (Temurin)](https://adoptium.net/) or [Azul (Zulu)](https://www.azul.com/downloads/).
2. **Configure:** Copy `ukulele.example.yml` to `ukulele.yml` and add your **Discord Bot Token**.
3. **Run:** Execute `./ukulele` (Linux/macOS) or `ukulele.bat` (Windows) to build and start the bot.

### Using Docker

**Option A — Prebuilt image (no build required).** A public image is published to GitHub Container Registry on every push:

```shell script
# 1. Prepare environment
mkdir db && chown -R 999 db/
cp ukulele.example.yml ukulele.yml   # then edit ukulele.yml and add your bot token

# 2. Pull and run the prebuilt image
docker pull ghcr.io/justinfreitas/ukulele:master
docker run -d --restart always \
  -v "$(pwd)/ukulele.yml:/opt/ukulele/ukulele.yml" \
  -v "$(pwd)/db:/opt/ukulele/db" \
  -e CONFIG_DATABASE=./db/database \
  -p 8080:8080 \
  ghcr.io/justinfreitas/ukulele:master
```

> [!TIP]
> To use the prebuilt image with `docker-compose` instead of building locally, swap `build: .` for `image: ghcr.io/justinfreitas/ukulele:master` in `docker-compose.yml`.

**Option B — Build from source.** The included `docker-compose.yml` builds the image locally from the `Dockerfile`:

```shell script
# 1. Prepare environment
mkdir db && chown -R 999 db/
cp ukulele.example.yml ukulele.yml

# 2. Build and start the bot
docker-compose up -d
```

### Running under PM2

The repo ships a [PM2](https://pm2.keymetrics.io/) ecosystem file (`ukulele.config.js`) so the bot can run as a managed, auto-restarting process. Build the jar first (`./gradlew build`), then:

```shell script
pm2 start ukulele.config.js
pm2 list
```

The config runs the bot as `java -jar build/libs/ukulele.jar` by declaring the **jar as the `script`** and `java` as the `interpreter`. This is deliberate: PM2 derives the `version` column in `pm2 list` by walking up from the script path looking for a `package.json`. Pointing the script at the jar (inside the repo) lets PM2 find this project's `package.json` and show the real version (kept in sync with `version` in `build.gradle.kts`); pointing it directly at `java.exe` would resolve to the JDK and show `N/A`. PM2 still spawns the JVM directly, so it owns the JVM PID and crash-restart works.

> [!IMPORTANT]
> PM2 does **not** persist its process list automatically. After your processes are running the way you want, run `pm2 save` to write the dump (`~/.pm2/dump.pm2`). PM2 restores from that dump on `pm2 resurrect` (and on boot, if you've set up `pm2 startup`). If you skip `pm2 save`, the bot will be gone after a reboot or `pm2 kill`.

```shell script
pm2 save        # persist the current process list to ~/.pm2/dump.pm2
pm2 resurrect   # restore the saved processes (e.g. after a reboot or pm2 kill)
```

#### Monitoring logs

The bot writes everything to stdout/stderr, which PM2 captures — there's no separate app log file. Use the process name (`ukulele`) to inspect it:

```shell script
pm2 logs ukulele               # live tail of stdout + stderr
pm2 logs ukulele --lines 200   # backfill more history when attaching
pm2 logs ukulele --err         # errors/stderr only (fastest way to read a crash trace)
pm2 list                       # status, uptime, restart count (↺), cpu/mem
pm2 show ukulele               # details, incl. the exact log file paths
pm2 monit                      # live dashboard: logs + resource graphs
```

By default the log files live at `~/.pm2/logs/ukulele-out.log` and `ukulele-error.log` (`pm2 show ukulele` prints the resolved paths). A climbing ↺ count in `pm2 list` means the JVM is crash-looping — check `pm2 logs ukulele --err`.

#### Log rotation

PM2 does **not** rotate logs on its own, so they grow unbounded. This is handled by the global [`pm2-logrotate`](https://github.com/keymetrics/pm2-logrotate) module, which rotates the logs of **all** PM2 processes (not just `ukulele`). Install and configure it once:

```shell script
pm2 install pm2-logrotate
pm2 set pm2-logrotate:max_size 10M           # rotate when a log file passes 10 MB
pm2 set pm2-logrotate:retain 30              # keep 30 rotated files, then delete oldest
pm2 set pm2-logrotate:compress true          # gzip rotated logs to save disk
pm2 set pm2-logrotate:rotateInterval "0 0 * * *"  # also rotate daily at midnight
```

These settings persist in `~/.pm2/module_conf.json` and survive restarts, so they don't need to be re-applied after `pm2 resurrect`.

---

## 📡 Remote API
Ukulele includes a powerful REST and WebSocket API. See **[API.md](API.md)** for full documentation on endpoints and integration.

### 📱 Ukulele Remote
**[Ukulele Remote](https://github.com/JustinFreitas/ukulele-remote)** is a companion mobile app that drives this API — a sleek, real-time controller for managing playback, the queue, and voice channel switching from your phone. Built with React Native and Expo, it stays in sync over the WebSocket interface (set `useWebsockets: true` in your config to enable live updates).

---

## 🛠️ Development
* **Build:** `./gradlew clean build`
* **Test:** `./gradlew test`
* **Linter:** `./gradlew ktlintCheck` (Enforces high-quality, idiomatic Kotlin code)
