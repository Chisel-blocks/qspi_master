package qspi_master

import chisel3._
import chisel3.util._
import chisel3.stage.{ChiselStage, ChiselGeneratorAnnotation}

import transceiver._
import clockgen._
import receiver._

class qspi_masterIo extends Bundle {
  val clk_div = Input(UInt(8.W))
  val clk_div_valid = Input(Bool())

  val addr = Input(UInt(32.W))
  val addr_len = Input(UInt(6.W))

  val cmd = Input(UInt(32.W))
  val cmd_len = Input(UInt(6.W))

  val dummy_len = Input(UInt(16.W))

  val data_len = Input(UInt(16.W))
  val data_tx = Flipped(Decoupled(UInt(32.W)))
  val data_rx = Decoupled(UInt(32.W))

  // SPI Control Signals
  val spi_rd = Input(Bool())
  val spi_wr = Input(Bool())
  val spi_qrd = Input(Bool())
  val spi_qwr = Input(Bool())

  val spi_clk = Output(Bool())
  val cs = Output(Bool())
  val spi_sdo0 = Output(Bool())
  val spi_sdo1 = Output(Bool())
  val spi_sdo2 = Output(Bool())
  val spi_sdo3 = Output(Bool())
  val spi_sdi0 = Input(Bool())
  val spi_sdi1 = Input(Bool())
  val spi_sdi2 = Input(Bool())
  val spi_sdi3 = Input(Bool())

  val debug_state = Output(UInt(3.W)) 
  val quad_mode = Output(Bool())
  val tx_en = Output(Bool())
  val rx_en = Output(Bool())
  val Tx_done = Output(Bool())
  val Rx_done = Output(Bool())
  val spi_status = Output(UInt(7.W))
}


class qspi_master extends Module {
  val io = IO(new qspi_masterIo)

  val idle :: command :: address :: dummy :: datatx :: datarx :: wait_edge :: Nil = Enum(7)
  val state = RegInit(idle)

  // Debug port for state (test-only)
  
  io.debug_state := state
  

  // Internal wires
  val clock_en = WireDefault(false.B)
  val cs_wire = WireDefault(true.B)
  val tx_en = WireDefault(false.B)
  io.tx_en := tx_en
  val rx_en = WireDefault(false.B)
  io.rx_en := rx_en
  val en_quad = Wire(Bool())
  val counter_tx = WireDefault(0.U(16.W))
  val counter_tx_upd = WireDefault(false.B)
  val counter_rx = WireDefault(0.U(16.W))
  val counter_rx_upd = WireDefault(false.B)
  val data_to_tx = Wire(Decoupled(UInt(32.W)))
  data_to_tx.valid := false.B
  data_to_tx.bits := 0.U
  val tx_done = Wire(Bool())
  io.Tx_done := tx_done
  val rx_done = Wire(Bool())
  io.Rx_done := rx_done
  val tx_clock_en = Wire(Bool())
  val rx_clock_en = Wire(Bool())
  val selector = WireDefault(0.U(3.W))
  val data_valid = WireDefault(false.B)
  val spi_fall = Wire(Bool())
  val spi_rise = Wire(Bool())
  val spi_clk = WireDefault(false.B) 
      io.spi_clk := spi_clk
  val spi_status = WireDefault(0.U(7.W))

  // Internal registers
  val en_quad_reg = RegInit(0.U(1.W))
  val do_rx = RegInit(false.B)

  // Instantiate submodules
  val clock_generator = Module(new clockgen)
  val receiver = Module(new receiver)
  val transmitter = Module(new transceiver)

  // Quad enable logic
  when(io.spi_qrd || io.spi_qwr) {
    en_quad_reg := true.B
  }.elsewhen(state === idle) {
    en_quad_reg := false.B
  }

  when(io.spi_rd || io.spi_qrd) {
    do_rx := true.B
  }.elsewhen(state === idle) {
    do_rx := false.B
  }

  en_quad := io.spi_qrd || io.spi_qwr || en_quad_reg.asBool
  io.quad_mode := en_quad


  // Clock generator connections
  clock_generator.io.en := clock_en
  clock_generator.io.clk_div_valid := io.clk_div_valid
  clock_generator.io.clk_div := io.clk_div
  spi_fall := clock_generator.io.spi_fall
  spi_rise:= clock_generator.io.spi_rise
  transmitter.io.tx_edge := spi_fall
  spi_clk := clock_generator.io.spi_clk
  receiver.io.rx_edge := spi_rise

  // Transmitter connections
  transmitter.io.en := tx_en
  transmitter.io.en_quad_in := en_quad
  transmitter.io.data <> data_to_tx
  transmitter.io.counter_in := counter_tx
  transmitter.io.counter_in_upd := counter_tx_upd
  tx_done := transmitter.io.tx_done
  tx_clock_en := transmitter.io.clk_en_o
  io.spi_sdo0 := transmitter.io.sdo0
  io.spi_sdo1 := transmitter.io.sdo1
  io.spi_sdo2 := transmitter.io.sdo2
  io.spi_sdo3 := transmitter.io.sdo3

  // Receiver connections
  receiver.io.en := rx_en
  receiver.io.en_quad_in := en_quad
  receiver.io.sdi0 := io.spi_sdi0
  receiver.io.sdi1 := io.spi_sdi1
  receiver.io.sdi2 := io.spi_sdi2
  receiver.io.sdi3 := io.spi_sdi3
  receiver.io.counter_in := counter_rx
  receiver.io.counter_in_upd := counter_rx_upd
  rx_done := receiver.io.rx_done
  io.data_rx <> receiver.io.data
  rx_clock_en := receiver.io.clk_en_o

