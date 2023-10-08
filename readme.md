适用于chisel的DPI-C绑定生成框架

可以自动生成Verilog侧与C侧代码，受香山Difftest框架启发


## 使用方法
(具体可参考src/DPIC-fw-test.scala）：
1. 定以需要使用的信号列表，支持输入与输出。如：
```
class DpTestBundle extends DPICBundle{
  val in1 = Input(UInt(8.W))
  val out = Output(UInt(16.W))
}
```
2. 实例化
```
val test1 = DPIC(new DpTestBundle)
```
3. 在所有实例化完成后，在顶层模块的结尾使用该接口来生成C侧的代码
```
DPIC.collect()
```

## 构建：
使用make构建，默认目标即可。构建需要mill

HDL_gen与SRC_gen分别存放了生成的verilog与C代码
复制到仿真项目中即可使用

C侧的默认行为是把数据简单地复制到结构体内。但可以在其他文件内定义特殊命名的函数覆盖

如:对于dpic_DpTestBundle，定义dpic_DpTestBundle_override可以修改默认行为

---

## 问题：
Verilog的DPIC调用写在哪里都可以，但Chisel中只能作为一个模块存在

这会导致不好控制调用的时机（只能通过敏感列表，而不能通过代码位置）

我删减了很多香山Difftest框架中与DPIC有关，但又看不出来作用的代码。可能会导致一些边角情况的缺失。甚至可能删了一半留了一半


