package org.emulinker.eval.client

import com.google.common.flogger.FluentLogger
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ConnectedDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.utils.io.core.ByteReadPacket
import io.ktor.utils.io.core.Closeable
import io.ktor.utils.io.core.readByteBuffer
import io.ktor.utils.io.core.use
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.emulinker.kaillera.controller.connectcontroller.protocol.ConnectMessage
import org.emulinker.kaillera.controller.connectcontroller.protocol.RequestPrivateKailleraPortRequest
import org.emulinker.kaillera.controller.connectcontroller.protocol.RequestPrivateKailleraPortResponse
import org.emulinker.kaillera.controller.messaging.ParseException
import org.emulinker.kaillera.controller.v086.LastMessageBuffer
import org.emulinker.kaillera.controller.v086.V086Controller
import org.emulinker.kaillera.controller.v086.protocol.AllReady
import org.emulinker.kaillera.controller.v086.protocol.CachedGameData
import org.emulinker.kaillera.controller.v086.protocol.ClientAck
import org.emulinker.kaillera.controller.v086.protocol.CreateGameNotification
import org.emulinker.kaillera.controller.v086.protocol.CreateGameRequest
import org.emulinker.kaillera.controller.v086.protocol.GameData
import org.emulinker.kaillera.controller.v086.protocol.GameStatus
import org.emulinker.kaillera.controller.v086.protocol.InformationMessage
import org.emulinker.kaillera.controller.v086.protocol.JoinGameNotification
import org.emulinker.kaillera.controller.v086.protocol.JoinGameRequest
import org.emulinker.kaillera.controller.v086.protocol.PlayerDropRequest
import org.emulinker.kaillera.controller.v086.protocol.PlayerInformation
import org.emulinker.kaillera.controller.v086.protocol.QuitGameRequest
import org.emulinker.kaillera.controller.v086.protocol.QuitRequest
import org.emulinker.kaillera.controller.v086.protocol.ServerAck
import org.emulinker.kaillera.controller.v086.protocol.ServerStatus
import org.emulinker.kaillera.controller.v086.protocol.StartGameNotification
import org.emulinker.kaillera.controller.v086.protocol.StartGameRequest
import org.emulinker.kaillera.controller.v086.protocol.UserInformation
import org.emulinker.kaillera.controller.v086.protocol.UserJoined
import org.emulinker.kaillera.controller.v086.protocol.V086Bundle
import org.emulinker.kaillera.controller.v086.protocol.V086Message
import org.emulinker.kaillera.model.ConnectionType
import org.emulinker.util.GameDataCache
import org.emulinker.util.GameDataCacheImpl

