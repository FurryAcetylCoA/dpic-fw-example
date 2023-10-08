package dpicfw

import chisel3._
import chisel3.experimental.ChiselAnnotation
import chisel3.experimental.ExtModule
import chisel3.reflect._
import chisel3.util._

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.collection.mutable.ListBuffer

class DPICBundle extends Bundle {

  val desiredModuleName: String = {
    val className: String = this.getClass().getName().replace("$", ".")
    className.split("\\.").filterNot(_.forall(java.lang.Character.isDigit)).last
  }
  val dpicFuncName: String = s"dpic_$desiredModuleName"
}

class DPIC[T <: DPICBundle](gen: T) extends ExtModule with HasExtModuleInline {
  val clock = IO(Input(Clock()))
  val io    = IO(((gen)))

  val dpicFuncName:         String = gen.dpicFuncName
  override def desiredName: String = gen.desiredModuleName

    def getVerilogDirString(data: Data): String = {
      if (DataMirror.directionOf(data) == ActualDirection.Input)
        "input "
      else
        "output"
    }
  def getModulePortString(argName: String, data: Data) = {
    val widthNote = if (data.getWidth == 1) "     " else f"[${data.getWidth - 1}%2d:0]"
    s"${getVerilogDirString(data)} $widthNote $argName"
  }

  def getDpicImportArgString(argName: String, data: Data): String = {
    val typeString = data.getWidth match {
      case 1                                 => "bit"
      case width if width > 1 && width <= 8  => "byte"
      case width if width > 8 && width <= 32 => "int"
      case _ => s"unsupported io width ${data.getWidth}!!\n"
    }
    val dirString = getVerilogDirString(data)
    f"$dirString $typeString%-5s $argName"
  }

  val modulePortsWithClock: Seq[Seq[(String, Data)]] = { //why can't this be a Seq[(String, Data)]
    Seq(("clock", clock)) +: io.elements.toSeq.reverse.map {
      case (name, data) =>
        data match{
          case vec: Vec[_] => vec.zipWithIndex.map { case (v, i) => (s"io_${name}_$i", v) }
          case _ => Seq((s"io_$name", data))
        }
    }
  }

  val modulePorts: Seq[Seq[(String, Data)]] = modulePortsWithClock.tail

  val inlineString: String = {
    val dpicImportArgs = modulePorts.flatten.map { arg => getDpicImportArgString(arg._1, arg._2) }.mkString(",\n  ")

    val dpicImport =
      s"""
         | import "DPI-C" function void $dpicFuncName(
         |  $dpicImportArgs
         | );
         |""".stripMargin

    val modulePortsString =
      modulePortsWithClock.flatten.map { arg => getModulePortString(arg._1, arg._2) }.mkString(",\n  ")

    val body = s"""
                  | module $desiredName(
                  |  $modulePortsString
                  | );
                  |/* verilator lint_off WIDTHEXPAND */
                  | $dpicImport
                  | always @(posedge clock) begin
                  |  $dpicFuncName (${modulePorts.flatten.map { arg => arg._1 }.mkString(", ")});
                  | end
                  |endmodule
       """.stripMargin
    body
  }

  setInline(s"$desiredName.sv", inlineString)
}

object DPIC {
  val interfaces = ListBuffer.empty[(String, Seq[Seq[(String, Data)]])]

  def apply[T <: DPICBundle](gen: T): T = {
    val module = Module(new DpicWrapper(gen))
    val dpic   = module.dpic
    if (!interfaces.map(_._1).contains(dpic.dpicFuncName)) {
      val interface = (dpic.dpicFuncName, dpic.modulePorts)
      interfaces += interface
    }
    module.io := DontCare
    module.io
  }

