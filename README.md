# Ukulele
...and his music was electric.

Ukulele is a lightweight, simple-to-host Discord music bot inspired by FredBoat. While FredBoat is engineered for millions of servers, Ukulele is designed for personal use and small communities, keeping the stack focused and efficient.

The bot is self-contained and requires **Java 25** to run.

> [!NOTE]
> The modernized stack (Java 25, Spring Boot 4, Kotlin 2.3, REST/WebSocket API) lives on the **[`modernize-java25`](../../tree/modernize-java25)** branch. This is a personal fork, so that branch is the active line of development rather than `master`.

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

The default prefix is `::`. Each command's aliases are shown in parentheses.

| Command | Description |
| :--- | :--- |
| `::play` (`p`) `<url>[\|<url>...]` | Add one or more tracks to the queue. |
| `::skip` (`s`) `[range]` | Skip the current track or a range of tracks. |
| `::nowplaying` (`np`) | Show detailed information about the current track. |
| `::queue` (`q`, `list`) | Display the current playback queue. |
| `::volume` (`v`) `<0-150>%` | Set the volume, or use `+` / `-` to step by the optimal amount. |
| `::seek <time>` | Seek to a specific timestamp in the track. |
| `::pause` / `::resume` | Pause or resume playback. |
| `::shuffle` | Randomize the current queue. |
| `::repeat` (`r`) | Toggle looping for the current track. |
| `::loop` (`l`) | Toggle looping for the entire queue. |
| `::stop` | Clear the queue and disconnect the player. |
| `::prefix <new_prefix>` | Change the bot's command prefix for the guild. |
| `::say <text>` | Repeat the given text back as a message. |
| `::exit` | Shut down the bot gracefully (owner only). |
| `::help` (`h`, `?`) `[command]` | List all commands, or show help for a specific one. |

### 🎶 `::play` syntax

A play request is one or more **identifiers** (a URL, a search like `ytsearch:...`, or a local file path). Each identifier may be prefixed with an optional `[...]` label.

```
::play [optional label] <url-or-path>
```

**Multiple tracks at once.** Separate identifiers with a pipe (`|`) to queue several in a single command:

```
::play https://youtu.be/aaa | https://youtu.be/bbb | ytsearch:lofi beats
```

**Labels.** Anything inside the leading `[...]` is a label for that track. If `prependQueueLabelToTitle` is enabled in your config, the label is shown in front of the track title in the queue:

```
::play [Morning Mix] https://youtu.be/aaa
```

**Per-track volume (`v:`).** Add a `v:<n>` attribute inside the label to set that track's volume (1–150) when it starts playing — handy for taming a track that's much louder or quieter than the rest. It's case-insensitive and can sit anywhere in the label:

```
::play [Quiet Intro, v:42] https://youtu.be/aaa | [v:120] https://youtu.be/bbb
```

Here the first track plays at 42% and the second at 120%, independent of the player's current volume.

> [!NOTE]
> When [Volume Normalization](#-volume-normalization-replaygain) is enabled, a track's `v:` volume is skipped for any track that already has ReplayGain applied — normalization takes precedence. The `v:` value still applies to tracks without ReplayGain data.

### 🔉 Volume Normalization (ReplayGain)

ReplayGain levels each track to a consistent loudness so you aren't constantly riding the volume control. It relies on the custom Lavaplayer fork and is **off by default**.

To enable it, set `normalization` to `true` under the `config:` block in your `ukulele.yml`:

```yaml
config:
  normalization: true
```

When enabled, the player applies ReplayGain on track start; tracks without ReplayGain data fall back to the player's current volume (and any per-track `v:` label).

---

## 🏠 Host it yourself

### Manual Installation
1. **Install Java 25:** Downloads available from [Adoptium (Temurin)](https://adoptium.net/) or [Azul (Zulu)](https://www.azul.com/downloads/).
2. **Configure:** Copy `ukulele.example.yml` to `ukulele.yml` and add your **Discord Bot Token**.
3. **Run:** Execute `./ukulele` (Linux/macOS) or `ukulele.bat` (Windows) to build and start the bot.

### Using Docker
```shell script
# 1. Prepare environment
mkdir db && chown -R 999 db/
cp ukulele.example.yml ukulele.yml

# 2. Start the bot
docker-compose up -d
```

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
