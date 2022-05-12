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
// This is the output side of A, C, E and input side of B, E
class TLBundleIO(params: TLBundleParameters) extends Bundle {
    val A = Decoupled(new TLBundleA(params))
    val B = Flipped(Decoupled(new TLBundleB(params)))
    val C = Decoupled(new TLBundleC(params))
    val D = Flipped(Decoupled(new TLBundleD(params)))
    val E = Decoupled(new TLBundleE(params))
}


class NICTargetIO extends Bundle {
  val clock = Input(Clock())
//  val nic = Flipped(new ACEBundleIO)
  val nic = Input(UInt(32.W))
}


// The ACE transmitter side Big Token into the PCIe port
class ACEBigToken(params: TLBundleParameters) extends Bundle {
    val A = new TLBundleA(params)
    val C = new TLBundleC(params)
    val E = new TLBundleE(params)
    val Avalid = Bool()
    val Cvalid = Bool()
    val Evalid = Bool()
    val Bready = Bool()
    val Dready = Bool()
    val isACE = UInt(3.W)
}


// The BD transmitter side Big Token into the PCIe port 
class BDBigToken(params: TLBundleParameters) extends Bundle {
    val B = new TLBundleB(params)
    val D = new TLBundleD(params)
    val Bvalid = Bool()
    val Dvalid = Bool()
    val Aready = Bool()
    val Cready = Bool()
    val Eready = Bool()
    val isACE = UInt(3.W)
}


// Random ACEBigToken Generator
class ACEsideIOInputGenerator(params: TLBundleParameters) extends Module {
    val io = IO(new TLBundleIO(params))

    // The variable indicating each token is generated in how many cycles
    val period = 10.U
    // This value should be num_Big_tokens
    val max_num_tokens = 100.U
    val p_counter = RegInit(UInt(32.W), 0.U)
    val counter = RegInit(UInt(32.W), 0.U)

    when (p_counter < period - 1.U || counter > max_num_tokens) {
        counter := counter
        p_counter := p_counter + 1.U
        io.A.valid := false.B
        io.C.valid := false.B
        io.E.valid := false.B
    } .otherwise {
        counter := counter + 1.U
        p_counter := 0.U
        io.A.valid := true.B
        io.C.valid := LFSR(16) >= 134.U
        io.E.valid := (LFSR(16) & 1.U) === 1.U
    }
    io.A.bits := counter.asTypeOf(new TLBundleA(params)) 
    io.C.bits := counter.asTypeOf(new TLBundleC(params)) 
    io.E.bits := counter.asTypeOf(new TLBundleE(params)) 

    io.B.ready := false.B
    io.D.ready := false.B
}


// Pack selecetd signals in TL bundles into ACEBigToken as 512 bits
class ACEBigTokenGenerator(params: TLBundleParameters) extends Module {
    val io = IO(new Bundle{
        val in = Input(new ACEBigToken(params))
        val out = Decoupled(UInt(512.W))
    })

    io.out.bits := io.in.asUInt
    require(io.in.asUInt.getWidth <= 512)
    // Always generate a token no matter if it is valid or not
    io.out.valid := true.B
}


// Decode 512-bit token from PCIe into ACEBigToken
class ACEBigTokenDecoder(params: TLBundleParameters) extends Module {
    val io = IO(new Bundle{
        val in = Flipped(Decoupled(UInt(512.W)))
        val out = Decoupled(new ACEBigToken(params))
    })
    io.out.bits := io.in.bits.asTypeOf(new ACEBigToken(params))
    io.out.valid := io.in.valid
    io.in.ready := io.out.ready
}
// Xingyu: End


class NICBridge(implicit p: Parameters) extends BlackBox with Bridge[HostPortIO[NICTargetIO], SimpleNICBridgeModule] {
  val io = IO(new NICTargetIO)
  val bridgeIO = HostPort(io)
  val constructorArg = None
  generateAnnotations()
}


object NICBridge {
  def apply(clock: Clock, nicIO: UInt)(implicit p: Parameters): NICBridge = {
    val ep = Module(new NICBridge)
    ep.io.nic <> nicIO
    ep.io.clock := clock
    ep
  }
}

//object NICBridge {
//  def apply(clock: Clock, nicIO: ACEBundleIO)(implicit p: Parameters): NICBridge = {
//    val ep = Module(new NICBridge)
//    ep.io.nic <> nicIO
//    ep.io.clock := clock
//    ep
//  }
//}

class SimpleNICBridgeModule(implicit p: Parameters) extends BridgeModule[HostPortIO[NICTargetIO]]()(p) {
  lazy val module = new BridgeModuleImp(this) with BidirectionalDMA {
    val io = IO(new WidgetIO)
    val hPort = IO(HostPort(new NICTargetIO))
   
    // DMA mixin parameters
    lazy val fromHostCPUQueueDepth = TOKEN_QUEUE_DEPTH
    lazy val toHostCPUQueueDepth   = TOKEN_QUEUE_DEPTH
    // Biancolin: Need to look into this
    lazy val dmaSize = BigInt((BIG_TOKEN_WIDTH / 8) * TOKEN_QUEUE_DEPTH)
 
    // Xingyu: Start
    val tlparams = TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)
    val ACEMasterBigTokenGenerator = Module(new ACEBigTokenGenerator((tlparams)))

