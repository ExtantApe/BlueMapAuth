# Mapkeeper

Mapkeeper for Paper: BlueMap's web map behind a sign-in, showing each player only the worlds and players they're allowed to see.

**Status:** early, version 0.1.0, not released. HTTP tests pass against a made-up BlueMap and made-up players (24 tests). It hasn't run on a real server, with real BlueMap, LuckPerms, HouseRules or Latchkey, or in a real browser yet.

## What it does

- Serves BlueMap's web map at its own address, such as `map.example.org`, and nothing of it to anyone who isn't signed in. BlueMap's own web server stays on 127.0.0.1, so Mapkeeper is the only way in.
- Signs a browser in with a one-time link from Latchkey's invite page ("Open the map"). The link works once, for a minute. The map then has its own cookie for its own address only, and it ends within 30 seconds of the Latchkey session ending.
- Lists only the maps of worlds the viewer may enter (`multiverse.access.<world>` by default). Any other map is "not found", the same as one that doesn't exist.
- Shows only the players the viewer may see. In game, that's chat's reach from HouseRules. A viewer who isn't in game sees only players who share a HouseRules circle with them.
- Fails closed. Without Latchkey nobody can sign in. Without BlueMap there are no maps. Without HouseRules the viewer sees only themselves. For a viewer who isn't in game, without LuckPerms there are no maps and no other players.

## Install or build

Needs Paper 26.1 or newer and Java 25. BlueMap, LuckPerms, HouseRules (0.4 or newer, for circles while away) and Latchkey (0.4 or newer, for the hand-off) are all optional.

```
./gradlew build
```

The jar is `build/libs/Mapkeeper-<version>.jar`. In `plugins/Mapkeeper/config.yml`, set `enabled: true` and `public-url`. Point your HTTPS proxy at `127.0.0.1:8180`, and set BlueMap's web server to `ip: "127.0.0.1"`. In Latchkey, set `page.map-address` to the same address.

## Commands

- `/mapkeeper status`: whether the map is served, what's wrong with the config, and which plugins it found. Needs `mapkeeper.admin`.
- `/mapkeeper reload`: re-reads `config.yml`.

## License

Not decided yet. Private repos carry no license.
