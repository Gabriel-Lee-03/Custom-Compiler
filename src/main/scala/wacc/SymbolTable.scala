package wacc

import scala.collection.mutable

import wacc.{FuncIdentifier, ArrayIdentifier, PairIdentifier}
class SymbolTable {

    val map: mutable.Map[String, TypeIdentifier] = mutable.Map()
    
    // Add variable to symbol table
    def addVar(name: String, ty: TypeIdentifier): Unit = {
        val varName = SemanticChecker.currScope().toString() + "!" + name
        map.addOne(varName, ty)
    }

    // Add array to symbol table
    def addArray(name: String, ty: TypeIdentifier, dim: Int): Unit = {
        val varName = SemanticChecker.currScope().toString() + "!" + name
        val identifier = new ArrayIdentifier(ty, dim)
        map.addOne(varName, identifier)
    }

    // Add pair to symbol table
    def addPair(name: String, ty1: TypeIdentifier, ty2: TypeIdentifier): Unit = {
        val varName = SemanticChecker.currScope().toString() + "!" + name
        val identifier = new PairIdentifier(ty1, ty2)
        map.addOne(varName, identifier)
    }

    // Add function to symbol table
    def addFunc(name: String, paramtype: List[TypeIdentifier], returntype: TypeIdentifier): Unit = {
        val funcName = "f!" + name
        val identifier = new FuncIdentifier(paramtype, returntype)
        map.addOne(funcName, identifier)
    }

    // Look up variable or array from symbol table in same or higher scopes
    def lookUpVar(name: String): Option[TypeIdentifier] = {
        var idx = 0
        var scope = -1
        var varName = ""
        var identifier: Option[TypeIdentifier] = None
        while (!SemanticChecker.scopeStack.isEmpty && idx < SemanticChecker.scopeStack.size && identifier.isEmpty) {
            scope = SemanticChecker.scopeStack.apply(idx)
            varName = scope.toString() + "!" + name
            identifier = map.get(varName)
            idx += 1
        }
        identifier
    }

    // Look up variable or array from symbol table in same or higher scopes
    def lookUpVarNewName(varName: String): Option[TypeIdentifier] = {
        map.get(varName)
    }

    // Get variable name stored in symbol table
    def getVarName(name: String): String = {
        var idx = 0
        var scope = -1
        var varName = ""
        var identifier: Option[TypeIdentifier] = None
        while (!SemanticChecker.scopeStack.isEmpty && idx < SemanticChecker.scopeStack.size && identifier.isEmpty) {
            scope = SemanticChecker.scopeStack.apply(idx)
            varName = scope.toString() + "!" + name
            identifier = map.get(varName)
            idx += 1
        }
        varName
    }

    // Check if variable is defined in the same scope
    def checkVarDefined(name: String): Boolean = {
        val varName = SemanticChecker.currScope().toString() + "!" + name
        map.contains(varName)
    }

    // Look up function from symbol table
    def lookUpFunc(name: String): Option[TypeIdentifier] = {
        val funcName = "f!" + name
        map.get(funcName)
    }

    override def toString(): String = {
        var ret = ""
        map.foreachEntry((name:String, ty:TypeIdentifier) =>{
            ret += s"{name: ${name}, type: ${ty}}"
        })
        ret
    }
}

/*
    all variables being globally unique

    renaming:
    <scope index> + ! + variable name
    scope index tracked using a stack
    new scope index assignment = max + 1
*/