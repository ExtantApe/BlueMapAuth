# Test audit

Kept by this repo's owning session, per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.


## 2026-11-24: security review fixes (0.2.1, after a fix)

- Promises: only an exact list of paths reaches BlueMap; encoded, dot and recased paths are refused; hidden maps and their live data stay hidden; live markers and the event stream never pass; a malformed hand-off is a 403; sign-in limits count an IPv6 /64 as one visitor.
- Covered: `MapServerTest.routeAllowsOnlyTheExactList`, `encodedDottedOrRecasedPathsNeverReachAKidsHiddenMap` (18 variants, BlueMap never asked), `liveMarkersAreNeverPassedOn`, `aMalformedPercentInAHandOffIsRefusedNot502`; `ClientAddressTest` (IPv6 /64, IPv4 per address, untrusted forwarding). Live: 12 variants sent raw against the real BlueMap, direct (27/27) and through Caddy with the staged block (28/28); fail closed 8/8.
- Gaps skipped: TLS, the http redirect and PROXY protocol are the proxy's, covered by Infrastructure's map-proxy-test.sh (17/17). The rate limit's per-/64 key is unit-tested; a live run with many IPv6 addresses isn't possible on 127.0.0.1.

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
| The token leaves the address bar; strict CSP on BlueMapAuth's own pages | `theHandOffPageMovesTheTokenOutOfTheAddressBarUnderAStrictPolicy` |
| A kid away from the game sees only their worlds' maps and their circle (D54) | `anAwayKidSeesOnlyTheirWorldsMapsAndTheirCircle` |
| A world the viewer can't enter: list entry, tiles, markers and players all absent, with the same 404 as a missing map | `anAdultWorldsMapIsNotThereForAKidAtAll` |
| In game, chat's reach decides (D41) | `anAdultSeesBothWorldsAndThePlayersTheyMayReach` (the reach rules themselves are Mayor' tests) |
| Odd BlueMap data shows nobody; a broken BlueMap shows an empty error | `oddPlayerListsShowNobody`, `aBrokenBlueMapGivesAnEmptyErrorWithNoDetails` |
| Map access ends within 30 s of WelcomeWagon's session, and after a day at most | `signingOutOfWelcomeWagonEndsTheMapWithin30Seconds`, `aMapSessionLastsADayAtMost` |
| Cookies and auth headers never reach BlueMap; its headers don't come back | `cookiesAndOtherHeadersNeverReachBlueMapAndItsHeadersDontComeBack` |
| Odd paths and methods refused; planted duplicate cookies count as none | `oddPathsAndMethodsAreRefused`, `twoMapCookiesCountAsNone`, `aMadeUpOrForeignCookieIsNoSession` |
| Unsafe config never starts: bind, BlueMap address, URLs, proxies, permission, limits | `SetupTest` (8 tests) |

Gaps, with why:
- **LiveAccess** (the calls into WelcomeWagon, Mayor, BlueMap and LuckPerms) is covered by the bot test, `test/live/bluemapauth.mjs`, 25/25 on 2026-11-11 with real plugins. It covers:
  - an away kid sees only their circle (D54), and in game, chat's reach (D41);
  - an adults-only world's map is absent for a kid;
  - losing a circle or a world reaches the map within 30 seconds;
  - signing out of WelcomeWagon, or removal from the whitelist, ends map access within 30 seconds.

  The fail-closed paths have their own run, `MK_TEST_MISSING=1`, which passed 8/8 on 2026-11-11 without Mayor or LuckPerms:
  - an away viewer gets no maps and no players;
  - an operator in game sees only themselves;
  - `/bluemapauth status` names what's missing.

  Without WelcomeWagon there's no way to make a hand-off token, and a made-up one is refused (`handOffsFromElsewhereOrMalformedAreRefused`).
- The hand-off page's script ran in a real browser on 2026-11-11 (`BrowserDemo`, made-up data). It signed in and landed on the map, the token left the address bar, the cookie wasn't readable by scripts, `settings.json` listed only the allowed map, the other map was 404, a reused link showed its message in a `role=status` line, and the console showed no policy errors. This was a manual check, not automated.
