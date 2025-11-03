parser grammar NebulaParser;

@header {
package org.lokray.parser;
}

options { tokenVocab = NebulaLexer; }

// ====================================================================
// TOP LEVEL
// ====================================================================

compilationUnit
    : topLevelDeclaration* EOF
    ;

topLevelDeclaration
    : importDeclaration
    | namespaceDeclaration
    | aliasDeclaration
    | typeDeclaration
    ;

// ====================================================================
// PROJECT STRUCTURE
// ====================================================================

importDeclaration
    : IMPORT_KW qualifiedName SEMI_SYM
    ;

namespaceDeclaration
    : NAMESPACE_KW qualifiedName L_CURLY_SYM topLevelDeclaration* R_CURLY_SYM
    ;

aliasDeclaration
    : ALIAS_KW ID EQUALS_SYM qualifiedName SEMI_SYM
    ;

qualifiedName
    : ID typeArgumentList? (DOT_SYM ID typeArgumentList?)*
    ;

// ====================================================================
// TYPES (CLASS / STRUCT)
// ====================================================================

typeDeclaration
    : (NATIVE_KW)? modifiers? (CLASS_KW | STRUCT_KW) ID typeParameterList?
      (L_CURLY_SYM typeMember* R_CURLY_SYM | SEMI_SYM)
    ;

typeMember
    : fieldDeclaration
    | methodDeclaration
    | constructorDeclaration
    | propertyDeclaration
    | operatorOverloadDeclaration
    ;

// ====================================================================
// CLASS MEMBERS
// ====================================================================

constructorDeclaration
    : (NATIVE_KW)? modifiers? ID L_PAREN_SYM parameterList? R_PAREN_SYM (block | SEMI_SYM)
    ;

propertyDeclaration
    : (NATIVE_KW)? modifiers? type ID
      (L_CURLY_SYM accessorDeclaration+ R_CURLY_SYM)?
      (EQUALS_SYM expression)?
      SEMI_SYM?
    ;

accessorDeclaration
    : (GET_KW | SET_KW) accessorBody
    ;

accessorBody
    : block
    | SEMI_SYM
    | L_CURLY_SYM expression R_CURLY_SYM
    ;

fieldDeclaration
    : (NATIVE_KW)? modifiers? type variableDeclarator (COMMA_SYM variableDeclarator)* SEMI_SYM
    ;

variableDeclarator
    : ID (EQUALS_SYM expression)?
    ;

methodDeclaration
    : (NATIVE_KW)? modifiers? type ID L_PAREN_SYM parameterList? R_PAREN_SYM (block | SEMI_SYM)
    ;

operatorOverloadDeclaration
    : (NATIVE_KW)? modifiers? type OPERATOR_KW validOperatorOverloads
      L_PAREN_SYM parameterList? R_PAREN_SYM (block | SEMI_SYM)
    ;

validOperatorOverloads
    : ADD_OP | SUB_OP | MUL_OP | DIV_OP | MOD_OP | EXP_OP | EQUAL_EQUAL_SYM
    ;

// ====================================================================
// MODIFIERS
// ====================================================================

modifier
    : PUBLIC_KW | PRIVATE_KW | STATIC_KW | CONST_KW | OVERRIDE_KW
    ;

modifiers : modifier+ ;

// ====================================================================
// TYPES AND TUPLES
// ====================================================================

tupleElement : type ID? ;

tupleType
    : L_PAREN_SYM tupleElement (COMMA_SYM tupleElement)* R_PAREN_SYM
    ;

type
    : primitiveType (L_BRACK_SYM R_BRACK_SYM)*
    | qualifiedName (L_BRACK_SYM R_BRACK_SYM)*
    | tupleType
    ;

primitiveType
    : VOID_T | NULL_T | BYTE_T | SHORT_T | INT_T | LONG_T
    | BYTE_SPE_T | SHORT_SPE_T | INT_SPE_T | LONG_SPE_T
    | U_BYTE_T | U_SHORT_T | U_INT_T | U_LONG_T
    | U_BYTE_SPE_T | U_SHORT_SPE_T | U_INT_SPE_T | U_LONG_SPE_T
    | FLOAT_T | DOUBLE_T | BOOL_T | CHAR_T | STRING_T
    ;

