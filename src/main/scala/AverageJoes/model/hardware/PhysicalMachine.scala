package AverageJoes.model.hardware

import AverageJoes.common.{LogManager, LoggableMsgFromTo, MachineTypes, NonLoggableMsg}
import AverageJoes.model.fitness.ExecutionValues
import AverageJoes.model.hardware.PhysicalMachine.Msg.HeartRate
import AverageJoes.model.machine.MachineActor
import AverageJoes.model.workout.{MachineParameters, MachineParametersBySet, MachineParametersByTime}
import AverageJoes.utils.SafePropertyValue.NonNegative.NonNegInt
import AverageJoes.view.ViewToolActor
import AverageJoes.view.ViewToolActor.ViewPhysicalMachineActor
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import scala.collection.mutable.ListBuffer
import scala.util.Random
import java.util.Date

sealed trait PhysicalMachine extends AbstractBehavior[PhysicalMachine.Msg]{
  import PhysicalMachine._
  def machineID: String
  val machineLabel: MachineLabel //To show on device
  val machineType: MachineTypes.MachineType

  private val logName: String = PhysicalMachine.logName+"_"+machineID //ToDo: mettere private anche nelle altre classi

  val machineGui = context.spawn[ViewToolActor.Msg](ViewPhysicalMachineActor(machineID, machineLabel, context.self) , "M_GUI_"+machineID)

  override def onMessage(msg: Msg): Behavior[Msg] = {
    LogManager.logBehaviourChange(logName,"onMessage")
    Behaviors.receiveMessagePartial {
      case m: Msg.MachineActorStarted =>
        m.refMA ! MachineActor.Msg.GoIdle(machineID)
        display(machineLabel+" OnLine")
        operative(m.refMA)
    }
  }

  private def operative(ma: ActorRef[MachineActor.Msg]): Behavior[Msg] = {
    LogManager.logBehaviourChange(logName,"operative")
    Behaviors.receiveMessagePartial {
      case m: Msg.Rfid => ma ! MachineActor.Msg.UserLogIn(m.customerID, machineLabel); Behaviors.same
      case m: Msg.Display => display(machineLabel + " " + m.message); Behaviors.same
      case m: Msg.ConfigMachine => configure(m.customerID, m.machineParameters); inExercise(ma, m.customerID, m.machineParameters)
    }

  }

  private case object TimerKey
  private def inExercise(ma: ActorRef[MachineActor.Msg], customerID: String, machineParameters: MachineParameters): Behavior[Msg] = Behaviors.withTimers[Msg]{ timers =>
    LogManager.logBehaviourChange(logName,"inExercise")
    timers.startSingleTimer(TimerKey, ExerciseEnds(), machineParameters.duration)

    if(checkDeterioration()){
      val detMessage = logName + " deterioration, please change consumable"
      LogManager.log(detMessage)
      display(detMessage)
    }

    var heartBeats = new ListBuffer[Int]()

    Behaviors.receiveMessagePartial {
      case m: HeartRate =>
        heartBeats += m.heartRate
        display(customerID+" HR: " + m.heartRate)
        Behaviors.same

      case ExerciseEnds() => exerciseEnds(ma, customerID, machineParameters, heartBeats)

      case m: Msg.Rfid =>
        m.customerID match {
          case `customerID` => exerciseEnds(ma, customerID, machineParameters, heartBeats)
          case _ => Behaviors.same
        }

    }
  }

  def exerciseEnds(ma: ActorRef[MachineActor.Msg], customerID: String, machineParameters: MachineParameters, heartBeats: ListBuffer[Int]): Behavior[Msg] = {
    LogManager.logBehaviourChange(logName,"exerciseEnds")
    val avg: Int = heartBeats.sum[Int] / (heartBeats.count(_ => true) match { case c: Int if c > 0 => c; case _ => 1})

    ma ! MachineActor.Msg.UserMachineWorkout(customerID, machineParameters, ExecutionValues(heartBeats.max, heartBeats.min, avg))

    display(machineLabel+" Free")
    operative(ma)
  }

  def display (s: String)
  def configure (customerID: String, machineParameters: MachineParameters)
  def formatConfiguration(machineParameters: MachineParameters): String
  def checkDeterioration(): Boolean
}

object PhysicalMachine {
  val logName: String = "PM"
  sealed trait Msg extends LoggableMsgFromTo
  object Msg{
    //From MachineActor
    final case class MachineActorStarted(machineID: String, refMA: ActorRef[MachineActor.Msg]) extends Msg { override def From: String = "MA"; override def To: String = logName }
    final case class Display(message: String) extends Msg { override def From: String = "MA"; override def To: String = logName }
    final case class ConfigMachine(customerID: String, machineParameters: MachineParameters) extends Msg { override def From: String = "MA"; override def To: String = logName }
    //From Device
    final case class Rfid(customerID: String) extends Msg { override def From: String = "Device"; override def To: String = logName }
    final case class HeartRate(heartRate: Int) extends Msg with NonLoggableMsg { override def From: String = "Device"; override def To: String = logName }
  }
  //Self messages
  private final case class ExerciseEnds() extends Msg with NonLoggableMsg { override def From: String = "PM"; override def To: String = "PM" }

