package org.emulinker.kaillera.model

import com.google.common.flogger.FluentLogger
import java.net.InetSocketAddress
import java.util.ArrayList
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.Throws
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.emulinker.config.RuntimeFlags
import org.emulinker.kaillera.access.AccessManager
import org.emulinker.kaillera.model.event.GameDataEvent
import org.emulinker.kaillera.model.event.GameStartedEvent
import org.emulinker.kaillera.model.event.KailleraEvent
import org.emulinker.kaillera.model.event.KailleraEventListener
import org.emulinker.kaillera.model.event.StopFlagEvent
import org.emulinker.kaillera.model.event.UserQuitEvent
import org.emulinker.kaillera.model.event.UserQuitGameEvent
import org.emulinker.kaillera.model.exception.*
import org.emulinker.kaillera.model.impl.KailleraGameImpl
import org.emulinker.util.EmuLang

/**
 * Represents a user in the server.
 *
 * A thread is dedicated to each user and we can probably clean this up.
 */
class KailleraUser(
  val id: Int,
  val protocol: String,
  val connectSocketAddress: InetSocketAddress,
  private val listener: KailleraEventListener,
  val server: KailleraServer,
  private val flags: RuntimeFlags,
  threadpoolExecutor: ThreadPoolExecutor,
  private val clock: Clock,
) {
  var inStealthMode = false

  /** Example: "Project 64k 0.13 (01 Aug 2003)" */
  var clientType: String? = null
    set(clientType) {
      field = clientType
      if (clientType != null && clientType.startsWith(EMULINKERSF_ADMIN_CLIENT_NAME))
        isEsfAdminClient = true
    }

  private val initTime = clock.now()
  val connectTime: Instant = initTime

  var connectionType: ConnectionType =
    ConnectionType.DISABLED // TODO(nue): This probably shouldn't have a default.
  var ping = 0
  var socketAddress: InetSocketAddress? = null
  var status = UserStatus.PLAYING // TODO(nue): This probably shouldn't have a default value..

  /**
   * Level of access that the user has.
   *
   * See [AccessManager] for available values. This should be turned into an enum.
   */
  var accessLevel = 0

  var isEsfAdminClient = false
    private set

  /** This marks the last time the user interacted in the server. */
  private var lastActivity: Instant = initTime

  private var smallLagSpikesCausedByUser = 0L
  private var bigLagSpikesCausedByUser = 0L

  /** The last time we heard from this player for lag detection purposes. */
  private var lastUpdateNs = System.nanoTime()
  private var smallLagThresholdNs = 0.seconds.inWholeNanoseconds
  private var bigSpikeThresholdNs = 0.seconds.inWholeNanoseconds

  fun updateLastActivity() {
    lastKeepAlive = clock.now()
    lastActivity = lastKeepAlive
  }

  /**
   * We haven't heard anything from the user in a long time and it's likely their client is no
   * longer connected.
   */
  val isDead: Boolean
    get() =
      clock.now() - lastKeepAlive > flags.keepAliveTimeout &&
        System.nanoTime() - lastUpdateNs > flags.keepAliveTimeout.inWholeNanoseconds

  /**
   * The user may have a successful connection to the server, but they are seemingly AFK for longer
   * than the policy allows.
   */
  val isIdleForTooLong: Boolean
    get() =
      clock.now().let { now ->
        now - lastActivity > flags.idleTimeout &&
          now - Instant.fromEpochMilliseconds(lastUpdateNs.nanoseconds.inWholeMilliseconds) >
            flags.idleTimeout
      }

  private var lastKeepAlive: Instant = initTime
  var lastChatTime: Instant = initTime
    private set

  var lastCreateGameTime: Long = 0
    private set

  var frameCount = 0
  var frameDelay = 0

  private var totalDelay = 0
  var bytesPerAction = 0
    private set

  /** User action data response message size (in number of bytes). */
  var arraySize = 0
    private set
  /**
   * This is called "p2p mode" in the code and commands.
   *
   * See the command /p2pon.
   */
  var ignoringUnnecessaryServerActivity = false

  var playerNumber = -1
  var ignoreAll = false
  var isAcceptingDirectMessages = true
  var lastMsgID = -1
  var isMuted = false

  private val lostInput: MutableList<ByteArray> = ArrayList()
  /** Note that this is a different type from lostInput. */
  fun getLostInput(): ByteArray {
    return lostInput[0]
  }

  private val ignoredUsers: MutableList<String> = ArrayList()
  private var gameDataErrorTime: Long = -1

  private var threadIsActive = false

  private var stopFlag = false
  private val eventQueue: BlockingQueue<KailleraEvent> = LinkedBlockingQueue()

  var tempDelay = 0

  val users: Collection<KailleraUser>
    get() = server.users

  fun addIgnoredUser(address: String) {
    ignoredUsers.add(address)
  }

  fun findIgnoredUser(address: String): Boolean {
    return ignoredUsers.any { it == address }
  }

  fun removeIgnoredUser(address: String, removeAll: Boolean): Boolean {
    var here = false
    if (removeAll) {
      ignoredUsers.clear()
      return true
    }
    var i = 0
    while (i < ignoredUsers.size) {
      if (ignoredUsers[i] == address) {
        ignoredUsers.removeAt(i)
        here = true
      }
      i++
    }
    return here
  }

  fun searchIgnoredUsers(address: String): Boolean {
    return ignoredUsers.any { it == address }
  }

  var loggedIn = false

  override fun toString(): String {
    val n = name
    return if (n == null) {
      "User$id(${connectSocketAddress.address.hostAddress})"
    } else {
      "User$id(${if (n.length > 15) n.take(15) + "..." else n}/${connectSocketAddress.address.hostAddress})"
    }
  }

  var name: String? = null

  fun updateLastKeepAlive() {
    lastKeepAlive = clock.now()
  }

  var game: KailleraGameImpl? = null
    set(value) {
      if (value == null) {
        playerNumber = -1
      }
      field = value
    }

  val accessStr: String
    get() = AccessManager.ACCESS_NAMES[accessLevel]

  override fun equals(other: Any?): Boolean {
    return other is KailleraUser && other.id == id
  }

  fun stop() {
    synchronized(this) {
      if (!threadIsActive) {
        logger.atFine().log("%s  thread stop request ignored: not running!", this)
        return
      }
      if (stopFlag) {
        logger.atFine().log("%s  thread stop request ignored: already stopping!", this)
        return
      }
      stopFlag = true
      queueEvent(StopFlagEvent())
    }
    listener.stop()
  }

  fun droppedPacket() = withLock {
    if (game != null) {
      // if(game.getStatus() == KailleraGame.STATUS_PLAYING){
      game!!.droppedPacket(this)
      // }
    }
  }

  // server actions
  fun login(): Result<Unit> = withLock {
    updateLastActivity()
    return server.login(this)
  }

  @Synchronized
  @Throws(ChatException::class, FloodException::class)
  fun chat(message: String?) {
    updateLastActivity()
    server.chat(this, message)
    lastChatTime = clock.now()
  }

  @Throws(GameKickException::class)
  fun gameKick(userID: Int) = withLock {
    updateLastActivity()
    if (game == null) {
      logger.atWarning().log("%s kick User %d failed: Not in a game", this, userID)
      throw GameKickException(EmuLang.getString("KailleraUserImpl.KickErrorNotInGame"))
    }
    game!!.kick(this, userID)
  }

  @Throws(CreateGameException::class, FloodException::class)
  fun createGame(romName: String?): KailleraGame? {
    updateLastActivity()
    if (server.getUser(id) == null) {
      logger.atSevere().log("%s create game failed: User don't exist!", this)
      return null
    }
    if (status == UserStatus.PLAYING) {
      logger.atWarning().log("%s create game failed: User status is Playing!", this)
      throw CreateGameException(EmuLang.getString("KailleraUserImpl.CreateGameErrorAlreadyInGame"))
    } else if (status == UserStatus.CONNECTING) {
      logger.atWarning().log("%s create game failed: User status is Connecting!", this)
      throw CreateGameException(
        EmuLang.getString("KailleraUserImpl.CreateGameErrorNotFullyConnected")
      )
    }
    val game = server.createGame(this, romName)
    lastCreateGameTime = System.currentTimeMillis()
    return game
  }

  @Throws(
    QuitException::class,
    DropGameException::class,
    QuitGameException::class,
    CloseGameException::class
  )
  fun quit(message: String?) = withLock {
    updateLastActivity()
    server.quit(this, message)
    loggedIn = false
  }

  fun summarizeLag(): String =
    "$smallLagSpikesCausedByUser (small), $bigLagSpikesCausedByUser (big)"

  fun resetLag() {
    smallLagSpikesCausedByUser = 0
    bigLagSpikesCausedByUser = 0
  }

  @Throws(JoinGameException::class)
  fun joinGame(gameID: Int): KailleraGame = withLock {
    updateLastActivity()
    if (game != null) {
      logger.atWarning().log("%s join game failed: Already in: %s", this, game)
      throw JoinGameException(EmuLang.getString("KailleraUserImpl.JoinGameErrorAlreadyInGame"))
    }
    if (status == UserStatus.PLAYING) {
      logger.atWarning().log("%s join game failed: User status is Playing!", this)
      throw JoinGameException(EmuLang.getString("KailleraUserImpl.JoinGameErrorAnotherGameRunning"))
    } else if (status == UserStatus.CONNECTING) {
      logger.atWarning().log("%s join game failed: User status is Connecting!", this)
      throw JoinGameException(EmuLang.getString("KailleraUserImpl.JoinGameErrorNotFullConnected"))
    }
    val game = server.getGame(gameID)
    if (game == null) {
      logger.atWarning().log("%s join game failed: Game %d does not exist!", this, gameID)
      throw JoinGameException(EmuLang.getString("KailleraUserImpl.JoinGameErrorDoesNotExist"))
    }

    // if (connectionType != game.getOwner().getConnectionType())
    // {
    //	logger.atWarning().log(this + " join game denied: " + this + ": You must use the same
    // connection type as
    // the owner: " + game.getOwner().getConnectionType());
    //	throw new
    // JoinGameException(EmuLang.getString("KailleraGameImpl.StartGameConnectionTypeMismatchInfo"));
    //
    // }
    playerNumber = game.join(this)
    this.game = game as KailleraGameImpl?
    gameDataErrorTime = -1
    return game
  }

  // game actions
  @Synchronized
  @Throws(GameChatException::class)
  fun gameChat(message: String, messageID: Int) {
    updateLastActivity()
    if (game == null) {
      logger.atWarning().log("%s game chat failed: Not in a game", this)
      throw GameChatException(EmuLang.getString("KailleraUserImpl.GameChatErrorNotInGame"))
    }
    if (isMuted) {
      logger.atWarning().log("%s gamechat denied: Muted: %s", this, message)
      game!!.announce("You are currently muted!", this)
      return
    }
    if (server.accessManager.isSilenced(socketAddress!!.address)) {
      logger.atWarning().log("%s gamechat denied: Silenced: %s", this, message)
      game!!.announce("You are currently silenced!", this)
      return
    }

    /*if(this == null){
    	throw new GameChatException("You don't exist!");
    }*/ game!!.chat(this, message)
  }

  @Throws(DropGameException::class)
  fun dropGame() = withLock {
    updateLastActivity()
    if (status == UserStatus.IDLE) {
      return
    }
    status = UserStatus.IDLE
    if (game != null) {
      game!!.drop(this, playerNumber)
      // not necessary to show it twice
      /*if(p2P == true)
      	game.announce("Please Relogin, to update your client of missed server activity during P2P!", this);
      p2P = false;*/
    } else logger.atFine().log("%s drop game failed: Not in a game", this)
  }

  @Throws(DropGameException::class, QuitGameException::class, CloseGameException::class)
  fun quitGame() = withLock {
    updateLastActivity()
    if (game == null) {
      logger.atFine().log("%s quit game failed: Not in a game", this)
      // throw new QuitGameException("You are not in a game!");
      return
    }
    if (status == UserStatus.PLAYING) {
      // first set STATUS_IDLE and then call game.drop, otherwise if someone
      // quit game whitout drop - game status will not change to STATUS_WAITING
      status = UserStatus.IDLE
      game!!.drop(this, playerNumber)
    }
    game?.quit(this, playerNumber)
    if (status != UserStatus.IDLE) {
      status = UserStatus.IDLE
    }
    isMuted = false
    game = null
    queueEvent(UserQuitGameEvent(game, this))
  }

  @Synchronized
  @Throws(StartGameException::class)
  fun startGame() {
    updateLastActivity()
    if (game == null) {
      logger.atWarning().log("%s start game failed: Not in a game", this)
      throw StartGameException(EmuLang.getString("KailleraUserImpl.StartGameErrorNotInGame"))
    }
    game!!.start(this)
  }

  @Throws(UserReadyException::class)
  fun playerReady() = withLock {
    updateLastActivity()
    if (game == null) {
      logger.atWarning().log("%s player ready failed: Not in a game", this)
      throw UserReadyException(EmuLang.getString("KailleraUserImpl.PlayerReadyErrorNotInGame"))
    }
    if (
      playerNumber > game!!.playerActionQueue!!.size ||
        game!!.playerActionQueue!![playerNumber - 1].synced
    ) {
      return
    }
    totalDelay = game!!.highestUserFrameDelay + tempDelay + 5

    val singleFrameDuration = 1.seconds / connectionType.updatesPerSecond
    smallLagThresholdNs = (singleFrameDuration * 1.65).inWholeNanoseconds
    bigSpikeThresholdNs = (singleFrameDuration * 2).inWholeNanoseconds

    game!!.ready(this, playerNumber)
  }

  fun addGameData(data: ByteArray): Result<Unit> {
    val timeWaitingNs = measureNanoTime {
      fun doTheThing(): Result<Unit> {
        if (game == null) {
          return Result.failure(
            GameDataException(
              EmuLang.getString("KailleraUserImpl.GameDataErrorNotInGame"),
              data,
              actionsPerMessage = connectionType.byteValue.toInt(),
              playerNumber = 1,
              numPlayers = 1
            )
          )
        }

        // Initial Delay
        // totalDelay = (game.getDelay() + tempDelay + 5)
        if (frameCount < totalDelay) {
          bytesPerAction = data.size / connectionType.byteValue
          arraySize = game!!.playerActionQueue!!.size * connectionType.byteValue * bytesPerAction
          val response = ByteArray(arraySize)
          for (i in response.indices) {
            response[i] = 0
          }
          lostInput.add(data)
          queueEvent(GameDataEvent(game as KailleraGameImpl, response))
          frameCount++
        } else {
          // lostInput.add(data);
          if (lostInput.size > 0) {
            game!!.addData(this, playerNumber, lostInput[0]).onFailure {
              return Result.failure(it)
            }
            lostInput.removeAt(0)
          } else {
            game!!.addData(this, playerNumber, data).onFailure {
              return Result.failure(it)
            }
          }
        }
        gameDataErrorTime = 0
        return Result.success(Unit)
      }

      val result = doTheThing()
      result.onFailure { e ->
        if (e is GameDataException) {
          // this should be warn level, but it creates tons of lines in the log
          logger.atFine().withCause(e).log("%s add game data failed", this)

          // i'm going to reflect the game data packet back at the user to prevent game lockups,
          // but this uses extra bandwidth, so we'll set a counter to prevent people from leaving
          // games running for a long time in this state
          if (gameDataErrorTime > 0) {
            // give the user time to close the game
            if (System.currentTimeMillis() - gameDataErrorTime > 30000) {
              // this should be warn level, but it creates tons of lines in the log
              logger.atFine().log("%s: error game data exceeds drop timeout!", this)
              return Result.failure(GameDataException(e.message))
            } else {
              // e.setReflectData(true);
              return result
            }
          } else {
            gameDataErrorTime = System.currentTimeMillis()
            return result
          }
        }
      }
    }

    val delaySinceLastResponseNs = System.nanoTime() - lastUpdateNs - timeWaitingNs
    when {
      delaySinceLastResponseNs < smallLagThresholdNs -> {
        // No lag occurred.
      }
      delaySinceLastResponseNs < bigSpikeThresholdNs -> smallLagSpikesCausedByUser++
      else -> bigLagSpikesCausedByUser++
    }

    lastUpdateNs = System.nanoTime()
    return Result.success(Unit)
  }

  fun queueEvent(event: KailleraEvent) {
    if (status != UserStatus.IDLE) {
      if (ignoringUnnecessaryServerActivity) {
        if (event.toString() == "InfoMessageEvent") return
      }
    }
    eventQueue.offer(event)
  }

  init {
    threadpoolExecutor.submit {
      threadIsActive = true
      logger.atFine().log("%s thread running...", this)
      try {
        while (!stopFlag) {
          val event = eventQueue.poll(200, TimeUnit.SECONDS)
          if (event == null) {
            continue
          } else if (event is StopFlagEvent) {
            break
          }
          listener.actionPerformed(event)
          if (event is GameStartedEvent) {
            status = UserStatus.PLAYING
            lastUpdateNs = System.nanoTime()
          } else if (event is UserQuitEvent && event.user == this) {
            stop()
          }
        }
      } catch (e: InterruptedException) {
        logger.atSevere().withCause(e).log("%s thread interrupted!", this)
      } catch (e: Throwable) {
        logger.atSevere().withCause(e).log("%s thread caught unexpected exception!", this)
      } finally {
        threadIsActive = false
        logger.atFine().log("%s thread exiting...", this)
      }
    }
  }

  private val o = Object()
  /** Helper function to avoid one level of indentation. */
  private inline fun <T> withLock(action: () -> T): T = synchronized(o) { action() }

  companion object {
    private val logger = FluentLogger.forEnclosingClass()

    private const val EMULINKERSF_ADMIN_CLIENT_NAME = "EmulinkerSF Admin Client"
  }
}
