package io.iohk.ethereum.network

import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain.Blockchain
import io.iohk.ethereum.network.EtcMessageHandler.EtcPeerInfo
import io.iohk.ethereum.network.MessageHandler.MessageAction.{IgnoreMessage, TransmitMessage}
import io.iohk.ethereum.network.MessageHandler.{HandshakeResult, MessageAction, MessageHandlingResult, PeerInfo}
import io.iohk.ethereum.network.PeerManagerActor.PeerConfiguration
import io.iohk.ethereum.network.p2p.Message
import io.iohk.ethereum.network.p2p.messages.CommonMessages.{NewBlock, Status}
import io.iohk.ethereum.network.p2p.messages.PV62.{BlockHeaders, NewBlockHashes}
import io.iohk.ethereum.network.p2p.messages.WireProtocol.Disconnect
import io.iohk.ethereum.utils.Logger

case class EtcMessageHandler(peer: Peer, peerInfo: EtcPeerInfo, forkResolverOpt: Option[ForkResolver],
                             appStateStorage: AppStateStorage, peerConfiguration: PeerConfiguration, blockchain: Blockchain)
  extends MessageHandler[EtcPeerInfo, EtcPeerInfo] with BlockchainHost with Logger {

  override def sendingMessage(message: Message): MessageHandlingResult[EtcPeerInfo, EtcPeerInfo] = {
    val handleSendMessage = updateMaxBlock(message) _
    val newHandler = this.copy(peerInfo = handleSendMessage(peerInfo))
    val messageAction = handleSendingNewBlock(message, newHandler.peerInfo)
    MessageHandlingResult(newHandler, messageAction)
  }

  override def receivingMessage(message: Message): MessageHandlingResult[EtcPeerInfo, EtcPeerInfo] = {
    val handleReceivedMessage =
      updateTotalDifficulty(message) _ andThen updateForkAccepted(message) andThen updateMaxBlock(message)
    val newHandler = this.copy(peerInfo = handleReceivedMessage(peerInfo))
    val messageAction = handleBlockchainHostRequest(message)
    MessageHandlingResult(newHandler, messageAction)
  }

  /**
    * Processes the message and updates the total difficulty of the peer
    *
    * @param message to be processed
    * @param initialPeerInfo from before the message was processed
    * @return new peer info with the total difficulty updated
    */
  private def updateTotalDifficulty(message: Message)(initialPeerInfo: EtcPeerInfo): EtcPeerInfo = message match {
    case newBlock: NewBlock =>
      initialPeerInfo.withTotalDifficulty(newBlock.totalDifficulty)
    case _ => initialPeerInfo
  }

  /**
    * Processes the message and updates if the fork block was accepted from the peer
    *
    * @param message to be processed
    * @param initialPeerInfo from before the message was processed
    * @return new peer info with the fork block accepted value updated
    */
  private def updateForkAccepted(message: Message)(initialPeerInfo: EtcPeerInfo): EtcPeerInfo = message match {
    case BlockHeaders(blockHeaders) =>
      val newPeerInfoOpt: Option[EtcPeerInfo] = for {
        forkResolver <- forkResolverOpt
        forkBlockHeader <- blockHeaders.find(_.number == forkResolver.forkBlockNumber)
      } yield {
        val newFork = forkResolver.recognizeFork(forkBlockHeader)
        log.info("Received fork block header with fork: {}", newFork)

        if (!forkResolver.isAccepted(newFork)) {
          log.warn("Peer is not running the accepted fork, disconnecting")
          peer.disconnectFromPeer(Disconnect.Reasons.UselessPeer)
          initialPeerInfo
        } else
          initialPeerInfo.withForkAccepted(true)
      }
      newPeerInfoOpt.getOrElse(initialPeerInfo)

    case _ => initialPeerInfo
  }

  /**
    * Processes the message and updates the max block number from the peer
    *
    * @param message to be processed
    * @param initialPeerInfo from before the message was processed
    * @return new peer info with the max block number updated
    */
  private def updateMaxBlock(message: Message)(initialPeerInfo: EtcPeerInfo): EtcPeerInfo = {
    def update(ns: Seq[BigInt]): EtcPeerInfo = {
      val maxBlockNumber = ns.fold(0: BigInt) { case (a, b) => if (a > b) a else b }
      if (maxBlockNumber> appStateStorage.getEstimatedHighestBlock())
        appStateStorage.putEstimatedHighestBlock(maxBlockNumber)

      if (maxBlockNumber > initialPeerInfo.maxBlockNumber)
        initialPeerInfo.withMaxBlockNumber(maxBlockNumber)
      else
        initialPeerInfo
    }

    message match {
      case m: BlockHeaders =>
        update(m.headers.map(_.number))
      case m: NewBlock =>
        update(Seq(m.block.header.number))
      case m: NewBlockHashes =>
        update(m.hashes.map(_.number))
      case _ => initialPeerInfo
    }
  }

  /**
    * Processes the message and updates if the fork block was accepted from the peer
    *
    * @param message to be processed
    * @param initialPeerInfo from before the message was processed
    * @return new peer info with the fork block accepted value updated
    */
  private def handleSendingNewBlock(message: Message, peerInfo: EtcPeerInfo): MessageAction = message match {
    case b: NewBlock =>
      if (b.block.header.number > peerInfo.maxBlockNumber)
        TransmitMessage
      else
        IgnoreMessage
    case _ => TransmitMessage
  }
}

object EtcMessageHandler {

  case class EtcPeerInfo(remoteStatus: Status,
                         totalDifficulty: BigInt,
                         forkAccepted: Boolean, maxBlockNumber: BigInt) extends PeerInfo with HandshakeResult {
    def withTotalDifficulty(totalDifficulty: BigInt): EtcPeerInfo = copy(totalDifficulty = totalDifficulty)

    def withForkAccepted(forkAccepted: Boolean): EtcPeerInfo = copy(forkAccepted = forkAccepted)

    def withMaxBlockNumber(maxBlockNumber: BigInt): EtcPeerInfo = copy(maxBlockNumber = maxBlockNumber)
  }
}