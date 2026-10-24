# Lumina Atlas Community

Self-hosted IPTV interface for live TV, films and series. The browser app keeps the Atlas layout, search, favourites, continue watching, EPG, detail pages, subtitles and seekable HLS conversion for formats a browser cannot play directly. The optional Google TV app displays this same interface and lets the owner select it as the device's Home app where Android permits it.

The repository contains no channels, media, provider account, APK or device token. Bring a lawful Xtream compatible subscription and your own server. This is a standalone community edition derived from a private Lumina Atlas project; it is not a GitHub fork of the private repository.

## Start the server

Requires Docker Compose. Copy `.env.example` to `.env`, then set `APP_PIN`, a random `SESSION_SECRET` (`openssl rand -hex 32`), `XTREAM_BASE`, `XTREAM_USERNAME` and `XTREAM_PASSWORD`. The provider URL should contain only the scheme, host and optional port. Keep `.env` private.

```sh
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:8091/health
```

Open `http://127.0.0.1:8091` and enter your PIN. The default binding is localhost and the session cookie requires HTTPS. For local HTTP testing, set `COOKIE_SECURE=false` in `.env`. For access from another device, put the server behind an HTTPS reverse proxy and set `PUBLIC_BASE_URL=https://your-domain.example`. If using a trusted LAN without TLS, set `LUMINA_BIND=0.0.0.0` and `COOKIE_SECURE=false`; anyone on that network who knows the PIN can access your catalogue and stream. Do not expose the HTTP LAN setup to the public internet.

The server fetches Xtream catalogues and streams on behalf of the browser. Provider stream limits and codecs still apply. Tesla Theater mode uses `PUBLIC_BASE_URL`; without it, `/theater` reports a configuration error.

## Google TV Streamer 4K

`android-tv/` builds an installable Android TV client. It asks for **your** Lumina Atlas server URL on first launch and stores that URL locally; IPTV credentials remain on the server. It uses Android WebView for the same catalogue and playback UI as the browser app. No private Hub account or prebuilt signed APK is included.

Build with Android SDK 36 and Java 17:

```sh
cd android-tv
./gradlew assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on the TV. Enter the HTTPS URL of your server. The setup screen includes **Choose Home app** and **Accessibility settings** shortcuts. Select Lumina Atlas as the default Home app if your Google TV firmware offers that choice. The remote Home button is controlled by Android; an ordinary app cannot capture it while another launcher is selected. Press Menu inside Lumina Atlas to change the server URL. Back returns from web history or opens setup. You can restore the original launcher from Android's Home app settings.

Some Google TV firmware may hide or block third-party Home selection. The Accessibility settings shortcut is for the device's own accessibility controls; this app does not install an accessibility service or remap remote keys. The Android client uses the web player, so its codec and remote behaviour may differ from a native TV player.

## Development

```sh
node --test server/server.test.js
```

The integration test uses a fake Xtream server and checks byte ranges and interrupted upstream media. The server uses only Node built-ins; Docker adds FFmpeg for HLS and subtitle extraction.
