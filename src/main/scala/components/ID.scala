package components
import chisel3._

class InstructionDecode extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val writeData = Input(UInt(64.W))
    val writeReg = Input(UInt(5.W))
    val pcAddress = Input(UInt(64.W))
    val ctl_writeEnable = Input(Bool())
    val id_ex_mem_read = Input(Bool())
    val id_ex_rd = Input(UInt(5.W))
    //for forwarding
    val ex_mem_ins = Input(UInt(32.W))
    val mem_wb_ins = Input(UInt(32.W))
    val ex_mem_result = Input(UInt(64.W))
    val mem_wb_result = Input(UInt(64.W))
    
    //Outputs
    val immediate = Output(UInt(64.W))
    val writeRegAddress = Output(UInt(5.W))
    val readData1 = Output(UInt(64.W))
    val readData2 = Output(UInt(64.W))
    val func7 = Output(UInt(1.W))
    val func3 = Output(UInt(3.W))
    val ctl_aluSrc = Output(Bool())
    val ctl_memToReg = Output(UInt(2.W))
    val ctl_regWrite = Output(Bool())
    val ctl_memRead = Output(Bool())
    val ctl_memWrite = Output(Bool())
    val ctl_branch = Output(Bool())
    val ctl_aluOp = Output(UInt(2.W))
    val ctl_jump = Output(UInt(2.W))
    val hdu_pcWrite = Output(Bool())
    val hdu_if_reg_write = Output(Bool())
    val pcSrc = Output(Bool())
    val pcPlusOffset = Output(UInt(64.W))
    val ifid_flush = Output(Bool())
  })

  //Hazard Detection Unit
  val hdu = Module(new HazardUnit)
  hdu.io.memRead := io.id_ex_mem_read
  hdu.io.rd := io.id_ex_rd
  hdu.io.rs1 := io.instruction(19, 15)
  hdu.io.rs2 := io.instruction(24, 20)
  hdu.io.jump := io.ctl_jump
  hdu.io.taken := io.ctl_branch
  io.hdu_pcWrite := hdu.io.pc_write
  io.hdu_if_reg_write := hdu.io.if_reg_write

  //Control Unit
  val control = Module(new Control)
  control.io.in := io.instruction(6, 0)
  when(hdu.io.ctl_mux) {
    io.ctl_aluOp := control.io.aluOp
    io.ctl_aluSrc := control.io.aluSrc
    io.ctl_branch := control.io.branch
    io.ctl_memRead := control.io.memRead
    io.ctl_memToReg := control.io.memToReg
    io.ctl_memWrite := control.io.memWrite
    io.ctl_regWrite := control.io.regWrite
    io.ctl_jump := control.io.jump

  }.otherwise {
    io.ctl_aluOp := DontCare
    io.ctl_aluSrc := DontCare
    io.ctl_branch := DontCare
    io.ctl_memRead := DontCare
    io.ctl_memToReg := DontCare
    io.ctl_memWrite := false.B
    io.ctl_regWrite := false.B
    io.ctl_jump := 0.U
  }

  //Register File
  val registers = Module(new Registers)
  val registerRd = io.writeReg
  val registerRs1 = io.instruction(19, 15)
  val registerRs2 = io.instruction(24, 20)
  registers.io.readAddress(0) := registerRs1
  registers.io.readAddress(1) := registerRs2
  registers.io.writeEnable := io.ctl_writeEnable
  registers.io.writeAddress := registerRd
  registers.io.writeData := io.writeData

  //Forwarding to fix structural hazard
  when(io.ctl_writeEnable && (io.writeReg === registerRs1)){
    when(registerRs1 === 0.U){
      io.readData1 := 0.U
    }.otherwise{
      io.readData1 := io.writeData
    }
  }.otherwise{
    io.readData1 := registers.io.readData(0)
  }
  when(io.ctl_writeEnable && (io.writeReg === registerRs2)){
    when(registerRs2 === 0.U){
      io.readData2 := 0.U
    }.otherwise{
      io.readData2 := io.writeData
    }
  }.otherwise{
    io.readData2 := registers.io.readData(1)
  }
  

  val immediate = Module(new ImmediateGen)
  immediate.io.instruction := io.instruction
  io.immediate := immediate.io.out

  // Branch Forwarding
  val input1 = Wire(UInt(64.W))
  val input2 = Wire(UInt(64.W))

  when(registerRs1 === io.ex_mem_ins(11, 7)) {
    input1 := io.ex_mem_result
  }.elsewhen(registerRs1 === io.mem_wb_ins(11, 7)) {
      input1 := io.mem_wb_result
    }
    .otherwise {
      input1 := io.readData1
    }
  when(registerRs2 === io.ex_mem_ins(11, 7)) {
    input2 := io.ex_mem_result
  }.elsewhen(registerRs2 === io.mem_wb_ins(11, 7)) {
      input2 := io.mem_wb_result
    }
    .otherwise {
      input2 := io.readData2
    }

  //Branch Unit
  val bu = Module(new BranchUnit)
  bu.io.branch := io.ctl_branch
  bu.io.funct3 := io.instruction(14, 12)
  bu.io.rd1 := input1
  bu.io.rd2 := input2

  //Offset Calculation (Jump/Branch)
  when(io.ctl_jump === 1.U) {
    io.pcPlusOffset := io.pcAddress + io.immediate
  }.elsewhen(io.ctl_jump === 2.U) {
      io.pcPlusOffset := io.pcAddress + input1
    }
    .otherwise {
      io.pcPlusOffset := io.pcAddress + immediate.io.out
    }

  when(bu.io.taken || io.ctl_jump =/= 0.U) {
    io.pcSrc := true.B
  }.otherwise {
    io.pcSrc := false.B
  }

  //Instruction Flush
  io.ifid_flush := hdu.io.ifid_flush

  io.writeRegAddress := io.instruction(11, 7)
  io.func3 := io.instruction(14, 12)
  io.func7 := io.instruction(30)
}
