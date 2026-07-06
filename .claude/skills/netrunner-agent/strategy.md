# Netrunner strategy cheat-sheet (heuristic policy)

A compact decision policy. It won't beat experts, but it plays a coherent,
honest game. When in doubt, take the action this file says; deviate when you
can articulate why.

## Universal

- **Count everything public.** Opponent credits, cards in hand, clicks left.
  Before any risk, compute: what can they afford? (Corp: which ice/traps are
  rezzable/firable. Runner: which runs are survivable.)
- **Tempo**: an action is good if it gains >1 credit-equivalent of value.
  Clicking for 1 credit is the baseline — most cards beat it; play econ cards
  before clicking for credits.
- **Win conditions only**: 7 agenda points, flatline (Corp), deck-out (Runner
  decked = Corp win). Everything else is instrumental.

## Corp policy

Turn skeleton (3 clicks):

1. **Mandatory draw happens automatically.** If hand has an agenda and the
   scoring remote is safe → install it there; advance-advance next turn (or
   install-advance-advance a 3/2 to score from hand next turn... track the
   math: an agenda needing N advancements scores in ceil((N-remaining clicks)
   ) extra turns).
2. **Safe remote** = ice the runner cannot profitably break with current rig
   + credits, or cost to enter > what they have. If no safe remote, build one:
   install ice on remote, rez only when run.
3. **Ice priorities**: 1st ice on HQ and R&D early (stop cheap central
   pressure), then remote, then double up on whatever the Runner hits most.
   Cheap ETR ("end the run") ice on centrals; taxing ice on the remote.
4. **Economy floor**: end turns with enough credits to rez your best ice on
   the server they'll run + fire key abilities. If below, take econ actions.
5. **Traps & bluffs** (if deck has them): occasionally install-advance a trap
   exactly like an agenda. Do it when the Runner is poor or tagged.
6. **Tags**: if Runner floats tags, punish (trash resources, tag punishment
   cards) before they matter less.
7. **Hand discipline**: don't overdraw into agenda flood; keep hand ≤ max,
   discard junk not agendas.

Rez decisions: rez ice iff (a) it likely stops/taxes this run AND (b) you
keep enough credits to function. Don't rez taxing ice on a run you don't care
about; do rez ETR to protect an agenda or a fresh install.

## Runner policy

Turn skeleton (4 clicks):

1. **Setup phase** (turns 1–4ish): install economy, then breakers (or tutors
   for them). Run only clearly cheap targets (unadvanced remotes with no ice,
   naked centrals) — early Corp credits are low, ice unrezzed: probe runs are
   cheap information.
2. **Pressure phase**: once you can break the rezzed ice types, run where the
   agendas are: R&D repeatedly (multi-access if available), remotes whenever
   something is installed-and-advanced or could be scored next turn.
3. **Check every scoring posture**: an install in an iced remote + Corp
   credits to advance twice = run it NOW unless a known trap pattern or you
   physically can't get in. Letting a 2-adv card sit is how you lose.
4. **Money rule**: keep credits ≥ cost of your best single run + 2. Below
   that, take econ actions. Never end your turn at 0 credits vs a trace/tag
   deck with cards in hand you can't afford to lose.
5. **Damage safety**: vs meat/net damage decks keep hand size ≥ 2 (≥ 3 vs
   known kill combos); don't run last-click into sentries you can't break.
6. **Tags**: clear tags immediately unless you can win before punishment or
   the deck has none visible.
7. **Archives is free money** when Corp discards face-down cards mid-game;
   check it when it's cheap.

Run execution: prefer `auto-pump-and-break` (the `break` CLI command); jack
out when an unrezzed ice gets rezzed into something you can't beat and
there's no must-steal behind it.

## Prompt heuristics (both sides)

- "End the run" style choices as Corp: yes if protecting agenda/economy,
  otherwise consider letting them through cheap ice to tax credits.
- Access prompts as Runner: steal agendas always; trash key econ/defensive
  assets when affordable and you're not poor; otherwise "No action".
- Damage/trash order prompts: keep combo pieces and breakers; pitch
  duplicates and situational cards.
- Traces: as Corp pay to land tags only if you can use them; as Runner beat
  traces that lead to death/tag-punishment, let harmless ones land... but
  count what the tag costs to clear (1 click + 2 credits) vs beating it.
- PSI games (bid 0/1/2): mix it up; slightly favor 0 as bidder-who-pays.

## Chat etiquette during play

Narrate intent lightly ("checking archives", "gotta respect that remote"),
never information. If the user misplays badly in a teaching game, point it
out gently after the action resolves, not before.
