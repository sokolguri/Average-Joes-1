package AverageJoes.common

import akka.actor.ActorRef

sealed trait MsgActorMessage

//TODO: definire un tipo per userID?
case class MsgRfid(userID: String) extends MsgActorMessage //Rfid fired
case class MsgUserInGym(userID: String) extends MsgActorMessage //User enter in Gym
case class MsgUserLogin(userID: String) extends MsgActorMessage //User logged
case class MsgUserLogged(machineID: String) extends MsgActorMessage //User logged
case class MsgPhysicalMachineWakeUp() extends MsgActorMessage //Login to the controller
case class MsgDisplay(message: String) extends MsgActorMessage
case class MsgNearDevice(device:ActorRef) extends MsgActorMessage

object MsgActorMessage {

}
