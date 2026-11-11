# Test audit

Kept by this repo's owning session, per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

## 2026-11-10: first audit (starter kit)

- Promises: none yet.
- Covered: none yet.
- Gaps added: none.
- Gaps skipped (and why): none.

## 2026-11-11: first build

| Promise | Covered by |
| --- | --- |
| Nothing of the map for a visitor who isn't signed in, and BlueMap is never asked | `aVisitorWhoIsntSignedInGetsNothingOfTheMap` |
| Hand-off: once, host-only Strict cookie, only from the map's own origin, rate limited | `aHandOffSetsAHostOnlyStrictCookieOnce`, `handOffsFromElsewhereOrMalformedAreRefused`, `handOffTriesAreRateLimited` |
| The token leaves the address bar; strict CSP on Mapkeeper's own pages | `theHandOffPageMovesTheTokenOutOfTheAddressBarUnderAStrictPolicy` |
| A kid away from the game sees only their worlds' maps and their circle (D54) | `anAwayKidSeesOnlyTheirWorldsMapsAndTheirCircle` |
| A world the viewer can't enter: list entry, tiles, markers and players all absent, with the same 404 as a missing map | `anAdultWorldsMapIsNotThereForAKidAtAll` |
| In game, chat's reach decides (D41) | `anAdultSeesBothWorldsAndThePlayersTheyMayReach` (the reach rules themselves are HouseRules' tests) |
| Odd BlueMap data shows nobody; a broken BlueMap shows an empty error | `oddPlayerListsShowNobody`, `aBrokenBlueMapGivesAnEmptyErrorWithNoDetails` |
| Map access ends within 30 s of Latchkey's session, and after a day at most | `signingOutOfLatchkeyEndsTheMapWithin30Seconds`, `aMapSessionLastsADayAtMost` |
| Cookies and auth headers never reach BlueMap; its headers don't come back | `cookiesAndOtherHeadersNeverReachBlueMapAndItsHeadersDontComeBack` |
| Odd paths and methods refused; planted duplicate cookies count as none | `oddPathsAndMethodsAreRefused`, `twoMapCookiesCountAsNone`, `aMadeUpOrForeignCookieIsNoSession` |
| Unsafe config never starts: bind, BlueMap address, URLs, proxies, permission, limits | `SetupTest` (8 tests) |

Gaps, with why:
- **LiveAccess** (the calls into Latchkey, HouseRules, BlueMap and LuckPerms) is covered by the bot test, `test/live/mapkeeper.mjs`, 25/25 on 2026-11-11 with real plugins. It covers:
  - an away kid sees only their circle (D54), and in game, chat's reach (D41);
  - an adults-only world's map is absent for a kid;
  - losing a circle or a world reaches the map within 30 seconds;
  - signing out of Latchkey, or removal from the whitelist, ends map access within 30 seconds.

  The fail-closed paths have their own run, `MK_TEST_MISSING=1`, which passed 8/8 on 2026-11-11 without HouseRules or LuckPerms:
  - an away viewer gets no maps and no players;
  - an operator in game sees only themselves;
  - `/mapkeeper status` names what's missing.

  Without Latchkey there's no way to make a hand-off token, and a made-up one is refused (`handOffsFromElsewhereOrMalformedAreRefused`).
- The hand-off page's script ran in a real browser on 2026-11-11 (`BrowserDemo`, made-up data). It signed in and landed on the map, the token left the address bar, the cookie wasn't readable by scripts, `settings.json` listed only the allowed map, the other map was 404, a reused link showed its message in a `role=status` line, and the console showed no policy errors. This was a manual check, not automated.