  type MachineLabel = String //ToDo: definire numero massimo caratteri (safe property value)

  import AverageJoes.common.MachineTypes._
  def apply(machineID: String, phMachineType: MachineType, machineLabel: MachineLabel): Behavior[Msg] = {
    Behaviors.setup(context =>
      phMachineType match{
        case MachineTypes.LEG_PRESS => new LegPress(context, machineID, machineLabel)
        case MachineTypes.CHEST_FLY => new ChestFly(context, machineID, machineLabel)
        case MachineTypes.CYCLING => new CyclingMachine(context, machineID, machineLabel)
        case MachineTypes.RUNNING => new RunningMachine(context, machineID, machineLabel)
        case MachineTypes.LIFTING => new LiftingMachine(context, machineID, machineLabel)
      }
    )
  }

  object PhysicalMachineImpl{
    abstract class PhysicalMachineImpl(context: ActorContext[Msg], override val machineID: String, override val machineLabel: String)
      extends AbstractBehavior[Msg](context) with PhysicalMachine {

      override def display(s: String): Unit = {
        machineGui ! ViewToolActor.Msg.UpdateViewObject(s)
      }

      //Commented for test
      override def configure(customerID: String, machineParameters: MachineParameters): Unit = {
        display(customerID)
        /*if(machineParameters.machineType != machineType) throw new IllegalArgumentException
        else formatConfiguration(machineParameters)*/
      }

      override def checkDeterioration(): Boolean = {
        val rnd : Int = new Random(new Date().getTime()).nextInt(100)
        println("*** Deterioration: " + rnd) //Todo: test
        rnd > 90
      }

      override def formatConfiguration(machineParameters: MachineParameters): String = {
        //ToDo: inviare messaggio a view
        /*machineParameters match {
        case p: LegPressParameters => p.length.toString
          case _ => throw new IllegalArgumentException
        }*/
        ""
      }

    }
  }

  import AverageJoes.model.workout.MachineParameters._
  /***** LEG PRESS *****/
  case class LegPressParameters(weight: NonNegInt, override val sets: NonNegInt, override val rep: NonNegInt, override val secForSet: NonNegInt)
    extends MachineParametersBySet with Weight
  { override val machineType: MachineType = LEG_PRESS }


  private class LegPress(context: ActorContext[Msg], override val machineID: String, override val machineLabel: String)
    extends PhysicalMachineImpl.PhysicalMachineImpl(context, machineID, machineLabel){ override val machineType: MachineType = LEG_PRESS }

  /***** CHEST FLY *****/
  case class ChestFlyParameters(weight: NonNegInt, override val sets: NonNegInt, override val rep: NonNegInt, override val secForSet: NonNegInt)
    extends  MachineParametersBySet with Weight
  { override val machineType: MachineType = CHEST_FLY }

  private class ChestFly(context: ActorContext[Msg], override val machineID: String, override val machineLabel: String)
    extends PhysicalMachineImpl.PhysicalMachineImpl(context, machineID, machineLabel){ override val machineType: MachineType = CHEST_FLY }

  /***** CYCLING MACHINE *****/
  case class CyclingMachineParameters(override val incline: NonNegInt, override val minutes: NonNegInt)
    extends MachineParametersByTime with Incline
  { override val machineType: MachineType = CYCLING }

  private class CyclingMachine(context: ActorContext[Msg], override val machineID: String, override val machineLabel: String)
    extends PhysicalMachineImpl.PhysicalMachineImpl(context, machineID, machineLabel){ override val machineType: MachineType = CYCLING }

  /***** RUNNING MACHINE *****/
  case class RunningMachineParameters(incline: NonNegInt, speed: NonNegInt, override val minutes: NonNegInt)
    extends MachineParametersByTime with Incline with Speed
  { override val machineType: MachineType = RUNNING }

  private class RunningMachine(context: ActorContext[Msg], override val machineID: String, override val machineLabel: String)
    extends PhysicalMachineImpl.PhysicalMachineImpl(context, machineID, machineLabel){ override val machineType: MachineType = RUNNING }

  /***** LIFTING MACHINE *****/
  case class LiftingMachineParameters(weight: NonNegInt, override val sets: NonNegInt, override val rep: NonNegInt, override val secForSet: NonNegInt)
    extends MachineParametersBySet with Weight
  { override val machineType: MachineType = LIFTING }

  private class LiftingMachine(context: ActorContext[Msg], override val machineID: String, override val machineLabel: String)
    extends PhysicalMachineImpl.PhysicalMachineImpl(context, machineID, machineLabel){ override val machineType: MachineType = LIFTING }

}