  def collect(): Unit = {
    def getCArgString(argName: String, data: Data): String = {
      val typeString = data.getWidth match {
        case width if width <= 8                => "uint8_t "
        case width if width > 8  && width <= 16 => "uint16_t"
        case width if width > 16 && width <= 32 => "uint32_t"
        case _                                  => s"unsopproted"
      }
      if (DataMirror.directionOf(data) == ActualDirection.Output){
        return f"$typeString%-8s* $argName"
      }
      f"$typeString%-8s $argName"
    }
    def getCProtoString(interface: (String, Seq[Seq[(String, Data)]])): String = {
      val proto = s"""
                     |void ${interface._1}(
                     |  ${interface._2.flatten.map(arg => getCArgString(arg._1, arg._2)).mkString(",\n  ")}
                     |)""".stripMargin
      proto
    }
    def getCOverrideString(interface: (String, Seq[Seq[(String, Data)]])): String = {
      s"""
        |void ${interface._1}_override(
        |  ${interface._2.flatten.map(arg => getCArgString(arg._1, arg._2)).mkString(",\n  ")})
        |__attribute__ ((weak));""".stripMargin
    }
    def getCBodyString(interface: (String, Seq[Seq[(String, Data)]])): String = {
      def getCAssignString(obj: String, name: String, data: Data): String = {
        if (DataMirror.directionOf(data) == ActualDirection.Output){
          return s"*${name} = ${obj}.${name.replace("io_","")};"
        }
        s"${obj}.${name.replace("io_","")} = ${name};"
      }
      val obj  = s"${interface._1}_data"
      val body = s"""
                    |struct ${obj}_struct ${obj};
                    |${getCProtoString(interface)} {
                    |  if(${interface._1}_override != nullptr){
                    |   ${interface._1}_override(${interface._2.flatten.map(_._1).mkString(", ")});
                    |   return;
                    |  }
                    |  auto packet = ${obj};
                    |  ${interface._2.flatten.map(arg => getCAssignString(obj, arg._1, arg._2)).mkString("\n  ")}
                    |}""".stripMargin
      body
    }
    def getCStructString(interface: (String, Seq[Seq[(String, Data)]])): String = {
      val obj  = s"${interface._1}_data"
      val body = s"""
                    |struct ${obj}_struct {
                    |  ${interface._2.flatten.map(arg => getCArgString(arg._1, arg._2) + ";").mkString("\n  ").replace("*","").replace("io_","")}
                    |};
                    |extern struct ${obj}_struct ${obj};""".stripMargin
      body
    }
    if (interfaces.isEmpty) {
      return
    }
    val outputDir  = Paths.get("").toAbsolutePath().resolve("SRC_gen")
    val cInterface = ListBuffer.empty[String]
    Files.createDirectories(outputDir)
    //dpic.h
    cInterface += "#ifndef __DPIC_H__"
    cInterface += "#define __DPIC_H__"
    cInterface += "/*Generated by DPIC-fw. Do NOT edit.*/"
    cInterface += "#ifdef __cplusplus"
    cInterface += "#include <cstdint>"
    cInterface += "extern \"C\" {"
    cInterface += "#else"
    cInterface += "#include <stdint.h>"
    cInterface += "#endif"
    cInterface += ""
    cInterface += interfaces.map(getCProtoString(_) + ";").mkString("\n")
    cInterface += interfaces.map(getCStructString(_)).mkString("\n")
    cInterface += "#ifdef __cplusplus"
    cInterface += "}"
    cInterface += "#endif"
    cInterface += "#endif // __DPIC_H__"
    cInterface += ""
    Files.write(outputDir.resolve("dpic.h"), cInterface.mkString("\n").getBytes(StandardCharsets.UTF_8))

    //dpic.cpp
    cInterface.clear()
    cInterface += "#include \"dpic.h\""
    cInterface += "/*Generated by DPIC-fw. Do NOT edit.*/"
    cInterface += "extern \"C\" {"
    cInterface += interfaces.map(getCOverrideString(_)).mkString("\n")
    cInterface += interfaces.map(getCBodyString(_)).mkString("\n")
    cInterface += "}"
    Files.write(outputDir.resolve("dpic.cpp"), cInterface.mkString("\n").getBytes(StandardCharsets.UTF_8))
  }
}

private class DpicWrapper[T <: DPICBundle](gen: T) extends Module {
  val io   = IO(((gen)))
  val dpic = Module(new DPIC(gen))
  override def desiredName: String = s"dpic_wrapper_${gen.desiredModuleName}"
  dpic.clock := clock
  dpic.io    <> io
}
