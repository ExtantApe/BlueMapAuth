# Decisions

Dated decisions for this repo, newest last.

- 2026-11-10: created from the ops starter kit, area `minecraft-plugins`.
- 2026-11-10 (owner, D40): Mapkeeper is its own plugin, with no hard dependency on any other.
- 2026-11-10 (owner, D41): players on the map follow chat's reach rules; anyone Mapkeeper can't check stays hidden.
- 2026-11-10 (owner, D43): the map has its own address, `map.<domain>`, and its own host-only cookie from a one-time hand-off. There is never a cookie on the whole domain.
- 2026-11-10 (owner, D54): a viewer who isn't in game sees only players who share a HouseRules circle with them. In game, D41's full chat reach applies. Anything Mapkeeper can't check stays hidden.

## 2026-11-14

- **First release and going public.** The owner approved release tags for the staged jars (Helm chat, 2026-11-14, "Go (Recommended)"), under the standing yes of 2026-11-07 for a private Minecraft plugin repo to go public at its first release. Before going public: a history audit (no secrets, private terms, server addresses or player data; only noreply commit emails; no build output), the leak check in CI, an MIT LICENSE and a README status line.

## 2026-11-14

- **Name (owner, D194):** Mapkeeper is renamed BlueMapAuth, so people looking for BlueMap add-ons find it. The owner: "yes… BlueMapAuth". Released as 0.2.0. The owner asked that no old names be kept, so there's no alias, old permission or folder copy.
