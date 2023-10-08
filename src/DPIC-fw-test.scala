package npc

import chisel3._
import chisel3.util._
import dpicfw._

class DpTestBundle extends DPICBundle{
  val in1 = Input(UInt(8.W))
  val out = Output(UInt(16.W))
  override val always_comb = true
}

class DpTestBundle2 extends DPICBundle{
  val in2 = Input(UInt(8.W))
  val in4 = Input(UInt(8.W))
  val out = Output(UInt(16.W))
}


class DPIC_fw_test extends Module {
  val io = IO(new Bundle{})
  val test1 = DPIC(new DpTestBundle)
  val test2 = DPIC(new DpTestBundle2)

  DPIC.collect()
}
