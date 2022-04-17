/* Xingyu: I tried to understand and modify from SimpleNICBridge.scala and TracerVBridge.scala
*/

//See LICENSE for license details
package firesim
package bridges

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._

import midas.widgets._
import testchipip.{StreamIO, StreamChannel}
import icenet.{NICIOvonly, RateLimiterSettings}
import icenet.IceNIC._
import junctions.{NastiIO, NastiKey}


// Xingyu specific Starts
import freechips.rocketchip.tilelink._

// Xingyu specific Ends


// Xingyu specific Starts
class ChipletTargetIO extends Bundle {
    val 
}

// Xingyu specific Ends
