# Lumina Atlas Community

Self-hosted IPTV interface for live TV, films and series, in the Noir design (black, one yellow accent, Anton and JetBrains Mono). The browser app has search, favourites, continue watching, EPG, detail pages, subtitles and seekable HLS conversion for formats a browser cannot play directly. The optional Google TV app displays this same interface and lets the owner select it as the device's Home app where Android permits it.

The repository contains no channels, media, provider account, APK or device token. Bring a lawful IPTV subscription (an Xtream Codes account or any M3U playlist) and your own server. This is a standalone community edition derived from a private Lumina Atlas project; it is not a GitHub fork of the private repository.

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

## Google TV Streamer 4K

`android-tv/` builds an installable Android TV client. Its first screen, in the same Noir design, asks for **your** Lumina Atlas server address and stores it locally. After entering the PIN, connect the IPTV source from **Ligar fonte** in the app. IPTV credentials remain on your server. The client uses Android WebView for the same catalogue and playback UI as the browser app. No private Hub account or prebuilt signed APK is included.

Build the APK yourself below; the [0.1.0 release](https://github.com/arqueox/lumina-atlas-community/releases/tag/v0.1.0) predates the Noir design and M3U support. APKs contain no provider credentials.

Build with Android SDK 36 and Java 17:

```sh
cd android-tv
./gradlew assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on the TV. Enter the HTTPS URL of your server. The setup screen includes **Botão Home** (Android Home app settings) and **Acessibilidade** shortcuts. Select Lumina Atlas as the default Home app if your Google TV firmware offers that choice. The remote Home button is controlled by Android; an ordinary app cannot capture it while another launcher is selected. Press Menu inside Lumina Atlas to change the server URL. Back returns from web history or opens setup. You can restore the original launcher from Android's Home app settings.

Some Google TV firmware may hide or block third-party Home selection. The Accessibility settings shortcut is for the device's own accessibility controls; this app does not install an accessibility service or remap remote keys. The Android client uses the web player, so its codec and remote behaviour may differ from a native TV player.

## Development

```sh
node --test server/*.test.js
```

The tests use fake Xtream and M3U servers: provider parsing, playlist parsing, catalogue loading, byte ranges and interrupted upstream media. The server uses only Node built-ins; Docker adds FFmpeg for HLS and subtitle extraction.

## Fonts

The Android TV app bundles Anton and JetBrains Mono, and the web app loads them and Instrument Serif from Google Fonts. All three are under the SIL Open Font License 1.1.
