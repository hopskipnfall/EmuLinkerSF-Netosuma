package org.emulinker.kaillera.model

import org.emulinker.kaillera.model.impl.KailleraGameImpl

enum class UserStatus(val byteValue: Byte, private val readableName: String) {
  PLAYING(0, "Playing"),
  IDLE(1, "Idle"),
  CONNECTING(2, "Connecting");

  override fun toString() = readableName

  companion object {
    fun fromByteValue(byteValue: Byte): UserStatus {
      return entries.find { it.byteValue == byteValue }
        ?: throw IllegalArgumentException("Invalid byte value: $byteValue")
    }
  }
}

enum class GameStatus(val byteValue: Byte, private val readableName: String) {
  WAITING(0, "Waiting"),
  SYNCHRONIZING(1, "Synchronizing"),
  PLAYING(2, "Playing");

  override fun toString() = readableName

  companion object {
    fun fromByteValue(byteValue: Byte): GameStatus {
      return entries.find { it.byteValue == byteValue }
        ?: throw IllegalArgumentException("Invalid byte value: $byteValue")
    }
  }
}

enum class ConnectionType(
  /**
   * ID for the connection type used over the wire, but has a more concrete meaning in that it
   * determines the number of updates per second while inside a game: 60/byteValue qps.
   *
   * Note: Technically not all games run at 60FPS, but Kaillera assumes it is for most purposes.
   */
  val byteValue: Byte,
  val readableName: String
) {
  DISABLED(0, "DISABLED"),
  LAN(1, "LAN"),
  EXCELLENT(2, "Excellent"),
  GOOD(3, "Good"),
  AVERAGE(4, "Average"),
  LOW(5, "Low"),
  BAD(6, "Bad");

  override fun toString() = readableName

  fun getUpdatesPerSecond(gameFps: Int = KailleraGameImpl.GAME_FPS): Double =
    getUpdatesPerSecond(gameFps.toDouble())

  fun getUpdatesPerSecond(gameFps: Double): Double =
    if (byteValue == 0.toByte()) 0.0 else gameFps.toDouble() / byteValue

  companion object {
    fun fromByteValue(byteValue: Byte): ConnectionType =
      entries.find { it.byteValue == byteValue }
        ?: throw IllegalArgumentException("Invalid byte value: $byteValue")
  }
}
