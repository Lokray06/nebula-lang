parser grammar NebulaParser;

@header
{
package org.lokray.parser;
}

options { tokenVocab = NebulaLexer; }

// --- Top Level ---
compilationUnit
    :   ( importDeclaration | namespaceDeclaration | aliasDeclaration | classDeclaration )* EOF
    ;

qualifiedName
    :   ID (DOT_SYM ID)*
    ;

// --- Project Structure ---
importDeclaration
    :   IMPORT_KW qualifiedName SEMI_SYM
    ;

namespaceDeclaration
    :   NAMESPACE_KW qualifiedName L_CURLY_SYM (classDeclaration)* R_CURLY_SYM
    ;

aliasDeclaration
    :   ALIAS_KW ID EQUALS_SYM qualifiedName SEMI_SYM
    ;

// --- Class Structure ---
classDeclaration
    :   modifiers? CLASS_KW ID L_CURLY_SYM classBody* R_CURLY_SYM
    ;

classBody
    :   fieldDeclaration
    |   methodDeclaration
    |   propertyDeclaration
    |   constructorDeclaration
    ;

constructorDeclaration
    :   modifiers? ID L_PAREN_SYM parameterList? R_PAREN_SYM block
    ;

propertyDeclaration
    :   modifiers? type ID L_CURLY_SYM (GET_KW SEMI_SYM)? (SET_KW SEMI_SYM)? R_CURLY_SYM
    ;

fieldDeclaration
    :   modifiers? type variableDeclarator (COMMA_SYM variableDeclarator)* SEMI_SYM
    ;

variableDeclarator
    :   ID (EQUALS_SYM expression)?
    ;

methodDeclaration
    :   modifiers? type ID L_PAREN_SYM parameterList? R_PAREN_SYM block
    ;

modifiers
    :   (PUBLIC_KW | PRIVATE_KW | STATIC_KW | CONST_KW | NATIVE_KW)+
    ;

type
    :   primitiveType (L_BRACK_SYM R_BRACK_SYM)*
    |   qualifiedName (L_BRACK_SYM R_BRACK_SYM)*
    ;

primitiveType
    :   VOID_T | NULL_T | OBJECT_T | BYTE_T | SHORT_T | INT_T | LONG_T | BYTE_SPE_T
    |   SHORT_SPE_T | INT_SPE_T | LONG_SPE_T | U_BYTE_T | U_SHORT_T | U_INT_T
    |   U_LONG_T | U_BYTE_SPE_T | U_SHORT_SPE_T | U_INT_SPE_T | U_LONG_SPE_T
    |   FLOAT_T | DOUBLE_T | BOOL_T | CHAR_T | STRING_T
    ;

// --- Parameters ---
parameterList
    :   parameter (COMMA_SYM parameter)*
    ;

parameter
    :   type ID (QUESTION_MARK_SYM EQUALS_SYM expression)? // Optional parameter
    |   ID COLON_SYM type (QUESTION_MARK_SYM EQUALS_SYM expression)? // Named parameter part
    ;

// --- Statements ---
block
    :   L_CURLY_SYM statement* R_CURLY_SYM
    ;

statement
    :   block
    |   variableDeclaration SEMI_SYM
    |   ifStatement
    |   forStatement
    |   foreachStatement
    |   whileStatement
    |   returnStatement
    |   breakStatement
    |   continueStatement
    |   switchStatement
    |   expression SEMI_SYM // Expression statement
    ;

variableDeclaration
    :   type variableDeclarator (COMMA_SYM variableDeclarator)*
    ;

ifStatement
    :   IF_KW L_PAREN_SYM expression R_PAREN_SYM statement (ELSE_KW statement)?
    ;

forStatement
    // 1. NEW: Rule for the simplified for loop syntax.
    // This is placed FIRST to be matched before the traditional loop.
    : FOR_KW L_PAREN_SYM simplifiedForClause R_PAREN_SYM statement
      # SimplifiedFor

    // 2. EXISTING: Rule for the traditional C-style for loop.
    | FOR_KW L_PAREN_SYM (variableDeclaration | expression)? SEMI_SYM expression? SEMI_SYM expression? R_PAREN_SYM statement
      # TraditionalFor
    ;

// NEW: This rule captures the different forms of your simplified loop condition.
simplifiedForClause
    // Matches: for (j = 5 < 15) or for (k = 0 <= 20)
    : iter=ID EQUALS_SYM start=expression op=relationalOperator limit=expression
      # SimplifiedForWithInitializer

    // Matches: for (i < 10)
    | iter=ID op=relationalOperator limit=expression
      # SimplifiedForNoInitializer
    ;

