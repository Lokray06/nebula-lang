lexer grammar NebulaLexer;

@header {
package org.lokray.parser;
}

// =====================================================
// KEYWORDS
// =====================================================
RETURN_KW: 'return';
ALIAS_KW: 'alias';
FOREACH_KW: 'foreach';
IN_KW: 'in';
GET_KW: 'get';
SET_KW: 'set';
NEW_KW: 'new';

PUBLIC_KW: 'public';
PRIVATE_KW: 'private';
STATIC_KW: 'static';
CONST_KW: 'const';
NATIVE_KW: 'native';

CLASS_KW: 'class';
NAMESPACE_KW: 'namespace';
IMPORT_KW: 'import';

INTERFACE_KW: 'interface';
ABSTRACT_KW: 'abstract';

IF_KW: 'if';
ELSE_KW: 'else';
WHILE_KW: 'while';
FOR_KW: 'for';

BREAK_KW: 'break';
CONTINUE_KW: 'continue';
SWITCH_KW: 'switch';
CASE_KW: 'case';
DEFAULT_KW: 'default';

VOID_T: 'void';
NULL_T: 'null';
OBJECT_T: 'Object';

BYTE_T: 'byte';
SHORT_T: 'short';
INT_T: 'int';
LONG_T: 'long';

BYTE_SPE_T: 'int8';
SHORT_SPE_T: 'int16';
INT_SPE_T: 'int32';
LONG_SPE_T: 'int64';

U_BYTE_T: 'ubyte';
U_SHORT_T: 'ushort';
U_INT_T: 'uint';
U_LONG_T: 'ulong';

U_BYTE_SPE_T: 'uint8';
U_SHORT_SPE_T: 'uint16';
U_INT_SPE_T: 'uint32';
U_LONG_SPE_T: 'uint64';

FLOAT_T: 'float';
DOUBLE_T: 'double';
BOOL_T: 'bool';
CHAR_T: 'char';
STRING_T: 'string';

// =====================================================
// SYMBOLS
// =====================================================
L_CURLY_SYM: '{' ;
R_CURLY_SYM: '}' ;

L_PAREN_SYM: '(' ;
R_PAREN_SYM: ')' ;
L_BRACK_SYM: '[' ;
R_BRACK_SYM: ']' ;
SEMI_SYM: ';' ;
COLON_SYM: ':' ;
COMMA_SYM: ',' ;
DOT_SYM: '.' ;
QUESTION_MARK_SYM: '?' ;
DOLLAR_SYM: '$' ;

// =====================================================
// OPERATORS
// =====================================================
EQUALS_SYM: '=' ;

LESS_THAN_SYM: '<' ;
GREATER_THAN_SYM: '>' ;
LESS_EQUAL_THAN_SYM: '<=' ;
GREATER_EQUAL_THAN_SYM: '>=' ;
EQUAL_EQUAL_SYM: '==' ;
NOT_EQUAL_SYM: '!=' ;

ADD_OP: '+' ;
SUB_OP: '-' ;
MUL_OP: '*' ;
DIV_OP: '/' ;
MOD_OP: '%' ;
EXP_OP: '**' ;

BIT_AND_OP: '&' ;
BIT_OR_OP: '|' ;
BIT_XOR_OP: '^' ;
BIT_NOT_OP: '~' ;
BIT_L_SHIFT: '<<' ;
BIT_R_SHIFT: '>>' ;

LOG_AND_OP: '&&' ;
LOG_OR_OP: '||' ;
LOG_NOT_OP: '!' ;

ADD_OP_COMP: '+=' ;
SUB_OP_COMP: '-=' ;
MUL_OP_COMP: '*=' ;
DIV_OP_COMP: '/=' ;
MOD_OP_COMP: '%=' ;
EXP_OP_COMP: '**=' ;
BIT_AND_OP_COMP: '&=' ;
BIT_OR_OP_COMP: '|=' ;
BIT_XOR_OP_COMP: '^=' ;
BIT_L_SHIFT_COMP: '<<=' ;
BIT_R_SHIFT_COMP: '>>=' ;

