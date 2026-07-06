# Playing against (and learning from) a local AI agent

This fork adds an **agent API** so any local program — a coding agent like
Claude Code or Codex, a script, or a future dedicated bot — can join and play
games on your local jinteki.net server, or spectate yours to coach you.

```
┌──────────────┐  websocket   ┌─────────────────┐   HTTP /agent-api   ┌──────────────┐
│ your browser │ ───────────► │ local jinteki   │ ◄────────────────── │ coding agent │
│ (you play)   │              │ server :1042    │                     │ (bin/nr CLI) │
└──────────────┘              └─────────────────┘                     └──────────────┘
```

Three pieces:

- **`/agent-api/*` HTTP endpoints** (`src/clj/web/agent_api.clj`): lobby
  list/join/create/watch, deck selection, full per-side game state (exactly
  what the browser renders — hidden information stays hidden), a compact
  status summary with long-polling, and action execution using the same
  engine commands the browser sends.
- **`bin/nr`** — a dependency-free Python CLI over those endpoints
  (`bin/nr --help`).
- **Agent skills** (`.claude/skills/netrunner-agent`, `netrunner-tutor`) —
  instructions that turn a coding agent into an opponent or a tutor. Other
  agent harnesses can read the same files; they're plain markdown.

## Setup

1. Run the server as usual (see DEVELOPMENT.md / README): database seeded,
   `lein repl`, browser on http://localhost:1042.
2. Register **two accounts** in the browser: yours, and one for the agent
   (e.g. `claude`).
3. Log in as the agent account → **Settings → Create API Keys** → copy the
   key.
4. `bin/nr config --api-key <key>` (or export `JNET_API_KEY`).
5. Check: `bin/nr lobbies`.

## Play against an agent

In a coding-agent session in this repo, say something like *"use the
netrunner-agent skill and play a beginner game against me as the Corp"*. The
flow it will follow:

- It creates a lobby (`bin/nr create --gateway-type beginner`) — or you create
  one in the browser with **Allow API access** checked and it joins.
- You join from the lobby list in your browser; whoever created starts.
- The agent long-polls (`bin/nr wait`), reads state, and acts. You just play.

Note the API key only grants access to games the key's account is in **and**
that have API access enabled — same rule as the pre-existing read-only
`/game/*` API.

## Learn with a tutor

Open a **separate** coding-agent window and ask for the `netrunner-tutor`
skill. The tutor spectates your game (`bin/nr watch <gameid> --side <yours>`)
and coaches you **in that chat window**, not in the game UI. Create your
lobby with **Allow spectators** + **Allow API access**, and (for hand-aware
coaching) let it watch from your side's perspective.

You can run both at once — one agent window playing you, another tutoring —
they're independent sessions with separate seats (use two accounts/API keys if
both need to act; a watching tutor can reuse the player key of either seat
only if that account is spectating, so simplest is: agent account plays,
tutor uses your account's key to watch from your perspective... or just make
a third account).

## For agent authors

Full endpoint + command reference:
`.claude/skills/netrunner-agent/reference.md`. Summary:

- Auth header `X-JNet-API: <key>` on everything.
- `GET /agent-api/state?since=<version>&wait-ms=25000` long-polls your view of
  the game; `GET /agent-api/status` is a compact digest (prompt, credits, run
  phase, log tail).
- `POST /agent-api/action {"command": "...", "args": {...}}` — commands are
  the engine's own table (`game.core.process_actions/commands`); card
  references accept cids from your state view.
- Everything is JSON; errors roll the game state back server-side.

## Security notes

- The agent API is as trusted as a logged-in browser client for the account
  that owns the key — don't expose your dev server to the internet with keys
  floating around.
- Title-based card references only match the calling side's own cards, and
  each seat only ever receives its own side's stripped state, so a
  misbehaving agent can't read hidden information; the engine validates
  actions the same way it does for browser clients.

## Where this is going

See [ai-design.md](ai-design.md) for the non-LLM AI roadmap (heuristic bots →
ISMCTS search → self-play RL), all of which will sit behind this same API.
