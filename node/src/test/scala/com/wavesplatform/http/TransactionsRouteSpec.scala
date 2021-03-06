package com.wavesplatform.http

import akka.http.scaladsl.model._
import com.wavesplatform.account.{AddressScheme, KeyPair, PublicKey}
import com.wavesplatform.api.common.CommonTransactionsApi
import com.wavesplatform.api.common.CommonTransactionsApi.TransactionMeta
import com.wavesplatform.api.http.ApiError._
import com.wavesplatform.api.http.ApiMarshallers._
import com.wavesplatform.api.http.TransactionsApiRoute
import com.wavesplatform.block.Block
import com.wavesplatform.block.Block.TransactionProof
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.{Base58, _}
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms.{CONST_BOOLEAN, CONST_LONG, FUNCTION_CALL}
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3
import com.wavesplatform.network.TransactionPublisher
import com.wavesplatform.state.{AccountScriptInfo, Blockchain, Height, InvokeScriptResult}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.smart.InvokeScriptTransaction.Payment
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import com.wavesplatform.transaction.smart.script.trace.{AccountVerifierTrace, TracedResult}
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.wavesplatform.transaction.{Asset, Proofs, TxHelpers, TxVersion}
import com.wavesplatform.{BlockGen, BlockchainStubHelpers, NoShrink, TestTime, TestWallet, TransactionGen}
import monix.reactive.Observable
import org.scalacheck.Gen._
import org.scalacheck.{Arbitrary, Gen}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{Matchers, OptionValues}
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.Random

