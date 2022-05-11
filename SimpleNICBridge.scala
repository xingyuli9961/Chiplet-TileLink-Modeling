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
  val tlparams = TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)
  val A = Decoupled(new TLBundleA(tlparams))
  val C = Decoupled(new TLBundleC(tlparams))
  val E = Decoupled(new TLBundleE(tlparams))
}

class NICTargetIO extends Bundle {
  val clock = Input(Clock())
//  val nic = Flipped(new ACEBundleIO)
  val nic = Input(UInt(32.W))
}

// The Fake bundle just for testing
class ACEToken extends Bundle{
    val tlparams = TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)
    val A = new TLBundleA(tlparams)
    val C = new TLBundleC(tlparams)
    val E = new TLBundleE(tlparams)
    val Avalid = Bool()
    val Cvalid = Bool()
    val Evalid = Bool()
    val isACE = UInt(1.W)
}

// Random ACEToken Generator
class ACEIOInputGenerator extends Module {
    val io = IO(new ACEBundleIO)
    
    // A variable indicating each token is generated in how many cycles
    val period = 10.U
    val max_num_tokens = 29.U 
    val p_counter = RegInit(UInt(32.W), 0.U)

    val counter = RegInit(UInt(32.W), 0.U)

    when (p_counter < period - 1.U || counter > max_num_tokens) {
        counter := counter
        p_counter := p_counter + 1.U
        io.A.valid := false.B
        io.C.valid := false.B
        io.E.valid := false.B
    } .otherwise {
        counter := counter + 3.U
        p_counter := 0.U
        io.A.valid := true.B
        io.C.valid := LFSR(16) >= 134.U
        io.E.valid := (LFSR(16) & 1.U) === 1.U
    }

    io.A.bits := counter.asTypeOf(new TLBundleA(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true))) 
    io.C.bits := (counter + 1.U).asTypeOf(new TLBundleC(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)))
    io.E.bits := (counter + 2.U).asTypeOf(new TLBundleE(TLBundleParameters(32, 64, 8, 8, 8, Nil, Nil, Nil, true)))

//    when (counter === 0.U) {
//        printf(p"Simulation starts\n")
//    }

//    when (counter <= 100.U) {
//        printf(p"The output at counter $counter = $io \n")
//    }
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

    // Always generate a token no matter if it is valid or not
    io.out.valid := true.B

    io.ACEio.A.ready := io.out.ready
    io.ACEio.C.ready := io.out.ready
    io.ACEio.E.ready := io.out.ready
}


// Dis-assemble 512 bits into ACEToken
class ACETokenDecoder extends Module {
    val io = IO(new Bundle{
        val in = Input(UInt(512.W))
        val out = Output(new ACEToken)
    })
    io.out := io.in.asTypeOf(new ACEToken)
}


class ACETokentoBundlesDecoder extends Module {
    val io = IO(new Bundle {
	val in = Flipped(Decoupled(UInt(512.W)))
        val ACEout = new ACEBundleIO
    })
    val PCIeparser = Module(new ACETokenDecoder)
    PCIeparser.io.in := io.in.bits
    io.in.ready := io.ACEout.A.ready && io.ACEout.C.ready && io.ACEout.E.ready
    io.ACEout.A.bits := PCIeparser.io.out.A
    io.ACEout.C.bits := PCIeparser.io.out.C
    io.ACEout.E.bits := PCIeparser.io.out.E
    io.ACEout.A.valid := PCIeparser.io.out.Avalid && io.in.valid
    io.ACEout.C.valid := PCIeparser.io.out.Cvalid && io.in.valid
    io.ACEout.E.valid := PCIeparser.io.out.Evalid && io.in.valid
    // require(PCIeparser.io.out.isACE == true.B) 

    val counter = RegInit(UInt(32.W), 0.U)
    counter := counter + 1.U
    when (counter === 0.U) {
        printf(p"The decoder actually instantiated\n")
    } 
    when (counter === 5.U) {
	printf(p"Just a print at $counter \n")
    }
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
    val ACE_to_NIC_generator = Module(new ACEBundleDMATokenGenerator)

    // A hacky way to do
    val BundleGenerator = Module(new ACEIOInputGenerator)
    ACE_to_NIC_generator.io.ACEio <> BundleGenerator.io

    // ACE_to_NIC_generator.io.ACEio <> hPort.hBits.nic

    outgoingPCISdat.io.enq <> ACE_to_NIC_generator.io.out

    val counter = RegInit(UInt(32.W), 0.U)
    counter := counter + 1.U

    val Aw = BundleGenerator.io.A.bits.getWidth.asUInt
    val Cw = BundleGenerator.io.C.bits.getWidth.asUInt
    val Ew = BundleGenerator.io.E.bits.getWidth.asUInt
    
    when (counter === 0.U) {
        printf(p"The A, C, E channel widths are $Aw, $Cw, $Ew \n")
    }
    
    val probeACE2 = BundleGenerator.io
    when (counter <= 200.U && ((ACE_to_NIC_generator.io.out.bits & 15.U) > 1.U)) {
        printf(p"This is counter $counter \n")
        printf(p"The inside generated input is = $probeACE2 \n")
        printf(p"The output to the PCIE = ${Hexadecimal(outgoingPCISdat.io.enq.bits)} \n")
    }

    // The decoder
    val decoder = Module(new ACETokentoBundlesDecoder)
    decoder.io.in <> incomingPCISdat.io.deq    

    // Test decoder: self-loop for correctness
    // decoder.io.in <> ACE_to_NIC_generator.io.out
    // incomingPCISdat.io.deq.ready := false.B

    decoder.io.ACEout.A.ready := true.B
    decoder.io.ACEout.C.ready := true.B
    decoder.io.ACEout.E.ready := true.B

    val ACE_decode_out = decoder.io.ACEout
    when (decoder.io.ACEout.A.valid || decoder.io.ACEout.C.valid || decoder.io.ACEout.E.valid) {
	printf(p"At counter $counter, the decoder output: $ACE_decode_out \n")
    }

    // In the version that only the hardware is changed, all incoming data is always 0
    // val incomingv = incomingPCISdat.io.deq
    // printf(p"at counter $counter, the incoming value from the PCIe is $incomingv \n")


    // Use this next line when do atual software driver tests
    var hardware_testing = false;
    / /val hardware_testing = true; 

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
	genROReg(Cw, "Cwidth")
	genROReg(Ew, "Ewidth")
    }

    genCRFile()
    // Xingyu: End
  }
}
