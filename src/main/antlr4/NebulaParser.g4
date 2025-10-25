parser grammar NebulaParser;

@header
{
package org.lokray.parser;
}

options { tokenVocab = NebulaLexer; }

// --- Top Level ---
compilationUnit
    :   ( importDeclaration
            | namespaceDeclaration
            | aliasDeclaration
            | classDeclaration
            | nativeClassDeclaration         // allow native classes at top level
            | structDeclaration
            | nativeStructDeclaration       // allow native structs at top level
            )* EOF
        ;

qualifiedName
    :   ID (DOT_SYM ID)*
    ;

// --- Project Structure ---
importDeclaration
    :   IMPORT_KW qualifiedName SEMI_SYM
    ;

namespaceDeclaration
    :   NAMESPACE_KW qualifiedName L_CURLY_SYM
            ( classDeclaration
            | nativeClassDeclaration
            | structDeclaration
            | nativeStructDeclaration
            )*
        R_CURLY_SYM
    ;

aliasDeclaration
    :   ALIAS_KW ID EQUALS_SYM qualifiedName SEMI_SYM
    ;

// --- Project Structure (Structural Types) ---

structDeclaration
    :   modifiers? STRUCT_KW ID L_CURLY_SYM structBody* R_CURLY_SYM
    ;

// Allow struct bodies to contain:
//  - specialized struct fields (no initializer), OR normal fieldDeclaration (with initializer allowed), OR native fields
structBody
    :   structFieldDeclaration        // specialized rule for fields without initializers
    |   fieldDeclaration              // allow normal (initialized) fields too if you want
    |   nativeFieldDeclaration        // native fields (no initializer)
    |   methodDeclaration
    |   propertyDeclaration
    |   constructorDeclaration
    |   operatorOverloadMethodDeclaration
    ;

// NEW: nativeStructDeclaration - supports either a body or a forward-decl semicolon
nativeStructDeclaration
    :   NATIVE_KW modifiers? STRUCT_KW ID
            ( L_CURLY_SYM nativeStructBody* R_CURLY_SYM
            | SEMI_SYM                              // forward-declare native struct: `native struct Foo;`
            )
    ;

// nativeStructBody mirrors nativeClassBody (native fields, methods, etc.)
nativeStructBody
    :   fieldDeclaration
    |   nativeFieldDeclaration
    |   methodDeclaration
    |   nativeMethodDeclaration
    |   propertyDeclaration
    |   nativePropertyDeclaration
    |   constructorDeclaration
    |   nativeConstructorDeclaration
    |   operatorOverloadMethodDeclaration
    |   nativeOperatorOverloadMethodDeclaration
    ;

// NEW RULE: For fields inside a struct that cannot have initializers.
// It uses a simplified variableDeclarator that *requires* ID only.
structVariableDeclarator
    :  type ID
    ;

// NEW RULE: Struct field declaration - excludes initializers for instance fields.
structFieldDeclaration
    :   modifiers? structVariableDeclarator (COMMA_SYM structVariableDeclarator)* SEMI_SYM
    ;

// --- Class Structure ---
// Retain your nativeClassDeclaration
nativeClassDeclaration
    :   NATIVE_KW modifiers? CLASS_KW ID
            ( L_CURLY_SYM nativeClassBody* R_CURLY_SYM
            | SEMI_SYM
            )
    ;

classDeclaration
    :   modifiers? CLASS_KW ID L_CURLY_SYM classBody* R_CURLY_SYM
    ;


// Retain your classBody for regular classes
classBody
    :   fieldDeclaration
    |   methodDeclaration
    |   propertyDeclaration
    |   constructorDeclaration
    |   operatorOverloadMethodDeclaration
    ;

// nativeClassBody
nativeClassBody
    :   fieldDeclaration              // Native fields can have initializers (i.e., be fieldDeclaration)
    |   nativeFieldDeclaration        // Or they can be native (no initializer)
    |   methodDeclaration
    |   nativeMethodDeclaration
    |   propertyDeclaration
    |   nativePropertyDeclaration
    |   constructorDeclaration
    |   nativeConstructorDeclaration
    |   nativeOperatorOverloadMethodDeclaration
    ;

// --- Class Members ---

// RETAIN: Regular constructor (must have a block)
constructorDeclaration
    :   modifiers? ID L_PAREN_SYM parameterList? R_PAREN_SYM block
    ;

