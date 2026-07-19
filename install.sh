#!/bin/sh
# Installs the `orca` shell shim (ADR 0021 §1) into ~/.local/bin.
#
#   curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash
#
# The shim is a ~5-line script that hands off to `scala-cli run` against the
# `latest.release` of `orca-shell` — nothing is downloaded or compiled here,
# so re-running this script never needs a version bump.
set -eu

marker="# orca-shell-shim (ADR 0021 §1) - safe to overwrite"
bin_dir="${HOME}/.local/bin"
bin_path="${bin_dir}/orca"

if [ -e "$bin_path" ] && ! grep -qF "$marker" "$bin_path" 2>/dev/null; then
  echo "orca: $bin_path already exists and isn't an orca shim - refusing to overwrite" >&2
  echo "orca: remove it (or move it aside) and re-run this script" >&2
  exit 1
fi

mkdir -p "$bin_dir"

cat > "$bin_path" <<EOF
#!/usr/bin/env bash
$marker
exec scala-cli run --jvm 21 --quiet \\
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
