/**
 * This file is part of nMix.
 * Copyright (C) 2015-2016-2017  Agora Voting SL <agora@agoravoting.com>

 * nMix is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.

 * nMix is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with nMix.  If not, see <http://www.gnu.org/licenses/>.
**/

package org.nvotes.mix

import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

import ch.bfh.unicrypt.math.algebra.multiplicative.classes.GStarModSafePrime
import org.nvotes.libmix._

import java.nio.charset.StandardCharsets
import java.math.BigInteger
import scala.collection.mutable.ListBuffer

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Implements the cryptographic protocol through stateless, reactive and
 *  choreographed actors.
 *
 *  At a high level the steps are
 *
 *  1. Public key generation
 *  2. Ballot casting (provided externally)
 *  3. Mixing
 *  4. Joint decryption
 *
 *  The protocol is implemented with rules of the form
 *
 *    Condition => Action
 *
 *  which operate on bulletin board data.
 *
 *  Because actors are stateless, Actions are responsible
 *  for verifying correctness of the execution by validating
 *  the bulletin board data from scratch at each execution.
 */
object Protocol extends Names {

  val logger = LoggerFactory.getLogger(Protocol.getClass)

  /** Executes one run of the protocol rules for one board section
   *
   *  The bulletin board is first updated (sync). Then rules are then evaluated against
   *  the updated data.
   *
   *  Global rules depend on data that is global to the section
   *  Item rules depend only on item data
   *
   *  Actions may return Errors which are posted to the bulletin board.
   *  In addition, a global try catches all exceptions, also posting an error
   *  to the bulletin board.
   *
   *  The following errors will not be posted to the bulletin board
   *  - no election config found
   *  - the election config cannot be parsed
   *  - the authority is not part of the trustees in the election config
   */
  def execute(section: BoardSectionInterface, trusteeCfg: TrusteeConfig): Result = {

    logger.info(s"Begin executing protocol for section '${section.name}'...")
    logger.info(s"Syncing '${section.name}'")

    section.sync()
    val files = section.getFileSet

    if(!files.contains(CONFIG)) {
      logger.error(s"Section ${section.name} does not have a config")
      return Error(s"Section ${section.name} does not have a config")
    }

    val configString = section.getConfig.get
    val parseConfig  = decode[Config](configString)
    if(parseConfig.isLeft) {
      logger.error(s"Cannot parse config for section ${section.name} $parseConfig")
      return Error(s"Cannot parse config for section ${section.name} $parseConfig")
    }

    val config = parseConfig.right.get
    logger.info(s"Found config: $config")
    val position = getMyPosition(config, trusteeCfg)
    logger.info(s"This authority at position $position")

    if(position == 0) {
      logger.info(s"could not find self in list of trustees for config $config")
      return Error(s"could not find self in list of trustees for config $config")
    }

    val group = GStarModSafePrime.getInstance(new BigInteger(config.modulus))
    val generator = group.getElementFrom(config.generator)
    val cSettings = CryptoSettings(group, generator)
    val ctx = Context(config, section, trusteeCfg, position, cSettings)

    try {
      execute(ctx)
    }
    catch {
      case e:Exception => {
        logger.error(s"Exception caught executing actions: $e", e)
        postError("An exception occurred during processing: ${e.getClass}", ctx)
        Error(e.toString)
      }
    }
  }

  private def postError(message: String, ctx: Context): Unit = {
    val file = IO.writeTemp(message)
    ctx.section.addError(file, ctx.position)
  }

