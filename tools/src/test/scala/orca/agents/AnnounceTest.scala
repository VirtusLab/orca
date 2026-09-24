package orca.agents

import orca.events.Announcement

class AnnounceTest extends munit.FunSuite:

  test("an instance answering an empty message is silent"):
    val announce = Announce.fromOption[Int](_ => Some(""))
    assertEquals(Announce.announcement(announce, 1), Announcement.Silent)

  test("an instance answering no message is silent"):
    val announce = Announce.fromOption[Int](_ => None)
    assertEquals(Announce.announcement(announce, 1), Announcement.Silent)

  test("a type with no instance of its own is unannounced"):
    assertEquals(
      Announce.announcement(summon[Announce[Int]], 1),
      Announcement.Unannounced
    )
