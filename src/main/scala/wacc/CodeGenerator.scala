package wacc

import scala.collection.mutable.ListBuffer
import wacc.Registers._
import wacc.AssignmentTranslations._

object CodeGenerator {
    var controlFlowGraph = new InstBlock()
    var currInstBlock = controlFlowGraph
    /* NEW: temporory design to accomodate label jumps */
    var controlFlowFuncs = ListBuffer[FuncBlock]()

    def translateAST(p: ProgramNode): Unit = {
        for (func <- p.funcList) {
            translate(func)
        }
        translate(p.stat)
    }

    def translate(f: FuncNode): Unit = {
        val funcBlock = new FuncBlock()

        // // Sample: To be corrected/checked
        // val varList: ListBuffer[Variable] = ListBuffer.empty
        // for (p <- f.paramList.paramList) {
        //     regList += new Variable()
        // }
        // funcBlock.param.addInst(PushInst(regList.toList))
        // //

        currInstBlock = funcBlock.body
        translate(f.stat)
    }

    def translate(node: StatNode): Unit = {
        node match {
            case SkipNode() | AssignIdentNode(_,_,_) | LValuesAssignNode(_,_) | ReadNode(_) | 
                 FreeNode(_) | ReturnNode(_) | ExitNode(_) | PrintNode(_) | PrintlnNode(_) | 
                 IfNode(_,_,_) | WhileNode(_,_) | BeginEndNode(_) | StatJoinNode(_) => 
                translate(node)
        }
    }
    def translate(node: SkipNode): Unit = {}

    def translate(node: AssignIdentNode): Unit = {
        val op = transRVal(node.rvalue)
        currInstBlock.addInst(MovInst(Variable(node.ident.name), op))

        // Pop back Caller Regs
        node.rvalue match {
            case CallNode(_, _) => currInstBlock.addInst(PopInst(List(r0, r1, r2, r3)))
            case _ => 
        }
    }

    def translate(node: LValuesAssignNode): Unit = {
        // Process right value first and store it in a temp reg
        val op = transRVal(node.rvalue)
        node.lvalue match {
            case IdentNode(name) => {
                currInstBlock.addInst(MovInst(Variable(name), op))
            }
            case ArrayElemNode(ident, exprList) => {
                currInstBlock.addInst(MovInst(r8, Variable(ident.name)))
                for (arrayNum <- 1 to exprList.length - 1) {
                    val index = translate(exprList(arrayNum))
                    currInstBlock.addInst(
                        // Ready for special convention for _arrLoad,
                        // r8 for array addr, r10 for index
                        PushInst(List(r8)),
                        MovInst(r10, index),
                        PopInst(List(r8)),
                        BranchLinkInst("_arrLoad"))
                }

                currInstBlock.addInst(
                    // Ready for special convention for _arrStore,
                    // r8 for the value to be stored, r9 for array addr, r10 for index
                    MovInst(r10, translate(exprList.last)),
                    MovInst(r9, op),
                    BranchLinkInst("_arrStore")
                    //arrstore to be defined
                )
            }
            case FstNode(lvalue) => storeToPairElemAddr(0, lvalue, op)
            case SndNode(lvalue) => storeToPairElemAddr(4, lvalue, op)
        }
    }

    def translate(node: ReadNode): Unit = {}
    def translate(node: FreeNode): Unit = {}
    def translate(node: ReturnNode): Unit = {
        val op = translate(node.expr)
        val inst = new MovInst(new FixedRegister(0), op)
        currInstBlock.addInst(inst)
    }
    def translate(node: ExitNode): Unit = {
        // currInstBlock.addInst()
        val op = translate(node.expr)
        val inst = new MovInst(new FixedRegister(0), op)
        val inst2 = new BranchLinkInst("exit")
        currInstBlock.addInst(inst)
        currInstBlock.addInst(inst2)
    }
    def translate(node: PrintNode): Unit = {
        /** 
         * TODO: push and pop r0 - r3 before calling
         * print may clobber any registers that are marked as caller-save under
         * arm's calling convention: R0, R1, R2, R3
        */
        val retOp = translate(node.expr)
        val exprTy = node.expr.typeVal()
        val printInstr = new PrintFunc()
        exprTy match {
            case CharIdentifier() => printInstr.printInt(retOp)
            case IntIdentifier() => ???
            case StrIdentifier() => ???
            case BoolIdentifier() => ???
            case a: ArrayIdentifier => ???
            case p: PairIdentifier => ???
            case _ => throw new IllegalArgumentException("print: not an expression")
        }
    }

