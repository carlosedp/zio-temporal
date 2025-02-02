package com.example.payments

import com.example.transactions._
import com.example.payments.impl._
import com.example.payments.workflows._
import zio._
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.worker.ZWorker
import zio.temporal.worker.ZWorkerFactory
import zio.temporal.worker.ZWorkerFactoryOptions
import zio.temporal.workflow.ZWorkflowClientOptions
import zio.temporal.workflow.ZWorkflowServiceStubsOptions

object ExampleModule {
  val stubOptions: ULayer[ZWorkflowServiceStubsOptions] = ZLayer.succeed {
    ZWorkflowServiceStubsOptions.default
  }

  val clientOptions: ULayer[ZWorkflowClientOptions] = ZLayer.succeed {
    ZWorkflowClientOptions.default.withDataConverter(
      ProtobufDataConverter.makeAutoLoad()
    )
  }

  val workerFactoryOptions: ULayer[ZWorkerFactoryOptions] = ZLayer.succeed {
    ZWorkerFactoryOptions.default
  }

  val worker: URLayer[PaymentActivity with ZWorkerFactory, Unit] =
    ZLayer.fromZIO {
      ZIO.serviceWithZIO[ZWorkerFactory] { workerFactory =>
        for {
          worker       <- workerFactory.newWorker("payments")
          activityImpl <- ZIO.service[PaymentActivity]
          _ = worker.addActivityImplementation(activityImpl)
          _ = worker.addWorkflow[PaymentWorkflow].from(new PaymentWorkflowImpl)
        } yield ()
      }
    }
}