    // A Hacky to to test the bridge
    val InputGenerator = Module(new ACEsideIOInputGenerator(tlparams))

    // Connect the IOs
    ACEMasterBigTokenGenerator.io.in.A := InputGenerator.io.A.bits
    ACEMasterBigTokenGenerator.io.in.C := InputGenerator.io.C.bits
    ACEMasterBigTokenGenerator.io.in.E := InputGenerator.io.E.bits
    ACEMasterBigTokenGenerator.io.in.Avalid := InputGenerator.io.A.valid
    ACEMasterBigTokenGenerator.io.in.Cvalid := InputGenerator.io.C.valid
    ACEMasterBigTokenGenerator.io.in.Evalid := InputGenerator.io.E.valid
    ACEMasterBigTokenGenerator.io.in.Bready := InputGenerator.io.B.ready
    ACEMasterBigTokenGenerator.io.in.Dready := InputGenerator.io.D.ready
    ACEMasterBigTokenGenerator.io.in.isACE := 1.U

    outgoingPCISdat.io.enq <> ACEMasterBigTokenGenerator.io.out

    InputGenerator.io.A.ready := false.B
    InputGenerator.io.C.ready := false.B
    InputGenerator.io.E.ready := false.B
    InputGenerator.io.B.bits := (0.U).asTypeOf(new TLBundleB(tlparams)) 
    InputGenerator.io.D.bits := (0.U).asTypeOf(new TLBundleD(tlparams)) 
    InputGenerator.io.B.valid := false.B
    InputGenerator.io.D.valid := false.B


    val counter = RegInit(UInt(32.W), 0.U)
    counter := counter + 1.U

    val Aw = InputGenerator.io.A.bits.getWidth.asUInt
    val Bw = InputGenerator.io.B.bits.getWidth.asUInt
    val Cw = InputGenerator.io.C.bits.getWidth.asUInt
    val Dw = InputGenerator.io.D.bits.getWidth.asUInt
    val Ew = InputGenerator.io.E.bits.getWidth.asUInt

    
    when (counter === 0.U) {
        printf(p"The A,B, C, D, E channel widths are $Aw, $Bw, $Cw, $Dw, $Ew \n")
        printf(p"TLbundle IO version. \n")
    }
    
    val probeACE2 = InputGenerator.io
//    when (counter <= 200.U) {
//        printf(p"This is counter $counter \n")
//        printf(p"The inside generated input is = $probeACE2 \n")
//        printf(p"The output to the PCIE = ${Hexadecimal(outgoingPCISdat.io.enq.bits)} \n")
//    }

    // The decoder
    val ACEdecoder = Module(new ACEBigTokenDecoder(tlparams))
    ACEdecoder.io.in <> incomingPCISdat.io.deq

    ACEdecoder.io.out.ready := true.B

    // Test decoder
    // ACEdecoder.io.in <> ACEMasterBigTokenGenerator.io.out
    // incomingPCISdat.io.deq.ready := false.B

    val ACE_decode_out = ACEdecoder.io.out

    when (ACEdecoder.io.out.bits.Avalid || ACEdecoder.io.out.bits.Cvalid || ACEdecoder.io.out.bits.Evalid) {
        printf(p"At counter $counter, the decoder output: $ACE_decode_out \n")
        when (ACEdecoder.io.out.valid && (ACEdecoder.io.out.bits.isACE =/= 1.U)) {
            printf(p"Error, wrong token here \n")
        }
    }

    // In the version that only the hardware is changed, all incoming data is always 0
    // val incomingv = incomingPCISdat.io.deq
    // printf(p"at counter $counter, the incoming value from the PCIe is $incomingv \n")


    // Use this next line when do atual software driver tests
    val hardware_testing = false;
    // val hardware_testing = true; 

    if (hardware_testing) {
        val macAddrRegUpper = Reg(UInt(32.W))
        val macAddrRegLower = Reg(UInt(32.W))
        val rlimitSettings = Reg(UInt(32.W))
        val pauseThreshold = Reg(UInt(32.W))
        val pauseTimes = Reg(UInt(32.W))
        val tFire = RegInit(UInt(1.W), 0.U)
        attach(macAddrRegUpper, "macaddr_upper", WriteOnly)
        attach(macAddrRegLower, "macaddr_lower", WriteOnly)
        attach(rlimitSettings, "rlimit_settings", WriteOnly)
        attach(pauseThreshold, "pause_threshold", WriteOnly)
        attach(pauseTimes, "pause_times", WriteOnly)
        genROReg(!tFire, "done")
    } else {
        genROReg(Aw, "Awidth")
        genROReg(Bw, "Bwidth")
        genROReg(Cw, "Cwidth")
        genROReg(Dw, "Dwidth")
        genROReg(Ew, "Ewidth")
    }

    genCRFile()
    // Xingyu: End
  }
}
