package wacc

sealed trait ASTNode {
    def semanticCheck(): Unit = {
    }
}

case class ProgramNode(funcList: List[FuncNode], stat: StatNode) extends ASTNode {
    val funcs = funcList
    val statNode = stat

    override def semanticCheck(): Unit = {
        for (f <- funcList) {
            f.semanticCheck()
        }
        stat.semanticCheck()
    }
}

case class FuncNode(ty: TypeNode, ident: IdentNode, paramList: ParamListNode, stat: StatNode) extends ASTNode {
    ident.symbolTableName = "f" + SemanticChecker.currScope() + "!" + ident.name
    ident.scope = SemanticChecker.currScope()
    /**
      * Checks for:
        1. legal params
        2. legal stats
        3. legal type (tbc)
        4. leagal func ident
        5. return type matches function type
      */

    override def semanticCheck(): Unit = {
        ty.semanticCheck()
        ident.semanticCheck()
        paramList.semanticCheck()
        stat.semanticCheck()
    }
}

case class ParamListNode(paramList: List[ParamNode]) extends ASTNode {
    /**
      * Checks for:
        1. identifier names are all different
        */
    override def semanticCheck(): Unit = {
        for (p <- paramList) {
            p.semanticCheck()
        }
    }
}

case class ParamNode(ty: TypeNode, ident: IdentNode) extends ASTNode {
    ty.semanticCheck()
    ident.semanticCheck()
}

// StatNode
sealed trait StatNode extends ASTNode

case class SkipNode() extends StatNode

case class AssignIdentNode(ty: TypeNode, ident: IdentNode, rvalue: RValueNode) extends StatNode {

    /**
     * Checks for:
       1. ident's name not declared in scope
       2. RHS val and type consistent 
       3. rval is semantically correct (TODO)
       4. lhs type is not func (type check should catch)
      */
    ident.symbolTableName = "v" + SemanticChecker.currScope() + "!" + ident.name
    ident.scope = SemanticChecker.currScope()
    override def semanticCheck(): Unit = {
        SemanticChecker.validDeclaration(ident)
        rvalue.semanticCheck()
        if (SemanticChecker.validDeclaration(ident) && SemanticChecker.typeCheck(ty, rvalue)) {
            SemanticChecker.symbolTable.add(ident.symbolTableName, new VarIdentifier(SemanticChecker.findTypeR(rvalue)))
        }
    }
}

case class LValuesAssignNode(lvalue: LValueNode, rvalue: RValueNode) extends StatNode {
    /**
      * check for:
        1. reassignment to itself (TODO pairs)
        2. lhs type is not func (type check should catch)
      */
    override def semanticCheck(): Unit = {
        lvalue.semanticCheck()
        rvalue.semanticCheck()

        var lname = ""
        var rname = ""

        var bothDefined = true

        /* check that ident(left) is not asssigned to itself */
        val lValTableHName = lvalue match{
            case id: IdentNode => {
                id.symbolTableName = "v" + SemanticChecker.currScope() + "!" + id.name
                lname = id.name
                if (SemanticChecker.tableContainsIdentifier(id)) {
                    
                    id.symbolTableName
                } else {
                    bothDefined = false
                    "NO SUCH THING AS -" + "v" + SemanticChecker.currScope() + "!" + id.name
                }
            }              
            case _ => "ARRAY: should pass test PAIR: TODO"
        }

        val rValTableHName = rvalue match{
            case id: IdentNode => {
                id.symbolTableName = "v" + SemanticChecker.currScope() + "!" + id.name
                rname = id.name
                if (SemanticChecker.tableContainsIdentifier(id)) {
                    id.symbolTableName
                } else {
                    bothDefined = false
                    "NO SUCH THING AS -" + "v" + SemanticChecker.currScope() + "!" + id.name
                }
            }     
            case _ => "ARRAY: should pass test PAIR: TODO"
        }

        if (lValTableHName == rValTableHName) {
            SemanticChecker.errorMessage += "Reassignment to same variable: " + lname + " to " + rname + "\n"
        }     
        else if (bothDefined) {
            if (SemanticChecker.typeCheck(lvalue, rvalue)) {
                SemanticChecker.errorMessage += "successful assignment of variable: " + lValTableHName + " to " + rValTableHName + "\n"
            }
        }
    }
}

case class ReadNode(lvalue: LValueNode) extends StatNode {
    override def semanticCheck(): Unit = {
        lvalue.semanticCheck()
    }
}

case class FreeNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
    }
}

case class ReturnNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
    }
}

case class ExitNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
    }
}

case class PrintNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
    }
}

case class PrintlnNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
    }
}

case class IfNode(expr: ExprNode, fstStat: StatNode, sndStat: StatNode) extends StatNode {
override def semanticCheck(): Unit = {
        SemanticChecker.checkIfWhileCond(expr)
        expr.semanticCheck()
        SemanticChecker.scope += 1
        fstStat.semanticCheck()
        sndStat.semanticCheck()
    }
}

