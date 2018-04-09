package io.iohk.ethereum.mallet.main

import java.security.SecureRandom
import java.time.Instant

import io.iohk.ethereum.domain.Address
import io.iohk.ethereum.keystore.KeyStoreImpl
import io.iohk.ethereum.mallet.interpreter.Interpreter
import io.iohk.ethereum.mallet.service.{RpcClient, State}

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._

object Mallet extends App {

  private val clOptions = OptionParser(args) match {
    case Some(co) => co
    case None => sys.exit(1)
  }

  private val shell = new Shell(clOptions.dataDir)

  private val initialState = {
    new State(
      shell,
      new RpcClient(clOptions.node),
      new KeyStoreImpl(clOptions.dataDir, new SecureRandom()),
      clOptions.account.map(Address(_)),
      None,
      Instant.now()
    )
  }

  private def nonInteractive(cmd: String, state: State): Unit = {
    val result = Interpreter(cmd, state)
    shell.printLine(result.msg)

    Await.ready(RpcClient.actorSystem.terminate(), 5.seconds)
    val exitCode = if (result.error) 1 else 0
    sys.exit(exitCode)
  }

  @tailrec
  private def loop(state: State): Unit = {

    shell.readLine() match {
      case Some(line) =>
        val result = Interpreter(line, state)
        shell.printLine(withNewLine(result.msg))
        loop(result.state)

      case None =>
        RpcClient.actorSystem.terminate()
    }
  }

  private def withNewLine(s: String): String = {
    val rightTrimmed = s.reverse.dropWhile(_.isWhitespace).reverse
    if (rightTrimmed.isEmpty) rightTrimmed else rightTrimmed + "\n"
  }

  clOptions.command match {
    case None =>
      loop(initialState)

    case Some(cmd) =>
      val passwordReader = clOptions.password.map(new ConstPasswordReader(_)).getOrElse(shell)
      val state = initialState.copy(passwordReader = passwordReader)
      nonInteractive(cmd, state)
  }
}
