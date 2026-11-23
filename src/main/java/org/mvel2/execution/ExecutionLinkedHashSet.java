// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.execution;

import org.mvel2.ExecutionContext;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ExecutionLinkedHashSet<E> extends LinkedHashSet<E> implements ExecutionObject {

    private final ExecutionContext executionContext;

    private long memorySize = 0;

    public ExecutionLinkedHashSet(ExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    public ExecutionLinkedHashSet(Set<? extends E> s, ExecutionContext executionContext) {
        super(Math.max(2 * s.size(), 11), 0.75F);
        this.executionContext = executionContext;
        this.addAll(s);
    }

    @Override
    public boolean add(E e) {
        if (super.add(e)) {
            this.memorySize += this.executionContext.onValAdd(this, e);
            return true;
        }
        return false;
    }

    // every element of the argument is hashed: the walk of the argument (the sum of what its elements expand to) bounds the call (elements with the same hash and different content are compared one by one inside a bucket: not bounded, ledgered)
    @Override
    public boolean addAll(Collection<? extends E> c) {
        // an element that holds members is compared with the existing elements of its bucket, and their members are hashed whatever the walk of the argument approved: this set is walked too
        if (ExecutionContext.anyWalkable(c)) {
            this.executionContext.checkExpansion(this);
        }
        boolean outermost = this.executionContext.beginExpansion(c);
        try {
            return super.addAll(c);
        } finally {
            this.executionContext.endExpansion(outermost);
        }
    }

    @Override
    public boolean remove(Object o) {
        if (super.remove(o)) {
            this.memorySize -= this.executionContext.onValRemove(this, o);
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        for (E val : this) {
            this.executionContext.onValRemove(this, val);
        }
        super.clear();
        this.memorySize = 0;
    }

    public void sort() {
        this.sort(true);
    }

    public void sort(boolean asc) {
        ExecutionArrayList<E> list = this.toList();
        list.sort(asc);

        // Очистити і перезаписати
        this.clear();
        this.addAll(list);
    }

    public ExecutionLinkedHashSet<E> toSorted() {
        return this.toSorted(true);
    }

    public ExecutionLinkedHashSet<E> toSorted(boolean asc) {
        ExecutionArrayList<E> list = this.toList();
        list.sort(asc);
        Set<E> newSet = new LinkedHashSet<>(list);
        return new ExecutionLinkedHashSet<>(newSet, this.executionContext);
    }

    @SuppressWarnings("unchecked")
    public ExecutionArrayList<E> toList() {
        return new ExecutionArrayList<>((List<E>)(List<?>) Arrays.asList(this.toArray()), this.executionContext);
    }


    @Override
    public String toString() {
        boolean outermost = this.executionContext.beginExpansion(this);
        try {
            return super.toString();
        } finally {
            this.executionContext.endExpansion(outermost);
        }
    }

    @Override
    public int hashCode() {
        boolean outermost = this.executionContext.beginExpansion(this);
        try {
            return super.hashCode();
        } finally {
            this.executionContext.endExpansion(outermost);
        }
    }

    // the comparison stops at the first difference and walks no more than this: the other side is not checked
    // AbstractSet.equals asks this set whether it contains each element of a set of the same size: those elements are hashed, so such an argument is
    // checked too (also inside an outer walk, that approved the receiver only); any other argument is answered false without being looked at
    @Override
    public boolean equals(Object o) {
        if (o instanceof Set && ((Set<?>) o).size() == this.size()) {
            this.executionContext.checkExpansion(o);
        }
        boolean outermost = this.executionContext.beginExpansion(this);
        try {
            return super.equals(o);
        } finally {
            this.executionContext.endExpansion(outermost);
        }
    }

    @Override
    public long memorySize() {
        return this.memorySize;
    }

    public Set<E> toUnmodifiable() {
        return ExecutionCollections.unmodifiableExecutionSet(this, this.executionContext);
    }
}
