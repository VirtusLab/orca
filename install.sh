#!/bin/sh
# Installs the `orca` shell shim into ~/.local/bin.
#
#   curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash
#
# The shim is a short script that hands off to `scala-cli run` against the
# `latest.release` of `orca-shell` — nothing is downloaded or compiled here,
# so re-running this script never needs a version bump. If scala-cli itself
# is missing, this script bootstraps it via its official installer first.
set -eu

marker="# orca-shell shim - safe to overwrite"
bin_dir="${HOME}/.local/bin"
bin_path="${bin_dir}/orca"

if [ -e "$bin_path" ] && ! grep -qF "$marker" "$bin_path" 2>/dev/null; then
  echo "orca: $bin_path already exists and isn't an orca shim - refusing to overwrite" >&2
  echo "orca: remove it (or move it aside) and re-run this script" >&2
  exit 1
fi

if command -v scala-cli >/dev/null 2>&1; then
  version="$(scala-cli version --cli --offline 2>/dev/null || true)"
  echo "orca: found scala-cli${version:+ $version}"
else
  if ! command -v curl >/dev/null 2>&1; then
    echo "orca: scala-cli (orca's runtime) is missing, and curl isn't available to install it" >&2
    echo "orca: install scala-cli yourself (https://scala-cli.virtuslab.org/install), then re-run this script" >&2
    exit 1
  fi

  echo "orca: scala-cli (orca's runtime) is missing - installing it via its official installer (https://scala-cli.virtuslab.org/get)"
  installer="$(mktemp)"
  trap 'rm -f "$installer"' EXIT
  if ! curl -sSLf https://scala-cli.virtuslab.org/get -o "$installer"; then
    echo "orca: failed to download the scala-cli installer from https://scala-cli.virtuslab.org/get" >&2
    echo "orca: install scala-cli yourself (https://scala-cli.virtuslab.org/install), then re-run this script" >&2
    exit 1
  fi
  if ! sh "$installer"; then
    echo "orca: the scala-cli installer failed" >&2
    echo "orca: install scala-cli yourself (https://scala-cli.virtuslab.org/install), then re-run this script" >&2
    exit 1
  fi

  if command -v scala-cli >/dev/null 2>&1; then
    echo "orca: scala-cli installed"
  else
    echo "orca: scala-cli installed, but isn't on your PATH in this shell yet (the installer"
    echo "orca: updates your shell profile) - open a new shell, then re-run this script"
  fi
fi

mkdir -p "$bin_dir"

cat > "$bin_path" <<EOF
#!/usr/bin/env bash
$marker
workspace="\${XDG_CACHE_HOME:-\$HOME/.cache}/orca/shell/workspace"
mkdir -p "\$workspace"
exec scala-cli run --workspace "\$workspace" --jvm 21 --quiet \\
  --dep "org.virtuslab::orca-shell:latest.release" \\
  --main-class orca.shell.Main -- "\$@"
EOF

chmod +x "$bin_path"

echo "orca: installed to $bin_path"

case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *)
    echo "orca: $bin_dir is not on your PATH - add this to your shell profile:"
    echo "    export PATH=\"$bin_dir:\$PATH\""
    ;;
esac