// ====================================================================
// PARAMETERS
// ====================================================================

parameterList : parameter (COMMA_SYM parameter)* ;

parameter : type ID (EQUALS_SYM expression)? ;

// ====================================================================
// GENERICS - TYPE PARAMETERS (FOR DECLARATIONS)
// ====================================================================

// Rule for the entire generic type parameter list, e.g., <T, U>
typeParameterList
    : LESS_THAN_SYM typeParameter (COMMA_SYM typeParameter)* GREATER_THAN_SYM
    ;

// Rule for a single type parameter, which is typically just an ID (the type variable name)
// You might add constraints here later, e.g., T : SomeConstraint
typeParameter
    : ID
    ;

// ====================================================================
// STATEMENTS
// ====================================================================

block : L_CURLY_SYM statement* R_CURLY_SYM ;

statement
    : block
    | variableDeclaration SEMI_SYM
    | ifStatement
    | forStatement
    | foreachStatement
    | whileStatement
    | returnStatement
    | breakStatement
    | continueStatement
    | switchStatement
    | expression SEMI_SYM
    ;

variableDeclaration
    : modifiers? type variableDeclarator (COMMA_SYM variableDeclarator)*
    ;

ifStatement
    : IF_KW L_PAREN_SYM expression R_PAREN_SYM statement (ELSE_KW statement)?
    ;

forStatement
    : FOR_KW L_PAREN_SYM
          (variableDeclaration | expression)? SEMI_SYM
          expression? SEMI_SYM
          expression?
      R_PAREN_SYM block
    | FOR_KW L_PAREN_SYM simplifiedForClause R_PAREN_SYM block
    ;

simplifiedForClause
    : ID EQUALS_SYM expression relationalOperator expression
    | ID relationalOperator expression
    ;

relationalOperator
    : LESS_THAN_SYM | GREATER_THAN_SYM | LESS_EQUAL_THAN_SYM | GREATER_EQUAL_THAN_SYM
    ;

foreachStatement
    : FOREACH_KW L_PAREN_SYM type ID IN_KW expression R_PAREN_SYM statement
    ;

whileStatement : WHILE_KW L_PAREN_SYM expression R_PAREN_SYM statement ;

returnStatement : RETURN_KW expression? SEMI_SYM ;

breakStatement : BREAK_KW SEMI_SYM ;

continueStatement : CONTINUE_KW SEMI_SYM ;

switchStatement
    : SWITCH_KW L_PAREN_SYM expression R_PAREN_SYM
      L_CURLY_SYM switchBlock* R_CURLY_SYM
    ;

switchBlock
    : (CASE_KW expression | DEFAULT_KW) COLON_SYM statement*
    ;

// ====================================================================
// EXPRESSIONS
// ====================================================================

expression
    : assignmentExpression
    ;

assignmentExpression
    : conditionalExpression (assignmentOperator conditionalExpression)?
    ;

conditionalExpression
    : logicalOrExpression (QUESTION_MARK_SYM expression COLON_SYM expression)?
    ;

logicalOrExpression
    : logicalAndExpression (LOG_OR_OP logicalAndExpression)*
    ;

logicalAndExpression
    : bitwiseOrExpression (LOG_AND_OP bitwiseOrExpression)*
    ;

bitwiseOrExpression
    : bitwiseXorExpression (BIT_OR_OP bitwiseXorExpression)*
    ;

bitwiseXorExpression
    : bitwiseAndExpression (BIT_XOR_OP bitwiseAndExpression)*
    ;

bitwiseAndExpression
    : equalityExpression (BIT_AND_OP equalityExpression)*
    ;

equalityExpression
    : relationalExpression ((EQUAL_EQUAL_SYM | NOT_EQUAL_SYM) relationalExpression)*
    ;

