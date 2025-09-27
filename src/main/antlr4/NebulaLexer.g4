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

// ---------------------- SYMBOLS ----------------------
L_CURLY_SYM: '{';
R_CURLY_SYM: '}';

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
OPEN_BRACE_INTERP
    :   '{' { interpBraceDepth = 0; } -> pushMode(INTERP_EXPR)
    ;

// end of the interpolated string
INTERPOLATION_END
    :   '"' -> popMode
    ;

// whitespace inside interpolation text
WS_INTERP : [ \t\r\n]+ -> skip ;

// ---------------------- INTERPOLATION: EXPRESSION MODE ----------------------
mode INTERP_EXPR;

// Handle nested '{' / '}' inside the expression properly using interpBraceDepth.
// If interpBraceDepth == 0 then the next '}' closes the interpolation and becomes CLOSE_BRACE_INTERP.
// Otherwise leave curly tokens as normal L_CURLY_SYM / R_CURLY_SYM so array / object initializers parse.

OPEN_CURLY_IN_EXPR
    :   '{' { interpBraceDepth++; setType(L_CURLY_SYM); }
    ;

CLOSE_CURLY_IN_EXPR
    :   '}' {
            if (interpBraceDepth == 0) {
                // this '}' closes the interpolation expression
                popMode();
                setType(CLOSE_BRACE_INTERP);
            } else {
                // inner '}' inside expression -> emit regular R_CURLY_SYM
                interpBraceDepth--;
                setType(R_CURLY_SYM);
            }
      }
    ;

// Whitespace/comments in expr
WS_EXPR : [ \t\r\n]+ -> skip ;
LINE_COMMENT_EXPR : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT_EXPR: '/*' .*? '*/' -> skip ;

// --------- map expression tokens to the same token types parser expects ---------
// keywords first (so 'new' becomes NEW_KW not ID)
NEW_IN_EXPR   : 'new'   -> type(NEW_KW) ;
NULL_IN_EXPR  : 'null'  -> type(NULL_T) ;
TRUE_IN_EXPR  : 'true'  -> type(BOOLEAN_LITERAL) ;
FALSE_IN_EXPR : 'false' -> type(BOOLEAN_LITERAL) ;

// literals
STRING_LITERAL_IN_EXPR
    :   '"' ( ESCAPE_SEQUENCE | ~["\\] )* '"' -> type(STRING_LITERAL)
    ;

CHAR_LITERAL_IN_EXPR
    :   '\'' ( ESCAPE_SEQUENCE | ~['\\] ) '\'' -> type(CHAR_LITERAL)
    ;

// numeric forms (we replicate the important ones; add more if needed)
HEX_LITERAL_IN_EXPR   : '0' [xX] HEX_DIGITS [Ll]? -> type(HEX_LITERAL) ;
LONG_LITERAL_IN_EXPR  : DECIMAL_DIGITS [Ll] -> type(LONG_LITERAL) ;
DOUBLE_LITERAL1_IN_EXPR
    :   DECIMAL_DIGITS '.' DECIMAL_DIGITS? -> type(DOUBLE_LITERAL)
    ;

DOUBLE_LITERAL2_IN_EXPR
    :   '.' DECIMAL_DIGITS -> type(DOUBLE_LITERAL)
    ;
FLOAT_LITERAL1_IN_EXPR
    :   DECIMAL_DIGITS '.' DECIMAL_DIGITS? [fF] -> type(FLOAT_LITERAL)
    ;

FLOAT_LITERAL2_IN_EXPR
    :   '.' DECIMAL_DIGITS [fF] -> type(FLOAT_LITERAL)
    ;

FLOAT_LITERAL3_IN_EXPR
    :   DECIMAL_DIGITS [fF] -> type(FLOAT_LITERAL)
    ;
INTEGER_LITERAL_IN_EXPR : DECIMAL_DIGITS -> type(INTEGER_LITERAL) ;

// identifiers and punctuation/operators (map to the same token names)
ID_IN_EXPR      : [a-zA-Z_] [a-zA-Z_0-9]* -> type(ID) ;
ADD_IN_EXPR     : '+' -> type(ADD_OP) ;
SUB_IN_EXPR     : '-' -> type(SUB_OP) ;
MUL_IN_EXPR     : '*' -> type(MUL_OP) ;
DIV_IN_EXPR     : '/' -> type(DIV_OP) ;
MOD_IN_EXPR     : '%' -> type(MOD_OP) ;
EXP_IN_EXPR     : '**' -> type(EXP_OP) ;

NOT_EQUAL_IN_EXPR: '!=' -> type(NOT_EQUAL_SYM) ;
EQEQ_IN_EXPR    : '==' -> type(EQUAL_EQUAL_SYM) ;
EQ_IN_EXPR      : '='  -> type(EQUALS_SYM) ;

LT_IN_EXPR      : '<' -> type(LESS_THAN_SYM) ;
GT_IN_EXPR      : '>' -> type(GREATER_THAN_SYM) ;
LE_IN_EXPR      : '<=' -> type(LESS_EQUAL_THAN_SYM) ;
GE_IN_EXPR      : '>=' -> type(GREATER_EQUAL_THAN_SYM) ;

AND_IN_EXPR     : '&&' -> type(LOG_AND_OP) ;
OR_IN_EXPR      : '||' -> type(LOG_OR_OP) ;
NOT_IN_EXPR     : '!' -> type(LOG_NOT_OP) ;

BIT_AND_IN_EXPR : '&' -> type(BIT_AND_OP) ;
BIT_OR_IN_EXPR  : '|' -> type(BIT_OR_OP) ;
BIT_XOR_IN_EXPR : '^' -> type(BIT_XOR_OP) ;
BIT_NOT_IN_EXPR : '~' -> type(BIT_NOT_OP) ;

LSHIFT_IN_EXPR  : '<<' -> type(BIT_L_SHIFT) ;
RSHIFT_IN_EXPR  : '>>' -> type(BIT_R_SHIFT) ;

ADD_COMP_IN_EXPR : '+=' -> type(ADD_OP_COMP) ;
SUB_COMP_IN_EXPR : '-=' -> type(SUB_OP_COMP) ;
MUL_COMP_IN_EXPR : '*=' -> type(MUL_OP_COMP) ;
DIV_COMP_IN_EXPR : '/=' -> type(DIV_OP_COMP) ;

COMMA_IN_EXPR   : ',' -> type(COMMA_SYM) ;
DOT_IN_EXPR     : '.' -> type(DOT_SYM) ;
LPAREN_IN_EXPR  : '(' -> type(L_PAREN_SYM) ;
RPAREN_IN_EXPR  : ')' -> type(R_PAREN_SYM) ;
LBRACK_IN_EXPR  : '[' -> type(L_BRACK_SYM) ;
RBRACK_IN_EXPR  : ']' -> type(R_BRACK_SYM) ;

// fallback: if something else appears, emit it as a single-char token if it matches a known symbol
OTHER_IN_EXPR
    :   . { /* consume any char not matched above; this will likely be an error at parse time */ }
    ;
