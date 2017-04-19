package org.nvotes.trustee

import java.nio.file.Paths
import java.nio.file.Files
import java.math.BigInteger

import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

object BallotboxAdd extends App {
  import org.nvotes.libmix._
  import ch.bfh.unicrypt.math.algebra.multiplicative.classes.GStarModSafePrime
  val t0 = System.nanoTime()

  val totalVotes = args(0).toInt
  println(s"BallotboxAdd votes = $totalVotes")
  val trusteeCfg = TrusteeConfig.load

  val board = new Board(trusteeCfg.dataStorePath)
  val section = board.cloneOrSyncSection(trusteeCfg.repoBaseUri, Paths.get("repo"))
  val configString = section.getConfig.get
  val configHash = Crypto.sha512(configString)
  val config = decode[Config](configString).right.get

  val group = GStarModSafePrime.getInstance(new BigInteger(config.modulus))
  val generator = group.getElementFrom(config.generator)
  val cSettings = CryptoSettings(group, generator)
  val votes = (1 to config.items).map { item =>
    val publicKey = section.getPublicKey(item).get
    val pk = Util.getPublicKeyFromString(publicKey, generator)

    val ballots = Util.getRandomVotesStr(totalVotes, generator, pk).toArray
    // val ballots = Util.encryptVotes(List(1, 3, 5, 7, 11).map(_ + item), cSettings, pk).map(_.convertToString).toArray

    val ballotsString = Ballots(ballots).asJson.noSpaces
    val ballotHash = Crypto.sha512(ballotsString)
    val statement = Statement.getBallotsStatement(ballotHash, configHash, item)
    val signature = statement.sign(Crypto.readPrivateRsa(Paths.get("keys/ballotbox.pem")))

    val file1 = IO.writeTemp(ballotsString)
    val file2 = IO.writeTemp(statement.asJson.noSpaces)
    val file3 = IO.writeTemp(signature)

    section.addBallots(file1, file2, file3, item)
  }

  val t1 = System.nanoTime()
  println("Generating ballots time: " + ((t1 - t0) / 1000000000.0) + " s")
}

object PushTest extends App {
  val trusteeCfg = TrusteeConfig.load
  val board = new Board(trusteeCfg.dataStorePath)
  val section = board.cloneOrSyncSection(trusteeCfg.repoBaseUri, Paths.get("repo"))

  Files.copy(Paths.get("bigfile"), section.gitRepo.repoPath.resolve("bigfile"))
  section.gitRepo.send("test", "bigfile")
}