case class WhileNode(expr: ExprNode, stat: StatNode) extends StatNode  {
    override def semanticCheck(): Unit = {
        SemanticChecker.checkIfWhileCond(expr)
        expr.semanticCheck()
        SemanticChecker.scope += 1
        stat.semanticCheck()
    }
}

case class BeginEndNode(stat: StatNode) extends StatNode  {
    override def semanticCheck(): Unit = {
        stat.semanticCheck()
    }
}

case class StatJoinNode(statList: List[StatNode]) extends StatNode  {
    override def semanticCheck(): Unit = {
        for (s <- statList) {
            s.semanticCheck()
        }
    }
}

// LValueNode
sealed trait LValueNode extends ASTNode

case class IdentNode(name: String) extends LValueNode with ExprNode { 
    // symbol table name is decided during traversal of nodes to do semantic checks
    var scope = 0
    var symbolTableName = "PLACEHODER: " + name + " : " + scope
}

case class ArrayElemNode(ident: IdentNode, exprList: List[ExprNode]) extends LValueNode with ExprNode {
    override def semanticCheck(): Unit = {
        SemanticChecker.validDeclaration(ident)
        ident.semanticCheck()
        for (e <- exprList) {
            e.semanticCheck()
        }
    }
}

sealed trait PairElemNode extends LValueNode with RValueNode

case class FstNode(lvalue: LValueNode) extends PairElemNode {
    override def semanticCheck(): Unit = {
        lvalue.semanticCheck()
    }
}

case class SndNode(lvalue: LValueNode) extends PairElemNode {
    override def semanticCheck(): Unit = {
        lvalue.semanticCheck()
    }
}

// RValueNode
sealed trait RValueNode extends ASTNode

sealed trait ExprNode extends RValueNode

case class ArrayLiterNode(expr: ExprNode, exprList: List[ExprNode]) extends ExprNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        for (e <- exprList) {
            e.semanticCheck()
        }
    }
}

case class NewPairNode(fstExpr: ExprNode, sndExpr: ExprNode) extends RValueNode {
    override def semanticCheck(): Unit = {
        fstExpr.semanticCheck()
        sndExpr.semanticCheck()
    }
}


case class CallNode(ident: IdentNode, argList: ArgListNode) extends RValueNode {
    override def semanticCheck(): Unit = {
        ident.semanticCheck()
        argList.semanticCheck()
    }
}


case class ArgListNode(exprList: List[ExprNode]) extends ASTNode {
    override def semanticCheck(): Unit = {
        for (e <- exprList) {
            e.semanticCheck()
        }
    }
}

sealed trait TypeNode extends ASTNode {
    val typeVal: String
}

case class BaseTypeNode(ty: String) extends TypeNode with PairElemTypeNode {
    val typeVal = ty
}

case class ArrayTypeNode(ty: TypeNode) extends TypeNode with PairElemTypeNode {
    def countDimension(ty: TypeNode): Int = {
        return ty match {
            case ArrayTypeNode(arrayTy) => 1 + countDimension(arrayTy)
            case node => return 1
        }  
    }

    def getType(ty: TypeNode): TypeNode = {
        return ty match {
            case ArrayTypeNode(arrayTy) => getType(arrayTy)
            case node => return node
        }  
    }
    val dimension = countDimension(ty)
    val baseType = getType(ty)
    val typeVal = baseType.typeVal + ":" + dimension
}


case class PairTypeNode(fstPET: PairElemTypeNode, sndPET: PairElemTypeNode) extends TypeNode {
    val typeVal = "pair"
    override def semanticCheck(): Unit = {
        fstPET.semanticCheck()
        sndPET.semanticCheck()
    }
}

// PairElemTypeNode
sealed trait PairElemTypeNode extends ASTNode

case class PETPairNode() extends PairElemTypeNode

case class IntLiterNode(n: Int) extends ExprNode

case class BoolLiterNode(b: Boolean) extends ExprNode

case class CharLiterNode(c: Char) extends ExprNode

case class StrLiterNode(s: String) extends ExprNode

case class PairLiterNode() extends ExprNode

case class UnOpExprNode(op: UnaryOperNode, expr: ExprNode) extends ExprNode {
    override def semanticCheck(): Unit = {
        op.semanticCheck()
        expr.semanticCheck()
    }
}

case class BinOpExprNode(fstExpr: ExprNode, op: BinaryOperatorNode, sndExpr: ExprNode) extends ExprNode {
    val evalType = op match {
        case BinaryOperatorNode("") | BinaryOperatorNode("") | BinaryOperatorNode("")| BinaryOperatorNode("") | BinaryOperatorNode("")
                => "int"
        case _ => "boolean"
    }

    override def semanticCheck(): Unit = {
        fstExpr.semanticCheck()
        op.semanticCheck()
        sndExpr.semanticCheck()
    }
}

case class BracketExprNode(expr: ExprNode) extends ExprNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
    }
}

case class UnaryOperNode(op: String) extends ASTNode

case class BinaryOperatorNode(op: String) extends ASTNode 