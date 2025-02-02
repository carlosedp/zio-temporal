package zio.temporal

import zio.*
import zio.temporal.activity.ZActivityOptions
import zio.temporal.fixture.Done
import zio.temporal.fixture.SagaWorkflow
import zio.temporal.fixture.SagaWorkflowImpl
import zio.temporal.fixture.*
import zio.temporal.internal.TemporalWorkflowFacade
import zio.temporal.signal.*
import zio.temporal.testkit.ZTestEnvironmentOptions
import zio.temporal.testkit.ZTestWorkflowEnvironment
import zio.temporal.worker.ZWorkerOptions
import zio.temporal.workflow.*
import zio.test.*
import zio.test.TestAspect.*

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer

object WorkflowSpec extends ZIOSpecDefault {

  override def spec = suite("ZWorkflow")(
    test("runs simple workflow") {
      ZIO.serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
        val taskQueue = "sample-queue"
        val sampleIn  = "Fooo"
        val sampleOut = sampleIn
        val client    = testEnv.workflowClient

        testEnv
          .newWorker(taskQueue, options = ZWorkerOptions.default)
          .addWorkflow[SampleWorkflowImpl]
          .fromClass

        withWorkflow {
          testEnv.use() {
            for {
              sampleWorkflow <- client
                                  .newWorkflowStub[SampleWorkflow]
                                  .withTaskQueue(taskQueue)
                                  .withWorkflowId(UUID.randomUUID().toString)
                                  .withWorkflowRunTimeout(10.second)
                                  .build
              result <- ZWorkflowStub.execute(sampleWorkflow.echo(sampleIn))
            } yield assertTrue(result == sampleOut)
          }
        }
      }
    }.provideEnv,
    test("runs child workflows") {
      ZIO.serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
        val taskQueue = "child-queue"
        val client    = testEnv.workflowClient

        testEnv
          .newWorker(taskQueue, options = ZWorkerOptions.default)
          .addWorkflow[GreetingWorkflowImpl]
          .fromClass
          .addWorkflow[GreetingChildImpl]
          .fromClass

        withWorkflow {
          testEnv.use() {
            for {
              greetingWorkflow <- client
                                    .newWorkflowStub[GreetingWorkflow]
                                    .withTaskQueue(taskQueue)
                                    .withWorkflowId(UUID.randomUUID().toString)
                                    .withWorkflowRunTimeout(10.second)
                                    .build
              result <- ZWorkflowStub.execute(greetingWorkflow.getGreeting("Vitalii"))
            } yield assertTrue(result == "Hello Vitalii!")
          }
        }
      }
    }.provideEnv,
    test("run workflow with signals") {
      ZIO
        .serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
          val taskQueue = "signal-queue"
          val client    = testEnv.workflowClient

          testEnv
            .newWorker(taskQueue, options = ZWorkerOptions.default)
            .addWorkflow[SignalWorkflowImpl]
            .fromClass

          val workflowId = UUID.randomUUID().toString + taskQueue

          withWorkflow {
            testEnv.use() {
              for {
                signalWorkflow <- client
                                    .newWorkflowStub[SignalWorkflow]
                                    .withTaskQueue(taskQueue)
                                    .withWorkflowId(workflowId)
                                    .withWorkflowRunTimeout(30.seconds)
                                    .withWorkflowTaskTimeout(30.seconds)
                                    .withWorkflowExecutionTimeout(30.seconds)
                                    .build
                _ <- ZIO.log("Before start")
                _ <- ZWorkflowStub
                       .start(signalWorkflow.echoServer("ECHO"))
                _               <- ZIO.log("Started")
                workflowStub    <- client.newWorkflowStubProxy[SignalWorkflow](workflowId)
                _               <- ZIO.log("New stub created!")
                progress        <- ZWorkflowStub.query(workflowStub.getProgress(default = None))
                _               <- ZIO.log(s"Progress=$progress")
                progressDefault <- ZWorkflowStub.query(workflowStub.getProgress(default = Some("default")))
                _               <- ZIO.log(s"Progress_default=$progressDefault")
                _ <- ZWorkflowStub.signal(
                       workflowStub.echo("Hello!")
                     )
                progress2 <- ZWorkflowStub
                               .query(workflowStub.getProgress(default = None))
                               .repeatWhile(_.isEmpty)
                _      <- ZIO.log(s"Progress2=$progress2")
                result <- workflowStub.result[String]
              } yield assertTrue(progress.isEmpty) &&
                assertTrue(progressDefault.contains("default")) &&
                assertTrue(progress2.contains("Hello!")) &&
                assertTrue(result == "ECHO Hello!")
            }
          }
        }
    }.provideEnv,
    test("run workflow with ZIO") {
      ZIO
        .serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
          val taskQueue = "zio-queue"
          val client    = testEnv.workflowClient
          import testEnv.activityOptions

          testEnv
            .newWorker(taskQueue, options = ZWorkerOptions.default)
            .addWorkflow[ZioWorkflowImpl]
            .fromClass
            .addActivityImplementation(new ZioActivityImpl())

          val workflowId = UUID.randomUUID().toString + taskQueue

          withWorkflow {
            testEnv.use() {
              for {
                zioWorkflow <- client
                                 .newWorkflowStub[ZioWorkflow]
                                 .withTaskQueue(taskQueue)
                                 .withWorkflowId(workflowId)
                                 .withWorkflowRunTimeout(30.seconds)
                                 .withWorkflowTaskTimeout(30.seconds)
                                 .withWorkflowExecutionTimeout(30.seconds)
                                 .build
                _ <- ZWorkflowStub
                       .start(zioWorkflow.echo("HELLO THERE"))
                workflowStub <- client.newWorkflowStubProxy[ZioWorkflow](workflowId)
                _ <- ZWorkflowStub.signal(
                       workflowStub.complete()
                     )
                result <- workflowStub.result[String]
              } yield assertTrue(result == "Echoed HELLO THERE")
            }
          }
        }
    }.provideEnv,
    test("run workflow with signal and start") {
      ZIO.serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
        val taskQueue = "signal-with-start-queue"
        val client    = testEnv.workflowClient

        testEnv
          .newWorker(taskQueue, options = ZWorkerOptions.default)
          .addWorkflow[SignalWithStartWorkflowImpl]
          .fromClass

        val workflowId = UUID.randomUUID().toString

        withWorkflow {
          testEnv.use() {
            for {
              signalWithStartWorkflow <- client
                                           .newWorkflowStub[SignalWithStartWorkflow]
                                           .withTaskQueue(taskQueue)
                                           .withWorkflowId(workflowId)
                                           .withWorkflowRunTimeout(10.seconds)
                                           .build
              _ <- client
                     .signalWith(
                       signalWithStartWorkflow.echo("Hello")
                     )
                     .start(signalWithStartWorkflow.echoServer())
              workflowStub <- client.newWorkflowStubProxy[SignalWithStartWorkflow](workflowId)
              initialSnapshot <- ZWorkflowStub.query(
                                   workflowStub.messages
                                 )
              _ <- ZWorkflowStub.signal(
                     workflowStub.echo("World!")
                   )
              secondSnapshot <- ZWorkflowStub.query(
                                  workflowStub.messages
                                )
              _ <- ZWorkflowStub.signal(
                     workflowStub.echo("Again...")
                   )
              thirdSnapshot <- ZWorkflowStub.query(
                                 workflowStub.messages
                               )
              _ <- ZWorkflowStub.signal(
                     workflowStub.stop()
                   )
              result <- workflowStub.result[Int]
            } yield assertTrue(initialSnapshot == List("Hello")) &&
              assertTrue(secondSnapshot == List("Hello", "World!")) &&
              assertTrue(thirdSnapshot == List("Hello", "World!", "Again...")) &&
              assertTrue(result == 3)
          }
        }
      }
    }.provideEnv @@ TestAspect.flaky,
    test("run workflow with successful sagas") {
      ZIO.serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
        val taskQueue = "saga-queue"
        val client    = testEnv.workflowClient

        val successFunc = (_: String, _: BigDecimal) => Right(Done())

        val expectedResult = BigDecimal(5.0)

        testEnv
          .newWorker(taskQueue, options = ZWorkerOptions.default)
          .addWorkflow[SagaWorkflowImpl]
          .fromClass
          .addActivityImplementation(new TransferActivityImpl(successFunc, successFunc))

        val workflowId = UUID.randomUUID().toString

        withWorkflow {
          testEnv.use() {
            for {
              signalWorkflow <- client
                                  .newWorkflowStub[SagaWorkflow]
                                  .withTaskQueue(taskQueue)
                                  .withWorkflowId(workflowId)
                                  .withWorkflowRunTimeout(10.seconds)
                                  .build
              result <- ZWorkflowStub.execute(signalWorkflow.transfer(TransferCommand("from", "to", expectedResult)))
            } yield assertTrue(result == expectedResult)
          }
        }
      }
    }.provideEnv,
    test("run workflow with saga compensations") {
      ZIO.serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
        val taskQueue = "saga-queue"
        val client    = testEnv.workflowClient
        val From      = "from"
        val To        = "to"

        val compensated  = new AtomicBoolean(false)
        val error        = Error("fraud")
        val withdrawFunc = (_: String, _: BigDecimal) => Right(Done())
        val depositFunc: (String, BigDecimal) => Either[fixture.Error, Done] = {
          case (From, _) =>
            compensated.set(true)
            Right(Done())
          case _ =>
            Left(error)
        }

        val amount = BigDecimal(5.0)

        testEnv
          .newWorker(taskQueue, options = ZWorkerOptions.default)
          .addWorkflow[SagaWorkflowImpl]
          .fromClass
          .addActivityImplementation(new TransferActivityImpl(depositFunc, withdrawFunc))

        val workflowId = UUID.randomUUID().toString

        withWorkflow {
          testEnv.use() {
            for {
              sagaWorkflow <- client
                                .newWorkflowStub[SagaWorkflow]
                                .withTaskQueue(taskQueue)
                                .withWorkflowId(workflowId)
                                .withWorkflowRunTimeout(10.seconds)
                                .build
              result <- ZWorkflowStub
                          .execute(sagaWorkflow.transfer(TransferCommand(From, To, amount)))
                          .either
            } yield {
              val actualError = result.left.getOrElse(???).getError.get
              assertTrue(actualError == error) &&
              assertTrue(compensated.get())
            }
          }
        }
      }
    }.provideEnv,
    test("run workflow with successful sagas (activities throws)") {
      ZIO.serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
        val taskQueue = "saga-exceptional-queue"
        val client    = testEnv.workflowClient

        val successFunc = (_: String, _: BigDecimal) => Done()

        val expectedResult = BigDecimal(5.0)

        testEnv
          .newWorker(taskQueue, options = ZWorkerOptions.default)
          .addWorkflow[SagaExceptionalWorkflowImpl]
          .fromClass
          .addActivityImplementation(new TransferExceptionalActivityImpl(successFunc, successFunc))

        val workflowId = UUID.randomUUID().toString

        withWorkflow {
          testEnv.use() {
            for {
              signalWorkflow <- client
                                  .newWorkflowStub[SagaExceptionalWorkflow]
                                  .withTaskQueue(taskQueue)
                                  .withWorkflowId(workflowId)
                                  .withWorkflowRunTimeout(10.seconds)
                                  .build
              result <- ZWorkflowStub.execute(signalWorkflow.transfer(TransferCommand("from", "to", expectedResult)))
            } yield assertTrue(result == expectedResult)
          }
        }
      }
    }.provideEnv,
    test("run workflow with saga compensations (activities throw)") {
      ZIO.serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
        val taskQueue = "saga-exceptional-queue"
        val client    = testEnv.workflowClient
        val From      = "from"
        val To        = "to"

        val compensated  = new AtomicBoolean(false)
        val withdrawFunc = (_: String, _: BigDecimal) => Done()
        class SagaTestException extends Exception("Saga test exception")
        val depositFunc: (String, BigDecimal) => Done = {
          case (From, _) =>
            compensated.set(true)
            Done()
          case _ =>
            throw new SagaTestException
        }

        val amount = BigDecimal(5.0)

        testEnv
          .newWorker(taskQueue, options = ZWorkerOptions.default)
          .addWorkflow[SagaExceptionalWorkflowImpl]
          .fromClass
          .addActivityImplementation(new TransferExceptionalActivityImpl(depositFunc, withdrawFunc))

        val workflowId = UUID.randomUUID().toString

        withWorkflow {
          testEnv.use() {
            for {
              sagaWorkflow <- client
                                .newWorkflowStub[SagaExceptionalWorkflow]
                                .withTaskQueue(taskQueue)
                                .withWorkflowId(workflowId)
                                .withWorkflowRunTimeout(10.seconds)
                                .build
              result <- ZWorkflowStub
                          .execute(sagaWorkflow.transfer(TransferCommand(From, To, amount)))
                          .either
            } yield {
              val actualError = result.left.getOrElse(???)
              assertTrue(actualError.isInstanceOf[TemporalClientError]) &&
              assertTrue(
                actualError
                  .asInstanceOf[TemporalClientError]
                  .error
                  .isInstanceOf[io.temporal.client.WorkflowFailedException]
              ) &&
              assertTrue(compensated.get())
            }
          }
        }
      }
    }.provideEnv,
    test("run workflow with promise") {
      ZIO.serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
        val taskQueue = "promise-queue"
        val client    = testEnv.workflowClient

        val order = new AtomicReference(ListBuffer.empty[(String, Int)])
        val fooFunc = (x: Int) => {
          println(s"foo($x)")
          order.get += ("foo" -> x)
          x
        }
        val barFunc = (x: Int) => {
          println(s"bar($x)")
          order.get += ("bar" -> x)
          x
        }

        testEnv
          .newWorker(taskQueue, options = ZWorkerOptions.default)
          .addWorkflow[PromiseWorkflowImpl]
          .fromClass
          .addActivityImplementation(new PromiseActivityImpl(fooFunc, barFunc))

        val x = 2
        val y = 3

        withWorkflow {
          testEnv.use() {
            val tests = for {
              workflowId <- ZIO.randomWith(_.nextUUID)
              promiseWorkflow <- client
                                   .newWorkflowStub[PromiseWorkflow]
                                   .withTaskQueue(taskQueue)
                                   .withWorkflowId(workflowId.toString)
                                   .withWorkflowRunTimeout(10.seconds)
                                   .build

              result <- ZWorkflowStub.execute(promiseWorkflow.fooBar(x, y))
            } yield assertTrue(result == x + y)

            tests.repeatN(19).map { res =>
              val actualResult = order.get.toList

              println(actualResult.toString)

              res &&
              assertTrue(actualResult.size == 40) &&
              assertTrue(actualResult != List.fill(20)(List("foo" -> x, "bar" -> y)))
            }
          }
        }
      }
    }.provideEnv @@ TestAspect.flaky
  )

  private def withWorkflow[R, E, A](f: ZIO[R, TemporalError[E], A]): RIO[R, A] =
    f.mapError(e => new RuntimeException(s"IO failed with $e"))

  private implicit class ProvidedTestkit[E, A](thunk: Spec[ZTestWorkflowEnvironment[Any], E]) {
    def provideEnv: Spec[Any, E] =
      thunk.provide(
        ZLayer.succeed(ZTestEnvironmentOptions.default),
        ZTestWorkflowEnvironment.make[Any]
      )
  }
}
