# Non-LLM AI for Netrunner: design brainstorm

This document surveys ways to build a Netrunner-playing AI that doesn't rely on
a large language model, using this codebase (the jinteki.net engine) as the
substrate. It goes from cheap-and-playable to research-grade, and ends with a
recommended path.

## Why Netrunner is hard for game AI

Before picking a technique, it's worth being honest about what makes this game
nasty compared to chess/go/poker:

1. **Hidden information, asymmetrically.** The Corp knows its installed cards;
   the Runner doesn't. The Runner's hand is hidden from the Corp. Unlike poker,
   the hidden state is *combinatorial* (which of 49 cards is that unrezzed
   piece of ice?) and *strategically chosen* by the opponent, not dealt.
2. **Bluffing is core, not incidental.** A naked remote install is a bet priced
   in the opponent's beliefs. Optimal play is a mixed strategy; deterministic
   AIs are exploitable ("it never advances traps, so never check").
3. **Huge, heterogeneous action space.** Every card is a rules patch. The
   engine has ~3,000 implemented cards, each with abilities, triggers, prompts,
   and timing windows. An "action" isn't one of a fixed small set — it's
   play/install/rez/advance/run/break/choose-from-prompt, parameterized by card
   and target.
4. **Long horizons with sparse reward.** Games run 20–40 turns with dozens of
   micro-decisions per turn (paid ability windows, subroutine ordering). Credit
   for winning must propagate back through hundreds of decisions.
5. **Two completely different roles.** Corp and Runner are nearly different
   games. Any AI needs two policies (or one policy with a very good role
   conditioning).

The flip side: the engine here is a **perfect, fast, headless simulator**. The
test framework (`test/clj/game/core_test.clj`) already drives full games
programmatically (`new-game`, `play-from-hand`, `run-on`, `fire-subs`, ...),
and `game.core.diffs/public-states` gives a clean, information-safe view per
side. That's the hard part of most game-AI projects already done.

## Tier 0: scripted/heuristic opponents (days of work, immediately playable)

A rules-based bot that plays a *fixed, known deck* with hand-written policy.
This is the classic approach of solo-play variants (Terminal Directive's
"challenges" and community bots like the "Salvaged Memories"-style corp
gauntlets work this way): the bot's deck is designed so a simple policy is
still menacing.

**Corp bot** is the easier side (this is why most solo variants make the human
play Runner):

- Priority list per turn, e.g.:
  1. If an agenda in hand and a scoring remote is safe (enough ice, runner
     poor), install; else keep in hand until safe.
  2. Ice the weakest centrals first (HQ > R&D early), then the remote.
  3. Advance installed agendas if they can score this/next turn.
  4. Click for credits to stay above "rez the most expensive ice" threshold.
- Rez decision = simple expected-value: rez if `credits_after >= comfort` and
  ice actually taxes/stops the breaker types seen so far.
- Randomize deliberately: with probability p, install a trap/ambush in a
  scoring posture. A little noise buys a lot of unexploitability.

**Runner bot** heuristics:

- Track what the Corp *could* rez given credits (public info!). A big part of
  Runner skill is exactly this arithmetic and it's trivial for a program.
- Run R&D when accesses are cheap; run remotes when advanced cards appear;
  economy clicks otherwise. "Cheap" = expected cost through known/predicted ice
  vs. current credits.
- Standard priorities: get breakers out, keep money above the trace/damage
  danger lines, check every advanced remote unless a trap has been seen.

**Where heuristics plug in:** the engine reduces every decision to either a
click action or a prompt. So the bot is a function
`decide(view) -> action`, with a big `cond`:
mulligan? → keep-rule; my turn? → priority list; prompt? → per-prompt rules
(break everything that ends the run if affordable; choose lowest-cost option;
etc.). The `/agent-api` added in this fork exposes exactly that decision
surface over HTTP, and the same logic could run in-process in Clojure.

**Verdict:** the right first milestone. Great as a teaching opponent for System
Gateway; hopeless against a good player, which is fine.

## Tier 1: search — determinized MCTS / ISMCTS (weeks)

Monte-Carlo tree search needs (a) fast state copy + step, (b) enumerable legal
actions, (c) rollouts. The engine gives (a) — states are Clojure maps in an
atom; copying is cheap because of persistent data structures. (b) is the real
engineering job: writing `legal-actions(state, side)` that enumerates clicks,
playable cards (the state summaries already annotate `:playable` on cards!),
rez windows, and prompt choices.

For hidden information, two standard options:

- **Determinization (PIMC):** sample N possible worlds consistent with your
  observations (shuffle unseen cards into unknown slots), solve each with
  vanilla MCTS, vote. Known flaws (strategy fusion — it "knows" the trap in
  each sample; never bluffs) but works surprisingly well in practice
  (Dominion, MTG bots, card games generally).
- **ISMCTS (Information Set MCTS):** one tree over information sets; each
  iteration re-samples a world. Handles the fusion problem partially and is
  barely harder to implement than PIMC.

