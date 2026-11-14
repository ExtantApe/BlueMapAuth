# BlueMapAuth

BlueMapAuth for Paper: BlueMap's web map behind a sign-in, showing each player only the worlds and players they're allowed to see.

**Status:** early, version 0.2.0. 24 HTTP and config tests pass, plus a bot test (25/25, and 8/8 without HouseRules and LuckPerms, 2026-11-11) on a throwaway Paper 26.1.2 server with the real BlueMap 5.29, LuckPerms 5.5, HouseRules and Latchkey, signing in through Latchkey's "Open the map" over HTTP. The hand-off page was checked in a real browser (Chromium, 2026-11-11, against made-up data on localhost; `BrowserDemo` in the test sources). The token leaves the address bar, the cookie is invisible to scripts, a reused link explains itself, and the page has no policy errors. On a copy of a real server on Paper 26.2 and 26.3 build 249 (2026-11-14) it loaded with no errors, switched off, so serving the map on 26.2 and 26.3 is still untested, as is running behind Caddy.

## What it does

- Serves BlueMap's web map at its own address, such as `map.example.org`, and nothing of it to anyone who isn't signed in. BlueMap's own web server stays on 127.0.0.1, so BlueMapAuth is the only way in.
- Signs a browser in with a one-time link from Latchkey's invite page ("Open the map"). The link works once, for a minute. The map then has its own cookie for its own address only, and it ends within 30 seconds of the Latchkey session ending.
- Lists only the maps of worlds the viewer may enter (`multiverse.access.<world>` by default). Any other map is "not found", the same as one that doesn't exist.
- Shows only the players the viewer may see. In game, that's chat's reach from HouseRules. A viewer who isn't in game sees only players who share a HouseRules circle with them.
- Fails closed. Without Latchkey nobody can sign in. Without BlueMap there are no maps. Without HouseRules the viewer sees only themselves. For a viewer who isn't in game, without LuckPerms there are no maps and no other players.

## Install or build

Needs Paper 26.1 or newer and Java 25. BlueMap, LuckPerms, HouseRules (0.4 or newer, for circles while away) and Latchkey (0.4 or newer, for the hand-off) are all optional.

```
./gradlew build
```

The jar is `build/libs/BlueMapAuth-<version>.jar`. In `plugins/BlueMapAuth/config.yml`, set `enabled: true` and `public-url`. Point your HTTPS proxy at `127.0.0.1:8180`, and set BlueMap's web server to `ip: "127.0.0.1"`. In Latchkey, set `page.map-address` to the same address.

## Bot test

`test/live/bluemapauth.mjs` builds a throwaway server in a temp folder, runs bots and browser requests against it, and deletes it. The settings it needs are at the top of the file. `MK_TEST_MISSING=1` runs it without HouseRules and LuckPerms, to check those paths fail closed. It needs Node 22 or newer, and `npm ci` in `test/live` first.

## Commands

- `/bluemapauth status`: whether the map is served, what's wrong with the config, and which plugins it found. Needs `bluemapauth.admin`.
- `/bluemapauth reload`: re-reads `config.yml`.

## License

MIT. See [LICENSE](LICENSE).
