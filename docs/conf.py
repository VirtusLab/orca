# -*- coding: utf-8 -*-
#
# Orca documentation build configuration file.

# https://about.readthedocs.com/blog/2024/07/addons-by-default/
import os

# Define the canonical URL if you are using a custom domain on Read the Docs
html_baseurl = os.environ.get(
    "READTHEDOCS_CANONICAL_URL",
    "https://orca.virtuslab.com/",
)

# Tell Jinja2 templates the build is running on Read the Docs
if os.environ.get("READTHEDOCS", "") == "True":
    if "html_context" not in globals():
        html_context = {}
    html_context["READTHEDOCS"] = True

# -- General configuration ------------------------------------------------

extensions = ['myst_parser', 'sphinx_rtd_theme', 'sphinxcontrib.mermaid', 'sphinx_llms_txt']

myst_enable_extensions = ['attrs_block', 'colon_fence']
myst_heading_anchors = 3

llms_txt_title = "Orca"
llms_txt_summary = "Deterministic, AI-driven development flows: Scala scripts that orchestrate coding agents (Claude, Codex, OpenCode, Pi, Gemini) through resumable plan-implement-review workflows"
llms_txt_full_file = True

# The suffix(es) of source filenames.
source_suffix = {
    '.rst': 'restructuredtext',
    '.md': 'markdown',
}

# The master toctree document.
master_doc = 'index'

# General information about the project.
project = u'Orca'
copyright = u'2026, VirtusLab'
author = u'VirtusLab'

# The short X.Y version.
version = u'0.1'
# The full version, including alpha/beta/rc tags.
release = u'0.1'

language = 'en'

# List of patterns, relative to source directory, that match files and
# directories to ignore when looking for source files. `plans`, `research` and
# `superpowers` are internal working documents, not part of the published site.
exclude_patterns = [
    '_build', 'Thumbs.db', '.DS_Store',
    '.venv', 'venv', 'env',
    '**/site-packages/**',
    '**/node_modules/**',
    '_templates',
    'requirements.txt',
    'README.md',
    'plans',
    'research',
    'superpowers',
]

pygments_style = 'default'

# Pygments has no lexer for `properties`, which the settings pages use for
# `settings.properties` fences; alias it to the INI lexer.
from pygments.lexers.configs import IniLexer
from sphinx.highlighting import lexers
lexers['properties'] = IniLexer()

# -- Options for HTML output ----------------------------------------------

html_theme = 'sphinx_rtd_theme'

htmlhelp_basename = 'orcadoc'

highlight_language = 'scala'

# configure edit on github: https://docs.readthedocs.io/en/latest/guides/vcs.html
html_context = {
    'display_github': True,
    'github_user': 'VirtusLab',
    'github_repo': 'orca',
    'github_version': 'master',
    'conf_py_path': '/docs/',
}
