package wacc

import parsley.Parsley
import parsley.Parsley.{attempt, lookAhead, notFollowedBy}
import parsley.implicits.character.charLift
import parsley.implicits.lift.{Lift1, Lift2, Lift3, Lift4}
import parsley.token.{Lexer, descriptions, predicate}
import parsley.token.text.Character
import parsley.expr.{Atoms, precedence, Postfix, Ops, InfixL, Prefix}
import parsley.expr.chain.postfix1
import descriptions.numeric.{NumericDesc, PlusSignPresence}
import PlusSignPresence.Optional
import descriptions.{LexicalDesc, SpaceDesc, SymbolDesc, NameDesc, text}
import text.{TextDesc, EscapeDesc}
import parsley.combinator.{sepBy, sepBy1, some, manyUntil, choice}
import parsley.character.{noneOf, stringOfMany, string, strings, spaces}
import parsley.errors.combinator.{amend, ErrorMethods}

object lexer {
    val desc = LexicalDesc.plain.copy(
        spaceDesc = SpaceDesc.plain.copy(
            commentLine = "#",
            space = predicate.Basic(Character.isWhitespace)
        ),
        symbolDesc = SymbolDesc.plain.copy(
            hardKeywords = Set(
                "begin", "skip", "end", 
                "int", "bool", "char", "string", 
                "true", "false", 
                "is", 
                "read", 
                "free", 
                "return", "exit", 
                "print", "println", 
                "if", "then", "else", "fi", 
                "while", "do", "done", 
                "call", 
                "fst", "snd", "newpair", "pair",
                "null")
        ),
        nameDesc = NameDesc.plain.copy(
            identifierStart = predicate.Basic(validIdentStart),
            identifierLetter = predicate.Basic(validIdentLetter)
        ),
        numericDesc = NumericDesc.plain.copy(
            positiveSign = Optional
        ),
        textDesc = TextDesc.plain.copy(
            escapeSequences = EscapeDesc.plain.copy(
                literals = Set('0', 'b', 't', 'n', 'f', 'r', '\"', '\'', '\\')
            ),
            multiStringEnds = Set.empty,
            graphicCharacter = predicate.Basic(validChar)
        )
    )
    private def validIdentStart(c: Char) = c == '_' || (c <= 'z' && c.isLetter)
    private def validIdentLetter(c: Char) = validIdentStart(c) || c.isDigit
    private def validChar(c: Char) = !(List('\\', '\'', '\"', '\n').contains(c))
    //private val comment = symbol("#") *> manyUntil(item, endOfLine)
    //private val skipWhitespace = skipMany(whitespace <|> comment).hide
    private val lexer = new Lexer(desc)

    def fully[A](p: Parsley[A]): Parsley[A] = lexer.fully(p)//skipWhitespace ~> p <~ eof 

    //def symbol(s: String): Parsley[Unit] = 
    //    lexer.lexeme.symbol(s)
    val implicits = lexer.lexeme.symbol.implicits
    val identifier = lexer.lexeme.names.identifier
    val character = lexer.lexeme.text.character.ascii
    val string = lexer.lexeme.text.string.ascii

    val num = lexer.lexeme.numeric.signed.decimal32
    def lexeme[A](p: Parsley[A]): Parsley[A] = lexer.lexeme(p)
}

object Parser {
    import lexer.{fully, identifier, num, character, string, lexeme}
    import lexer.implicits.implicitSymbol

    /* General expressions */

    lazy val ident = IdentNode.lift(identifier).label("identifier")

    lazy val literals: Parsley[ExprNode] =
        (attempt(arrayElem) <|>
        intLiter           <|>
        attempt(boolLiter) <|> 
        charLiter          <|> 
        strLiter           <|>
        literalIdent       <|>
        pairLiter          <|>
        bracketExpr)

    lazy val literalIdent = ident <~ notFollowedBy("(").explain(
        "function calls should start with call and not appear in expressions.")

    lazy val bracketExpr: Parsley[ExprNode] =
        ("(" ~> expr <~ ")").label("brackets")
    
    lazy val expr: Parsley[ExprNode] =
        (attempt(op) <|> literals).label("literal of any type, identifier, null, (, uOp, binOp")

