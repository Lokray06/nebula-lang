lexer grammar NebulaLexer;

@header
{
package org.lokray.parser;
}

// KEYWORDS
RETURN_KW: 'return';
ALIAS_KW: 'alias';
FOREACH_KW: 'foreach';
IN_KW: 'in';
GET_KW: 'get';
SET_KW: 'set';
NEW_KW: 'new';

    // SCOPE / MODIFIERS
    PUBLIC_KW: 'public';
    PRIVATE_KW: 'private';
    STATIC_KW: 'static';
    CONST_KW: 'const';
    NATIVE_KW: 'native';

    // PROJECT STRUCTURE
    CLASS_KW: 'class';
    NAMESPACE_KW: 'namespace';
    IMPORT_KW: 'import';

    // OOP RELATED
    INTERFACE_KW: 'interface';
    ABSTRACT_KW: 'abstract';

    // FLOW CONTROL
    IF_KW: 'if';
    ELSE_KW: 'else';
    WHILE_KW: 'while';
    FOR_KW: 'for';

    BREAK_KW: 'break';
    CONTINUE_KW: 'continue';
    SWITCH_KW: 'switch';
    CASE_KW: 'case';
    DEFAULT_KW: 'default';

    // TYPES
        // SPECIAL
        VOID_T: 'void';
        NULL_T: 'null';
        OBJECT_T: 'Object'; // Added for completeness

        // INTEGER NUMBER TYPES
        BYTE_T: 'byte';
        SHORT_T: 'short';
        INT_T: 'int';
        LONG_T: 'long';
        // INTEGER NUMBER TYPES (SPECIFIC)
        BYTE_SPE_T: 'int8';
        SHORT_SPE_T: 'int16';
        INT_SPE_T: 'int32';
        LONG_SPE_T: 'int64';

        // UNSIGNED INTEGER NUMBER TYPES
        U_BYTE_T: 'ubyte';
        U_SHORT_T: 'ushort';
        U_INT_T: 'uint';
        U_LONG_T: 'ulong';
        // UNSIGNED INTEGER NUMBER TYPES (SPECIFIC)
        U_BYTE_SPE_T: 'uint8';
        U_SHORT_SPE_T: 'uint16';
        U_INT_SPE_T: 'uint32';
        U_LONG_SPE_T: 'uint64';

        // FLOATING-POINT NUMBER TYPES
        FLOAT_T: 'float';
        DOUBLE_T: 'double';

        // BOOLEAN & CHAR TYPES
        BOOL_T: 'bool';
        CHAR_T: 'char';
        STRING_T: 'string';

// SYMBOLS
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

    // OPERATORS
        // ASSIGNMENT
        EQUALS_SYM: '=';

        // COMPARISON
        LESS_THAN_SYM: '<';
        GREATER_THAN_SYM: '>';
        LESS_EQUAL_THAN_SYM: '<=';
        GREATER_EQUAL_THAN_SYM: '>=';
        EQUAL_EQUAL_SYM: '==';
        NOT_EQUAL_SYM: '!=';

        // ALGEBRAIC
        ADD_OP: '+';
        SUB_OP: '-';
        MUL_OP: '*';
        DIV_OP: '/';
        MOD_OP: '%';
        EXP_OP: '**';

        // BITWISE
        BIT_AND_OP: '&';
        BIT_OR_OP: '|';
        BIT_XOR_OP: '^';
        BIT_NOT_OP: '~';
        BIT_L_SHIFT: '<<';
        BIT_R_SHIFT: '>>';

        // LOGICAL
        LOG_AND_OP: '&&';
        LOG_OR_OP: '||';
        LOG_NOT_OP: '!';

        // COMPOUND OPERATORS
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

// Literals
// The order of these rules is important to resolve ambiguity.
// More specific rules (like FLOAT) come before less specific ones (like INTEGER).

// e.g., 1.23f, .5f, 10f, 3_000.5f
FLOAT_LITERAL
    :   (DECIMAL_DIGITS '.' DECIMAL_DIGITS? | '.' DECIMAL_DIGITS | DECIMAL_DIGITS) [fF]
    ;

// e.g., 1.23, .5, 1.
DOUBLE_LITERAL
    :   DECIMAL_DIGITS '.' DECIMAL_DIGITS?
    |   '.' DECIMAL_DIGITS
    ;

// e.g., 0xFF, 0x1A_00, 0xCAFEBABEL, 0x1L
HEX_LITERAL
    :   '0' [xX] HEX_DIGITS [Ll]?
    ;

// e.g., 123L, 4_000_000L
LONG_LITERAL
    :   DECIMAL_DIGITS [Ll]
    ;

// e.g., 123, 4_000, 25_000_000
INTEGER_LITERAL
    :   DECIMAL_DIGITS
    ;

BOOLEAN_LITERAL
    :   'true' | 'false'
    ;

CHAR_LITERAL
    :   '\'' ( ESCAPE_SEQUENCE | ~['\\] ) '\''
    ;

// NOTE: Interpolated strings ($"...") require more advanced handling with lexer modes.
// This rule handles regular strings.
STRING_LITERAL
    :   '"' ( ESCAPE_SEQUENCE | ~["\\] )* '"'
    ;

// Identifier
ID : [a-zA-Z_] [a-zA-Z_0-9]* ;

// Whitespace and Comments
WS : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' .*? '\r'? '\n' -> skip ;
MULTI_LINE_COMMENT : '/*' .*? '*/' -> skip ;


// Fragments
// Fragments are helper rules that are not tokens themselves but are used in other token rules.
fragment ESCAPE_SEQUENCE
    :   '\\' [btnfr"'\\]
    |   '\\' 'u' HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT // Unicode escapes
    ;

// Matches sequences like "123" or "1_234_567"
fragment DECIMAL_DIGITS: [0-9] ([0-9_]* [0-9])? ;

// Matches sequences like "DEADBEEF" or "CAFE_BABE"
fragment HEX_DIGITS: HEX_DIGIT ([HEX_DIGIT_UNDERSCORE]* HEX_DIGIT)? ;
fragment HEX_DIGIT_UNDERSCORE: [0-9a-fA-F_];
fragment HEX_DIGIT: [0-9a-fA-F];
