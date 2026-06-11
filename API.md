# Ukulele Remote API Reference

Ukulele provides a REST API and WebSocket interface to allow remote control of the bot. This enables the creation of web or mobile "remote control" applications.

## Authentication

All API requests (except for basic health checks if implemented) require an authorization token.

- **Header**: `Authorization`
- **Format**: `<token>` or `Bearer <token>`
- **Configuration**: The token is defined in your `ukulele.yml` under `config.apiToken`. The default value is `secret`.

## REST API

The base path for all API calls is `/api`.

### Guilds

#### `GET /api/guilds`
Returns a list of all guilds (servers) the bot is currently connected to.

**Response:**
```json
[
  {
    "id": "123456789",
    "name": "My Discord Server"
  }
]
```

### Player Control

#### `GET /api/player/{guildId}`
Returns the current status of the player for a specific guild.

**Response:**
```json
{
  "guildId": "123456789",
  "isPaused": false,
  "volume": 50,
  "repeatTrack": true,
  "queueLooping": false,
  "currentTrack": {
    "title": "Song Title",
    "author": "Artist Name",
    "uri": "https://youtube.com/...",
    "duration": 180000,
    "position": 45000
  },
  "queue": [...],
  "remainingDuration": 135000,
  "isReplayGainEnabled": true
}
```

#### `GET /api/player/{guildId}/queue`
Returns the current track queue for the guild.

#### `GET /api/player/{guildId}/channels`
Returns a list of voice channels available in the guild.

#### `POST /api/player/{guildId}/play`
Adds a track to the queue. If the bot is not in a voice channel, it will attempt to join one.

**Body:**
```json
{
  "url": "https://www.youtube.com/watch?v=..."
}
```

#### `POST /api/player/{guildId}/pause`
Pauses the current playback.

#### `POST /api/player/{guildId}/resume`
Resumes the current playback.

#### `POST /api/player/{guildId}/skip`
Skips the current track.

#### `POST /api/player/{guildId}/stop`
Stops playback and clears the queue.

#### `POST /api/player/{guildId}/shuffle`
Shuffles the current queue.

#### `POST /api/player/{guildId}/volume`
Sets the player volume.

**Body:**
```json
{
  "volume": 50
}
```

#### `POST /api/player/{guildId}/repeat`
Enables or disables track repeating.

**Body:**
```json
{
  "repeat": true
}
```

#### `POST /api/player/{guildId}/loop`
Enables or disables queue looping.

**Body:**
```json
{
  "loop": true
}
```

#### `POST /api/player/{guildId}/seek`
Seeks to a specific position in the current track.

**Body:**
```json
{
  "position": 60000 
}
```

#### `POST /api/player/{guildId}/say`
Makes the bot send a message to the last used text channel in that guild.

**Body:**
```json
{
  "text": "Hello from the Remote App!"
}
```

### Configuration

#### `GET /api/config`
Returns the public configuration of the bot.

## WebSockets (Real-time Updates)

Ukulele uses the STOMP protocol over WebSockets for real-time status updates.

- **Endpoint**: `/ws`
- **Topic**: `/topic/player/{guildId}`

When a player status changes (e.g., track starts, pauses, volume changes), an update is published to the corresponding guild topic. The payload is identical to the response from `GET /api/player/{guildId}`.

## CORS Policy

The API currently has an **unrestricted CORS policy** (`*`). This allows any web application to connect to your bot instance, provided they have the correct `apiToken`.
