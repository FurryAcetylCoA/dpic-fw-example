package npc
import circt.stage._

object Main extends App {
  // def top       = new Top()
  def top       = new DPIC_fw_test()
  val generator = Seq(chisel3.stage.ChiselGeneratorAnnotation(() => top))
  var lower_opt = "--lowering-options="
  lower_opt = lower_opt + "disallowLocalVariables,disallowPackedArrays" // for Vosys
  lower_opt = lower_opt + ",locationInfoStyle=wrapInAtSquareBracket" // for Verilator
  lower_opt = lower_opt + ",disallowPortDeclSharing,emitWireInPorts,emitBindComments" // for me
  (new ChiselStage).execute(
    args,
    generator :+
      FirtoolOption("--disable-all-randomization") :+
      FirtoolOption(lower_opt)
  )
}