// NEW: Native constructor (no block/body)
nativeConstructorDeclaration
    :   NATIVE_KW modifiers? ID L_PAREN_SYM parameterList? R_PAREN_SYM SEMI_SYM
    ;

accessorBody
    : block // Full statement block: { statement* }
    | SEMI_SYM // Auto-implemented accessor: get;
    | L_CURLY_SYM expression R_CURLY_SYM // The fix for your custom syntax: { expression }
    ;

propertyDeclaration
    : modifiers?
      type ID L_CURLY_SYM accessorDeclaration+ R_CURLY_SYM
      (EQUALS_SYM expression)?
      SEMI_SYM? // <--- FIX: Made optional to allow for block-bodied properties
    ;

accessorDeclaration
    : (GET_KW | SET_KW) accessorBody
    ;

// NEW: Native property (no getter/setter body/block)
// This assumes 'native properties' are essentially accessors implemented by the runtime,
// and thus only require a declaration signature.
nativePropertyDeclaration
    :   NATIVE_KW modifiers? type ID SEMI_SYM // No L_CURLY_SYM block
    ;

// RETAIN: Regular field (can have an initializer)
fieldDeclaration
    :   modifiers? type variableDeclarator (COMMA_SYM variableDeclarator)* SEMI_SYM
    ;

// NEW: Native field (must NOT have an initializer, enforced by using simple ID and no '= expression')
nativeFieldDeclaration
    :   NATIVE_KW modifiers? type ID (COMMA_SYM ID)* SEMI_SYM
    ;

// RETAIN: Regular method (must have a block)
nativeOperatorOverloadMethodDeclaration
    :   NATIVE_KW modifiers? type OPERATOR_KW validOperatorOverloads L_PAREN_SYM parameterList? R_PAREN_SYM SEMI_SYM
    ;

// RETAIN: Native method (no block/body)
nativeMethodDeclaration
    :   NATIVE_KW modifiers? type ID L_PAREN_SYM parameterList? R_PAREN_SYM SEMI_SYM
    ;

// RETAIN: Regular method (must have a block)
operatorOverloadMethodDeclaration
    :   modifiers? type OPERATOR_KW validOperatorOverloads L_PAREN_SYM parameterList? R_PAREN_SYM block
    ;

validOperatorOverloads
    : ADD_OP | SUB_OP | MUL_OP | DIV_OP | MOD_OP | EXP_OP | EQUAL_EQUAL_SYM;

// RETAIN: Regular method (must have a block)
methodDeclaration
    :   modifiers? type ID L_PAREN_SYM parameterList? R_PAREN_SYM block
    ;

// RETAIN variableDeclarator (used by fieldDeclaration)
variableDeclarator
    :  ID (EQUALS_SYM expression)?
    ;

modifiers
    :   (PUBLIC_KW | PRIVATE_KW | STATIC_KW | CONST_KW | OVERRIDE_KW)+
    ;

// --- Type Definition ---

// NEW RULE: Defines an element within a tuple type, e.g., "int" or "int Count"
tupleTypeElement
    :   type ID?
    ;

// UPDATED: Now includes named elements
tupleType
    :   L_PAREN_SYM tupleTypeElement (COMMA_SYM tupleTypeElement)* R_PAREN_SYM
    ;

// UPDATED: Now includes tupleType as a valid alternative
type
    :   primitiveType (L_BRACK_SYM R_BRACK_SYM)*
    |   qualifiedName (L_BRACK_SYM R_BRACK_SYM)*
    |   tupleType
    ;

primitiveType
    :   VOID_T | NULL_T | BYTE_T | SHORT_T | INT_T | LONG_T | BYTE_SPE_T
    |   SHORT_SPE_T | INT_SPE_T | LONG_SPE_T | U_BYTE_T | U_SHORT_T | U_INT_T
    |   U_LONG_T | U_BYTE_SPE_T | U_SHORT_SPE_T | U_INT_SPE_T | U_LONG_SPE_T
    |   FLOAT_T | DOUBLE_T | BOOL_T | CHAR_T | STRING_T
    ;

// --- Parameters ---
parameterList
    :   parameter (COMMA_SYM parameter)*
    ;

parameter
    :   type ID (EQUALS_SYM expression)?
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
    |   statementExpression SEMI_SYM
    ;

variableDeclaration
    :   (modifiers)? type variableDeclarator (COMMA_SYM variableDeclarator)*
    ;

