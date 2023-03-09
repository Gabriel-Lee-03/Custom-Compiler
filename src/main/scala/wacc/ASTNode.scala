package wacc

import scala.collection.mutable.ListBuffer

import wacc.{ArrayIdentifier, BoolIdentifier, PairIdentifier, FuncIdentifier, NullIdentifier, AnyIdentifier, IntIdentifier, StrIdentifier, CharIdentifier}
sealed trait ASTNode {
    def semanticCheck(): Unit = {}
}

case class ProgramNode(funcList: List[FuncNode], stat: StatNode) extends ASTNode {

    override def semanticCheck(): Unit = {
        for (f <- funcList) {
            f.addToSymbolTable()
        }
        for (f <- funcList) {
            f.semanticCheck()
        }
        SemanticChecker.insideFunc = false
        stat.semanticCheck()
    }
}

case class FuncNode(ty: TypeNode, ident: IdentNode, paramList: ParamListNode, 
                    stat: StatNode) extends ASTNode {
    override def semanticCheck(): Unit = {
        ident.isFunction = true
        
        SemanticChecker.scopeStack.push(SemanticChecker.nextScope)
        SemanticChecker.nextScope += 1

        paramList.semanticCheck()

        for (param <- paramList.paramList) {
            // Check for repeated parameter names
            if (SemanticChecker.symbolTable.checkVarDefined(param.ident.name)) {
                 Error.addSemErr(s"Repeated parameter name \"${param.ident.name}\"")
            }
            else {
                SemanticChecker.symbolTable.addVar(param.ident.name, param.ty.typeVal())
                param.ident.newName = SemanticChecker.currScope().toString() + "!" + param.ident.name
            }
        }
        SemanticChecker.scopeStack.push(SemanticChecker.nextScope)
        SemanticChecker.nextScope += 1

        stat.semanticCheck()
        // Check that return type matches function return type
        checkReturnType(ty, stat)
        SemanticChecker.scopeStack.pop()
        SemanticChecker.scopeStack.pop()
    }

    def checkReturnType(ty: TypeNode, stat: StatNode): Unit = {
        stat match {
            case r: ReturnNode => {
                if (!SemanticChecker.typeCheck(ty.typeVal(), r.expr.typeVal())) {
                    Error.addSemErr("return type should be" +ty.typeVal() + ", unexpected " + r.expr.typeVal())
                }
            }
            case b: BeginEndNode => checkReturnType(ty, b.stat)
            case i: IfNode => {
                checkReturnType(ty, i.fstStat)
                checkReturnType(ty, i.sndStat)
            }
            case w: WhileNode => checkReturnType(ty, w.stat)
            case s: StatJoinNode => {
                for (st <- s.statList) {
                    checkReturnType(ty, st)
                }
            }
            case _ =>
        }
    }

    def addToSymbolTable(): Unit = {
        ty.semanticCheck()

        val paramtypeList = ListBuffer[TypeIdentifier]()
        for (param <- paramList.paramList) {
            paramtypeList += param.ty.typeVal()
        }

        // Check if function name is used
        SemanticChecker.symbolTable.getFuncNewName(ident.name, paramtypeList.toList, ty.typeVal()) match {
            case Some(newName) => {
                Error.addSemErr(s"Function \"${ident.name}\" is defined more than once with the same parameter and return types")
            }
            // Add to symbol table
            case _ => {
                ident.newName = SemanticChecker.symbolTable.addFunc(ident.name, paramtypeList.toList, ty.typeVal())
            }
        }
    }
}


case class ParamListNode(paramList: List[ParamNode]) extends ASTNode {
    override def semanticCheck(): Unit = {
        for (p <- paramList) {
            p.semanticCheck()
        }
    }
}

case class ParamNode(ty: TypeNode, ident: IdentNode) extends ASTNode {
    override def semanticCheck(): Unit = {
        ty.semanticCheck()
    }
}

// StatNode
sealed trait StatNode extends ASTNode

case class SkipNode() extends StatNode

