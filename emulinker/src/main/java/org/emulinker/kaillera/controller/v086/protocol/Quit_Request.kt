package org.emulinker.kaillera.controller.v086.protocol

import org.emulinker.kaillera.controller.messaging.MessageFormatException

data class Quit_Request
    @Throws(MessageFormatException::class)
    constructor(override val messageNumber: Int, override val message: String?) : Quit() {

  override val username = ""
  override val userId = 0xFFFF

  override val description = DESC
  override val messageId = ID

  init {
    validateMessageNumber(messageNumber, DESC)
  }

  companion object {
    private const val DESC = "User Quit Request"
  }
}