  private def execute(ctx: Context): Result = {
    val files = ctx.section.getFileSet
    val rules = globalRules(ctx)

    /** first rule that matches is executed */
    val hit = rules.find{ case (c, a) => c.eval(files)}
      .map(_._2)
    logger.info(s"* Global matches: $hit")

    val result = hit.map(_.execute()).getOrElse(Ok)
    logger.info(s"* Global rules result: $result")

    if(result == Ok) {
      val items = ctx.config.items
      val irules = (1 to items).map(i => itemRules(ctx, i, files))

      /** get first rule that matches for each item, sort Actions by priority */
      val hits = irules.flatMap { rules =>
        rules.find{ case (c, a) => c.eval(files)}
        .map(_._2)
      }.sorted

      if(hits.size > 0) {
        logger.info(s"* Per-item hits: ${hits.map(_.toString)}")

        /** If the action is preshuffle, we execute in parallel to eat up the serial loop */
        val preShuffleHits = hits.filter(_.isInstanceOf[AddPreShuffleData])
        val results = if(preShuffleHits.size == hits.size) {
          preShuffleHits.par.map(action => action -> action.execute)
        } else {
          hits.map(action => action -> action.execute)
        }

        logger.info(s"Per-item results: $results")

        val errorStrings = results.flatMap {
          case (_, Error(message)) => Some(message)
          case _ => None
        }
        if(errorStrings.size > 0) {
          logger.error(s"Results returned errors: $errorStrings")
          postError(errorStrings.mkString("\n"), ctx)
          Error(errorStrings.mkString("\n"))
        } else {
          Ok
        }
      }
      else {
        logger.info(s"* Per-item matches: None")
        Ok
      }
    }
    else {
      logger.warn(s"Got pause or error signal: $result")

      result match {
        case Error(message) => postError(message, ctx)
        case _ =>
      }

      result
    }
  }

  /** Returns the global rules.
   *
   *  Examples of global rules are
   *
   *  1) Stop execution if a pause is requested
   *  2) Stop execution if any authority has reported an error or there is a global error
   *  3) Validate the global config if this authority has not yet done so
   *
   *  A list of rules has type List[(Cond, Action)]
   */
  private def globalRules(ctx: Context) = {

    val pauseYes = Condition.yes(PAUSE)
    /** no errors, neither per authority, nor global */
    val errorsYes = Condition((1 to ctx.config.trustees.size).map(i => ERROR(i) -> false).toList).no(ERROR).neg
    val configNo = Condition.yes(CONFIG).yes(CONFIG_STMT).no(CONFIG_SIG(ctx.position))

    ListBuffer[(Cond, Action)](
      pauseYes -> StopAction("pause found"),
      errorsYes -> StopAction("error found"),
      configNo -> ValidateConfig(ctx)
    )
  }

  /** Returns the per-item rules.
   *
   *  Per-item rules implement the core of the crypto protocol.
   *
   *  A list of rules has type List[(Cond, Action)]
   */
  private def itemRules(ctx: Context, item: Int, files: Set[String]) = {

    val config = ctx.config

    /** construct conditions */

    val allConfigsYes = Condition(
      (1 to config.trustees.size).map(auth => CONFIG_SIG(auth) -> true)
      .toList
    )

    val myShareNo = Condition.no(SHARE(item, ctx.position)).no(SHARE_STMT(item, ctx.position))
      .no(SHARE_SIG(item, ctx.position))
    val allShares = Condition((1 to config.trustees.size).flatMap { auth =>
        List(
          SHARE(item, auth) -> true,
          SHARE_STMT(item, auth) -> true,
          SHARE_SIG(item, auth) -> true
        )
      }
      .toList
    )

    val noPublicKey = Condition.no(PUBLIC_KEY(item))
    val noPublicKeySig = Condition.yes(PUBLIC_KEY(item)).no(PUBLIC_KEY_SIG(item, ctx.position))

    val myMixPosition = getMixPosition(ctx.position, item, ctx.config.trustees.size)

    val previousMixesYes = Condition((1 to myMixPosition - 1).flatMap { auth =>
        val mixAuth = getTrusteeForMixPosition(auth, item, ctx.config.trustees.size)
        List(
          MIX(item, mixAuth) -> true,
          MIX_STMT(item, mixAuth) -> true,
          MIX_SIG(item, mixAuth, mixAuth) -> true
        )
      }
      .toList
    )

    val ballotsYes = Condition.yes(BALLOTS(item)).yes(BALLOTS_STMT(item)).yes(BALLOTS_SIG(item))

    val myPreShuffleNo = ballotsYes.andNot(MIX(item, ctx.position)).andNot(PERM_DATA(item, ctx.position))
    val myPreShuffleYes = Condition.yes(PERM_DATA(item, ctx.position))

    val myMixNo = ballotsYes.and(previousMixesYes).andNot(MIX(item, ctx.position))

    /** verify mixes other than our own */
    val missingMixSigs = (1 to config.trustees.size).filter(_ != ctx.position).map { auth =>
      (auth, item, Condition(
        List(MIX(item, auth) -> true,
        MIX_STMT(item, auth) -> true,
        MIX_SIG(item, auth, auth) -> true,
        MIX_SIG(item, auth, ctx.position) -> false)
      ))
    }
    val allMixSigs = Condition((1 to config.trustees.size).map { auth =>
      MIX_SIG(item, auth, ctx.position) -> true
    }.toList)

    val noDecryptions = Condition.no(DECRYPTION(item, ctx.position))
      .no(DECRYPTION_STMT(item, ctx.position)).no(DECRYPTION_SIG(item, ctx.position))

    val allDecryptions = Condition((1 to config.trustees.size).flatMap { auth =>
        List(
          DECRYPTION(item, auth) -> true,
          DECRYPTION_STMT(item, auth) -> true,
          DECRYPTION_SIG(item, auth) -> true
        )
      }
      .toList
    )

    val decryptor = getDecryptingTrustee(item, config.trustees.size)

    val noPlaintexts = Condition.no(PLAINTEXTS(item, decryptor))
    val noPlaintextsSig = Condition.yes(PLAINTEXTS(item, decryptor)).no(PLAINTEXTS_SIG(item, ctx.position))

    /** construct rules */

    val rules = ListBuffer[(Cond, Action)]()

    rules += allConfigsYes.and(myShareNo) -> AddShare(ctx, item)

    if(ctx.position == 1) {
      rules += allShares.and(noPublicKey) -> AddOrSignPublicKey(ctx, item)
    }

    rules += allShares.and(noPublicKeySig) -> AddOrSignPublicKey(ctx, item)

    if(ctx.trusteeCfg.offlineSplit) {
      // add pre shuffle data
      rules += myPreShuffleNo -> AddPreShuffleData(ctx, item)
      // add mix (online phase)
      rules += myPreShuffleYes.and(myMixNo) -> AddMix(ctx, item)
    }
    else {
      // add mix (offline + online phases)
      rules += myMixNo -> AddMix(ctx, item)
    }

    missingMixSigs.foreach { case(auth, item, noMixSig) =>
      rules += noMixSig -> VerifyMix(ctx, item, auth)
    }

    rules += allMixSigs.and(noDecryptions) -> AddDecryption(ctx, item)

    if(ctx.position == decryptor) {
      rules += allDecryptions.and(noPlaintexts) -> AddOrSignPlaintexts(ctx, item)
    }
    rules += allDecryptions.and(noPlaintextsSig) -> AddOrSignPlaintexts(ctx, item)

    rules
  }

