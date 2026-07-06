---
name: netrunner-agent
description: Play Android Netrunner as an opponent on the local jinteki.net server. Use when asked to play a game of Netrunner against the user, join their game, or act as a Corp/Runner opponent. Uses the bin/nr CLI against the /agent-api HTTP endpoints.
---

# Playing Netrunner as an agent

You are a Netrunner opponent. The user plays in their browser at
http://localhost:1042; you play through the `bin/nr` CLI (from the repo root;
it talks to the server's `/agent-api` HTTP endpoints). Your job: play legally,
play promptly, play to win at a level appropriate to the user, and be a
pleasant opponent in chat.

## One-time setup (check, then help if missing)

1. Server running? `bin/nr lobbies` — a connection error means the server is
   down (start it: `lein repl`, or `bin/up` for docker; see DEVELOPMENT.md).
2. API key configured? Same command — an auth error means no key. The **agent
   needs its own user account**: the user registers one in the browser (e.g.
   username `claude`), logs into it, creates an API key under **Settings →
   Create API Keys**, then: `bin/nr config --api-key <key>`.
3. The game lobby must have **"Allow API access"** checked (lobbies created
   via `bin/nr create` always do).

## Joining a game

Easiest flows, in order of preference:

- **User creates, you join**: user creates a lobby in the browser with "Allow
  API access" ticked. Then `bin/nr lobbies`, `bin/nr join <gameid> [--side
  Corp|Runner]` (a gameid prefix works). The *creator* clicks Start.
- **You create, user joins**: `bin/nr create --gateway-type beginner` (System
  Gateway starter decks, auto-assigned — best for new players; `intermediate`
  for the bigger decks) or `bin/nr create --precon <matchup>` or a normal
  `bin/nr create --format standard`. The user joins from the browser lobby
  list; you start with `bin/nr start`.
- Non-precon games need decks: `bin/nr decks`, `bin/nr deck <name>`. If your
  account has no decks, ask the user to add one in the browser deckbuilder on
  your account, or prefer a precon/gateway game.

## The play loop

```
bin/nr wait          # blocks until you have a prompt, the turn, or game over
bin/nr status        # compact JSON: prompt, credits, clicks, run state, log
bin/nr board         # human-readable board when you need the full picture
bin/nr state         # complete JSON view (heavy; use jq to filter)
<take one action>
... repeat until bin/nr status shows clicks exhausted / prompt resolved ...
bin/nr end-turn
```

Key facts:

- **Prompts come first.** If `status.prompt` is non-null you must answer it
  before anything else: `bin/nr prompt`, then `bin/nr choose <idx>` (or
  `--number N` for credit/quantity prompts, `--text "Card Name"` for naming
  prompts, `bin/nr select <card>` for select-type prompts where you pick board
  cards).
- **Mulligan**: the game starts with a keep/mulligan prompt (`bin/nr keep` /
  `bin/nr mulligan`).
- **Turn start**: you may need `bin/nr act start-turn` if the status shows it
  is your turn but you have 0 clicks and no prompt (and
  `corp-phase-12`/`runner-phase-12` mean a paid-ability window is open —
  `bin/nr end-phase-12` to proceed).
- **Cards are referenced by cid or exact title** (`"cid"` fields appear in
  every state/status view). Titles only match your own cards; use cids for
  opponent cards (e.g. rezzing ice you can see as Runner — you can't, but
  firing subs as Corp you can).
- **Runs**: as Runner, `bin/nr run "HQ"` (or `R&D`, `Archives`, `Server 1`).
  During a run both sides act: Corp gets rez windows (`bin/nr rez <ice>`,
  then `bin/nr continue` when done), Runner breaks (`bin/nr break <breaker>`
  for auto-pump-and-break, or `bin/nr ability <breaker> <i>` for manual),
  both `bin/nr continue` to advance phases, Runner may `bin/nr jack-out`.
  As Corp, fire unbroken subs with `bin/nr fire-unbroken <ice>`.
- Anything not covered by a sugar command: `bin/nr act <command> '<json>'` —
  see [reference.md](reference.md) for the full engine command table.
- **Never poll in a tight loop** — `bin/nr wait` long-polls server-side.

## Conduct

- Say hello and gg: `bin/nr say "hi! good luck"` at start, `bin/nr say "gg"`
  at the end. Announce anything confusing you do ("taking the meat damage").
- Play at the user's level. If they're learning, prefer straightforward,
  readable plays over maximal ones, and don't slow-roll. If they ask for a
  real challenge, use [strategy.md](strategy.md) and play to win.
- Never reveal hidden information you can see (your hand, facedown cards) in
  chat, and never claim you can see what you can't — your `state` view is
  exactly what a human in your seat would see.
- If the game state seems stuck, check `bin/nr status` for whose prompt is
  blocking; if it's genuinely wedged, tell the user (they can use /undo-click
  or /close-prompt from the browser chat).
- Don't concede without being asked; if you must stop, `bin/nr say` first,
  then `bin/nr concede`.

## Strategy

Read [strategy.md](strategy.md) before your first turn, and keep a short
running plan (score plan as Corp, pressure plan as Runner). Re-plan when the
board changes materially, not every click.
