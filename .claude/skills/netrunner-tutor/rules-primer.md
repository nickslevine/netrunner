# Netrunner rules primer (teaching curriculum)

Ordered so each layer is playable before the next is introduced. This matches
current NSG rules (the ones this engine implements).

## Layer 1: the core loop

- Two asymmetric players. **Corp** advances hidden agendas to score them;
  **Runner** breaks into Corp servers to steal them. First to **7 agenda
  points** wins. Corp also wins by killing the Runner (**flatline**); Runner
  also wins if the Corp must draw from an empty deck.
- **Clicks** are actions: Corp gets 3/turn (after a mandatory draw), Runner
  gets 4. **Credits** are money. Basic actions: 1 click = 1 credit, or draw a
  card; cards cost credits to play/install.
- Corp cards come in: **agendas** (points; only Corp can install them, in
  remotes), **ice** (walls protecting servers), **assets** (money/utility in
  remotes), **upgrades**, **operations** (one-shot). Runner: **programs**
  (including **icebreakers**), **hardware**, **resources**, **events**.
- **Servers**: HQ (Corp hand), R&D (Corp deck), Archives (Corp discard) are
  *centrals*; *remotes* are built by installing cards. Every server can be
  protected by ice.
- Corp cards are installed **face down** (unrezzed); the Corp pays to **rez**
  them when relevant. This hidden info is the heart of the game.
- Agendas: Corp installs in a remote, **advances** (1 click + 1 credit each),
  scores when advancements = requirement. Runner steals them on access —
  agendas can't be "defended" once accessed.

## Layer 2: runs

A **run** = Runner attacks a server (1 click):

1. Approach each piece of ice outermost-first. Corp may **rez** it (pay its
   cost) when approached.
2. If rezzed: **encounter** — its **subroutines** fire unless the Runner
   **breaks** them with an icebreaker (pay to match strength, pay per sub).
   Subroutines do things like "end the run", damage, tags.
3. Past all ice: **access** — Runner sees cards (HQ: random from hand; R&D:
   top of deck; remote: everything there). Steal agendas; may **trash**
   assets/upgrades by paying their trash cost.
- Runner can usually **jack out** between ice (not before the first).
- Breaker types match ice types: fracter→barrier, decoder→code gate,
  killer→sentry. AI breakers break anything, with drawbacks.

## Layer 3: danger

- **Damage** (net/meat/core): discard random cards from the Runner's hand
  (grip). More damage than cards in hand = **flatline**. Core damage lowers
  max hand size permanently.
- **Tags**: while tagged, Corp can trash the Runner's resources and use tag
  punishment. Clearing a tag: 1 click + 2 credits.
- **Traces**: `Trace(N)` — Corp may boost N with credits, Runner boosts link;
  Corp wins ties... teach as "a credit bidding war the Corp starts".
- **Traps/ambushes**: Corp cards that punish access or advancement bluffs
  (they can be advanced to look like agendas).
- **Bad publicity**: Runner gets 1 free credit per point during each run.

## Layer 4: timing fine print (introduce only when it comes up)

- **Paid ability windows**: brief moments (start of turn, between run steps)
  where either side may use abilities/rez non-ice. The engine prompts for
  these; beginners just click through.
- Corp turn: mandatory draw → 3 clicks; discard to max hand size (5) at end.
- Runner turn: 4 clicks, discard at end.
- "When installed / when scored / when stolen" triggers, `interrupt`s, and
  simultaneity: defer to the engine; it enforces the order.

## Strategy concepts (post-basics curriculum)

1. **Economy is everything.** Most games are lost by being poor. Efficiency:
   every click should net > 1 credit of value.
2. **Information asymmetry pricing**: unrezzed ice and facedown remotes are
   *bets*. Teach the Runner to price a run (worst-case rez + break cost vs
   credits) and the Corp to price a bluff.
3. **The remote game**: Corp wants exactly one good scoring remote; Runner
   wants to make holding it unprofitable. "Can I contest the remote?" is THE
   recurring decision.
4. **Central pressure**: R&D lock (repeated multi-access) and HQ pressure win
   games without touching remotes; Archives punishes sloppy discards.
5. **Scoring windows**: the Corp scores when the Runner *can't* contest
   (poor, no breaker, wrong moment). Recognizing and creating windows is the
   Corp's core skill; denying them (stay rich, stay ready) is the Runner's.
6. **Threat assessment turn order**: each turn ask — can I lose next turn?
   (flatline? score-out?) → block that; else advance my own win condition.

## Glossary quickies

grip=Runner hand, stack=Runner deck, heap=Runner discard, rig=Runner board;
HQ/R&D/Archives=Corp hand/deck/discard; ETR="end the run" subroutine;
ICE=intrusion countermeasures; face-check=running unrezzed ice to see it;
float tags=not clearing them; 3/2=3-to-advance, 2-point agenda; glacier=big
ice tax Corp style; rush=score fast early behind cheap ice; jinteki.net=this
software.

## Useful engine facts for tutoring

- The in-game chat accepts slash commands; the ones worth teaching a
  beginner's table: `/undo-click`, `/undo-turn`, `/close-prompt` (fix stuck
  prompts). Full list in the site help.
- Card text for any card: `GET /data/cards` on the local server, or hover in
  the browser.
- System Gateway is the designed learning product: small card pool, no
  degenerate combos, its **beginner** decks teach layers 1–3 naturally.