    lazy val op: Parsley[ExprNode] =
        precedence(literals)(
            Ops(Prefix)("!".label("unOp") #> NotNode,
                        "-".label("unOp") #> NegNode,
                        "len".label("unOp") #> LenNode,
                        "ord".label("unOp") #> OrdNode,
                        "chr".label("unOp") #> ChrNode),
            Ops(InfixL)("*".label("binOp") #> MulNode,
                        "/".label("binOp") #> DivNode,
                        "%".label("binOp") #> ModNode),
            Ops(InfixL)("+".label("binOp") #> AddNode,
                        "-".label("binOp") #> SubNode),
            Ops(InfixL)(">=".label("binOp") #> GTENode,
                        ">".label("binOp")#> GTNode,
                        "<=".label("binOp") #> LTENode,
                        "<".label("binOp")#> LTNode
                        ),
            Ops(InfixL)("==".label("binOp") #> EqNode,
                        "!=".label("binOp") #> IEqNode),
            Ops(InfixL)("&&".label("binOp") #> AndNode),
            Ops(InfixL)("||".label("binOp") #> OrNode)
        )
       
    lazy val lValue = 
        (pairElem <|> attempt(arrayElem) <|> ident).label("LVal").explain(
            "LValue can be identifiers, array elements or pair elements (fst, snd)."
        )

    lazy val rValue =
        (arrayLiter     <|>
        newPair         <|>
        pairElem        <|>
        funcCall        <|>
        expr).label("RValue")
        .explain("RValue can be array literals, new pair literal," +
          " pair elements (fst, snd), function calls and expressions.")

    /* Types */

    lazy val generalType: Parsley[TypeNode] =
        (attempt(arrayType) <|> arrayBaseType).label("type")
    
    lazy val arrayBaseType: Parsley[TypeNode] =
        (baseType <|> pairType).label("non-array type")

    lazy val baseType: Parsley[BaseTypeNode] = 
        "int"    #> BaseTypeNode("int")    <|>
        "bool"   #> BaseTypeNode("bool")   <|>
        "char"   #> BaseTypeNode("char")   <|>
        "string" #> BaseTypeNode("string")

    lazy val intLiter: Parsley[IntLiterNode] =
        (IntLiterNode.lift(num)).label("num")
    
    lazy val boolLiter: Parsley[BoolLiterNode] = 
        ("true" #> BoolLiterNode(true) <|>
        "false" #> BoolLiterNode(false)).label("bool")

    lazy val escapedChar = 
        "\\" ~> choice(
            '0' #> '\u0000',
            'b' #> '\b',
            't' #> '\t',
            'n' #> '\n',
            'f' #> '\f',
            'r' #> '\r',
            '\"',
            '\'',
            '\\'
        )

    lazy val charLiter: Parsley[CharLiterNode] = 
        (CharLiterNode.lift(character)).label("character")
    
    lazy val strLiter: Parsley[StrLiterNode] =
        (StrLiterNode.lift(string)).label("string")

    lazy val arrayType: Parsley[ArrayTypeNode] =
        (postfix1(arrayBaseType, "[]".label("[] (as array type)") #> ArrayTypeNode))

    lazy val arrayElem: Parsley[ArrayElemNode] =
        (ArrayElemNode.lift(ident, some("[" ~> expr <~ "]").label("array index"))).label("array element")

    lazy val arrayLiter: Parsley[ArrayLiterNode] =
        ("[" ~> ArrayLiterNode.lift(sepBy(expr, ",")) <~ "]").label("array literal")

    lazy val pairType: Parsley[PairTypeNode] =
        (PairTypeNode.lift
            ("pair(" ~> pairElemType <~ ",", pairElemType <~ ")")).label("pair type")

    lazy val pairElemType: Parsley[PairElemTypeNode] =
        (attempt(arrayType) <|> baseType <|> "pair" #> PETPairNode()).label("pair element type")

    lazy val pairLiter: Parsley[PairLiterNode] =
        ("null" #> PairLiterNode()).label("pair literal")

    lazy val pairElem: Parsley[PairElemNode] =
        "fst" ~> FstNode.lift(lValue) <|> "snd" ~> SndNode.lift(lValue).label("pair element: (fst / snd) LValue")

    lazy val newPair: Parsley[NewPairNode] =
        NewPairNode.lift("newpair(" ~> expr <~ ",", expr <~ ")").label("new pair literal")

    /* Functions */
    
    lazy val param: Parsley[ParamNode] =
        (ParamNode.lift(generalType, ident)).label("parameter")

    lazy val paramList: Parsley[ParamListNode] =
        (ParamListNode.lift(sepBy(param, ","))).label("list of parameters")

    lazy val func: Parsley[FuncNode] = 
        (FuncNode.lift(
            generalType,
            ident,
            "(" ~> paramList <~ ")",
            "is" ~> stats.filterOut {
                case s if notExitStmt(s) => s"Missing exit or return statement"
            } <~ "end")).label("function definition")

    def notExitStmt(s: StatNode): Boolean = s match {
        case IfNode(_, fstStat, sndStat) => notExitStmt(fstStat) || notExitStmt(sndStat)
        case WhileNode(_, stat) => notExitStmt(stat)
        case BeginEndNode(stat) => notExitStmt(stat)
        case StatJoinNode(statList) => notExitStmt(statList.last)
        case _: ExitNode | _: ReturnNode => false
        case _ => true
    }
    
    lazy val funcCall: Parsley[CallNode] =
        (CallNode.lift("call" ~> ident, "(" ~> argList <~ ")")).label("function call")

    lazy val argList: Parsley[ArgListNode] =
        (ArgListNode.lift(sepBy(expr, ","))).label("list of arguments")

    /* Statements */
    
    lazy val skip: Parsley[StatNode] =
        ("skip" #> SkipNode()).label("skip statement")

    lazy val read =
        ReadNode.lift("read" ~> lValue).label("reading a value")

    lazy val free =
        FreeNode.lift("free" ~> expr).label("freeing a value")

    lazy val valReturn =
        ReturnNode.lift("return" ~> expr).label("return statement")

    lazy val exit =
        ExitNode.lift("exit" ~> expr).label("program exit statement")

    lazy val print =
        PrintNode.lift("print" ~> expr).label("print statement")

    lazy val println =
        PrintlnNode.lift("println" ~> expr).label("println statement")

    lazy val ifCon =
        IfNode.lift("if" ~> expr, "then" ~> stats, "else".explain("All if statements must have an else clause.") ~> stats <~ "fi".explain("Unclosed if statement")).label("if condition")

    lazy val whileCon =
        WhileNode.lift("while" ~> expr, "do".explain("While-loop conditions should be bounded by the do keyword") ~> stats <~ "done".explain("Unclosed while-loop")).label("while loop")

    lazy val beginEnd =
        BeginEndNode.lift("begin" ~> stats <~ "end").label("scoping statement")

    lazy val assignIdent: Parsley[AssignIdentNode] =
        AssignIdentNode.lift(generalType, ident <~ notFollowedBy("(").hide.explain("function declaration format mismatch or is within body"), "=".label("Identifier assignment") ~> rValue).label("Identifier assignment")

    lazy val lValueAssign: Parsley[LValuesAssignNode] =
        LValuesAssignNode.lift(lValue <~ notFollowedBy("(").hide.explain("function declaration format mismatch or is within body"), "=".label("LValue assignment") ~> rValue).label("LValue assignment")

    lazy val stat: Parsley[StatNode] = lexer.lexeme(
        skip        <|>
        read        <|>
        free        <|>
        valReturn   <|>
        exit        <|>
        println     <|>
        print       <|>
        ifCon       <|>
        whileCon    <|>
        beginEnd    <|>
        attempt(assignIdent) <|>
        lValueAssign)
    
    lazy val stats: Parsley[StatNode] =
        (attempt(stat <~ notFollowedBy(";")) <|> statJoin)

    lazy val statJoin: Parsley[StatNode] = 
        (StatJoinNode.lift(sepBy1(stat, ";"))).label("Multiple statements")

    /* Top Level */
    
    lazy val prog: Parsley[ProgramNode] =
        ("begin".label("beginning of program") ~> ProgramNode.lift(
            manyUntil(func, lookAhead(attempt(stat))), stats) <~ "end".label("end of program"))

    val topLevel = fully(prog)
}