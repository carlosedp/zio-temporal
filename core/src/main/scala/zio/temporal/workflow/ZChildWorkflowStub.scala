package zio.temporal.workflow

import io.temporal.workflow.ChildWorkflowStub
import zio.temporal.internal.ClassTagUtils
import zio.temporal.internal.Stubs
import zio.temporal.{ZWorkflowExecution, internalApi}
import zio.temporal.query.ZWorkflowStubQuerySyntax
import zio.temporal.signal.ZChildWorkflowStubSignalSyntax
import scala.reflect.ClassTag

/** Represents untyped child workflow stub
  *
  * @see
  *   [[ChildWorkflowStub]]
  */
sealed trait ZChildWorkflowStub {
  def toJava: ChildWorkflowStub

  def untyped: ZChildWorkflowStub.Untyped

  /** If workflow completes before this promise is ready then the child might not start at all.
    *
    * @return
    *   [[ZAsync]] that becomes ready once the child has started.
    */
  def getExecution: ZAsync[ZWorkflowExecution] =
    untyped.getExecution
}

final class ZChildWorkflowStubImpl @internalApi() (val toJava: ChildWorkflowStub) extends ZChildWorkflowStub {
  override val untyped: ZChildWorkflowStub.Untyped = new ZChildWorkflowStub.UntypedImpl(toJava)
}

object ZChildWorkflowStub
    extends Stubs[ZChildWorkflowStub]
    with ZChildWorkflowExecutionSyntax
    with ZWorkflowStubQuerySyntax
    with ZChildWorkflowStubSignalSyntax {

  /** An untyped version of [[ZChildWorkflowStub]]
    */
  sealed trait Untyped {
    def toJava: ChildWorkflowStub

    /** If workflow completes before this promise is ready then the child might not start at all.
      *
      * @return
      *   [[ZAsync]] that becomes ready once the child has started.
      */
    def getExecution: ZAsync[ZWorkflowExecution]

    def execute[R: ClassTag](args: Any*): R

    def executeAsync[R: ClassTag](args: Any*): ZAsync[R]

    def signal(signalName: String, args: Any*): Unit

  }

  private[temporal] final class UntypedImpl(val toJava: ChildWorkflowStub) extends Untyped {

    override def getExecution: ZAsync[ZWorkflowExecution] =
      ZAsync
        .fromJava(toJava.getExecution)
        .map(new ZWorkflowExecution(_))

    override def execute[R: ClassTag](args: Any*): R =
      toJava.execute[R](ClassTagUtils.classOf[R], args.asInstanceOf[Seq[AnyRef]]: _*)

    override def executeAsync[R: ClassTag](args: Any*): ZAsync[R] =
      ZAsync.fromJava[R](
        toJava.executeAsync[R](ClassTagUtils.classOf[R], args.asInstanceOf[Seq[AnyRef]]: _*)
      )

    override def signal(signalName: String, args: Any*): Unit =
      toJava.signal(signalName, args.asInstanceOf[Seq[AnyRef]]: _*)
  }

  final implicit class Ops[A](private val self: ZChildWorkflowStub.Of[A]) extends AnyVal {}
}
