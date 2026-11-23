// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.execution;

import org.mvel2.ExecutionContext;

import java.util.Map;

public class ExecutionEntry<K, V> implements Map.Entry<K, V>, ExecutionObject {
    public final K key;
    public V value;

    private final ExecutionContext executionContext;

    public ExecutionEntry(K key, V value, ExecutionContext executionContext) {
        this.key = key;
        this.value = value;
        this.executionContext = executionContext;
    }

    @Override
    public K getKey() {
        return key;
    }

    @Override
    public V getValue() {
        return value;
    }

    @Override
    public V setValue(V value) {
        V oldValue = this.value;
        this.value = value;
        return oldValue;
    }

    // a key or a value that is an entry, held twice, doubles at each level: the entry is guarded as the lists, maps and sets are
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Map.Entry)) return false;
        boolean outermost = this.executionContext.beginExpansion(this);
        try {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            return (key == null ? entry.getKey() == null : key.equals(entry.getKey())) &&
                    (value == null ? entry.getValue() == null : value.equals(entry.getValue()));
        } finally {
            this.executionContext.endExpansion(outermost);
        }
    }

    @Override
    public int hashCode() {
        boolean outermost = this.executionContext.beginExpansion(this);
        try {
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        } finally {
            this.executionContext.endExpansion(outermost);
        }
    }

    @Override
    public String toString() {
        boolean outermost = this.executionContext.beginExpansion(this);
        try {
            return key + "=" + value;
        } finally {
            this.executionContext.endExpansion(outermost);
        }
    }

    @Override
    public long memorySize() {
        return 24;
    }
}