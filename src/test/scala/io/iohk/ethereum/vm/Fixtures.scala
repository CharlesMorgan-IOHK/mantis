package io.iohk.ethereum.vm

object Fixtures {

  val ConstantinopleBlockNumber = 200

  val blockchainConfig = BlockchainConfigForEvm(
    // block numbers are irrelevant
    frontierBlockNumber = 0,
    homesteadBlockNumber = 0,
    eip150BlockNumber = 0,
    eip160BlockNumber = 0,
    eip161BlockNumber = 0,
    byzantiumBlockNumber = 0,
    constantinopleBlockNumber = ConstantinopleBlockNumber,
    maxCodeSize = None,
    accountStartNonce = 0
  )

}
