package org.emulinker.util

import com.codahale.metrics.Timer
import io.ktor.utils.io.core.ByteReadPacket
import io.netty.buffer.ByteBuf
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit.MINUTES
import kotlin.time.DurationUnit.SECONDS
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.format
import kotlinx.datetime.offsetIn
import org.emulinker.kaillera.pico.AppModule

object EmuUtil {
  private val HEX_CHARS =
    charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
  val LB: String = System.getProperty("line.separator")
  var DATE_FORMAT: DateFormat = SimpleDateFormat("MM/dd/yyyy HH:mm:ss")

  // TODO(nue): This looks like a hack. Maybe clean it up.
  fun systemIsWindows(): Boolean {
    return File.separatorChar == '\\'
  }

  fun loadProperties(filename: String): Properties? {
    return try {
      val file = File(filename)
      loadProperties(file)
    } catch (e: Exception) {
      // log some kind of error here
      null
    }
  }

  private fun loadProperties(file: File): Properties? {
    var p: Properties? = null
    try {
      val `in` = FileInputStream(file)
      p = Properties()
      p.load(`in`)
      `in`.close()
    } catch (e: Throwable) {
      // log the error
    }
    return p
  }

  @JvmOverloads
  fun formatBytes(data: ByteArray?, allHex: Boolean = false): String {
    if (data == null) return "null"
    if (data.isEmpty()) return ""
    if (allHex) return bytesToHex(data, ',')
    val len = data.size
    val sb = StringBuilder()
    for (i in 0 until len) {
      if (Character.isLetterOrDigit(data[i].toInt().toChar()) || data[i] in 32..126)
        sb.append(data[i].toInt().toChar())
      else sb.append(byteToHex(data[i]))
      if (i < len - 1) sb.append(',')
    }
    return sb.toString()
  }

  private fun bytesToHex(data: ByteArray, sep: Char): String {
    val len = data.size
    val sb = StringBuilder(len * 3)
    for (i in 0 until len) {
      sb.append(byteToHex(data[i]))
      if (i < len - 1) sb.append(sep)
    }
    return sb.toString()
  }

  fun bytesToHex(data: ByteArray): String {
    val len = data.size
    val sb = StringBuilder(len * 3)
    for (i in 0 until len) {
      sb.append(byteToHex(data[i]))
    }
    return sb.toString()
  }

  fun bytesToHex(data: ByteArray, pos: Int, len: Int): String {
    val sb = StringBuilder(len * 2)
    for (i in pos until pos + len) {
      sb.append(byteToHex(data[i]))
    }
    return sb.toString()
  }

  fun ByteArray.toHexString(): String =
    this.joinToString("") { it.toHexString() }.chunked(size = 2).joinToString(separator = ",")

  fun Byte.toHexString(): String = this.toUByte().toString(16).padStart(2, '0').uppercase()

  @Deprecated(
    message = "This doesn't work very well",
    replaceWith =
      ReplaceWith("b.toHexString()", imports = arrayOf("org.emulinker.util.Byte.toHexString")),
    level = DeprecationLevel.WARNING
  )
  fun byteToHex(b: Byte): String {
    return (HEX_CHARS[b.toInt() and 0xf0 shr 4].toString() +
      HEX_CHARS[b.toInt() and 0xf].toString())
  }

  @Throws(NumberFormatException::class)
  fun hexToByteArray(hex: String): ByteArray {
    if (hex.length % 2 != 0)
      throw NumberFormatException(
        "The string has the wrong length, not pairs of hex representations."
      )
    val len = hex.length / 2
    val ba = ByteArray(len)
    var pos = 0
    for (i in 0 until len) {
      ba[i] = hexToByte(hex.substring(pos, pos + 2).toCharArray())
      pos += 2
    }
    return ba
  }

  @Throws(NumberFormatException::class)
  fun hexToByte(hex: CharArray): Byte {
    if (hex.size != 2) throw NumberFormatException("Invalid number of digits in " + String(hex))
    var i = 0
    var nibble: Byte =
      if (hex[i] in '0'..'9') {
        (hex[i] - '0' shl 4).toByte()
      } else if (hex[i] in 'A'..'F') {
        ((hex[i] - ('A'.code - 0x0A)).code shl 4).toByte()
      } else if (hex[i] in 'a'..'f') {
        ((hex[i] - ('a'.code - 0x0A)).code shl 4).toByte()
      } else {
        throw NumberFormatException(hex[i].toString() + " is not a hexadecimal string.")
      }
    i++
    nibble =
      if (hex[i] in '0'..'9') {
        (nibble.toInt() or hex[i] - '0').toByte()
      } else if (hex[i] in 'A'..'F') {
        (nibble.toInt() or (hex[i] - ('A'.code - 0x0A)).code).toByte()
      } else if (hex[i] in 'a'..'f') {
        (nibble.toInt() or (hex[i] - ('a'.code - 0x0A)).code).toByte()
      } else {
        throw NumberFormatException(hex[i].toString() + " is not a hexadecimal string.")
      }
    return nibble
  }

  fun arrayToString(array: IntArray, sep: Char): String {
    val sb = StringBuilder()
    for (i in array.indices) {
      sb.append(array[i])
      if (i < array.size - 1) sb.append(sep)
    }
    return sb.toString()
  }

  fun arrayToString(array: ByteArray, sep: Char): String {
    val sb = StringBuilder()
    for (i in array.indices) {
      sb.append(array[i])
      if (i < array.size - 1) sb.append(sep)
    }
    return sb.toString()
  }