case class AssignIdentNode(ty: TypeNode, ident: IdentNode, rvalue: RValueNode) extends StatNode {
    override def semanticCheck(): Unit = {AnyIdentifier
        ty.semanticCheck()
        rvalue match {
            case c: CallNode => c.returnType = Some(ty.typeVal())
            case _ =>
        }
        rvalue.semanticCheck()
        
        // Check if variable name is already declared in the same scope
        if (SemanticChecker.symbolTable.checkVarDefined(ident.name)){
            Error.addSemErr(s"Variable name \"${ident.name}\" is already used in the same scope")
        }
        // Add to symbol table and get type
        else {
            rvalue.typeVal() match {
                case b: BoolIdentifier => SemanticChecker.symbolTable.addVar(ident.name, b)
                case c: CharIdentifier => SemanticChecker.symbolTable.addVar(ident.name, c)
                case i: IntIdentifier => SemanticChecker.symbolTable.addVar(ident.name, i)
                case s: StrIdentifier => SemanticChecker.symbolTable.addVar(ident.name, s)                    
                case ArrayIdentifier(baseTy, dim) => {
                    if (baseTy.typeEquals(new AnyIdentifier)) {
                        SemanticChecker.symbolTable.addVar(ident.name, ty.typeVal())
                    } else {
                        SemanticChecker.symbolTable.addArray(ident.name, baseTy, dim)
                    }
                    
                    }
                case p: PairIdentifier => SemanticChecker.symbolTable.addVar(ident.name, p)
                case any: AnyIdentifier => SemanticChecker.symbolTable.addVar(ident.name, any)
                case n: NullIdentifier => SemanticChecker.symbolTable.addVar(ident.name, ty.typeVal())
                case ty => throw new IllegalArgumentException (s"type ${ty} is not a assignable rvalue element")
            }
            // SemanticChecker.debugMessage += "new pair added: " + SemanticChecker.symbolTable.toString()
            ident.newName = SemanticChecker.currScope().toString() + "!" + ident.name
        }

        /* check whether rvalue type matches that of TypeNode */
        if (!SemanticChecker.typeCheck(ty.typeVal(), rvalue.typeVal())) {
            Error.addSemErr("definition: type mismatch: expected "+ ty.typeVal().toString()
                        + s" for ${ident.name}, gets "
                        + rvalue.typeVal().toString())
        }
    }        
}


// Example: a=5
case class LValuesAssignNode(lvalue: LValueNode, rvalue: RValueNode) extends StatNode {
    override def semanticCheck(): Unit = { 
        lvalue.semanticCheck()
        rvalue match {
            case c: CallNode => c.returnType = Some(lvalue.typeVal())
            case _ =>
        }
        rvalue.semanticCheck()
        SemanticChecker.debugMessage += s"assigning ${rvalue.typeVal()} to ${lvalue.typeVal()}"
        lvalue match {
            case i: IdentNode => {
                val func = SemanticChecker.symbolTable.lookUpFunc(i.newName)
                func match {
                    case Some(FuncIdentifier(_,_,_)) => Error.addSemErr(s"function ${i.newName} cannot be assigned with any values\n")
                    case _ => 
                }
            }
            case _ => 
        }

        rvalue match {
            case i: IdentNode => {
                val func = SemanticChecker.symbolTable.lookUpFunc(i.newName)
                func match {
                    case Some(FuncIdentifier(_,_,_)) => Error.addSemErr(s"function ${i.name} cannot be assigned as values\n")
                    case _ =>
                }
            }
            case _ => 
        }
        
        val lhsType = lvalue.typeVal()
        val rhsType = rvalue.typeVal()
        if (!SemanticChecker.typeCheck(lhsType, rhsType)) {
                Error.addSemErr(s"assignment: type mismatch: expected ${lvalue.typeVal().toString}, gets ${rvalue.typeVal().toString()}")
        }else{
            SemanticChecker.debugMessage += (s"lhs type: ${lvalue.typeVal()} rhs type: ${rvalue.typeVal()}")
        }

        val undefinedTypeAssign = lhsType match {
            case a: AnyIdentifier => {
                rhsType match {
                    case a: AnyIdentifier => true
                    case _ => false
                }
            }
            case _ => false
        }

        if (undefinedTypeAssign) {
            Error.addSemErr("unidentified type for both LHS RHS type assignment\n")
        }
        
    }

}

