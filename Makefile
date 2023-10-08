GEN_DIR = ./HDL_gen

SCA_SRC = $(abspath $(wildcard src/*.scala))
SCA_SRC += $(abspath $(wildcard src/*/*.scala))

TOP_NAME = DPIC_fw_test

V_GEN = $(abspath  gen/$(TOP_NAME).sv)

all-scala: $(V_GEN)

$(V_GEN): $(SCA_SRC) 
	./mill run --target-dir $(GEN_DIR) --split-verilog --target systemverilog --warnings-as-errors

test: 
	./mill test

reformat_scala:
	./mill -i reformat

checkformat_scala:
	./mill -i checkFormat

clean_scala:
	-rm -rf target test_run_dir
	-rm -rf gen

.PHONY: test clean_scala all-scala reformat_scala checkformat_scala