// =====================================================
// LITERALS
// =====================================================
FLOAT_LITERAL
    :   (DECIMAL_DIGITS '.' DECIMAL_DIGITS? | '.' DECIMAL_DIGITS | DECIMAL_DIGITS) [fF]
    ;

DOUBLE_LITERAL
    :   DECIMAL_DIGITS '.' DECIMAL_DIGITS?
    |   '.' DECIMAL_DIGITS
    ;

HEX_LITERAL
    :   '0' [xX] HEX_DIGITS [Ll]?
    ;

LONG_LITERAL
    :   DECIMAL_DIGITS [Ll]
    ;

INTEGER_LITERAL
    :   DECIMAL_DIGITS
    ;

BOOLEAN_LITERAL
    :   'true' | 'false'
    ;

CHAR_LITERAL
    :   '\'' ( ESCAPE_SEQUENCE | ~['\\] ) '\''
    ;

// interpolated string start — enters INTERP mode
INTERPOLATED_STRING_START
    :   '$"' -> pushMode(INTERP)
    ;

// normal string literal
STRING_LITERAL
    :   '"' ( ESCAPE_SEQUENCE | ~["\\] )* '"'
    ;

// =====================================================
// IDENTIFIER & COMMON
// =====================================================
ID : [a-zA-Z_] [a-zA-Z_0-9]* ;

WS : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' .*? '\r'? '\n' -> skip ;
MULTI_LINE_COMMENT : '/*' .*? '*/' -> skip ;

// =====================================================
// FRAGMENTS
// =====================================================
fragment ESCAPE_SEQUENCE
    :   '\\' [btnfr"'\\]
    |   '\\' 'u' HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT
    ;

fragment DECIMAL_DIGITS: [0-9] ([0-9_]* [0-9])? ;
fragment HEX_DIGITS: HEX_DIGIT (HEX_DIGIT_UNDERSCORE* HEX_DIGIT)? ;
fragment HEX_DIGIT_UNDERSCORE: [0-9a-fA-F_] ;
fragment HEX_DIGIT: [0-9a-fA-F] ;

// =====================================================
// MODES
// =====================================================

// --- INTERP mode: inside $"..." but outside { expr } ---
mode INTERP;

TEXT_FRAGMENT
    :   (ESCAPE_SEQUENCE | ~[{"\\])+  // raw text
    ;

ESCAPED_BRACE_INTERP
    :   '{{' | '}}'
    ;

OPEN_BRACE_INTERP
    :   '{' -> pushMode(INTERP_EXPR)
    ;

INTERPOLATION_END
    :   '"' -> popMode
    ;

WS_INTERP : [ \t\r\n]+ -> skip ;

// --- INTERP_EXPR mode: inside { expr } ---
mode INTERP_EXPR;

// On closing }, pop back to INTERP mode
CLOSE_BRACE_INTERP
    :   '}' -> popMode
    ;

// Duplicate key tokens so parser sees the same names
ID_IN_EXPR : [a-zA-Z_] [a-zA-Z_0-9]* -> type(ID) ;
INT_IN_EXPR: [0-9]+ -> type(INTEGER_LITERAL) ;
ADD_IN_EXPR: '+' -> type(ADD_OP) ;
SUB_IN_EXPR: '-' -> type(SUB_OP) ;
MUL_IN_EXPR: '*' -> type(MUL_OP) ;
DIV_IN_EXPR: '/' -> type(DIV_OP) ;
COMMA_IN_EXPR: ',' -> type(COMMA_SYM) ;
DOT_IN_EXPR: '.' -> type(DOT_SYM) ;
LPAREN_IN_EXPR: '(' -> type(L_PAREN_SYM) ;
RPAREN_IN_EXPR: ')' -> type(R_PAREN_SYM) ;
LT_IN_EXPR: '<' -> type(LESS_THAN_SYM) ;
GT_IN_EXPR: '>' -> type(GREATER_THAN_SYM) ;
EQ_IN_EXPR: '=' -> type(EQUALS_SYM) ;
NEQ_IN_EXPR: '!=' -> type(NOT_EQUAL_SYM) ;
// Add more if your expressions need them

WS_EXPR : [ \t\r\n]+ -> skip ;
LINE_COMMENT_EXPR : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT_EXPR: '/*' .*? '*/' -> skip ;