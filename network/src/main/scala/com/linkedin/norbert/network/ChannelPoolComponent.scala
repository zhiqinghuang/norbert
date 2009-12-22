/*
 * Copyright 2009 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linkedin.norbert.network

import java.net.InetSocketAddress
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger
import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.protobuf.{ProtobufEncoder, ProtobufDecoder}
import org.jboss.netty.handler.codec.frame.{LengthFieldPrepender, LengthFieldBasedFrameDecoder}
import netty.RequestHandlerComponent
import org.jboss.netty.channel.group.{DefaultChannelGroup, ChannelGroup}
import com.linkedin.norbert.util.Logging
import com.linkedin.norbert.cluster.Node
import com.linkedin.norbert.protos._

trait ChannelPoolComponent {
  this: BootstrapFactoryComponent with RequestHandlerComponent =>

  val channelPool: ChannelPool

  class ChannelPool(maxConnectionsPerNode: Int, writeTimeout: Int, channelGroup: ChannelGroup) extends Logging {
    def this(maxConnectionsPerNode: Int, writeTimeout: Int) = this(maxConnectionsPerNode, writeTimeout, new DefaultChannelGroup("norbert-client"))
    def this(channelGroup: ChannelGroup) = this(NetworkDefaults.MAX_CONNECTIONS_PER_NODE, NetworkDefaults.WRITE_TIMEOUT, channelGroup)
    def this() = this(new DefaultChannelGroup("norbert-client"))

    private val bootstrap = bootstrapFactory.newClientBootstrap
    bootstrap.setPipelineFactory(pipelineFactory)
    bootstrap.setOption("tcpNoDelay", true)
    bootstrap.setOption("reuseAddress", true)
    
    private val channelPool = new ConcurrentHashMap[Node, Pool]
    private val queuedWriteExecutor = Executors.newCachedThreadPool
    private val requestHandler = new RequestHandler

    def sendRequest(nodes: scala.collection.Set[Node], request: Request) {
      nodes.foreach { node =>
        var pool = channelPool.get(node)
        if (pool == null) {
          pool = new Pool(node.address)
          channelPool.putIfAbsent(node, pool)
          pool = channelPool.get(node)
        }

        pool.sendRequest(request)
      }
    }

    def shutdown: Unit = {
      log.info("Shutting down ChannelPool")
      
      channelGroup.close
      bootstrap.releaseExternalResources
      queuedWriteExecutor.shutdown
    }

    protected def pipelineFactory: ChannelPipelineFactory = new ChannelPipelineFactory {
      def getPipeline = {
        val p = Channels.pipeline
        p.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Math.MAX_INT, 0, 4, 0, 4))
        p.addLast("protobufDecoder", new ProtobufDecoder(NorbertProtos.NorbertMessage.getDefaultInstance))

        p.addLast("frameEncoder", new LengthFieldPrepender(4))
        p.addLast("protobufEncoder", new ProtobufEncoder)

        p.addLast("requestHandler", requestHandler)
        
        p
      }
    }

    private class Pool(address: InetSocketAddress) {
      private val pool = new ArrayBlockingQueue[Channel](maxConnectionsPerNode)
      private val waitingWrites = new LinkedBlockingQueue[Request]
      private val poolSize = new AtomicInteger(0)

      def sendRequest(request: Request) {
        checkoutChannel match {
          case Some(channel) => if (channel.isOpen) {
            writeRequestToChannel(request, channel)
          } else {
            openChannelAndWrite(request)
          }

          case None => if (attemptGrow) {
            openChannelAndWrite(request)
          } else {
            waitingWrites.offer(request)
          }
        }
      }

      private def checkoutChannel: Option[Channel] = pool.poll match {
        case null => None
        case c => Some(c)
      }

      private def checkinChannel(channel: Channel): Unit = waitingWrites.poll match {
        case null => pool.offer(channel)
        case request => if (isTimedOut(request.timestamp)) {
          checkinChannel(channel)
          request.offerResponse(Left(new TimeoutException("Timed out waiting for available channel")))
        } else {
          queuedWriteExecutor.submit(new Runnable {
            def run = writeRequestToChannel(request, channel)
          })
        }
      }
      
      private def attemptGrow = if (poolSize.incrementAndGet > maxConnectionsPerNode) {
        poolSize.decrementAndGet
        false
      } else {
        true
      }

      private def openChannelAndWrite(request: Request): Unit = {
        log.ifDebug("Opening a channel to: %s", address)
        
        bootstrap.connect(address).addListener(new ChannelFutureListener {
          def operationComplete(openFuture: ChannelFuture) = {
            if (openFuture.isSuccess) {
              val channel = openFuture.getChannel
              channelGroup.add(channel)

              if (isTimedOut(request.timestamp)) {
                request.offerResponse(Left(new TimeoutException("Timed out waiting for channel to open")))
              } else {
                val f = writeRequestToChannel(request, channel)
                f.addListener(new ChannelFutureListener {
                  def operationComplete(writeFuture: ChannelFuture) = if (!writeFuture.isSuccess) {
                    request.offerResponse(Left(writeFuture.getCause))
                  }
                })
              }
            } else {
              request.offerResponse(Left(openFuture.getCause))
            }
          }
        })
      }

      private def writeRequestToChannel(request: Request, channel: Channel) = try {
        log.ifDebug("Writing request[%s] to channel: %s", request, channel)
        channel.write(request)
      } finally {
        checkinChannel(channel)
      }

      private def isTimedOut(started: Long) = (System.currentTimeMillis - started) > writeTimeout
    }
  }
}