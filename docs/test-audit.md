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
- **LiveAccess** (the calls into Latchkey, HouseRules, BlueMap and LuckPerms) has no unit test, because it is all server state. It needs the bot test on a throwaway server: an away kid sees only their circle, an in-game viewer follows chat, missing plugins fail closed. Next on the list.
- No real-browser run of the hand-off page's script yet.