case class ReadNode(lvalue: LValueNode) extends StatNode {
    override def semanticCheck(): Unit = {
        lvalue.semanticCheck()
        lvalue.typeVal() match {
            case IntIdentifier() | CharIdentifier() =>
            case AnyIdentifier() =>  
            case ty => Error.addSemErr(s"read type error: unexpected ${lvalue.typeVal()}" +
                    "(expect variable with type char or int)")
        }
    }
}

case class FreeNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        val ty = expr.typeVal()
        // Check if type is array or pair
        if ((!SemanticChecker.typeIsPair(ty) && !SemanticChecker.typeIsArray(ty))) {
            Error.addSemErr(s"free type error: unexpected ${ty} (expected: at least " +
                            "1-dimension array or pair)")
        } 
    }
}

case class ReturnNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        if (!SemanticChecker.insideFunc) {
            Error.addSemErr("return outside of function is not allowed")
        }
        expr.semanticCheck()
        expr match {
            case i: IdentNode => SemanticChecker.tableContainsIdentifier(i)
            case a: ArrayElemNode => SemanticChecker.tableContainsIdentifier(a.ident)

            case _ =>
        }
    }
}

case class ExitNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        if (!SemanticChecker.typeCheck(new IntIdentifier, expr.typeVal())) {
            Error.addSemErr(s"free type error: unexpected ${expr.typeVal()} (expected: int)")
        }
    }
}

case class PrintNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        expr match {
            case i: IdentNode => SemanticChecker.tableContainsIdentifier(i)
            case _ =>
        }
    }
}

case class PrintlnNode(expr: ExprNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        expr match {
            case i: IdentNode => SemanticChecker.tableContainsIdentifier(i)
            case _ =>
        }
    }
}

case class IfNode(expr: ExprNode, fstStat: StatNode, sndStat: StatNode) extends StatNode {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()

        SemanticChecker.scopeStack.push(SemanticChecker.nextScope)
        SemanticChecker.nextScope += 1
        fstStat.semanticCheck()
        SemanticChecker.scopeStack.pop()

        SemanticChecker.scopeStack.push(SemanticChecker.nextScope)
        SemanticChecker.nextScope += 1
        sndStat.semanticCheck()
        SemanticChecker.scopeStack.pop()
        
        if (!SemanticChecker.typeCheck(new BoolIdentifier, expr.typeVal())) {
            Error.addSemErr(s"if condition type error: unexpected ${expr.typeVal()} " +
                            "(expected: bool)")
        }
    }
}

case class WhileNode(expr: ExprNode, stat: StatNode) extends StatNode  {
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        SemanticChecker.scopeStack.push(SemanticChecker.nextScope)
        SemanticChecker.nextScope += 1
        stat.semanticCheck()
        SemanticChecker.scopeStack.pop()

        if (!SemanticChecker.typeCheck(new BoolIdentifier, expr.typeVal())) {
            Error.addSemErr(s"while condition type error: unexpected ${expr.typeVal()} " +
                            "(expected: bool)")
        }
    }
}

