# polyllmith (template repo)

This repo is a **deps-new template library**, not a runnable project. The files under `resources/brianium/polyllmith/` are the template payload; `template.edn` is the descriptor deps-new reads. There is no code to run at the root — verification means *generating a workspace and testing it*.

## Layout

```
resources/brianium/polyllmith/
├── template.edn          # :transform descriptor (deps-new)
├── build/                # → generated project ROOT (deps.edn, bb.edn, AGENTS.md, ...)
├── .github/              # → .github/ — copied :raw
├── .claude/              # settings.json + skills/clojure-eval — substituted
├── claude-raw/           # skills/discuss → .claude/skills/discuss — copied :raw
├── .clj-kondo/ bases/ projects/ development/
└── components/{secrets,browser}/
    └── src/<brick>/...   # NOTE: no top-ns dir level on disk — the transform
                          # target inserts {{top/file}}/{{main/file}}
```

## Substitution rules (critical)

- Files go through mustache `{{ }}` substitution **unless their transform is `:raw`**.
- `:raw` transforms and why they must stay raw:
  - `.github/` — GitHub Actions `${{ }}` expressions would be eaten by mustache.
  - `claude-raw/` (the discuss skill) — its prompt template uses literal `{{brief}}` / `{{question}}` placeholders.
- Never add a file containing literal `{{` to a substituted transform. Clojure nested-map destructuring (`[{{:keys [x]} :y}]`) is the sneaky case — check with `grep -rn '{{' <dir>` before adding source files.
- Placeholder conventions: `{{top/ns}}.{{main/ns}}` in namespace forms and lib coords, `{{top/file}}/{{main/file}}` in transform target paths, `{{name}}` in doc headings.
- Brick source lives at `components/<brick>/src/<brick>/...` on disk (no top-ns dir); the transform target path supplies the `{{top/file}}/{{main/file}}` prefix.

## Verifying changes

```bash
cd /tmp && rm -rf shop
clojure -Sdeps '{:deps {io.github.brianium/polyllmith {:local/root "/path/to/polyllmith"}}}' \
  -Tnew create :template brianium/polyllmith :name acme/shop
cd shop
grep -rn '{{' . --exclude-dir=.claude --exclude=ci.yml   # should be empty
bb kondo:lint
bb test
```

CI (`.github/workflows/ci.yml` at this repo's root) does exactly this on every PR.

## Releases

Follow the tag-the-content-commit workflow:

1. Commit the template changes (e.g. `abc1234`).
2. `git tag vX.Y.Z abc1234`
3. Update README.md's pinned `{:git/tag "vX.Y.Z" :git/sha "abc1234"}`.
4. Commit the doc update (`chore: bump version to vX.Y.Z`) — this commit is NOT tagged.
5. `git push && git push --tags`
