# Domain Docs

## Layout and reading rules

This repository uses a single-context layout:

- `CONTEXT.md` at the repository root holds the domain glossary.
- `docs/adr/` holds architectural decision records.

Before exploring the codebase, read the glossary and any ADRs relevant
to the work. If a root `CONTEXT-MAP.md` is introduced later, follow its
pointers to relevant context documentation and scoped ADRs.

If these documents are absent, proceed silently. Domain modeling
creates them lazily when terms or decisions are resolved.

## Vocabulary

Use glossary terms in issue titles, proposals, hypotheses, and tests.
When a needed concept is absent, check existing project language and
note real gaps for domain modeling.

## Decisions

Explicitly identify any proposal that contradicts an existing ADR,
cite that ADR, and explain why the decision should be reconsidered.