case class BeginEndNode(stat: StatNode) extends StatNode  {
    override def semanticCheck(): Unit = {
        SemanticChecker.scopeStack.push(SemanticChecker.nextScope)
        SemanticChecker.nextScope += 1
        stat.semanticCheck()
        SemanticChecker.scopeStack.pop()
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
sealed trait LValueNode extends ASTNode {
    def typeVal(): TypeIdentifier
}

case class IdentNode(name: String) extends LValueNode with ExprNode {            
    var newName: String = null
    var isFunction: Boolean = false
    override def typeVal() = {
        val identifier = SemanticChecker.symbolTable.lookUpVar(name)
        identifier match {
            case Some(ty) => ty
            /* semantic error: identifier not defined */
            case None => new AnyIdentifier
        }
    }
    override def semanticCheck(): Unit = {
        val identifier = SemanticChecker.symbolTable.lookUpVar(name)
        identifier match {
            case None => Error.addSemErr(s"${identifier} variable \"${name}\" is not in scope " +
              s"or not defined\n")
            case _ => {
                if (!isFunction) {
                    newName = SemanticChecker.symbolTable.getVarName(name)
                }
            }
        }

    }
}

// Example: a[1][b]
case class ArrayElemNode(ident: IdentNode, exprList: List[ExprNode]) extends LValueNode with ExprNode {
    /* temporary type val before typeval is called */
    var arrayType: TypeIdentifier = new AnyIdentifier
    var arrayDim = 0
    override def typeVal() = {
        val identifier = SemanticChecker.symbolTable.lookUpVar(ident.name)
        identifier match {
            case Some(ArrayIdentifier(baseTy: TypeIdentifier, dim: Int)) => {
                arrayDim = dim - exprList.length
                if (arrayDim > 0) {
                    new ArrayIdentifier(baseTy, arrayDim)
                } else if (arrayDim == 0) {
                    baseTy
                } else {
                    /* semantic error: expr list longer than dimension of array declared for var */
                    new AnyIdentifier()                                    
                }
            }
            case Some(ty) => {
                /* semantic error: non-array elem should not be assigned as arrayelem node */
                new AnyIdentifier


            }
            /* semantic error: variable not defined */
            case None => new AnyIdentifier
        }
     }

    override def semanticCheck(): Unit = {
        if (SemanticChecker.tableContainsIdentifier(ident)) {
            val identifier = SemanticChecker.symbolTable.lookUpVar(ident.name)
            identifier match {
                case (a@Some(ArrayIdentifier(tableBaseTy: TypeIdentifier, tableDim: Int))) => {
                    arrayType = tableBaseTy
                    arrayDim = tableDim
                    for (e <- exprList) {
                        arrayDim -= 1
                        e.semanticCheck()
                        if (!e.typeVal().typeEquals(new IntIdentifier)) {
                            Error.addSemErr(s"array elem index: unexpected type ${e.typeVal().toString()}, expected int")
                        }
                    }
                    if (arrayDim < 0) {
                        Error.addSemErr(s"array dimension error: unexpected type " +
                        s"${(new ArrayIdentifier(tableBaseTy, exprList.length)).toString()} at ${ident.name}, expected ${a.get.toString()}")   
                    }
                }
                case None => Error.addSemErr(s"variable \"${ident.name}\" is not in scope")
                case ty => Error.addSemErr(s"expect at least 1 dimension array, gets ${ty.toString()}")
            }
        }
        ident.semanticCheck()
    }
}

sealed trait PairElemNode extends LValueNode with RValueNode

case class FstNode(lvalue: LValueNode) extends PairElemNode {
    override def typeVal() = {
        val ty = lvalue match {
            case IdentNode(name) => {
                SemanticChecker.symbolTable.lookUpVar(name) match {
                    case Some(vartype) => vartype
                    /* semantic error: identifier is not in th symmbol table */
                    case None => new AnyIdentifier
                } 
            }
            case lval => lval.typeVal()
        }

        ty match {
            case p: PairIdentifier => p.ty1
            case n: NullIdentifier => n
            case _ => {
                /* semantic error: wrong lvalue type*/
                new AnyIdentifier
            }
        } 
    }
    override def semanticCheck(): Unit = {
        lvalue.semanticCheck()

        val ty = lvalue match {
            case IdentNode(name) => {
                SemanticChecker.symbolTable.lookUpVar(name) match {
                    case Some(vartype) => vartype
                    /* semantic error: identifier is not in th symmbol table: case caught in lval semantic check */
                    case None => new AnyIdentifier
                } 
            }
            case lval => lval.typeVal()
        }

        ty match {
            case p: PairIdentifier => {
                SemanticChecker.debugMessage += s"${lvalue} is ${p}"
            }
            case n: NullIdentifier => {
                SemanticChecker.debugMessage += s"${lvalue} is null"
            }
            case _ => {
                /* semantic error: wrong lvalue type*/
                Error.addSemErr(s"Wrong type assigned for fst, gets ${ty.toString()}")
            }
        }
    }
}

case class SndNode(lvalue: LValueNode) extends PairElemNode {
    override def typeVal() = {
        val ty = lvalue match {
            case IdentNode(name) => {
                SemanticChecker.symbolTable.lookUpVar(name) match {
                    case Some(vartype) => vartype
                    /* semantic error: identifier is not in th symmbol table */
                    case None => new AnyIdentifier
                } 
            }
            case lval => lval.typeVal()
        }

        ty match {
            case p: PairIdentifier => p.ty2
            case n: NullIdentifier => n
            case _ => {
                /* semantic error: wrong lvalue type*/
                new AnyIdentifier
            }
        } 
    }

    override def semanticCheck(): Unit = {
        lvalue.semanticCheck()

        val ty = lvalue match {
            case IdentNode(name) => {
                SemanticChecker.symbolTable.lookUpVar(name) match {
                    case Some(vartype) => vartype
                    /* semantic error: identifier is not in th symmbol table: case caught in lval semantic check */
                    case None => new AnyIdentifier
                } 
            }
            case lval => lval.typeVal()
        }

        ty match {
            case p: PairIdentifier => {
                SemanticChecker.debugMessage += s"${lvalue} is ${p}"
            }
            case n: NullIdentifier => {
                SemanticChecker.debugMessage += s"${lvalue} is null"
            }
            case _ => {
                /* semantic error: wrong lvalue type*/
                Error.addSemErr(s"Wrong type assigned for snd, gets ${ty.toString()}")
            }
        }
    }
}

// RValueNode
sealed trait RValueNode extends ASTNode {
    def typeVal(): TypeIdentifier
}

sealed trait ExprNode extends RValueNode

case class ArrayLiterNode(exprList: List[ExprNode]) extends RValueNode {
    override def typeVal(): TypeIdentifier = {
        if (!exprList.isEmpty) {
            val elemType = exprList(0).typeVal()
            elemType match {
                case ArrayIdentifier(base, dim) => new ArrayIdentifier(base, dim + 1)
                case t => new ArrayIdentifier(t, 1)
            }
        } else {
            /* type for empty array wiil be assigned at variable definition */
            new ArrayIdentifier(new AnyIdentifier, 1)
        }
    }

    override def semanticCheck(): Unit = {
        if (!exprList.isEmpty) {
            exprList.map(expr => expr.semanticCheck())
            val exprTypes:List[TypeIdentifier] = exprList.map(expr => expr.typeVal())
            // check whether array literal expr all have same types
            exprTypes
            .zip(exprTypes.map(ty => ty.typeEquals(exprTypes(0))))
            .map{case (x:TypeIdentifier, y:Boolean) => if (!y) {
                Error.addSemErr(s"array literal expr should have type ${exprTypes(0).toString()}, but was ${x}")
            }}
        }
    }
}

case class NewPairNode(fstExpr: ExprNode, sndExpr: ExprNode) extends RValueNode {
    override def typeVal() = {
        new PairIdentifier(fstExpr.typeVal(), sndExpr.typeVal())
    }
    override def semanticCheck(): Unit = {
        fstExpr.semanticCheck()
        sndExpr.semanticCheck()
    }
}

case class CallNode(ident: IdentNode, argList: ArgListNode) extends RValueNode {
    var newName: Option[String] = Option.empty
    var returnType: Option[TypeIdentifier] = Option.empty
    override def typeVal() = returnType match {
        case Some(ty) => ty
        case _ => AnyIdentifier()
    }

    override def semanticCheck(): Unit = {
        argList.semanticCheck()

        val exprTypes: List[TypeIdentifier] = argList.exprList.map(expr => expr.typeVal())
        newName = SemanticChecker.symbolTable.getFuncNewName(ident.name, exprTypes, typeVal())
        newName match {
            case None => Error.addSemErr("Function name \"" + ident.name + 
                                            "\" is not defined or parameter/return type(s) do not match")
            case Some(name) => ident.newName = name
        }
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
    def typeVal(): TypeIdentifier = new AnyIdentifier
}

case class BaseTypeNode(ty: String) extends TypeNode with PairElemTypeNode {
    // includes: int, char, string, bool
    override def typeVal() = {
        ty match {
            case "int" => new IntIdentifier
            case "bool" => new BoolIdentifier
            case "char" => new CharIdentifier
            case "string" => new StrIdentifier
            /* syntactic error: int, bool, char, string are the only basic types */
            case _ =>
                throw new IllegalArgumentException(s"basic type: \"${ty}\" is not a legal basic type")
       }
    }
}


case class ArrayTypeNode(ty: TypeNode) extends TypeNode with PairElemTypeNode {
    /* temporary holder of the basetype of an array */
    var arrayType: TypeIdentifier = new AnyIdentifier
    var arrayDim = 1

    override def typeVal(): TypeIdentifier = {
        return new ArrayIdentifier(getType(ty).typeVal(), countDimension(ty))
    }

    override def semanticCheck(): Unit = {
        ty.semanticCheck()
        arrayType = getType(ty).typeVal()
        arrayDim = countDimension(ty)
    }

    def countDimension(ty: TypeNode): Int = {
        ty match {
            case ArrayTypeNode(arrayTy) => 1 + countDimension(arrayTy)
            case _ => 1
        }  
    }

    // gets the base type of array. ie base type of pair(int, int)[][][] is pair(int, int)
    def getType(ty: TypeNode): TypeNode = {
        ty match {
            case ArrayTypeNode(arrayTy) => getType(arrayTy)
            case node => node
        }  
    }
}

// Example: Pair(bool, int[])
case class PairTypeNode(fstPET: PairElemTypeNode, sndPET: PairElemTypeNode) extends TypeNode {
    override def typeVal(): TypeIdentifier = {
        new PairIdentifier(fstPET.typeVal(), sndPET.typeVal())
    }

    override def semanticCheck(): Unit = {
        fstPET.semanticCheck()
        sndPET.semanticCheck()
    }
}

//NOTE: should not exist for full pair type
sealed trait PairElemTypeNode extends ASTNode {
    /* "pair" can be refering to any valid pair type */
    def typeVal(): TypeIdentifier = new PairIdentifier(new AnyIdentifier, new AnyIdentifier)
}
// <pair>
case class PETPairNode() extends PairElemTypeNode {
    override def typeVal(): TypeIdentifier = super[PairElemTypeNode].typeVal()
}

case class IntLiterNode(n: Int) extends ExprNode {
    override def typeVal() = new IntIdentifier
}

case class BoolLiterNode(b: Boolean) extends ExprNode {
    override def typeVal() = new BoolIdentifier
}

case class CharLiterNode(c: Char) extends ExprNode {
    override def typeVal() = new CharIdentifier
}

case class StrLiterNode(s: String) extends ExprNode {
    override def typeVal() = new StrIdentifier
}

case class PairLiterNode() extends ExprNode {
    // val: null
    override def typeVal() = new NullIdentifier
}

sealed trait UnOpExprNode extends ExprNode

// bool -> bool
case class NotNode(expr: ExprNode) extends UnOpExprNode {
    override def typeVal() = new BoolIdentifier
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        if(!SemanticChecker.typeCheck(new BoolIdentifier, expr.typeVal())) {
            Error.addSemErr(s"Unary op: expression unexpected type ${expr.typeVal().toString()}, expected type bool")
        }
    }
}

// int -> int
case class NegNode(expr: ExprNode) extends UnOpExprNode {
     override def typeVal() = new IntIdentifier
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        if(!SemanticChecker.typeCheck(new IntIdentifier, expr.typeVal())) {
            Error.addSemErr(s"Unary op: expression unexpected type ${expr.typeVal().toString()}, expected type int")
        }
    }
}

// array -> int
case class LenNode(expr: ExprNode) extends UnOpExprNode {
    override def typeVal() = new IntIdentifier
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        expr.typeVal() match {
            case a: ArrayIdentifier => 
            case _ => Error.addSemErr(
                    "Wrong array dimension in len(), expected at least 1-dimensional array")
        }
    }
}

