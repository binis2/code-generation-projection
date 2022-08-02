package net.binis.codegen.projection.objects;

import net.binis.codegen.factory.CodeFactory;

import java.util.Iterator;
import java.util.ListIterator;

public class CodeProjectionProxyListIterator implements ListIterator {

    private final ListIterator iterator;
    private final Class<?>[] proxies;

    public CodeProjectionProxyListIterator(ListIterator iterator, Class<?>... proxies) {
        this.iterator = iterator;
        this.proxies = proxies;
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public Object next() {
        return CodeFactory.projection(iterator.next(), proxies[0]);
    }

    @Override
    public boolean hasPrevious() {
        return iterator.hasPrevious();
    }

    @Override
    public Object previous() {
        return iterator.previous();
    }

    @Override
    public int nextIndex() {
        return iterator.nextIndex();
    }

    @Override
    public int previousIndex() {
        return iterator.previousIndex();
    }

    @Override
    public void remove() {
        iterator.remove();
    }

    @Override
    public void set(Object o) {
        iterator.set(CodeProxyBase.unwrap(o));
    }

    @Override
    public void add(Object o) {
        iterator.add(CodeProxyBase.unwrap(o));
    }

}