// NEW: A helper rule to group the relational operators.
relationalOperator
    : LESS_THAN_SYM
    | GREATER_THAN_SYM
    | LESS_EQUAL_THAN_SYM
    | GREATER_EQUAL_THAN_SYM
    ;

foreachStatement
    : FOREACH_KW L_PAREN_SYM type ID IN_KW expression R_PAREN_SYM statement
    ;

whileStatement
    :   WHILE_KW L_PAREN_SYM expression R_PAREN_SYM statement
    ;

returnStatement
    :   RETURN_KW expression? SEMI_SYM
    ;

breakStatement
    :   BREAK_KW SEMI_SYM
    ;

continueStatement
    :   CONTINUE_KW SEMI_SYM
    ;

switchStatement
    :   SWITCH_KW L_PAREN_SYM expression R_PAREN_SYM L_CURLY_SYM switchBlock* R_CURLY_SYM
    ;

switchBlock
    :   (CASE_KW expression | DEFAULT_KW) COLON_SYM statement*
    ;

// --- Expressions (with precedence) ---
expression
    :   assignmentExpression
    ;

assignmentExpression
    :   conditionalExpression (assignmentOperator conditionalExpression)?
    ;

conditionalExpression
    :   logicalOrExpression (QUESTION_MARK_SYM expression COLON_SYM expression)?
    ;

logicalOrExpression
    :   logicalAndExpression (LOG_OR_OP logicalAndExpression)*
    ;

logicalAndExpression
    :   bitwiseOrExpression (LOG_AND_OP bitwiseOrExpression)*
    ;

bitwiseOrExpression
    :   bitwiseXorExpression (BIT_OR_OP bitwiseXorExpression)*
    ;

bitwiseXorExpression
    :   bitwiseAndExpression (BIT_XOR_OP bitwiseAndExpression)*
    ;

bitwiseAndExpression
    :   equalityExpression (BIT_AND_OP equalityExpression)*
    ;

equalityExpression
    :   relationalExpression ((EQUAL_EQUAL_SYM | NOT_EQUAL_SYM) relationalExpression)*
    ;

relationalExpression
    :   shiftExpression ((LESS_THAN_SYM | GREATER_THAN_SYM | LESS_EQUAL_THAN_SYM | GREATER_EQUAL_THAN_SYM) shiftExpression)*
    ;

shiftExpression
    :   additiveExpression ((BIT_L_SHIFT | BIT_R_SHIFT) additiveExpression)*
    ;

additiveExpression
    :   multiplicativeExpression ((ADD_OP | SUB_OP) multiplicativeExpression)*
    ;

multiplicativeExpression
    :   powerExpression ((MUL_OP | DIV_OP | MOD_OP) powerExpression)*
    ;

powerExpression
    :   unaryExpression (EXP_OP unaryExpression)*
    ;

unaryExpression
    :   (ADD_OP | SUB_OP | LOG_NOT_OP | BIT_NOT_OP) unaryExpression
    |   L_PAREN_SYM type R_PAREN_SYM unaryExpression // Casting
    |   postfixExpression
    ;

postfixExpression
    :   primary
        (   DOT_SYM ID                                  // Member access
        |   L_PAREN_SYM expressionList? R_PAREN_SYM     // Method call
        |   L_BRACK_SYM expression R_BRACK_SYM          // Array access
        )*
    ;

primary
    :   L_PAREN_SYM expression R_PAREN_SYM
    |   literal
    |   ID
    |   NEW_KW type (L_PAREN_SYM expressionList? R_PAREN_SYM | L_BRACK_SYM expression R_BRACK_SYM)
    ;

expressionList
    :   expression (COMMA_SYM expression)*
    ;

literal
    :   INTEGER_LITERAL
    |   LONG_LITERAL
    |   HEX_LITERAL
    |   FLOAT_LITERAL
    |   DOUBLE_LITERAL
    |   BOOLEAN_LITERAL
    |   CHAR_LITERAL
    |   STRING_LITERAL
    |   NULL_T
    ;

assignmentOperator
    :   EQUALS_SYM
    |   ADD_OP_COMP
    |   SUB_OP_COMP
    |   MUL_OP_COMP
    |   DIV_OP_COMP
    |   MOD_OP_COMP
    |   EXP_OP_COMP
    |   BIT_AND_OP_COMP
    |   BIT_OR_OP_COMP
    |   BIT_XOR_OP_COMP
    |   BIT_L_SHIFT_COMP
    |   BIT_R_SHIFT_COMP
    ;
