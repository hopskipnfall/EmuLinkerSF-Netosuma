package org.emulinker.kaillera.model.event

import org.emulinker.kaillera.model.KailleraGame

class GameStartedEvent(override val game: KailleraGame) : GameEvent {
  override fun toString(): String {
    return "GameStartedEvent"
  }
}
