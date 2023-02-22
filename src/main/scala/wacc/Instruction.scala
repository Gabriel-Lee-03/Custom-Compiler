package wacc

sealed trait Instruction

case class AddInst(rd: Register, rn: Register, op: Operand) extends Instruction
case class SubInst(rd: Register, rn: Register, op: Operand) extends Instruction
case class MulInst(rd: Register, rm: Register, op: Operand) extends Instruction

case class CmpInst(rn: Register, op: Operand) extends Instruction
case class MovInst(rd: Register, op: Operand) extends Instruction
case class MovEqInst(rd: Register, op: Operand) extends Instruction
case class MovNEqInst(rd: Register, op: Operand) extends Instruction
case class AndInst(rd: Register, op: Operand) extends Instruction
case class OrInst(rd: Register, op: Operand) extends Instruction

case class LdrInst(rd: Register, op: Operand) extends Instruction
case class StrInst(rd: Register, op: Operand) extends Instruction
case class PushInst(regList: List[Register]) extends Instruction
case class PopInst(regList: List[Register]) extends Instruction
case class BranchInst(label: String) extends Instruction
case class BranchLinkInst(label: String) extends Instruction

// To be moved to assign register part
// sealed trait Register
// E.g. R1: Register(1)
// case class Register() {
//     var num = ControlFlowGraph.nextRegNum
//     ControlFlowGraph.nextRegNum += 1
// }


sealed trait Operand
sealed trait Register extends Operand
case class TempRegister() extends Register
case class FixedRegister(num: Int) extends Register
case class Variable(name: String) extends Register
// TODO: replace string with identifier for type
case class ImmVal(num: Int, t: TypeIdentifier) extends Operand