relationalExpression
    : shiftExpression ((LESS_THAN_SYM | GREATER_THAN_SYM | LESS_EQUAL_THAN_SYM | GREATER_EQUAL_THAN_SYM) shiftExpression)*
    ;

shiftExpression
    : additiveExpression ((BIT_L_SHIFT | BIT_R_SHIFT) additiveExpression)*
    ;

additiveExpression
    : multiplicativeExpression ((ADD_OP | SUB_OP) multiplicativeExpression)*
    ;

multiplicativeExpression
    : powerExpression ((MUL_OP | DIV_OP | MOD_OP) powerExpression)*
    ;

powerExpression
    : unaryExpression (EXP_OP unaryExpression)*
    ;

unaryExpression
    : (ADD_OP | SUB_OP | LOG_NOT_OP | BIT_NOT_OP | INC_OP | DEC_OP) unaryExpression
    | castExpression
    | postfixExpression
    ;

castExpression
    : L_PAREN_SYM type R_PAREN_SYM unaryExpression
    ;

postfixExpression
    : primary (
        DOT_SYM ID
      | L_PAREN_SYM argumentList? R_PAREN_SYM
      | L_BRACK_SYM expression R_BRACK_SYM
      | INC_OP
      | DEC_OP
      )*
    ;

// ====================================================================
// PRIMARY EXPRESSIONS
// ====================================================================

primary
    : L_PAREN_SYM expression R_PAREN_SYM
    | literal
    | ID
    | NEW_KW type (L_PAREN_SYM argumentList? R_PAREN_SYM | L_BRACK_SYM expression R_BRACK_SYM)
    | arrayInitializer
    | tupleLiteral
    | primitiveType
    ;

argumentList
    : expression (COMMA_SYM expression)* (COMMA_SYM namedArgument (COMMA_SYM namedArgument)*)?
    | namedArgument (COMMA_SYM namedArgument)*
    ;

// ====================================================================
// GENERICS - TYPE ARGUMENTS (FOR USAGE)
// ====================================================================

// Rule for the entire generic type argument list, e.g., <int, string>
typeArgumentList
    : LESS_THAN_SYM type (COMMA_SYM type)* GREATER_THAN_SYM
    ;

namedArgument : ID COLON_SYM expression ;

tupleLiteral
    : L_PAREN_SYM
        ( namedArgument (COMMA_SYM namedArgument)*
        | expression (COMMA_SYM expression)+ )
      R_PAREN_SYM
    ;

// ====================================================================
// ARRAYS
// ====================================================================

arrayInitializer
    : L_CURLY_SYM (arrayElement (COMMA_SYM arrayElement)*)? COMMA_SYM? R_CURLY_SYM
    ;

arrayElement : expression | arrayInitializer ;

// ====================================================================
// LITERALS
// ====================================================================

literal
    : HEX_LITERAL | BIN_LITERAL | INTEGER_LITERAL | FLOAT_LITERAL
    | DOUBLE_LITERAL | BOOLEAN_LITERAL | CHAR_LITERAL | STRING_LITERAL
    | interpolatedString | NULL_T
    ;

interpolatedString
    : INTERPOLATED_STRING_START interpolationPart* INTERPOLATION_END
    ;

interpolationPart
    : TEXT_FRAGMENT
    | ESCAPED_BRACE_INTERP
    | OPEN_BRACE_INTERP expression CLOSE_BRACE_INTERP
    ;

// ====================================================================
// ASSIGNMENT OPERATORS
// ====================================================================

assignmentOperator
    : EQUALS_SYM | ADD_OP_COMP | SUB_OP_COMP | MUL_OP_COMP | DIV_OP_COMP
    | MOD_OP_COMP | EXP_OP_COMP | BIT_AND_OP_COMP | BIT_OR_OP_COMP
    | BIT_XOR_OP_COMP | BIT_L_SHIFT_COMP | BIT_R_SHIFT_COMP
    ;