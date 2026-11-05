# Habit Tracker — Product and Build Specification

**Purpose:** Hand this document to a coding agent to build the first usable Android version.  
**Platform/UI:** Android, Jetpack Compose, Material 3, dark theme.  
**Status:** Product decisions are marked **Confirmed**. Items marked **Open** need a product decision before implementation; do not invent behavior for them.

## Product goal

Build a focused habit tracker for configuring time-bounded runs, logging due habits quickly, tracking impact points, writing goals, and recording personal metrics. The product should support a future connection into a task manager, where this habit tracker can be embedded. It should not include task-management features itself.

The user’s existing tracking model and screenshots are the primary design reference. Loop Habit Tracker and Way of Life are secondary interaction references; Griply contributes goal ideas; the task-manager screenshot is only an integration reference. Use the supplied mockups for current visual direction:

- Mobile screens: `outputs/habit-tracker-mobile-mockups.html`
- Larger-screen screens: `outputs/habit-tracker-mockup.html`

## Phase 1 scope

### Main navigation

Use a Material 3 bottom navigation bar with five destinations, in this order:

1. Runs
2. Analytics (show as a Phase 2 preview or leave unavailable in Phase 1; see Phase 2)
3. **Quick Log** (center destination)
4. Goals
5. Trackers

Use a dark Material 3 color scheme. Keep screens calm, readable, and compact. Avoid adding dashboard widgets that are not specified here.

### Habits and identity

- A habit has a stable, unique internal ID.
- A change to an existing habit’s target or definition is a new **iteration** of that same habit, not a new habit. Preserve its stable ID and history.
- Display a small Roman numeral iteration badge immediately after the habit name: I, II, III, IV, etc.
- Keep internal IDs out of ordinary user-facing habit labels.
- A habit has a name, impact points per full completion, iteration, and recurrence rule.
- Allow comments to be recorded against a habit for a specific date.

### Run creation

Provide a **Build a new run** screen with two entry choices:

- **Copy previous:** Select an earlier run, copy its habits and settings, then edit the copy without changing the original.
- **Start from scratch:** Create a run and add habits manually.

The builder includes a run name, duration (7 days, 14 days, 21 days, or custom), and editable habit cards. Each habit must have exactly one recurrence mode:

1. **Days per week:** A weekly target count, such as 3 days per week. The prototype treats this as a flexible count; exact week boundaries are an open decision.
2. **Every N days:** An interval from the most recent full completion.

Each habit editor also includes impact points per completion and its iteration badge when applicable. Do not force specific weekdays for a weekly count unless the user later asks for weekday scheduling.

### Interval due behavior

For an **every N days** habit:

- It becomes due on its initial due date.
- If skipped or left unlogged, it remains due and appears in later Quick Log sessions until completed.
- A full completion resets the interval. For an every-3-days habit completed on day 2, it is hidden on days 3 and 4 and is due again on day 5.
- Do not show it while it is in its post-completion waiting interval.

Weekly-count due behavior is not fully defined yet. Preserve the weekly target and log states without inventing daily overdue rules.

### Runs screen

The Runs screen is the primary habit tracking screen. Order the mobile content as follows:

1. Due habit groups and check-ins at the top.
2. Impact points by day chart.
3. Impact points burnup chart, with actual cumulative points and a predicted line.
4. Run information at the bottom, including impact and effort summary.

The run log should display the configured duration. On a phone, page through the full run in weekly slices (for example, week 1 of 3) so the complete 21-day record remains reachable without shrinking the day cells beyond usability.

Group habits visually (sample groups in the mockup: Wellbeing and Focus). Habit group assignment/editing details remain open unless they are already defined in the supplied user screenshots.

### Impact and effort scoring

- Assign an impact point value to each habit completion.
- Earned impact points are the sum of the values for completed habits on the relevant dates.
- Partial and skipped states are distinct from full completion.
- Show progress as earned impact points divided by planned/available impact points, not as a count of checked habits.
- Show total and average impact and total and average effort in the run summary.
- Compare raw total impact points across different cycles in Phase 2; do not use a habit-count consistency percentage to compare cycles whose point plans differ.
- The burnup chart is cumulative earned impact points by day. Draw the prediction as a distinct dashed line from the current actual total through the run end. Clearly label forecasts and demo values.

**Open scoring decisions:** Define whether partial completion earns a fraction of impact points, whether effort is entered per habit or per day, and how planned points are calculated for interval habits and missed opportunities. Keep these calculations behind a scoring service so the rules can be changed without rewriting screens.

### Quick Log

Quick Log is the center bottom-navigation destination and is intended to let someone log today’s due habits in about five minutes. Present one habit card at a time in a swipe-card stack.

