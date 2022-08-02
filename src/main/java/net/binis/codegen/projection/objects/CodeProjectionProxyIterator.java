package net.binis.codegen.projection.objects;

import net.binis.codegen.factory.CodeFactory;

import java.util.Iterator;

public class CodeProjectionProxyIterator implements Iterator {

    private final Iterator iterator;
    private final Class<?>[] proxies;

    public CodeProjectionProxyIterator(Iterator iterator, Class<?>... proxies) {
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

}
