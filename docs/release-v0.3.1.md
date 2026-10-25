## Lumina Atlas Community 0.3.1

Fixes two problems in 0.3.0:

- The player closed as soon as it started (the app lacked Android's wake-lock permission).
- Providers on plain `http://` were blocked by Android; they now load.

Tested on a Google TV Streamer 4K: the first screen asks for the IPTV source, the catalogue loads from an Xtream account, and live channels play. Still a pre-release while it is tried with more providers. Signed with the same key as 0.3.0, so it installs over it.

SHA-256: `13ef3213cf3643bf082a6adba2311940fdbcbd3a7d30d2ef8f582012b3ef62af`
