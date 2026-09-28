# Orca documentation

Source for the Orca documentation site, built with Sphinx + MyST and hosted on
Read the Docs. Read the Docs builds straight from this folder (`.readthedocs.yaml`
at the repo root), so committing a change here is all it takes to publish it.

## Run locally

From this folder:

```
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
./watch.sh
```

Open <http://127.0.0.1:8000>. Edits to `.md` files live-reload in the browser.

Next time, just:

```
source .venv/bin/activate
./watch.sh
```

`make html` builds once into `_build/html`; CI runs `sphinx-build -W` so a
broken cross-reference or toctree entry fails the build.

## Notes

- Orca's version in code snippets (`//> using dep "org.virtuslab::orca:…"`) is
  bumped by the release (`sbt updateDocs`), which walks this folder like it
  walks `flows/` and `examples/`.
- The pages here are also bundled into the `orca-shell` jar as the API
  reference `orca create` / `orca fork` hand to the authoring agent
  (`build.sbt`), so keep them accurate for a reader that has no other source.
- `plans/`, `research/` and `superpowers/` in this directory are internal
  project documents — they are excluded from the published site.