  // Multiplexer for data_to_tx
  io.data_tx.ready := false.B
  data_to_tx.bits := MuxCase(0.U, Seq(
    (selector === 0.U) -> 0.U,
    (selector === 1.U) -> 0.U,
    (selector === 2.U) -> io.cmd,
    (selector === 3.U) -> io.addr,
    (selector === 4.U) -> io.data_tx.bits
  ))

  data_to_tx.valid := MuxCase(false.B, Seq(
    (selector === 0.U) -> false.B,
    (selector === 1.U) -> true.B,
    (selector === 2.U) -> data_valid,
    (selector === 3.U) -> data_valid,
    (selector === 4.U) -> io.data_tx.valid
  ))

  io.data_tx.ready := MuxCase(false.B, Seq(
    (selector === 4.U) -> data_to_tx.ready
  ))

  io.spi_status := spi_status // Default value for all bits

  // FSM
    switch(state) {
    is(idle) {
      spi_status := 1.U
      when(io.spi_rd || io.spi_wr || io.spi_qrd || io.spi_qwr) {
        cs_wire := false.B
        clock_en := true.B
        when(io.cmd_len =/= 0.U) {
          counter_tx := io.cmd_len
          counter_tx_upd := true.B
          selector := 2.U
          data_valid := true.B
          tx_en := true.B
          state := command
        }.elsewhen(io.addr_len =/= 0.U) {
          counter_tx := io.addr_len
          counter_tx_upd := true.B
          selector := 3.U
          data_valid := true.B
          tx_en := true.B
          state := address
        }.elsewhen(io.data_len =/= 0.U) {
          when(io.spi_qrd || io.spi_qrd) {
            when(io.dummy_len =/= 0.U) {
              counter_tx := io.dummy_len
              counter_tx_upd := true.B
              selector := 1.U
              tx_en := true.B
              state := dummy
            }.otherwise {
              counter_rx := io.data_len
              counter_rx_upd := true.B
              rx_en := true.B
              state := datarx
            }
          }.otherwise {
            counter_tx := io.data_len
            counter_tx_upd := true.B
            selector := 4.U
            //data_valid := true.B
            tx_en := true.B
            state := datatx
          }
        }.otherwise {
          //cs_wire := true.B
          state := idle
        }
      }
    }

    is(command) {
      spi_status := 2.U
      clock_en := true.B
      cs_wire := false.B
      when(tx_done) {
        when(io.addr_len =/= 0.U) {
          counter_tx := io.addr_len
          counter_tx_upd := true.B
          selector := 3.U
          data_valid := true.B
          tx_en := true.B
          state := address
        }.elsewhen(io.data_len =/= 0.U) {
          when(do_rx) {
            when(io.dummy_len =/= 0.U) {
              counter_tx := io.dummy_len
              counter_tx_upd := true.B
              selector := 1.U
              tx_en := true.B
              state := dummy
            }.otherwise {
              counter_rx := io.data_len
              counter_rx_upd := true.B
              rx_en := true.B
              state := datarx
            }
          }.otherwise {
            counter_tx := io.data_len
            counter_tx_upd := true.B
            selector := 4.U
            //data_valid := true.B
            tx_en := true.B
            state := datatx
          }
        }.otherwise {
          state := idle
        }
      }.otherwise {
        tx_en := true.B
      }
    }

    is(address) {
      spi_status := 3.U
      clock_en := true.B
      cs_wire := false.B
      when(tx_done) {
        when(io.data_len =/= 0.U) {
          when(do_rx) {
            when(io.dummy_len =/= 0.U) {
              counter_tx := io.dummy_len
              counter_tx_upd := true.B
              selector := 1.U
              tx_en := true.B
              state := dummy
            }.otherwise {
              counter_rx := io.data_len
              counter_rx_upd := true.B
              rx_en := true.B
              state := datarx
            }
          }.otherwise {
            counter_tx := io.data_len
            counter_tx_upd := true.B
            selector := 4.U
            //data_valid := true.B
            tx_en := true.B
            state := datatx
          }
        }.otherwise {
          state := idle
        }
      }.otherwise {
        tx_en := true.B
      }
    }

    is(dummy) {
      clock_en := true.B
      spi_status := 4.U
      cs_wire := false.B
      when(tx_done) {
        when(io.data_len =/= 0.U) {
          when(do_rx) {
            counter_rx := io.data_len
            counter_rx_upd := true.B
            rx_en := true.B
            state := datarx
          }.otherwise {
            counter_tx := io.data_len
            counter_tx_upd := true.B
            selector := 4.U
            data_valid := true.B
            tx_en := true.B
            state := datatx
          }
        }.otherwise {
          state := idle
        }
      }.otherwise {
        tx_en := true.B
      }
    }

    is(datatx) {
      spi_status := 5.U
      clock_en := tx_clock_en
      cs_wire := false.B
      selector := 4.U
      tx_en := true.B
      when(tx_done) {
        state := idle
        clock_en := false.B
      }.otherwise {
        state := datatx
      }
    }

    is(datarx) {
      spi_status := 6.U
      clock_en := rx_clock_en
      cs_wire := false.B
      when(rx_done) {
        state := wait_edge
      }.otherwise {
        state := datarx
        rx_en := true.B
      }
    }

    is(wait_edge) {
      spi_status := 7.U
      clock_en := true.B
      cs_wire := false.B
      when(spi_fall) {
        state := idle
      }.otherwise {
        state := wait_edge
      }
    }
  }


  io.cs := cs_wire
}

object qspi_master extends App {
  // Generate Verilog
  val annos = Seq(ChiselGeneratorAnnotation(() => new qspi_master()))
  (new ChiselStage).execute(args, annos)
}