- Swipe right: mark **Done** (full completion).
- Swipe left: mark **Skipped** (not complete). Skipped interval habits remain due until completed.
- Visible controls on the card: **Partial** and **Comment** only. Do not add visible Done or Skip buttons.
- Partial records a distinct partial state. Do not assume a numeric fraction or point value until the partial-scoring rule is decided.
- Comment opens a note field attached to that habit’s entry for the current date.
- Show swipe direction feedback and animate the current card away; reveal the next due habit card underneath.
- Provide accessible tap alternatives for swipe actions, such as screen-reader actions, without adding extra visible buttons.
- After all due habits, show a compact summary of Done, Partial, and Skipped counts.

The web prototype demonstrates this interaction with pointer/touch events; implement native drag gestures in Compose using pointer input/drag handling.

### Goals

- Goals are written outcomes, separate from habits and trackers.
- Show goal count and percentage complete.
- Show each goal’s progress graph starting from its start date.
- Do not add the goal timeline widget in Phase 1; it is deferred to Phase 2.

### Trackers

Keep a separate Trackers destination for custom measurements such as sleep quality, energy, mood, or weight, with dated entries and simple history graphs. Do not add habit correlations or “patterns to explore” in Phase 1.

## Phase 2

Defer these capabilities until the core Runs, Quick Log, Goals, and Trackers experience is stable:

- Named cycles that group related runs and cycle-level reviews.
- Cycle analytics, including impact-point comparison across cycles.
- Habit-group analytics. Use percentage of impact points earned divided by available impact points for each group; do not compare raw group point totals as the bar height when group plans differ.
- Experiment signal/baseline comparisons.
- Trackers shown alongside a run.
- Goal timeline view.
- Exploratory habit/tracker relationships, clearly labeled as correlations rather than causal claims.
- More advanced analytics after sufficient personal data exists.

The current mockup’s analytics screen is a Phase 2 preview. Example charts and automaticity ratings in the mockup are fictional sample data, not research-derived predictions or user data.

Habit automaticity can be displayed as a separate self-rating over time if that becomes a user-tracked measure. Research on asymptotic habit-strength curves does not establish a universal impact-return multiplier; do not use the curve to change impact scoring.

## Out of scope

- Tasks, Kanban, or task-manager views inside this application.
- A “Today / In Progress / Done” task workflow or a “Today’s progress” dashboard widget.
- Task-manager functionality in the standalone habit tracker. Future integration direction is one-way: embed habit-tracker capability into the user’s task manager.
- Automated claims that 21 days forms a habit.

## Suggested core data model

- **Habit:** stable ID, title, group, current iteration ID, archived state.
- **HabitIteration:** iteration ID, habit ID, Roman numeral/order, definition/target, impact points, recurrence rule, start/end dates.
- **Run:** ID, name, start date, duration, status, selected habit iterations, copied-from run ID (optional).
- **HabitLogEntry:** habit ID, iteration ID, date, state (`done`, `partial`, `skipped`), optional comment, earned impact points (computed according to the approved scoring rule).
- **Goal:** ID, written description, start date, target date (optional), progress value/history.
- **Tracker:** ID, name, value type/unit, cadence.
- **TrackerEntry:** tracker ID, date/time, value, optional note.

Keep habit identity separate from its iterations so upgrades preserve history. Keep scoring calculations out of UI composables.

## Acceptance criteria

- A new run can be created from scratch or copied and edited without mutating the source run.
- A habit can use either a weekly target count or an every-N-days interval.
- An overdue interval habit stays visible until full completion; completion starts the next interval.
- A habit upgrade creates a new iteration under the same stable habit identity.
- Quick Log shows due habits one at a time, supports right/left swipe, Partial, and a date-linked comment, and summarizes the session.
- No visible Done or Skip buttons appear on the Quick Log card.
- Run progress is impact-point based. Daily and burnup charts use earned impact points; burnup predictions are visibly distinguished from actual data.
- Goals and trackers remain distinct from habits.
- The Android UI follows Material 3 dark-theme patterns and works at narrow phone widths.

## Open decisions for product owner

1. What is the week boundary for a weekly target: calendar week, user-selected week start, or rolling 7 days?
2. Does Partial award a fixed fraction, a user-entered fraction, or no impact points initially?
3. What are the effort units and how are effort values entered?
4. How should a run calculate planned impact points when schedules vary or interval habits are overdue?
5. Can a run be paused, ended early, or edited after it starts? What happens to logs if its duration or habit settings change?
6. Are cycles definitely introduced in Phase 2, and should the Analytics destination be hidden until then or shown as a preview?
