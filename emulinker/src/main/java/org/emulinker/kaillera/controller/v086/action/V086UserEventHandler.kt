package org.emulinker.kaillera.controller.v086.action

import org.emulinker.kaillera.controller.v086.V086ClientHandler
import org.emulinker.kaillera.model.event.UserEvent

interface V086UserEventHandler<T : UserEvent?> {
  override fun toString(): String
  fun handleEvent(event: T, clientHandler: V086ClientHandler)
  val handledEventCount: Int
}
