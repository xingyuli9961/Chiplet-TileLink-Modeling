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

object TokenQueueConsts {
  val TOKENS_PER_BIG_TOKEN = 7
  val BIG_TOKEN_WIDTH = (TOKENS_PER_BIG_TOKEN + 1) * 64
  val TOKEN_QUEUE_DEPTH = 6144
}
import TokenQueueConsts._

case object LoopbackNIC extends Field[Boolean](false)


// Xingyu
import chisel3.util._
import chisel3.util.random._
import freechips.rocketchip.tilelink._
// Xingyu: Define special IO
// This is the output side
class ACEBundleIO extends Bundle {
  val A = Decoupled(new TLBundleA(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)))
  val C = Decoupled(new TLBundleC(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)))
  val E = Decoupled(new TLBundleE(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)))
}

class NICTargetIO extends Bundle {
  val clock = Input(Clock())
  val nic = Flipped(new ACEBundleIO)
}

// The Fake bundle just for testing
class ACEToken extends Bundle{
    // val A = UInt(32.W)
    val A = new TLBundleA(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true))
    val C = new TLBundleC(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true))
    val E = new TLBundleE(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true))
    val Avalid = Bool()
    val Cvalid = Bool()
    val Evalid = Bool()
    val isACE = UInt(1.W)
}

// Random ACEToken Generator
class ACEIOInputGenerator extends Module {
    val io = IO(new ACEBundleIO)


    val counter = RegInit(UInt(32.W), 0.U)
    counter := counter + 3.U

    io.A.bits := counter.asTypeOf(new TLBundleA(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true))) 
    io.C.bits := (counter + 1.U).asTypeOf(new TLBundleC(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)))
    io.E.bits := (counter + 2.U).asTypeOf(new TLBundleE(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)))
    io.A.valid := LFSR(16) & 1.U
    io.C.valid := LFSR(16) & 1.U
    io.E.valid := LFSR(16) & 1.U
}

// Pack ACEBundleIO into ACEToken into 512 bits
class ACETokenGenerator extends Module {
    val io = IO(new Bundle{
        val in = Input(new ACEToken)
        val out = Output(UInt(512.W))
    })
    io.out := io.in.asUInt
    require(io.in.asUInt.getWidth <= 512)
}

class ACEBundleDMATokenGenerator extends Module {
    val io = IO(new Bundle{
        val ACEio = Flipped(new ACEBundleIO)
        val out = Decoupled(UInt(512.W))
    })
    val outputgenerator = Module(new ACETokenGenerator)
    outputgenerator.io.in.A := io.ACEio.A.bits
    outputgenerator.io.in.C := io.ACEio.C.bits
    outputgenerator.io.in.E := io.ACEio.E.bits
    outputgenerator.io.in.Avalid := io.ACEio.A.valid
    outputgenerator.io.in.Cvalid := io.ACEio.C.valid
    outputgenerator.io.in.Evalid := io.ACEio.E.valid
    outputgenerator.io.in.isACE := 1.U

    io.out.bits := outputgenerator.io.out
    io.out.valid := io.ACEio.A.valid || io.ACEio.C.valid || io.ACEio.E.valid

    io.ACEio.A.ready := io.out.ready
    io.ACEio.C.ready := io.out.ready
    io.ACEio.E.ready := io.out.ready
}

// Xinyu: End
// Xingyu


class NICBridge(implicit p: Parameters) extends BlackBox with Bridge[HostPortIO[NICTargetIO], SimpleNICBridgeModule] {
  val io = IO(new NICTargetIO)
  val bridgeIO = HostPort(io)
  val constructorArg = None
  generateAnnotations()
}


object NICBridge {
  def apply(clock: Clock, nicIO: NICIOvonly)(implicit p: Parameters): NICBridge = {
    val ep = Module(new NICBridge)
    ep.io.nic <> nicIO
    ep.io.clock := clock
    ep
  }
}

class SimpleNICBridgeModule(implicit p: Parameters) extends BridgeModule[HostPortIO[NICTargetIO]]()(p) {
  lazy val module = new BridgeModuleImp(this) with BidirectionalDMA {
    val io = IO(new WidgetIO)
    val hPort = IO(HostPort(new NICTargetIO))
    
    // Xingyu: Start
    val ACEBundlesgenerator = Module(new ACEIOInputGenerator)
    val ACE_to_NIC_generator = Module(new ACEBundleDMATokenGenerator)

    // ACE_to_NIC_generator.io.ACEio <> hPort.hBits.nic
    ACE_to_NIC_generator.io.ACEio <> ACEBundlesgenerator.io
    outgoingPCISdat.io.enq <> ACE_to_NIC_generator.io.out

    val tokensToEnqueue = RegInit(0.U(64.W))
    when (ACE_to_NIC_generator.io.out.valid) {
        tokensToEnqueue := tokensToEnqueue + 1.U
    }
    val A = new TLBundleA(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true))
    val C = new TLBundleC(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true))
    val E = new TLBundleE(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true))
    val Aw = A.getWidth.asUInt
    val Cw = C.getWidth.asUInt
    val Ew = E.getWidth.asUInt
    
    genROReg(tokensToEnqueue, "number_of_tokens")
    genROReg(Aw, "Awidth")
    genROReg(Cw, "Cwidth")
    genROReg(Ew, "Ewidth")
    genCRFile()
    // Xingyu: End

    // DMA mixin parameters
    lazy val fromHostCPUQueueDepth = TOKEN_QUEUE_DEPTH
    lazy val toHostCPUQueueDepth   = TOKEN_QUEUE_DEPTH
    // Biancolin: Need to look into this
    lazy val dmaSize = BigInt((BIG_TOKEN_WIDTH / 8) * TOKEN_QUEUE_DEPTH)
  }
}