ifStatement
    :   IF_KW L_PAREN_SYM expression R_PAREN_SYM statement (ELSE_KW statement)?
    ;

forStatement
    : FOR_KW L_PAREN_SYM simplifiedForClause R_PAREN_SYM block     // SimplifiedFor
    | FOR_KW L_PAREN_SYM (variableDeclaration | expression)? SEMI_SYM expression? SEMI_SYM expression? R_PAREN_SYM block
                                                                    // TraditionalFor
    ;

simplifiedForClause
    : iter=ID EQUALS_SYM start=expression op=relationalOperator limit=expression // SimplifiedForWithInitializer
    | iter=ID op=relationalOperator limit=expression                             // SimplifiedForNoInitializer
    ;

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

// 1. New rule for just the assignment *part* of an expression statement
//    Note: 'assignmentOperator' is not the simple EQUALS_SYM, but all compound ones as well.
actualAssignment
    :   (unaryExpression | postfixExpression) assignmentOperator expression
    ;

statementExpression
    :   actualAssignment
    |   (op=(INC_OP | DEC_OP) unaryExpression) // Only allow pre-inc/dec unary ops
    |   postfixExpression (L_PAREN_SYM argumentList? R_PAREN_SYM) // Function/Method call
    |   postfixExpression (INC_OP | DEC_OP) // Post-inc/dec
    ;

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

// NEW RULE: Explicitly defines a casting operation
castExpression
    :   L_PAREN_SYM type R_PAREN_SYM unaryExpression
    ;

unaryExpression
    :   op=(ADD_OP | SUB_OP | LOG_NOT_OP | BIT_NOT_OP | INC_OP | DEC_OP) unaryExpression
    |   castExpression
    |   postfixExpression
    ;

postfixExpression
    :   primary
        ( DOT_SYM ID
        | L_PAREN_SYM argumentList? R_PAREN_SYM
        | L_BRACK_SYM expression R_BRACK_SYM
        | INC_OP
        | DEC_OP
        )*
    ;

// UPDATED: Now supports positional or named elements
tupleLiteral
    :   L_PAREN_SYM
        (   namedArgument (COMMA_SYM namedArgument)* // Case 1: All named elements
        |   expression (COMMA_SYM expression)+          // Case 2: 2+ positional elements
        )
        R_PAREN_SYM
    ;

// --- Primary: allow array initializer here as well ---
primary
    :   L_PAREN_SYM expression R_PAREN_SYM // Parenthesized expression for grouping
    |   literal
    |   ID
    |   NEW_KW type (L_PAREN_SYM argumentList? R_PAREN_SYM | L_BRACK_SYM expression R_BRACK_SYM)
    |   arrayInitializer
    |   tupleLiteral                     // Allow tuple literals
    |   primitiveType                    // <--- ADD THIS LINE
    ;

// An argument list that handles positional, named, and mixed arguments like C#.
argumentList
    :   expression (COMMA_SYM expression)* (COMMA_SYM namedArgument (COMMA_SYM namedArgument)*)?
    |   namedArgument (COMMA_SYM namedArgument)*
    ;

// A named argument is 'identifier: expression'
namedArgument
    :   ID COLON_SYM expression
    ;

// ---------------------- Array initializers ----------------------
// Accepts nested initializers and optional trailing comma.
arrayInitializer
    :   L_CURLY_SYM ( arrayElement ( COMMA_SYM arrayElement )* )? ( COMMA_SYM )? R_CURLY_SYM
    ;

arrayElement
    :   expression
    |   arrayInitializer
    ;

// ---------------------- Literals ----------------------
literal
    :   HEX_LITERAL
    |   BIN_LITERAL
    |   INTEGER_LITERAL
    |   FLOAT_LITERAL
    |   DOUBLE_LITERAL
    |   BOOLEAN_LITERAL
    |   CHAR_LITERAL
    |   STRING_LITERAL
    |   interpolatedString
    |   NULL_T
    ;

interpolatedString
    :   INTERPOLATED_STRING_START interpolationPart* INTERPOLATION_END
    ;

interpolationPart
    :   TEXT_FRAGMENT
    |   ESCAPED_BRACE_INTERP
    |   OPEN_BRACE_INTERP expression CLOSE_BRACE_INTERP
    ;

// ---------------------- Assignment Operators ----------------------
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
