# Ukulele
...and his music was electric.

Ukulele is a lightweight, simple-to-host Discord music bot inspired by FredBoat. While FredBoat is engineered for millions of servers, Ukulele is designed for personal use and small communities, keeping the stack focused and efficient.

The bot is self-contained and requires **Java 25** to run.

> [!IMPORTANT]
> This project utilizes a **custom Lavaplayer fork** which enables advanced features like **ReplayGain (Volume Normalization)**. This allows the bot to maintain consistent volume levels across different tracks automatically.

---

## 🚀 Key Features

### 🎵 Core Music Playback
* **Multi-Source Support:** Play music from YouTube, SoundCloud, Bandcamp, Vimeo, Twitch, and more.
* **Local Files:** Support for playing local audio files directly from the host system.
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
* **Java 25 & Spring Boot 4:** Built on the latest, high-performance Java ecosystem.
* **Zero-Maintenance Database:** Uses an embedded H2 database with R2DBC for efficient, reactive data handling.
* **Flyway Migrations:** Automated database schema management.
* **Docker Ready:** Includes a `Dockerfile` and `docker-compose.yml` for instant deployment.

---

## ⌨️ Commands

| Command | Description |
| :--- | :--- |
| `::play <query/url>` | Play a track or add it to the queue. |
| `::skip [range]` | Skip the current track or a range of tracks. |
| `::nowplaying` | Show detailed information about the current track. |
| `::queue` | Display the current playback queue. |
| `::volume <0-150>` | Adjust the player volume. |
| `::seek <time>` | Seek to a specific timestamp in the track. |
| `::pause` / `::resume` | Pause or resume playback. |
| `::shuffle` | Randomize the current queue. |
| `::repeat` | Toggle looping for the current track. |
| `::loop` | Toggle looping for the entire queue. |
| `::prefix <new_prefix>` | Change the bot's command prefix for the guild. |
| `::say <message>` | Make the bot speak in the voice channel. |
| `::help` | List all available commands. |

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

## 🤝 Contributing
Pull requests are welcome! Please read **[CONTRIBUTING.md](CONTRIBUTING.md)** before submitting.

---

## 🛠️ Development
* **Build:** `./gradlew clean build`
* **Test:** `./gradlew test`
* **Linter:** `./gradlew ktlintCheck` (Enforces high-quality, idiomatic Kotlin code)