Practical constraints:

- Rollouts to game end are too long; use a depth cutoff + a hand-written
  evaluation function (agenda points scored/threatened, credit differential,
  ice coverage, cards in hand, tags). That eval function is basically Tier 0's
  heuristics reused as a scoring function — good synergy.
- Restrict to one card pool (System Gateway: ~60 distinct cards) so the
  enumeration and the eval stay sane.
- Chance nodes (accesses, PSI games, traces) are fine for MCTS by design.

**Verdict:** the sweet spot for a "actually tries to win" bot without ML
infrastructure. A System-Gateway-only ISMCTS corp/runner with a decent eval
would beat most beginners.

## Tier 2: reinforcement learning (months, research-flavored)

The engine makes self-play RL *feasible*; the action space makes it *hard*.

**Environment.** Wrap the Clojure engine as a gym-style env. Two routes:
- In-JVM: a Clojure namespace that resets/steps games directly through
  `game.core` (same functions the test framework uses), exposing observation
  tensors over a socket or via libpython-clj. Fastest (thousands of steps/sec,
  no HTTP).
- Out-of-process: the `/agent-api` from this fork. Fine for evaluation, too
  slow for training at scale.

**Observation encoding.** The public state is a nested map; flatten to
per-card feature vectors (card id embedding, zone, rezzed/advanced counters,
credits...) + global features. Transformer/set encoders fit naturally since
zones are variable-length sets; this is what recent card-game RL (e.g. work on
Hanabi, HearthStone simulacra, and the Slay-the-Spire/MTG hobby projects)
converged on.

**Action encoding — the crux.** Recommended: a two-level pointer policy:
1. pick an action *type* (click-credit, draw, play, run-HQ/RD/Archives/remote-k,
   rez, advance, prompt-choice, continue, jack-out, ...);
2. pick a *target* by attending over the card/choice set (pointer network).
Prompt answering reuses the same head (choices are just another set). Invalid
actions are masked using the engine's own legality (crucial — never learn the
rules from scratch when a simulator can mask).

**Algorithms.**
- Start with **PPO self-play + action masking**, separate Corp and Runner
  policies, league-style checkpoint pool to avoid strategy collapse
  (AlphaStar's trick). This is the workhorse; it will learn economy management
  and "run when ahead" quickly.
- **NFSP / regret-based methods** (Deep CFR-ish) are theoretically the right
  tool for the bluffing equilibrium, but at this scale they're a research
  project. A cheaper approximation: train PPO against a pool that includes
  *exploiter* agents trained specifically against the main agent — pushes
  toward mixed strategies without CFR machinery.
- **Model-free is enough**: the simulator is cheap; no need for MuZero-style
  learned models.

**Reward.** Win/loss (+shaping early: agenda points differential, credits at
small weight, annealed to zero). Beware reward hacking on shaping (bot that
hoards credits and never wins).

**Scope control is everything:** fix the matchup (the two System Gateway
starter decks), no deckbuilding, standard timing only. That's a genuinely
learnable game (~10^2 card types) and matches the tutor use case. Expanding
card pools later is transfer learning, not a restart.

**Verdict:** feasible and a fantastic project, but treat it as phase 3. The
big prerequisite it shares with Tier 1 is the same: a legal-action enumerator
and a fast headless step loop — build those once, use for both.

## Tier 3: behavior cloning from jinteki replays (interesting shortcut)

jinteki.net stores full replays (`:save-replay`, `web.stats/fetch-replay`) as
initial state + diff streams, and the site has hosted *millions* of games. A
supervised policy ("what would a human do in this view?") trained on replays
would give a surprisingly human-feeling opponent and a great initialization
for RL fine-tuning (the AlphaGo recipe). Even a few thousand of your own
archived games would train a passable System Gateway policy.

Caveats: replays record *outcomes* of actions, so you need to reconstruct
(view, action) pairs by replaying diffs through the engine; and public dumps
of other people's replays aren't available in bulk — this works best on
self-hosted data or with community consent.

## Recommended path for this fork

1. **Now:** heuristic bot driven through `/agent-api` (an LLM coding agent can
   *be* the Tier-0 bot today using the `netrunner-agent` skill — its "policy"
   is prompted heuristics; the skill ships with a strategy cheat-sheet).
2. **Next:** `legal-actions` enumerator in Clojure + a fast headless
   reset/step namespace (reusing test-framework plumbing). This unlocks both
   search and RL and is the single highest-leverage piece of code.
3. **Then:** ISMCTS over System Gateway with the Tier-0 eval function.
4. **Later:** PPO self-play on the fixed starter matchup, warm-started from
   replay cloning if data exists; evaluate by playing it against the ISMCTS
   bot and humans via the same agent API.

A nice property of this plan: every tier is a *client of the same interface*
(view → action), so the browser UI, the coding-agent skill, the heuristic bot,
the search bot, and the RL bot are all interchangeable opponents.