  /** Returns the permuted mix position of the trustee for the given config.
   *
   *  The permutation is a 1 shift left permutation
   *
   *  This 1-shift left is a generator of a cyclic permutation group of
   *  order 'trustees'. For example
   *
   *  123 at item 1 => 123    (identity)
   *  123 at item 2 => 231    (auth 2 is at 1, 3 is at 2, 1 is at 3)
   *  123 at item 3 => 312
   *  123 at item 4 => 123
   */
  def getMixPosition(auth: Int, item: Int, trustees: Int): Int = {
    val permuted = (auth + (item - 1)) % trustees
    permuted + 1
  }

  /** Returns the inverse of the permuted mix position */
  def getTrusteeForMixPosition(auth: Int, item: Int, trustees: Int): Int = {
    /** for a cyclic group with generator g of order n we have
     *
     *  FIXME revise
     *
     * g^n = 1. Applying g to some power p we have
     *
     * g^p = x
     *
     * since g^p * g^(n-p) = g^n = 1
     *
     * the inverse of x=g^p is g^(n-p)
     *
     * Below, n-p is the "gap", and (item - 1) is p
     */
    val gap = trustees - item

    val permuted = (auth + gap) % trustees
    if(permuted == 0) {
      trustees
    }
    else {
      permuted
    }
  }

  /** Specifies which trustee will generate the plaintexts */
  def getDecryptingTrustee(item: Int, trustees: Int): Int = {
    val permuted = (item - 1) % trustees
    permuted + 1
  }

  /** Returns the position of the trustee for the given config.
   *
   *  Positions start at 1. If the trustee is not found, returns 0.
   */
  private def getMyPosition(config: Config, trusteeConfig: TrusteeConfig): Int = {
    val pks = config.trustees.map(Crypto.readPublicRsa(_))
    pks.indexOf(trusteeConfig.publicKey) + 1
  }
}

sealed trait Result
/** continue execution */
case object Ok extends Result
/** should stop execution for this section */
case class Stop(message: String) extends Result
/** should stop execution and write error to the section */
case class Error(message: String) extends Result