class TransactionsRouteSpec
    extends RouteSpec("/transactions")
    with RestAPISettingsHelper
    with MockFactory
    with Matchers
    with TransactionGen
    with BlockGen
    with PropertyChecks
    with OptionValues
    with TestWallet
    with NoShrink
    with BlockchainStubHelpers {

  private val blockchain          = mock[Blockchain]
  private val utxPoolSynchronizer = mock[TransactionPublisher]
  private val addressTransactions = mock[CommonTransactionsApi]
  private val utxPoolSize         = mockFunction[Int]
  private val testTime            = new TestTime

  private val transactionsApiRoute = new TransactionsApiRoute(
    restAPISettings,
    addressTransactions,
    testWallet,
    blockchain,
    utxPoolSize,
    utxPoolSynchronizer,
    testTime
  )

  private val route = seal(transactionsApiRoute.route)

  private val invalidBase58Gen = alphaNumStr.map(_ + "0")

  routePath("/calculateFee") - {
    "transfer with Waves fee" - {
      val transferTxScenario =
        for {
          sender    <- accountGen
          recipient <- accountGen
          version   <- Gen.oneOf(TransferTransaction.supportedVersions.toSeq)
          tx = Json.obj(
            "type"            -> 4,
            "version"         -> version,
            "amount"          -> 1000000,
            "senderPublicKey" -> sender.publicKey.toString,
            "recipient"       -> recipient.toAddress
          )
        } yield (sender.publicKey, tx)
      "TransferTransaction" in forAll(transferTxScenario) {
        case (_, transferTx) =>
          (addressTransactions.calculateFee _).expects(*).returning(Right((Asset.Waves, 100000L, 0L))).once()

          Post(routePath("/calculateFee"), transferTx) ~> route ~> check {
            status shouldEqual StatusCodes.OK
            (responseAs[JsObject] \ "feeAssetId").asOpt[String] shouldBe empty
            (responseAs[JsObject] \ "feeAmount").as[Long] shouldEqual 100000
          }
      }

      val massTransferTxScenario =
        for {
          sender     <- accountGen
          recipient1 <- accountGen
          recipient2 <- accountGen
          version    <- Gen.oneOf(MassTransferTransaction.supportedVersions.toSeq)
          tx = Json.obj(
            "type"            -> 11,
            "version"         -> version,
            "senderPublicKey" -> Base58.encode(sender.publicKey.arr),
            "transfers" -> Json.arr(
              Json.obj(
                "recipient" -> recipient1.toAddress,
                "amount"    -> 1000000
              ),
              Json.obj(
                "recipient" -> recipient2.toAddress,
                "amount"    -> 2000000
              )
            )
          )
        } yield (sender.publicKey, tx)
      "MassTransferTransaction" in forAll(massTransferTxScenario) {
        case (_, transferTx) =>
          (addressTransactions.calculateFee _).expects(*).returning(Right((Asset.Waves, 200000L, 0L))).once()

          Post(routePath("/calculateFee"), transferTx) ~> route ~> check {
            status shouldEqual StatusCodes.OK
            (responseAs[JsObject] \ "feeAssetId").asOpt[String] shouldBe empty
            (responseAs[JsObject] \ "feeAmount").as[Long] shouldEqual 200000
          }
      }
    }

    "transfer with Asset fee" - {
      val transferTxWithAssetFeeScenario =
        for {
          assetId   <- issueGen.map(_.assetId)
          sender    <- accountGen
          recipient <- accountGen
          version   <- Gen.oneOf(TransferTransaction.supportedVersions.toSeq)
          tx = Json.obj(
            "type"            -> 4,
            "version"         -> version,
            "amount"          -> 1000000,
            "feeAssetId"      -> assetId.toString,
            "senderPublicKey" -> Base58.encode(sender.publicKey.arr),
            "recipient"       -> recipient.toAddress
          )
        } yield (sender.publicKey, tx, IssuedAsset(assetId))
      "without sponsorship" in forAll(transferTxWithAssetFeeScenario) {
        case (_, transferTx, _) =>
          (addressTransactions.calculateFee _).expects(*).returning(Right((Asset.Waves, 100000L, 0L))).once()

          Post(routePath("/calculateFee"), transferTx) ~> route ~> check {
            status shouldEqual StatusCodes.OK
            (responseAs[JsObject] \ "feeAssetId").asOpt[String] shouldBe empty
            (responseAs[JsObject] \ "feeAmount").as[Long] shouldEqual 100000
          }
      }

      "with sponsorship" in {
        val assetId: IssuedAsset = IssuedAsset(issueGen.sample.get.assetId)
        val sender: PublicKey    = accountGen.sample.get.publicKey
        val transferTx = Json.obj(
          "type"            -> 4,
          "version"         -> 2,
          "amount"          -> 1000000,
          "feeAssetId"      -> assetId.id.toString,
          "senderPublicKey" -> Base58.encode(sender.arr),
          "recipient"       -> accountGen.sample.get.toAddress
        )

        (addressTransactions.calculateFee _).expects(*).returning(Right((assetId, 5L, 0L))).once()

        Post(routePath("/calculateFee"), transferTx) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          (responseAs[JsObject] \ "feeAssetId").as[String] shouldBe assetId.id.toString
          (responseAs[JsObject] \ "feeAmount").as[Long] shouldEqual 5
        }
      }

      "with sponsorship, smart token and smart account" in {
        val assetId: IssuedAsset = IssuedAsset(issueGen.sample.get.assetId)
        val sender: PublicKey    = accountGen.sample.get.publicKey
        val transferTx = Json.obj(
          "type"            -> 4,
          "version"         -> 2,
          "amount"          -> 1000000,
          "feeAssetId"      -> assetId.id.toString,
          "senderPublicKey" -> Base58.encode(sender.arr),
          "recipient"       -> accountGen.sample.get.toAddress
        )

        (addressTransactions.calculateFee _).expects(*).returning(Right((assetId, 45L, 0L))).once()

        Post(routePath("/calculateFee"), transferTx) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          (responseAs[JsObject] \ "feeAssetId").as[String] shouldBe assetId.id.toString
          (responseAs[JsObject] \ "feeAmount").as[Long] shouldEqual 45
        }
      }
    }
  }

  routePath("/address/{address}/limit/{limit}") - {
    val bytes32StrGen = bytes32gen.map(Base58.encode)
    val addressGen    = accountGen.map(_.toAddress.toString)

    "returns lease tx for lease cancel tx" in {
      val lease       = TxHelpers.lease()
      val leaseCancel = TxHelpers.leaseCancel(lease.id())

      val blockchain = createBlockchainStub { blockchain =>
        (blockchain.transactionInfo _).when(lease.id()).returns(Some((1, lease, true)))
        (blockchain.transactionInfo _).when(leaseCancel.id()).returns(Some((1, leaseCancel, true)))
      }

      val transactionsApi = stub[CommonTransactionsApi]
      (transactionsApi.transactionById _).when(lease.id()).returns(Some(TransactionMeta.Default(Height(1), lease, succeeded = true)))
      (transactionsApi.transactionById _).when(leaseCancel.id()).returns(Some(TransactionMeta.Default(Height(1), leaseCancel, succeeded = true)))
      (transactionsApi.transactionsByAddress _)
        .when(TxHelpers.secondAddress, None, *, None)
        .returns(Observable(TransactionMeta.Default(Height(1), leaseCancel, succeeded = true)))
      (transactionsApi.aliasesOfAddress _).when(*).returns(Observable.empty)

      val route = transactionsApiRoute.copy(blockchain = blockchain, commonApi = transactionsApi).route
      Get(routePath(s"/address/${TxHelpers.secondAddress}/limit/10")) ~> route ~> check {
        val json = (responseAs[JsArray] \ 0 \ 0).as[JsObject]
        json shouldBe leaseCancel.json() ++ Json.obj("lease" -> lease.json(), "height" -> 1, "applicationStatus" -> "succeeded")
      }
    }

    "handles parameter errors with corresponding responses" - {
      "invalid address" in {
        forAll(bytes32StrGen) { badAddress =>
          Get(routePath(s"/address/$badAddress/limit/1")) ~> route should produce(InvalidAddress)
        }
      }

      "invalid limit" - {
        "limit is too big" in {
          forAll(addressGen, choose(MaxTransactionsPerRequest + 1, Int.MaxValue).label("limitExceeded")) {
            case (address, limit) =>
              Get(routePath(s"/address/$address/limit/$limit")) ~> route should produce(TooBigArrayAllocation)
          }
        }
      }

      "invalid after" in {
        forAll(addressGen, choose(1, MaxTransactionsPerRequest).label("limitCorrect"), invalidBase58Gen) {
          case (address, limit, invalidBase58) =>
            Get(routePath(s"/address/$address/limit/$limit?after=$invalidBase58")) ~> route ~> check {
              status shouldEqual StatusCodes.BadRequest
              (responseAs[JsObject] \ "message").as[String] shouldEqual s"Unable to decode transaction id $invalidBase58"
            }
        }
      }
    }

    "returns 200 if correct params provided" - {
      "address and limit" in {
        forAll(addressGen, choose(1, MaxTransactionsPerRequest).label("limitCorrect")) {
          case (address, limit) =>
            (addressTransactions.aliasesOfAddress _).expects(*).returning(Observable.empty).once()
            (addressTransactions.transactionsByAddress _).expects(*, *, *, None).returning(Observable.empty).once()
            Get(routePath(s"/address/$address/limit/$limit")) ~> route ~> check {
              status shouldEqual StatusCodes.OK
            }
        }
      }

      "address, limit and after" in {
        forAll(addressGen, choose(1, MaxTransactionsPerRequest).label("limitCorrect"), bytes32StrGen) {
          case (address, limit, txId) =>
            (addressTransactions.aliasesOfAddress _).expects(*).returning(Observable.empty).once()
            (addressTransactions.transactionsByAddress _).expects(*, *, *, *).returning(Observable.empty).once()
            Get(routePath(s"/address/$address/limit/$limit?after=$txId")) ~> route ~> check {
              status shouldEqual StatusCodes.OK
            }
        }
      }
    }

    "provides stateChanges" in forAll(accountGen) { account =>
      val transaction = TxHelpers.invoke(account.toAddress, "test")

      (() => blockchain.activatedFeatures).expects().returns(Map.empty).anyNumberOfTimes()
      (addressTransactions.aliasesOfAddress _).expects(*).returning(Observable.empty).once()
      (addressTransactions.transactionsByAddress _)
        .expects(account.toAddress, *, *, None)
        .returning(Observable(TransactionMeta.Invoke(Height(1), transaction, succeeded = true, Some(InvokeScriptResult()))))
        .once()

      Get(routePath(s"/address/${account.toAddress}/limit/1")) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        (responseAs[JsArray] \ 0 \ 0 \ "stateChanges").as[JsObject] shouldBe Json.toJsObject(InvokeScriptResult())
      }
    }
  }

  routePath("/info/{id}") - {
    "returns lease tx for lease cancel tx" in {
      val lease       = TxHelpers.lease()
      val leaseCancel = TxHelpers.leaseCancel(lease.id())

      val blockchain = createBlockchainStub { blockchain =>
        (blockchain.transactionInfo _).when(lease.id()).returns(Some((1, lease, true)))
        (blockchain.transactionInfo _).when(leaseCancel.id()).returns(Some((1, leaseCancel, true)))
      }

      val transactionsApi = stub[CommonTransactionsApi]
      (transactionsApi.transactionById _).when(lease.id()).returns(Some(TransactionMeta.Default(Height(1), lease, succeeded = true)))
      (transactionsApi.transactionById _).when(leaseCancel.id()).returns(Some(TransactionMeta.Default(Height(1), leaseCancel, succeeded = true)))

      val route = transactionsApiRoute.copy(blockchain = blockchain, commonApi = transactionsApi).route
      Get(routePath(s"/info/${leaseCancel.id()}")) ~> route ~> check {
        val json = responseAs[JsObject]
        json shouldBe leaseCancel.json() ++ Json.obj("lease" -> lease.json(), "height" -> 1, "applicationStatus" -> "succeeded")
      }
    }

    "handles invalid signature" in {
      forAll(invalidBase58Gen) { invalidBase58 =>
        Get(routePath(s"/info/$invalidBase58")) ~> route should produce(InvalidTransactionId("Wrong char"), matchMsg = true)
      }

      Get(routePath(s"/info/")) ~> route should produce(InvalidTransactionId("Transaction ID was not specified"))
      Get(routePath(s"/info")) ~> route should produce(InvalidTransactionId("Transaction ID was not specified"))
    }

    "working properly otherwise" in {
      val txAvailability = for {
        tx                           <- randomTransactionGen
        height                       <- posNum[Int]
        acceptFailedActivationHeight <- posNum[Int]
        succeed                      <- if (height >= acceptFailedActivationHeight) Arbitrary.arbBool.arbitrary else Gen.const(true)
      } yield (tx, succeed, height, acceptFailedActivationHeight)

      forAll(txAvailability) {
        case (tx, succeed, height, acceptFailedActivationHeight) =>
          (addressTransactions.transactionById _).expects(tx.id()).returning(Some(TransactionMeta.Default(Height(height), tx, succeed))).once()
          (() => blockchain.activatedFeatures)
            .expects()
            .returning(Map(BlockchainFeatures.BlockV5.id -> acceptFailedActivationHeight))
            .anyNumberOfTimes()

          def validateResponse(): Unit = {
            status shouldEqual StatusCodes.OK

            val extraFields =
              if (blockchain.isFeatureActivated(BlockchainFeatures.BlockV5, height))
                Json.obj("height"    -> height, "applicationStatus" -> JsString(if (succeed) "succeeded" else "script_execution_failed"))
              else Json.obj("height" -> height)
            responseAs[JsValue] shouldEqual (tx.json() ++ extraFields)
          }

          Get(routePath(s"/info/${tx.id().toString}")) ~> route ~> check(validateResponse())
      }
    }

    "provides stateChanges" in forAll(accountGen) { account =>
      val transaction = TxHelpers.invoke(account.toAddress, "test")

      (() => blockchain.activatedFeatures).expects().returns(Map.empty).anyNumberOfTimes()
      (addressTransactions.transactionById _)
        .expects(transaction.id())
        .returning(Some(TransactionMeta.Invoke(Height(1), transaction, succeeded = true, Some(InvokeScriptResult()))))
        .once()

      Get(routePath(s"/info/${transaction.id()}")) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        (responseAs[JsObject] \ "stateChanges").as[JsObject] shouldBe Json.toJsObject(InvokeScriptResult())
      }
    }

    "handles multiple ids" in {
      val txCount = 5
      val txs     = (1 to txCount).map(_ => TxHelpers.invoke(TxHelpers.defaultSigner.toAddress, "test"))
      txs.foreach(
        tx =>
          (addressTransactions.transactionById _)
            .expects(tx.id())
            .returns(Some(TransactionMeta.Invoke(Height(1), tx, succeeded = true, Some(InvokeScriptResult()))))
            .repeat(3)
      )

      (() => blockchain.activatedFeatures).expects().returns(Map(BlockchainFeatures.BlockV5.id -> 1)).anyNumberOfTimes()

      def checkResponse(): Unit = txs.zip(responseAs[JsArray].value) foreach {
        case (tx, json) =>
          val extraFields = Json.obj("height" -> 1, "applicationStatus" -> "succeeded", "stateChanges" -> InvokeScriptResult())
          json shouldBe (tx.json() ++ extraFields)
      }

      Get(routePath(s"/info?${txs.map("id=" + _.id()).mkString("&")}")) ~> route ~> check(checkResponse())
      Post(routePath("/info"), FormData(txs.map("id" -> _.id().toString): _*)) ~> route ~> check(checkResponse())
      Post(
        routePath("/info"),
        HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(txs.map(_.id().toString: JsValueWrapper): _*)).toString())
      ) ~> route ~> check(
        checkResponse()
      )
    }
  }

  routePath("/status/{signature}") - {
    "handles invalid signature" in {
      forAll(invalidBase58Gen) { invalidBase58 =>
        Get(routePath(s"/status?id=$invalidBase58")) ~> route should produce(InvalidIds(Seq(invalidBase58)))
      }
    }

    "handles empty request" in {
      Get(routePath(s"/status?")) ~> route should produce(CustomValidationError("Empty request"))
    }

    "working properly otherwise" in {
      val txAvailability = for {
        tx                           <- randomTransactionGen
        height                       <- Gen.chooseNum(1, 1000)
        acceptFailedActivationHeight <- Gen.chooseNum(1, 1000)
        succeed                      <- if (height >= acceptFailedActivationHeight) Arbitrary.arbBool.arbitrary else Gen.const(true)
      } yield (tx, height, acceptFailedActivationHeight, succeed)

      forAll(txAvailability) {
        case (tx, height, acceptFailedActivationHeight, succeed) =>
          (blockchain.transactionInfo _).expects(tx.id()).returning(Some((height, tx, succeed))).anyNumberOfTimes()
          (() => blockchain.height).expects().returning(1000).anyNumberOfTimes()
          (() => blockchain.activatedFeatures)
            .expects()
            .returning(Map(BlockchainFeatures.BlockV5.id -> acceptFailedActivationHeight))
            .anyNumberOfTimes()

          Get(routePath(s"/status?id=${tx.id().toString}&id=${tx.id().toString}")) ~> route ~> check {
            status shouldEqual StatusCodes.OK
            val obj = {
              val common = Json.obj(
                "id"            -> tx.id().toString,
                "status"        -> "confirmed",
                "height"        -> JsNumber(height),
                "confirmations" -> JsNumber(1000 - height)
              )
              val applicationStatus =
                if (blockchain.isFeatureActivated(BlockchainFeatures.BlockV5, height))
                  Json.obj("applicationStatus" -> JsString(if (succeed) "succeeded" else "script_execution_failed"))
                else Json.obj()
              common ++ applicationStatus
            }
            responseAs[JsValue] shouldEqual Json.arr(obj, obj)
          }
          Post(routePath("/status"), Json.obj("ids" -> Seq(tx.id().toString, tx.id().toString))) ~> route ~> check {
            status shouldEqual StatusCodes.OK
          }
      }
    }
  }

  routePath("/unconfirmed") - {
    "returns lease tx for lease cancel tx" in {
      val lease       = TxHelpers.lease()
      val leaseCancel = TxHelpers.leaseCancel(lease.id())

      val blockchain = createBlockchainStub { blockchain =>
        (blockchain.transactionInfo _).when(lease.id()).returns(Some((1, lease, true)))
        (blockchain.transactionInfo _).when(leaseCancel.id()).returns(Some((1, leaseCancel, true)))
      }

      val transactionsApi = stub[CommonTransactionsApi]
      (transactionsApi.transactionById _).when(lease.id()).returns(Some(TransactionMeta.Default(Height(1), lease, succeeded = true)))
      (transactionsApi.transactionById _).when(leaseCancel.id()).returns(Some(TransactionMeta.Default(Height(1), leaseCancel, succeeded = true)))
      (() => transactionsApi.unconfirmedTransactions).when().returns(Seq(leaseCancel))
      (transactionsApi.unconfirmedTransactionById _).when(leaseCancel.id()).returns(Some(leaseCancel))
      (transactionsApi.aliasesOfAddress _).when(*).returns(Observable.empty)

      val route = transactionsApiRoute.copy(blockchain = blockchain, commonApi = transactionsApi).route

      Get(routePath(s"/unconfirmed")) ~> route ~> check {
        val json = (responseAs[JsArray] \ 0).as[JsObject]
        json shouldBe leaseCancel.json() ++ Json.obj("lease" -> lease.json())
      }

      Get(routePath(s"/unconfirmed/info/${leaseCancel.id()}")) ~> route ~> check {
        val json = responseAs[JsObject]
        json shouldBe leaseCancel.json() ++ Json.obj("lease" -> lease.json())
      }
    }

    "returns the list of unconfirmed transactions" in {
      val g = for {
        i <- chooseNum(0, 20)
        t <- listOfN(i, randomTransactionGen)
      } yield t

      forAll(g) { txs =>
        (() => addressTransactions.unconfirmedTransactions).expects().returning(txs).once()
        Get(routePath("/unconfirmed")) ~> route ~> check {
          val resp = responseAs[Seq[JsValue]]
          for ((r, t) <- resp.zip(txs)) {
            if ((r \ "version").as[Int] == 1) {
              (r \ "signature").as[String] shouldEqual t.proofs.proofs.head.toString
            } else {
              (r \ "proofs").as[Seq[String]] shouldEqual t.proofs.proofs.map(_.toString)
            }
          }
        }
      }
    }
  }

  routePath("/unconfirmed/size") - {
    "returns the size of unconfirmed transactions" in {
      val g = for {
        i <- chooseNum(0, 20)
        t <- listOfN(i, randomTransactionGen)
      } yield t

      forAll(g) { txs =>
        utxPoolSize.expects().returning(txs.size).once()
        Get(routePath("/unconfirmed/size")) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[JsValue] shouldEqual Json.obj("size" -> JsNumber(txs.size))
        }
      }
    }
  }

  routePath("/unconfirmed/info/{id}") - {
    "handles invalid signature" in {
      forAll(invalidBase58Gen) { invalidBase58 =>
        Get(routePath(s"/unconfirmed/info/$invalidBase58")) ~> route should produce(InvalidTransactionId("Wrong char"), matchMsg = true)
      }

      Get(routePath(s"/unconfirmed/info/")) ~> route should produce(InvalidSignature)
      Get(routePath(s"/unconfirmed/info")) ~> route should produce(InvalidSignature)
    }

    "working properly otherwise" in {
      forAll(randomTransactionGen) { tx =>
        (addressTransactions.unconfirmedTransactionById _).expects(tx.id()).returns(Some(tx)).once()
        Get(routePath(s"/unconfirmed/info/${tx.id().toString}")) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[JsValue] shouldEqual tx.json()
        }
      }
    }
  }

  routePath("/sign") - {
    "function call without args" in {
      val acc1 = testWallet.generateNewAccount().get
      val acc2 = testWallet.generateNewAccount().get

      val funcName          = "func"
      val funcWithoutArgs   = Json.obj("function" -> funcName)
      val funcWithEmptyArgs = Json.obj("function" -> funcName, "args" -> JsArray.empty)
      val funcWithArgs = InvokeScriptTransaction.serializer.functionCallToJson(
        FUNCTION_CALL(
          FunctionHeader.User(funcName),
          List(CONST_LONG(1), CONST_BOOLEAN(true))
        )
      )

      def invoke(func: JsObject, expectedArgsLength: Int): Unit = {
        val ist = Json.obj(
          "type"       -> InvokeScriptTransaction.typeId,
          "version"    -> Gen.oneOf(InvokeScriptTransaction.supportedVersions.toSeq).sample.get,
          "sender"     -> acc1.toAddress,
          "dApp"       -> acc2.toAddress,
          "call"       -> func,
          "payment"    -> Seq[Payment](),
          "fee"        -> 500000,
          "feeAssetId" -> JsNull
        )
        Post(routePath("/sign"), ist) ~> ApiKeyHeader ~> route ~> check {
          status shouldEqual StatusCodes.OK
          val jsObject = responseAs[JsObject]
          (jsObject \ "senderPublicKey").as[String] shouldBe acc1.publicKey.toString
          (jsObject \ "call" \ "function").as[String] shouldBe funcName
          (jsObject \ "call" \ "args").as[JsArray].value.length shouldBe expectedArgsLength
        }
      }

      invoke(funcWithoutArgs, 0)
      invoke(funcWithEmptyArgs, 0)
      invoke(funcWithArgs, 2)
    }
  }

  routePath("/broadcast") - {
    def withInvokeScriptTransaction(f: (KeyPair, InvokeScriptTransaction) => Unit): Unit = {
      val seed = new Array[Byte](32)
      Random.nextBytes(seed)
      val sender: KeyPair = KeyPair(seed)
      val ist = InvokeScriptTransaction(
        TxVersion.V1,
        sender.publicKey,
        sender.toAddress,
        None,
        Seq.empty,
        500000L,
        Asset.Waves,
        testTime.getTimestamp(),
        Proofs.empty,
        AddressScheme.current.chainId
      ).signWith(sender.privateKey)
      f(sender, ist)
    }

    "shows trace when trace is enabled" in withInvokeScriptTransaction { (sender, ist) =>
      val accountTrace = AccountVerifierTrace(sender.toAddress, Some(GenericError("Error in account script")))
      (utxPoolSynchronizer.validateAndBroadcast _)
        .expects(*, None)
        .returning(
          Future.successful(TracedResult(Right(true), List(accountTrace)))
        )
        .once()
      Post(routePath("/broadcast?trace=true"), ist.json()) ~> route ~> check {
        val result = responseAs[JsObject]
        (result \ "trace").as[JsValue] shouldBe Json.arr(accountTrace.json)
      }
    }

    "does not show trace when trace is disabled" in withInvokeScriptTransaction { (sender, ist) =>
      val accountTrace = AccountVerifierTrace(sender.toAddress, Some(GenericError("Error in account script")))
      (utxPoolSynchronizer.validateAndBroadcast _)
        .expects(*, None)
        .returning(
          Future.successful(TracedResult(Right(true), List(accountTrace)))
        )
        .twice()
      Post(routePath("/broadcast"), ist.json()) ~> route ~> check {
        (responseAs[JsObject] \ "trace") shouldBe empty
      }
      Post(routePath("/broadcast?trace=false"), ist.json()) ~> route ~> check {
        (responseAs[JsObject] \ "trace") shouldBe empty
      }
    }

    "generates valid trace with vars" in {
      val blockchain = createBlockchainStub { blockchain =>
        val (dAppScript, _) = ScriptCompiler
          .compile(
            s"""
               |{-# STDLIB_VERSION 4 #-}
               |{-# SCRIPT_TYPE ACCOUNT #-}
               |{-# CONTENT_TYPE DAPP #-}
               |
               |@Callable(i)
               |func test() = {
               |  let test = 1
               |  if (test == 1) then [] else []
               |}
               |""".stripMargin,
            ScriptEstimatorV3
          )
          .explicitGet()

        (blockchain.accountScript _)
          .when(*)
          .returns(
            Some(
              AccountScriptInfo(
                TxHelpers.defaultSigner.publicKey,
                dAppScript,
                0L,
                Map(3 -> Seq("test").map(_ -> 0L).toMap)
              )
            )
          )

        (blockchain.hasAccountScript _).when(*).returns(true)
      }
      val publisher = createTxPublisherStub(blockchain)
      val route     = transactionsApiRoute.copy(blockchain = blockchain, transactionPublisher = publisher).route

      val tx = TxHelpers.invoke(TxHelpers.defaultAddress, "test")
      Post(routePath("/broadcast?trace=true"), tx.json()) ~> route ~> check {
        responseAs[JsObject] shouldBe Json.parse(
          s"""{
            |  "type" : 16,
            |  "id" : "${tx.id()}",
            |  "sender" : "3MtGzgmNa5fMjGCcPi5nqMTdtZkfojyWHL9",
            |  "senderPublicKey" : "9BUoYQYq7K38mkk61q8aMH9kD9fKSVL1Fib7FbH6nUkQ",
            |  "fee" : 1000000,
            |  "feeAssetId" : null,
            |  "timestamp" : ${tx.timestamp},
            |  "proofs" : [ "${tx.signature}" ],
            |  "version" : 1,
            |  "dApp" : "3MtGzgmNa5fMjGCcPi5nqMTdtZkfojyWHL9",
            |  "payment" : [ ],
            |  "call" : {
            |    "function" : "test",
            |    "args" : [ ]
            |  },
            |  "trace" : [ {
            |    "type" : "verifier",
            |    "id" : "3MtGzgmNa5fMjGCcPi5nqMTdtZkfojyWHL9",
            |    "result" : "success",
            |    "error" : null
            |  }, {
            |    "type" : "dApp",
            |    "id" : "3MtGzgmNa5fMjGCcPi5nqMTdtZkfojyWHL9",
            |    "function" : "test",
            |    "args" : [ ],
            |    "result" : {
            |      "data" : [ ],
            |      "transfers" : [ ],
            |      "issues" : [ ],
            |      "reissues" : [ ],
            |      "burns" : [ ],
            |      "sponsorFees" : [ ]
            |    },
            |    "error" : null,
            |    "vars" : [ {
            |      "name" : "test",
            |      "type" : "Int",
            |      "value" : 1
            |    } ]
            |  } ]
            |}""".stripMargin
        )
      }
    }
  }

  routePath("/merkleProof") - {
    val transactionsGen = for {
      txsSize <- Gen.choose(1, 10)
      txs     <- Gen.listOfN(txsSize, randomTransactionGen)
    } yield txs

    val invalidBlockGen = for {
      txs     <- transactionsGen
      signer  <- accountGen
      version <- Gen.choose(Block.GenesisBlockVersion, Block.RewardBlockVersion)
      block   <- versionedBlockGen(txs, signer, version)
    } yield block

    val invalidBlocksGen =
      for {
        blockchainHeight <- Gen.choose(1, 10)
        blocks           <- Gen.listOfN(blockchainHeight, invalidBlockGen)
      } yield blocks

    val merkleProofs = for {
      index        <- Gen.choose(0, 50)
      tx           <- randomTransactionGen
      proofsLength <- Gen.choose(1, 5)
      proofBytes   <- Gen.listOfN(proofsLength, bytes32gen)
    } yield (tx, TransactionProof(tx.id(), index, proofBytes))

    def validateSuccess(expectedProofs: Seq[TransactionProof], response: HttpResponse): Unit = {
      response.status shouldBe StatusCodes.OK

      val proofs = responseAs[List[JsObject]]

      proofs.size shouldBe expectedProofs.size

      proofs.zip(expectedProofs).foreach {
        case (p, e) =>
          val transactionId    = (p \ "id").as[String]
          val transactionIndex = (p \ "transactionIndex").as[Int]
          val digests          = (p \ "merkleProof").as[List[String]].map(s => ByteStr.decodeBase58(s).get)

          transactionId shouldEqual e.id.toString
          transactionIndex shouldEqual e.transactionIndex
          digests shouldEqual e.digests.map(ByteStr(_))
      }
    }

    def validateFailure(response: HttpResponse): Unit = {
      response.status shouldEqual StatusCodes.BadRequest
      (responseAs[JsObject] \ "message").as[String] shouldEqual s"transactions do not exist or block version < ${Block.ProtoBlockVersion}"
    }

    "returns merkle proofs" in {
      forAll(Gen.choose(10, 20).flatMap(n => Gen.listOfN(n, merkleProofs))) { transactionsAndProofs =>
        val (transactions, proofs) = transactionsAndProofs.unzip
        (addressTransactions.transactionProofs _).expects(transactions.map(_.id())).returning(proofs).twice()

        val queryParams = transactions.map(t => s"id=${t.id()}").mkString("?", "&", "")
        val requestBody = Json.obj("ids" -> transactions.map(_.id().toString))

        Get(routePath(s"/merkleProof$queryParams")) ~> route ~> check {
          validateSuccess(proofs, response)
        }

        Post(routePath("/merkleProof"), requestBody) ~> route ~> check {
          validateSuccess(proofs, response)
        }
      }
    }

    "returns error in case of all transactions are filtered" in {
      forAll(invalidBlocksGen) { blocks =>
        val txIdsToBlock = blocks.flatMap(b => b.transactionData.map(tx => (tx.id().toString, b))).toMap

        val queryParams = txIdsToBlock.keySet.map(id => s"id=$id").mkString("?", "&", "")
        val requestBody = Json.obj("ids" -> txIdsToBlock.keySet)

        (addressTransactions.transactionProofs _).expects(*).returning(Nil).anyNumberOfTimes()

        Get(routePath(s"/merkleProof$queryParams")) ~> route ~> check {
          validateFailure(response)
        }

        Post(routePath("/merkleProof"), requestBody) ~> route ~> check {
          validateFailure(response)
        }
      }
    }

    "handles invalid signatures" in {
      val invalidIdsGen = for {
        ids       <- Gen.nonEmptyListOf(randomTransactionGen.map(_.id().toString))
        invalidId <- Gen.nonEmptyListOf(invalidBase58Gen)
      } yield Random.shuffle(ids ++ invalidId)

      forAll(invalidIdsGen) { invalidIds =>
        val queryParams = invalidIds.map(id => s"id=$id").mkString("?", "&", "")
        val requestBody = Json.obj("ids" -> invalidIds)

        Get(routePath(s"/merkleProof$queryParams")) ~> route should produce(InvalidSignature)

        Post(routePath("/merkleProof"), requestBody) ~> route should produce(InvalidSignature)
      }
    }
  }
}
