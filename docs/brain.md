# The Brain screen

The Brain screen is the dashboard of the vault: four pages, switched with `Tab` / `Shift+Tab`, over a window of weeks that `w` cycles (8 by default, then 4 and 12). Code: `screens/Home.kt` (state, keys, layout), `screens/BrainPages.kt` (the blocks each page draws), `design/Charts.kt` (the charts, as plain text), `core/BrainStats.kt` (every number, as a pure function). Tests: `BrainStatsTest`, `ChartTest`.

| Page | Left | Right |
|---|---|---|
| **Overview** | *Pulse* (notes changed per week and tokens per week as sparklines, the busiest day, the trend against the usual pace), *Health* (linked, tagged, reports done, work done as bars), *Pipeline* (open, in progress and done per kind), *Where your notes live* (top folders), *Hubs* (the most linked-to notes) | the selectable list: **Bottlenecks** (ranked, `Enter` goes to the fix), **Next up** (the oldest open questions, todos and research), **Go to** |
| **Work** | the capture row and every open item of Questions, Research and Todos (`Enter` starts it in a chat linked to the item, `x` done / not done, `t` question ⇄ todo) | *Pipeline* and *Health* |
| **Health** | *Needs attention*, *Vault hygiene* and *Top tags* (selectable) | *Health*, *Where your notes live* (8), *Hubs* |
| **Activity** | a calendar of notes changed per day (a row per weekday, a column per week, `·░▒▓█`), then *Pulse* | notes changed and tokens per week, as bars |

`b` still opens all the work in one list, `h` the hygiene list.

## How the numbers are made

`BrainStats.compute` takes the notes of the vault index, the health numbers, the reports, the work items (with the titles of finished reports and answers), the usage records and the hygiene check, and returns a `BrainSnapshot`; it reads no file and no clock, which is why it is unit tested. `computeSnapshot` (in `BrainPages.kt`) gathers the inputs and runs on the IO thread whenever the vault, the hygiene check, the work or the usage changed.

- **Window:** whole weeks from a Monday, up to today; a week is the sum of its days. "Changed" is the modification time of a note, so a note edited ten times counts once, on its last day.
- **Trend:** the last two weeks against the average of the weeks before (needs 4 weeks; below 50% it becomes a bottleneck).
- **Bottlenecks** (`BrainStats.bottlenecks`), each with a score from 1 to 100 and an action: inbox notes waiting, reports not finished, research never started, open questions, open todos, items in progress without a finished answer, notes with broken links, islands, untagged notes, stale notes, four hygiene kinds (stale reports, no type, loose in the root, duplicate names) and a drop in activity. The score grows with the count and, for work, with the age of the oldest open item. When nothing applies the list says so (`Nothing is holding the brain back`).
- **Charts** (`Chart`): a sparkline is one character per value scaled to the largest (a zero is a dot); a bar is `█` filled and `░` empty and shows at least one cell for any value above zero; the calendar's five levels are `·░▒▓█`.

## Colours

The Brain screen uses no red and no green, so nothing in it shouts: good is a soft cyan, middling a soft violet, behind a soft amber (`Palette.CyanSoft`, `AccentSoft`, `OrangeSoft`: the theme's colour mixed into its background), and the empty part of a bar is the dim colour. The calendar runs from dim through cyan and violet.

## Limits

On a window narrower than 100 columns the Overview and the other pages show their list only (the charts need two columns). Nothing here changes any file: the pages only read.
