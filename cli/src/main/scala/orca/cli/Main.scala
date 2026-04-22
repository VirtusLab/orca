package orca.cli

import ox.{Ox, OxApp}

object Main extends OxApp.Simple:
  def run(using Ox): Unit =
    println("orca")
