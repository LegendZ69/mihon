# Issue tracker: GitHub

Issues and specs live in `LegendZ69/mihon`. Use the `gh` CLI.
Pass `--repo LegendZ69/mihon` explicitly to issue and PR commands
so operations target this fork.

## Conventions

- Create: `gh issue create --repo LegendZ69/mihon --title "..." --body-file <file>`.
- Read: `gh issue view <number> --repo LegendZ69/mihon --comments`.
- List: `gh issue list --repo LegendZ69/mihon --state open --json number,title,body,labels,comments`.
  Apply label and state filters as needed.
- Comment: `gh issue comment <number> --repo LegendZ69/mihon --body-file <file>`.
- Label: `gh issue edit <number> --repo LegendZ69/mihon --add-label "..."` or `--remove-label "..."`.
- Close: `gh issue close <number> --repo LegendZ69/mihon`.

For multiline bodies, write the exact Markdown to a temporary file
and pass it with `--body-file`.

“Publish to the issue tracker” means create a GitHub issue.
“Fetch the relevant ticket” means read the issue and its comments.

## Pull requests as a triage surface

**PRs as a request surface: no.**

GitHub shares issue and PR numbers. Resolve ambiguous references with
`gh pr view <number> --repo LegendZ69/mihon`, falling back to issue view.

## Wayfinding operations

- Map: one issue labelled `wayfinder:map`, containing Notes,
  Decisions-so-far, and Fog.
- Children: link tickets as GitHub sub-issues. If unavailable, use a
  task list in the map and `Part of #<map>` in each child.
  Label children `wayfinder:<type>` using research, prototype,
  grilling, or task.
- Blocking: use GitHub native issue dependencies through `gh api`,
  targeting `repos/LegendZ69/mihon`. Dependency operations use issue
  database IDs. If unavailable, record `Blocked by: #<number>` in
  the child body. All blockers must be closed before work starts.
- Frontier: select the first open, unassigned child in map order
  with no open blockers.
- Claim: assign the selected ticket to `@me`.
- Resolve: comment with the result, close the child, and append a
  concise result and link to the map’s Decisions-so-far.
