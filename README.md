# Ukulele
...and his music was electric.

Ukulele is a lightweight, simple-to-host Discord music bot inspired by FredBoat. While FredBoat is engineered for millions of servers, Ukulele is designed for personal use and small communities, keeping the stack focused and efficient.

The bot is self-contained and requires **Java 25** to run.

> [!NOTE]
> The modernized stack (Java 25, Spring Boot 4, Kotlin 2.3, REST/WebSocket API) lives on the **[`modernize-java25`](../../tree/modernize-java25)** branch. This is a personal fork, so that branch is the active line of development rather than `master`.

> [!IMPORTANT]
> This project utilizes a **custom Lavaplayer fork** ([`JustinFreitas/lavaplayer`](https://github.com/JustinFreitas/lavaplayer), `v2.2.6_13`) which enables advanced features like **ReplayGain (Volume Normalization)**. This allows the bot to maintain consistent volume levels across different tracks automatically.

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
* **Volume Normalization (ReplayGain):** Automatically balances audio levels so you don't have to constantly adjust your volume.
* **Virtual Volume Scaling:** High-fidelity volume control mapped to the player's internal engine.
* **Per-Track Volume:** Support for specifying volume levels within a track's queue label (e.g., `[Label, v:42] URL`).

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

---

## 🛠️ Development
* **Build:** `./gradlew clean build`
* **Test:** `./gradlew test`
* **Linter:** `./gradlew ktlintCheck` (Enforces high-quality, idiomatic Kotlin code)
