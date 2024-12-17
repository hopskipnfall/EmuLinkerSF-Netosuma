package org.emulinker.kaillera.pico

import com.codahale.metrics.ConsoleReporter
import com.codahale.metrics.CsvReporter
import com.codahale.metrics.MetricFilter
import com.codahale.metrics.jvm.ThreadStatesGaugeSet
import com.google.common.flogger.FluentLogger
import java.io.File
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.TimeUnit.SECONDS
import org.emulinker.kaillera.controller.v086.action.ActionModule
import org.emulinker.util.EmuUtil.toSimpleUtcDatetime
import org.emulinker.util.stripFromProdBinary
import org.koin.core.context.startKoin

private val logger = FluentLogger.forEnclosingClass()

const val MINIMUM_JAVA_VERSION = 11

/** Main entry point for the Kaillera server. */
fun main() {
  val javaVersion: Double? = System.getProperty("java.specification.version").toDoubleOrNull()
  when {
    javaVersion == null -> {
      logger.atSevere().log("Unable to detect installed Java version!")
    }
    javaVersion < MINIMUM_JAVA_VERSION -> {
      throw IllegalStateException(
        "Installed Java version $javaVersion is below the required minimum $MINIMUM_JAVA_VERSION. Please install a recent version of Java to run this server."
      )
    }
    else -> {
      logger.atInfo().log("Detected installed Java version: %f", javaVersion)
    }
  }

  startKoin { modules(koinModule, ActionModule) }
  val component = NewAppComponent()
  // Use log4j as the flogger backend.
  System.setProperty(
    "flogger.backend_factory",
    "com.google.common.flogger.backend.log4j2.Log4j2BackendFactory#getInstance"
  )

  logger.atInfo().log("EmuLinker server Starting...")
  logger.atInfo().log(component.releaseInfo.welcome)
  stripFromProdBinary {
    logger.atWarning().log("DEBUG BUILD -- This should not be used for production servers!")
  }
  logger
    .atInfo()
    .log("EmuLinker server is running @ %s", component.clock.now().toSimpleUtcDatetime())
  // Essentially does nothing.
  component.kailleraServerController.start()

  // Keeps iterating over users to look for people who should be kicked.
  component.kailleraServer.start()

  // Reports data to various servers.
  component.masterListUpdater.run()

  val metrics = component.metricRegistry
  metrics.registerAll(ThreadStatesGaugeSet())
  //  metrics.registerAll(MemoryUsageGaugeSet())
  val flags = component.flags
  if (flags.metricsEnabled) {
    ConsoleReporter.forRegistry(metrics)
      .convertRatesTo(SECONDS)
      .convertDurationsTo(MILLISECONDS)
      .filter(MetricFilter.ALL)
      .build()
      .start(1, MINUTES)

    val file = File("./metrics/")
    file.mkdirs()
    CsvReporter.forRegistry(metrics)
      .convertRatesTo(SECONDS)
      .convertDurationsTo(MILLISECONDS)
      .filter(MetricFilter.ALL)
      .build(file)
      .start(flags.metricsLoggingFrequency.inWholeSeconds, SECONDS)
  }

  component.combinedKaillerController.bind(component.flags.serverPort)
  //  // Hacky code but it works! Tests that two users can make and play a game.
  //  // TODO(nue): Move this into a test file in a subsequent PR.
  //  runBlocking {
  //    delay(4.seconds)
  //
  //    arrayOf(
  //        async {
  //          EvalClient("testuser1", io.ktor.network.sockets.InetSocketAddress("127.0.0.1", 27888))
  //              .use {
  //                delay(5.seconds)
  //
  //                it.connectToDedicatedPort()
  //                it.start()
  //
  //                delay(1.seconds)
  //
  //                it.createGame()
  //
  //                delay(5.seconds)
  //
  //                it.startOwnGame()
  //
  //                delay(30.seconds)
  //                it.dropGame()
  //                delay(1.seconds)
  //                it.quitGame()
  //                delay(1.seconds)
  //                it.quitServer()
  //
  //                delay(15.seconds)
  //              }
  //        },
  //        async {
  //          EvalClient("testuser2", io.ktor.network.sockets.InetSocketAddress("127.0.0.1", 27888))
  //              .use {
  //                delay(9.seconds)
  //
  //                it.connectToDedicatedPort()
  //                it.start()
  //
  //                delay(1.seconds)
  //                it.joinAnyAvailableGame()
  //
  //                delay(40.seconds)
  //                it.quitServer()
  //              }
  //        })
  //        .forEach { it.join() }
  //
  //    logger.atInfo().log("Shutting down everything else")
  //
  //    component.accessManager.stop()
  //    component.kailleraServerController.stop()
  //    component.kailleraServer.stop()
  //    component.masterListUpdater.stop()
  //  }
}
