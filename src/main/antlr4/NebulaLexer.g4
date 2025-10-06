lexer grammar NebulaLexer;

@header {
package org.lokray.parser;
}

@members {
    // Tracks nested '{' inside an interpolation expression.
    // When we enter INTERP_EXPR we set this to 0. Any further '{' increments it.
    // On '}' we decrement; if it becomes < 0 (i.e. 0 before decrement) we treat that
    // '}' as the interpolation-close and pop back to INTERP mode.
    private int interpBraceDepth = 0;
}

// declare some special token names so we can setType(...) in actions
tokens {
    INTERPOLATED_STRING_START,
    INTERPOLATION_END,
    OPEN_BRACE_INTERP,
    CLOSE_BRACE_INTERP,
    TEXT_FRAGMENT,
    ESCAPED_BRACE_INTERP
}

// ---------------------- KEYWORDS ----------------------
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

OPERATOR_KW: 'operator';

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

// ---------------------- SYMBOLS ----------------------
//L_CURLY_SYM: '{';
//R_CURLY_SYM: '}';

// The corrected rule
L_CURLY_SYM: '{' {
    if (interpBraceDepth > 0) { // ONLY if we are already inside an interpolation expression
        interpBraceDepth++;     // do we increment the nesting level.
    }
};

// In DEFAULT_MODE
R_CURLY_SYM: '}' {
    if (interpBraceDepth > 0) {
        interpBraceDepth--;
        if (interpBraceDepth == 0) {
            // We've just closed the entire interpolation expression
            pushMode(INTERP);            // Go back to scanning for string text
            setType(CLOSE_BRACE_INTERP); // Change this token's type for the parser
        }
        // If depth is still > 0, it was a nested brace and we do nothing special,
        // which correctly leaves the token type as R_CURLY_SYM.
    }
};


L_PAREN_SYM: '(';
R_PAREN_SYM: ')';
L_BRACK_SYM: '[';
R_BRACK_SYM: ']';
SEMI_SYM: ';';
COLON_SYM: ':';
COMMA_SYM: ',';
DOT_SYM: '.';
QUESTION_MARK_SYM: '?';
DOLLAR_SYM: '$';

// ---------------------- OPERATORS ----------------------
EQUALS_SYM: '=';

LESS_THAN_SYM: '<';
GREATER_THAN_SYM: '>';
LESS_EQUAL_THAN_SYM: '<=';
GREATER_EQUAL_THAN_SYM: '>=';

EQUAL_EQUAL_SYM: '==';
NOT_EQUAL_SYM: '!=';

INC_OP: '++';
DEC_OP: '--';

ADD_OP: '+';
SUB_OP: '-';
MUL_OP: '*';
DIV_OP: '/';
MOD_OP: '%';
EXP_OP: '**';

BIT_AND_OP: '&';
BIT_OR_OP: '|';
BIT_XOR_OP: '^';
BIT_NOT_OP: '~';
BIT_L_SHIFT: '<<';
BIT_R_SHIFT: '>>';

LOG_AND_OP: '&&';
LOG_OR_OP: '||';
LOG_NOT_OP: '!';

ADD_OP_COMP: '+=';
SUB_OP_COMP: '-=';
MUL_OP_COMP: '*=';
DIV_OP_COMP: '/=';
MOD_OP_COMP: '%=';
EXP_OP_COMP: '**=';
BIT_AND_OP_COMP: '&=';
BIT_OR_OP_COMP: '|=';
BIT_XOR_OP_COMP: '^=';
BIT_L_SHIFT_COMP: '<<=';
BIT_R_SHIFT_COMP: '>>=';

// ---------------------- LITERALS ----------------------
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

// ---------------------- INTERPOLATED STRING START ----------------------
// When we see $" we enter INTERP mode (inside string text).
INTERPOLATED_STRING_START
    :   '$"' -> pushMode(INTERP)
    ;

// regular string (non-interpolated) stays in DEFAULT_MODE
STRING_LITERAL
    :   '"' ( ESCAPE_SEQUENCE | ~["\\] )* '"'
    ;

// ---------------------- IDENT & COMMENTS ----------------------
ID : [a-zA-Z_] [a-zA-Z_0-9]* ;

WS : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' .*? '\r'? '\n' -> skip ;
MULTI_LINE_COMMENT : '/*' .*? '*/' -> skip ;

// ---------------------- FRAGMENTS ----------------------
fragment ESCAPE_SEQUENCE
    :   '\\' [btnfr"'\\]
    |   '\\' 'u' HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT
    ;

fragment DECIMAL_DIGITS: [0-9] ([0-9_]* [0-9])? ;
fragment HEX_DIGITS: HEX_DIGIT (HEX_DIGIT_UNDERSCORE* HEX_DIGIT)? ;
fragment HEX_DIGIT_UNDERSCORE: [0-9a-fA-F_] ;
fragment HEX_DIGIT: [0-9a-fA-F] ;

// ---------------------- INTERPOLATION: TEXT MODE ----------------------
mode INTERP;

// Text inside the interpolated string (outside braces). It MUST consume escaped quotes
// (\"), so the final closing quote is only matched by INTERPOLATION_END when it is a real close.
TEXT_FRAGMENT
    :   ( ESCAPE_SEQUENCE | ~[{\\" ] )+    // any run of chars except {, " and backslash; escapes handled above
    ;

ESCAPED_BRACE_INTERP
    :   '{{' | '}}'
    ;

// When we see a '{' we switch to the expression mode and reset brace depth.
// We *emit* OPEN_BRACE_INTERP so parser can match OPEN_BRACE_INTERP expression CLOSE_BRACE_INTERP.
// In mode INTERP
OPEN_BRACE_INTERP
    :   '{' { interpBraceDepth = 1; } -> popMode // Change from 0 to 1
    ;

// end of the interpolated string
INTERPOLATION_END
    :   '"' -> popMode
    ;

// whitespace inside interpolation text
WS_INTERP : [ \t\r\n]+ -> skip ;