// char -> int
case class OrdNode(expr: ExprNode) extends UnOpExprNode {
    override def typeVal() = new IntIdentifier
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        if(!SemanticChecker.typeCheck(new CharIdentifier, expr.typeVal())) {
            Error.addSemErr(s"Unary op: expression unexpected type ${expr.typeVal().toString()}, expected type char")
        }
    }
}

// int -> char
case class ChrNode(expr: ExprNode) extends UnOpExprNode {
    override def typeVal() = new CharIdentifier
    override def semanticCheck(): Unit = {
        expr.semanticCheck()
        if(!SemanticChecker.typeCheck(new IntIdentifier, expr.typeVal())) {
            Error.addSemErr(s"Unary op: expression unexpected type ${expr.typeVal().toString()}, expected type int")
        }
    }
}

sealed trait BinOpExprNode extends ExprNode {
    override def typeVal(): TypeIdentifier = {
        this match {
            case MulNode(_,_) | DivNode(_,_) | ModNode(_,_) | AddNode(_,_) | SubNode(_,_) => new IntIdentifier
            case _ => new BoolIdentifier
            }
    }
    override def semanticCheck(): Unit = {
        typeVal()
        this match {
            case MulNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (! SemanticChecker.typeCheck(new IntIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type int")
                    }
                    if (! SemanticChecker.typeCheck(new IntIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int")
                    }
            }
            case DivNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (! SemanticChecker.typeCheck(new IntIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type int")
                    }
                    if (! SemanticChecker.typeCheck(new IntIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int")
                    }
            }
            case ModNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (! SemanticChecker.typeCheck(new IntIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type int")
                    }
                    if (! SemanticChecker.typeCheck(new IntIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int")
                    }
            }
            case AddNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (! SemanticChecker.typeCheck(new IntIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type int")
                    }
                    if (! SemanticChecker.typeCheck(new IntIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int")
                    }
            }
            case SubNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (! SemanticChecker.typeCheck(new IntIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type int")
                    }
                    if (! SemanticChecker.typeCheck(new IntIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int")
                    }
            }
            case GTNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (!SemanticChecker.typeCheck(new IntIdentifier, fstExpr.typeVal()) && !SemanticChecker.typeCheck(new CharIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type int/char")
                    }
                    if (! SemanticChecker.typeCheck(new IntIdentifier, sndExpr.typeVal()) && !SemanticChecker.typeCheck(new CharIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int/char")
                    }
            }
            case GTENode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (!SemanticChecker.typeCheck(new IntIdentifier, fstExpr.typeVal()) && !SemanticChecker.typeCheck(new CharIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type int/char")
                    }
                    if (! SemanticChecker.typeCheck(new IntIdentifier, sndExpr.typeVal()) && !SemanticChecker.typeCheck(new CharIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int/char")
                    }
            }
            case LTNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (!SemanticChecker.typeCheck(new IntIdentifier, fstExpr.typeVal()) && !SemanticChecker.typeCheck(new CharIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type int/char")
                    }
                    if (! SemanticChecker.typeCheck(new IntIdentifier, sndExpr.typeVal()) && !SemanticChecker.typeCheck(new CharIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int/char")
                    }
            }
            case LTENode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (!SemanticChecker.typeCheck(new IntIdentifier, fstExpr.typeVal()) && !SemanticChecker.typeCheck(new CharIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int/char")
                    }
                    if (! SemanticChecker.typeCheck(new IntIdentifier, sndExpr.typeVal()) && !SemanticChecker.typeCheck(new CharIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type int/char")
                    }
            }
            case EqNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (!SemanticChecker.typeCheck(fstExpr.typeVal(), sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: wrong type, expected ${fstExpr.typeVal()} " +
                                        s"instead of ${sndExpr.typeVal()}")
                    }
            }
            case IEqNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (!SemanticChecker.typeCheck(fstExpr.typeVal(), sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: wrong type, expected ${fstExpr.typeVal()}" +
                                        s"instead of ${sndExpr.typeVal()}")
                    }
            }
            case AndNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (!SemanticChecker.typeCheck(new BoolIdentifier, fstExpr.typeVal())) {
                       Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type bool")
                    }
                    if (!SemanticChecker.typeCheck(new BoolIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type bool")
                    }
            }
            case OrNode(fstExpr, sndExpr) => {
                    fstExpr.semanticCheck()
                    sndExpr.semanticCheck()
                    if (! SemanticChecker.typeCheck(new BoolIdentifier, fstExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: first expression unexpected type ${fstExpr.typeVal().toString()}, expected lhs type bool")
                    }
                    if (! SemanticChecker.typeCheck(new BoolIdentifier, sndExpr.typeVal())) {
                        Error.addSemErr(s"Binary op: second expression unexpected type ${sndExpr.typeVal().toString()}, expected lhs type bool")
                    }
            }
        }
    }
}

case class MulNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class DivNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class ModNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class AddNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class SubNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class GTNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class GTENode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class LTNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class LTENode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class EqNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class IEqNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class AndNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode
case class OrNode(fstexpr: ExprNode, sndexpr: ExprNode) extends BinOpExprNode

case class UnaryOperNode(op: String) extends ASTNode
case class BinaryOperatorNode(op: String) extends ASTNode 