# BlueMapAuth design (2026-11-10)

BlueMapAuth puts BlueMap's web map behind a sign-in and shows each signed-in player only what they may see. Owner decisions: D40 (the name, its own agnostic plugin), D41 (players on the map follow chat's reach rules; anyone BlueMapAuth can't check stays hidden), D54 (a viewer who isn't in game sees only players in their circles), D43 (its own address, `map.<domain>`, with its own host-only cookie from a one-time hand-off; never a cookie on the whole domain).

## Pieces

```
browser ──https──▶ Caddy (map.<domain>) ──▶ BlueMapAuth (127.0.0.1:8180) ──▶ BlueMap's web server (127.0.0.1:8100)
                                               │ in-process, by name:
                                               ├─ WelcomeWagon: hand-off tokens, session still valid?
                                               ├─ BlueMap API: which world each map shows
                                               ├─ LuckPerms: may this player enter that world? (offline too)
                                               └─ Posse: may the viewer reach that player?
```

- **BlueMapAuth is the only way in.** BlueMap's own web server listens on 127.0.0.1 only. BlueMapAuth also listens on 127.0.0.1 only, behind the same Caddy as the invite page.
- **No hard dependencies** (D2). WelcomeWagon, LuckPerms and Posse are each read by name. Without one, the part that needs it fails closed: no WelcomeWagon means nobody can sign in; no permissions check means no maps; no Posse means no other players on the map.

## Signing in (D43)

1. On the invite page's welcome view, a signed-in player clicks **Open the map**.
2. WelcomeWagon makes a one-time token for that player and that session: random, single use, good for 60 seconds, audience `map`. It redirects to `https://map.<domain>/handoff#<token>` (after `#`, so it never reaches a log).
3. The page posts the token to BlueMapAuth, which asks WelcomeWagon to redeem it (in-process). WelcomeWagon answers with the player and the session it came from, once.
4. BlueMapAuth sets its own cookie on `map.<domain>` only: HttpOnly, Secure, SameSite=Strict, no Domain attribute. It lasts no longer than the WelcomeWagon session, and ends when that session ends: BlueMapAuth asks WelcomeWagon whether it's still valid (cached for 30 seconds), so a sign-out, a revoke or a whitelist removal reaches the map within 30 seconds.

## What each viewer gets

| Request | Signed in | Not signed in |
| --- | --- | --- |
| `/settings.json` (the map list) | only maps of worlds they may enter | the sign-in page, no map names |
| `/maps/<id>/...` (tiles, textures, settings) | only for allowed maps; any other id is 404, the same as one that doesn't exist | 401 |
| `/maps/<id>/live/players.json` | only players they may reach (D41); a player it can't check isn't listed | 401 |
| `/maps/<id>/live/markers.json` | allowed maps only | 401 |
| the web app (`/`, `/assets/...`, `/lang/...`) | passed through | the sign-in page instead of the app |

- **Which worlds:** the same permission the server uses, `multiverse.access.<world>`, read through LuckPerms so it works while the player is offline. Can't read it: no map.
- **Which players:** with the viewer online in game, Posse's `mayReach(viewer, target)`, exactly chat's rule. With the viewer not in game (D54): only players who share a Posse circle with them. If Posse can't answer, nobody.
- Every answer carries `Cache-Control: private, no-store` for live data, the same security headers as the invite page, and no CORS.

## Changes in other plugins

- **WelcomeWagon:** a hand-off service (make a token for the welcome view's link, redeem it once, say whether a session is still valid) and the "Open the map" link, shown only when a map address is set.
- **Posse:** `sharesCircle(UUID viewer, UUID target)` for a viewer who isn't in game (D54), by circles only.

## Tests

Bots and HTTP, on throwaway servers with BlueMap, LuckPerms, Posse and WelcomeWagon:

- an unsigned visitor gets nothing: not the map list, not a tile, not players.json;
- a kid account never receives an adult world's map list entry, tiles or markers, and never an adult player in players.json;
- a hand-off token works once and expires; a token for another audience or a forged one is refused;
- signing out on the invite page, or removal from the whitelist, ends map access within 30 seconds;
- the cookie is host-only, HttpOnly, Secure and Strict;
- a kid who isn't in game sees only players in their own circle on the map, and nobody else, even players in the same world (D54);
- nothing from the server's player data leaks: positions only for players the viewer may reach.
