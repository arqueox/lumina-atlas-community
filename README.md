# Lumina Atlas Community

Self-hosted IPTV interface for live TV, films and series, in the Noir design (black, one yellow accent, Anton and JetBrains Mono). The browser app has search, favourites, continue watching, EPG, detail pages, subtitles and seekable HLS conversion for formats a browser cannot play directly. The Google TV launcher in `android-tv/` connects to the provider natively and needs no server.

The repository contains no channels, media, provider account, APK or device token. Bring a lawful IPTV subscription (an Xtream Codes account or any M3U playlist) and, for the browser app, your own server. This is a standalone community edition derived from a private Lumina Atlas project; it is not a GitHub fork of the private repository.

## Start the server

Requires Docker Compose. Copy `.env.example` to `.env`, then set `APP_PIN` and a random `SESSION_SECRET` (`openssl rand -hex 32`). For IPTV, use **one** of these methods:

- Leave the IPTV fields empty, start the server, enter your PIN in the browser or TV app, then open **Ligar fonte** (the link button in the top bar). Paste an address or choose a file. It is saved in `./data/provider.url` with owner-only access.
- Set `IPTV_URL` to an address: an Xtream link such as `https://provider.example/get.php?username=you&password=secret&type=m3u_plus` or `https://you:secret@provider.example`, or any M3U/M3U8 playlist such as `https://lists.example/tv.m3u`. URL-encode special characters in credentials.
- Set `IPTV_URL_FILE=/data/provider.url` and put a `.url` shortcut or a whole `.m3u` playlist in `./data/provider.url`.
- Set `XTREAM_BASE`, `XTREAM_USERNAME` and `XTREAM_PASSWORD` separately.

What you get depends on the source:

| Source | Live TV | Films and series | Programme guide |
| --- | --- | --- | --- |
| Xtream account (`get.php`, `player_api.php`, `user:pass@host`, or a playlist of Xtream stream URLs) | Yes | Yes, with details | Yes |
| Any other M3U/M3U8 playlist | Yes, grouped by `group-title` | Entries with `/movie/`, `/series/` or a video file extension; series grouped by `SxxEyy` | No |

A playlist address is fetched again every 30 minutes (up to 64 MB); a playlist uploaded from the app is kept as a file. Item numbers come from the playlist order, so favourites can move if the provider reorders its list.

`IPTV_URL` takes priority over `IPTV_URL_FILE`; either disables changes from the app. A saved source takes priority over separate `XTREAM_*` fields. Keep `.env` and `./data` private; both are excluded from Git and the Docker build context. The `./data` folder must be writable by the container user (uid 1000): `mkdir -p data && sudo chown 1000:1000 data` if the app reports it cannot save.

```sh
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:8091/health
```

Open `http://127.0.0.1:8091` and enter your PIN. The default binding is localhost and the session cookie requires HTTPS. For local HTTP testing, set `COOKIE_SECURE=false` in `.env`. For access from another device, put the server behind an HTTPS reverse proxy and set `PUBLIC_BASE_URL=https://your-domain.example`. If using a trusted LAN without TLS, set `LUMINA_BIND=0.0.0.0` and `COOKIE_SECURE=false`; anyone on that network who knows the PIN can access your catalogue and stream. Do not expose the HTTP LAN setup to the public internet.

The server fetches Xtream catalogues and streams on behalf of the browser. Provider stream limits and codecs still apply. Tesla Theater mode uses `PUBLIC_BASE_URL`; without it, `/theater` reports a configuration error.

## Google TV launcher

`android-tv/` is a native Google TV launcher (Kotlin, Jetpack Compose, Media3 ExoPlayer) in the same Noir design. It does **not** need the server above: the TV talks straight to your IPTV provider.

- **Your own source.** On first launch the app opens **Ligar fonte**. Paste an Xtream link (`get.php?username=…&password=…` or `user:pass@host`) or any M3U/M3U8 address, or choose a `.m3u` playlist or `.url` shortcut on the device. It is stored only on that TV. The app ships with no link, account or token, and asks every user for their own.
- **Catalogue.** Direto by channel group, Filmes and Séries by category with details, seasons and episodes (Xtream accounts also give synopses and backdrops). Providers that mark Portuguese sections with "█ … █" rows get Generalistas, Desporto and Filmes, as in the web app.
- **Player.** ExoPlayer with FFmpeg audio decoders for MP2/AC3/EAC3/DTS channels. ▲/▼ changes channel, ◀/▶ seeks −10 s/+30 s, OK pauses; film positions are remembered.
- **Launcher.** The home lists the TV's apps. With notification access granted (rail › *A ver agora*, or `adb shell cmd notification allow_listener dev.lumina.atlas/dev.lumina.atlas.MediaWatcher`), an *A ver agora* row shows the last title played in each streaming app, read from Android media sessions; the app reads no notifications.
- **Home button.** Google TV keeps its own launcher on Home. Turn on the optional accessibility service *Lumina Atlas: botão Home* (rail › *Botão Home*) to come back to Lumina Atlas whenever the Google TV home appears; turn it off to restore the stock behaviour.

Build with Android SDK 36 and Java 17:

```sh
cd android-tv
./gradlew assembleRelease   # unsigned unless android-tv/keystore.properties exists (see app/build.gradle.kts)
```

Install the APK on the TV with `adb install`. The [0.1.0 release](https://github.com/arqueox/lumina-atlas-community/releases/tag/v0.1.0) was the earlier WebView client, signed with a different key: uninstall it before installing 0.3.0.

## Development

```sh
node --test server/*.test.js
```

The tests use fake Xtream and M3U servers: provider parsing, playlist parsing, catalogue loading, byte ranges and interrupted upstream media. The server uses only Node built-ins; Docker adds FFmpeg for HLS and subtitle extraction.

## Fonts

The Google TV launcher bundles Anton, JetBrains Mono and Instrument Serif; the web app loads them from Google Fonts. All three are under the SIL Open Font License 1.1.
