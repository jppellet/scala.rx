package rx

import rx.Flow.{Emitter, Signal}
import util.{DynamicVariable, Failure, Try}
import java.util.concurrent.atomic.AtomicReference
import annotation.tailrec
import scala.concurrent.stm._

/**
 * A collection of Signals that update immediately when pinged. These should
 * generally not be created directly; instead use the alias Rx in the package
 * to construct DynamicSignals, and the extension methods defined in Combinators
 * to build SyncSignals from other Rxs.
 */
object SyncSignals {
  object DynamicSignal{
    /**
     * Provides a nice wrapper to use to create DynamicSignals
     */
    def apply[T](calc: => T)(implicit name: String = ""): DynamicSignal[T] = {
      new DynamicSignal(name, () => calc)
    }

    private[rx] val enclosing = new DynamicVariable[Option[(DynamicSignal[Any], InTxn)]](None)
  }

  /**
   * A DynamicSignal is a signal that is defined relative to other signals, and
   * updates automatically when they change.
   *
   * Note that while the propagation tries to minimize the number of times a
   * DynamicSignal needs to be recalculated, there is always going to be some
   * redundant recalculation. Since this is unpredictable, the body of a
   * DynamicSignal should always be side-effect free
   *
   * @param calc The method of calculating the future of this DynamicSignal
   * @tparam T The type of the future this contains
   */
  class DynamicSignal[+T](val name: String, calc: () => T) extends Flow.Signal[T] with Flow.Reactor[Any]{

    @volatile var active = true

    private[this] val parents   = Ref[Seq[Flow.Emitter[Any]]](Nil)
    private[this] val levelRef  = Ref(0L)
    private[this] val timeStamp = Ref(System.nanoTime())
    private[this] val value     = Ref(fullCalc())

    def fullCalc(): Try[T] = {
      atomic{ txn =>
        DynamicSignal.enclosing.withValue(Some(this -> txn)){
          Try(calc())
        }
      }
    }

    def getParents = parents.single()

    def ping(incoming: Seq[Flow.Emitter[Any]]) = {
      if (active && getParents.intersect(incoming).isDefinedAt(0)){

        val newValue = fullCalc()

        atomic{ implicit txn =>
          if (value() != newValue){
            value() = newValue
           getChildren
          }else Nil
        }
      }else {
        Nil
      }
    }

    def toTry = value.single()

    def level = levelRef.single()

    def addParent(e: Emitter[Any]) = {
      parents.single.transform(_ :+ e)
    }

    def incrementLevel(l: Long) = {
      levelRef.single.transform(old => math.max(l, old))
    }
  }

  abstract class WrapSignal[T, A](source: Signal[T], prefix: String) extends Signal[A] with Flow.Reactor[Any]{
    source.linkChild(this)
    def level = source.level + 1
    def getParents = Seq(source)
    def name = prefix + " " + source.name
  }
  class FilterSignal[T](source: Signal[T])(transformer: (Try[T], Try[T]) => Try[T])
    extends WrapSignal[T, T](source, "FilterSignal"){

    private[this] val lastResult = Ref(transformer(Failure(null), source.toTry))

    def toTry = lastResult.single()

    def ping(incoming: Seq[Flow.Emitter[Any]]) = {
      val newTime = System.nanoTime()
      val newValue = transformer(lastResult.single(), source.toTry)

      atomic{ implicit txn =>
        if (lastResult() == newValue) Nil
        else {
          lastResult() = newValue
          getChildren
        }
      }
    }
  }
  class MapSignal[T, A](source: Signal[T])(transformer: Try[T] => Try[A])
    extends WrapSignal[T, A](source, "MapSignal"){

    private[this] val lastValue = Ref(transformer(source.toTry))
    private[this] val lastTime = Ref(System.nanoTime())

    def toTry = lastValue.single()
    def ping(incoming: Seq[Flow.Emitter[Any]]) = {
      val newTime = System.nanoTime()
      val newValue = transformer(source.toTry)
      atomic{ implicit txn =>
        if (newTime > lastTime()){
          lastValue() = newValue
          lastTime() = newTime
          getChildren
        }else Nil
      }
    }
  }


}