  fun formatSocketAddress(sa: SocketAddress): String {
    return ((sa as InetSocketAddress).address.hostAddress + ":" + sa.port)
  }

  @JvmOverloads
  fun dumpBuffer(buffer: ByteBuffer, allHex: Boolean = false): String {
    val sb = StringBuilder()
    buffer.mark()
    while (buffer.hasRemaining()) {
      val b = buffer.get()
      if (!allHex && Character.isLetterOrDigit(Char(b.toUShort()))) sb.append(Char(b.toUShort()))
      else sb.append(byteToHex(b))
      if (buffer.hasRemaining()) sb.append(",")
    }
    buffer.reset()
    return sb.toString()
  }

  fun ByteBuffer.dumpBufferFromBeginning(allHex: Boolean = false): String {
    val sb = StringBuilder()
    val pos = this.position()
    this.position(0)
    val byteList = mutableListOf<Byte>()
    while (this.hasRemaining()) {
      val b = this.get()
      byteList.add(b)
      if (!allHex && Character.isLetterOrDigit(Char(b.toUShort()))) sb.append(Char(b.toUShort()))
      else sb.append(byteToHex(b))
      if (this.hasRemaining()) sb.append(",")
    }
    this.position(pos)
    //    return sb.toString()
    return byteList.toByteArray().toHexString()
  }

  fun ByteBuffer.readString(
    stopByte: Int = 0x00,
    charset: Charset = AppModule.charsetDoNotUse
  ): String {
    val tempBuffer = ByteBuffer.allocate(this.remaining())
    while (this.hasRemaining()) {
      var b: Byte
      if (this.get().also { b = it }.toInt() == stopByte) break
      tempBuffer.put(b)
    }
    return charset.decode(tempBuffer.flip() as ByteBuffer).toString()
  }

  fun ByteBuf.readString(
    stopByte: Int = 0x00,
    charset: Charset = AppModule.charsetDoNotUse
  ): String {
    val tempBuffer = ByteBuffer.allocate(this.readableBytes())
    while (this.readableBytes() > 0) {
      var b: Byte
      if (this.readByte().also { b = it }.toInt() == stopByte) break
      tempBuffer.put(b)
    }
    return charset.decode(tempBuffer.flip() as ByteBuffer).toString()
  }

  fun ByteReadPacket.readString(
    stopByte: Int = 0x00,
    charset: Charset = AppModule.charsetDoNotUse
  ): String {
    val tempBuffer = ByteBuffer.allocate(this.remaining.toInt())
    while (!this.endOfInput) {
      var b: Byte
      if (this.readByte().also { b = it }.toInt() == stopByte) break
      tempBuffer.put(b)
    }
    return charset.decode(tempBuffer.flip() as ByteBuffer).toString()
  }

  fun writeString(
    buffer: ByteBuffer,
    s: String,
    stopByte: Int = 0x00,
    charset: Charset = AppModule.charsetDoNotUse
  ) {
    buffer.put(charset.encode(s))
    //		char[] tempArray = s.toCharArray();
    //		for(int i=0; i<tempArray.length; i++)
    //			buffer.put((byte) tempArray[i]);
    buffer.put(stopByte.toByte())
  }

  fun writeString(
    buffer: ByteBuf,
    s: String,
    stopByte: Int = 0x00,
    charset: Charset = AppModule.charsetDoNotUse
  ) {
    buffer.writeBytes(charset.encode(s))
    //		char[] tempArray = s.toCharArray();
    //		for(int i=0; i<tempArray.length; i++)
    //			buffer.put((byte) tempArray[i]);
    buffer.writeByte(stopByte)
  }

  @Throws(InstantiationException::class)
  fun construct(className: String, args: Array<Any>): Any {
    return try {
      val c = Class.forName(className)
      val constructorArgs: Array<Class<*>?> = arrayOfNulls(args.size)
      for (i in args.indices) constructorArgs[i] = args[i].javaClass
      val constructor = c.getConstructor(*constructorArgs)
      constructor.newInstance(*args)
    } catch (e: Exception) {
      throw InstantiationException("Problem constructing new " + className + ": " + e.message)
    }
  }

  fun Instant.toSimpleUtcDatetime(): String =
    DateTimeComponents.Formats.RFC_1123.format {
      setDateTimeOffset(
        this@toSimpleUtcDatetime,
        this@toSimpleUtcDatetime.offsetIn(TimeZone.currentSystemDefault())
      )
    }

  // TODO(nue): Get rid of this after it's confirmed it can be safely removed.
  /** NOOP placeholder for a function that _used to_ call [Thread.sleep]. */
  @Deprecated(message = "Don't sleep!", level = DeprecationLevel.WARNING)
  fun threadSleep(d: Duration) {
    Thread.yield()
  }

  fun min(a: Duration, b: Duration) = if (a <= b) a else b

  inline fun <R> Timer.timeKt(toTime: () -> R): R {
    var out: R
    this.update(measureNanoTime { out = toTime() }, TimeUnit.NANOSECONDS)
    return out
  }

  fun Duration.toMillisDouble(): Double =
    this.inWholeNanoseconds / 1.milliseconds.inWholeNanoseconds.toDouble()

  fun Duration.toSecondDoublePrecisionString() =
    if (this < 1.minutes) toString(SECONDS, 2) else toString(MINUTES, 1)
}
