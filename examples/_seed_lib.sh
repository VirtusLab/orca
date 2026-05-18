#!/usr/bin/env bash
#
# Shared helpers for `examples/*/create-test-project.sh`.
#
# Each example sources this file and drives four helpers:
#   parse_args "$@"           # honours --local / -h
#   resolve_dest "<prefix>"   # mktemp unless caller passed a path
#   init_destination $SEED_DIR $PLANS_DIR <flow-script> "<commit msg>"
#   apply_local_flag $REPO_ROOT "$DEST/<flow-script>"
#
# Passing `--local` to the example script swaps Maven Central for the
# local ivy cache: `sbt publishLocal` runs in the orca checkout, and the
# copied flow script gets its `using dep` pinned to the freshly-published
# dynver version plus an extra `using repository ivy2Local` line.

# Sets USE_LOCAL (0/1) and DEST (may be empty -> resolve_dest fills it).
parse_args() {
  USE_LOCAL=0
  local dest=""
  for arg in "$@"; do
    case "$arg" in
      --local)
        USE_LOCAL=1
        ;;
      -h|--help)
        cat <<USAGE
Usage: $(basename "$0") [--local] [<dest>]

  --local   Resolve org.virtuslab::orca from a local 'sbt publishLocal'
            instead of Maven Central. Runs publishLocal in the orca repo
            root, then patches the generated flow script with the current
            dynver-derived version and 'using repository ivy2Local'.
  <dest>    Destination directory (defaults to a fresh mktemp).
USAGE
        exit 0
        ;;
      --*)
        echo "unknown flag: $arg" >&2
        exit 64
        ;;
      *)
        dest="$arg"
        ;;
    esac
  done
  DEST="$dest"
}

# Picks a destination dir: mktemp(-d) if DEST is empty, else mkdir -p.
resolve_dest() {
  local prefix="$1"
  if [[ -z "$DEST" ]]; then
    DEST="$(mktemp -d -t "$prefix-XXXXXX")"
  else
    mkdir -p "$DEST"
  fi
}

# Copies the seed files + flow script into $DEST, then `git init` and
# makes one initial commit so the flow has something to diff against.
init_destination() {
  local seed_dir="$1" plans_dir="$2" flow_script="$3" commit_msg="$4"
  cp -R "$seed_dir/." "$DEST/"
  cp "$plans_dir/$flow_script" "$DEST/$flow_script"
  (
    cd "$DEST"
    git init -q -b main
    git -c user.name=orca-seed -c user.email=orca-seed@example.com \
        add . > /dev/null
    git -c user.name=orca-seed -c user.email=orca-seed@example.com \
        commit -q -m "$commit_msg"
  )
}

# If `--local` was given, runs `sbt publishLocal` at the orca repo root,
# reads the dynver-derived version, and rewrites the copied flow script
# so it resolves the local artifact:
#   - replaces the `using dep` version with the freshly-published one
#   - injects `//> using repository ivy2Local`
# No-op when --local was not passed.
apply_local_flag() {
  local repo_root="$1" script_path="$2"
  [[ "$USE_LOCAL" -eq 1 ]] || return 0

  # `sbt --client` reuses a running sbt server when one exists, skipping the
  # JVM cold-start. The first invocation boots the server (slow); subsequent
  # invocations in the same checkout are near-instant.
  echo "[orca] publishLocal — first run may take a minute…" >&2
  (cd "$repo_root" && sbt --client publishLocal)

  # `print version` aggregates to every subproject, so the output is a
  # block of (project / version, <ver>) pairs. They're all the same dynver
  # value; take the first version line (starts with whitespace + a digit).
  # sbt --client decorates lines with ANSI escapes — strip them first.
  local version
  version="$(
    cd "$repo_root" \
      && sbt --client --error 'print version' \
      | sed -E $'s/\x1b\\[[0-9;]*[A-Za-z]//g' \
      | grep -m1 -E '^[[:space:]]+[0-9]' \
      | tr -d '[:space:]'
  )"
  if [[ -z "$version" ]]; then
    echo "[orca] could not read orca version from sbt" >&2
    return 1
  fi

  awk -v ver="$version" '
    /^\/\/> using dep "org\.virtuslab::orca:[^"]+"$/ {
      print "//> using dep \"org.virtuslab::orca:" ver "\""
      print "//> using repository ivy2Local"
      next
    }
    { print }
  ' "$script_path" > "$script_path.tmp" && mv "$script_path.tmp" "$script_path"

  echo "[orca] flow script pinned to org.virtuslab::orca:$version (ivy2Local)" >&2
}