    def translate(node: PrintlnNode): Unit = {
        // TODO: add new line
        translate(new PrintNode(node.expr))
    }

    def translate(node: IfNode): Unit = {
        val ifBlock = new IfBlock()
        currInstBlock = ifBlock.cond
        // translate cond
        currInstBlock = ifBlock.nextT
        translate(node.fstStat)
        currInstBlock = ifBlock.nextF
        translate(node.sndStat)
        currInstBlock = ifBlock.next
    }
    def translate(node: WhileNode): Unit = {
        val whileBlock = new WhileBlock()
        currInstBlock = whileBlock.cond
        // translate cond
        currInstBlock = whileBlock.loop
        translate(node.stat)
    }
    def translate(node: BeginEndNode): Unit = {
        translate(node.stat)
    }
    def translate(node: StatJoinNode): Unit = {
        for (n <- node.statList) {
            translate(n)
        }
    }

    def translate(node: ExprNode): Operand = {
        val immTrue = ImmVal(1, BoolIdentifier())
        val immFalse = ImmVal(0, BoolIdentifier())

        def translateComparisonOperators(op1: Operand, op2: Operand, cond: String, notcond: String): Register = {
            val reg =  TempRegister()
            op1 match {
                case ImmVal(num, ty) => {
                    val reg1 = TempRegister()
                    currInstBlock.addInst(MovInst(reg1, op1))
                    currInstBlock.addInst(CmpInst(reg1, op2))
                    currInstBlock.addInst(MovCondInst(cond, reg, immTrue))
                    currInstBlock.addInst(MovCondInst(notcond, reg, immFalse))
                }
                case _ => {
                    currInstBlock.addInst(CmpInst(op1.asInstanceOf[Register], op2))
                    currInstBlock.addInst(MovCondInst(cond, reg, immTrue))
                    currInstBlock.addInst(MovCondInst(notcond, reg, immFalse))
                }
            }
            return reg
        }
        
        node match {
            case IntLiterNode(n) => {
                if (n >= 0) {return ImmVal(n, node.typeVal())}
                else {
                    val reg = TempRegister()
                    val inst = LdrPseudoInst(reg, n)
                    currInstBlock.addInst(inst)
                    return reg
                }
            }
            case BoolLiterNode(true) => {
                return ImmVal(1, node.typeVal())
            }
            case BoolLiterNode(false) => {
                return ImmVal(0, node.typeVal())
            }
            case CharLiterNode(c) => {
                val num = c.toInt
                return ImmVal(num, node.typeVal())
            }
            case StrLiterNode(s) => {
                ???
            }            
            case PairLiterNode() => {
                return ImmVal(0, node.typeVal())
            }
            case NotNode(expr) => {
                var op = translate(expr)
                op match {
                    case ImmVal(num, ty) => {
                        if (num == 0) op = immTrue
                        else op = immFalse
                        return op 
                    }
                    // TODO: asInstanceOf or put all remaining cases
                    case _ => {
                        val inst1 = CmpInst(op.asInstanceOf[Register], ImmVal(1, expr.typeVal()))
                        val reg = TempRegister()
                        val inst2 = MovCondInst("NE", reg, ImmVal(1, expr.typeVal()))
                        val inst3 = MovCondInst("Eq", reg, ImmVal(0, expr.typeVal()))
                        currInstBlock.addInst(List(inst1, inst2, inst3))
                        // var inst4 = MovInst(op, reg)
                        return reg 
                    }
                }            
            }
            case NegNode(expr) => {
                val op = translate(expr)
                val reg = TempRegister()
                op match {
                    case ImmVal(num, ty) => {
                        currInstBlock.addInst(MovInst(reg, op))
                        currInstBlock.addInst(NegInst(reg, reg))
                    }
                    case _ => {
                        val inst = NegInst(reg, op.asInstanceOf[Register])
                        currInstBlock.addInst(inst)
                    }
                }
                return reg
            }
            case LenNode(expr) => {
                ???
            }
            case OrdNode(expr) => {
                val op = translate(expr)
                op match {
                    case ImmVal(num, ty) => {
                        return ImmVal(num, IntIdentifier())
                    }
                    case _ => {
                        val reg = TempRegister()
                        val inst = MovInst(reg, op)
                        currInstBlock.addInst(inst)
                        return reg
                    }
                }
            }
            case ChrNode(expr) => {
                val op = translate(expr)
                op match {
                    case ImmVal(num, ty) => {
                        return ImmVal(num, CharIdentifier())
                    }
                    case _ => {
                        val reg = TempRegister()
                        val inst = MovInst(reg, op)
                        currInstBlock.addInst(inst)
                        return reg
                    }
                }
            }
            case MulNode(fstexpr, sndexpr) => {
                val reg1 = TempRegister()
                val reg2 = TempRegister()
                val op1 = translate(fstexpr)
                currInstBlock.addInst(MovInst(reg1, op1))
                val op2 = translate(sndexpr)
                currInstBlock.addInst(MovInst(reg2, op2))
                val reg3 = TempRegister()
                val reg4 = TempRegister()
                currInstBlock.addInst(SmullInst(reg3, reg4, reg1, reg2))
                currInstBlock.addInst(CmpInst(reg4, ASR(reg3, 31)))
                currInstBlock.addInst(BranchCondInst("NE", "errOverflow"))
                return reg3
            }
            case DivNode(fstexpr, sndexpr) => {
                val reg0 = FixedRegister(0)
                val reg1 = FixedRegister(1)
                val op1 = translate(fstexpr)
                currInstBlock.addInst(MovInst(reg0, op1))
                val op2 = translate(sndexpr)
                currInstBlock.addInst(MovInst(reg1, op2))
                currInstBlock.addInst(CmpInst(reg1, ImmVal(0, IntIdentifier())))
                currInstBlock.addInst(BranchCondInst("Eq", "_errDivZero"))
                currInstBlock.addInst(BranchLinkInst("__aeabi_idivmod"))
                val reg2 = TempRegister()
                currInstBlock.addInst(MovInst(reg2, reg0))
                return reg2
            }
            case ModNode(fstexpr, sndexpr) => {
                val reg0 = FixedRegister(0)
                val reg1 = FixedRegister(1)
                val op1 = translate(fstexpr)
                currInstBlock.addInst(MovInst(reg0, op1))
                val op2 = translate(sndexpr)
                currInstBlock.addInst(MovInst(reg1, op2))
                currInstBlock.addInst(CmpInst(reg1, ImmVal(0, IntIdentifier())))
                currInstBlock.addInst(BranchCondInst("Eq", "_errDivZero"))
                currInstBlock.addInst(BranchLinkInst("__aeabi_idivmod"))
                val reg2 = TempRegister()
                currInstBlock.addInst(MovInst(reg2, reg1))
                return reg2
            }
            case AddNode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                op1 match {
                    case ImmVal(num, ty) => {
                        val reg1 = TempRegister()
                        currInstBlock.addInst(MovInst(reg1, op2))
                        val reg2 = TempRegister()
                        currInstBlock.addInst(AddsInst(reg2, reg1, op1))
                        return reg2
                    }
                    case _ => {
                        val reg = TempRegister()
                        currInstBlock.addInst(AddsInst(reg, op1.asInstanceOf[Register], op2))
                        return reg
                    }
                }
            }
            case SubNode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                op1 match {
                    case ImmVal(num, ty) => {
                        val reg1 = TempRegister()
                        currInstBlock.addInst(MovInst(reg1, op1))
                        val reg2 = TempRegister()
                        currInstBlock.addInst(SubsInst(reg2, reg1, op2))
                        return reg2
                    }
                    case _ => {
                        val reg = TempRegister()
                        currInstBlock.addInst(SubsInst(reg, op1.asInstanceOf[Register], op2))
                        return reg
                    }
                }
            }
            case GTNode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                return translateComparisonOperators(op1, op2, "GT", "LE")
            }
            case GTENode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                return translateComparisonOperators(op1, op2, "GE", "LT")
            }
            case LTNode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                return translateComparisonOperators(op1, op2, "LT", "GE")
            }
            case LTENode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                return translateComparisonOperators(op1, op2, "LE", "GT")
            }
            case EqNode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                return translateComparisonOperators(op1, op2, "Eq", "NE")
            }
            case IEqNode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                return translateComparisonOperators(op1, op2, "NE", "Eq")
            }
            // TODO: fix label
            case AndNode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                val reg = TempRegister()
                val label = Label()
                op1 match {
                    case ImmVal(num, ty) => {
                        val reg1 = TempRegister()
                        currInstBlock.addInst(MovInst(reg1, op1))
                        currInstBlock.addInst(CmpInst(reg1, immTrue))
                        currInstBlock.addInst(BranchCondInst("NE", "label"))
                        op2 match {
                            case ImmVal(num, ty) => {
                                val reg2 = TempRegister()
                                currInstBlock.addInst(MovInst(reg2, op2))
                                currInstBlock.addInst(CmpInst(reg2, immTrue))
                            }
                            case _ => {
                                currInstBlock.addInst(CmpInst(op2.asInstanceOf[Register], immTrue))
                            }
                        }
                    }
                    case _ => {
                       currInstBlock.addInst(CmpInst(op1.asInstanceOf[Register], immTrue)) 
                       currInstBlock.addInst(BranchCondInst("NE", "label"))
                       op2 match {
                            case ImmVal(num, ty) => {
                                val reg2 = TempRegister()
                                currInstBlock.addInst(MovInst(reg2, op2))
                                currInstBlock.addInst(CmpInst(reg2, immTrue))
                            }
                            case _ => {
                                currInstBlock.addInst(CmpInst(op2.asInstanceOf[Register], immTrue))
                            }
                        }
                    }
                }
                currInstBlock.addInst(label)
                currInstBlock.addInst(MovCondInst("Eq", reg, immTrue))
                currInstBlock.addInst(MovCondInst("NE", reg, immFalse))
                return reg
            }
            case OrNode(fstexpr, sndexpr) => {
                val op1 = translate(fstexpr)
                val op2 = translate(sndexpr)
                val reg = TempRegister()
                val label = Label()
                op1 match {
                    case ImmVal(num, ty) => {
                        val reg1 = TempRegister()
                        currInstBlock.addInst(MovInst(reg1, op1))
                        currInstBlock.addInst(CmpInst(reg1, immTrue))
                        currInstBlock.addInst(BranchCondInst("Eq", "label"))
                        op2 match {
                            case ImmVal(num, ty) => {
                                val reg2 = TempRegister()
                                currInstBlock.addInst(MovInst(reg2, op2))
                                currInstBlock.addInst(CmpInst(reg2, immTrue))
                            }
                            case _ => {
                                currInstBlock.addInst(CmpInst(op2.asInstanceOf[Register], immTrue))
                            }
                        }
                    }
                    case _ => {
                       currInstBlock.addInst(CmpInst(op1.asInstanceOf[Register], immTrue)) 
                       currInstBlock.addInst(BranchCondInst("Eq", "label"))
                       op2 match {
                            case ImmVal(num, ty) => {
                                val reg2 = TempRegister()
                                currInstBlock.addInst(MovInst(reg2, op2))
                                currInstBlock.addInst(CmpInst(reg2, immTrue))
                            }
                            case _ => {
                                currInstBlock.addInst(CmpInst(op2.asInstanceOf[Register], immTrue))
                            }
                        }
                    }
                }
                currInstBlock.addInst(label)
                currInstBlock.addInst(MovCondInst("Eq", reg, immTrue))
                currInstBlock.addInst(MovCondInst("NE", reg, immFalse))
                return reg
            }
        }
    }
}