/** Fake client for testing. */
class EvalClient(
  private val username: String,
  private val connectControllerAddress: InetSocketAddress,
  private val simulateGameLag: Boolean = false,
  private val connectionType: ConnectionType = ConnectionType.LAN,
  private val frameDelay: Int = 1,
  private val clientType: String = "Project 64k 0.13 (01 Aug 2003)"
) : Closeable {
  private val evalCoroutineScope =
    CoroutineScope(Dispatchers.IO) + CoroutineName("EvalClient[${username}]Scope")

  private val lastMessageBuffer = LastMessageBuffer(V086Controller.MAX_BUNDLE_SIZE)

  private lateinit var socket: ConnectedDatagramSocket

  private var gameDataCache: GameDataCache = GameDataCacheImpl(256)

  private val outMutex = Mutex()

  private var killSwitch = false

  private var lastOutgoingMessageNumber = -1

  private var latestServerStatus: ServerStatus? = null

  private var playerNumber: Int? = null

  private val delayBetweenPackets =
    1.seconds.div(connectionType.getUpdatesPerSecond()).times(frameDelay)

  private var lastIncomingMessageNumber: Int = -1

  /**
   * Registers as a new user with the ConnectController server and joins the dedicated port
   * allocated for the user.
   */
  suspend fun connectToDedicatedPort() {
    val selectorManager = SelectorManager(Dispatchers.IO)
    socket = aSocket(selectorManager).udp().connect(connectControllerAddress)

    val allocatedPort =
      socket.use { connectedSocket ->
        logger.atInfo().log("Started new eval client at %s", connectedSocket.localAddress)

        sendConnectMessage(RequestPrivateKailleraPortRequest(protocol = "0.83"))

        val response =
          ConnectMessage.parse(connectedSocket.receive().packet.readByteBuffer()).getOrThrow()
        logger.atInfo().log("<<<<<<<< Received message: %s", response)
        require(response is RequestPrivateKailleraPortResponse)

        response.port
      }

    socket =
      aSocket(selectorManager)
        .udp()
        .connect(InetSocketAddress(connectControllerAddress.hostname, allocatedPort))
    logger.atInfo().log("Changing connection to: %s", socket.remoteAddress)

    giveServerTime()
  }

  /** Interacts in the server */
  suspend fun start() {
    evalCoroutineScope.launch {
      while (!killSwitch) {
        try {
          val response =
            V086Bundle.parse(
              socket.receive().packet.readByteBuffer(),
              lastMessageID = lastIncomingMessageNumber
            )
          handleIncoming(response)
        } catch (e: ParseException) {

          if (
            e.message?.contains("Failed byte count validation") == true &&
              e.stackTrace.firstOrNull()?.fileName == "PlayerInformation.kt"
          ) {
            // TODO(nue): There's a PlayerInformation parsing failure here and I don't understand..
            // We need to figure out what's going on, but for now log and continue.
            logger.atSevere().withCause(e).log("Failed to parse the PlayerInformation message!")
          } else {
            throw e
          }
        }
      }
      logger.atInfo().log("EvalClient shut down.")
    }

    sendWithMessageId { UserInformation(messageNumber = it, username, clientType, connectionType) }
    giveServerTime()
  }

  private suspend fun handleIncoming(bundle: V086Bundle) {
    val message = bundle.messages.firstOrNull()

    if (message == null) {
      logger.atInfo().log("Received bundle with no messages!")
      return
    }
    if (message.messageNumber != lastIncomingMessageNumber + 1) {
      logger
        .atSevere()
        .log(
          "Received message with unexpected messageNumber.. lastMessageNumber=%d, message=%d",
          lastIncomingMessageNumber,
          message
        )
    }
    lastIncomingMessageNumber = message.messageNumber

    logger.atInfo().log("<<<<<<<< Received message: %s", message)

    when (message) {
      is ServerAck -> {
        sendWithMessageId { ClientAck(messageNumber = it) }
      }
      is ServerStatus -> {
        latestServerStatus = message
      }
      is InformationMessage -> {}
      is UserJoined -> {}
      is CreateGameNotification -> {}
      is GameStatus -> {}
      is PlayerInformation -> {}
      is JoinGameNotification -> {}
      is AllReady -> {
        if (simulateGameLag) {
          delay(delayBetweenPackets)
        }
        sendWithMessageId {
          GameData(
            messageNumber = it,
            gameData =
              when (playerNumber) {
                1 -> {
                  byteArrayOf(
                    16,
                    36,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    0
                  )
                }
                2 -> {
                  byteArrayOf(
                    17,
                    32,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                  )
                }
                else -> {
                  logger.atSevere().log("Unexpected message type: %s", message)
                  throw IllegalStateException()
                }
              }
          )
        }
      }
      is GameData -> {
        val index = gameDataCache.indexOf(message.gameData)
        if (index < 0) {
          gameDataCache.add(message.gameData)
        }
        if (simulateGameLag) {
          delay(delayBetweenPackets)
        }
        sendWithMessageId {
          GameData(
            messageNumber = it,
            gameData =
              when (playerNumber) {
                1 -> {
                  byteArrayOf(
                    16,
                    36,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    0
                  )
                }
                2 -> {
                  byteArrayOf(
                    17,
                    32,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                  )
                }
                else -> {
                  logger.atSevere().log("Unexpected message type: %s", message)
                  throw IllegalStateException()
                }
              }
          )
        }
      }
      is CachedGameData -> {
        requireNotNull(gameDataCache[message.key])
        if (simulateGameLag) {
          delay(delayBetweenPackets)
        }
        sendWithMessageId {
          GameData(
            messageNumber = it,
            gameData =
              when (playerNumber) {
                1 -> {
                  byteArrayOf(
                    16,
                    36,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    0
                  )
                }
                2 -> {
                  byteArrayOf(
                    17,
                    32,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                  )
                }
                else -> {
                  logger.atSevere().log("Unexpected message type: %s", message)
                  throw IllegalStateException()
                }
              }
          )
        }
      }
      is StartGameNotification -> {
        playerNumber = message.playerNumber.toInt()
        delay(1.seconds)
        sendWithMessageId { AllReady(messageNumber = it) }
      }
      else -> {
        logger.atSevere().log("Unexpected message type: %s", message)
      }
    }
  }

  suspend fun createGame() {
    sendWithMessageId {
      CreateGameRequest(
        messageNumber = it,
        romName = "Nintendo All-Star! Dairantou Smash Brothers (J)"
      )
    }
    giveServerTime()
  }

  suspend fun startOwnGame() {
    sendWithMessageId { StartGameRequest(messageNumber = it) }
    giveServerTime()
  }

  suspend fun joinAnyAvailableGame() {
    // TODO(nue): Make it listen to individual game creation updates too.
    val games = requireNotNull(latestServerStatus?.games)
    sendWithMessageId {
      JoinGameRequest(messageNumber = it, gameId = games.first().gameId, connectionType)
    }
    giveServerTime()
  }

  override fun close() {
    logger.atInfo().log("Shutting down EvalClient.")
    killSwitch = true
    if (!socket.isClosed) {
      socket.close()
    }
    evalCoroutineScope.cancel()
  }

  private suspend fun sendConnectMessage(message: ConnectMessage) {
    socket.send(Datagram(ByteReadPacket(message.toBuffer()), socket.remoteAddress))
  }

  private suspend fun sendWithMessageId(messageIdToMessage: (messageNumber: Int) -> V086Message) {
    outMutex.withLock {
      lastOutgoingMessageNumber++
      val messageAsArray: Array<V086Message?> =
        arrayOf(messageIdToMessage(lastOutgoingMessageNumber))

      val outBuffer = ByteBuffer.allocateDirect(4096)
      lastMessageBuffer.fill(messageAsArray, messageAsArray.size)
      val outBundle = V086Bundle(messageAsArray, messageAsArray.size)
      outBundle.writeTo(outBuffer)
      outBuffer.flip()
      logger.atInfo().log(">>>>>>>> SENT message: %s", outBundle.messages.first())
      socket.send(Datagram(ByteReadPacket(outBuffer), socket.remoteAddress))
    }
  }

  suspend fun dropGame() {
    sendWithMessageId { PlayerDropRequest(messageNumber = it) }
    giveServerTime()
  }

  suspend fun quitGame() {
    sendWithMessageId { QuitGameRequest(messageNumber = it) }
    giveServerTime()
  }

  suspend fun quitServer() {
    sendWithMessageId { QuitRequest(messageNumber = it, message = "End of test.") }
    giveServerTime()
  }

  private suspend fun giveServerTime() {
    delay(1.seconds)
  }

  companion object {
    private val logger = FluentLogger.forEnclosingClass()
  }
}
