// File: src/main/java/org/lokray/semantic/symbol/TupleElementSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.Type;

/**
 * Represents an element within a tuple, like 'Item1' or a user-defined name like 'Sum'.
 * It's a specialized VariableSymbol that also tracks its positional index.
 */
public class TupleElementSymbol extends VariableSymbol {
    private final int index;

    public TupleElementSymbol(String name, Type type, int index) {
        // Tuple elements are treated as public, constant (read-only) fields.
        super(name, type, false, true, true);
        this.index = index;
    }

    public int getIndex() {
        return index;
    }
}