/*
 * MIT License
 *
 * Copyright (c) 2019-2020 JetBrains s.r.o.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.jetbrains.projector.intTest

import io.ktor.application.install
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.http.content.files
import io.ktor.http.content.static
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import org.jetbrains.projector.common.protocol.data.FontDataHolder
import org.jetbrains.projector.common.protocol.data.TtfFontData
import org.jetbrains.projector.common.protocol.handshake.COMMON_VERSION
import org.jetbrains.projector.common.protocol.handshake.ToClientHandshakeSuccessEvent
import org.jetbrains.projector.common.protocol.handshake.commonVersionList
import org.jetbrains.projector.common.protocol.toClient.ToClientMessageType
import org.jetbrains.projector.common.protocol.toServer.ToServerMessageType
import org.jetbrains.projector.server.core.protocol.HandshakeTypesSelector
import org.jetbrains.projector.server.core.protocol.KotlinxJsonToClientHandshakeEncoder
import org.jetbrains.projector.server.core.protocol.KotlinxJsonToServerHandshakeDecoder
import java.io.File
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

object ConnectionUtil {

  private val clientFile = File("../projector-client-web/build/distributions/index.html")

  val clientUrl = "file://${clientFile.absolutePath}"

  private fun getFontHolderData(): FontDataHolder {
    val data = File("src/intTest/resources/fonts/intTestFont.ttf").readBytes()
    val base64 = String(Base64.getEncoder().encode(data))

    return FontDataHolder(0, TtfFontData(ttfBase64 = base64))
  }

  data class SenderReceiver(
    val sender: suspend (ToClientMessageType) -> Unit,
    val receiver: suspend () -> ToServerMessageType,
  )

  private suspend fun DefaultWebSocketServerSession.doHandshake(): SenderReceiver {
    val handshakeText = (incoming.receive() as Frame.Text).readText()
    val toServerHandshakeEvent = KotlinxJsonToServerHandshakeDecoder.decode(handshakeText)

    assertEquals(COMMON_VERSION, toServerHandshakeEvent.commonVersion,
                 "Incompatible common protocol versions: server - $COMMON_VERSION (#${commonVersionList.indexOf(COMMON_VERSION)}), " +
                 "client - ${toServerHandshakeEvent.commonVersion} (#${toServerHandshakeEvent.commonVersionId})"
    )

    val toClientCompressor = HandshakeTypesSelector.selectToClientCompressor(toServerHandshakeEvent.supportedToClientCompressions)
    assertNotNull(toClientCompressor,
                  "Server doesn't support any of the following to-client compressions: ${toServerHandshakeEvent.supportedToClientCompressions}"
    )

    val toClientEncoder = HandshakeTypesSelector.selectToClientEncoder(toServerHandshakeEvent.supportedToClientProtocols)
    assertNotNull(toClientEncoder) {
      "Server doesn't support any of the following to-client protocols: ${toServerHandshakeEvent.supportedToClientProtocols}"
    }

    val toServerDecompressor = HandshakeTypesSelector.selectToServerDecompressor(toServerHandshakeEvent.supportedToServerCompressions)
    assertNotNull(toServerDecompressor) {
      "Server doesn't support any of the following to-server compressions: ${toServerHandshakeEvent.supportedToServerCompressions}"
    }

    val toServerDecoder = HandshakeTypesSelector.selectToServerDecoder(toServerHandshakeEvent.supportedToServerProtocols)
    assertNotNull(toServerDecoder) {
      "Server doesn't support any of the following to-server protocols: ${toServerHandshakeEvent.supportedToServerProtocols}"
    }

    val successEvent = ToClientHandshakeSuccessEvent(
      toClientCompression = toClientCompressor.compressionType,
      toClientProtocol = toClientEncoder.protocolType,
      toServerCompression = toServerDecompressor.compressionType,
      toServerProtocol = toServerDecoder.protocolType,
      fontDataHolders = listOf(getFontHolderData()),
      colors = null
    )
    outgoing.send(Frame.Binary(true, KotlinxJsonToClientHandshakeEncoder.encode(successEvent)))

    incoming.receive()  // this message means the client is ready

    return SenderReceiver(
      sender = { outgoing.send(Frame.Binary(true, toClientCompressor.compress(toClientEncoder.encode(it)))) },
      receiver = { toServerDecoder.decode(toServerDecompressor.decompress((incoming.receive() as Frame.Text).readText())) }
    )
  }

  fun startServerAndDoHandshake(
    port: Int = 8887,  // todo: take from constant "default server port"
    afterHandshake: suspend DefaultWebSocketServerSession.(senderReceiver: SenderReceiver) -> Unit,
  ): ApplicationEngine =
    embeddedServer(Netty, port) {
      install(WebSockets)

      routing {
        webSocket("/") {
          val senderReceiver = doHandshake()
          afterHandshake(senderReceiver)
        }
      }
    }

  fun hostFiles(port: Int, host: String): ApplicationEngine = embeddedServer(Netty, port, host) {
    routing {
      static("/") {
        files(clientFile.parentFile)
      }
    }
  }
}