# Agent API reference

Everything the `bin/nr` CLI does is plain HTTP against the local server. Auth:
`X-JNet-API: <api key>` header on every request. Base URL default
`http://localhost:1042`.

## Endpoints

| Method/Path | Body / params | Purpose |
|---|---|---|
| GET `/agent-api/lobbies` | — | list lobbies (gameid, players, started, api-access) |
| GET `/agent-api/lobby` | — | the lobby you're in + your side |
| POST `/agent-api/lobby/create` | `{title, format, side, password, precon, gateway-type, spectatorhands, open-decklists}` | create lobby (API access always on) |
| POST `/agent-api/lobby/join` | `{gameid, request-side, password}` | join as player (`request-side`: side YOU want) |
| POST `/agent-api/lobby/watch` | `{gameid, request-side, password}` | spectate; `request-side` = see that side's cards |
| POST `/agent-api/lobby/leave` | `{}` | leave lobby/game |
| GET `/agent-api/decks` | — | your saved decks |
| POST `/agent-api/lobby/deck` | `{deck-id}` or `{name}` | select deck for the lobby |
| POST `/agent-api/lobby/start` | `{}` | start (creator only, both decks selected) |
| GET `/agent-api/state` | `?since=<version>&wait-ms=<n>` | full state, your perspective; long-polls if `since` given |
| GET `/agent-api/status` | `?since=<version>&wait-ms=<n>` | compact summary (below) |
| POST `/agent-api/action` | `{command, args}` | execute an engine command; returns updated status |
| POST `/agent-api/say` | `{text}` | chat |
| POST `/agent-api/concede` | `{}` | concede |

`version` is an opaque cursor; pass the last one you saw as `since` to block
(max 25s) until something changes.

## Status shape

```json
{"perspective": "Corp", "version": "12.9.44.0", "turn": 4,
 "active-player": "corp", "your-turn": true,
 "corp": {"credit": 8, "click": 2, "hand-count": 5, "agenda-point": 2, ...},
 "runner": {...},
 "prompt": {"msg": "...", "prompt-type": "...",
            "choices": [{"idx": 0, "uuid": "...", "value": "Yes"}, ...]},
 "run": {"server": ["hq"], "position": 1, "phase": "encounter-ice"},
 "encounter": {"ice": {"cid": "...", "title": "Whitespace", "subroutines": [...]}},
 "log": ["last 15 log lines ..."]}
```

## Engine commands (`POST /agent-api/action`)

These are exactly the commands the browser client sends (see
`src/clj/game/core/process_actions.clj`). `card` args accept a cid string, an
exact title of one of YOUR cards, or `{"cid": "..."}`.

Basic turn actions:

| command | args | notes |
|---|---|---|
| `credit` | `{}` | click for 1 credit |
| `draw` | `{}` | click to draw |
| `play` | `{card}` | play event/operation OR install from hand (server choice arrives as a prompt) |
| `remove-tag` | `{}` | runner basic action |
| `purge` | `{}` | corp basic action |
| `advance` | `{card}` | corp: advance installed card |
| `score` | `{card}` | corp: score agenda (when advanced enough) |
| `trash` | `{card}` | e.g. trash accessed card via prompt is usually a `choice` instead |
| `rez` / `derez` | `{card}` | corp, installed cards |
| `run` | `{server}` | `"HQ"`, `"R&D"`, `"Archives"`, `"Server 1"` |
| `start-turn` / `end-turn` | `{}` | |
| `end-phase-12` | `{}` | close start-of-turn paid window |
| `keep` / `mulligan` | — | answer the opening-hand **prompt** with `choice` (the `nr keep`/`nr mulligan` CLI commands do this); the raw engine commands do not clear the prompt |
| `shuffle` | `{}` | shuffle your deck (after searches, usually automatic) |
| `concede` | `{}` | |

Prompts and abilities:

| command | args | notes |
|---|---|---|
| `choice` | `{choice: {uuid}}` or `{choice: <number>}` or `{choice: "text"}` | answer the current prompt; uuid comes from status.prompt.choices |
| `select` | `{card}` | pick a board card for select-type prompts |
| `ability` | `{card, ability: <idx>}` | card's ability list is in state as `:abilities` |
| `corp-ability` / `runner-ability` | `{card, ability: <idx>}` | abilities usable by the *other* side's cards (rare) |
| `dynamic-ability` | `{card, dynamic: "auto-pump-and-break"}` | also `"auto-pump"` |
| `subroutine` | `{card, subroutine: <idx>}` | corp fires one sub |
| `unbroken-subroutines` | `{card}` | corp fires all unbroken subs |
| `continue` | `{}` | advance run phase (both sides must continue) |
| `toggle-auto-no-action` | `{}` | corp: auto-continue when nothing to rez |
| `jack-out` | `{}` | runner, at allowed timing |
| `move` | `{card, server}` | drag between zones (rarely needed) |
| `expend` | `{card}` | corp: expend-cost cards from hand |
| `flashback` | `{card}` | runner: flashback events from heap |

Notes:

- After `play` of an installable card, the server-choice ("HQ", "New remote",
  ...) comes back as a normal prompt — answer with `choice`.
- During runs the flow is: corp rez window → both `continue` → encounter
  (runner breaks via `dynamic-ability`/`ability`, corp `fire-unbroken` if subs
  left) → repeat per ice → approach server → access prompts.
- Trace prompts and credit prompts are numeric: `{"choice": 3}`.
- If a command errors, the server rolls the game state back and returns the
  exception message; re-read `status` before retrying.
- The state JSON marks cards you can currently afford/play with
  `"playable": true`, and installed-ability targets carry `:abilities` with
  costs — trust those over your own arithmetic.

## Formats for `create`

`standard` (default), `startup`, `eternal`, `casual`, `system-gateway`
(+ `gateway-type: beginner|intermediate` for auto starter decks),
`preconstructed` (+ `precon` matchup key), `core`, `throwback`, `sunset`,
`neo`. For teaching games use `gateway-type: beginner`.
